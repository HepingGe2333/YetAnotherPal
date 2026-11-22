package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具协议违规的「先提醒一次」（`spec/tool-call-agents/README.md` §1）。
 *
 * 这些用例对应需求里的四条验收：
 * 1. 违规 → 提醒后 `send_message` 成功（提醒过一次之后，下一次响应里只要有工具就不再提醒）；
 * 2. 提醒后仍然只有正文 → 走兼容回退（`shouldRemind` 返回 false，交给 legacy 路径）；
 * 3. 提醒后 `stay_silent` → 正常沉默；
 * 4. 正常轮（本来就在调用工具）→ 不提醒。
 *
 * 另外钉住边界：危机、沉默、延后、已经发过消息、正文为空都不提醒；同一轮最多一次。
 */
class ProtocolReminderTest {

    // ---------------------------------------------------------- 四种主场景

    @Test
    fun `违规轮要提醒一次`() {
        // 一条正文、没有任何工具调用、还没发过消息、没沉默：这是典型的协议违规。
        assertTrue(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "在的，怎么了"
            )
        )
    }

    @Test
    fun `提醒之后下一次响应调用了 send_message 就不再提醒`() {
        // 提醒后模型照做：这一轮已经有工具调用（send_message 属于工具调用），不再打扰它。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "",
                messagesSent = 1
            )
        )
    }

    @Test
    fun `提醒之后仍然只有正文就交给兼容回退`() {
        // 已经提醒过一次：这是第二次违规，不该无限重试——legacy 路径接手。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = false,
                body = "还是只有一句话",
                alreadyReminded = true
            )
        )
        assertTrue(ProtocolReminder.MAX_ATTEMPTS == 1)
    }

    @Test
    fun `提醒之后调用 stay_silent 就是正常沉默`() {
        // stay_silent 也是工具调用；同时 silenced 标记已经成立。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "",
                silenced = true
            )
        )
        // 即使正文与 stay_silent 同批出现，也不该再提醒（规范：工具 + 正文只沉默）。
        assertFalse(
            ProtocolReminder.shouldRemind(
                hasToolCalls = true,
                body = "算了不说了",
                silenced = true
            )
        )
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

    // ---------------------------------------------------------- 边界

    @Test
    fun `没有正文不算违规`() {
        assertFalse(ProtocolReminder.shouldRemind(hasToolCalls = false, body = ""))
        assertFalse(ProtocolReminder.shouldRemind(hasToolCalls = false, body = "   \n  "))
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
    fun `同一轮最多提醒一次`() {
        var reminded = false
        var reminders = 0
        repeat(3) {
            if (ProtocolReminder.shouldRemind(hasToolCalls = false, body = "正文", alreadyReminded = reminded)) {
                reminded = true
                reminders++
            }
        }
        assertTrue("只提醒一次", reminders == 1)
    }

    // ---------------------------------------------------------- 文案

    @Test
    fun `中文提醒逐字包含三件事 不评价回复内容`() {
        val text = ProtocolReminder.text()
        assertTrue(text.startsWith("（系统提醒）"))
        assertTrue("要发消息 → send_message", text.contains("send_message(text)"))
        assertTrue("不想发 → stay_silent", text.contains("stay_silent"))
        assertTrue("要做别的事 → 对应工具", text.contains("对应工具"))
        assertTrue("最后一句必须是「不要直接输出正文」", text.trimEnd().endsWith("不要直接输出正文。"))
        // 不夹带任何正文片段（提醒是操作性的，不复述他说的话）。
        assertFalse(text.contains("怎么了"))
        assertTrue(text.length < 160)
    }

    @Test
    fun `英文人格有英文提醒`() {
        val text = ProtocolReminder.text(PromptLanguage.EN)
        assertTrue(text.contains("send_message(text)"))
        assertTrue(text.contains("stay_silent"))
        assertTrue(text.contains("Do not output plain text."))
        assertFalse("英文提醒里不该出现中文字符", text.any { it.code in 0x4E00..0x9FFF })
    }

    @Test
    fun `日志只记次数与长度 不记正文`() {
        val line = ProtocolReminder.logLine(attempt = 1, chars = 12)
        assertTrue("日志里要有稳定标记：$line", line.contains(ProtocolReminder.LOG_MARKER))
        assertTrue(line.contains("attempt=1"))
        assertTrue(line.contains("chars=12"))
        assertEqualsCountOnce(line)
    }

    private fun assertEqualsCountOnce(line: String) {
        // 只出现一次，便于日志里 grep 到「这一轮提醒过一次」。
        assertTrue(line.split(ProtocolReminder.LOG_MARKER).size == 2)
    }
}
