package com.yap.app.network

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ProviderType
import com.yap.app.domain.ReasoningEffort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `reasoning_effort` 真的进了请求体（`spec/tool-call-agents/README.md` §2）。
 *
 * 断言的是**发送出去的 JSON**：
 * - 主聊天会话：默认 `low`，think 之后 `high`；
 * - 辅助调用（ASR / 摘要 / 主动消息…）走同一个 `inferenceParams()` 时不带这个字段，
 *   所以老网关不会因为多了个不认识的参数而报错。
 */
class ReasoningEffortPayloadTest {

    private fun config() = ApiConfigEntity(
        id = "cfg",
        name = "默认配置",
        provider = ProviderType.OPENAI,
        baseUrl = "https://api.deepseek.com",
        apiKeyCipher = null,
        model = "deepseek-chat",
        customHeadersJson = "{}",
        allowInsecureHttp = false,
        temperature = null,
        topP = null,
        maxOutputTokens = null,
        frequencyPenalty = null,
        presencePenalty = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun payload(params: InferenceParams): JsonObject = buildChatCompletionPayload(
        model = "deepseek-chat",
        messages = listOf(WireMessage("system", "你是阿深。"), WireMessage("user", "在干嘛")),
        params = params,
        stream = false
    )

    private fun effortOf(body: JsonObject): String? =
        body["reasoning_effort"]?.jsonPrimitive?.contentOrNull

    @Test
    fun `主聊天默认 low`() {
        val body = payload(config().inferenceParams(reasoningEffort = ReasoningEffort.DEFAULT))
        assertEquals("low", effortOf(body))
        // 默认值与契约同源：不是网络层又写了一个 "low"。
        assertEquals(ReasoningEffort.LOW, effortOf(body))
    }

    @Test
    fun `调用 think 之后这一轮后续请求是 high`() {
        val afterThink = payload(config().inferenceParams(reasoningEffort = ReasoningEffort.forRequest(true)))
        assertEquals("high", effortOf(afterThink))
        val nextTurn = payload(config().inferenceParams(reasoningEffort = ReasoningEffort.forRequest(false)))
        assertEquals("low", effortOf(nextTurn))
    }

    @Test
    fun `辅助调用不带这个字段`() {
        val body = payload(config().inferenceParams())
        assertFalse("没有思考强度的请求不应该多一个字段", body.containsKey("reasoning_effort"))
    }

    @Test
    fun `坏档位不会把请求变成 400`() {
        val body = payload(config().inferenceParams(reasoningEffort = "off"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertTrue("其余参数照旧", body.containsKey("temperature"))
    }
}
