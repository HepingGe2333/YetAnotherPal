package com.yap.app.data.update

import com.yap.app.BuildConfig
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.ReleaseInfo
import com.yap.app.domain.UpdateDecision
import com.yap.app.domain.UpdateThrottle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 「这一轮检查的结论」——界面按它决定显示哪一行字。 */
enum class UpdateCheckStatus {
    /** 还没查过（或清空数据后）。 */
    NEVER,

    /** 正在检查。 */
    CHECKING,

    /** 已是最新版本。 */
    UP_TO_DATE,

    /** 有新版本（或已经弹过同一个版本）。 */
    AVAILABLE,

    /** 远端最新是预发布版，而用户没开「包含预发布版本」。 */
    SKIPPED_PRERELEASE,

    /** 没有可用的 release / 版本号认不出来。 */
    UNAVAILABLE,

    /** 网络失败、限流 403、坏 JSON：**只显示一行状态，不弹错误**。 */
    FAILED
}

/**
 * 设置页「检查更新」与前台自动检查共享的状态。
 *
 * 为什么放在一个单例而不是 ViewModel：自动检查由 `RootViewModel` 在 App 回到前台时发起，
 * 手动检查由设置页发起，而「发现新版本」的对话框只应该有一份（挂在 `MainActivity`）。
 * 一个 coordinator 同时给两边提供状态，就不会出现「两个地方各弹一次」。
 *
 * 记录写在 DataStore（[SettingsRepository]）：24h 节流跨进程重启也有效。
 */
data class UpdateUiState(
    val checking: Boolean = false,
    val status: UpdateCheckStatus = UpdateCheckStatus.NEVER,
    /** 远端新版本号（`0.15.0`），没有新版本时为 null。 */
    val availableVersion: String? = null,
    /**
     * 远端新版本的发布页地址（`html_url`），没有新版本时为空串。
     *
     * 设置页用它做「更新到 vX」那一行的按钮：**手工点开浏览器**去发布页，
     * 不在后台自动下载、也不装 APK。地址在打开前还要过一次 https 校验
     * （[com.yap.app.domain.UpdatePolicy.isSafeReleaseUrl]）。
     */
    val availableUrl: String = "",
    /** 需要弹对话框的发布信息；null = 不弹。 */
    val prompt: ReleaseInfo? = null,
    /** 本机版本号（界面上的「当前版本」）。 */
    val currentVersion: String = "",
    /** 这次检查是不是用户手动点的（决定「已是最新」这类话要不要显示）。 */
    val manual: Boolean = false,
    /** 上次检查的时刻；0 = 从没查过。 */
    val lastCheckedAt: Long = 0L
)

/**
 * 更新检查的协调器（单例）。
 *
 * 规则：
 * - 自动检查受设置开关控制，且最多 24 小时一次（[UpdateThrottle]）；
 * - 手动检查不受开关与节流限制（用户点了就该查）；
 * - 同一个版本只弹一次窗（`updateLastNotifiedVersion`），之后状态行仍显示「有新版本」；
 * - 任何失败都只落 [UpdateCheckStatus.FAILED]，**不抛异常、不弹错误**。
 */
@Singleton
class UpdateCoordinator @Inject constructor(
    private val checker: UpdateChecker,
    private val settingsRepository: SettingsRepository
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(UpdateUiState(currentVersion = BuildConfig.VERSION_NAME))

    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** App 启动 / 回到前台时调用：受开关与 24h 节流约束。 */
    suspend fun maybeCheckOnStart(nowMillis: Long = System.currentTimeMillis()) {
        // 这里先做一次「便宜的」预判，避免明显不该查的时候还去抢锁；
        // 真正的判定在 runCheck 的锁内再做一次（设置页与前台启动几乎同时触发时，
        // 第二次必须在第一次写完时间戳之后重新判断，否则会发出两个一样的请求）。
        val settings = settingsRepository.settings.first()
        if (!settings.updateCheckEnabled) return
        if (!UpdateThrottle.isDue(nowMillis, settings.updateLastCheckedAt)) {
            // 节流命中：只把「上次查过」的事实同步给界面，不发请求。
            _state.update { it.copy(lastCheckedAt = settings.updateLastCheckedAt) }
            return
        }
        runCheck(manual = false, includePrereleases = settings.updateIncludePrereleases)
    }

    /** 设置页「检查更新」：不看开关、不看节流。 */
    suspend fun checkNow() {
        val settings = settingsRepository.settings.first()
        runCheck(manual = true, includePrereleases = settings.updateIncludePrereleases)
    }

    /** 用户点了「稍后」/点了下载：收起对话框（版本仍记着，24h 内不再打扰）。 */
    fun dismissPrompt() {
        _state.update { it.copy(prompt = null) }
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        settingsRepository.setUpdateCheckEnabled(enabled)
    }

    suspend fun setIncludePrereleases(enabled: Boolean) {
        settingsRepository.setUpdateIncludePrereleases(enabled)
    }

    private suspend fun runCheck(manual: Boolean, includePrereleases: Boolean) {
        mutex.withLock {
            if (_state.value.checking) return
            val settingsNow = settingsRepository.settings.first()
            if (!manual) {
                // 锁内复判：并发触发（前台启动 + 打开设置页）时只有第一次真的发请求。
                if (!settingsNow.updateCheckEnabled) return
                if (!UpdateThrottle.isDue(System.currentTimeMillis(), settingsNow.updateLastCheckedAt)) {
                    _state.update { it.copy(lastCheckedAt = settingsNow.updateLastCheckedAt) }
                    return
                }
            }
            _state.update {
                it.copy(checking = true, status = UpdateCheckStatus.CHECKING, manual = manual)
            }
            val previousNotified = settingsNow.updateLastNotifiedVersion
            val outcome = checker.check(includePrereleases)
            val now = System.currentTimeMillis()
            var notified = previousNotified
            var prompt: ReleaseInfo? = null
            var availableVersion: String? = null
            var availableUrl: String = ""
            var status: UpdateCheckStatus = UpdateCheckStatus.FAILED

            when (outcome) {
                is UpdateCheckOutcome.Failed -> {
                    // 失败/限流：不弹、不提示错误；把检查时间往后推，避免每次进设置页都再打一次。
                    status = UpdateCheckStatus.FAILED
                }

                is UpdateCheckOutcome.Decided -> when (val decision = outcome.decision) {
                    is UpdateDecision.Available -> {
                        availableVersion = decision.version.describe()
                        availableUrl = decision.release.htmlUrl
                        status = UpdateCheckStatus.AVAILABLE
                        // 自动检查时同一个版本只弹一次；手动检查（用户主动点）允许再弹。
                        val alreadyNotified = !manual && previousNotified == availableVersion
                        if (!alreadyNotified) {
                            prompt = decision.release
                            notified = availableVersion
                        }
                    }

                    is UpdateDecision.UpToDate -> {
                        status = UpdateCheckStatus.UP_TO_DATE
                        notified = null
                    }

                    is UpdateDecision.SkippedPrerelease -> status = UpdateCheckStatus.SKIPPED_PRERELEASE

                    UpdateDecision.NoRelease,
                    is UpdateDecision.Unparseable -> status = UpdateCheckStatus.UNAVAILABLE
                }
            }

            settingsRepository.setUpdateCheckState(lastCheckedAt = now, lastNotifiedVersion = notified)
            _state.update {
                it.copy(
                    checking = false,
                    status = status,
                    availableVersion = availableVersion,
                    availableUrl = availableUrl,
                    prompt = prompt,
                    manual = manual,
                    lastCheckedAt = now,
                    currentVersion = BuildConfig.VERSION_NAME
                )
            }
        }
    }
}
