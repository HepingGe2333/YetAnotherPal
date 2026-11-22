package com.yap.agentd.llm

/**
 * 服务端 LLM 能力的装配点：**解析配置 + 调客户端**。
 *
 * 为什么再包一层而不是让每个调用方自己拿 [LlmClient] + [LlmConfigResolver]：
 * 聊天、主动消息、情绪判定、人格进化四条路径必须走**同一份**配置解析与同一个
 * `HttpClient`（连接复用、超时一致），否则「本机用客户端配置、远程用服务端配置」这条规则
 * 会被抄四遍、迟早有一处走偏。
 *
 * 测试里注入真实的 [LlmClient]（指向假 HTTP 服务端）与一个构造好的 [LlmConfigResolver] 即可，
 * 不需要 mock 框架。
 */
class LlmAccess(
    val client: LlmClient = LlmClient(),
    val resolver: LlmConfigResolver
) {

    /** 解析一份配置；没有/不完整时抛 [LlmException]（聊天路径把它变成 SSE `error`）。 */
    fun config(): LlmConfig = resolver.resolve()

    /** 后台路径（主动消息/情绪衰减）用：没有配置就返回 null，安静跳过。 */
    fun configOrNull(): LlmConfig? = resolver.resolveOrNull()

    fun stream(config: LlmConfig, messages: List<LlmMessage>, onDelta: (String) -> Unit): LlmUsage =
        client.stream(config, messages, onDelta)

    /**
     * 带工具表的一次生成（工具回路用）：返回文字 + 用量 + 模型想调用的工具。
     *
     * [tools] 是序列化好的 OpenAI tools 数组（见 `tools/ServerToolCatalog.toOpenAiToolsJson`）。
     * [reasoningEffort] 只有主聊天会传（`low` 默认 / `think` 之后 `high`）；辅助路径不传 = 不发。
     */
    fun chat(
        config: LlmConfig,
        messages: List<LlmMessage>,
        tools: String?,
        onDelta: (String) -> Unit = {},
        reasoningEffort: String? = null
    ): LlmTurn = client.chat(config, messages, tools, onDelta, reasoningEffort = reasoningEffort)

    /**
     * 非流式语义的生成（内部仍走流式客户端，只是收集起来）。
     *
     * @param temperature/maxTokens 覆盖默认值：进化评估要稳（0.3 / 900），主动消息要短
     */
    fun complete(
        config: LlmConfig,
        messages: List<LlmMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null
    ): LlmCompletion = client.complete(config.withGenerationParams(temperature, maxTokens), messages)
}
