package com.yap.agentd

import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.tools.ServerToolCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「每一段非工具正文都要先确认发送」——`LlmReplyGenerator` 的确认循环（最多 2 轮）。
 *
 * 旧行为（0.15/0.16）：只提醒一次，仍然违规就 legacy 落库。新行为：
 * 1. 正文没有工具调用 → 作为 assistant 放回 + **不可见** user 提醒，带 tools 重试；
 * 2. 重试调用 `send_message` / `stay_silent` / 其它工具 → 正常路径；
 * 3. 仍然只写正文 → 再确认一次（第 2 轮）；
 * 4. 2 轮后仍没有工具 → **不落库、不 legacy**：按沉默结束（done.silent），日志 `protocol_dropped`，
 *    正文一个字都不发给用户（没有正文 `message.created`）。
 *
 * 钉住七件事：
 * 1. 第 1 次提醒后 `send_message` → 正常落库；
 * 2. 第 2 次提醒后 `send_message` → 正常落库（确实问了两次）；
 * 3. 两次之后仍然只有正文 → 不落库、不 legacy、silent done、日志 `protocol_dropped`；
 * 4. 重试可以改口 `stay_silent`（整轮沉默）；
 * 5. 正常 `send_message` 轮次一次都不提醒；
 * 6. 危机轮不确认、不 dropped（安全提示照旧）；
 * 7. 提醒本身不写库、不发 SSE；SSE 形状不变（`accepted → delta* → done`）。
 */
class ProtocolReminderTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-protocol-reminder")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun llmFor(server: FakeLlmServer): LlmAccess {
        store.pushLlmConfig(server.baseUrl)
        return LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
    }

    private fun chatService(llm: LlmAccess, toolRounds: Int? = null): ChatService =
        ChatService(store, LlmReplyGenerator(store, llm, toolRounds = toolRounds))

    private fun send(service: ChatService, clientMessageId: String): RecordingSink {
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(clientMessageId = clientMessageId, content = "在吗"),
            sink
        )
        return sink
    }

    private fun requestMessages(server: FakeLlmServer, index: Int): List<com.yap.app.core.json.JsonValue.Obj> =
        TestSupport.parse(server.requests[index].body).objects("messages")

    private fun assistants() = store.blockMessages(sessionId, 0)
        .filter { it.role == MessageRole.ASSISTANT }

    private fun sendMessage(id: String, text: String): String = FakeLlmServer.toolCall(
        0,
        id = id,
        name = ServerToolCatalog.SEND_MESSAGE,
        arguments = """{"text":"$text"}"""
    )

    // ------------------------------------------------------------------ 1. 第一次提醒后改口

    @Test
    fun `a direct body is asked once and the retry uses send_message`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(FakeLlmServer.chunk("我先直接说了。"), FakeLlmServer.DONE))
                1 -> exchange.sse(listOf(sendMessage("send-1", "我在。"), FakeLlmServer.DONE))
                else -> exchange.sse(listOf(FakeLlmServer.chunk("不该有第三次"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server), toolRounds = 2), "reminder-send")

            assertEquals("第一轮正文 + 确认后的重试（重试那轮带 tools）", 2, server.requests.size)
            assertTrue("重试必须带 tools", server.requests[1].body.contains("\"tools\""))
            val retry = requestMessages(server, 1)
            assertEquals("正文作为 assistant 放进重试上下文", "assistant", retry[retry.size - 2].str("role"))
            assertEquals("我先直接说了。", retry[retry.size - 2].str("content"))
            assertEquals("追加的是不可见 user 提醒", "user", retry.last().str("role"))
            assertEquals(LlmReplyGenerator.PROTOCOL_REMINDER, retry.last().str("content"))

            assertEquals("最终发出去的是 send_message 的正文", listOf("我在。"), assistants().map { it.content })
            assertFalse("不是沉默", sink.firstOf<SseEvent.Done>().silent)
            assertEquals(1, sink.events.count { it is SseEvent.MessageCreated })
            assertTrue(
                "提醒不能流给客户端",
                sink.events.none { event -> event is SseEvent.Delta && event.text.contains("你刚才输出的不是工具调用") }
            )
        }
    }

    // ------------------------------------------------------------------ 2. 第二次提醒后改口

    @Test
    fun `a second confirmation is asked before the retry uses send_message`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(FakeLlmServer.chunk("第一次说的。"), FakeLlmServer.DONE))
                1 -> exchange.sse(listOf(FakeLlmServer.chunk("第二次还是正文。"), FakeLlmServer.DONE))
                2 -> exchange.sse(listOf(sendMessage("send-2", "这次是确认过的。"), FakeLlmServer.DONE))
                else -> exchange.sse(listOf(FakeLlmServer.chunk("不该有第四次"), FakeLlmServer.DONE))
            }
        }.use { server ->
            // 3 轮刚好够：正文 → 提醒 → 正文 → 提醒 → send_message（确认轮不吃普通轮数）。
            val sink = send(chatService(llmFor(server), toolRounds = 3), "reminder-send-2")

            assertEquals("正文 → 提醒 → 正文 → 提醒 → send_message：总共 3 次请求", 3, server.requests.size)
            val second = requestMessages(server, 1)
            assertEquals("第一次的正文作为 assistant", "第一次说的。", second[second.size - 2].str("content"))
            assertEquals(LlmReplyGenerator.PROTOCOL_REMINDER, second.last().str("content"))
            val third = requestMessages(server, 2)
            assertEquals("第二次的正文也作为 assistant", "第二次还是正文。", third[third.size - 2].str("content"))
            assertEquals(LlmReplyGenerator.PROTOCOL_REMINDER, third.last().str("content"))

            assertEquals(listOf("这次是确认过的。"), assistants().map { it.content })
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
            assertEquals(1, sink.events.count { it is SseEvent.MessageCreated })
            assertEquals("提醒前两段正文都不落库", 1, assistants().size)
        }
    }

    // ------------------------------------------------------------------ 3. 两次之后仍然只有正文

    @Test
    fun `a body after two confirmations is dropped as silent and never persisted`() {
        val oldErr = System.err
        val captured = ByteArrayOutputStream()
        val calls = AtomicInteger()
        System.setErr(PrintStream(captured, true, StandardCharsets.UTF_8.name()))
        try {
            FakeLlmServer { exchange ->
                when (calls.getAndIncrement()) {
                    0 -> exchange.sse(listOf(FakeLlmServer.chunk("第一段正文。"), FakeLlmServer.DONE))
                    1 -> exchange.sse(listOf(FakeLlmServer.chunk("第二段正文。"), FakeLlmServer.DONE))
                    else -> exchange.sse(listOf(FakeLlmServer.chunk("第三段正文。"), FakeLlmServer.DONE))
                }
            }.use { server ->
                val sink = send(chatService(llmFor(server)), "reminder-drop")

                assertEquals("正文 → 提醒 → 正文 → 提醒 → 正文：确认两轮后放弃", 3, server.requests.size)
                val logs = captured.toString(StandardCharsets.UTF_8.name())
                assertTrue("日志里要有 protocol_reminder：$logs", logs.contains("protocol_reminder"))
                assertTrue("日志里要有 protocol_dropped：$logs", logs.contains("protocol_dropped"))
                assertFalse("不能自动 legacy：$logs", logs.contains("legacy 回退"))

                assertTrue("没有 send_message / stay_silent → 一条 assistant 消息都不落库", assistants().isEmpty())
                assertTrue("SSE 不出现正文 message.created", sink.events.none { it is SseEvent.MessageCreated })
                val done = sink.firstOf<SseEvent.Done>()
                assertTrue("按沉默结束", done.silent)
                assertEquals("", done.assistantMessageId)
                assertEquals("会话已读", 0, store.session(sessionId)!!.unread)
                assertEquals(
                    "只有用户消息，没有 assistant",
                    listOf(MessageRole.USER),
                    store.blockMessages(sessionId, 0).map { it.role }
                )
                // 临时正文会被一个空 delta 清掉；客户端最终没有正文，也没有 message.created。
                assertEquals("最后给客户端的是一个清空 delta", "", sink.events.filterIsInstance<SseEvent.Delta>().last().text)
                assertFalse(
                    "提醒不能发给客户端",
                    sink.events.any { event -> event is SseEvent.Delta && event.text.contains("你刚才输出的不是工具调用") }
                )
            }
        } finally {
            System.setErr(oldErr)
        }
    }

    // ------------------------------------------------------------------ 4. 重试可以改口 stay_silent

    @Test
    fun `a retry can choose stay_silent and the turn stays silent`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(FakeLlmServer.chunk("我先说一句。"), FakeLlmServer.DONE))
                else -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "silent-1",
                            name = ServerToolCatalog.STAY_SILENT,
                            arguments = """{"reason":"不想说"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "reminder-silent")

            assertEquals("提醒后重试一次", 2, server.requests.size)
            assertTrue("沉默轮不落 assistant 消息", assistants().isEmpty())
            val done = sink.firstOf<SseEvent.Done>()
            assertTrue("done 必须标 silent", done.silent)
            assertEquals("", done.assistantMessageId)
            assertTrue("不产生 message.created", sink.events.none { it is SseEvent.MessageCreated })
            assertEquals("会话已读", 0, store.session(sessionId)!!.unread)
        }
    }

    @Test
    fun `a retry can use another tool first and still send the message normally`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(FakeLlmServer.chunk("我先记一下。"), FakeLlmServer.DONE))
                1 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(0, id = "d-1", name = "diary", arguments = """{"action":"list"}"""),
                        FakeLlmServer.DONE
                    )
                )
                else -> exchange.sse(listOf(sendMessage("send-3", "记好了，我在。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            // 3 轮：正文 → 提醒 → 别的工具 → send_message；最后一轮不再给 tools（模型仍可调已知工具）。
            val sink = send(chatService(llmFor(server), toolRounds = 3), "reminder-other-tool")

            assertEquals("提醒 → 调别的工具 → 再 send_message", 3, server.requests.size)
            assertEquals(listOf("记好了，我在。"), assistants().map { it.content })
            assertFalse("不能用沉默把「用了别的工具」的轮次吞掉", sink.firstOf<SseEvent.Done>().silent)
        }
    }

    // ------------------------------------------------------------------ 5. 正常轮次不提醒

    @Test
    fun `a normal send_message turn never gets a reminder`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(sendMessage("send-ok", "在的。"), FakeLlmServer.DONE))
                // send_message 之后还写正文：这段只当「正在输入」，不触发提醒。
                else -> exchange.sse(listOf(FakeLlmServer.chunk("（这句不落库）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "reminder-normal")

            assertEquals(2, server.requests.size)
            assertTrue(
                "正常工具轮不能出现提醒",
                server.requests.none { it.body.contains(LlmReplyGenerator.PROTOCOL_REMINDER) }
            )
            assertEquals(listOf("在的。"), assistants().map { it.content })
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    // ------------------------------------------------------------------ 6. 危机优先

    @Test
    fun `crisis turns are never asked to confirm and are never dropped`() {
        val oldErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, StandardCharsets.UTF_8.name()))
        try {
            FakeLlmServer { exchange ->
                exchange.sse(listOf(FakeLlmServer.chunk("别想太多，早点睡。"), FakeLlmServer.DONE))
            }.use { server ->
                val sink = RecordingSink()
                chatService(llmFor(server)).send(
                    sessionId,
                    SendMessageRequest(clientMessageId = "reminder-crisis", content = "我不想活了"),
                    sink
                )

                assertEquals("危机轮不确认（只问一次模型）", 1, server.requests.size)
                val assistant = assistants().last()
                assertTrue("安全兜底照旧：有正文用正文", assistant.content.startsWith("别想太多，早点睡。"))
                assertTrue("缺锚点要补上", assistant.content.contains("120"))
                assertFalse("危机轮不能 dropped", sink.firstOf<SseEvent.Done>().silent)
                assertTrue(
                    "危机轮不走确认/放弃日志",
                    captured.toString(StandardCharsets.UTF_8.name()).let {
                        !it.contains("protocol_reminder") && !it.contains("protocol_dropped")
                    }
                )
            }
        } finally {
            System.setErr(oldErr)
        }
    }

    // ------------------------------------------------------------------ 7. SSE / defer 形状

    @Test
    fun `the drop sse shape stays accepted delta done`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("直接说"), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "reminder-shape")

            assertEquals("三次正文轮 + 一次清空 delta", 3, server.requests.size)
            assertEquals(
                "SSE 事件种类不变（没有 message.created，done 是 silent）",
                listOf("accepted", "delta", "delta", "done"),
                sink.types()
            )
        }
    }

    @Test
    fun `a defer reply turn is not asked to confirm`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.chunk("等会儿再说。"),
                        FakeLlmServer.toolCall(
                            0,
                            id = "defer-1",
                            name = ServerToolCatalog.DEFER_REPLY,
                            arguments = """{"minutes":30,"reason":"等会儿"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                else -> exchange.sse(listOf(FakeLlmServer.chunk("好。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "reminder-defer")

            assertEquals("defer 已经明确晚点回：不再确认（只有工具轮 + 正文轮）", 2, server.requests.size)
            assertTrue(server.requests.none { it.body.contains(LlmReplyGenerator.PROTOCOL_REMINDER) })
            assertEquals(1, store.pendingIntents(personaId).size)
            assertEquals("defer 轮的正文照旧兼容回退", "等会儿再说。好。", assistants().single().content)
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }
}
