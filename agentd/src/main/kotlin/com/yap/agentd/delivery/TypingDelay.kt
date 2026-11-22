package com.yap.agentd.delivery

import com.yap.agentd.store.Mood
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 「打字延迟」的唯一实现（`spec/experience` §5 / §8）。
 *
 * ```
 * 均值 = clamp(0.6 + 字数 × 0.01 + 心情修正, 0.6, 0.9) 秒
 * 实际 = 均值 × (1 ± 35%)
 * ```
 *
 * 为什么把均值收在 **0.6–0.9 秒**：0.15 的评测（`eval/ux_explore.py` 的 baseline / warm_short）
 * 里，最像真人的回复在「首条出现前约 0.6–0.9 秒」这一档；再快像查询接口，再慢像在等客服。
 * 长句仍然按长度多等一点，但封顶 0.9 秒，不让 100 字的消息变成 4 秒的进度条。
 *
 * 随机波动 ±35%：每次的等待时长不完全一样（同样的字数不会永远等同样久）。随机源由调用方
 * 注入，生产传 [Random.nextDouble]，测试传固定值（0.0 = 下界、0.5 = 均值、1.0 = 上界）。
 *
 * 心情修正（§5「低唤醒/累 → 更慢，高唤醒 → 更快」）：
 * - 唤醒度 ≥ 4（兴奋/激动）：−0.4 秒；
 * - 唤醒度 ≤ 1（很困/很低落）：+0.5 秒；
 * - 其余不加不减；
 * - 不愉快（valence < 0）再 +0.2 秒：心情差的时候打字也会慢一点。
 * 修正后的均值仍夹在 [MEAN_MIN_SECONDS]..[MEAN_MAX_SECONDS]。
 *
 * 均值公式与 `:agentcore` 的 `ExperienceContract`（`0.6 + 字数 × 0.01`）一致；服务端权威模式
 * 下客户端只读 `visibleAt`、不再自己加延迟。本文件额外把均值封顶在 0.9 秒（需求里的
 * 「首条延迟均值 0.6–0.9 秒」），长消息不会变成 4 秒的进度条。
 *
 * 纯函数、不读时钟：随机源由调用方注入，测试可以精确钉住每一次等待。
 */
object TypingDelay {

    /** 均值下限/上限（秒）：只覆盖首条出现的等待，不含随机的上下浮动。 */
    const val MEAN_MIN_SECONDS = 0.6
    const val MEAN_MAX_SECONDS = 0.9

    /** 随机波动幅度：实际时长 = 均值 × (1 ± 35%)。 */
    const val JITTER_RATIO = 0.35

    /** 固定起手 + 每个字符的增量（在均值区间内按长度增加：0 字 0.6、15 字 0.75、30 字 0.9）。 */
    const val BASE_SECONDS = 0.6
    const val SECONDS_PER_CHAR = 0.01

    /** 乘上随机波动后能达到的最短/最长值（秒）；只用于文档与断言。 */
    const val MIN_SECONDS = MEAN_MIN_SECONDS * (1.0 - JITTER_RATIO)
    const val MAX_SECONDS = MEAN_MAX_SECONDS * (1.0 + JITTER_RATIO)

    /** 这条消息的**均值**（秒）：按长度增加，夹在 [MEAN_MIN_SECONDS]..[MEAN_MAX_SECONDS]。 */
    fun meanSeconds(text: String, mood: Mood? = null): Double {
        val chars = text.trim().length
        val raw = BASE_SECONDS + chars * SECONDS_PER_CHAR + moodAdjustment(mood)
        return raw.coerceIn(MEAN_MIN_SECONDS, MEAN_MAX_SECONDS)
    }

    /**
     * 延迟（秒）：均值 × `1 ± 35%`。
     *
     * [random] 返回 0..1（生产传 [Random.nextDouble]；测试传固定值）。越界/NaN 按 0.5 处理，
     * 即不抖动——坏随机源不能让聊天节奏跑飞。
     */
    fun seconds(
        text: String,
        mood: Mood?,
        random: () -> Double = { Random.nextDouble() }
    ): Double {
        val mean = meanSeconds(text, mood)
        val raw = random()
        val value = if (!raw.isFinite()) 0.5 else raw.coerceIn(0.0, 1.0)
        return mean * (1.0 + (value * 2.0 - 1.0) * JITTER_RATIO)
    }

    /** 延迟（毫秒）；与 [seconds] 同一口径，四舍五入到整毫秒。 */
    fun millis(
        text: String,
        mood: Mood?,
        random: () -> Double = { Random.nextDouble() }
    ): Long = (seconds(text, mood, random) * 1000.0).roundToLong()

    /** 心情修正（秒）。没有心情记录时按「平静」处理。 */
    fun moodAdjustment(mood: Mood?): Double {
        if (mood == null) return 0.0
        var adjust = when {
            mood.arousal >= 4 -> -0.4
            mood.arousal <= 1 -> 0.5
            else -> 0.0
        }
        if (mood.valence < 0) adjust += 0.2
        return adjust
    }
}

/**
 * 「等到某个时刻」的抽象。
 *
 * 生产是 [REAL]（`Thread.sleep`）；测试注入一个只记录目标并推进假时钟的实现，
 * 所以时序测试既不需要真的等 4 秒、也能断言「第几条在什么时刻发」。
 */
fun interface Sleeper {
    fun sleepUntil(epochMillis: Long)

    companion object {
        /** 真的睡到那个时刻（已经过了就立即返回）。 */
        val REAL: Sleeper = Sleeper { target ->
            val wait = target - System.currentTimeMillis()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        /** 什么都不等（测试/关闭延迟时用）。 */
        val NONE: Sleeper = Sleeper { }
    }
}
