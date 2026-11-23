import Foundation
import XCTest
@testable import YapKit

/// 聊天的「节奏」：消息已读、`defer_reply`、睡着时的三种沉默（§2.3、§四、§六）。
///
/// 这些行为有一个共同点：**界面上什么都不会发生**（没有助手气泡），但库里必须留下正确的痕迹。
/// 所以这一组测试全部断言「库里的东西」而不是「界面事件」：
/// - 用户消息的 `readAt` 有没有写；
/// - 有没有多出一条助手消息（`defer_reply` 时绝对不许有）；
/// - 有没有落下一条到点的 `agent_intents`；
/// - 被叫醒之后 `persona.mood` 与 `agent_ticks` 有没有变化。
final class ChatRhythmTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var apiKeys: ApiKeyStore!
    private var ticks: AgentTickStore!
    private var intents: AgentIntentSupport!
    private var mood: MoodRepository!
    private var rhythm: ChatRhythmService!
    private var service: ChatService!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"
    private let keychain = InMemoryKeychain()
    private let tools = SpeakingToolRunner()

    /// 固定时钟：当地 01:00（正好落在默认作息 23:30–08:00 里）。
    private var nightNow: Int64 {
        var calendar = ServiceTime.calendar()
        let date = calendar.date(from: DateComponents(
            year: 2026, month: 11, day: 14, hour: 1, minute: 0
        ))!
        _ = calendar
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    /// 白天 15:00：不睡觉。
    private var dayNow: Int64 { nightNow + 14 * 3_600_000 }

    private var clock: Int64 = 0

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings())
        apiKeys = ApiKeyStore(backend: keychain)
        ticks = AgentTickStore(database: db.stores.database)
        intents = AgentIntentSupport(stores: db.stores, ticks: ticks, now: { [unowned self] in self.clock })
        mood = MoodRepository(stores: db.stores, now: { [unowned self] in self.clock })
        rhythm = ChatRhythmService(
            stores: db.stores, mood: mood, ticks: ticks, intents: intents,
            now: { [unowned self] in self.clock }
        )
        clock = dayNow
        try seed()
        service = makeService()
    }

    override func tearDownWithError() throws {
        service = nil
        rhythm = nil
        mood = nil
        intents = nil
        ticks = nil
        apiKeys = nil
        settings = nil
        db.cleanUp()
        db = nil
        StubURLProtocol.reset()
        try super.tearDownWithError()
    }

    private func makeService() -> ChatService {
        ChatService(
            stores: db.stores,
            apiKeys: apiKeys,
            settings: settings,
            client: OpenAICompatibleClient(
                session: StubURLProtocol.makeSession(),
                gate: LlmConcurrencyGate()
            ),
            tools: tools,
            background: NoBackgroundTasks(),
            mood: mood,
            rhythm: rhythm,
            now: { [unowned self] in self.clock }
        )
    }

    private func seed() throws {
        try db.stores.personas.upsert(PersonaEntity(
            id: personaId, name: "林岸", prompt: "你叫林岸，住在海边。",
            version: 1, isDefault: true, updatedAt: 1,
            sleepEnabled: true, wakeThreshold: 3
        ))
        try db.stores.apiConfigs.upsert(ApiConfigEntity(
            id: configId, name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", updatedAt: 1
        ))
        try db.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, apiConfigId: configId,
            title: "与 林岸 的对话", createdAt: 1, updatedAt: 1, lastPersonaVersion: 1
        ))
        try apiKeys.setAPIKey("sk-test", for: configId)
    }


    // MARK: - 0.15 协议夹具

    /// `send_message` 的协议块（与生产协议同形）。
    static func sendMessageBlock(_ text: String) -> String {
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        return "```yap-tool\n{\"name\":\"send_message\",\"arguments\":{\"text\":\"\(escaped)\"}}\n```"
    }

    /// 模型按协议说话：一段 `send_message` + 工具回灌后的空白收尾。
    private func enqueueSpoken(_ text: String) {
        StubURLProtocol.enqueue(.sse(textDeltas: [Self.sendMessageBlock(text)]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
    }

    /// 只写正文的模型会被确认两次；第三次仍然只写正文 → 整轮丢弃（不落库）。
    private func enqueueBodyOnlyDrop(_ text: String) {
        for _ in 0..<3 { StubURLProtocol.enqueue(.sse(textDeltas: [text])) }
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func messages() throws -> [MessageEntity] {
        try db.stores.messages.bySession(sessionId)
    }

    // MARK: - 已读

    /// 模型看到用户消息的那一刻要写 `readAt`（§2.3）。
    func testModelSeeingMessageMarksItRead() async throws {
        enqueueSpoken("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let all = try messages()
        let user = try XCTUnwrap(all.first { $0.role == .user })
        XCTAssertNotNil(user.readAt, "模型看到了就该写已读")
        // 助手消息不需要 readAt（那是「他读过你的话」，不是「你读过他的话」）。
        XCTAssertNil(try XCTUnwrap(all.first { $0.role == .assistant }).readAt)
    }

    // MARK: - defer_reply

    /// 模型用 `defer_reply`：不写助手消息、用户消息已读、落一条到点的念头（§四）。
    func testDeferReplyWritesNoAssistantMessageButPlansAnIntent() async throws {
        clock = dayNow
        let block = """
        ```yap-tool
        {"name":"defer_reply","arguments":{"minutes":90,"reason":"现在不想说话"}}
        ```
        """
        StubURLProtocol.enqueue(.sse(textDeltas: [block]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        // 事件：只有「这次不回」，没有 done（没有助手消息可报）。
        XCTAssertTrue(events.contains { if case .noReply = $0 { return true } else { return false } })
        XCTAssertFalse(events.contains { if case .done = $0 { return true } else { return false } })

        // 库里：只有用户那条消息，且已读。
        let all = try messages()
        XCTAssertEqual(all.count, 1, "不许写一条空的助手消息")
        let user = try XCTUnwrap(all.first)
        XCTAssertEqual(user.role, .user)
        XCTAssertNotNil(user.readAt)

        // 念头：REACH_OUT，最早 90 分钟后。
        let planned = try db.stores.agentIntents.byPersona(personaId)
        XCTAssertEqual(planned.count, 1)
        let intent = try XCTUnwrap(planned.first)
        XCTAssertEqual(intent.kind, .reachOut)
        XCTAssertEqual(intent.status, .pending)
        XCTAssertEqual(intent.earliestAt, clock + 90 * 60_000)
        XCTAssertEqual(intent.note, "现在不想说话")
        XCTAssertEqual(intent.sessionId, sessionId)
        XCTAssertGreaterThan(intent.latestAt, intent.earliestAt)
    }

    /// `minutes` 缺省 45、夹在 5–720（§四）。
    func testDeferReplyClampsMinutes() async throws {
        clock = dayNow
        let cases: [(String, Int)] = [
            (#"{"name":"defer_reply","arguments":{"reason":"懒得说"}}"#, 45),
            (#"{"name":"defer_reply","arguments":{"minutes":1}}"#, 5),
            (#"{"name":"defer_reply","arguments":{"minutes":9999}}"#, 720)
        ]
        for (payload, expected) in cases {
            try db.stores.agentIntents.deleteAll()
            try db.stores.messages.deleteAll()
            StubURLProtocol.enqueue(.sse(textDeltas: ["```yap-tool\n\(payload)\n```"]))
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
            let intent = try XCTUnwrap(try db.stores.agentIntents.byPersona(personaId).first)
            XCTAssertEqual(intent.earliestAt, clock + Int64(expected) * 60_000, "payload: \(payload)")
            // reason 只留 80 字（它只进日志与「他最近在想什么」）。
            XCTAssertLessThanOrEqual(intent.note.count, ChatRhythmService.deferReasonMax)
        }
    }

    // MARK: - 睡觉时的三种沉默

    /// 免打扰：不回、不留已读、没有念头（§6.2）。
    func testSleepingWithDndLeavesMessageUnreadAndSaysNothing() async throws {
        clock = nightNow
        var persona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        persona.dndOnSleep = true
        try db.stores.personas.upsert(persona)

        let events = try await collect(service.send(sessionId: sessionId, text: "睡着了吗"))
        XCTAssertTrue(events.contains { if case .noReply(.sleepingDoNotDisturb) = $0 { return true } else { return false } })
        let user = try XCTUnwrap(try messages().first)
        XCTAssertNil(user.readAt, "免打扰时连「已读」都不该留")
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "免打扰时不该调模型")
    }

    /// 没开免打扰、消息数没到阈值：不回但已读（§6.2）。
    func testSleepingBelowWakeThresholdMarksReadOnly() async throws {
        clock = nightNow
        let events = try await collect(service.send(sessionId: sessionId, text: "还在睡吗"))
        XCTAssertTrue(events.contains { if case .noReply(.sleepingWaiting) = $0 { return true } else { return false } })
        let user = try XCTUnwrap(try messages().first)
        XCTAssertNotNil(user.readAt)
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    /// 连着发满阈值：被叫醒 → 正常回复 + 心情拉回 + 留一句「被你叫醒了」（§6.2）。
    func testWakingThresholdWakesHimUpAndEasesMood() async throws {
        clock = nightNow
        // 先放一条低落的心情：被叫醒后应当回到「平静」。
        _ = mood.setMood(personaId: personaId, label: "低落", intensity: 4, now: clock)
        // 阈值是 3：先发两条（不够），第三条才叫醒。
        for text in ["喂", "醒醒"] {
            _ = try await collect(service.send(sessionId: sessionId, text: text))
        }
        XCTAssertEqual(try messages().filter { $0.role == .assistant }.count, 0, "没到阈值不该回")

        enqueueSpoken("……几点了")
        let events = try await collect(service.send(sessionId: sessionId, text: "起来啦"))
        XCTAssertFalse(events.contains { if case .noReply = $0 { return true } else { return false } })

        let assistant = try XCTUnwrap(try messages().last { $0.role == .assistant })
        XCTAssertEqual(assistant.content, "……几点了")
        // 心情：负面 → 平静；强度只减一档（4 → 3），不额外归 1（§6.2 字面语义，与 Android 一致）。
        let persona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        XCTAssertEqual(persona.mood, MoodLexicon.BASELINE)
        XCTAssertEqual(persona.moodIntensity, 3)
        // tick：留一句「被你叫醒了」，这是「他被吵醒」唯一的证据。
        let tick = try XCTUnwrap(ticks.latest(personaId: personaId))
        XCTAssertTrue(tick.outcome.contains("被你叫醒了"))
    }

    /// 用户消息被叫醒那一条也要标记已读（唤醒本身就是「看到了」）。
    func testWokenUpTurnStillMarksTheMessageRead() async throws {
        clock = nightNow
        for text in ["喂", "醒醒"] {
            _ = try await collect(service.send(sessionId: sessionId, text: text))
        }
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "起来啦"))
        let userMessages = try messages().filter { $0.role == .user }
        XCTAssertEqual(userMessages.count, 3)
        XCTAssertTrue(userMessages.allSatisfy { $0.readAt != nil })
    }
}


/// 0.15：只会「按协议说话」的假执行器（没有别的工具）。
private final class SpeakingToolRunner: ToolRunning {
    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        guard invocation.name == ToolCatalog.sendMessage else {
            return ToolResult(invocation: invocation, ok: true, summary: "做完了", contentForModel: "结果")
        }
        var result = ToolResult(
            invocation: invocation, ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        result.outboundText = invocation.argument("text")
        return result
    }
}
// MARK: - 假流

private extension StubURLProtocol.Stub {
    /// 「只有文字增量 + [DONE]」的最简流（与 ChatServiceTests 里那份同形，
    /// 但它是 fileprivate 的，所以这里各留一份）。
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
