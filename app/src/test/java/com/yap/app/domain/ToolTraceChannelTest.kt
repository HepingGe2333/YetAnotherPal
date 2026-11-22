package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `toolTrace` 的「回复通道」标记（`spec/tool-call-agents/README.md` §1 / §3）。
 *
 * 这个标记有两个用途：
 * 1. 让下一轮能判定「历史里有没有旧版本直接说的消息」（迁移提示）；
 * 2. 但**不能**出现在气泡下面——那行小字只该给用户看真实动作。
 *
 * 备份 v7 兼容：旧的 `toolTraceJson` 里没有 `channel` 字段，读出来必须是空串；
 * 写回时也不补（`encodeDefaults=false`，缺省不写）。
 */
class ToolTraceChannelTest {

    private val count: (Int) -> String = { "$it 次" }

    @Test
    fun `新路径与回退路径都写通道标记 但用户看不见`() {
        val oldTrace = ToolTraceCodec.encode(
            listOf(ToolTraceItem(name = "web_search", title = "联网搜索", detail = "3 条"))
        )
        val tool = ToolTraceCodec.decode(ToolTraceCodec.withChannel(oldTrace, ToolTraceCodec.CHANNEL_TOOL))
        val legacy = ToolTraceCodec.decode(ToolTraceCodec.withChannel(oldTrace, ToolTraceCodec.CHANNEL_LEGACY))

        assertEquals(2, tool.size)
        assertEquals("联网搜索", ToolTraceCodec.visible(tool).single().title)
        assertEquals("联网搜索", ToolTraceCodec.summarize(tool, count))
        assertEquals(ToolTraceCodec.CHANNEL_TOOL, tool.last().channel)
        assertEquals(ToolTraceCodec.CHANNEL_LEGACY, legacy.last().channel)
    }

    @Test
    fun `只有标记时摘要为空 不会冒出 send_message 这种字`() {
        val raw = ToolTraceCodec.withChannel(null, ToolTraceCodec.CHANNEL_TOOL)
        val items = ToolTraceCodec.decode(raw)
        assertEquals(1, items.size)
        assertTrue("标记本身不是用户可见动作", ToolTraceCodec.visible(items).isEmpty())
        assertEquals("", ToolTraceCodec.summarize(items, count))
    }

    @Test
    fun `同一个通道不会重复追加标记`() {
        val raw = ToolTraceCodec.withChannel(null, ToolTraceCodec.CHANNEL_TOOL)
        val again = ToolTraceCodec.withChannel(raw, ToolTraceCodec.CHANNEL_TOOL)
        assertEquals(1, ToolTraceCodec.decode(again).count { it.channel == ToolTraceCodec.CHANNEL_TOOL })
    }

    @Test
    fun `旧消息没有 toolTrace 算旧版本直接说的`() {
        assertTrue("0.16 之前的 assistant 消息没有这个字段", ToolTraceCodec.isLegacyReply(null))
        assertTrue(ToolTraceCodec.isLegacyReply(""))
        assertTrue(ToolTraceCodec.isLegacyReply("  "))
        // 旧版带工具痕迹的消息（只有真实动作、没有通道）同样算「不是 send_message 发的」。
        val oldToolsOnly = ToolTraceCodec.encode(listOf(ToolTraceItem(name = "web_search", title = "联网搜索")))
        assertTrue(ToolTraceCodec.isLegacyReply(oldToolsOnly))
        assertTrue(ToolTraceCodec.hasLegacyReply(listOf(null, oldToolsOnly)))
    }

    @Test
    fun `新消息与状态消息都不算旧话`() {
        val tool = ToolTraceCodec.withChannel(null, ToolTraceCodec.CHANNEL_TOOL)
        val legacy = ToolTraceCodec.withChannel(null, ToolTraceCodec.CHANNEL_LEGACY)
        val status = ToolTraceCodec.withChannel(null, ToolTraceCodec.CHANNEL_STATUS)
        assertFalse(ToolTraceCodec.isLegacyReply(tool))
        assertTrue(ToolTraceCodec.isLegacyReply(legacy))
        assertFalse("失败/中断不是「直接说的正文」", ToolTraceCodec.isLegacyReply(status))
        assertFalse(ToolTraceCodec.hasLegacyReply(listOf(tool, status)))
        assertTrue(ToolTraceCodec.hasLegacyReply(listOf(tool, status, legacy)))
    }

    @Test
    fun `旧备份里的 toolTrace 仍可读 缺省不写 channel`() {
        // 0.13 时代写下的 JSON：没有 channel 字段。
        val oldJson = """[{"name":"think","title":"想了想","subtitle":null,"detail":"要不要先问清楚","ok":true}]"""
        val items = ToolTraceCodec.decode(oldJson)
        assertEquals(1, items.size)
        assertEquals("", items.single().channel)
        assertEquals("想了想", ToolTraceCodec.summarize(items, count))

        // 原样（不带通道）再编码时不出现 channel 键。
        val reencoded = ToolTraceCodec.encode(items).orEmpty()
        assertFalse("缺省字段不许写出来", reencoded.contains("channel"))
    }

    @Test
    fun `空通道原样返回 不会给普通调用多写一个标记`() {
        val trace = ToolTraceCodec.encode(listOf(ToolTraceItem(name = "diary", title = "备忘")))
        assertEquals(trace, ToolTraceCodec.withChannel(trace, ""))
        assertNull(ToolTraceCodec.withChannel(null, ""))
    }
}
