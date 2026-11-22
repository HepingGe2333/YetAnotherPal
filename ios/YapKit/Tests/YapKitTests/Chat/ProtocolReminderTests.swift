import Foundation
import XCTest
@testable import YapKit

/// 0.15.7 工具协议**自动提醒**（`ProtocolReminder` + `ChatService` 里的重试分支）。
///
/// 现状（0.16）：话只能通过 `send_message` 说出去，直接写正文只做 legacy 兼容。
/// 但模型偶尔会「写得很自然、忘了调工具」——这时先提醒它一次，而不是默默走旧协议：
///
/// 1. 提醒后正确调用 `send_message` → 正常落库（**一条**消息，不是两条）；
/// 2. 提醒后仍然只写正文 → 才退回 legacy 落库（用的是它**最新**写的那段）；
/// 3. 提醒后 `stay_silent` → 照常沉默（什么都不落）；
/// 4. 一开始就按协议调用 → 根本没有第二次请求，也不会有 `protocol_reminder` 日志。
///
/// 另外两条硬约束：提醒**不落库**、**不改变沉默限制**（沉默那一条用例同时验证后者）。
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

    // MARK: - 1. 提醒之后正确调用 send_message

    func testReminderThenSendMessageDeliversOneMessage() async throws {
        var result = ToolResult(
            invocation: ToolInvocation(id: "call-1", name: ToolCatalog.sendMessage, argumentsJson: "{}"),
            ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        result.outboundText = "早点睡吧。"
        tools.results = [result]

        // 第 1 次：只写正文，没有工具调用（违规）；第 2 次：正确调用 send_message；
        // 第 3 次：工具结果回灌之后它只回了一个空白（这一轮说完就停，不再多说一句）。
        enqueueText("早点睡吧。")
        enqueueText(sendMessagePayload("早点睡吧。"))
        enqueueText(" ")

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 3, "提醒之后要带 tools 再请求一次（再一次是工具回灌）")
        // 第二次请求里：正文作为 assistant、提醒作为**不可见**的 user 跟在后面。
        let second = try requestMessages(at: 1)
        XCTAssertTrue(second.contains { $0.role == "assistant" && $0.content.contains("早点睡吧。") },
                      "模型刚才写的正文要作为 assistant 交回去：\(second)")
        XCTAssertTrue(second.contains {
            $0.role == "user" && $0.content == ProtocolReminder.chineseText
        }, "要追加一条不可见的 user 提醒：\(second)")

        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["早点睡吧。"], "正确调用之后只落一条消息（不能再走 legacy 多一条）")
        XCTAssertEqual(tools.callCount, 1, "send_message 真的被执行了一次")
        XCTAssertEqual(assistant.count, 1)
        XCTAssertEqual(noReplyEvents(events).count, 0, "正常发送不该出现「不回」")
        XCTAssertTrue(reminderLines.contains { $0.contains("protocol_reminder") },
                      "要留下提醒日志：\(logs)")
        // 提醒本身不落库：库里除了用户那句就只有他发出去的那一条。
        XCTAssertFalse(try storedMessages().contains { $0.content.contains("系统提醒") })
    }

    // MARK: - 2. 提醒之后仍然违规 → legacy

    func testReminderStillViolatingFallsBackToLegacy() async throws {
        enqueueText("在呢。")
        enqueueText("在呢，别担心。")

        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 2, "同一轮最多提醒 1 次（第三次请求就是死循环）")
        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["在呢，别担心。"],
                       "仍然违规时按 legacy 落库，并且用的是它**最新**写的那段")
        XCTAssertEqual(tools.callCount, 0, "这一轮没有任何工具被调用")
        XCTAssertTrue(reminderLines.contains { $0.contains("protocol_reminder") })
        XCTAssertTrue(logs.contains { $0.contains(OutboundProtocol.legacyFallbackLogTag) },
                      "legacy 回退仍然要留下标记（可观测）")
    }

    /// 提醒之后**什么都没说**（空正文、无工具）：用提醒前那段兜底，不能把这句话弄丢。
    func testReminderThenSilenceFallsBackToTheEarlierBody() async throws {
        enqueueText("我在的。")
        // 第二次：只回了一个空白（既没有正文，也没有工具调用）。
        // 不能真的回「空流」：客户端把「一个字都没吐出来」当成可重试的错误，会再发一次请求。
        enqueueText(" ")

        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let assistant = try storedMessages().filter { $0.role == .assistant }
        XCTAssertEqual(assistant.map(\.content), ["我在的。"], "提醒之后没有输出时，按提醒前的正文收尾")
        XCTAssertTrue(reminderLines.contains { $0.contains("按提醒前的正文收尾") })
    }

    // MARK: - 3. 提醒之后 stay_silent → 正常沉默

    func testReminderThenStaySilentHonorsSilence() async throws {
        enqueueText("嗯。")
        enqueueText(staySilentPayload())

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 2, "沉默是提醒之后的有效回答，不需要再请求")
        XCTAssertEqual(try storedMessages().map(\.role), [.user], "沉默这一轮不落任何助手消息")
        XCTAssertEqual(noReplyEvents(events).count, 1, "界面要看到「这一轮不回」")
        if case .stayedSilent = noReplyEvents(events).first {} else {
            XCTFail("应当是 stay_silent 的「不回」：\(noReplyEvents(events))")
        }
        XCTAssertEqual(tools.callCount, 0, "stay_silent 不是工具，不该被执行")
        XCTAssertTrue(reminderLines.contains { $0.contains("protocol_reminder") })
    }

    // MARK: - 4. 正常按协议调用：根本不提醒

    func testNoReminderWhenProtocolFollowed() async throws {
        var result = ToolResult(
            invocation: ToolInvocation(id: "call-1", name: ToolCatalog.sendMessage, argumentsJson: "{}"),
            ok: true, summary: "发了条消息", contentForModel: "发出去了"
        )
        result.outboundText = "在呢。"
        tools.results = [result]
        enqueueText(sendMessagePayload("在呢。"))
        enqueueText(" ")

        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 2,
                       "按协议走时只有「工具回灌」那一次请求，没有提醒")
        XCTAssertTrue(reminderLines.isEmpty, "不该出现提醒日志：\(logs)")
        XCTAssertEqual(try storedMessages().filter { $0.role == .assistant }.map(\.content), ["在呢。"])
    }

    /// 英文人格拿到英文提醒（中文提醒会被它当成设定而不是指令）。
    func testEnglishPersonaGetsEnglishReminder() async throws {
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.languageTag = "en"
        try stores.personas.upsert(persona)
        enqueueText("I'm here.")
        enqueueText("I'm here, don't worry.")

        _ = try await collect(service.send(sessionId: sessionId, text: "you there?"))

        let second = try requestMessages(at: 1)
        XCTAssertTrue(second.contains {
            $0.role == "user" && $0.content == ProtocolReminder.englishText
        }, "英文人格应当读到英文提醒：\(second)")
        XCTAssertFalse(second.contains { $0.content == ProtocolReminder.chineseText })
    }

    // MARK: - 提醒文案本身

    func testReminderTextEnumeratesTheThreeExits() {
        for text in [ProtocolReminder.chineseText, ProtocolReminder.englishText] {
            XCTAssertTrue(text.contains("send_message"), "要发消息 → send_message：\(text)")
            XCTAssertTrue(text.contains("stay_silent"), "不想发 → stay_silent：\(text)")
        }
        XCTAssertTrue(ProtocolReminder.chineseText.contains("对应工具"), "要做别的事 → 对应工具")
        XCTAssertTrue(ProtocolReminder.chineseText.contains("（系统提醒）"))
        XCTAssertTrue(ProtocolReminder.chineseText.contains("不要直接输出正文"))
        XCTAssertTrue(ProtocolReminder.englishText.contains("the matching tool"))
        XCTAssertEqual(
            ProtocolReminder.text(for: PersonaEntity(id: "p-1", name: "林深", languageTag: "zh")),
            ProtocolReminder.chineseText
        )
        XCTAssertEqual(
            ProtocolReminder.text(for: PersonaEntity(id: "p-1", name: "Lin", languageTag: "en")),
            ProtocolReminder.englishText
        )
        XCTAssertEqual(ProtocolReminder.logTag, "protocol_reminder")
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
