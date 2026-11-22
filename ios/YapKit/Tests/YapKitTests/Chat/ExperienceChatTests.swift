import Foundation
import XCTest
@testable import YapKit

/// 0.14 聊天链路的体验规则：拉黑、连发、打字延迟、备忘录/念头注入。
///
/// 与 `ChatServiceTests` 一样用「真 URLSession（假流式服务端）+ 真 SQLite」，
/// 区别只在这份用例关心的是**节奏与可见性**：几条消息、隔多久、哪些进上下文。
final class ExperienceChatTests: XCTestCase {

    private var directoryURL: URL!
    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var keychain: InMemoryKeychain!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var recorder: UsageRecorder!
    private var tools: ToolStub!
    private var sleeps: SleepRecorder!
    private var service: ChatService!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-experience-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        databaseURL = directoryURL.appendingPathComponent("db.sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        keychain = InMemoryKeychain()
        apiKeys = ApiKeyStore(backend: keychain)
        settings = InMemorySettings(AppSettings())
        recorder = UsageRecorder(stores: stores)
        tools = ToolStub()
        sleeps = SleepRecorder()
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
            sleep: { nanos in self.sleeps.record(nanos) },
            agentCore: NativeAgentCoreClient(),
            now: { self.now }
        )
    }

    // MARK: - 夹具

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

    private func blockPersona(hours: Int = 3, now: Int64? = nil) throws {
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.blockedUntil = BlockPolicy.expiry(hours: hours, now: now ?? self.now)
        persona.blockReason = "想静静"
        try stores.personas.upsert(persona)
    }

    private func unblockPersona() throws {
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.blockedUntil = nil
        persona.blockReason = nil
        try stores.personas.upsert(persona)
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func storedMessages() throws -> [MessageEntity] {
        try stores.messages.bySession(sessionId)
    }

    private func appended(_ events: [ChatEvent]) -> [MessageEntity] {
        events.compactMap { if case .appended(let message) = $0 { return message } else { return nil } }
    }

    private func doneEvent(_ events: [ChatEvent]) -> ChatCompletion? {
        for event in events {
            if case .done(let completion) = event { return completion }
        }
        return nil
    }

    private func lastRequestMessages() throws -> [[String: Any]] {
        let body = try XCTUnwrap(StubURLProtocol.recordedBodies.last)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        return (json["messages"] as? [[String: Any]]) ?? []
    }

    // MARK: - 拉黑：发消息不生成、消息带标记

    func testSendWhileBlockedPersistsBlockedMessageAndSkipsGeneration() async throws {
        try blockPersona()
        guard case .started(let stream) = service.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("消息仍然要落库（只是不生成）")
        }
        let events = try await collect(stream)

        XCTAssertTrue(events.isEmpty, "拉黑期这一轮不生成，界面上不该出现任何生成事件")
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "连模型都不该叫")
        let messages = try storedMessages()
        XCTAssertEqual(messages.map(\.role), [.user])
        XCTAssertTrue(messages[0].blocked, "拉黑期间的消息要落库并标 blocked（规范 §2）")
        XCTAssertTrue(
            BlockPolicy.showsBlockedIndicator(role: messages[0].role, blocked: messages[0].blocked),
            "界面据此显示红色感叹号 + 「对方已将你拉黑」"
        )
    }

    func testBlockedMessagesNeverReachModelContextEvenAfterUnblock() async throws {
        try blockPersona()
        guard case .started(let first) = service.sendChecked(sessionId: sessionId, text: "拉黑时说的秘密") else {
            return XCTFail("应当返回流")
        }
        _ = try await collect(first)

        try unblockPersona()
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        guard case .started(let second) = service.sendChecked(sessionId: sessionId, text: "现在呢") else {
            return XCTFail("应当返回流")
        }
        _ = try await collect(second)

        let wire = try lastRequestMessages()
        let texts = wire.compactMap { $0["content"] as? String }
        XCTAssertFalse(
            texts.contains { $0.contains("拉黑时说的秘密") },
            "被屏蔽的消息永久不进模型上下文（解除后也不可见）：\(texts)"
        )
        XCTAssertTrue(texts.contains { $0.contains("现在呢") })
        // 存储层也不能把屏蔽消息当成「最近对话 / 对方上次说话」。
        let dialogue = try stores.messages.recentDialogue(sessionId: sessionId, limit: 10)
        XCTAssertEqual(dialogue.map(\.content), ["现在呢", "嗯"], "只留下未被屏蔽的那一轮")
    }

    // MARK: - 连发：空行拆分、逐条独立消息、间隔

    func testPlainReplyIsSplitIntoIndependentMessagesWithGaps() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["今天有点忙。\n\n晚点找你。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["今天有点忙。\n\n晚点找你。"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["今天有点忙。", "晚点找你。"], "按空行拆成两条独立消息")
        XCTAssertEqual(appended(events).count, 2, "每一条都要通知界面（逐条出现）")
        XCTAssertEqual(doneEvent(events)?.message.content, "晚点找你。", "done 指向最后一条")
        // 一次首条延迟 + 一次连发间隔。
        XCTAssertEqual(sleeps.requests.count, 2)
        guard sleeps.requests.count == 2 else { return }
        // 0.15：延迟是「均值 ±35% 随机波动」，所以只断言区间，不再钉死旧公式的值。
        XCTAssertGreaterThanOrEqual(
            sleeps.requests[0],
            TypingDelay.nanos(seconds: ExperienceContract.TYPING_MIN_SECONDS * 0.65)
        )
        XCTAssertLessThanOrEqual(
            sleeps.requests[0],
            TypingDelay.nanos(seconds: ExperienceContract.TYPING_MAX_SECONDS * 1.35) + 1
        )
        XCTAssertGreaterThanOrEqual(
            sleeps.requests[1],
            TypingDelay.nanos(seconds: ExperienceContract.BURST_GAP_MIN_SECONDS * 0.65)
        )
        XCTAssertLessThanOrEqual(
            sleeps.requests[1],
            TypingDelay.nanos(seconds: ExperienceContract.BURST_GAP_MAX_SECONDS * 1.35) + 1
        )
    }

    func testSendMessageToolProducesIndependentMessagesAndStopsAtFour() async throws {
        // 模型连续调用 5 次 send_message：前 4 条发出去，第 5 次被拒绝（规范 §4 每轮最多 4 条）。
        tools.results = (1...5).map { index in
            var result = ToolResult(
                invocation: ToolInvocation(id: "call-\(index)", name: "send_message", argumentsJson: "{}"),
                ok: true, summary: "发了条消息", contentForModel: "发出去了"
            )
            result.outboundText = "第 \(index) 条"
            return result
        }
        // 用一次工具回合里连着调用 5 次 send_message（正文是代码块）。
        var payload = ""
        for index in 1...5 {
            payload += "```yap-tool\n"
            payload += "{\"name\":\"send_message\",\"arguments\":{\"text\":\"第 \(index) 条\"}}\n"
            payload += "```\n\n"
        }
        StubURLProtocol.enqueue(.sse(textDeltas: [payload]))
        StubURLProtocol.enqueue(.sse(textDeltas: ["说完了"]))

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["第 1 条", "第 2 条", "第 3 条", "第 4 条", "说完了"],
                       "工具连发 4 条 + 正文 1 条，但每条都是独立消息")
        XCTAssertEqual(tools.callCount, 4, "第 5 次 send_message 不该被执行")
        // 4 条连发各自通知界面一次；最后那句正文并进第 4 条（不再多一个气泡）。
        XCTAssertEqual(appended(events).count, 4)
        // 4 条连发：首条延迟 + 3 次间隔；最后那句正文在流里到达时另付一次首字延迟
        //（它随后被并进第 4 条，所以不再有第 5 个气泡）。
        XCTAssertEqual(sleeps.requests.count, 5)
    }

    // MARK: - 打字延迟

    func testFailureBeforeFirstCharacterDoesNotDelayAndLeavesNoEmptyMessage() async throws {
        StubURLProtocol.enqueue(.json(#"{"error":{"message":"invalid key"}}"#, status: 401))
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertNotNil(thrown, "失败要抛给界面（toast），而不是落一条空气泡")
        XCTAssertTrue(sleeps.requests.isEmpty, "失败不延迟（规范 §5）")
        XCTAssertEqual(try storedMessages().map(\.role), [.user], "不产生空消息（规范 §1）")
    }

    func testStopBeforeFirstCharacterLeavesNoMessageAndNoDelay() async throws {
        // 一个很慢的流：还没吐字就取消。
        StubURLProtocol.enqueue(.sse("data: {\"choices\":[{\"delta\":{\"content\":\"慢\"}}]}\n\ndata: [DONE]\n\n",
                                     splitEvery: 8, delay: 0.5))
        guard case .started(let stream) = service.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("应当返回流")
        }
        service.stop(sessionId: sessionId)
        _ = try? await collect(stream)
        XCTAssertEqual(try storedMessages().map(\.role), [.user])
        XCTAssertTrue(sleeps.requests.allSatisfy { $0 == 0 } || sleeps.requests.isEmpty)
    }

    // MARK: - 注入

    func testSessionMemoryAndMentalActivityAreInjectedIntoSystemPrompt() async throws {
        // 备忘录 + 一句刚发生的念头。
        try SessionMemoryRepository(stores: stores, now: { self.now }).applyShort(
            sessionId: sessionId, personaId: personaId,
            summary: "最近他在赶方案", userCount: 5, at: now
        )
        try MentalActivityRepository(stores: stores, now: { self.now }).record(
            personaId: personaId, sessionId: sessionId,
            thought: "他今天声音好轻", mood: MoodState(label: "平静", intensity: 2), at: now
        )
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let wire = try lastRequestMessages()
        let system = try XCTUnwrap(wire.first?["content"] as? String)
        XCTAssertTrue(system.contains("你的备忘录"))
        XCTAssertTrue(system.contains("最近他在赶方案"))
        XCTAssertTrue(system.contains("你刚才心里想："))
        XCTAssertTrue(system.contains("他今天声音好轻"))
        // 0.15 上下文 v2（state_first）：备忘录（记忆段）在「当前状态」（含「你刚才心里想」）
        // **之后**——状态是这一轮的坐标，记忆是背景。
        XCTAssertLessThan(
            try XCTUnwrap(system.range(of: "你刚才心里想：")).lowerBound,
            try XCTUnwrap(system.range(of: "你的备忘录")).lowerBound
        )
    }
}

// MARK: - 测试替身

/// 假工具执行器：按预设结果依次返回。
private final class ToolStub: ToolRunning {
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

/// 记录「要求睡多久」：用来断言打字延迟与连发间隔（不真的睡，测试才不慢）。
private final class SleepRecorder {
    private(set) var requests: [UInt64] = []

    func record(_ nanos: UInt64) {
        requests.append(nanos)
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
