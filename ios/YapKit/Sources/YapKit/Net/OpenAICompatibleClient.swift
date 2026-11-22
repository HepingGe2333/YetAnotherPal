import Foundation

// ---------------------------------------------------------------------------
// OpenAI 兼容协议的网络层：一个 POST，一条 SSE 流。
//
// 来源：Android `network/OpenAiAdapter.kt`、`network/NetworkModels.kt`、
// `network/NetworkGuard.kt`、`network/AiProviderAdapter.kt`。
//
// 报文形状、错误文案、重试次数都照 Android 抄：
//   POST {baseUrl}/v1/chat/completions
//   Authorization: Bearer {key}
//   {"model":…, "messages":[…], "stream":true, "stream_options":{"include_usage":true}, …}
//
// 那条 `stream_options.include_usage = true` 是关键：没有它，很多网关不会在最后一片里
// 报 usage，聊天就会变成「花钱但账上记 0」。不支持的网关会忽略这个字段，不报错。
//
// 为什么用 `URLSession.bytes(for:)` 而不是 OkHttp：系统自带、没有第三方依赖，
// 而且它给的是字节流（能真流式）；配合 `SSEParser` 处理半包。
// ---------------------------------------------------------------------------

// MARK: - 传输模型

/// 一条要发给模型的消息。iOS 与 Android 的 `WireMessage` 同形。
public struct WireMessage: Equatable, Sendable {
    public var role: String
    public var content: String
    /// 已经编码好的 data URL（base64）。非空时以多模态 content 数组发送。
    public var images: [String]
    /// 音频片段（0.13 语音输入 / `listen` 工具）。非空时以 `input_audio` part 发送。
    ///
    /// 为什么放在 `WireMessage` 上而不是单独一条消息：OpenAI 兼容协议的
    /// `content[]` 里音频就是**同一条消息**的一个 part，拆成两条消息模型会以为
    /// 对方发了两句。
    public var audio: [WireAudioPart]

    public init(
        role: String,
        content: String,
        images: [String] = [],
        audio: [WireAudioPart] = []
    ) {
        self.role = role
        self.content = content
        self.images = images
        self.audio = audio
    }

    /// 这条消息需不需要以多模态数组发送。
    public var isMultimodal: Bool { !images.isEmpty || !audio.isEmpty }
}

/// 推理参数。nil 表示「不发给服务端」，让它用默认值。
public struct InferenceParams: Equatable, Sendable {
    public var temperature: Double?
    public var topP: Double?
    public var maxOutputTokens: Int?
    public var frequencyPenalty: Double?
    public var presencePenalty: Double?
    /// 0.16 思考强度（`spec/tool-call-agents/README.md` §2）：`low` / `high`。
    ///
    /// nil = 不发这个字段（ASR 之外的所有辅助调用都保持 nil：它们要稳定与便宜）。
    /// 只有主 LLM 会话（`ChatService.generate`）设置它：默认 low、调用 `think` 之后这一轮升到 high。
    public var reasoningEffort: ReasoningEffort?

    public init(
        temperature: Double? = nil,
        topP: Double? = nil,
        maxOutputTokens: Int? = nil,
        frequencyPenalty: Double? = nil,
        presencePenalty: Double? = nil,
        reasoningEffort: ReasoningEffort? = nil
    ) {
        self.temperature = temperature
        self.topP = topP
        self.maxOutputTokens = maxOutputTokens
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.reasoningEffort = reasoningEffort
    }
}

public extension ApiConfigEntity {
    /// 配置里的推理参数（Android 是 `ApiConfigEntity.inferenceParams()`）。
    ///
    /// 0.13 起它把 `spec/api-defaults/README.md` 的默认参数补进来：
    /// temperature 0.75 / top_p 1.0 / max_tokens 16384 / 惩罚 0；配置里显式填了的以配置为准。
    /// 聊天与工具回路都调它，所以两端看到的默认值只在这里定义一次。
    func inferenceParams() -> InferenceParams {
        effectiveInferenceParams()
    }
}

// MARK: - 错误

/// 网络/服务错误。`message` 永远是能给用户看的中文（与 Android 文案逐字一致）。
public struct ApiException: Error, LocalizedError, Equatable, Sendable {
    /// HTTP 状态码；nil 表示「没拿到响应」（超时、断网、解析失败等）。
    public let statusCode: Int?
    public let message: String
    /// 响应原文（截断），只用于日志与排查，不展示给用户。
    public let detail: String?

    public init(statusCode: Int?, message: String, detail: String? = nil) {
        self.statusCode = statusCode
        self.message = message
        self.detail = detail
    }

    public var errorDescription: String? { message }
}

/// provider 不可用（预留的 Gemini/Claude 适配器用）。
public struct ProviderUnavailableException: Error, LocalizedError, Equatable, Sendable {
    public let message: String
    public init(message: String) { self.message = message }
    public var errorDescription: String? { message }
}

// MARK: - 地址校验

/// 地址校验与端点拼装。与 Android 的 `NetworkGuard` 同语义、同文案。
public enum NetworkGuard {

    /// 这个**构建**允不允许明文 HTTP。
    ///
    /// 0.10.2 审计 P1-6：发布构建不再全量放开 ATS（`Config/Info.plist` 只留
    /// `NSAllowsLocalNetworking`，Debug 用 `Config/Info-Debug.plist`），
    /// 于是「允许不安全 HTTP」这个开关在 Release 里也必须给出明确错误，
    /// 而不是让用户以为打开了就能用、或者让代码在系统层被静默拦掉。
    ///
    /// 只有 DEBUG 构建放行（本机参考服务就在 `http://127.0.0.1:8787`）。
    public static var buildAllowsInsecureHTTP: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// 只允许 HTTPS；用本地服务时必须在配置里显式打开「允许不安全 HTTP」，
    /// 而且当前构建必须允许明文（见 `buildAllowsInsecureHTTP`）。
    /// 这不是洁癖：API Key 明文走 http 等于把它交给同网段的每一个人。
    ///
    /// - Parameter buildAllowsInsecureHttp: 只为测试注入；业务代码用默认值。
    public static func parseHttpURL(
        _ raw: String,
        allowInsecureHttp: Bool,
        buildAllowsInsecureHttp: Bool = NetworkGuard.buildAllowsInsecureHTTP
    ) throws -> URL {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty {
            throw ApiException(statusCode: nil, message: String(localized: "地址不能为空"))
        }
        guard let url = URL(string: value),
              let scheme = url.scheme?.lowercased(),
              let host = url.host, !host.isEmpty else {
            throw ApiException(statusCode: nil, message: String(localized: "地址格式不正确，请填写完整的 http(s) 地址"))
        }
        if scheme == "https" { return url }
        if scheme == "http" && allowInsecureHttp {
            guard buildAllowsInsecureHttp else {
                // 发布构建：明确报错（不能悄悄走明文，也不能让 ATS 在系统层静默拦掉）。
                throw ApiException(
                    statusCode: nil,
                    message: String(localized: "这个版本的 App 不允许明文 HTTP 连接（只有调试版可以）。请把 API 地址改成 https:// 开头的地址。")
                )
            }
            return url
        }
        if scheme == "http" {
            throw ApiException(
                statusCode: nil,
                message: String(localized: "出于安全要求，仅允许 HTTPS。若使用本地服务，请在 API 配置中显式开启“允许不安全 HTTP”。")
            )
        }
        throw ApiException(statusCode: nil, message: String(localized: "仅支持 HTTP(S) 地址"))
    }

    /// 去掉尾部斜杠、以及用户误粘进来的 `/chat/completions`、`/models`。
    public static func normalizeBaseUrl(_ raw: String) -> String {
        var base = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        for suffix in ["/chat/completions", "/models"] where base.hasSuffix(suffix) {
            base.removeLast(suffix.count)
        }
        return base
    }

    /// 拼一个接口地址：baseUrl 已经带 `/v1` 就不重复加。
    public static func endpoint(baseUrl: String, path: String) throws -> URL {
        let base = normalizeBaseUrl(baseUrl)
        var suffix = path.trimmingCharacters(in: .whitespacesAndNewlines)
        while suffix.hasPrefix("/") { suffix.removeFirst() }
        let root = base.hasSuffix("/v1") ? base : "\(base)/v1"
        guard let url = URL(string: "\(root)/\(suffix)") else {
            throw ApiException(statusCode: nil, message: String(localized: "API 地址无效，请检查 Base URL"))
        }
        return url
    }

}

// MARK: - 协议

/// 一次对话要走的那几件事。抽象成协议是为了：
/// 1. 聊天服务可以用假实现跑（不必真的发请求）；
/// 2. 以后要加 Gemini/Claude 适配器时，上层不用改。
public protocol ChatCompleting: AnyObject {
    /// 拉模型列表（设置页的「测试连接」用）。
    func fetchModels(config: ApiConfigEntity, apiKey: String?) async throws -> [String]

    /// 流式聊天：每拿到一段增量就抛给上层。
    ///
    /// - Parameter usageContext: 这次调用算在谁头上（`api_usage` 要写 sessionId）。
    func streamChat(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String,
        usageContext: UsageContext
    ) -> AsyncThrowingStream<String, Error>

    /// 一次性的完整回复（后台任务都用它）。
    func complete(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String,
        usageContext: UsageContext
    ) async throws -> String
}

public extension ChatCompleting {
    func streamChat(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams = InferenceParams(),
        kind: String = UsageKind.unknown,
        usageContext: UsageContext = .none
    ) -> AsyncThrowingStream<String, Error> {
        streamChat(
            config: config, apiKey: apiKey, model: model, messages: messages,
            params: params, extraHeaders: [:], kind: kind, usageContext: usageContext
        )
    }

    func complete(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams = InferenceParams(),
        kind: String = UsageKind.unknown,
        usageContext: UsageContext = .none
    ) async throws -> String {
        try await complete(
            config: config, apiKey: apiKey, model: model, messages: messages,
            params: params, extraHeaders: [:], kind: kind, usageContext: usageContext
        )
    }

    /// 给了 `extraHeaders` 但不在意「这笔算在谁头上」的调用点用这两个。
    func streamChat(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String
    ) -> AsyncThrowingStream<String, Error> {
        streamChat(
            config: config, apiKey: apiKey, model: model, messages: messages,
            params: params, extraHeaders: extraHeaders, kind: kind, usageContext: .none
        )
    }

    func complete(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String
    ) async throws -> String {
        try await complete(
            config: config, apiKey: apiKey, model: model, messages: messages,
            params: params, extraHeaders: extraHeaders, kind: kind, usageContext: .none
        )
    }
}

// MARK: - 客户端

/// OpenAI 兼容的流式客户端。
///
/// 只依赖 Foundation；`URLSession` 可注入（测试用自定义 `URLProtocol` 假装服务端）。
///
/// 1.9 起它多担了一件事：**所有模型调用的并发闸**（§五）。聊天、记忆抽取、每日整理、
/// 自主思考……在 App 里都走这一个客户端，把闸门放在这里就不可能出现「新加了一个后台
/// 任务但忘了加闸」。预算闸那一层已经整段删除（没有额度、没有拦截）。
public final class OpenAICompatibleClient: ChatCompleting {

    /// 流式失败后最多重试几次（Android 是 2）。
    public static let maxRetries = 2
    /// 单次请求的超时。Android 的 `complete` 用 `withTimeout(180_000)`。
    public static let requestTimeout: TimeInterval = 180

    private let session: URLSession
    /// 记账。允许为空只为了「纯连接测试」这类场景；App 里必须给，
    /// 否则会悄悄漏账（Android 的构造参数也是非空的）。
    private let usage: UsageRecording?
    /// 全局并发闸。默认用进程内单例，测试可以塞一个自己的。
    private let gate: LlmConcurrencyGate
    private let now: () -> Int64

    public init(
        session: URLSession = .shared,
        usage: UsageRecording? = nil,
        gate: LlmConcurrencyGate = .shared,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.session = session
        self.usage = usage
        self.gate = gate
        self.now = now
    }

    // MARK: 模型列表

    public func fetchModels(config: ApiConfigEntity, apiKey: String?) async throws -> [String] {
        let url = try NetworkGuard.parseHttpURL(
            try NetworkGuard.endpoint(baseUrl: config.baseUrl, path: "models").absoluteString,
            allowInsecureHttp: config.allowInsecureHttp
        )
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = Self.requestTimeout
        applyHeaders(&request, config: config, apiKey: apiKey, extraHeaders: [:])

        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let body = String(decoding: data, as: UTF8.self)
            guard (200..<300).contains(status) else {
                throw apiException(status: status, body: body)
            }
            guard let root = Self.jsonObject(body) else {
                throw ApiException(statusCode: status, message: String(localized: "服务返回了无法解析的模型列表"))
            }
            let entries = (root["data"] as? [[String: Any]]) ?? (root["models"] as? [[String: Any]]) ?? []
            var models: [String] = []
            var seen = Set<String>()
            for entry in entries {
                guard let id = entry["id"] as? String else { continue }
                let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty || seen.contains(trimmed) { continue }
                seen.insert(trimmed)
                models.append(trimmed)
            }
            return models.sorted()
        } catch {
            throw mapNetworkError(error, timedOutMessage: timeOutMessage)
        }
    }

    // MARK: 流式

    public func streamChat(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String,
        usageContext: UsageContext
    ) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                // 名额要一直占到流结束（聊天可能持续几十秒）：所以不是「进函数拿、出函数还」，
                // 而是包在整条流的生命周期里，任何退出路径都只还一次。
                let acquired = await self.gate.acquire(kind: kind)
                guard let ticket = acquired.ticket else {
                    // 被取消（用户点了停止）不是错误：按取消收流，上层会把半句落库。
                    if acquired.isCancelled {
                        continuation.finish(throwing: CancellationError())
                    } else {
                        // 车道按用途判断：后台道不该拿到面向聊天的文案（0.10.2 审计 P1-11）。
                        continuation.finish(throwing: self.busyError(lane: LlmCallLane(kind: kind)))
                    }
                    return
                }
                defer { self.gate.release(ticket) }
                // 拿到名额之后、发请求之前再确认一次取消：等待期间用户可能已经点了停止。
                if Task.isCancelled {
                    continuation.finish(throwing: CancellationError())
                    return
                }
                do {
                    try await self.runStream(
                        config: config, apiKey: apiKey, model: model, messages: messages,
                        params: params, extraHeaders: extraHeaders, kind: kind,
                        usageContext: usageContext,
                        continuation: continuation
                    )
                    continuation.finish()
                } catch is CancellationError {
                    // 用户点了「停止」：不算失败，直接把流收掉（部分内容由上层落库）。
                    continuation.finish(throwing: CancellationError())
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runStream(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String,
        usageContext: UsageContext,
        continuation: AsyncThrowingStream<String, Error>.Continuation
    ) async throws {
        var attempts = 0
        var emittedAny = false
        while true {
            var emittedThisAttempt = false
            // 有的服务端会把 usage 放在最后一条（choices 为空的）分片里；有就记一笔。
            var streamUsage: TokenUsage?
            // 这一轮实际发出的文字量：没有 usage 时用它估算 completion tokens。
            var collected = ""
            let request = try buildRequest(
                config: config, apiKey: apiKey, model: model, messages: messages,
                params: params, stream: true, extraHeaders: extraHeaders
            )
            do {
                let (bytes, response) = try await session.bytes(for: request)
                let http = response as? HTTPURLResponse
                let status = http?.statusCode ?? 0
                let contentType = (http?.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()

                guard (200..<300).contains(status) else {
                    let body = await Self.readAll(bytes)
                    throw apiException(status: status, body: body)
                }

                if !contentType.contains("text/event-stream") {
                    // 有的网关忽略 stream=true，直接返回一整份 completion。
                    let body = await Self.readAll(bytes)
                    let payload = try parseCompletion(raw: body, status: status)
                    streamUsage = payload.usage
                    collected = payload.text
                    if payload.text.isEmpty {
                        throw ApiException(
                            statusCode: nil,
                            message: String(localized: "模型没有返回任何文字，请检查模型名称或服务配置")
                        )
                    }
                    continuation.yield(payload.text)
                    emittedThisAttempt = true
                    emittedAny = true
                } else {
                    var parser = SSEParser()
                    var buffer = Data()
                    var finished = false
                    for try await byte in bytes {
                        if Task.isCancelled { throw CancellationError() }
                        buffer.append(byte)
                        // 0.11.1 审计 P1-1：单行 8 MB 上限必须加在**生产真正累加的那个缓冲区**上。
                        // `SSEParser.feed` 的超限分支只在「喂进去的块里没有换行、且长度 > 8 MB」时执行，
                        // 而这里总是攒到换行才喂 —— 那条分支在生产路径上不可达。真正会无上限增长的
                        // 就是这里的 `buffer`（服务端声明 text/event-stream 却一直不吐换行时）。
                        // 超限即中止整条流：与 Android `SseLines` 的「抛 ResponseTooLong 并中止」对齐，
                        // 也避免把几百 MB 的「一行」当成正文解析。
                        guard buffer.count <= SSEParser.MAX_LINE_BYTES else {
                            throw ApiException(
                                statusCode: status,
                                message: String(localized: "模型返回的数据过长（单行超过 8 MB），已中止")
                            )
                        }
                        // 攒到一整行再进解析器：byte 级喂进去只会多绕几圈，结果一样。
                        guard byte == 0x0A else { continue }
                        let events = parser.feed(buffer)
                        buffer.removeAll(keepingCapacity: true)
                        for event in events {
                            switch event {
                            case .done:
                                finished = true
                            case .data(let payload):
                                let chunk = try parseStreamChunk(payload: payload, status: status)
                                if let usage = chunk.usage, usage.isMeaningful {
                                    streamUsage = usage
                                }
                                if let text = chunk.text, !text.isEmpty {
                                    emittedThisAttempt = true
                                    emittedAny = true
                                    collected += text
                                    continuation.yield(text)
                                }
                            }
                            if finished { break }
                        }
                        if finished { break }
                    }
                    if !finished {
                        for event in parser.flush() {
                            switch event {
                            case .done:
                                finished = true
                            case .data(let payload):
                                let chunk = try parseStreamChunk(payload: payload, status: status)
                                if let usage = chunk.usage, usage.isMeaningful {
                                    streamUsage = usage
                                }
                                if let text = chunk.text, !text.isEmpty {
                                    emittedThisAttempt = true
                                    emittedAny = true
                                    collected += text
                                    continuation.yield(text)
                                }
                            }
                        }
                    }
                    if !emittedThisAttempt {
                        throw ApiException(
                            statusCode: nil,
                            message: String(localized: "模型没有返回任何文字，请检查模型名称或服务配置")
                        )
                    }
                }

                // 服务端没给 usage 时按字数估一个：聊天的钱通常是最大头，不能记成 0。
                let usage = streamUsage ?? TokenUsage(
                    promptTokens: ApiCost.estimateTokens(messages.map(\.content).joined()),
                    completionTokens: ApiCost.estimateTokens(collected)
                )
                if usage.isMeaningful {
                    self.usage?.record(
                        kind: kind, modelName: model, usage: usage, at: now(),
                        personaId: usageContext.personaId, sessionId: usageContext.sessionId
                    )
                }
                return
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                let retryable = isRetryable(error) && !emittedAny && attempts < Self.maxRetries
                guard retryable else {
                    throw mapNetworkError(error, timedOutMessage: timeOutMessage)
                }
                attempts += 1
                // 退避 500ms * 次数：第一次立刻重试往往只是撞上同一个抖动。
                try await Task.sleep(nanoseconds: UInt64(500_000_000 * attempts))
            }
        }
    }

    // MARK: 非流式

    public func complete(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        extraHeaders: [String: String],
        kind: String,
        usageContext: UsageContext
    ) async throws -> String {
        // 并发闸放在最前面：等不到名额就「这次不做」（后台 20 秒 / 聊天 60 秒）。
        let acquired = await gate.acquire(kind: kind)
        guard let ticket = acquired.ticket else {
            if acquired.isCancelled { throw CancellationError() }
            throw busyError(lane: LlmCallLane(kind: kind))
        }
        defer { gate.release(ticket) }
        // 等待期间被取消：不要再发一次用户已经不想要的请求（0.10.2 审计 P1-11）。
        if Task.isCancelled { throw CancellationError() }
        var attempts = 0
        while true {
            do {
                let request = try buildRequest(
                    config: config, apiKey: apiKey, model: model, messages: messages,
                    params: params, stream: false, extraHeaders: extraHeaders
                )
                let (data, response) = try await session.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                let raw = String(decoding: data, as: UTF8.self)
                guard (200..<300).contains(status) else {
                    throw apiException(status: status, body: raw)
                }
                let payload = try parseCompletion(raw: raw, status: status)
                // 记账在返回之前做：这一笔用掉了就是用了，哪怕之后调用方处理失败。
                usage?.record(
                    kind: kind, modelName: model, usage: payload.usage, at: now(),
                    personaId: usageContext.personaId, sessionId: usageContext.sessionId
                )
                return payload.text
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if !isRetryable(error) || attempts >= Self.maxRetries {
                    throw mapNetworkError(error, timedOutMessage: completeTimeOutMessage)
                }
                attempts += 1
                try await Task.sleep(nanoseconds: UInt64(700_000_000 * attempts))
            }
        }
    }

    /// 排队超时的那句话。
    ///
    /// 聊天是用户在等回话，得说人话；后台任务的这条错误只会在日志与「他最近在想什么」里出现。
    func busyError(lane: LlmCallLane) -> ApiException {
        switch lane {
        case .chat:
            return ApiException(
                statusCode: nil,
                message: String(localized: "他手上还有别的事没忙完，这次没顾上回你，过一会儿再发一条试试")
            )
        case .background:
            // 后台道的超时是「排队没排上」，不是「他在忙没顾上」——文案要按车道分开，
            // 而且这条只会进日志与「他最近在想什么」，不该出现面向聊天的说法（P1-11）。
            return ApiException(
                statusCode: nil,
                message: String(localized: "后台任务排队超时，这次先不做")
            )
        }
    }

    // MARK: - 请求构造

    private func buildRequest(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        stream: Bool,
        extraHeaders: [String: String]
    ) throws -> URLRequest {
        let url = try NetworkGuard.parseHttpURL(
            try NetworkGuard.endpoint(baseUrl: config.baseUrl, path: "chat/completions").absoluteString,
            allowInsecureHttp: config.allowInsecureHttp
        )
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = Self.requestTimeout
        request.httpBody = try Self.encodeRequest(
            model: model, messages: messages, params: params, stream: stream
        )
        applyHeaders(&request, config: config, apiKey: apiKey, extraHeaders: extraHeaders)
        return request
    }

    /// 报文形状与 Android 逐字段对齐。单独抽出来是为了能被单测直接断言
    /// （不必起服务端就能验证 `stream_options.include_usage` 有没有发出去）。
    public static func encodeRequest(
        model: String,
        messages: [WireMessage],
        params: InferenceParams,
        stream: Bool
    ) throws -> Data {
        var payload: [String: Any] = [:]
        payload["model"] = model
        payload["messages"] = messages.map { message -> [String: Any] in
            var entry: [String: Any] = ["role": message.role]
            if !message.isMultimodal {
                entry["content"] = message.content
            } else {
                entry["content"] = MultimodalPayload.contentParts(
                    text: message.content, images: message.images, audio: message.audio
                )
            }
            return entry
        }
        payload["stream"] = stream
        if stream {
            // 让服务端在最后一个分片里带上 usage；不支持的网关会忽略这个字段。
            payload["stream_options"] = ["include_usage": true]
        }
        if let temperature = params.temperature { payload["temperature"] = temperature }
        if let topP = params.topP { payload["top_p"] = topP }
        if let maxTokens = params.maxOutputTokens { payload["max_tokens"] = maxTokens }
        if let frequencyPenalty = params.frequencyPenalty { payload["frequency_penalty"] = frequencyPenalty }
        if let presencePenalty = params.presencePenalty { payload["presence_penalty"] = presencePenalty }
        // 0.16 思考强度（`spec/tool-call-agents/README.md` §2）：只有主 LLM 会话会填这个字段；
        // 不填 = 报文里连键都没有（辅助调用不必为一个用不到的字段冒「网关不认识」的风险）。
        if let effort = params.reasoningEffort { payload["reasoning_effort"] = effort.wireValue }
        return try JSONSerialization.data(withJSONObject: payload)
    }

    private func applyHeaders(
        _ request: inout URLRequest,
        config: ApiConfigEntity,
        apiKey: String?,
        extraHeaders: [String: String]
    ) {
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream, application/json", forHTTPHeaderField: "Accept")
        var customHasAuthorization = false
        for (name, value) in Self.parseHeaders(config.customHeadersJson) {
            if name.caseInsensitiveCompare("Content-Type") == .orderedSame { continue }
            if name.caseInsensitiveCompare("Authorization") == .orderedSame { customHasAuthorization = true }
            request.setValue(value, forHTTPHeaderField: name)
        }
        if let key = apiKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !key.isEmpty, !customHasAuthorization {
            request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        }
        for (name, value) in extraHeaders {
            let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedName.isEmpty, !trimmedValue.isEmpty else { continue }
            request.setValue(trimmedValue, forHTTPHeaderField: trimmedName)
        }
    }

    /// `customHeadersJson` 是一个扁平的 `{name: value}` 对象；坏数据当作没有。
    static func parseHeaders(_ raw: String) -> [(String, String)] {
        guard let root = jsonObject(raw) else { return [] }
        var result: [(String, String)] = []
        for (key, value) in root {
            let name = key.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { continue }
            let text: String?
            if let string = value as? String {
                text = string
            } else if let number = value as? NSNumber {
                text = number.stringValue
            } else {
                text = nil
            }
            guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            result.append((name, text))
        }
        // 字典无序，排一下让同样的配置每次发出同一组 header（便于排查与测试）。
        return result.sorted { $0.0 < $1.0 }
    }

    // MARK: - 响应解析

    /// 流式分片：这一片里的文字 + （可能有的）usage。
    struct StreamChunk: Equatable {
        var text: String?
        var usage: TokenUsage?
    }

    func parseStreamChunk(payload: String, status: Int) throws -> StreamChunk {
        guard let root = Self.jsonObject(payload) else { return StreamChunk(text: nil, usage: nil) }
        if let error = root["error"] {
            throw ApiException(
                statusCode: status,
                message: Self.extractErrorMessage(error: error, fallback: String(localized: "流式响应返回错误")),
                detail: String(Self.renderJSON(error).prefix(500))
            )
        }
        let usage = ApiCost.parseUsage(root: root)
        let meaningfulUsage = usage.isMeaningful ? usage : nil
        guard let choices = root["choices"] as? [[String: Any]],
              let choice = choices.first else {
            return StreamChunk(text: nil, usage: meaningfulUsage)
        }
        var content: String?
        if let delta = choice["delta"] as? [String: Any] {
            content = delta["content"] as? String
        }
        if content == nil { content = choice["text"] as? String }
        if content == nil, let message = choice["message"] as? [String: Any] {
            content = message["content"] as? String
        }
        return StreamChunk(text: content, usage: meaningfulUsage)
    }

    struct CompletionPayload: Equatable {
        var text: String
        var usage: TokenUsage
    }

    func parseCompletion(raw: String, status: Int) throws -> CompletionPayload {
        guard let root = Self.jsonObject(raw) else {
            throw ApiException(
                statusCode: status,
                message: String(localized: "服务返回了无法识别的回复内容"),
                detail: String(raw.prefix(300))
            )
        }
        if let error = root["error"] {
            throw ApiException(
                statusCode: nil,
                message: Self.extractErrorMessage(error: error, fallback: String(localized: "服务返回错误")),
                detail: String(Self.renderJSON(error).prefix(500))
            )
        }
        let usage = ApiCost.parseUsage(root: root)
        guard let choices = root["choices"] as? [[String: Any]],
              let choice = choices.first else {
            throw ApiException(
                statusCode: status, message: String(localized: "服务返回了空回复"), detail: String(raw.prefix(300))
            )
        }
        let text = (choice["message"] as? [String: Any])?["content"] as? String
            ?? choice["text"] as? String
        guard let text else {
            throw ApiException(
                statusCode: status, message: String(localized: "服务返回了空回复"), detail: String(raw.prefix(300))
            )
        }
        return CompletionPayload(text: text, usage: usage)
    }

    // MARK: - 错误映射

    /// 把 HTTP 状态码翻成中文。文案照抄 Android（用户看到的是同一句话，排查文档才通用）。
    func apiException(status: Int, body: String) -> ApiException {
        let friendly: String
        switch status {
        case 400: friendly = String(localized: "请求被服务拒绝（400），请检查模型名称与推理参数")
        case 401: friendly = String(localized: "API Key 无效或未授权（401）")
        case 403: friendly = String(localized: "没有访问该模型的权限（403）")
        case 404: friendly = String(localized: "接口或模型不存在（404），请检查 Base URL 与模型 ID")
        case 408: friendly = String(localized: "服务请求超时（408）")
        case 413: friendly = String(localized: "请求内容过长（413），请缩短上下文或输出长度")
        case 429: friendly = String(localized: "请求过于频繁或额度不足（429）")
        case 500...599: friendly = String(format: String(localized: "服务端暂时不可用（%d）"), status)
        default: friendly = String(format: String(localized: "请求失败（HTTP %d）"), status)
        }
        let detail = Self.extractErrorMessage(bodyString: body, fallback: "")
        return ApiException(
            statusCode: status,
            message: detail.isEmpty ? friendly : String(format: String(localized: "%@：%@"), friendly, detail),
            detail: String(body.prefix(1200))
        )
    }

    /// 系统级错误（超时、DNS、断网）的中文。
    func mapNetworkError(_ error: Error, timedOutMessage: String) -> Error {
        switch error {
        case let api as ApiException:
            return api
        case let unavailable as ProviderUnavailableException:
            return unavailable
        case is CancellationError:
            return error
        case let urlError as URLError:
            switch urlError.code {
            case .cancelled:
                // 用户点了停止 / 上层取消了 Task：这不是「网络断了」，别再报一句吓人的中文。
                return CancellationError()
            case .timedOut:
                return ApiException(statusCode: nil, message: timedOutMessage)
            case .cannotFindHost, .dnsLookupFailed:
                return ApiException(statusCode: nil, message: String(localized: "无法解析服务器地址，请检查网络连接"))
            case .notConnectedToInternet, .networkConnectionLost,
                 .cannotConnectToHost, .secureConnectionFailed:
                let reason = urlError.localizedDescription
                return ApiException(
                    statusCode: nil,
                    message: String(format: String(localized: "网络连接中断：%@"), reason.isEmpty ? String(localized: "请检查网络") : reason)
                )
            default:
                let reason = urlError.localizedDescription
                return ApiException(
                    statusCode: nil,
                    message: String(format: String(localized: "网络连接中断：%@"), reason.isEmpty ? String(localized: "请检查网络") : reason)
                )
            }
        default:
            // 0.11.0 审计：不再把系统 `localizedDescription` 当用户文案（那是「The operation
            // couldn't be completed. (NSURLErrorDomain error -1005.)」这种气人的句子）。
            // 给一句通用、两段语言都翻好的话：具体错误码在日志里，不在气泡里。
            return ApiException(
                statusCode: nil,
                message: String(localized: "网络连接中断，请检查网络后重试")
            )
        }
    }

    /// 可重试：没有 HTTP 状态码（网络抖动）、408、429、5xx。
    /// 拿到 401/404 这种「重试一百次也一样」的错误就别再打了。
    func isRetryable(_ error: Error) -> Bool {
        if let api = error as? ApiException {
            guard let status = api.statusCode else { return true }
            return status == 408 || status == 429 || (500...599).contains(status)
        }
        if error is URLError { return true }
        if error is ProviderUnavailableException { return false }
        return false
    }

    /// 流式里超时读作「连接超时」，后台一次性调用读作「请求超时」——与 Android 两条路径的文案一致。
    var timeOutMessage: String { String(localized: "连接超时，请检查网络或服务地址") }
    var completeTimeOutMessage: String { String(localized: "请求超时，请检查网络或服务响应时间") }

    // MARK: - 小工具

    static func jsonObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return object as? [String: Any]
    }

    static func extractErrorMessage(bodyString: String, fallback: String) -> String {
        guard let root = jsonObject(bodyString) else { return fallback }
        if let error = root["error"] {
            return extractErrorMessage(error: error, fallback: fallback)
        }
        if let message = root["message"] as? String, !message.isEmpty {
            return String(message.prefix(400))
        }
        return fallback
    }

    static func extractErrorMessage(error: Any, fallback: String) -> String {
        if let text = error as? String, !text.isEmpty { return String(text.prefix(400)) }
        if let object = error as? [String: Any] {
            if let message = object["message"] as? String, !message.isEmpty {
                return String(message.prefix(400))
            }
            return String(renderJSON(error).prefix(400))
        }
        return fallback
    }

    static func renderJSON(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value),
              let text = String(data: data, encoding: .utf8) else {
            return String(describing: value)
        }
        return text
    }

    /// 把一条 `URLSession.AsyncBytes` 一次性读完（错误页、非流式响应）。
    /// 普通（非流式）响应体的体积上限（0.10.2 审计 P2-2）。
    ///
    /// 0.10.1 的 `readAll` 逐字节 append、没有上限：网关/代理返回一个没有 Content-Length、
    /// 一直往外吐的错误页时，这里会一直吃内存。SSE 那条路径有 `SSEParser.MAX_LINE_BYTES`
    /// 兜底，`readAll` 原来没有。1MB 够放任何正常的 completion/错误体，超了就截断并标记。
    static let maxResponseBodyBytes = 1 * 1024 * 1024

    static func readAll(_ bytes: URLSession.AsyncBytes) async -> String {
        var data = Data()
        do {
            for try await byte in bytes {
                // 多读一个字节就能判断「超了」，具体截断与提示交给 `decodeBody`（可单测）。
                if data.count > maxResponseBodyBytes { break }
                data.append(byte)
            }
        } catch {
            // 读不完就用已读到的那部分：错误信息总比空串有用。
        }
        return decodeBody(data)
    }

    /// Data → UTF-8 文本；超过上限就截断并加一句说明。
    ///
    /// 抽成纯函数是为了能在 `swift test` 里直接钉住上限（不用真的造 1MB 的 HTTP 响应）。
    static func decodeBody(_ data: Data) -> String {
        guard data.count > maxResponseBodyBytes else {
            return String(decoding: data, as: UTF8.self)
        }
        let clipped = String(decoding: data.prefix(maxResponseBodyBytes), as: UTF8.self)
        return clipped + String(localized: "…（响应过长，已截断）")
    }
}
