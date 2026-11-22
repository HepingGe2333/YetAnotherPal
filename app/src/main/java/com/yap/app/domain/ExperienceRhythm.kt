package com.yap.app.domain

import kotlin.math.roundToLong

/**
 * 0.15 的「像人」节奏（`spec/experience/README.md` §4 / §5）。
 *
 * 0.15 起公式的**唯一真源**是共享契约 [ExperienceContract]：
 * 首条延迟的均值 `clamp(0.3 + 字数 × 0.018, 0.3, 2.2)` 秒，连发间隔
 * `clamp(0.2 + 字数 × 0.012, 0.2, 1.5)` 秒，两者都乘一个 `1 ± 35%` 的随机波动。
 * 这里只把「毫秒 / 心情修正 / 旧调用签名」适配过去，不再自己写第二份数字。
 *
 * 为什么不再用 0.14 的 `clamp(0.4 + 字数×0.025, 0.4, 4.0)`：那是**命令式**的——
 * 同样的字数永远等同样久，聊久了像进度条。人的打字间隔本来就有分布：
 * 越长的句子平均等越久，但每一次都不完全一样。0.15 的随机波动由契约负责，
 * 生产传随机源，测试传固定值。
 *
 * 真正的 `delay` 由仓库层执行；UI 不自己算，测试也不依赖真实时间。
 */
object ExperienceRhythm {

    /** 首条延迟能达到的最短值（均值下界 × (1 − 35%)）：0.3 × 0.65 = 195ms。 */
    val TYPING_MIN_MS: Long = secondsToMillis(
        ExperienceContract.TYPING_MIN_SECONDS * (1.0 - ExperienceContract.RHYTHM_JITTER_RATIO)
    )

    /** 首条延迟能达到的最长值（均值上界 × (1 + 35%)）：2.2 × 1.35 = 2970ms。 */
    val TYPING_MAX_MS: Long = secondsToMillis(
        ExperienceContract.TYPING_MAX_SECONDS * (1.0 + ExperienceContract.RHYTHM_JITTER_RATIO)
    )

    /** 均值公式的三个常量（转发契约，保留旧名字给调用方/测试）。 */
    const val TYPING_BASE_MS = 600L
    const val TYPING_PER_CHAR_MS = 10L
    const val TYPING_LENGTH_CAP = 120

    /** 连发间隔能达到的最短/最长值：130ms .. 2025ms。 */
    val GAP_MIN_MS: Long = secondsToMillis(
        ExperienceContract.BURST_GAP_MIN_SECONDS * (1.0 - ExperienceContract.RHYTHM_JITTER_RATIO)
    )
    val GAP_MAX_MS: Long = secondsToMillis(
        ExperienceContract.BURST_GAP_MAX_SECONDS * (1.0 + ExperienceContract.RHYTHM_JITTER_RATIO)
    )
    const val GAP_BASE_MS = 200L
    const val GAP_SPAN_MS = 1_300L

    /** 随机波动幅度（±35%），测试与日志用它解释分布。 */
    const val JITTER_RATIO = ExperienceContract.RHYTHM_JITTER_RATIO

    /** 单条 `send_message` 的字数上限（规范 §4）。 */
    const val MAX_SEND_MESSAGE_CHARS = 120

    /** 助手一轮最多发几条（规范 §4）。 */
    const val MAX_MESSAGES_PER_ROUND = 4

    /** 不带工具的回复最多拆成几条（规范 §4）。 */
    const val MAX_SPLIT_MESSAGES = 3

    /**
     * 首条消息的「正在输入」时长（毫秒）：均值正比于长度 + 心情修正 + ±35% 随机。
     *
     * @param charCount 已经到达的可见字数（负数按 0）
     * @param arousal 唤醒度 0..5；null 表示没读到心情（不做修正）
     * @param lowEnergy 「累/困」这类低能量标签：再慢一点
     * @param random 返回 0..1 的随机源；默认 0.5 = 不抖动（测试可精确钉住）
     */
    fun typingDelayMillis(
        charCount: Int,
        arousal: Int? = null,
        lowEnergy: Boolean = false,
        random: () -> Float = { 0.5f }
    ): Long = ExperienceContract.typingDelayMillis(
        length = charCount,
        moodAdjustment = moodAdjustmentSeconds(arousal, lowEnergy),
        random = { random().toDouble() }
    )

    /**
     * 连发之间的停顿（毫秒）：同一套「均值 + 随机」，均值区间更短。
     *
     * 语义不同：首条是「他看到了、在想」，连发是「他还在打字，下一句马上出来」。
     */
    fun sendGapMillis(
        charCount: Int,
        arousal: Int? = null,
        lowEnergy: Boolean = false,
        random: () -> Float = { 0.5f }
    ): Long = ExperienceContract.burstGapMillis(
        length = charCount,
        moodAdjustment = moodAdjustmentSeconds(arousal, lowEnergy),
        random = { random().toDouble() }
    )

    /**
     * 心情修正（秒）：低唤醒/累 → 正数（更慢），高唤醒 → 负数（更快）。
     *
     * 总幅度由契约夹在 ±0.4 秒内；这里只给方向与力度，不重复夹取。
     */
    internal fun moodAdjustmentSeconds(arousal: Int?, lowEnergy: Boolean): Double {
        var adjustment = if (arousal == null) 0.0 else (2.5 - arousal.coerceIn(0, 5)) * 0.08
        if (lowEnergy) adjustment += 0.15
        return adjustment.coerceIn(
            -ExperienceContract.MAX_MOOD_ADJUSTMENT_SECONDS,
            ExperienceContract.MAX_MOOD_ADJUSTMENT_SECONDS
        )
    }

    private fun secondsToMillis(seconds: Double): Long = (seconds * 1000.0).roundToLong()

    /**
     * 不带 `send_message` 的回复按空行拆成 1–3 条。
     *
     * 超过 3 段时把开头几段并进第 1 条（用空行保留原意），绝不丢内容；空文本返回空列表。
     * 单段内的换行保留，因为那可能是一首诗/一段引用，不该被当成「两条消息」。
     */
    fun splitPlainReply(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        if (parts.size <= MAX_SPLIT_MESSAGES) return parts
        // 段落太多时把开头几段并进第一条——他先说的话都在第一条里。
        val head = parts.take(parts.size - MAX_SPLIT_MESSAGES + 1).joinToString("\n\n")
        return listOf(head) + parts.takeLast(MAX_SPLIT_MESSAGES - 1)
    }

    /**
     * `send_message` 的正文规整：去首尾空白、把连续空行压成一个换行，并截到 120 字。
     *
     * 截断而不是报错：模型已经决定「发这一句」，因为超了 3 个字整条丢掉会让对话断掉；
     * 截断后回灌给模型一句「已按 120 字截断」，它下一次会自己写短。
     */
    fun clampSendText(raw: String): String {
        val text = raw.trim().replace(Regex("\\n{3,}"), "\n\n")
        if (text.length <= MAX_SEND_MESSAGE_CHARS) return text
        return text.take(MAX_SEND_MESSAGE_CHARS).trimEnd()
    }
}
