package com.yap.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 更新渠道的**接线契约**（0.15）：逻辑单测全绿、发布包里却没接上线，是这类功能最常见的失败形态。
 *
 * 这里只钉最容易掉的三处：
 * - 地址真的是那个仓库、请求真的带 User-Agent（GitHub 没 UA 直接 403）；
 * - 设置页与前台启动都真的会触发静默检查，且「手动检查」不被节流挡住；
 * - 24h 节流的时间/版本真的落在 DataStore（重启后仍然有效），而不是一个内存变量。
 */
class UpdateWiringContractTest {

    private fun projectRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录，当前工作目录：${File(".").absolutePath}")

    private fun source(path: String): String = File(projectRoot(), path).readText()

    private val checker get() = source("src/main/java/com/yap/app/data/update/UpdateChecker.kt")
    private val coordinator get() = source("src/main/java/com/yap/app/data/update/UpdateCoordinator.kt")
    private val settingsRepo get() = source("src/main/java/com/yap/app/data/prefs/SettingsRepository.kt")
    private val settingsScreen get() = source("src/main/java/com/yap/app/ui/screens/settings/SettingsScreen.kt")
    private val rootViewModel get() = source("src/main/java/com/yap/app/ui/RootViewModel.kt")
    private val settingsViewModel get() = source("src/main/java/com/yap/app/ui/screens/settings/SettingsViewModel.kt")
    private val mainActivity get() = source("src/main/java/com/yap/app/MainActivity.kt")
    private val appModule get() = source("src/main/java/com/yap/app/di/AppModule.kt")

    @Test
    fun `请求的仓库地址与 User-Agent 都在`() {
        assertTrue(
            "地址必须是项目自己的 releases/latest",
            checker.contains("https://api.github.com/repos/HepingGe2333/YetAnotherPal/releases/latest")
        )
        assertTrue(
            "「包含预发布」要查列表接口（latest 永远跳过 prerelease）",
            checker.contains(
                "https://api.github.com/repos/HepingGe2333/YetAnotherPal/releases?per_page=10"
            )
        )
        assertTrue("GitHub API 要求 User-Agent", checker.contains(".header(\"User-Agent\""))
        assertTrue("带 Accept: application/vnd.github+json", checker.contains("application/vnd.github+json"))
        // 失败静默：不抛异常，只返回 Failed。
        assertTrue("失败要落成 Failed 而不是 throw", coordinator.contains("is UpdateCheckOutcome.Failed"))
    }

    @Test
    fun `前台启动与设置页都会静默检查一次`() {
        assertTrue(
            "App 启动（前台）时 RootViewModel 要触发自动检查",
            rootViewModel.contains("updateCoordinator.maybeCheckOnStart()")
        )
        assertTrue(
            "打开设置页时也要静默检查一次",
            settingsScreen.contains("viewModel.maybeAutoCheckUpdates()")
        )
        assertTrue("设置页有检查更新的入口", settingsScreen.contains("UpdateSection("))
        assertTrue("设置页也能手动检查", settingsViewModel.contains("updateCoordinator.checkNow()"))
        // 失败不弹错误：状态栏只有枚举，不写 message。
        val checkBody = settingsViewModel
            .substringAfter("fun checkForUpdates()")
            .substringBefore("fun maybeAutoCheckUpdates()")
        assertFalse("检查更新失败不能塞进设置页的错误 message", checkBody.contains("message.value"))
    }

    @Test
    fun `发现新版本弹对话框 稍后与下载都能走`() {
        assertTrue("对话框挂在 MainActivity（全 App 一份）", mainActivity.contains("UpdateAvailableDialog("))
        assertTrue("去下载打开发布页", mainActivity.contains("openReleasePage("))
        assertTrue("稍后只是收起对话框", mainActivity.contains("dismissUpdatePrompt"))
        assertTrue("下载地址只接受 https", source("src/main/java/com/yap/app/ui/screens/settings/UpdateSection.kt")
            .contains("UpdatePolicy.isSafeReleaseUrl(url)"))
    }

    @Test
    fun `设置页里有当前版本 最新版本与更新按钮`() {
        val updateSection = source("src/main/java/com/yap/app/ui/screens/settings/UpdateSection.kt")
        assertTrue("当前版本要一直显示", updateSection.contains("update.currentVersion"))
        assertTrue("有新版本时显示最新版本号", updateSection.contains("update.availableVersion"))
        assertTrue(
            "「更新」按钮只打开 https 发布页（不自动下载、不装 APK）",
            updateSection.contains("openReleasePage(context, update.availableUrl)")
        )
        assertTrue("状态行覆盖七种结果", updateSection.contains("UpdateCheckStatus.FAILED"))
        assertTrue(
            "发布页地址来自 release，而不是拼出来的",
            coordinator.contains("availableUrl = decision.release.htmlUrl")
        )
        // 不自动下载：整个更新链路里没有下载/安装动作。
        listOf(updateSection, coordinator, checker).forEach { text ->
            listOf("DownloadManager", "PackageInstaller", "REQUEST_INSTALL_PACKAGES").forEach { action ->
                assertFalse("更新检查不该自动下载/安装（出现 $action）", text.contains(action))
            }
        }
    }

    @Test
    fun `24 小时节流靠 DataStore 里的时间与版本 不是内存变量`() {
        assertTrue("节流规则来自 domain", coordinator.contains("UpdateThrottle.isDue("))
        assertTrue("自动检查受开关约束", coordinator.contains("settings.updateCheckEnabled"))
        assertTrue(
            "上次检查时间要落 DataStore",
            settingsRepo.contains("update_last_checked_at") &&
                settingsRepo.contains("fun setUpdateCheckState(")
        )
        assertTrue(
            "同一个版本只弹一次，需要记住上次提示的版本",
            settingsRepo.contains("update_last_notified_version") &&
                coordinator.contains("previousNotified")
        )
        assertTrue("手动检查不受节流限制", coordinator.contains("suspend fun checkNow()"))
        assertTrue("检查器由 DI 提供（复用全 App 的 OkHttp）", appModule.contains("fun provideUpdateChecker("))
    }
}
