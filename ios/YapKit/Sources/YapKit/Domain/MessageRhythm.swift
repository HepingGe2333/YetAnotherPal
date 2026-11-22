import Foundation

// 来源：`spec/experience/README.md` §3「心理活动」与 §4「连发与节奏」、§5「打字延迟」。
//
// 三件纯逻辑放在一起，因为它们服务同一个目标：**让他的节奏像人**。
// - 心理活动：什么时候该在心里想一句、想完心情动多少；
// - 连发：一轮能发几条、每条多长、按空行怎么拆；
// - 打字延迟：一条消息显示之前该等多久。
//
// 这些数字（3 分钟、3 轮、0.8–2.5 秒、0.4–4.0 秒）是**产品**，两端必须一致，
// 所以全部写在这里由单测钉住，而不是散在服务层里 `min(max(...))`。

// MARK: - 活跃窗口

/// 他此刻「还在聊」的判定（规范 §3：最近一条消息在 10 分钟内，或助手刚发出消息）。
public enum ActiveWindow {

    /// 活跃窗口：10 分钟。窗口内允许连续触发心理活动与主动消息。
    public static let windowMillis: Int64 = 10 * 60_000

    /// 最后一条消息是不是足够新（`lastMessageAt` 为空 = 还没聊过）。
    public static func isActive(lastMessageAt: Int64?, now: Int64) -> Bool {
        guard let lastMessageAt, lastMessageAt > 0 else { return false }
        return now - lastMessageAt <= windowMillis
    }
}

// MARK: - 心理活动

/// 一句待落库的私密念头 + 它带来的心情微调。
public struct MentalActivityDraft: Equatable, Sendable {
    public var thought: String
    /// 每维最多 ±1（规范 §3）。超出的部分在这里被夹掉，落库与心情都不会越界。
    public var valenceDelta: Int
    public var arousalDelta: Int
    public var dominanceDelta: Int

    public init(thought: String, valenceDelta: Int = 0, arousalDelta: Int = 0, dominanceDelta: Int = 0) {
        self.thought = MentalActivityPolicy.sanitizeThought(thought) ?? ""
        self.valenceDelta = MentalActivityPolicy.clampDelta(valenceDelta)
        self.arousalDelta = MentalActivityPolicy.clampDelta(arousalDelta)
        self.dominanceDelta = MentalActivityPolicy.clampDelta(dominanceDelta)
    }

    public var hasAnyMoodDelta: Bool {
        valenceDelta != 0 || arousalDelta != 0 || dominanceDelta != 0
    }
}

public enum MentalActivityPolicy {

    /// 念头上限：60 字（规范 §3）。
    public static let thoughtLimit = 60
    /// 时间节流：每 3 分钟一次。
    public static let intervalMillis: Int64 = 3 * 60_000
    /// 轮次节流：每 3 轮一次（一轮 = 一条用户消息之后的回应）。
    public static let rounds = 3
    /// 心情「激动」的阈值：唤醒度 ≥4 时必须做（规范 §3）。
    public static let highArousal = 4
    /// 心情「变化 ≥2」的判定阈值（规范 §3）。
    public static let changeThreshold = 2
    /// 只保留最近 50 条（规范 §3）。
    public static let retention = 50
    /// 每维单次最多动 1（规范 §3）。
    public static let maxDelta = 1

    public static func clampDelta(_ value: Int) -> Int {
        min(max(value, -maxDelta), maxDelta)
    }

    /// 念头清洗：去空白、压成一行、截到 60 字；空的返回 nil（不写空念头）。
    public static func sanitizeThought(_ raw: String) -> String? {
        let flattened = raw
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !flattened.isEmpty else { return nil }
        return String(flattened.prefix(thoughtLimit))
    }

    /// 这次该不该做心理活动。
    ///
    /// 顺序（规范 §3 的三条触发）：
    /// 1. 必须**在活跃窗口里**（否则他早就不在聊了，写一句念头只是浪费）；
    /// 2. 心情激动（唤醒度 ≥4）或心情变化 ≥2：**必做**，不等节流；
    /// 3. 否则每 3 分钟或每 3 轮做一次。
    ///
    /// - Parameters:
    ///   - lastActivityAt: 上一次心理活动的时间；nil = 从没做过。
    ///   - userMessagesSinceLastActivity: 上一次心理活动之后，对方又说了几句（「每 3 轮」用）。
    ///   - mood: 此刻的心情。
    ///   - lastMood: 上一次心理活动时的心情快照；nil = 没有可比对象。
    public static func shouldGenerate(
        lastMessageAt: Int64?,
        now: Int64,
        lastActivityAt: Int64?,
        userMessagesSinceLastActivity: Int,
        mood: MoodState,
        lastMood: MoodState?
    ) -> Bool {
        guard ActiveWindow.isActive(lastMessageAt: lastMessageAt, now: now) else { return false }
        // 这一轮他刚说完，还没轮到「新的一轮」：同一时刻重复触发只会写出重复念头。
        if let lastActivityAt, lastActivityAt >= now { return false }
        if isExcited(mood: mood, lastMood: lastMood) { return true }
        guard let lastActivityAt else { return true }
        if now - lastActivityAt >= intervalMillis { return true }
        return userMessagesSinceLastActivity >= rounds
    }

    /// 心情「激动 / 变化 ≥2」：两种都要求必做一次心理活动。
    public static func isExcited(mood: MoodState, lastMood: MoodState?) -> Bool {
        if let dimensions = mood.dimensions, dimensions.arousal >= highArousal { return true }
        // 没有多维（老数据）时退回 intensity：5 浓到满脑子，也算激动。
        if mood.dimensions == nil, mood.intensity >= 5 { return true }
        guard let lastMood else { return false }
        return changeDistance(mood, lastMood) >= changeThreshold
    }

    /// 两次心情之间的距离：有三维比三维（曼哈顿距离），没有就比强度 + 标签。
    public static func changeDistance(_ lhs: MoodState, _ rhs: MoodState) -> Int {
        if let a = lhs.dimensions, let b = rhs.dimensions {
            return abs(a.valence - b.valence) + abs(a.arousal - b.arousal) + abs(a.dominance - b.dominance)
        }
        let labelChanged = lhs.label == rhs.label ? 0 : 1
        return abs(lhs.intensity - rhs.intensity) + labelChanged
    }

    /// 微调之后的三维：每维只动 ≤1，并夹在合法范围内。
    public static func adjusted(_ dimensions: MoodDimensions, by draft: MentalActivityDraft) -> MoodDimensions {
        MoodDimensions(
            valence: dimensions.valence + clampDelta(draft.valenceDelta),
            arousal: dimensions.arousal + clampDelta(draft.arousalDelta),
            dominance: dimensions.dominance + clampDelta(draft.dominanceDelta)
        )
    }

    /// 注入下一轮提示词的那一句（规范 §3：「你刚才心里想：…」）。
    ///
    /// 只在念头还**新鲜**时注入：一句三天前的自言自语塞进当前这一轮，只会让他莫名伤感。
    public static let promptFreshnessMillis: Int64 = 30 * 60_000

    public static func promptLine(thought: String, at: Int64, now: Int64) -> String {
        let trimmed = thought.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        guard at > 0, now - at <= promptFreshnessMillis else { return "" }
        return "\n\n你刚才心里想：\(String(trimmed.prefix(thoughtLimit)))"
    }
}

// MARK: - 连发与节奏

public enum MessageRhythm {

    /// 一轮最多几条（规范 §4）。
    public static let maxMessagesPerRound = 4
    /// `send_message` 每条的字数上限（规范 §4）。
    public static let maxMessageChars = 120
    /// 不走工具的回复最多拆成几条（规范 §4：1–3 条）。
    public static let maxSplitMessages = 3
    /// 连发之间的停顿区间（秒）：0.15 起改用共享契约
    /// `ExperienceContract.BURST_GAP_MIN/MAX_SECONDS`（均值 0.2–1.5 秒，再乘 ±35% 波动）。
    ///
    /// 旧值是 0.8–2.5 的**固定区间**；真机反馈多条短句之间等 2 秒像卡断，
    /// 而长句又不够「在打字」。0.15 统一成「长度决定均值 + 随机波动」，两端同一份公式。
    public static let minGapSeconds = ExperienceContract.BURST_GAP_MIN_SECONDS
    public static let maxGapSeconds = ExperienceContract.BURST_GAP_MAX_SECONDS

    /// `send_message` 的正文清洗：压掉首尾空白、截到 120 字。空串 = 没东西可发。
    /// 与 Android `ExperienceRhythm.clampSendText` 同一套清洗：去首尾空白、
    /// 把连续空行压成一个空行、截到 120 字（截断后再 trimEnd，避免以空格结尾）。
    public static func cleanSendText(_ raw: String?) -> String? {
        var text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        text = text.replacingOccurrences(
            of: "\n{3,}", with: "\n\n", options: .regularExpression
        )
        guard text.count > maxMessageChars else { return text }
        return String(text.prefix(maxMessageChars))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 更贴心的拆句：先按空行（规范 §4 明文），再按行；最多 3 条，空段丢掉。
    ///
    /// 为什么优先空行：模型写「短消息」时天然会用空行分段（那是它唯一能表达的「这条发完了」）。
    /// 只有空行拆出来的段数超过上限时才合并——合并**从后往前**，保证前几条仍然是完整短句，
    /// 而不是把最后一句截成半截。
    public static func split(_ raw: String, max limit: Int = maxSplitMessages) -> [String] {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        let limit = Swift.max(1, limit)
        // 空行分隔（允许行尾有空格）：与 Android `splitPlainReply` 的 `\n\s*\n` 等价。
        let normalized = text.replacingOccurrences(
            of: "\n[ \t]*\n", with: "\n\n", options: .regularExpression
        )
        var chunks = normalized
            .components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if chunks.count <= limit { return chunks }
        // 段落太多：把开头几段合成第一条，剩下的按原样排——他先说的话都在第一条里。
        let head = chunks.prefix(chunks.count - limit + 1).joined(separator: "\n\n")
        chunks = [head] + chunks.suffix(limit - 1)
        return chunks
    }

    /// 连发之间的停顿（秒）：**长度决定均值 + 心情修正 + ±35% 随机波动**（0.15）。
    ///
    /// 公式与首条延迟同一条口径，只是均值区间更短（0.2–1.5 秒）：
    /// 首条是「他看到了、在想」，连发是「他还在打字，下一句马上出来」。
    /// [random] 由调用方注入（生产传 `Double.random(in: 0...1)`，测试传固定值钉住结果）。
    public static func gapSeconds(
        charCount: Int,
        mood: MoodState,
        random: () -> Double = { Double.random(in: 0...1) }
    ) -> Double {
        ExperienceContract.shared.burstGapSeconds(
            length: charCount,
            moodAdjustment: TypingDelay.moodAdjustment(mood),
            random: random
        )
    }
}

// MARK: - 打字延迟

public enum TypingDelay {

    /// 均值公式里的基础项与每字项：**转发共享契约**（0.15.7 起 `0.6 + 字数 × 0.01`）。
    ///
    /// 旧公式（`0.4 + 字数 × 0.025`，区间 0.4–4.0 秒）是**命令式**的：同样字数永远等同样久，
    /// 聊久了像进度条。新公式是「均值 + ±35% 随机波动」，随机源由调用方注入（测试可钉住）。
    ///
    /// 0.15.7 按体验结论把均值整体调慢了一档（需求：**均值 0.6–0.9 秒，15 字约 0.75 秒**）：
    /// 0 字 0.6 秒、15 字 0.75 秒、30 字 0.9 秒。字少的短句不再「秒回」，
    /// 但也没有慢到让人觉得他在拖；±35% 的随机波动保留（同样字数每次也不完全一样）。
    /// 数字只在 `ExperienceContract` 一处（与 Android / agentd 同一份），这里不写第二份。
    public static let baseSeconds = ExperienceContract.TYPING_BASE_SECONDS
    public static let perCharSeconds = ExperienceContract.TYPING_SECONDS_PER_CHAR
    public static let minSeconds = ExperienceContract.TYPING_MIN_SECONDS
    public static let maxSeconds = ExperienceContract.TYPING_MAX_SECONDS
    /// 字数上限：120 字之后不再变慢（保留常量；共享公式自己会在 2.2 秒封顶）。
    public static let lengthCap = 120

    /// 心情修正（秒）：低唤醒 / 累 → 更慢（正数），高唤醒 → 更快（负数）。
    ///
    /// 唤醒度 0..5 的基准是 2.5：偏离一格 ±0.15 秒，最多 ±0.375 秒。
    /// 另外「累 / 困 / 烦」这类词再慢一点：累的人打字确实更慢。
    public static func moodAdjustment(_ mood: MoodState) -> Double {
        let arousal = Double(mood.dimensions?.arousal ?? mood.intensity)
        var adjustment = (2.5 - arousal) * 0.15
        if mood.dimensions == nil { adjustment *= 0.5 }
        let label = mood.label
        if ["累", "困", "疲", "烦", "低落"].contains(where: { label.contains($0) }) {
            adjustment += 0.25
        }
        return min(max(adjustment, -0.5), 0.5)
    }

    /// 首条消息显示前的延迟（秒）：**长度均值 + 心情修正 + ±35% 随机波动**（0.15）。
    ///
    /// 公式在 `ExperienceContract.typingDelaySeconds`（与 Android 同一份）：
    /// 均值 `clamp(0.6 + 字数×0.01 + 心情修正, 0.3, 2.2)`，再乘 `1 ± 35%`。
    /// 15 字约 0.75 秒、30 字约 0.9 秒（需求里的「均值 0.6–0.9 秒」）。
    ///
    /// - Parameter serverAuthoritative: 服务端权威模式（0.14.1 §8）。延迟已经由服务端在
    ///   入队时按同一个公式算进 `visibleAt`，客户端**不能再加一次**，所以这里直接返回 0。
    /// - Parameter random: 随机源，返回 0..1；生产用默认值（真随机），测试传固定值。
    public static func seconds(
        charCount: Int,
        mood: MoodState,
        serverAuthoritative: Bool = false,
        random: () -> Double = { Double.random(in: 0...1) }
    ) -> Double {
        guard !serverAuthoritative else { return 0 }
        return ExperienceContract.shared.typingDelaySeconds(
            length: charCount,
            moodAdjustment: moodAdjustment(mood),
            random: random
        )
    }

    /// 秒 → 纳秒（`Task.sleep` 用）。负值 / 0 一律 0（失败/停止不延迟）。
    public static func nanos(seconds: Double) -> UInt64 {
        guard seconds > 0, seconds.isFinite else { return 0 }
        return UInt64((seconds * 1_000_000_000).rounded())
    }
}
