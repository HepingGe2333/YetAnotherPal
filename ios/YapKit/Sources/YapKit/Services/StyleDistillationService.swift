import Foundation

// ---------------------------------------------------------------------------
// 「从最近聊天学习话风」（0.15.7）。
//
// 和现有的「粘贴一段聊天记录 → 总结话风」是同一件事的两个入口：
// - 那个入口的样本来自用户**手动粘贴**（`SpeechStyleSummarizer.summarize(transcript:)`）；
// - 这个入口的样本来自**他和这个人最近真的聊过的话**（他自己最近的 50 条消息）。
//
// 所以本类型只做三件事，总结本身仍然交给 `SpeechStyleSummarizer`（提示词、兜底、解析
// 都不复制第二份）：
//
// 1. `sample`：取当前人格最近 N 条 user + assistant 消息，压成「用户：… / 他：…」样本；
// 2. `distill`：把样本交给 `SpeechStyleSummarizer`，拿回 `SpeechStyleSummary`（预览用，**不写库**）；
// 3. `apply`：用户点「应用」之后才写 `speechStyleJson`，并把 `personas.version` +1。
//
// ## 隐私（`spec/persona-guidance/README.md` §3.3，硬要求）
//
// 聊天记录**只用于这一次总结**：
// - 不落库：`apply` 只写总结后的 `SpeechStyle`，样本本身没有第二处引用；
// - 不进日志：`log` 只收到「取到几条」「截断几条」「成功/兜底」，绝不带正文片段；
// - 不进备份：备份里只有 `speechStyleJson`（`SpeechStyle` 的 JSON），没有样本。
// 所以 `StyleDistillationSample.transcript` 只在一份局部变量里活一次请求；
// **界面上也不要把它显示出来**（预览只显示总结结果，不显示样本）。
//
// ## 版本历史
//
// Android 有 `persona_revisions` 表，iOS 刻意没有建（见 `PersonaCardRepository` 文件头）。
// 这里沿用同一条取舍：写成功就把 `personas.version` +1（界面上就是「现在是他第 N 版」），
// 不假装有一份可以回滚的历史。`apply` 返回新的版本号，界面据此显示结果。
// ---------------------------------------------------------------------------

/// 一次「从最近聊天学习」的样本（**只在内存里**）。
public struct StyleDistillationSample: Equatable, Sendable {
    /// 「用户：… / 他：…」逐行样本。隐私数据：不落库、不进日志、不上屏。
    public var transcript: String
    /// 真正进了样本的消息条数。
    public var messageCount: Int
    /// 因为超过上限 / 内容为空被丢掉的消息条数。
    public var droppedMessages: Int
    /// 是否有消息被按字数截断（单条超长，或样本总量超上限）。
    public var truncated: Bool

    public init(
        transcript: String,
        messageCount: Int,
        droppedMessages: Int = 0,
        truncated: Bool = false
    ) {
        self.transcript = transcript
        self.messageCount = messageCount
        self.droppedMessages = droppedMessages
        self.truncated = truncated
    }
}

/// 一次「从最近聊天学习」的结果。
public enum StyleDistillationResult: Equatable, Sendable {
    /// 这个人还没有可以学的聊天记录（界面提示「先聊几句」）。
    case noMessages
    /// 拿到了总结（`usedModel == false` 表示走的是本地兜底，界面要提示可手动修改）。
    case ready(SpeechStyleSummary)
    /// 读库失败（坏库 / 迁移中）；样本与错误详情都不外泄。
    case failed(String)
}

/// 应用之后的结果。
public struct StyleDistillationApplyResult: Equatable, Sendable {
    /// 是否真的写了库（总结跟现在一模一样时为 false）。
    public var applied: Bool
    /// 应用之后的人格版本（`personas.version`）。
    public var version: Int
    /// 写进去的 `speechStyleJson`（没写入时是当前库里那一份）。
    public var json: String?

    public init(applied: Bool, version: Int, json: String?) {
        self.applied = applied
        self.version = version
        self.json = json
    }
}

/// 「从最近聊天学习话风」的错误（`localizedDescription` 可直接上屏）。
public enum StyleDistillationError: Error, LocalizedError, Equatable {
    case personaNotFound
    case emptyStyle

    public var errorDescription: String? {
        switch self {
        case .personaNotFound: return String(localized: "这个联系人已经不在了")
        case .emptyStyle: return String(localized: "这次学出来的是空的，先手填几条吧")
        }
    }
}

public final class StyleDistillationService {

    // MARK: - 常量（界面提示与测试都读它，不写第二份）

    /// 学一次话风看多少条消息：最近 50 条（user + assistant）。
    ///
    /// 50 是「够看出习惯、又不至于把半个月的对话都翻一遍」的量：
    /// 一个人一天聊 20 条，50 条约等于最近两三天，句长与口头禅已经稳定。
    public static let defaultMessageLimit = 50

    /// 单条消息进样本前最多留多少字（长消息只在前面那部分里看得到句子的开场习惯）。
    public static let maxCharsPerMessage = 120

    /// 样本总字数上限：与总结器自己的上限同一份（`SpeechStyleSummarizer.maxTranscriptChars`）。
    public static var maxSampleChars: Int { SpeechStyleSummarizer.maxTranscriptChars }

    /// 样本里的说话人标记（与总结提示词里的「昵称：内容」同一形状）。
    public static let userLabel = "用户"
    public static let assistantLabel = "他"

    private let stores: DatabaseStores
    private let summarizer: SpeechStyleSummarizer
    private let log: (String) -> Void
    private let now: () -> Int64

    public init(
        stores: DatabaseStores,
        summarizer: SpeechStyleSummarizer,
        log: @escaping (String) -> Void = { _ in },
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.stores = stores
        self.summarizer = summarizer
        self.log = log
        self.now = now
    }

    // MARK: - 取样

    /// 取当前人格最近 `limit` 条对话消息，压成「用户：… / 他：…」样本。
    ///
    /// 取数顺序是「会话最近的先取、每段会话里取最新的几条、凑够 50 条就停」：
    /// 跨会话合并之后仍然是**全局最近的 50 条**，但每次只从库里捞凑不够的那几条，
    /// 不会因为「他有一百个会话」就把每段会话的正文都读出来。
    ///
    /// 没有可用消息时返回 nil（界面据此提示「先聊几句」）。
    public func sample(personaId: String, limit: Int = defaultMessageLimit) -> StyleDistillationSample? {
        let safeLimit = min(max(limit, 1), 200)
        let sessions: [ChatSessionEntity]
        do {
            sessions = try stores.sessions.byPersona(personaId)
        } catch {
            log("[style] 读会话失败，放弃这次取样（不重试）")
            return nil
        }
        let ordered = sessions.sorted {
            ($0.lastMessageAt ?? $0.updatedAt) > ($1.lastMessageAt ?? $1.updatedAt)
        }

        var collected: [MessageEntity] = []
        for session in ordered {
            guard collected.count < safeLimit else { break }
            let need = safeLimit - collected.count
            let rows = (try? stores.messages.recentDialogue(sessionId: session.id, limit: need)) ?? []
            collected.append(contentsOf: rows)
        }
        guard !collected.isEmpty else { return nil }

        // 跨会话合并后再按时间正序：样本读起来才是「一段连续的聊天」。
        let dialogue = collected.sorted {
            $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt
        }
        return Self.compose(dialogue, limit: safeLimit)
    }

    /// 把消息拼成样本（纯函数：不读库、不读时钟，测试直接喂数据）。
    ///
    /// - 每条压成一行（换行 → 空格）并截到 `maxCharsPerMessage`；
    /// - 只保留 `limit` 条以内的最新消息；
    /// - 总量超过 `maxSampleChars` 时**从最旧的开始丢**（最近说的话最能代表现在的话风）。
    public static func compose(
        _ messages: [MessageEntity],
        limit: Int = defaultMessageLimit
    ) -> StyleDistillationSample? {
        let safeLimit = min(max(limit, 1), 200)
        var lines: [(text: String, at: Int64)] = []
        var dropped = 0
        var truncated = false

        for message in messages {
            guard message.role == .user || message.role == .assistant else {
                dropped += 1
                continue
            }
            guard !message.blocked, message.status != .error else {
                dropped += 1
                continue
            }
            let flattened = MemoryContract.Injection.flatten(message.content)
            guard !flattened.isEmpty else {
                dropped += 1
                continue
            }
            var lineText = flattened
            if lineText.count > maxCharsPerMessage {
                lineText = String(lineText.prefix(maxCharsPerMessage))
                truncated = true
            }
            let speaker = message.role == .user ? userLabel : assistantLabel
            lines.append((speaker + "：" + lineText, message.createdAt))
        }
        guard !lines.isEmpty else { return nil }

        // 只留最新的 `safeLimit` 条（调用方一般已经限过，这里再守一次，纯函数也能独立成立）。
        if lines.count > safeLimit {
            dropped += lines.count - safeLimit
            lines = Array(lines.suffix(safeLimit))
        }

        // 总量预算：从最新往前装，装不下就停（剩下的都算丢掉的）。
        var kept: [String] = []
        var chars = 0
        for line in lines.reversed() {
            let cost = line.text.count + 1
            if !kept.isEmpty, chars + cost > maxSampleChars {
                truncated = true
                dropped += 1
                continue
            }
            kept.append(line.text)
            chars += cost
        }
        let transcript = kept.reversed().joined(separator: "\n")
        guard !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return StyleDistillationSample(
            transcript: transcript,
            messageCount: kept.count,
            droppedMessages: dropped,
            truncated: truncated
        )
    }

    // MARK: - 总结（不写库）

    /// 学一次：取样 → `SpeechStyleSummarizer`。**不写库**，结果只用来预览。
    ///
    /// 日志只有条数与结果（`Privacy`：绝不带正文）。
    public func distill(
        personaId: String,
        config: CompletionConfig?,
        model: String = ""
    ) async -> StyleDistillationResult {
        guard let sample = sample(personaId: personaId) else {
            log("[style] 从最近聊天学习：没有可用的聊天记录")
            return .noMessages
        }
        log("[style] 从最近聊天学习：取到 \(sample.messageCount) 条"
            + (sample.droppedMessages > 0 ? "（丢弃 \(sample.droppedMessages) 条）" : "")
            + (sample.truncated ? "（有截断）" : ""))
        let summary = await summarizer.summarize(
            transcript: sample.transcript,
            config: config,
            model: model
        )
        guard !summary.style.isEmpty else {
            // 空结果不是错误：界面上就是「这次没学到」，让用户手填。
            log("[style] 从最近聊天学习：结果为空")
            return .noMessages
        }
        return .ready(summary)
    }

    // MARK: - 应用（写库）

    /// 用户点「应用」之后才走这里：写 `speechStyleJson` 并把版本号 +1。
    ///
    /// - 总结与库里那份**一模一样**时不写库（`applied == false`），也不顶版本号：
    ///   不然每点一次「应用」都会让「第 N 版」往上跳一格；
    /// - 版本号的口径与 `PersonaCardRepository` / 编辑页保存一致：
    ///   `max(version, 1) + 1`（老库里 version 可能是 0）；
    /// - 写失败时抛错，界面显示「没应用成」，库里保持原来那份话风。
    @discardableResult
    public func apply(personaId: String, style: SpeechStyle) throws -> StyleDistillationApplyResult {
        guard var persona = try stores.personas.byId(personaId) else {
            throw StyleDistillationError.personaNotFound
        }
        guard let json = style.jsonString() else {
            throw StyleDistillationError.emptyStyle
        }
        if PromptGuidanceJSON.clean(persona.speechStyleJson) == json {
            log("[style] 应用话风：与现在一样，不写库")
            return StyleDistillationApplyResult(applied: false, version: persona.version, json: json)
        }
        persona.speechStyleJson = json
        persona.version = max(persona.version, 1) + 1
        persona.updatedAt = now()
        try stores.personas.upsert(persona)
        log("[style] 应用话风：写入第 \(persona.version) 版（\(style.phrases.count) 条常用语）")
        return StyleDistillationApplyResult(applied: true, version: persona.version, json: json)
    }
}

// MARK: - App 层默认装配

public extension StyleDistillationService {
    /// 给编辑页用的默认装配（与 `SpeechStyleSummarizer.makeDefault` 同一种取舍：
    /// 这一层需要真的发请求，而 App 层不注入 completion client）。
    static func makeDefault(
        stores: DatabaseStores,
        log: @escaping (String) -> Void = { _ in }
    ) -> StyleDistillationService {
        StyleDistillationService(
            stores: stores,
            summarizer: SpeechStyleSummarizer.makeDefault(log: log),
            log: log
        )
    }
}
