package com.yap.app.domain

/**
 * 0.16 的思考强度（`spec/tool-call-agents/README.md` §2）。
 *
 * 规则只有两条：
 * 1. 主 LLM 会话**默认 `low`**（此前没有这个字段，等于让模型自己决定想多久）；
 * 2. 模型调用过 `think` 之后，**这一轮的后续请求**升到 `high`——只有真的想清楚了才值得多花；
 *    think 的结果作为工具结果回灌，这一轮结束（下一轮重新从 low 开始）自动恢复。
 *
 * 参数形状是 OpenAI 兼容的 `reasoning_effort`；不认识它的网关会忽略这个字段，不会报错。
 * 只有主聊天会话用它：ASR / 搜索 / 摘要这些辅助调用各自有自己的参数，不跟着聊天变。
 */
object ReasoningEffort {

    /** 默认值：日常闲聊、短回复都不需要深想。 */
    const val LOW = "low"

    /** 调用过 `think` 之后这一轮剩余请求用的值。 */
    const val HIGH = "high"

    /** 规范写的默认值就是 [LOW]。 */
    const val DEFAULT = LOW

    /** 网关认的三个档位（只用于校验外部输入，不参与选择）。 */
    val ALLOWED: Set<String> = setOf(LOW, "medium", HIGH)

    /**
     * 这一轮下一次请求该用哪个档位。
     *
     * @param thinkCalledThisTurn 这一轮（不是上一轮）有没有成功调用过 `think`
     */
    fun forRequest(thinkCalledThisTurn: Boolean): String = if (thinkCalledThisTurn) HIGH else DEFAULT

    /**
     * 单测/日志用的合法性判断：不认识的档位不发送，免得某个网关把整个请求判成 400。
     */
    fun isValid(value: String?): Boolean = value != null && value.trim().lowercase() in ALLOWED

    /** 只接受合法值：坏值退成 null（请求体里就不出现这个字段）。 */
    fun sanitize(value: String?): String? {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return normalized.takeIf { isValid(it) }
    }
}
