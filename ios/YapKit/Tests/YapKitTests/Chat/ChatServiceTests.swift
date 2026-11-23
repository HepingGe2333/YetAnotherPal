import Foundation
import XCTest
@testable import YapKit

/// 聊天主链路：真 `URLSession`（自定义 `URLProtocol` 假装流式服务端）+ 真 SQLite。
///
/// 这一层是「所有零件装在一起」的地方，所以断言也偏整体：
/// 事件序列、落库内容、会话预览、用量记账、第二次请求里带了什么。
/// 任何一处接错，用户看到的就是「话说一半」「账没记」「记忆没进去」这类问题。
final class ChatServiceTests: XCTestCase {

    private var directoryURL: URL!
    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var keychain: InMemoryKeychain!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var recorder: UsageRecorder!
    private var service: ChatService!
    private var background: RecordingBackground!
    private var tools: RecordingTools!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"

    // MARK: - 夹具

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-chat-tests", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        databaseURL = directoryURL.appendingPathComponent("chat-\(UUID().uuidString).sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        keychain = InMemoryKeychain()
        apiKeys = ApiKeyStore(backend: keychain)
        settings = InMemorySettings(AppSettings())
        recorder = UsageRecorder(stores: stores)
        background = RecordingBackground()
        tools = RecordingTools()
        service = makeService()
        AgentCoreDiagnostics.shared.reset()
    }

    override func tearDownWithError() throws {
        service = nil
        AgentCoreDiagnostics.shared.reset()
        StubURLProtocol.reset()
        stores = nil
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databaseURL.path + suffix)
        }
        try super.tearDownWithError()
    }

    private func makeService(
        media: MediaStorage = .shared,
        agentCore: any AgentCoreClient = NativeAgentCoreClient()
    ) -> ChatService {
        ChatService(
            stores: stores,
            apiKeys: apiKeys,
            settings: settings,
            client: OpenAICompatibleClient(session: StubURLProtocol.makeSession(),
                                           usage: recorder, gate: LlmConcurrencyGate()),
            tools: tools,
            background: background,
            media: media,
            agentCore: agentCore
        )
    }

    @discardableResult
    private func seedPersona(prompt: String = "你是小满，说话很轻。") throws -> PersonaEntity {
        // `sleepEnabled: false`：这些用例测的是「生成链路」，不该被「测试机现在几点」影响。
        // 1.9 的默认人格是会睡觉的（23:30–08:00），凌晨跑测试时整条链路会正确走到
        // `ChatNoReplyReason.sleepingWaiting`，于是「没有请求、没有回复」——那是**睡眠判定**的用例
        // （`ChatRhythmTests` / `SleepScheduleTests`）该管的事，不是这里。
        let persona = PersonaEntity(
            id: personaId, name: "小满", prompt: prompt, version: 1, isDefault: true,
            updatedAt: 1, sleepEnabled: false
        )
        try stores.personas.upsert(persona)
        return persona
    }

    @discardableResult
    private func seedConfig(webTools: Bool = false) throws -> ApiConfigEntity {
        let config = ApiConfigEntity(
            id: configId, name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat",
            updatedAt: 1, webToolsEnabled: webTools
        )
        try stores.apiConfigs.upsert(config)
        return config
    }

    @discardableResult
    private func seedSession(apiConfigId: String? = "cfg-1") throws -> ChatSessionEntity {
        let session = ChatSessionEntity(
            id: sessionId, personaId: personaId, apiConfigId: apiConfigId,
            model: "", title: "与 小满 的对话", createdAt: 1, updatedAt: 1,
            lastPersonaVersion: 1, lastMemoryExtractedCount: 0
        )
        try stores.sessions.upsert(session)
        return session
    }

    private func seedKey(_ key: String = "sk-test") throws {
        try apiKeys.setAPIKey(key, for: configId)
    }

    private func seedMemory(_ content: String, pinned: Bool = true) throws {
        try stores.memories.upsert(MemoryEntity(
            id: "m-\(UUID().uuidString)", personaId: personaId, content: content,
            category: .fact, importance: 5, pinned: pinned, createdAt: 1, updatedAt: 1,
            lastUsedAt: 0, useCount: 0, status: "ACTIVE", strength: 1
        ))
    }

    /// 一轮完整对话的准备：人格 + 配置 + 会话 + Key。
    private func seedReadySession(webTools: Bool = false) throws {
        try seedPersona()
        try seedConfig(webTools: webTools)
        try seedSession()
        try seedKey()
    }


    // MARK: - 0.15.0 协议确认循环的夹具

    /// 只写正文的模型会被确认两次；两次之后仍然只写正文 → **整轮丢弃**（不落库、不 legacy）。
    /// 只关心「提示词 / 请求报文」的用例用它：三轮都排同一段正文，最后按 silent 结束，
    /// 既不会因为 stub 用尽而抛错，也不会给用例塞一条不该存在的助手消息。
    private func enqueueBodyOnlyDrop(_ text: String) {
        for _ in 0..<3 {
            StubURLProtocol.enqueue(.sse(textDeltas: [text]))
        }
    }

    /// `send_message` 的协议块（与生产协议同形）。
    static func sendMessageBlock(_ text: String) -> String {
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        return "```yap-tool\n{\"name\":\"send_message\",\"arguments\":{\"text\":\"\(escaped)\"}}\n```"
    }

    /// 模型按协议说话：先排一段 `send_message`，工具回灌之后不再多说（空白一轮收尾）。
    private func enqueueSpoken(_ text: String) {
        deliverSentMessages()
        StubURLProtocol.enqueue(.sse(textDeltas: [Self.sendMessageBlock(text)]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
    }

    /// 假执行器按 `send_message` 的参数把正文交回来（真实执行器就是这么做）。
    /// 已经设了 `resultFor` 的用例自己负责在闭包里处理 `send_message`。
    private func deliverSentMessages() {
        guard tools.resultFor == nil else { return }
        tools.resultFor = { invocation in
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

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>,
                         onEvent: ((ChatEvent) -> Void)? = nil) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream {
            events.append(event)
            onEvent?(event)
        }
        return events
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

    /// 严格远程（`remote-strict`）下决策层失败必须变成**可见错误**（0.11.1 审计 P2-1）。
    ///
    /// 0.11.0 的问题：每个调用点都 `try?` 掉并改用本机判定，于是严格模式与普通回退模式
    /// 在 App 行为上没有区别 —— 用它验收「agentd 真的被用上了」会得到假阳性。
    func testStrictRemoteFailureSurfacesAsVisibleChatError() async throws {
        try seedReadySession()
        let strict = makeService(agentCore: StrictFailingAgentCoreClient())
        guard case .started(let stream) = strict.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("用户消息落库成功，应当返回 .started")
        }
        do {
            _ = try await collect(stream)
            XCTFail("严格模式失败必须让这条流报错，而不是把本机判定当成远程结果")
        } catch let error as AgentCoreClientError {
            let text = error.errorDescription ?? ""
            XCTAssertFalse(text.isEmpty, "用户要能看到一句人话（严格模式是联调模式，失败要显眼）")
        }
        XCTAssertEqual(
            AgentCoreDiagnostics.shared.strictCount(endpoint: "sleep-decision"), 1,
            "严格模式的失败要计数（可观测）"
        )
    }

    /// 永远失败、并对外声称自己是严格远程的假客户端。
    private struct StrictFailingAgentCoreClient: AgentCoreClient {
        var agentCoreMode: AgentCoreMode { .remote(preferredFallback: false) }
        func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
            throw AgentCoreClientError.transport("agentd 没起")
        }
        func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
            throw AgentCoreClientError.transport("agentd 没起")
        }
        func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
            throw AgentCoreClientError.transport("agentd 没起")
        }
        func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
            throw AgentCoreClientError.transport("agentd 没起")
        }
    }

    private func messages(sessionId: String = "s-1") throws -> [MessageEntity] {
        try stores.messages.bySession(sessionId)
    }

    /// 拿到这一次请求发出去的 JSON（系统提示词 + 消息数组）。
    private func requestBody(at index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "没有记录到第 \(index) 次请求")
        let body = bodies[index]
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
    }

    private func systemPrompt(at index: Int = 0) throws -> String {
        let body = try requestBody(at: index)
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        return try XCTUnwrap(messages.first?["content"] as? String)
    }

    // MARK: - 人设引导进提示词（0.12，§1）

    /// 世界里的事实、话风、当前活动、边界都要真的出现在发出去的 system 里。
    func testSystemPromptCarriesWorldSpeechStyleActivityAndBoundary() async throws {
        try seedPersona()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.worldJson = WorldPreset(
            worldName: "临港新城·云筑建筑事务所",
            era: "当代，沿海城市",
            description: "事务所竞争激烈，甲方频繁改图。",
            schedule: [WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30",
                                         activity: "在事务所画图")],
            relationships: [WorldRelationship(name: "老周", relation: "合伙人", note: "催图直接")],
            obligations: ["按节点交图"]
        ).jsonString()
        persona.speechStyleJson = SpeechStyle(
            tone: "简短直接，偏冷淡务实",
            phrases: ["在忙，晚点说", "别想太多"],
            habits: ["句子很短"],
            avoid: ["长篇大论"]
        ).jsonString()
        let now = DomainClock.nowMillis
        persona.activityJson = PersonaActivity(
            kind: .work, activity: "在事务所赶第三版方案", minutes: 90,
            availability: .busy, startedAt: now
        ).jsonString()
        try stores.personas.upsert(persona)
        try seedConfig()
        try seedSession()
        try seedKey()
        // 0.15.0 协议确认循环：只写正文的模型会被问两次；第三次仍然只写正文 → 整轮丢弃。
        // 这条用例只关心请求里的 system，所以三轮都排同一段正文。
        for _ in 0..<3 {
            StubURLProtocol.enqueue(.sse("data: {\"choices\":[{\"delta\":{\"content\":\"嗯\"}}]}\n\ndata: [DONE]\n\n", splitEvery: 8))
        }

        _ = try await collect(service.send(sessionId: sessionId, text: "你在干嘛"))
        let system = try systemPrompt()

        XCTAssertTrue(system.contains("# 你是谁"))
        XCTAssertTrue(system.contains("第一人称"), "身份段必须写明第一人称、不是助手")
        XCTAssertTrue(system.contains("# 你所在的世界"))
        XCTAssertTrue(system.contains("临港新城·云筑建筑事务所"))
        XCTAssertTrue(system.contains("老周（合伙人）：催图直接")) 
        XCTAssertTrue(system.contains("# 你此刻的状态"))
        XCTAssertTrue(system.contains("在事务所赶第三版方案"))
        XCTAssertTrue(system.contains("# 你怎么说话"))
        XCTAssertTrue(system.contains("「在忙，晚点说」"))
        XCTAssertTrue(system.contains("# 边界"))
        XCTAssertFalse(system.contains("{name}"), "提示词里不能留未替换的占位符")
    }

    /// 没有世界/话风/活动时：不出现那几节，但不是崩——只有身份与边界。
    func testSystemPromptWithoutGuidanceStillHasIdentityAndBoundary() async throws {
        try seedReadySession()
        // 0.15.0 协议确认循环：只写正文的模型会被问两次；第三次仍然只写正文 → 整轮丢弃。
        // 这条用例只关心请求里的 system，所以三轮都排同一段正文。
        for _ in 0..<3 {
            StubURLProtocol.enqueue(.sse("data: {\"choices\":[{\"delta\":{\"content\":\"嗯\"}}]}\n\ndata: [DONE]\n\n", splitEvery: 8))
        }
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        let system = try systemPrompt()
        XCTAssertTrue(system.contains("# 你是谁"))
        XCTAssertTrue(system.contains("# 边界"))
        // 没有世界设定时，这一节仍然存在、只写一句「没有特别的世界设定」（脚手架 `world_empty`）：
        // 模型需要知道"这里没有设定"，而不是自己编一个世界出来。
        XCTAssertTrue(system.contains("# 你所在的世界"))
        XCTAssertTrue(system.contains("你没有特别的世界设定"))
        XCTAssertFalse(system.contains("# 你怎么说话"))
        // 没有活动也要说清「没在忙什么」（§6.3）。
        XCTAssertTrue(system.contains("没在忙什么"))
    }

    // MARK: - 正常一轮

    func testSendStreamsDeltasPersistsMessagesAndRecordsUsage() async throws {
        try seedReadySession()
        deliverSentMessages()
        // 0.15 协议：正文只做输入指示，气泡由 `send_message` 落库。模型一边写一边把
        // 这句话交给工具，所以既能看到流式正文，也能看到工具完成后的那条消息。
        StubURLProtocol.enqueue(.sse(
            textDeltas: ["你", "好呀" + "\n" + Self.sendMessageBlock("你好呀")],
            usage: (120, 9)
        ))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "], usage: (80, 5)))

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(deltas(events).prefix(2), ["你", "你好呀"], "delta 是「当前可见全文」，不是增量")
        XCTAssertEqual(deltas(events).last, "", "工具轮结束时清掉临时正文（气泡由落库消息显示）")
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "你好呀")
        XCTAssertEqual(done.message.role, .assistant)
        XCTAssertEqual(done.message.status, .complete)
        XCTAssertEqual(done.toolTraces.count, 1, "这一轮只用了一个工具：send_message")
        XCTAssertEqual(done.toolTraces.first?.name, ToolCatalog.sendMessage)
        XCTAssertFalse(done.pendingMemory, "才两条消息，还不到抽记忆的间隔")

        // 落库：用户消息在前，助手消息在后。
        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored.map(\.content), ["在吗", "你好呀"])
        XCTAssertEqual(stored[1].id, done.message.id)
        XCTAssertTrue(stored[0].createdAt < stored[1].createdAt, "时间戳必须严格递增")

        // 会话预览与未读：会话没打开过，所以未读 +1。
        let session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        XCTAssertEqual(session.title, "在吗", "第一条用户消息自动当标题")
        XCTAssertEqual(session.lastMessagePreview, "你好呀")
        XCTAssertEqual(session.unreadCount, 1)

        // 记账：两笔 CHAT（发送那一轮 + 工具回灌之后收尾那一轮），带 usage。
        // 每一次真实的模型调用都照记，否则用量页上会少一笔。
        let usageRows = try stores.apiUsage.all()
        XCTAssertEqual(usageRows.count, 2)
        XCTAssertEqual(usageRows[0].kind, UsageKind.chat)
        XCTAssertEqual(usageRows[0].promptTokens, 120)
        XCTAssertEqual(usageRows[0].completionTokens, 9)

        // 报文：一条 system + 一句用户消息，且 stream/usage 选项都对。
        let body = try requestBody()
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual((body["stream_options"] as? [String: Any])?["include_usage"] as? Bool, true)
        let requestMessages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages.count, 2)
        XCTAssertEqual(requestMessages[0]["role"] as? String, "system")
        XCTAssertTrue((requestMessages[1]["content"] as? String ?? "").hasSuffix("用户：在吗"),
                      "\(String(describing: requestMessages[1]["content"]))")
    }

    /// system 里该有的段落：人格、工具、主动唤醒、闲聊时也不该硬塞记忆块。
    func testSystemPromptContainsPersonaAndCapabilitiesInOrder() async throws {
        try seedReadySession()
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("你是小满，说话很轻。"))
        XCTAssertTrue(system.contains("## 你可以主动联系对方"))
        // 0.13：工具那一段的标题改成「# 你能做什么」（内容由 ToolCatalog 生成）。
        XCTAssertTrue(system.contains(ToolCatalog.capabilitySection))
        XCTAssertTrue(system.contains("- think(question, rounds?)："))
        let personaIndex = try XCTUnwrap(system.range(of: "你是小满"))
        let capabilityIndex = try XCTUnwrap(system.range(of: "## 你可以主动联系对方"))
        XCTAssertTrue(personaIndex.lowerBound < capabilityIndex.lowerBound, "人格必须在能力说明之前")
    }

    /// 心情：由 `MoodRepository` 读出来（顺手让时间冲淡），渲染成 `# 你的心情` 那一段。
    func testMoodLineComesFromMoodRepository() async throws {
        try seedReadySession()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.mood = "雀跃"
        persona.moodIntensity = 4
        persona.moodReason = "他夸我了"
        persona.moodUpdatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        try stores.personas.upsert(persona)
        enqueueBodyOnlyDrop("嘿嘿")
        _ = try await collect(service.send(sessionId: sessionId, text: "今天真好看"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains(MoodPrompt.sectionTitle), "多维心情段（spec/mood/README.md §3）")
        XCTAssertTrue(system.contains("心情：雀跃（强度 4/5）。"))
        XCTAssertTrue(system.contains("愉快度"))
        XCTAssertTrue(system.contains("掌控感"))
        XCTAssertTrue(system.contains("他夸我了"), "来由（reason）只给模型自己看")
    }

    /// 记忆块：有记忆时进 system，标题是 `## 你记得的事`（与 Android 逐字一致）。
    func testMemoriesAreInjectedIntoSingleSystemPrompt() async throws {
        try seedReadySession()
        try seedMemory("他怕冷，冬天总穿两双袜子。")
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "今天好冷"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("## 你记得的事"))
        XCTAssertTrue(system.contains("他怕冷，冬天总穿两双袜子。"))
        // 0.15 上下文 v2：完整设定+工具 → 长期记忆 → 短期 → 状态 → 主题 → 历史。
        // 记忆块在人格/工具之后，但在「更早记得的」与历史之前。
        XCTAssertTrue(system.contains(MemoryContract.Injection.LONG_TERM_TITLE),
                      "记忆属于「长期记忆」段：\(system)")
        let personaIndex = try XCTUnwrap(system.range(of: "你是小满"))
        let memoryIndex = try XCTUnwrap(system.range(of: "## 你记得的事"))
        XCTAssertTrue(personaIndex.lowerBound < memoryIndex.lowerBound, "记忆必须在人设之后")
    }

    /// 1.9：记忆**内置开启**，没有开关能把它关掉（§一）。
    /// 这条测试同时守着「以后别有人再把一个 memoryEnabled 加回来」。
    func testMemoryIsAlwaysOn() async throws {
        try seedReadySession()
        try seedMemory("他怕冷。")
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "今天好冷"))
        XCTAssertTrue(try systemPrompt().contains("## 你记得的事"))
    }

    /// 滚动摘要：会话压过的那一段以「【更早之前你记得的】」进 system。
    func testHistorySummaryBlockIsInjected() async throws {
        try seedReadySession()
        var session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        session.historySummary = "上次聊到他搬家。"
        session.historySummarizedUpTo = 500
        try stores.sessions.upsert(session)
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "后来呢"))
        let system = try systemPrompt()
        XCTAssertTrue(system.contains(HistoryCompression.SUMMARY_HEADER))
        XCTAssertTrue(system.contains("上次聊到他搬家。"))
    }

    // MARK: - 每轮注入当前时间（0.13，`spec/context-time/README.md`）

    /// 一次回复里 system 只拼一次：工具回路的第二、三轮请求带的是同一段 system，
    /// 时间行进**只出现一次**，不会因为多轮工具而重复追加。
    func testTimeInjectedOncePerReplyAcrossToolRounds() async throws {
        try seedReadySession()
        let first = """
        data: {"choices":[{"delta":{"content":"我查一下。\n```yap-tool\n{\"name\":\"think\",\"arguments\":{\"question\":\"今天几号\"}}\n```"}}]}

        data: [DONE]

        """
        let second = """
        data: {"choices":[{"delta":{"content":"想好了。"}}]}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(first, splitEvery: 11))
        // 0.15.0 协议确认循环：工具轮之后模型只写正文 → 问两次；第三次仍然是正文 → 整轮丢弃。
        // 这条用例只关心每一轮请求里的 system 拼装，所以第二段排三份。
        for _ in 0..<3 {
            StubURLProtocol.enqueue(.sse(second, splitEvery: 11))
        }
        tools.resultFor = { invocation in
            ToolResult(invocation: invocation, ok: true, summary: "想了想", contentForModel: "今天周四")
        }
        _ = try await collect(service.send(sessionId: sessionId, text: "今天几号"))

        // 两次请求各只有一条 system（时间没有被当成一条新消息追加）。
        for index in 0..<2 {
            let body = try requestBody(at: index)
            let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
            XCTAssertEqual(messages.filter { ($0["role"] as? String) == "system" }.count, 1)
        }
        let firstSystem = try systemPrompt(at: 0)
        let secondSystem = try systemPrompt(at: 1)
        XCTAssertEqual(firstSystem, secondSystem, "同一轮回复里 system 只应拼一次（工具回路不重建）")

        // 时间行的形状：日期 + 周几 + 时刻 + UTC 偏移；整段 system 里只出现一次。
        let pattern = #"现在是\d{4}-\d{2}-\d{2} 周[日一二三四五六] \d{2}:\d{2}（UTC[+-]\d{2}:\d{2}）。"#
        let regex = try NSRegularExpression(pattern: pattern)
        let range = NSRange(firstSystem.startIndex..<firstSystem.endIndex, in: firstSystem)
        XCTAssertEqual(regex.numberOfMatches(in: firstSystem, range: range), 1,
                       "时间行必须只出现一次：\(firstSystem)")
        let secondBody = try requestBody(at: 1)
        let secondContents = (secondBody["messages"] as? [[String: Any]] ?? [])
            .compactMap { $0["content"] as? String }
            .joined(separator: "\n")
        XCTAssertEqual(regex.numberOfMatches(
            in: secondContents, range: NSRange(secondContents.startIndex..<secondContents.endIndex, in: secondContents)
        ), 1, "回灌工具结果之后，时间行仍然只出现一次")
    }

    // MARK: - 工具轮

    /// 0.13 表情包：`sticker.send` 已经落了一条助手消息，模型不再写正文时，
    /// 这一轮应当正常结束（而不是被「他查完了东西，怕是走神了」当成失败）。
    func testStickerSendRoundWithoutFinalTextCompletesWithStickerMessage() async throws {
        try seedReadySession()
        let produced = MessageEntity(
            id: "sticker-msg-1", sessionId: sessionId, personaId: personaId, role: .assistant,
            content: "", createdAt: 999, updatedAt: 999, status: .complete,
            attachmentsJson: AttachmentsCodec.encode([
                MessageAttachment(uri: "file:///s.gif", mime: "image/gif",
                                  kind: MessageAttachment.stickerKind)
            ])
        )
        try stores.messages.upsert(produced)
        let delta = """
        ```yap-tool
        {"name":"sticker","arguments":{"action":"send","stickerId":"s-1"}}
        ```
        """
        StubURLProtocol.enqueue(
            .sse(textDeltas: [delta]),
            // 第二轮只吐一个空白：流里有字（客户端不会判「没有返回任何文字」），
            // 但 trim 之后正文为空 —— 正是「模型只发了个表情、不再说话」的形状。
            .sse(textDeltas: [" "])
        )
        tools.resultFor = { invocation in
            ToolResult(
                invocation: invocation, ok: true, summary: "表情",
                contentForModel: "已经把表情发出去了。",
                traceTitle: "已发送表情", producedMessageId: produced.id
            )
        }

        let events = try await collect(service.send(sessionId: sessionId, text: "发个表情"))
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.id, produced.id, "这一轮的产物就是那条表情消息")
        XCTAssertEqual(done.toolTraces.map(\.title), ["已发送表情"])
        // 用户一条 + 表情一条；不再多写一条空助手消息。
        XCTAssertEqual(try stores.messages.count(sessionId: sessionId), 2)
    }

    func testToolRoundEmitsEventsAndFeedsResultBack() async throws {
        try seedReadySession()
        let first = """
        data: {"choices":[{"delta":{"content":"我查一"}}]}

        data: {"choices":[{"delta":{"content":"下。\\n```yap-tool\\n{\\"name\\":\\"web_search\\",\\"arguments\\":{\\"query\\":\\"今天天气\\"}}\\n```"}}]}

        data: [DONE]

        """
        let second = """
        data: {"choices":[{"delta":{"content":"查到了，"}}]}

        data: {"choices":[{"delta":{"content":"今天晴。"}}]}

        data: {"choices":[],"usage":{"prompt_tokens":80,"completion_tokens":6}}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(first, splitEvery: 11))
        // 0.15 协议：工具轮之后模型用 `send_message` 把话发出去（不再直接输出正文）。
        StubURLProtocol.enqueue(.sse(textDeltas: [" " + Self.sendMessageBlock("查到了，今天晴。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        tools.resultFor = { invocation in
            if invocation.name == ToolCatalog.sendMessage {
                var result = ToolResult(
                    invocation: invocation, ok: true, summary: "发了条消息", contentForModel: "发出去了"
                )
                result.outboundText = invocation.argument("text")
                return result
            }
            return ToolResult(
                invocation: invocation, ok: true, summary: "查了 3 个网页",
                contentForModel: "今天晴，12 度"
            )
        }

        let events = try await collect(service.send(sessionId: sessionId, text: "今天天气怎么样"))

        // 事件序列：先正文，再工具开始/结束，最后是第二轮正文。
        XCTAssertTrue(events.contains { if case .toolStarted(let trace) = $0 { return trace.title == "联网搜索" } else { return false } })
        XCTAssertTrue(events.contains { if case .toolFinished(let trace) = $0 { return trace.title == "联网搜索" && trace.subtitle == "查了 3 个网页" } else { return false } })
        // 协议块绝不能出现在推给界面的文字里。
        XCTAssertFalse(deltas(events).contains { $0.contains("yap-tool") })
        XCTAssertTrue(deltas(events).contains("我查一下。"), "工具轮之前的正文照常流式显示")

        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "查到了，今天晴。")
        XCTAssertEqual(done.toolTraces.map(\.name), [ToolCatalog.webSearch, ToolCatalog.sendMessage])
        XCTAssertEqual(done.toolTraces[0].title, "联网搜索")
        XCTAssertNotNil(done.message.toolTraceJson, "新协议消息要留空数组标记（不是 nil）")
        // `send_message` 落库时先写空数组标记，真实痕迹挂在 `done.toolTraces` 上（界面去重显示）。
        XCTAssertEqual(ToolTraceCodec.decode(done.message.toolTraceJson), [])

        // 第二次请求：把「他自己刚做的事」回灌给模型。
        let secondBody = try requestBody(at: 1)
        let secondMessages = try XCTUnwrap(secondBody["messages"] as? [[String: Any]])
        let contents = secondMessages.compactMap { $0["content"] as? String }
        XCTAssertTrue(contents.contains { $0.contains("【工具结果】web_search") })
        XCTAssertTrue(contents.contains { $0.contains("今天晴，12 度") })
        XCTAssertTrue(contents.contains { $0.contains("现在接着把话说完") })
    }

    /// 模型同一轮里把同一个调用写了两次：只执行一次（防「查了又查」的死循环）。
    func testDuplicateToolCallsAreDeduplicated() async throws {
        try seedReadySession()
        let block = #"{"name":"think","arguments":{"question":"要不要问"}}"#
        let first = "容我想想。\n```yap-tool\n\(block)\n```\n```yap-tool\n\(block)\n```"
        StubURLProtocol.enqueue(.sse(textDeltas: [first]))
        StubURLProtocol.enqueue(.sse(textDeltas: [Self.sendMessageBlock("想好了。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [" "]))
        tools.resultFor = { invocation in
            if invocation.name == ToolCatalog.sendMessage {
                var result = ToolResult(
                    invocation: invocation, ok: true, summary: "发了条消息", contentForModel: "发出去了"
                )
                result.outboundText = invocation.argument("text")
                return result
            }
            return ToolResult(invocation: invocation, ok: true, summary: "想了想", contentForModel: "想好了")
        }
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(tools.callCount, 2, "think 的重复调用只执行一次；send_message 另算一次")
        // 与 Android 一致：被去重掉的那次不执行、也不留痕（留痕只记「真的跑过」的动作）。
        XCTAssertEqual(doneEvent(events)?.toolTraces.count, 2, "think + send_message 各一条真实痕迹")
    }

    // MARK: - 失败与停止

    func testUnauthorizedMarksAssistantMessageFailed() async throws {
        try seedReadySession()
        StubURLProtocol.enqueue(.json(#"{"error":{"message":"invalid key"}}"#, status: 401))

        var events: [ChatEvent] = []
        var thrown: Error?
        do {
            for try await event in service.send(sessionId: sessionId, text: "在吗") {
                events.append(event)
            }
        } catch {
            thrown = error
        }
        let api = try XCTUnwrap(thrown as? ApiException)
        XCTAssertEqual(api.statusCode, 401)
        XCTAssertEqual(api.message, "API Key 无效或未授权（401）：invalid key")

        // 0.14 §1「不产生空消息」：一个字都没吐出来就失败时，不再存一条空的失败气泡，
        // 错误交给界面（`ChatViewModel` 弹一行 toast）。用户那句话仍然在库里。
        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user])
        XCTAssertNil(doneEvent(events), "没有落库消息时不该发 done（避免界面拿到一条空气泡）")
    }

    /// 没填 Key：本地就说清楚，不发请求、不白花一次往返。
    func testMissingApiKeyFailsWithChineseMessageAndNoRequest() async throws {
        try seedPersona()
        try seedConfig()
        try seedSession()

        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertEqual(
            (thrown as? ApiException)?.message,
            "还没有填 API Key：请在设置里为这个 API 配置补上密钥"
        )
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "没 Key 不该发出请求")
        // 用户消息仍然留着（他说的话不能丢）；0.14 起不再为「一个字都没说」的失败
        // 落一条空助手消息（错误由界面 toast 呈现）。
        XCTAssertEqual(try messages().map(\.role), [.user])
    }

    func testMissingApiConfigFailsBeforePersistingAnything() async throws {
        try seedPersona()
        try seedConfig()
        try seedSession(apiConfigId: nil)

        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "请先为该会话选择 API 配置")
        XCTAssertTrue(try messages().isEmpty, "配置不全时不该留下半截消息")
    }

    func testEmptyTextIsRejected() async throws {
        try seedReadySession()
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "   "))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "消息不能为空")
        XCTAssertTrue(try messages().isEmpty)
    }

    func testUnknownSessionFails() async throws {
        try seedPersona()
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: "nope", text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "会话不存在")
    }

    /// 用户点停止：已经吐出来的字要落成 STOPPED，不能凭空消失。
    func testStopMidStreamPersistsStoppedMessage() async throws {
        try seedReadySession()
        let sse = """
        data: {"choices":[{"delta":{"content":"第一句。"}}]}

        data: {"choices":[{"delta":{"content":"第二句。"}}]}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(sse, splitEvery: 32, delay: 0.3))

        var events: [ChatEvent] = []
        for try await event in service.send(sessionId: sessionId, text: "讲个故事") {
            events.append(event)
            if case .delta = event {
                service.stop(sessionId: sessionId)
            }
        }
        XCTAssertFalse(deltas(events).isEmpty)
        let stored = try messages()
        let assistant = try XCTUnwrap(stored.last)
        XCTAssertEqual(assistant.role, .assistant)
        XCTAssertEqual(assistant.status, .stopped)
        XCTAssertTrue(assistant.content.hasPrefix("第一句"), "半截正文要保住：\(assistant.content)")
    }

    // MARK: - 后台任务

    /// 回完话之后异步触发「抽记忆 / 压摘要」，但不阻塞回复。
    func testBackgroundTasksAreTriggeredAfterReply() async throws {
        try seedReadySession()
        // 先放 4 条旧消息，加上这一轮 2 条，正好到抽记忆的间隔（6）。
        for index in 0..<4 {
            try stores.messages.upsert(MessageEntity(
                id: "old-\(index)", sessionId: sessionId, personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "旧消息 \(index)", createdAt: Int64(index + 1), updatedAt: Int64(index + 1)
            ))
        }
        // 0.15 协议：先 `send_message` 发一句，再用正文补一句（工具已经说过话时，
        // 正文不再确认、按正常收尾落库）——走后半段完整链路，后台任务才会被触发。
        deliverSentMessages()
        StubURLProtocol.enqueue(.sse(textDeltas: [Self.sendMessageBlock("嗯")]))
        StubURLProtocol.enqueue(.sse(textDeltas: ["我还在。"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(background.compressionCalls, [sessionId])
        XCTAssertEqual(background.memoryCalls.map(\.sessionId), [sessionId])
        XCTAssertTrue(doneEvent(events)?.pendingMemory == true)
        XCTAssertEqual(doneEvent(events)?.message.content, "我还在。")
    }

    /// 会话开着时不计未读（界面正在看）。
    func testOpenSessionDoesNotIncrementUnread() async throws {
        try seedReadySession()
        service.setOpenSession(sessionId)
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(try stores.sessions.byId(sessionId)?.unreadCount, 0)
        XCTAssertTrue(service.isSessionOpen(sessionId))
    }

    // MARK: - 主动唤醒代码块

    func testProactiveBlockIsStrippedAndReported() async throws {
        try seedReadySession()
        let text = "```myfriend-proactive\n{\"kind\":\"daily\",\"time\":\"22:30\",\"note\":\"每晚问候\"}\n```"
        // 0.15 协议：用户能看到的那句话走 `send_message`；`yap-wake` 提案块留在正文里，
        // 工具回灌之后的那一轮不再输出正文，只交回提案（它不是给用户看的文字）。
        deliverSentMessages()
        StubURLProtocol.enqueue(.sse(textDeltas: [Self.sendMessageBlock("好呀，我记住啦。")]))
        StubURLProtocol.enqueue(.sse(textDeltas: [text]))
        let events = try await collect(service.send(sessionId: sessionId, text: "每天晚上十点半来问我一句"))
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "好呀，我记住啦。")
        XCTAssertFalse(done.message.content.contains("myfriend-proactive"))
        XCTAssertEqual(done.proactiveProposalJson, #"{"kind":"daily","time":"22:30","note":"每晚问候"}"#)
    }

    // MARK: - 图片消息

    /// 只发一张图（正文为空）：落库 `attachmentsJson`、会话预览写「图片」、
    /// 这一次请求真的以多模态 content 数组把 data URL 发出去了。
    func testSendWithImagePersistsAttachmentsJsonAndSendsMultimodalPart() async throws {
        try seedReadySession()
        let mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-chat-media-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: mediaDirectory) }
        let media = MediaStorage(directory: mediaDirectory)
        service = makeService(media: media)

        let attachment = try media.saveImage(data: makeJPEG(width: 1200, height: 800), kind: .chatImage)
        enqueueSpoken("好看")

        _ = try await collect(service.send(sessionId: sessionId, text: "", attachments: [attachment]))

        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored[0].content, "", "只发图时正文可以是空的")
        let decoded = AttachmentsCodec.decode(stored[0].attachmentsJson)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded.first?.uri, attachment.uri)
        XCTAssertEqual(decoded.first?.width, 1200)
        XCTAssertEqual(decoded.first?.height, 800)

        // 首页那一行预览与标题：只有图的时候写「图片」（与 Android 的 ifBlank 一致）。
        let session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        XCTAssertEqual(session.title, "图片")
        XCTAssertEqual(session.lastMessagePreview, "好看", "回复落库后预览是最后一句助手消息")

        // 报文：user 的 content 是数组，先一句「（发来一张图片）」，再一张 data URL。
        let requestMessages = try XCTUnwrap(try requestBody()["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages.count, 2)
        let parts = try XCTUnwrap(requestMessages[1]["content"] as? [[String: Any]])
        XCTAssertEqual(parts.first?["type"] as? String, "text")
        XCTAssertTrue((parts.first?["text"] as? String ?? "").hasSuffix("用户：（发来一张图片）"),
                      "\(String(describing: parts.first?["text"]))")
        XCTAssertEqual(parts.last?["type"] as? String, "image_url")
        let url = try XCTUnwrap((parts.last?["image_url"] as? [String: Any])?["url"] as? String)
        XCTAssertTrue(url.hasPrefix("data:image/jpeg;base64,"))
    }

    /// 一条消息最多四张图（`MAX_IMAGES_PER_MESSAGE`）：多选的第五张不落库。
    func testSendCapsImagesPerMessage() async throws {
        try seedReadySession()
        let mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-chat-media-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: mediaDirectory) }
        let media = MediaStorage(directory: mediaDirectory)
        service = makeService(media: media)

        var attachments: [MessageAttachment] = []
        for index in 1...5 {
            let data = try makeJPEG(width: 120 + index, height: 120)
            attachments.append(try media.saveImage(data: data, kind: .chatImage))
        }
        enqueueBodyOnlyDrop("收到")

        _ = try await collect(service.send(sessionId: sessionId, text: "都看看", attachments: attachments))

        let stored = try messages()
        XCTAssertEqual(
            AttachmentsCodec.decode(stored[0].attachmentsJson).count,
            AttachmentsCodec.maxImagesPerMessage
        )
    }

    /// 既没有字也没有图：不该落一条空消息，而是明确报「消息不能为空」。
    func testSendRejectsEmptyTextWithoutImages() async throws {
        try seedReadySession()
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "   "))
        } catch {
            thrown = error
        }
        let api = try XCTUnwrap(thrown as? ApiException)
        XCTAssertEqual(api.message, "消息不能为空")
        XCTAssertTrue(try messages().isEmpty, "失败时不该留下半截消息")
    }

    // MARK: - 发送被拒（0.10.2 审计 P1-10）

    /// 消息过长 / 会话被删这类错误发生在**落库之前**：`sendChecked` 必须告诉调用方
    /// 「这次没落库」，界面才能把草稿与图片还给用户（原来只能拿到一条只会抛错的流，
    /// 而那时草稿已经被清空了）。
    func testSendCheckedRejectsOversizedMessageWithoutPersistingOrDeletingAttachments() async throws {
        try seedReadySession()
        let mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-chat-media-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: mediaDirectory) }
        let media = MediaStorage(directory: mediaDirectory)
        service = makeService(media: media)
        let attachment = try media.saveImage(data: makeJPEG(width: 120, height: 120), kind: .chatImage)

        let tooLong = String(repeating: "字", count: ChatService.maxMessageLength + 1)
        let outcome = service.sendChecked(
            sessionId: sessionId, text: tooLong, attachments: [attachment]
        )
        guard case .rejected(let error) = outcome else {
            return XCTFail("超长消息应当被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "消息过长")
        XCTAssertTrue(try messages().isEmpty, "没落库就不能留下半截消息")
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "更不该发请求")
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: try XCTUnwrap(media.fileURL(for: attachment.uri)).path),
            "附件文件不能被删：界面会把这张图还给用户重试，删了就成孤儿/丢图"
        )
    }

    /// 被拒的另一种现实来源：会话在点发送的一瞬间被别人删掉了。
    func testSendCheckedRejectsMissingSession() throws {
        try seedReadySession()
        try stores.sessions.delete(id: sessionId)
        guard case .rejected(let error) = service.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("会话不存在时应当被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "会话不存在")
        XCTAssertTrue(try messages().isEmpty)
    }

    /// 真的开始生成时 `sendChecked` 返回 `.started`，并且用户那句已经落库。
    func testSendCheckedStartsAndPersistsUserMessage() async throws {
        try seedReadySession()
        enqueueSpoken("在")
        guard case .started(let stream) = service.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("正常发送应当返回 .started")
        }
        _ = try await collect(stream)
        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored.first?.content, "在吗")
    }

    /// 界面守卫：`ChatViewModel` 只有在 `.started` 之后才清空草稿/图片；`.rejected` 时保留并给出重试入口。
    func testChatViewModelOnlyClearsComposerAfterPersisted() throws {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var path: URL?
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("YetAnotherPal/Views/Chat/ChatViewModel.swift")
            if FileManager.default.fileExists(atPath: candidate.path) { path = candidate; break }
            current = current.deletingLastPathComponent()
        }
        let source = try String(contentsOf: try XCTUnwrap(path), encoding: .utf8)
        // 0.12 服务端模式之后，「走哪条链路」的判断收进了装配层：
        // `env.sendChecked(...)` 内部按模式选 `ChatService` 或 `ServerChatGateway`，
        // 但入口仍然必须是**能分辨「有没有落库」**的那个（两者都返回 `SendOutcome`）。
        XCTAssertTrue(
            source.contains("env.sendChecked("),
            "ViewModel 要走能分辨「有没有落库」的入口"
        )
        XCTAssertFalse(
            source.contains("env.chat.sendChecked("),
            "链路选择只该出现在 AppEnvironment 一处，不该在界面里再分叉"
        )
        XCTAssertTrue(source.contains("case .rejected(let error)"), "被拒要走单独分支")
        XCTAssertTrue(source.contains("sendFailure = message"), "被拒时要留一个可重试状态")
        // 清空动作必须只出现在 `.started` 分支里（``.rejected` 分支只留提示，不碰输入状态）。
        let rejected = try XCTUnwrap(source.range(of: "case .rejected(let error)"))
        let started = try XCTUnwrap(source.range(of: "case .started(let stream)"))
        XCTAssertTrue(started.lowerBound < rejected.lowerBound)
        let startedBranch = source[started.upperBound..<rejected.lowerBound]
        XCTAssertTrue(startedBranch.contains("draft = \"\""), "成功落库后才清草稿")
        XCTAssertTrue(startedBranch.contains("pendingAttachments.removeAll()"), "成功落库后才清已选图片")
        let rejectedBranch = source[rejected.upperBound..<source.endIndex]
        let rejectedEnd = try XCTUnwrap(rejectedBranch.range(of: "func dismissSendFailure"))
        let rejectedBody = rejectedBranch[..<rejectedEnd.lowerBound]
        XCTAssertFalse(rejectedBody.contains("draft = "), "被拒时不能动草稿")
        XCTAssertFalse(rejectedBody.contains("pendingAttachments.removeAll()"), "被拒时不能丢已选图片")
    }

    /// 文件不在时（从 Android 备份接回、被系统清掉）仍然能发新消息：
    /// 历史里那条只剩文字，不崩、不阻塞这一轮。
    func testHistoryWithMissingAttachmentFileStillGenerates() async throws {
        try seedReadySession()
        try stores.messages.upsert(MessageEntity(
            id: "m-missing", sessionId: sessionId, personaId: personaId, role: .user,
            content: "", createdAt: 1, updatedAt: 1, status: .complete,
            attachmentsJson: #"[{"uri":"file:///var/mobile/gone.jpg"}]"#
        ))
        enqueueBodyOnlyDrop("那张图我这边看不到了")

        _ = try await collect(service.send(sessionId: sessionId, text: "还在吗"))

        let requestMessages = try XCTUnwrap(try requestBody()["messages"] as? [[String: Any]])
        XCTAssertTrue((requestMessages[1]["content"] as? String ?? "").hasSuffix("用户：（发来一张图片）"),
                      "\(String(describing: requestMessages[1]["content"]))")
        XCTAssertEqual(requestMessages.count, 3)
    }

    // MARK: - 长会话只读窗口（0.11.0 P2-1）

    /// 400 条的会话里发一句：上下文窗口只从 SQLite 捞 `contextRecentLimit` 行。
    ///
    /// 为什么这条测试必须在**调用点**上做：`MessageStore.recent` 自己只返回 N 条，
    /// 但「读全文再 suffix」的写法返回值一模一样 —— 只有数物化了多少行才分得清。
    /// 0.10.2 审计 P2-1 说的就是这条路径（`ChatService.swift` 的 `bySession` + `suffix`）。
    func testLongSessionContextReadsWindowOnly() async throws {
        try seedReadySession()
        // 400 条历史：远超上下文窗口（200 条），也远超一屏。
        let history = (0..<400).map { index in
            TestData.message(
                id: String(format: "h-%03d", index),
                sessionId: sessionId,
                personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "历史第 \(index) 句",
                createdAt: 1_000 + Int64(index) * 1000
            )
        }
        try stores.messages.upsert(history)

        let reads = ReadLog()
        stores.messages.queryObserver = { method, rows in reads.record(method, rows) }
        defer { stores.messages.queryObserver = nil }

        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let snapshot = reads.snapshot()
        XCTAssertFalse(
            snapshot.contains { $0.method == "bySession" },
            "长会话不该整段读历史（实际读到：\(snapshot)）"
        )
        let contextReads = snapshot.filter { $0.method == "recentVisible" }
        XCTAssertEqual(contextReads.count, 1, "上下文窗口只查一次，实际：\(snapshot)")
        XCTAssertEqual(
            contextReads.first?.rows, PromptBuilder.contextRecentLimit,
            "窗口只该物化 \(PromptBuilder.contextRecentLimit) 行，而不是 400 行"
        )
        // 报文里也只带窗口内的消息：system + 窗口那 200 条（最后一条就是刚发的这句）。
        let requestMessages = try XCTUnwrap(try requestBody()["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages.count, PromptBuilder.contextRecentLimit + 1)
        XCTAssertTrue((requestMessages.last?["content"] as? String ?? "").hasSuffix("用户：在吗"),
                      "\(String(describing: requestMessages.last?["content"]))")
    }

    // MARK: - 造图

    private func makeJPEG(width: Int, height: Int) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.55, green: 0.35, blue: 0.25, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(
            destination, image,
            [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }
    // MARK: - 0.15 上下文 v2（固定顺序 / 预算 / 人格语言 / 工具轮数）

    /// 固定顺序（0.15 state_first）：当前状态 → 引导 → 人设 → 工具 → 反AI
    /// → 记忆/备忘录（长期 → 短期 → 主题）→ 语言 → 边界 → 历史。
    func testContextV2AssemblesFixedSegmentOrder() async throws {
        try seedReadySession()
        let memo = SessionMemoryRepository(stores: stores, now: { 1 })
        memo.applyLong(sessionId: sessionId, personaId: personaId,
                       summary: "他换了工作", userCount: 20, at: 1)
        memo.applyShort(sessionId: sessionId, personaId: personaId,
                        summary: "最近在赶方案", userCount: 5, at: 1)
        memo.applyTopics(sessionId: sessionId, personaId: personaId,
                         topics: [MemoryContract.TopicSegment(
                            timeLabel: "2026-11-18 晚", summary: "加班与改方案")],
                         userCount: 15, at: 1)
        try seedMemory("他怕冷。")
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let system = try systemPrompt()
        let long = try XCTUnwrap(system.range(of: MemoryContract.Injection.LONG_TERM_TITLE))
        let short = try XCTUnwrap(system.range(of: MemoryContract.Injection.SHORT_TERM_TITLE))
        let state = try XCTUnwrap(system.range(of: PersonaPromptScaffold.stateSection))
        let topic = try XCTUnwrap(system.range(of: MemoryContract.Injection.TOPIC_TITLE))
        let tools = try XCTUnwrap(system.range(of: ToolCatalog.capabilitySection))
        let guidance = try XCTUnwrap(system.range(of: PromptGuidance.sectionTitle))
        let persona = try XCTUnwrap(system.range(of: "# 你是谁"))
        let antiAI = try XCTUnwrap(system.range(of: AntiAIPrompt.section))
        XCTAssertLessThan(state.lowerBound, guidance.lowerBound, "状态在引导之前")
        XCTAssertLessThan(guidance.lowerBound, persona.lowerBound, "引导在人设（话风）之前")
        XCTAssertLessThan(persona.lowerBound, tools.lowerBound, "人设在工具之前")
        XCTAssertLessThan(tools.lowerBound, antiAI.lowerBound, "工具在反 AI 之前")
        XCTAssertLessThan(antiAI.lowerBound, long.lowerBound, "反 AI 在记忆之前")
        XCTAssertLessThan(long.lowerBound, short.lowerBound, "长期在短期之前")
        XCTAssertLessThan(short.lowerBound, topic.lowerBound, "短期在主题总结之前")
        XCTAssertTrue(system.contains("他换了工作"))
        XCTAssertTrue(system.contains("最近在赶方案"))
        XCTAssertTrue(system.contains("- 2026-11-18 晚：加班与改方案"))
        XCTAssertTrue(system.contains("他怕冷"))
    }

    /// 人格语言：`languageTag` 决定提示词语言，和页面语言（`languageDirective` 闭包）无关。
    func testPersonaLanguageTagControlsPromptLanguageEvenWhenPageIsChinese() async throws {
        try seedReadySession()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.languageTag = "en"
        try stores.personas.upsert(persona)
        enqueueBodyOnlyDrop("Sure.")
        _ = try await collect(service.send(sessionId: sessionId, text: "hi"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("# Your mood"), "英文人格的心情段要用英文标题：\(system)")
        XCTAssertTrue(system.contains("Think and speak in English."))
        XCTAssertTrue(system.contains("# What you can do"), "工具描述按人格语言")
        XCTAssertFalse(system.contains(MoodPrompt.sectionTitle), "不能同时出现中文心情段")
    }

    func testChinesePersonaKeepsChinesePrompt() async throws {
        try seedReadySession()
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        let system = try systemPrompt()
        XCTAssertTrue(system.contains(MoodPrompt.sectionTitle))
        XCTAssertTrue(system.contains("用中文思考和说话。"))
        XCTAssertFalse(system.contains("# Your mood"))
    }

    // MARK: - 模型优化开关（0.15，schema v20）

    /// `AUTO`（默认）+ DeepSeek 模型名 → 注入「# 工具使用」，而且排在工具能力清单之后。
    func testAutoProfileWithDeepSeekModelInjectsToolUsage() async throws {
        try seedReadySession()
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains(ModelProfilePrompt.section), "DeepSeek 档要注入工具使用")
        XCTAssertTrue(system.contains(ToolCatalog.setActivity))
        XCTAssertTrue(system.contains(ToolCatalog.memorize))
        XCTAssertTrue(system.contains(ToolCatalog.webSearch))
        let capability = try XCTUnwrap(system.range(of: ToolCatalog.capabilitySection))
        let toolUsage = try XCTUnwrap(system.range(of: ModelProfilePrompt.section))
        XCTAssertLessThan(capability.lowerBound, toolUsage.lowerBound,
                          "工具使用规则排在工具能力清单之后（同一段里）")
    }

    /// `AUTO` + 模型名含 `glm` → lean（不注入）。
    func testAutoProfileWithGLMModelSkipsToolUsage() async throws {
        try seedReadySession()
        var config = try XCTUnwrap(try stores.apiConfigs.byId(configId))
        config.model = "glm-4-flash"
        try stores.apiConfigs.upsert(config)
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let system = try systemPrompt()
        XCTAssertFalse(system.contains(ModelProfilePrompt.section), "GLM 走 lean，不注入：\(system)")
    }

    /// 显式选择优先于模型名：人格选了 DeepSeek，即使用 GLM 也不该 lean。
    func testExplicitDeepSeekProfileWinsOverGLMModelName() async throws {
        try seedReadySession()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.modelProfile = .deepseek
        try stores.personas.upsert(persona)
        var config = try XCTUnwrap(try stores.apiConfigs.byId(configId))
        config.model = "glm-4-flash"
        try stores.apiConfigs.upsert(config)
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertTrue(try systemPrompt().contains(ModelProfilePrompt.section))
    }

    /// 显式选择优先于模型名：人格选了 GLM，即使模型叫 deepseek 也不注入。
    func testExplicitGLMProfileSkipsToolUsageOnDeepSeekModel() async throws {
        try seedReadySession()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.modelProfile = .glm
        try stores.personas.upsert(persona)
        enqueueBodyOnlyDrop("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertFalse(try systemPrompt().contains(ModelProfilePrompt.section))
    }

    /// 英文人格的工具使用段用英文标题（不能把中文小标题塞进英文提示词）。
    func testEnglishPersonaGetsEnglishToolUsageBlock() async throws {
        try seedReadySession()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.languageTag = "en"
        try stores.personas.upsert(persona)
        enqueueBodyOnlyDrop("Sure.")
        _ = try await collect(service.send(sessionId: sessionId, text: "hi"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains(ModelProfilePrompt.sectionEN))
        XCTAssertFalse(system.contains(ModelProfilePrompt.section))
    }

    /// 模型用 `think.rounds` 指定这一次最多几轮工具：默认 5、指定值夹到 1..15。
    ///
    /// 逻辑在 `ChatService.toolRoundLimit(requested:)`（纯函数）：整条聊天链路里唯一的
    /// 一处「轮数上限」判定，测试不需要构造 SSE 与工具回路就能钉住三个分支。
    func testModelSpecifiedToolRoundsIsClamped() {
        XCTAssertEqual(ChatService.toolRoundLimit(requested: nil), 5)
        XCTAssertEqual(ChatService.toolRoundLimit(requested: 0), 5, "0 = 没指定，不是「只能 1 轮」")
        XCTAssertEqual(ChatService.toolRoundLimit(requested: -2), 5)
        XCTAssertEqual(ChatService.toolRoundLimit(requested: 1), 1)
        XCTAssertEqual(ChatService.toolRoundLimit(requested: 8), 8)
        XCTAssertEqual(ChatService.toolRoundLimit(requested: 40), 15, "上限 15")
    }

    /// 默认 5 轮（0.15）：旧值 3 轮会把「查 → 核对 → 记 → 说」截断。
    func testDefaultToolRoundsIsFive() {
        XCTAssertEqual(ChatService.maxToolRounds, 5)
        XCTAssertEqual(ExperienceContract.shared.clampToolRounds(requested: nil), 5)
        XCTAssertEqual(ExperienceContract.shared.clampToolRounds(requested: 15), 15)
        XCTAssertEqual(ExperienceContract.shared.clampToolRounds(requested: 99), 15)
    }
}

// MARK: - 读行数记录（P2-1 的验收）

/// `MessageStore.queryObserver` 的记录器：生成跑在别的 Task 上，所以要加锁。
final class ReadLog {
    struct Entry {
        var method: String
        var rows: Int
    }

    private var entries: [Entry] = []
    private let lock = NSLock()

    func record(_ method: String, _ rows: Int) {
        lock.lock()
        entries.append(Entry(method: method, rows: rows))
        lock.unlock()
    }

    func snapshot() -> [Entry] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }
}

// MARK: - 假工具执行器 / 假后台任务

private final class RecordingTools: ToolRunning {
    var resultFor: ((ToolInvocation) -> ToolResult)?
    private(set) var callCount = 0

    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        callCount += 1
        if let resultFor { return resultFor(invocation) }
        return ToolResult(invocation: invocation, ok: true, summary: "做完了", contentForModel: "结果")
    }
}

private final class RecordingBackground: ChatBackgroundTasks {
    private(set) var memoryCalls: [(sessionId: String, personaId: String)] = []
    private(set) var compressionCalls: [String] = []

    func memoryExtractionNeeded(sessionId: String, personaId: String) {
        memoryCalls.append((sessionId, personaId))
    }

    func historyCompressionNeeded(sessionId: String) {
        compressionCalls.append(sessionId)
    }
}

// MARK: - SSE 夹具

private extension StubURLProtocol.Stub {
    /// 「只有文字增量 + [DONE]」的最简流。
    static func sse(textDeltas: [String], usage: (Int, Int)? = nil) -> StubURLProtocol.Stub {
        var text = ""
        for delta in textDeltas {
            let escaped = delta
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
                .replacingOccurrences(of: "\n", with: "\\n")
            text += "data: {\"choices\":[{\"delta\":{\"content\":\"\(escaped)\"}}]}\n\n"
        }
        if let usage {
            text += "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":\(usage.0),\"completion_tokens\":\(usage.1)}}\n\n"
        }
        text += "data: [DONE]\n\n"
        return .sse(text)
    }
}
