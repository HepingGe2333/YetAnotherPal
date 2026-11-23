package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新渠道的纯逻辑（0.15）：版本号比较、预发布策略、24h 节流、发布说明摘要。
 *
 * 这些规则决定了「会不会弹一个升级提示」，所以不用「看起来对」糊过去：
 * 每一档都钉死，包括时钟被往回拨、tag 认不出来这类边界。
 */
class UpdateReleaseTest {

    // ------------------------------------------------------------------ 版本号解析

    @Test
    fun `v 前缀与两段三段版本号都能认`() {
        assertEquals(SemanticVersion(0, 15, 0), SemanticVersion.parse("v0.15.0")?.copy(raw = ""))
        assertEquals(SemanticVersion(0, 15, 0), SemanticVersion.parse("0.15.0")?.copy(raw = ""))
        assertEquals(SemanticVersion(0, 14, 0), SemanticVersion.parse("V0.14.0")?.copy(raw = ""))
        assertEquals("两段补 0", SemanticVersion(1, 2, 0), SemanticVersion.parse("1.2")?.copy(raw = ""))
        assertEquals("build metadata 不参与比较", 0, SemanticVersion.parse("1.2.3+abc")!!.compareTo(SemanticVersion(1, 2, 3)))
    }

    @Test
    fun `预发布后缀被识别但不参与大小比较`() {
        val beta = SemanticVersion.parse("0.15.0-beta.2")!!
        val stable = SemanticVersion.parse("0.15.0")!!
        assertTrue("带后缀就是预发布", beta.isPrerelease)
        assertFalse(stable.isPrerelease)
        assertEquals("只比较数字段：0.15.0-beta.2 == 0.15.0", 0, beta.compareTo(stable))
        assertEquals("debug 构建的 versionNameSuffix 也认", "debug", SemanticVersion.parse("0.14.0-debug")!!.prerelease)
        assertEquals("0.15.0-beta.2", beta.describe())
        assertEquals("0.15.0", stable.describe())
    }

    @Test
    fun `认不出来的版本号返回 null 而不是猜一个`() {
        listOf(null, "", "   ", "v", "nightly", "0.15.x", "1.2.3.4", "-1.0.0", "1..2").forEach { raw ->
            assertNull("「$raw」不该被解析成版本号", SemanticVersion.parse(raw))
        }
    }

    @Test
    fun `数字段比较是逐段的 不是字符串比较`() {
        assertTrue(
            "0.10.0 比 0.9.0 新（字符串比较会反过来）",
            SemanticVersion.parse("0.10.0")!! > SemanticVersion.parse("0.9.0")!!
        )
        assertTrue(
            "0.15.0 比 0.14.9 新",
            SemanticVersion.parse("v0.15.0")!! > SemanticVersion.parse("0.14.9")!!
        )
        assertTrue(
            "1.0.0 比 0.99.99 新",
            SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("0.99.99")!!
        )
        assertEquals(
            "v 前缀不影响相等",
            0,
            SemanticVersion.parse("v0.15.0")!!.compareTo(SemanticVersion.parse("0.15.0")!!)
        )
    }

    // ------------------------------------------------------------------ GitHub JSON

    @Test
    fun `release JSON 的六个字段都被读出来`() {
        val raw = """
            {
              "tag_name": "v0.15.0",
              "name": "0.15 · 更新渠道与备份版本",
              "body": "## 新增\n- 检查更新\n- 备份元数据",
              "html_url": "https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v0.15.0",
              "published_at": "2026-11-20T10:00:00Z",
              "prerelease": false,
              "draft": false,
              "unknown_future_field": 1
            }
        """.trimIndent()
        val release = (UpdatePolicy.parseRelease(raw) as ReleaseParseResult.Success).release
        assertEquals("v0.15.0", release.tagName)
        assertEquals("0.15 · 更新渠道与备份版本", release.name)
        assertTrue(release.body.contains("检查更新"))
        assertEquals("https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v0.15.0", release.htmlUrl)
        assertEquals("2026-11-20T10:00:00Z", release.publishedAt)
        assertFalse(release.prerelease)
        assertEquals(SemanticVersion.parse("v0.15.0"), release.version!!)
    }

    @Test
    fun `坏 JSON 与缺 tag 分别报不同的原因`() {
        assertEquals(ReleaseParseResult.MalformedJson, UpdatePolicy.parseRelease(null))
        assertEquals(ReleaseParseResult.MalformedJson, UpdatePolicy.parseRelease(""))
        assertEquals(ReleaseParseResult.MalformedJson, UpdatePolicy.parseRelease("not json"))
        assertEquals(ReleaseParseResult.MalformedJson, UpdatePolicy.parseRelease("[1,2,3]"))
        assertEquals(ReleaseParseResult.MissingTag, UpdatePolicy.parseRelease("""{"name":"x"}"""))
        assertEquals(ReleaseParseResult.MissingTag, UpdatePolicy.parseRelease("""{"tag_name":"  "}"""))
    }

    @Test
    fun `body 为 null 或缺失时按空串处理 不让整份失败`() {
        val release = (UpdatePolicy.parseRelease(
            """{"tag_name":"v0.15.0","name":null,"body":null,"html_url":null}"""
        ) as ReleaseParseResult.Success).release
        assertEquals("", release.body)
        assertEquals("", release.name)
        assertEquals("", release.htmlUrl)
    }

    @Test
    fun `releases 列表取第一个非 draft 的条目`() {
        val raw = """
            [
              {"tag_name":"v0.15.0-rc.1","prerelease":true,"body":"rc notes"},
              {"tag_name":"v0.15.0","draft":true,"body":"draft notes"},
              {"tag_name":"v0.14.0","prerelease":false,"body":"old"}
            ]
        """.trimIndent()
        assertEquals(
            "新的在前；draft 跳过",
            "v0.15.0-rc.1",
            (UpdatePolicy.parseReleaseList(raw) as ReleaseParseResult.Success).release.tagName
        )
        assertEquals(ReleaseParseResult.NoReleases, UpdatePolicy.parseReleaseList("[]"))
        assertEquals(
            "全是 draft 等于没有可提示的版本",
            ReleaseParseResult.NoReleases,
            UpdatePolicy.parseReleaseList("""[{"tag_name":"v0.15.0","draft":true}]""")
        )
        assertEquals(
            ReleaseParseResult.MissingTag,
            UpdatePolicy.parseReleaseList("""[{"name":"no tag"}]""")
        )
        assertEquals(
            "列表接口返回对象而不是数组 → 当作坏 JSON",
            ReleaseParseResult.MalformedJson,
            UpdatePolicy.parseReleaseList("""{"tag_name":"v0.15.0"}""")
        )
    }

    // ------------------------------------------------------------------ 要不要提示

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = ReleaseInfo(
        tagName = tag,
        name = tag,
        body = "notes",
        htmlUrl = "https://github.com/HepingGe2333/YetAnotherPal/releases/tag/$tag",
        publishedAt = "2026-11-20T10:00:00Z",
        prerelease = prerelease,
        draft = draft
    )

    @Test
    fun `只有远端真的更新才提示`() {
        val available = UpdatePolicy.decide("v0.14.0-debug", release("v0.15.0"), includePrereleases = false)
        assertTrue("0.15.0 > 0.14.0-debug 应当提示", available is UpdateDecision.Available)

        val same = UpdatePolicy.decide("0.15.0", release("v0.15.0"), includePrereleases = false)
        assertTrue(same is UpdateDecision.UpToDate)

        val older = UpdatePolicy.decide("0.16.0", release("v0.15.0"), includePrereleases = false)
        assertTrue("本机比远端新时不该提示", older is UpdateDecision.UpToDate)
    }

    @Test
    fun `预发布默认被忽略 打开开关后才提示`() {
        val skipped = UpdatePolicy.decide("0.14.0", release("v0.15.0-rc.1", prerelease = true), false)
        assertEquals(UpdateDecision.SkippedPrerelease(SemanticVersion.parse("v0.15.0-rc.1")!!), skipped)

        val allowed = UpdatePolicy.decide("0.14.0", release("v0.15.0-rc.1", prerelease = true), true)
        assertTrue("设置里打开后预发布也要提示", allowed is UpdateDecision.Available)

        val notNewer = UpdatePolicy.decide("0.15.0", release("v0.15.0-rc.1", prerelease = true), true)
        assertTrue("预发布与正式版数字段相同 → 不算新", notNewer is UpdateDecision.UpToDate)
    }

    @Test
    fun `draft 与认不出的 tag 都不会提示更新`() {
        assertEquals(UpdateDecision.NoRelease, UpdatePolicy.decide("0.14.0", null, true))
        assertEquals(UpdateDecision.NoRelease, UpdatePolicy.decide("0.14.0", release("v0.15.0", draft = true), true))
        assertEquals(UpdateDecision.Unparseable("nightly"), UpdatePolicy.decide("0.14.0", release("nightly"), true))
        assertTrue(
            "本机版本号认不出来时按「不提示」处理（构建配置坏了，不该骚扰用户）",
            UpdatePolicy.decide("", release("v0.15.0"), true) is UpdateDecision.UpToDate
        )
    }

    // ------------------------------------------------------------------ 摘要 / URL

    @Test
    fun `发布说明摘要去掉 markdown 噪音并截断`() {
        val summary = UpdatePolicy.summarize(
            "## 新增\n- **检查更新**\n- [备份](https://example.com)\n\n> 引用\n`code`",
            maxChars = 200
        )
        assertFalse(summary.contains("##"))
        assertFalse(summary.contains("**"))
        assertFalse(summary.contains("https://example.com"))
        assertTrue(summary.contains("检查更新"))
        assertTrue(summary.contains("备份"))

        val long = UpdatePolicy.summarize("x".repeat(1000), maxChars = 420)
        assertTrue("超长要截断并带省略号", long.length <= 421 && long.endsWith("…"))
    }

    @Test
    fun `只有 https 的发布页地址才允许打开`() {
        assertTrue(UpdatePolicy.isSafeReleaseUrl("https://github.com/a/b/releases/tag/v1"))
        assertFalse(UpdatePolicy.isSafeReleaseUrl("http://github.com/a/b"))
        assertFalse(UpdatePolicy.isSafeReleaseUrl("javascript:alert(1)"))
        assertFalse(UpdatePolicy.isSafeReleaseUrl("intent://evil"))
        assertFalse(UpdatePolicy.isSafeReleaseUrl("file:///etc/passwd"))
        assertFalse(UpdatePolicy.isSafeReleaseUrl("https://evil.com/ with space"))
        assertFalse(UpdatePolicy.isSafeReleaseUrl(null))
    }

    // ------------------------------------------------------------------ 节流

    @Test
    fun `自动检查 24 小时最多一次`() {
        val now = 1_800_000_000_000L
        val hour = 60L * 60L * 1000L
        assertTrue("从没查过 → 该查", UpdateThrottle.isDue(now, 0L))
        assertFalse("刚查过 → 不该查", UpdateThrottle.isDue(now, now))
        assertFalse("23 小时后 → 还不到", UpdateThrottle.isDue(now, now - 23 * hour))
        assertTrue("24 小时后 → 该查", UpdateThrottle.isDue(now, now - 24 * hour))
        assertTrue("25 小时后 → 该查", UpdateThrottle.isDue(now, now - 25 * hour))
        assertTrue(
            "系统时间被往回拨（上次时间在「未来」）也要能恢复检查，不然会被永久卡住",
            UpdateThrottle.isDue(now, now + 48 * hour)
        )
    }
}
