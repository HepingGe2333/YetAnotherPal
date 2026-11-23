import Foundation
import XCTest
@testable import YapKit

/// 0.13 聊天主链路上的音视频：当轮语音真的以 `input_audio` 发给模型、历史只留一行说明、
/// 不支持音视频的模型 4xx 翻译成明确报错、`listen` 工具结果的多模态内容进入下一轮请求。
final class ChatServiceAudioVideoTests: XCTestCase {

    private var directoryURL: URL!
    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var service: ChatService!
    private var audioVideo: AudioVideoStore!
    private var mediaStorage: MediaStorage!
    private var mediaPartsBuilder: FakeMediaPartsBuilder!
    private var tools: FakeToolRunner!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-av-chat-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        databaseURL = directoryURL.appendingPathComponent("db.sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        settings = InMemorySettings(AppSettings())
        mediaStorage = MediaStorage(directory: directoryURL.appendingPathComponent("attachments"))
        audioVideo = AudioVideoStore(
            audioDirectory: directoryURL.appendingPathComponent("audio"),
            videoDirectory: directoryURL.appendingPathComponent("video")
        )
        mediaPartsBuilder = FakeMediaPartsBuilder()
        tools = FakeToolRunner()

        try stores.personas.upsert(PersonaEntity(
            id: personaId, name: "小满", prompt: "你是小满，说话很轻。", version: 1,
            isDefault: true, updatedAt: 1, sleepEnabled: false
        ))
        try stores.apiConfigs.upsert(ApiConfigEntity(
            id: configId, name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", updatedAt: 1
        ))
        try stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, apiConfigId: configId, model: "",
            title: "与小满的对话", createdAt: 1, updatedAt: 1,
            lastPersonaVersion: 1, lastMemoryExtractedCount: 0
        ))
        try apiKeys.setAPIKey("sk-test", for: configId)
        service = makeService()
        AgentCoreDiagnostics.shared.reset()
    }

    override func tearDownWithError() throws {
        service = nil
        AgentCoreDiagnostics.shared.reset()
        StubURLProtocol.reset()
        stores = nil
        try? FileManager.default.removeItem(at: directoryURL)
        try super.tearDownWithError()
    }

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
            media: mediaStorage,
            audioVideo: audioVideo,
            mediaParts: mediaPartsBuilder,
            agentCore: NativeAgentCoreClient()
        )
    }

    // MARK: - 夹具

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func requestBody(at index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "没有记录到第 \(index) 次请求")
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: bodies[index]) as? [String: Any])
    }

    private func requestMessages(at index: Int = 0) throws -> [[String: Any]] {
        try XCTUnwrap(try requestBody(at: index)["messages"] as? [[String: Any]])
    }

    private func storedMessages() throws -> [MessageEntity] {
        try stores.messages.recent(sessionId: sessionId, limit: 50)
    }


    /// 0.15 协议：`send_message` 的协议块（与生产协议同形）。
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

    private func makeAudio(
        durationMs: Int = 12_000,
        bytes: Int = 1_024
    ) throws -> MessageAttachment {
        try audioVideo.saveAudio(
            data: Data((0..<bytes).map { UInt8($0 % 251) }), durationMs: durationMs
        )
    }

    private func seedUserAudioMessage(_ attachment: MessageAttachment, at: Int64) throws {
        try stores.messages.upsert(MessageEntity(
            id: "old-audio", sessionId: sessionId, personaId: personaId, role: .user,
            content: "", createdAt: at, updatedAt: at,
            attachmentsJson: AttachmentsCodec.encode([attachment])
        ))
    }

    // MARK: - 当轮语音

    func testAudioMessagePersistsAndIsSentAsInputAudioPart() async throws {
        let audio = try makeAudio()
        mediaPartsBuilder.partsToReturn = MediaWireParts(
            audio: [WireAudioPart(
                base64: (try XCTUnwrap(audioVideo.data(for: audio))).base64EncodedString(),
                format: "m4a"
            )]
        )
        enqueueSpoken("嗯，我在听")
        _ = try await collect(service.send(sessionId: sessionId, text: "听我说", attachments: [audio]))

        // 1) 落库：kind/durationMs 都在（界面据此画语音气泡）。
        let stored = try storedMessages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        let attachment = try XCTUnwrap(AttachmentsCodec.decode(stored[0].attachmentsJson).first)
        XCTAssertTrue(attachment.isAudio)
        XCTAssertEqual(attachment.durationMs, 12_000)

        // 2) 报文：当轮那条 user 消息是 parts 数组，含 text + input_audio。
        let messages = try requestMessages()
        let user = try XCTUnwrap(messages.last)
        XCTAssertEqual(user["role"] as? String, "user")
        let parts = try XCTUnwrap(user["content"] as? [[String: Any]])
        XCTAssertEqual(parts.first?["type"] as? String, "text")
        let text = try XCTUnwrap(parts.first?["text"] as? String)
        XCTAssertTrue(text.contains("语音"))
        XCTAssertTrue(text.contains("listen"))
        let audioPart = try XCTUnwrap(parts.first { $0["type"] as? String == "input_audio" })
        let payload = try XCTUnwrap(audioPart["input_audio"] as? [String: Any])
        XCTAssertEqual(payload["format"] as? String, "m4a")
        XCTAssertEqual(
            payload["data"] as? String,
            (try XCTUnwrap(audioVideo.data(for: audio))).base64EncodedString()
        )
    }

    func testHistoryDoesNotResendOldAudioBase64() async throws {
        let oldAudio = try makeAudio(durationMs: 9_000)
        try seedUserAudioMessage(oldAudio, at: 1)
        mediaPartsBuilder.partsToReturn = nil
        enqueueSpoken("嗯")
        _ = try await collect(service.send(sessionId: sessionId, text: "刚说到哪了"))

        let messages = try requestMessages()
        // 历史里那条语音只剩一行说明，没有 input_audio。
        let texts = messages.compactMap { $0["content"] as? String }
        XCTAssertTrue(
            texts.contains { $0.contains("语音") && $0.contains("audio-") },
            "历史里要说明「这条消息是一段语音」并给媒体 id：\(texts)"
        )
        let raw = try JSONSerialization.data(withJSONObject: messages)
        XCTAssertFalse(
            String(decoding: raw, as: UTF8.self).contains("input_audio"),
            "历史里不该重复发送音频 base64"
        )
    }

    // MARK: - 不支持的模型

    func testUnsupportedModel4xxBecomesExplicitAudioError() async throws {
        let audio = try makeAudio()
        mediaPartsBuilder.partsToReturn = MediaWireParts(
            audio: [WireAudioPart(base64: "AAAA", format: "m4a")]
        )
        StubURLProtocol.enqueue(.json(
            #"{"error":{"message":"unsupported content type","code":"invalid_request"}}"#,
            status: 400
        ))
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "听", attachments: [audio]))
        } catch {
            thrown = error
        }
        let api = try XCTUnwrap(thrown as? ApiException)
        XCTAssertEqual(api.message, "这个模型听不了语音")
        XCTAssertEqual(api.statusCode, 400)
        // 用户那句仍然落库（失败的是「他听不了」，不是「你没发出去」）。
        let stored = try storedMessages()
        XCTAssertEqual(stored.first?.role, .user)
    }

    /// 401 与「听不了」无关：不能被翻译成模态错误。
    func testUnrelatedAuthErrorIsNotRewritten() async throws {
        let audio = try makeAudio()
        mediaPartsBuilder.partsToReturn = MediaWireParts(
            audio: [WireAudioPart(base64: "AAAA", format: "m4a")]
        )
        StubURLProtocol.enqueue(.json(#"{"error":{"message":"bad key"}}"#, status: 401))
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "听", attachments: [audio]))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.statusCode, 401)
        XCTAssertEqual((thrown as? ApiException)?.message.contains("401"), true)
    }

    // MARK: - 拒绝超限（落库之前）

    func testTooLongAudioIsRejectedBeforePersisting() throws {
        let audio = try makeAudio(durationMs: 60_000)
        var tooLong = audio
        tooLong.durationMs = AudioVideoLimits.audioMaxDurationMs + 1
        guard case .rejected(let error) = service.sendChecked(
            sessionId: sessionId, text: "听", attachments: [tooLong]
        ) else {
            return XCTFail("超时长的语音应当在落库之前被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "语音最长 5 分钟")
        XCTAssertTrue(try storedMessages().isEmpty)
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "更不该发请求")
        XCTAssertNotNil(audioVideo.fileURL(for: audio), "附件文件要留给用户重试，不能被删")
    }

    // MARK: - listen 工具的多模态回灌

    func testListenToolResultIsFedBackAsAudioPartInNextRequest() async throws {
        StubURLProtocol.enqueue(
            .sse(textDeltas: ["我听听。\n```yap-tool\n{\"name\":\"listen\",\"arguments\":{}}\n```"])
        )
        // 0.15 协议：工具轮之后模型用 `send_message` 把话发出去，再回灌一轮空白收尾。
        StubURLProtocol.enqueue(.sse(textDeltas: [Self.sendMessageBlock("听完了，你说得挺轻的。")]))
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
                invocation: invocation,
                ok: true,
                summary: "听了一遍",
                contentForModel: "我听了一遍这段语音。",
                traceTitle: "听了一遍",
                mediaParts: MediaWireParts(audio: [WireAudioPart(base64: "QUJD", format: "m4a")])
            )
        }
        _ = try await collect(service.send(sessionId: sessionId, text: "听听我刚才说的"))

        XCTAssertEqual(StubURLProtocol.recordedBodies.count, 3,
                       "工具一轮 + 发送消息一轮 + 工具回灌一轮")
        let second = try requestMessages(at: 1)
        let reinjected = try XCTUnwrap(second.first { message in
            (message["content"] as? [[String: Any]])?.contains {
                $0["type"] as? String == "input_audio"
            } == true
        })
        let parts = try XCTUnwrap(reinjected["content"] as? [[String: Any]])
        let audioPart = try XCTUnwrap(parts.first { $0["type"] as? String == "input_audio" })
        XCTAssertEqual((audioPart["input_audio"] as? [String: Any])?["data"] as? String, "QUJD")
        let textParts = parts.compactMap { $0["text"] as? String }.joined(separator: "\n")
        XCTAssertTrue(textParts.contains("我听了一遍"), "工具结果那句话要一起回灌")
    }
}

// MARK: - 假实现

private final class FakeMediaPartsBuilder: MediaPartsBuilding {
    var partsToReturn: MediaWireParts?
    private(set) var requested: [[MessageAttachment]] = []

    func parts(for attachments: [MessageAttachment]) async -> MediaWireParts? {
        requested.append(attachments)
        return partsToReturn
    }
}

private final class FakeToolRunner: ToolRunning {
    var resultFor: ((ToolInvocation) -> ToolResult)?
    private(set) var calls: [ToolInvocation] = []

    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        calls.append(invocation)
        if let resultFor { return resultFor(invocation) }
        return ToolResult(invocation: invocation, ok: true, summary: "做完了", contentForModel: "结果")
    }
}

private extension StubURLProtocol.Stub {
    /// 「只有文字增量 + [DONE]」的最简流。
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
