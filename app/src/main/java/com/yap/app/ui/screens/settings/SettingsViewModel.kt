package com.yap.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yap.app.data.repo.BackupImportException
import com.yap.app.ui.common.BackupText
import com.yap.app.ui.common.ErrorText
import com.yap.app.R
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.BubbleStyle
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.prefs.ThemeMode
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.ApiUsageRepository
import com.yap.app.data.repo.BackupRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.ImportMode
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PresetRepository
import com.yap.app.data.repo.PersonaRepositoryInitializer
import com.yap.app.data.repo.ProactiveRepository
import com.yap.app.data.repo.UsageTotals
import com.yap.app.data.update.UpdateCoordinator
import com.yap.app.data.update.UpdateUiState
import com.yap.app.data.remote.ServerConfigRejection
import com.yap.app.data.remote.ServerCredentials
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.data.remote.ServerConnectionState
import com.yap.app.data.remote.ServerBootstrapManager
import com.yap.app.data.remote.ServerBootstrapPhase
import com.yap.app.data.remote.ServerBootstrapProgress
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.data.remote.ServerModeSwitchCoordinator
import com.yap.app.data.remote.ServerSyncConsent
import com.yap.app.data.remote.ServerSyncFailure
import com.yap.app.data.remote.ServerSyncPhase
import com.yap.app.data.remote.ServerSyncStatus
import com.yap.app.data.remote.ServerToggleAction
import com.yap.app.data.remote.UserAvatarCoordinator
import com.yap.app.domain.BackupDecodeResult
import com.yap.app.domain.BackupPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 设置页「服务端模式」小节的状态。
 *
 * 令牌**不进这个状态**：界面只拿到脱敏串/“有没有”，输入框里新填的令牌由 Composable 自己
 * 持有，保存时直接交给 ViewModel。这样重组、状态保存、日志都不会把明文令牌带出去。
 */
data class ServerSettingsUiState(
    val url: String = "",
    val enabled: Boolean = false,
    val hasToken: Boolean = false,
    val maskedToken: String = "",
    val usable: Boolean = false,
    val rejection: ServerConfigRejection? = null,
    val testing: Boolean = false,
    /** 测试连接的结果（成功一行摘要 / 失败原因），直接显示在按钮下面。 */
    val testMessage: String? = null,
    /** 保存/清除失败时的提示。 */
    val error: String? = null,
    /** 0.12.1 一次性数据对齐（bootstrap）的一行状态；没有可显示的内容时为 null。 */
    val bootstrapStatus: String? = null,
    /** 0.13：本地 → 远程的切换正在等用户回答「把对话交给云端处理？」。 */
    val consentPrompt: Boolean = false,
    /** 0.13：同步/下载进行中的一行进度（历史媒体逐项上传也在这里）。 */
    val syncStatus: String? = null,
    /** 0.13：同步/下载进行中（界面显示「取消同步」）。 */
    val syncRunning: Boolean = false
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val personaCount: Int = 0,
    val apiCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    /** 他这个月自己上网、整理记忆花掉的量（只在本机）。 */
    val usage: UsageTotals = UsageTotals.EMPTY,
    /** 服务端模式（M3）：默认关闭。 */
    val server: ServerSettingsUiState = ServerSettingsUiState()
)

sealed interface SettingsEvent {
    /**
     * 「导出全部对话」已经准备好：只带建议文件名，真正的内容在用户选完位置之后
     * 边读库边分批写盘（见 [SettingsViewModel.exportAllChatsTo]）。
     */
    data class ExportReady(val fileName: String) : SettingsEvent

    /**
     * 反馈已经保存好了：界面拿这个地址去打开浏览器（https 校验在 UI 层，见 `openFeedbackIssue`）。
     *
     * 事件里只带 URL，不带文件内容：本地文件已经写好，页面不需要再碰它。
     */
    data class FeedbackReady(val issueUrl: String) : SettingsEvent
}

/**
 * 「把他带走 / 从备份里接他回来」这一节的状态。
 *
 * [preview] 不为空表示：文件已经读进来、等着用户决定合并还是替换。
 */
data class BackupUiState(
    val busy: Boolean = false,
    val preview: BackupPreview? = null,
    /** 这份备份里有东西没读出来 / 带不回来时的中文说明，直接给用户看。 */
    val decodeWarnings: List<com.yap.app.domain.BackupWarning> = emptyList(),
    /** v3 媒体段的提醒（坏条目 / 读不出来），与 [decodeWarnings] 一起显示。 */
    val decodeMediaWarnings: List<com.yap.app.domain.BackupMediaWarning> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    /** 0.15 ASR：独立 DataStore + 独立加密 Key；「删除全部数据」时也要一起清掉。 */
    private val asrSettingsRepository: com.yap.app.data.prefs.AsrSettingsRepository,
    private val chatRepository: ChatRepository,
    personaRepository: PersonaRepository,
    private val presetRepository: PresetRepository,
    private val proactiveRepository: ProactiveRepository,
    apiConfigRepository: ApiConfigRepository,
    private val apiUsageRepository: ApiUsageRepository,
    private val backupRepository: BackupRepository,
    private val personaInitializer: PersonaRepositoryInitializer,
    private val serverModeRepository: ServerModeRepository,
    private val serverLocalStore: ServerLocalStore,
    /** 0.12.1：一次性全量对齐的进度（设置页一行字）。 */
    private val serverBootstrap: ServerBootstrapManager,
    /** 0.12.1：头像「压缩→上传→写设置→镜像」链路。 */
    private val userAvatarCoordinator: UserAvatarCoordinator,
    /** 0.13：服务端开关的状态机（同意弹窗 / 历史媒体同步 / 切回本地全量下载）。 */
    private val serverModeSwitch: ServerModeSwitchCoordinator,
    /** 0.13：连接成功后把本机模型配置推给本机部署的服务端（远程地址会被策略挡掉）。 */
    private val llmConfigPusher: com.yap.app.data.remote.ServerLlmConfigPusher,
    /** 0.15：检查更新（与前台自动检查共用同一个协调器与状态）。 */
    private val updateCoordinator: UpdateCoordinator,
    /** 0.15：反馈落盘（`files/feedback`）+ 生成 GitHub Issue 预填链接。 */
    private val feedbackRepository: com.yap.app.data.repo.FeedbackRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val baseState: Flow<SettingsUiState> = combine(
        settingsRepository.settings,
        personaRepository.observeAll(),
        apiConfigRepository.observeAll(),
        busy,
        message
    ) { settings, personas, configs, isBusy, currentMessage ->
        SettingsUiState(settings, personas.size, configs.size, isBusy, currentMessage)
    }

    private val serverTesting = MutableStateFlow(false)
    private val serverTestMessage = MutableStateFlow<String?>(null)
    private val serverError = MutableStateFlow<String?>(null)

    /** bootstrap 进度 + 开关状态机的阶段：一个给进度行，一个给弹窗/取消按钮。 */
    private val serverSyncUi: Flow<Pair<ServerBootstrapProgress, ServerSyncStatus>> = combine(
        serverBootstrap.progress,
        serverModeSwitch.status
    ) { progress, status -> progress to status }

    private val serverState: Flow<ServerSettingsUiState> = combine(
        serverModeRepository.state,
        serverTesting,
        serverTestMessage,
        serverError,
        serverSyncUi
    ) { state, testing, testMessage, error, sync ->
        state.toUiState(
            testing = testing,
            testMessage = testMessage,
            error = error,
            bootstrap = sync.first,
            sync = sync.second
        )
    }

    /** 「用量」要实时刷新：订阅本月流水与按人的排行，聊天或后台任务一跑，这里立刻变。 */
    val uiState: StateFlow<SettingsUiState> = combine(
        baseState,
        apiUsageRepository.observeMonth(),
        apiUsageRepository.observeMonthBySession(),
        serverState
    ) { current, usage, bySession, server ->
        current.copy(usage = usage.copy(bySession = bySession), server = server)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val backupBusy = MutableStateFlow(false)
    private val backupPreview = MutableStateFlow<BackupPreview?>(null)
    private val backupDecodeWarnings = MutableStateFlow<List<com.yap.app.domain.BackupWarning>>(emptyList())
    private val backupDecodeMediaWarnings =
        MutableStateFlow<List<com.yap.app.domain.BackupMediaWarning>>(emptyList())

    /** 「把他带走」这一节：正在打包/接回来时按钮停一下；读完文件先给一句摘要。 */
    val backupState: StateFlow<BackupUiState> = combine(
        backupBusy,
        backupPreview,
        backupDecodeWarnings,
        backupDecodeMediaWarnings
    ) { busy, preview, warnings, mediaWarnings ->
        BackupUiState(busy, preview, warnings, mediaWarnings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** 设置页「更新」小节的状态：检查中 / 已是最新 / 有新版本 / 失败（失败只显示一行）。 */
    val updateState: StateFlow<UpdateUiState> = updateCoordinator.state

    /** 用户手里那份备份文件：预览之后先拿着，等用户决定怎么接回来时再用。 */
    private var pendingBackup: String? = null

    fun dismissMessage() {
        message.value = null
    }

    /**
     * 提交一条反馈（0.15）。
     *
     * 顺序固定：**先落盘，再打开浏览器**。落盘成功 → 提示「已保存」并发事件让界面打开预填页；
     * 落盘失败 → 只提示失败（什么都不打开，避免用户以为已经保存）。
     *
     * 界面在调用前已经用 `Feedback.validate` 拦过一次；这里不重复校验（仓储层还有一道）。
     */
    fun submitFeedback(
        kind: com.yap.app.domain.FeedbackKind,
        content: String,
        contact: String
    ) {
        viewModelScope.launch {
            val result = feedbackRepository.submit(
                com.yap.app.domain.FeedbackDraft(kind = kind, content = content, contact = contact)
            )
            result.onSuccess { receipt ->
                message.value = context.getString(R.string.feedback_saved)
                events.send(SettingsEvent.FeedbackReady(receipt.issueUrl))
            }.onFailure { error ->
                message.value = ErrorText.of(context, error, R.string.feedback_save_failed)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = launchSetting { settingsRepository.setThemeMode(mode) }
    fun setAccentColor(color: Long) = launchSetting { settingsRepository.setAccentColor(color) }
    fun setFontScale(scale: Float) = launchSetting { settingsRepository.setFontScale(scale) }
    fun setBubbleStyle(style: BubbleStyle) = launchSetting { settingsRepository.setBubbleStyle(style) }
    fun setAnimations(enabled: Boolean) = launchSetting { settingsRepository.setAnimationsEnabled(enabled) }
    fun setHaptics(enabled: Boolean) = launchSetting { settingsRepository.setHapticsEnabled(enabled) }

    fun setAutoTranslatePersona(enabled: Boolean) =
        launchSetting { settingsRepository.setAutoTranslatePersona(enabled) }

    fun setChatBackgroundColor(color: Long?) = launchSetting { settingsRepository.setChatBackgroundColor(color) }
    fun setChatBackgroundImage(uri: String?) = launchSetting { settingsRepository.setChatBackgroundImageUri(uri) }

    /**
     * 「我的头像」换了一张新图（文件已经由 `MediaStorage.persistAvatar` 采样压缩好）。
     *
     * 0.12.1：
     * - 本地模式：只写本地文件路径（行为与旧版本一致）；
     * - 服务端模式：压缩 → `POST /v1/media?kind=USER_AVATAR` → 写 `/v1/media/<id>` 并镜像设置；
     *   **上传/推送失败时保留旧头像**并提示，不出现「看起来换了、其实没同步」；
     * - 成功后旧文件按引用回收（共享文件不删）。
     */
    fun onUserAvatarPicked(localFileUri: String) {
        viewModelScope.launch {
            runCatching { userAvatarCoordinator.applyPicked(localFileUri) }
                .onFailure {
                    message.value = context.getString(R.string.settings_error_avatar_upload)
                }
        }
    }

    /** 恢复默认头像；服务端模式下会先把服务端引用清掉，失败则恢复旧头像。 */
    fun clearUserAvatar() {
        viewModelScope.launch {
            runCatching { userAvatarCoordinator.clear() }
                .onFailure {
                    message.value = context.getString(R.string.settings_error_avatar_upload)
                }
        }
    }

    /** 常驻后台：关掉后他就只在应用打开时存在。 */
    fun setAgentLoopEnabled(enabled: Boolean) = launchSetting { settingsRepository.setAgentLoopEnabled(enabled) }

    /** 导出全部对话的默认文件名（界面先拿它去开系统文件选择器）。 */
    fun suggestedAllChatsFileName(): String = chatRepository.suggestedAllChatsFileName()

    fun exportAllChats() {
        viewModelScope.launch {
            // 这里只发起「选位置」；真正的读库 + 分批写文件在 exportAllChatsTo 里做。
            runCatching { events.send(SettingsEvent.ExportReady(chatRepository.suggestedAllChatsFileName())) }
        }
    }

    /**
     * 把全部对话**边读边写**进用户选的文件。
     *
     * 0.10.2 之前：仓库先在主线程上下文里组装整份字符串，界面再整体写盘 ——
     * 整库导出时这是几十 MB 的堆峰值 + 一段主线程卡顿。现在读取/序列化在仓库的
     * IO 上下文里，按 [com.yap.app.domain.ExportJsonStream] 的批大小一段段回调，
     * 这里每段直接写进输出流并 flush；失败（磁盘满、权限没了）会让整次导出失败并报出来。
     */
    fun exportAllChatsTo(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        chatRepository.exportAllChatsTo { chunk ->
                            output.write(chunk.toByteArray(Charsets.UTF_8))
                            // 分批落盘：不要等整份文件拼完再一次性写。
                            output.flush()
                        }.getOrThrow()
                    } ?: error(context.getString(R.string.settings_error_unwritable_file))
                }
            }
            message.value = result.fold(
                { context.getString(R.string.settings_export_success) },
                { context.getString(R.string.settings_export_failed, ErrorText.of(context, it)) }
            )
            busy.value = false
        }
    }

    fun clearChats() {
        viewModelScope.launch {
            busy.value = true
            runCatching { chatRepository.clearAllChatData() }
                .onSuccess { message.value = context.getString(R.string.settings_cleared_chats) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.settings_error_clear) }
            busy.value = false
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                // 顺序有意义：先清规则与排期，再清聊天/人格，最后清模板。
                proactiveRepository.wipeAll()
                chatRepository.wipeAllData()
                apiUsageRepository.wipe()
                presetRepository.wipeAll()
                // 媒体文件（files/media + files/avatars）由 ChatRepository.wipeAllData 内的
                // DataEraser 一并清掉：删表与删文件走同一份清单，避免两处各自漏。
                settingsRepository.resetAll(preserveSeedMarker = false)
                // ASR 的开关/地址/Key 在独立 DataStore 里（不进备份、不上云），
                // 但「删除全部数据」必须连它一起清：Key 不该在用户以为清空后还留在设备上。
                asrSettingsRepository.resetAll()
                // 服务端连接也一起清：否则「删除全部数据」之后 App 还会连回旧服务器。
                clearServerConnectionQuietly()
                // 与 iOS 对齐：清空之后按当前语言重新播种，别让用户面对一个空白 App。
                personaInitializer.seedIfNeeded()
            }.onSuccess { message.value = context.getString(R.string.settings_wiped) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.settings_error_delete) }
            busy.value = false
        }
    }

    // ------------------------------------------------------------------
    // 检查更新
    // ------------------------------------------------------------------

    /** 手动检查：不看节流、不看自动开关（用户点了一下就该真的查）。 */
    fun checkForUpdates() {
        viewModelScope.launch { updateCoordinator.checkNow() }
    }

    /** 打开设置页时的静默检查：受开关与 24h 节流约束，通常是一次 no-op。 */
    fun maybeAutoCheckUpdates() {
        viewModelScope.launch { updateCoordinator.maybeCheckOnStart() }
    }

    fun setUpdateAutoCheck(enabled: Boolean) =
        launchSetting { updateCoordinator.setAutoCheckEnabled(enabled) }

    fun setUpdateIncludePrereleases(enabled: Boolean) =
        launchSetting { updateCoordinator.setIncludePrereleases(enabled) }

    // ------------------------------------------------------------------
    // 把他带走 / 从备份里接他回来
    // ------------------------------------------------------------------

    /** 备份文件建议名字（界面上先给用户看，选位置时填进文件名）。 */
    fun suggestedBackupFileName(): String = backupRepository.suggestedFileName()

    /**
     * 生成备份文本。
     *
     * 交给界面的 rememberCoroutineScope 在 IO 线程写文件——文件可能几 MB，
     * 但生成这份文本要读库，所以这里只负责「生成」。
     */
    suspend fun buildBackupJson(): Result<com.yap.app.data.repo.BackupExport> = runCatching {
        backupBusy.value = true
        try {
            backupRepository.export()
        } finally {
            backupBusy.value = false
        }
    }

    /** 读进来的备份先解码看一眼：几个人格、多少条记忆、多少句话。 */
    fun openBackup(text: String) {
        viewModelScope.launch {
            backupBusy.value = true
            when (val decoded = backupRepository.preview(text)) {
                is BackupDecodeResult.Failure -> message.value = BackupText.failure(context, decoded.reason)
                // 0.15：checksum 对不上 = 文件坏了，直接说清楚，不给「要不要接着导」的错觉。
                is BackupDecodeResult.Corrupted ->
                    message.value = BackupText.corruption(context, decoded.reason)
                is BackupDecodeResult.Success -> {
                    pendingBackup = text
                    backupDecodeWarnings.value = decoded.warnings
                    backupDecodeMediaWarnings.value = decoded.mediaWarnings
                    // 版本信息（格式 / schema / appVersion / checksum）随预览一起显示。
                    backupPreview.value = BackupPreview.from(decoded.bundle, decoded.metadata)
                }
            }
            backupBusy.value = false
        }
    }

    /** 先不接了：把文件放回去，什么都不写。 */
    fun dismissBackupPreview() {
        pendingBackup = null
        backupPreview.value = null
        backupDecodeWarnings.value = emptyList()
        backupDecodeMediaWarnings.value = emptyList()
    }

    /** 照备份把他接回来。失败时库里什么都没变，只给一句中文。 */
    fun importBackup(mode: ImportMode) {
        val raw = pendingBackup ?: return
        viewModelScope.launch {
            backupBusy.value = true
            runCatching { backupRepository.importJson(raw, mode) }
                .onSuccess { summary ->
                    message.value = BackupText.importSummary(context, summary)
                    dismissBackupPreview()
                }
                .onFailure { error ->
                    message.value = when {
                        error !is BackupImportException ->
                            ErrorText.of(context, error, R.string.settings_error_import)
                        error.corruption != null -> BackupText.corruption(context, error.corruption)
                        error.failure != null -> BackupText.failure(context, error.failure)
                        else -> context.getString(R.string.settings_error_import)
                    }
                }
            backupBusy.value = false
        }
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                message.value = ErrorText.of(context, it, R.string.settings_error_save)
            }
        }
    }

    // ---------------------------------------------------------------- 服务端模式（M3）

    /**
     * 保存地址/令牌/开关。
     *
     * 令牌输入框留空 = 沿用已保存的令牌（脱敏展示时用户不该为了改地址而重新粘贴一次）；
     * 要清除令牌用 [clearServerToken]。
     */
    fun saveServerConnection(url: String, tokenInput: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = serverModeRepository.currentCredentials()
            val token = tokenInput.trim().ifEmpty { current.token }
            val credentials = ServerCredentials(url = url, token = token, enabled = enabled)
            serverModeRepository.save(credentials)
                .onSuccess {
                    serverTestMessage.value = null
                    serverError.value = null
                    message.value = context.getString(R.string.settings_server_saved)
                }
                .onFailure {
                    serverError.value = context.getString(R.string.settings_server_save_failed)
                }
        }
    }

    /**
     * 打开/关闭服务端模式（0.13）。
     *
     * - 本地 → 远程：先问 [ServerModeSwitchCoordinator.requestToggle]；没有同意记录就弹窗，
     *   开关**保持关闭**等用户回答；有记录（同一台地址同意过）才直接开启并同步；
     * - 远程 → 本地：先全量下载 + 条数校验，成功才真正关闭（失败/取消保持开启）。
     */
    fun setServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            when (val action = serverModeSwitch.requestToggle(enabled)) {
                is ServerToggleAction.NeedsConsent -> Unit
                is ServerToggleAction.Enable -> startServerSwitch { serverModeSwitch.enableWith(action.consent) }
                ServerToggleAction.Disable -> startServerSwitch { serverModeSwitch.disable() }
                ServerToggleAction.Ignore -> Unit
            }
        }
    }

    /** 弹窗里选了「同意并同步」/「只同步之后的新内容」。 */
    fun chooseServerSyncConsent(consent: ServerSyncConsent) {
        viewModelScope.launch {
            if (!serverModeSwitch.status.value.consentRequired) return@launch
            startServerSwitch { serverModeSwitch.enableWith(consent) }
        }
    }

    /** 弹窗取消（或点外面）：不记同意、开关保持关闭。 */
    fun dismissServerSyncConsent() {
        serverModeSwitch.dismissConsent()
    }

    /** 取消正在跑的同步/下载：取消后不切换模式（已上传的媒体保留）。 */
    fun cancelServerSync() {
        serverSyncJob?.cancel()
    }

    private fun startServerSwitch(block: suspend () -> ServerSyncStatus) {
        serverSyncJob?.cancel()
        serverSyncJob = viewModelScope.launch {
            try {
                val status = block()
                message.value = serverSyncResultMessage(status)
            } catch (cancelled: CancellationException) {
                message.value = context.getString(
                    if (serverModeSwitch.status.value.phase == ServerSyncPhase.CANCELLED &&
                        serverModeSwitch.status.value.failure == ServerSyncFailure.DOWNLOAD_INCOMPLETE
                    ) {
                        R.string.settings_server_download_cancelled
                    } else {
                        R.string.settings_server_sync_cancelled
                    }
                )
                throw cancelled
            }
        }
    }

    /** 结果 → 一句中文/英文（失败与取消都要说清楚「模式有没有变」）。 */
    private fun serverSyncResultMessage(status: ServerSyncStatus): String? = when (status.phase) {
        ServerSyncPhase.COMPLETED -> when {
            status.consent == ServerSyncConsent.NEW_ONLY ->
                context.getString(R.string.settings_server_sync_new_only)
            status.skipped -> context.getString(R.string.settings_server_sync_already)
            status.mediaFailed > 0 -> context.getString(
                R.string.settings_server_sync_done_media,
                status.messages,
                status.mediaFailed
            )
            else -> context.getString(R.string.settings_server_sync_done, status.messages)
        }
        ServerSyncPhase.DOWNLOADED ->
            context.getString(R.string.settings_server_download_done, status.downloadedMessages)
        ServerSyncPhase.FAILED -> when (status.failure) {
            ServerSyncFailure.SAVE_FAILED -> context.getString(R.string.settings_server_save_failed)
            ServerSyncFailure.DOWNLOAD_UNAVAILABLE ->
                context.getString(R.string.settings_server_download_unavailable)
            ServerSyncFailure.DOWNLOAD_INCOMPLETE -> context.getString(
                R.string.settings_server_download_failed,
                status.localMessages,
                status.serverMessages
            )
            else -> context.getString(R.string.settings_server_sync_failed)
        }
        ServerSyncPhase.CANCELLED -> context.getString(
            if (status.failure == ServerSyncFailure.DOWNLOAD_INCOMPLETE) {
                R.string.settings_server_download_cancelled
            } else {
                R.string.settings_server_sync_cancelled
            }
        )
        else -> null
    }

    /** 0.13：正在跑的那次切换（取消按钮 / 防止两轮并发）。 */
    private var serverSyncJob: Job? = null

    /** 清除已保存令牌（地址/开关保留）。 */
    fun clearServerToken() {
        viewModelScope.launch {
            val current = serverModeRepository.currentCredentials()
            serverModeRepository.save(current.copy(token = ""))
                .onFailure { serverError.value = context.getString(R.string.settings_server_save_failed) }
        }
    }

    /**
     * 测试连接：用界面上**当前输入**试一次（令牌留空则用已保存的）。
     *
     * 测试成功不代表已保存：用户可能还在改地址；保存是另一个动作，避免「点测试就悄悄改了配置」。
     */
    fun testServerConnection(url: String, tokenInput: String) {
        viewModelScope.launch {
            if (serverTesting.value) return@launch
            serverTesting.value = true
            serverTestMessage.value = null
            val current = serverModeRepository.currentCredentials()
            val credentials = ServerCredentials(
                url = url,
                token = tokenInput.trim().ifEmpty { current.token },
                enabled = true
            )
            serverModeRepository.testConnection(credentials)
                .onSuccess { result ->
                    serverTestMessage.value =
                        context.getString(R.string.settings_server_test_success, result.describe())
                    // 0.13：「连接成功」也是一次推送时机（开关已打开、地址是回环才真的发）。
                    // 失败不影响这句「连接成功」——推送是便利，不是连通性判定的一部分。
                    runCatching { llmConfigPusher.pushIfLocal() }
                }
                .onFailure { error ->
                    serverTestMessage.value = ErrorText.of(
                        context,
                        error,
                        R.string.settings_server_test_failed
                    )
                }
            serverTesting.value = false
        }
    }

    /** 删除全部数据后连服务端配置一起清掉（否则新用户会连上旧服务器/旧游标）。 */
    private suspend fun clearServerConnectionQuietly() {
        // 本机数据被清空后，同一台服务端也应该重新做一次全量对齐：清掉它的 bootstrap 标记。
        val baseUrl = runCatching { serverModeRepository.currentConfig().baseUrl }.getOrDefault("")
        serverModeRepository.clear()
        serverModeRepository.reload()
        // 游标、会话映射、待发队列都属于那台服务端；数据清空后它们没有意义。
        runCatching { serverLocalStore.resetForNewServer() }
        if (baseUrl.isNotBlank()) runCatching { serverLocalStore.clearBootstrapDone(baseUrl) }
    }

    private fun ServerConnectionState.toUiState(
        testing: Boolean,
        testMessage: String?,
        error: String?,
        bootstrap: ServerBootstrapProgress,
        sync: ServerSyncStatus
    ): ServerSettingsUiState = ServerSettingsUiState(
        url = credentials.cleanUrl,
        enabled = credentials.enabled,
        hasToken = credentials.cleanToken.isNotEmpty(),
        maskedToken = credentials.maskedToken(),
        usable = usable,
        rejection = config.rejection(),
        testing = testing,
        testMessage = testMessage,
        error = error,
        // 关掉服务端模式后这一行没有意义；切换进行中时由 syncStatus 统一显示（避免两行重复）。
        bootstrapStatus = bootstrapMessage(bootstrap)?.takeIf { credentials.enabled && !sync.running },
        consentPrompt = sync.consentRequired,
        syncStatus = serverSyncProgressMessage(bootstrap, sync),
        syncRunning = sync.running
    )

    /** 同步/下载进行中的一行字（历史媒体逐项进度也走这里）。 */
    private fun serverSyncProgressMessage(
        progress: ServerBootstrapProgress,
        status: ServerSyncStatus
    ): String? {
        if (status.consentRequired) return null
        return when {
            progress.phase == ServerBootstrapPhase.DOWNLOADING ||
                status.phase == ServerSyncPhase.DOWNLOADING ->
                context.getString(R.string.settings_server_downloading, progress.messages)
            progress.phase == ServerBootstrapPhase.UPLOADING_MEDIA ->
                if (progress.mediaTotal > 0) {
                    context.getString(
                        R.string.settings_server_media_progress,
                        progress.mediaProcessed,
                        progress.mediaTotal,
                        progress.mediaUploaded,
                        progress.mediaFailed
                    )
                } else {
                    context.getString(R.string.settings_server_media_starting)
                }
            status.phase == ServerSyncPhase.SYNCING && progress.phase == ServerBootstrapPhase.IDLE ->
                context.getString(R.string.settings_server_sync_starting)
            else -> bootstrapMessage(progress)
        }
    }

    /** bootstrap 阶段 → 一行中文/英文（文案在资源里，数据层不出现面向用户的句子）。 */
    private fun bootstrapMessage(progress: ServerBootstrapProgress): String? = when (progress.phase) {
        ServerBootstrapPhase.IDLE,
        ServerBootstrapPhase.ALREADY_DONE -> null
        ServerBootstrapPhase.INSPECTING ->
            context.getString(R.string.settings_server_bootstrap_inspecting)
        ServerBootstrapPhase.PUSHING ->
            context.getString(R.string.settings_server_bootstrap_pushing, progress.messages)
        ServerBootstrapPhase.UPLOADING_MEDIA ->
            if (progress.mediaTotal > 0) {
                context.getString(
                    R.string.settings_server_media_progress,
                    progress.mediaProcessed,
                    progress.mediaTotal,
                    progress.mediaUploaded,
                    progress.mediaFailed
                )
            } else {
                context.getString(R.string.settings_server_media_starting)
            }
        ServerBootstrapPhase.DOWNLOADING ->
            context.getString(R.string.settings_server_downloading, progress.messages)
        ServerBootstrapPhase.PULLING ->
            context.getString(R.string.settings_server_bootstrap_pulling, progress.messages)
        ServerBootstrapPhase.MERGING ->
            context.getString(R.string.settings_server_bootstrap_merging, progress.messages)
        ServerBootstrapPhase.COMPLETED ->
            context.getString(R.string.settings_server_bootstrap_done, progress.messages)
        ServerBootstrapPhase.FAILED ->
            context.getString(R.string.settings_server_bootstrap_failed)
    }
}
