package com.yap.agentd

import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfig
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.LlmErrorKind
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.ServerLlmConfig
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.agentd.tools.ToolExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 0.16「完全工具调用化 + 思考强度」（`spec/tool-call-agents/README.md`）。
 *
 * 钉住五件事：
 * 1. **`send_message` 是唯一出口**：工具结果回真 `messageId`，落库用同一个 id，SSE 是
 *    `accepted → message.created → done`；
 * 2. **legacy 兼容回退**：整轮一次 `send_message` 都没调、却有正文 → 正文落库并逐条
 *    `message.created`（标记 legacy，日志可查），不是正常路径；
 * 3. **只有沉默工具**：`stay_silent` → 不落消息、`done.silent`、没有 `message.created`；
 * 4. **迁移兼容**：旧消息（无 `toolTraceJson`）让提示词注入迁移说明，但旧消息本身不补痕迹；
 * 5. **思考强度**：主聊天默认 `low`，`think` 之后同一轮 `high`，下一轮回到默认；辅助调用不带；
 *    服务端配置（`ServerLlmConfig.reasoningEffort` / `YAP_LLM_REASONING_EFFORT`）覆盖默认值。
 */
class ToolCallOnlyTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Long = Instant.parse("2026-11-18T11:40:00Z").toEpochMilli() // 19:40 本地

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-toolcall-only")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
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
        ChatService(store, LlmReplyGenerator(store, llm, clock = clock.asClock(), toolRounds = toolRounds))

    private fun send(service: ChatService, clientMessageId: String = "c-1"): RecordingSink =
        RecordingSink().also { sink ->
            service.send(
                sessionId,
                SendMessageRequest(clientMessageId = clientMessageId, content = "在吗"),
                sink
            )
        }

    private fun assistants(): List<Message> =
        store.blockMessages(sessionId, 0).filter { it.role == MessageRole.ASSISTANT }

    private fun requestBody(server: FakeLlmServer, index: Int) = TestSupport.parse(server.requests[index].body)

    /** 这次请求的最后一条是不是「请确认要不要发送」的不可见提醒。 */
    private fun awaitsConfirmation(body: String): Boolean {
        val last = TestSupport.parse(body).objects("messages").lastOrNull() ?: return false
        return last.str("role") == "user" && last.str("content") == LlmReplyGenerator.PROTOCOL_REMINDER
    }

    // ------------------------------------------------------------------ 1. send_message 是唯一出口

    @Test
    fun `send_message returns a real message id and the persisted message uses it`() {
        var round = 0
        FakeLlmServer { exchange ->
            when (round++) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "call_send",
                            name = ServerToolCatalog.SEND_MESSAGE,
                            arguments = """{"text":"我在的。"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                // 第二轮模型还写了一句「正文」：既然它用了 send_message，这段只当「正在输入」。
                else -> exchange.sse(listOf(FakeLlmServer.chunk("（这句不该落库）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val service = chatService(llmFor(server))
            val sink = send(service, "only-1")

            assertEquals("必须问了第二轮（模型可以继续）", 2, server.requests.size)
            // 第二轮请求里，工具结果已回灌「已发送，messageId=…」。
            val toolMessage = requestBody(server, 1).objects("messages")
                .last { it.str("role") == "tool" }
            val toolText = toolMessage.str("content")
            assertTrue(toolText, toolText.startsWith("已发送，messageId="))
            val messageId = Regex("messageId=([0-9a-f-]{36})").find(toolText)?.groupValues?.get(1)
                ?: error("工具结果里没有 messageId：$toolText")
            assertEquals("call_send", toolMessage.str("tool_call_id"))

            // 落库的那条消息用的就是这个 id（工具结果里的 id 不是编的）。
            val assistant = assistants().single()
            assertEquals(messageId, assistant.id)
            assertEquals("我在的。", assistant.content)
            assertTrue("send_message 留下工具痕迹", assistant.toolTraceJson.orEmpty().contains("send_message"))

            // SSE 形状：accepted → (tool 事件) → message.created → done，只有一条气泡 + 一个 done。
            val types = sink.types()
            assertEquals("accepted", types.first())
            assertEquals("done", types.last())
            assertEquals(1, types.count { it == "message.created" })
            assertTrue("message.created 必须早于 done", types.indexOf("message.created") < types.indexOf("done"))
            val created = sink.firstOf<SseEvent.MessageCreated>()
            assertEquals(messageId, created.message.id)
            assertEquals(created.message.id, sink.firstOf<SseEvent.Done>().assistantMessageId)
            assertFalse("不是沉默", sink.firstOf<SseEvent.Done>().silent)
        }
    }

    @Test
    fun `every visible message must be sent through the tool and the prompt says so`() {
        val capabilities = ServerToolCatalog.capabilityPrompt()
        assertTrue(capabilities.contains("每一句话"))
        assertTrue(capabilities.contains("`send_message`"))
        assertTrue(capabilities.contains("每轮最多 4 条"))
        assertTrue(capabilities.contains("120 字"))
        assertTrue("工具结果要回 messageId", capabilities.contains("已发送，messageId="))
        assertTrue("什么都不发 = 协议违规", capabilities.contains("协议违规"))

        val prompt = ChatPromptBuilder(zone).systemPrompt(
            persona = store.persona(personaId)!!,
            mood = null,
            activity = null,
            now = now
        )
        assertTrue(prompt.contains("## 怎么说话（必须遵守）"))
        assertTrue(prompt.contains("直接写在回复正文里的字对方看不到"))
        assertTrue("think 也注册进了工具段", prompt.contains("`think`"))
    }

    // ------------------------------------------------------------------ 2. legacy 兼容回退

    @Test
    fun `a body without any send_message is a legacy fallback that still lands and is logged`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("第一段\n\n第二段"), FakeLlmServer.DONE))
        }.use { server ->
            val llm = llmFor(server)
            // 关掉工具是**纯文字降级**路径（老模型/不支持 tools 的网关）：这里没有确认循环，
            // 因为没有 send_message 可调；正文仍走 legacy 兼容回退（与 0.16 一致）。
            val userMessage = store.appendMessage(sessionId, MessageRole.USER, "说两句")
            val outcome = LlmReplyGenerator(
                store,
                llm,
                clock = clock.asClock(),
                toolEnabled = false
            ).generate(
                ReplyRequest(
                    sessionId = sessionId,
                    personaId = personaId,
                    userMessage = userMessage,
                    history = store.latestMessages(sessionId, 10)
                )
            ) { }
            assertTrue("没有 send_message 就是 legacy", outcome.legacy)
            assertTrue("legacy 不产出工具消息", outcome.messages.isEmpty())
            assertTrue(outcome.messageIds.isEmpty())

            // 聊天层视角：正文按空行拆成两条落库，各发一条 message.created，最后只有一个 done。
            val sink = send(
                ChatService(store, LlmReplyGenerator(store, llm, clock = clock.asClock(), toolEnabled = false)),
                "legacy-1"
            )
            val created = sink.events.filterIsInstance<SseEvent.MessageCreated>()
            assertEquals(listOf("第一段", "第二段"), created.map { it.message.content })
            assertEquals(2, assistants().size)
            assertEquals("legacy 消息没有工具痕迹", null, assistants().first().toolTraceJson)
            assertEquals(1, sink.events.count { it is SseEvent.Done })
            assertEquals(created.last().message.id, sink.firstOf<SseEvent.Done>().assistantMessageId)
        }
    }

    // ------------------------------------------------------------------ 3. 只有沉默工具

    @Test
    fun `a silent-only turn sends nothing and creates no message`() {
        FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.toolCall(
                        0,
                        id = "c1",
                        name = ServerToolCatalog.STAY_SILENT,
                        arguments = """{"reason":"在忙"}"""
                    ),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            val sink = send(chatService(llmFor(server)), "silent-1")

            assertEquals("stay_silent 立即结束这一轮（不再问模型）", 1, server.requests.size)
            assertTrue(assistants().isEmpty())
            assertTrue("只已读，没有任何气泡", sink.events.none { it is SseEvent.MessageCreated })
            val done = sink.firstOf<SseEvent.Done>()
            assertTrue(done.silent)
            assertEquals("", done.assistantMessageId)
        }
    }

    // ------------------------------------------------------------------ 4. 迁移兼容

    @Test
    fun `old assistant messages inject the migration note but are never rewritten`() {
        val legacy = store.appendMessage(sessionId, MessageRole.ASSISTANT, "旧版本直接说的一句")
        assertNull("旧消息本来就没有工具痕迹", legacy.toolTraceJson)

        val builder = ChatPromptBuilder(zone)
        val history = store.latestMessages(sessionId, 20)
        val built = builder.chatMessages(store.persona(personaId)!!, null, null, history, now)
        val system = built.first().content
        assertTrue("有旧消息时要说明「那是记录，不是许可」", system.contains(ChatPromptBuilder.LEGACY_HISTORY_NOTE))
        assertTrue(system.contains("不代表你现在可以跳过"))

        // 旧消息不被改写：提示词注入只是给模型看的，存储里仍然没有 toolTraceJson。
        assertNull(store.blockMessages(sessionId, 0).single { it.role == MessageRole.ASSISTANT }.toolTraceJson)

        // 有工具痕迹的新消息（send_message 之类）不触发迁移说明。
        val fresh = legacy.copy(
            id = "fresh-1",
            toolTraceJson = """[{"name":"send_message","title":"发了消息","detail":"我在。","ok":true}]"""
        )
        val withoutLegacy = builder.chatMessages(store.persona(personaId)!!, null, null, listOf(fresh), now)
        assertFalse(withoutLegacy.first().content.contains(ChatPromptBuilder.LEGACY_HISTORY_NOTE))
    }

    @Test
    fun `think tool only asks for deeper reasoning and writes nothing`() {
        val executor = ToolExecutor(store, clock = Clock.fixed(Instant.ofEpochMilli(now), zone))
        val moodBefore = store.mood(personaId)
        val outcome = executor.execute(
            ServerToolCatalog.THINK,
            """{"question":"要不要直说"}""",
            ToolExecutor.Context(personaId = personaId, sessionId = sessionId)
        )
        assertTrue(outcome.ok)
        assertTrue("think 要让这一轮后续请求升到 high", outcome.deepThinking)
        assertEquals("想了想", outcome.trace.title)
        assertNull("think 没有副作用", store.blockMessages(sessionId, 0).firstOrNull())
        assertEquals("think 不动心情", moodBefore, store.mood(personaId))
        // 参数缺失也不抛（模型经常只给一个空对象）。
        assertTrue(executor.execute(ServerToolCatalog.THINK, "{}", ToolExecutor.Context(personaId, sessionId)).deepThinking)
    }

    // ------------------------------------------------------------------ 5. 思考强度

    @Test
    fun `main chat sends low by default and high after think then back to low next turn`() {
        var round = 0
        FakeLlmServer { exchange ->
            when (round++) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "c1",
                            name = ServerToolCatalog.THINK,
                            arguments = """{"question":"怎么回"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                // 正文直出之后会被问「要不要发送」：模型改口 send_message（确认轮在同一轮里）。
                // 判断「上一条就是确认提醒」，避免把上下文里留着的旧提醒误当成新提醒。
                else -> if (awaitsConfirmation(exchange.request.body)) {
                    exchange.sse(
                        listOf(
                            FakeLlmServer.toolCall(
                                0,
                                id = "send-${round}",
                                name = ServerToolCatalog.SEND_MESSAGE,
                                arguments = """{"text":"还没想好怎么回。"}"""
                            ),
                            FakeLlmServer.DONE
                        )
                    )
                } else {
                    exchange.sse(listOf(FakeLlmServer.chunk("还没想好怎么回。"), FakeLlmServer.DONE))
                }
            }
        }.use { server ->
            // 2 轮刚好：think + 正文，确认后的 send_message 不吃普通轮数。
            val service = chatService(llmFor(server), toolRounds = 2)
            send(service, "effort-1")
            // think 1 轮 + 正文直出一轮 + 确认提醒后的 send_message 1 轮 = 3 次请求。
            assertEquals(3, server.requests.size)
            assertEquals(
                "主聊天默认 low",
                "low",
                requestBody(server, 0).stringAt("reasoning_effort")
            )
            assertEquals(
                "think 之后同一轮的后续请求是 high",
                "high",
                requestBody(server, 1).stringAt("reasoning_effort")
            )
            assertEquals(
                "确认提醒后的重试仍属于同一轮（think 之后 → high）",
                "high",
                requestBody(server, 2).stringAt("reasoning_effort")
            )

            // 下一轮重新开始：恢复默认 low（think 只影响它所在的那一轮）。
            send(service, "effort-2")
            // 正文直出 + 确认提醒后 send_message 各 1 次。
            assertEquals(5, server.requests.size)
            assertEquals("low", requestBody(server, 3).stringAt("reasoning_effort"))
        }
    }

    @Test
    fun `server config overrides the default reasoning effort`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("嗯。"), FakeLlmServer.DONE))
        }.use { server ->
            val resolver = LlmConfigResolver(
                store = null,
                loopback = false,
                server = ServerLlmConfig(
                    baseUrl = server.baseUrl,
                    model = "test-model",
                    reasoningEffort = "medium"
                )
            )
            val llm = LlmAccess(LlmClient(), resolver)
            assertEquals("medium", llm.config().reasoningEffort)
            send(chatService(llm), "override-1")
            assertEquals("medium", requestBody(server, 0).stringAt("reasoning_effort"))
        }
    }

    @Test
    fun `environment variable configures the effort and a bad value fails loudly`() {
        store.pushLlmConfig("http://127.0.0.1:9/v1")
        val high = LlmConfigResolver(
            store = store,
            loopback = true,
            env = { name -> if (name == LlmConfigResolver.ENV_LLM_REASONING_EFFORT) "HIGH" else null }
        )
        assertEquals("high", high.resolve().reasoningEffort)

        val broken = LlmConfigResolver(
            store = store,
            loopback = true,
            env = { name -> if (name == LlmConfigResolver.ENV_LLM_REASONING_EFFORT) "hyper" else null }
        )
        try {
            broken.resolve()
            fail("不认识的思考强度必须明确失败")
        } catch (failure: LlmException) {
            assertEquals(LlmErrorKind.INVALID_CONFIG.code, failure.code)
            assertTrue(failure.message.orEmpty().contains("low / medium / high"))
        }
    }

    @Test
    fun `auxiliary calls never carry reasoning effort`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("嗯。"), FakeLlmServer.DONE))
        }.use { server ->
            val llm = llmFor(server)
            llm.complete(llm.config(), listOf(LlmMessage.user("在吗")))
            assertNull(
                "情绪判定/进化/主动消息这类辅助调用不带 reasoning_effort",
                TestSupport.parse(server.onlyRequest().body).stringAt("reasoning_effort")
            )
        }
    }

    @Test
    fun `an unknown reasoning effort is rejected before the request is sent`() {
        val client = LlmClient()
        val config = LlmConfig(baseUrl = "http://127.0.0.1:1/v1", model = "test-model")
        try {
            client.chat(config, listOf(LlmMessage.user("在吗")), tools = null, onDelta = {}, reasoningEffort = "hyper")
            fail("不良取值必须失败")
        } catch (failure: LlmException) {
            assertEquals(LlmErrorKind.INVALID_CONFIG.code, failure.code)
        }
    }
}
