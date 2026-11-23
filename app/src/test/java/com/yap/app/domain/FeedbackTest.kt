package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈（0.15）：表单校验、Issue 预填链接、https 校验。
 *
 * 这一版没有 token、不会自动提交，所以「链接对不对」和「校验拦不拦得住」就是全部正确性：
 * 一条超长内容会生成一个浏览器打不开的巨长 URL；一个拼错的 host 会变成「去提交反馈」
 * 却打开了别的站点。这里逐条钉住。
 */
class FeedbackTest {

    private val version = "0.15.0"
    private val platform = "android"
    private val at = 1_763_770_800_000L // 2025-11-22T00:20:00Z

    private fun draft(
        content: String = "设置页点反馈没反应",
        contact: String = "",
        kind: FeedbackKind = FeedbackKind.PROBLEM
    ) = FeedbackDraft(kind = kind, content = content, contact = contact)

    // ---------------------------------------------------------------- 校验

    @Test
    fun `空内容与纯空白都不让提交`() {
        assertEquals(FeedbackError.CONTENT_EMPTY, Feedback.validate(draft(content = "")))
        assertEquals(FeedbackError.CONTENT_EMPTY, Feedback.validate(draft(content = "   \n\t ")))
        assertNull(Feedback.validate(draft(content = "能打开")))
    }

    @Test
    fun `内容上限一千字 正好一千可以提交`() {
        val atLimit = "雨".repeat(Feedback.MAX_CONTENT_CHARS)
        val overLimit = "雨".repeat(Feedback.MAX_CONTENT_CHARS + 1)
        assertEquals(1000, Feedback.MAX_CONTENT_CHARS)
        assertNull(Feedback.validate(draft(content = atLimit)))
        assertEquals(FeedbackError.CONTENT_TOO_LONG, Feedback.validate(draft(content = overLimit)))
    }

    @Test
    fun `联系方式可选 但太长要拦`() {
        assertNull("不填联系方式是合法状态", Feedback.validate(draft(contact = "")))
        assertNull(Feedback.validate(draft(contact = "a".repeat(Feedback.MAX_CONTACT_CHARS))))
        assertEquals(
            FeedbackError.CONTACT_TOO_LONG,
            Feedback.validate(draft(contact = "a".repeat(Feedback.MAX_CONTACT_CHARS + 1)))
        )
    }

    // ---------------------------------------------------------------- 标题与正文

    @Test
    fun `标题带类型标签 只取第一行并截断`() {
        assertEquals(
            "[问题] 打开设置页会闪退",
            Feedback.issueTitle(draft(content = "打开设置页会闪退\n复现步骤：1. 打开设置"))
        )
        assertEquals(
            "[建议] 想按日期搜消息",
            Feedback.issueTitle(draft(kind = FeedbackKind.SUGGESTION, content = "\n\n  想按日期搜消息  \n"))
        )
        val long = Feedback.issueTitle(draft(content = "占".repeat(200)))
        assertTrue("标题不能无限长：${long.length}", long.length <= 60 + "[问题] ".length)
        assertEquals("内容全空时退回类型标签", "[其他]", Feedback.issueTitle(draft(kind = FeedbackKind.OTHER, content = " ")))
    }

    @Test
    fun `正文带类型 内容 联系方式与版本平台时间`() {
        val body = Feedback.issueBody(
            draft = draft(content = "点反馈按钮没反应", contact = "me@example.com"),
            appVersion = version,
            platform = platform,
            atMillis = at
        )
        assertTrue(body.contains("**类型**：问题"))
        assertTrue(body.contains("**内容**："))
        assertTrue(body.contains("点反馈按钮没反应"))
        assertTrue(body.contains("**联系方式**：me@example.com"))
        assertTrue(body.contains("App 版本：0.15.0"))
        assertTrue(body.contains("平台：android"))
        assertTrue("时间用 ISO 8601", body.contains("2025-11-22T00:20:00Z"))
        assertFalse("没有联系方式时写一句未填写，而不是留空", body.contains("**联系方式**：\n"))
    }

    @Test
    fun `没填联系方式时正文标注未填写`() {
        val body = Feedback.issueBody(draft(), version, platform, at)
        assertTrue(body.contains("（未填写）"))
    }

    // ---------------------------------------------------------------- URL 编码

    @Test
    fun `链接是 https 的 new issue 页面且带 title 与 body`() {
        val url = Feedback.issueUrl(draft(content = "中文内容 with space"), version, platform, at)
        assertTrue(url.startsWith("https://github.com/HepingGe2333/YetAnotherPal/issues/new?title="))
        assertTrue(url.contains("&body="))
        assertTrue("不能有未编码的空格", !url.contains(" "))
        assertTrue("不能有换行", !url.contains("\n"))
        assertTrue("中文必须百分号编码", !Regex("[\\u4e00-\\u9fff]").containsMatchIn(url))
        assertTrue("空格编码成 %20 而不是 +", url.contains("%20"))
        assertTrue(Feedback.isSafeIssueUrl(url))
    }

    @Test
    fun `编码函数处理中文 空格 换行与保留字符`() {
        assertEquals("a%20b", Feedback.encode("a b"))
        assertEquals("%E9%97%AE%E9%A2%98", Feedback.encode("问题"))
        assertEquals("a%0Ab", Feedback.encode("a\nb"))
        assertEquals("100%25", Feedback.encode("100%"))
        assertEquals("a%26b%3Dc%3Fd", Feedback.encode("a&b=c?d"))
        assertEquals("", Feedback.encode(""))
    }

    @Test
    fun `标题与正文都真的进了链接`() {
        val url = Feedback.issueUrl(draft(content = "闪退"), version, platform, at)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("title=[问题] 闪退"))
        assertTrue(decoded.contains("body=**类型**：问题"))
        assertTrue(decoded.contains("App 版本：0.15.0"))
    }

    @Test
    fun `每个类型都有自己的标签`() {
        assertEquals("问题", Feedback.kindLabel(FeedbackKind.PROBLEM))
        assertEquals("建议", Feedback.kindLabel(FeedbackKind.SUGGESTION))
        assertEquals("其他", Feedback.kindLabel(FeedbackKind.OTHER))
    }

    // ---------------------------------------------------------------- 打开前的 https 校验

    @Test
    fun `只放行本仓库的 https new issue 地址`() {
        assertTrue(Feedback.isSafeIssueUrl(Feedback.ISSUE_BASE_URL))
        assertTrue(Feedback.isSafeIssueUrl(Feedback.issueUrl(draft(), version, platform, at)))
        assertFalse("http 不放行", Feedback.isSafeIssueUrl("http://github.com/HepingGe2333/YetAnotherPal/issues/new"))
        assertFalse("别的仓库不放行", Feedback.isSafeIssueUrl("https://github.com/someone/else/issues/new"))
        assertFalse("别的 host 不放行", Feedback.isSafeIssueUrl("https://example.com/HepingGe2333/YetAnotherPal/issues/new"))
        assertFalse("intent 不放行", Feedback.isSafeIssueUrl("intent://github.com/HepingGe2333/YetAnotherPal/issues/new"))
        assertFalse("空串不放行", Feedback.isSafeIssueUrl(""))
        assertFalse("带空白不放行", Feedback.isSafeIssueUrl("  ${Feedback.ISSUE_BASE_URL}"))
        assertFalse("正文里塞换行也不放行", Feedback.isSafeIssueUrl("${Feedback.ISSUE_BASE_URL}?title=a\nb"))
    }

    @Test
    fun `一千字中文也能拼出一个合法的链接`() {
        val url = Feedback.issueUrl(
            draft(content = "雨".repeat(Feedback.MAX_CONTENT_CHARS)),
            version,
            platform,
            at
        )
        assertNotNull("链接必须还是可以解析的 URI", java.net.URI(url))
        assertTrue(Feedback.isSafeIssueUrl(url))
    }
}
