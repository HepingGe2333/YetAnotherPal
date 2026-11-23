package com.yap.app.data.update

import com.yap.app.BuildConfig
import com.yap.app.domain.ReleaseParseResult
import com.yap.app.domain.UpdateDecision
import com.yap.app.domain.UpdatePolicy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 一次更新检查的结果。
 *
 * 失败**没有**用户可见的句子：设置页只在手动检查时显示一行「稍后再试」，
 * 自动检查失败完全静默（只记日志）——限流 403、断网都不该打断用户。
 */
sealed interface UpdateCheckOutcome {
    data class Decided(val decision: UpdateDecision) : UpdateCheckOutcome
    data class Failed(val failure: UpdateFailure) : UpdateCheckOutcome
}

/** 失败原因（只给日志/状态行用，不弹错误）。 */
sealed interface UpdateFailure {
    /** 非 2xx：403 / 429 是限流，其它是服务端问题。 */
    data class Http(val status: Int) : UpdateFailure

    /** 连接失败 / 超时 / DNS。 */
    data class Network(val kind: String) : UpdateFailure

    /** 响应读不动（超大 / 不是 JSON / 没有 tag）。 */
    data class Unreadable(val reason: String) : UpdateFailure
}

/**
 * 更新检查：只做「请求 GitHub Releases API + 交给纯逻辑判断」，不碰 UI、不写库。
 *
 * - 地址固定为项目仓库的 `/releases/latest`（构造参数可换，只给单测用）；
 * - 必须带 `User-Agent`（GitHub API 没有它会直接 403）；
 * - 超时收得很短（连接 5s / 读 10s）：这是后台的锦上添花，不该让用户等；
 * - **任何失败都返回 [UpdateCheckOutcome.Failed]，绝不抛异常**，由调用方决定是否记日志。
 *
 * 不引第三方依赖：用项目已有的 OkHttp（与 `ServerApi` 同一个客户端来源，连接池共享）。
 */
class UpdateChecker(
    httpClient: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val listEndpoint: String = DEFAULT_LIST_ENDPOINT,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val logger: (String) -> Unit = ::logQuietly,
    connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    readTimeoutMillis: Long = READ_TIMEOUT_MILLIS,
    callTimeoutMillis: Long = CALL_TIMEOUT_MILLIS
) {

    private val client: OkHttpClient = httpClient.newBuilder()
        .followRedirects(true)
        // 一次检查只发一次：GitHub 未鉴权接口有 60 次/小时的限流，失败重试只会更快撞上限。
        .retryOnConnectionFailure(false)
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 手动检查 / 自动检查都走这里；[includePrereleases] 来自设置页开关。
     *
     * 「包含预发布」打开时查 `/releases`（列表，新的在前），否则查 `/releases/latest`：
     * GitHub 的 latest 按定义永远跳过 prerelease，只查它的话开关就形同虚设。
     */
    suspend fun check(includePrereleases: Boolean): UpdateCheckOutcome {
        val raw = when (val fetched = fetch(if (includePrereleases) listEndpoint else endpoint)) {
            is FetchResult.Ok -> fetched.body
            is FetchResult.Failure -> return UpdateCheckOutcome.Failed(fetched.failure)
        }
        val parsed = if (includePrereleases) {
            UpdatePolicy.parseReleaseList(raw)
        } else {
            UpdatePolicy.parseRelease(raw)
        }
        val release = when (parsed) {
            is ReleaseParseResult.Success -> parsed.release
            // 列表全空 / 全是 draft：没有可提示的版本，不是失败。
            ReleaseParseResult.NoReleases ->
                return UpdateCheckOutcome.Decided(UpdateDecision.NoRelease)
            ReleaseParseResult.MalformedJson -> {
                logger("update check: release json malformed")
                return UpdateCheckOutcome.Failed(UpdateFailure.Unreadable("malformed_json"))
            }
            ReleaseParseResult.MissingTag -> {
                logger("update check: release json has no tag_name")
                return UpdateCheckOutcome.Failed(UpdateFailure.Unreadable("missing_tag"))
            }
        }
        val decision = UpdatePolicy.decide(currentVersion, release, includePrereleases)
        logger("update check: current=$currentVersion remote=${release.tagName} decision=${decision::class.simpleName}")
        return UpdateCheckOutcome.Decided(decision)
    }

    private sealed interface FetchResult {
        data class Ok(val body: String) : FetchResult
        data class Failure(val failure: UpdateFailure) : FetchResult
    }

    private suspend fun fetch(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            // GitHub API 强制要求 UA；带上自己的版本号，维护日志里能看出是谁在查。
            .header("User-Agent", USER_AGENT_PREFIX + currentVersion)
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation: CancellableContinuation<FetchResult> ->
            // 协程取消时关连接：自动检查的生命周期跟着界面，不该留下后台请求。
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    val kind = failure.javaClass.simpleName.ifBlank { "IOException" }
                    logger("update check: network failure $kind")
                    if (continuation.isActive) {
                        continuation.resume(FetchResult.Failure(UpdateFailure.Network(kind)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        runCatching { response.close() }
                        return
                    }
                    val result = try {
                        response.use { readResponse(it) }
                    } catch (failure: Exception) {
                        // 读响应体失败（超限 / 连接断了）同样是静默失败。
                        logger("update check: unreadable body ${failure.javaClass.simpleName}")
                        FetchResult.Failure(UpdateFailure.Unreadable("unreadable_body"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    /** 状态码 → 响应体；非 2xx 只记日志，不抛。 */
    private fun readResponse(response: Response): FetchResult {
        if (!response.isSuccessful) {
            logger("update check: http ${response.code}")
            return FetchResult.Failure(UpdateFailure.Http(response.code))
        }
        val body = response.body ?: return FetchResult.Failure(UpdateFailure.Unreadable("empty_body"))
        val text = runCatching { readAtMost(body.byteStream(), MAX_BODY_BYTES) }.getOrElse {
            return FetchResult.Failure(UpdateFailure.Unreadable("body_too_large"))
        }
        return FetchResult.Ok(text)
    }

    companion object {
        /** 项目仓库的「最新正式版」；GitHub 的 latest 本身会跳过 prerelease/draft。 */
        const val DEFAULT_ENDPOINT: String =
            "https://api.github.com/repos/HepingGe2333/YetAnotherPal/releases/latest"

        /**
         * 「包含预发布版本」时用的列表地址（新的在前）。
         *
         * 用 `per_page=10` 而不是全量：只要最新的几条就够判断了。
         */
        const val DEFAULT_LIST_ENDPOINT: String =
            "https://api.github.com/repos/HepingGe2333/YetAnotherPal/releases?per_page=10"

        const val USER_AGENT_PREFIX: String = "YetAnotherPal-Android/"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val READ_TIMEOUT_MILLIS = 10_000L
        private const val CALL_TIMEOUT_MILLIS = 15_000L

        /** 响应体上限：release JSON 再大也不该超过 512 KiB。 */
        const val MAX_BODY_BYTES: Int = 512 * 1024

        internal fun readAtMost(input: InputStream, limit: Int): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) error("response body exceeds $limit bytes")
                    output.write(buffer, 0, read)
                }
            }
            return output.toString("UTF-8")
        }

        /** 默认日志：JVM 单测里 `android.util.Log` 是 not mocked，包一层别让它把测试打挂。 */
        private fun logQuietly(message: String) {
            runCatching { android.util.Log.d("UpdateChecker", message) }
        }
    }
}
