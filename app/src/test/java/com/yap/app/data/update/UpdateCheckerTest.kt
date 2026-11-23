package com.yap.app.data.update

import com.yap.app.data.remote.FakeHttpTestServer
import com.yap.app.domain.UpdateDecision
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UpdateChecker` 的 HTTP 行为（0.15）：真实 socket、真实 OkHttp，不 mock。
 *
 * 用仓库里现成的 [FakeHttpTestServer]（手写 HTTP/1.1 的假服务端），因为要验的是
 * 「带没带 User-Agent」「非 2xx 是不是静默失败」「坏 JSON 会不会炸」这些传输层行为。
 *
 * 重点不是「能解析成功」，而是**失败一律不抛异常**：更新检查失败不该打断任何操作。
 */
class UpdateCheckerTest {

    private fun checker(
        baseUrl: String,
        currentVersion: String = "0.14.0",
        logger: (String) -> Unit = {},
        readTimeoutMillis: Long = 10_000L
    ) = UpdateChecker(
        httpClient = OkHttpClient(),
        endpoint = "$baseUrl/repos/HepingGe2333/YetAnotherPal/releases/latest",
        listEndpoint = "$baseUrl/repos/HepingGe2333/YetAnotherPal/releases?per_page=10",
        currentVersion = currentVersion,
        logger = logger,
        connectTimeoutMillis = 2_000L,
        readTimeoutMillis = readTimeoutMillis,
        callTimeoutMillis = readTimeoutMillis + 2_000L
    )

    private fun releaseJson(tag: String, prerelease: Boolean = false, draft: Boolean = false) = """
        {
          "tag_name": "$tag",
          "name": "release $tag",
          "body": "- 修好了备份",
          "html_url": "https://github.com/HepingGe2333/YetAnotherPal/releases/tag/$tag",
          "published_at": "2026-11-20T10:00:00Z",
          "prerelease": $prerelease,
          "draft": $draft
        }
    """.trimIndent()

    @Test
    fun `成功拿到更新的 release 时给出 Available 并带上 User-Agent`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson(releaseJson("v0.15.0"))
            val outcome = checker(server.baseUrl).check(includePrereleases = false)
            val decision = (outcome as UpdateCheckOutcome.Decided).decision
            assertTrue("0.15.0 > 0.14.0", decision is UpdateDecision.Available)
            assertEquals("v0.15.0", (decision as UpdateDecision.Available).release.tagName)

            val request = server.requests.single()
            assertEquals("GET", request.method)
            assertEquals("/repos/HepingGe2333/YetAnotherPal/releases/latest", request.path)
            assertEquals(
                "GitHub API 没有 User-Agent 会直接 403",
                "YetAnotherPal-Android/0.14.0",
                request.headers["user-agent"]
            )
            assertEquals("application/vnd.github+json", request.headers["accept"])
        }
    }

    @Test
    fun `打开包含预发布后改查 releases 列表 并提示 beta`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson(
                """
                [
                  {"tag_name":"v0.15.0-rc.1","name":"RC","prerelease":true,
                   "html_url":"https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v0.15.0-rc.1"},
                  {"tag_name":"v0.14.0","name":"stable","prerelease":false,
                   "html_url":"https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v0.14.0"}
                ]
                """.trimIndent()
            )
            val outcome = checker(server.baseUrl).check(includePrereleases = true)
            val decision = (outcome as UpdateCheckOutcome.Decided).decision
            assertTrue("开了开关就该看到 beta", decision is UpdateDecision.Available)
            assertEquals(
                "v0.15.0-rc.1",
                (decision as UpdateDecision.Available).release.tagName
            )
            assertEquals(
                "includePrereleases=true 必须查列表接口",
                "/repos/HepingGe2333/YetAnotherPal/releases",
                server.requests.single().path
            )
        }
    }

    @Test
    fun `列表为空时结论是 NoRelease 而不是失败`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson("[]")
            val outcome = checker(server.baseUrl).check(includePrereleases = true)
            assertEquals(
                UpdateCheckOutcome.Decided(UpdateDecision.NoRelease),
                outcome
            )
        }
    }

    @Test
    fun `列表 JSON 坏掉时静默失败`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson("""{"message":"weird"}""")
            assertEquals(
                UpdateCheckOutcome.Failed(UpdateFailure.Unreadable("malformed_json")),
                checker(server.baseUrl).check(includePrereleases = true)
            )
        }
    }

    @Test
    fun `本机已是最新时不提示`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson(releaseJson("v0.14.0"))
            val outcome = checker(server.baseUrl, currentVersion = "0.14.0-debug").check(false)
            val decision = (outcome as UpdateCheckOutcome.Decided).decision
            assertTrue(decision is UpdateDecision.UpToDate)
        }
    }

    @Test
    fun `限流 403 只返回失败 不抛异常`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson("""{"message":"API rate limit exceeded"}""", status = 403)
            val logs = mutableListOf<String>()
            val outcome = checker(server.baseUrl, logger = { logs += it }).check(false)
            assertEquals(UpdateCheckOutcome.Failed(UpdateFailure.Http(403)), outcome)
            assertTrue("失败要留一行日志（界面不显示）", logs.any { it.contains("403") })
        }
    }

    @Test
    fun `5xx 与 404 也只返回失败`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson("""{"message":"boom"}""", status = 500)
            assertEquals(UpdateCheckOutcome.Failed(UpdateFailure.Http(500)), checker(server.baseUrl).check(false))

            server.respondJson("""{"message":"Not Found"}""", status = 404)
            assertEquals(UpdateCheckOutcome.Failed(UpdateFailure.Http(404)), checker(server.baseUrl).check(false))
        }
    }

    @Test
    fun `200 但 JSON 坏掉时返回 Unreadable 而不是崩`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson("<!doctype html><html>rate limited</html>")
            assertEquals(
                UpdateCheckOutcome.Failed(UpdateFailure.Unreadable("malformed_json")),
                checker(server.baseUrl).check(false)
            )
        }
    }

    @Test
    fun `200 但没有 tag_name 时也按失败处理`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondJson("""{"name":"no tag here"}""")
            assertEquals(
                UpdateCheckOutcome.Failed(UpdateFailure.Unreadable("missing_tag")),
                checker(server.baseUrl).check(false)
            )
        }
    }

    @Test
    fun `响应体超过上限时静默失败`() = runBlocking {
        FakeHttpTestServer().use { server ->
            // 合法 JSON 但远超 512 KiB 的上限：不能让一份巨大的响应把检查链路拖垮。
            val huge = """{"tag_name":"v0.15.0","body":"${"x".repeat(UpdateChecker.MAX_BODY_BYTES + 1024)}"}"""
            server.respondJson(huge)
            val outcome = checker(server.baseUrl).check(false)
            assertEquals(
                UpdateCheckOutcome.Failed(UpdateFailure.Unreadable("body_too_large")),
                outcome
            )
        }
    }

    @Test
    fun `连不上时返回 Network 失败 不抛异常`() = runBlocking {
        // 先起一个服务端拿到端口再关掉：请求一定被拒绝，且不需要等超时。
        val server = FakeHttpTestServer()
        val baseUrl = server.baseUrl
        server.close()
        val outcome = checker(baseUrl).check(false)
        assertTrue("连不上要落成 Failed(Network)", outcome is UpdateCheckOutcome.Failed)
        assertTrue((outcome as UpdateCheckOutcome.Failed).failure is UpdateFailure.Network)
    }

    @Test
    fun `读超时也算失败 不崩`() = runBlocking {
        FakeHttpTestServer().use { server ->
            server.respondAfter(1_500, releaseJson("v0.15.0"))
            val outcome = checker(server.baseUrl, readTimeoutMillis = 200L).check(false)
            assertTrue("读超时要落成 Failed", outcome is UpdateCheckOutcome.Failed)
        }
    }
}
