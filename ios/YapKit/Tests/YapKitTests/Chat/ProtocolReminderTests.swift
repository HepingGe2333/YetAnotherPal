import Foundation
import XCTest
@testable import YapKit

/// 0.15.0 工具协议**确认循环**（`ProtocolReminder` + `ChatService` 里的确认分支）。
///
/// 产品规则：话只能通过 `send_message` 说出去；模型只写正文时，客户端会**逐次问它**
/// 「这段话要不要发送」（最多 2 轮），不再自动 legacy 落库：
///
/// 1. 第一次确认后正确调用 `send_message` → 正常落库（**一条**消息，不是两条）；
/// 2. 两次确认后调用 `send_message` → 仍然正常落库（第二轮确认真的发得出去）；
/// 3. 两次确认后仍然只写正文 → **整段丢弃**：不落库、不 legacy、不显示，
///    按 silent 结束并记 `protocol_dropped`；
/// 4. 确认后 `stay_silent` → 照常沉默（什么都不落）；
/// 5. 一开始就按协议调用 → 根本没有确认请求，也不会有 `protocol_reminder` 日志；
/// 6. 危机轮**不走确认循环、永不 dropped**：安全提示必须落库。
///
/// 另外两条硬约束：提醒**不落库**、日志**不带正文**。
final class ProtocolReminderTests: XCTestCase {

    private var directoryURL: URL!
    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var recorder: UsageRecorder!
    private var tools: ProtocolReminderToolStub!
    private var service: ChatService!
    private var logs: [String] = []

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        logs = []
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-reminder-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        databaseURL = directoryURL.appendingPathComponent("db.sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        settings = InMemorySettings(AppSettings())
        recorder = UsageRecorder(stores: stores)
        tools = ProtocolReminderToolStub()
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

    private func makeService() -> ChatService {
        ChatService(
            stores: stores,
            apiKeys: apiKeys,
            settings: settings,
            client: OpenAICompatibleClient(
                session: StubURLProtocol.makeSession(), usage: recorder, gate: LlmConcurrencyGate()
            ),
            tools: tools,
            background: NoBackgroundTasks(),
            // 延迟只影响节奏，这一组不测节奏：立刻返回，测试才不慢。
            sleep: { _ in },
            log: { [weak self] line in self?.logs.append(line) },
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

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func storedMessages() throws -> [MessageEntity] {
        try stores.messages.bySession(sessionId)
    }

    private func noReplyEvents(_ events: [ChatEvent]) -> [ChatNoReplyReason] {
        events.compactMap { if case .noReply(let reason) = $0 { return reason } else { return nil } }
    }

    private func doneEvents(_ events: [ChatEvent]) -> [ChatCompletion] {
        events.compactMap { if case .done(let completion) = $0 { return completion } else { return nil } }
    }

    private func deltas(_ events: [ChatEvent]) -> [String] {
        events.compactMap { if case .delta(let text) = $0 { return text } else { return nil } }
    }

    /// 这一次请求发出去的消息数组（`role` + `content`）。
    private func requestMessages(at index: Int) throws -> [(role: String, content: String)] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "没有记录到第 \(index) 次请求")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: bodies[index]) as? [String: Any])
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        return messages.map { ($0["role"] as? String ?? "", $0["content"] as? String ?? "") }
    }

    /// 把一次 `send_message` 的正文交给模型（`yap-tool` 代码块，与生产协议同形）。
    private func sendMessagePayload(_ text: String) -> String {
        "```yap-tool\n{\"name\":\"send_message\",\"arguments\":{\"text\":\"\(text)\"}}\n```"
    }

    private func staySilentPayload() -> String {
        "```yap-tool\n{\"name\":\"stay_silent\",\"arguments\":{\"reason\":\"不想说\"}}\n```"
    }

    private func enqueueText(_ text: String) {
        var payload = ""
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        payload += "data: {\"choices\":[{\"delta\":{\"content\":\"\(escaped)\"}}]}\n\n"
        payload += "data: [DONE]\n\n"
        StubURLProtocol.enqueue(.sse(payload))
    }

    private var reminderLines: [String] {
        logs.filter { $0.contains(ProtocolReminder.logTag) }
    }

    private var droppedLines: [String] {
        logs.filter { $0.contains(ProtocolReminder.droppedLogTag) }
    }

    private func sentMessageResult(_ text: String) -> ToolResult {
        var result = ToolResult(
            invocation: ToolInvocation(id: "call-1", name: ToolCatalog.sendMessage, argumentsJson: "{}"),
            ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        result.outboundText = text
        return result
    }

    // MARK: - 1. 第一次确认之后正确调用 send_message

    func testFirstConfirmationThenSendMessageDeliversOneMessage() async throws {
        tools.results = [sentMessageResult("早点睡吧。")]

        // 第 1 次：只写正文，没有工具调用（违规）；第 2 次：确认之后正确调用 send_message；
        // 第 3 次：工具结果回灌之后它只回了一个空白（这一轮说完就停，不再多说一句）。
        enqueueText("早点睡吧。")
        enqueueText(sendMessagePayload("早点睡吧。"))
        enqueueText(" ")

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 3,
                       "第一次确认之后要带 tools 再请求一次（再一次是工具回灌）")
        // 第二次请求里：正文作为 assistant、确认提醒作为**不可见**的 user 跟在后面。
        let second = try requestMessages(at: 1)
        XCTAssertTrue(second.contains { $0.role == "assistant" && $0.content.contains("早点睡吧。") },
                      "模型刚才写的正文要作为 assistant 交回去：\(second)")
        XCTAssertTrue(second.contains {
            $0.role == "user" && $0.content == ProtocolReminder.chineseText
        }, "要追加一条不可见的确认提醒：\(second)")

        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["早点睡吧。"],
                       "正确调用之后只落一条消息（不能再走 legacy 多一条）")
        XCTAssertEqual(tools.callCount, 1, "send_message 真的被执行了一次")
        XCTAssertEqual(doneEvents(events).count, 1)
        XCTAssertEqual(noReplyEvents(events).count, 0, "正常发送不该出现「不回」")
        XCTAssertEqual(reminderLines.count, 1, "只问了一次：\(logs)")
        XCTAssertTrue(reminderLines[0].contains("attempt=1/2"), "日志要能看出问到了第几轮：\(reminderLines)")
        XCTAssertTrue(reminderLines[0].contains("chars="), "日志要带正文字数（但不带正文）：\(reminderLines)")
        XCTAssertTrue(droppedLines.isEmpty, "按协议发出去之后不该 dropped：\(logs)")
        XCTAssertFalse(logs.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) })
        // 提醒本身不落库：库里除了用户那句就只有他发出去的那一条。
        XCTAssertFalse(try storedMessages().contains { $0.content.contains("你刚才输出的不是工具调用") })
    }

    // MARK: - 2. 第二次确认之后才调用 send_message

    func testSecondConfirmationThenSendMessageDeliversOneMessage() async throws {
        tools.results = [sentMessageResult("在呢，别担心。")]

        enqueueText("在呢。")
        enqueueText("在呢，别担心。")
        enqueueText(sendMessagePayload("在呢，别担心。"))
        enqueueText(" ")

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 4,
                       "两次确认 + 一次工具调用 + 一次工具回灌")
        // 第三次请求里应该有两段 assistant 正文、两条不可见提醒。
        let third = try requestMessages(at: 2)
        XCTAssertTrue(third.contains { $0.role == "assistant" && $0.content.contains("在呢。") })
        XCTAssertTrue(third.contains { $0.role == "assistant" && $0.content.contains("在呢，别担心。") },
                      "两段正文都要放回去：\(third)")
        XCTAssertEqual(third.filter { $0.role == "user" && $0.content == ProtocolReminder.chineseText }.count,
                       2, "两轮确认各有一条提醒：\(third)")

        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["在呢，别担心。"],
                       "第二次确认后正确发送：只落最新那句，且只落一条")
        XCTAssertEqual(tools.callCount, 1)
        XCTAssertTrue(reminderLines.contains { $0.contains("attempt=2/2") }, "\(logs)")
        XCTAssertTrue(droppedLines.isEmpty)
        XCTAssertFalse(logs.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) })
    }

    // MARK: - 3. 两次确认之后仍然只写正文 → 整段丢弃（不落库、不 legacy、不显示）

    func testStillBodyAfterTwoConfirmationsIsDroppedSilently() async throws {
        enqueueText("在呢。")
        enqueueText("在呢，别担心。")
        enqueueText("在呢，真的别担心。")

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 3,
                       "最多问 2 次：初稿 + 两次确认请求（第 4 次就是无限循环）")
        // 不落库：库里只有用户那句。
        XCTAssertEqual(try storedMessages().map(\.role), [.user])
        XCTAssertTrue(try storedMessages().allSatisfy { $0.role != .assistant })
        // 不 legacy：没有任何旧协议标记。
        XCTAssertFalse(logs.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) })
        // 按 silent 结束：没有 done，只有一个 noReply，且原因是协议丢弃。
        XCTAssertTrue(doneEvents(events).isEmpty, "丢弃不能发 done（界面会以为有一条消息）")
        XCTAssertEqual(noReplyEvents(events), [.protocolDropped])
        // 不显示正文：流里先把已显示的那段抹掉，最后一条 delta 是空串。
        XCTAssertEqual(deltas(events).last, "", "丢弃时要把界面上已经显示过的正文抹掉")
        // 日志：protocol_dropped，且**不带正文**。
        let dropped = try XCTUnwrap(droppedLines.first, "要留下 protocol_dropped 日志：\(logs)")
        XCTAssertTrue(dropped.contains("dropped without saving"), "\(dropped)")
        XCTAssertTrue(dropped.contains("attempts=2/2"), "丢弃日志要带问过几次：\(dropped)")
        XCTAssertTrue(dropped.contains("chars="), "丢弃日志要带字数（但不带正文）：\(dropped)")
        XCTAssertFalse(dropped.contains("别担心"), "日志不能带正文：\(dropped)")
        XCTAssertEqual(reminderLines.count, 2, "两次确认各留一行：\(logs)")
        XCTAssertTrue(reminderLines.allSatisfy { !$0.contains("别担心") }, "提醒日志也不能带正文")
    }

    // MARK: - 3b. 确认之后调用「别的工具」→ 回到正常工具回路

    func testConfirmationThenOtherToolResumesNormalLoop() async throws {
        // 正文 → 一次确认；第二次响应调用 `think`（其它工具）→ 正常执行；
        // 工具回灌之后它才用 `send_message` 把话发出去。
        var thinkResult = ToolResult(
            invocation: ToolInvocation(id: "call-0", name: ToolCatalog.think, argumentsJson: "{}"),
            ok: true, summary: "想了想", contentForModel: "好"
        )
        thinkResult.traceTitle = "想了想"
        tools.results = [thinkResult, sentMessageResult("想好了。")]

        enqueueText("让我想想。")
        enqueueText("```yap-tool\n{\"name\":\"think\",\"arguments\":{\"question\":\"怎么接\"}}\n```")
        enqueueText(sendMessagePayload("想好了。"))
        enqueueText(" ")

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 4,
                       "确认一次 + think 一轮 + send_message 一轮 + 工具回灌一轮")
        XCTAssertEqual(tools.callCount, 2, "think 与 send_message 都真的执行了")
        XCTAssertEqual(reminderLines.count, 1, "调了别的工具就不再追问：\(logs)")
        XCTAssertEqual(try storedMessages().filter { $0.role == .assistant }.map(\.content), ["想好了。"])
        XCTAssertTrue(droppedLines.isEmpty)
        XCTAssertTrue(doneEvents(events).count == 1)
    }

    // MARK: - 4. 确认之后 stay_silent → 正常沉默

    func testConfirmationThenStaySilentHonorsSilence() async throws {
        enqueueText("嗯。")
        enqueueText(staySilentPayload())

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 2,
                       "沉默是确认之后的有效回答，不需要再请求")
        XCTAssertEqual(try storedMessages().map(\.role), [.user], "沉默这一轮不落任何助手消息")
        XCTAssertEqual(noReplyEvents(events), [.stayedSilent], "这是模型主动沉默，不是协议丢弃")
        XCTAssertEqual(tools.callCount, 0, "stay_silent 不是工具，不该被执行")
        XCTAssertEqual(reminderLines.count, 1)
        XCTAssertTrue(droppedLines.isEmpty, "主动沉默不该记 protocol_dropped：\(logs)")
    }

    // MARK: - 5. 正常按协议调用：根本不问

    func testNoConfirmationWhenProtocolFollowed() async throws {
        tools.results = [sentMessageResult("在呢。")]
        enqueueText(sendMessagePayload("在呢。"))
        enqueueText(" ")

        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 2,
                       "按协议走时只有「工具回灌」那一次请求，没有确认循环")
        XCTAssertTrue(reminderLines.isEmpty, "不该出现确认日志：\(logs)")
        XCTAssertTrue(droppedLines.isEmpty)
        XCTAssertEqual(try storedMessages().filter { $0.role == .assistant }.map(\.content), ["在呢。"])
    }

    // MARK: - 6. 危机页：不走确认循环、永不 dropped

    func testCrisisTurnIsNeverDroppedAndLandsSafetyReply() async throws {
        // crisis 判定（词表命中后的一次低温度复核）与聊天各用一份响应；内容相同，
        // 谁先取到都不影响结论（坏 JSON → failsafe 仍按危机处理）。
        enqueueText("嗯。那你早点睡。")
        enqueueText("嗯。那你早点睡。")

        let events = try await collect(service.send(sessionId: sessionId, text: "我真的不想活了"))

        // 第一段正文直接走危机兜底：不确认、不 dropped。
        let assistant = try XCTUnwrap(try storedMessages().last { $0.role == .assistant },
                                      "危机轮必须留下一条看得见的回复")
        XCTAssertTrue(assistant.content.hasPrefix("嗯。那你早点睡。"), "只在末尾追加，不改写")
        XCTAssertTrue(assistant.content.contains("120") || assistant.content.contains("110"))
        XCTAssertTrue(doneEvents(events).count == 1)
        XCTAssertTrue(noReplyEvents(events).isEmpty, "危机轮不能被当成沉默")
        XCTAssertTrue(droppedLines.isEmpty, "危机轮永远不能 dropped：\(logs)")
        XCTAssertTrue(reminderLines.isEmpty, "危机轮不问「要不要发」：安全提示不能等两轮：\(logs)")
    }

    // MARK: - 7. 英文人格拿到英文确认提醒

    func testEnglishPersonaGetsEnglishConfirmation() async throws {
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.languageTag = "en"
        try stores.personas.upsert(persona)
        tools.results = [sentMessageResult("I'm here, don't worry.")]

        enqueueText("I'm here.")
        enqueueText("I'm here, don't worry.")
        enqueueText(sendMessagePayload("I'm here, don't worry."))
        enqueueText(" ")

        _ = try await collect(service.send(sessionId: sessionId, text: "you there?"))

        let second = try requestMessages(at: 1)
        XCTAssertTrue(second.contains {
            $0.role == "user" && $0.content == ProtocolReminder.englishText
        }, "英文人格应当读到英文确认提醒：\(second)")
        XCTAssertFalse(second.contains { $0.content == ProtocolReminder.chineseText })
        XCTAssertEqual(try storedMessages().filter { $0.role == .assistant }.map(\.content),
                       ["I'm here, don't worry."])
    }

    // MARK: - 工具轮上限之后补写的正文：同样不落库、不 legacy

    func testBodyAfterToolRoundLimitIsDroppedNotLegacy() async throws {
        // 模型先用 `think(rounds: 1)` 把工具轮数用满，下一轮却只写正文、没有 send_message。
        // 这时已经不能再问（轮数上限），但正文仍然不能自动 legacy 发送。
        enqueueText("```yap-tool\n{\"name\":\"think\",\"arguments\":{\"rounds\":1}}\n```")
        enqueueText("还是想直接说。")

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 2, "工具轮 + 收尾正文，不再追问")
        XCTAssertTrue(try storedMessages().allSatisfy { $0.role != .assistant })
        XCTAssertEqual(noReplyEvents(events), [.protocolDropped])
        XCTAssertFalse(logs.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) },
                       "工具轮用尽也不是 legacy 的借口：\(logs)")
        let dropped = try XCTUnwrap(droppedLines.first)
        XCTAssertTrue(dropped.contains("attempts=0/2"), "这一轮没问过：\(dropped)")
    }

    // MARK: - 纯规则（与 Android `shouldRemind` / `shouldDrop` 同名同义）

    func testShouldRemindBoundaries() {
        XCTAssertTrue(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯"))
        // 有工具 → 不问；没正文 → 不问；已发过消息 → 不问。
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: true, body: "嗯"))
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: ""))
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "  \n "))
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯", messagesSent: 1))
        // 沉默 / 延后 / 危机 / 问满 2 次 → 不问。
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯", silenced: true))
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯", deferred: true))
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯", crisis: true))
        XCTAssertTrue(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯", attempts: 1))
        XCTAssertFalse(ProtocolReminder.shouldRemind(hasToolCalls: false, body: "嗯", attempts: 2))
    }

    func testShouldDropOnlyUnconfirmedBodyOutsideCrisis() {
        // 没有 send_message 确认过的正文 → 丢（问过几次不影响）。
        XCTAssertTrue(ProtocolReminder.shouldDrop(body: "我在呢。"))
        XCTAssertTrue(ProtocolReminder.shouldDrop(body: "我在呢。", messagesSent: 0))
        // 已经发过消息 → 剩下的半句并进最后一条，不丢。
        XCTAssertFalse(ProtocolReminder.shouldDrop(body: "我在呢。", messagesSent: 1))
        // 危机轮 → 安全提示必须落库，不能丢。
        XCTAssertFalse(ProtocolReminder.shouldDrop(body: "我在呢。", crisis: true))
        // 没有正文 → 不涉及「丢正文」（空流由失败路径处理）。
        XCTAssertFalse(ProtocolReminder.shouldDrop(body: "  \n "))
    }

    func testProtocolLogLinesDoNotCarryTheReplyBody() {
        let reminder = ProtocolReminder.logLine(attempt: 1, chars: 12)
        XCTAssertTrue(reminder.contains("protocol_reminder"))
        XCTAssertTrue(reminder.contains("attempt=1/2"))
        XCTAssertTrue(reminder.contains("chars=12"))
        XCTAssertEqual(2, reminder.components(separatedBy: "protocol_reminder").count,
                       "标记只出现一次，便于 grep")

        let dropped = ProtocolReminder.dropLogLine(attempts: 2, chars: 12)
        XCTAssertTrue(dropped.contains("protocol_dropped"))
        XCTAssertTrue(dropped.contains("attempts=2/2"))
        XCTAssertTrue(dropped.contains("chars=12"))
        XCTAssertFalse(dropped.contains("protocol_reminder"), "两个标记能分开统计")
    }

    // MARK: - 确认提醒文案本身

    func testReminderTextIsTheConfirmationQuestion() {
        XCTAssertEqual(ProtocolReminder.maxConfirmationRounds, 2)
        XCTAssertEqual(ProtocolReminder.logTag, "protocol_reminder")
        XCTAssertEqual(ProtocolReminder.droppedLogTag, "protocol_dropped")

        let chinese = ProtocolReminder.chineseText
        XCTAssertTrue(chinese.contains("你刚才输出的不是工具调用"), "\(chinese)")
        XCTAssertTrue(chinese.contains("请确认"), "\(chinese)")
        XCTAssertTrue(chinese.contains("这段话要发给用户吗"), "\(chinese)")
        XCTAssertTrue(chinese.contains("send_message(text)"), "要发 → send_message：\(chinese)")
        XCTAssertTrue(chinese.contains("stay_silent"), "不想发 → stay_silent：\(chinese)")
        XCTAssertTrue(chinese.contains("只是思考"), "只是思考 → 不要输出正文：\(chinese)")
        XCTAssertTrue(chinese.contains("不要输出正文"), "\(chinese)")

        let english = ProtocolReminder.englishText
        XCTAssertTrue(english.contains("not a tool call"), "\(english)")
        XCTAssertTrue(english.contains("send_message(text)"), "\(english)")
        XCTAssertTrue(english.contains("stay_silent"), "\(english)")
        XCTAssertTrue(english.contains("thinking"), "\(english)")

        XCTAssertEqual(
            ProtocolReminder.text(for: PersonaEntity(id: "p-1", name: "林深", languageTag: "zh")),
            ProtocolReminder.chineseText
        )
        XCTAssertEqual(
            ProtocolReminder.text(for: PersonaEntity(id: "p-1", name: "Lin", languageTag: "en")),
            ProtocolReminder.englishText
        )
    }
}

// MARK: - 测试替身

/// 假工具执行器：按预设结果依次返回（与 `ExperienceChatTests` 里的同名替身同形，
/// 但那一个是 `private` 在别的文件里，这里要自己一份）。
private final class ProtocolReminderToolStub: ToolRunning {
    var results: [ToolResult] = []
    private(set) var callCount = 0

    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        callCount += 1
        if results.isEmpty {
            return ToolResult(invocation: invocation, ok: true, summary: "做完了", contentForModel: "结果")
        }
        return results.removeFirst()
    }
}
