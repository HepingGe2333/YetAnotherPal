import Foundation
import XCTest
@testable import YapKit

/// 「这一轮可以不回」：`stay_silent`（`spec/mood/README.md` §4）。
///
/// 与 `ChatRhythmTests`（`defer_reply`）的分工是这一组要钉住的事：
/// - 不产生 assistant 消息、用户消息只标已读；
/// - **不落念头**（`defer_reply` 会落一条到点的 `agent_intents`）；
/// - 连续最多 2 轮，第 3 轮会被拒绝并逼他说话；
/// - 工具描述把这些条件写给模型看。
final class StaySilentTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var apiKeys: ApiKeyStore!
    private var ticks: AgentTickStore!
    private var intents: AgentIntentSupport!
    private var mood: MoodRepository!
    private var ledger: InMemoryStaySilentLedger!
    private var rhythm: ChatRhythmService!
    private var service: ChatService!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"
    private let keychain = InMemoryKeychain()

    /// 白天 15:00：不睡觉，免得睡眠判定把这一轮的语义搅进来。
    private var dayNow: Int64 {
        var components = DateComponents()
        components.year = 2026
        components.month = 11
        components.day = 14
        components.hour = 15
        components.minute = 0
        return Int64((Calendar.current.date(from: components)!.timeIntervalSince1970 * 1000).rounded())
    }

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
        ledger = InMemoryStaySilentLedger()
        rhythm = ChatRhythmService(
            stores: db.stores, mood: mood, ticks: ticks, intents: intents,
            silentLedger: ledger, now: { [unowned self] in self.clock }
        )
        clock = dayNow
        try seed()
        service = makeService()
    }

    override func tearDownWithError() throws {
        service = nil
        rhythm = nil
        ledger = nil
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
            sleepEnabled: false
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

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func messages() throws -> [MessageEntity] {
        try db.stores.messages.bySession(sessionId)
    }

    private func silentBlock(_ reason: String = "累了") -> String {
        """
        ```yap-tool
        {"name":"stay_silent","arguments":{"reason":"\(reason)"}}
        ```
        """
    }

    // MARK: - 不落消息、只标已读、不留念头

    func testStaySilentWritesNoMessageButMarksRead() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock()]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertTrue(events.contains { if case .noReply(.stayedSilent) = $0 { return true } else { return false } })
        XCTAssertFalse(events.contains { if case .done = $0 { return true } else { return false } })

        let all = try messages()
        XCTAssertEqual(1, all.count, "不许写一条空的助手消息")
        XCTAssertEqual(.user, all[0].role)
        XCTAssertNotNil(all[0].readAt, "看到了就是已读（规范 §4）")
        XCTAssertTrue(try db.stores.agentIntents.byPersona(personaId).isEmpty,
                      "stay_silent 不留念头（这是它与 defer_reply 的区别）")
    }

    func testDeferReplyStillLeavesAnIntent() async throws {
        // 对照组：同一个语义场景，`defer_reply` 必须落一条到点的念头（不能被 stay_silent 的行为带跑）。
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n{\"name\":\"defer_reply\",\"arguments\":{\"minutes\":30}}\n```"
        ]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertTrue(events.contains { if case .noReply(.deferred) = $0 { return true } else { return false } })
        XCTAssertEqual(1, try db.stores.agentIntents.byPersona(personaId).count)
        XCTAssertEqual(1, try messages().count)
    }

    // MARK: - 连续上限（≤2 轮）

    func testThirdSilentRoundIsRefusedAndHeHasToSpeak() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("第一轮")]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("第二轮")]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在干嘛"))
        XCTAssertEqual(2, try messages().filter { $0.role == .user }.count)
        XCTAssertEqual(0, try messages().filter { $0.role == .assistant }.count,
                       "前两轮只标已读，不该有消息")

        // 第三轮：模型还想沉默 → 服务拒绝，回灌「这轮得说句话」，再生成一次。
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("第三轮")]))
        // 「嗯」排两份：被逼出来的这句仍然是「只写正文」，0.15.7 起会先被协议提醒一次。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]), .sse(textDeltas: ["嗯"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "你怎么了"))
        XCTAssertFalse(events.contains { if case .noReply = $0 { return true } else { return false } },
                       "第三轮不能再沉默")
        let assistants = try messages().filter { $0.role == .assistant }
        XCTAssertEqual(1, assistants.count)
        XCTAssertEqual("嗯", assistants[0].content)
        // 说了话 → 连续沉默清零（`ChatService` 落库后调 `handleReplied`），下次又能沉默两轮。
        XCTAssertEqual(0, ledger.consecutiveRounds(personaId: personaId, now: clock))
        XCTAssertTrue(ledger.canStaySilent(personaId: personaId, now: clock))
    }

    func testLedgerCountsConsecutiveRoundsWithinWindow() {
        let inMemory = InMemoryStaySilentLedger()
        XCTAssertTrue(inMemory.canStaySilent(personaId: "p", now: dayNow))
        inMemory.recordSilent(personaId: "p", at: dayNow)
        XCTAssertEqual(1, inMemory.consecutiveRounds(personaId: "p", now: dayNow))
        XCTAssertTrue(inMemory.canStaySilent(personaId: "p", now: dayNow))
        inMemory.recordSilent(personaId: "p", at: dayNow + 60_000)
        XCTAssertEqual(2, inMemory.consecutiveRounds(personaId: "p", now: dayNow + 60_000))
        XCTAssertFalse(inMemory.canStaySilent(personaId: "p", now: dayNow + 60_000))
        // 隔了很久再沉默：不该被上一次的连击惩罚（窗口 2 小时）。
        XCTAssertTrue(inMemory.canStaySilent(
            personaId: "p", now: dayNow + 60_000 + StaySilent.streakWindowMillis + 1
        ))
        inMemory.reset(personaId: "p")
        XCTAssertEqual(0, inMemory.consecutiveRounds(personaId: "p", now: dayNow))
    }

    // MARK: - 工具描述（使用条件写给模型看）

    func testToolDescriptionExplainsWhenToStaySilent() throws {
        let spec = try XCTUnwrap(ToolCatalog.byName("stay_silent"))
        XCTAssertTrue(spec.description.contains("别连续两轮都用"))
        XCTAssertTrue(spec.description.contains("超过 2 轮会被强制要求回一句"))
        XCTAssertTrue(spec.description.contains("defer_reply"), "要与 defer_reply 区分开")
        XCTAssertTrue(spec.description.contains("不产生任何消息"))
        XCTAssertFalse(spec.sideEffect.isEmpty)
        XCTAssertTrue(spec.sideEffect.contains("已读"))
        // 无必填参数（规范 §4：无参数或 reason 可选）。
        let data = try XCTUnwrap(spec.parametersJson.data(using: .utf8))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNil(root["required"])
        // 能力段里由注册表生成，模型看得到。
        XCTAssertTrue(ToolCatalog.capabilityPrompt().contains("- stay_silent(reason?)："))
        // 界面上不留占位气泡：标题只用于留痕，且有中文。
        XCTAssertEqual("这轮没说话", ToolProtocol.title(forTool: ToolCatalog.staySilent))
    }

    func testExecutorKnowsStaySilent() async throws {
        // 聊天链路先拦截，但执行器也必须认识它（注册表与执行器一一对应，否则能力段在说谎）。
        let memoryRepository = MemoryRepository(stores: db.stores)
        let executor = ToolExecutor(
            stores: db.stores,
            apiKeys: apiKeys,
            memory: memoryRepository,
            recallService: RecallService(stores: db.stores, memoryRepository: memoryRepository),
            mood: mood,
            personaCards: PersonaCardRepository(stores: db.stores),
            intents: intents,
            agentCore: AgentCoreClientFactory.makeNative()
        )
        let persona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        let result = await executor.run(
            ToolInvocation(id: "call-1", name: "stay_silent", argumentsJson: "{}"),
            persona: persona,
            sessionId: sessionId
        )
        XCTAssertTrue(result.ok)
        XCTAssertFalse(result.summary.isEmpty)
    }
}

// MARK: - 假流（与 ChatRhythmTests 同一份最简 SSE）

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
