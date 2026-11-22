import Foundation

// ---------------------------------------------------------------------------
// 0.15「共享契约」的 iOS 侧镜像：预算 / 节奏 / 工具轮数 / 记忆 / 提示词标题 /
// 人格语言 / 拉黑协议。
//
// 唯一真源在 `agentcore/src/commonMain/kotlin/com/yap/app/domain/`：
// `ExperienceContract.kt` / `MemoryContract.kt` / `PromptContract.kt` /
// `PromptLanguage.kt` / `BlockProtocol.kt`。Kotlin/Native 的 framework 只能在 iOS
// App 构建里链接（macOS 的 `swift test` 链不了），而这一层是**纯常量与纯函数**，
// 所以 iOS 侧保留一份逐字镜像，由 `SharedContractsParityTests` 读 Kotlin 源码逐条核对：
// 数字、标题、模板、字段名任何一个漂了，测试就红。
//
// 形状刻意做成 `X.shared.y(...)`：Kotlin 的 `object` 跨 ObjC 桥就是
// `X.shared`，将来把这一层换成直接调 framework 时，调用点一行都不用改。
// 同理，所有方法都**显式传参**（K/N 不导出默认参数），不给 Swift 默认值，
// 免得「iOS 用了默认值、Android 没传」这种只在真机上出现的差异。
//
// 硬约束：不读时钟、不读资源、不读 `Locale`。时间/随机数/语言标签全部由调用方传入。
// ---------------------------------------------------------------------------

// MARK: - ExperienceContract（预算 / 节奏 / 轮数）

/// 上下文预算、消息节奏、工具轮数（`agentcore/.../ExperienceContract.kt` 的镜像）。
public final class ExperienceContract {

    public static let shared = ExperienceContract()
    private init() {}

    // MARK: 上下文预算

    public static let DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 1_000_000
    public static let DEFAULT_MAX_OUTPUT_TOKENS: Int = 16_384
    public static let CHARS_PER_TOKEN: Int = 2
    public static let PRACTICAL_HISTORY_CHARS: Int = 28_000

    /// 固定上下文的六段（顺序即提示词里的顺序）。
    public enum ContextSegment: String, CaseIterable, Sendable {
        case fullPersonaAndTools
        case longTermMemory
        case shortTermMemory
        case currentState
        case topicSummary
        case remainingHistory

        /// 稳定 key：与 Kotlin 的 `ContextSegment.key` 逐字一致。
        public var key: String { rawValue }
    }

    /// 各段的预算比例（千分比，合计 1000）。
    public static let SEGMENT_RATIOS_PER_MILLE: [ContextSegment: Int] = [
        .fullPersonaAndTools: 180,
        .longTermMemory: 100,
        .shortTermMemory: 70,
        .currentState: 50,
        .topicSummary: 100,
        .remainingHistory: 500
    ]

    /// 三段有设计硬顶的段：长期 500 / 短期 200 / 主题 120×12。
    public static let SEGMENT_HARD_CAPS: [ContextSegment: Int] = [
        .longTermMemory: MemoryContract.Summary.LONG_TERM_MAX_CHARS,
        .shortTermMemory: MemoryContract.Summary.SHORT_TERM_MAX_CHARS,
        .topicSummary: MemoryContract.Summary.TOPIC_MAX_CHARS
    ]

    public struct SegmentBudget: Equatable, Sendable {
        public var segment: ContextSegment
        public var ratioPerMille: Int
        public var charLimit: Int

        public init(segment: ContextSegment, ratioPerMille: Int, charLimit: Int) {
            self.segment = segment
            self.ratioPerMille = ratioPerMille
            self.charLimit = charLimit
        }
    }

    public struct ContextPlan: Equatable, Sendable {
        public var historyCharBudget: Int
        public var segments: [SegmentBudget]

        public init(historyCharBudget: Int, segments: [SegmentBudget]) {
            self.historyCharBudget = historyCharBudget
            self.segments = segments
        }

        public func charLimit(for segment: ContextSegment) -> Int {
            segments.first(where: { $0.segment == segment })?.charLimit ?? 0
        }

        public var totalCharLimit: Int {
            segments.reduce(0) { $0 + $1.charLimit }
        }

        public var isBalanced: Bool {
            totalCharLimit == max(historyCharBudget, 0)
        }

        /// 返回问题列表；空 = 自洽（与 Kotlin 的 `validate()` 同口径）。
        public func validate() -> [String] {
            var problems: [String] = []
            let safeBudget = max(historyCharBudget, 0)
            if historyCharBudget < 0 { problems.append("historyCharBudget 不能为负：\(historyCharBudget)") }
            var seen = Set<ContextSegment>()
            for budget in segments {
                if !seen.insert(budget.segment).inserted {
                    problems.append("重复的预算段：\(budget.segment.key)")
                }
                if budget.charLimit < 0 {
                    problems.append("预算段 \(budget.segment.key) 的字符上限为负：\(budget.charLimit)")
                }
                if budget.ratioPerMille < 0 {
                    problems.append("预算段 \(budget.segment.key) 的比例为负：\(budget.ratioPerMille)")
                }
            }
            for segment in ContextSegment.allCases where !seen.contains(segment) {
                problems.append("缺少预算段：\(segment.key)")
            }
            if totalCharLimit != safeBudget {
                problems.append("各段合计 \(totalCharLimit) != historyCharBudget \(safeBudget)")
            }
            return problems
        }
    }

    /// `(窗口 − 最大输出) × 每 token 字符数`；默认 `(1,000,000 − 16,384) × 2 = 1,967,232`。
    public func historyCharBudget(contextWindowTokens: Int, maxOutputTokens: Int) -> Int {
        let window = max(contextWindowTokens, 0)
        let output = min(max(maxOutputTokens, 0), window)
        let chars = Int64(window - output) * Int64(Self.CHARS_PER_TOKEN)
        return Int(min(max(chars, 0), Int64(Int.max)))
    }

    /// 按窗口能力规划六段（硬校验 / 诊断用）。
    public func plan(contextWindowTokens: Int, maxOutputTokens: Int) -> ContextPlan {
        planForBudget(historyCharBudget: historyCharBudget(
            contextWindowTokens: contextWindowTokens,
            maxOutputTokens: maxOutputTokens
        ))
    }

    /// 按**产品口径**规划：`min(practicalChars, 窗口折算值)`。聊天装配走这一条。
    public func practicalPlan(
        contextWindowTokens: Int,
        maxOutputTokens: Int,
        practicalChars: Int
    ) -> ContextPlan {
        planForBudget(historyCharBudget: min(
            max(practicalChars, 0),
            historyCharBudget(contextWindowTokens: contextWindowTokens, maxOutputTokens: maxOutputTokens)
        ))
    }

    /// 直接按字符预算规划（算法与 Kotlin 逐行对应）。
    public func planForBudget(historyCharBudget: Int) -> ContextPlan {
        let safeBudget = max(historyCharBudget, 0)
        var limits: [ContextSegment: Int] = [:]
        var used = 0
        for segment in ContextSegment.allCases where segment != .remainingHistory {
            let ratio = Self.SEGMENT_RATIOS_PER_MILLE[segment] ?? 0
            var limit = Int(Int64(safeBudget) * Int64(ratio) / 1000)
            if let cap = Self.SEGMENT_HARD_CAPS[segment] { limit = min(limit, cap) }
            limits[segment] = limit
            used += limit
        }
        let remaining = max(safeBudget - used, 0)
        let segments = ContextSegment.allCases.map { segment -> SegmentBudget in
            let ratio = Self.SEGMENT_RATIOS_PER_MILLE[segment] ?? 0
            let limit = segment == .remainingHistory ? remaining : (limits[segment] ?? 0)
            return SegmentBudget(segment: segment, ratioPerMille: ratio, charLimit: limit)
        }
        return ContextPlan(historyCharBudget: safeBudget, segments: segments)
    }

    // MARK: 消息节奏

    public static let TYPING_BASE_SECONDS: Double = 0.6
    public static let TYPING_SECONDS_PER_CHAR: Double = 0.01
    public static let TYPING_MIN_SECONDS: Double = 0.6
    public static let TYPING_MAX_SECONDS: Double = 0.9
    public static let BURST_GAP_BASE_SECONDS: Double = 0.2
    public static let BURST_GAP_SECONDS_PER_CHAR: Double = 0.012
    public static let BURST_GAP_MIN_SECONDS: Double = 0.2
    public static let BURST_GAP_MAX_SECONDS: Double = 1.5
    public static let RHYTHM_JITTER_RATIO: Double = 0.35
    public static let MAX_MOOD_ADJUSTMENT_SECONDS: Double = 0.4

    /// 首条延迟的均值（秒），不含随机项与心情修正。
    public func typingBaseSeconds(length: Int) -> Double {
        let chars = Double(max(length, 0))
        return min(max(Self.TYPING_BASE_SECONDS + chars * Self.TYPING_SECONDS_PER_CHAR,
                       Self.TYPING_MIN_SECONDS), Self.TYPING_MAX_SECONDS)
    }

    /// 连发间隔的均值（秒）。
    public func burstGapBaseSeconds(length: Int) -> Double {
        let chars = Double(max(length, 0))
        return min(max(Self.BURST_GAP_BASE_SECONDS + chars * Self.BURST_GAP_SECONDS_PER_CHAR,
                       Self.BURST_GAP_MIN_SECONDS), Self.BURST_GAP_MAX_SECONDS)
    }

    /// 首条延迟（秒）：均值 + 心情修正，再乘 ±35% 随机波动。
    public func typingDelaySeconds(length: Int, moodAdjustment: Double, random: () -> Double) -> Double {
        jittered(mean: min(max(typingBaseSeconds(length: length) + safeMoodAdjustment(moodAdjustment),
                               Self.TYPING_MIN_SECONDS), Self.TYPING_MAX_SECONDS),
                 random: random)
    }

    /// 首条延迟（毫秒，四舍五入）。
    public func typingDelayMillis(length: Int, moodAdjustment: Double, random: () -> Double) -> Int64 {
        Int64((typingDelaySeconds(length: length, moodAdjustment: moodAdjustment, random: random) * 1000).rounded())
    }

    public func burstGapSeconds(length: Int, moodAdjustment: Double, random: () -> Double) -> Double {
        jittered(mean: min(max(burstGapBaseSeconds(length: length) + safeMoodAdjustment(moodAdjustment),
                               Self.BURST_GAP_MIN_SECONDS), Self.BURST_GAP_MAX_SECONDS),
                 random: random)
    }

    public func burstGapMillis(length: Int, moodAdjustment: Double, random: () -> Double) -> Int64 {
        Int64((burstGapSeconds(length: length, moodAdjustment: moodAdjustment, random: random) * 1000).rounded())
    }

    private func safeMoodAdjustment(_ seconds: Double) -> Double {
        guard seconds.isFinite else { return 0 }
        return min(max(seconds, -Self.MAX_MOOD_ADJUSTMENT_SECONDS), Self.MAX_MOOD_ADJUSTMENT_SECONDS)
    }

    /// `mean × (1 + (r×2 − 1) × 35%)`；坏随机值按 0.5（= 均值）。
    private func jittered(mean: Double, random: () -> Double) -> Double {
        let raw = random()
        let value = raw.isFinite ? min(max(raw, 0), 1) : 0.5
        let factor = 1 + (value * 2 - 1) * Self.RHYTHM_JITTER_RATIO
        return mean * factor
    }

    // MARK: 工具轮数

    public static let DEFAULT_TOOL_ROUNDS: Int = 5
    public static let MIN_TOOL_ROUNDS: Int = 1
    public static let MAX_TOOL_ROUNDS: Int = 15

    /// `nil` → 默认 5；其余夹到 1..15（0/负数也变成 1）。
    public func clampToolRounds(requested: Int?) -> Int {
        guard let requested else { return Self.DEFAULT_TOOL_ROUNDS }
        return min(max(requested, Self.MIN_TOOL_ROUNDS), Self.MAX_TOOL_ROUNDS)
    }
}

// MARK: - MemoryContract（记忆 / 主题总结）

/// 会话记忆与主题总结的共享规则（`agentcore/.../MemoryContract.kt` 的镜像）。
public final class MemoryContract {

    public static let shared = MemoryContract()
    private init() {}

    public enum Summary {
        public static let LONG_TERM_MAX_CHARS = 500
        public static let SHORT_TERM_MAX_CHARS = 200
        public static let TOPIC_SEGMENT_MAX_CHARS = 120
        public static let TOPIC_MAX_SEGMENTS = 12
        public static let TOPIC_MAX_CHARS = TOPIC_SEGMENT_MAX_CHARS * TOPIC_MAX_SEGMENTS
        public static let SHORT_UPDATE_EVERY_ROUNDS = 5
        public static let LONG_UPDATE_MIN_ROUNDS = 15
        public static let LONG_UPDATE_MAX_ROUNDS = 40
    }

    public struct MemoryItem: Equatable, Sendable {
        public var content: String
        public var drawer: MemoryDrawer
        public var pinned: Bool
        public var importance: Int

        public init(content: String, drawer: MemoryDrawer = .user, pinned: Bool = false, importance: Int = 3) {
            self.content = content
            self.drawer = drawer
            self.pinned = pinned
            self.importance = importance
        }
    }

    /// 一段「对话主题」总结（带时间段）。
    public struct TopicSegment: Equatable, Sendable {
        public var timeLabel: String
        public var summary: String

        public init(timeLabel: String = "", summary: String = "") {
            self.timeLabel = timeLabel
            self.summary = summary
        }
    }

    public enum Retrieval {
        public static let MAX_QUERY_CHARS = 300

        /// 取检索线索：最近一句用户消息 → 压成一行 → 截到 300 字。
        public static func query(lastUserMessage: String?, fallback: String?) -> String {
            let direct = Injection.flatten(lastUserMessage)
            if !direct.isEmpty { return String(direct.prefix(MAX_QUERY_CHARS)) }
            return String(Injection.flatten(fallback).prefix(MAX_QUERY_CHARS))
        }
    }

    public enum Injection {
        public static let TITLE = "# 你的备忘录"
        public static let LEGACY_TITLE = "## 你的备忘录"
        public static let LEGACY_MEMORY_TITLE = "## 你记得的事"
        public static let LONG_TERM_TITLE = "## 长期记忆"
        public static let SHORT_TERM_TITLE = "## 短期记忆"
        public static let TOPIC_TITLE = "## 对话主题"
        public static let MAX_ITEMS = 14
        public static let MAX_ITEM_CHARS = 300
        public static let PINNED_SUFFIX = "（对你很重要）"
        public static let DRAWER_USER_LABEL = "关于对方"
        public static let DRAWER_SELF_LABEL = "你自己是什么样的人"
        public static let DRAWER_EVENT_LABEL = "你们之间的经历与约定"

        public static let DRAWER_ORDER: [MemoryDrawer] = [.user, .selfDrawer, .event]

        public static func drawerLabel(_ drawer: MemoryDrawer) -> String {
            switch drawer {
            case .user: return DRAWER_USER_LABEL
            case .selfDrawer: return DRAWER_SELF_LABEL
            case .event: return DRAWER_EVENT_LABEL
            }
        }

        public static func itemLine(_ item: MemoryItem) -> String {
            let content = flatten(item.content)
            if content.isEmpty { return "" }
            let clipped = content.count <= MAX_ITEM_CHARS
                ? content
                : String(content.prefix(MAX_ITEM_CHARS)) + "…"
            return item.pinned ? "- \(clipped)\(PINNED_SUFFIX)" : "- \(clipped)"
        }

        /// 长期记忆注入块（`# 你的备忘录` + `## 长期记忆`）。
        public static func memoryInjection(items: [MemoryItem], maxItems: Int) -> String {
            let limit = min(max(maxItems, 0), MAX_ITEMS)
            if limit == 0 { return "" }
            let usable = Array(items.filter { !flatten($0.content).isEmpty }.prefix(limit))
            if usable.isEmpty { return "" }
            var lines: [String] = [TITLE, LONG_TERM_TITLE]
            for drawer in DRAWER_ORDER {
                // 同分保持原顺序（Kotlin 的 `sortedByDescending` 是稳定排序）。
                let group = usable.enumerated()
                    .filter { $0.element.drawer == drawer }
                    .sorted { lhs, rhs in
                        lhs.element.importance == rhs.element.importance
                            ? lhs.offset < rhs.offset
                            : lhs.element.importance > rhs.element.importance
                    }
                    .map(\.element)
                if group.isEmpty { continue }
                lines.append("**\(drawerLabel(drawer))**")
                for item in group {
                    let line = itemLine(item)
                    if !line.isEmpty { lines.append(line) }
                }
            }
            return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        }

        /// 会话备忘录：顺序固定「长期 → 短期 → 主题」，空段省略。
        public static func notes(longTerm: String?, shortTerm: String?, topics: [TopicSegment]) -> String {
            let long = clipParagraph(longTerm, max: Summary.LONG_TERM_MAX_CHARS)
            let short = clipParagraph(shortTerm, max: Summary.SHORT_TERM_MAX_CHARS)
            let topicLines = topics.prefix(Summary.TOPIC_MAX_SEGMENTS).compactMap { topicLine($0) }
            if long.isEmpty && short.isEmpty && topicLines.isEmpty { return "" }
            var lines: [String] = [TITLE]
            if !long.isEmpty {
                lines.append(LONG_TERM_TITLE)
                lines.append("- \(long)")
            }
            if !short.isEmpty {
                lines.append(SHORT_TERM_TITLE)
                lines.append("- \(short)")
            }
            if !topicLines.isEmpty {
                lines.append(TOPIC_TITLE)
                lines.append(contentsOf: topicLines)
            }
            return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        }

        /// 一行主题总结：`- 2026-11-18 晚：加班与方案改版`。
        public static func topicLine(_ topic: TopicSegment) -> String? {
            let summary = flatten(topic.summary)
            if summary.isEmpty { return nil }
            let clipped = summary.count <= Summary.TOPIC_SEGMENT_MAX_CHARS
                ? summary
                : String(summary.prefix(Summary.TOPIC_SEGMENT_MAX_CHARS)) + "…"
            let label = flatten(topic.timeLabel)
            return label.isEmpty ? "- \(clipped)" : "- \(label)：\(clipped)"
        }

        /// 压成一行（提示词里的摘要不需要换行）。
        public static func flatten(_ raw: String?) -> String {
            (raw ?? "").replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        /// 截断到 `max` 字并补「…」；空/纯空白返回空串。
        public static func clipParagraph(_ raw: String?, max maxChars: Int) -> String {
            let text = flatten(raw)
            if text.isEmpty || maxChars <= 0 { return "" }
            return text.count <= maxChars ? text : String(text.prefix(maxChars)) + "…"
        }
    }
}

// 与 Kotlin `MemoryDrawer`（agentcore 既有的 `MemoryCommon.kt`）取值一致。
// iOS 已有一份 `MemoryDrawer`（`Domain/MemoryCommon.swift`），这里直接复用它的 rawValue，
// 不再定义第二个同名类型。

// MARK: - PromptContract（段落标题 / 行模板 / 时间）

/// 两端共用的提示词标题与行模板（`agentcore/.../PromptContract.kt` 的镜像）。
public enum PromptContract {

    public enum Section {
        public static let IDENTITY = PersonaPromptScaffold.identitySection
        public static let WORLD = PersonaPromptScaffold.worldSection
        public static let STATE = PersonaPromptScaffold.stateSection
        public static let VOICE = PersonaPromptScaffold.voiceSection
        public static let RELATIONSHIP = PersonaPromptScaffold.relationshipSection
        public static let BOUNDARIES = PersonaPromptScaffold.boundariesSection
        public static let CAPABILITIES = "# 你能做什么"
        public static let MOOD = "# 你的心情"
        public static let ANTI_AI = "# 别像 AI"
        public static let MEMO = MemoryContract.Injection.TITLE
        public static let MENTAL_ACTIVITY = "# 你刚才心里想"
        public static let LANGUAGE = "## 语言"

        public static let all: [String: String] = [
            "identity": IDENTITY,
            "world": WORLD,
            "state": STATE,
            "voice": VOICE,
            "relationship": RELATIONSHIP,
            "capabilities": CAPABILITIES,
            "mood": MOOD,
            "antiAi": ANTI_AI,
            "memo": MEMO,
            "mentalActivity": MENTAL_ACTIVITY,
            "boundaries": BOUNDARIES,
            "language": LANGUAGE
        ]
    }

    public enum ToolCapability {
        public static let TITLE = Section.CAPABILITIES
        public static let LEAD =
            "下面这些是你自己的手。需要的时候直接调用，别为了显得勤快而用；用不上就不要调用。"
        public static let NAME_SEPARATOR = "："
        public static let CONTRAST_PREFIX = "  · 区别："
        public static let OPTIONAL_MARK = "?"
        public static let PARAM_SEPARATOR = ", "
        public static let OFFLINE_NOTE =
            "- 你现在没有联网能力。涉及实时信息时，老实说自己不确定，别编。"

        public static func signature(name: String, required: [String], optional: [String]) -> String {
            let toolName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let req = required.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            let opt = optional.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            let params = req + opt.map { $0 + OPTIONAL_MARK }
            if params.isEmpty { return toolName }
            return toolName + "(" + params.joined(separator: PARAM_SEPARATOR) + ")"
        }

        public static func line(signature: String, description: String, contrast: String) -> String {
            let sig = signature.trimmingCharacters(in: .whitespacesAndNewlines)
            let desc = description.trimmingCharacters(in: .whitespacesAndNewlines)
            let head = desc.isEmpty ? "- \(sig)" : "- \(sig)\(NAME_SEPARATOR)\(desc)"
            let detail = contrast.trimmingCharacters(in: .whitespacesAndNewlines)
            return detail.isEmpty ? head : head + "\n" + CONTRAST_PREFIX + detail
        }

        public static func section(lines: [String]) -> String {
            ([TITLE] + lines.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
                .joined(separator: "\n")
        }
    }

    public enum Mood {
        public static let TITLE = Section.MOOD
        public static let LINE = "心情：{label}（强度 {intensity}/5）。"
        public static let DIMENSIONS_LINE = "愉快度 {valence} / 唤醒度 {arousal}/5 / 掌控感 {dominance}/5。"
        public static let INTENSITY_MIN = 1
        public static let INTENSITY_MAX = 5
        public static let VALENCE_MIN = -2
        public static let VALENCE_MAX = 2
        public static let AROUSAL_MIN = 0
        public static let AROUSAL_MAX = 5
        public static let DOMINANCE_MIN = 0
        public static let DOMINANCE_MAX = 5

        public static func line(label: String, intensity: Int) -> String {
            LINE
                .replacingOccurrences(of: "{label}", with: label.trimmingCharacters(in: .whitespacesAndNewlines))
                .replacingOccurrences(of: "{intensity}",
                                     with: String(min(max(intensity, INTENSITY_MIN), INTENSITY_MAX)))
        }

        public static func dimensionsLine(valence: Int, arousal: Int, dominance: Int) -> String {
            DIMENSIONS_LINE
                .replacingOccurrences(of: "{valence}",
                                     with: String(min(max(valence, VALENCE_MIN), VALENCE_MAX)))
                .replacingOccurrences(of: "{arousal}",
                                     with: String(min(max(arousal, AROUSAL_MIN), AROUSAL_MAX)))
                .replacingOccurrences(of: "{dominance}",
                                     with: String(min(max(dominance, DOMINANCE_MIN), DOMINANCE_MAX)))
        }
    }

    public enum AntiAi {
        public static let TITLE = Section.ANTI_AI

        /// core 四条（0.15 profile `anti=core`，`spec/context-profile/README.md`）。
        ///
        /// 原来有七条，评测里「短句与口头禅」「不主动给建议」「stay_silent 的用法」
        /// 与这四条大量重叠，删掉之后综合分反而更高
        ///（`eval/reports/context-search-axes-*.md`：core 87.8 vs full 86.5）。
        /// 第五条「不写旁白」在 iOS 侧是 `AntiAIPrompt.noNarrationRule`：本数组是 Kotlin
        /// 真源的镜像，Kotlin 那边还没有那一条，等两端对齐之后再收进来。
        public static let RULES: [String] = [
            "不要每句话都反问；不需要每轮都把话题抛回去。",
            "不要总结对方说的话，也不要说「所以你的意思是…」。",
            "不要列点、不要「首先/其次/最后」，不要写成一段说明书。",
            "不要每次都认同或安慰；可以不同意、可以只回一句、也可以不回（stay_silent）。"
        ]

        public static func section() -> String {
            ([TITLE] + RULES.map { "- \($0)" }).joined(separator: "\n")
        }
    }

    public enum Boundaries {
        public static let TITLE = Section.BOUNDARIES
        public static let RULES: [String] = PersonaPromptScaffold.boundaryRules

        public static func section() -> String {
            ([TITLE] + RULES.map { "- \($0)" }).joined(separator: "\n")
        }
    }

    public enum Memo {
        public static let TITLE = MemoryContract.Injection.TITLE
        public static let LONG_TITLE = MemoryContract.Injection.LONG_TERM_TITLE
        public static let SHORT_TITLE = MemoryContract.Injection.SHORT_TERM_TITLE
        public static let TOPIC_TITLE = MemoryContract.Injection.TOPIC_TITLE
        public static let LONG_MAX_CHARS = MemoryContract.Summary.LONG_TERM_MAX_CHARS
        public static let SHORT_MAX_CHARS = MemoryContract.Summary.SHORT_TERM_MAX_CHARS
        public static let TOPIC_SEGMENT_MAX_CHARS = MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS
        public static let TOPIC_MAX_SEGMENTS = MemoryContract.Summary.TOPIC_MAX_SEGMENTS

        public static func block(
            longTerm: String?,
            shortTerm: String?,
            topics: [MemoryContract.TopicSegment]
        ) -> String {
            MemoryContract.Injection.notes(longTerm: longTerm, shortTerm: shortTerm, topics: topics)
        }
    }

    public enum MentalActivity {
        public static let TITLE = Section.MENTAL_ACTIVITY
        public static let LINE_PREFIX = "你刚才心里想："
        public static let MAX_THOUGHT_CHARS = 60

        public static func sanitizeThought(_ raw: String?) -> String {
            String(MemoryContract.Injection.flatten(raw).prefix(MAX_THOUGHT_CHARS))
        }

        public static func line(_ thought: String?) -> String? {
            let text = sanitizeThought(thought)
            return text.isEmpty ? nil : LINE_PREFIX + text
        }

        public static func block(_ thought: String?) -> String {
            let text = sanitizeThought(thought)
            return text.isEmpty ? "" : "\(TITLE)\n\(LINE_PREFIX)\(text)"
        }
    }

    public enum Time {
        public static let HH_MM_PATTERN = "HH:mm"
        public static let DATE_PATTERN = "yyyy-MM-dd"
        public static let UTC_PREFIX = "UTC"
        public static let STATE_LINE = PersonaPromptScaffold.stateLine
        public static let WEEKDAYS_ZH = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

        public static func weekdayZh(mondayBasedIndex: Int) -> String? {
            guard WEEKDAYS_ZH.indices.contains(mondayBasedIndex) else { return nil }
            return WEEKDAYS_ZH[mondayBasedIndex]
        }

        public static func zoneOffsetLabel(offsetMinutes: Int) -> String {
            let total = min(max(offsetMinutes, -18 * 60), 18 * 60)
            if total == 0 { return UTC_PREFIX }
            let sign = total < 0 ? "-" : "+"
            let abs = Swift.abs(total)
            return UTC_PREFIX + sign + padTwo(abs / 60) + ":" + padTwo(abs % 60)
        }

        public static func stateLine(weekday: String, time: String) -> String {
            STATE_LINE
                .replacingOccurrences(of: "{weekday}", with: weekday.trimmingCharacters(in: .whitespacesAndNewlines))
                .replacingOccurrences(of: "{time}", with: time.trimmingCharacters(in: .whitespacesAndNewlines))
        }

        public static func clockText(date: String, weekday: String, time: String, offsetMinutes: Int) -> String {
            date.trimmingCharacters(in: .whitespacesAndNewlines) + " "
                + weekday.trimmingCharacters(in: .whitespacesAndNewlines) + " "
                + time.trimmingCharacters(in: .whitespacesAndNewlines)
                + "（" + zoneOffsetLabel(offsetMinutes: offsetMinutes) + "）"
        }

        private static func padTwo(_ value: Int) -> String {
            value < 10 ? "0\(value)" : String(value)
        }
    }
}

// MARK: - PromptLanguage / PromptTemplates（人格语言）

/// 人格提示词的语言（`agentcore/.../PromptLanguage.kt` 的镜像）。
public enum PromptLanguage: String, CaseIterable, Sendable {
    case zh, en, ja, ko, fr, de, es, pt, ru, ar

    public var code: String { rawValue }

    public var englishName: String {
        switch self {
        case .zh: return "Chinese"
        case .en: return "English"
        case .ja: return "Japanese"
        case .ko: return "Korean"
        case .fr: return "French"
        case .de: return "German"
        case .es: return "Spanish"
        case .pt: return "Portuguese"
        case .ru: return "Russian"
        case .ar: return "Arabic"
        }
    }

    public var nativeName: String {
        switch self {
        case .zh: return "中文"
        case .en: return "English"
        case .ja: return "日本語"
        case .ko: return "한국어"
        case .fr: return "Français"
        case .de: return "Deutsch"
        case .es: return "Español"
        case .pt: return "Português"
        case .ru: return "Русский"
        case .ar: return "العربية"
        }
    }

    public static let DIRECTIVE_ZH = "用中文思考和说话。"

    /// 写给模型的语言指令：中文单独一句，其余用英文。
    public func directive() -> String {
        self == .zh ? Self.DIRECTIVE_ZH : "Think and speak in \(englishName)."
    }

    /// 宽容解析语言标签（`zh-CN` / `EN` / `中文` / `English` 都认）。
    public static func parse(_ tag: String?) -> PromptLanguage? {
        let raw = (tag ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return nil }
        let normalized = raw.lowercased().replacingOccurrences(of: "_", with: "-")
        let primary = normalized.components(separatedBy: "-").first ?? normalized
        return allCases.first { language in
            language.code == normalized || language.code == primary
                || language.englishName.lowercased() == normalized
                || language.nativeName.lowercased() == normalized
        }
    }

    /// 解析；认不出时回落（默认中文 = 产品的默认人格语言）。
    public static func resolve(_ tag: String?, fallback: PromptLanguage) -> PromptLanguage {
        parse(tag) ?? fallback
    }
}

/// 六个规则段的双语标题与模板（`PromptTemplates` 的镜像）。
public enum PromptTemplates {

    public enum CoreSection: String, CaseIterable, Sendable {
        case capabilities, mood, antiAi, boundaries, memo, mentalActivity
    }

    public static func title(section: CoreSection, language: PromptLanguage) -> String {
        language == .zh ? zhTitle(section) : enTitle(section)
    }

    public static func body(section: CoreSection, language: PromptLanguage) -> [String] {
        language == .zh ? zhBody(section) : enBody(section)
    }

    public static func render(section: CoreSection, language: PromptLanguage) -> String {
        ([title(section: section, language: language)]
            + body(section: section, language: language).map { "- \($0)" })
            .joined(separator: "\n")
    }

    public static func directive(language: PromptLanguage) -> String {
        language.directive()
    }

    /// 按**人格语言**生成指令；页面语言不参与。
    public static func directiveForPersona(personaTag: String?, fallback: PromptLanguage) -> String {
        PromptLanguage.resolve(personaTag, fallback: fallback).directive()
    }

    public static func moodLine(language: PromptLanguage, label: String, intensity: Int) -> String {
        let safe = min(max(intensity, PromptContract.Mood.INTENSITY_MIN), PromptContract.Mood.INTENSITY_MAX)
        if language == .zh { return PromptContract.Mood.line(label: label, intensity: safe) }
        return "Mood: \(label.trimmingCharacters(in: .whitespacesAndNewlines)) (intensity \(safe)/5)."
    }

    public static func mentalActivityLine(language: PromptLanguage, thought: String?) -> String {
        let text = PromptContract.MentalActivity.sanitizeThought(thought)
        if text.isEmpty { return "" }
        return language == .zh
            ? PromptContract.MentalActivity.LINE_PREFIX + text
            : "You were just thinking: \(text)"
    }

    private static func zhTitle(_ section: CoreSection) -> String {
        switch section {
        case .capabilities: return PromptContract.Section.CAPABILITIES
        case .mood: return PromptContract.Section.MOOD
        case .antiAi: return PromptContract.Section.ANTI_AI
        case .boundaries: return PromptContract.Section.BOUNDARIES
        case .memo: return PromptContract.Section.MEMO
        case .mentalActivity: return PromptContract.Section.MENTAL_ACTIVITY
        }
    }

    private static func zhBody(_ section: CoreSection) -> [String] {
        switch section {
        case .capabilities:
            return [PromptContract.ToolCapability.LEAD]
        case .mood:
            return [
                PromptContract.Mood.LINE,
                "不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。"
            ]
        case .antiAi:
            return PromptContract.AntiAi.RULES
        case .boundaries:
            return PromptContract.Boundaries.RULES
        case .memo:
            return ["这是你自己记下的摘要（不是人设，会随时间更新）；回答时自然地用，不要逐条复述。"]
        case .mentalActivity:
            return [
                PromptContract.MentalActivity.LINE_PREFIX + "{thought}",
                "这句不要说出来，也不要引用；它只影响你这轮的语气。"
            ]
        }
    }

    private static func enTitle(_ section: CoreSection) -> String {
        switch section {
        case .capabilities: return "# What you can do"
        case .mood: return "# Your mood"
        case .antiAi: return "# Don't sound like an AI"
        case .boundaries: return "# Boundaries"
        case .memo: return "# Your notebook"
        case .mentalActivity: return "# What you were just thinking"
        }
    }

    private static func enBody(_ section: CoreSection) -> [String] {
        switch section {
        case .capabilities:
            return ["These are your own hands. Use them when you actually need them, not to look busy."]
        case .mood:
            return [
                "Mood: {label} (intensity {intensity}/5).",
                "Don't report the numbers or say \"my emotion is…\"; let it show in how you speak."
            ]
        case .antiAi:
            return EN_ANTI_AI_RULES
        case .boundaries:
            return EN_BOUNDARY_RULES
        case .memo:
            return ["This is your own notebook of what you remember (not your persona; it changes over time). "
                + "Use it naturally; don't repeat it item by item."]
        case .mentalActivity:
            return [
                "You were just thinking: {thought}",
                "Don't say it out loud or quote it; it only colors your tone."
            ]
        }
    }

    /// 英文的 core 四条：与中文一一对应（0.15 只保留 core 五条，第五条「不写旁白」
    /// 在 iOS 侧是 `PersonaLanguagePrompt.englishNoNarrationRule`）。
    private static let EN_ANTI_AI_RULES: [String] = [
        "Don't end every sentence with a question; you don't have to throw the topic back every turn.",
        "Don't summarize what they said, and don't say \"so what you mean is…\".",
        "No bullet lists, no \"first / second / finally\", no manual-style paragraphs.",
        "Don't agree or comfort every time; you may disagree, reply with one sentence, or not reply at all (stay_silent)."
    ]

    private static let EN_BOUNDARY_RULES: [String] = [
        "Don't explain that you're an AI, restate your persona, summarize the conversation, or make decisions for them.",
        "No action descriptions and no stage directions in parentheses; don't refer to yourself in the third person.",
        "If you don't know something, say \"I'm not sure\" or \"let's talk about it later\"; don't invent facts about the real world.",
        "Don't psychoanalyze them, and don't start every sentence with the same catchphrase."
    ]
}

// MARK: - BlockProtocol（拉黑两轮确认）

/// 「AI 主动拉黑」协议（`agentcore/.../BlockProtocol.kt` 的镜像）。
///
/// iOS 已有一份 `BlockPolicy` / `BlockProposal`（`Domain/BlockPolicy.swift`，跑在聊天链路里）。
/// 这一层把**字段名、状态值、七键 JSON、三种返回形状**收成共享口径：`BlockPolicy` 继续
/// 负责本地判定，JSON 形状与常量以本类型为准（`BlockProtocolParityTests` 钉住两者一致）。
public final class BlockProtocol {

    public static let shared = BlockProtocol()
    private init() {}

    public static let STATUS_PENDING_CONFIRMATION = "pending_confirmation"
    public static let STATUS_BLOCKED = "blocked"
    public static let STATUS_REJECTED = "rejected"
    public static let STATUSES: [String] = [STATUS_PENDING_CONFIRMATION, STATUS_BLOCKED, STATUS_REJECTED]

    public static let FIELD_PROPOSAL_ID = "proposalId"
    public static let FIELD_PERSONA_ID = "personaId"
    public static let FIELD_HOURS = "hours"
    public static let FIELD_REASON = "reason"
    public static let FIELD_USER_MESSAGE_ID = "userMessageId"
    public static let FIELD_PROPOSED_AT = "proposedAt"
    public static let FIELD_EXPIRES_AT = "expiresAt"
    public static let FIELD_STATUS = "status"
    public static let FIELD_BLOCKED_UNTIL = "blockedUntil"
    public static let FIELD_CODE = "code"

    public static let PROPOSAL_FIELDS: [String] = [
        FIELD_PROPOSAL_ID, FIELD_PERSONA_ID, FIELD_HOURS, FIELD_REASON,
        FIELD_USER_MESSAGE_ID, FIELD_PROPOSED_AT, FIELD_EXPIRES_AT
    ]
    public static let PENDING_FIELDS: [String] = [FIELD_STATUS, FIELD_EXPIRES_AT]
    public static let BLOCKED_FIELDS: [String] = [FIELD_STATUS, FIELD_BLOCKED_UNTIL]
    public static let REJECTED_FIELDS: [String] = [FIELD_STATUS, FIELD_CODE]

    public static let PROPOSAL_TTL_MS: Int64 = 10 * 60 * 1000
    public static let MIN_HOURS = 1
    public static let MAX_HOURS = 24
    public static let DEFAULT_HOURS = 12
    public static let MAX_REASON_CHARS = 120

    /// 规范 §9 的七字段提议。
    public struct Proposal: Equatable, Sendable {
        public var proposalId: String
        public var personaId: String
        public var hours: Int
        public var reason: String
        public var userMessageId: String
        public var proposedAt: Int64
        public var expiresAt: Int64

        public init(
            proposalId: String,
            personaId: String,
            hours: Int,
            reason: String,
            userMessageId: String,
            proposedAt: Int64,
            expiresAt: Int64
        ) {
            self.proposalId = proposalId
            self.personaId = personaId
            self.hours = hours
            self.reason = reason
            self.userMessageId = userMessageId
            self.proposedAt = proposedAt
            self.expiresAt = expiresAt
        }
    }

    public func clampHours(_ hours: Int) -> Int {
        min(max(hours, Self.MIN_HOURS), Self.MAX_HOURS)
    }

    public func expiresAtFor(proposedAt: Int64) -> Int64 {
        proposedAt + Self.PROPOSAL_TTL_MS
    }

    public func sanitize(_ proposal: Proposal) -> Proposal {
        Proposal(
            proposalId: proposal.proposalId.trimmingCharacters(in: .whitespacesAndNewlines),
            personaId: proposal.personaId.trimmingCharacters(in: .whitespacesAndNewlines),
            hours: clampHours(proposal.hours),
            reason: String(proposal.reason.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(Self.MAX_REASON_CHARS)),
            userMessageId: proposal.userMessageId.trimmingCharacters(in: .whitespacesAndNewlines),
            proposedAt: proposal.proposedAt,
            expiresAt: proposal.expiresAt
        )
    }

    /// 编码成七键 JSON（字段顺序固定，缺省字段也写出）。
    public func encode(_ proposal: Proposal) -> String {
        let fields: [(String, String)] = [
            (Self.FIELD_PROPOSAL_ID, quoted(proposal.proposalId)),
            (Self.FIELD_PERSONA_ID, quoted(proposal.personaId)),
            (Self.FIELD_HOURS, String(proposal.hours)),
            (Self.FIELD_REASON, quoted(proposal.reason)),
            (Self.FIELD_USER_MESSAGE_ID, quoted(proposal.userMessageId)),
            (Self.FIELD_PROPOSED_AT, String(proposal.proposedAt)),
            (Self.FIELD_EXPIRES_AT, String(proposal.expiresAt))
        ]
        return "{" + fields.map { "\(quoted($0.0)):\($0.1)" }.joined(separator: ",") + "}"
    }

    /// 解码七键 JSON：不是对象 / 坏 JSON / 没有 `proposalId` → nil；多余键忽略、缺省取默认。
    public func decode(_ raw: String?) -> Proposal? {
        guard let object = Self.parseObject(raw) else { return nil }
        let proposalId = (object[Self.FIELD_PROPOSAL_ID] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if proposalId.isEmpty { return nil }
        let hoursRaw = Self.longValue(object[Self.FIELD_HOURS]) ?? Int64(Self.DEFAULT_HOURS)
        let hours = Int(min(max(hoursRaw, Int64(Self.MIN_HOURS)), Int64(Self.MAX_HOURS)))
        return Proposal(
            proposalId: proposalId,
            personaId: (object[Self.FIELD_PERSONA_ID] as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines),
            hours: hours,
            reason: String(((object[Self.FIELD_REASON] as? String) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.MAX_REASON_CHARS)),
            userMessageId: (object[Self.FIELD_USER_MESSAGE_ID] as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines),
            proposedAt: Self.longValue(object[Self.FIELD_PROPOSED_AT]) ?? 0,
            expiresAt: Self.longValue(object[Self.FIELD_EXPIRES_AT]) ?? 0
        )
    }

    public func pendingJson(expiresAt: Int64) -> String {
        statusJson(status: Self.STATUS_PENDING_CONFIRMATION, numberField: Self.FIELD_EXPIRES_AT, number: expiresAt)
    }

    public func blockedJson(blockedUntil: Int64) -> String {
        statusJson(status: Self.STATUS_BLOCKED, numberField: Self.FIELD_BLOCKED_UNTIL, number: blockedUntil)
    }

    public func rejectedJson(code: String) -> String {
        "{" + quoted(Self.FIELD_STATUS) + ":" + quoted(Self.STATUS_REJECTED) + ","
            + quoted(Self.FIELD_CODE) + ":" + quoted(code.trimmingCharacters(in: .whitespacesAndNewlines)) + "}"
    }

    public func statusOf(_ raw: String?) -> String? {
        guard let object = Self.parseObject(raw) else { return nil }
        let value = (object[Self.FIELD_STATUS] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    public func isKnownStatus(_ status: String?) -> Bool {
        guard let status else { return false }
        return Self.STATUSES.contains(status)
    }

    /// 提议是否作废：时钟倒退也按过期处理。
    public func isExpired(_ proposal: Proposal, now: Int64) -> Bool {
        now < proposal.proposedAt || now >= proposal.expiresAt
    }

    private func statusJson(status: String, numberField: String, number: Int64) -> String {
        "{" + quoted(Self.FIELD_STATUS) + ":" + quoted(status) + ","
            + quoted(numberField) + ":" + String(number) + "}"
    }

    private func quoted(_ raw: String) -> String {
        var escaped = raw
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        escaped = escaped
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
            .replacingOccurrences(of: "\t", with: "\\t")
        return "\"\(escaped)\""
    }

    private static func parseObject(_ raw: String?) -> [String: Any]? {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func longValue(_ raw: Any?) -> Int64? {
        if let number = raw as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
            return number.int64Value
        }
        if let text = raw as? String { return Int64(text) }
        return nil
    }
}
