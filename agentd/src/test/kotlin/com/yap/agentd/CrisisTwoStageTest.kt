package com.yap.agentd

import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.safety.CrisisClassifier
import com.yap.agentd.safety.SafetyGuard
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.15.2 危机两段式判定接进聊天链路（`ChatService` + `LlmReplyGenerator`）。
 *
 * 钉住七件事：
 * 1. 普通消息**不请求判定器**（词表不中，聊天只有一次模型请求）；
 * 2. 词表命中 + 模型 `risk=false` → 这一轮就是普通聊天：不补安全提示、不拦沉默工具；
 *    `stay_silent` 仍然只已读，`defer_reply` 仍然写念头；
 * 3. 词表命中 + 模型 `risk=true` → 危机兜底照旧：缺锚点就补、沉默工具不执行；
 * 4. 判定请求发生在生成**之前**，而且同一轮只有一次（生成器复用 `ReplyRequest.crisis`）；
 * 5. 判定失败（HTTP 500 / 超时）→ 安全优先，照样进危机兜底；
 * 6. 判定器只看最近一条用户消息：历史里出现过危机词不会让普通消息被当成危机；
 * 7. 没有注入判定器（老配置/测试）时仍然是 0.15.1 的纯词表行为。
 */
class CrisisTwoStageTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-crisis-two-stage")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    // ------------------------------------------------------------------ 装配

    private fun chatService(
        server: FakeLlmServer,
        timeoutMs: Long = 1_000,
        // 2 轮刚好：正文 + 确认后的 send_message（确认轮不吃普通轮数），之后不再多问一轮。
        toolRounds: Int? = 2
    ): ChatService {
        store.pushLlmConfig(server.baseUrl)
        val llm = llmAccess(store)
        return ChatService(
            store,
            LlmReplyGenerator(store, llm, toolRounds = toolRounds),
            crisisClassifier = CrisisClassifier(llm, timeoutMs = timeoutMs)
        )
    }

    /**
     * 假模型服务端：判定请求（body 里有判定器提示词的标记）回 [riskJson]；
     * 聊天请求第一轮回 [chatFrames]，之后每轮回「好。」（工具回路的第二轮）。
     */
    private fun fakeServer(
        riskJson: String = """{"risk":false,"reason":"测试"}""",
        chatFrames: List<String> = listOf(FakeLlmServer.chunk("我在，你身边有人吗？"), FakeLlmServer.DONE),
        /** 被协议确认提醒之后模型改口 `send_message` 的正文（必须与 [chatFrames] 一致，测试才能断言内容）。 */
        replyText: String = "我在，你身边有人吗？",
        classifierStatus: Int = 200,
        classifierDelayMs: Long = 0
    ): FakeLlmServer {
        val chatCalls = AtomicInteger()
        return FakeLlmServer { exchange ->
            if (exchange.request.body.contains(CLASSIFIER_MARKER)) {
                if (classifierDelayMs > 0) Thread.sleep(classifierDelayMs)
                if (classifierStatus != 200) {
                    exchange.json(classifierStatus, """{"error":{"message":"upstream boom"}}""")
                } else {
                    exchange.json(200, FakeLlmServer.completion(riskJson))
                }
            } else if (chatCalls.getAndIncrement() == 0) {
                exchange.sse(chatFrames)
            } else if (awaitsConfirmation(exchange.request.body)) {
                // 确认循环：被问「要不要发送」之后，模型改口用 send_message 说原本那句话。
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "reminder-send",
                            name = "send_message",
                            arguments = """{"text":"$replyText"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("好。"), FakeLlmServer.DONE))
            }
        }
    }

    /** 这次请求的最后一条是不是「请确认要不要发送」的不可见提醒。 */
    private fun awaitsConfirmation(body: String): Boolean {
        val last = TestSupport.parse(body).objects("messages").lastOrNull() ?: return false
        return last.str("role") == "user" && last.str("content") == LlmReplyGenerator.PROTOCOL_REMINDER
    }

    private fun send(
        service: ChatService,
        content: String,
        clientMessageId: String
    ): RecordingSink {
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(clientMessageId = clientMessageId, content = content),
            sink
        )
        return sink
    }

    private fun assistantMessages(): List<Message> =
        store.blockMessages(sessionId, 0).filter { it.role == MessageRole.ASSISTANT }

    private fun classifierRequests(server: FakeLlmServer) =
        server.requests.filter { it.body.contains(CLASSIFIER_MARKER) }

    private fun staySilentCall(id: String = "s1"): String =
        FakeLlmServer.toolCall(0, id = id, name = "stay_silent", arguments = """{"reason":"不想说"}""")

    private fun deferCall(id: String = "d1"): String =
        FakeLlmServer.toolCall(0, id = id, name = "defer_reply", arguments = """{"minutes":30,"reason":"等会儿"}""")

    // ------------------------------------------------------------------ 普通聊天不变

    @Test
    fun `an ordinary message never asks the classifier`() {
        fakeServer(riskJson = """{"risk":true,"reason":"不该被问到"}""").use { server ->
            val sink = send(chatService(server), "在吗，今天有点累", "ordinary-1")

            // 0.15：正文直出会先触发一次协议提醒重试；普通聊天仍然不请求判定器。
            assertEquals("聊天 + 一次确认提醒重试 = 2 次请求（没有判定请求）", 2, server.requests.size)
            assertEquals(0, classifierRequests(server).size)
            assertEquals("我在，你身边有人吗？", assistantMessages().single().content)
            assertEquals("我在，你身边有人吗？", assistantMessages().single().content)
            assertTrue("最新一遍的正文也要流给客户端", sink.text().endsWith("我在，你身边有人吗？"))
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    @Test
    fun `without a classifier the plain wordlist still guards the turn`() {
        // 老装配（没有判定器）：词表命中即危机，行为和 0.15.1 一样。
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("别想太多，早点睡。"))
            ReplyOutcome(Usage(1, 10), textLength = 7)
        }
        val sink = send(ChatService(store, generator), "我不想活了", "legacy-1")

        val assistant = assistantMessages().single()
        assertTrue("纯词表仍然会补安全提示", assistant.content.contains("120"))
        assertTrue(sink.text().contains("120"))
    }

    // ------------------------------------------------------------------ risk=false：普通聊天

    @Test
    fun `risk false leaves a wordlist-hit reply completely untouched`() {
        fakeServer(
            riskJson = """{"risk":false,"reason":"是在引用歌词，不是在说他自己"}""",
            chatFrames = listOf(FakeLlmServer.chunk("别想太多，早点睡。"), FakeLlmServer.DONE),
            replyText = "别想太多，早点睡。"
        ).use { server ->
            val sink = send(chatService(server), "我想自杀（歌词）", "false-1")

            assertEquals("模型说不是危机 → 回复一个字都不改", "别想太多，早点睡。", assistantMessages().single().content)
            assertFalse("不能追加安全提示", sink.text().contains("120"))
            assertEquals("确认后的 send_message 会发一条 message.created", 1, sink.events.count { it is SseEvent.MessageCreated })
            assertEquals(1, classifierRequests(server).size)
            // 判定 1 + 生成 1 + 确认提醒重试 1 = 3 次请求。
            assertEquals("判定 + 生成 + 确认提醒重试 = 3 次请求", 3, server.requests.size)
        }
    }

    @Test
    fun `risk false keeps an ordinary stay silent silent`() {
        fakeServer(
            riskJson = """{"risk":false,"reason":"只是夸张说法"}""",
            chatFrames = listOf(staySilentCall(), FakeLlmServer.DONE)
        ).use { server ->
            val sink = send(chatService(server), "我想自杀", "false-silent")

            assertEquals("判定器确实被问过", 1, classifierRequests(server).size)
            assertEquals("不是危机 → stay_silent 仍然是只已读", 0, assistantMessages().size)
            assertTrue(sink.firstOf<SseEvent.Done>().silent)
            assertTrue("普通 stay_silent 仍然显示工具卡片", sink.events.any { it is SseEvent.Tool })
        }
    }

    @Test
    fun `risk false keeps an ordinary defer reply`() {
        fakeServer(
            riskJson = """{"risk":false,"reason":"只是玩笑"}""",
            chatFrames = listOf(
                FakeLlmServer.chunk("等会儿再说。"),
                deferCall(),
                FakeLlmServer.DONE
            )
        ).use { server ->
            val sink = send(chatService(server), "我想自杀", "false-defer")

            assertEquals("判定器确实被问过", 1, classifierRequests(server).size)
            assertEquals("不是危机 → defer 照常写念头", 1, store.pendingIntents(personaId).size)
            assertTrue("普通 defer 仍然显示工具卡片", sink.events.any { it is SseEvent.Tool })
            assertEquals("等会儿再说。好。", assistantMessages().single().content)
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    // ------------------------------------------------------------------ risk=true：危机兜底

    @Test
    fun `risk true keeps the safety note and asks the model only once`() {
        fakeServer(
            riskJson = """{"risk":true,"reason":"有具体计划"}""",
            chatFrames = listOf(FakeLlmServer.chunk("别想太多，早点睡。"), FakeLlmServer.DONE)
        ).use { server ->
            val sink = send(chatService(server), "我想自杀", "true-1")

            val assistant = assistantMessages().single()
            assertTrue("有正文用正文", assistant.content.startsWith("别想太多，早点睡。"))
            assertTrue("缺锚点要补上：我在", assistant.content.contains("我在"))
            assertTrue("缺锚点要补上：120", assistant.content.contains("120"))
            assertTrue("在线客户端也要看到追加内容", sink.text().contains("120"))
            assertEquals("同一轮只判一次", 1, classifierRequests(server).size)
            assertEquals("判定 + 生成 = 2 次请求", 2, server.requests.size)
            assertTrue(
                "判定必须发生在生成之前（生成器要用它拦沉默工具）",
                server.requests.first().body.contains(CLASSIFIER_MARKER)
            )
        }
    }

    @Test
    fun `risk true still overrides stay silent`() {
        fakeServer(
            riskJson = """{"risk":true,"reason":"在说自杀计划"}""",
            chatFrames = listOf(staySilentCall(), FakeLlmServer.DONE)
        ).use { server ->
            val sink = send(chatService(server), "我真的不想活了", "true-silent")

            val assistant = assistantMessages().single()
            assertEquals("一个字的正文都没有 → 安全提示就是唯一正文", SafetyGuard.EMOTIONAL_NOTE, assistant.content)
            assertFalse("危机轮不能只已读", sink.firstOf<SseEvent.Done>().silent)
            assertEquals(assistant.id, sink.firstOf<SseEvent.Done>().assistantMessageId)
            assertEquals("沉默工具不能执行", 0, sink.events.count { it is SseEvent.Tool })
            assertEquals("危机轮只问一次生成模型", 2, server.requests.size)
            assertTrue(store.pendingIntents(personaId).isEmpty())
        }
    }

    // ------------------------------------------------------------------ 判定失败：安全优先

    @Test
    fun `a failed classification request still gets the safety note`() {
        fakeServer(
            chatFrames = listOf(FakeLlmServer.chunk("早点睡。"), FakeLlmServer.DONE),
            classifierStatus = 500
        ).use { server ->
            val sink = send(chatService(server), "我不想活了", "fail-http")

            val assistant = assistantMessages().single()
            assertTrue("判定失败按危机处理", assistant.content.contains("120"))
            assertTrue(sink.text().contains("120"))
            assertEquals(1, classifierRequests(server).size)
        }
    }

    @Test
    fun `a timed-out classification request still gets the safety note`() {
        fakeServer(
            chatFrames = listOf(FakeLlmServer.chunk("早点睡。"), FakeLlmServer.DONE),
            classifierDelayMs = 700
        ).use { server ->
            val startedAt = System.nanoTime()
            val sink = send(chatService(server, timeoutMs = 80), "我不想活了", "fail-timeout")
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("超时按危机处理", assistantMessages().single().content.contains("120"))
            assertTrue(sink.text().contains("120"))
            assertTrue("不能把用户晾在超时上，实际 ${elapsedMs}ms", elapsedMs < 3_000)
        }
    }

    // ------------------------------------------------------------------ 只看最近一条

    @Test
    fun `only the latest user message is judged - history never triggers the classifier`() {
        fakeServer(
            riskJson = """{"risk":false,"reason":"夸张说法"}""",
            chatFrames = listOf(FakeLlmServer.chunk("我在。"), FakeLlmServer.DONE),
            replyText = "我在。"
        ).use { server ->
            val service = chatService(server)
            // 第一条：词表命中 → 判一次（模型说不是）。
            send(service, "我想自杀", "latest-1")
            assertEquals(1, classifierRequests(server).size)
            assertEquals("我在。", assistantMessages().single().content)

            // 判定请求里只有 system + 这一条用户原话，没有历史。
            val judged = TestSupport.parse(classifierRequests(server).single().body)
            val messages = judged.objects("messages")
            assertEquals(2, messages.size)
            assertEquals("user", messages[1].str("role"))
            assertEquals("我想自杀", messages[1].str("content"))

            // 第二条普通消息：历史里有危机词，但不能再触发判定（只判最近一条）。
            send(service, "在吗", "latest-2")
            assertEquals("历史里的危机词不该让普通消息进判定", 1, classifierRequests(server).size)
            // 第一条：判定 1 + 聊天 1 + 提醒重试 1；第二条：聊天 1 + 提醒重试 1。
            assertEquals("两条消息共 5 次请求（判定只发生一次）", 5, server.requests.size)
        }
    }

    // ------------------------------------------------------------------ 装配

    @Test
    fun `AgentdServices wires the two-stage classifier into the chat service`() {
        fakeServer(
            riskJson = """{"risk":true,"reason":"在说自杀计划"}""",
            chatFrames = listOf(FakeLlmServer.chunk("别想太多，早点睡。"), FakeLlmServer.DONE)
        ).use { server ->
            store.pushLlmConfig(server.baseUrl)
            val llm = llmAccess(store)
            // 生产装配：AgentdServices 自己创建 ChatService（属性初始化顺序错了就会静默漏判）。
            val services = AgentdServices(store = store, llm = llm)
            assertTrue("有 LLM 就该有判定器", services.crisisClassifier != null)
            assertTrue(services.chatService != null)

            val sink = RecordingSink()
            services.chatService!!.send(
                sessionId,
                SendMessageRequest(clientMessageId = "wiring-1", content = "我想自杀"),
                sink
            )

            assertEquals("装配后的聊天必须真的问过判定器", 1, classifierRequests(server).size)
            assertTrue(
                "危机兜底要生效：${assistantMessages().map { it.content }}",
                assistantMessages().any { it.content.contains("120") }
            )
        }
    }

    private companion object {
        /** 判定器 system 提示词里的稳定标记（用来和聊天请求区分）。 */
        const val CLASSIFIER_MARKER = "危机判定器"
    }
}
