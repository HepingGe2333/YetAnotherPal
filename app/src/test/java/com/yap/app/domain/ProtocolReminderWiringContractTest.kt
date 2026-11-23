package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具协议「正文确认循环」的**接线契约**：规则与文案在 [ProtocolReminderTest] 里逐条测，
 * 这里只钉「聊天主循环真的接上了」——因为 `ChatRepository.generateAssistant` 依赖
 * Android/Room，JVM 单测跑不动（本仓库的 `*WiringContractTest` 都是这个路子）。
 *
 * 要证明的事：
 * 1. 违规（只有正文、没有工具）时代码先追问模型，而不是直接落 legacy；
 * 2. 追问的形态正确：模型那段正文当 `assistant`、提醒是**不可见**的 `user`，不落库；
 * 3. 追问之后照常带工具继续跑，最多两轮（计数器不是布尔）；
 * 4. 两轮之后仍然只有正文 → **丢弃**：不落库、不走 legacy、不显示正文，只发一个
 *    `Silent(PROTOCOL_DROPPED)`，日志 `protocol_dropped` 记次数与字数；
 * 5. 正常轮的正文不再自动 legacy 落库（legacy 只留给危机轮）；
 * 6. 危机 / 拉黑 / 沉默的既有分支没有被挪动或绕过，危机轮不会被丢弃。
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

    private fun slice(from: Int, length: Int): String =
        chat.substring(from, minOf(chat.length, from + length))

    /** 从提醒分支的开头截一段，用来断言这一个块里做了什么、没做什么。 */
    private fun reminderBlock(): String {
        val start = indexOf("if (ProtocolReminder.shouldRemind(")
        val end = chat.indexOf("val silenceInCalls = calls.any(StaySilent::isSilence)", start)
        assertTrue("找不到提醒分支的结尾", end > start)
        return chat.substring(start, end)
    }

    /** 从丢弃判定截一段：判定参数 + 那一行日志。 */
    private fun dropDecisionBlock(): String {
        val start = indexOf("val protocolDropped = ProtocolReminder.shouldDrop(")
        return slice(start, 700)
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
        assertTrue(
            "判定看的是「有没有工具调用」，正常工具轮不提醒",
            block.contains("hasToolCalls = calls.isNotEmpty()")
        )
    }

    @Test
    fun `同一轮最多问两次 计数器不是布尔`() {
        assertTrue("要有一个「这一轮问过几次」的计数器", chat.contains("var protocolAttempts = 0"))
        val block = reminderBlock()
        assertTrue("进入分支先自增", block.contains("protocolAttempts++"))
        assertTrue("判定时把已问次数传进去", block.contains("attempts = protocolAttempts"))
        assertFalse("旧的一次性布尔必须消失", block.contains("alreadyReminded"))
        // 计数器在循环外初始化、循环内只自增一处：第二轮仍然可以再问一次。
        assertTrue("自增只有一处", chat.split("protocolAttempts++").size == 2)
        // 上限来自纯规则（两轮），不在这里另写一个数字。
        assertTrue(chat.contains("ProtocolReminder.MAX_ATTEMPTS"))
    }

    @Test
    fun `提醒只写本地日志 且带稳定标记`() {
        val block = reminderBlock()
        assertTrue(block.contains("ProtocolReminder.logLine(protocolAttempts, reminderBody.length)"))
        assertTrue(block.contains("android.util.Log.i("))
        // 日志里不出现正文本身。
        assertFalse(block.contains("Log.i(\"ChatRepository\", reminderBody"))
    }

    // ------------------------------------------------------------ 两轮之后：丢弃

    @Test
    fun `两轮之后仍然只有正文就丢弃 不落库也不 legacy`() {
        val block = dropDecisionBlock()
        assertTrue(block.contains("body = finalText"))
        assertTrue(block.contains("messagesSent = sentMessages.size"))
        assertTrue("危机轮不参与丢弃（安全提示必须落库）", block.contains("crisis = crisisTurn"))
        assertTrue(
            "丢弃也要写日志：protocol_dropped + 问过几次 + 字数",
            block.contains("ProtocolReminder.dropLogLine(protocolAttempts, finalText.length)")
        )
        assertTrue(block.contains("android.util.Log.i("))

        val silent = indexOf("if (protocolDropped && lastSaved == null) {")
        val branch = chat.substring(silent, chat.indexOf("return@flow", silent))
        assertTrue(branch.contains("SilentReason.PROTOCOL_DROPPED"))
        assertTrue("只发一个 Silent 事件", branch.contains("emit(GenerationEvent.Silent("))
        assertFalse("丢弃不能落库", branch.contains("saveAssistantMessage("))
        assertFalse("丢弃不能写消息表", branch.contains("messageDao."))
        assertFalse("丢弃不能显示正文", branch.contains("GenerationEvent.MessageSent"))
        assertFalse("丢弃不能打 legacy 日志", branch.contains("legacyLogLine"))
        // 丢弃的正文不参与后面「并进最后一条」的合并（安全提示只在危机轮，不会冲突）。
        assertTrue(chat.contains("var replyText = if (protocolDropped) \"\" else finalText"))
        // 被丢弃的回合不是空流失败：有附件（表情/媒体）时只丢正文、附件照常落库。
        assertTrue(chat.contains("if (!protocolDropped &&"))
    }

    @Test
    fun `正常轮的正文不再自动 legacy 落库 legacy 只留给危机轮`() {
        // 回退分支多了一个 crisisTurn 门槛：非危机轮永远走不到「正文直接落库」。
        assertTrue(chat.contains("if (legacyText.isNotBlank() && legacyFallback && crisisTurn) {"))
        assertTrue("正常轮的正文在更早的地方被丢弃", chat.contains("ProtocolReminder.shouldDrop("))
        // 危机轮的回退路径原样保留（安全提示靠它落库）。
        assertTrue(chat.contains("replyChannel = ToolTraceCodec.CHANNEL_LEGACY"))
        assertTrue(chat.contains("AgentReplyProtocol.legacyLogLine(sentMessages.size, legacyText.length)"))
        // 已经用 send_message 发过时，剩余正文仍然并进最后一条（不造第 5 个气泡）。
        assertTrue(chat.contains("content = (previous.content + \"\\n\\n\" + legacyText.trim()).trim()"))
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
        // 危机轮唯一的正文兜底仍在。
        assertTrue(chat.contains("CrisisPriority.replyBody(crisisTurn, replyText, crisisNote)"))
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
        // 沉默 / 延后的收尾仍在丢弃之前（它们有各自的 Silent 原因）。
        assertTrue(chat.indexOf("SilentReason.STAY_SILENT") < indexOf("ProtocolReminder.shouldDrop("))
        assertTrue(chat.indexOf("SilentReason.DEFERRED") < indexOf("ProtocolReminder.shouldDrop("))
    }

    @Test
    fun `提醒与丢弃文案都跟人格语言走`() {
        val block = reminderBlock()
        assertTrue(block.contains("ProtocolReminder.text(replyLanguage)"))
        assertTrue(chat.contains("val replyLanguage = PromptLanguage.resolve(persona.languageTag)"))
    }
}
