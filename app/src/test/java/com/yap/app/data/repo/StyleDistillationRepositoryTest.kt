package com.yap.app.data.repo

import com.yap.app.data.ai.JsonLlmClient
import com.yap.app.data.ai.SpeechStyleSource
import com.yap.app.data.ai.SpeechStyleSummarizer
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.domain.StyleDistillation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 「从最近聊天学习话风」的取样 + 总结（人格编辑页的那颗按钮）。
 *
 * 这里不打 Room：`distillMessages` 只吃「已经读到的消息」，所以可以用真
 * [SpeechStyleSummarizer] + 假 [JsonLlmClient] 覆盖三条核心路径：
 *
 * 1. **成功**：模型给了合法 JSON → `llm`，tone/phrases/habits/avoid 都解析出来；
 * 2. **本地兜底**：模型调用失败 → `heuristic`（界面会标「本地粗略总结」），不是「什么都没学到」；
 * 3. **没有消息**：不让用户白等一次请求（直接 `NoMessages`）。
 *
 * 另外钉住隐私与「只发一次请求」：样本只交给 summarizer 一次，`completeJson` 只被调一次。
 */
class StyleDistillationRepositoryTest {

    private class RecordingLlm(private val reply: String?) : JsonLlmClient {
        var calls = 0
            private set
        var lastUser: String? = null
            private set
        var lastSystem: String? = null
            private set

        override suspend fun completeJson(
            system: String,
            user: String,
            configId: String?,
            kind: String,
            temperature: Double,
            maxOutputTokens: Int
        ): String? {
            calls++
            lastSystem = system
            lastUser = user
            return reply
        }
    }

    /** DAO 这里用不到：`distillMessages` 不碰库；真被调用就立刻失败（防止有人把读库塞进核心路径）。 */
    private fun <T> unusedDao(type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            error("distillMessages 不该碰 DAO：${method.name}")
        } as T
    }

    private fun repository(llm: JsonLlmClient): StyleDistillationRepository =
        StyleDistillationRepository(
            messageDao = unusedDao(MessageDao::class.java),
            sessionDao = unusedDao(ChatSessionDao::class.java),
            summarizer = SpeechStyleSummarizer(llm)
        )

    private var seq = 0L

    private fun message(role: MessageRole, content: String) = MessageEntity(
        id = "m${seq++}",
        sessionId = "s1",
        personaId = "p1",
        role = role,
        content = content,
        createdAt = seq,
        updatedAt = seq,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        model = null,
        edited = false
    )

    /** Room 的 `recentBySession` 是「最近在前」；学习话风的样本要按时间正序读。 */
    private fun dbOrder(): List<MessageEntity> = conversation().asReversed()

    private fun conversation(): List<MessageEntity> = listOf(
        message(MessageRole.USER, "在吗"),
        message(MessageRole.ASSISTANT, "嗯，刚下班"),
        message(MessageRole.USER, "累不累"),
        message(MessageRole.ASSISTANT, "还行，就是有点困"),
        message(MessageRole.USER, "早点睡"),
        message(MessageRole.ASSISTANT, "知道了")
    )

    private val llmJson = """
        {"tone":"短句，偏冷淡","phrases":["知道了","还行"],"habits":["少用感叹号"],"avoid":["长篇大论"]}
    """.trimIndent()

    // ------------------------------------------------------------ 成功

    @Test
    fun `模型可用时得到 llm 来源的话风`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        val result = repository(llm).distillMessages(dbOrder())
        assertTrue(result is StyleDistillationRepository.Distillation.Ready)
        result as StyleDistillationRepository.Distillation.Ready
        assertEquals(SpeechStyleSource.LLM, result.source)
        assertEquals("短句，偏冷淡", result.style.tone)
        assertEquals(listOf("知道了", "还行"), result.style.phrases)
        assertEquals(listOf("少用感叹号"), result.style.habits)
        assertEquals(listOf("长篇大论"), result.style.avoid)
        assertEquals(6, result.messageCount)
    }

    @Test
    fun `样本按时间正序 用 用户与 他 两个前缀`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        repository(llm).distillMessages(dbOrder())
        val sample = llm.lastUser.orEmpty()
        assertEquals(
            "用户：在吗\n他：嗯，刚下班\n用户：累不累\n他：还行，就是有点困\n用户：早点睡\n他：知道了",
            sample
        )
        assertTrue("用的是话风总结的提示词", llm.lastSystem.orEmpty().contains("对话风格分析器"))
    }

    @Test
    fun `只发一次请求 样本不重复发`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        repository(llm).distillMessages(dbOrder())
        assertEquals("一次蒸馏只发一次请求", 1, llm.calls)
    }

    // ------------------------------------------------------------ 本地兜底

    @Test
    fun `模型不可用时走本地兜底 并标成 heuristic`() = runBlocking {
        val llm = RecordingLlm(reply = null)
        val result = repository(llm).distillMessages(dbOrder())
        assertTrue(result is StyleDistillationRepository.Distillation.Ready)
        result as StyleDistillationRepository.Distillation.Ready
        assertEquals(SpeechStyleSource.HEURISTIC, result.source)
        // 本地兜底也给出能用的东西：tone + 不该出现的表达（界面据此提示「可手动修改」）。
        assertTrue(result.style.tone.isNotBlank())
        assertTrue(result.style.avoid.isNotEmpty())
        assertEquals("兜底同样只发一次请求", 1, llm.calls)
    }

    @Test
    fun `模型返回坏 JSON 也退回本地兜底`() = runBlocking {
        val llm = RecordingLlm("这不是 JSON")
        val result = repository(llm).distillMessages(dbOrder())
        result as StyleDistillationRepository.Distillation.Ready
        assertEquals(SpeechStyleSource.HEURISTIC, result.source)
        // 坏 JSON 不能变成「什么都没有」：兜底至少有 tone。
        assertTrue(result.style.tone.isNotBlank())
    }

    // ------------------------------------------------------------ 没有消息

    @Test
    fun `没有消息时不发请求 直接说没得学`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        val empty = repository(llm).distillMessages(emptyList())
        assertEquals(StyleDistillationRepository.Distillation.NoMessages, empty)
        assertEquals("没消息就不该发请求", 0, llm.calls)
    }

    @Test
    fun `只有表情图片消息时同样不算有消息`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        val onlyMedia = listOf(
            message(MessageRole.USER, ""),
            message(MessageRole.ASSISTANT, ""),
            message(MessageRole.USER, "   ")
        )
        assertEquals(
            StyleDistillationRepository.Distillation.NoMessages,
            repository(llm).distillMessages(onlyMedia)
        )
        assertEquals(0, llm.calls)
    }

    @Test
    fun `聊得太少不打扰模型 直接提示先聊几句`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        val two = listOf(
            message(MessageRole.USER, "在吗"),
            message(MessageRole.ASSISTANT, "在")
        )
        assertEquals(
            StyleDistillationRepository.Distillation.NoMessages,
            repository(llm).distillMessages(two)
        )
        assertEquals(0, llm.calls)
    }

    @Test
    fun `系统消息不进样本`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        val withSystem = conversation() + message(MessageRole.SYSTEM, "（系统提示）")
        repository(llm).distillMessages(withSystem.asReversed())
        assertFalse("系统行不是他说的话", llm.lastUser.orEmpty().contains("系统提示"))
    }

    @Test
    fun `样本长度受上限约束`() = runBlocking {
        val llm = RecordingLlm(llmJson)
        val many = (1..120).map { message(if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, "第 $it 条") }
        repository(llm).distillMessages(many.asReversed())
        val sample = llm.lastUser.orEmpty()
        assertTrue(sample.length <= StyleDistillation.MAX_TOTAL_CHARS)
        assertTrue("最多 50 条", sample.lines().size <= StyleDistillation.MAX_MESSAGES)
    }

    // ------------------------------------------------------------ 小结
}
