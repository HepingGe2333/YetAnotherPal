package com.yap.agentd.llm

/**
 * 服务端 LLM 能力的最小模型集合（`spec/api-defaults/README.md` + `docs/design/server-authoritative.md` §3.3）。
 *
 * 这里**不发明新的产品语义**：请求参数、错误分类、用量形状都与客户端 `OpenAiAdapter` 一致，
 * 只是把传输换成 JDK 自带的 [java.net.http.HttpClient]（`:agentd` 的硬约束是零第三方依赖）。
 *
 * 三条设计取舍：
 * 1. **配置与传输分开**：[LlmConfig] 只描述「用哪个地址/模型/key」，谁提供它（客户端推送的
 *    `api_configs.json` 还是服务端 `--llm-*`）由 [LlmConfigResolver] 决定；
 * 2. **错误分类是契约**：HTTP 层要按 [LlmErrorKind.code] 给 SSE `event: error`，不能把
 *    401 说成「生成失败」——客户端/用户要能看出是 key 不对还是模型挂了；
 * 3. **不泄漏密钥**：任何错误文案都不包含 `Authorization` 头；[LlmConfig.redacted] 是唯一
 *    允许出现在日志里的配置描述。
 */

/** 模型请求的固定默认值（`spec/api-defaults/README.md`：temperature 0.75、max_tokens 16384）。 */
object LlmDefaults {
    const val TEMPERATURE: Double = 0.75

    /** 单次最大输出（`max_tokens`）；工具回路每轮同样。 */
    const val MAX_OUTPUT_TOKENS: Int = 16_384

    /** 上下文窗口上限（只作为预算上限；实际历史仍按条数与字数裁剪）。 */
    const val CONTEXT_WINDOW_TOKENS: Int = 1_000_000

    /** 聊天一次发给模型的历史条数上限（更早的靠事件/分页，不进本轮提示词）。 */
    const val MAX_HISTORY_MESSAGES: Int = 40

    /**
     * 主 LLM 会话的默认思考强度（0.16，`spec/tool-call-agents/README.md` §2）。
     *
     * 0.15 之前服务端从不发 `reasoning_effort`（对支持它的 provider 等于「不指定」）；
     * 0.16 起**每一条聊天请求**都显式带 `low`，模型调用 `think` 之后同一轮的后续请求升到
     * [REASONING_EFFORT_HIGH]。只有主聊天会话这样做（情绪判定/进化/主动消息/ASR 这些辅助调用
     * 走 [LlmClient.stream]/[LlmClient.complete]，不带这一格）。
     *
     * 「服务端配置可覆盖默认值」：`--reasoning-effort` / `YAP_LLM_REASONING_EFFORT` 写进
     * [LlmConfig.reasoningEffort]，此时默认值以配置为准（think 仍然把它升到 high）。
     */
    const val DEFAULT_REASONING_EFFORT = "low"

    /** 调用 `think` 之后同一轮后续请求的思考强度。 */
    const val REASONING_EFFORT_HIGH = "high"

    /** 允许写进请求体的取值（与 OpenAI 兼容 provider 的常见集合一致）。 */
    val ALLOWED_REASONING_EFFORTS: Set<String> = setOf("low", "medium", "high")

    /** 归一化一个配置值：去空白 + 小写；空串 = 没配（`null`）。不合法时抛 [IllegalArgumentException]。 */
    fun normalizeReasoningEffort(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        require(value in ALLOWED_REASONING_EFFORTS) {
            "reasoning_effort 只能是 ${ALLOWED_REASONING_EFFORTS.joinToString(" / ")}，收到 $value"
        }
        return value
    }
}

/**
 * 一条可用的 LLM 连接配置。
 *
 * @param baseUrl OpenAI 兼容根地址，例如 `https://api.deepseek.com/v1`（也接受已经带
 *   `/chat/completions` 的完整地址，见 [endpoint]）
 * @param model 模型名，例如 `deepseek-chat`
 * @param apiKey 明文 key；可以没有（本机 Ollama/one-api 之类的网关常常不要 key），
 *   有就发 `Authorization: Bearer <key>`，**绝不写日志/响应**
 * @param source 配置从哪来：本机部署来自客户端推送（[Source.CLIENT]），远程部署来自服务端
 *   启动参数/环境变量（[Source.SERVER]）。只用于日志与 `/v1/state` 之外的诊断，不参与鉴权
 */
data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val source: Source = Source.SERVER,
    val configId: String? = null,
    val temperature: Double = LlmDefaults.TEMPERATURE,
    val maxTokens: Int = LlmDefaults.MAX_OUTPUT_TOKENS,
    /**
     * 0.16：服务端配置的默认思考强度（`--reasoning-effort` / `YAP_LLM_REASONING_EFFORT`）。
     *
     * `null` = 没配，主聊天按 [LlmDefaults.DEFAULT_REASONING_EFFORT]（low）。只有
     * `LlmReplyGenerator` 会把它带进请求体；辅助调用（情绪/进化/主动消息）不带这一格。
     */
    val reasoningEffort: String? = null
) {

    enum class Source(val wireValue: String) {
        CLIENT("client"),
        SERVER("server")
    }

    /**
     * 实际的 `chat/completions` 地址。
     *
     * 客户端配置里的 `baseUrl` 是根地址（`.../v1`）；服务端 `--llm-base-url` 允许两种写法，
     * 以免部署者因为「少写/多写一段」得到一个 404。
     */
    fun endpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** 日志里能出现的配置描述（key 只说有没有）。 */
    fun redacted(): String {
        val key = if (apiKey.isNullOrBlank()) "无 key" else "key 已配置"
        return "${endpoint()} · 模型 $model · $key · 来源 ${source.wireValue}"
    }

    fun withGenerationParams(temperature: Double? = null, maxTokens: Int? = null): LlmConfig =
        copy(
            temperature = temperature ?: this.temperature,
            maxTokens = maxTokens ?: this.maxTokens
        )

    /** 0.16：带上服务端配置的默认思考强度（`null` = 不改，交给生成器用 low）。 */
    fun withReasoningEffort(effort: String?): LlmConfig =
        if (effort == null) this else copy(reasoningEffort = effort)
}

/**
 * 模型要求的一次工具调用（OpenAI function calling）。
 *
 * [arguments] 保留**原始 JSON 文本**：模型经常给 `{"minutes":"30"}` 这种「数字写成字符串」
 * 的形状，先解析再序列化会把这类宽容处理藏进传输层；工具执行器自己用宽松解析。
 */
data class LlmToolCall(val id: String, val name: String, val arguments: String) {
    /** 宽松解析参数；坏 JSON 返回 null（执行器据此回一条「参数不是合法 JSON」的工具结果）。 */
    fun argumentsObject(): com.yap.app.core.json.JsonValue.Obj? {
        val text = arguments.trim().ifEmpty { "{}" }
        return runCatching {
            com.yap.app.core.json.Json.parse(text) as? com.yap.app.core.json.JsonValue.Obj
        }.getOrNull()
    }
}

/**
 * OpenAI 兼容的一条消息（`role` 可以是 system/user/assistant/tool）。
 *
 * 工具回路需要两种额外形状：
 * - assistant 消息带 `toolCalls`（模型说「我要调用这些工具」）；
 * - tool 消息带 `toolCallId` + 工具结果正文（把结果回灌给模型）。
 *
 * 这两个字段都有默认值，所以老调用方（`LlmMessage.system/user/assistant`）一行都不用改。
 */
data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    /**
     * 0.14.1：多模态内容（`spec/audio-video`）。空 = 只发 [content] 字符串（老路径不变）。
     *
     * 只有 `role=user` 用得到：`listen` 把音频变成 `input_audio`，`watch` 把关键帧变成多个
     * `image_url`，由 `LlmClient.buildPayload` 序列化成 OpenAI 的 content 数组。
     */
    val parts: List<LlmContentPart> = emptyList()
) {
    companion object {
        fun system(content: String): LlmMessage = LlmMessage("system", content)

        fun user(content: String): LlmMessage = LlmMessage("user", content)

        fun assistant(content: String, toolCalls: List<LlmToolCall> = emptyList()): LlmMessage =
            LlmMessage("assistant", content, toolCalls = toolCalls)

        /** 工具结果回灌（`role=tool`，必须带对应的 `tool_call_id`）。 */
        fun tool(content: String, toolCallId: String): LlmMessage =
            LlmMessage("tool", content, toolCallId = toolCallId)

        /** 带多模态内容的 user 消息（`listen`/`watch` 的工具结果回灌）。 */
        fun userWithParts(text: String, parts: List<LlmContentPart>): LlmMessage =
            LlmMessage("user", text, parts = parts)

        /** 历史消息的角色映射；`SYSTEM` 只可能来自导入数据，按 system 透传。 */
        fun fromStoreRole(role: String, content: String): LlmMessage = when (role.uppercase()) {
            "ASSISTANT" -> assistant(content)
            "SYSTEM" -> system(content)
            else -> user(content)
        }
    }
}

/**
 * 一条多模态内容（OpenAI content 数组里的一项）。
 *
 * 为什么用 sealed class 而不是直接拼 JSON：工具层只关心「是音频还是图」，
 * 具体字段名（`input_audio.data` / `image_url.url`）只有 `LlmClient` 知道，
 * 换 provider 时也只改那一处。
 */
sealed class LlmContentPart {
    /** 文字（通常是与媒体一起回灌的一句说明）。 */
    data class Text(val text: String) : LlmContentPart()

    /** 一张图：http(s) 链接，或 `data:image/jpeg;base64,…`（`watch` 的关键帧走后者）。 */
    data class ImageUrl(val url: String) : LlmContentPart()

    /** 一段音频：base64 原始字节 + provider 认的格式（`m4a` / `mp3` / `wav` / `ogg`）。 */
    data class InputAudio(val base64: String, val format: String) : LlmContentPart()
}

/** 一轮模型输出：文字 + 用量 + 它想调用的工具（没有工具时 `toolCalls` 为空）。 */
data class LlmTurn(
    val text: String,
    val usage: LlmUsage = LlmUsage.ZERO,
    val toolCalls: List<LlmToolCall> = emptyList()
)

/** 一次生成的 token 用量；服务端只累加，不猜（缺 usage 时按字数粗估，见 [estimate]）。 */
data class LlmUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {

    fun plus(other: LlmUsage): LlmUsage = LlmUsage(
        promptTokens + other.promptTokens,
        completionTokens + other.completionTokens
    )

    companion object {
        val ZERO = LlmUsage()

        /**
         * 粗估 token（中文约 1 字 ≈ 0.7 token，英文约 4 字符 ≈ 1 token）。
         *
         * 只在网关没报 usage 时兜底：宁可估得粗，也不要把「他陪你聊天」记成 0 花费。
         */
        fun estimate(text: String): Int {
            if (text.isBlank()) return 0
            var cjk = 0
            var other = 0
            text.forEach { ch ->
                if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) cjk++ else other++
            }
            return (cjk * 0.7 + other / 4.0).toInt().coerceAtLeast(1)
        }
    }
}

/** 一次非流式（内部收集流）生成的完整结果。 */
data class LlmCompletion(val text: String, val usage: LlmUsage)

/**
 * 失败分类。`code` 直接进 SSE `event: error` 的 `data.code`（以及进化/情绪端点的错误体），
 * 所以它是**对外契约**：改名字要同步 `agentd/README.md`。
 */
enum class LlmErrorKind(val code: String) {
    /** 没有任何可用配置（本机模式没有客户端推送的配置；远程模式没有 `--llm-*`）。 */
    NOT_CONFIGURED("llm_not_configured"),

    /** 配置存在但不完整（缺 baseUrl 或 model）。 */
    INVALID_CONFIG("llm_invalid_config"),

    /** 上游 401/403：key 无效或没有权限。 */
    UNAUTHORIZED("llm_unauthorized"),

    /** 上游 429：限流/额度。 */
    RATE_LIMITED("llm_rate_limited"),

    /** 上游 4xx/5xx 或流里返回 `error`。 */
    UPSTREAM("llm_upstream_error"),

    /** 连接失败/DNS/TLS 等网络问题。 */
    NETWORK("llm_network_error"),

    /** 超时。 */
    TIMEOUT("llm_timeout"),

    /** 200 但一个字都没生成。 */
    EMPTY_REPLY("llm_empty_reply"),

    /** 响应不是预期形状（不是 JSON、没有 choices）。 */
    INVALID_RESPONSE("llm_invalid_response")
}

/**
 * LLM 调用的可预期失败。
 *
 * [message] 是给人看的中文一句（不含 key、不含完整上游响应体）；[detail] 只进服务端 stderr。
 */
class LlmException(
    val kind: LlmErrorKind,
    message: String,
    val detail: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    val code: String get() = kind.code

    /** SSE/HTTP 错误体里的文案：先给结论，再给下一步（不暴露内部地址之外的细节）。 */
    fun userMessage(): String = when (kind) {
        LlmErrorKind.NOT_CONFIGURED -> messageText
        LlmErrorKind.INVALID_CONFIG -> messageText
        LlmErrorKind.UNAUTHORIZED -> "模型接口拒绝了这个 key（401/403）：请检查 API 配置里的密钥。"
        LlmErrorKind.RATE_LIMITED -> "模型接口限流了（429）：稍后再试，或换一个配置。"
        LlmErrorKind.UPSTREAM -> "模型接口返回错误：$messageText"
        LlmErrorKind.NETWORK -> "连不上模型接口：$messageText"
        LlmErrorKind.TIMEOUT -> "模型接口超时，没有生成出回复。"
        LlmErrorKind.EMPTY_REPLY -> "模型没有生成任何内容（空回复）。"
        LlmErrorKind.INVALID_RESPONSE -> "模型返回的内容无法解析：$messageText"
    }

    private val messageText: String get() = message.orEmpty().ifBlank { "模型调用失败" }

    companion object {
        fun notConfigured(message: String): LlmException =
            LlmException(LlmErrorKind.NOT_CONFIGURED, message)
    }
}
