package com.yap.agentd.mood

import com.yap.agentd.ChatService
import com.yap.agentd.RecordingSink
import com.yap.agentd.SendMessageRequest
import com.yap.agentd.TestSupport
import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Mood
import com.yap.agentd.tools.ToolExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.15「心情直觉化」的端到端契约：
 *
 * 1. **每条用户消息之后判一次**（标签 + 三维），且在**组提示词之前**判完——
 *    所以这一轮的 `# 你的心情` 是「刚看到这句话之后」的心情；
 * 2. **不再限制每维 ≤2**：模型给 2 → -2 就落成 -2（只夹物理范围）；
 * 3. **模型可以在同一轮里 `set_mood` 再改**：手动的意图覆盖自动判定；
 * 4. **提示词先给直觉结论**（标签 + 强度），再给三维校准值。
 */
class MoodPerMessageTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock = TestSupport.FakeClock(1_000L)
    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-mood-per-message")
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

    private fun send(service: ChatService, index: Int, content: String = "我今天被领导骂了"): RecordingSink {
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(clientMessageId = "m-$index", content = content),
            sink
        )
        return sink
    }

    @Test
    fun `every user message is judged before the reply prompt is built`() {
        // 上一轮心情在谷底；用户说了一句大好消息，模型判定从 -2 跳到 +2。
        store.setMood(personaId, "低落", intensity = 4, valence = -2, arousal = 1, dominance = 1, reason = "刚被骂")
        val moodRequests = AtomicInteger()
        FakeLlmServer { exchange ->
            if (exchange.request.body.contains("情绪判定器")) {
                moodRequests.incrementAndGet()
                exchange.json(
                    200,
                    """{"choices":[{"message":{"content":"{\"label\":\"雀跃\",\"valence\":2,""" +
                        """\"arousal\":5,\"dominance\":4,\"intensity\":5,""" +
                        """\"reason\":\"他忽然说方案过了\"}"}}]}"""
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("真的？那太好了。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val llm = llmFor(server)
            val service = ChatService(
                store,
                LlmReplyGenerator(store, llm, clock = clock.asClock()),
                moodJudge = MoodService(store, llm, clock.asClock())
            )
            send(service, 1)

            assertEquals("一条用户消息 → 一次心情判定", 1, moodRequests.get())
            val mood = store.mood(personaId)!!
            assertEquals("不再限制每维 ≤2：-2 → 2 直接落库", 2, mood.valence)
            assertEquals("雀跃", mood.label)
            assertEquals(5, mood.arousal)

            // 关键：这一轮的聊天提示词里带的是**新**心情（判定发生在组提示词之前）。
            val chat = server.requests.first { it.body.contains("# 你是谁") }
            assertTrue(chat.body, chat.body.contains("心情：雀跃（强度 5/5）"))
        }
    }

    @Test
    fun `a manual set_mood in the same round can change it again`() {
        store.setMood(personaId, "有点累", intensity = 3, valence = -1, arousal = 2, dominance = 3)
        val executor = ToolExecutor(store, clock = clock.asClock())
        val context = ToolExecutor.Context(personaId = personaId, sessionId = sessionId)

        val first = executor.execute(
            "set_mood",
            """{"label":"烦","valence":-2,"arousal":4,"intensity":4,"reason":"被催进度"}""",
            context
        )
        val second = executor.execute(
            "set_mood",
            """{"label":"雀跃","valence":2,"arousal":5,"dominance":4,"intensity":5,"reason":"忽然想通了"}""",
            context
        )

        assertTrue(first.ok)
        assertTrue(second.ok)
        val mood = store.mood(personaId)!!
        assertEquals("同一轮里第二次 set_mood 生效（手动的意图最后说了算）", "雀跃", mood.label)
        assertEquals(2, mood.valence)
        assertEquals(5, mood.intensity)
    }

    @Test
    fun `the mood section leads with the intuitive conclusion`() {
        val mood = Mood(
            personaId = personaId,
            label = "有点累",
            intensity = 3,
            updatedAt = clock.now,
            valence = -1,
            arousal = 2,
            dominance = 3,
            reason = "连着加班"
        )
        val prompt = ChatPromptBuilder(zone).systemPrompt(
            persona = store.persona(personaId)!!,
            mood = mood,
            activity = null,
            now = Instant.parse("2026-11-18T11:40:00Z").toEpochMilli(),
            sessionMemory = null
        )
        val labelIndex = prompt.indexOf("心情：有点累（强度 3/5）")
        val dimensionsIndex = prompt.indexOf("愉快度 -1")
        assertTrue("直觉结论必须出现", labelIndex >= 0)
        assertTrue("三维数值必须出现", dimensionsIndex >= 0)
        assertTrue("先直觉结论、再三维校准", labelIndex < dimensionsIndex)
        assertTrue(
            "不要把数值放在结论前面",
            prompt.indexOf("# 你的心情") < labelIndex
        )
        assertEquals("心情原因不直接进提示词", -1, prompt.indexOf("连着加班"))
    }

    @Test
    fun `a failing mood judgement decays instead of blocking the reply`() {
        val chatCalls = AtomicInteger()
        FakeLlmServer { exchange ->
            if (exchange.request.body.contains("情绪判定器")) {
                exchange.json(500, """{"error":{"message":"boom"}}""")
            } else if (chatCalls.getAndIncrement() == 0) {
                // 现在：聊天回复必须用 send_message（正文不再算出口），否则会被确认循环丢弃。
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "send-1",
                            name = "send_message",
                            arguments = """{"text":"嗯，我在。"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("（这句只是正在输入）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val llm = llmFor(server)
            val service = ChatService(
                store,
                LlmReplyGenerator(store, llm, clock = clock.asClock()),
                moodJudge = MoodService(store, llm, clock.asClock())
            )
            val sink = send(service, 1)
            assertEquals("心情判定失败不能打挂回复", "done", sink.types().last())
            assertTrue("模型已按时间衰减兜底（写入基准心情）", store.mood(personaId) != null)
            assertEquals(
                "回复照常落库",
                1,
                store.blockMessages(sessionId, 0).count { it.role == MessageRole.ASSISTANT }
            )
        }
    }
}
