package com.yap.agentd.llm

import com.yap.agentd.store.FileStore

/**
 * 服务端到底用哪一份 LLM 配置（0.13 新增）。
 *
 * ### 优先级（唯一规则，测试与 README 都按它）
 *
 * | 部署形态 | 配置来源 | 客户端推送的配置 |
 * | --- | --- | --- |
 * | **本机部署**（监听 `127.0.0.1` / `::1` / `localhost`） | 先用客户端推送的 `api_configs.json` + `secrets.json`；一个都没有时回落到服务端 `--llm-*` | **允许** |
 * | **远程部署**（监听 `0.0.0.0` / 具体网卡地址） | 只用服务端 `--llm-base-url` / `--llm-key` / `--llm-model`（或 `YAP_LLM_*` 环境变量） | **不读**（不打开、不查询） |
 *
 * 为什么本机允许回落：用户在本地跑 `agentd`、客户端还没推进配置时，用环境变量临时指一个
 * 模型是正常运维动作；显式服务端配置永远比"什么都没有"更可信。
 *
 * 为什么远程**绝不读**客户端配置：远程服务端的密钥属于部署者；客户端（可能来自别的设备）
 * 推上来的配置相当于让调用方决定「服务端把请求发到哪、用谁的 key」——那是 SSRF + 密钥外带。
 * 所以 [remote] 路径下连 [FileStore.apiConfigs] 都不会被调用。
 *
 * ### 「哪一条客户端配置」
 *
 * 客户端可以存多条配置（不同 provider/模型）。服务端没有「人格绑定了哪条配置」的信息
 * （`/v1/personas` 的 `defaultApiConfigId` 是客户端的列，服务端按原样保留但没有解释），
 * 所以取**最近更新的一条可用配置**：`baseUrl` 与 `model` 都非空；key 可以没有
 * （本机 Ollama / one-api 常常不要 key）。排序按 `updatedAt` 降序，其次 `id`，保证可重现。
 */
class LlmConfigResolver(
    /** 服务端权威存储；`null` = 没启用状态存储，本机模式下就没有客户端配置可读。 */
    private val store: FileStore?,
    /** 服务端是否监听回环地址（由 `Main` 用启动参数判断，测试直接传）。 */
    private val loopback: Boolean,
    /** 服务端自己的配置（`--llm-*` / `YAP_LLM_*`）。任何一项为空都表示"没配"。 */
    private val server: ServerLlmConfig = ServerLlmConfig(),
    /** 环境变量读取器（与 `AgentdServices.env` 同一个可注入入口，测试不需要改进程环境）。 */
    private val env: (String) -> String? = { System.getenv(it) }
) {

    /** 启动日志/诊断用：这次是不是「本机部署」（决定读不读客户端推送的配置）。 */
    val isLoopbackDeployment: Boolean get() = loopback

    /**
     * 解析一份可用配置；没有就抛 [LlmException]（[LlmErrorKind.NOT_CONFIGURED]）。
     *
     * 错误文案要能直接指导下一步：本机模式提"让客户端推配置或加 --llm-*"，远程模式提
     * "远程部署不读客户端配置"。
     */
    fun resolve(): LlmConfig = resolveOrNull() ?: throw LlmException.notConfigured(notConfiguredMessage())

    /** 同 [resolve]，但没配置时返回 `null`（主动消息/情绪判定这类后台路径用：安静跳过）。 */
    fun resolveOrNull(): LlmConfig? {
        val base = if (loopback) {
            clientConfig() ?: serverConfigOrThrow()
        } else {
            serverConfigOrThrow()
        } ?: return null
        // 0.16：思考强度的默认值（`--reasoning-effort` / `YAP_LLM_REASONING_EFFORT`）是**服务端**
        // 配置，所以不管这次用的是客户端推送的配置还是服务端配置，都统一叠在解析结果上。
        // 只有主聊天会把它带进请求体（见 `LlmReplyGenerator`）；辅助调用不传。
        return base.withReasoningEffort(reasoningEffortOverride())
    }

    /**
     * 客户端推送的配置（只在本机模式用）。
     *
     * 即使不在本机模式，也留成公开方法：测试要能断言「远程模式确实没有读它」。
     */
    fun clientConfig(): LlmConfig? {
        val store = store ?: return null
        val candidates = store.apiConfigs()
            .filter { it.baseUrl.isNotBlank() && it.model.isNotBlank() }
            .sortedWith(compareByDescending<com.yap.agentd.store.ApiConfig> { it.updatedAt }.thenBy { it.id })
        val chosen = candidates.firstOrNull() ?: return null
        return LlmConfig(
            baseUrl = chosen.baseUrl.trim(),
            model = chosen.model.trim(),
            // key 只在服务端内部读；空 key 也允许（本机网关）。
            apiKey = store.apiKey(chosen.id)?.takeIf { it.isNotBlank() },
            source = LlmConfig.Source.CLIENT,
            configId = chosen.id
        )
    }

    /** 服务端配置：显式参数优先，其次环境变量；缺一半就报「配置不完整」而不是静默忽略。 */
    fun serverConfigOrThrow(): LlmConfig? {
        val baseUrl = server.baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: env(ENV_LLM_BASE_URL)?.trim()?.takeIf { it.isNotEmpty() }
        val model = server.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: env(ENV_LLM_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
        val apiKey = server.apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: env(ENV_LLM_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        if (baseUrl == null && model == null && apiKey == null) return null
        if (baseUrl == null || model == null) {
            val missing = buildList {
                if (baseUrl == null) add("--llm-base-url（YAP_LLM_BASE_URL）")
                if (model == null) add("--llm-model（YAP_LLM_MODEL）")
            }.joinToString("、")
            throw LlmException(
                LlmErrorKind.INVALID_CONFIG,
                "服务端 LLM 配置不完整：还缺 $missing"
            )
        }
        return LlmConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            source = LlmConfig.Source.SERVER
        )
    }

    /**
     * 0.16：服务端配置的默认思考强度；没配返回 `null`（主聊天用 `low`）。
     *
     * 与 LLM 地址/模型不同，这一格**不参与「配置是否可用」的判定**：只写了
     * `YAP_LLM_REASONING_EFFORT=high` 而没有地址/模型时，仍然算「没有 LLM 配置」。
     * 取值不合法时明确失败（[LlmErrorKind.INVALID_CONFIG]），而不是静默忽略。
     */
    fun reasoningEffortOverride(): String? {
        val raw = server.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }
            ?: env(ENV_LLM_REASONING_EFFORT)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return try {
            LlmDefaults.normalizeReasoningEffort(raw)
        } catch (invalid: IllegalArgumentException) {
            throw LlmException(
                LlmErrorKind.INVALID_CONFIG,
                "思考强度配置不合法（--reasoning-effort / $ENV_LLM_REASONING_EFFORT）：${invalid.message}"
            )
        }
    }

    private fun notConfiguredMessage(): String = if (loopback) {
        "服务端还没有可用的 LLM 配置：本机部署会优先用客户端推送的 api_configs.json + secrets.json，" +
            "目前一条可用的都没有；也可以给 agentd 加 --llm-base-url / --llm-key / --llm-model。"
    } else {
        "服务端还没有可用的 LLM 配置：远程部署不会读取客户端推送的配置，" +
            "请用 --llm-base-url / --llm-key / --llm-model（或 YAP_LLM_BASE_URL / YAP_LLM_KEY / YAP_LLM_MODEL）配置。"
    }

    companion object {
        const val ENV_LLM_BASE_URL = "YAP_LLM_BASE_URL"
        const val ENV_LLM_KEY = "YAP_LLM_KEY"
        const val ENV_LLM_MODEL = "YAP_LLM_MODEL"

        /** 0.16：服务端配置的默认思考强度（`low` / `medium` / `high`）。 */
        const val ENV_LLM_REASONING_EFFORT = "YAP_LLM_REASONING_EFFORT"

        /** 判断「本机部署」：与 `Main` 的监听地址安全提示用同一套回环判定。 */
        fun isLoopbackHost(host: String): Boolean {
            val value = host.trim().lowercase()
            if (value == "localhost" || value == "127.0.0.1" || value == "::1" || value == "[::1]") return true
            return value.startsWith("127.")
        }
    }
}

/** `--llm-*` 解析后的服务端配置（三个都可以空；缺 baseUrl/model 时由 [LlmConfigResolver] 报错）。 */
data class ServerLlmConfig(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    /**
     * 0.16：默认思考强度（`--reasoning-effort` / `YAP_LLM_REASONING_EFFORT`）。
     *
     * 它不是「配置是否可用」的一部分：只配这一格不算有 LLM。`null` = 用 `low`。
     */
    val reasoningEffort: String? = null
)
