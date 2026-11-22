package com.yap.app.domain

import kotlin.math.round

/**
 * 0.15「体验契约」里**两端必须一致、且不需要平台能力**的三组规则：
 * 上下文预算、消息节奏、工具轮数。
 *
 * 为什么放在 `:agentcore`：这三组数字此前分别写在 Android 的 `ApiDefaults` / `ExperienceRhythm` /
 * `ChatRepository` 与 iOS 的 `ApiDefaults` / `TypingDelay` / `ChatService` 里；同一句
 * `clamp(0.4 + 字数×0.025, …)` 改一处忘一处，用户能直接感觉到「同一个模型，两个 App 打字速度不一样」。
 * 这里先新增唯一口径，两端迁移由后续 agent 做（本文件不删任何现有类）。
 *
 * 三块内容：
 * 1. **上下文预算**：1M 窗口 − 16,384 输出 → 历史字符预算（1 token ≈ 2 字符），
 *    再按固定段拆成「完整设定+工具 / 长期记忆 / 短期记忆 / 当前状态 / 主题总结 / 剩余历史」；
 * 2. **消息节奏**：首条打字延迟与连发间隔，都改成「均值正比于长度 + ±35% 随机波动」，
 *    随机源由调用方注入（测试可精确钉住，生产传 `Random.nextDouble()`）；
 * 3. **工具轮数**：默认 5 轮、硬上限 15 轮，模型指定轮数时夹到 1..15。
 */
object ExperienceContract {

    // ================================================================== 上下文预算

    /** 上下文窗口默认上限（tokens）：只作为上限，不是「每次都要发满」。 */
    const val DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 1_000_000

    /** 单次最大输出（tokens）：工具回路每一轮同样用这个上限。 */
    const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 16_384

    /**
     * tokens → 字符的保守折算：中英混排按 1 token ≈ 2 字符估。
     *
     * 估小一点更安全：低估字符预算只会让历史裁得更早，不会撞上窗口。
     */
    const val CHARS_PER_TOKEN: Int = 2

    /**
     * 产品侧额外收紧的「历史正文字符预算」（Android `PRACTICAL_CONTEXT_CHARS` /
     * iOS `PromptBuilder.contextCharBudget` 都是 28,000）。
     *
     * 1M 是模型能力，28k 是首字时间与成本；[practicalPlan] 会把两者取小。
     */
    const val PRACTICAL_HISTORY_CHARS: Int = 28_000

    /**
     * 固定上下文的六段（顺序即提示词里的出现顺序，来自 `docs/design/context-memory-v2.md`）。
     *
     * [key] 是稳定字符串：协议、日志、Swift 侧都按它对齐，不随枚举重命名漂移。
     */
    enum class ContextSegment(val key: String) {
        /** 完整设定：身份/世界/关系/话风/边界 + 工具清单与「你能做什么」。 */
        FULL_PERSONA_AND_TOOLS("fullPersonaAndTools"),

        /** 长期记忆缓存：稳定事实、约定、重要事件（≤500 字）。 */
        LONG_TERM_MEMORY("longTermMemory"),

        /** 短期记忆缓存：最近几轮的计划、待办、情绪线索（≤200 字）。 */
        SHORT_TERM_MEMORY("shortTermMemory"),

        /** 当前状态：时间、活动、睡眠、心情（直觉标签 + 三维）。 */
        CURRENT_STATE("currentState"),

        /** 对话主题总结：分段滚动摘要（每段 ≤120 字、最多 12 段）。 */
        TOPIC_SUMMARY("topicSummary"),

        /** 剩余预算：全部给最近聊天原文。 */
        REMAINING_HISTORY("remainingHistory")
    }

    /**
     * 各段的**预算比例**（千分比，合计 1000‰）。
     *
     * 比例是**上限**，不是实际占用：长期/短期/主题三段的真实上限还受设计文档的硬顶约束
     * （见 [SEGMENT_HARD_CAPS]），剩余历史则吃掉「预算 − 前五段」的全部余额。
     * 用千分比而不是 Double，是为了让两个平台算出来的整数完全一致（浮点乘法会差 1 个字符）。
     */
    val SEGMENT_RATIOS_PER_MILLE: Map<ContextSegment, Int> = linkedMapOf(
        ContextSegment.FULL_PERSONA_AND_TOOLS to 180,
        ContextSegment.LONG_TERM_MEMORY to 100,
        ContextSegment.SHORT_TERM_MEMORY to 70,
        ContextSegment.CURRENT_STATE to 50,
        ContextSegment.TOPIC_SUMMARY to 100,
        ContextSegment.REMAINING_HISTORY to 500
    )

    /**
     * 三段有设计硬顶的段（其余段只受比例约束）。
     *
     * 长期 500 / 短期 200 / 主题 120×12 来自 `docs/design/context-memory-v2.md`；
     * 它们与 [MemoryContract] 共用同一份常量，避免「预算规划 500、摘要生成 600」。
     */
    val SEGMENT_HARD_CAPS: Map<ContextSegment, Int> = linkedMapOf(
        ContextSegment.LONG_TERM_MEMORY to MemoryContract.Summary.LONG_TERM_MAX_CHARS,
        ContextSegment.SHORT_TERM_MEMORY to MemoryContract.Summary.SHORT_TERM_MAX_CHARS,
        ContextSegment.TOPIC_SUMMARY to MemoryContract.TOPIC_SUMMARY_CHAR_BUDGET
    )

    /** 一段的预算：比例（千分比）与最终字符上限。 */
    data class SegmentBudget(
        val segment: ContextSegment,
        val ratioPerMille: Int,
        val charLimit: Int
    )

    /**
     * 一次上下文规划的结论。
     *
     * 不变量（[validate] 逐条检查）：
     * - 六段齐全、不重复；
     * - 每段字符上限非负；
     * - [totalCharLimit] 恰好等于 [historyCharBudget]（剩余历史吃掉余额，所以永远相等）。
     */
    data class ContextPlan(
        val historyCharBudget: Int,
        val segments: List<SegmentBudget>
    ) {
        /** 某段的字符上限；段不存在时返回 0（不给调用方 null 处理负担）。 */
        fun charLimitFor(segment: ContextSegment): Int =
            segments.firstOrNull { it.segment == segment }?.charLimit ?: 0

        /** 各段字符上限合计。 */
        val totalCharLimit: Int
            get() = segments.fold(0) { sum, budget -> sum + budget.charLimit }

        /** 合计是否与预算相等（正常规划永远为 true）。 */
        val isBalanced: Boolean
            get() = totalCharLimit == historyCharBudget.coerceAtLeast(0)

        /**
         * 总校验：返回问题列表，空列表 = 规划自洽。
         *
         * 用「问题列表」而不是布尔，是为了日志里能直接看出是哪一段、差多少。
         */
        fun validate(): List<String> {
            val problems = ArrayList<String>()
            val safeBudget = historyCharBudget.coerceAtLeast(0)
            if (historyCharBudget < 0) problems += "historyCharBudget 不能为负：$historyCharBudget"
            val seen = LinkedHashSet<ContextSegment>()
            for (budget in segments) {
                if (!seen.add(budget.segment)) {
                    problems += "重复的预算段：${budget.segment.key}"
                }
                if (budget.charLimit < 0) {
                    problems += "预算段 ${budget.segment.key} 的字符上限为负：${budget.charLimit}"
                }
                if (budget.ratioPerMille < 0) {
                    problems += "预算段 ${budget.segment.key} 的比例为负：${budget.ratioPerMille}"
                }
            }
            for (segment in ContextSegment.entries) {
                if (segment !in seen) problems += "缺少预算段：${segment.key}"
            }
            if (totalCharLimit != safeBudget) {
                problems += "各段合计 $totalCharLimit != historyCharBudget $safeBudget"
            }
            return problems
        }
    }

    /**
     * 上下文窗口折算出的**输入**字符容量：`(窗口 − 单次最大输出) × 每 token 字符数`。
     *
     * 减掉输出是必须的：窗口是「输入 + 输出」的总和，把 1M 全给输入会让最后一段回复被截断。
     * 默认参数下 = `(1,000,000 − 16,384) × 2 = 1,967,232`。
     *
     * 与 Android `ApiDefaults.inputCharBudget` / iOS `ApiDefaults.historyCharBudget` 同式同值，
     * 但这里不依赖平台 API，两端与 `agentd` 共用。
     */
    fun historyCharBudget(
        contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS
    ): Int {
        val window = contextWindowTokens.coerceAtLeast(0)
        val output = maxOutputTokens.coerceIn(0, window)
        val tokens = window - output
        val chars = tokens.toLong() * CHARS_PER_TOKEN
        return chars.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 按窗口预算规划六段：`plan()` 的默认入口。
     *
     * 注意：1M 窗口给的是**能力上限**，实际发送还要过 [practicalPlan] 的 28k 产品口径；
     * 本函数用于「最多能放多少」的硬校验（测试、诊断面板、agentd 的预检）。
     */
    fun plan(
        contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS
    ): ContextPlan = planForBudget(historyCharBudget(contextWindowTokens, maxOutputTokens))

    /**
     * 按**产品口径**规划：`min(28,000, 窗口折算值)`。
     *
     * 这才是聊天装配真正该用的预算：1M 不会让请求体真的胀到 1M；只有在窗口配得很小时
     * （例如 20K）才会真的收紧历史。
     */
    fun practicalPlan(
        contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        practicalChars: Int = PRACTICAL_HISTORY_CHARS
    ): ContextPlan = planForBudget(
        minOf(practicalChars.coerceAtLeast(0), historyCharBudget(contextWindowTokens, maxOutputTokens))
    )

    /**
     * 直接按字符预算规划（窗口换算之后的那一步）。
     *
     * 算法（顺序有意义）：
     * 1. 前五段 `floor(预算 × 比例 / 1000)`，有硬顶的再取 `min(硬顶)`；
     * 2. 剩余历史 = 预算 − 前五段合计（**吃掉所有余额**，保证合计恰好等于预算）；
     * 3. 预算 ≤ 0 时六段全 0（不抛异常：坏配置只该让上下文变空，不该让聊天崩）。
     */
    fun planForBudget(historyCharBudget: Int): ContextPlan {
        val safeBudget = historyCharBudget.coerceAtLeast(0)
        val limits = LinkedHashMap<ContextSegment, Int>()
        var used = 0
        for (segment in ContextSegment.entries) {
            if (segment == ContextSegment.REMAINING_HISTORY) continue
            val ratio = SEGMENT_RATIOS_PER_MILLE[segment] ?: 0
            var limit = (safeBudget.toLong() * ratio / 1000L).toInt()
            val cap = SEGMENT_HARD_CAPS[segment]
            if (cap != null) limit = minOf(limit, cap)
            limits[segment] = limit
            used += limit
        }
        val remaining = (safeBudget - used).coerceAtLeast(0)
        val segments = ContextSegment.entries.map { segment ->
            val ratio = SEGMENT_RATIOS_PER_MILLE[segment] ?: 0
            val limit = if (segment == ContextSegment.REMAINING_HISTORY) {
                remaining
            } else {
                limits[segment] ?: 0
            }
            SegmentBudget(segment = segment, ratioPerMille = ratio, charLimit = limit)
        }
        return ContextPlan(historyCharBudget = safeBudget, segments = segments)
    }

    // ================================================================== 消息节奏

    /**
     * 首条消息的「正在输入」时长：均值 `clamp(0.3 + 字数 × 0.018, 0.3, 2.2)` 秒，
     * 再乘 `1 ± 35%` 的随机波动。
     *
     * 为什么改成「均值正比于长度 + 抖动」：旧公式 `0.4 + 字数×0.025` 是**命令式**的——
     * 同样的字数永远等同样久，用户聊久了会觉得像进度条。人的打字间隔本来就有一个分布：
     * 越长的句子平均等越久，但每一次都不完全一样。
     */
    const val TYPING_BASE_SECONDS: Double = 0.6

    /** 每字增加的秒数（0.018 秒/字：100 字约 2.1 秒，接近真人敲 100 个汉字）。 */
    const val TYPING_SECONDS_PER_CHAR: Double = 0.01

    /** 首条延迟的均值下界。 */
    const val TYPING_MIN_SECONDS: Double = 0.6

    /** 首条延迟的均值上界：再长的消息也不该让人等 3 秒以上。 */
    const val TYPING_MAX_SECONDS: Double = 0.9

    /**
     * 连发之间的间隔：同一条公式，但更短（均值 `clamp(0.2 + 字数 × 0.012, 0.2, 1.5)` 秒）。
     *
     * 语义不同：首条是「他看到了、在想」，连发是「他还在打字，下一句马上出来」。
     * 连发太长会像卡住，太短又不像人在敲。
     */
    const val BURST_GAP_BASE_SECONDS: Double = 0.2
    const val BURST_GAP_SECONDS_PER_CHAR: Double = 0.012
    const val BURST_GAP_MIN_SECONDS: Double = 0.2
    const val BURST_GAP_MAX_SECONDS: Double = 0.9

    /**
     * 随机波动的幅度：±35%。
     *
     * 只作用在均值上，**不再二次夹取**——再夹一次会把长句的分布压成单边（永远等于上界），
     * 「均值正比于长度」就不成立了。生产上界约 2.2×1.35 ≈ 2.97 秒，符合体感。
     */
    const val RHYTHM_JITTER_RATIO: Double = 0.35

    /** 心情修正的绝对值上限（秒）：低唤醒/累 → 正数更慢，高唤醒 → 负数更快。 */
    const val MAX_MOOD_ADJUSTMENT_SECONDS: Double = 0.4

    /** 首条延迟的均值（秒），不含随机项、不含心情修正。负数长度按 0 处理。 */
    fun typingBaseSeconds(length: Int): Double {
        val chars = length.coerceAtLeast(0)
        return (TYPING_BASE_SECONDS + chars * TYPING_SECONDS_PER_CHAR)
            .coerceIn(TYPING_MIN_SECONDS, TYPING_MAX_SECONDS)
    }

    /** 连发间隔的均值（秒），不含随机项、不含心情修正。负数长度按 0 处理。 */
    fun burstGapBaseSeconds(length: Int): Double {
        val chars = length.coerceAtLeast(0)
        return (BURST_GAP_BASE_SECONDS + chars * BURST_GAP_SECONDS_PER_CHAR)
            .coerceIn(BURST_GAP_MIN_SECONDS, BURST_GAP_MAX_SECONDS)
    }

    /**
     * 首条延迟（秒）：均值 + 心情修正，再乘 ±35% 随机波动。
     *
     * [moodAdjustment] 单位是秒，内部夹到 ±[MAX_MOOD_ADJUSTMENT_SECONDS]；NaN 按 0 处理。
     * [random] 返回 0..1（生产传 `Random.nextDouble()`；测试传固定值）。越界/NaN 按 0.5 处理，
     * 即不抖动——坏随机源不能让节奏跑飞。
     */
    fun typingDelaySeconds(
        length: Int,
        moodAdjustment: Double = 0.0,
        random: () -> Double = { 0.5 }
    ): Double = jittered(typingAdjustedSeconds(length, moodAdjustment), random)

    /** [typingDelaySeconds] 的毫秒版（UI/协程 `delay` 用）；四舍五入到整毫秒。 */
    fun typingDelayMillis(
        length: Int,
        moodAdjustment: Double = 0.0,
        random: () -> Double = { 0.5 }
    ): Long = secondsToMillis(typingDelaySeconds(length, moodAdjustment, random))

    /**
     * 连发间隔（秒）：同 [typingDelaySeconds] 的规则，更短的均值区间。
     */
    fun burstGapSeconds(
        length: Int,
        moodAdjustment: Double = 0.0,
        random: () -> Double = { 0.5 }
    ): Double = jittered(burstGapAdjustedSeconds(length, moodAdjustment), random)

    /** [burstGapSeconds] 的毫秒版。 */
    fun burstGapMillis(
        length: Int,
        moodAdjustment: Double = 0.0,
        random: () -> Double = { 0.5 }
    ): Long = secondsToMillis(burstGapSeconds(length, moodAdjustment, random))

    /** 心情修正后的首条均值（秒）：夹在 [TYPING_MIN_SECONDS]..[TYPING_MAX_SECONDS]。 */
    private fun typingAdjustedSeconds(length: Int, moodAdjustment: Double): Double =
        (typingBaseSeconds(length) + safeMoodAdjustment(moodAdjustment))
            .coerceIn(TYPING_MIN_SECONDS, TYPING_MAX_SECONDS)

    /** 心情修正后的连发均值（秒）。 */
    private fun burstGapAdjustedSeconds(length: Int, moodAdjustment: Double): Double =
        (burstGapBaseSeconds(length) + safeMoodAdjustment(moodAdjustment))
            .coerceIn(BURST_GAP_MIN_SECONDS, BURST_GAP_MAX_SECONDS)

    private fun safeMoodAdjustment(seconds: Double): Double = when {
        !seconds.isFinite() -> 0.0
        else -> seconds.coerceIn(-MAX_MOOD_ADJUSTMENT_SECONDS, MAX_MOOD_ADJUSTMENT_SECONDS)
    }

    /** `mean × (1 + (r×2 − 1) × 35%)`；随机源坏值按 0.5（= 均值）处理。 */
    private fun jittered(meanSeconds: Double, random: () -> Double): Double {
        val raw = random()
        val value = if (!raw.isFinite()) 0.5 else raw.coerceIn(0.0, 1.0)
        val factor = 1.0 + (value * 2.0 - 1.0) * RHYTHM_JITTER_RATIO
        return meanSeconds * factor
    }

    private fun secondsToMillis(seconds: Double): Long = round(seconds * 1000.0).toLong()

    // ================================================================== 工具轮数

    /**
     * 一次回复里默认最多连着用几轮工具（模型没指定时）。
     *
     * 5 轮覆盖「查一下 → 再确认一下 → 顺手记一笔 → 说话」这类多步任务；
     * 每多一轮就是一次完整 completion，默认值不能跟着上限一起放大。
     */
    const val DEFAULT_TOOL_ROUNDS: Int = 5

    /** 模型可指定的最小轮数。 */
    const val MIN_TOOL_ROUNDS: Int = 1

    /**
     * 工具回路的硬上限：15 轮。
     *
     * 旧实现两端都是 3；0.15 允许模型按任务复杂度要更多轮，但必须有硬顶——
     * 否则一个死循环能把一次回复变成几十次 16K 输出。
     */
    const val MAX_TOOL_ROUNDS: Int = 15

    /**
     * 夹取模型指定的工具轮数：`null` → [DEFAULT_TOOL_ROUNDS]；其余夹到 1..15。
     *
     * 为什么 0/负数不报错而是变成 1：模型输出脏值时，「至少做一轮」比「整轮失败」更像正常对话；
     * 真正的越界在日志里由调用方记录，规则层只负责给出可执行的值。
     */
    fun clampToolRounds(requested: Int?): Int =
        requested?.coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS) ?: DEFAULT_TOOL_ROUNDS
}
