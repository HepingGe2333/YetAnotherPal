package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 体验契约：上下文预算、消息节奏、工具轮数。
 *
 * 这些数字会同时影响 Android、iOS 与 `agentd`，所以每条公式都钉边界：
 * 默认窗口、输出大于窗口、负数预算、随机源坏值、心情修正越界、轮数 0/负数/超大。
 */
class ExperienceContractTest {

    // ------------------------------------------------------------------ 上下文预算

    @Test
    fun `default history budget is one million minus output times two`() {
        assertEquals(1_967_232, ExperienceContract.historyCharBudget())
        assertEquals(1_000_000, ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS)
        assertEquals(16_384, ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS)
        assertEquals(2, ExperienceContract.CHARS_PER_TOKEN)
    }

    @Test
    fun `history budget clamps output and negative windows`() {
        // 输出比窗口还大：没有输入预算，不是负数。
        assertEquals(0, ExperienceContract.historyCharBudget(contextWindowTokens = 100, maxOutputTokens = 16_384))
        assertEquals(0, ExperienceContract.historyCharBudget(contextWindowTokens = 0, maxOutputTokens = 16_384))
        assertEquals(0, ExperienceContract.historyCharBudget(contextWindowTokens = -5, maxOutputTokens = 16_384))
        // 输出为复数按 0 处理：整个窗口都给输入。
        assertEquals(200, ExperienceContract.historyCharBudget(contextWindowTokens = 100, maxOutputTokens = -1))
        // 正常：窗口 − 输出。
        assertEquals(72_000, ExperienceContract.historyCharBudget(contextWindowTokens = 100_000, maxOutputTokens = 64_000))
    }

    @Test
    fun `plan allocates six segments in fixed order and balances exactly`() {
        val plan = ExperienceContract.plan()
        assertEquals(1_967_232, plan.historyCharBudget)
        assertEquals(
            listOf(
                ExperienceContract.ContextSegment.FULL_PERSONA_AND_TOOLS,
                ExperienceContract.ContextSegment.LONG_TERM_MEMORY,
                ExperienceContract.ContextSegment.SHORT_TERM_MEMORY,
                ExperienceContract.ContextSegment.CURRENT_STATE,
                ExperienceContract.ContextSegment.TOPIC_SUMMARY,
                ExperienceContract.ContextSegment.REMAINING_HISTORY
            ),
            plan.segments.map { it.segment }
        )
        // 比例算出来的前五段（后两段有设计硬顶）。
        assertEquals(354_101, plan.charLimitFor(ExperienceContract.ContextSegment.FULL_PERSONA_AND_TOOLS))
        assertEquals(500, plan.charLimitFor(ExperienceContract.ContextSegment.LONG_TERM_MEMORY))
        assertEquals(200, plan.charLimitFor(ExperienceContract.ContextSegment.SHORT_TERM_MEMORY))
        assertEquals(98_361, plan.charLimitFor(ExperienceContract.ContextSegment.CURRENT_STATE))
        assertEquals(1_440, plan.charLimitFor(ExperienceContract.ContextSegment.TOPIC_SUMMARY))
        // 剩余历史吃掉余额。
        assertEquals(1_512_630, plan.charLimitFor(ExperienceContract.ContextSegment.REMAINING_HISTORY))
        assertEquals(plan.historyCharBudget, plan.totalCharLimit)
        assertTrue(plan.isBalanced)
        assertTrue(plan.validate().toString(), plan.validate().isEmpty())
    }

    @Test
    fun `practical plan at 28k matches design caps`() {
        val plan = ExperienceContract.practicalPlan()
        assertEquals(28_000, plan.historyCharBudget)
        assertEquals(5_040, plan.charLimitFor(ExperienceContract.ContextSegment.FULL_PERSONA_AND_TOOLS))
        assertEquals(500, plan.charLimitFor(ExperienceContract.ContextSegment.LONG_TERM_MEMORY))
        assertEquals(200, plan.charLimitFor(ExperienceContract.ContextSegment.SHORT_TERM_MEMORY))
        assertEquals(1_400, plan.charLimitFor(ExperienceContract.ContextSegment.CURRENT_STATE))
        assertEquals(1_440, plan.charLimitFor(ExperienceContract.ContextSegment.TOPIC_SUMMARY))
        assertEquals(19_420, plan.charLimitFor(ExperienceContract.ContextSegment.REMAINING_HISTORY))
        assertEquals(28_000, plan.totalCharLimit)
    }

    @Test
    fun `practical plan takes the smaller of 28k and window budget`() {
        val tight = ExperienceContract.practicalPlan(contextWindowTokens = 20_000, maxOutputTokens = 16_384)
        assertEquals(7_232, tight.historyCharBudget)
        assertEquals(7_232, tight.totalCharLimit)
        // 窗口很小时，长期/短期/主题的硬顶不一定都吃满，但整段仍然守恒。
        assertTrue(tight.validate().toString(), tight.validate().isEmpty())
        assertEquals(
            ExperienceContract.ContextSegment.REMAINING_HISTORY,
            tight.segments.last().segment
        )
    }

    @Test
    fun `zero and negative budgets are all zero and still valid`() {
        for (budget in listOf(0, -1, -100_000)) {
            val plan = ExperienceContract.planForBudget(budget)
            assertEquals(0, plan.historyCharBudget)
            assertTrue(plan.segments.all { it.charLimit == 0 })
            assertEquals(0, plan.totalCharLimit)
            assertTrue(plan.isBalanced)
            assertTrue(plan.validate().toString(), plan.validate().isEmpty())
        }
    }

    @Test
    fun `odd budget remainder goes to remaining history`() {
        val plan = ExperienceContract.planForBudget(7)
        assertEquals(7, plan.totalCharLimit)
        assertEquals(6, plan.charLimitFor(ExperienceContract.ContextSegment.REMAINING_HISTORY))
        val ratios = ExperienceContract.SEGMENT_RATIOS_PER_MILLE.values.toList()
        assertEquals(1_000, ratios.sum())
    }

    @Test
    fun `plan validation reports broken plans`() {
        val broken = ExperienceContract.ContextPlan(
            historyCharBudget = 100,
            segments = listOf(
                ExperienceContract.SegmentBudget(
                    ExperienceContract.ContextSegment.FULL_PERSONA_AND_TOOLS,
                    ratioPerMille = 180,
                    charLimit = 100
                ),
                ExperienceContract.SegmentBudget(
                    ExperienceContract.ContextSegment.FULL_PERSONA_AND_TOOLS,
                    ratioPerMille = 180,
                    charLimit = -1
                )
            )
        )
        val problems = broken.validate()
        assertFalse(problems.isEmpty())
        assertTrue(problems.any { it.contains("重复") })
        assertTrue(problems.any { it.contains("为负") })
        assertTrue(problems.any { it.contains("缺少预算段") })
        assertFalse(broken.isBalanced)
    }

    // ------------------------------------------------------------------ 消息节奏

    @Test
    fun `typing base is clamped and grows with length`() {
        assertEquals(0.6, ExperienceContract.typingBaseSeconds(0), 1e-9)
        assertEquals(0.6, ExperienceContract.typingBaseSeconds(-5), 1e-9)
        assertEquals(0.9, ExperienceContract.typingBaseSeconds(40), 1e-9)
        assertEquals(0.9, ExperienceContract.typingBaseSeconds(100), 1e-9)
        assertEquals(0.9, ExperienceContract.typingBaseSeconds(200), 1e-9)
        assertEquals(0.9, ExperienceContract.typingBaseSeconds(10_000), 1e-9)
    }

    @Test
    fun `typing delay applies symmetric 35 percent jitter around mean`() {
        // 40 字：均值 0.9s；×0.65 = 0.585，×1.35 = 1.215。
        assertEquals(585L, ExperienceContract.typingDelayMillis(40, 0.0) { 0.0 })
        assertEquals(1_215L, ExperienceContract.typingDelayMillis(40, 0.0) { 1.0 })
        assertEquals(900L, ExperienceContract.typingDelayMillis(40, 0.0) { 0.5 })
        // 默认 random = 0.5：不抖动，等于均值。
        assertEquals(900L, ExperienceContract.typingDelayMillis(40))
    }

    @Test
    fun `typing delay mean equals base for representative lengths`() {
        val lengths = listOf(0, 10, 50, 105, 200)
        for (length in lengths) {
            val mean = ExperienceContract.typingBaseSeconds(length)
            val delay = ExperienceContract.typingDelayMillis(length, 0.0) { 0.5 }
            assertEquals("length=$length", (mean * 1000.0).toLong(), delay)
        }
        // 边界：0 字最快 0.6×0.65=390ms，最长 0.9×1.35=1215ms（不再二次夹取）。
        assertEquals(390L, ExperienceContract.typingDelayMillis(0, 0.0) { 0.0 })
        assertEquals(810L, ExperienceContract.typingDelayMillis(0, 0.0) { 1.0 })
        assertEquals(1_215L, ExperienceContract.typingDelayMillis(200, 0.0) { 1.0 })
    }

    @Test
    fun `typing delay survives bad random sources`() {
        val mean = ExperienceContract.typingDelayMillis(40)
        assertEquals(mean, ExperienceContract.typingDelayMillis(40, 0.0) { Double.NaN })
        assertEquals(mean, ExperienceContract.typingDelayMillis(40, 0.0) { Double.POSITIVE_INFINITY })
        // 越界随机值夹到 0/1，而不是外推。
        assertEquals(585L, ExperienceContract.typingDelayMillis(40, 0.0) { -2.0 })
        assertEquals(1_215L, ExperienceContract.typingDelayMillis(40, 0.0) { 2.0 })
    }

    @Test
    fun `mood adjustment is applied and clamped`() {
        // 40 字：均值封顶 0.9；+0.4 仍 0.9，−0.4 到 0.5→下界 0.6。
        assertEquals(900L, ExperienceContract.typingDelayMillis(40, 0.4) { 0.5 })
        assertEquals(600L, ExperienceContract.typingDelayMillis(40, -0.4) { 0.5 })
        // 越界修正夹到 ±0.4；NaN 当作 0。
        assertEquals(900L, ExperienceContract.typingDelayMillis(40, 99.0) { 0.5 })
        assertEquals(600L, ExperienceContract.typingDelayMillis(40, -99.0) { 0.5 })
        assertEquals(900L, ExperienceContract.typingDelayMillis(40, Double.NaN) { 0.5 })
    }

    @Test
    fun `typing delay never goes below the base floor`() {
        // 长消息：均值到 0.9 后再加心情修正也不越过上界。
        assertEquals(900L, ExperienceContract.typingDelayMillis(200, 0.4) { 0.5 })
        // 短消息：均值到 0.6 后再减心情修正也不越过下界。
        assertEquals(600L, ExperienceContract.typingDelayMillis(0, -0.4) { 0.5 })
    }

    @Test
    fun `burst gap is shorter than first typing delay`() {
        for (length in listOf(0, 20, 50, 120, 200)) {
            assertTrue(
                "length=$length",
                ExperienceContract.burstGapBaseSeconds(length) <
                    ExperienceContract.typingBaseSeconds(length) + 1e-9
            )
        }
        assertEquals(0.2, ExperienceContract.burstGapBaseSeconds(0), 1e-9)
        assertEquals(0.8, ExperienceContract.burstGapBaseSeconds(50), 1e-9)
        assertEquals(0.9, ExperienceContract.burstGapBaseSeconds(200), 1e-9)
    }

    @Test
    fun `burst gap applies the same jitter rule`() {
        // 50 字：均值 0.8s；×0.65 = 0.52，×1.35 = 1.08。
        assertEquals(520L, ExperienceContract.burstGapMillis(50, 0.0) { 0.0 })
        assertEquals(1_080L, ExperienceContract.burstGapMillis(50, 0.0) { 1.0 })
        assertEquals(800L, ExperienceContract.burstGapMillis(50, 0.0) { 0.5 })
        // 边界：0 字 0.2s；200 字封顶 1.5s。
        assertEquals(130L, ExperienceContract.burstGapMillis(0, 0.0) { 0.0 })
        assertEquals(270L, ExperienceContract.burstGapMillis(0, 0.0) { 1.0 })
        assertEquals(585L, ExperienceContract.burstGapMillis(200, 0.0) { 0.0 })
        assertEquals(1_215L, ExperienceContract.burstGapMillis(200, 0.0) { 1.0 })
    }

    @Test
    fun `burst gap clamps mood and bad random`() {
        assertEquals(800L, ExperienceContract.burstGapMillis(50, Double.NaN) { 0.5 })
        assertEquals(800L, ExperienceContract.burstGapMillis(50, 0.0) { Double.NaN })
        // 心情修正后夹在 0.2..0.9：0 字再快也停在 0.2s；200 字再慢也不超过 0.9s。
        assertEquals(200L, ExperienceContract.burstGapMillis(0, -0.4) { 0.5 })
        assertEquals(900L, ExperienceContract.burstGapMillis(200, 0.4) { 0.5 })
    }

    // ------------------------------------------------------------------ 工具轮数

    @Test
    fun `tool rounds default to five and clamp to one through fifteen`() {
        assertEquals(5, ExperienceContract.DEFAULT_TOOL_ROUNDS)
        assertEquals(15, ExperienceContract.MAX_TOOL_ROUNDS)
        assertEquals(5, ExperienceContract.clampToolRounds(null))
        val expected = linkedMapOf(
            0 to 1,
            -3 to 1,
            1 to 1,
            2 to 2,
            5 to 5,
            14 to 14,
            15 to 15,
            16 to 15,
            1_000 to 15
        )
        for ((input, output) in expected) {
            assertEquals("input=$input", output, ExperienceContract.clampToolRounds(input))
        }
    }

    @Test
    fun `tool round limits are ordered`() {
        assertTrue(ExperienceContract.MIN_TOOL_ROUNDS <= ExperienceContract.DEFAULT_TOOL_ROUNDS)
        assertTrue(ExperienceContract.DEFAULT_TOOL_ROUNDS < ExperienceContract.MAX_TOOL_ROUNDS)
    }
}
