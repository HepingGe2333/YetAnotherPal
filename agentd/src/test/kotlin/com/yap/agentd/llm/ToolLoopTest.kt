package com.yap.agentd.llm

import com.yap.agentd.ChatService
import com.yap.agentd.RecordingSink
import com.yap.agentd.SendMessageRequest
import com.yap.agentd.SseEvent
import com.yap.agentd.TestSupport
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MessageRole
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具回路（`LlmReplyGenerator` 的 3 轮循环）端到端测试：
 *
 * 假模型服务端 → 真 `LlmClient`/`LlmAccess` → 真提示词 → 真工具执行 → 真 `ChatService` 落库。
 *
 * 钉住六件事：
 * 1. 工具调用被**真的执行**（`set_mood` 写进 moods.json），结果回灌给模型（第二轮请求里有 `role=tool`）；
 * 2. 流式 `tool_calls` 的参数被拆成多片时能拼回来；
 * 3. 默认最多 5 轮（模型/调用方可指定、上限 15），最后一轮不带 tools；超时按 deadline 停止；
 * 4. `stay_silent` 不落 assistant 消息、只标已读（`done.silent=true`）；
 * 5. `sticker.send` 的附件挂到 assistant 消息上；
 * 6. `toolTraceJson` 按客户端 `ToolTraceItem` 形状落库。
 */
class ToolLoopTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-tool-loop")
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

    private fun chatService(llm: LlmAccess, clock: Clock = Clock.systemDefaultZone()): ChatService =
        ChatService(store, LlmReplyGenerator(store, llm, clock = clock))

    private fun send(service: ChatService, clientMessageId: String = "c-1"): RecordingSink {
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(clientMessageId = clientMessageId, content = "在吗"),
            sink
        )
        return sink
    }

    // ------------------------------------------------------------------ 基本回路

    @Test
    fun `tool call is executed and its result is fed back before the reply`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            when (counter.getAndIncrement()) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "call_1",
                            name = "set_mood",
                            arguments = """{"label":"有点累","valence":-1,"intensity":3}"""
                        ),
                        FakeLlmServer.usage(120, 30),
                        FakeLlmServer.DONE
                    )
                )
                // 现在：话只能通过 send_message 说出去（正文不再是出口）。
                1 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "call_send",
                            name = "send_message",
                            arguments = """{"text":"嗯，今天有点乏。"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                else -> exchange.sse(listOf(FakeLlmServer.chunk("（这句只是正在输入）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val service = chatService(llmFor(server))
            val sink = send(service)

            assertEquals("accepted", sink.types().first())
            assertTrue("必须有 tool 事件", sink.types().contains("tool"))
            assertEquals("done", sink.types().last())
            // 现在：文字通过 send_message 发出去 → 客户端收到的是 message.created（不是 delta）。
            val created = sink.events.filterIsInstance<SseEvent.MessageCreated>().single()
            assertTrue("最终消息里要有模型的文字", created.message.content.contains("有点乏"))
            assertFalse(sink.firstOf<SseEvent.Done>().silent)

            // 工具真的写了库。
            assertEquals("有点累", store.mood(personaId)!!.label)

            // 第二次请求把工具结果回灌了：assistant(tool_calls) + tool(结果)。
            val second = server.requests[1].body
            assertTrue(second, second.contains("\"tool_calls\""))
            assertTrue(second, second.contains("\"role\":\"tool\""))
            assertTrue(second, second.contains("\"tool_call_id\":\"call_1\""))
            assertTrue(second, second.contains("记住了：你现在的心情"))

            // 一次用户消息 + 一条 assistant 消息。
            assertEquals(2, store.session(sessionId)!!.messageCount)
            assertEquals(2, store.blockMessages(sessionId, 0).size)
        }
    }

    @Test
    fun `streamed tool arguments split across chunks are reassembled`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            if (counter.getAndIncrement() == 0) {
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(0, id = "call_a", name = "set_activity"),
                        FakeLlmServer.toolArgs(0, """{"activity":"在画图","min"""),
                        FakeLlmServer.toolArgs(0, """utes":90}"""),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("好，我忙一会儿。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)))
            assertTrue(sink.types().contains("tool"))
            val stored = Json.parse(store.activity(personaId)!!) as JsonValue.Obj
            assertEquals("在画图", (stored.fields["activity"] as JsonValue.Str).value)
            val until = (stored.fields["until"] as JsonValue.Num).asLong ?: 0L
            val minutes = (until - System.currentTimeMillis()) / 60_000
            assertTrue("90 分钟的 until 应落在 88–90 分钟之间，实际 $minutes", minutes in 88..90)
        }
    }

    @Test
    fun `tool trace is persisted in the client shape`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            when (counter.getAndIncrement()) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(0, id = "c1", name = "diary", arguments = """{"action":"add","content":"买牛奶"}"""),
                        FakeLlmServer.DONE
                    )
                )
                1 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "c-send",
                            name = "send_message",
                            arguments = """{"text":"记下了。"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                else -> exchange.sse(listOf(FakeLlmServer.chunk("（这句只是正在输入）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            send(chatService(llmFor(server)))
            val assistant = store.blockMessages(sessionId, 0).last()
            assertEquals(MessageRole.ASSISTANT, assistant.role)
            val trace = assistant.toolTraceJson ?: error("没有 toolTraceJson")
            val items = Json.parse(trace) as JsonValue.Arr
            val item = items.items
                .filterIsInstance<JsonValue.Obj>()
                .single { (it.fields["name"] as JsonValue.Str).value == "diary" }
            assertEquals("写日记", (item.fields["title"] as JsonValue.Str).value)
            assertEquals(true, (item.fields["ok"] as JsonValue.Bool).value)
        }
    }

    @Test
    fun `sticker send attaches the media to the assistant message`() {
        val sticker = store.createSticker(personaId, MediaMime.GIF, GIF, width = 1, height = 1)
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            when (counter.getAndIncrement()) {
                0 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(0, id = "c1", name = "sticker", arguments = """{"action":"send","stickerId":"${sticker.id}"}"""),
                        FakeLlmServer.DONE
                    )
                )
                1 -> exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "c-send",
                            name = "send_message",
                            arguments = """{"text":"（发了个表情）"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
                else -> exchange.sse(listOf(FakeLlmServer.chunk("（这句只是正在输入）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            send(chatService(llmFor(server)))
            val assistant = store.blockMessages(sessionId, 0).last()
            assertEquals(listOf(sticker.mediaUrl), assistant.attachments)
        }
    }

    // ------------------------------------------------------------------ stay_silent

    @Test
    fun `stay silent leaves no assistant message and marks the session read`() {
        FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.toolCall(0, id = "c1", name = "stay_silent", arguments = """{"reason":"在忙"}"""),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            val service = chatService(llmFor(server))
            // 先有一条未读的 assistant 消息：`stay_silent` 之后它应该被标已读。
            store.appendMessage(sessionId, MessageRole.ASSISTANT, "之前的一条")
            assertEquals(1, store.session(sessionId)!!.unread)
            val sink = send(service, clientMessageId = "c-silent")

            val done = sink.firstOf<SseEvent.Done>()
            assertTrue("done 必须标 silent", done.silent)
            assertEquals("没有 assistant 消息", "", done.assistantMessageId)
            assertEquals(
                "这一轮只多了用户消息，没有 assistant 消息",
                listOf(MessageRole.ASSISTANT, MessageRole.USER),
                store.blockMessages(sessionId, 0).map { it.role }
            )
            assertEquals("会话已读", 0, store.session(sessionId)!!.unread)
            assertTrue(
                "之前那条 assistant 消息要被标已读",
                store.blockMessages(sessionId, 0).first().readAt != null
            )
            assertEquals("只问了一次模型", 1, server.requests.size)
        }
    }

    // ------------------------------------------------------------------ XML 工具标签（0.15）

    @Test
    fun `a tool call written as an XML tag is executed and never leaks to the client`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            if (counter.getAndIncrement() == 0) {
                // 评测里的真实形状：模型把工具调用写在正文里（DeepSeek 基线会这样）。
                exchange.sse(
                    listOf(
                        FakeLlmServer.chunk("好，我先记一下。"),
                        FakeLlmServer.chunk("<tool_call>"),
                        FakeLlmServer.chunk("""{"name":"set_mood","arguments":{"label":"有点累","valence":-1}}"""),
                        FakeLlmServer.chunk("</tool_call>"),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("嗯，今天有点乏。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)))

            assertTrue("XML 标签里的工具真的执行了", sink.types().contains("tool"))
            assertEquals("有点累", store.mood(personaId)!!.label)
            assertFalse("标签不能流到客户端", sink.text().contains("tool_call"))
            assertFalse(sink.text().contains("<tool"))
            assertTrue("标签外的文字照常下发", sink.text().contains("好，我先记一下。"))
            // 第二轮把 tool 消息回灌给了模型（同一条工具回路）。
            assertTrue(server.requests[1].body.contains("\"role\":\"tool\""))
        }
    }

    // ------------------------------------------------------------------ 轮数与超时

    @Test
    fun `default five rounds and the last one carries no tools`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            val index = counter.getAndIncrement()
            exchange.sse(
                listOf(
                    FakeLlmServer.toolCall(0, id = "c$index", name = "diary", arguments = """{"action":"list"}"""),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            val sink = send(chatService(llmFor(server)))
            assertEquals("默认 5 轮（0.15 的 ExperienceContract.DEFAULT_TOOL_ROUNDS）", 5, server.requests.size)
            assertTrue("第一轮必须带工具表", server.requests[0].body.contains("\"tools\""))
            assertFalse("最后一轮不再给 tools", server.requests[4].body.contains("\"tools\""))
            val done = sink.firstOf<SseEvent.Done>()
            assertTrue("只调工具不说话 → silent done", done.silent)
            assertEquals("没有 assistant 消息", 1, store.blockMessages(sessionId, 0).size)
        }
    }

    @Test
    fun `deadline stops the loop after the first tool round`() {
        // 每次读时钟前进 600ms：deadline=1000ms 时第一轮通过、工具执行后再检查就超时。
        var ticks = 0L
        val advancing = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long = ticks++ * 600L
        }
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.toolCall(0, id = "c", name = "set_mood", arguments = """{"label":"平静"}"""),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            val generator = LlmReplyGenerator(store, llmFor(server), clock = advancing, deadlineMs = 1_000L)
            val sink = RecordingSink()
            val outcome = generator.generate(
                com.yap.agentd.ReplyRequest(
                    sessionId = sessionId,
                    personaId = personaId,
                    userMessage = store.appendMessage(sessionId, MessageRole.USER, "在吗"),
                    history = store.latestMessages(sessionId, 10)
                )
            ) { sink.emit(it) }
            assertEquals("超时后不再问第二轮", 1, server.requests.size)
            assertEquals("工具仍然执行了", "平静", store.mood(personaId)!!.label)
            assertTrue(outcome.toolsUsed.contains("set_mood"))
        }
    }

    @Test
    fun `a gateway that ignores stream still yields tool calls`() {
        // 有的网关忽略 stream=true，直接回一整份 completion：tool_calls 也要能解析。
        FakeLlmServer { exchange ->
            exchange.json(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[""" +
                    """{"id":"call_ns","type":"function","function":{"name":"set_mood","arguments":"{\"label\":\"雀跃\"}"}}]}}],""" +
                    """"usage":{"prompt_tokens":5,"completion_tokens":5}}"""
            )
        }.use { server ->
            val llm = llmFor(server)
            val turn = llm.chat(
                llm.config(),
                listOf(LlmMessage.system("s"), LlmMessage.user("u")),
                tools = ServerToolCatalog.toOpenAiToolsJson()
            )
            val call = turn.toolCalls.single()
            assertEquals("call_ns", call.id)
            assertEquals("set_mood", call.name)
            assertEquals("雀跃", call.argumentsObject()!!.let { (it.fields["label"] as JsonValue.Str).value })
        }
    }

    @Test
    fun `tools can be disabled for a plain text reply`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("直接说"), FakeLlmServer.DONE))
        }.use { server ->
            val generator = LlmReplyGenerator(store, llmFor(server), toolEnabled = false)
            generator.generate(
                com.yap.agentd.ReplyRequest(
                    sessionId = sessionId,
                    personaId = personaId,
                    userMessage = store.appendMessage(sessionId, MessageRole.USER, "在吗"),
                    history = store.latestMessages(sessionId, 10)
                )
            ) {}
            assertFalse("关掉工具后请求里没有 tools", server.onlyRequest().body.contains("\"tools\""))
        }
    }

    @Test
    fun `unknown tool calls are reported back as failed results without breaking the reply`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            if (counter.getAndIncrement() == 0) {
                exchange.sse(
                    listOf(
                        // 0.16 起 `think` 是注册过的工具，这里换一个真的不存在的名字。
                        FakeLlmServer.toolCall(0, id = "c1", name = "teleport", arguments = "{}"),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("我不太确定，但可以说说。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(llmFor(server)))
            assertTrue(sink.text().contains("不太确定"))
            assertTrue(
                "工具结果里要说明不认识这个工具",
                server.requests[1].body.contains("不认识的工具")
            )
        }
    }

    private companion object {
        val GIF: ByteArray = java.util.Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    }
}
