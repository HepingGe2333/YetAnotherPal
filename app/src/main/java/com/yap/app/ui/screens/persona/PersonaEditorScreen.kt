@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yap.app.ui.screens.persona

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Expand
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yap.app.ui.common.ErrorText
import com.yap.app.R
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.domain.PersonaActivity
import com.yap.app.domain.PersonaCard
import com.yap.app.domain.ModelProfile
import com.yap.app.domain.PromptLanguage
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaTrait
import com.yap.app.domain.ProactiveLevel
import com.yap.app.ui.components.AppHeader
import com.yap.app.ui.components.BackNavigation
import com.yap.app.ui.components.BottomSheetHandle
import com.yap.app.ui.components.BottomSheetTitle
import com.yap.app.ui.components.ConfirmDialog
import com.yap.app.ui.components.PersonaAvatar
import com.yap.app.ui.components.PrimaryButton
import com.yap.app.ui.components.SecondaryButton
import com.yap.app.ui.components.ModelProfilePicker
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.modelProfileHint
import com.yap.app.ui.components.SpeechStyleCard
import com.yap.app.ui.components.StyledOutlinedField
import com.yap.app.ui.components.WorldPresetCard
import com.yap.app.domain.WorldEditorState
import com.yap.app.ui.components.TextPill
import com.yap.app.ui.theme.LocalAccentColor
import com.yap.app.ui.theme.avatarPalette
import com.yap.app.util.MediaStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PersonaEditorScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenApiConfigs: () -> Unit,
    viewModel: PersonaEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPromptEditor by remember { mutableStateOf(false) }
    var showApiPicker by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // 系统照片选择器：只给「这一张」的读取权限，比 OpenDocument 更克制，也少一个权限面。
    // 选完立刻采样压缩进 files/avatars，库里只存 App 私有路径（avatarUri 列早就有了，不动 schema）。
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                // 审计 P2-12（0.11.1）：落盘与登记放进同一个不可取消区域——选完头像立刻
                // 返回时，写好的文件必须已经被登记进草稿集合（或交给 ViewModel 直接删掉），
                // 否则 discardDraftFiles/onCleared 按已登记集合删除，永远删不到它。
                withContext(NonCancellable) {
                    MediaStorage.persistAvatar(context, uri)
                        .onSuccess { viewModel.setAvatarUri(it) }
                        .onFailure {
                            snackbar.showSnackbar(
                                ErrorText.of(context, it, R.string.persona_editor_avatar_save_failed)
                            )
                        }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is PersonaEditorEvent.Saved -> onSaved(event.personaId)
                is PersonaEditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    fun requestBack() {
        if (state.isDirty) {
            showDiscardConfirm = true
        } else {
            // 审计 P2-2：选了头像又直接返回时，本次新建的那张不能留在 files/avatars 里。
            viewModel.discardDraftFiles()
            onBack()
        }
    }
    BackHandler(enabled = true) { requestBack() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    PrimaryButton(
                        text = if (state.isNew) stringResource(R.string.persona_editor_create)
                        else stringResource(R.string.persona_editor_save_changes),
                        onClick = viewModel::save,
                        loading = state.saving,
                        enabled = !state.loading
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppHeader(
                title = if (state.isNew) stringResource(R.string.persona_editor_title_new)
                else stringResource(R.string.persona_editor_title_edit),
                subtitle = if (state.isDefault) stringResource(R.string.persona_editor_subtitle_default) else null,
                navigation = { BackNavigation(::requestBack) }
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    AvatarSection(
                        state = state,
                        onPick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = { viewModel.setAvatarUri(null) },
                        onSeed = viewModel::setAvatarSeed
                    )
                    Spacer(Modifier.height(22.dp))
                    StyledOutlinedField(
                        value = state.form.name,
                        onValueChange = { value -> viewModel.update { it.copy(name = value.take(80)) } },
                        label = stringResource(R.string.persona_editor_name_label),
                        placeholder = stringResource(R.string.persona_editor_name_placeholder)
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.form.description,
                        onValueChange = { value -> viewModel.update { it.copy(description = value.take(600)) } },
                        label = stringResource(R.string.persona_editor_description_label),
                        placeholder = stringResource(R.string.persona_editor_description_placeholder),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                    Spacer(Modifier.height(20.dp))
                    CategorySection(
                        category = state.form.category,
                        onCategory = { category -> viewModel.update { it.copy(category = category.take(30)) } }
                    )
                    PersonaLanguageSection(
                        languageTag = state.form.languageTag,
                        onSelect = { tag -> viewModel.update { it.copy(languageTag = tag) } }
                    )
                    ContactProfileSection(
                        form = state.form,
                        onForm = { transform -> viewModel.update(transform) }
                    )
                    Spacer(Modifier.height(20.dp))
                    SectionLabel(stringResource(R.string.persona_editor_prompt_section), trailing = {
                        Text(
                            pluralStringResource(
                                R.plurals.persona_editor_prompt_chars,
                                state.form.prompt.length,
                                state.form.prompt.length
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    })
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            if (state.form.prompt.isBlank()) {
                                Text(
                                    stringResource(R.string.persona_editor_prompt_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    state.form.prompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 7,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        showPromptEditor = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(stringResource(R.string.persona_editor_prompt_focus_edit))
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    stringResource(R.string.persona_editor_prompt_verbatim),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.persona_editor_prompt_version_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    )

                    PersonaCardSection(
                        card = state.card,
                        onCard = viewModel::updateCard,
                        onAddTrait = viewModel::addTrait,
                        onTrait = viewModel::updateTrait,
                        onRemoveTrait = viewModel::removeTrait
                    )

                    // 0.13 人格进化：这几轮聊下来他自己觉得的变化，用户点头才写进人设。
                    // 空列表时这个 Composable 整块不出现（连标题都不留）。
                    PersonaEvolutionSection(
                        changes = state.pendingChanges,
                        busy = state.pendingBusy,
                        // 服务端模式也显示：接受会先写服务端再写本机缓存（0.13）。
                        serverMode = state.serverMode,
                        onAccept = viewModel::acceptPendingChange,
                        onReject = viewModel::rejectPendingChange
                    )

                    PersonaGuidanceSection(
                        form = state.form,
                        currentActivity = state.currentActivity,
                        worldFilling = state.worldFilling,
                        speechSummarizing = state.speechSummarizing,
                        speechLearning = state.speechLearning,
                        learnedSpeech = state.learnedSpeech,
                        onUpdate = viewModel::update,
                        onFillWorld = viewModel::fillWorldWithAi,
                        onSummarizeSpeech = viewModel::summarizeSpeech,
                        onClearSpeech = viewModel::clearSpeech,
                        onLearnSpeech = viewModel::learnSpeechFromChat,
                        onApplyLearnedSpeech = viewModel::applyLearnedSpeech,
                        onDismissLearnedSpeech = viewModel::dismissLearnedSpeech
                    )

                    // 0.12 提示词翻译（spec/persona-translation §4/§7）：显示缓存状态 + 手动重译。
                    // 新建人格还没有 id，没有缓存可言，整段不出现。
                    if (state.personaId != null) {
                        TranslationSection(
                            translating = state.translating,
                            translationLang = state.translationLang,
                            translationFresh = state.translationFresh,
                            languageLabel = viewModel::languageLabel,
                            onRetranslate = viewModel::retranslate
                        )
                    }

                    SectionLabel(stringResource(R.string.persona_editor_api_section))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showApiPicker = true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.persona_editor_api_bound_label),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        state.boundConfig?.let {
                                            stringResource(R.string.persona_editor_api_bound_value, it.name, it.model)
                                        } ?: stringResource(R.string.persona_editor_api_unbound_value),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(Icons.Outlined.ArrowOutward, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                StyledOutlinedField(
                                    value = state.form.modelOverride,
                                    onValueChange = { value -> viewModel.update { it.copy(modelOverride = value.take(160)) } },
                                    label = stringResource(R.string.persona_editor_model_override_label),
                                    placeholder = state.boundConfig?.model
                                        ?: stringResource(R.string.persona_editor_model_override_placeholder),
                                    supportingText = stringResource(R.string.persona_editor_model_override_support)
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )
                            // 0.15 模型优化（schema v20）：只改提示词里的工具规则段，不改人设与请求参数。
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    stringResource(R.string.model_profile_label),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    modelProfileHint(ModelProfile.fromStored(state.form.modelProfile)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                ModelProfilePicker(
                                    selected = state.form.modelProfile,
                                    onSelect = { value -> viewModel.update { it.copy(modelProfile = value) } }
                                )
                            }
                        }
                    }
                    if (state.configs.isEmpty()) {
                        TextButton(onClick = onOpenApiConfigs, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.persona_editor_add_api_config))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (showPromptEditor) {
        PromptFullScreenEditor(
            initialValue = state.form.prompt,
            savedValue = state.initialPrompt,
            onDismiss = { showPromptEditor = false },
            onApply = {
                viewModel.setPrompt(it)
                showPromptEditor = false
            },
            modifier = Modifier.zIndex(5f)
        )
    }
    }

    if (showApiPicker) {
        ApiPickerSheet(
            configs = state.configs,
            selectedId = state.form.defaultApiConfigId,
            onSelect = {
                viewModel.update { form -> form.copy(defaultApiConfigId = it) }
                showApiPicker = false
            },
            onDismiss = { showApiPicker = false }
        )
    }

    if (showDiscardConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.persona_editor_discard_title),
            message = stringResource(R.string.persona_editor_discard_message),
            confirmText = stringResource(R.string.persona_editor_discard_confirm),
            destructive = true,
            onConfirm = {
                showDiscardConfirm = false
                // 放弃这次编辑：本次新建的头像草稿一起回收（审计 P2-2）。
                viewModel.discardDraftFiles()
                onBack()
            },
            onDismiss = { showDiscardConfirm = false }
        )
    }
}

/**
 * 联系人细分字段（规格 §三 / §七）。
 *
 * 分节：性格（多选 + 自定义）/ 喜欢的事 / 行为方式 / 成长经历 / 个人设定 /
 * 主动程度 / 记性 / 抽记忆频率 / 会不会主动睡觉 / 睡觉时间段 / 睡眠唤醒阈值。
 *
 * 列表字段用 TextPill 提供常见候选，同时保留一行自定义输入——候选只是降低输入成本，
 * 不是限制：用户写「半夜给人改简历」这种具体习惯也应该能存进去。
 */
@Composable
private fun ContactProfileSection(
    form: PersonaForm,
    onForm: ((PersonaForm) -> PersonaForm) -> Unit
) {
    SectionLabel(stringResource(R.string.persona_editor_profile_section))
    val listSeparator = stringResource(R.string.persona_editor_list_separator)
    val traitPresets = listOf(
        stringResource(R.string.persona_trait_preset_slow_warm),
        stringResource(R.string.persona_trait_preset_prickly_soft),
        stringResource(R.string.persona_trait_preset_quiet),
        stringResource(R.string.persona_trait_preset_sensitive),
        stringResource(R.string.persona_trait_preset_jokes),
        stringResource(R.string.persona_trait_preset_stubborn),
        stringResource(R.string.persona_trait_preset_gentle),
        stringResource(R.string.persona_trait_preset_direct),
        stringResource(R.string.persona_trait_preset_clingy),
        stringResource(R.string.persona_trait_preset_independent)
    )
    val likePresets = listOf(
        stringResource(R.string.persona_likes_preset_night_walks),
        stringResource(R.string.persona_likes_preset_iced_americano),
        stringResource(R.string.persona_likes_preset_old_songs),
        stringResource(R.string.persona_likes_preset_movies),
        stringResource(R.string.persona_likes_preset_cooking),
        stringResource(R.string.persona_likes_preset_rainy_days),
        stringResource(R.string.persona_likes_preset_cats),
        stringResource(R.string.persona_likes_preset_travel)
    )
    Text(
        stringResource(R.string.persona_editor_profile_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            ProfileListField(
                label = stringResource(R.string.persona_editor_traits_label),
                placeholder = stringResource(R.string.persona_editor_traits_placeholder),
                value = form.traitsText,
                onChange = { value -> onForm { it.copy(traitsText = value.take(120)) } },
                presets = traitPresets,
                selected = form.traits,
                max = 6,
                onTogglePreset = { trait ->
                    val current = form.traits.toMutableList()
                    if (current.contains(trait)) current.remove(trait) else if (current.size < 6) current.add(trait)
                    onForm { it.copy(traitsText = PersonaForm.joinList(current, listSeparator)) }
                }
            )
            Spacer(Modifier.height(12.dp))
            ProfileListField(
                label = stringResource(R.string.persona_editor_likes_label),
                placeholder = stringResource(R.string.persona_editor_likes_placeholder),
                value = form.likesText,
                onChange = { value -> onForm { it.copy(likesText = value.take(160)) } },
                presets = likePresets,
                selected = form.likes,
                max = 8,
                onTogglePreset = { item ->
                    val current = form.likes.toMutableList()
                    if (current.contains(item)) current.remove(item) else if (current.size < 8) current.add(item)
                    onForm { it.copy(likesText = PersonaForm.joinList(current, listSeparator)) }
                }
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = form.behaviorsText,
                onValueChange = { value -> onForm { it.copy(behaviorsText = value.take(160)) } },
                label = stringResource(R.string.persona_editor_behaviors_label),
                placeholder = stringResource(R.string.persona_editor_behaviors_placeholder),
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = form.backstory,
                onValueChange = { value -> onForm { it.copy(backstory = value.take(600)) } },
                label = stringResource(R.string.persona_editor_backstory_label),
                placeholder = stringResource(R.string.persona_editor_backstory_placeholder),
                singleLine = false,
                minLines = 3,
                maxLines = 8
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = form.quirks,
                onValueChange = { value -> onForm { it.copy(quirks = value.take(300)) } },
                label = stringResource(R.string.persona_editor_quirks_label),
                placeholder = stringResource(R.string.persona_editor_quirks_placeholder),
                singleLine = false,
                minLines = 2,
                maxLines = 6
            )

            Spacer(Modifier.height(18.dp))
            ProfileSlider(
                title = stringResource(R.string.persona_editor_proactive_title),
                valueText = stringResource(
                    R.string.persona_editor_proactive_value,
                    form.proactiveLevel,
                    stringResource(proactiveLevelDescriptionRes(form.proactiveLevel))
                ),
                value = form.proactiveLevel.toFloat(),
                valueRange = 1f..5f,
                steps = 3,
                onChange = { raw -> onForm { it.copy(proactiveLevel = raw.roundToInt().coerceIn(1, 5)) } }
            )
            ProfileSlider(
                title = stringResource(R.string.persona_editor_memory_title),
                valueText = memoryRetentionText(form.memoryRetention),
                value = form.memoryRetention.toFloat(),
                valueRange = 1f..5f,
                steps = 3,
                onChange = { raw -> onForm { it.copy(memoryRetention = raw.roundToInt().coerceIn(1, 5)) } }
            )
            ProfileSlider(
                title = stringResource(R.string.persona_editor_extraction_title),
                valueText = pluralStringResource(
                    R.plurals.persona_editor_extraction_value,
                    form.extractionEvery,
                    form.extractionEvery
                ),
                value = form.extractionEvery.toFloat(),
                valueRange = 3f..12f,
                steps = 8,
                onChange = { raw -> onForm { it.copy(extractionEvery = raw.roundToInt().coerceIn(3, 12)) } }
            )

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.persona_editor_sleep_title),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (form.sleepEnabled) stringResource(R.string.persona_editor_sleep_on)
                        else stringResource(R.string.persona_editor_sleep_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = form.sleepEnabled,
                    onCheckedChange = { enabled -> onForm { it.copy(sleepEnabled = enabled) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = LocalAccentColor.current
                    )
                )
            }
            if (form.sleepEnabled) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyledOutlinedField(
                        value = form.sleepAt,
                        onValueChange = { value -> onForm { it.copy(sleepAt = value.take(5)) } },
                        label = stringResource(R.string.persona_editor_sleep_at_label),
                        placeholder = "23:30",
                        modifier = Modifier.weight(1f)
                    )
                    StyledOutlinedField(
                        value = form.wakeAt,
                        onValueChange = { value -> onForm { it.copy(wakeAt = value.take(5)) } },
                        label = stringResource(R.string.persona_editor_wake_at_label),
                        placeholder = "08:00",
                        modifier = Modifier.weight(1f)
                    )
                }
                ProfileSlider(
                    title = stringResource(R.string.persona_editor_wake_threshold_title),
                    valueText = pluralStringResource(
                        R.plurals.persona_editor_wake_threshold_value,
                        form.wakeThreshold,
                        form.wakeThreshold
                    ),
                    value = form.wakeThreshold.toFloat(),
                    valueRange = 1f..20f,
                    steps = 18,
                    onChange = { raw -> onForm { it.copy(wakeThreshold = raw.roundToInt().coerceIn(1, 20)) } }
                )
            }
        }
    }
}

@Composable
private fun ProfileListField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    presets: List<String>,
    selected: List<String>,
    max: Int,
    onTogglePreset: (String) -> Unit
) {
    StyledOutlinedField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = placeholder
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presets.forEach { preset ->
            TextPill(
                text = preset,
                selected = selected.contains(preset),
                onClick = {
                    if (selected.contains(preset) || selected.size < max) onTogglePreset(preset)
                }
            )
        }
    }
}

@Composable
private fun ProfileSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = LocalAccentColor.current,
                activeTrackColor = LocalAccentColor.current
            )
        )
    }
}

@Composable
private fun memoryRetentionText(level: Int): String = when (level.coerceIn(1, 5)) {
    1 -> stringResource(R.string.persona_editor_memory_level_1)
    2 -> stringResource(R.string.persona_editor_memory_level_2)
    3 -> stringResource(R.string.persona_editor_memory_level_3)
    4 -> stringResource(R.string.persona_editor_memory_level_4)
    else -> stringResource(R.string.persona_editor_memory_level_5)
}

/**
 * 分类候选。这些是**落库值**（和库里的旧数据对得上），所以常量保持中文不动，
 * 界面上显示的那一份走 [personaCategoryLabel] 映射。
 */
private val CATEGORY_SUGGESTIONS = listOf("日常聊天", "角色扮演", "学习", "编程", "创意写作", "工作")

/**
 * 已知的分类落库值映射成本地化文案；用户自己敲的分类原样显示。
 *
 * 中文值是 1.9 起的内置分类；英文值来自两处：`values-en/seed.xml`（英文首次安装时
 * 种下的分类）与英文设备的备份/人格包导入。两边都映射到同一批资源，
 * 这样英文设备上显示英文、中文设备导入后也不会露出英文。
 */
@Composable
internal fun personaCategoryLabel(value: String): String = when (value.trim()) {
    "日常聊天", "Everyday talk" -> stringResource(R.string.persona_category_daily_chat)
    "角色扮演", "Roleplay" -> stringResource(R.string.persona_category_roleplay)
    "学习", "Study" -> stringResource(R.string.persona_category_study)
    "编程", "Coding" -> stringResource(R.string.persona_category_coding)
    "创意写作", "Creative writing" -> stringResource(R.string.persona_category_writing)
    "工作", "Work" -> stringResource(R.string.persona_category_work)
    "未分类", "Uncategorized" -> stringResource(R.string.chat_persona_category_unknown)
    else -> value
}

@Composable
private fun AvatarSection(
    state: PersonaEditorUiState,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onSeed: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            PersonaAvatar(
                state.form.avatarUri,
                state.form.avatarSeed,
                state.form.name.ifBlank { stringResource(R.string.persona_editor_avatar_fallback_name) },
                size = 92.dp,
                ringColor = LocalAccentColor.current.copy(alpha = 0.45f)
            )
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onPick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.PhotoCamera,
                    contentDescription = stringResource(R.string.persona_editor_avatar_change_cd),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    if (state.form.avatarUri == null) stringResource(R.string.persona_editor_avatar_pick)
                    else stringResource(R.string.persona_editor_avatar_replace)
                )
            }
            if (state.form.avatarUri != null) {
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    // 「恢复默认」而不是「移除」：默认不是空白，是这个人的色块 + 首字。
                    Text(
                        stringResource(R.string.persona_editor_avatar_reset),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.form.avatarUri == null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (0..12).forEach { seed ->
                    val palette = avatarPalette(seed)
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(palette.first, palette.second)))
                            .then(
                                if (state.form.avatarSeed == seed) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                else Modifier
                            )
                            .clickable { onSeed(seed) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.form.avatarSeed == seed) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 0.15：人格自己的提示词语言。
 *
 * 与界面语言无关：这里选什么，他就用什么思考和说话；换 App 语言不会改变聊天对象的语言。
 * 只列共享契约里有的语言（[PromptLanguage]），选「跟随默认」= 中文。
 */
@Composable
private fun PersonaLanguageSection(languageTag: String, onSelect: (String) -> Unit) {
    SectionLabel(stringResource(R.string.persona_editor_language_section))
    Text(
        stringResource(R.string.persona_editor_language_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextPill(
            text = stringResource(R.string.persona_editor_language_default),
            selected = languageTag.isBlank(),
            onClick = { onSelect("") }
        )
        PromptLanguage.entries.forEach { language ->
            TextPill(
                text = language.nativeName,
                selected = PromptLanguage.parse(languageTag) == language,
                onClick = { onSelect(language.code) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun CategorySection(category: String, onCategory: (String) -> Unit) {
    SectionLabel(stringResource(R.string.persona_editor_category_section))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CATEGORY_SUGGESTIONS.forEach { item ->
            TextPill(personaCategoryLabel(item), selected = category == item, onClick = { onCategory(item) })
        }
    }
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = category,
        onValueChange = onCategory,
        label = stringResource(R.string.persona_editor_category_custom_label),
        singleLine = true
    )
}

/**
 * 世界与话风：可以覆盖从预设带进来的那一份，也可以在这里现填。
 *
 * 「当前活动」只读：那是模型用 `set_activity` 自己声明的，用户改不了（规范 §4），
 * 这里只让用户看见「他正在做什么」。
 */
@Composable
private fun PersonaGuidanceSection(
    form: PersonaForm,
    currentActivity: PersonaActivity?,
    worldFilling: Boolean,
    speechSummarizing: Boolean,
    speechLearning: Boolean,
    learnedSpeech: LearnedSpeechUi?,
    onUpdate: ((PersonaForm) -> PersonaForm) -> Unit,
    onFillWorld: () -> Unit,
    onSummarizeSpeech: () -> Unit,
    onClearSpeech: () -> Unit,
    onLearnSpeech: () -> Unit,
    onApplyLearnedSpeech: () -> Unit,
    onDismissLearnedSpeech: () -> Unit
) {
    SectionLabel(stringResource(R.string.persona_editor_guidance_section))
    Text(
        stringResource(R.string.persona_editor_guidance_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(8.dp))
    WorldPresetCard(
        state = form.world,
        onChange = { value -> onUpdate { it.copy(world = value) } },
        onAiFill = onFillWorld,
        aiFilling = worldFilling,
        aiHint = null,
        issues = form.world.issues(),
        onUseExample = { onUpdate { it.copy(world = WorldEditorState.example()) } }
    )
    Spacer(Modifier.height(10.dp))
    SpeechStyleCard(
        sampleText = form.speechSample,
        tone = form.speechTone,
        phrases = form.speechPhrases,
        habitsText = form.speechHabits,
        avoidText = form.speechAvoid,
        source = form.speechSource.takeIf { it.isNotBlank() },
        summarizing = speechSummarizing,
        onSampleText = { value -> onUpdate { it.copy(speechSample = value) } },
        onSummarize = onSummarizeSpeech,
        onClear = onClearSpeech,
        onTone = { value -> onUpdate { it.copy(speechTone = value) } },
        onPhrases = { value -> onUpdate { it.copy(speechPhrases = value) } },
        onHabitsText = { value -> onUpdate { it.copy(speechHabits = value) } },
        onAvoidText = { value -> onUpdate { it.copy(speechAvoid = value) } }
    )
    // 0.16：不用去别的 App 复制聊天记录了——他和你的这段对话本身就是最像他的语料。
    SpeechLearnSection(
        learning = speechLearning,
        learned = learnedSpeech,
        onLearn = onLearnSpeech,
        onApply = onApplyLearnedSpeech,
        onDismiss = onDismissLearnedSpeech
    )
    Spacer(Modifier.height(14.dp))
    SectionLabel(stringResource(R.string.persona_editor_activity_section))
    Text(
        text = activityLine(currentActivity),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    if (currentActivity?.isActive(System.currentTimeMillis()) == true) {
        Text(
            text = stringResource(
                when (currentActivity.availability) {
                    com.yap.app.domain.ActivityAvailability.BUSY -> R.string.persona_editor_activity_busy
                    com.yap.app.domain.ActivityAvailability.LIMITED -> R.string.persona_editor_activity_limited
                    com.yap.app.domain.ActivityAvailability.FREE -> R.string.persona_editor_activity_free
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )
    }
}

/**
 * 「从最近聊天学习话风」：一个按钮 + 一块预览。
 *
 * 产品上的一句话：他和你聊过的话本身就是最像他的语料，不该让用户再去别的 App 里复制粘贴。
 * 所以这里只有三步——点一下（读最近聊天、一次总结）、看一眼（tone / 常用语 / 习惯 / 不要的表达，
 * 并标明是模型总结还是本地兜底）、决定（应用才写库；取消等于什么都没发生）。
 *
 * 隐私文案说清楚：样本不会留下记录，只有这一版结果会存进他的人设。
 */
@Composable
private fun SpeechLearnSection(
    learning: Boolean,
    learned: LearnedSpeechUi?,
    onLearn: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(
            text = if (learning) {
                stringResource(R.string.guidance_speech_learning)
            } else {
                stringResource(R.string.guidance_speech_learn_from_chat)
            },
            onClick = onLearn,
            enabled = !learning,
            loading = learning,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.guidance_speech_learn_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    if (learned == null) return
    Spacer(Modifier.height(10.dp))
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.guidance_speech_learn_preview_title),
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(6.dp))
            // 来源必须写清楚：模型总结 / 本地粗略总结（本地兜底不是「模型看过」）。
            Text(
                if (learned.isHeuristic) {
                    stringResource(R.string.guidance_speech_source_heuristic)
                } else {
                    stringResource(R.string.guidance_speech_source_llm)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (learned.tone.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.guidance_speech_tone_label) + "：" + learned.tone,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (learned.phrases.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.guidance_speech_phrases_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    learned.phrases.joinToString("、") { "「$it」" },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            listOf(
                stringResource(R.string.guidance_speech_habits_label) to learned.habits,
                stringResource(R.string.guidance_speech_avoid_label) to learned.avoid
            ).forEach { (label, lines) ->
                if (lines.isEmpty()) return@forEach
                Spacer(Modifier.height(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(lines.joinToString("；"), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.guidance_speech_learn_cancel),
                    onClick = onDismiss,
                    enabled = !learning,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.guidance_speech_learn_apply),
                    onClick = onApply,
                    enabled = !learning,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun activityLine(activity: PersonaActivity?): String {
    if (activity == null || !activity.isActive(System.currentTimeMillis())) {
        return stringResource(R.string.persona_editor_activity_none)
    }
    return stringResource(R.string.persona_editor_activity_line, activity.activity, activity.formatUntil())
}

// ---------------------------------------------------------------------------
// 结构化人设卡编辑器
// ---------------------------------------------------------------------------

/** 逗号（中英文）、顿号、换行都可作为列表分隔符。 */
private fun parseCardList(raw: String): List<String> =
    raw.split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun strengthText(strength: Float): String {
    val tenths = (strength.coerceIn(0f, 1f) * 10f).roundToInt()
    return if (tenths >= 10) "1.0" else "0.$tenths"
}

@Composable
private fun CardGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 2.dp)
    )
}

/**
 * 逗号分隔的字符串列表输入。
 *
 * 显示用本地原文，写入状态时才拆成 List，
 * 这样输入到「a，b，」这种半截状态时不会把刚敲的逗号吃掉。
 */
@Composable
private fun CommaListField(
    values: List<String>,
    onValues: (List<String>) -> Unit,
    label: String,
    placeholder: String
) {
    val separator = stringResource(R.string.persona_editor_list_separator)
    var text by rememberSaveable { mutableStateOf(values.joinToString(separator)) }
    StyledOutlinedField(
        value = text,
        onValueChange = { raw ->
            text = raw
            onValues(parseCardList(raw))
        },
        label = label,
        placeholder = placeholder
    )
}

@Composable
private fun PersonaCardSection(
    card: PersonaCard,
    onCard: ((PersonaCard) -> PersonaCard) -> Unit,
    onAddTrait: () -> Unit,
    onTrait: (Int, (PersonaTrait) -> PersonaTrait) -> Unit,
    onRemoveTrait: (Int) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(!card.isEmpty()) }
    SectionLabel(
        stringResource(R.string.persona_editor_card_section),
        trailing = {
            Text(
                if (card.isEmpty()) stringResource(R.string.persona_editor_card_status_optional)
                else stringResource(R.string.persona_editor_card_status_filled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    Text(
        stringResource(R.string.persona_editor_card_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.persona_editor_card_structured_title),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.persona_editor_card_structured_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.persona_editor_card_collapse_cd)
                    else stringResource(R.string.persona_editor_card_expand_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp)
                )
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)) {
                    // ---------------------------------------------------- 身份
                    CardGroupLabel(stringResource(R.string.persona_editor_group_identity))
                    StyledOutlinedField(
                        value = card.identity.name,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(name = value.take(40))) }
                        },
                        label = stringResource(R.string.persona_editor_name_label),
                        placeholder = stringResource(R.string.persona_editor_card_name_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.identity.age,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(age = value.take(40))) }
                        },
                        label = stringResource(R.string.persona_editor_age_label),
                        placeholder = stringResource(R.string.persona_editor_age_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.identity.role,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(role = value.take(80))) }
                        },
                        label = stringResource(R.string.persona_editor_role_label),
                        placeholder = stringResource(R.string.persona_editor_role_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.identity.relation,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(relation = value.take(40))) }
                        },
                        label = stringResource(R.string.persona_editor_relation_label),
                        placeholder = stringResource(R.string.persona_editor_relation_placeholder)
                    )

                    // ---------------------------------------------------- 声音
                    CardGroupLabel(stringResource(R.string.persona_editor_group_voice))
                    StyledOutlinedField(
                        value = card.voice.pace,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(pace = value.take(240))) }
                        },
                        label = stringResource(R.string.persona_editor_pace_label),
                        placeholder = stringResource(R.string.persona_editor_pace_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.voice.sentence,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(sentence = value.take(240))) }
                        },
                        label = stringResource(R.string.persona_editor_sentence_label),
                        placeholder = stringResource(R.string.persona_editor_sentence_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    CommaListField(
                        values = card.voice.catchphrases,
                        onValues = { list ->
                            onCard { it.copy(voice = it.voice.copy(catchphrases = list)) }
                        },
                        label = stringResource(R.string.persona_editor_catchphrases_label),
                        placeholder = stringResource(R.string.persona_editor_catchphrases_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.voice.address,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(address = value.take(40))) }
                        },
                        label = stringResource(R.string.persona_editor_address_label),
                        placeholder = stringResource(R.string.persona_editor_address_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.voice.quirks,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(quirks = value.take(240))) }
                        },
                        label = stringResource(R.string.persona_editor_voice_quirks_label),
                        placeholder = stringResource(R.string.persona_editor_voice_quirks_placeholder)
                    )

                    // ---------------------------------------------------- 性格
                    CardGroupLabel(stringResource(R.string.persona_editor_group_traits))
                    if (card.traits.isEmpty()) {
                        Text(
                            stringResource(R.string.persona_editor_traits_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                        )
                    }
                    card.traits.forEachIndexed { index, trait ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        TraitRow(
                            trait = trait,
                            onChange = { updated -> onTrait(index) { updated } },
                            onRemove = { onRemoveTrait(index) }
                        )
                    }
                    TextButton(
                        onClick = onAddTrait,
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        Text(stringResource(R.string.persona_editor_trait_add))
                    }

                    // ---------------------------------------------------- 价值观 / 边界
                    CardGroupLabel(stringResource(R.string.persona_editor_group_values))
                    CommaListField(
                        values = card.values,
                        onValues = { list -> onCard { it.copy(values = list) } },
                        label = stringResource(R.string.persona_editor_values_label),
                        placeholder = stringResource(R.string.persona_editor_values_placeholder)
                    )
                    CardGroupLabel(stringResource(R.string.persona_editor_group_boundaries))
                    CommaListField(
                        values = card.boundaries,
                        onValues = { list -> onCard { it.copy(boundaries = list) } },
                        label = stringResource(R.string.persona_editor_boundaries_label),
                        placeholder = stringResource(R.string.persona_editor_boundaries_placeholder)
                    )

                    // ---------------------------------------------------- 生活
                    CardGroupLabel(stringResource(R.string.persona_editor_group_life))
                    StyledOutlinedField(
                        value = card.life.schedule,
                        onValueChange = { value ->
                            onCard { it.copy(life = it.life.copy(schedule = value.take(240))) }
                        },
                        label = stringResource(R.string.persona_editor_schedule_label),
                        placeholder = stringResource(R.string.persona_editor_schedule_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.life.doing,
                        onValueChange = { value ->
                            onCard { it.copy(life = it.life.copy(doing = value.take(240))) }
                        },
                        label = stringResource(R.string.persona_editor_doing_label),
                        placeholder = stringResource(R.string.persona_editor_doing_placeholder)
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.life.caresAbout,
                        onValueChange = { value ->
                            onCard { it.copy(life = it.life.copy(caresAbout = value.take(240))) }
                        },
                        label = stringResource(R.string.persona_editor_cares_label),
                        placeholder = stringResource(R.string.persona_editor_cares_placeholder)
                    )

                    // ---------------------------------------------------- 你们之间
                    CardGroupLabel(stringResource(R.string.persona_editor_group_bond))
                    StyledOutlinedField(
                        value = card.bond,
                        onValueChange = { value -> onCard { it.copy(bond = value.take(2000)) } },
                        label = stringResource(R.string.persona_editor_bond_label),
                        placeholder = stringResource(R.string.persona_editor_bond_placeholder),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        supportingText = stringResource(R.string.persona_editor_bond_support)
                    )
                }
            }
        }
    }
}

@Composable
private fun TraitRow(
    trait: PersonaTrait,
    onChange: (PersonaTrait) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StyledOutlinedField(
                value = trait.name,
                onValueChange = { value -> onChange(trait.copy(name = value.take(40))) },
                label = stringResource(R.string.persona_editor_trait_name_label),
                placeholder = stringResource(R.string.persona_editor_trait_name_placeholder),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.persona_editor_trait_delete_cd),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.persona_editor_trait_strength),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = trait.strength,
                onValueChange = { value -> onChange(trait.copy(strength = value)) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(
                strengthText(trait.strength),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApiPickerSheet(
    configs: List<ApiConfigEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            BottomSheetTitle(
                stringResource(R.string.persona_editor_api_sheet_title),
                stringResource(R.string.persona_editor_api_sheet_subtitle)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(null) }
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.persona_editor_api_none), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                if (selectedId == null) Icon(Icons.Outlined.Check, contentDescription = null, tint = LocalAccentColor.current)
            }
            configs.forEach { config ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(config.id) }
                        .padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            config.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedId == config.id) Icon(Icons.Outlined.Check, contentDescription = null, tint = LocalAccentColor.current)
                }
            }
        }
    }
}

@Composable
private fun PromptFullScreenEditor(
    initialValue: String,
    savedValue: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialValue, TextRange(0)))
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(1600)
            notice = null
        }
    }
    BackHandler(enabled = true) { onDismiss() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.persona_editor_cancel))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.persona_editor_prompt_screen_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.persona_editor_prompt_screen_meta,
                                value.text.length,
                                value.text.length
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onApply(value.text) }) {
                        Text(stringResource(R.string.persona_editor_done))
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value.text))
                        notice = context.getString(R.string.persona_editor_copied_notice)
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.persona_editor_copy_all))
                    }
                    TextButton(onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) {
                            val combined = if (value.text.isBlank()) pasted else value.text + "\n" + pasted
                            value = TextFieldValue(combined, TextRange(combined.length))
                            notice = context.getString(R.string.persona_editor_pasted_notice)
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.persona_editor_paste))
                    }
                    TextButton(onClick = {
                        if (savedValue.isNotBlank()) {
                            value = TextFieldValue(savedValue, TextRange(savedValue.length))
                            notice = context.getString(R.string.persona_editor_restored_notice)
                        }
                    }) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.persona_editor_restore_last))
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            stringResource(R.string.persona_editor_prompt_editor_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            notice = null
                        },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 25.sp
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current)
                    )
                }
                if (notice != null) {
                    Text(
                        notice!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAccentColor.current,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.persona_editor_cancel),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Surface(
                        onClick = { onApply(value.text) },
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.persona_editor_save_draft),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
    }
}

/**
 * 「提示词语言」：显示当前缓存的翻译状态，并提供「重新翻译」。
 *
 * 文案刻意只描述事实：有没有缓存、缓存是不是还新鲜、上次失败没有。
 * 不假装翻译成功，也不在没有缓存时催用户点按钮（打开聊天就会自动译）。
 */
@Composable
private fun TranslationSection(
    translating: Boolean,
    translationLang: String?,
    translationFresh: Boolean,
    languageLabel: (String) -> String,
    onRetranslate: () -> Unit
) {
    SectionLabel(stringResource(R.string.persona_editor_translation_section))
    Text(
        stringResource(R.string.persona_editor_translation_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = when {
            translationLang == null -> stringResource(R.string.persona_editor_translation_none)
            translationFresh -> stringResource(
                R.string.persona_editor_translation_done,
                languageLabel(translationLang)
            )
            // 有缓存但源字段/语言变了：下次打开聊天会自动重译。
            else -> stringResource(R.string.persona_editor_translation_none)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = if (translating) {
            stringResource(R.string.persona_editor_translating)
        } else {
            stringResource(R.string.persona_editor_retranslate)
        },
        onClick = onRetranslate,
        enabled = !translating,
        loading = translating
    )
}

// ---------------------------------------------------------------------------
// 0.13 人格进化：「他最近的变化」
// ---------------------------------------------------------------------------

/** add / remove 的前缀符号。用文本而不是图标：两个符号在中文与英文字体里都能对齐。 */
private const val EVOLUTION_ADD_SYMBOL = "+"
private const val EVOLUTION_REMOVE_SYMBOL = "\u2212"

/**
 * 「他最近的变化」：后台评估产出的待审变化，用户点头才写进人设。
 *
 * 没有待审变化时整块不出现——空标题比没有这块更让人困惑。
 * 每条变化右边两个入口就够，不加确认弹窗（一句话 + 一个入口）。
 */
@Composable
private fun PersonaEvolutionSection(
    changes: List<PendingChangeUi>,
    busy: Boolean,
    serverMode: Boolean,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit
) {
    if (changes.isEmpty()) return
    SectionLabel(stringResource(R.string.persona_evolution_section_title))
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            changes.forEachIndexed { index, change ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }
                PersonaEvolutionRow(
                    change = change,
                    busy = busy,
                    onAccept = onAccept,
                    onReject = onReject
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (serverMode) R.string.persona_evolution_section_note_server
                    else R.string.persona_evolution_section_note
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 一条变化：`+ 性格 嘴硬心软`，下面一行小字是模型给的原因（没有就不占行）。 */
@Composable
private fun PersonaEvolutionRow(
    change: PendingChangeUi,
    busy: Boolean,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val adding = change.op == PersonaEvolution.Op.ADD
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = if (adding) EVOLUTION_ADD_SYMBOL else EVOLUTION_REMOVE_SYMBOL,
            style = MaterialTheme.typography.titleMedium,
            color = if (adding) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    evolutionFieldLabel(change.field),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    change.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (change.reason.isNotBlank()) {
                Text(
                    change.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Row {
            TextButton(
                onClick = { onAccept(change.patchId) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Text(stringResource(R.string.persona_evolution_accept))
            }
            TextButton(
                onClick = { onReject(change.patchId) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Text(
                    stringResource(R.string.persona_evolution_reject),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 字段名走资源，别把人设页的四个分组名在 Kotlin 里再抄一遍中文。 */
@Composable
private fun evolutionFieldLabel(field: PersonaEvolution.Field): String = when (field) {
    PersonaEvolution.Field.TRAITS -> stringResource(R.string.persona_evolution_field_traits)
    PersonaEvolution.Field.LIKES -> stringResource(R.string.persona_evolution_field_likes)
    PersonaEvolution.Field.BEHAVIORS -> stringResource(R.string.persona_evolution_field_behaviors)
    PersonaEvolution.Field.QUIRKS -> stringResource(R.string.persona_evolution_field_quirks)
}
