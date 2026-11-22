package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「所有对外消息必须走 send_message」的协议（`spec/tool-call-agents/README.md` §1 / §3）。
 *
 * 这一组只测纯规则/纯文案：提示词里必须写清「不能直接输出正文」、工具结果要回灌 messageId、
 * 4 条与 120 字的数字只有一处真源、迁移提示只在真有旧消息时出现。
 * 真正的接线（谁调用谁、什么时候落库）在 `ToolCallAgentWiringContractTest` 里读源码钉住。
 */
class AgentReplyProtocolTest {

    @Test
    fun `一轮上限与单条字数与体验规范是同一份数字`() {
        assertEquals(4, AgentReplyProtocol.MAX_PER_ROUND)
        assertEquals(120, AgentReplyProtocol.MAX_CHARS)
        assertEquals(ExperienceRhythm.MAX_MESSAGES_PER_ROUND, AgentReplyProtocol.MAX_PER_ROUND)
        assertEquals(ExperienceRhythm.MAX_SEND_MESSAGE_CHARS, AgentReplyProtocol.MAX_CHARS)
        assertEquals(ToolCatalog.SEND_MESSAGE, AgentReplyProtocol.TOOL)
    }

    @Test
    fun `硬规则说清 不能直接输出正文 必须 send_message 沉默用 stay_silent`() {
        val rule = AgentReplyProtocol.rule(PromptLanguage.ZH)
        assertTrue(rule.contains("不能直接输出正文"))
        assertTrue(rule.contains("send_message(text)"))
        assertTrue(rule.contains("stay_silent"))
        assertTrue("上限要写在规则里：$rule", rule.contains("4 条"))
        assertTrue("单条字数要写在规则里：$rule", rule.contains("120 字"))
    }

    @Test
    fun `英文人格也拿到同一条规则`() {
        val rule = AgentReplyProtocol.rule(PromptLanguage.EN)
        assertTrue(rule.contains("cannot reply with plain text"))
        assertTrue(rule.contains(ToolCatalog.SEND_MESSAGE))
        assertTrue(rule.contains(ToolCatalog.STAY_SILENT))
        assertTrue(rule.contains("4"))
    }

    @Test
    fun `能力段把规则放在工具清单之前 并且不再教模型直接说`() {
        val section = ToolCatalog.capabilityPrompt(language = PromptLanguage.ZH)
        val ruleAt = section.indexOf(AgentReplyProtocol.RULE_ZH)
        val firstToolAt = section.indexOf("- ${ToolCatalog.THINK}(")
        assertTrue("能力段里必须有这条硬规则", ruleAt >= 0)
        assertTrue("规则要在工具清单之前（模型先知道没有正文通道）", firstToolAt >= 0 && ruleAt < firstToolAt)
        // 0.14 的描述是「日常只回一句时不要用它，直接说」——0.16 必须删掉这种鼓励。
        assertFalse("不能再教模型「直接说」", section.contains("日常只回一句时不要用它"))
        assertFalse("不能再让模型用正文补最后一句", section.contains("发完还可以继续用正文"))
        assertTrue(section.contains("不要直接输出正文"))
    }

    @Test
    fun `英文能力段也带同一条规则`() {
        val section = ToolCatalog.capabilityPrompt(language = PromptLanguage.EN)
        assertTrue(section.contains(AgentReplyProtocol.RULE_EN))
    }

    @Test
    fun `工具结果回灌里带已发送与 messageId`() {
        val text = AgentReplyProtocol.sentResult(index = 2, messageId = "msg-42")
        assertTrue(text.contains("已发送"))
        assertTrue("规范要求回灌 messageId", text.contains("messageId=msg-42"))
        assertTrue("要说清是第几条/共几条", text.contains("2/4"))
        assertTrue("不能提示用正文收尾", !text.contains("正文补"))
    }

    @Test
    fun `发满 4 条的回灌不再让模型改写成正文`() {
        val text = AgentReplyProtocol.tooManyResult()
        assertTrue(text.contains("4 条"))
        assertTrue("要引导留到下一轮", text.contains("下一轮"))
        assertFalse("不能让模型改用正文（那是回退路径）", text.contains("并进正文"))
    }

    @Test
    fun `没有 send_message 的正文才算兼容回退`() {
        assertTrue(AgentReplyProtocol.isLegacyFallback(messagesSent = 0))
        assertFalse(AgentReplyProtocol.isLegacyFallback(messagesSent = 1))
        assertFalse(AgentReplyProtocol.isLegacyFallback(messagesSent = 4))
    }

    @Test
    fun `迁移提示只在历史里真有旧消息时出现`() {
        assertNull("正常对话一个字都不多", AgentReplyProtocol.migrationNote(hasLegacyDirectReply = false))
        val zh = AgentReplyProtocol.migrationNote(hasLegacyDirectReply = true)
        assertTrue(zh != null)
        assertTrue("要说明历史里有些话是旧版本直接说的", zh!!.contains("旧版本直接说"))
        assertTrue("要说明不代表现在可以跳过工具", zh.contains("不代表你现在可以跳过工具"))
        assertTrue("要指向唯一通道", zh.contains(ToolCatalog.SEND_MESSAGE))

        val en = AgentReplyProtocol.migrationNote(hasLegacyDirectReply = true, language = PromptLanguage.EN)
        assertTrue(en != null)
        assertTrue(en!!.contains("older version"))
        assertTrue(en.contains(ToolCatalog.SEND_MESSAGE))
        assertNull(AgentReplyProtocol.migrationNote(false, PromptLanguage.EN))
    }

    @Test
    fun `旧消息不补工具痕迹 迁移提示只是提醒`() {
        // 迁移段落本身不能声称给旧消息补过 toolTrace / 改写过历史。
        val note = AgentReplyProtocol.migrationNote(hasLegacyDirectReply = true).orEmpty()
        assertFalse(note.contains("toolTrace"))
        assertFalse(note.contains("补上"))
    }

    @Test
    fun `日志行标记回退通道`() {
        val line = AgentReplyProtocol.legacyLogLine(messagesSent = 0, chars = 12)
        assertTrue(line.contains("legacy"))
        assertTrue(line.contains(ToolCatalog.SEND_MESSAGE))
    }
}
