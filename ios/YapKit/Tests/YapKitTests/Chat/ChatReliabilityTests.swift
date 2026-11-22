import Foundation
import XCTest
@testable import YapKit

/// 0.15 聊天链路的三条可靠性兜底（端到端：真 SQLite + 真流式客户端 + 假工具执行器）：
///
/// 1. **remember 可靠性**：用户明确说了高置信事实（过敏 / 忌口 / 生日 / 纪念日 / 重要约定）
///    而模型这一轮没调 `remember` 时，回复落库后自动补一条 `USER` 记忆；
///    模型调过不重复、同一件事不重复、日常闲聊不写。
/// 2. **stay_silent 严格语义**：模型同一轮里既写正文又调 `stay_silent` 时，
///    这一轮只沉默——不落 assistant 正文、不留工具卡片、只标已读。
/// 3. **危机安全兜底**：用户说了「不想活」而回复里没有安全锚点时，落库的正文末尾追加
///    1–2 句安全提示；回复里已经有锚点时一个字都不改。
/// 4. **危机优先于沉默**（0.15）：用户这一轮说的是危机表达时，`stay_silent` / `defer_reply`
///    不再允许「已读不回」——必须落一条看得见的助手消息（有正文用正文、没正文用安全提示），
///    并进最后一条而不是造第 5 条；非危机轮行为不变。
final class ChatReliabilityTests: XCTestCase {

    private var db: TempDatabase!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var tools: ToolStub!
    private var mood: MoodRepository!
    private var ledger: InMemoryStaySilentLedger!
    private var rhythm: ChatRhythmService!
    private var service: ChatService!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        db = try TempDatabase()
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        settings = InMemorySettings(AppSettings())
        tools = ToolStub()
        mood = MoodRepository(stores: db.stores, now: { self.now })
        ledger = InMemoryStaySilentLedger()
        let ticks = AgentTickStore(database: db.stores.database)
        let intents = AgentIntentSupport(stores: db.stores, ticks: ticks, now: { self.now })
        rhythm = ChatRhythmService(
            stores: db.stores,
            mood: mood,
            ticks: ticks,
            intents: intents,
            silentLedger: ledger,
            now: { self.now }
        )
        service = makeService()
        try seed()
    }

    override func tearDownWithError() throws {
        service = nil
        rhythm = nil
        ledger = nil
        mood = nil
        tools = nil
        settings = nil
        apiKeys = nil
        StubURLProtocol.reset()
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    /// 危机判定的第二段用**假 provider**：这组用例只关心「危机轮写了什么」，
    /// 不该让每次命中词表都真的再发一次 HTTP（那会把 StubURLProtocol 的 FIFO 队列挤歪）。
    private func makeService(
        crisis: CrisisClassifier = CrisisClassifier(provider: { _ in #"{"risk":true,"reason":"测试"}"# })
    ) -> ChatService {
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
            // 不真的睡：这组用例只关心「写了什么」，不关心打字延迟（规范 §5 有专门的测试）。
            mood: mood,
            sleep: { _ in },
            rhythm: rhythm,
            crisis: crisis,
            now: { self.now }
        )
    }

    private func seed() throws {
        try db.stores.personas.upsert(PersonaEntity(
            id: personaId, name: "林岸", prompt: "你叫林岸，住在海边。",
            version: 1, isDefault: true, updatedAt: 1, sleepEnabled: false
        ))
        try db.stores.apiConfigs.upsert(ApiConfigEntity(
            id: configId, name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", updatedAt: 1
        ))
        try db.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, apiConfigId: configId,
            title: "对话", createdAt: 1, updatedAt: 1,
            lastPersonaVersion: 1, lastMemoryExtractedCount: 0
        ))
        try apiKeys.setAPIKey("sk-test", for: configId)
    }

    // MARK: - 夹具

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func messages() throws -> [MessageEntity] {
        try db.stores.messages.bySession(sessionId)
    }

    private func storedMemories() throws -> [MemoryEntity] {
        try db.stores.memories.fetch(
            where: "personaId = ?",
            [.text(personaId)],
            orderBy: "createdAt DESC"
        )
    }

    private func eventKinds(_ events: [ChatEvent]) -> [String] {
        events.map { event in
            switch event {
            case .delta: return "delta"
            case .toolStarted: return "toolStarted"
            case .toolFinished: return "toolFinished"
            case .done: return "done"
            case .noReply: return "noReply"
            case .appended: return "appended"
            }
        }
    }

    private func silentBlock(_ reason: String = "累了") -> String {
        """
        ```yap-tool
        {"name":"stay_silent","arguments":{"reason":"\(reason)"}}
        ```
        """
    }

    // MARK: - 1. remember 可靠性

    func testAllergyMentionWithoutRememberCallWritesUserMemory() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["好，我记住了。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["好，我记住了。"]))
        let events = try await collect(service.send(
            sessionId: sessionId, text: "我对花生过敏，别给我点带坚果的菜"
        ))

        XCTAssertNotNil(
            events.first { if case .done = $0 { return true } else { return false } },
            "回复照常落库（自动记忆不能影响这一轮）"
        )
        XCTAssertEqual(0, tools.callCount, "模型这一轮没有调用任何工具")

        let memories = try storedMemories()
        XCTAssertEqual(1, memories.count, "高置信事实要自动写一条记忆")
        let memory = try XCTUnwrap(memories.first)
        XCTAssertTrue(memory.content.contains("过敏"), "内容取原句关键片段：\(memory.content)")
        XCTAssertEqual(MemoryDrawer.user.rawValue, memory.drawer, "记在「关于对方」抽屉")
        XCTAssertEqual(MemoryCategory.fact.stored, memory.category.stored)
        XCTAssertEqual(5, memory.importance, "过敏给最高重要度（4–5）")
        XCTAssertEqual(sessionId, memory.sourceSessionId, "知道这条是从哪段会话来的")
        XCTAssertNotNil(memory.sourceQuote, "留一句原话，以后能核对")
        let tags = MemoryTags.decode(memory.tags)
        XCTAssertTrue(tags.contains(HighConfidenceMemory.autoTag), "标签带 auto（与 Android 一致）")
        XCTAssertTrue(tags.contains("allergy"), "标签带类型（过敏）")

        // 用户消息 + 助手回复都在，顺序不变。
        XCTAssertEqual([.user, .assistant], try messages().map(\.role))
    }

    func testBirthdayMentionWithoutRememberCallWritesMemory() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["记下了。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["记下了。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我生日是三月十四号"))

        let memory = try XCTUnwrap(try storedMemories().first)
        XCTAssertTrue(memory.content.contains("生日"))
        XCTAssertEqual(5, memory.importance)
        XCTAssertTrue(MemoryTags.decode(memory.tags).contains("birthday"))
    }

    func testEverydayMessageWritesNoMemory() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["早点休息。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["早点休息。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "今天好累，先睡了"))

        XCTAssertTrue(try storedMemories().isEmpty, "日常闲聊不写记忆（否则记忆库会被灌满）")
    }

    func testModelRememberCallSkipsAutoMemory() async throws {
        // 第一轮：模型自己调 remember（正文是空的，只有工具块）。
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n"
                + #"{"name":"remember","arguments":{"content":"对方对花生过敏","drawer":"USER","importance":5}}"#
                + "\n```"
        ]))
        // 工具结果回灌后的第二轮：把话说完。排两份：这一轮「只写正文」，会先被协议提醒一次。
        StubURLProtocol.enqueue(.sse(textDeltas: ["记住了。"]), .sse(textDeltas: ["记住了。"]))
        let events = try await collect(service.send(
            sessionId: sessionId, text: "我对花生过敏，别给我点带坚果的菜"
        ))

        XCTAssertEqual(["remember"], tools.names, "模型确实调了 remember")
        XCTAssertTrue(try storedMemories().isEmpty,
                      "模型自己记了就不再自动补（否则同一件事写两遍）")
        XCTAssertNotNil(events.first { if case .done = $0 { return true } else { return false } })
    }

    func testRepeatedFactIsNotWrittenTwice() async throws {
        // 每一轮都排两份正文：0.15.7 起「只写正文」会先被协议提醒一次，再请求一次。
        StubURLProtocol.enqueue(.sse(textDeltas: ["好。"]), .sse(textDeltas: ["好。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我对花生过敏"))
        XCTAssertEqual(1, try storedMemories().count)

        // 同一件事再说一遍：不该多出一条一模一样的记忆。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯，我记得。"]), .sse(textDeltas: ["嗯，我记得。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我对花生过敏，真的"))

        XCTAssertEqual(1, try storedMemories().count, "重复的说法要去重")
    }

    // MARK: - 2. stay_silent 严格语义

    func testStaySilentWithBodyKeepsSilence() async throws {
        // 模型既写了正文，又在同一轮调用了 stay_silent：这一轮必须**只沉默**。
        StubURLProtocol.enqueue(.sse(textDeltas: ["我在呢。", silentBlock()]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let kinds = eventKinds(events)
        XCTAssertTrue(kinds.contains("noReply"))
        XCTAssertFalse(kinds.contains("done"), "沉默不是一轮回复")
        XCTAssertFalse(kinds.contains("toolStarted"), "stay_silent 不该留下工具卡片")
        XCTAssertFalse(kinds.contains("toolFinished"))

        // 界面上的临时正文要被抹掉：最后一条 delta 是空正文。
        let deltas: [String] = events.compactMap { event in
            if case .delta(let text) = event { return text } else { return nil }
        }
        XCTAssertTrue(deltas.contains("我在呢。"), "流式阶段模型确实先吐了正文")
        XCTAssertEqual("", deltas.last, "收到 stay_silent 后必须把这段正文抹掉")

        let stored = try messages()
        XCTAssertEqual([.user], stored.map(\.role), "绝不落 assistant 正文")
        XCTAssertNotNil(stored.first?.readAt, "只标已读")
        XCTAssertTrue(try db.stores.agentIntents.byPersona(personaId).isEmpty,
                      "stay_silent 不留念头（这是它与 defer_reply 的区别）")
    }

    func testStaySilentIsRefusedWhenMessagesWereAlreadyDelivered() async throws {
        // 这一轮已经用 `send_message` 发出去了一条：此时再收 `stay_silent` 只会变成
        // 「消息发了但装作没回」。与 Android `StaySilent.canHonor` 同一条规则：不认这次沉默。
        var sent = ToolResult(
            invocation: ToolInvocation(id: "call-1", name: "send_message", argumentsJson: "{}"),
            ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        sent.outboundText = "早点睡吧。"
        tools.results = [sent]
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n"
                + #"{"name":"send_message","arguments":{"text":"早点睡吧。"}}"#
                + "\n```"
        ]))
        // 第二轮想沉默：被拒绝，回灌一句「这一轮已经发过消息了」。
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("不想说")]))
        // 第三轮把话说完。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯，睡吧。"]))

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertFalse(eventKinds(events).contains("noReply"), "已经发过消息就不能再沉默")
        XCTAssertEqual(
            ["早点睡吧。", "嗯，睡吧。"],
            try messages().filter { $0.role == .assistant }.map(\.content)
        )
        XCTAssertTrue(ledger.canStaySilent(personaId: personaId, now: now),
                      "被拒绝的沉默不该记进「连续沉默」")
    }

    func testThirdConsecutiveSilentRoundIsRefused() async throws {
        // 前两轮沉默照旧允许（连续 ≤2 轮），第三轮被拒并逼他说一句。
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("一")]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        StubURLProtocol.enqueue(.sse(textDeltas: ["一路顺风。", silentBlock("二")]))
        let second = try await collect(service.send(sessionId: sessionId, text: "在干嘛"))
        XCTAssertTrue(eventKinds(second).contains("noReply"))
        XCTAssertNil(try messages().first { $0.role == .assistant })

        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("三")]))
        // 「嗯」排两份：被逼出来的这句仍然是「只写正文」，会先被协议提醒一次。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]), .sse(textDeltas: ["嗯"]))
        let third = try await collect(service.send(sessionId: sessionId, text: "你怎么了"))
        XCTAssertFalse(eventKinds(third).contains("noReply"), "第三轮不能再沉默")
        XCTAssertEqual(["嗯"], try messages().filter { $0.role == .assistant }.map(\.content))
    }

    // MARK: - 3. 危机安全兜底

    func testCrisisMessageWithoutAnchorGetsSafetyNet() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯。那你早点睡。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯。那你早点睡。"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "我真的不想活了"))

        let assistant = try XCTUnwrap(try messages().last { $0.role == .assistant })
        XCTAssertTrue(assistant.content.hasPrefix("嗯。那你早点睡。"), "只在末尾追加，不改写模型的话")
        XCTAssertTrue(assistant.content.contains("120") || assistant.content.contains("110"))
        XCTAssertTrue(assistant.content.contains("别一个人") || assistant.content.contains("身边有人"))
        // 落库的与界面收到的（done）必须是同一句话。
        let done = events.compactMap { event -> MessageEntity? in
            if case .done(let completion) = event { return completion.message } else { return nil }
        }.first
        XCTAssertEqual(assistant.content, done?.content)
    }

    func testCrisisMessageWithAnchorIsLeftAlone() async throws {
        let reply = "我在，别一个人待着。要不要先给家人打个电话？"
        StubURLProtocol.enqueue(.sse(textDeltas: [reply]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: [reply]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我不想活了"))

        let assistant = try XCTUnwrap(try messages().last { $0.role == .assistant })
        XCTAssertEqual(reply, assistant.content, "已经有安全锚点就一个字都不加")
    }

    func testNonCrisisMessageGetsNoSafetyNet() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["早点休息。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["早点休息。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "今天有点累"))
        let assistant = try XCTUnwrap(try messages().last { $0.role == .assistant })
        XCTAssertEqual("早点休息。", assistant.content)
    }

    func testCrisisSafetyNetAlsoCoversToolDeliveredReply() async throws {
        // 这一轮的话是 `send_message` 发出去的，且模型把工具轮数用满了（`think.rounds = 1`），
        // 最后一轮只剩下一个工具块、没有正文：安全锚点必须补在那条**真正落库的**消息上，
        // 否则用户看到的就只是一句「早点睡吧」，兜底等于没做。
        var thinkResult = ToolResult(
            invocation: ToolInvocation(id: "call-0", name: "think", argumentsJson: "{}"),
            ok: true, summary: "想了想", contentForModel: "好"
        )
        thinkResult.traceTitle = "想了想"
        var sent = ToolResult(
            invocation: ToolInvocation(id: "call-1", name: "send_message", argumentsJson: "{}"),
            ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        sent.outboundText = "早点睡吧。"
        tools.results = [thinkResult, sent]

        let first = "```yap-tool\n"
            + #"{"name":"think","arguments":{"rounds":1}}"#
            + "\n```\n\n"
            + "```yap-tool\n"
            + #"{"name":"send_message","arguments":{"text":"早点睡吧。"}}"#
            + "\n```"
        StubURLProtocol.enqueue(.sse(textDeltas: [first]))
        // 第二轮回的是一个**只有工具块**的响应：轮数已用满，服务不再执行它，这一轮到此为止。
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n"
                + #"{"name":"send_message","arguments":{"text":"再说一句"}}"#
                + "\n```"
        ]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我真的不想活了"))

        let assistants = try messages().filter { $0.role == .assistant }
        XCTAssertEqual(1, assistants.count, "只有工具真发出去的那一条，不额外造气泡")
        let only = try XCTUnwrap(assistants.first)
        XCTAssertTrue(only.content.hasPrefix("早点睡吧。"))
        XCTAssertTrue(only.content.contains("120") || only.content.contains("110"),
                      "工具发出去的回复同样要补安全锚点")
        XCTAssertEqual(["think", "send_message"], tools.names, "第二轮的工具块不该被执行")
    }

    // MARK: - 3b. 危机两段式（0.15）：词表第一段 + 模型复核

    /// 模型复核说「不是真实危机」→ **不进入兜底**：正文一个字都不改。
    func testClassifierRiskFalseSkipsSafetyNet() async throws {
        service = makeService(crisis: CrisisClassifier(
            provider: { _ in #"{"risk":false,"reason":"只是夸张说法"}"# }
        ))
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯，那你早点休息。"]))
        // 0.15.7 工具协议提醒：第一次只写正文会被提醒一次、再请求一次，
        // 同一段正文排两份（第二次仍然违规 → 按 legacy 落库，最终内容不变）。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯，那你早点休息。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我真的不想活了"))

        let assistant = try XCTUnwrap(try messages().first { $0.role == .assistant })
        XCTAssertEqual("嗯，那你早点休息。", assistant.content,
                       "risk=false 不该在末尾追加安全提示")
    }

    /// 同上，`stay_silent` 也不为它让路（沉默只在真实危机时才被拒绝）。
    func testClassifierRiskFalseHonorsStaySilent() async throws {
        service = makeService(crisis: CrisisClassifier(
            provider: { _ in #"{"risk":false,"reason":"只是夸张说法"}"# }
        ))
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock()]))
        let events = try await collect(service.send(sessionId: sessionId, text: "我不想活了"))

        XCTAssertTrue(eventKinds(events).contains("noReply"), "risk=false 时沉默照旧生效")
        XCTAssertNil(try messages().first { $0.role == .assistant })
    }

    /// 两段式的第二段真的发了一次「当前 API 配置」的低温度 completion：
    /// 请求体里 system 是固定的判定指令、user 只有最近一条消息、stream=false、temperature=0。
    func testClassifierRequestUsesCurrentApiConfig() async throws {
        // provider 传 nil = 走 ChatService 用当轮配置拼的那个真 provider（假服务端按 FIFO 收）。
        service = makeService(crisis: CrisisClassifier())
        StubURLProtocol.enqueue(.json(
            #"{"choices":[{"message":{"content":"{\"risk\":false,\"reason\":\"夸张\"}"}}]}"#
        ))
        // 聊天正文两份：0.15.7 起「只写正文」会先被协议提醒一次，再请求一次。
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯。"]), .sse(textDeltas: ["嗯。"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我真的不想活了"))

        // 判定在后台 Task 里跑（不挡第一句回复），所以两次请求的到达顺序不保证：
        // 按 `stream` 字段区分「判定」与「聊天」，不去假定谁先谁后。
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertEqual(3, bodies.count,
                       "一次判定 + 两次聊天（第二次是协议提醒之后的重试），一共三次请求")
        let parsed = try bodies.map { body -> [String: Any] in
            try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        }
        let judgeBody = try XCTUnwrap(parsed.first { $0["stream"] as? Bool == false },
                                      "没有找到非流式的判定请求")
        let chatBody = try XCTUnwrap(parsed.first { $0["stream"] as? Bool == true },
                                     "没有找到流式的聊天请求")
        XCTAssertEqual(judgeBody["model"] as? String, "deepseek-chat", "判定用当前 API 配置的模型")
        XCTAssertEqual(judgeBody["temperature"] as? Double, 0, "判定用低温度（0）")
        XCTAssertEqual(judgeBody["max_tokens"] as? Int, CrisisJudgement.maxOutputTokens)
        let judgeMessages = try XCTUnwrap(judgeBody["messages"] as? [[String: Any]])
        XCTAssertEqual(judgeMessages.first?["role"] as? String, "system")
        XCTAssertEqual(judgeMessages.first?["content"] as? String, CrisisClassifier.systemPrompt)
        XCTAssertEqual(judgeMessages.last?["role"] as? String, "user")
        XCTAssertEqual(judgeMessages.last?["content"] as? String, "我真的不想活了",
                       "只判最近一条用户消息")
        XCTAssertNotNil(chatBody)
    }

    // MARK: - 4. 危机优先于沉默（0.15）

    /// 危机 + `stay_silent` + 模型同一轮写了正文：正文照常落库，缺锚点补安全提示。
    func testCrisisStaySilentWithBodyLandsVisibleReply() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯。那你早点睡。", silentBlock("不想说")]))
        let events = try await collect(service.send(sessionId: sessionId, text: "我真的不想活了"))

        let kinds = eventKinds(events)
        XCTAssertFalse(kinds.contains("noReply"), "危机轮不许「已读不回」")
        XCTAssertFalse(kinds.contains("toolStarted"), "stay_silent 不执行、不该留下工具卡片")
        XCTAssertFalse(kinds.contains("toolFinished"))
        XCTAssertTrue(kinds.contains("done"), "这是一轮正常回复")

        let stored = try messages()
        XCTAssertEqual([.user, .assistant], stored.map(\.role))
        let assistant = try XCTUnwrap(stored.last)
        XCTAssertTrue(assistant.content.hasPrefix("嗯。那你早点睡。"),
                      "模型写了正文就用它，绝不改成另一句话")
        XCTAssertTrue(assistant.content.contains("120") || assistant.content.contains("110"),
                      "缺安全锚点时补安全提示")
        XCTAssertTrue(assistant.content.contains("别一个人") || assistant.content.contains("身边有人"))
        XCTAssertNotNil(stored.first?.readAt, "照常标已读")
        XCTAssertTrue(try db.stores.agentIntents.byPersona(personaId).isEmpty,
                      "stay_silent 留不下念头")
        XCTAssertTrue(ledger.canStaySilent(personaId: personaId, now: now),
                      "危机轮不算「连续沉默」，计数要清零")

        // 界面收到的最后一份正文与库里逐字一致。
        let deltas: [String] = events.compactMap { event in
            if case .delta(let text) = event { return text } else { return nil }
        }
        XCTAssertEqual(assistant.content, deltas.last)
        let done = events.compactMap { event -> MessageEntity? in
            if case .done(let completion) = event { return completion.message } else { return nil }
        }.first
        XCTAssertEqual(assistant.content, done?.content)
    }

    /// 危机 + `stay_silent` + 模型只调工具（没有正文）：本地化安全提示就是唯一正文。
    func testCrisisStaySilentWithoutBodyLandsSafetyNote() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("累了")]))
        let events = try await collect(service.send(sessionId: sessionId, text: "我不想活了"))

        let kinds = eventKinds(events)
        XCTAssertFalse(kinds.contains("noReply"))
        XCTAssertFalse(kinds.contains("toolStarted"), "stay_silent 不该留下工具卡片")
        XCTAssertTrue(kinds.contains("done"))

        let stored = try messages()
        XCTAssertEqual([.user, .assistant], stored.map(\.role))
        let assistant = try XCTUnwrap(stored.last)
        XCTAssertEqual(
            SafetyGuard.safetyNetText(language: .zh), assistant.content,
            "没有正文时，安全提示是这一轮唯一的正文"
        )
        XCTAssertNotNil(stored.first?.readAt)
        XCTAssertTrue(ledger.canStaySilent(personaId: personaId, now: now))
    }

    /// 危机 + `defer_reply`：不排窗口、不留念头，照常回一句。
    func testCrisisDeferReplyLandsVisibleReplyWithoutIntent() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n{\"name\":\"defer_reply\",\"arguments\":{\"minutes\":30}}\n```"
        ]))
        let events = try await collect(service.send(sessionId: sessionId, text: "我想不开"))

        let kinds = eventKinds(events)
        XCTAssertFalse(kinds.contains("noReply"), "危机轮不许「过一会儿再说」")
        XCTAssertTrue(kinds.contains("done"))

        let stored = try messages()
        XCTAssertEqual([.user, .assistant], stored.map(\.role))
        let assistant = try XCTUnwrap(stored.last)
        XCTAssertEqual(SafetyGuard.safetyNetText(language: .zh), assistant.content)
        XCTAssertNotNil(stored.first?.readAt)
        XCTAssertTrue(try db.stores.agentIntents.byPersona(personaId).isEmpty,
                      "危机轮不写「到点开口」的念头")
        XCTAssertTrue(ledger.canStaySilent(personaId: personaId, now: now),
                      "危机轮不算沉默")
    }

    /// 正文里已经有安全锚点：一个字都不加（也不重复补）。
    func testCrisisStaySilentWithAnchorDoesNotDuplicate() async throws {
        let reply = "我在，别一个人待着。要不要先给家人打个电话？"
        StubURLProtocol.enqueue(.sse(textDeltas: [reply, silentBlock()]))
        _ = try await collect(service.send(sessionId: sessionId, text: "我不想活了"))

        let assistant = try XCTUnwrap(try messages().last { $0.role == .assistant })
        XCTAssertEqual(reply, assistant.content, "已有锚点就不再补第二遍")
    }

    /// 危机 + 这一轮已经用 `send_message` 说过一句**带锚点**的话，然后想沉默：
    /// 不再补一条纯提示气泡（不重复），这一轮就用那条收尾。
    func testCrisisStaySilentAfterAnchoredSendDoesNotAddDuplicateBubble() async throws {
        var sent = ToolResult(
            invocation: ToolInvocation(id: "call-1", name: "send_message", argumentsJson: "{}"),
            ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        sent.outboundText = "我在，别一个人待着。"
        tools.results = [sent]
        StubURLProtocol.enqueue(.sse(textDeltas: [
            "```yap-tool\n"
                + #"{"name":"send_message","arguments":{"text":"我在，别一个人待着。"}}"#
                + "\n```"
        ]))
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock()]))

        let events = try await collect(service.send(sessionId: sessionId, text: "我不想活了"))
        XCTAssertFalse(eventKinds(events).contains("noReply"), "危机轮不许沉默")
        let assistants = try messages().filter { $0.role == .assistant }
        XCTAssertEqual(["我在，别一个人待着。"], assistants.map(\.content),
                       "已经说过的锚点不再补第二遍")
        XCTAssertNotNil(try messages().first { $0.role == .user }?.readAt)
    }

    /// 非危机 + `stay_silent`：行为一个字都不变（只标已读、不落消息）。
    func testNonCrisisStaySilentIsUnchanged() async throws {
        StubURLProtocol.enqueue(.sse(textDeltas: ["我在呢。", silentBlock("累了")]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let kinds = eventKinds(events)
        XCTAssertTrue(kinds.contains("noReply"))
        XCTAssertFalse(kinds.contains("done"))
        XCTAssertFalse(kinds.contains("toolStarted"))
        XCTAssertEqual([.user], try messages().map(\.role), "非危机轮照旧不落 assistant 正文")
        XCTAssertNotNil(try messages().first?.readAt, "只标已读")
    }

    /// 危机 + `stay_silent` 且这一轮 4 条 `send_message` 已经发满：
    /// 安全提示并进**最后一条**，不造第 5 个气泡。
    func testCrisisStaySilentAfterFullQuotaMergesSafetyNetIntoLastMessage() async throws {
        tools.results = ["一", "二", "三", "四"].map { text in
            var sent = ToolResult(
                invocation: ToolInvocation(id: "call-\(text)", name: "send_message", argumentsJson: "{}"),
                ok: true, summary: "发了条消息", contentForModel: "发出去了"
            )
            sent.outboundText = text
            return sent
        }
        let blocks = ["一", "二", "三", "四"].map { text in
            "```yap-tool\n"
                + #"{"name":"send_message","arguments":{"text":"\#(text)"}}"#
                + "\n```"
        }.joined(separator: "\n")
        StubURLProtocol.enqueue(.sse(textDeltas: [blocks]))
        // 第二轮想沉默：危机轮不认，安全提示并进第 4 条。
        StubURLProtocol.enqueue(.sse(textDeltas: [silentBlock("不想说")]))

        let events = try await collect(service.send(sessionId: sessionId, text: "我不想活了"))

        let kinds = eventKinds(events)
        XCTAssertFalse(kinds.contains("noReply"), "危机轮不许沉默")
        XCTAssertTrue(kinds.contains("appended"), "并进最后一条后界面要立刻看到那句提示")
        XCTAssertEqual(4, tools.callCount, "stay_silent 不执行（没有第 5 次工具调用）")

        let assistants = try messages().filter { $0.role == .assistant }
        XCTAssertEqual(4, assistants.count, "4 条是硬上限，不能为了安全提示造第 5 条")
        let last = try XCTUnwrap(assistants.last)
        XCTAssertTrue(last.content.hasPrefix("四"), "提示并进最后一条，不改写它原本的话")
        XCTAssertTrue(last.content.contains("120") || last.content.contains("110"))
        XCTAssertTrue(last.content.contains("别一个人") || last.content.contains("身边有人"))
        XCTAssertNotNil(try messages().first { $0.role == .user }?.readAt)
    }
}

// MARK: - 测试替身

/// 假工具执行器：记录调用名，按预设结果返回（不碰真库）。
private final class ToolStub: ToolRunning {
    var results: [ToolResult] = []
    private(set) var names: [String] = []

    var callCount: Int { names.count }

    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        names.append(invocation.name)
        if results.isEmpty {
            return ToolResult(
                invocation: invocation, ok: true,
                summary: "记住了", contentForModel: "已写进长期记忆。"
            )
        }
        return results.removeFirst()
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
