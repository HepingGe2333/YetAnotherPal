import Foundation
import XCTest
@testable import YapKit

/// 语音转写的调度层（`AsrTranscriber`）：什么时候转、失败怎么标记、什么时候**根本不发请求**。
///
/// 这一层的价值在于「发送链路不需要判断任何事」：
/// - 关着 / 没 Key / 地址写错 → 静默跳过，附件原样（老数据一个字节都不变）；
/// - 转写失败 → 附件没有 transcript，但打上 `FAILED` 标记（气泡显示「没有转写」）；
/// - 任何情况都**不抛错**：语音该发还是发，绝不让一次转写失败拦住一条消息。
final class AsrTranscriberTests: XCTestCase {

    /// 假客户端：只记录「被怎么调」，结果由测试指定。
    private final class FakeClient: AsrTranscribing {
        struct Call: Equatable {
            var fileName: String
            var mime: String
            var language: String
            var model: String
            var baseUrl: String
            var apiKey: String
            var byteCount: Int
        }

        var result: Result<String, Error>
        private(set) var calls: [Call] = []

        init(result: Result<String, Error>) {
            self.result = result
        }

        func resetCalls() { calls.removeAll() }

        func transcribe(
            audio data: Data,
            fileName: String,
            mime: String,
            config: AsrConfig,
            language: String
        ) async throws -> String {
            calls.append(
                Call(
                    fileName: fileName, mime: mime, language: language,
                    model: config.resolvedModel, baseUrl: config.resolvedBaseUrl,
                    apiKey: config.trimmedAPIKey, byteCount: data.count
                )
            )
            return try result.get()
        }
    }

    private final class LogCollector {
        private let lock = NSLock()
        private var storage: [String] = []
        func append(_ line: String) { lock.lock(); defer { lock.unlock() }; storage.append(line) }
        var joined: String { lock.lock(); defer { lock.unlock() }; return storage.joined(separator: "\n") }
    }

    private var root: URL!
    private var store: AudioVideoStore!
    private var audio: MessageAttachment!

    override func setUpWithError() throws {
        try super.setUpWithError()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-asr-transcriber-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        store = AudioVideoStore(
            audioDirectory: root.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: root.appendingPathComponent("video", isDirectory: true)
        )
        audio = try store.saveAudio(data: Data([0x01, 0x02, 0x03, 0x04]), durationMs: 3_000)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
        root = nil
        store = nil
        audio = nil
        try super.tearDownWithError()
    }

    private func config(
        enabled: Bool = true,
        baseUrl: String = "https://api.deepseek.com",
        model: String = "whisper-1",
        apiKey: String? = "sk-asr-test"
    ) -> AsrConfig {
        AsrConfig(enabled: enabled, baseUrl: baseUrl, model: model, apiKey: apiKey)
    }

    private func makeTranscriber(
        client: FakeClient,
        config: AsrConfig? = nil,
        log: LogCollector? = nil
    ) -> AsrTranscriber {
        AsrTranscriber(
            client: client,
            store: store,
            configProvider: { config ?? self.config() },
            log: { line in log?.append(line) }
        )
    }

    // MARK: - 成功

    func testSuccessStoresTranscriptOnAttachment() async {
        let client = FakeClient(result: .success("今天有点累"))
        let transcriber = makeTranscriber(client: client)

        let result = await transcriber.transcribe([audio])
        XCTAssertEqual(result.transcribedCount, 1)
        XCTAssertEqual(result.failedCount, 0)
        XCTAssertFalse(result.hasFailure)

        let updated = result.attachments[0]
        XCTAssertEqual(updated.transcript, "今天有点累")
        XCTAssertEqual(updated.transcriptState, .succeeded)
        XCTAssertTrue(updated.hasTranscript)
        XCTAssertFalse(updated.transcriptIsMissing)
        // 原媒体信息原样保留（转写只是多一行字，不动 kind/mime/时长/uri）。
        XCTAssertEqual(updated.uri, audio.uri)
        XCTAssertEqual(updated.mime, audio.mime)
        XCTAssertEqual(updated.durationMs, 3_000)
        XCTAssertTrue(updated.isAudio)

        // 调用参数：模型/语言/文件名/mime 都对。
        XCTAssertEqual(client.calls.count, 1)
        let call = client.calls[0]
        XCTAssertEqual(call.model, "whisper-1")
        XCTAssertEqual(call.language, "zh")
        XCTAssertEqual(call.mime, "audio/m4a")
        XCTAssertEqual(call.fileName, "audio.m4a")
        XCTAssertEqual(call.baseUrl, "https://api.deepseek.com")
        XCTAssertEqual(call.byteCount, 4)
    }

    func testAnnotateReturnsAttachmentsInOriginalOrder() async throws {
        let video = try makeVideoAttachment()
        let image = MessageAttachment.image(uri: "file:///a.jpg")
        let audioAttachment = audio!
        let client = FakeClient(result: .success("听见了"))
        let result = await makeTranscriber(client: client).transcribe([image, audioAttachment, video])

        XCTAssertEqual(result.attachments.count, 3)
        XCTAssertEqual(result.attachments[0].uri, image.uri, "顺序不能变")
        XCTAssertEqual(result.attachments[1].transcript, "听见了")
        XCTAssertEqual(result.attachments[2].transcript, "听见了")
        XCTAssertTrue(result.attachments[0].isImage)
        XCTAssertTrue(result.attachments[2].isVideo)
        XCTAssertEqual(result.transcribedCount, 2)
        XCTAssertEqual(result.skippedCount, 1)
    }

    // MARK: - 失败

    func testFailureKeepsNoTranscriptAndMarksIt() async {
        let client = FakeClient(result: .failure(AsrError.unauthorized(401)))
        let result = await makeTranscriber(client: client).transcribe([audio])

        let updated = result.attachments[0]
        XCTAssertNil(updated.transcript, "失败绝不能编一段文字")
        XCTAssertEqual(updated.transcriptState, .failed)
        XCTAssertTrue(updated.transcriptIsMissing)
        XCTAssertTrue(result.hasFailure)
        XCTAssertEqual(result.failedCount, 1)

        guard case .failed(let error) = result.results[0].outcome else {
            return XCTFail("outcome 应当是 .failed")
        }
        XCTAssertEqual(error, .unauthorized(401))
        XCTAssertEqual(result.results[0].outcome.failureMessage, AsrError.unauthorized(401).message)
        XCTAssertFalse(result.results[0].outcome.isTranscribed)
    }

    func testTimeoutIsFailureAndRetryable() async {
        let client = FakeClient(result: .failure(AsrError.timedOut))
        let result = await makeTranscriber(client: client).transcribe([audio])
        guard case .failed(let error) = result.results[0].outcome else {
            return XCTFail("超时应当是 .failed")
        }
        XCTAssertEqual(error, .timedOut)
        XCTAssertTrue(error.isRetryable)
        XCTAssertTrue(result.attachments[0].transcriptIsMissing)
    }

    /// 未知错误也按「失败」处理（不往上抛，不阻断发送）。
    func testUnexpectedErrorBecomesNetworkFailure() async {
        struct Boom: Error {}
        let client = FakeClient(result: .failure(Boom()))
        let result = await makeTranscriber(client: client).transcribe([audio])
        guard case .failed(let error) = result.results[0].outcome else {
            return XCTFail("未知错误应归一成 .failed")
        }
        if case .network = error {} else { XCTFail("应当是 .network：\(error)") }
        XCTAssertTrue(result.attachments[0].transcriptIsMissing)
    }

    /// 取消（用户点了停止）不算「失败」，不打标记。
    func testCancellationIsSkippedNotFailed() async {
        let client = FakeClient(result: .failure(CancellationError()))
        let result = await makeTranscriber(client: client).transcribe([audio])
        guard case .skipped(let reason) = result.results[0].outcome else {
            return XCTFail("取消应当是 .skipped")
        }
        XCTAssertEqual(reason, .cancelled)
        XCTAssertNil(result.attachments[0].transcript)
        XCTAssertNil(result.attachments[0].transcriptState, "取消不该留下「没有转写」的标记")
        XCTAssertFalse(result.hasFailure)
    }

    // MARK: - 根本不发请求的情况

    func testDisabledSkipsWithoutRequest() async {
        let client = FakeClient(result: .success("不该被调用"))
        let result = await makeTranscriber(client: client, config: config(enabled: false)).transcribe([audio])
        XCTAssertTrue(client.calls.isEmpty, "关着转写不该发请求")
        guard case .skipped(.disabled) = result.results[0].outcome else {
            return XCTFail("应当是 .skipped(.disabled)")
        }
        XCTAssertNil(result.attachments[0].transcript)
        XCTAssertNil(result.attachments[0].transcriptState)
        XCTAssertFalse(result.attachments[0].transcriptIsMissing)
    }

    func testMissingKeySkipsWithoutRequest() async {
        let client = FakeClient(result: .success("不该被调用"))
        for key in [nil, "", "   "] {
            client.resetCalls()
            let result = await makeTranscriber(client: client, config: config(apiKey: key)).transcribe([audio])
            XCTAssertTrue(client.calls.isEmpty, "没 Key 不该发请求（\(key ?? "nil")）")
            guard case .skipped(.missingKey) = result.results[0].outcome else {
                return XCTFail("应当是 .skipped(.missingKey)")
            }
        }
    }

    /// 地址写错（这里是明文 http）——先改设置，不是这条语音的错；不打「没有转写」。
    func testInvalidConfigSkipsWithoutRequest() async {
        let client = FakeClient(result: .success("不该被调用"))
        let result = await makeTranscriber(
            client: client, config: config(baseUrl: "http://127.0.0.1:8787")
        ).transcribe([audio])
        XCTAssertTrue(client.calls.isEmpty)
        guard case .skipped(.invalidConfig(let error)) = result.results[0].outcome else {
            return XCTFail("应当是 .skipped(.invalidConfig)")
        }
        XCTAssertEqual(error, .insecureBaseUrl)
        XCTAssertNil(result.attachments[0].transcriptState)
    }

    func testNonPlayableAttachmentIsSkipped() async {
        let client = FakeClient(result: .success("不该被调用"))
        let image = MessageAttachment.image(uri: "file:///a.jpg")
        let result = await makeTranscriber(client: client).transcribe([image])
        XCTAssertTrue(client.calls.isEmpty)
        guard case .skipped(.notPlayable) = result.results[0].outcome else {
            return XCTFail("应当是 .skipped(.notPlayable)")
        }
    }

    func testMissingLocalFileIsSkipped() async {
        let client = FakeClient(result: .success("不该被调用"))
        let missing = MessageAttachment.audio(uri: "file:///nowhere/audio-x.m4a", durationMs: 1_000)
        let result = await makeTranscriber(client: client).transcribe([missing])
        XCTAssertTrue(client.calls.isEmpty)
        guard case .skipped(.fileUnavailable) = result.results[0].outcome else {
            return XCTFail("应当是 .skipped(.fileUnavailable)")
        }
    }

    /// 服务端媒体引用（本机没有文件）：跳过（不猜、不偷偷下载）。
    func testServerMediaReferenceIsSkipped() async {
        let client = FakeClient(result: .success("不该被调用"))
        let remote = MessageAttachment.audio(uri: "/v1/media/media-123", durationMs: 2_000)
        let result = await makeTranscriber(client: client).transcribe([remote])
        XCTAssertTrue(client.calls.isEmpty)
        guard case .skipped(.fileUnavailable) = result.results[0].outcome else {
            return XCTFail("应当是 .skipped(.fileUnavailable)")
        }
    }

    // MARK: - 配置读取

    /// 每次转写都重新读配置：用户在设置页改了模型，下一条语音立刻生效。
    func testConfigIsReadPerAttachment() async {
        var current = config(model: "whisper-1")
        let client = FakeClient(result: .success("x"))
        let transcriber = AsrTranscriber(
            client: client, store: store, configProvider: { current }
        )
        _ = await transcriber.transcribe([audio])
        current = config(model: "whisper-large-v3")
        _ = await transcriber.transcribe([audio])
        XCTAssertEqual(client.calls.map(\.model), ["whisper-1", "whisper-large-v3"])
    }

    func testLiveFactoryUsesSettingsAndKeychain() async throws {
        var settings = AppSettings(asrEnabled: true, asrBaseUrl: "https://asr.example.com", asrModel: "whisper-1")
        settings.asrEnabled = true
        let client = FakeClient(result: .success("x"))
        let transcriber = AsrTranscriber.live(
            settings: { settings },
            apiKey: { "sk-from-keychain" },
            client: client,
            store: store
        )
        _ = await transcriber.transcribe([audio])
        XCTAssertEqual(client.calls.count, 1)
        XCTAssertEqual(client.calls[0].apiKey, "sk-from-keychain")
        XCTAssertEqual(client.calls[0].baseUrl, "https://asr.example.com")
    }

    // MARK: - 不泄漏

    /// 日志只记「成功/失败/字数」，不记 Key，也不记转写正文。
    func testLogsNeverContainKeyOrTranscript() async {
        let collector = LogCollector()
        let client = FakeClient(result: .success("这句话不该出现在日志里"))
        _ = await makeTranscriber(client: client, log: collector).transcribe([audio])
        XCTAssertFalse(collector.joined.contains("sk-asr-test"), collector.joined)
        XCTAssertFalse(collector.joined.contains("这句话不该出现在日志里"), collector.joined)

        let failing = LogCollector()
        let failingClient = FakeClient(result: .failure(AsrError.unauthorized(401)))
        _ = await makeTranscriber(client: failingClient, log: failing).transcribe([audio])
        XCTAssertFalse(failing.joined.contains("sk-asr-test"), failing.joined)
        XCTAssertTrue(failing.joined.contains("失败"), failing.joined)
    }

    // MARK: - 小工具

    private func makeVideoAttachment() throws -> MessageAttachment {
        let source = root.appendingPathComponent("source-\(UUID().uuidString).mp4")
        try Data([0x00, 0x00, 0x00, 0x18]).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }
        return try store.importVideo(at: source, durationMs: 5_000)
    }
}
