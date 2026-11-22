package com.yap.agentd

import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.LlmDefaults
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.ServerLlmConfig
import com.yap.agentd.store.FileStore
import java.nio.file.Path

/**
 * `agentd` 的入口。
 *
 * 用法：
 * ```
 * ./gradlew :agentd:run --args="--port 8788"
 * # 或（distribution 装好之后）
 * agentd/build/install/agentd/bin/agentd --port 8788
 * ```
 *
 * 默认只监听 `127.0.0.1`：它是「嵌入式服务」，不是公网 API。
 * 真要部署成独立服务器时显式传 `--host 0.0.0.0`，并由部署方自己加 TLS/反向代理；
 * 0.11.1 起可以再叠一层可选的共享 token（`--token` 或环境变量 `YAP_AGENTD_TOKEN`），
 * 0.12.0 起再加一层按来源的令牌桶限流（`--rate-limit` / `--rate-burst`），
 * 但仍然**没有 TLS**——见 README 的「限流」与「部署与 TLS」两节。
 *
 * 0.12.0 的服务端权威模式把状态也搬到了服务端：`--data <目录>`（或 `YAP_AGENTD_DATA`）指向
 * 一个数据目录，默认 `./agentd-data`。**目录不存在/为空时自动播种** 3 个人格 + 1 个示例会话，
 * 已存在则原样读回。数据格式与原子写见 `store/FileStore.kt` 与 README 的「服务端权威模式」一节。
 *
 * 客户端（Android/iOS 远程模式）目前不发 `Authorization`，所以默认不设 token；
 * 设了 token 之后所有请求（含 `/health`）都必须带 `Authorization: Bearer <token>`。
 */
fun main(args: Array<String>) {
    val options = try {
        parseOptions(args)
    } catch (failure: IllegalArgumentException) {
        exitWith(failure.message ?: "参数不正确")
    }
    if (options.help) {
        printUsage()
        return
    }
    val rateLimiter = RateLimiter(options.rateLimitPerMinute, options.rateBurst)
    val dataDir = Path.of(options.dataDir).toAbsolutePath().normalize()
    val store = try {
        FileStore.open(dataDir)
    } catch (failure: Exception) {
        System.err.println("数据目录初始化失败（$dataDir）：${failure.message}")
        System.err.println("（目录里有不认识的版本/坏文件时不会静默重建；换一个空目录或用 --data 指定别的目录）")
        kotlin.system.exitProcess(1)
    }
    // LLM 配置的优先级见 `LlmConfigResolver`：本机部署用客户端推送的 api_configs.json + secrets.json；
    // 远程部署只用服务端自己的 --llm-* / YAP_LLM_*，**不读客户端配置**。
    val llmResolver = LlmConfigResolver(
        store = store,
        loopback = isLoopbackHost(options.host),
        server = ServerLlmConfig(
            baseUrl = options.llmBaseUrl,
            apiKey = options.llmKey,
            model = options.llmModel,
            // 0.16：主聊天的默认思考强度（think 之后同一轮升到 high；辅助调用不带）。
            reasoningEffort = options.reasoningEffort
        )
    )
    val llm = LlmAccess(client = LlmClient(), resolver = llmResolver)
    // 0.14.1（§8）：服务端消息队列 + typing。presence 与路由共用同一份（最近一次 /v1/ 请求）。
    val presence = com.yap.agentd.delivery.ClientPresence()
    val delivery = com.yap.agentd.delivery.MessageDelivery(store, presence = presence)
    val services = AgentdServices(
        store = store,
        replyGenerator = LlmReplyGenerator(store, llm, tools = toolExecutor(store, options.ffmpegPath)),
        llm = llm,
        // 只有本机部署才接受客户端推送的模型配置；远程部署必须用 --llm-*/YAP_LLM_*。
        allowClientLlmConfig = isLoopbackHost(options.host),
        presence = presence,
        delivery = delivery
    )
    val server: AgentdServer.StartedServer = try {
        AgentdServer.start(options.host, options.port, options.token, rateLimiter, services)
    } catch (failure: Exception) {
        System.err.println("启动失败：${failure.message}")
        System.err.println("（端口被占用？换一个 --port 试试）")
        kotlin.system.exitProcess(1)
    }
    // 主动心跳：与任何客户端连接无关，服务端自己每 60 秒判定一次（§3.4）。
    // 掷中后由 LLM 写一句正文再落库，并发 `message.created` 事件（客户端前台/下次同步能收到）。
    services.proactiveTicker?.start()
    // 0.14.1：投递队列的崩溃恢复线程（前台投递由请求线程完成，它只补发没来得及发的那几条）。
    services.delivery?.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            services.delivery?.stop()
            services.proactiveTicker?.stop()
            server.stop(0)
            println("yap-agentd 已停止")
        }
    )
    println("yap-agentd $VERSION 正在监听 http://${options.host}:${options.port}")
    println(
        "  数据目录：$dataDir" +
            if (store.seeded) "（新目录，已播种 3 个人格 + 1 个示例会话）" else "（已存在，按已有数据启动）"
    )
    printLlmNotice(llmResolver)
    println(
        "  主动心跳：" +
            if (services.proactiveTicker?.isRunning == true) {
                "已启动（60 秒一拍：主动开口 / 日记提醒 / defer_reply 念头；LLM 生成正文）"
            } else {
                "未启用（没有状态存储）"
            }
    )
    println(
        "  自动判定：" +
            if (services.autoTriggers != null) "已启用（每条聊天后：每 4 条判心情、每 20 条评估进化；不依赖客户端连接）"
            else "未启用（没有 LLM 配置）"
    )
    println(
        "  体验功能（0.14）：" +
            if (services.mentalActivityService != null && services.sessionMemoryService != null) {
                "已启用（拉黑两轮确认、心理活动每 3 分钟/3 轮、会话记忆每 5/30 条；聊天后 + 每拍心跳都跑）"
            } else {
                "部分启用（没有 LLM：拉黑与到期解除仍然生效，心理活动/会话记忆跳过）"
            }
    )
    // 0.14.1：`watch` 要系统 ffmpeg；没有就明确说「看不了视频」，而不是等模型调用时才失败。
    val ffmpeg = com.yap.agentd.media.FfmpegFrameExtractor(options.ffmpegPath)
    println(
        "  视频解码：ffmpeg " +
            (ffmpeg.resolvedBinary()?.let { "可用（$it）" } ?: "不可用（服务器没有视频解码器，watch 会明确说看不了）")
    )
    printSecurityNotice(options)
    if (rateLimiter.enabled) {
        println(
            "  限流：每个来源 ${options.rateLimitPerMinute} 请求/分钟，突发 ${options.rateBurst}；" +
                "/health 单独计一个桶"
        )
    } else {
        println("  限流：已关闭（--rate-limit 0）")
    }
    println("  GET  /health")
    println("  GET  /v1/state?since=&personaId=")
    println("  GET  /v1/sessions")
    println("  GET  /v1/sessions/{id}/messages?beforeBlock=&afterBlock=")
    println("  POST /v1/sessions/{id}/messages   （SSE：accepted → delta/tool/message.created → done；真调 LLM + 工具回路）")
    println("  POST /v1/sessions/{id}/messages/import   （批量历史导入，≤200 条/批，≤8 MiB）")
    println("  GET  /v1/events?since=&timeoutSeconds=")
    println("  GET  /v1/backup                    （v6 备份，与客户端 v6 同形；chunked 流式）")
    println("  POST /v1/backup                    （{mode,data} 或裸 v6 文件，body 流式落盘后增量解析）")
    println("  GET/POST /v1/stickers · DELETE /v1/stickers/{id}   （表情包，spec/stickers）")
    println("  GET/POST /v1/personas/{id}/diary · DELETE …/{noteId}（日记板，spec/diary）")
    println("  POST /v1/media?kind=&mime=&personaId=   （原始图片字节，≤2 MiB）")
    println("  GET  /v1/media/{id}                     （原始字节 + ETag/304）")
    println("  DELETE /v1/media/{id}?force=1")
    println("  POST /v1/proactive/tick           （立即跑一次主动判定；生产由服务端心跳驱动）")
    println("  POST /v1/personas/{id}/evolution/evaluate|pending|{patchId}/accept|reject")
    println("  POST /v1/personas/{id}/mood/evaluate")
    println("  POST /v1/core/sleep-decision")
    println("  POST /v1/core/defer-plan")
    println("  POST /v1/core/proactive-cap")
    println("  POST /v1/core/tool-args")
}

private const val VERSION = AgentdApi.VERSION

/** 共享 token 的最短长度：只做提醒，不拒绝启动（部署方可能有自己的考虑）。 */
private const val MIN_TOKEN_LENGTH = 16

/** 共享 token 的环境变量入口（不写进 `ps`）。 */
internal const val ENV_TOKEN = "YAP_AGENTD_TOKEN"

/** 数据目录的环境变量入口（不想把路径写进 `ps` 时用）。 */
internal const val ENV_DATA = "YAP_AGENTD_DATA"

/** 数据目录默认值：相对**进程工作目录**的 `./agentd-data`。 */
internal const val DEFAULT_DATA_DIR = "./agentd-data"

/** 限流速率的环境变量入口（请求/分钟，0 = 关闭）。 */
internal const val ENV_RATE_LIMIT = "YAP_AGENTD_RATE_LIMIT"

/** 限流突发的环境变量入口。 */
internal const val ENV_RATE_BURST = "YAP_AGENTD_RATE_BURST"

/**
 * 解析后的启动参数。
 *
 * `internal` 是为了让 `MainOptionsTest` 不用启动服务就能覆盖「命令行 > 环境变量 > 默认值」
 * 这三层优先级；`main` 之外不应该有别的调用方。
 */
internal data class Options(
    val host: String = "127.0.0.1",
    val port: Int = 8788,
    val dataDir: String = DEFAULT_DATA_DIR,
    val token: String? = null,
    val rateLimitPerMinute: Int = RateLimiter.DEFAULT_PER_MINUTE,
    val rateBurst: Int = RateLimiter.DEFAULT_BURST,
    /** 服务端自己的 LLM 配置（远程部署唯一入口；本机部署是客户端配置缺失时的回落）。 */
    val llmBaseUrl: String? = null,
    val llmKey: String? = null,
    val llmModel: String? = null,
    /**
     * 0.16：主聊天的默认思考强度（`low` / `medium` / `high`；`null` = 用 low）。
     *
     * 只影响主 LLM 会话：情绪判定、进化评估、主动消息、ASR 这些辅助调用不带 `reasoning_effort`。
     */
    val reasoningEffort: String? = null,
    /**
     * 0.14.1：系统 `ffmpeg` 的路径（`watch` 抽视频关键帧用）。
     *
     * `null` = 按 `YAP_FFMPEG` → `PATH` 里的 `ffmpeg` 自己找；找不到时 `watch` 明确回
     * 「服务器没有视频解码器，看不了视频」，绝不伪造帧。
     */
    val ffmpegPath: String? = null,
    val help: Boolean = false
)

/**
 * 解析命令行 + 环境变量，**不退出进程**（出错抛 [IllegalArgumentException]，由 `main` 转成退出码 2）。
 *
 * 优先级：命令行显式值 > 环境变量 > 默认值。这样容器里可以用环境变量固定配置，
 * 临时覆盖时再用命令行（例如 `--rate-limit 0` 临时关掉限流做压测）。
 */
internal fun parseOptions(
    args: Array<String>,
    env: Map<String, String> = System.getenv()
): Options {
    var host = "127.0.0.1"
    var port = 8788
    var dataDir: String? = null
    var help = false
    var token: String? = null
    var rateLimit: Int? = null
    var rateBurst: Int? = null
    var llmBaseUrl: String? = null
    var llmKey: String? = null
    var llmModel: String? = null
    var reasoningEffort: String? = null
    var ffmpegPath: String? = null
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--help" || arg == "-h" -> {
                help = true
                index += 1
            }

            arg == "--host" -> {
                host = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--host 后面要跟一个地址，例如 127.0.0.1")
                index += 2
            }

            arg.startsWith("--host=") -> {
                host = arg.substringAfter("--host=").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--host= 后面要跟一个地址")
                index += 1
            }

            arg == "--port" -> {
                port = intArgument("--port", args.getOrNull(index + 1))
                index += 2
            }

            arg.startsWith("--port=") -> {
                port = intArgument("--port", arg.substringAfter("--port="))
                index += 1
            }

            arg == "--data" -> {
                dataDir = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--data 后面要跟一个目录，例如 ./agentd-data")
                index += 2
            }

            arg.startsWith("--data=") -> {
                dataDir = arg.substringAfter("--data=").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--data= 后面要跟一个目录")
                index += 1
            }

            arg == "--token" -> {
                token = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--token 后面要跟一个非空的共享密钥")
                index += 2
            }

            arg.startsWith("--token=") -> {
                token = arg.substringAfter("--token=").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--token= 后面要跟一个非空的共享密钥")
                index += 1
            }

            arg == "--rate-limit" -> {
                rateLimit = intArgument("--rate-limit", args.getOrNull(index + 1))
                index += 2
            }

            arg.startsWith("--rate-limit=") -> {
                rateLimit = intArgument("--rate-limit", arg.substringAfter("--rate-limit="))
                index += 1
            }

            arg == "--rate-burst" -> {
                rateBurst = intArgument("--rate-burst", args.getOrNull(index + 1))
                index += 2
            }

            arg.startsWith("--rate-burst=") -> {
                rateBurst = intArgument("--rate-burst", arg.substringAfter("--rate-burst="))
                index += 1
            }

            arg == "--llm-base-url" -> {
                llmBaseUrl = args.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--llm-base-url 后面要跟一个 OpenAI 兼容地址，例如 https://api.deepseek.com/v1")
                index += 2
            }

            arg.startsWith("--llm-base-url=") -> {
                llmBaseUrl = arg.substringAfter("--llm-base-url=").trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--llm-base-url= 后面要跟一个地址")
                index += 1
            }

            arg == "--llm-key" -> {
                llmKey = args.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--llm-key 后面要跟一个 API key（或用环境变量 YAP_LLM_KEY）")
                index += 2
            }

            arg.startsWith("--llm-key=") -> {
                llmKey = arg.substringAfter("--llm-key=").trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--llm-key= 后面要跟一个 API key")
                index += 1
            }

            arg == "--reasoning-effort" -> {
                reasoningEffort = args.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--reasoning-effort 后面要跟 low / medium / high")
                index += 2
            }

            arg.startsWith("--reasoning-effort=") -> {
                reasoningEffort = arg.substringAfter("--reasoning-effort=").trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--reasoning-effort= 后面要跟 low / medium / high")
                index += 1
            }

            arg == "--ffmpeg" -> {
                ffmpegPath = args.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--ffmpeg 后面要跟 ffmpeg 可执行文件路径（或留空用 PATH）")
                index += 2
            }

            arg.startsWith("--ffmpeg=") -> {
                ffmpegPath = arg.substringAfter("--ffmpeg=").trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--ffmpeg= 后面要跟 ffmpeg 可执行文件路径")
                index += 1
            }

            arg == "--llm-model" -> {
                llmModel = args.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--llm-model 后面要跟模型名，例如 deepseek-chat")
                index += 2
            }

            arg.startsWith("--llm-model=") -> {
                llmModel = arg.substringAfter("--llm-model=").trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("--llm-model= 后面要跟模型名")
                index += 1
            }

            else -> throw IllegalArgumentException("不认识的参数：$arg（用 --help 看用法）")
        }
    }
    if (port !in 1..65535) throw IllegalArgumentException("端口必须在 1–65535 之间，收到 $port")

    // 环境变量是「不想把 token 写进进程参数（ps 可见）」时的替代入口。
    if (token == null) {
        token = env[ENV_TOKEN]?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (dataDir == null) {
        dataDir = env[ENV_DATA]?.trim()?.takeIf { it.isNotEmpty() }
    }
    // LLM：命令行 > 环境变量（YAP_LLM_*） > 没有（本机模式还能用客户端推送的配置）。
    if (llmBaseUrl == null) llmBaseUrl = env[LlmConfigResolver.ENV_LLM_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() }
    if (llmKey == null) llmKey = env[LlmConfigResolver.ENV_LLM_KEY]?.trim()?.takeIf { it.isNotEmpty() }
    if (llmModel == null) llmModel = env[LlmConfigResolver.ENV_LLM_MODEL]?.trim()?.takeIf { it.isNotEmpty() }
    if (reasoningEffort == null) {
        reasoningEffort = env[LlmConfigResolver.ENV_LLM_REASONING_EFFORT]?.trim()?.takeIf { it.isNotEmpty() }
    }
    // 取值在启动时就校验：写错了不要让「思考强度和以前一样」变成查不出来的悬案。
    reasoningEffort = try {
        LlmDefaults.normalizeReasoningEffort(reasoningEffort)
    } catch (invalid: IllegalArgumentException) {
        throw IllegalArgumentException(invalid.message ?: "推理强度不合法")
    }
    if (ffmpegPath == null) ffmpegPath = env["YAP_FFMPEG"]?.trim()?.takeIf { it.isNotEmpty() }
    val envRateLimit = envInt(env, ENV_RATE_LIMIT)
    val envRateBurst = envInt(env, ENV_RATE_BURST)
    val perMinute = rateLimit ?: envRateLimit ?: RateLimiter.DEFAULT_PER_MINUTE
    val burst = rateBurst ?: envRateBurst ?: RateLimiter.DEFAULT_BURST
    if (perMinute < 0) {
        throw IllegalArgumentException(
            "${sourceOf(rateLimit, ENV_RATE_LIMIT, "--rate-limit")} 不能是负数（0 = 关闭限流），收到 $perMinute"
        )
    }
    if (burst < 1) {
        throw IllegalArgumentException(
            "${sourceOf(rateBurst, ENV_RATE_BURST, "--rate-burst")} 至少是 1，收到 $burst"
        )
    }

    return Options(
        host = host,
        port = port,
        dataDir = dataDir ?: DEFAULT_DATA_DIR,
        token = token,
        rateLimitPerMinute = perMinute,
        rateBurst = burst,
        llmBaseUrl = llmBaseUrl,
        llmKey = llmKey,
        llmModel = llmModel,
        reasoningEffort = reasoningEffort,
        ffmpegPath = ffmpegPath,
        help = help
    )
}

private fun intArgument(flag: String, raw: String?): Int =
    raw?.trim()?.toIntOrNull()
        ?: throw IllegalArgumentException("$flag 后面要跟一个整数（收到 ${raw?.trim()?.ifEmpty { "空字符串" } ?: "缺参数"}）")

/** 出错时告诉用户「这个值是从命令行还是环境变量来的」，而不是笼统写一个 flag。 */
private fun sourceOf(cliValue: Int?, envName: String, flag: String): String =
    if (cliValue == null) "环境变量 $envName" else flag

private fun envInt(env: Map<String, String>, name: String): Int? {
    val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toIntOrNull() ?: throw IllegalArgumentException("环境变量 $name 必须是整数，收到 $raw")
}

private fun isLoopbackHost(host: String): Boolean {
    val value = host.trim().lowercase()
    if (value == "localhost" || value == "127.0.0.1" || value == "::1" || value == "[::1]") return true
    return value.startsWith("127.")
}

/** 启动时打印一份脱敏的 LLM 配置来源（key 只说有没有），配置不完整只警告不退出。 */
private fun printLlmNotice(resolver: LlmConfigResolver) {
    val config = try {
        resolver.resolveOrNull()
    } catch (failure: LlmException) {
        System.err.println("  注意：LLM 配置不完整——${failure.userMessage()}")
        return
    }
    if (config == null) {
        println(
            "  LLM：未配置" +
                if (resolver.isLoopbackDeployment) {
                    "（本机部署：等客户端推送 API 配置，或用 --llm-base-url/--llm-key/--llm-model）"
                } else {
                    "（远程部署：不会读取客户端配置，请用 --llm-* 或 YAP_LLM_*）"
                }
        )
        return
    }
    println("  LLM：${config.redacted()}")
}

private fun printSecurityNotice(options: Options) {
    if (options.token != null && options.token.length < MIN_TOKEN_LENGTH) {
        System.err.println(
            "提示：共享 token 少于 $MIN_TOKEN_LENGTH 个字符，容易被猜到；建议用 `openssl rand -hex 32` 生成。"
        )
    }
    if (isLoopbackHost(options.host)) {
        if (options.token != null) {
            println("  已启用共享 token：所有请求都要带 `Authorization: Bearer <token>`")
        }
        return
    }
    println("  注意：监听地址不是回环地址，而本服务没有 TLS。")
    if (options.token == null) {
        System.err.println("警告：${options.host} 上没有鉴权——同一网络里的任何主机都能调用这 4 个接口。")
        System.err.println("      只在可信网络里这样用；要暴露给别的机器请加 --token，或放在带鉴权的反向代理后面。")
    } else {
        System.err.println("提示：已启用 --token，但流量仍是明文 HTTP；不要把 token 用在不受信网络。")
    }
}

/** 生产用的工具执行器：`listen`/`watch` 的抽帧器按 `--ffmpeg` / `YAP_FFMPEG` 解析。 */
private fun toolExecutor(store: FileStore, ffmpegPath: String?): com.yap.agentd.tools.ToolExecutor =
    com.yap.agentd.tools.ToolExecutor(
        store = store,
        media = com.yap.agentd.media.MediaUnderstanding(
            store,
            extractor = com.yap.agentd.media.FfmpegFrameExtractor(ffmpegPath)
        )
    )

private fun printUsage() {
    println(
        """
        yap-agentd $VERSION —— 两端共用的 agent 核心（HTTP 骨架 + 服务端权威状态）

        用法：
          agentd [--host 127.0.0.1] [--port 8788] [--data ./agentd-data]
                 [--token <共享密钥>] [--rate-limit 60] [--rate-burst 30]
                 [--llm-base-url <地址>] [--llm-key <API key>] [--llm-model <模型名>]
                 [--reasoning-effort low|medium|high] [--ffmpeg <可执行文件路径>]

        选项：
          --host <地址>            监听地址，默认 127.0.0.1（只给本机/同进程用）
          --port <端口>            监听端口，默认 8788
          --data <目录>            服务端状态目录，默认 ./agentd-data（相对工作目录）；
                                   不存在/为空时自动播种 3 个人格 + 1 个示例会话。
                                   也可用环境变量 YAP_AGENTD_DATA。
          --token <密钥>           可选：所有请求要求 Authorization: Bearer <密钥>
                                   （也可用环境变量 YAP_AGENTD_TOKEN，避免密钥出现在进程参数里）
          --rate-limit <n>         每个来源每分钟允许的请求数，默认 60；0 = 关闭限流
                                   （环境变量 YAP_AGENTD_RATE_LIMIT）
          --rate-burst <n>         突发容量（空闲时可以攒下的令牌数），默认 30，至少 1
                                   （环境变量 YAP_AGENTD_RATE_BURST）
          --llm-base-url <地址>    OpenAI 兼容根地址，例如 https://api.deepseek.com/v1
                                   （环境变量 YAP_LLM_BASE_URL）。本机部署（监听 127.0.0.1）优先用
                                   客户端推送的 api_configs.json + secrets.json；远程部署只用这里的配置。
          --llm-key <API key>      API key（环境变量 YAP_LLM_KEY）；本机网关可以不给
          --llm-model <模型名>     模型名，例如 deepseek-chat（环境变量 YAP_LLM_MODEL）
          --reasoning-effort <档>  主聊天的默认思考强度：low（默认）/ medium / high
                                   （环境变量 YAP_LLM_REASONING_EFFORT）。模型调用 think 之后
                                   同一轮后续请求升到 high，这一轮结束回到默认值。
                                   情绪判定/进化/主动消息等辅助调用不使用这一格。
          --ffmpeg <路径>          系统 ffmpeg 可执行文件（watch 抽视频关键帧用）；
                                   默认按 YAP_FFMPEG → PATH 查找，找不到时 watch 会明确说
                                   「服务器没有视频解码器，看不了视频」，不会伪造帧。
          --help, -h               打印这段说明

        接口：
          GET  /health                      健康检查（返回服务名与版本；需要 token 时也要带）
          GET  /v1/state?since=&personaId=  全量/增量状态快照（personas/sessions/messages/心情/在场）
          GET  /v1/sessions                 会话列表（lastMessageAt / unread / lastBlockIndex）
          GET  /v1/sessions/{id}/messages   一页一块的消息分页（beforeBlock 前翻 / afterBlock 后翻）
          POST /v1/sessions/{id}/messages   发送消息：SSE accepted → delta/tool/message.created → done
          GET/POST /v1/stickers, DELETE /v1/stickers/{id}          表情包（spec/stickers）
          GET/POST /v1/personas/{id}/diary, DELETE …/{noteId}      日记板（spec/diary）
          GET  /v1/backup                    v6 备份（与客户端同形，chunked 流式）
          POST /v1/backup                    {mode,data} 或裸 v6 文件
          POST /v1/proactive/tick            立即跑一次主动判定（生产由服务端心跳驱动）
          POST /v1/personas/{id}/evolution/evaluate   人格进化评估 → 待审补丁
          GET  /v1/personas/{id}/evolution/pending    待审补丁
          POST /v1/personas/{id}/evolution/{patchId}/accept|reject
          POST /v1/personas/{id}/mood/evaluate        用最近对话判定多维情绪
          GET  /v1/events?since=            增量事件长轮询（最多 25s）
          POST /v1/core/sleep-decision      睡眠/免打扰/唤醒阈值判定
          POST /v1/core/defer-plan          defer_reply 时间计划
          POST /v1/core/proactive-cap       主动程度 → 每日上限
          POST /v1/core/tool-args           工具参数夹取

        边界：
          请求体上限 1 MiB（超出 413）；读请求体超时 5 秒（超时 408）；
          请求由 4 个固定工作线程处理，慢连接不会卡住 /health；
          每个来源一个令牌桶，超限 429 + Retry-After；/health 单独计一个桶。
          服务本身只有明文 HTTP：对外部署请放在 Caddy/Nginx/Traefik 后面由代理终止 TLS
          （见 agentd/README.md 的「部署与 TLS」）。
        """.trimIndent()
    )
}

private fun exitWith(message: String): Nothing {
    System.err.println(message)
    println("用 --help 看用法")
    kotlin.system.exitProcess(2)
}
