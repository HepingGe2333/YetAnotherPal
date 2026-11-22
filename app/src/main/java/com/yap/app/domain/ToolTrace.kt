package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 他这一轮「做过什么」的痕迹：查了什么、记住了什么、想了什么。
 *
 * 存进 messages.toolTraceJson，聊天里在气泡下方折成一行小字，
 * 点开能看到搜索词、命中的网页标题、失败原因——不是黑箱。
 */
@Serializable
data class ToolTraceItem(
    val name: String = "",
    /**
     * 显示用标题，**写入时**按当时的界面语言从 `R.string.tool_trace_*` 取
     *（`data/repo/ToolTraceText`）。老库里的值（中文原文）原样显示，不改写。
     */
    val title: String = "",
    val subtitle: String? = null,    // 「DeepSeek V4 发布」
    val detail: String = "",         // 展开后的正文（查询词 + 结果摘要，或错误信息）
    val ok: Boolean = true,
    /**
     * 0.16「回复通道」标记：非空表示这一条**不是用户可见的动作**，而是「这条消息是怎么发出去的」。
     *
     * 两个取值见 [ToolTraceCodec.CHANNEL_TOOL]（`send_message` 新路径）与
     * [ToolTraceCodec.CHANNEL_LEGACY]（正文直出的兼容回退）。它同时承担一个职责：
     * 让「历史里有没有旧版本直接说的消息」可判定（[ToolTraceCodec.isLegacyReply]）——
     * 旧消息没有这个字段（`encodeDefaults=false`，缺省不写），也不会被补造。
     *
     * 界面必须用 [ToolTraceCodec.visible] 过滤后再渲染，否则气泡下面会冒出一行
     * 「send_message」这种对用户毫无意义的字。
     */
    val channel: String = ""
)

object ToolTraceCodec {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 新路径：这一条消息是模型调用 `send_message` 发出去的。 */
    const val CHANNEL_TOOL = AgentReplyProtocol.CHANNEL_TOOL

    /** 兼容回退：这一条消息是模型直接输出的正文（旧版本 / 不守规矩的模型）。 */
    const val CHANNEL_LEGACY = AgentReplyProtocol.CHANNEL_LEGACY

    /** 状态消息（失败 / 中断）：既不是工具通道，也不是「直接说的正文」，迁移判定里不算旧话。 */
    const val CHANNEL_STATUS = "status"

    fun encode(items: List<ToolTraceItem>): String? = if (items.isEmpty()) {
        null
    } else {
        runCatching { json.encodeToString(ListSerializer(ToolTraceItem.serializer()), items) }.getOrNull()
    }

    fun decode(raw: String?): List<ToolTraceItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ToolTraceItem>>(raw) }.getOrDefault(emptyList())
    }

    /** 用户能看见的痕迹：去掉「回复通道」标记（界面只该显示真实动作）。 */
    fun visible(items: List<ToolTraceItem>): List<ToolTraceItem> =
        items.filter { it.channel.isBlank() }

    /**
     * 给一条助手消息的痕迹打上「回复通道」标记。
     *
     * - [channel] 为空时原样返回（不写多余字段）；
     * - 已有真实工具痕迹时保留它们，再追加（或复用）一条标记；
     * - 没有痕迹时只写标记——`toolTraceJson` 因此对**新消息**永远非空，
     *   「这条消息有没有工具痕」就成了「它是不是旧版本直接说的」的判据。
     */
    fun withChannel(raw: String?, channel: String): String? {
        if (channel.isBlank()) return raw
        val items = decode(raw)
        val merged = if (items.any { it.channel == channel }) {
            items
        } else {
            items + ToolTraceItem(name = channel, channel = channel, ok = true)
        }
        return encode(merged) ?: raw
    }

    /**
     * 这条 assistant 消息是不是「旧版本/回退路径直接说出来的正文」。
     *
     * 判据只有两条：完全没有 `toolTrace`（0.16 之前写下的消息），
     * 或者痕迹里带了 [CHANNEL_LEGACY] 标记。失败/中断消息带 [CHANNEL_STATUS]，不算。
     */
    fun isLegacyReply(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val items = decode(raw)
        if (items.any { it.channel == CHANNEL_LEGACY }) return true
        // 0.16 之前写下的痕迹里没有 channel 字段：那一轮也一定不是 send_message 发的
        // （新路径一定会带 CHANNEL_TOOL，状态消息带 CHANNEL_STATUS）。
        return items.none { it.channel == CHANNEL_TOOL || it.channel == CHANNEL_STATUS }
    }

    /**
     * 上下文里有没有「旧版本直接说的」assistant 消息（规范 §3 的迁移提示用）。
     *
     * @param rawTraces 按时间顺序取的历史消息的 `toolTraceJson`
     */
    fun hasLegacyReply(rawTraces: List<String?>): Boolean = rawTraces.any { isLegacyReply(it) }

    /**
     * 气泡下面那一行：多个工具压成一句，如「联网搜索 2 次 · 想了想」。
     *
     * 「次」是**界面文案**，各语言说法不同（英文「2 times」/「once」），所以由调用方
     * 传入 [countText]（Android 侧是 `R.plurals.chat_trace_count`）；标题本身已经是
     * 写入时的显示文案，这里只负责分组与拼接。
     *
     * 通道标记不参与摘要（见 [visible]）。
     */
    fun summarize(items: List<ToolTraceItem>, countText: (Int) -> String): String {
        val shown = visible(items)
        if (shown.isEmpty()) return ""
        val counts = shown.groupingBy { it.title.ifBlank { it.name } }.eachCount()
        return counts.entries.joinToString(" · ") { (title, count) ->
            if (count > 1) "$title ${countText(count)}" else title
        }
    }
}
