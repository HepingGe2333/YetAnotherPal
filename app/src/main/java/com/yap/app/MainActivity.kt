package com.yap.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.yap.app.data.prefs.ThemeMode
import com.yap.app.data.repo.ChatRepository
import com.yap.app.navigation.YapNavHost
import com.yap.app.util.ChatNotifier
import com.yap.app.util.ProactiveNotifier
import com.yap.app.ui.RootViewModel
import com.yap.app.ui.screens.settings.UpdateAvailableDialog
import com.yap.app.ui.screens.settings.openReleasePage
import com.yap.app.ui.theme.YapTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var chatNotifier: ChatNotifier

    @Inject
    lateinit var chatRepository: ChatRepository

    private var pendingSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 安全 P2-7（审计 P2-6）：MainActivity 是 exported 的，extra 可能来自任意应用，
        // 不能直接塞给导航；先做「会话存在 + 属于当前 activePersona」的校验。
        handleDeepLink(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb())
        )
        setContent {
            val rootViewModel: RootViewModel = hiltViewModel()
            val settings by rootViewModel.settings.collectAsStateWithLifecycle()
            // 更新检查的结果是全局的（自动检查 + 设置页手动检查共用一份），对话框就挂在这里：
            // 同一个版本不会在两个地方各弹一次。
            val updateState by rootViewModel.updateState.collectAsStateWithLifecycle()
            // Android 13+ 通知权限：首次进入请求一次，拒绝过两次就不再打扰。
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) chatNotifier.ensureChannel(this@MainActivity)
            }
            LaunchedEffect(Unit) {
                if (shouldAskForNotificationPermission()) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            YapTheme(settings) {
                val dark = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                ApplySystemBars(dark)
                YapNavHost(
                    settings = settings,
                    pendingSessionId = pendingSessionId,
                    onPendingSessionConsumed = {
                        // 从通知进来时把这条通知撤掉，免得会话里还挂着提醒。
                        pendingSessionId?.let { chatNotifier.cancel(this@MainActivity, it) }
                        pendingSessionId = null
                    }
                )
                updateState.prompt?.let { release ->
                    UpdateAvailableDialog(
                        release = release,
                        // 「稍后」只是收起这一次：版本仍记着，24 小时内不再自动打扰。
                        onLater = rootViewModel::dismissUpdatePrompt,
                        onDownload = {
                            openReleasePage(this@MainActivity, release.htmlUrl)
                            rootViewModel.dismissUpdatePrompt()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * 只接受「本机真实存在、且属于当前正在聊的人格」的通知落点（安全 P2-7 / 审计 P2-6）。
     *
     * 本 App 是单用户本地库，所以这不是越权读数据，而是完整性/可控性：
     * 任意应用不该能把 App 强推到空白会话页、也不该能借深链触发 `markRead` 或撤通知。
     * 校验不过就丢弃这个 extra，停在首页。
     */
    private fun handleDeepLink(intent: Intent?) {
        val raw = intent?.getStringExtra(ProactiveNotifier.EXTRA_SESSION_ID)
        if (raw.isNullOrBlank()) {
            pendingSessionId = null
            return
        }
        lifecycleScope.launch {
            val validated = runCatching { chatRepository.resolveNotificationSession(raw) }.getOrNull()
            pendingSessionId = validated
            if (validated == null) intent?.removeExtra(ProactiveNotifier.EXTRA_SESSION_ID)
        }
    }

    /** 首次进入请求一次通知权限；被拒后只有系统还允许解释时才补问一次，两次之后不再打扰。 */
    private fun shouldAskForNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return false
        val prefs = getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE)
        val asked = prefs.getInt(KEY_NOTIFICATION_ASKED, 0)
        if (asked >= MAX_NOTIFICATION_ASKS) return false
        // 问过一次后，只有在还能弹解释框（而非「不再询问」）时才补问。
        if (asked > 0 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            return false
        }
        prefs.edit().putInt(KEY_NOTIFICATION_ASKED, asked + 1).apply()
        return true
    }

    private companion object {
        const val NOTIFICATION_PREFS = "notification_permission"
        const val KEY_NOTIFICATION_ASKED = "asked_count"

        /** 最多问两次：拒绝两次后系统默认不再弹，我们也不再打扰。 */
        const val MAX_NOTIFICATION_ASKS = 2
    }
}

@Composable
private fun ApplySystemBars(dark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? ComponentActivity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}
