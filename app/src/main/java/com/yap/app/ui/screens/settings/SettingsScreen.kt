package com.yap.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yap.app.ui.common.ErrorText
import com.yap.app.BuildConfig
import com.yap.app.R
import com.yap.app.data.prefs.BubbleStyle
import com.yap.app.data.prefs.ThemeMode
import com.yap.app.ui.components.AppHeader
import com.yap.app.ui.components.BackNavigation
import com.yap.app.ui.components.ConfirmDialog
import com.yap.app.ui.components.SettingCard
import com.yap.app.ui.components.SettingRow
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.StyledOutlinedField
import com.yap.app.ui.components.UserAvatar
import com.yap.app.ui.components.TextPill
import com.yap.app.ui.theme.LocalAccentColor
import com.yap.app.util.BackgroundKeeper
import com.yap.app.util.formatFullDateTime
import kotlinx.coroutines.launch

private val accentChoices = listOf(
    0xFFC76F4E, 0xFFB6883E, 0xFF6F8B65, 0xFF4F7F92,
    0xFF6E79A8, 0xFF966A88, 0xFFB76076, 0xFF66645E
)

private val backgroundChoices = listOf(
    0xFFFAF8F4, 0xFFF6EFE6, 0xFFF1F3EE, 0xFFEEF2F5,
    0xFF191A18, 0xFF1D1B19, 0xFF171D1C, 0xFF1B1A22
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenApiConfigs: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenProactive: () -> Unit,
    onOpenAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // 打开设置页时静默检查一次（设置开关关掉 / 24h 内查过时，协调器会直接返回）。
    LaunchedEffect(Unit) { viewModel.maybeAutoCheckUpdates() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「把他带走」这一节和下面清空前的快捷入口共用同一份文件选择器。
    val backupTransfer = rememberBackupTransfer(viewModel) { message ->
        scope.launch { snackbar.showSnackbar(message) }
    }
    var showClearChatsConfirm by remember { mutableStateOf(false) }
    // 反馈：弹窗开着时内容由用户自己留住；保存失败不清空（他还能重试）。
    var showFeedback by remember { mutableStateOf(false) }
    var feedbackSaving by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var customHex by remember { mutableStateOf("") }
    var notificationsGranted by remember { mutableStateOf(BackgroundKeeper.hasNotificationPermission(context)) }
    var batteryUnrestricted by remember { mutableStateOf(BackgroundKeeper.isIgnoringBatteryOptimizations(context)) }

    // 从系统设置页回来时重新读一次：用户可能刚打开了通知或关掉了省电限制。
    LifecycleResumeEffect(Unit) {
        notificationsGranted = BackgroundKeeper.hasNotificationPermission(context)
        batteryUnrestricted = BackgroundKeeper.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                com.yap.app.util.MediaStorage.persistImage(context, uri, "background")
                    .onSuccess { viewModel.setChatBackgroundImage(it) }
                    .onFailure {
                        snackbar.showSnackbar(
                            ErrorText.of(context, it, R.string.settings_error_background_image)
                        )
                    }
            }
        }
    }
    // 「我的头像」：和人格头像走同一条管线（采样 → 512px → JPEG → ≤200KB → files/avatars）。
    val userAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                com.yap.app.util.MediaStorage.persistAvatar(context, uri)
                    .onSuccess { viewModel.onUserAvatarPicked(it) }
                    .onFailure {
                        snackbar.showSnackbar(
                            ErrorText.of(context, it, R.string.settings_error_avatar)
                        )
                    }
            }
        }
    }
    // 「导出全部对话」：选完位置后由 ViewModel 边读库边分批写盘（都在 IO 上），
    // 结果通过 state.message 显示，屏幕不再自己拿到整份 JSON 字符串。
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportAllChatsTo(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is SettingsEvent.ExportReady -> {
                    exportLauncher.launch(event.fileName)
                }
                is SettingsEvent.FeedbackReady -> {
                    // 先保存、再打开浏览器（顺序在仓储层已经保证）。浏览器打不开时内容仍在本地，
                    // 这里只补一句「没能打开」，不让用户以为反馈丢了。
                    feedbackSaving = false
                    showFeedback = false
                    if (!openFeedbackIssue(context, event.issueUrl)) {
                        snackbar.showSnackbar(context.getString(R.string.feedback_saved_open_failed))
                    }
                }
            }
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        // 任何一条提示都意味着这次提交有结论了：保存失败时把按钮恢复，弹窗留在原地。
        feedbackSaving = false
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                navigation = { BackNavigation(onBack) }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 40.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LocalAccentColor.current.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.WavingHand, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    R.string.settings_summary,
                                    pluralStringResource(
                                        R.plurals.settings_persona_count,
                                        state.personaCount,
                                        state.personaCount
                                    ),
                                    pluralStringResource(
                                        R.plurals.settings_api_count,
                                        state.apiCount,
                                        state.apiCount
                                    )
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SectionLabel(stringResource(R.string.settings_section_appearance))
                SettingCard {
                    // 「我的头像」是安静的：一行预览 + 点一下换图，没有单独的设置页，
                    // 也没有「确定要移除吗」这种弹窗——恢复默认只是一个普通按钮。
                    SettingRow(
                        title = stringResource(R.string.settings_my_avatar),
                        description = stringResource(R.string.settings_my_avatar_desc),
                        icon = Icons.Outlined.PersonOutline,
                        trailing = {
                            UserAvatar(
                                avatarUri = state.settings.userAvatarUri,
                                fallbackUri = state.settings.userAvatarLocalUri,
                                size = 32.dp
                            )
                        },
                        onClick = {
                            userAvatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        showDivider = true
                    )
                    if (state.settings.userAvatarUri != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.clearUserAvatar() }) {
                                Text(
                                    stringResource(R.string.settings_restore_default),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_theme_mode),
                        description = stringResource(R.string.settings_theme_mode_desc),
                        icon = Icons.Outlined.SettingsBrightness,
                        trailing = { Text(themeLabel(state.settings.themeMode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    ThemeModeRow(
                        selected = state.settings.themeMode,
                        onSelect = viewModel::setThemeMode
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_accent_color),
                        description = stringResource(R.string.settings_accent_color_desc),
                        icon = Icons.Outlined.Palette,
                        showDivider = false
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        accentChoices.forEach { colorValue ->
                            AccentSwatch(
                                color = Color(colorValue),
                                selected = state.settings.accentColor == colorValue,
                                onClick = { viewModel.setAccentColor(colorValue) }
                            )
                        }
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { customHex = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = stringResource(R.string.settings_custom_color_cd),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (customHex.isEmpty() && state.settings.accentColor !in accentChoices) {
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StyledOutlinedField(
                            value = customHex,
                            onValueChange = { customHex = it.take(9) },
                            label = stringResource(R.string.settings_custom_accent_label),
                            placeholder = "#C76F4E",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                parseColor(customHex)?.let { viewModel.setAccentColor(it) }
                            },
                            enabled = parseColor(customHex) != null
                        ) { Text(stringResource(R.string.settings_apply)) }
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_font_size),
                        description = stringResource(R.string.settings_font_size_desc),
                        icon = Icons.Outlined.AutoAwesome,
                        trailing = {
                            Text("${(state.settings.fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    )
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("A", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = state.settings.fontScale,
                            onValueChange = { viewModel.setFontScale(it) },
                            valueRange = 0.85f..1.3f,
                            steps = 8,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = LocalAccentColor.current,
                                activeTrackColor = LocalAccentColor.current
                            )
                        )
                        Text("A", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_bubble_style),
                        description = stringResource(R.string.settings_bubble_style_desc),
                        icon = Icons.Outlined.ChatBubbleOutline,
                        showDivider = false
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BubbleStyle.values().forEach { style ->
                            BubbleStyleOption(
                                style = style,
                                selected = state.settings.bubbleStyle == style,
                                onClick = { viewModel.setBubbleStyle(style) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_animations),
                        description = stringResource(R.string.settings_animations_desc),
                        icon = Icons.Outlined.Tune,
                        trailing = {
                            Switch(
                                checked = state.settings.animationsEnabled,
                                onCheckedChange = viewModel::setAnimations,
                                colors = switchColors()
                            )
                        }
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_haptics),
                        description = stringResource(R.string.settings_haptics_desc),
                        icon = Icons.Outlined.Vibration,
                        trailing = {
                            Switch(
                                checked = state.settings.hapticsEnabled,
                                onCheckedChange = viewModel::setHaptics,
                                colors = switchColors()
                            )
                        },
                        showDivider = false
                    )
                }

                SectionLabel(stringResource(R.string.settings_section_language))
                SettingCard {
                    // 0.12：人设提示词翻译（spec/persona-translation §6）。默认开，关掉就一律用原文。
                    SettingRow(
                        title = stringResource(R.string.settings_auto_translate_persona),
                        description = stringResource(R.string.settings_auto_translate_persona_desc),
                        icon = Icons.Outlined.Translate,
                        trailing = {
                            Switch(
                                checked = state.settings.autoTranslatePersona,
                                onCheckedChange = viewModel::setAutoTranslatePersona,
                                colors = switchColors()
                            )
                        },
                        showDivider = false
                    )
                }

                SectionLabel(stringResource(R.string.settings_section_background))
                SettingCard {
                    SettingRow(
                        title = stringResource(R.string.settings_agent_loop),
                        description = stringResource(R.string.settings_agent_loop_desc),
                        icon = Icons.Outlined.NightsStay,
                        trailing = {
                            Switch(
                                checked = state.settings.agentLoopEnabled,
                                onCheckedChange = viewModel::setAgentLoopEnabled,
                                colors = switchColors()
                            )
                        },
                        showDivider = true
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_notification_permission),
                        description = stringResource(R.string.settings_notification_permission_desc),
                        icon = Icons.Outlined.NotificationsNone,
                        trailing = {
                            Text(
                                stringResource(
                                    if (notificationsGranted) R.string.settings_notification_on
                                    else R.string.settings_notification_off
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (notificationsGranted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = if (notificationsGranted) null else {
                            { BackgroundKeeper.openAppNotificationSettings(context) }
                        },
                        showDivider = true
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_battery_limit),
                        description = stringResource(R.string.settings_battery_limit_desc),
                        icon = Icons.Outlined.BatterySaver,
                        trailing = {
                            Text(
                                stringResource(
                                    if (batteryUnrestricted) R.string.settings_battery_allowed
                                    else R.string.settings_battery_restricted
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (batteryUnrestricted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { BackgroundKeeper.requestIgnoreBatteryOptimizations(context) },
                        showDivider = true
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            stringResource(R.string.settings_background_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.settings_open_app_settings),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalAccentColor.current,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { BackgroundKeeper.openAppDetails(context) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                UsageSection(totals = state.usage)

                SectionLabel(stringResource(R.string.settings_section_chat_background))
                SettingCard {
                    SettingRow(
                        title = stringResource(R.string.settings_solid_background),
                        description = stringResource(
                            if (state.settings.chatBackgroundImageUri != null) R.string.settings_solid_background_desc_image
                            else R.string.settings_solid_background_desc_color
                        ),
                        icon = Icons.Outlined.Image,
                        showDivider = false
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = { viewModel.setChatBackgroundColor(null) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.settings_background_default), style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
                            }
                        }
                        backgroundChoices.forEach { colorValue ->
                            AccentSwatch(
                                color = Color(colorValue),
                                selected = state.settings.chatBackgroundColor == colorValue,
                                onClick = { viewModel.setChatBackgroundColor(colorValue) }
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.settings_pick_background_image))
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.settings.chatBackgroundImageUri != null) {
                            TextButton(onClick = { viewModel.setChatBackgroundImage(null) }) {
                                Text(stringResource(R.string.settings_remove_image), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                SectionLabel(stringResource(R.string.settings_api_title))
                SettingCard {
                    SettingRow(
                        title = stringResource(R.string.settings_api_title),
                        description = stringResource(R.string.settings_api_desc),
                        icon = Icons.Outlined.Security,
                        trailing = { Text("${state.apiCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = onOpenApiConfigs,
                        showDivider = false
                    )
                }

                ServerModeSection(
                    state = state.server,
                    onSave = viewModel::saveServerConnection,
                    onSetEnabled = viewModel::setServerEnabled,
                    onTest = viewModel::testServerConnection,
                    onClearToken = viewModel::clearServerToken,
                    onConsent = viewModel::chooseServerSyncConsent,
                    onDismissConsent = viewModel::dismissServerSyncConsent,
                    onCancelSync = viewModel::cancelServerSync
                )

                UpdateSection(
                    settings = state.settings,
                    update = updateState,
                    onCheck = viewModel::checkForUpdates,
                    onToggleAutoCheck = viewModel::setUpdateAutoCheck,
                    onTogglePrereleases = viewModel::setUpdateIncludePrereleases
                )

                BackupSection(
                    state = backupState,
                    transfer = backupTransfer,
                    onConfirmImport = viewModel::importBackup,
                    onDismissPreview = viewModel::dismissBackupPreview
                )

                SectionLabel(stringResource(R.string.settings_section_data_security))
                SettingCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.clear_data_intro_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(R.string.clear_data_intro_body),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = backupTransfer.takeHimAway,
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) { Text(stringResource(R.string.clear_data_take_away_first)) }
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_export_chats_title),
                        description = stringResource(R.string.settings_export_chats_desc),
                        icon = Icons.Outlined.FileDownload,
                        onClick = viewModel::exportAllChats,
                        showDivider = true
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_clear_chats_title),
                        description = stringResource(R.string.settings_clear_chats_desc),
                        icon = Icons.Outlined.CleaningServices,
                        onClick = { showClearChatsConfirm = true },
                        showDivider = true
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_wipe_title),
                        description = stringResource(R.string.settings_wipe_desc),
                        icon = Icons.Outlined.DeleteForever,
                        danger = true,
                        onClick = { showWipeConfirm = true },
                        showDivider = false
                    )
                }
                Text(
                    stringResource(R.string.settings_security_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp)
                )
                SectionLabel(stringResource(R.string.settings_section_feedback))
                SettingCard {
                    SettingRow(
                        title = stringResource(R.string.settings_feedback_title),
                        description = stringResource(R.string.settings_feedback_desc),
                        icon = Icons.Outlined.Feedback,
                        onClick = { showFeedback = true },
                        showDivider = false
                    )
                }
                TextButton(onClick = onOpenAbout, modifier = Modifier.padding(horizontal = 2.dp)) {
                    Text(stringResource(R.string.settings_about_entry))
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (showClearChatsConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear_chats_confirm_title),
            message = stringResource(R.string.clear_chats_confirm_message),
            confirmText = stringResource(R.string.clear_chats_confirm_action),
            destructive = true,
            onConfirm = {
                showClearChatsConfirm = false
                viewModel.clearChats()
            },
            onDismiss = { showClearChatsConfirm = false }
        )
    }
    if (showWipeConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.wipe_confirm_title),
            message = stringResource(R.string.wipe_confirm_message),
            confirmText = stringResource(R.string.wipe_confirm_action),
            destructive = true,
            onConfirm = {
                showWipeConfirm = false
                viewModel.deleteAllData()
            },
            onDismiss = { showWipeConfirm = false }
        )
    }
    if (showFeedback) {
        // 反馈：保存成功后才关弹窗（内容不会因为一次失败就丢），失败提示走下面的 snackbar。
        FeedbackDialog(
            saving = feedbackSaving,
            onDismiss = { if (!feedbackSaving) showFeedback = false },
            onSubmit = { kind, content, contact ->
                feedbackSaving = true
                viewModel.submitFeedback(kind, content, contact)
            }
        )
    }
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeOption(
            label = themeLabel(ThemeMode.SYSTEM),
            icon = Icons.Outlined.SettingsBrightness,
            selected = selected == ThemeMode.SYSTEM,
            onClick = { onSelect(ThemeMode.SYSTEM) },
            modifier = Modifier.weight(1f)
        )
        ThemeOption(
            label = themeLabel(ThemeMode.LIGHT),
            icon = Icons.Outlined.LightMode,
            selected = selected == ThemeMode.LIGHT,
            onClick = { onSelect(ThemeMode.LIGHT) },
            modifier = Modifier.weight(1f)
        )
        ThemeOption(
            label = themeLabel(ThemeMode.DARK),
            icon = Icons.Outlined.DarkMode,
            selected = selected == ThemeMode.DARK,
            onClick = { onSelect(ThemeMode.DARK) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.settings_selected_cd),
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
private fun BubbleStyleOption(
    style: BubbleStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(11.dp)
                        .clip(
                            when (style) {
                                BubbleStyle.SOFT -> RoundedCornerShape(6.dp, 6.dp, 2.dp, 6.dp)
                                BubbleStyle.COMPACT -> RoundedCornerShape(3.dp)
                                BubbleStyle.OUTLINED -> RoundedCornerShape(5.dp)
                            }
                        )
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            when (style) {
                                BubbleStyle.SOFT -> RoundedCornerShape(6.dp, 6.dp, 2.dp, 6.dp)
                                BubbleStyle.COMPACT -> RoundedCornerShape(3.dp)
                                BubbleStyle.OUTLINED -> RoundedCornerShape(5.dp)
                            }
                        )
                )
                Box(
                    Modifier
                        .width(36.dp)
                        .height(12.dp)
                        .clip(
                            when (style) {
                                BubbleStyle.SOFT -> RoundedCornerShape(6.dp, 6.dp, 6.dp, 2.dp)
                                BubbleStyle.COMPACT -> RoundedCornerShape(3.dp)
                                BubbleStyle.OUTLINED -> RoundedCornerShape(5.dp)
                            }
                        )
                        .background(if (style == BubbleStyle.OUTLINED) LocalAccentColor.current.copy(alpha = 0.2f) else LocalAccentColor.current)
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when (style) {
                    BubbleStyle.SOFT -> stringResource(R.string.settings_bubble_soft)
                    BubbleStyle.COMPACT -> stringResource(R.string.settings_bubble_compact)
                    BubbleStyle.OUTLINED -> stringResource(R.string.settings_bubble_outlined)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepperCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else Color.Transparent)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.8f else 0.35f),
                CircleShape
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
internal fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = LocalAccentColor.current,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
)

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

private fun parseColor(value: String): Long? {
    val cleaned = value.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val parsed = cleaned.toLongOrNull(16) ?: return null
    return if (cleaned.length == 6) 0xFF000000L or parsed else parsed
}
