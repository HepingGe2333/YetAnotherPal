package com.yap.app.domain

import java.net.URI
import java.net.URLEncoder
import java.time.Instant

/**
 * 反馈（0.14.2）：本地保存 + GitHub Issue 预填链接。
 *
 * 这一版**没有 token、也不自动提交**：App 里不存任何 GitHub 凭据，所以只能做到
 * 「把内容存在本机」+「生成一个预填好的 Issue 链接，让用户自己在浏览器里点提交」。
 * 好处是零权限、零后端；代价是用户要自己点一下（提示文案里说清楚了）。
 *
 * 两件事都发生有先后：
 * 1. 先落盘（`files/feedback/feedback-<ts>.json`，原子写）——浏览器打不开、用户关掉页面，
 *    内容也不会丢；
 * 2. 再拼链接、打开浏览器——链接里的内容与落盘的是同一份，不会出现「本地有、Issue 里少一段」。
 *
 * 这个文件只做**纯计算**：校验、编码、链接校验。落盘在 `FeedbackStore`（data 层），
 * 打开浏览器在 UI 层（要 Context），所以三条路径都能在 JVM 单测里逐条跑。
 *
 * 关于语言：Issue 正文里的标签（类型/内容/联系方式…）固定用中文。它们是**发给仓库维护者看的
 * 内容**（这个仓库的 Issue 就是中文的），不是 App 界面文案；界面上的类型选项走
 * `R.string.feedback_kind_*`，英文界面看到的是英文，而生成的 Issue 仍然是同一份结构。
 */
enum class FeedbackKind {
    PROBLEM,
    SUGGESTION,
    OTHER
}

/** 一条待提交的反馈。 */
data class FeedbackDraft(
    val kind: FeedbackKind,
    val content: String,
    /** 可选：怎么联系他（邮箱 / 其他账号）；空表示不填。 */
    val contact: String = ""
)

/** 表单校验的失败原因（界面按它挑资源文案）。 */
enum class FeedbackError {
    CONTENT_EMPTY,
    CONTENT_TOO_LONG,
    CONTACT_TOO_LONG
}

/**
 * 反馈的纯规则。
 *
 * 内容上限 1000 字（按 `trim` 之后的长度算）：再长就不适合塞进一个 Issue URL 了
 * （中文百分号编码后会膨胀到 URL 长度上限附近），所以界面上也按同一个数字截断。
 */
object Feedback {

    /** 内容上限（产品口径：≤1000 字）。 */
    const val MAX_CONTENT_CHARS: Int = 1000

    /** 联系方式上限：够放一个邮箱或一个账号，不鼓励写长文。 */
    const val MAX_CONTACT_CHARS: Int = 120

    /** 预填链接的基础部分（https，仓库固定）。 */
    const val ISSUE_BASE_URL: String = "https://github.com/HepingGe2333/YetAnotherPal/issues/new"

    /** Issue 标题里正文摘要最多多少字（标题太长会在 GitHub 列表里被截，看不清类型）。 */
    private const val TITLE_SUMMARY_CHARS: Int = 60

    /** 校验：返回 null 表示可以提交。 */
    fun validate(draft: FeedbackDraft): FeedbackError? {
        val content = draft.content.trim()
        return when {
            content.isEmpty() -> FeedbackError.CONTENT_EMPTY
            content.length > MAX_CONTENT_CHARS -> FeedbackError.CONTENT_TOO_LONG
            draft.contact.trim().length > MAX_CONTACT_CHARS -> FeedbackError.CONTACT_TOO_LONG
            else -> null
        }
    }

    /** 类型的中文标签（只进 Issue 正文/标题，不进界面）。 */
    fun kindLabel(kind: FeedbackKind): String = when (kind) {
        FeedbackKind.PROBLEM -> "问题"
        FeedbackKind.SUGGESTION -> "建议"
        FeedbackKind.OTHER -> "其他"
    }

    /**
     * Issue 标题：`[问题] 打开设置页会闪退`。
     *
     * 只取正文第一行（很多人第一行就是一句话概括），按 [TITLE_SUMMARY_CHARS] 截断；
     * 全空时退回类型标签本身（校验会在别处拦住空内容，这里只是不让链接里出现空标题）。
     */
    fun issueTitle(draft: FeedbackDraft): String {
        val label = kindLabel(draft.kind)
        val summary = draft.content.trim()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .take(TITLE_SUMMARY_CHARS)
        return if (summary.isEmpty()) "[$label]" else "[$label] $summary"
    }

    /**
     * Issue 正文：类型 + 内容 + 联系方式 + 环境信息（版本/平台/时间）。
     *
     * 环境信息是排查问题的关键（「0.15.0 / Android / 11-22」能立刻缩小一半范围），
     * 所以默认带上；里面没有任何用户隐私（没有设备 ID、没有 IP、没有聊天记录）。
     */
    fun issueBody(
        draft: FeedbackDraft,
        appVersion: String,
        platform: String,
        atMillis: Long
    ): String {
        val contact = draft.contact.trim().ifEmpty { "（未填写）" }
        return buildString {
            appendLine("**类型**：${kindLabel(draft.kind)}")
            appendLine()
            appendLine("**内容**：")
            appendLine(draft.content.trim())
            appendLine()
            appendLine("**联系方式**：$contact")
            appendLine()
            appendLine("---")
            appendLine("App 版本：${appVersion.ifBlank { "-" }}")
            appendLine("平台：${platform.ifBlank { "-" }}")
            appendLine("时间：${Instant.ofEpochMilli(atMillis)}")
        }.trimEnd()
    }

    /**
     * 预填链接：`<base>?title=<编码>&body=<编码>`。
     *
     * 用 `URLEncoder` + 把 `+` 换回 `%20`：GitHub 两种都收，但 `%20` 在测试与日志里更可读，
     * 也避免某些浏览器把 query 里的 `+` 当空格再二次编码。
     */
    fun issueUrl(
        draft: FeedbackDraft,
        appVersion: String,
        platform: String,
        atMillis: Long
    ): String = ISSUE_BASE_URL +
        "?title=" + encode(issueTitle(draft)) +
        "&body=" + encode(issueBody(draft, appVersion, platform, atMillis))

    /** 百分号编码（UTF-8）。 */
    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * 打开浏览器之前的最后一道校验：只认「https + 这个仓库的 new-issue 页面」。
     *
     * 为什么不能只判 `startsWith("https://")`：URL 是拼出来的数据，任何一段被污染
     * （比如正文里塞了换行或别的 host 的跳转）都不该被当成「去提交反馈」直接打开。
     * 另外挡住空白字符：`URI` 解析失败或 host 不符一律不放行。
     */
    fun isSafeIssueUrl(url: String): Boolean {
        val text = url.trim()
        if (text.isEmpty() || text != url) return false
        if (text.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host == "github.com" &&
            uri.rawPath == "/HepingGe2333/YetAnotherPal/issues/new"
    }
}
