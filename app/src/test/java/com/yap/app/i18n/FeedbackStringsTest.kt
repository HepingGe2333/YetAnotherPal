package com.yap.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈功能的中英文案守卫（0.14.2）。
 *
 * 现有的 `StringResourceParityTest` 已经保证两个语言的 key 集合与占位符一致；
 * 这里只多钉两件这个功能特有的事：
 * 1. 反馈那一组 key **确实存在且两边都有**（新增功能最容易「只加了中文」）；
 * 2. 英文目录里没有漏进来的中文（复制粘贴时最常见的事故），中文目录里也不是纯英文占位。
 *
 * 只比一组固定 key 而不是整个文件的原因：这组文案是这个功能的「对外接口」，
 * 改名字（比如 `feedback_submit` → `feedback_send`）会同时让界面和这里变红，
 * 不至于悄悄换掉一个用户看得见的字。
 */
class FeedbackStringsTest {

    private val zh by lazy { TestResources.strings("values") }
    private val en by lazy { TestResources.strings("values-en") }

    private val feedbackKeys = listOf(
        "settings_section_feedback",
        "settings_feedback_title",
        "settings_feedback_desc",
        "feedback_dialog_title",
        "feedback_kind_section",
        "feedback_kind_problem",
        "feedback_kind_suggestion",
        "feedback_kind_other",
        "feedback_content_label",
        "feedback_content_hint",
        "feedback_content_counter",
        "feedback_contact_label",
        "feedback_contact_hint",
        "feedback_submit",
        "feedback_cancel",
        "feedback_error_empty",
        "feedback_error_too_long",
        "feedback_error_contact_too_long",
        "feedback_saved",
        "feedback_saved_open_failed",
        "feedback_save_failed",
        "feedback_saved_where"
    )

    @Test
    fun `反馈用到的 key 中英文都有且非空`() {
        feedbackKeys.forEach { key ->
            val zhValue = zh[key] ?: error("values/strings.xml 里缺 $key")
            val enValue = en[key] ?: error("values-en/strings.xml 里缺 $key")
            assertTrue("$key 的中文是空的", zhValue.isNotBlank())
            assertTrue("$key 的英文是空的", enValue.isNotBlank())
        }
    }

    @Test
    fun `英文文案里没有漏进来的中文`() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val leaked = feedbackKeys.filter { key -> cjk.containsMatchIn(en.getValue(key)) }
        assertEquals("这些英文条目里还有中文：$leaked", emptyList<String>(), leaked)
    }

    @Test
    fun `中文条目确实是中文（不是照抄英文占位）`() {
        val zhSamples = listOf(
            "feedback_dialog_title",
            "feedback_submit",
            "feedback_saved",
            "feedback_error_empty"
        )
        assertTrue(
            "中文条目应当有中文字符：$zhSamples",
            zhSamples.all { key -> Regex("[\\u4e00-\\u9fff]").containsMatchIn(zh.getValue(key)) }
        )
    }

    @Test
    fun `带参数的文案两边占位符一致`() {
        assertEquals(
            "计数文案要同时有当前字数与上限",
            true,
            zh.getValue("feedback_content_counter").contains("%1\$d") &&
                zh.getValue("feedback_content_counter").contains("%2\$d")
        )
        assertEquals(
            "英文计数文案同样两个参数",
            true,
            en.getValue("feedback_content_counter").contains("%1\$d") &&
                en.getValue("feedback_content_counter").contains("%2\$d")
        )
        assertTrue("超长提示要带上限参数", zh.getValue("feedback_error_too_long").contains("%1\$d"))
        assertTrue("超长提示要带上限参数", en.getValue("feedback_error_too_long").contains("%1\$d"))
        assertTrue("联系方式超长提示要带上限参数", zh.getValue("feedback_error_contact_too_long").contains("%1\$d"))
        assertTrue("联系方式超长提示要带上限参数", en.getValue("feedback_error_contact_too_long").contains("%1\$d"))
    }

    @Test
    fun `保存成功的提示说清楚是本地保存 要去浏览器提交`() {
        assertTrue("不说清楚「已经保存」用户会以为丢了", zh.getValue("feedback_saved").contains("已保存"))
        assertTrue("要告诉用户去哪里提交", zh.getValue("feedback_saved").contains("浏览器"))
        assertFalse("不能只说「已提交」（这一版不会自动提交）", zh.getValue("feedback_saved").contains("已提交"))
        assertTrue(en.getValue("feedback_saved").contains("Saved"))
        assertTrue(en.getValue("feedback_saved").contains("browser"))
    }
}
