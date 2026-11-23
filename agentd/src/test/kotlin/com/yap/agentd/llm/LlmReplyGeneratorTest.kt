package com.yap.agentd.llm

import com.yap.agentd.AgentdServices
import com.yap.agentd.AuthorityRoutes
import com.yap.agentd.SseEvent
import com.yap.agentd.SseSink
import com.yap.agentd.TestSupport
import com.yap.agentd.objects
import com.yap.agentd.str
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import com.yap.agentd.llm.FakeLlmServer.Companion.DONE
import com.yap.agentd.llm.FakeLlmServer.Companion.chunk
import com.yap.agentd.llm.FakeLlmServer.Companion.error
import com.yap.agentd.llm.FakeLlmServer.Companion.usage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * `LlmReplyGenerator` + `ChatService` 的端到端行为（假模型服务端）：
 *
 * - 真流式：`delta` 是模型分片原样转发的，落库正文等于所有 delta 拼起来；
 * - 真失败：没有配置/401/流内错误 → SSE `event: error` 带明确 code，**不退回 Echo**；
 * - 已生成的部分仍然落库（契约 §3.3「客户端断开/出错已生成部分不丢」的同一套）。
 */
class LlmReplyGeneratorTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private lateinit var server: FakeLlmServer

    private val sessionId: String get() = FileStore.SEED_SESSION_ID
    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID

    private class RecordingSink : SseSink {
        val events = mutableListOf<SseEvent>()

        override fun emit(event: SseEvent) {
            events += event
        }

        fun types(): List<String> = events.map { it.type }

        inline fun <reified T : SseEvent> firstOf(): T = events.filterIsInstance<T>().first()
    }

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-llm-reply-test")
        store = FileStore.open(root, { 1_000L })
        server = FakeLlmServer { exchange ->
            exchange.sse(listOf(chunk("在改图，"), chunk("第三版。"), chunk("晚点说。"), usage(30, 9), DONE))
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        TestSupport.deleteRecursively(root)
    }

    private fun access(withConfig: Boolean = true): LlmAccess {
        if (withConfig) store.pushLlmConfig(server.baseUrl)
        return llmAccess(store)
    }

    private fun send(access: LlmAccess, body: String, toolEnabled: Boolean = true): RecordingSink {
        val generator = LlmReplyGenerator(store, access, toolEnabled = toolEnabled)
        val services = AgentdServices(store, replyGenerator = generator, llm = access)
        val sink = RecordingSink()
        AuthorityRoutes.stream(services, "POST", "/v1/sessions/$sessionId/messages", body, sink)
        return sink
    }

    private fun messages() = store.blockMessages(sessionId, 0)

    @Test
    fun `real streaming deltas are forwarded and the assembled text is persisted`() {
        // 关掉工具 = 纯文字降级路径（老模型/不支持 tools 的网关）：没有 send_message 可调，
        // 正文仍走 legacy 兼容回退；这里验证流式分片被原样转发、拼起来的正文落库。
        // 开着工具时的确认循环见 `ProtocolReminderTest`。
        val sink = send(access(), """{"clientMessageId":"llm-1","content":"你在干嘛"}""", toolEnabled = false)

        assertEquals(
            listOf("accepted", "delta", "delta", "delta", "done"),
            sink.types()
        )
        assertEquals(
            listOf("在改图，", "第三版。", "晚点说。"),
            sink.events.filterIsInstance<SseEvent.Delta>().map { it.text }
        )

        val assistant = messages().last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals("在改图，第三版。晚点说。", assistant.content)
        assertEquals(MessageSource.CHAT, assistant.source)
        assertEquals(30, assistant.promptTokens)
        assertEquals(9, assistant.completionTokens)
        val done = sink.firstOf<SseEvent.Done>()
        assertEquals(assistant.id, done.assistantMessageId)
        assertEquals(9, done.usage.completionTokens)
    }

    @Test
    fun `no llm config emits llm_not_configured instead of pretending with echo`() {
        val sink = send(access(withConfig = false), """{"clientMessageId":"llm-2","content":"在吗"}""")

        assertEquals(listOf("accepted", "error"), sink.types())
        val error = sink.firstOf<SseEvent.Error>()
        assertEquals("llm_not_configured", error.code)
        assertTrue(error.message.contains("api_configs.json"))
        assertFalse("不能出现回显文案", sink.events.any { it is SseEvent.Delta })
        assertEquals("用户消息已落库，但没有 assistant 消息", 1, messages().size)
        assertEquals(MessageRole.USER, messages().single().role)
    }

    @Test
    fun `upstream 401 becomes llm_unauthorized with a clear message`() {
        server.close()
        server = FakeLlmServer { exchange -> exchange.json(401, """{"error":{"message":"bad key"}}""") }
        val sink = send(access(), """{"clientMessageId":"llm-3","content":"在吗"}""")

        val error = sink.firstOf<SseEvent.Error>()
        assertEquals("llm_unauthorized", error.code)
        assertTrue(error.message.contains("401"))
        assertEquals(1, messages().size)
    }

    @Test
    fun `stream error after partial text persists the partial reply and emits an error`() {
        server.close()
        server = FakeLlmServer { exchange ->
            exchange.sse(listOf(chunk("先说一半"), error("upstream boom")))
        }
        val sink = send(access(), """{"clientMessageId":"llm-4","content":"在吗"}""")

        assertEquals(listOf("accepted", "delta", "error"), sink.types())
        assertEquals("llm_upstream_error", sink.firstOf<SseEvent.Error>().code)
        val assistant = messages().last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals("先说一半", assistant.content)
    }

    @Test
    fun `the model receives the persona system prompt and the conversation history`() {
        store.appendMessage(sessionId, MessageRole.USER, "昨天说的那个方案")
        store.appendMessage(sessionId, MessageRole.ASSISTANT, "嗯，我记着。")
        send(access(), """{"clientMessageId":"llm-5","content":"今天继续吗"}""")

        // 0.15 起每条用户消息之后还有一次心情判定（服务端自动），所以这里找**聊天**那一条请求。
        val request = server.requests.first { it.body.contains("# 你是谁") }
        val body = TestSupport.parse(request.body)
        val messages = body.objects("messages")
        assertTrue(messages.first().str("role") == "system")
        assertTrue(messages.first().str("content").contains("# 你是谁"))
        assertTrue(messages.first().str("content").contains("林深"))
        assertEquals(
            listOf("user", "assistant", "user"),
            messages.drop(1).map { it.str("role") }
        )
        // 历史带时间戳前缀（profile 的 timestamped）：`[HH:mm] 用户：今天继续吗`；
        // 这个测试的存储时钟是 1970，跟真实的「现在」不同天，所以前缀是 `[MM-dd HH:mm]`。
        assertTrue(
            "历史最后一条是这一轮用户消息（带时间戳前缀）",
            messages.last().str("content").matches(Regex("""\[[^\]]+] 用户：今天继续吗"""))
        )
    }
}
