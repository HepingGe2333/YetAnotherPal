package com.yap.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 反馈的**接线契约**（0.14.2）。
 *
 * 0.15 有 `UpdateWiringContractTest`，却没有对应的反馈版本——而反馈这一版最危险的两条恰恰是
 * 「接错线」：接上了一个会替用户提交 Issue 的调用，或者把反馈写进了 Room（于是要么加迁移，
 * 要么写丢）。所以这里直接扫源码钉死四件事：
 *
 * 1. 只落盘到 `files/feedback`，**原子写**，不碰数据库；
 * 2. **没有** token、没有 HTTP：这一版不自动提交，只生成预填链接；
 * 3. 顺序是「先保存 → 再打开浏览器」，失败时不关弹窗、不丢用户写的内容；
 * 4. 界面上真的有这个入口（设置页 → 反馈 → 表单）。
 */
class FeedbackWiringContractTest {

    private fun projectRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录，当前工作目录：${File(".").absolutePath}")

    private fun source(path: String): String = File(projectRoot(), path).readText()

    /** 只看代码：注释里提到「token」是解释，不是实现。 */
    private fun codeOnly(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    private val store get() = source("src/main/java/com/yap/app/data/local/FeedbackStore.kt")
    private val repository get() = source("src/main/java/com/yap/app/data/repo/FeedbackRepository.kt")
    private val domain get() = source("src/main/java/com/yap/app/domain/Feedback.kt")
    private val section get() = source("src/main/java/com/yap/app/ui/screens/settings/FeedbackSection.kt")
    private val settingsScreen get() = source("src/main/java/com/yap/app/ui/screens/settings/SettingsScreen.kt")
    private val settingsViewModel get() = source("src/main/java/com/yap/app/ui/screens/settings/SettingsViewModel.kt")

    @Test
    fun `反馈落在 files feedback 里 走原子写 不进数据库`() {
        assertTrue(
            "目录名必须是需求里的 files/feedback",
            repository.contains("FEEDBACK_DIR = \"feedback\"")
        )
        assertTrue(
            "文件必须落在 context.filesDir 下（不是外部存储、不是 Room）",
            repository.contains("File(context.filesDir, FEEDBACK_DIR)")
        )
        assertTrue("要原子写：先写 .tmp", store.contains(".tmp"))
        assertTrue("再 rename 落到正式文件名", store.contains("renameTo(target)"))
        assertTrue("文件名形状要可读：feedback-<ts>.json", store.contains("\"feedback-\$stamp.json\""))
        // 0.14.2 不引入 0.15 的 schema v20：反馈没有数据库这一份。
        listOf(store, repository).forEach { text ->
            assertFalse(
                "反馈不该出现在 Room 里",
                text.contains("YapDatabase") || text.contains("@Entity") || text.contains("@Dao")
            )
            assertFalse("反馈不该写迁移", text.contains("Migration"))
        }
    }

    @Test
    fun `不收集 token 也不自动提交 只生成预填链接`() {
        listOf(store, repository).forEach { text ->
            val code = codeOnly(text)
            assertFalse("这一版没有 GitHub 凭据", code.contains("Authorization"))
            assertFalse(
                "不该有任何 token 变量/参数（凭据不进设备）",
                Regex("\\btoken\\b", RegexOption.IGNORE_CASE).containsMatchIn(code)
            )
            assertFalse(
                "不该发 HTTP 请求（否则就是「自动提交」）",
                code.contains("OkHttp") || code.contains("Request.Builder")
            )
        }
        assertTrue(
            "交给浏览器的必须是仓库的 new-issue 页面",
            domain.contains("https://github.com/HepingGe2333/YetAnotherPal/issues/new")
        )
        assertTrue("打开前要过 https + host + path 校验", domain.contains("fun isSafeIssueUrl("))
    }

    @Test
    fun `先保存再打开浏览器 失败时弹窗留在原地`() {
        // ViewModel：保存成功才发 FeedbackReady（界面据此打开浏览器），失败只提示。
        assertTrue("保存入口在仓储层", settingsViewModel.contains("feedbackRepository.submit("))
        assertTrue("成功事件带的是预填链接", settingsViewModel.contains("SettingsEvent.FeedbackReady(receipt.issueUrl)"))
        val failureBranch = settingsViewModel
            .substringAfter("}.onFailure { error ->", missingDelimiterValue = "")
            .substringBefore("}", missingDelimiterValue = "")
        assertTrue("失败时只提示、不发事件：$failureBranch", failureBranch.contains("feedback_save_failed"))

        // 界面：收到事件才打开浏览器；打不开时补一句「已保存但没能打开」。
        assertTrue("设置页要处理 FeedbackReady", settingsScreen.contains("is SettingsEvent.FeedbackReady ->"))
        assertTrue("打开浏览器的调用在 UI 层", section.contains("fun openFeedbackIssue("))
        assertTrue("只打开校验过的链接", section.contains("if (!Feedback.isSafeIssueUrl(url)) return false"))
        assertTrue(
            "打不开浏览器要补一句「已保存」",
            settingsScreen.contains("R.string.feedback_saved_open_failed")
        )
        val readyHandler = settingsScreen
            .substringAfter("is SettingsEvent.FeedbackReady ->")
            .substringBefore("LaunchedEffect(state.message)")
        assertFalse("保存成功才关弹窗，事件处理里不该再顺手 dismiss", readyHandler.contains("dismiss"))
    }

    @Test
    fun `设置页真的有反馈表单 类型 内容 联系方式 都在`() {
        assertTrue("设置页有反馈入口", settingsScreen.contains("settings_feedback_title"))
        assertTrue("点开是表单弹窗", settingsScreen.contains("FeedbackDialog("))
        assertTrue("提交走 ViewModel", settingsScreen.contains("viewModel.submitFeedback("))
        listOf(
            "feedback_kind_problem",
            "feedback_kind_suggestion",
            "feedback_kind_other"
        ).forEach { key ->
            assertTrue("类型选项缺 $key", section.contains("R.string.$key"))
        }
        assertTrue("内容输入要按上限截断", section.contains("value.take(Feedback.MAX_CONTENT_CHARS)"))
        assertTrue("联系方式可选、也要截断", section.contains("value.take(Feedback.MAX_CONTACT_CHARS)"))
        assertTrue("提交前先本地校验", section.contains("Feedback.validate(draft)"))
        assertTrue("输入时就显示字数", section.contains("R.string.feedback_content_counter"))
        assertTrue("要说清楚存在哪（不是自动提交）", section.contains("R.string.feedback_saved_where"))
    }
}
