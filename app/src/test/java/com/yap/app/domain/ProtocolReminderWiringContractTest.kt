package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具协议提醒的**接线契约**：规则与文案在 [ProtocolReminderTest] 里逐条测，
 * 这里只钉「聊天主循环真的接上了」——因为 `ChatRepository.generateAssistant` 依赖
 * Android/Room，JVM 单测跑不动（本仓库的 `*WiringContractTest` 都是这个路子）。
 *
 * 要证明的事：
 * 1. 违规（只有正文、没有工具）时代码会先请求一次提醒，而不是直接落 legacy；
 * 2. 提醒的形态正确：模型那段正文当 `assistant`、提醒是**不可见**的 `user`，不落库；
 * 3. 提醒之后的响应照常走工具执行（send_message / stay_silent / 别的工具）；
 * 4. 同一轮最多一次；第二次仍违规才回到原来的 legacy 回退；
 * 5. 危机 / 拉黑 / 沉默的既有分支没有被挪动或绕过。
 */
class ProtocolReminderWiringContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private val chat by lazy {
        File(appRoot(), "src/main/java/com/yap/app/data/repo/ChatRepository.kt").readText()
    }

    private fun indexOf(text: String): Int {
        val index = chat.indexOf(text)
        assertTrue("ChatRepository 里找不到：$text", index >= 0)
        return index
    }

    /** 从提醒分支的开头截一段，用来断言这一个块里做了什么、没做什么。 */
    private fun reminderBlock(): String {
        val start = indexOf("if (ProtocolReminder.shouldRemind(")
        val end = chat.indexOf("val silenceInCalls = calls.any(StaySilent::isSilence)", start)
        assertTrue("找不到提醒分支的结尾", end > start)
        return chat.substring(start, end)
    }

    // ------------------------------------------------------------ 接线

    @Test
    fun `违规时先走提醒分支 而不是直接 legacy`() {
        val reminder = indexOf("if (ProtocolReminder.shouldRemind(")
        val legacy = indexOf("val legacyFallback = AgentReplyProtocol.isLegacyFallback(sentMessages.size)")
        assertTrue("提醒分支在循环里", reminder < legacy)
        // 提醒分支在「轮数用尽就结束」之前：先给模型一次改正机会，再谈结束。
        assertTrue(reminder < indexOf("val silenceInCalls = calls.any(StaySilent::isSilence)"))
    }

    @Test
    fun `提醒把模型正文当 assistant 消息 提醒本身是 user 且不进库`() {
        val block = reminderBlock()
        assertTrue(
            "模型刚才的正文要作为 assistant 消息塞回对话",
            block.contains("""conversation += WireMessage("assistant", reminderBody)""")
        )
        assertTrue(
            "提醒是一条不可见的 user 消息",
            block.contains("""conversation += WireMessage("user", ProtocolReminder.text(replyLanguage))""")
        )
        // 不落库、不显示：这个块里没有任何写库动作，也没有把提醒推到界面。
        assertFalse("提醒不能落库", block.contains("saveAssistantMessage("))
        assertFalse("提醒不能写消息表", block.contains("messageDao."))
        assertFalse("提醒不能出现在界面上", block.contains("GenerationEvent.MessageSent"))
        assertTrue("只清空气泡里的临时文字", block.contains("emit(GenerationEvent.Delta(\"\"))"))
    }

    @Test
    fun `提醒之后重新请求 带工具继续跑`() {
        val block = reminderBlock()
        assertTrue("提醒后继续循环（下一次请求带 tools）", block.contains("continue"))
        // continue 在提醒之后：说明这一轮不会以 finalBody 结束，而是再请求一次。
        assertTrue(block.indexOf("ProtocolReminder.logLine") < block.lastIndexOf("continue"))
    }

    @Test
    fun `同一轮最多提醒一次 第二次仍违规才回退`() {
        assertTrue("要有一个「这一轮提醒过没有」的标记", chat.contains("var protocolReminded = false"))
        val block = reminderBlock()
        assertTrue("进入分支先立标记", block.contains("protocolReminded = true"))
        assertTrue("判定时把标记传进去", block.contains("alreadyReminded = protocolReminded"))
        // 标记在循环外初始化、循环内只置一次 true：第二轮 shouldRemind 直接返回 false。
        assertTrue(chat.split("protocolReminded = true").size == 2)
    }

    @Test
    fun `提醒只写本地日志 且带稳定标记`() {
        val block = reminderBlock()
        assertTrue(block.contains("ProtocolReminder.logLine("))
        assertTrue(block.contains("android.util.Log.i("))
        // 日志里不出现正文本身。
        assertFalse(block.contains("Log.i(\"ChatRepository\", reminderBody"))
    }

    // ------------------------------------------------------------ 既有边界不被改变

    @Test
    fun `危机轮不提醒 危机优先仍然最先决定`() {
        val block = reminderBlock()
        assertTrue(
            "危机判定要走既有 Deferred（判失败就当不是危机，不能让提醒把这一轮打崩）",
            block.contains("crisisJudgement.await().isCrisis")
        )
        assertTrue(block.contains("runCatching"))
        // 危机优先拦工具的分支仍在（提醒没有绕开它）。
        assertTrue(chat.contains("CrisisPriority.blocksTool(crisisJudgement.await().isCrisis, call.name)"))
    }

    @Test
    fun `拉黑与沉默的限制原样保留`() {
        val blocked = indexOf("SilentReason.BLOCKED")
        val reminder = indexOf("if (ProtocolReminder.shouldRemind(")
        assertTrue("拉黑在更早的位置直接返回", blocked < reminder)
        val block = reminderBlock()
        assertTrue("沉默的轮次不提醒", block.contains("silenced = silenced"))
        assertTrue("延后的轮次不提醒", block.contains("deferred = deferred"))
        assertTrue("发过消息的轮次不提醒", block.contains("messagesSent = sentMessages.size"))
    }

    @Test
    fun `legacy 回退与迁移提示保持原样`() {
        // 提醒只是「多一次机会」，回退条款本身一个字都不改。
        assertTrue(chat.contains("AgentReplyProtocol.isLegacyFallback(sentMessages.size)"))
        assertTrue(chat.contains("AgentReplyProtocol.legacyLogLine(sentMessages.size, legacyText.length)"))
        assertTrue(chat.contains("replyChannel = ToolTraceCodec.CHANNEL_LEGACY"))
        // 提醒用的语言跟人格语言走（不是界面语言）。
        assertTrue(chat.contains("val replyLanguage = PromptLanguage.resolve(persona.languageTag)"))
    }
}
