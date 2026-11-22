package com.yap.app.domain

import com.yap.app.domain.ExperienceContract.ContextSegment

/**
 * 0.15 上下文装配 v2（`docs/design/context-memory-v2.md` + `spec/context-profile/README.md`）。
 *
 * **state_first** 的固定段顺序（越靠前越强）：
 *
 * ```
 * 1. 当前状态             （时间/活动/睡眠/心情）
 * 2. 引导                 （g12 有点防备 / g17 只聊此刻，按心情选）
 * 2.5 回复节奏            （这一轮发几条、每条多长；与引导同属「这一轮怎么说」）
 * 3. 人设                 （身份/世界/话风/关系）
 * 4. 工具                 （「你能做什么」）
 * 5. 反 AI                （core 五条）
 * 6. 备忘录               （长期 ≤500 字 / 短期 ≤200 字 / 主题 ≤12 段 × 120 字）
 * 7. 附加块               （心理活动/媒体/回忆/拉黑提示…）
 * 8. 语言                 （语言指令）
 * 9. 边界                 （最后一段）
 * 10. 剩余预算 → 最近聊天原文
 * ```
 *
 * 段正文全部由调用方（`ChatRepository` + `PromptBuilder`）准备好传进来，
 * 这里只负责**顺序、标题、预算与超限压缩**，所以顺序可以逐条断言。
 *
 * 不读库、不读时钟、不拼具体人设：所以顺序、标题、预算可以逐条断言。
 *
 * 预算来自共享契约 [ExperienceContract.practicalPlan]（28,000 字符产品口径，
 * 1 token ≈ 2 字符）；各段的字符上限也来自同一份规划，标题逐字复用
 * [MemoryContract.Injection] 与 [PromptContract.Section]，不在本文件另写一份中文。
 *
 * **超限时的压缩顺序**（设计文档「预算是硬约束」）：
 * 1. 先压主题总结：从最旧的一段开始丢，仍然不够就把每段再截短；
 * 2. 再丢附加块，最后减少历史（历史预算降到 0）；
 * 3. 仍然超出（人设本身超过预算）时不再丢人设/记忆——它们是「他是谁」，
 *    宁可这一轮少带历史，也不能让模型忘记自己是谁；超出量记录在 [Result.overflowChars] 里供诊断。
 */
object ContextAssembly {

    /** 备忘录三段（按设计文档的来源划分，不是按注入顺序）。 */
    data class Memo(
        val longTerm: String = "",
        val shortTerm: String = "",
        val topics: List<MemoryContract.TopicSegment> = emptyList()
    )

    /**
     * 装配输入：每个字段就是提示词里的一段（**可组合段**），装配顺序由本类固定。
     *
     * @param state 当前状态段正文（时间/活动/睡眠/心情；不含标题）
     * @param guidance 「# 引导」整段（含标题；g12 / g17 由 [PromptBuilder.guidanceBlock] 选）
     * @param rhythm 「# 回复节奏」整段（含标题；由 [PromptBuilder.rhythmBlock] 按人格语言给）。
     *        默认为空：老调用方（人格卡预览、测试）不传就没有这一段，行为与 0.15 完全一致。
     * @param persona 人设四段（身份/世界/话风/关系）
     * @param tools 「# 你能做什么」整段
     * @param antiAi 「# 别像 AI」整段（core 五条）
     * @param memo 长/短期缓存与主题总结
     * @param extras 备忘录之外的附加块（心理活动、当前媒体、拉黑提示、表情清单…）；
     *        它们放在主题总结之后、语言之前，同样吃预算
     * @param language 语言小节（`## 语言`）
     * @param boundaries 边界段（整份提示词的最后一段）
     */
    data class Inputs(
        val state: String = "",
        val guidance: String = "",
        val rhythm: String = "",
        val persona: String = "",
        val tools: String = "",
        val antiAi: String = "",
        val memo: Memo = Memo(),
        val extras: List<String> = emptyList(),
        val language: String = "",
        val boundaries: String = "",
        val contextWindowTokens: Int = ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
        val maxOutputTokens: Int = ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
        val practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS
    )

    /** 装配结论：渲染好的 system 正文 + 这一轮留给历史的字符预算。 */
    data class Result(
        val blocks: List<String>,
        val plan: ExperienceContract.ContextPlan,
        val historyCharLimit: Int,
        val topicSegments: List<MemoryContract.TopicSegment>,
        val droppedTopicSegments: Int,
        val compressedTopics: Boolean,
        val overflowChars: Int
    ) {
        /** 真正发给模型的 system 文本（空段已被丢掉）。 */
        val system: String
            get() = blocks.filter { it.isNotBlank() }.joinToString("\n\n").trim()
    }

    /** 六段的键（按注入顺序）；测试与日志按它对齐。 */
    val SEGMENT_ORDER: List<ContextSegment> = ContextSegment.entries.toList()

    /**
     * 装配一次。
     *
     * @param inputs 各段正文
     * @param topicMaxSegments 主题总结最多保留几段（默认 [MemoryContract.Summary.TOPIC_MAX_SEGMENTS]；
     *        测试与「只剩很少预算」的调用方可以传更小）
     */
    fun assemble(
        inputs: Inputs,
        topicMaxSegments: Int = MemoryContract.Summary.TOPIC_MAX_SEGMENTS
    ): Result {
        val plan = ExperienceContract.practicalPlan(
            contextWindowTokens = inputs.contextWindowTokens,
            maxOutputTokens = inputs.maxOutputTokens,
            practicalChars = inputs.practicalChars
        )
        val budget = plan.historyCharBudget.coerceAtLeast(0)

        val longCap = minOf(
            plan.charLimitFor(ContextSegment.LONG_TERM_MEMORY),
            MemoryContract.Summary.LONG_TERM_MAX_CHARS
        ).coerceAtLeast(0)
        val shortCap = minOf(
            plan.charLimitFor(ContextSegment.SHORT_TERM_MEMORY),
            MemoryContract.Summary.SHORT_TERM_MAX_CHARS
        ).coerceAtLeast(0)
        val topicCap = minOf(
            plan.charLimitFor(ContextSegment.TOPIC_SUMMARY),
            MemoryContract.Summary.TOPIC_MAX_CHARS
        ).coerceAtLeast(0)
        val stateCap = plan.charLimitFor(ContextSegment.CURRENT_STATE).coerceAtLeast(0)
        val extrasCap = plan.charLimitFor(ContextSegment.REMAINING_HISTORY)
            .coerceAtLeast(0)
            .coerceAtMost(MAX_EXTRAS_CHARS)

        // state_first 的固定块（顺序即注入顺序的前半段）：状态 → 引导 → 人设 → 工具 → 反 AI。
        // 这些段**从不被裁掉**：它们是「他是谁、此刻怎么样」，宁可少带历史也不能丢。
        // 状态与引导共用 CURRENT_STATE 的比例上限（与服务端 `ContextAssembler` 同一口径）：
        // 引导是这一轮的调子，比多一行状态描述更值钱，所以先扣引导再给状态。
        val guidance = inputs.guidance.trim()
        // 0.16 回复节奏：「这一轮怎么说」的第二段，和引导共用状态段的预算。
        // 顺序固定为 引导 → 回复节奏，所以先扣引导、再扣节奏，最后才是状态正文。
        val rhythmBlock = inputs.rhythm.trim()
        val guidanceBudget = if (rhythmBlock.isEmpty()) {
            stateCap
        } else {
            (stateCap - rhythmBlock.length - 2).coerceAtLeast(0)
        }
        val stateBudget = if (guidance.isEmpty()) {
            guidanceBudget
        } else {
            (guidanceBudget - guidance.length - 2).coerceAtLeast(0)
        }
        val stateText = clip(inputs.state, stateBudget)
        val persona = inputs.persona.trim()
        val tools = inputs.tools.trim()
        val antiAi = inputs.antiAi.trim()
        // 语言与边界固定收尾（边界必须最后一段）。
        val language = inputs.language.trim()
        val boundaries = inputs.boundaries.trim()
        val fixedBlocks = fixedBlocks(stateText, guidance, rhythmBlock, persona, tools, antiAi, language, boundaries)

        val longText = clip(inputs.memo.longTerm, longCap)
        val shortText = clip(inputs.memo.shortTerm, shortCap)
        val extras = clipExtras(inputs.extras, extrasCap)

        // 主题总结：先按段数/段长规范收一次，再按预算压。
        val sanitized = inputs.memo.topics
            .mapNotNull { topic ->
                val summary = flatten(topic.summary)
                if (summary.isEmpty()) return@mapNotNull null
                val clipped = if (summary.length <= MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS) {
                    summary
                } else {
                    summary.take(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS).trimEnd() + "…"
                }
                MemoryContract.TopicSegment(timeLabel = flatten(topic.timeLabel), summary = clipped)
            }
            .take(topicMaxSegments.coerceIn(0, MemoryContract.Summary.TOPIC_MAX_SEGMENTS))
        var topics = sanitized
        var dropped = 0
        var compressed = false
        var fixed = fixedLength(fixedBlocks, longText, shortText, topics, extras)

        // 1) 先压主题总结：从最旧的一段开始丢。
        while (fixed > budget && topics.isNotEmpty()) {
            topics = topics.drop(1)
            dropped++
            compressed = true
            fixed = fixedLength(fixedBlocks, longText, shortText, topics, extras)
        }
        // 1b) 还是超：把剩下的主题段按可用空间再截短（至少留 1 段，避免主题整段消失）。
        if (fixed > budget && topics.isNotEmpty()) {
            val topicBudget = (topicCap - (fixed - budget)).coerceAtLeast(MIN_TOPIC_CHARS)
            var remaining = topicBudget
            val shortened = ArrayList<MemoryContract.TopicSegment>()
            topics.forEach { topic ->
                if (remaining <= 0) {
                    dropped++
                    return@forEach
                }
                val take = minOf(topic.summary.length, remaining, MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
                if (take <= 0) return@forEach
                shortened += topic.copy(
                    summary = if (take >= topic.summary.length) topic.summary else topic.summary.take(take).trimEnd() + "…"
                )
                remaining -= take
            }
            topics = shortened
            compressed = true
            fixed = fixedLength(fixedBlocks, longText, shortText, topics, extras)
        }
        // 2) 再减历史：附加块让位（它们不是「他是谁」）。
        //    从最后一个附加块开始丢：越靠后越像「这一轮的附注」，最不影响身份。
        var finalExtras = extras
        while (fixed > budget && finalExtras.isNotEmpty()) {
            finalExtras = finalExtras.dropLast(1)
            fixed = fixedLength(fixedBlocks, longText, shortText, topics, finalExtras)
        }

        val historyCharLimit = (budget - fixed).coerceAtLeast(0)
        // state_first 注入顺序：状态 → 引导 → 人设 → 工具 → 反AI → 备忘录 → 附加块 → 语言 → 边界。
        // 顺序在这里只写一遍，测试按这块逐条断言。
        val blocks = buildList {
            if (stateText.isNotBlank()) {
                add(PromptContract.Section.STATE + "\n" + stateText)
            }
            if (guidance.isNotBlank()) add(guidance)
            if (rhythmBlock.isNotBlank()) add(rhythmBlock)
            if (persona.isNotBlank()) add(persona)
            if (tools.isNotBlank()) add(tools)
            if (antiAi.isNotBlank()) add(antiAi)
            val memoTitle = if (longText.isNotBlank() || shortText.isNotBlank() || topics.isNotEmpty()) {
                MemoryContract.Injection.TITLE
            } else {
                ""
            }
            if (longText.isNotBlank()) {
                add(
                    buildString {
                        if (memoTitle.isNotEmpty()) appendLine(memoTitle)
                        appendLine(MemoryContract.Injection.LONG_TERM_TITLE)
                        append("- $longText")
                    }.trimEnd()
                )
            }
            if (shortText.isNotBlank()) {
                add(
                    buildString {
                        // 长期存在时标题已在上一段；长期为空时短期承担标题。
                        if (longText.isBlank() && memoTitle.isNotEmpty()) appendLine(memoTitle)
                        appendLine(MemoryContract.Injection.SHORT_TERM_TITLE)
                        append("- $shortText")
                    }.trimEnd()
                )
            }
            if (topics.isNotEmpty()) {
                add(
                    buildString {
                        if (longText.isBlank() && shortText.isBlank() && memoTitle.isNotEmpty()) {
                            appendLine(memoTitle)
                        }
                        appendLine(MemoryContract.Injection.TOPIC_TITLE)
                        topics.forEach { topic ->
                            MemoryContract.Injection.topicLine(topic)?.let { appendLine(it) }
                        }
                    }.trimEnd()
                )
            }
            addAll(finalExtras.filter { it.isNotBlank() })
            if (language.isNotBlank()) add(language)
            if (boundaries.isNotBlank()) add(boundaries)
        }
        return Result(
            blocks = blocks,
            plan = plan,
            historyCharLimit = historyCharLimit,
            topicSegments = topics,
            droppedTopicSegments = dropped,
            compressedTopics = compressed,
            overflowChars = (fixed - budget).coerceAtLeast(0)
        )
    }

    /**
     * 从最近的消息往前，按字符预算挑一批原文（顺序保持不变）。
     *
     * 「至少留一条」是有意的：预算再紧也要把这一轮刚收到的那句话带给模型，
     * 否则他会对着空气回话。单条超预算时截到预算内（并补「…」）。
     */
    fun <T> trimHistory(
        history: List<T>,
        charLimit: Int,
        charsOf: (T) -> Int
    ): List<T> {
        if (history.isEmpty()) return history
        val limit = charLimit.coerceAtLeast(0)
        val picked = ArrayList<T>()
        var used = 0
        for (item in history.asReversed()) {
            val size = charsOf(item).coerceAtLeast(0)
            if (picked.isNotEmpty() && used + size > limit) break
            picked.add(item)
            used += size
        }
        return picked.asReversed()
    }

    private const val MAX_EXTRAS_CHARS = 4_000

    /** 主题段在极端压缩下的最小可读长度：再短就只剩一个词，不如整段丢掉。 */
    private const val MIN_TOPIC_CHARS = 60

    /** 固定块（状态/引导/人设/工具/反AI/语言/边界）：按注入顺序排好，空块已丢掉。 */
    private fun fixedBlocks(
        stateText: String,
        guidance: String,
        rhythm: String,
        persona: String,
        tools: String,
        antiAi: String,
        language: String,
        boundaries: String
    ): List<String> = buildList {
        if (stateText.isNotBlank()) add(PromptContract.Section.STATE + "\n" + stateText)
        if (guidance.isNotBlank()) add(guidance)
        if (rhythm.isNotBlank()) add(rhythm)
        if (persona.isNotBlank()) add(persona)
        if (tools.isNotBlank()) add(tools)
        if (antiAi.isNotBlank()) add(antiAi)
        if (language.isNotBlank()) add(language)
        if (boundaries.isNotBlank()) add(boundaries)
    }

    private fun fixedLength(
        fixedBlocks: List<String>,
        longText: String,
        shortText: String,
        topics: List<MemoryContract.TopicSegment>,
        extras: List<String>
    ): Int {
        var total = fixedBlocks.sumOf { it.length + 2 }
        if (longText.isNotBlank()) total += MemoryContract.Injection.LONG_TERM_TITLE.length + longText.length + 4
        if (shortText.isNotBlank()) total += MemoryContract.Injection.SHORT_TERM_TITLE.length + shortText.length + 4
        if (topics.isNotEmpty()) {
            total += MemoryContract.Injection.TOPIC_TITLE.length + 2
            topics.forEach { topic ->
                total += (MemoryContract.Injection.topicLine(topic)?.length ?: 0) + 1
            }
        }
        extras.forEach { total += it.length + 2 }
        if (longText.isNotBlank() || shortText.isNotBlank() || topics.isNotEmpty()) {
            total += MemoryContract.Injection.TITLE.length + 1
        }
        return total
    }

    private fun clipExtras(extras: List<String>, maxChars: Int): List<String> {
        if (maxChars <= 0) return emptyList()
        val result = ArrayList<String>()
        var used = 0
        for (raw in extras) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val left = maxChars - used
            if (left <= 0) break
            val clipped = if (text.length <= left) text else text.take(left).trimEnd()
            if (clipped.isNotBlank()) {
                result += clipped
                used += clipped.length + 2
            }
        }
        return result
    }

    private fun clip(raw: String?, maxChars: Int): String {
        val text = flatten(raw)
        if (text.isEmpty() || maxChars <= 0) return ""
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }

    private fun flatten(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim()
}
