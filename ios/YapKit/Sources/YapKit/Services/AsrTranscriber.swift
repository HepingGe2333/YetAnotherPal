import Foundation

// ---------------------------------------------------------------------------
// 语音转写（ASR）的**调度层**（0.15）。
//
// 它只做一件事：把「一组待发送的语音/视频附件」变成「一组带 `transcript` 的附件」。
// 三条边界写死在这里，接线的调用点不需要再判断：
//
// 1. **关着 / 没 Key 就整段跳过**：不发请求、不改附件（老数据一个字节都不变）；
// 2. **失败不阻断发送**：失败时附件保持没有 transcript，并打上 `transcriptState = .failed`
//    （气泡显示「没有转写」）；异常一律不往上抛；
// 3. **不伪造**：只有真的拿到 `text` 才写 transcript。空文字 / 坏 JSON 都算失败。
//
// 为什么单独一层而不是直接塞进 `ChatService`：
// - 发送链路（本地 / 服务端两种模式）都要用，写在某一处另一处就会漏；
// - 这一层可以用假客户端逐条测「成功/失败/超时/没 Key」，不必真发录音。
//
// TODO(0.15 合并，lead 接线)：三处调用点（本文件不做，避免与 ChatService/ChatViewModel
// 的并行改动冲突）：
//   1. `ChatViewModel.send()`（本地模式）：在 `env.sendChecked(...)` **之前**
//      `let attachments = await transcriber.annotate(attachments)`；
//   2. 服务端模式：`env.sendCheckedWithMedia(...)` 之前同样 `annotate`（上传/入队前落库）；
//   3. `PromptBuilder.history` 里把 `attachment.mediaContextLine` 换成
//      `AsrTranscriptContext.mediaLine(for: attachment)` —— 转写就进了当轮与历史的上下文
//      （原始 audio/frames 的发送逻辑**完全不改**，转写只是多一行字）。
//   `AsrTranscriber.live(settings:apiKey:)` 负责把设置 + Keychain 里的 Key 变成配置。
// ---------------------------------------------------------------------------

// MARK: - 结果模型

/// 为什么没转写（跳过而不是失败）。
public enum AsrSkipReason: Equatable, Sendable {
    /// 设置里的开关关着。
    case disabled
    /// 开关开着，但没填转写 Key。
    case missingKey
    /// 配置本身有问题（地址空/非法/明文 http）：先改设置，不是这条语音的错。
    case invalidConfig(AsrError)
    /// 不是语音/视频附件（图片、表情、头像）。
    case notPlayable
    /// 文件不在本机（换机、清缓存、只有服务端 mediaId）。
    case fileUnavailable
    /// 用户点了停止 / 上层取消。
    case cancelled

    /// 给日志/诊断用的一句话（不进提示词）。
    public var message: String {
        switch self {
        case .disabled:
            return String(localized: "语音转写关着，跳过")
        case .missingKey:
            return String(localized: "语音转写没填 Key，跳过")
        case .invalidConfig(let error):
            return error.message
        case .notPlayable:
            return String(localized: "不是语音/视频附件，跳过")
        case .fileUnavailable:
            return String(localized: "媒体文件不在本机，跳过转写")
        case .cancelled:
            return String(localized: "转写被取消")
        }
    }
}

/// 一条附件的转写结果。
public enum AsrAttachmentOutcome: Equatable, Sendable {
    case transcribed(String)
    case failed(AsrError)
    case skipped(AsrSkipReason)

    public var transcript: String? {
        if case .transcribed(let text) = self { return text }
        return nil
    }

    public var isTranscribed: Bool {
        if case .transcribed = self { return true }
        return false
    }

    /// 真的试着转写但没成功（配置问题/跳过都不算）。
    public var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }

    /// 失败的原因（只给日志/设置页用；不写进提示词，避免把错误文案喂给模型）。
    public var failureMessage: String? {
        switch self {
        case .failed(let error): return error.message
        case .skipped(let reason): return reason.message
        case .transcribed: return nil
        }
    }
}

/// 一条附件 + 它这次的结果（附件本体已经带上 transcript / 失败标记）。
public struct AsrAttachmentResult: Equatable, Sendable {
    public var attachment: MessageAttachment
    public var outcome: AsrAttachmentOutcome

    public init(attachment: MessageAttachment, outcome: AsrAttachmentOutcome) {
        self.attachment = attachment
        self.outcome = outcome
    }
}

/// 一组附件的转写结果。
public struct AsrTranscriptionResult: Equatable, Sendable {
    public var results: [AsrAttachmentResult]

    public init(results: [AsrAttachmentResult] = []) {
        self.results = results
    }

    /// 转写后的附件（原顺序；没转写的原样返回）。接线点直接用这个。
    public var attachments: [MessageAttachment] { results.map(\.attachment) }

    public var transcribedCount: Int { results.filter { $0.outcome.isTranscribed }.count }
    public var failedCount: Int { results.filter { $0.outcome.isFailure }.count }
    public var skippedCount: Int {
        results.filter {
            if case .skipped = $0.outcome { return true }
            return false
        }.count
    }

    public var hasFailure: Bool { failedCount > 0 }

    /// 这一轮到底有没有发生转写请求成功（用于决定要不要提示「没有转写」）。
    public var didTranscribeAny: Bool { transcribedCount > 0 }
}

// MARK: - 附件上的便捷变换

public extension MessageAttachment {
    /// 转写成功：带上文字（状态自动归一成 `.succeeded`）。
    func withTranscript(_ text: String) -> MessageAttachment {
        MessageAttachment(
            uri: uri, mime: mime, width: width, height: height, kind: kind,
            durationMs: durationMs, serverMediaId: serverMediaId,
            transcript: text, transcriptState: .succeeded
        )
    }

    /// 转写失败：保持没有文字，但打上「试过但没成功」的标记（气泡显示「没有转写」）。
    func markedTranscriptFailure() -> MessageAttachment {
        MessageAttachment(
            uri: uri, mime: mime, width: width, height: height, kind: kind,
            durationMs: durationMs, serverMediaId: serverMediaId,
            transcript: nil, transcriptState: .failed
        )
    }
}

// MARK: - 调度器

/// 把待发送附件转成带转写的附件。
///
/// 线程模型：`transcribe` 是 `async` 的，可以并发调用；内部不改共享状态
///（每次只读配置、读文件、发请求），所以不需要锁。
public final class AsrTranscriber {

    /// 每次转写前重新读一次配置（用户可能在设置页改了地址/模型/开关）。
    public typealias ConfigProvider = () -> AsrConfig

    private let client: any AsrTranscribing
    private let store: AudioVideoStore
    private let configProvider: ConfigProvider
    private let log: (String) -> Void

    public init(
        client: any AsrTranscribing = AsrClient(),
        store: AudioVideoStore = .shared,
        configProvider: @escaping ConfigProvider,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.store = store
        self.configProvider = configProvider
        self.log = log
    }

    /// App 里的默认装配：设置 + Keychain（ASR 独立 Key）。
    public static func live(
        settings: @escaping () -> AppSettings,
        apiKey: @escaping () -> String?,
        client: any AsrTranscribing = AsrClient(),
        store: AudioVideoStore = .shared,
        log: @escaping (String) -> Void = { _ in }
    ) -> AsrTranscriber {
        AsrTranscriber(
            client: client,
            store: store,
            configProvider: { AsrConfig.from(settings: settings(), apiKey: apiKey()) },
            log: log
        )
    }

    /// 转写一组附件；**永不抛错**，失败在结果里（附件已带失败标记）。
    public func transcribe(_ attachments: [MessageAttachment]) async -> AsrTranscriptionResult {
        var results: [AsrAttachmentResult] = []
        results.reserveCapacity(attachments.count)
        for attachment in attachments {
            let (updated, outcome) = await transcribeOne(attachment)
            results.append(AsrAttachmentResult(attachment: updated, outcome: outcome))
        }
        return AsrTranscriptionResult(results: results)
    }

    /// 接线点最常用的形式：只要附件（没转写的原样返回）。
    public func annotate(_ attachments: [MessageAttachment]) async -> [MessageAttachment] {
        await transcribe(attachments).attachments
    }

    /// 转写一条附件（可显式给配置；nil = 读当前配置）。
    public func transcribeOne(
        _ attachment: MessageAttachment,
        config explicitConfig: AsrConfig? = nil
    ) async -> (attachment: MessageAttachment, outcome: AsrAttachmentOutcome) {
        guard attachment.isPlayableMedia else {
            return (attachment, .skipped(.notPlayable))
        }
        let config = explicitConfig ?? configProvider()
        guard config.enabled else { return (attachment, .skipped(.disabled)) }
        guard config.hasAPIKey else { return (attachment, .skipped(.missingKey)) }
        do {
            _ = try AsrClient.endpoint(baseUrl: config.resolvedBaseUrl)
        } catch let error as AsrError {
            log("[asr] 配置不可用：\(error.message)")
            return (attachment, .skipped(.invalidConfig(error)))
        } catch {
            return (attachment, .skipped(.invalidConfig(.invalidBaseUrl)))
        }
        guard let data = store.data(for: attachment) else {
            return (attachment, .skipped(.fileUnavailable))
        }

        do {
            let text = try await client.transcribe(
                audio: data,
                fileName: AsrClient.fileName(for: attachment),
                mime: AudioVideoLimits.canonicalMime(attachment.mime),
                config: config,
                language: config.resolvedLanguage.isEmpty ? AsrConfig.defaultLanguage : config.resolvedLanguage
            )
            // 只记字数，**不记转写内容**（可能是很私人的话）。
            log("[asr] 转写成功：\(text.count) 字")
            return (attachment.withTranscript(text), .transcribed(text))
        } catch is CancellationError {
            return (attachment, .skipped(.cancelled))
        } catch let error as AsrError {
            log("[asr] 转写失败：\(error.message)")
            return (attachment.markedTranscriptFailure(), .failed(error))
        } catch {
            log("[asr] 转写失败：\(error)")
            return (
                attachment.markedTranscriptFailure(),
                .failed(.network(String(localized: "语音转写请求失败，请检查网络后重试")))
            )
        }
    }
}
