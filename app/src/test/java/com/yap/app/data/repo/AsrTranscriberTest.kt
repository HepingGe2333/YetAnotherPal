package com.yap.app.data.repo

import com.yap.app.domain.AsrAvailability
import com.yap.app.domain.AsrContext
import com.yap.app.domain.AsrFailure
import com.yap.app.domain.AsrOutcome
import com.yap.app.domain.AsrTranscriptState
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.ResolvedAsrConfig
import com.yap.app.domain.transcriptOrNull
import com.yap.app.network.AsrClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * 发送链路转写服务的单测：**不联网、不落库**。
 *
 * 覆盖需求里的每一条降级规则：
 * - 关着 / 没 Key / Key 解不开 → 不读文件、不发请求、附件照样能发；
 * - 成功 → 写 transcript + OK；
 * - 失败/读不到/超限 → transcript 为 null + FAILED/SKIPPED，**绝不伪造文本**；
 * - 上下文注入「他说：…」与「没有转写」。
 */
class AsrTranscriberTest {

    private val http = MockHttp()
    private val client = AsrClient(
        client = OkHttpClient.Builder().addInterceptor(http).build(),
        json = Json { ignoreUnknownKeys = true; isLenient = true }
    )

    private fun audio(
        transcript: String? = null,
        state: String? = null,
        uri: String = "file:///tmp/voice.m4a"
    ) = MessageAttachment(
        uri = uri,
        mime = "audio/m4a",
        kind = MessageAttachment.KIND_AUDIO,
        durationMs = 3_000L,
        transcript = transcript,
        transcriptState = state
    )

    private class FakeConfigs(var value: AsrAvailability) : AsrConfigSource {
        override suspend fun availability(): AsrAvailability = value
    }

    private class FakeMedia(private val bytes: ByteArray?) : AsrMediaSource {
        var reads = 0
        override suspend fun read(attachment: MessageAttachment): ByteArray? {
            reads += 1
            return bytes
        }
    }

    private fun ready() = AsrAvailability.Ready(
        ResolvedAsrConfig(
            baseUrl = "https://api.deepseek.com",
            model = "whisper-1",
            apiKey = "sk-asr-live"
        )
    )

    // ---------------------------------------------------------------- 跳过：不读文件、不发请求

    @Test
    fun `开关关着：不读文件不发请求，附件标记 SKIPPED 但仍可发送`() = runBlocking {
        val media = FakeMedia(byteArrayOf(1, 2, 3))
        val transcriber = AsrTranscriber(FakeConfigs(AsrAvailability.Disabled), media, client)

        val outcome = transcriber.transcribe(audio())
        assertEquals(AsrOutcome.Skipped(AsrFailure.DISABLED), outcome)
        assertEquals(0, media.reads)
        assertTrue(http.requests.isEmpty())

        val enriched = transcriber.enrich(audio())
        assertNull(enriched.transcript)
        assertEquals(AsrTranscriptState.SKIPPED, enriched.transcriptState)
        assertEquals("file:///tmp/voice.m4a", enriched.uri)
        assertEquals("audio/m4a", enriched.mime)
        assertEquals(3_000L, enriched.durationMs)
        assertTrue("跳过也要能照常发送", enriched.isAudio)
    }

    @Test
    fun `没有 Key：同样直接跳过，不联网`() = runBlocking {
        val media = FakeMedia(byteArrayOf(1, 2, 3))
        val transcriber = AsrTranscriber(FakeConfigs(AsrAvailability.KeyMissing), media, client)

        assertEquals(AsrOutcome.Skipped(AsrFailure.KEY_MISSING), transcriber.transcribe(audio()))
        assertEquals(0, media.reads)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `Key 解不开：跳过而不是当成没配`() = runBlocking {
        val media = FakeMedia(byteArrayOf(1, 2, 3))
        val transcriber = AsrTranscriber(FakeConfigs(AsrAvailability.KeyUnrecoverable), media, client)

        assertEquals(AsrOutcome.Skipped(AsrFailure.KEY_UNRECOVERABLE), transcriber.transcribe(audio()))
        assertEquals(0, media.reads)
    }

    // ---------------------------------------------------------------- 成功

    @Test
    fun `成功：写 transcript 与 OK，保留原附件字段`() = runBlocking {
        http.respond { FakeResponse(body = """{"text":"今天有点累"}""") }
        val transcriber = AsrTranscriber(FakeConfigs(ready()), FakeMedia(byteArrayOf(1, 2, 3)), client)

        val enriched = transcriber.enrich(audio())

        assertEquals("今天有点累", enriched.transcript)
        assertEquals(AsrTranscriptState.OK, enriched.transcriptState)
        assertEquals("file:///tmp/voice.m4a", enriched.uri)
        assertEquals(3_000L, enriched.durationMs)

        val sent = http.requests.single()
        assertEquals("Bearer sk-asr-live", sent.header("authorization"))
        assertEquals("https://api.deepseek.com/audio/transcriptions", sent.url)
        assertTrue(sent.body.contains("whisper-1"))
        assertTrue(sent.body.contains("\r\n\r\nzh\r\n"))
    }

    // ---------------------------------------------------------------- 失败：不伪造

    @Test
    fun `服务端 500：transcript 为 null 并标记 FAILED`() = runBlocking {
        http.respond { FakeResponse(code = 500, body = """{"error":"boom"}""") }
        val transcriber = AsrTranscriber(FakeConfigs(ready()), FakeMedia(byteArrayOf(1, 2, 3)), client)

        val outcome = transcriber.transcribe(audio())
        assertEquals(AsrFailure.SERVER_ERROR, (outcome as AsrOutcome.Failed).reason)

        val enriched = transcriber.enrich(audio())
        assertNull("失败时绝不能补一句猜的文本", enriched.transcript)
        assertEquals(AsrTranscriptState.FAILED, enriched.transcriptState)
        assertTrue(AsrTranscriber.isMissingTranscript(enriched))
    }

    @Test
    fun `读不到媒体字节：FAILED，不联网`() = runBlocking {
        val media = FakeMedia(null)
        val transcriber = AsrTranscriber(FakeConfigs(ready()), media, client)

        val outcome = transcriber.transcribe(audio())
        assertEquals(AsrFailure.UNREADABLE_MEDIA, (outcome as AsrOutcome.Failed).reason)
        assertEquals(1, media.reads)
        assertTrue(http.requests.isEmpty())

        val enriched = transcriber.enrich(audio())
        assertEquals(AsrTranscriptState.FAILED, enriched.transcriptState)
    }

    @Test
    fun `超过音频上限：SKIPPED，不联网`() = runBlocking {
        val media = FakeMedia(ByteArray((8 * 1024 * 1024) + 1))
        val transcriber = AsrTranscriber(FakeConfigs(ready()), media, client)

        assertEquals(AsrOutcome.Skipped(AsrFailure.TOO_LARGE), transcriber.transcribe(audio()))
        assertTrue(http.requests.isEmpty())

        val enriched = transcriber.enrich(audio())
        assertNull(enriched.transcript)
        assertEquals(AsrTranscriptState.SKIPPED, enriched.transcriptState)
    }

    @Test
    fun `图片不参与转写`() = runBlocking {
        val media = FakeMedia(byteArrayOf(1))
        val transcriber = AsrTranscriber(FakeConfigs(ready()), media, client)
        val image = MessageAttachment(uri = "file:///tmp/a.jpg", mime = "image/jpeg", kind = MessageAttachment.KIND_IMAGE)

        assertEquals(AsrOutcome.Skipped(AsrFailure.NOT_MEDIA), transcriber.transcribe(image))
        assertEquals(0, media.reads)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `已有转写不会被关掉开关抹掉`() = runBlocking {
        val transcriber = AsrTranscriber(FakeConfigs(AsrAvailability.Disabled), FakeMedia(byteArrayOf(1)), client)
        val existing = audio(transcript = "之前转写过的内容", state = AsrTranscriptState.OK)

        val enriched = transcriber.enrich(existing)

        assertEquals("之前转写过的内容", enriched.transcript)
        assertEquals(AsrTranscriptState.OK, enriched.transcriptState)
    }

    // ---------------------------------------------------------------- 上下文注入

    @Test
    fun `上下文能看到他说…，失败时明确写没有转写`() {
        val success = audio(transcript = "今天有点累", state = AsrTranscriptState.OK)
        val failed = audio(transcript = null, state = AsrTranscriptState.FAILED)
        val untouched = audio()

        val base = "（对方发来一段 3 秒的语音）"
        val annotated = AsrContext.annotated(base, success)
        assertTrue(annotated.contains("他说：今天有点累"))
        assertTrue(annotated.startsWith(base))

        assertTrue(AsrContext.annotated(base, failed).contains("没有转写"))
        assertEquals("从没转写过的老数据保持原占位正文", base, AsrContext.annotated(base, untouched))
        assertNull(AsrContext.sentence(untouched))

        val video = MessageAttachment(
            uri = "file:///tmp/v.mp4",
            mime = "video/mp4",
            kind = MessageAttachment.KIND_VIDEO,
            transcript = "视频里说你好",
            transcriptState = AsrTranscriptState.OK
        )
        assertTrue(AsrContext.annotated("（对方发来一段视频）", video).contains("他说：视频里说你好"))
    }

    // ---------------------------------------------------------------- 测试按钮

    @Test
    fun `测试按钮发 1 秒 wav 探测音频`() = runBlocking {
        http.respond { FakeResponse(body = """{"text":"嗯"}""") }
        val transcriber = AsrTranscriber(FakeConfigs(AsrAvailability.Disabled), FakeMedia(null), client)

        val outcome = transcriber.probe(
            ResolvedAsrConfig(baseUrl = "https://asr.example.com/v1", model = "whisper-1", apiKey = "sk-asr-live")
        )

        assertEquals("嗯", outcome.transcriptOrNull)
        val sent = http.requests.single()
        assertEquals("https://asr.example.com/v1/audio/transcriptions", sent.url)
        assertTrue(sent.body.contains("name=\"file\"; filename=\"probe.wav\""))
        assertTrue(sent.body.contains("RIFF"))
    }

    @Test
    fun `探测请求的 Key 不会进日志`() = runBlocking {
        http.respond { FakeResponse(code = 401, body = """{"error":"bad key sk-asr-live"}""") }
        val transcriber = AsrTranscriber(FakeConfigs(AsrAvailability.Disabled), FakeMedia(null), client)
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        try {
            transcriber.probe(
                ResolvedAsrConfig(baseUrl = "https://asr.example.com", model = "whisper-1", apiKey = "sk-asr-live")
            )
        } finally {
            System.setOut(original)
        }
        assertFalse(buffer.toString(Charsets.UTF_8.name()).contains("sk-asr-live"))
    }

    // ---------------------------------------------------------------- 辅助

    private data class FakeResponse(
        val code: Int = 200,
        val body: String = "",
        val contentType: String = "application/json"
    )

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private class MockHttp : Interceptor {
        val requests = mutableListOf<RecordedRequest>()
        private var responder: (RecordedRequest) -> FakeResponse = { FakeResponse() }

        fun respond(responder: (RecordedRequest) -> FakeResponse) {
            this.responder = responder
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request: Request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requests += RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.toMultimap().mapKeys { it.key.lowercase() }
                    .mapValues { it.value.firstOrNull().orEmpty() },
                body = buffer.readUtf8()
            )
            val fake = responder(requests.last())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(fake.code)
                .message("mock")
                .body(fake.body.toResponseBody(fake.contentType.toMediaType()))
                .build()
        }
    }
}
