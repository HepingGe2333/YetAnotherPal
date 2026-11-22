@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yap.app.ui.screens.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yap.app.R
import com.yap.app.ui.common.ErrorText
import com.yap.app.ui.common.rememberMediaImageRequest
import com.yap.app.data.prefs.BubbleStyle
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.local.ProactiveKind
import com.yap.app.data.local.ProactiveRuleEntity
import com.yap.app.data.prefs.AppSettings
import com.yap.app.ui.components.BottomSheetHandle
import com.yap.app.ui.components.InlineText
import com.yap.app.ui.components.markdownLinkColor
import com.yap.app.ui.components.BottomSheetTitle
import com.yap.app.ui.components.CircleIconButton
import com.yap.app.ui.components.ConfirmDialog
import com.yap.app.ui.components.MarkdownText
import com.yap.app.ui.components.MoodChip
import com.yap.app.ui.components.PersonaAvatar
import com.yap.app.ui.components.UserAvatar
import com.yap.app.ui.components.PresenceText
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.SheetActionRow
import com.yap.app.ui.components.StyledOutlinedField
import com.yap.app.ui.components.TextPill
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.InterruptedReply
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.ToolTraceCodec
import com.yap.app.domain.ToolTraceItem
import com.yap.app.ui.theme.LocalAccentColor
import com.yap.app.ui.theme.LocalAnimationsEnabled
import com.yap.app.ui.theme.LocalAppDark
import com.yap.app.util.dayLabel
import com.yap.app.util.formatClock
import com.yap.app.util.isSameDay
import com.yap.app.data.repo.ProactiveProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private sealed interface ChatDisplayItem {
    data class Date(val label: String, val key: String) : ChatDisplayItem
    data class Message(val message: MessageEntity) : ChatDisplayItem
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenMemory: (String) -> Unit,
    onOpenEvolution: (String) -> Unit,
    onOpenApiConfigs: () -> Unit,
    onOpenProactive: (String) -> Unit,
    onSwitchSession: (String) -> Unit,
    /** 「去设置」的跳转；由导航层接上，没接时引导卡片只保留「知道了」。 */
    onOpenSettings: (() -> Unit)? = null,
    /** 点消息里的链接：在应用里打开看一眼，不跳外部浏览器。 */
    onOpenLink: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val animations = LocalAnimationsEnabled.current
    val isDark = LocalAppDark.current
    val accent = LocalAccentColor.current

    var showProfileSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingExport by remember { mutableStateOf<com.yap.app.domain.ExportFile?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var viewingImage by remember { mutableStateOf<MessageAttachment?>(null) }
    /**
     * 从检索结果跳进来时要高亮的那条消息。
     *
     * 它只在这里活约 [MESSAGE_FOCUS_MILLIS]：滚到位之后给一条可见但不刺眼的底色，
     * 然后淡出 —— 居留太久会让人以为那条消息被「选中」了。
     */
    var focusedMessageId by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris -> viewModel.addImages(uris) }
    // 表情导入：一次选一张（GIF/PNG/WebP/APNG 都认），选完走 sticker 自己的压缩管线。
    val stickerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::importSticker) }
    // 0.13：视频只从系统选择器选（VideoOnly），选完直接进草稿，不在这里转码。
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::addVideo) }
    // 录音权限：第一次按语音按钮时申请；拒绝就提示一句，不反复弹。
    //
    // 授权回来后**不自动开始录**：申请权限那一下用户已经松手了，此时偷偷录一段是
    // 最容易被当成「App 自己在录音」的行为；让用户再按一次更诚实。
    val recordPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) viewModel.onVoicePermissionDenied()
    }
    val coroutineScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        // ActivityResult 回调在主线程：写文件（可能几百 KB～几 MB）必须切到 IO。
        // 0.10.2 之前这里是 `stream.write(file.content.toByteArray())`，StrictMode 会直接报磁盘写。
        val file = pendingExport
        pendingExport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(file.content.toByteArray(Charsets.UTF_8))
                    } ?: error(context.getString(R.string.chat_export_error_unwritable))
                }
            }
            written.fold(
                {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.chat_export_success),
                        duration = SnackbarDuration.Short
                    )
                },
                { error ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.chat_export_failed_reason, ErrorText.of(context, error)),
                        duration = SnackbarDuration.Long
                    )
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.OpenSession -> onSwitchSession(event.sessionId)
                is ChatEvent.ExportReady -> {
                    pendingExport = event.file
                    runCatching { exportLauncher.launch(event.file.fileName) }
                }
                ChatEvent.SessionDeleted -> onBack()
                is ChatEvent.FocusMessage -> focusedMessageId = event.messageId
            }
        }
    }

    LaunchedEffect(state.snackbar) {
        val message = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.dismissSnackbar()
    }

    // 定位高亮：亮 2 秒后自己灭掉（底色由 MessageBubble 用动画过渡）。
    LaunchedEffect(focusedMessageId) {
        val id = focusedMessageId ?: return@LaunchedEffect
        delay(MESSAGE_FOCUS_MILLIS)
        if (focusedMessageId == id) focusedMessageId = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatBackground(state.settings, isDark)
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                persona = state.persona,
                mood = state.mood,
                presence = state.presence,
                config = state.config,
                session = state.session,
                typing = state.typing,
                onBack = onBack,
                onProfile = { showProfileSheet = true },
                onOpenMenu = { showMenu = true }
            )
            if (state.config == null) {
                NoConfigBanner(
                    configsAvailable = state.apiConfigs.isNotEmpty(),
                    onChoose = viewModel::openConfigPicker,
                    onAdd = onOpenApiConfigs
                )
            }
            Box(Modifier.weight(1f)) {
                if (!state.loaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = accent
                        )
                    }
                } else {
                    ChatMessageList(
                        state = state,
                        focusMessageId = focusedMessageId,
                        onLoadOlder = viewModel::loadOlder,
                        onLoadNewer = viewModel::loadNewer,
                        onShowLatest = viewModel::showLatest,
                        onLongPress = { message ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedMessage = message
                        },
                        onViewImage = { viewingImage = it },
                        loadStickerFrames = viewModel::loadStickerFrames,
                        resolveMedia = viewModel::playableFile,
                        onOpenLink = onOpenLink,
                        onSuggestion = { suggestion ->
                            viewModel.setDraft(suggestion)
                        }
                    )
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            if (!state.settings.backgroundTipDismissed) {
                BackgroundTipBanner(
                    onDismiss = viewModel::dismissBackgroundTip,
                    onOpenSettings = onOpenSettings
                )
            }
            if (state.showStickerPanel) {
                val visibleStickers = remember(state.stickers, state.stickerShowAll) {
                    if (state.stickerShowAll) {
                        state.stickers
                    } else {
                        state.stickers.take(com.yap.app.data.repo.StickerRepository.RECENT_LIMIT)
                    }
                }
                val thumbnails = rememberStickerThumbnails(
                    stickers = visibleStickers,
                    visible = state.showStickerPanel,
                    load = viewModel::loadStickerFrames
                )
                StickerPanel(
                    stickers = visibleStickers,
                    showAll = state.stickerShowAll,
                    thumbnails = thumbnails,
                    onToggleShowAll = viewModel::setStickerShowAll,
                    onPick = { sticker -> viewModel.sendSticker(sticker.id) },
                    onImport = {
                        stickerPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClose = viewModel::closeStickerPanel,
                    onDelete = { sticker -> viewModel.deleteSticker(sticker.id) }
                )
            }
            Composer(
                state = state,
                accent = accent,
                onDraftChange = viewModel::setDraft,
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
                onPickImages = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickVideo = {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onRemoveImage = viewModel::removeImage,
                onVoicePress = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.startVoiceRecording()
                    } else {
                        recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceRelease = viewModel::finishVoiceRecording,
                onVoiceCancel = viewModel::cancelVoiceRecording,
                onToggleStickers = viewModel::toggleStickerPanel,
                stickersOpen = state.showStickerPanel
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 54.dp, end = 12.dp)
                .align(Alignment.TopEnd)
        ) {
            DropdownItem(stringResource(R.string.chat_menu_switch_persona)) {
                showMenu = false
                viewModel.openPersonaPicker()
            }
            DropdownItem(stringResource(R.string.chat_clear_context_menu)) {
                showMenu = false
                showClearConfirm = true
            }
            DropdownItem(stringResource(R.string.chat_menu_export)) {
                showMenu = false
                viewModel.exportChat()
            }
            DropdownItem(stringResource(R.string.chat_menu_delete), destructive = true) {
                showMenu = false
                showDeleteConfirm = true
            }
        }
    }

    if (showProfileSheet && state.persona != null && state.session != null) {
        ProfileSheet(
            persona = state.persona!!,
            session = state.session!!,
            configName = state.config?.name,
            configModel = state.session?.model ?: state.config?.model,
            onDismiss = { showProfileSheet = false },
            onEdit = {
                showProfileSheet = false
                onOpenPersona(state.persona!!.id)
            },
            onMemory = {
                showProfileSheet = false
                onOpenMemory(state.persona!!.id)
            },
            onEvolution = {
                showProfileSheet = false
                onOpenEvolution(state.persona!!.id)
            },
            onProactive = {
                showProfileSheet = false
                onOpenProactive(state.persona!!.id)
            },
            onSwitchPersona = {
                showProfileSheet = false
                viewModel.openPersonaPicker()
            }
        )
    }

    if (state.showConfigPicker) {
        ConfigPickerSheet(
            state = state,
            onDismiss = viewModel::closeConfigPicker,
            onSelect = viewModel::selectConfig,
            onAddConfig = {
                viewModel.closeConfigPicker()
                onOpenApiConfigs()
            }
        )
    }

    if (state.showPersonaPicker) {
        PersonaPickerSheet(
            state = state,
            onDismiss = viewModel::closePersonaPicker,
            onSelect = viewModel::switchPersona
        )
    }

    selectedMessage?.let { message ->
        val messageHasSticker = remember(message.id, message.attachmentsJson) {
            com.yap.app.domain.AttachmentsCodec.decode(message.attachmentsJson).any { it.isSticker }
        }
        MessageActionSheet(
            message = message,
            onDismiss = { selectedMessage = null },
            canSaveSticker = messageHasSticker,
            onSaveSticker = {
                selectedMessage = null
                viewModel.saveStickerFromMessage(message.id)
            },
            onCopy = {
                clipboard.setText(AnnotatedString(message.content))
                selectedMessage = null
                coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_copied)) }
            },
            onEdit = {
                selectedMessage = null
                editMessage = message
            },
            onDelete = {
                selectedMessage = null
                deleteMessage = message
            }
        )
    }

    editMessage?.let { message ->
        var value by remember(message.id) { mutableStateOf(message.content) }
        AlertDialog(
            onDismissRequest = { editMessage = null },
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.chat_edit_title)) },
            text = {
                StyledOutlinedField(
                    value = value,
                    onValueChange = { value = it },
                    label = stringResource(R.string.chat_your_message),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 10
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.editUserMessage(message.id, value)
                    editMessage = null
                }, enabled = value.isNotBlank()) { Text(stringResource(R.string.chat_edit_save)) }
            },
            dismissButton = { TextButton(onClick = { editMessage = null }) { Text(stringResource(R.string.chat_cancel)) } }
        )
    }

    deleteMessage?.let { message ->
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_message_title),
            message = stringResource(R.string.chat_delete_message_body),
            confirmText = stringResource(R.string.chat_delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteMessage(message.id)
                deleteMessage = null
            },
            onDismiss = { deleteMessage = null }
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.chat_clear_context_title),
            message = stringResource(R.string.chat_clear_context_body),
            confirmText = stringResource(R.string.chat_clear_context_action),
            destructive = true,
            onConfirm = {
                showClearConfirm = false
                viewModel.clearContext()
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    viewingImage?.let { attachment ->
        ImageViewerDialog(attachment = attachment, onDismiss = { viewingImage = null })
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_conversation_title),
            message = stringResource(R.string.chat_delete_conversation_body),
            confirmText = stringResource(R.string.chat_delete),
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteSession()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

/**
 * 一个「目标尺寸写死」的图片请求。
 *
 * 聊天里的每一张图都必须走这里：Coil 会按目标尺寸采样解码，而不是把相册原图整张
 * 解出来再交给 ContentScale。没有 size 的请求是最容易被忽略的内存黑洞——
 * 一张 12MP 的照片按原尺寸是 48MB，按 272dp 的显示尺寸只有几百 KB。
 */
@Composable
private fun rememberSizedImageRequest(data: String?, width: Dp, height: Dp): ImageRequest? {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    // 本地图片是 file://，服务端同步回来的消息里是 `/v1/media/<id>`：
    // 统一交给解析器（它会补 Authorization），聊天页不再出现「本地能看、服务端看不了」。
    return rememberMediaImageRequest(raw = data, widthPx = widthPx, heightPx = heightPx)
}

/** 全屏尺寸（背景、大图查看器）：按屏幕像素解码，不碰原图分辨率。 */
@Composable
private fun rememberScreenSize(): Pair<Int, Int> {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            (configuration.screenWidthDp.dp.roundToPx().coerceAtLeast(1)) to
                (configuration.screenHeightDp.dp.roundToPx().coerceAtLeast(1))
        }
    }
}

@Composable
private fun ChatBackground(settings: AppSettings, isDark: Boolean) {
    if (!settings.chatBackgroundImageUri.isNullOrBlank()) {
        val context = LocalContext.current
        val (screenWidthPx, screenHeightPx) = rememberScreenSize()
        val backgroundRequest = remember(settings.chatBackgroundImageUri, screenWidthPx, screenHeightPx) {
            ImageRequest.Builder(context)
                .data(settings.chatBackgroundImageUri)
                .size(screenWidthPx, screenHeightPx)
                .build()
        }
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = backgroundRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (isDark) Color.Black.copy(alpha = 0.62f)
                        else Color.White.copy(alpha = 0.66f)
                    )
            )
        }
    } else {
        val color = settings.chatBackgroundColor?.let { Color(it) } ?: MaterialTheme.colorScheme.background
        Box(
            Modifier
                .fillMaxSize()
                .background(color)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, LocalAccentColor.current.copy(alpha = 0.025f))
                    )
                )
        )
    }
}

@Composable
private fun ChatTopBar(
    persona: PersonaEntity?,
    mood: com.yap.app.domain.MoodState,
    presence: com.yap.app.domain.Presence,
    config: com.yap.app.data.local.ApiConfigEntity?,
    session: ChatSessionEntity?,
    typing: Boolean,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(66.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.chat_back),
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = onProfile, onLongClick = null)
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (persona != null) {
                    Box {
                        PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 40.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(
                                    if (config == null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            persona.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (typing) {
                            TypingSubtitle()
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 心情先露一眼：他此刻是什么状态，比模型名字更值得知道。
                                MoodChip(mood, compact = true)
                                // 副标题让给「他正在……」：他自己在做什么，用户只是看见。
                                PresenceText(
                                    presence = presence,
                                    modifier = Modifier.weight(1f, fill = false),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            CircleIconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.chat_more),
                onClick = onOpenMenu,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/**
 * 标题栏里的「正在输入…」，像微信那样，等他自己开口。
 */
@Composable
private fun TypingSubtitle() {
    val animations = LocalAnimationsEnabled.current
    val accent = LocalAccentColor.current
    val transition = rememberInfiniteTransition(label = "typing-subtitle")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.chat_typing),
            style = MaterialTheme.typography.labelSmall,
            color = accent.copy(alpha = 0.95f)
        )
        Spacer(Modifier.width(3.dp))
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 140),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typingSubtitleDot$index"
            )
            Text(
                "·",
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = if (animations) alpha else 0.8f)
            )
        }
    }
}

@Composable
private fun NoConfigBanner(
    configsAvailable: Boolean,
    onChoose: () -> Unit,
    onAdd: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.chat_no_config_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = if (configsAvailable) onChoose else onAdd) {
                Text(
                    if (configsAvailable) stringResource(R.string.chat_choose)
                    else stringResource(R.string.chat_add),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** 加载更早之前的视口锚点。 */
private data class Anchor(val key: String, val offset: Int, val displayCount: Int)

/** 检索定位的高亮停留时长：够看清「就是这条」，又不至于让人以为它被选中了。 */
private const val MESSAGE_FOCUS_MILLIS = 2_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageList(
    state: ChatUiState,
    /** 从检索结果跳进来时要滚到并高亮的那条消息（null = 正常打开会话）。 */
    focusMessageId: String? = null,
    onLoadOlder: () -> Unit,
    /** 滚到底且不在最新块：向下加载一块（0.12 新增，不再只能「跳回最新」）。 */
    onLoadNewer: () -> Unit,
    /** 「回到底部」按钮：直接回到最新一屏（只保留最新一块）。 */
    onShowLatest: () -> Unit,
    onLongPress: (MessageEntity) -> Unit,
    onViewImage: (MessageAttachment) -> Unit,
    onSuggestion: (String) -> Unit,
    /** 按需解一张表情的帧（本地文件 / 服务端媒体都走这条）。 */
    loadStickerFrames: suspend (String) -> com.yap.app.domain.StickerSource?,
    /** 0.13：把一条语音/视频附件解析成本机可播放文件（服务端媒体先缓存）。 */
    resolveMedia: suspend (MessageAttachment) -> File?,
    onOpenLink: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val coroutineScope = rememberCoroutineScope()
    var autoFollow by remember { mutableStateOf(true) }
    /**
     * 「自动贴底」的滚动动画。
     *
     * 它是一个会跑几百毫秒的协程：从检索结果定位过来时，最后那一页加载完会起一条
     * 贴底动画，若不取消，它会把刚刚滚到目标消息的视口又拽回底部（第一版就踩了这个坑）。
     */
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    /**
     * 「跟着往下加载」：点了底部那一行之后，每次新块拼上来都贴到最新位置，
     * 于是「滚到底 → 自动加载下一块」会一路走到最新块；用户一拖动就停。
     */
    var followNewer by remember { mutableStateOf(false) }
    val displayItems = remember(state.messages, context) {
        buildList {
            var previousTimestamp: Long? = null
            state.messages.forEach { message ->
                val previous = previousTimestamp
                if (previous == null || !isSameDay(previous, message.createdAt)) {
                    add(ChatDisplayItem.Date(dayLabel(context, message.createdAt), dayLabel(context, message.createdAt) + message.createdAt))
                }
                add(ChatDisplayItem.Message(message))
                previousTimestamp = message.createdAt
            }
        }
    }
    // 列表顶部这一行：还有更早的消息 / 正在加载更早。它是 LazyColumn 的第 0 项，
    // 所以下面算「消息在列表里的绝对下标」时要带上这个偏移。
    val showLoadEarlier = state.hasMoreOlder || state.loadingOlder
    val loadEarlierKey = "load-earlier"
    fun itemKey(item: ChatDisplayItem): String = when (item) {
        is ChatDisplayItem.Date -> "date-${item.key}"
        is ChatDisplayItem.Message -> item.message.id
    }

    val isAtBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            info.totalItemsCount == 0 ||
                (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 48)
        }
    }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoFollow = false
            // 用户自己接手滚动：不再替他把视口往下拽。
            followNewer = false
        }
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoFollow = true
    }
    LaunchedEffect(displayItems.size) {
        if (focusMessageId != null) return@LaunchedEffect
        if (autoFollow && displayItems.isNotEmpty()) {
            autoScrollJob?.cancel()
            autoScrollJob = launch {
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
            }
        }
    }
    // 点了「下面还有更新的消息」之后：每加载一块都贴到当前底部，
    // 到了底部 isAtBottom 又为真，于是自动加载继续往下走（见下面的 LaunchedEffect）。
    LaunchedEffect(state.messages.size, followNewer) {
        if (followNewer && displayItems.isNotEmpty()) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(state.streamingText.length, state.isGenerating) {
        if (autoFollow) {
            val target = listState.layoutInfo.totalItemsCount - 1
            if (target >= 0) listState.scrollToItem(target)
        }
    }

    // 上滑加载更早：滚到顶部附近就触发一次。是否真的发查询由 ViewModel 的状态机决定
    // （加载中 / 已到底都会直接忽略），所以这里只负责「该不该问」。
    LaunchedEffect(state.hasMoreOlder, state.loadingOlder, state.messages.size) {
        if (!state.hasMoreOlder || state.loadingOlder || state.messages.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, _) ->
            // 只在第 0 项（「加载更早」那一行）真正露出来时触发：加载后我们按 key 把视口
            // 恢复到原来那条消息（下标必然 ≥1），所以不会「一加载完又立刻触发下一頁」地连锁加载。
            if (index == 0) onLoadOlder()
        }
    }

    // 滚到底（且不在最新块）：继续往下加载一块。是否真的发查询由 ViewModel 的状态机决定
    // （加载中 / 已经到最新块都会直接忽略），所以这里可以在每次重组时放心调用。
    LaunchedEffect(state.hasMoreNewer, state.loadingNewer, isAtBottom) {
        if (state.hasMoreNewer && !state.loadingNewer && isAtBottom) onLoadNewer()
    }

    // 往前面插入旧消息之后，LazyColumn 的「第几项」会整体后移，视口会跳。
    // 这里记住加载前「第一条可见消息的 key + 偏移」，等列表更新后按 key 找回它，
    // 视觉上就是「停在原地、上面悄悄多出一段」。
    var restoreAnchor by remember { mutableStateOf<Anchor?>(null) }
    LaunchedEffect(state.loadingOlder) {
        if (state.loadingOlder) {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val key = info?.key as? String
            if (key != null && key != loadEarlierKey) {
                // 记下「第一条可见消息 + 它相对视口的偏移 + 当时列表长度」。
                restoreAnchor = Anchor(key, info.offset, displayItems.size)
            }
        }
    }
    LaunchedEffect(state.messages.size) {
        val anchor = restoreAnchor ?: return@LaunchedEffect
        // 条数没变说明这一页是空的（已经到底）：丢掉锚点，别等到下一条新消息时乱跳。
        if (displayItems.size <= anchor.displayCount) {
            restoreAnchor = null
            return@LaunchedEffect
        }
        restoreAnchor = null
        // 旧消息插在前面之后，按 key 找回原来那条消息的绝对下标，停在原来的位置。
        val index = run {
            var absolute = if (showLoadEarlier) 1 else 0
            for (item in displayItems) {
                if (itemKey(item) == anchor.key) return@run absolute
                absolute++
            }
            -1
        }
        if (index >= 0) listState.scrollToItem(index, anchor.offset)
    }

    // 定位到检索命中的那条消息。
    //
    // 依赖 displayItems.size 是必须的：从检索跳进来时可能还要往前翻好几页，目标消息
    // 进入窗口的那一次重组才会让 indexOfFirst 找到它。
    // 滚到目标上方留 2 条上下文，比「贴顶」更容易看出这段话在讲什么。
    LaunchedEffect(focusMessageId, displayItems.size) {
        val id = focusMessageId ?: return@LaunchedEffect
        val index = displayItems.indexOfFirst { it is ChatDisplayItem.Message && it.message.id == id }
        if (index < 0) return@LaunchedEffect
        // 定位期间关掉「自动贴底」，并取消已经在跑的贴底动画（否则它会把视口又拉回底部）。
        autoFollow = false
        autoScrollJob?.cancel()
        autoScrollJob = null
        // 等一帧：列表刚变长时 layoutInfo 还没稳定，直接 scrollToItem 可能被这一帧的测量修正。
        withFrameNanos { }
        val offset = if (showLoadEarlier) 1 else 0
        listState.scrollToItem((index - 2).coerceAtLeast(0) + offset)
    }

    Box(Modifier.fillMaxSize()) {
        if (state.messages.isEmpty() && !state.isGenerating) {
            EmptyConversation(state, onSuggestion)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (showLoadEarlier) {
                    item(key = loadEarlierKey) {
                        LoadEarlierRow(loading = state.loadingOlder)
                    }
                }
                items(displayItems, key = { itemKey(it) }) { item ->
                    when (item) {
                        is ChatDisplayItem.Date -> DateDivider(item.label)
                        is ChatDisplayItem.Message -> {
                            MessageBubble(
                                message = item.message,
                                settings = state.settings,
                                userAvatarUri = state.settings.userAvatarUri,
                                userAvatarFallbackUri = state.settings.userAvatarLocalUri,
                                highlighted = item.message.id == focusMessageId,
                                // 规格 §2.3：只有「他已读」且「这一轮没有助手回复」时才显示「已读」，
                                // 否则每条用户消息都挂一个小字，会像回执系统而不像一个人。
                                // 0.11.0（审计 P2-10）：「最后一条助手消息」由 ViewModel 一次算好
                                // （state.lastAssistantAt），这里每条气泡只做一次比较，
                                // 不再对每条可见消息扫整个已加载窗口（翻完历史后是 O(n²)）。
                                showRead = com.yap.app.domain.ReadReceipt.shouldShow(
                                    readAt = item.message.readAt,
                                    userCreatedAt = item.message.createdAt,
                                    lastAssistantAt = state.lastAssistantAt
                                ),
                                onLongPress = { onLongPress(item.message) },
                                onViewImage = onViewImage,
                                loadStickerFrames = loadStickerFrames,
                                resolveMedia = resolveMedia,
                                onOpenLink = onOpenLink
                            )
                        }
                    }
                }
                if (state.typing || state.streamingText.isNotBlank()) {
                    // 首字到达前（以及连发之间）只显示输入指示，不画带「…」的占位气泡。
                    // 服务端模式：只有前台收到 typing.started 才为 true（§8）；离线同步不补动画。
                    // 老服务端若仍走 delta，也保留暂存气泡的兼容显示（新服务端 message.created 直接入 Room）。
                    if (state.streamingText.isBlank()) {
                        item(key = "typing") { TypingRow() }
                    } else {
                        item(key = "streaming") {
                            StreamingBubble(
                                text = state.streamingText,
                                settings = state.settings,
                                traces = state.streamingTraces,
                                onOpenLink = onOpenLink
                            )
                        }
                    }
                }
                if (state.hasMoreNewer || state.loadingNewer) {
                    // 规范 §3 Newer：不在最新块时，底部提示「下面还有更新的消息」。
                    // 滚到底会自动加载（见上面的 LaunchedEffect），点一下也加载——
                    // 不再是「只能跳回最新、中间那段永远看不到」。
                    item(key = "load-newer") {
                        LoadNewerRow(
                            loading = state.loadingNewer,
                            onClick = {
                                // 点一下就开始「跟着往下」：加载一块 + 贴到底，
                                // 之后滚到底会自动接着加载，直到最新块。
                                followNewer = true
                                autoFollow = true
                                onLoadNewer()
                            }
                        )
                    }
                }
                item(key = "tail-space") { Spacer(Modifier.height(4.dp)) }
            }
            AnimatedVisibility(
                visible = !isAtBottom && state.messages.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 10.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                autoFollow = true
                                if (state.hasMoreNewer) {
                                    // 下面还有没加载的块：直接回到最新一屏（只留最新一块），
                                    // 而不是先滚到「已加载的最后一条」再逐块往下爬。
                                    onShowLatest()
                                } else {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
                                    }
                                }
                            },
                            onLongClick = null
                        )
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ExpandMore, contentDescription = stringResource(R.string.chat_scroll_to_bottom), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_latest), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(state: ChatUiState, onSuggestion: (String) -> Unit) {
    val persona = state.persona ?: return
    val accent = LocalAccentColor.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 76.dp, ringColor = accent.copy(alpha = 0.55f))
        Spacer(Modifier.height(18.dp))
        Text(persona.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(7.dp))
        val emptyDescription = stringResource(R.string.chat_empty_fallback_description)
        Text(
            text = persona.description.ifBlank { emptyDescription },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        val suggestions = listOf(
            stringResource(R.string.chat_suggestion_1),
            stringResource(R.string.chat_suggestion_2),
            stringResource(R.string.chat_suggestion_3)
        )
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onSuggestion(suggestion) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 9.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.chat_local_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** 列表顶部那一行：「还有更早的消息」/「正在加载更早」。 */
@Composable
private fun LoadEarlierRow(loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = LocalAccentColor.current
            )
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.chat_loading_earlier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                stringResource(R.string.chat_load_earlier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 列表底部那一行：「下面还有更新的消息」。
 *
 * 0.12（规范 §3）起它是**加载入口**而不只是提示：点击加载下一块，滚到底也会自动加载；
 * 加载中显示转圈并挡住重复触发。
 */
@Composable
private fun LoadNewerRow(loading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(enabled = !loading, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = LocalAccentColor.current
            )
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.chat_loading_newer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                stringResource(R.string.chat_load_newer),
                style = MaterialTheme.typography.labelSmall,
                color = LocalAccentColor.current
            )
        }
    }
}

@Composable
private fun DateDivider(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    settings: AppSettings,
    /** 「我的头像」；null 时不画（默认就是没有头像的克制样子）。 */
    userAvatarUri: String? = null,
    userAvatarFallbackUri: String? = null,
    /** 从检索结果定位过来时短暂亮一下（见 ChatScreen 的 focusedMessageId）。 */
    highlighted: Boolean = false,
    showRead: Boolean = false,
    onLongPress: () -> Unit,
    onViewImage: (MessageAttachment) -> Unit,
    /** 表情要逐帧播放，所以这里要一个「异步解帧」的口子（Coil 的动图支持在额外依赖里）。 */
    loadStickerFrames: suspend (String) -> com.yap.app.domain.StickerSource?,
    /** 0.13：语音/视频播放前的本地文件解析（服务端引用会先下载到缓存）。 */
    resolveMedia: suspend (MessageAttachment) -> File? = { null },
    onOpenLink: (String) -> Unit
) {
    val attachments = remember(message.id, message.attachmentsJson) {
        AttachmentsCodec.decode(message.attachmentsJson)
    }
    // 他这一轮做过什么（搜了什么、记住了什么）。折叠成一行小字，点开看细节。
    // 0.16：`toolTrace` 里还可能有「回复通道」标记（send_message / legacy / status）——
    // 那是给迁移判定和日志用的，不是用户可见的动作，渲染前必须过滤掉。
    val traceItems = remember(message.id, message.toolTraceJson) {
        ToolTraceCodec.visible(ToolTraceCodec.decode(message.toolTraceJson))
    }
    // 展开状态跟着消息 id 走，滚动收起再回来时保持；不跨消息串味。
    var traceExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val outgoing = message.role == MessageRole.USER
    val context = LocalContext.current
    // 纯图片消息（无正文）用更小的内边距，让照片几乎贴着气泡边缘，
    // 否则会像被一圈底色装裱起来，观感偏重。
    val stickerOnly = attachments.isNotEmpty() && attachments.all { it.isSticker }
    val imageOnly = attachments.isNotEmpty() &&
        message.content.isBlank() &&
        message.status != MessageStatus.ERROR
    val padH = when {
        stickerOnly -> 6.dp
        imageOnly -> 5.dp
        settings.bubbleStyle == BubbleStyle.COMPACT -> 12.dp
        else -> 14.dp
    }
    val padV = when {
        stickerOnly -> 6.dp
        imageOnly -> 5.dp
        settings.bubbleStyle == BubbleStyle.COMPACT -> 8.dp
        else -> 10.dp
    }
    val accent = LocalAccentColor.current
    val animations = LocalAnimationsEnabled.current
    val shape = bubbleShape(settings.bubbleStyle, outgoing)
    val isOutlined = settings.bubbleStyle == BubbleStyle.OUTLINED
    val background = when {
        isOutlined && outgoing -> accent.copy(alpha = 0.10f)
        isOutlined -> MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        outgoing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    }
    val contentColor = when {
        isOutlined -> MaterialTheme.colorScheme.onSurface
        outgoing -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        isOutlined && outgoing -> androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f))
        isOutlined -> androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        outgoing -> null
        else -> androidx.compose.foundation.BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    }
    val showUserAvatar = outgoing && !userAvatarUri.isNullOrBlank()
    // 定位高亮：一条横贯整行的浅色底，2 秒后由 ChatScreen 撤掉 highlighted，这里再淡出。
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(durationMillis = if (highlighted) 220 else 600),
        label = "searchHitHighlight"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                color = accent.copy(alpha = 0.16f * highlightAlpha),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(vertical = if (settings.bubbleStyle == BubbleStyle.COMPACT) 1.dp else 2.dp),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 0.14 拉黑：红色感叹号在气泡左侧，提示与气泡分开，不挤占正文。
            if (outgoing && message.blocked) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(R.string.chat_blocked_icon_desc),
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 5.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Surface(
                modifier = Modifier
                    .widthIn(min = 56.dp, max = 560.dp)
                    // 头像占 28dp + 6dp 间距：气泡整体往左让出这条槽，两者不会叠在一起。
                    .padding(end = if (showUserAvatar) 34.dp else 0.dp)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
            shape = shape,
            color = background,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                Modifier.padding(
                    horizontal = padH,
                    vertical = padV
                )
            ) {
                attachments.forEach { attachment ->
                    // 0.13：语音/视频各自一个气泡内组件（音频播放器 / 视频首帧 + 点开播放）。
                    if (attachment.isAudio) {
                        VoiceBubble(
                            attachment = attachment,
                            tint = contentColor,
                            accent = if (outgoing && !isOutlined) contentColor else accent,
                            resolveFile = resolveMedia,
                            modifier = Modifier.padding(bottom = if (imageOnly) 2.dp else 6.dp)
                        )
                        return@forEach
                    }
                    if (attachment.isVideo) {
                        VideoBubble(
                            attachment = attachment,
                            tint = contentColor,
                            resolveFile = resolveMedia,
                            modifier = Modifier.padding(bottom = if (imageOnly) 2.dp else 6.dp)
                        )
                        return@forEach
                    }
                    if (attachment.isSticker) {
                        // 表情：自己解帧自己播（240dp、等比、不动时只画第一帧），点开看大图。
                        var stickerFrames by remember(attachment.uri) {
                            mutableStateOf<com.yap.app.domain.StickerSource?>(null)
                        }
                        LaunchedEffect(attachment.uri) {
                            stickerFrames = loadStickerFrames(attachment.uri)
                        }
                        StickerImage(
                            source = stickerFrames,
                            contentDescription = stringResource(R.string.chat_sticker_message),
                            maxSize = STICKER_BUBBLE_SIZE,
                            modifier = Modifier
                                .padding(bottom = if (imageOnly) 2.dp else 6.dp)
                                .widthIn(max = STICKER_BUBBLE_SIZE),
                            onClick = { onViewImage(attachment) },
                            onLongClick = onLongPress
                        )
                        return@forEach
                    }
                    // 气泡里图片的最大显示尺寸就是解码目标：272×340 / 248×300dp。
                    val imageRequest = rememberSizedImageRequest(
                        data = attachment.uri,
                        width = if (imageOnly) 272.dp else 248.dp,
                        height = if (imageOnly) 340.dp else 300.dp
                    )
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = stringResource(R.string.chat_image_message),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(bottom = if (imageOnly) 2.dp else 6.dp)
                            .widthIn(max = if (imageOnly) 272.dp else 248.dp)
                            .heightIn(max = if (imageOnly) 340.dp else 300.dp)
                            .clip(RoundedCornerShape(if (imageOnly) 16.dp else 14.dp))
                            .combinedClickable(
                                onClick = { onViewImage(attachment) },
                                onLongClick = onLongPress
                            )
                    )
                }
                if (message.content.isNotBlank()) {
                    MarkdownText(
                        text = message.content,
                        color = contentColor,
                        isOutgoing = outgoing && !isOutlined,
                        compact = settings.bubbleStyle == BubbleStyle.COMPACT,
                        onOpenLink = onOpenLink,
                        selectable = false,
                        onLongPress = onLongPress
                    )
                } else if (InterruptedReply.showsStatusPlaceholder(message.content, message.status == MessageStatus.STOPPED) &&
                    attachments.isEmpty()
                ) {
                    // 审计 P2-8：只有工具调用、没有正文就点了「停止」的那一轮，以前会落一条
                    // content="" 的消息——既没正文也不渲染工具痕迹，是个点不开的空气泡。
                    // 现在落库的是一条「状态消息」，这里按资源显示「已停止」。
                    Text(
                        stringResource(R.string.chat_stopped_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (outgoing && !isOutlined) {
                            contentColor.copy(alpha = 0.75f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (message.status == MessageStatus.ERROR) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (outgoing && !isOutlined) Color.White.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (outgoing && !isOutlined) contentColor else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.chat_generation_failed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (outgoing && !isOutlined) contentColor else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            val errorTint = (if (outgoing && !isOutlined) contentColor
                            else MaterialTheme.colorScheme.onErrorContainer).copy(alpha = 0.85f)
                            // 报错里偶尔带着地址（比如某个服务给的提示），同样点得开
                            InlineText(
                                // 落库的是错误令牌：按当前语言翻译；老数据（中文原文）原样显示。
                                raw = ErrorText.stored(context, message.errorMessage)
                                    ?: stringResource(R.string.chat_unknown_error),
                                color = errorTint,
                                codeBackground = Color.Transparent,
                                isOutgoing = outgoing && !isOutlined,
                                linkColor = markdownLinkColor(outgoing && !isOutlined),
                                onOpenLink = onOpenLink,
                                style = MaterialTheme.typography.bodySmall,
                                // 报错里也可能带链接；长按同样要叫得出菜单，不然这段文字像块死区。
                                onLongPress = onLongPress
                            )
                        }
                    }
                }
                if (traceItems.isNotEmpty() && message.status == MessageStatus.COMPLETE) {
                    // 出向气泡（用户消息）一般没有动作痕迹；真有时退回气泡自身文字色，避免在 accent 底上看不清。
                    val baseTint = if (outgoing && !isOutlined) contentColor
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    val traceTint = baseTint.copy(alpha = 0.75f)
                    Spacer(Modifier.height(6.dp))
                    ToolTraceRow(
                        items = traceItems,
                        expanded = traceExpanded,
                        onToggle = { traceExpanded = !traceExpanded },
                        tint = traceTint,
                        onLongPress = onLongPress,
                        // 和时间戳 Row 一样只占自身宽度并对齐到气泡内侧，
                        // 用 fillMaxWidth 会把气泡撑到最大宽度。
                        modifier = Modifier.align(if (outgoing) Alignment.End else Alignment.Start)
                    )
                    AnimatedVisibility(
                        visible = traceExpanded,
                        modifier = Modifier.align(if (outgoing) Alignment.End else Alignment.Start)
                    ) {
                        ToolTraceDetail(items = traceItems, tint = traceTint)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    // 时间戳只占自身宽度并对齐到气泡内侧：
                    // 用 fillMaxWidth 会把每个气泡都撑到最大宽度。
                    modifier = Modifier
                        .align(if (outgoing) Alignment.End else Alignment.Start)
                        .padding(
                            horizontal = if (imageOnly) 7.dp else 0.dp,
                            vertical = if (imageOnly) 3.dp else 0.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 0.14 拉黑：提示挂在时间戳行，红叹号在气泡左侧外面（见上面的 Row）。
                    if (com.yap.app.domain.BlockPolicy.showsBlockedIndicator(
                            roleIsUser = outgoing,
                            blocked = message.blocked
                        )
                    ) {
                        Text(
                            stringResource(R.string.chat_blocked_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (message.status == MessageStatus.STOPPED) {
                        Text(
                            stringResource(R.string.chat_status_stopped),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.62f)
                        )
                    }
                    if (message.edited) {
                        Text(
                            stringResource(R.string.chat_status_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.62f)
                        )
                    }
                    Text(
                        text = formatClock(LocalContext.current, message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.62f)
                    )
                    if (outgoing && showRead) {
                        Text(
                            stringResource(R.string.chat_status_read),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.62f)
                        )
                    }
                }
            }
        }
        }
        if (showUserAvatar) {
            // 像 Telegram 的「自己这一侧」：小头像贴在这条消息的右下角，只占一条窄槽。
            UserAvatar(
                avatarUri = userAvatarUri,
                fallbackUri = userAvatarFallbackUri,
                size = 28.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    settings: AppSettings,
    // 生成过程中已经发生的动作（ViewModel 接好后传实时的，目前先走默认空列表）。
    traces: List<ToolTraceItem> = emptyList(),
    onOpenLink: (String) -> Unit = {}
) {
    var traceExpanded by rememberSaveable { mutableStateOf(false) }
    val outgoing = false
    val shape = bubbleShape(settings.bubbleStyle, outgoing)
    Surface(
        modifier = Modifier
            .widthIn(min = 56.dp, max = 560.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            // 调用方保证这里 text 非空；空文本由 TypingRow 负责，不再生成占位气泡。
            MarkdownText(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                isOutgoing = false,
                compact = settings.bubbleStyle == BubbleStyle.COMPACT,
                onOpenLink = onOpenLink,
                selectable = false
            )
            BlinkingCursor()
            if (traces.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                StreamingTraceRow(
                    items = traces,
                    expanded = traceExpanded,
                    onToggle = { traceExpanded = !traceExpanded }
                )
            }
        }
    }
}

/**
 * 生成中的动作行：药丸样式的一行「联网搜索」，点开可以看到已经拿到的细节。
 */
@Composable
private fun StreamingTraceRow(
    items: List<ToolTraceItem>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val summary = ToolTraceCodec.summarize(items, traceCountText(context))
        .ifBlank { stringResource(R.string.chat_trace_busy) }
    Column(Modifier.widthIn(max = 460.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onToggle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = tint.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_trace_collapse)
                else stringResource(R.string.chat_trace_expand),
                    modifier = Modifier.size(13.dp),
                    tint = tint.copy(alpha = 0.7f)
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            ToolTraceDetail(items = items, tint = tint.copy(alpha = 0.75f))
        }
    }
}

/**
 * 气泡里的动作行折叠头：图标 + 一句话摘要 + 展开箭头。
 * 整行可点，长按仍然交给外层的删除菜单，只包住这一行，不抢气泡正文的点击。
 */
@Composable
private fun ToolTraceRow(
    items: List<ToolTraceItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = 1.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "· " + ToolTraceCodec.summarize(items, traceCountText(LocalContext.current))
                .ifBlank { stringResource(R.string.chat_trace_title) },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.chat_trace_collapse)
                else stringResource(R.string.chat_trace_expand),
            modifier = Modifier.size(14.dp),
            tint = tint
        )
    }
}

/**
 * 痕迹摘要里的「N 次」：中文只有一种说法，英文要分 one/other，所以走 plurals。
 *
 * 标题本身是写入时的显示文案（见 `data/repo/ToolTraceText`），这里只补计数词。
 */
private fun traceCountText(context: android.content.Context): (Int) -> String = { count ->
    context.resources.getQuantityString(R.plurals.chat_trace_count, count, count)
}

/**
 * 展开后的逐条明细：标题（· 副标题），正文最多 3 行；失败的条目整条用 error 色并标注「失败」。
 */
@Composable
private fun ToolTraceDetail(
    items: List<ToolTraceItem>,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val errorColor = MaterialTheme.colorScheme.error
    val context = LocalContext.current
    Column(
        modifier = modifier
            .widthIn(max = 440.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items.forEach { item ->
            val itemColor = if (item.ok) tint.copy(alpha = 0.92f) else errorColor
            // 「失败」两个字同样走资源，所以先取出来，避免在纯列表拼接里调 Composable。
            val failedLabel = stringResource(R.string.chat_trace_failed)
            val headline = buildList {
                add(item.title.ifBlank { item.name })
                item.subtitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (!item.ok) add(failedLabel)
            }.filter { it.isNotBlank() }.joinToString(" · ")
            Column {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelSmall,
                    color = itemColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = ErrorText.stored(context, item.detail) ?: item.detail
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = itemColor.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 生成中的输入指示：一行三个点，没有气泡、没有「…」、不占一条消息。
 *
 * 这是 0.14 规范 §1 的核心：宁可只显示「他正在输入」，也不要在列表里先放一个空气泡。
 */
@Composable
private fun TypingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypingIndicator()
    }
}

@Composable
private fun TypingIndicator() {
    val animations = LocalAnimationsEnabled.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha = if (animations) {
                val animated by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 140),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot$index"
                )
                animated
            } else 0.6f
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val animations = LocalAnimationsEnabled.current
    if (!animations) return
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursorAlpha"
    )
    Text(
        "▍",
        color = LocalAccentColor.current.copy(alpha = alpha),
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 2.dp)
    )
}

private fun bubbleShape(style: BubbleStyle, outgoing: Boolean): RoundedCornerShape = when (style) {
    BubbleStyle.SOFT -> if (outgoing) RoundedCornerShape(21.dp, 21.dp, 7.dp, 21.dp)
    else RoundedCornerShape(21.dp, 21.dp, 21.dp, 7.dp)
    BubbleStyle.COMPACT -> if (outgoing) RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp)
    else RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp)
    BubbleStyle.OUTLINED -> RoundedCornerShape(18.dp)
}

/**
 * 0.15：拉黑倒计时（输入区上方一行）。
 *
 * 每秒刷新一次，`blockedUntil` 到期后整行消失（[BlockCountdown.isActive] 为 false 时不再渲染）。
 * 文案只有两种（小时+分钟 / 分钟），避免「0 小时 12 分」这种读起来像 bug 的写法。
 */
@Composable
private fun BlockedCountdownBanner(blockedUntil: Long?) {
    if (blockedUntil == null || blockedUntil <= 0L) return
    var now by remember(blockedUntil) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(blockedUntil) {
        while (com.yap.app.domain.BlockCountdown.isActive(blockedUntil, System.currentTimeMillis())) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val parts = com.yap.app.domain.BlockCountdown.parts(blockedUntil, now) ?: return
    val text = if (parts.hours > 0) {
        stringResource(R.string.chat_blocked_remaining_hours, parts.hours, parts.minutes)
    } else {
        stringResource(R.string.chat_blocked_remaining_minutes, parts.minutes)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
    )
}

@Composable
private fun Composer(
    state: ChatUiState,
    accent: Color,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImages: () -> Unit,
    /** 0.13：视频按钮（系统 VideoOnly 选择器，选完进草稿，不转码）。 */
    onPickVideo: () -> Unit,
    onRemoveImage: (String) -> Unit,
    /** 0.13：按住录音 → 松开发送。 */
    onVoicePress: () -> Unit,
    onVoiceRelease: () -> Unit,
    onVoiceCancel: () -> Unit,
    /** 表情按钮（与图片按钮分开）：点开/收起「我的表情」面板。 */
    onToggleStickers: () -> Unit,
    stickersOpen: Boolean
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(state.draft) {
        if (state.draft.isEmpty() && value.text.isNotEmpty()) {
            value = TextFieldValue("")
        } else if (state.draft.isNotEmpty() && value.text.isEmpty()) {
            value = TextFieldValue(state.draft, TextRange(state.draft.length))
        }
    }
    val sendEnabled = (state.draft.isNotBlank() || state.images.isNotEmpty()) && state.canSend && !state.isGenerating
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
        ) {
            // 0.15 拉黑：输入区上方实时显示「你已被拉黑，还剩 X 小时 Y 分」，到期自动消失。
            // 每秒只重算一次文本，不写库；bubble 上的红叹号仍由消息自己的 blocked 标记决定。
            BlockedCountdownBanner(blockedUntil = state.persona?.blockedUntil)
            // 0.12 续 7：这一轮他被睡眠静默了，就在输入框正上方说清楚为什么没有回复。
            // 不是消息气泡、不写库；他醒了或回了话，[ChatUiState.sleepHint] 自己变空。
            state.sleepHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
                )
            }
            if (state.images.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.images.forEach { attachment ->
                        when {
                            // 语音/视频：一个小方块显示类型和时长，右上角删除。
                            attachment.isAudio -> PendingMediaTile(
                                icon = Icons.Rounded.Mic,
                                label = com.yap.app.domain.AudioVideoPolicy.clockDuration(attachment.durationMs),
                                accent = accent,
                                contentDescription = stringResource(R.string.chat_voice_message),
                                onRemove = { onRemoveImage(attachment.uri) }
                            )
                            attachment.isVideo -> PendingMediaTile(
                                icon = Icons.Rounded.Videocam,
                                label = com.yap.app.domain.AudioVideoPolicy.clockDuration(attachment.durationMs),
                                accent = accent,
                                contentDescription = stringResource(R.string.chat_video_message),
                                onRemove = { onRemoveImage(attachment.uri) }
                            )
                            else -> Box {
                                val pendingRequest = rememberSizedImageRequest(
                                    data = attachment.uri,
                                    width = 62.dp,
                                    height = 62.dp
                                )
                                AsyncImage(
                                    model = pendingRequest,
                                    contentDescription = stringResource(R.string.chat_pending_image),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(62.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .combinedClickable(
                                            onClick = { onRemoveImage(attachment.uri) },
                                            onLongClick = null
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.chat_remove_image),
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 录音中：输入框上方一行字，告诉用户「在录 / 松开发送」。
            if (state.recordingElapsedMs > 0L) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.chat_voice_recording,
                            com.yap.app.domain.AudioVideoPolicy.clockDuration(state.recordingElapsedMs)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                if (!state.isGenerating) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .combinedClickable(onClick = onPickImages, onLongClick = null),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = stringResource(R.string.chat_send_image),
                            tint = if (state.canSend) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // 表情是**独立**的一个按钮（规范要求与图片按钮分开）：
                    // 它打开的是收藏面板，点一下就发，不经过输入框。
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (stickersOpen) accent.copy(alpha = 0.16f) else Color.Transparent
                            )
                            .combinedClickable(onClick = onToggleStickers, onLongClick = null),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.EmojiEmotions,
                            contentDescription = stringResource(R.string.chat_send_sticker),
                            tint = when {
                                stickersOpen -> accent
                                state.canSend -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // 0.13：语音（按住录音、松开发送）与视频（系统选择器）。
                    // 录音中不换按钮：同一个位置、同一根手指，松手就是发送。
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.recordingElapsedMs > 0L) accent.copy(alpha = 0.18f)
                                else Color.Transparent
                            )
                            .pointerInput(state.canSend) {
                                detectTapGestures(
                                    onPress = {
                                        if (state.canSend) {
                                            onVoicePress()
                                            val released = tryAwaitRelease()
                                            if (released) onVoiceRelease() else onVoiceCancel()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.chat_send_voice),
                            tint = when {
                                state.recordingElapsedMs > 0L -> accent
                                state.canSend -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { if (state.canSend) onPickVideo() },
                                onLongClick = null
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = stringResource(R.string.chat_send_video),
                            tint = if (state.canSend) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(25.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (state.isGenerating) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Box(
                        Modifier
                            .heightIn(min = 46.dp, max = 132.dp)
                            .padding(horizontal = 17.dp, vertical = 12.dp)
                    ) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = when {
                                    state.config == null -> stringResource(R.string.chat_placeholder_no_config)
                                    state.images.isNotEmpty() -> stringResource(R.string.chat_placeholder_with_images)
                                    else -> stringResource(R.string.chat_placeholder)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = {
                                value = it
                                onDraftChange(it.text)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                            keyboardOptions = KeyboardOptions.Default,
                            maxLines = 6
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                val enabled = state.isGenerating || sendEnabled
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) accent
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        )
                        .combinedClickable(
                            onClick = {
                                if (state.isGenerating) onStop() else if (sendEnabled) onSend()
                            },
                            onLongClick = null
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isGenerating) {
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White)
                        )
                    } else {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 一次性引导：把他留在后台。关掉一次就不再出现。 */
@Composable
private fun BackgroundTipBanner(
    onDismiss: () -> Unit,
    onOpenSettings: (() -> Unit)?
) {
    val accent = LocalAccentColor.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_background_tip_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chat_background_tip_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.chat_background_tip_settings), style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.chat_background_tip_dismiss),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageViewerDialog(attachment: MessageAttachment, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            // 大图查看器：目标就是屏幕，不再放大到原图分辨率；ContentScale.Fit 负责留边。
            // 服务端图片与大图同一条解析路径（带 Authorization + Coil 磁盘缓存）。
            val (screenWidthPx, screenHeightPx) = rememberScreenSize()
            val viewerRequest = rememberMediaImageRequest(
                raw = attachment.uri,
                widthPx = screenWidthPx,
                heightPx = screenHeightPx,
                crossfade = false
            )
            AsyncImage(
                model = viewerRequest,
                contentDescription = stringResource(R.string.chat_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DropdownItem(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    persona: PersonaEntity,
    session: ChatSessionEntity,
    configName: String?,
    configModel: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMemory: () -> Unit,
    onEvolution: () -> Unit,
    onProactive: () -> Unit,
    onSwitchPersona: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 58.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(persona.name, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextPill(persona.category.ifBlank { stringResource(R.string.chat_persona_category_unknown) })
                        TextPill("v${persona.version}")
                    }
                }
            }
            if (persona.description.isNotBlank()) {
                Text(
                    persona.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            SettingLine(stringResource(R.string.chat_profile_api_label), configName ?: stringResource(R.string.chat_not_bound))
            SettingLine(
                stringResource(R.string.chat_profile_model_label),
                configModel?.ifBlank { stringResource(R.string.chat_not_set) } ?: stringResource(R.string.chat_not_set)
            )
            SettingLine(
                stringResource(R.string.chat_profile_injection_label),
                stringResource(R.string.chat_profile_injection_value)
            )
            SettingLine(
                stringResource(R.string.chat_profile_session_label),
                stringResource(R.string.chat_profile_session_value, session.id.take(8))
            )
            SettingLine(
                stringResource(R.string.chat_profile_prompt_label),
                pluralStringResource(
                    R.plurals.chat_profile_prompt_value,
                    persona.prompt.length,
                    persona.prompt.length
                )
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SheetActionRow(Icons.Outlined.PersonOutline, stringResource(R.string.chat_profile_edit), onClick = onEdit)
            SheetActionRow(Icons.Outlined.Memory, stringResource(R.string.chat_profile_memory), onClick = onMemory)
            SheetActionRow(Icons.Outlined.AutoAwesome, stringResource(R.string.chat_profile_evolution), onClick = onEvolution)
            SheetActionRow(Icons.Outlined.Alarm, stringResource(R.string.chat_profile_proactive), onClick = onProactive)
            SheetActionRow(Icons.Outlined.Sync, stringResource(R.string.chat_profile_switch), onClick = onSwitchPersona)
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigPickerSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAddConfig: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            BottomSheetTitle(
                stringResource(R.string.chat_config_picker_title),
                stringResource(R.string.chat_config_picker_subtitle)
            )
            Spacer(Modifier.height(10.dp))
            if (state.apiConfigs.isEmpty()) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text(stringResource(R.string.chat_config_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.chat_config_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onAddConfig, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.chat_config_add))
                    }
                }
            } else {
                state.apiConfigs.forEach { config ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onSelect(config.id) }, onLongClick = null)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(config.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                config.model,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (config.id == state.session?.apiConfigId) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                TextButton(
                    onClick = onAddConfig,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                ) { Text(stringResource(R.string.chat_config_manage)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaPickerSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            BottomSheetTitle(
                stringResource(R.string.chat_menu_switch_persona),
                stringResource(R.string.chat_persona_picker_subtitle)
            )
            Spacer(Modifier.height(8.dp))
            if (state.switchingPersona) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = LocalAccentColor.current)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.chat_persona_switching), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.personas.forEach { persona ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (!state.switchingPersona) onSelect(persona.id) },
                            onLongClick = null
                        )
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(persona.name, style = MaterialTheme.typography.titleSmall)
                        Text(persona.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (persona.id == state.session?.personaId) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** 这条消息里有表情时为 true：菜单多一项「添加到我的表情」。 */
    canSaveSticker: Boolean,
    onSaveSticker: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            BottomSheetTitle(
                if (message.role == MessageRole.USER) stringResource(R.string.chat_your_message)
                else stringResource(R.string.chat_reply)
            )
            Text(
                message.content.take(160).ifBlank {
                    ErrorText.stored(LocalContext.current, message.errorMessage)
                        ?: stringResource(R.string.chat_empty_message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(6.dp))
            SheetActionRow(Icons.Outlined.ContentCopy, stringResource(R.string.chat_copy), onClick = onCopy)
            if (canSaveSticker) {
                // 表情是「作品」不是文字：长按的第一件事是收进自己的收藏（规范 §发送与历史）。
                SheetActionRow(
                    Icons.Outlined.EmojiEmotions,
                    stringResource(R.string.chat_sticker_save),
                    onClick = onSaveSticker
                )
            }
            if (message.role == MessageRole.USER) {
                SheetActionRow(Icons.Outlined.Edit, stringResource(R.string.chat_edit_and_resend), onClick = onEdit)
            }
            // §10：不再提供「重新生成」——失败只提示，用户自己决定要不要再说一句。
            SheetActionRow(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.chat_delete_message),
                danger = true,
                onClick = onDelete
            )
        }
    }
}
