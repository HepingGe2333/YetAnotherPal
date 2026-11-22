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
 * 0.15「工具协议违规自动提醒」。
 *
 * 现状（0.16）：模型整轮没有 `send_message` / `stay_silent`、却直接写了正文 → 直接 legacy 落库。
 * 新行为：先追加一条**不可见**的 `user` 提醒，带 tools 重试一次：
 * - 重试调用 `send_message` → 正常落库；
 * - 重试调用 `stay_silent` → 正常沉默；
 * - 重试仍然直接写正文 → legacy（正文用重试时**最新**那段，与 Android/iOS 一致）；
 * - defer 轮已经明确晚点回 → 不再提醒。
 *
 * 钉住六件事：
 * 1. 提醒请求里能看到 `assistant(正文)` + `user(提醒)`，重试请求带 tools；
 * 2. 提醒本身不写库、不发 SSE（客户端文本里没有「系统提醒」）；
 * 3. 仍违规时回退 legacy，并用最新那段正文；
 * 4. 正常调用 `send_message` 的轮次不会被提醒；
 * 5. SSE 事件种类不变：legacy 仍是 `accepted → delta* → done`，沉默仍是 silent done；
 * 6. 危机轮与 defer 轮不提醒。
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

    @Test
    fun `a direct body is reminded once and the retry uses send_message`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(FakeLlmServer.chunk("我先直接说了。"), FakeLlmServer.DONE))
                1 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "send-1",
                            name = ServerToolCatalog.SEND_MESSAGE,
                            arguments = """{"text":"我在。"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                else -> exchange.sse(listOf(FakeLlmServer.chunk("不该有第三次"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server), toolRounds = 2), "reminder-send")

            assertEquals("第一轮正文 + 提醒后的重试（重试那轮带 tools）", 2, server.requests.size)
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
                sink.events.none { event -> event is SseEvent.Delta && event.text.contains("系统提醒") }
            )
        }
    }

    @Test
    fun `a retry that still writes a body falls back to legacy with the latest text`() {
        val oldErr = System.err
        val captured = ByteArrayOutputStream()
        val calls = AtomicInteger()
        System.setErr(PrintStream(captured, true, StandardCharsets.UTF_8.name()))
        try {
            FakeLlmServer { exchange ->
                when (calls.getAndIncrement()) {
                    0 -> exchange.sse(listOf(FakeLlmServer.chunk("第一段\n\n第二段"), FakeLlmServer.DONE))
                    else -> exchange.sse(
                        listOf(FakeLlmServer.chunk("这次还是正文。\n\n补充一句。"), FakeLlmServer.DONE)
                    )
                }
            }.use { server ->
                val sink = send(chatService(llmFor(server)), "reminder-legacy")

                assertEquals("提醒后重试一次", 2, server.requests.size)
                assertEquals(
                    "重试请求里最后一条是提醒",
                    LlmReplyGenerator.PROTOCOL_REMINDER,
                    requestMessages(server, 1).last().str("content")
                )
                assertEquals(
                    "仍违规 → legacy，用最新那段正文",
                    listOf("这次还是正文。", "补充一句。"),
                    assistants().map { it.content }
                )
                assertTrue("客户端看到的是最新那段（提醒前的正文不会重复落库）", sink.text().contains("这次还是正文。"))
                assertTrue("legacy 仍发 message.created", sink.events.any { it is SseEvent.MessageCreated })
                assertFalse("不是沉默", sink.firstOf<SseEvent.Done>().silent)
                assertTrue(
                    "日志里要能查到 protocol_reminder",
                    captured.toString(StandardCharsets.UTF_8.name()).contains("protocol_reminder")
                )
            }
        } finally {
            System.setErr(oldErr)
        }
    }

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
    fun `a normal send_message turn never gets a reminder`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "send-ok",
                            name = ServerToolCatalog.SEND_MESSAGE,
                            arguments = """{"text":"在的。"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                // send_message 之后还写正文：这段只当「正在输入」，不触发提醒。
                else -> exchange.sse(listOf(FakeLlmServer.chunk("（这句不落库）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "reminder-normal")

            assertEquals(2, server.requests.size)
            assertTrue(
                "正常工具轮不能出现提醒",
                server.requests.none { it.body.contains("系统提醒") }
            )
            assertEquals(listOf("在的。"), assistants().map { it.content })
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    @Test
    fun `legacy sse shape stays accepted delta done even after a reminder`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            when (calls.getAndIncrement()) {
                0 -> exchange.sse(listOf(FakeLlmServer.chunk("直接说"), FakeLlmServer.DONE))
                else -> exchange.sse(listOf(FakeLlmServer.chunk("重试正文"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "reminder-shape")

            assertEquals(
                "SSE 事件种类不变（提醒时用一个空 delta 清掉第一遍临时文字）",
                listOf("accepted", "delta", "delta", "delta", "done"),
                sink.types()
            )
            assertEquals("落库的是重试时最新那段正文", "重试正文", assistants().single().content)
            assertTrue("客户端最后看到的是最新正文", sink.text().endsWith("重试正文"))
        }
    }

    @Test
    fun `a defer reply turn is not reminded`() {
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

            assertEquals("defer 已经明确晚点回：不再提醒（只有工具轮 + 正文轮）", 2, server.requests.size)
            assertTrue(server.requests.none { it.body.contains("系统提醒") })
            assertEquals(1, store.pendingIntents(personaId).size)
            assertEquals("等会儿再说。好。", assistants().single().content)
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    @Test
    fun `crisis turns keep their old behavior and never get a reminder`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("别想太多，早点睡。"), FakeLlmServer.DONE))
        }.use { server ->
            val sink = RecordingSink()
            chatService(llmFor(server)).send(
                sessionId,
                SendMessageRequest(clientMessageId = "reminder-crisis", content = "我不想活了"),
                sink
            )

            assertEquals("危机轮不提醒（只问一次模型）", 1, server.requests.size)
            assertTrue("安全兜底照旧", assistants().last().content.contains("120"))
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }
}
