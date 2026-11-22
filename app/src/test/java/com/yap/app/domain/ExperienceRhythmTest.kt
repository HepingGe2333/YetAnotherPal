package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceRhythmTest {

    @Test
    fun `首条延迟走共享契约的均值与随机波动`() {
        // 0.15：均值 = clamp(0.6 + 字数 × 0.01, 0.6, 0.9) 秒；random = 0.5 时不抖动。
        assertEquals(600L, ExperienceRhythm.typingDelayMillis(0))
        assertEquals(610L, ExperienceRhythm.typingDelayMillis(1))
        assertEquals(900L, ExperienceRhythm.typingDelayMillis(500))
        // 随机波动 ±35%：0.0 → 均值 ×0.65，1.0 → 均值 ×1.35。
        assertEquals(390L, ExperienceRhythm.typingDelayMillis(0, random = { 0f }))
        assertEquals(810L, ExperienceRhythm.typingDelayMillis(0, random = { 1f }))
        // 低唤醒/累比高唤醒慢；高唤醒明显更快。
        val low = ExperienceRhythm.typingDelayMillis(10, arousal = 0, lowEnergy = true)
        val high = ExperienceRhythm.typingDelayMillis(10, arousal = 5)
        assertTrue("低唤醒应更慢：$low vs $high", low > high)
    }

    @Test
    fun `连发间隔走共享契约且比首条短`() {
        listOf(0, 1, 20, 120, 500).forEach { chars ->
            val gap = ExperienceRhythm.sendGapMillis(chars, arousal = 3)
            assertTrue("chars=$chars gap=$gap", gap in 130L..2_025L)
        }
        // 同样的字数：连发间隔不能比首条延迟长（语义不同）。
        assertTrue(
            ExperienceRhythm.sendGapMillis(40, arousal = 3) <
                ExperienceRhythm.typingDelayMillis(40, arousal = 3)
        )
        assertTrue(
            ExperienceRhythm.sendGapMillis(1, arousal = 5) <
                ExperienceRhythm.sendGapMillis(1, arousal = 0, lowEnergy = true)
        )
    }

    @Test
    fun `随机源坏值不会让节奏跑飞`() {
        // NaN / 越界按 0.5（不抖动）处理，结果仍然落在物理范围内。
        val nan = ExperienceRhythm.typingDelayMillis(20, random = { Float.NaN })
        val huge = ExperienceRhythm.typingDelayMillis(20, random = { 99f })
        assertTrue(nan in ExperienceRhythm.TYPING_MIN_MS..ExperienceRhythm.TYPING_MAX_MS)
        assertTrue(huge in ExperienceRhythm.TYPING_MIN_MS..ExperienceRhythm.TYPING_MAX_MS)
        assertEquals(ExperienceRhythm.typingDelayMillis(20), nan)
    }

    @Test
    fun `不带工具的回复按空行拆 1 到 3 条且不丢内容`() {
        assertEquals(emptyList<String>(), ExperienceRhythm.splitPlainReply("   "))
        assertEquals(listOf("在"), ExperienceRhythm.splitPlainReply("在"))
        assertEquals(listOf("在", "忙吗"), ExperienceRhythm.splitPlainReply("在\n\n忙吗"))
        val four = ExperienceRhythm.splitPlainReply("一\n\n二\n\n三\n\n四")
        assertEquals(3, four.size)
        assertTrue(four.first().contains("一") && four.first().contains("二"))
        assertTrue(four.last().contains("四"))
    }

    @Test
    fun `send_message 文本去空白并截到 120 字`() {
        assertEquals("你好", ExperienceRhythm.clampSendText(" 你好 "))
        val long = "字".repeat(200)
        assertEquals(120, ExperienceRhythm.clampSendText(long).length)
        assertEquals("a\n\nb", ExperienceRhythm.clampSendText("a\n\n\n\nb"))
    }
}
