package com.yap.app.network

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.domain.ApiDefaults
import com.yap.app.domain.NetworkErrorKind
import com.yap.app.domain.UserError
import com.yap.app.domain.debugText
import com.yap.app.data.local.ProviderType

/**
 * 一段要随请求发出的语音（0.13）。
 *
 * [base64] 是裸编码（OpenAI 兼容的 `input_audio.input_audio.data` 就长这样），
 * [format] 是 provider 认的短名（`m4a` / `mp3` / `wav`）。
 * 只有「这一轮用户发的语音」和 `listen` 工具结果会带它：历史里永远不重复 base64。
 */
data class WireAudio(
    val base64: String,
    val format: String
)

data class WireMessage(
    val role: String,
    val content: String,
    /** 已经编码好的 data URL（base64），非空时以多模态 content 数组发送。 */
    val images: List<String> = emptyList(),
    /** 这一轮要一起发出去的语音（`input_audio`）；历史消息里恒为空。 */
    val audios: List<WireAudio> = emptyList()
) {
    /** 这条消息是否带任何多模态内容（决定 content 用字符串还是数组）。 */
    val hasMedia: Boolean get() = images.isNotEmpty() || audios.isNotEmpty()
}

data class InferenceParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    /**
     * 0.16：思考强度（OpenAI 兼容的 `reasoning_effort`），取值见
     * [com.yap.app.domain.ReasoningEffort]。
     *
     * null = 这一路请求不带这个字段（ASR/搜索/摘要等辅助调用保持原样）；
     * 只有主聊天会话会显式给 low / high（`spec/tool-call-agents/README.md` §2）。
     */
    val reasoningEffort: String? = null
)

/**
 * 这个配置实际要发出去的推理参数。
 *
 * 配置里**显式填了**就用配置（用户能覆盖），没填就用应用默认值：
 * `temperature=0.75`、`max_tokens=16384`、`top_p=1.0`、频率/存在惩罚 0
 * （`spec/api-defaults/README.md`）。默认值写在 [ApiDefaults] 一处，聊天、主动消息、
 * 工具回路每一轮都走这里，不会出现「第一轮是 0.75、第二轮忘了带」。
 *
 * @param reasoningEffort 0.16：思考强度。**默认 null**（辅助调用不发这个字段）；
 *        主聊天会话由 `ChatRepository` 显式传 `low`，调用过 `think` 的那一轮后续请求传 `high`。
 */
fun ApiConfigEntity.inferenceParams(reasoningEffort: String? = null): InferenceParams = InferenceParams(
    temperature = temperature ?: ApiDefaults.TEMPERATURE,
    topP = topP ?: ApiDefaults.TOP_P,
    maxOutputTokens = maxOutputTokens ?: ApiDefaults.MAX_OUTPUT_TOKENS,
    frequencyPenalty = frequencyPenalty ?: ApiDefaults.FREQUENCY_PENALTY,
    presencePenalty = presencePenalty ?: ApiDefaults.PRESENCE_PENALTY,
    // 坏值不发送：某个网关把不认识的档位当 400 时，整轮聊天都会失败。
    reasoningEffort = com.yap.app.domain.ReasoningEffort.sanitize(reasoningEffort)
)

data class ProviderCapabilities(
    val available: Boolean,
    val supportsTemperature: Boolean,
    val supportsTopP: Boolean,
    val supportsMaxTokens: Boolean,
    val supportsFrequencyPenalty: Boolean,
    val supportsPresencePenalty: Boolean
)

sealed class StreamEvent {
    data class Text(val value: String) : StreamEvent()
    data object Done : StreamEvent()
}

/**
 * 连接测试的结果。
 *
 * 0.10.1 起不再带一句现成的中文：成功时只给「发现几个模型」，失败由调用方从
 * [ApiException.kind] 映射资源；界面上不会再读到数据层拼的句子。
 */
data class ConnectionTestResult(
    val success: Boolean = true,
    val modelCount: Int = 0,
    val models: List<String> = emptyList()
)

/**
 * 网络层错误。
 *
 * 只装结构化原因 [kind]（见 `domain/UserError.kt`）与服务端原始 [detail]；
 * [message] 是给日志/调试看的中文，**界面不得直接显示**（UI 走 `ErrorText` 映射资源）。
 */
class ApiException(
    val kind: NetworkErrorKind,
    val detail: String? = null
) : Exception(UserError.Network(kind).debugText())

/** 服务商适配还没做（Gemini / Claude 预留）：等价于一个网络层错误。 */
class ProviderUnavailableException(
    val provider: String? = null
) : Exception(UserError.Network(NetworkErrorKind.ProviderUnavailable(provider)).debugText())

data class ProviderUiInfo(
    val type: ProviderType,
    val capabilities: ProviderCapabilities
)
