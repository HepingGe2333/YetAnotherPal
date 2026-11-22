package com.yap.agentd

import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfig
import com.yap.agentd.llm.LlmContentPart
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.media.FrameExtractor
import com.yap.agentd.media.MediaUnderstanding
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.MessageRole
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.agentd.tools.ToolExecutor
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.Base64

/**
 * 0.14.1 的 `listen` / `watch`（`spec/audio-video/README.md`）：
 *
 * - `listen`：取会话里最近/指定的一条语音，base64 后作为 `input_audio` 交给模型；
 * - `watch`：用（可注入的）抽帧器抽 ≤8 帧 JPEG，作为多个 `image_url`；
 * - **没有解码器就明确说看不了**，不伪造帧；媒体不存在/已删/在拉黑消息里都明确失败；
 * - 端到端：工具结果真的以 content 数组送进下一次模型请求（`LlmReplyGenerator` 的回灌）。
 */
class MultimodalToolsTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    /** 两个固定 JPEG 头，用来让假抽帧器返回「真 JPEG」。 */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-multimodal")
        clock.now = 1_000_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun audioMedia(bytes: ByteArray = ByteArray(1024) { 7 }, mime: String = MediaMime.M4A): String =
        store.saveMedia(MediaKind.AUDIO, null, mime, bytes).item.id

    private fun videoMedia(bytes: ByteArray = ByteArray(4096) { 9 }): String =
        store.saveMedia(MediaKind.VIDEO, null, MediaMime.VIDEO_MP4, bytes).item.id

    private fun attach(mediaId: String, blocked: Boolean = false, content: String = "语音"): String =
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content,
            attachments = listOf(MediaUrl.url(mediaId)),
            blocked = blocked
        ).id

    private fun executor(extractor: FrameExtractor = FrameExtractor { _, _, _, _ -> emptyList() }): ToolExecutor =
        ToolExecutor(
            store = store,
            clock = clock.asClock(),
            media = MediaUnderstanding(store, extractor)
        )

    private fun run(
        name: String,
        arguments: String,
        extractor: FrameExtractor = FrameExtractor { _, _, _, _ -> emptyList() }
    ): ToolExecutor.Outcome = executor(extractor).execute(
        name,
        arguments,
        ToolExecutor.Context(personaId = personaId, sessionId = sessionId, userMessageId = "u-1")
    )

    // ------------------------------------------------------------------ listen

    @Test
    fun `listen turns the latest audio into an input_audio part`() {
        val bytes = ByteArray(2048) { 3 }
        val mediaId = audioMedia(bytes)
        attach(mediaId)

        val outcome = run(ServerToolCatalog.LISTEN, "{}")
        assertTrue(outcome.contentForModel, outcome.ok)
        assertTrue(outcome.contentForModel.contains("听了一遍"))
        val part = outcome.modelParts.single() as LlmContentPart.InputAudio
        assertEquals("m4a", part.format)
        assertEquals(Base64.getEncoder().encodeToString(bytes), part.base64)
    }

    @Test
    fun `listen uses an explicit id and maps mpeg to mp3`() {
        val first = audioMedia(ByteArray(100) { 1 })
        val second = audioMedia(ByteArray(200) { 2 }, mime = MediaMime.MPEG)
        attach(first)
        attach(second, content = "第二条语音")

        val outcome = run(ServerToolCatalog.LISTEN, """{"audioId":"$second"}""")
        assertTrue(outcome.ok)
        assertEquals("mp3", (outcome.modelParts.single() as LlmContentPart.InputAudio).format)
        assertEquals(
            Base64.getEncoder().encodeToString(ByteArray(200) { 2 }),
            (outcome.modelParts.single() as LlmContentPart.InputAudio).base64
        )
    }

    @Test
    fun `listen fails clearly for unknown deleted or blocked media`() {
        val unknown = run(ServerToolCatalog.LISTEN, """{"audioId":"nope"}""")
        assertTrue(!unknown.ok)
        assertTrue(unknown.contentForModel.contains("没有这个音频"))

        // 已删除的媒体。
        val deleted = audioMedia(ByteArray(50) { 1 })
        store.deleteMedia(deleted, force = true)
        val gone = run(ServerToolCatalog.LISTEN, """{"audioId":"$deleted"}""")
        assertTrue(!gone.ok)
        assertTrue(gone.contentForModel.contains("没有这个音频"))

        // 拉黑期间的消息里的语音不能被「最近一条」找到。
        val blockedMedia = audioMedia(ByteArray(60) { 2 })
        attach(blockedMedia, blocked = true)
        val blocked = run(ServerToolCatalog.LISTEN, "{}")
        assertFalse("拉黑消息里的语音不能被听到", blocked.ok)
        assertTrue(blocked.modelParts.isEmpty())
    }

    @Test
    fun `listen rejects an image and an oversized audio`() {
        val image = store.saveMedia(MediaKind.CHAT_IMAGE, null, MediaMime.JPEG, byteArrayOf(1, 2, 3)).item.id
        assertTrue(run(ServerToolCatalog.LISTEN, """{"audioId":"$image"}""").contentForModel.contains("这不是音频"))

        val huge = ByteArray(MediaUnderstanding.MAX_AUDIO_BYTES + 1) { 1 }
        val big = audioMedia(huge)
        assertTrue(run(ServerToolCatalog.LISTEN, """{"audioId":"$big"}""").contentForModel.contains("音频太大"))
    }

    // ------------------------------------------------------------------ watch

    @Test
    fun `watch returns image_url parts from the injected frame extractor`() {
        val videoId = videoMedia()
        attach(videoId, content = "视频")
        var requestedFrames = -1
        var requestedEdge = -1
        val extractor = FrameExtractor { _, _, maxFrames, maxEdge ->
            requestedFrames = maxFrames
            requestedEdge = maxEdge
            listOf(jpeg, jpeg, jpeg)
        }

        val outcome = run(ServerToolCatalog.WATCH, "{}", extractor)
        assertTrue(outcome.contentForModel, outcome.ok)
        assertTrue(outcome.summary.contains("看了 3 帧"))
        assertEquals(3, outcome.modelParts.size)
        assertTrue(outcome.modelParts.all { it is LlmContentPart.ImageUrl })
        val url = (outcome.modelParts.first() as LlmContentPart.ImageUrl).url
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals(8, requestedFrames)
        assertEquals(1024, requestedEdge)
    }

    @Test
    fun `watch without a decoder says it cannot watch instead of faking frames`() {
        val videoId = videoMedia()
        attach(videoId, content = "视频")
        val unavailable = object : FrameExtractor {
            override fun available(): Boolean = false
            override fun extract(video: ByteArray, mime: String, maxFrames: Int, maxEdge: Int): List<ByteArray> =
                emptyList()
        }

        val outcome = run(ServerToolCatalog.WATCH, """{"videoId":"$videoId"}""", unavailable)
        assertFalse(outcome.ok)
        assertEquals("服务器没有视频解码器，看不了视频", outcome.contentForModel.removePrefix("工具调用没有成功："))
        assertTrue(outcome.modelParts.isEmpty())
    }

    @Test
    fun `watch fails when the video is missing or the extractor has no frames`() {
        assertTrue(run(ServerToolCatalog.WATCH, """{"videoId":"nope"}""").contentForModel.contains("没有这个视频"))

        val videoId = videoMedia()
        attach(videoId, content = "视频")
        val empty = run(ServerToolCatalog.WATCH, "{}")
        assertTrue(!empty.ok)
        assertTrue(empty.contentForModel.contains("没能从这段视频里抽出画面"))
    }

    // ------------------------------------------------------------------ 端到端：多模态真的进了下一次请求

    @Test
    fun `listen result is fed back to the model as multimodal content`() {
        val bytes = ByteArray(512) { 5 }
        val audioId = audioMedia(bytes)
        val userMessageId = attach(audioId, content = "听听我这条语音")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        FakeLlmServer { exchange ->
            if (calls.incrementAndGet() == 1) {
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(0, id = "call-1", name = "listen", arguments = "{}"),
                        FakeLlmServer.usage(10, 2),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("听到了，你说周末想去看海。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            store.pushLlmConfig(server.baseUrl)
            val generator = LlmReplyGenerator(
                store = store,
                llm = llmAccess(store),
                clock = clock.asClock(),
                tools = ToolExecutor(
                    store = store,
                    clock = clock.asClock(),
                    media = MediaUnderstanding(store, FrameExtractor { _, _, _, _ -> emptyList() })
                )
            )
            val history = store.latestMessages(sessionId, 40)
            val userMessage = history.single { it.id == userMessageId }
            val sink = RecordingSink()
            val outcome = generator.generate(
                ReplyRequest(
                    sessionId = sessionId,
                    personaId = personaId,
                    userMessage = userMessage,
                    history = history
                )
            ) { sink.emit(it) }

            assertTrue("这一轮说了一句话", outcome.textLength > 0)
            assertEquals(2, server.requests.size)
            // 第二次请求里有一条 user 消息，content 是数组，里面就是 input_audio。
            val second = TestSupport.parse(server.requests[1].body)
            val messages = second.arr("messages")
            val audioMessage = messages
                .filterIsInstance<JsonValue.Obj>()
                .firstOrNull { message ->
                    val content = message.fields["content"]
                    content is JsonValue.Arr && content.items.any { item ->
                        (item as? JsonValue.Obj)?.fields?.get("type")?.let { (it as JsonValue.Str).value } == "input_audio"
                    }
                }
            assertTrue("模型请求里必须带 input_audio：${server.requests[1].body.take(400)}", audioMessage != null)
            val audioEntry = (audioMessage!!.fields["content"] as JsonValue.Arr).items
                .map { it as JsonValue.Obj }
                .single { (it.fields["type"] as JsonValue.Str).value == "input_audio" }
            val inputAudio = audioEntry.fields["input_audio"] as JsonValue.Obj
            assertEquals("m4a", (inputAudio.fields["format"] as JsonValue.Str).value)
            assertEquals(Base64.getEncoder().encodeToString(bytes), (inputAudio.fields["data"] as JsonValue.Str).value)
            // 工具结果也给模型看了一句「我听了」。
            assertTrue(server.requests[1].body.contains("我听了一遍"))
        }
    }

    // ------------------------------------------------------------------ LlmClient 序列化

    @Test
    fun `llm client serialises text image and audio content parts`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("好"), FakeLlmServer.DONE))
        }.use { server ->
            val client = LlmClient()
            client.chat(
                config = LlmConfig(baseUrl = server.baseUrl, model = "test-model"),
                messages = listOf(
                    LlmMessage.userWithParts(
                        "听一下",
                        listOf(
                            LlmContentPart.InputAudio("QUJD", "m4a"),
                            LlmContentPart.ImageUrl("data:image/jpeg;base64,QUJD")
                        )
                    ),
                    LlmMessage.user("普通文本消息")
                ),
                tools = null,
                onDelta = { }
            )
            val body = TestSupport.parse(server.onlyRequest().body)
            val sent = body.arr("messages")
            val content = (sent[0] as JsonValue.Obj).fields["content"] as JsonValue.Arr
            val types = content.items.map { ((it as JsonValue.Obj).fields["type"] as JsonValue.Str).value }
            assertEquals(listOf("text", "input_audio", "image_url"), types)
            val audio = (content.items[1] as JsonValue.Obj).fields["input_audio"] as JsonValue.Obj
            assertEquals("QUJD", (audio.fields["data"] as JsonValue.Str).value)
            assertEquals("m4a", (audio.fields["format"] as JsonValue.Str).value)
            val image = (content.items[2] as JsonValue.Obj).fields["image_url"] as JsonValue.Obj
            assertEquals("data:image/jpeg;base64,QUJD", (image.fields["url"] as JsonValue.Str).value)
            // 普通文本消息仍是字符串 content（老网关兼容）。
            assertTrue((sent[1] as JsonValue.Obj).fields["content"] is JsonValue.Str)
        }
    }
}
