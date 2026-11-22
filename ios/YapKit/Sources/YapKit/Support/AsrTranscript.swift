import Foundation

// ---------------------------------------------------------------------------
// 语音转写（ASR）的**纯逻辑**：状态、上下文里那几行字、以及「没有转写」的标记。
//
// 这里刻意不放网络（`Net/AsrClient.swift`）也不放调度（`Services/AsrTranscriber.swift`）：
// 这三件事出问题时排查方向完全不同 —— 连不上服务、没配、还是提示词里没带上。
// 拆开之后「上下文里到底写了什么」可以逐字断言，不必真的发一次录音。
//
// 一条硬规矩：**绝不伪造转写**。失败就是失败（`transcriptState == .failed`），
// 上下文里宁可写「这段语音没有转写」，也不写「我听到了…」。
// ---------------------------------------------------------------------------

/// 一条语音/视频附件的转写状态（持久化在 `attachmentsJson` 里）。
///
/// 只有两个值，而且只有 `.failed` 会被写进 JSON：
/// - `.succeeded` 由 `transcript` 本身表达（有文字 = 转写成功），不再多写一个字段；
/// - `.failed` 是「试过但没成功」的显式标记，气泡上据此显示「没有转写」。
///
/// 老数据里没有这个字段（`nil`）= 「没试过」，与 `.failed` 严格区分：
/// 关着转写时收下的语音，不该被标成「没有转写」。
public enum AsrTranscriptState: String, Codable, CaseIterable, Sendable {
    case succeeded = "OK"
    case failed = "FAILED"

    /// 宽松读法：大小写/空白都容忍；不认识的值返回 nil（当「没写」）。
    ///
    /// 为什么不抛错：未来版本可能加一个状态，老版本读到它时应当忽略这个字段、
    /// 而不是把整条附件当坏数据丢掉（`AttachmentsCodec` 的整段语义很严厉）。
    public init?(stored raw: String?) {
        let normalized = raw?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased() ?? ""
        guard !normalized.isEmpty else { return nil }
        self.init(rawValue: normalized)
    }

    /// 写回 JSON 用的规范值（大写）。
    public var stored: String { rawValue }
}

/// 提示词里的转写行。
///
/// 用法（`PromptBuilder.history` 里那一处）：
/// ```swift
/// let line = AsrTranscriptContext.mediaLine(for: attachment)   // 取代 attachment.mediaContextLine
/// ```
/// 它返回「这条消息是一段语音…」那一行，**后面再跟一行「他说：…」**；
/// 没有转写时原样只有那一行（与 0.13/0.14 的提示词完全一致，老数据不受影响）。
public enum AsrTranscriptContext {

    /// 单条转写进提示词的最大字数。
    ///
    /// 为什么截断：一段 5 分钟的语音转写可以有两三千字，历史里再来几条就把上下文预算吃光。
    /// 2 000 字足够模型理解「对方说了什么」，超出的部分用省略号明确表示被截断（不假装完整）。
    public static let maxCharacters = 2_000

    /// 转写文字本身（已截断）；没有就是 nil。空白算没有。
    public static func transcriptText(for attachment: MessageAttachment) -> String? {
        let raw = attachment.transcript?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !raw.isEmpty else { return nil }
        return clipped(raw)
    }

    /// 一行「他说：…」；没有转写返回空串（调用方据此决定加不加这一行）。
    ///
    /// 语音与视频都用同一句「他说」：模型需要的是「这句话是谁说的」，
    /// 而不是「这段话是从什么容器里出来的」（容器信息在上一行里已经给过了）。
    public static func line(for attachment: MessageAttachment) -> String {
        guard let text = transcriptText(for: attachment) else { return "" }
        return String(format: String(localized: "他说：%@"), text)
    }

    /// 「试过但没转出来」的那一行；没失败过返回空串。
    ///
    /// 只在 `transcriptState == .failed` 时出现 —— 关着转写、或还没转过的旧消息
    /// 不会凭空多出这句话。
    public static func missingLine(for attachment: MessageAttachment) -> String {
        guard attachment.transcriptIsMissing else { return "" }
        return attachment.isVideo
            ? String(localized: "（这段视频没有转写）")
            : String(localized: "（这段语音没有转写）")
    }

    /// 上下文里描述「这条消息带的是什么媒体」的完整段落：
    /// 媒体说明（`mediaContextLine`）+ 转写行（有就加）+ 失败标记（有就加）。
    ///
    /// 这是 `attachment.mediaContextLine` 的**超集**：没有转写时两者逐字相同。
    public static func mediaLine(for attachment: MessageAttachment) -> String {
        guard attachment.isPlayableMedia else { return "" }
        var lines: [String] = []
        let base = attachment.mediaDescriptionLine.trimmingCharacters(in: .whitespacesAndNewlines)
        if !base.isEmpty { lines.append(base) }
        let transcript = line(for: attachment)
        if !transcript.isEmpty { lines.append(transcript) }
        let missing = missingLine(for: attachment)
        if !missing.isEmpty { lines.append(missing) }
        return lines.joined(separator: "\n")
    }

    /// 把一条消息里所有媒体的转写行接到正文后面（当轮消息用；历史走 `PromptBuilder.history`）。
    ///
    /// 纯函数：输入正文 + 附件，输出正文（没有可加的内容时逐字返回原正文）。
    public static func appending(to text: String, attachments: [MessageAttachment]) -> String {
        var lines: [String] = []
        for attachment in attachments where attachment.isPlayableMedia {
            let transcript = line(for: attachment)
            if !transcript.isEmpty { lines.append(transcript) }
            let missing = missingLine(for: attachment)
            if !missing.isEmpty { lines.append(missing) }
        }
        guard !lines.isEmpty else { return text }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let addition = lines.joined(separator: "\n")
        return trimmed.isEmpty ? addition : text + "\n" + addition
    }

    /// 截断（超长时用一个明确的省略号结尾）。
    static func clipped(_ text: String) -> String {
        guard text.count > maxCharacters else { return text }
        return String(text.prefix(maxCharacters)) + "…"
    }
}
