package com.yap.app.network

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ProviderType
import com.yap.app.domain.ApiCost
import com.yap.app.domain.LlmLane
import com.yap.app.domain.NetworkErrorKind
import com.yap.app.domain.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * chat.completions 请求体的唯一构造处。
 *
 * 抽成 `internal` 顶层函数只为一件事：**请求体里的参数可以被单测直接断言**。
 * 以前温度/最大输出是在这里 `?.let { put(...) }` 拼的，null 就整段不出现，
 * 于是「应用默认到底发的是什么」只能靠读代码猜；现在默认值来自 `ApiDefaults`，
 * 这里只负责如实写进 JSON（`spec/api-defaults/README.md` 的验收项）。
 */
internal fun buildChatCompletionPayload(
    model: String,
    messages: List<WireMessage>,
    params: InferenceParams,
    stream: Boolean
): JsonObject = buildJsonObject {
    put("model", model)
    put("messages", buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("role", message.role)
                if (!message.hasMedia) {
                    put("content", message.content)
                } else {
                    put("content", buildJsonArray {
                        if (message.content.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        }
                        // 图片/视频帧走 OpenAI 兼容的 image_url（视频也是「抽出来的几张图」）。
                        message.images.forEach { dataUrl ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", dataUrl) })
                            })
                        }
                        // 语音走 input_audio：base64 + format（`spec/audio-video/README.md`）。
                        // 不支持音频的模型会在服务端回 4xx，由聊天层明确报「这个模型听不了语音」，
                        // 这里绝不退化成「自己编一段转写」。
                        message.audios.forEach { audio ->
                            add(buildJsonObject {
                                put("type", "input_audio")
                                put("input_audio", buildJsonObject {
                                    put("data", audio.base64)
                                    put("format", audio.format)
                                })
                            })
                        }
                    })
                }
            })
        }
    })
    put("stream", stream)
    if (stream) {
        // 让服务端在最后一个分片里带上 usage；不支持的网关会忽略这个字段。
        put("stream_options", buildJsonObject { put("include_usage", true) })
    }
    params.temperature?.let { put("temperature", it) }
    params.topP?.let { put("top_p", it) }
    params.maxOutputTokens?.let { put("max_tokens", it) }
    params.frequencyPenalty?.let { put("frequency_penalty", it) }
    params.presencePenalty?.let { put("presence_penalty", it) }
    // 0.16：思考强度（`spec/tool-call-agents/README.md` §2）。主聊天会话默认 low，
    // 调用过 think 之后的后续请求是 high；辅助调用不传，请求体里就不出现这个字段。
    params.reasoningEffort?.let { put("reasoning_effort", it) }
}

class OpenAiAdapter(
    private val client: OkHttpClient,
    private val json: Json,
    /**
     * 记账 + 全局并发闸。
     *
     * 两者都由 AppModule 显式传入，不走静态句柄：记账漏一笔、闸门漏接一个调用点，
     * 都是那种「不报错但产品慢慢变形」的问题，依赖注入能在编译期把它们钉住。
     */
    private val reporting: ApiUsageReporter,
    private val gatekeeper: LlmGatekeeper
) : AiProviderAdapter {

    /** 一次完整回复：要发给用户看的文字 + 这次花掉的 tokens。 */
    private data class CompletionPayload(val text: String, val usage: TokenUsage)

    override val provider: ProviderType = ProviderType.OPENAI

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        available = true,
        supportsTemperature = true,
        supportsTopP = true,
        supportsMaxTokens = true,
        supportsFrequencyPenalty = true,
        supportsPresencePenalty = true
    )

    override suspend fun fetchModels(config: ApiConfigEntity, apiKey: String?): List<String> {
        val url = NetworkGuard.parseHttpUrl(
            NetworkGuard.endpoint(config.baseUrl, "models").toString(),
            config.allowInsecureHttp
        )
        val request = Request.Builder()
            .url(url)
            .get()
            .applyHeaders(config, apiKey)
            .build()
        return withContext(Dispatchers.IO) {
            // 连接失败也要变成结构化错误：否则「连接测试」失败会落到「未知错误」，
            // 用户看不出是地址不对、断网，还是服务端的问题。
            val response = try {
                client.newCall(request).execute()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                throw mapNetworkError(error)
            }
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw resp.toApiException(body)
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: throw ApiException(NetworkErrorKind.UnparsableModels, body.take(300))
                val entries = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: JsonArray(emptyList())
                entries.mapNotNull { element ->
                    element.runCatching { jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }.filter { it.isNotBlank() }.distinct().sorted()
            }
        }
    }

    override fun streamChat(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        params: InferenceParams,
        extraHeaders: Map<String, String>,
        kind: String,
        lane: LlmLane,
        sessionId: String?,
        personaId: String?
    ): Flow<StreamEvent> = flow {
        // 整个流都包在闸门里：首字之前拿位，流结束（或取消）才归还。
        // 不能只包「建立连接」那一下，否则生成过程本身不受并发约束，后台一多照样卡聊天。
        gatekeeper.withPermit(lane) {
            streamChatInner(config, apiKey, model, messages, params, extraHeaders, kind, sessionId, personaId)
                .collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    private fun streamChatInner(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        params: InferenceParams,
        extraHeaders: Map<String, String>,
        kind: String,
        sessionId: String?,
        personaId: String?
    ): Flow<StreamEvent> = flow {
        var attempts = 0
        var emittedAny = false
        while (true) {
            var emittedThisAttempt = false
            // 有的服务端会把 usage 放在最后一条（choices 为空的）分片里；有就记一笔，没有就算了。
            var streamUsage: TokenUsage? = null
            // 这一轮实际发出的文字量：没有 usage 时用它估算 completion tokens。
            val collector = StringBuilder()
            val call = client.newCall(buildRequest(config, apiKey, model, messages, params, stream = true, extraHeaders = extraHeaders))
            val completionHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                val response = withContext(Dispatchers.IO) { call.execute() }
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        throw resp.toApiException(body)
                    }
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    if (!contentType.contains("text/event-stream")) {
                        // A few gateways ignore stream=true and return a full completion.
                        val body = resp.body?.string().orEmpty()
                        val payload = parseCompletion(body, resp.code)
                        streamUsage = payload.usage
                        emit(StreamEvent.Text(payload.text))
                        emit(StreamEvent.Done)
                        reportStreamUsage(kind, model, streamUsage, sessionId, personaId)
                        return@flow
                    }
                    val source = resp.body?.source() ?: throw ApiException(NetworkErrorKind.NoContent)
                    source.use {
                        while (currentCoroutineContext().isActive) {
                            // 单行长度上限：服务端异常时可能吐出无穷无尽的「一行」，不能让它吃掉内存。
                            val line = SseLines.readLine(it) ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            if (payload == "[DONE]") break
                            val chunk = parseStreamChunk(payload, resp.code)
                            val event = chunk?.text
                            if (!event.isNullOrEmpty()) {
                                emittedThisAttempt = true
                                emittedAny = true
                                collector.append(event)
                                emit(StreamEvent.Text(event))
                            }
                            chunk?.usage?.let { usage ->
                                if (usage.promptTokens > 0 || usage.completionTokens > 0) streamUsage = usage
                            }
                        }
                    }
                    if (!emittedThisAttempt) {
                        throw ApiException(NetworkErrorKind.EmptyStream)
                    }
                    emit(StreamEvent.Done)
                    // 服务端没给 usage 时按字数估一个：聊天的钱通常是最大头，不能记成 0。
                    // 服务端没给 usage 时按字数估：多模态 parts（图片/语音 base64）按字符数/4 计入，
                    // 否则「发了段语音」这一轮的花费会被记成只有几个字。
                    val mediaChars = messages.sumOf { message ->
                        message.images.sumOf { it.length } + message.audios.sumOf { it.base64.length }
                    }
                    reportStreamUsage(
                        kind,
                        model,
                        streamUsage ?: TokenUsage(
                            promptTokens = estimateTokens(
                                messages.joinToString("") { it.content.orEmpty() }
                            ) + mediaChars / 4,
                            completionTokens = estimateTokens(collector.toString())
                        ),
                        sessionId,
                        personaId
                    )
                    return@flow
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val retryable = isRetryable(error) && !emittedAny && attempts < MAX_RETRIES
                if (!retryable) throw mapNetworkError(error)
                attempts++
                delay(500L * attempts)
            } finally {
                completionHandle.dispose()
                call.cancel()
            }
        }
    }

    override suspend fun complete(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        params: InferenceParams,
        extraHeaders: Map<String, String>,
        kind: String,
        lane: LlmLane,
        sessionId: String?,
        personaId: String?
    ): String = gatekeeper.withPermit(lane) {
        withContext(Dispatchers.IO) {
            var attempts = 0
            while (true) {
                try {
                    val request = buildRequest(
                        config, apiKey, model, messages, params, stream = false, extraHeaders = extraHeaders
                    )
                    val payload = withTimeout(180_000) {
                        client.newCall(request).execute().use { response ->
                            val raw = response.body?.string().orEmpty()
                            if (!response.isSuccessful) throw response.toApiException(raw)
                            parseCompletion(raw, response.code)
                        }
                    }
                    // 记账在返回之前做：这一笔花掉了就是花掉了，哪怕之后调用方处理失败。
                    reporting.record(kind, model, payload.usage, sessionId, personaId)
                    return@withContext payload.text
                } catch (cancel: CancellationException) {
                    if (cancel is kotlinx.coroutines.TimeoutCancellationException) {
                        throw ApiException(NetworkErrorKind.Timeout)
                    }
                    throw cancel
                } catch (error: Exception) {
                    if (!isRetryable(error) || attempts >= MAX_RETRIES) throw mapNetworkError(error)
                    attempts++
                    delay(700L * attempts)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    /** 流式回复里只有服务端真的报了 usage 才记账，免得把「调用次数」虚增。 */
    /**
     * 粗略估算 token：中文约 1 字 ≈ 0.7 token，英文约 4 字符 ≈ 1 token。
     *
     * 只在服务端不给 usage 时兜底用——宁可估得粗，也不要让「他陪你聊天」的花费记成 0。
     */
    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { ch -> if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) cjk++ else other++ }
        return (cjk * 0.7 + other / 4.0).toInt().coerceAtLeast(1)
    }

    private suspend fun reportStreamUsage(
        kind: String,
        model: String,
        usage: TokenUsage?,
        sessionId: String?,
        personaId: String?
    ) {
        val reported = usage ?: return
        if (reported.promptTokens <= 0 && reported.completionTokens <= 0) return
        reporting.record(kind, model, reported, sessionId, personaId)
    }

    private fun buildRequest(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        params: InferenceParams,
        stream: Boolean,
        extraHeaders: Map<String, String>
    ): Request {
        val url = NetworkGuard.parseHttpUrl(
            NetworkGuard.endpoint(config.baseUrl, "chat/completions").toString(),
            config.allowInsecureHttp
        )
        val requestBody = buildChatCompletionPayload(model, messages, params, stream)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .post(requestBody)
            .applyHeaders(config, apiKey, extraHeaders)
            .build()
    }

    private fun Request.Builder.applyHeaders(
        config: ApiConfigEntity,
        apiKey: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): Request.Builder {
        header("Content-Type", "application/json")
        header("Accept", "text/event-stream, application/json")
        var customHasAuthorization = false
        parseHeaders(config.customHeadersJson).forEach { (name, value) ->
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            // 保存时已经拦过保留头（Host / Content-Length / Transfer-Encoding / …），
            // 这里再滤一遍：老库里可能存着 0.10.2 之前写进去的值，历史配置也享受同样的保护。
            // 其余自定义头（X-*、HTTP-Referer、OpenAI-Organization…）一律保留。
            if (com.yap.app.domain.ApiConfigRules.isReservedHeader(name)) return@forEach
            if (name.equals("Authorization", ignoreCase = true)) customHasAuthorization = true
            header(name, value)
        }
        if (!apiKey.isNullOrBlank() && !customHasAuthorization) {
            header("Authorization", "Bearer $apiKey")
        }
        extraHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) header(name, value)
        }
        return this
    }

    /** 流式分片：这一片里的文字 + （可能有的）usage。 */
    private data class StreamChunk(val text: String?, val usage: TokenUsage?)

    private fun parseStreamChunk(payload: String, status: Int): StreamChunk? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        root["error"]?.let { error ->
            throw ApiException(NetworkErrorKind.StreamError(status), error.toString().take(500))
        }
        val usage = ApiCost.parseUsage(root).takeIf { it.promptTokens > 0 || it.completionTokens > 0 }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return StreamChunk(text = null, usage = usage)
        val delta = choice["delta"]?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
            ?: choice["text"]?.jsonPrimitive?.contentOrNull
            ?: choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return StreamChunk(text = content, usage = usage)
    }

    /**
     * 解析一次完整回复：文字 + usage。
     *
     * usage 走 [ApiCost.parseUsage]：OpenAI 兼容、Gemini、Claude 三种写法都能认，
     * 认不出来只当 0（记账差一笔，也不能让聊天失败）。
     */
    private fun parseCompletion(raw: String, status: Int): CompletionPayload {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw ApiException(NetworkErrorKind.UnparsableReply, raw.take(300))
        root["error"]?.let { error ->
            throw ApiException(NetworkErrorKind.Http(status), error.toString().take(500))
        }
        val usage = ApiCost.parseUsage(root)
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ApiException(NetworkErrorKind.EmptyReply, raw.take(300))
        val text = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: choice["text"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException(NetworkErrorKind.EmptyReply, raw.take(300))
        return CompletionPayload(text = text, usage = usage)
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.forEach { (key, value) ->
                val text = when (value) {
                    is JsonPrimitive -> value.contentOrNull
                    else -> value.toString()
                }
                if (key.isNotBlank() && !text.isNullOrBlank()) put(key, text)
            }
        }
    }

    private fun Response.toApiException(body: String): ApiException {
        val detail = extractErrorMessage(body, "").takeIf { it.isNotBlank() }
        return ApiException(NetworkErrorKind.Http(code), detail ?: body.take(1200))
    }

    private fun extractErrorMessage(body: String, fallback: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = root?.get("error")?.let { error ->
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        } ?: root?.get("message")?.jsonPrimitive?.contentOrNull
        return message?.take(400) ?: fallback
    }

    private fun isRetryable(error: Exception): Boolean = when (error) {
        is ApiException -> error.kind.statusCode.let { code ->
            code == null || code == 408 || code == 429 || code in 500..599
        }
        is IOException -> true
        else -> false
    }

    private fun mapNetworkError(error: Exception): Exception = when (error) {
        is ApiException, is ProviderUnavailableException -> error
        is java.net.SocketTimeoutException -> ApiException(NetworkErrorKind.Timeout)
        is java.net.UnknownHostException -> ApiException(NetworkErrorKind.UnknownHost)
        is IOException -> ApiException(NetworkErrorKind.NetworkInterrupted, error.message)
        else -> error
    }

    private companion object {
        const val MAX_RETRIES = 2

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
