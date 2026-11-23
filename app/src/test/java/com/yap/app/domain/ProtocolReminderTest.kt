package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具协议违规的「正文确认循环」（`spec/tool-call-agents/README.md` §1）。
 *
 * 这些用例对应需求里的六条验收：
 * 1. 第一次只有正文 → 提醒；提醒后 `send_message` 就正常发出去；
 * 2. 提醒后仍然只有正文 → 第二次提醒；第二次提醒后 `send_message` 仍然正常；
 * 3. 两轮提醒之后仍然只有正文 → 不再提醒，并且**必须丢弃**（不落库、不走 legacy）；
 * 4. 提醒后 `stay_silent` → 正常沉默；
 * 5. 正常工具轮（本来就在调用工具）→ 不提醒；
 * 6. 危机轮 → 不提醒，也**不能被丢弃**（安全提示必须落库）。
 *
 * 另外钉住边界：沉默、延后、已经发过消息、正文为空都不提醒；提醒/丢弃的日志只记次数与字数。
 */
class ProtocolReminderTest {

    // ---------------------------------------------------------- 确认循环（最多两轮）

    @Test
    fun `违规轮第一次要提醒`() {
        // 一条正文、没有任何工具调用、还没发过消息、没沉默：这是典型的协议违规。
        assertTrue(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "在的，怎么了",
                attempts = 0
            )
        )
    }

    @Test
    fun `第一次提醒后仍然只有正文 还要再问第二次`() {
        // 关键变化：0.15 只问一次就回落 legacy；0.16 要继续问，最多 MAX_ATTEMPTS 次。
        assertTrue(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "还是只有一句话",
                attempts = 1
            )
        )
        assertTrue("规范：最多两轮提醒", ProtocolReminder.MAX_ATTEMPTS == 2)
    }

    @Test
    fun `两轮提醒之后不再提醒`() {
        for (attempts in ProtocolReminder.MAX_ATTEMPTS..ProtocolReminder.MAX_ATTEMPTS + 3) {
            assertFalse(
                "问满以后不能再无限重试（attempts=$attempts）",
                ProtocolReminder.shouldRemind(
                    hasToolCalls = false,
                    body = "第三段正文",
                    attempts = attempts
                )
            )
        }
    }

    @Test
    fun `第一次正文 提醒后 send_message 就正常发出去`() {
        var attempts = 0
        // 第 1 次响应：只有正文 → 提醒。
        assertTrue(ProtocolReminder.shouldRemind(hasToolCalls = false, body = "在的", attempts = attempts))
        attempts++
        // 第 2 次响应：调用了 send_message → 不再提醒；已经有消息，不该被丢弃。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "",
                messagesSent = 1,
                attempts = attempts
            )
        )
        assertFalse(ProtocolReminder.shouldDrop(body = "", messagesSent = 1))
    }

    @Test
    fun `提醒后仍正文 第二次提醒后 send_message 也能正常发出`() {
        var attempts = 0
        // 第 1 次响应：正文 → 提醒 1。
        assertTrue(ProtocolReminder.shouldRemind(hasToolCalls = false, body = "第一段", attempts = attempts))
        attempts++
        // 第 2 次响应：还是正文 → 提醒 2。
        assertTrue(ProtocolReminder.shouldRemind(hasToolCalls = false, body = "第二段", attempts = attempts))
        attempts++
        // 第 3 次响应：这次照做，调用 send_message → 不再提醒，正常落库。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "",
                messagesSent = 1,
                attempts = attempts
            )
        )
        assertFalse(ProtocolReminder.shouldDrop(body = "", messagesSent = 1))
    }

    @Test
    fun `两轮之后仍然只有正文 这一轮必须丢弃`() {
        var attempts = 0
        // 模型连着三次都只给正文：前两次各问一次，第三次问不了了。
        repeat(3) {
            if (ProtocolReminder.shouldRemind(hasToolCalls = false, body = "只有正文", attempts = attempts)) {
                attempts++
            }
        }
        assertEquals("只问两轮", 2, attempts)
        // 第三次响应仍然只有正文、也没有任何消息 → 丢弃（不落库、不走 legacy）。
        assertTrue(ProtocolReminder.shouldDrop(body = "只有正文", messagesSent = 0, crisis = false))
    }

    @Test
    fun `提醒之后调用 stay_silent 就是正常沉默`() {
        // stay_silent 也是工具调用；同时 silenced 标记已经成立。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "",
                silenced = true,
                attempts = 1
            )
        )
        // 即使正文与 stay_silent 同批出现，也不该再提醒（规范：工具 + 正文只沉默）。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "算了不说了",
                silenced = true,
                attempts = 1
            )
        )
        // 沉默轮不落正文，也不该被当成「丢弃」（它有明确的收尾分支）。
        assertFalse(ProtocolReminder.shouldDrop(body = "", messagesSent = 0))
    }

    @Test
    fun `正常轮不提醒`() {
        // 正常轮：调了 think / remember 或 send_message，本来就在用工具。
        assertFalse(ProtocolReminder.shouldRemind(hasToolCalls = true, body = "（查一下）"))
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "",
                messagesSent = 2
            )
        )
    }

    // ---------------------------------------------------------- 丢弃（不落库 / 不 legacy）

    @Test
    fun `没有 send_message 确认的正文一律丢弃`() {
        // 这是 0.16 的产品底线：正文永远不能自动变成用户可见的消息。
        assertTrue(ProtocolReminder.shouldDrop(body = "直接说出来的话"))
        assertFalse(ProtocolReminder.shouldDrop(body = "   \n  "))
        assertFalse("空白正文不用丢（空流走既有失败分支）", ProtocolReminder.shouldDrop(body = ""))
        // 已经用 send_message 发过：剩下的只是半句话，并进最后一条（既有行为）。
        assertFalse(ProtocolReminder.shouldDrop(body = "对了还有一件事", messagesSent = 1))
        assertFalse(ProtocolReminder.shouldDrop(body = "对了还有一件事", messagesSent = 4))
    }

    @Test
    fun `危机轮不能被丢弃 安全提示必须落库`() {
        // 危机优先（0.15）：哪怕模型一个字都没通过 send_message 确认，这一轮的正文/安全提示
        // 也必须落库——丢掉它等于把安全网一起丢掉。
        assertFalse(ProtocolReminder.shouldDrop(body = "我在，你先别动", messagesSent = 0, crisis = true))
        assertFalse(ProtocolReminder.shouldDrop(body = "我在", messagesSent = 0, crisis = true))
    }

    @Test
    fun `危机轮不提醒 危机优先不拖慢`() {
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "我在，你先别动",
                crisis = true
            )
        )
        // 危机轮即使已经问过一轮，也不该再追问（判定优先级最高）。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "我在，你先别动",
                crisis = true,
                attempts = 1
            )
        )
    }

    @Test
    fun `已经沉默或延后的轮次不提醒`() {
        assertFalse(
            ProtocolReminder.shouldRemind(hasToolCalls = false, body = "嗯", silenced = true)
        )
        assertFalse(
            ProtocolReminder.shouldRemind(hasToolCalls = false, body = "晚点说", deferred = true)
        )
    }

    @Test
    fun `已经发过消息的轮次不提醒 剩下的正文并进最后一条`() {
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "对了还有一件事",
                messagesSent = 1
            )
        )
    }

    @Test
    fun `没有正文不算违规`() {
        assertFalse(ProtocolReminder.shouldRemind(hasToolCalls = false, body = ""))
        assertFalse(ProtocolReminder.shouldRemind(hasToolCalls = false, body = "   \n  "))
    }

    // ---------------------------------------------------------- 文案

    @Test
    fun `中文提醒逐字等于需求文案`() {
        // 需求给定的逐字文案：要发 → send_message；不想发 → stay_silent；只是思考 → 不要正文。
        assertEquals(
            "你刚才输出的不是工具调用。请确认：这段话要发给用户吗？要发→必须调用 send_message(text) " +
                "把它发出去；不想发→调用 stay_silent；只是思考→不要输出正文。",
            ProtocolReminder.TEXT_ZH
        )
        assertEquals(ProtocolReminder.TEXT_ZH, ProtocolReminder.text(PromptLanguage.ZH))
        // 不夹带任何正文片段（提醒是操作性的，不复述他说的话）。
        assertFalse(ProtocolReminder.TEXT_ZH.contains("怎么了"))
    }

    @Test
    fun `英文人格有英文提醒`() {
        val text = ProtocolReminder.text(PromptLanguage.EN)
        assertTrue(text.contains("send_message(text)"))
        assertTrue(text.contains("stay_silent"))
        assertTrue(text.contains("do not output plain text"))
        assertFalse("英文提醒里不该出现中文字符", text.any { it.code in 0x4E00..0x9FFF })
    }

    // ---------------------------------------------------------- 日志

    @Test
    fun `提醒日志只记次数与长度 不记正文`() {
        val line = ProtocolReminder.logLine(attempt = 1, chars = 12)
        assertTrue("日志里要有稳定标记：$line", line.contains(ProtocolReminder.LOG_MARKER))
        assertTrue(line.contains("attempt=1"))
        assertTrue(line.contains("chars=12"))
        assertEquals("标记只出现一次，便于 grep", 2, line.split(ProtocolReminder.LOG_MARKER).size)
    }

    @Test
    fun `丢弃日志带 protocol_dropped 次数与字数`() {
        val line = ProtocolReminder.dropLogLine(attempts = 2, chars = 12)
        assertTrue("日志里要有稳定标记：$line", line.contains(ProtocolReminder.DROP_LOG_MARKER))
        assertTrue(line.contains("attempts=2"))
        assertTrue(line.contains("chars=12"))
        assertEquals(2, line.split(ProtocolReminder.DROP_LOG_MARKER).size)
        // 提醒与丢弃是两个不同的标记，日志里能分开统计。
        assertFalse(line.contains(ProtocolReminder.LOG_MARKER))
    }
}
