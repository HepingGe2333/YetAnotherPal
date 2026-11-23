package com.yap.app.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.2 更新渠道的界面文案：中英两边都要有，且英文不能是「没翻的中文」。
 *
 * `StringResourceParityTest` 已经保证 key 集合与占位符一致；这里补的是**这一组新文案**
 * 的显式清单——加一条中文却忘了英文时，报错会直接点名是哪一条，
 * 而不是让 review 去两个大 XML 里对。
 *
 * 注意：0.15 里这个文件还顺带守了「备份版本信息」那组文案（`backup_dialog_format*` /
 * `backup_fail_checksum`）。那是 0.15 的备份 v7 + checksum 功能的一部分，0.14.2 没有那份
 * 备份格式，所以这里只守更新渠道自己的 key。
 */
class UpdateStringsTest {

    private val updateKeys = listOf(
        "settings_section_update",
        "settings_update_check_title",
        "settings_update_desc",
        "settings_update_checking",
        "settings_update_up_to_date",
        "settings_update_available",
        "settings_update_skipped_prerelease",
        "settings_update_unavailable",
        "settings_update_failed",
        "settings_update_auto_title",
        "settings_update_auto_desc",
        "settings_update_prerelease_title",
        "settings_update_prerelease_desc",
        "settings_update_install_title",
        "settings_update_install_desc",
        "update_dialog_title",
        "update_dialog_prerelease",
        "update_dialog_no_notes",
        "update_dialog_later",
        "update_dialog_download"
    )

    @Test
    fun `更新的新文案中英都在`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        updateKeys.forEach { key ->
            assertTrue("values/ 缺少 $key", zh.containsKey(key))
            assertTrue("values-en/ 缺少 $key", en.containsKey(key))
        }
    }

    @Test
    fun `英文文案是真的英文 不是没翻的中文`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        updateKeys.forEach { key ->
            val zhText = zh.getValue(key)
            val enText = en.getValue(key)
            assertTrue("$key 的中文是空的", zhText.isNotBlank())
            assertTrue("$key 的英文是空的", enText.isNotBlank())
            assertFalse("$key 的英文里还有中文：$enText", CJK.containsMatchIn(enText))
            assertFalse("$key 的英文看起来是中文原文", enText == zhText)
        }
    }

    /** 「检查更新」的几条状态句必须说人话，不能把枚举名直接上屏。 */
    @Test
    fun `状态行不是枚举名 也不提技术细节`() {
        val zh = TestResources.strings("values")
        listOf(
            "settings_update_checking",
            "settings_update_up_to_date",
            "settings_update_available",
            "settings_update_skipped_prerelease",
            "settings_update_unavailable",
            "settings_update_failed"
        ).forEach { key ->
            val text = zh.getValue(key)
            assertTrue("$key 不该出现枚举名/英文技术词：$text", !text.contains("Update") && !text.contains("_"))
        }
    }

    @Test
    fun `失败文案只说稍后再试 不指责用户也不提限流`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        val zhFailed = zh.getValue("settings_update_failed")
        assertTrue("失败要留一条可再试的出路", zhFailed.contains("再试") || zhFailed.contains("稍后"))
        listOf(zhFailed, en.getValue("settings_update_failed")).forEach { text ->
            listOf("403", "API", "token", "Token", "限流").forEach { jargon ->
                assertTrue("失败文案不该暴露技术细节「$jargon」：$text", !text.contains(jargon))
            }
        }
    }

    private companion object {
        val CJK = Regex("[\\u3000-\\u303f\\uff00-\\uffef\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    }
}
