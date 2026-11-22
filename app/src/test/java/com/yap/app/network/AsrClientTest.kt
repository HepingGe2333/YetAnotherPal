package com.yap.app.network

import com.yap.app.domain.AsrDefaults
import com.yap.app.domain.AsrFailure
import com.yap.app.domain.AsrOutcome
import com.yap.app.domain.AsrUrls
import com.yap.app.domain.transcriptOrNull
import com.yap.app.domain.AudioVideoKind
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ASR 客户端的纯 JVM 单测（不发真网）。
 *
 * 用 OkHttp `Interceptor` 冒充转写服务端：能逐项检查「方法 / 路径 / 头 / multipart 形状」，
 * 也能返回任意状态码、坏 JSON、超时——比 MockWebServer 少一个依赖，不受本机端口影响。
 */
class AsrClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val server = FakeServer()
    private val client = AsrClient(
        client = OkHttpClient.Builder().addInterceptor(server).build(),
        json = json
    )

    private fun request(
        baseUrl: String = "https://api.deepseek.com",
        model: String = AsrDefaults.MODEL,
        apiKey: String = "sk-asr-secret",
        bytes: ByteArray = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        kind: AudioVideoKind = AudioVideoKind.AUDIO,
        mime: String = "audio/m4a",
        language: String = AsrDefaults.LANGUAGE
    ) = AsrRequest(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        bytes = bytes,
        kind = kind,
        mime = mime,
        language = language
    )

    // ---------------------------------------------------------------- 成功路径 / multipart 形状

    @Test
    fun `multipart 形状与 Bearer 头符合 OpenAI 转写接口`() = runBlocking {
        server.respond { FakeResponse(body = """{"text":"今天有点累"}""") }

        val outcome = client.transcribe(request())

        assertEquals("今天有点累", outcome.transcriptOrNull)
        val sent = server.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("https://api.deepseek.com/audio/transcriptions", sent.url)
        assertEquals("Bearer sk-asr-secret", sent.header("authorization"))
        assertEquals("application/json", sent.header("accept"))
        // 应用层拦截器跑在 BridgeInterceptor 之前：此时 request.header("content-type") 还没被补上，
        // 但 MultipartBody 自带 contentType，形状判定要看它。
        val contentType = sent.contentType
        assertTrue("必须是 multipart/form-data：$contentType", contentType.startsWith("multipart/form-data; boundary="))
        assertTrue("multipart 必须带 boundary：$contentType", contentType.substringAfter("boundary=").isNotBlank())
        // file 段带文件名与 mime；model / language 是普通表单字段。
        assertTrue(sent.body.contains("name=\"file\"; filename=\"voice.m4a\""))
        assertTrue(sent.body.contains("Content-Type: audio/m4a"))
        assertTrue(sent.body.contains("name=\"model\""))
        assertTrue(sent.body.contains(AsrDefaults.MODEL))
        assertTrue(sent.body.contains("name=\"language\""))
        assertTrue(sent.body.contains("\r\n\r\nzh\r\n"))
    }

    @Test
    fun `base 带 v1 时保留 v1，粘完整 endpoint 也只出现一次`() = runBlocking {
        server.respond { FakeResponse(body = """{"text":"hi"}""") }
        client.transcribe(request(baseUrl = "https://api.openai.com/v1"))
        client.transcribe(request(baseUrl = "https://api.openai.com/v1/audio/transcriptions/"))

        assertEquals(
            listOf(
                "https://api.openai.com/v1/audio/transcriptions",
                "https://api.openai.com/v1/audio/transcriptions"
            ),
            server.requests.map { it.url }
        )
    }

    @Test
    fun `视频按 quicktime 文件名发送，响应字段兼容 data_text`() = runBlocking {
        server.respond { FakeResponse(body = """{"data":{"text":"视频里说了话"}}""") }

        val outcome = client.transcribe(
            request(mime = "video/quicktime", kind = AudioVideoKind.VIDEO)
        )

        assertEquals("视频里说了话", outcome.transcriptOrNull)
        val sent = server.requests.single()
        assertTrue(sent.body.contains("name=\"file\"; filename=\"video.mov\""))
        assertTrue(sent.body.contains("Content-Type: video/quicktime"))
    }

    // ---------------------------------------------------------------- 跳过（不发请求）

    @Test
    fun `没有 Key 直接跳过，一个请求都不发`() = runBlocking {
        val outcome = client.transcribe(request(apiKey = "   "))
        assertEquals(AsrOutcome.Skipped(AsrFailure.KEY_MISSING), outcome)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `超过音频上限直接跳过，一个请求都不发`() = runBlocking {
        // 8 MiB + 1：与 AudioVideoPolicy.AUDIO_MAX_BYTES 同一条线。
        val bytes = ByteArray((8 * 1024 * 1024) + 1)
        val outcome = client.transcribe(request(bytes = bytes))
        assertEquals(AsrOutcome.Skipped(AsrFailure.TOO_LARGE), outcome)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `不支持的 mime 直接跳过`() = runBlocking {
        val outcome = client.transcribe(request(mime = "image/jpeg", kind = AudioVideoKind.AUDIO))
        assertEquals(AsrOutcome.Skipped(AsrFailure.UNSUPPORTED_FORMAT), outcome)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `空字节跳过`() = runBlocking {
        val outcome = client.transcribe(request(bytes = ByteArray(0)))
        assertEquals(AsrOutcome.Skipped(AsrFailure.EMPTY_MEDIA), outcome)
        assertTrue(server.requests.isEmpty())
    }

    // ---------------------------------------------------------------- 失败分类

    @Test
    fun `401 归类为 Key 被拒绝，且日志与 detail 都不含 Key`() = runBlocking {
        server.respond {
            FakeResponse(
                code = 401,
                body = """{"error":{"message":"invalid key sk-asr-secret"}}"""
            )
        }
        val stdout = captureStdout {
            val outcome = runBlocking { client.transcribe(request()) }
            assertEquals(401, (outcome as AsrOutcome.Failed).httpStatus)
            assertEquals(AsrFailure.UNAUTHORIZED, outcome.reason)
            assertFalse("detail 里不能出现明文 Key", outcome.detail.orEmpty().contains("sk-asr-secret"))
            assertFalse("outcome.toString 不能出现明文 Key", outcome.toString().contains("sk-asr-secret"))
        }
        assertFalse("日志里不能出现明文 Key", stdout.contains("sk-asr-secret"))
        assertTrue("日志应保留脱敏后的错误体", stdout.contains("***"))
        assertTrue("日志应带状态码", stdout.contains("status=401"))
    }

    @Test
    fun `403 也是 UNAUTHORIZED，429 是限流，5xx 是服务端错误`() = runBlocking {
        server.respond { FakeResponse(code = 403, body = "{}") }
        assertEquals(AsrFailure.UNAUTHORIZED, (client.transcribe(request()) as AsrOutcome.Failed).reason)

        server.respond { FakeResponse(code = 429, body = "{}") }
        assertEquals(AsrFailure.RATE_LIMITED, (client.transcribe(request()) as AsrOutcome.Failed).reason)

        server.respond { FakeResponse(code = 503, body = "{}") }
        assertEquals(AsrFailure.SERVER_ERROR, (client.transcribe(request()) as AsrOutcome.Failed).reason)

        server.respond { FakeResponse(code = 400, body = "{}") }
        assertEquals(AsrFailure.BAD_REQUEST, (client.transcribe(request()) as AsrOutcome.Failed).reason)

        server.respond { FakeResponse(code = 413, body = "{}") }
        assertEquals(AsrFailure.TOO_LARGE, (client.transcribe(request()) as AsrOutcome.Failed).reason)
    }

    @Test
    fun `超时归类为 TIMEOUT`() = runBlocking {
        server.respond { throw SocketTimeoutException("read timed out") }
        val outcome = client.transcribe(request())
        assertEquals(AsrFailure.TIMEOUT, (outcome as AsrOutcome.Failed).reason)
    }

    @Test
    fun `连不上归类为 NETWORK`() = runBlocking {
        server.respond { throw UnknownHostException("no-such-host") }
        val outcome = client.transcribe(request())
        assertEquals(AsrFailure.NETWORK, (outcome as AsrOutcome.Failed).reason)
    }

    @Test
    fun `坏 JSON 是 UNPARSABLE，空 text 是 EMPTY_TRANSCRIPT`() = runBlocking {
        server.respond { FakeResponse(body = "not-a-json") }
        assertEquals(AsrFailure.UNPARSABLE, (client.transcribe(request()) as AsrOutcome.Failed).reason)

        server.respond { FakeResponse(body = """{"text":"   "}""") }
        assertEquals(AsrFailure.EMPTY_TRANSCRIPT, (client.transcribe(request()) as AsrOutcome.Failed).reason)

        server.respond { FakeResponse(body = """{"other":"x"}""") }
        assertEquals(AsrFailure.EMPTY_TRANSCRIPT, (client.transcribe(request()) as AsrOutcome.Failed).reason)
    }

    @Test
    fun `响应体超过 1 MiB 归类为 RESPONSE_TOO_LARGE`() = runBlocking {
        server.respond { FakeResponse(body = "x".repeat(AsrDefaults.MAX_RESPONSE_BYTES + 1)) }
        val outcome = client.transcribe(request())
        assertEquals(AsrFailure.RESPONSE_TOO_LARGE, (outcome as AsrOutcome.Failed).reason)
    }

    // ---------------------------------------------------------------- 超时配置 / 日志脱敏

    @Test
    fun `默认总超时是 60 秒`() {
        assertEquals(60L, AsrDefaults.TIMEOUT_SECONDS)
        assertEquals(60L, AsrClient.defaultTimeoutSeconds())
        assertEquals(60_000, client.clientFor(AsrDefaults.TIMEOUT_SECONDS).callTimeoutMillis)
    }

    @Test
    fun `请求对象与地址脱敏不泄露 Key`() {
        val req = request(apiKey = "sk-asr-secret")
        assertFalse(req.toString().contains("sk-asr-secret"))
        assertTrue(req.toString().contains("hasKey=true"))

        val redacted = client.redactUrl("https://api.example.com/audio/transcriptions?api_key=sk-asr-secret")
        assertFalse(redacted.contains("sk-asr-secret"))
        assertTrue(redacted.contains("audio/transcriptions"))

        assertEquals("Bearer ***", "Bearer " + client.scrub("sk-asr-secret", "sk-asr-secret"))
    }

    @Test
    fun `AsrUrls 归一后默认地址就是 DeepSeek 兼容端点`() {
        assertEquals("https://api.deepseek.com/audio/transcriptions", AsrUrls.endpoint(""))
        assertEquals(
            "https://api.deepseek.com/audio/transcriptions",
            AsrUrls.endpoint(" https://api.deepseek.com/chat/completions/ ")
        )
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            AsrUrls.endpoint("https://api.openai.com/v1")
        )
    }

    // ---------------------------------------------------------------- 辅助

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        return try {
            block()
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(original)
        }
    }

    private data class FakeResponse(
        val code: Int = 200,
        val body: String = "",
        val contentType: String = "application/json"
    )

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val contentType: String
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private class FakeServer : Interceptor {
        val requests = mutableListOf<RecordedRequest>()
        private var responder: (RecordedRequest) -> FakeResponse = { FakeResponse() }

        fun respond(responder: (RecordedRequest) -> FakeResponse) {
            this.responder = responder
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request: Request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val record = RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.toMultimap().mapKeys { it.key.lowercase() }
                    .mapValues { it.value.firstOrNull().orEmpty() },
                body = buffer.readUtf8(),
                contentType = request.body?.contentType()?.toString().orEmpty()
            )
            requests += record
            val fake = responder(record)
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
