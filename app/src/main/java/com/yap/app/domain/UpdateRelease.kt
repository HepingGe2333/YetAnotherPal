package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 更新渠道的纯逻辑（0.14.2；没有任何 Android / OkHttp 依赖，可以直接在 JVM 单测里钉死）。
 *
 * 这里只回答三个问题：
 * 1. 这串版本号怎么比大小（[SemanticVersion]）；
 * 2. GitHub 上的这个 release 要不要提示（[UpdatePolicy]）；
 * 3. 距离上次检查够不够 24 小时（[UpdateThrottle]）。
 *
 * 网络与「怎么把结果告诉用户」分别归 `data/update/UpdateChecker` 与设置页的 UI 层。
 */

/**
 * 语义版本号：`v0.15.0` / `0.15.0` / `1.2` / `0.15.0-beta.1` 都能认。
 *
 * 比较规则按产品要求刻意收窄：**只比较数字段**，预发布后缀不参与大小比较
 * （`0.15.0-beta.1` 与 `0.15.0` 视为同一版），后缀只用来判断「这是不是预发布」。
 * 这样「远端 tag 带 `-rc1`、本机是正式版」不会因为字符串比较得出「远端更大」的错觉，
 * 是否提示由 [UpdatePolicy] 的 prerelease 策略单独决定。
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 预发布后缀（`-` 之后、`+` 之前的部分），没有则为 null。 */
    val prerelease: String? = null,
    /** 原始字符串（用于日志/展示）。 */
    val raw: String = ""
) : Comparable<SemanticVersion> {

    /** 这是不是预发布版本（tag 里带 `-`）。 */
    val isPrerelease: Boolean get() = !prerelease.isNullOrBlank()

    override fun compareTo(other: SemanticVersion): Int {
        val left = listOf(major, minor, patch)
        val right = listOf(other.major, other.minor, other.patch)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /** `0.15.0` / `0.15.0-beta.1`（展示用，恒带 `v` 前缀由 UI 决定）。 */
    fun describe(): String = if (isPrerelease) "$major.$minor.$patch-$prerelease" else "$major.$minor.$patch"

    companion object {
        /**
         * 宽松解析：认 `v`/`V` 前缀、1–3 段数字、`-suffix` 与 `+build`。
         *
         * 认不出来返回 null（调用方按「无法比较」处理，绝不猜一个版本出来）。
         * `0.14.0-debug`（debug 构建的 versionNameSuffix）解析成 `0.14.0` + prerelease `debug`。
         */
        fun parse(raw: String?): SemanticVersion? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val withoutPrefix = value.removePrefix("v").removePrefix("V")
            // build metadata（`+abc`）不参与比较，直接丢掉。
            val withoutBuild = withoutPrefix.substringBefore('+')
            val core = withoutBuild.substringBefore('-')
            val prerelease = withoutBuild.substringAfter('-', "").takeIf { it.isNotBlank() }
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = parts.map { part ->
                // 只接受纯数字：`0.15.x` 这种不是版本号。
                if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
                part.toIntOrNull() ?: return null
            }
            return SemanticVersion(
                major = numbers.getOrElse(0) { 0 },
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                prerelease = prerelease,
                raw = value
            )
        }
    }
}

/** GitHub Releases API（`/releases/latest`）里我们真正用到的字段。 */
data class ReleaseInfo(
    /** `tag_name`，例如 `v0.15.0`。 */
    val tagName: String = "",
    /** `name`（发布标题），可能为空。 */
    val name: String = "",
    /** `body`（发布说明 Markdown），可能为空。 */
    val body: String = "",
    /** `html_url`（发布页地址，只在 https 下才允许打开）。 */
    val htmlUrl: String = "",
    /** `published_at`（ISO-8601 字符串，原样展示/记录，不在这里解析时区）。 */
    val publishedAt: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false
) {
    val version: SemanticVersion? get() = SemanticVersion.parse(tagName)

    /** 对话框标题用的版本号：优先 tag，其次 name，最后原样。 */
    fun displayTag(): String = tagName.ifBlank { name }.ifBlank { "?" }
}

/** 解析 GitHub release JSON 的结果：坏数据也只在这里说话，不抛异常。 */
sealed interface ReleaseParseResult {
    data class Success(val release: ReleaseInfo) : ReleaseParseResult
    /** JSON 本身不合法 / 顶层不是对象。 */
    data object MalformedJson : ReleaseParseResult
    /** 是合法 JSON 但没有可用的 `tag_name`，无法判断版本。 */
    data object MissingTag : ReleaseParseResult

    /** `/releases` 列表是空的（或全是 draft）：没有可提示的版本。 */
    data object NoReleases : ReleaseParseResult
}

/** 「这次检查的结论」。纯数据，界面自己决定怎么翻译成句子。 */
sealed interface UpdateDecision {
    /** 远端比本机新：应该提示。 */
    data class Available(val version: SemanticVersion, val release: ReleaseInfo) : UpdateDecision

    /** 远端不比本机新。 */
    data class UpToDate(val latest: SemanticVersion?) : UpdateDecision

    /** 远端是预发布版，而用户没有开启「包含预发布版本」。 */
    data class SkippedPrerelease(val version: SemanticVersion) : UpdateDecision

    /** 没有可用的 release（没发过版 / 是 draft / 拿不到 tag）。 */
    data object NoRelease : UpdateDecision

    /** 版本号认不出来（tag 是 `nightly` 这种）。 */
    data class Unparseable(val tag: String) : UpdateDecision
}

object UpdatePolicy {

    /** 解析 GitHub `/releases/latest` 的响应体（单个 release 对象）。 */
    fun parseRelease(raw: String?, json: Json = DEFAULT_JSON): ReleaseParseResult {
        if (raw.isNullOrBlank()) return ReleaseParseResult.MalformedJson
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return ReleaseParseResult.MalformedJson
        return parseReleaseObject(obj)
    }

    /**
     * 解析 GitHub `/releases` 的响应体（数组，新的在前）。
     *
     * 「包含预发布版本」打开时走这条：`/releases/latest` 按 GitHub 的定义**永远跳过**
     * prerelease 与 draft，只查 latest 的话那个开关等于没有。这里取第一个非 draft 的条目
     * （GitHub 已按创建时间倒序），因此既能拿到最新的 beta，也能拿到稳定版。
     */
    fun parseReleaseList(raw: String?, json: Json = DEFAULT_JSON): ReleaseParseResult {
        if (raw.isNullOrBlank()) return ReleaseParseResult.MalformedJson
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return ReleaseParseResult.MalformedJson
        var sawTagless = false
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val parsed = parseReleaseObject(obj)
            when (parsed) {
                is ReleaseParseResult.Success -> if (!parsed.release.draft) return parsed
                ReleaseParseResult.MissingTag -> sawTagless = true
                else -> Unit
            }
        }
        return if (sawTagless) ReleaseParseResult.MissingTag else ReleaseParseResult.NoReleases
    }

    private fun parseReleaseObject(obj: JsonObject): ReleaseParseResult {
        fun text(key: String): String =
            (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
        fun flag(key: String): Boolean =
            (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false

        val tag = text("tag_name").trim()
        if (tag.isEmpty()) return ReleaseParseResult.MissingTag
        return ReleaseParseResult.Success(
            ReleaseInfo(
                tagName = tag,
                name = text("name"),
                body = text("body"),
                htmlUrl = text("html_url"),
                publishedAt = text("published_at"),
                prerelease = flag("prerelease"),
                draft = flag("draft")
            )
        )
    }

    /**
     * 决定要不要提示更新。
     *
     * 顺序：draft/非法 → 预发布策略 → 数字段比较。
     * [currentVersion] 认不出来时按「不比」处理（[UpdateDecision.UpToDate]）：本机版本号
     * 是构建时写进去的，读不懂说明是构建配置坏了，不该因此骚扰用户。
     */
    fun decide(
        currentVersion: String?,
        release: ReleaseInfo?,
        includePrereleases: Boolean
    ): UpdateDecision {
        if (release == null || release.draft) return UpdateDecision.NoRelease
        val remote = release.version ?: return UpdateDecision.Unparseable(release.tagName)
        val current = SemanticVersion.parse(currentVersion)
            ?: return UpdateDecision.UpToDate(remote)
        if (remote.isPrerelease && !includePrereleases) {
            return UpdateDecision.SkippedPrerelease(remote)
        }
        return if (remote > current) UpdateDecision.Available(remote, release) else UpdateDecision.UpToDate(remote)
    }

    /** 发布说明摘要：去掉 Markdown 里最吵的那几种标记，截到 [maxChars] 字以内。 */
    fun summarize(body: String?, maxChars: Int = 420): String {
        val text = body.orEmpty()
            .replace("\r\n", "\n")
            .lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("###").removePrefix("##").removePrefix("#")
                    .trim()
                    .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
                    .replace(Regex("[`*_>~]"), "")
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (text.length <= maxChars) return text
        // 截断时尽量落在换行处，避免半个单词。
        val cut = text.take(maxChars)
        val lastBreak = cut.lastIndexOf('\n')
        return (if (lastBreak > maxChars / 2) cut.substring(0, lastBreak) else cut).trimEnd() + "…"
    }

    /**
     * GitHub 的 `html_url` 只允许 https（导出/下载只走官方发布页）。
     *
     * 这是防「服务端/中间人被改成 `javascript:` / `file:` / `intent:`」的那道闸；
     * 打不开就只记日志，不弹错误。
     */
    fun isSafeReleaseUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        return value.startsWith("https://", ignoreCase = true) &&
            !value.any { it.isWhitespace() }
    }

    private val DEFAULT_JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}

/** 「自动检查」的节流：默认 24 小时一次。 */
object UpdateThrottle {

    const val INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L

    /**
     * 现在该不该自动检查。
     *
     * - 从没检查过（`<= 0`）→ 该；
     * - 距上次不足 [interval] → 不该；
     * - 系统时间被往回拨（`lastCheckedAt > now`）→ 该（否则会被一个未来时间永久卡住）。
     */
    fun isDue(
        nowMillis: Long,
        lastCheckedAtMillis: Long,
        intervalMillis: Long = INTERVAL_MILLIS
    ): Boolean {
        if (lastCheckedAtMillis <= 0L) return true
        if (lastCheckedAtMillis > nowMillis) return true
        return nowMillis - lastCheckedAtMillis >= intervalMillis
    }
}
