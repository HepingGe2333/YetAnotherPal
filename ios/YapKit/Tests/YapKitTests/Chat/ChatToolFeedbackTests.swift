import Foundation
import XCTest
@testable import YapKit

/// 聊天链路里那两处「交接」：工具结果回灌 + 主动唤醒提案落库。
///
/// 为什么单独一组：这两件事的边界不在聊天服务内部——
/// 工具怎么执行在 `ToolExecutor`（有它自己的测试），规则怎么写库在 `ProactiveRuleService`；
/// 这里只钉「聊天服务把调用交出去、把结果原样回灌、把提案变成一行规则」。
final class ChatToolFeedbackTests: XCTestCase {

    private var temp: TempDatabase!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var service: ChatService!
    private var tools: RecordingToolRunner!
    private var background: RecordingChatBackground!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        temp = try TempDatabase()
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        settings = InMemorySettings(AppSettings())
        tools = RecordingToolRunner()
        background = RecordingChatBackground()
        var persona = TestData.persona(id: personaId)
        persona.sleepEnabled = false      // 这条链路与「他在睡觉」无关
        try temp.stores.personas.upsert(persona)
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", updatedAt: 1
        ))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        try apiKeys.setAPIKey("sk-test", for: "config-1")
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 工具结果回灌

    func testToolResultIsFedBackWithExecutorTraceTitle() async throws {
        tools.result = ToolResult(
            invocation: ToolInvocation(id: "call_block_1", name: "remember",
                                       argumentsJson: #"{"content":"对方怕冷","drawer":"USER","importance":3}"#),
            ok: true,
            summary: "对方怕冷",
            contentForModel: "已写进长期记忆：「对方怕冷」。",
            traceTitle: "记住了",
            traceDetail: "对方怕冷"
        )
        let block = #"{"name":"remember","arguments":{"content":"对方怕冷","drawer":"USER","importance":3}}"#
        StubURLProtocol.enqueue(
            Self.sse(["嗯，我记一下。\n```yap-tool\n\(block)\n```"]),
            Self.sse(["记住了，天冷的时候我会提醒你。"])
        )
        // 0.15.7 工具协议提醒：最后一次「只写正文」会被提醒一次、再请求一次，
        // 同一段正文再排一份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(Self.sse(["记住了，天冷的时候我会提醒你。"]))
        service = makeService()

        var events: [ChatEvent] = []
        for try await event in service.send(sessionId: sessionId, text: "天冷的时候提醒我一下") {
            events.append(event)
        }

        XCTAssertEqual(tools.callCount, 1)
        XCTAssertEqual(tools.lastName, "remember")
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "记住了，天冷的时候我会提醒你。")
        // 痕迹标题来自执行器（Android 的 ToolExecutor 也按结果给标题）。
        XCTAssertEqual(done.toolTraces.first?.title, "记住了")
        XCTAssertEqual(done.toolTraces.first?.subtitle, "对方怕冷")
        XCTAssertEqual(ToolTraceCodec.decode(done.message.toolTraceJson), done.toolTraces)

        // 回灌给模型的形状：`【工具结果】` + 摘要 + 结果 + 「接着把话说完」。
        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 3,
                       "工具一轮 + 回灌一轮 + 提醒后的重试一轮")
        let second = try JSONSerialization.jsonObject(with: StubURLProtocol.recordedBodies[1]) as? [String: Any]
        let messages = try XCTUnwrap(second?["messages"] as? [[String: Any]])
        let contents = messages.compactMap { $0["content"] as? String }
        XCTAssertTrue(contents.contains { $0.contains("【工具结果】remember") })
        XCTAssertTrue(contents.contains { $0.contains("已写进长期记忆：「对方怕冷」。") })
        XCTAssertTrue(contents.contains { $0.contains("现在接着把话说完") })
        // 协议块绝不能出现在推给界面的文字里。
        XCTAssertFalse(visibleText(events).contains("yap-tool"))
    }

    func testFailedToolKeepsChineseReason() async throws {
        tools.result = ToolResult(
            invocation: ToolInvocation(name: "web_search", argumentsJson: #"{"query":"天气"}"#),
            ok: false,
            summary: "这个人格还没有开启联网",
            contentForModel: "这次调用没成功：这个人格还没有开启联网。用你已经知道的东西继续，不要向对方解释工具细节。",
            errorMessage: "这个人格还没有开启联网",
            traceTitle: "联网搜索"
        )
        let block = #"{"name":"web_search","arguments":{"query":"天气"}}"#
        StubURLProtocol.enqueue(
            Self.sse(["我去看看。\n```yap-tool\n\(block)\n```"]),
            Self.sse(["我这边查不了，凭印象说吧。"])
        )
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(
            Self.sse(["我去看看。\n```yap-tool\n\(block)\n```"]),
            Self.sse(["我这边查不了，凭印象说吧。"])
        )
        service = makeService()

        var events: [ChatEvent] = []
        for try await event in service.send(sessionId: sessionId, text: "查一下天气") {
            events.append(event)
        }
        let done = try XCTUnwrap(doneEvent(events))
        let trace = try XCTUnwrap(done.toolTraces.first)
        XCTAssertFalse(trace.ok)
        XCTAssertEqual(trace.subtitle, "这个人格还没有开启联网")
        let second = try JSONSerialization.jsonObject(with: StubURLProtocol.recordedBodies[1]) as? [String: Any]
        let messages = try XCTUnwrap(second?["messages"] as? [[String: Any]])
        let contents = messages.compactMap { $0["content"] as? String }
        XCTAssertTrue(contents.contains { $0.contains("失败：这个人格还没有开启联网") })
    }

    // MARK: - 主动唤醒规则落库

    func testWakeProposalIsPersistedToProactiveRules() async throws {
        var persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        persona.sleepEnabled = false
        try temp.stores.personas.upsert(persona)
        let rules = ProactiveRuleService(stores: temp.stores, now: { 1_794_500_000_000 })
        service = makeService(proactive: rules)

        let reply = """
        好呀，那我们就这样约定。每天到了那个时间，我会先来找你。

        ```yap-wake
        {"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"轻声问他今天过得怎么样"}
        ```
        """
        StubURLProtocol.enqueue(Self.sse([reply]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(Self.sse([reply]))
        var events: [ChatEvent] = []
        for try await event in service.send(sessionId: sessionId, text: "每天晚上十点半来问我一句") {
            events.append(event)
        }
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertFalse(done.message.content.contains("yap-wake"), "协议块不能给用户看")
        XCTAssertTrue(done.proactiveProposalJson?.contains("\"daily\"") == true)

        let stored = try XCTUnwrap(try temp.stores.proactiveRules.byPersona(personaId).first)
        XCTAssertEqual(stored.kind, .daily)
        XCTAssertEqual(stored.timeOfDayMinutes, 22 * 60 + 30)
        XCTAssertEqual(stored.note, "每晚睡前问候")
        XCTAssertEqual(stored.hint, "轻声问他今天过得怎么样")
        XCTAssertTrue(stored.enabled, "他自己安排的节奏直接生效")
        XCTAssertEqual(stored.source, .ai)
        XCTAssertGreaterThan(stored.nextTriggerAt, 1_794_500_000_000)
        XCTAssertEqual(stored.sessionId, sessionId)
    }

    func testLegacyWakeBlockLanguageStillParsed() async throws {
        let rules = ProactiveRuleService(stores: temp.stores)
        service = makeService(proactive: rules)
        let reply = """
        好。

        ```myfriend-proactive
        {"kind":"interval","intervalHours":3,"note":"每三小时问一句"}
        ```
        """
        StubURLProtocol.enqueue(Self.sse([reply]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(Self.sse([reply]))
        for try await _ in service.send(sessionId: sessionId, text: "每隔几小时来陪我一下") {}
        let stored = try XCTUnwrap(try temp.stores.proactiveRules.byPersona(personaId).first)
        XCTAssertEqual(stored.kind, .interval)
        XCTAssertEqual(stored.intervalMinutes, 180)
    }

    // MARK: - 人设卡进提示词

    func testPersonaCardIsRenderedIntoSystemPrompt() async throws {
        var persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        persona.personaCardJson = PersonaCardCodec.encode(PersonaCard(
            identity: PersonaIdentity(name: "林岸", role: "海边书店的店员"),
            voice: PersonaVoice(pace: "慢，偶尔停顿"),
            traits: [PersonaTrait(name: "慢热", strength: 0.8)],
            bond: "你们认识很久了，他知道你不爱被追问。"
        ))
        try temp.stores.personas.upsert(persona)
        let cards = PersonaCardRepository(stores: temp.stores)
        service = makeService(personaText: { cards.systemText($0) })

        StubURLProtocol.enqueue(Self.sse(["在的。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(Self.sse(["在的。"]))
        for try await _ in service.send(sessionId: sessionId, text: "在吗") {}

        let body = try JSONSerialization.jsonObject(with: StubURLProtocol.recordedBodies[0]) as? [String: Any]
        let messages = try XCTUnwrap(body?["messages"] as? [[String: Any]])
        let system = try XCTUnwrap(messages.first { $0["role"] as? String == "system" }?["content"] as? String)
        XCTAssertTrue(system.contains("海边书店的店员"), "人设卡要进提示词")
        XCTAssertTrue(system.contains("慢热"))
        XCTAssertTrue(system.contains("他知道你不爱被追问"))
    }

    func testEmptyCardFallsBackToWrittenPrompt() async throws {
        let cards = PersonaCardRepository(stores: temp.stores)
        service = makeService(personaText: { cards.systemText($0) })
        StubURLProtocol.enqueue(Self.sse(["在的。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(Self.sse(["在的。"]))
        for try await _ in service.send(sessionId: sessionId, text: "在吗") {}
        let body = try JSONSerialization.jsonObject(with: StubURLProtocol.recordedBodies[0]) as? [String: Any]
        let messages = try XCTUnwrap(body?["messages"] as? [[String: Any]])
        let system = try XCTUnwrap(messages.first { $0["role"] as? String == "system" }?["content"] as? String)
        XCTAssertTrue(system.contains("你叫林岸，住在海边，说话慢一点。"), "没填卡就走用户手写的 prompt")
    }

    // MARK: - 夹具

    private func makeService(
        proactive: ProactiveRuleWriting? = nil,
        personaText: ((PersonaEntity) -> String)? = nil
    ) -> ChatService {
        ChatService(
            stores: temp.stores,
            apiKeys: apiKeys,
            settings: settings,
            client: OpenAICompatibleClient(
                session: StubURLProtocol.makeSession(),
                usage: UsageRecorder(stores: temp.stores),
                gate: LlmConcurrencyGate()
            ),
            tools: tools,
            background: background,
            proactive: proactive,
            personaText: personaText
        )
    }

    private func doneEvent(_ events: [ChatEvent]) -> ChatCompletion? {
        for event in events {
            if case .done(let completion) = event { return completion }
        }
        return nil
    }

    private func visibleText(_ events: [ChatEvent]) -> String {
        var text = ""
        for event in events {
            if case .delta(let piece) = event { text = piece }
        }
        return text
    }

    /// 与 `ChatServiceTests` 里同名工具一致：一条 SSE 里放若干个正文分片。
    static func sse(_ deltas: [String]) -> StubURLProtocol.Stub {
        var text = ""
        for delta in deltas {
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

// MARK: - 假执行器 / 假后台

private final class RecordingToolRunner: ToolRunning {
    var result: ToolResult?
    private(set) var callCount = 0
    private(set) var lastName: String?

    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        callCount += 1
        lastName = invocation.name
        if let result { return result }
        return ToolResult(invocation: invocation, ok: true, summary: "做完了", contentForModel: "结果")
    }
}

private final class RecordingChatBackground: ChatBackgroundTasks {
    private(set) var compressionCalls: [String] = []
    private(set) var memoryCalls: [String] = []

    func memoryExtractionNeeded(sessionId: String, personaId: String) { memoryCalls.append(sessionId) }
    func historyCompressionNeeded(sessionId: String) { compressionCalls.append(sessionId) }
}
