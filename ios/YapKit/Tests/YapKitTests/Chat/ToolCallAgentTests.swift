import Foundation
import XCTest
@testable import YapKit

/// 0.16「Agent 行为完全工具调用化 + 思考强度」（`spec/tool-call-agents/README.md`）。
///
/// 这一组只钉四件事，都是**用户能感觉到**的差异：
/// 1. 说话必须走 `send_message`（提示词 + 工具结果带 messageId + 气泡在工具完成后才出现）；
/// 2. 不走工具的正文只做兼容回退（仍然是 legacy 消息，但日志里必须留下标记）；
/// 3. 旧上下文（assistant 没有工具痕迹）注入迁移说明，旧消息本身不补造痕迹；
/// 4. 思考强度：默认 `low`，调用 `think` 之后这一轮 `high`，下一轮回到 `low`。
///
/// 夹具与 `ExperienceChatTests` 同一套（真 URLSession + 假流式服务端 + 真 SQLite），
/// 因为「请求报文里到底发了什么」只有在这一层才看得见。
final class ToolCallAgentTests: XCTestCase {

    private var directoryURL: URL!
    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var tools: OutboundToolRunner!
    private var logs: LogRecorder!
    private var service: ChatService!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-toolcall-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        databaseURL = directoryURL.appendingPathComponent("db.sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        settings = InMemorySettings(AppSettings())
        tools = OutboundToolRunner()
        logs = LogRecorder()
        service = makeService()
        try seedReadySession()
    }

    override func tearDownWithError() throws {
        service = nil
        StubURLProtocol.reset()
        stores = nil
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databaseURL.path + suffix)
        }
        try? FileManager.default.removeItem(at: directoryURL)
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func makeService() -> ChatService {
        ChatService(
            stores: stores,
            apiKeys: apiKeys,
            settings: settings,
            client: OpenAICompatibleClient(
                session: StubURLProtocol.makeSession(),
                usage: UsageRecorder(stores: stores),
                gate: LlmConcurrencyGate()
            ),
            tools: tools,
            background: NoBackgroundTasks(),
            // 延迟只影响节奏，这一组不测节奏：立刻返回，测试才不慢。
            sleep: { _ in },
            log: { [logs] line in logs?.append(line) },
            agentCore: NativeAgentCoreClient(),
            now: { self.now }
        )
    }

    private func seedReadySession() throws {
        try stores.personas.upsert(PersonaEntity(
            id: personaId, name: "小满", prompt: "你是小满。", version: 1, isDefault: true,
            updatedAt: 1, sleepEnabled: false
        ))
        try stores.apiConfigs.upsert(ApiConfigEntity(
            id: configId, name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", updatedAt: 1
        ))
        try stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, apiConfigId: configId,
            model: "", title: "对话", createdAt: 1, updatedAt: 1,
            lastPersonaVersion: 1, lastMemoryExtractedCount: 0
        ))
        try apiKeys.setAPIKey("sk-test", for: configId)
    }

    /// 一条旧版本写下的 assistant 消息：没有 `toolTraceJson`（迁移判据，§3）。
    private func seedLegacyAssistantMessage(_ content: String, createdAt: Int64) throws {
        try stores.messages.upsert(MessageEntity(
            id: "legacy-\(UUID().uuidString)",
            sessionId: sessionId,
            personaId: personaId,
            role: .assistant,
            content: content,
            createdAt: createdAt,
            updatedAt: createdAt,
            status: .complete
        ))
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func storedMessages() throws -> [MessageEntity] {
        try stores.messages.bySession(sessionId)
    }

    private func assistantMessages() throws -> [MessageEntity] {
        try storedMessages().filter { $0.role == .assistant }
    }

    private func appended(_ events: [ChatEvent]) -> [MessageEntity] {
        events.compactMap { if case .appended(let message) = $0 { return message } else { return nil } }
    }

    private func deltas(_ events: [ChatEvent]) -> [String] {
        events.compactMap { if case .delta(let text) = $0 { return text } else { return nil } }
    }

    private func doneEvent(_ events: [ChatEvent]) -> ChatCompletion? {
        for event in events {
            if case .done(let completion) = event { return completion }
        }
        return nil
    }

    private func requestBody(at index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "没有记录到第 \(index) 次请求")
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: bodies[index]) as? [String: Any])
    }

    private func systemPrompt(at index: Int = 0) throws -> String {
        let body = try requestBody(at: index)
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        return try XCTUnwrap(messages.first?["content"] as? String)
    }

    /// 请求里所有 `content` 为字符串的消息正文（回灌给模型的那几条都在这里）。
    private func requestTexts(at index: Int = 0) throws -> [String] {
        let body = try requestBody(at: index)
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        return messages.compactMap { $0["content"] as? String }
    }

    private func sendMessageBlock(_ text: String) -> String {
        "```yap-tool\n"
            + #"{"name":"send_message","arguments":{"text":"\#(text)"}}"#
            + "\n```"
    }

    private func thinkBlock(rounds: Int? = nil) -> String {
        let arguments: String
        if let rounds {
            arguments = #"{"question":"怎么接这句话","rounds":\#(rounds)}"#
        } else {
            arguments = #"{"question":"怎么接这句话"}"#
        }
        return "```yap-tool\n" + #"{"name":"think","arguments":"# + arguments + "}\n```"
    }

    // MARK: - 1. 提示词：只能通过 send_message 说话

    func testSystemPromptDeclaresSendMessageOnlyProtocol() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["好"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["好"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains(ToolCatalog.outboundProtocolTitle), "要有「怎么说话」这一小段")
        XCTAssertTrue(system.contains("每一句给对方看的话都必须调用 send_message"), "不能直接输出正文")
        XCTAssertTrue(system.contains("直接输出的正文不算发出去"))
        XCTAssertTrue(system.contains("stay_silent"), "沉默也要写清（表示这一轮不发）")
        XCTAssertTrue(system.contains("每轮最多 \(MessageRhythm.maxMessagesPerRound) 条"))
        XCTAssertTrue(system.contains("\(MessageRhythm.maxMessageChars) 字"))
        XCTAssertTrue(system.contains("两个都没调用算协议违规"))
        // 旧的「发完还可以继续用正文补最后一句」与新协议直接冲突，必须消失。
        XCTAssertFalse(system.contains("发完还可以继续用正文补最后一句"))

        // 工具描述本身也要改口（模型最先读的是清单里那一行）。
        let spec = try XCTUnwrap(ToolCatalog.byName(ToolCatalog.sendMessage))
        XCTAssertTrue(spec.description.contains("唯一能把话传到对方那里的方式"), spec.description)
        XCTAssertTrue(spec.description.contains("正文不算发出去"), spec.description)
        XCTAssertTrue(spec.sideEffect.contains("唯一能说话的方式"), spec.sideEffect)
    }

    func testEnglishCapabilityPromptDeclaresSendMessageOnlyProtocol() {
        let prompt = ToolCatalog.capabilityPrompt(webEnabled: true, language: .en)
        XCTAssertTrue(prompt.contains("## How you speak"))
        XCTAssertTrue(prompt.contains("You can only speak through send_message"))
        XCTAssertTrue(prompt.contains("stay_silent"))
        XCTAssertTrue(prompt.contains("At most \(MessageRhythm.maxMessagesPerRound) send_message calls per turn"))
    }

    // MARK: - 2. send_message 回灌 messageId，模型可以继续

    func testSendMessageFeedsBackMessageIdAndModelKeepsTalking() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("到家了吗？")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("路上小心。")]))
        // 第三轮什么都不写：`send_message` 已经说完了，这是正常收尾（不是走神）。
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))

        let events = try await collect(service.send(sessionId: sessionId, text: "我出门了"))
        let assistants = try assistantMessages()
        XCTAssertEqual(assistants.map(\.content), ["到家了吗？", "路上小心。"], "两条独立气泡")
        XCTAssertEqual(appended(events).map(\.content), ["到家了吗？", "路上小心。"])
        XCTAssertEqual(doneEvent(events)?.message.content, "路上小心。", "done 指向最后一条")

        // 第二次请求里，第一次 `send_message` 的结果必须带回真实的 messageId。
        let firstId = assistants[0].id
        let secondRequest = try requestTexts(at: 1)
        XCTAssertTrue(
            secondRequest.contains { $0.contains("已发送，messageId=\(firstId)") },
            "工具结果要回灌 messageId：\(secondRequest)"
        )
        // 模型自己的那次调用（含协议块）会作为 assistant 历史回灌，这是既有协议；
        // 但**工具结果**里不该有协议块，只有回执。
        XCTAssertTrue(
            secondRequest.filter { $0.contains("【工具结果】") }.allSatisfy { !$0.contains("yap-tool") }
        )
        // 第二次请求已经能看到第一条的历史（它是消息，不是工具卡）。
        XCTAssertTrue(secondRequest.contains { $0.contains("到家了吗？") })

        // 新协议的消息带 `[]` 标记（不是 nil）：下次读历史时不会被当成「旧版本直接说的」。
        XCTAssertEqual(assistants[0].toolTraceJson, OutboundProtocol.toolProtocolTraceMarker)
        XCTAssertEqual(assistants[1].toolTraceJson, OutboundProtocol.toolProtocolTraceMarker)
        // 走了新协议就不该再留下 legacy 标记（日志是给「迁移进度」看的）。
        XCTAssertFalse(logs.lines.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) })
    }

    // MARK: - 3. 流式只做输入指示；气泡在工具完成后出现（参数预览）

    func testBubbleAppearsOnlyAfterSendMessageCompletes() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("到家了吗？")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))

        let events = try await collect(service.send(sessionId: sessionId, text: "我出门了"))
        // 流式正文里**没有**这句话：它只是工具参数，不是正文。
        XCTAssertFalse(deltas(events).contains { $0.contains("到家了吗？") }, "token 只做输入指示")
        XCTAssertTrue(deltas(events).allSatisfy { $0.isEmpty }, "新协议下没有可显示的流式正文")

        // 开始事件带参数预览（「可参数预览」），并且发生在气泡出现之前。
        guard let previewIndex = events.firstIndex(where: {
            if case .toolStarted(let item) = $0 {
                return item.name == ToolCatalog.sendMessage && item.subtitle == "到家了吗？"
            }
            return false
        }) else {
            return XCTFail("send_message 开始时要把参数当成即时预览交给界面")
        }
        guard let bubbleIndex = events.firstIndex(where: {
            if case .appended(let message) = $0 { return message.content == "到家了吗？" }
            return false
        }) else {
            return XCTFail("send_message 完成后要有一个独立气泡")
        }
        XCTAssertLessThan(previewIndex, bubbleIndex, "预览在前，真气泡在工具完成后才出现")
    }

    // MARK: - 4. 兼容回退：没有 send_message 的正文仍然落库，但标记 legacy

    func testLegacyBodyFallbackIsLoggedAndStillSaved() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["我在呢。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["我在呢。"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let assistants = try assistantMessages()
        XCTAssertEqual(assistants.map(\.content), ["我在呢。"], "老模型仍然能说话，不能变哑")
        XCTAssertNil(assistants[0].toolTraceJson, "legacy 消息没有工具痕迹（也不补造）")
        XCTAssertEqual(doneEvent(events)?.message.content, "我在呢。")
        XCTAssertTrue(
            logs.lines.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) },
            "兼容回退必须留下日志标记：\(logs.lines)"
        )
    }

    // MARK: - 5. 沉默 / 协议违规

    func testStaySilentOnlyKeepsReadAndWritesNoMessage() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n{\"name\":\"stay_silent\",\"arguments\":{\"reason\":\"累了\"}}\n```"
        ]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertTrue(events.contains { if case .noReply(.stayedSilent) = $0 { return true } else { return false } })
        XCTAssertFalse(events.contains { if case .done = $0 { return true } else { return false } })
        let stored = try storedMessages()
        XCTAssertEqual(stored.map(\.role), [.user], "只有沉默工具 → 不产生消息")
        XCTAssertNotNil(stored[0].readAt, "只标已读")
    }

    func testNoMessageAndNoSilenceIsProtocolViolation() async throws {
        // 既没有正文、也没有任何工具调用：这是协议违规，按失败处理（界面可重试）。
        // 注意：真正「一个字节都没有」的流会被网络层当成服务端故障并重试（那是另一层的事），
        // 这里用「只有空白」把场景落在聊天服务这一层。
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        } catch {
            thrown = error
        }
        let api = try XCTUnwrap(thrown as? ApiException)
        XCTAssertEqual(api.message, "模型没有返回任何文字")
        XCTAssertEqual(try storedMessages().map(\.role), [.user], "不产生空助手消息")
    }

    // MARK: - 6. 每轮最多 4 条

    func testSendMessageIsCappedAtFourPerRound() async throws {
        var payload = ""
        for index in 1...5 {
            payload += sendMessageBlock("第 \(index) 条") + "\n\n"
        }
        StubURLProtocol.enqueue(.sse(textDeltas: [payload]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(
            try assistantMessages().map(\.content),
            ["第 1 条", "第 2 条", "第 3 条", "第 4 条"],
            "第 5 条不该发出去（每轮最多 4 条）"
        )
        XCTAssertEqual(appended(events).count, 4)
        XCTAssertEqual(tools.sentTexts.count, 4, "第 5 次 send_message 不该被执行")
        // 超出的那次不执行，但要明确告诉模型「已经发够了」。
        XCTAssertTrue(
            try requestTexts(at: 1).contains { $0.contains("这一轮你已经发了 4 条消息") },
            "模型要知道为什么第 5 条没发出去"
        )
    }

    // MARK: - 7. 思考强度

    func testDefaultEffortIsLowAndThinkRaisesItForTheRestOfTheTurn() async throws {
        // 第一轮：think → send_message → 空响应收尾。
        StubURLProtocol.enqueue(.sse(textDeltas: [thinkBlock()]))
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("我想了想，别急。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        _ = try await collect(service.send(sessionId: sessionId, text: "你觉得我该去吗"))

        // 第二轮（新的用户消息）：回到默认 low。
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("嗯。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        _ = try await collect(service.send(sessionId: sessionId, text: "那我再想想"))

        let efforts = try (0..<5).map { index in
            try requestBody(at: index)["reasoning_effort"] as? String
        }
        XCTAssertEqual(efforts, ["low", "high", "high", "low", "low"],
                       "默认 low；think 之后这一轮 high；下一轮回到 low")
    }

    func testAuxiliaryRequestOmitsReasoningEffort() throws {
        let messages = [WireMessage(role: "user", content: "在吗")]
        // 主会话：带 low。
        let chat = try OpenAICompatibleClient.encodeRequest(
            model: "deepseek-chat", messages: messages,
            params: InferenceParams(reasoningEffort: .low), stream: false
        )
        let chatJson = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: chat) as? [String: Any]
        )
        XCTAssertEqual(chatJson["reasoning_effort"] as? String, "low")

        // 辅助调用（危机判定 / 记忆抽取…）：参数里没有这一档，报文里连键都不该有。
        let aux = try OpenAICompatibleClient.encodeRequest(
            model: "deepseek-chat", messages: messages,
            params: InferenceParams(temperature: 0, maxOutputTokens: 200), stream: false
        )
        let auxJson = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: aux) as? [String: Any]
        )
        XCTAssertNil(auxJson["reasoning_effort"], "辅助能力不参与思考强度")
    }

    // MARK: - 8. 迁移兼容

    func testMigrationNoteInjectedForLegacyAssistantMessages() async throws {
        try seedLegacyAssistantMessage("早点睡。", createdAt: now - 60_000)
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("嗯，睡了。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))

        _ = try await collect(service.send(sessionId: sessionId, text: "我睡了"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("# 历史说明"), "旧上下文要注入迁移说明")
        XCTAssertTrue(system.contains("不代表你现在可以跳过工具"))
        XCTAssertTrue(system.contains("每一句给对方看的话都要调用 send_message"))

        // 旧消息原样保留：不补造痕迹（§3）。
        let legacy = try XCTUnwrap(try storedMessages().first { $0.content == "早点睡。" })
        XCTAssertNil(legacy.toolTraceJson, "旧消息不补工具痕迹")
        // 新消息是干净的新协议消息。
        let fresh = try XCTUnwrap(try assistantMessages().first { $0.content == "嗯，睡了。" })
        XCTAssertEqual(fresh.toolTraceJson, OutboundProtocol.toolProtocolTraceMarker)
    }

    func testNoMigrationNoteForNewProtocolContext() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("到家了吗？")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我出门了"))

        StubURLProtocol.enqueue(.sse(textDeltas: [sendMessageBlock("好。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        _ = try await collect(service.send(sessionId: sessionId, text: "到家了"))

        let secondTurnSystem = try systemPrompt(at: 2)
        XCTAssertFalse(secondTurnSystem.contains("# 历史说明"),
                       "全是新协议消息时不该反复注入迁移说明")
    }

    func testLegacyDetectionIgnoresErrorAndUserMessages() throws {
        let legacy = MessageEntity(
            id: "m1", sessionId: "s", personaId: "p", role: .assistant, content: "旧话"
        )
        let marked = MessageEntity(
            id: "m2", sessionId: "s", personaId: "p", role: .assistant, content: "新话",
            toolTraceJson: OutboundProtocol.toolProtocolTraceMarker
        )
        let failed = MessageEntity(
            id: "m3", sessionId: "s", personaId: "p", role: .assistant, content: "半句",
            status: .error
        )
        let user = MessageEntity(
            id: "m4", sessionId: "s", personaId: "p", role: .user, content: "我说的"
        )
        XCTAssertTrue(OutboundProtocol.isLegacyAssistantMessage(legacy))
        XCTAssertFalse(OutboundProtocol.isLegacyAssistantMessage(marked))
        XCTAssertFalse(OutboundProtocol.isLegacyAssistantMessage(failed), "失败气泡不是他真说过的话")
        XCTAssertFalse(OutboundProtocol.isLegacyAssistantMessage(user))
        XCTAssertEqual(OutboundProtocol.legacyAssistantMessageCount([legacy, marked, failed, user]), 1)
    }

    func testMigrationNoteIsLocalized() {
        XCTAssertTrue(OutboundProtocol.migrationNote(language: .zh).hasPrefix("# 历史说明"))
        let english = OutboundProtocol.migrationNote(language: .en)
        XCTAssertTrue(english.hasPrefix("# About the history"))
        XCTAssertTrue(english.contains("send_message"))
        XCTAssertFalse(english.contains("历史"))
    }

    // MARK: - 9. 备份 v7：旧消息不补痕迹、新标记原样带走

    func testBackupMessageKeepsLegacyNilAndToolProtocolMarker() throws {
        // 旧备份（v7 之前）里的 assistant 消息没有 toolTraceJson：导出时也不该凭空补一个。
        let legacy = BackupMessage(
            id: "m1", sessionId: "s", personaId: "p", role: "ASSISTANT", content: "旧话"
        )
        let legacyJson = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(legacy)) as? [String: Any]
        )
        XCTAssertNil(legacyJson["toolTraceJson"], "旧消息不补痕迹")

        // 新协议的空标记是一个合法的 JSON 数组（读回来就是「没有痕迹」）。
        let marked = BackupMessage(
            id: "m2", sessionId: "s", personaId: "p", role: "ASSISTANT", content: "新话",
            toolTraceJson: OutboundProtocol.toolProtocolTraceMarker
        )
        let markedJson = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(marked)) as? [String: Any]
        )
        XCTAssertEqual(markedJson["toolTraceJson"] as? String, "[]")
        XCTAssertEqual(ToolTraceCodec.decode(OutboundProtocol.toolProtocolTraceMarker), [],
                       "空标记解码成「没有工具痕迹」，界面不会多一行")
    }
}

// MARK: - 测试替身

/// 按工具名给出结果：`send_message` 交出清洗后的正文（与真 `ToolExecutor` 同形）。
private final class OutboundToolRunner: ToolRunning {

    private(set) var names: [String] = []
    private(set) var sentTexts: [String] = []

    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        names.append(invocation.name)
        switch invocation.name {
        case ToolCatalog.sendMessage:
            guard let text = MessageRhythm.cleanSendText(invocation.argument("text")) else {
                return ToolResult(
                    invocation: invocation, ok: false,
                    summary: String(localized: "没有写要发什么"),
                    contentForModel: String(localized: "没有写要发什么")
                )
            }
            sentTexts.append(text)
            var result = ToolResult(
                invocation: invocation, ok: true,
                summary: String(localized: "发了条消息"), contentForModel: "发出去了"
            )
            result.outboundText = text
            return result
        case ToolCatalog.think:
            return ToolResult(
                invocation: invocation, ok: true,
                summary: String(localized: "想了想"), contentForModel: "（想清楚了）"
            )
        case ToolCatalog.staySilent:
            return ToolResult(
                invocation: invocation, ok: true,
                summary: String(localized: "这轮不回"), contentForModel: "好的"
            )
        default:
            return ToolResult(
                invocation: invocation, ok: true,
                summary: String(localized: "做完了"), contentForModel: "结果"
            )
        }
    }
}

/// 收日志行（`ChatService` 的 `log` 闭包）。
private final class LogRecorder {
    private let lock = NSLock()
    private var storage: [String] = []

    var lines: [String] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func append(_ line: String) {
        lock.lock()
        storage.append(line)
        lock.unlock()
    }
}

private extension StubURLProtocol.Stub {
    static func sse(textDeltas: [String]) -> StubURLProtocol.Stub {
        var text = ""
        for delta in textDeltas {
            let escaped = delta
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
                .replacingOccurrences(of: "\n", with: "\\n")
            text += "data: {\"choices\":[{\"delta\":{\"content\":\"\(escaped)\"}}]}\n\n"
        }
        text += "data: [DONE]\n\n"
        return .sse(text)
    }
}
