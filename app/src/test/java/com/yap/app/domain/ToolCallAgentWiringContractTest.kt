package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.16「Agent 行为完全工具调用化」的接线契约（`spec/tool-call-agents/README.md` §1 / §2 / §3）。
 *
 * 聊天回合的循环在 `ChatRepository.generateAssistant` 里（依赖 Android/Room，JVM 跑不动），
 * 所以这一组按仓库一贯做法**读源码**钉住「有没有接线」：send_message 落库、回灌 messageId、
 * 正文只做兼容回退、沉默保持已读、流式不再预览正文、think 升级思考强度、迁移提示注入。
 * 文案与纯规则在 `AgentReplyProtocolTest` / `ReasoningEffortTest` / `ToolTraceChannelTest` 里逐条测。
 */
class ToolCallAgentWiringContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    private val chat by lazy { source("src/main/java/com/yap/app/data/repo/ChatRepository.kt") }
    private val chatScreen by lazy { source("src/main/java/com/yap/app/ui/screens/chat/ChatScreen.kt") }
    private val toolProtocol by lazy { source("src/main/java/com/yap/app/domain/ToolProtocol.kt") }

    private fun indexOf(text: String): Int {
        val index = chat.indexOf(text)
        assertTrue("ChatRepository 里找不到：$text", index >= 0)
        return index
    }

    private fun slice(from: Int, length: Int): String =
        chat.substring(from, minOf(chat.length, from + length))

    // ------------------------------------------------------------ send_message 必走

    @Test
    fun `每个用户可见消息都由 send_message 落库 并带新通道标记`() {
        val sendIndex = indexOf("if (call.name.trim().equals(ToolCatalog.SEND_MESSAGE, ignoreCase = true)) {")
        val fingerprint = indexOf("val fingerprint = call.name.trim()")
        assertTrue("send_message 分支在指纹去重之前结束", fingerprint > sendIndex)
        val block = chat.substring(sendIndex, fingerprint)
        assertTrue("必须走 saveAssistantMessage", block.contains("saveAssistantMessage("))
        assertTrue("新路径标 tool 通道", block.contains("replyChannel = ToolTraceCodec.CHANNEL_TOOL"))
        assertTrue("落库后要通知界面（气泡在这时出现）", block.contains("emit(GenerationEvent.MessageSent(saved))"))
        assertTrue("字数上限仍由 ExperienceRhythm 夹取", block.contains("ExperienceRhythm.clampSendText("))
        assertTrue("4 条上限用协议常量", block.contains("AgentReplyProtocol.MAX_PER_ROUND"))
    }

    @Test
    fun `工具结果回灌已发送 messageId 模型可以继续调用`() {
        val sendIndex = indexOf("if (call.name.trim().equals(ToolCatalog.SEND_MESSAGE, ignoreCase = true)) {")
        val block = slice(sendIndex, 4_000)
        assertTrue("回灌文案来自协议（含 messageId）", block.contains("AgentReplyProtocol.sentResult("))
        assertTrue("要带上真正落库的 id", block.contains("messageId = saved.id"))
        assertTrue("发满 4 条时也不能引导改正文", block.contains("AgentReplyProtocol.tooManyResult()"))
    }

    @Test
    fun `流式 token 只做输入指示 不再把正文推到气泡`() {
        assertFalse(
            "流式期间不能再把模型正文当预览（否则气泡会先出现、send_message 后又被清掉）",
            chat.contains("emit(GenerationEvent.Delta(visible))")
        )
        // 清空气泡/输入指示仍然保留（连发之间与工具回灌后都用它）。
        assertTrue(chat.contains("emit(GenerationEvent.Delta(\"\"))"))
        // 协议块仍然不会露出来：正文只在回合结束时统一处理（legacy）或由工具落库。
        assertTrue(chat.contains("ToolProtocol.extract("))
    }

    // ------------------------------------------------------------ 兼容回退 / 沉默

    @Test
    fun `没有 send_message 的正文落一条 legacy 消息 并打标记与日志`() {
        val fallback = indexOf("val legacyFallback = AgentReplyProtocol.isLegacyFallback(sentMessages.size)")
        val attachmentOnly = indexOf("if (legacyText.isBlank() && pendingAttachments.isNotEmpty())")
        assertTrue("附件兜底排在回退之后", attachmentOnly > fallback)
        assertTrue("回退时打 legacy 通道", chat.contains("replyChannel = ToolTraceCodec.CHANNEL_LEGACY"))
        assertTrue("回退要写日志（它是回退，不是新路径成功）", chat.contains("AgentReplyProtocol.legacyLogLine("))
        // 旧的「按空行拆成 1–3 条气泡」实现必须消失：回退只落一条。
        assertFalse("正文不能再拆成多个气泡", chat.contains("chunks.forEachIndexed"))
        assertEquals(
            "回退路径只允许一次 saveAssistantMessage（一条 legacy 消息）",
            1,
            chat.substring(fallback, attachmentOnly).split("saveAssistantMessage(").size - 1
        )
        // 拆条函数仍然是唯一实现（老测试与回退排版都指向它）。
        assertTrue(chat.contains("ExperienceRhythm.splitPlainReply"))
        assertTrue("拆条发生在回退落库之前", chat.indexOf("ExperienceRhythm.splitPlainReply") < fallback)
    }

    @Test
    fun `只有沉默工具时保持已读 不落任何 assistant 消息`() {
        val silent = indexOf("// stay_silent：只把用户消息标成已读")
        val fallback = indexOf("val legacyFallback = AgentReplyProtocol.isLegacyFallback(sentMessages.size)")
        assertTrue("stay_silent 的收尾必须排在回退之前", silent < fallback)
        val block = slice(silent - 600, 1_000)
        assertTrue(block.contains("SilentReason.STAY_SILENT"))
        assertTrue(block.contains("markUserMessagesRead"))
        assertTrue(block.contains("return@flow"))
        // defer_reply 的收尾同样先于回退（它也是「这一轮不回」）。
        assertTrue(chat.indexOf("SilentReason.DEFERRED") < fallback)
    }

    @Test
    fun `既没有工具也没有正文时仍然是协议违规 不伪造一条消息`() {
        val empty = indexOf("throw ApiException(NetworkErrorKind.EmptyStream)")
        val fallback = indexOf("val legacyFallback = AgentReplyProtocol.isLegacyFallback(sentMessages.size)")
        assertTrue("空流判定在回退之前", empty < fallback)
        val block = slice(empty - 300, 500)
        assertTrue(block.contains("sentMessages.isEmpty()"))
        assertTrue(block.contains("toolAttachments.isEmpty()"))
    }

    @Test
    fun `迁移提示在旧消息存在时注入 旧消息不补痕迹`() {
        assertTrue(
            "按历史 assistant 消息的 toolTrace 判定",
            chat.contains("ToolTraceCodec.hasLegacyReply(")
        )
        assertTrue(
            "取的是历史里 assistant 消息的痕迹",
            chat.contains("visible.filter { it.role == MessageRole.ASSISTANT }.map { it.toolTraceJson }")
        )
        assertTrue(
            "迁移句来自协议（中英按人格语言）",
            chat.contains("AgentReplyProtocol.migrationNote(legacyContext, personaLanguage)")
        )
        assertTrue("要真的进提示词附加块", chat.contains("                    migrationNote,"))
        // 旧消息原样显示：上下文装配不写库、更不会给旧消息补 toolTrace。
        val contextStart = indexOf("private suspend fun buildContext(")
        val contextBody = slice(contextStart, 12_000)
        assertFalse("上下文装配不能改写历史消息", contextBody.contains("messageDao.update("))
        assertFalse("上下文装配不能给旧消息补通道", contextBody.contains("withChannel("))
    }

    // ------------------------------------------------------------ 思考强度

    @Test
    fun `主聊天默认 low 调用 think 后升到 high`() {
        assertTrue(
            "请求参数带思考强度",
            chat.contains("params = config.inferenceParams(reasoningEffort = reasoningEffort)")
        )
        assertTrue("默认值只在 ReasoningEffort 一处", chat.contains("var reasoningEffort = ReasoningEffort.DEFAULT"))
        val think = indexOf("equals(ToolCatalog.THINK, ignoreCase = true) && execution.result.ok")
        val upgrade = slice(think, 400)
        assertTrue("think 成功后才升级", upgrade.contains("ReasoningEffort.forRequest(thinkCalledThisTurn)"))
        assertTrue(upgrade.contains("reasoningEffort = "))
        // 升级发生在工具结果回灌之前（下一轮请求才用得上 high）。
        assertTrue(think < indexOf("results += execution.result"))
        // 辅助调用不带这个参数（只有 ChatRepository 显式传）。
        val json = source("src/main/java/com/yap/app/network/OpenAiAdapter.kt")
        assertTrue("请求体写入 reasoning_effort", json.contains("put(\"reasoning_effort\", it)"))
    }

    // ------------------------------------------------------------ 提示词 / UI

    @Test
    fun `能力段带硬规则 工具描述不再教模型直接说`() {
        assertTrue(toolProtocol.contains("appendLine(AgentReplyProtocol.rule(language))"))
        val send = ToolCatalog.byName(ToolCatalog.SEND_MESSAGE) ?: error("没有注册 send_message")
        assertTrue("send_message 描述要写「唯一通道」", send.description.contains("唯一通道"))
        assertTrue("要禁止直接输出正文", send.description.contains("不要直接输出正文"))
        assertFalse("旧描述里的「直接说」必须删掉", send.description.contains("直接说"))
    }

    @Test
    fun `界面渲染痕迹前过滤掉回复通道标记`() {
        assertTrue(
            "回复通道标记不能变成气泡下的一行字",
            chatScreen.contains("ToolTraceCodec.visible(ToolTraceCodec.decode(message.toolTraceJson))")
        )
    }
}
