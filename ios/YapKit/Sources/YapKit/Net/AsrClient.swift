import Foundation

// ---------------------------------------------------------------------------
// 语音转写（ASR）的网络客户端（0.15）。
//
// 形状照 OpenAI 兼容的 `/audio/transcriptions` 来：
//
//   POST {baseUrl}/v1/audio/transcriptions
//   Authorization: Bearer {asrApiKey}
//   multipart/form-data: file=<音频字节> + model=<asrModel> + language=zh
//   → {"text":"…"}
//
// 为什么单独一个客户端，而不是塞进 `OpenAICompatibleClient`：
// 1. **Key 是独立的**：语音转写可以走另一家服务（本地 whisper、Groq、OpenAI…），
//    和主 LLM 的 Key 不是一回事；混在一起迟早出现「换聊天模型把转写搞坏了」；
// 2. **接口形状不同**：multipart 上传音频 vs 流式 JSON —— 两条链路的超时、错误处理、
//    响应大小上限都不一样；
// 3. 聊天链路的报文形状被 `OpenAICompatibleClientTests` 逐字钉着，不该为 ASR 动它。
//
// 三条硬规矩：
// - **60 秒超时**：一段 5 分钟的语音转写要等一会儿，但也不能让「正在发送」永远转圈；
// - **只认 https**：Key 明文走 http 等于把它交给同网段的每一个人（与 `NetworkGuard` 同一条规矩）；
// - **不伪造**：拿不到 `text` 就是失败（`.emptyTranscript` / `.decodeFailed`），
//   这里绝不返回一句「（没听清）」糊弄上层。
//
// 只依赖 Foundation：本包同时跑在 iOS 16 与 macOS 13 上，`URLSession` 可注入
//（测试用 `StubURLProtocol` 假装服务端，不必真的连网）。
// ---------------------------------------------------------------------------

// MARK: - 配置

/// 一份语音转写配置（设置页那一小节的值）。
///
/// 与主 LLM 的 `ApiConfigEntity` 刻意分开：ASR 只有「开/地址/模型/Key」四件事，
/// 没有温度、没有上下文窗口、也不跟着会话走。
public struct AsrConfig: Equatable, Sendable {

    /// 默认地址：DeepSeek 官方的 OpenAI 兼容入口。
    ///
    /// 端点按 OpenAI 的规矩拼成 `{base}/v1/audio/transcriptions`（`/v1` 已经写了就不重复加），
    /// 所以这一条默认值就是「DeepSeek 官方兼容 OpenAI 的做法」。
    public static let defaultBaseUrl = "https://api.deepseek.com"
    /// 默认模型：OpenAI 兼容转写接口的通用名字。
    public static let defaultModel = "whisper-1"
    /// 默认语言：中文语音优先，服务端据此不猜语种（可被调用方覆盖）。
    public static let defaultLanguage = "zh"

    public var enabled: Bool
    public var baseUrl: String
    public var model: String
    /// 独立的 ASR Key（与主 LLM 的 Key 不是同一条）。
    public var apiKey: String?
    /// 转写语言（`zh` / `en` / …）；空 = 不传，让服务端自己判。
    public var language: String

    public init(
        enabled: Bool = false,
        baseUrl: String = AsrConfig.defaultBaseUrl,
        model: String = AsrConfig.defaultModel,
        apiKey: String? = nil,
        language: String = AsrConfig.defaultLanguage
    ) {
        self.enabled = enabled
        self.baseUrl = baseUrl
        self.model = model
        self.apiKey = apiKey
        self.language = language
    }

    /// 设置里没填就落默认值（与 `SettingsStore.load()` 的回落规则一致，双保险）。
    public var resolvedBaseUrl: String {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultBaseUrl : trimmed
    }

    public var resolvedModel: String {
        let trimmed = model.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultModel : trimmed
    }

    public var resolvedLanguage: String {
        language.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public var trimmedAPIKey: String {
        apiKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    public var hasAPIKey: Bool { !trimmedAPIKey.isEmpty }

    /// 现在就能转写吗：开关打开 + Key 非空。地址是否合法由客户端在发请求前校验
    ///（这样「地址写错了」能被明确报出来，而不是被当成「没配置」静默跳过）。
    public var isReady: Bool { enabled && hasAPIKey }

    /// 设置 + Keychain 里的 Key → 一份配置。
    ///
    /// Key 的来源只有一个：`ApiKeyStore.asrAPIKey()`（Keychain），不进 UserDefaults。
    public static func from(settings: AppSettings, apiKey: String?) -> AsrConfig {
        AsrConfig(
            enabled: settings.asrEnabled,
            baseUrl: settings.asrBaseUrl,
            model: settings.asrModel,
            apiKey: apiKey,
            language: Self.defaultLanguage
        )
    }
}

// MARK: - 错误

/// 语音转写能出的错，按「用户下一步该做什么」分类。
///
/// 与 `WebToolsFailure` 同一个思路：`message` 是能给用户看的中文，
/// 原始响应体只写日志（`detail` 不往界面上带）。
public enum AsrError: Error, Equatable, LocalizedError, Sendable {
    /// 没开 / 没填 Key：调用方应当在发请求之前就跳过，不该走到这里。
    case notConfigured
    /// 地址空着（理论上会被默认值兜住；留着是为了让「用户把默认值删了」有话说）。
    case emptyBaseUrl
    /// 地址形状不对（没有 host、不是 http(s)、粘进了怪东西）。
    case invalidBaseUrl
    /// 明文 http：这个版本不支持（Key 会裸奔）。
    case insecureBaseUrl
    /// 401 / 403：Key 不对或没权限。
    case unauthorized(Int)
    /// 404：地址或模型名不对。
    case endpointNotFound(Int)
    /// 400 / 415 / 422：服务端不收这种音频（格式 / 内容）。
    case unsupportedMedia(Int)
    /// 413：音频太大。
    case payloadTooLarge(Int)
    /// 429：限流。
    case rateLimited(Int)
    /// 5xx：服务端暂时不可用。
    case serverUnavailable(Int)
    /// 其它非 2xx。
    case http(Int)
    /// 响应体过大（防一个坏网关把内存撑爆）。
    case responseTooLarge(Int)
    /// 服务端 200 但没给文字（例如一段静音）。
    case emptyTranscript
    /// 响应不是预期的 JSON（`{"text": …}`）。
    case decodeFailed
    /// 这段音频/视频的文件不在本机（换机、清缓存、只有服务端 mediaId）。
    case fileUnavailable
    /// 读得出来但是空的（坏文件）。
    case unreadableAudio
    /// 60 秒超时。
    case timedOut
    /// 其它网络问题（断网、DNS、TLS…）。
    case network(String)

    /// 给用户看的一句话（中文；Kit 层用 `String(localized:)`，与其它网络错误同一套做法）。
    public var message: String {
        switch self {
        case .notConfigured:
            return String(localized: "语音转写还没配置好：先到设置里打开，并填一个 Key")
        case .emptyBaseUrl:
            return String(localized: "语音转写地址不能为空")
        case .invalidBaseUrl:
            return String(localized: "语音转写地址格式不正确，请填写完整的 https 地址")
        case .insecureBaseUrl:
            return String(localized: "语音转写只支持 https 地址：明文 http 会把这个 Key 交给同一网段的每个人")
        case .unauthorized(let status):
            return String(format: String(localized: "语音转写服务说没有权限（HTTP %d），检查一下转写 Key"), status)
        case .endpointNotFound(let status):
            return String(format: String(localized: "语音转写服务找不到这个接口或模型（HTTP %d），检查一下地址与模型名"), status)
        case .unsupportedMedia(let status):
            return String(format: String(localized: "语音转写服务不接受这种音频（HTTP %d）"), status)
        case .payloadTooLarge(let status):
            return String(format: String(localized: "音频超过了转写服务的大小上限（HTTP %d）"), status)
        case .rateLimited(let status):
            return String(format: String(localized: "语音转写服务限流了（HTTP %d），过一会儿再试"), status)
        case .serverUnavailable(let status):
            return String(format: String(localized: "语音转写服务暂时不可用（HTTP %d），过一会儿再试"), status)
        case .http(let status):
            return String(format: String(localized: "语音转写服务返回了 HTTP %d，检查一下地址与 Key"), status)
        case .responseTooLarge(let bytes):
            return String(format: String(localized: "语音转写的响应太大（%d 字节），已放弃"), bytes)
        case .emptyTranscript:
            return String(localized: "语音转写没有返回文字（可能是静音）")
        case .decodeFailed:
            return String(localized: "语音转写返回的内容看不懂，检查一下服务地址")
        case .fileUnavailable:
            return String(localized: "这段语音/视频的文件不在这台手机上，转写不了")
        case .unreadableAudio:
            return String(localized: "读不出这段音频的字节，没法转写")
        case .timedOut:
            return String(localized: "语音转写超时了（60 秒），稍后再试")
        case .network(let detail):
            return detail.isEmpty
                ? String(localized: "语音转写请求失败，请检查网络后重试")
                : detail
        }
    }

    public var errorDescription: String? { message }

    /// 值得再试一次吗（限流 / 5xx / 超时 / 断网）。
    ///
    /// 只作提示用：转写失败**永远不阻断发送**（发送链路会保留无转写并标记），
    /// 这里只是告诉调用方「过一会儿重发这条语音也许就好了」。
    public var isRetryable: Bool {
        switch self {
        case .rateLimited, .serverUnavailable, .timedOut, .network:
            return true
        case .notConfigured, .emptyBaseUrl, .invalidBaseUrl, .insecureBaseUrl,
             .unauthorized, .endpointNotFound, .unsupportedMedia, .payloadTooLarge,
             .http, .responseTooLarge, .emptyTranscript, .decodeFailed,
             .fileUnavailable, .unreadableAudio:
            return false
        }
    }

    /// HTTP 状态码 → 错误分类（测试直接喂数字）。
    public static func classify(status: Int) -> AsrError {
        switch status {
        case 401, 403: return .unauthorized(status)
        case 404: return .endpointNotFound(status)
        case 400, 415, 422: return .unsupportedMedia(status)
        case 413: return .payloadTooLarge(status)
        case 429: return .rateLimited(status)
        case 500...599: return .serverUnavailable(status)
        default: return .http(status)
        }
    }

    /// 系统级网络错误 → 中文分类。
    ///
    /// 原文只写日志：模型/用户看到英文系统报错只会更困惑。
    public static func from(urlError: URLError) -> AsrError {
        switch urlError.code {
        case .timedOut: return .timedOut
        case .cannotFindHost, .dnsLookupFailed:
            return .network(String(localized: "解析不了语音转写地址，检查一下网络连接"))
        case .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost,
             .secureConnectionFailed, .internationalRoamingOff, .dataNotAllowed:
            return .network(String(localized: "网络连接中断，请检查网络后重试"))
        default:
            return .network(String(localized: "语音转写请求失败，请检查网络后重试"))
        }
    }
}

// MARK: - 客户端

/// 能做转写的最小接口（`AsrTranscriber` 只依赖它，测试可以喂假实现）。
public protocol AsrTranscribing: AnyObject {
    func transcribe(
        audio data: Data,
        fileName: String,
        mime: String,
        config: AsrConfig,
        language: String
    ) async throws -> String
}

/// `/audio/transcriptions` 的客户端。
public final class AsrClient: AsrTranscribing {

    /// 一次转写最多等多久（一段 5 分钟的语音加慢网关也够）。
    public static let requestTimeout: TimeInterval = 60
    /// 端点路径（相对 baseUrl，`/v1` 由 `NetworkGuard.endpoint` 补齐）。
    public static let endpointPath = "audio/transcriptions"
    /// 响应体上限：`{"text": …}` 正常只有几 KB，1 MiB 已经非常宽松。
    public static let maxResponseBytes = 1 << 20

    private let session: URLSession
    private let log: (String) -> Void
    private let boundaryProvider: () -> String

    /// - Parameters:
    ///   - session: 可注入的会话（测试用 `StubURLProtocol`）。
    ///   - log: 只写排查信息（状态码、字节数）；**绝不写 Key、绝不写音频/转写文字**。
    ///   - boundaryProvider: multipart 分隔符（测试钉死报文形状时注入固定值）。
    public init(
        session: URLSession = .shared,
        log: @escaping (String) -> Void = { _ in },
        boundaryProvider: @escaping () -> String = AsrClient.makeBoundary
    ) {
        self.session = session
        self.log = log
        self.boundaryProvider = boundaryProvider
    }

    // MARK: 端点

    /// `baseUrl` → 真正的转写端点。
    ///
    /// 兼容用户在设置页可能粘进来的几种形状（与 `WebToolsClient` 的处理同一套习惯）：
    /// - `https://api.deepseek.com` → `https://api.deepseek.com/v1/audio/transcriptions`
    /// - `https://api.openai.com/v1` → `…/v1/audio/transcriptions`（不重复加 `/v1`）
    /// - 整条端点粘进来 → 去掉重复的 `/audio/transcriptions` 再拼
    ///
    /// 明文 http 一律拒绝：这条请求会带 Key。
    public static func endpoint(baseUrl raw: String, allowInsecureHttp: Bool = false) throws -> URL {
        var base = NetworkGuard.normalizeBaseUrl(raw)
        guard !base.isEmpty else { throw AsrError.emptyBaseUrl }
        for suffix in ["/v1/audio/transcriptions", "/audio/transcriptions"] where base.hasSuffix(suffix) {
            base.removeLast(suffix.count)
        }
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty else { throw AsrError.emptyBaseUrl }

        let url: URL
        do {
            url = try NetworkGuard.endpoint(baseUrl: base, path: endpointPath)
        } catch {
            throw AsrError.invalidBaseUrl
        }
        guard let scheme = url.scheme?.lowercased(), let host = url.host, !host.isEmpty else {
            throw AsrError.invalidBaseUrl
        }
        if scheme == "https" { return url }
        if scheme == "http" && allowInsecureHttp { return url }
        if scheme == "http" { throw AsrError.insecureBaseUrl }
        throw AsrError.invalidBaseUrl
    }

    // MARK: 转写

    /// 转写一段音频的原始字节，返回 `text`（已 trim，空则抛 `.emptyTranscript`）。
    public func transcribe(
        audio data: Data,
        fileName: String,
        mime: String,
        config: AsrConfig,
        language: String = AsrConfig.defaultLanguage
    ) async throws -> String {
        guard config.hasAPIKey else { throw AsrError.notConfigured }
        guard !data.isEmpty else { throw AsrError.unreadableAudio }
        let endpoint = try Self.endpoint(baseUrl: config.resolvedBaseUrl)

        let boundary = boundaryProvider()
        let body = Self.multipartBody(
            boundary: boundary,
            fileName: fileName,
            mime: mime,
            fileData: data,
            model: config.resolvedModel,
            language: language.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        // 只改这一条请求的超时，不动 session 的全局配置（App 里别的请求超时是另一回事）。
        request.timeoutInterval = Self.requestTimeout
        request.setValue("Bearer \(config.trimmedAPIKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body

        let responseData: Data
        let response: URLResponse
        do {
            (responseData, response) = try await session.data(for: request)
        } catch let urlError as URLError {
            let mapped = AsrError.from(urlError: urlError)
            // 只记分类，不记 URL 里的查询（ASR 端点没有查询，但习惯如此）。
            log("[asr] 网络失败：\(mapped)")
            throw mapped
        } catch is CancellationError {
            // 用户点了停止 / 上层取消 Task：不是「网络坏了」。
            throw CancellationError()
        } catch {
            log("[asr] 请求失败：\(error)")
            throw AsrError.network(String(localized: "语音转写请求失败，请检查网络后重试"))
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if responseData.count > Self.maxResponseBytes {
            log("[asr] 响应 \(responseData.count) 字节，超过上限 \(Self.maxResponseBytes)")
            throw AsrError.responseTooLarge(responseData.count)
        }
        guard (200..<300).contains(status) else {
            // 原始响应体只进日志（截 200 字），绝不回灌给用户/模型。
            log("[asr] HTTP \(status)：\(Self.excerpt(responseData))")
            throw AsrError.classify(status: status)
        }
        let text = try Self.parseTranscript(responseData)
        log("[asr] 成功：\(text.count) 字")
        return text
    }

    /// 转写一条语音/视频附件（文件从本机 `Application Support/audio|video` 读）。
    ///
    /// 本机没有文件（换机 / 清缓存 / 只有服务端 mediaId）时抛 `.unsupportedMedia(0)`：
    /// 上层按「没转写」处理即可，不阻断发送。
    public func transcribe(
        attachment: MessageAttachment,
        store: AudioVideoStore = .shared,
        config: AsrConfig
    ) async throws -> String {
        guard attachment.isPlayableMedia else { throw AsrError.fileUnavailable }
        guard let data = store.data(for: attachment) else { throw AsrError.fileUnavailable }
        return try await transcribe(
            audio: data,
            fileName: Self.fileName(for: attachment),
            mime: AudioVideoLimits.canonicalMime(attachment.mime),
            config: config,
            language: config.resolvedLanguage.isEmpty ? AsrConfig.defaultLanguage : config.resolvedLanguage
        )
    }

    // MARK: 报文

    /// multipart 分隔符（随机，避免与音频字节撞上）。
    public static func makeBoundary() -> String {
        "----YapAsr" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
    }

    /// 拼 multipart 请求体。
    ///
    /// 字段：`file`（带 filename 与 Content-Type）、`model`、`language`（空则不传）。
    /// 顺序固定为 file → model → language：形状可断言，排查时一眼能看出少了哪一段。
    public static func multipartBody(
        boundary: String,
        fileName: String,
        mime: String,
        fileData: Data,
        model: String,
        language: String
    ) -> Data {
        var body = Data()
        func append(_ text: String) { body.append(Data(text.utf8)) }

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"\(safeFileName(fileName))\"\r\n")
        append("Content-Type: \(mime)\r\n\r\n")
        body.append(fileData)
        append("\r\n")

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        append("\(model)\r\n")

        if !language.isEmpty {
            append("--\(boundary)\r\n")
            append("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
            append("\(language)\r\n")
        }

        append("--\(boundary)--\r\n")
        return body
    }

    /// 解析 `{"text": …}`（也容忍 `{"data":{"text":…}}` 这类包一层的网关）。
    ///
    /// 解析不到文字就是失败：不返回空串，也不返回 nil 让上层自己猜。
    public static func parseTranscript(_ data: Data) throws -> String {
        guard let root = try? JSONSerialization.jsonObject(with: data) else {
            throw AsrError.decodeFailed
        }
        var text: String?
        if let object = root as? [String: Any] {
            text = object["text"] as? String
            if text == nil, let nested = object["data"] as? [String: Any] {
                text = nested["text"] as? String
            }
        }
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else { throw AsrError.emptyTranscript }
        return trimmed
    }

    /// 附件 → 上传文件名（扩展名跟 mime 走，名字里不带路径）。
    public static func fileName(for attachment: MessageAttachment) -> String {
        let ext = AudioVideoLimits.fileExtension(forMime: attachment.mime) ?? (attachment.isVideo ? "mp4" : "m4a")
        return "audio.\(ext)"
    }

    /// 文件名会被塞进 `Content-Disposition`：引号、换行、路径分隔符一律清掉。
    static func safeFileName(_ raw: String) -> String {
        let cleaned = raw
            .replacingOccurrences(of: "\"", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "audio.m4a" : cleaned
    }

    private static func excerpt(_ data: Data) -> String {
        String(decoding: data.prefix(200), as: UTF8.self)
            .replacingOccurrences(of: "\n", with: " ")
    }
}

// MARK: - 测试音

/// 设置页「测试连接」用的一小段**静音** WAV。
///
/// 为什么真的发一段音频而不是 `GET /models`：
/// 转写端点才是这条配置真正要用的东西（很多网关有 `/models` 却没有
/// `/audio/transcriptions`），测它才有意义。半秒 16 kHz 单声道 PCM 只有 ~16 KB，
/// 不会花钱，也不会碰到用户的任何录音。
public enum AsrTestClip {

    public static let sampleRate = 16_000
    public static let durationMs = 500

    /// 标准 44 字节 WAV 头 + 全零 PCM（静音）。
    public static func silentWAV(durationMs: Int = AsrTestClip.durationMs) -> Data {
        let sampleCount = max(1, sampleRate * max(1, durationMs) / 1_000)
        let dataBytes = sampleCount * 2 // 16-bit 单声道
        var data = Data()
        func append32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        func append16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8))
        append32(UInt32(36 + dataBytes))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8))
        append32(16)                       // fmt 块长度
        append16(1)                        // PCM
        append16(1)                        // 单声道
        append32(UInt32(sampleRate))       // 采样率
        append32(UInt32(sampleRate * 2))   // 每秒字节数
        append16(2)                        // 块对齐
        append16(16)                       // 位深
        data.append(contentsOf: Array("data".utf8))
        append32(UInt32(dataBytes))
        data.append(Data(count: dataBytes))
        return data
    }
}
