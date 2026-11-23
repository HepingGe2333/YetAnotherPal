package com.yap.app.data.local

import com.yap.app.domain.Feedback
import com.yap.app.domain.FeedbackDraft
import com.yap.app.domain.FeedbackKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 反馈落盘（`files/feedback/feedback-<ts>.json`）。
 *
 * 产品上这一段是「最后一道保险」：没有 token、不自动提交，用户最后的提交动作在浏览器里；
 * 浏览器打不开、用户改主意、手机切后台，内容都得还在。所以这里盯：
 * 1. 文件名与目录（需求逐字要求 `files/feedback/feedback-<ts>.json`）；
 * 2. JSON 里必须有 appVersion / 平台 / 时间（排查问题靠它）；
 * 3. **原子写**：写完不留 `.tmp`，且同一毫秒内再写一条不会覆盖上一条。
 */
class FeedbackStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 固定时钟：文件名与 JSON 里的时间都可预测。 */
    private val fixedNow = 1_763_770_800_000L

    private fun store(
        root: File = File(temp.root, "feedback"),
        now: () -> Long = { fixedNow }
    ) = FeedbackStore(
        root = root,
        appVersion = "0.15.0",
        platform = "android",
        now = now
    )

    private fun draft(
        content: String = "设置页点反馈没有反应",
        contact: String = "",
        kind: FeedbackKind = FeedbackKind.PROBLEM
    ) = FeedbackDraft(kind = kind, content = content, contact = contact)

    private fun parse(file: File) = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `写到 feedback 目录下 文件名是 feedback-时间戳 json`() {
        val root = File(temp.root, "feedback")
        val file = store(root).save(draft())

        assertTrue("目录必须自动创建", root.isDirectory)
        assertTrue("文件必须落在给定目录里", file.parentFile == root)
        assertEquals("feedback-$fixedNow.json", file.name)
        assertTrue(file.name.matches(Regex("feedback-\\d+\\.json")))
        assertTrue(file.isFile)
    }

    @Test
    fun `JSON 带版本 平台 时间 内容与类型`() {
        val record = parse(store().save(draft(kind = FeedbackKind.SUGGESTION, contact = "me@example.com")))

        assertEquals("SUGGESTION", record["kind"]!!.jsonPrimitive.content)
        assertEquals("设置页点反馈没有反应", record["content"]!!.jsonPrimitive.content)
        assertEquals("me@example.com", record["contact"]!!.jsonPrimitive.content)
        assertEquals("0.15.0", record["appVersion"]!!.jsonPrimitive.content)
        assertEquals("android", record["platform"]!!.jsonPrimitive.content)
        assertEquals(fixedNow.toString(), record["createdAt"]!!.jsonPrimitive.content)
        assertEquals("2025-11-22T00:20:00Z", record["createdAtIso"]!!.jsonPrimitive.content)
    }

    @Test
    fun `存下来的链接与领域层生成的一致 且是安全的 https 地址`() {
        val d = draft(content = "中文 内容")
        val record = parse(store().save(d))

        val saved = record["issueUrl"]!!.jsonPrimitive.content
        assertEquals(
            Feedback.issueUrl(d, appVersion = "0.15.0", platform = "android", atMillis = fixedNow),
            saved
        )
        assertTrue(Feedback.isSafeIssueUrl(saved))
    }

    @Test
    fun `原子写不留下 tmp 文件`() {
        val root = File(temp.root, "feedback")
        store(root).save(draft())

        val leftovers = root.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("不许留下半截文件：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `同一毫秒内写两条不会覆盖前一条`() {
        val root = File(temp.root, "feedback")
        val first = store(root).save(draft(content = "第一条"))
        val second = store(root).save(draft(content = "第二条"))

        assertTrue("两个文件都要在", first.isFile && second.isFile)
        assertFalse("文件名必须不同", first.name == second.name)
        assertEquals("feedback-$fixedNow.json", first.name)
        assertEquals("feedback-$fixedNow-2.json", second.name)
        assertEquals("第一条", parse(first)["content"]!!.jsonPrimitive.content)
        assertEquals("第二条", parse(second)["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `内容去首尾空白 空联系方式存 null`() {
        val record = parse(store().save(draft(content = "  有前有后  \n", contact = "   ")))

        assertEquals("有前有后", record["content"]!!.jsonPrimitive.content)
        assertEquals(
            "没填就是 null，不写空串",
            JsonNull,
            record["contact"]
        )
    }

    @Test
    fun `目录已存在时重复写也没问题`() {
        val root = File(temp.root, "feedback")
        root.mkdirs()
        assertTrue(store(root).save(draft()).isFile)
        assertTrue(store(root, now = { fixedNow + 1 }).save(draft()).isFile)
        assertEquals(2, root.listFiles().orEmpty().count { it.name.endsWith(".json") })
    }
}
