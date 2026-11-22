package com.yap.app.network

import com.yap.app.domain.AsrDefaults
import com.yap.app.domain.AsrFailure
import com.yap.app.domain.AsrModels
import com.yap.app.domain.AsrOutcome
import com.yap.app.domain.AsrUrls
import com.yap.app.domain.AudioVideoKind
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MediaTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次语音/视频转写请求。
 *
 * 刻意**不是** data class：data class 自动生成的 `toString()` 会把 `apiKey` 打出来，
 * 而这个对象会出现在日志、异常上下文、调试器里。这里显式覆盖成「有没有 Key」。
 *
 * [kind] 决定大小上限（音频 8 MiB / 视频 64 MiB，与发送上限同一套 [AudioVideoPolicy]）；
 * [mime] 决定 multipart 里的文件 mime 与扩展名。
 */
class AsrRequest(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val bytes: ByteArray,
    val kind: AudioVideoKind,
    val mime: String,
    val language: String = AsrDefaults.LANGUAGE,
    val timeoutSeconds: Long = AsrDefaults.TIMEOUT_SECONDS,
    val allowInsecureHttp: Boolean = false
) {
    override fun toString(): String =
        "AsrRequest(baseUrl=$baseUrl, model=$model, kind=$kind, mime=$mime, " +
            "bytes=${bytes.size}, hasKey=${apiKey.isNotBlank()})"
}

/**
 * ASR 客户端：OpenAI 兼容的 `POST {base}/audio/transcriptions`。
 *
 * 请求形状（`spec/` 没有单独契约，这里以 OpenAI 的 multipart 为准，DeepSeek 官方兼容）：
 * ```
 * POST {base}/audio/transcriptions
 * Authorization: Bearer <ASR Key>
 * Content-Type: multipart/form-data; boundary=…
 *
 * file      <二进制>  filename=voice.m4a
 * model     whisper-1
 * language  zh
 * ```
 *
 * 设计要点：
 * - **不抛异常**：[transcribe] 把一切失败收敛成 [AsrOutcome]，聊天层不需要 try/catch，
 *   也就不会因为一次转写失败把语音消息卡住（需求：失败不阻断发送）；
 * - **60 秒总超时**：为这一条请求单独派生一个 OkHttpClient（callTimeout），
 *   不污染全局客户端的长超时；测试直接断言派生客户端的 `callTimeoutMillis`；
 * - **Key 不进日志**：日志只有端点（去掉 query）、状态码、抹掉 Key 后的错误体摘要；
 *   错误体最多留 [AsrDefaults.MAX_LOGGED_BODY_CHARS] 字符；
 * - **不解析出文本就是失败**：空 `text` 归到 [AsrFailure.EMPTY_TRANSCRIPT]，绝不返回空串。
 */
@Singleton
class AsrClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    /** 哪些 mime 可以转写 + multipart 里的文件名。与发送白名单同族，另加探测用的 wav。 */
    internal object MimeNames {
        private val names = mapOf(
            "audio/m4a" to "voice.m4a",
            "audio/mp4" to "voice.m4a",
            "audio/mpeg" to "voice.mp3",
            "audio/mp3" to "voice.mp3",
            "audio/wav" to "probe.wav",
            "audio/x-wav" to "probe.wav",
            "video/mp4" to "video.mp4",
            "video/quicktime" to "video.mov"
        )

        fun filename(mime: String?): String? = names[MediaTypes.canonicalMime(mime)]

        fun mediaType(mime: String?): okhttp3.MediaType? =
            MediaTypes.canonicalMime(mime)?.let { runCatching { it.toMediaType() }.getOrNull() }
    }

    /**
     * 调一次转写。
     *
     * 调用方（[com.yap.app.data.repo.AsrTranscriber]）已经做过「开没开、有没有 Key」的判断；
     * 这里再判一次是防御：任何人直接 new 一个请求都不该带着空 Key 发出去。
     */
    suspend fun transcribe(request: AsrRequest): AsrOutcome = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey.trim()
        if (apiKey.isEmpty()) return@withContext AsrOutcome.Skipped(AsrFailure.KEY_MISSING)
        if (request.bytes.isEmpty()) return@withContext AsrOutcome.Skipped(AsrFailure.EMPTY_MEDIA)
        val limit = maxBytes(request.kind)
        if (request.bytes.size.toLong() > limit) return@withContext AsrOutcome.Skipped(AsrFailure.TOO_LARGE)
        val filename = MimeNames.filename(request.mime)
            ?: return@withContext AsrOutcome.Skipped(AsrFailure.UNSUPPORTED_FORMAT)
        val mediaType = MimeNames.mediaType(request.mime)
            ?: return@withContext AsrOutcome.Skipped(AsrFailure.UNSUPPORTED_FORMAT)

        val endpoint = try {
            NetworkGuard.parseHttpUrl(AsrUrls.endpoint(request.baseUrl), request.allowInsecureHttp)
        } catch (error: ApiException) {
            return@withContext AsrOutcome.Failed(AsrFailure.INVALID_URL, detail = error.kind.toString())
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, request.bytes.toRequestBody(mediaType))
            .addFormDataPart("model", AsrModels.normalize(request.model))
            .addFormDataPart("language", request.language.ifBlank { AsrDefaults.LANGUAGE })
            .build()
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(multipart)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            clientFor(request.timeoutSeconds).newCall(httpRequest).execute().use { response ->
                val body = readBounded(response, AsrDefaults.MAX_RESPONSE_BYTES)
                    ?: return@withContext AsrOutcome.Failed(
                        AsrFailure.RESPONSE_TOO_LARGE,
                        httpStatus = response.code
                    )
                if (!response.isSuccessful) {
                    logFailure(response.code, endpoint.toString(), body, apiKey)
                    return@withContext AsrOutcome.Failed(
                        reason = failureForStatus(response.code),
                        httpStatus = response.code,
                        detail = scrub(body.take(AsrDefaults.MAX_LOGGED_BODY_CHARS), apiKey)
                    )
                }
                when (val parsed = parseText(body)) {
                    is AsrParseResult.Text -> AsrOutcome.Transcribed(parsed.value)
                    AsrParseResult.Missing ->
                        AsrOutcome.Failed(AsrFailure.EMPTY_TRANSCRIPT, httpStatus = response.code)
                    AsrParseResult.Unparsable ->
                        AsrOutcome.Failed(AsrFailure.UNPARSABLE, httpStatus = response.code)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (timeout: SocketTimeoutException) {
            AsrOutcome.Failed(AsrFailure.TIMEOUT, detail = "timeout ${request.timeoutSeconds}s")
        } catch (unknownHost: UnknownHostException) {
            AsrOutcome.Failed(AsrFailure.NETWORK, detail = "unknown host")
        } catch (io: IOException) {
            AsrOutcome.Failed(AsrFailure.NETWORK, detail = io.javaClass.simpleName)
        } catch (error: Exception) {
            // 兜底：未知异常不能把聊天链路炸掉。message 只可能带 URL，仍统一过一遍 scrub。
            AsrOutcome.Failed(
                AsrFailure.NETWORK,
                detail = scrub(error.message.orEmpty(), apiKey).take(AsrDefaults.MAX_LOGGED_BODY_CHARS)
            )
        }
    }

    /** 这一条请求专用的客户端：总超时 60 秒（默认），连接/读写跟着收紧。 */
    internal fun clientFor(timeoutSeconds: Long): OkHttpClient {
        val seconds = timeoutSeconds.coerceIn(1L, AsrDefaults.TIMEOUT_SECONDS)
        return client.newBuilder()
            .callTimeout(seconds, TimeUnit.SECONDS)
            .connectTimeout(minOf(seconds, CONNECT_TIMEOUT_SECONDS), TimeUnit.SECONDS)
            .readTimeout(seconds, TimeUnit.SECONDS)
            .writeTimeout(seconds, TimeUnit.SECONDS)
            .build()
    }

    /** 大小上限：音频/视频各用发送链路那条线。 */
    internal fun maxBytes(kind: AudioVideoKind): Long = when (kind) {
        AudioVideoKind.AUDIO -> AudioVideoPolicy.AUDIO_MAX_BYTES
        AudioVideoKind.VIDEO -> AudioVideoPolicy.VIDEO_MAX_BYTES
    }

    internal sealed interface AsrParseResult {
        data class Text(val value: String) : AsrParseResult

        /** JSON 读懂了，但没有 text（或只有空白）。 */
        data object Missing : AsrParseResult

        /** 根本不是 JSON。 */
        data object Unparsable : AsrParseResult
    }

    /**
     * 解析响应里的 `text`。
     *
     * 兼容几种常见形状：`{"text":"…"}`、`{"transcript":"…"}`、`{"data":{"text":"…"}}`。
     * 只认字符串；数字/对象/null 都按「没有 text」处理。
     */
    internal fun parseText(body: String): AsrParseResult {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return AsrParseResult.Unparsable
        val root = element as? JsonObject ?: return AsrParseResult.Unparsable
        val value = root["text"].asText()
            ?: root["transcript"].asText()
            ?: (root["data"] as? JsonObject)?.get("text").asText()
            ?: return AsrParseResult.Missing
        val text = value.trim()
        return if (text.isEmpty()) AsrParseResult.Missing else AsrParseResult.Text(text)
    }

    /** 错误响应的状态码 → 结构化原因（界面再翻成人话）。 */
    internal fun failureForStatus(status: Int): AsrFailure = when {
        status == 401 || status == 403 -> AsrFailure.UNAUTHORIZED
        status == 429 -> AsrFailure.RATE_LIMITED
        status == 413 -> AsrFailure.TOO_LARGE
        status == 408 -> AsrFailure.TIMEOUT
        status in 400..499 -> AsrFailure.BAD_REQUEST
        status >= 500 -> AsrFailure.SERVER_ERROR
        else -> AsrFailure.BAD_REQUEST
    }

    /**
     * 日志用的端点：去掉 query/fragment（有人会把 `?api_key=…` 拼进 base）。
     *
     * 解析不了就退化成「问号之前」，保证不会把整段带密钥的串打出去。
     */
    internal fun redactUrl(raw: String): String = runCatching {
        val url = raw.toHttpUrl()
        url.newBuilder().query(null).fragment(null).build().toString()
    }.getOrDefault(raw.substringBefore('?'))

    /** 错误体里的 Key 抹成 `***`（服务端有时会把请求头回显在错误里）。 */
    internal fun scrub(text: String, apiKey: String): String {
        if (apiKey.isEmpty()) return text
        return text.replace(apiKey, "***")
    }

    private fun logFailure(status: Int, endpoint: String, body: String, apiKey: String) {
        val preview = scrub(body.take(AsrDefaults.MAX_LOGGED_BODY_CHARS), apiKey)
            .replace('\n', ' ')
            .replace('\r', ' ')
        // 只打状态码、脱敏端点与错误体摘要；**绝不**打请求头（Bearer Key 在里面）。
        println("[asr] status=$status endpoint=${redactUrl(endpoint)} body=$preview")
    }

    /** 读最多 [limit] 字节；声明长度超限或实际读到超限都返回 null。 */
    private fun readBounded(response: Response, limit: Int): String? {
        if (response.body?.contentLength()?.let { it > limit } == true) return null
        val stream = response.body?.byteStream() ?: return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    internal companion object {
        /** 单条转写请求的总超时（需求：60 秒）。 */
        const val TIMEOUT_SECONDS: Long = AsrDefaults.TIMEOUT_SECONDS

        /** 连接最多等 20 秒：连不上就不该把 60 秒全花在握手。 */
        const val CONNECT_TIMEOUT_SECONDS = 20L

        const val USER_AGENT = "Yap-Android"
        /** 便于测试断言「默认超时确实是 60 秒」。 */
        fun defaultTimeoutSeconds(): Long = TIMEOUT_SECONDS
    }
}
