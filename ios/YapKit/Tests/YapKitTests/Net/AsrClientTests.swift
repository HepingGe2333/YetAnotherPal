import Foundation
import XCTest
@testable import YapKit

/// 语音转写（ASR）网络客户端：端点拼装、multipart 报文、成功/失败/超时分类、以及「不泄漏 Key」。
///
/// 为什么这些要逐条钉住：
/// 1. **端点**：用户会把 `https://api.deepseek.com`、带 `/v1`、带尾斜杠、甚至整条
///    `/audio/transcriptions` 粘进来 —— 四种都得拼对，否则「语音发了没有转写」；
/// 2. **报文形状**：`file/model/language` 三个字段、`Authorization: Bearer`、60 秒超时，
///    少一个服务端就 400，用户只会看到「转写失败」；
/// 3. **错误分类**：401（Key 错）和 429（限流）对用户是两件事，不能都写成「失败了」；
/// 4. **不泄漏**：日志里不能出现 Key，也不能出现转写正文（那可能是很私人的话）。
final class AsrClientTests: XCTestCase {

    /// 线程安全的日志收集器（`AsrClient` 的 log 闭包可能在任意线程被调）。
    private final class LogCollector {
        private let lock = NSLock()
        private var storage: [String] = []
        func append(_ line: String) {
            lock.lock(); defer { lock.unlock() }
            storage.append(line)
        }
        var lines: [String] {
            lock.lock(); defer { lock.unlock() }
            return storage
        }
        var joined: String { lines.joined(separator: "\n") }
    }

    private let apiKey = "sk-asr-secret-do-not-leak"

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func makeClient(log: LogCollector? = nil) -> AsrClient {
        AsrClient(
            session: StubURLProtocol.makeSession(),
            log: { line in log?.append(line) },
            boundaryProvider: { "BOUNDARY-TEST" }
        )
    }

    private func config(
        baseUrl: String = "https://api.deepseek.com",
        model: String = "whisper-1",
        apiKey: String? = nil
    ) -> AsrConfig {
        AsrConfig(enabled: true, baseUrl: baseUrl, model: model, apiKey: apiKey ?? self.apiKey)
    }

    private func audioBytes() -> Data {
        Data([0x00, 0x01, 0x02, 0xFF, 0x10, 0x20])
    }

    private func transcribe(
        _ client: AsrClient,
        config: AsrConfig? = nil,
        language: String = AsrConfig.defaultLanguage
    ) async throws -> String {
        try await client.transcribe(
            audio: audioBytes(),
            fileName: "voice.m4a",
            mime: "audio/m4a",
            config: config ?? self.config(),
            language: language
        )
    }

    private func recordedRequest(index: Int = 0) throws -> URLRequest {
        let requests = StubURLProtocol.recordedRequests
        XCTAssertGreaterThan(requests.count, index, "应该发出过一次请求")
        return try XCTUnwrap(requests.indices.contains(index) ? requests[index] : nil)
    }

    private func recordedBody(index: Int = 0) throws -> Data {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "应该发出过一次请求")
        return bodies.indices.contains(index) ? bodies[index] : Data()
    }

    @discardableResult
    private func assertFailure(
        _ expected: AsrError,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ block: () async throws -> Void
    ) async -> AsrError? {
        do {
            try await block()
            XCTFail("应该抛 \(expected)", file: file, line: line)
            return nil
        } catch let error as AsrError {
            XCTAssertEqual(error, expected, file: file, line: line)
            XCTAssertFalse(error.message.isEmpty, "message 不能为空", file: file, line: line)
            return error
        } catch {
            XCTFail("抛出了意料之外的错误：\(error)", file: file, line: line)
            return nil
        }
    }

    // MARK: - 端点

    /// 同一批用户输入必须拼出同一个端点（默认 DeepSeek 官方兼容 OpenAI 的形状）。
    func testEndpointNormalizesBaseUrlShapes() throws {
        let expected = "https://api.deepseek.com/v1/audio/transcriptions"
        let cases = [
            "https://api.deepseek.com",
            "https://api.deepseek.com/",
            "  https://api.deepseek.com  ",
            "https://api.deepseek.com/v1",
            "https://api.deepseek.com/v1/",
            "https://api.deepseek.com/v1/audio/transcriptions",
            "https://api.deepseek.com/audio/transcriptions"
        ]
        for baseUrl in cases {
            XCTAssertEqual(
                try AsrClient.endpoint(baseUrl: baseUrl).absoluteString,
                expected,
                "Base URL 形状没归一化：\(baseUrl)"
            )
        }
    }

    /// OpenAI 官方地址（已经带 `/v1`）不能变成 `/v1/v1/...`。
    func testEndpointDoesNotDoubleVersionPrefix() throws {
        XCTAssertEqual(
            try AsrClient.endpoint(baseUrl: "https://api.openai.com/v1").absoluteString,
            "https://api.openai.com/v1/audio/transcriptions"
        )
    }

    /// 空地址 / 明文 http / 非 http(s) 都要明确报错，绝不「猜一个地址发出去」。
    func testEndpointRejectsEmptyInsecureAndInvalidAddresses() {
        XCTAssertThrowsError(try AsrClient.endpoint(baseUrl: "   ")) { error in
            XCTAssertEqual(error as? AsrError, .emptyBaseUrl)
        }
        XCTAssertThrowsError(try AsrClient.endpoint(baseUrl: "http://127.0.0.1:8787")) { error in
            XCTAssertEqual(error as? AsrError, .insecureBaseUrl)
        }
        XCTAssertThrowsError(try AsrClient.endpoint(baseUrl: "ftp://example.com")) { error in
            XCTAssertEqual(error as? AsrError, .invalidBaseUrl)
        }
        XCTAssertThrowsError(try AsrClient.endpoint(baseUrl: "not a url")) { error in
            XCTAssertEqual(error as? AsrError, .invalidBaseUrl)
        }
        // 显式允许（给未来的本地 whisper 留的开关）：http 放行。
        XCTAssertEqual(
            try AsrClient.endpoint(baseUrl: "http://127.0.0.1:8787", allowInsecureHttp: true).absoluteString,
            "http://127.0.0.1:8787/v1/audio/transcriptions"
        )
    }

    // MARK: - 成功

    func testSuccessSendsMultipartWithFileModelLanguageAndBearer() async throws {
        StubURLProtocol.enqueue(.json(#"{"text":"  你好呀  "}"#))
        let client = makeClient()

        let text = try await transcribe(client)
        XCTAssertEqual(text, "你好呀", "text 要 trim")

        let request = try recordedRequest()
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://api.deepseek.com/v1/audio/transcriptions")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(apiKey)")
        XCTAssertEqual(request.timeoutInterval, AsrClient.requestTimeout)
        XCTAssertEqual(AsrClient.requestTimeout, 60, "转写超时必须是 60 秒")
        XCTAssertTrue(
            (request.value(forHTTPHeaderField: "Content-Type") ?? "").contains("multipart/form-data; boundary=BOUNDARY-TEST"),
            "Content-Type 要带 boundary：\(request.value(forHTTPHeaderField: "Content-Type") ?? "nil")"
        )

        let body = try recordedBody()
        let bodyText = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(bodyText.contains("name=\"file\"; filename=\"voice.m4a\""), bodyText)
        XCTAssertTrue(bodyText.contains("Content-Type: audio/m4a"))
        XCTAssertTrue(bodyText.contains("name=\"model\"\r\n\r\nwhisper-1"))
        XCTAssertTrue(bodyText.contains("name=\"language\"\r\n\r\nzh"))
        XCTAssertTrue(bodyText.hasSuffix("--BOUNDARY-TEST--\r\n"), "multipart 要有结束分隔符")
        // 原始字节必须原样在 body 里（不转码、不 base64）。
        XCTAssertNotNil(body.range(of: audioBytes()), "音频字节应当原样出现在 multipart 里")
    }

    /// `language` 传空 = 这个字段不发（让服务端自己判语种）。
    func testEmptyLanguageOmitsField() async throws {
        StubURLProtocol.enqueue(.json(#"{"text":"ok"}"#))
        _ = try await transcribe(makeClient(), language: "")
        let bodyText = String(decoding: try recordedBody(), as: UTF8.self)
        XCTAssertFalse(bodyText.contains("name=\"language\""), bodyText)
    }

    /// 有的网关把结果包一层 `{"data":{"text":…}}`：也要认。
    func testParsesNestedDataText() async throws {
        StubURLProtocol.enqueue(.json(#"{"data":{"text":"包了一层"}}"#))
        let text = try await transcribe(makeClient())
        XCTAssertEqual(text, "包了一层")
    }

    /// 静音 → 200 但没文字：这是失败（不伪造），不是空字符串成功。
    func testEmptyTextIsFailureNotFakeTranscript() async {
        StubURLProtocol.enqueue(.json(#"{"text":"   "}"#))
        await assertFailure(.emptyTranscript) { _ = try await self.transcribe(self.makeClient()) }
    }

    func testMalformedJSONIsDecodeFailure() async {
        StubURLProtocol.enqueue(.json("not json at all"))
        await assertFailure(.decodeFailed) { _ = try await self.transcribe(self.makeClient()) }
    }

    // MARK: - 没 Key / 没字节

    /// 没填 Key：在发请求**之前**就失败（不浪费一次网络往返，也不把空 Bearer 发出去）。
    func testMissingKeyFailsWithoutSendingRequest() async {
        let client = makeClient()
        await assertFailure(.notConfigured) {
            _ = try await self.transcribe(client, config: self.config(apiKey: ""))
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "没 Key 不该发请求")
    }

    func testEmptyAudioDataFailsWithoutSendingRequest() async {
        let client = makeClient()
        await assertFailure(.unreadableAudio) {
            _ = try await client.transcribe(
                audio: Data(), fileName: "empty.m4a", mime: "audio/m4a", config: self.config()
            )
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty)
    }

    // MARK: - HTTP 错误分类

    func testHTTPStatusClassification() async {
        let cases: [(Int, AsrError)] = [
            (401, .unauthorized(401)),
            (403, .unauthorized(403)),
            (404, .endpointNotFound(404)),
            (400, .unsupportedMedia(400)),
            (415, .unsupportedMedia(415)),
            (422, .unsupportedMedia(422)),
            (413, .payloadTooLarge(413)),
            (429, .rateLimited(429)),
            (500, .serverUnavailable(500)),
            (503, .serverUnavailable(503)),
            (418, .http(418))
        ]
        for (status, expected) in cases {
            StubURLProtocol.reset()
            StubURLProtocol.enqueue(.json(#"{"error":{"message":"nope"}}"#, status: status))
            await assertFailure(expected) { _ = try await self.transcribe(self.makeClient()) }
        }
    }

    /// 状态码 → 分类是一张固定的表（测试直接喂数字，不必真发请求）。
    func testClassifyIsPureFunctionOfStatus() {
        XCTAssertEqual(AsrError.classify(status: 401), .unauthorized(401))
        XCTAssertEqual(AsrError.classify(status: 429), .rateLimited(429))
        XCTAssertEqual(AsrError.classify(status: 502), .serverUnavailable(502))
        XCTAssertEqual(AsrError.classify(status: 302), .http(302))
        XCTAssertTrue(AsrError.classify(status: 429).isRetryable)
        XCTAssertFalse(AsrError.classify(status: 401).isRetryable)
    }

    /// 系统超时 / 断网 → 明确的中文分类（不是把英文系统报错抛给用户）。
    func testURLErrorMapping() {
        XCTAssertEqual(AsrError.from(urlError: URLError(.timedOut)), .timedOut)
        XCTAssertFalse(AsrError.from(urlError: URLError(.timedOut)).message.isEmpty)
        if case .network(let message) = AsrError.from(urlError: URLError(.notConnectedToInternet)) {
            XCTAssertFalse(message.isEmpty)
        } else {
            XCTFail("断网应当是 .network")
        }
        if case .network = AsrError.from(urlError: URLError(.cannotFindHost)) {} else {
            XCTFail("DNS 失败应当是 .network")
        }
    }

    /// 响应过大：拒绝，不把整个 body 读进内存后再解析。
    func testOversizedResponseIsRejected() async {
        let oversized = String(repeating: "x", count: AsrClient.maxResponseBytes + 8)
        StubURLProtocol.enqueue(.json(oversized))
        await assertFailure(.responseTooLarge(AsrClient.maxResponseBytes + 8)) {
            _ = try await self.transcribe(self.makeClient())
        }
    }

    // MARK: - 不泄漏

    /// 日志里绝不能出现 Key，也不能出现转写正文。
    func testLogsNeverContainKeyOrTranscriptText() async throws {
        let collector = LogCollector()
        StubURLProtocol.enqueue(.json(#"{"text":"这是很私人的一句话"}"#))
        let text = try await transcribe(makeClient(log: collector))
        XCTAssertEqual(text, "这是很私人的一句话")

        let logs = collector.joined
        XCTAssertFalse(logs.contains(apiKey), "日志里出现了 Key：\(logs)")
        XCTAssertFalse(logs.contains("sk-"), "日志里出现了 Key 前缀：\(logs)")
        XCTAssertFalse(logs.contains("这是很私人的一句话"), "日志里出现了转写正文：\(logs)")
        XCTAssertTrue(logs.contains("成功"), "失败/成功至少要有一条线索：\(logs)")
    }

    /// 出错时的日志同样不能带 Key（原始响应体只截断进日志）。
    func testFailureLogsNeverContainKey() async {
        let collector = LogCollector()
        StubURLProtocol.enqueue(.json(#"{"error":{"message":"bad key"}}"#, status: 401))
        await assertFailure(.unauthorized(401)) {
            _ = try await self.transcribe(self.makeClient(log: collector))
        }
        XCTAssertFalse(collector.joined.contains(apiKey), collector.joined)
    }

    // MARK: - 测试音

    /// 设置页「测试连接」用的静音 WAV：标准 44 字节头 + 全零 PCM。
    func testSilentTestClipIsValidWAV() {
        let data = AsrTestClip.silentWAV(durationMs: 500)
        XCTAssertEqual(String(decoding: data.prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(String(decoding: data.dropFirst(8).prefix(4), as: UTF8.self), "WAVE")
        XCTAssertEqual(String(decoding: data.dropFirst(36).prefix(4), as: UTF8.self), "data")
        let sampleCount = AsrTestClip.sampleRate * 500 / 1_000
        XCTAssertEqual(data.count, 44 + sampleCount * 2)
        XCTAssertEqual(data.suffix(64), Data(count: 64), "静音片段应当全零")
    }
}
