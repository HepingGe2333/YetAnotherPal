package com.yap.app.ui.screens.persona

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yap.app.domain.PersonaRevisionReason
import com.yap.app.ui.common.ErrorText
import com.yap.app.ui.common.WorldEditorErrors
import com.yap.app.R
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.PersonaCardRepository
import com.yap.app.data.repo.PersonaEvolutionRepository
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.StyleDistillationRepository
import com.yap.app.data.repo.PersonaTranslationCoordinator
import com.yap.app.data.repo.PersonaTranslationOutcome
import com.yap.app.data.ai.SpeechStyleResult
import com.yap.app.data.ai.SpeechStyleSource
import com.yap.app.data.ai.SpeechStyleSummarizer
import com.yap.app.data.ai.WorldPresetExpander
import com.yap.app.domain.AvatarDraftFiles
import com.yap.app.domain.PersonaActivity
import com.yap.app.domain.PersonaActivityCodec
import com.yap.app.domain.PersonaCard
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.ProactiveLevel
import com.yap.app.domain.SleepPolicy
import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.SpeechStyleCodec
import com.yap.app.domain.WorldEditorState
import com.yap.app.domain.WorldPresetCodec
import com.yap.app.domain.PersonaDraft
import com.yap.app.domain.PersonaDraftDefaults
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaPromptLocalization
import com.yap.app.domain.PersonaTranslationCodec
import com.yap.app.domain.PersonaTranslationSource
import com.yap.app.domain.PersonaTrait
import com.yap.app.util.MediaStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * 联系人编辑表单。
 *
 * 1.9 把它从「名字 + 大段提示词」扩展成可一眼看懂的分节：
 * 性格 / 喜欢 / 习惯 / 过去 / 设定 + 主动程度 / 记性 / 抽记忆频率 / 睡觉 / 唤醒阈值。
 * 列表字段用「逗号分隔」的字符串编辑，落库前再拆成数组——这是界面上最不容易写错的形式。
 */
data class PersonaForm(
    val name: String = "",
    val description: String = "",
    val avatarUri: String? = null,
    val avatarSeed: Int = 0,
    val prompt: String = "",
    val category: String = "日常聊天",
    val defaultApiConfigId: String? = null,
    val modelOverride: String = "",
    /**
     * 0.15 模型优化开关（schema v20，[com.yap.app.domain.ModelProfile]）：
     * `AUTO` / `DEEPSEEK` / `GLM`，落库是同一串枚举名。
     */
    val modelProfile: String = com.yap.app.domain.ModelProfile.DEFAULT_STORED,
    val traitsText: String = "",
    val likesText: String = "",
    val behaviorsText: String = "",
    val backstory: String = "",
    val quirks: String = "",
    val proactiveLevel: Int = ProactiveLevel.DEFAULT,
    val memoryRetention: Int = 3,
    val extractionEvery: Int = 6,
    /**
     * 新建人格默认不睡（0.12 续 7）。
     *
     * 打开它他才会有作息：没设时段就按 23:30–08:00 睡，睡眠中只标已读、连着发满
     * [wakeThreshold] 条才醒。默认打开会让「刚装好 App 的第一晚」变成「发什么都没反应」，
     * 所以睡眠改成用户主动打开的功能。已有的人格仍然按库里存的值回显。
     */
    val sleepEnabled: Boolean = false,
    /** 睡觉 / 醒来的 HH:mm；留空用默认 23:30–08:00（只在 [sleepEnabled] 打开时有意义）。 */
    val sleepAt: String = "",
    val wakeAt: String = "",
    /** 睡眠中被叫醒需要的连续消息数（1–20）；默认 3，夜里发几条就能把他叫起来。 */
    val wakeThreshold: Int = 3,
    // ---- 0.12 人设引导：世界 / 话风 / 当前活动（v13；世界是可视化编辑器状态） ----
    val world: WorldEditorState = WorldEditorState(),
    val speechTone: String = "",
    val speechPhrases: List<String> = emptyList(),
    val speechHabits: String = "",
    val speechAvoid: String = "",
    val speechSample: String = "",
    val speechSource: String = "",
    /**
     * 0.15：这个人的提示词语言（`zh` / `en` …）；空 = 产品默认中文。
     *
     * 与界面语言无关：用户换成英文界面，中文人格仍然说中文。
     */
    val languageTag: String = ""
) {
    val traits: List<String> get() = parseList(traitsText)
    val likes: List<String> get() = parseList(likesText)
    val behaviors: List<String> get() = parseList(behaviorsText)

    companion object {
        /** 中英文逗号、顿号、换行都当分隔符；去空、去重、保序。 */
        fun parseList(raw: String): List<String> =
            raw.split(',', '，', '、', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** [separator] 是界面上回显用的分隔符（中英文不同），解析时两种都认。 */
        fun joinList(items: List<String>, separator: String): String = items.joinToString(separator)
    }
}

/**
 * 「他最近的变化」里的一条：把 [PersonaEvolution.Change] 摊成界面能直接排版的结构。
 *
 * 文案（字段名、加减号、按钮）仍由界面取资源，这里只带数据。
 * 同一条待审补丁最多含 3 条变化，所以 [patchId] 可能重复出现——
 * 接受 / 拒绝的粒度是整条补丁，点其中任意一行都会把整条提议处理掉。
 */
data class PendingChangeUi(
    val patchId: String,
    val field: PersonaEvolution.Field,
    val op: PersonaEvolution.Op,
    val value: String,
    val reason: String
)

/**
 * 「从最近聊天学习话风」的预览：只是**待确认**的一版，用户点「应用」之前不写库。
 *
 * [source] 是 `llm` / `heuristic`（来自 [SpeechStyleResult]），界面据此标一句
 * 「模型总结」或「本地粗略总结」——不能让用户以为本地规则也是模型看过的。
 */
data class LearnedSpeechUi(
    val tone: String,
    val phrases: List<String>,
    val habits: List<String>,
    val avoid: List<String>,
    val source: String,
    /** 这次一共看了多少条消息：只在界面上说「看了最近 N 条」，不是落库字段。 */
    val messageCount: Int
) {
    fun toStyle(): SpeechStyle = SpeechStyle(
        tone = tone,
        phrases = phrases,
        habits = habits,
        avoid = avoid
    )

    val isEmpty: Boolean get() = toStyle().isEmpty()
    val isHeuristic: Boolean get() = source == SpeechStyleSource.HEURISTIC
}

data class PersonaEditorUiState(
    val personaId: String? = null,
    val form: PersonaForm = PersonaForm(),
    val initialPrompt: String = "",
    val initialForm: PersonaForm = PersonaForm(),
    /** 结构化人设卡（可在提示词下方展开编辑）。 */
    val card: PersonaCard = PersonaCard(),
    val initialCard: PersonaCard = PersonaCard(),
    val configs: List<ApiConfigEntity> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val isNew: Boolean = true,
    val isDefault: Boolean = false,
    /** 他此刻在做什么（只读展示；到点自动按「没在忙什么」显示）。 */
    val currentActivity: PersonaActivity? = null,
    val worldFilling: Boolean = false,
    val speechSummarizing: Boolean = false,
    /** 「从最近聊天学习话风」正在读聊天记录 / 等模型回话。 */
    val speechLearning: Boolean = false,
    /** 学到的预览：非 null 时编辑页弹一块「看一下再决定」的卡片，点「取消」不清库、只清它。 */
    val learnedSpeech: LearnedSpeechUi? = null,
    // ---- 0.12 提示词翻译（spec/persona-translation） -------------------------
    /** 已缓存的译文语言；null = 还没有缓存。 */
    val translationLang: String? = null,
    /** 缓存是否仍然有效（语言与源字段哈希都对得上）。 */
    val translationFresh: Boolean = false,
    val translating: Boolean = false,
    // ---- 0.13 人格进化：人设页「他最近的变化」（待审，用户点头才生效） ----------
    /** 当前人格待审的进化变化，按补丁创建时间倒序；空列表时整块不显示。 */
    val pendingChanges: List<PendingChangeUi> = emptyList(),
    /** 正在接受 / 拒绝某一条：期间所有按钮禁用，避免连点打两次写库。 */
    val pendingBusy: Boolean = false,
    /**
     * 当前是不是服务端模式（0.13）：只影响文案与「接受」实际走的写路径。
     * 本地模式写本机；服务端模式先 `POST /v1/personas`，成功后再写本机缓存。
     */
    val serverMode: Boolean = false
) {
    val isDirty: Boolean get() = form != initialForm || card != initialCard
    val boundConfig: ApiConfigEntity? get() = configs.firstOrNull { it.id == form.defaultApiConfigId }
}

sealed interface PersonaEditorEvent {
    data class Saved(val personaId: String) : PersonaEditorEvent
    data class Message(val text: String) : PersonaEditorEvent
}

@HiltViewModel
class PersonaEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personaRepository: PersonaRepository,
    private val personaCardRepository: PersonaCardRepository,
    private val speechStyleSummarizer: SpeechStyleSummarizer,
    /** 0.16 「从最近聊天学习话风」：只读最近聊天 + 一次总结，应用时再写库。 */
    private val styleDistillationRepository: StyleDistillationRepository,
    private val worldPresetExpander: WorldPresetExpander,
    private val translationCoordinator: PersonaTranslationCoordinator,
    apiConfigRepository: ApiConfigRepository,
    @ApplicationContext private val context: Context,
    private val personaEvolutionRepository: PersonaEvolutionRepository
) : ViewModel() {
    private val personaId: String? = savedStateHandle.get<String>("personaId")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(
        PersonaEditorUiState(
            personaId = personaId,
            isNew = personaId == null,
            serverMode = personaEvolutionRepository.isServerMode()
        )
    )
    val state: StateFlow<PersonaEditorUiState> = _state.asStateFlow()
    private val events = Channel<PersonaEditorEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    /**
     * 「他最近的变化」的订阅。
     *
     * 换人格 / 新建人格时要取消上一次的收集，否则旧人格的待审列表会继续往新页面里推。
     * 本页的 ViewModel 目前一人格一个（personaId 来自导航参数），这里仍然按「可切换」写，
     * 免得以后复用同一个 ViewModel 时残留。
     */
    private var pendingJob: Job? = null

    init {
        viewModelScope.launch {
            observePending(personaId)
            if (personaId != null) {
                val persona = personaRepository.getById(personaId)
                savedAvatarUri = persona?.avatarUri
                if (persona == null) {
                    events.send(PersonaEditorEvent.Message(context.getString(R.string.persona_editor_persona_missing)))
                    _state.update { it.copy(loading = false) }
                } else {
                    val world = WorldPresetCodec.decode(persona.worldJson)
                    val speech = SpeechStyleCodec.decode(persona.speechStyleJson)
                    val form = PersonaForm(
                        name = persona.name,
                        description = persona.description,
                        avatarUri = persona.avatarUri,
                        avatarSeed = persona.avatarSeed,
                        prompt = persona.prompt,
                        category = persona.category,
                        defaultApiConfigId = persona.defaultApiConfigId,
                        modelOverride = persona.modelOverride.orEmpty(),
                        modelProfile = com.yap.app.domain.ModelProfile.fromStored(persona.modelProfile).stored,
                        traitsText = PersonaForm.joinList(
                            PersonaProfile.decodeList(persona.traits),
                            context.getString(R.string.persona_editor_list_separator)
                        ),
                        likesText = PersonaForm.joinList(
                            PersonaProfile.decodeList(persona.likes),
                            context.getString(R.string.persona_editor_list_separator)
                        ),
                        behaviorsText = PersonaForm.joinList(
                            PersonaProfile.decodeList(persona.behaviors),
                            context.getString(R.string.persona_editor_list_separator)
                        ),
                        backstory = persona.backstory.orEmpty(),
                        quirks = persona.quirks.orEmpty(),
                        proactiveLevel = ProactiveLevel.normalize(persona.proactiveLevel),
                        memoryRetention = persona.memoryRetention.coerceIn(1, 5),
                        extractionEvery = persona.extractionEvery.coerceIn(3, 12),
                        sleepEnabled = persona.sleepEnabled,
                        sleepAt = persona.quietSleepMinutes?.let { formatClock(it) }.orEmpty(),
                        wakeAt = persona.quietWakeMinutes?.let { formatClock(it) }.orEmpty(),
                        wakeThreshold = SleepPolicy.normalizeThreshold(persona.wakeThreshold),
                        world = WorldEditorState.from(world),
                        speechTone = speech?.tone.orEmpty(),
                        speechPhrases = speech?.phrases.orEmpty(),
                        speechHabits = speech?.habits.orEmpty().joinToString("\n"),
                        speechAvoid = speech?.avoid.orEmpty().joinToString("\n"),
                        // 0.15：人格自己的提示词语言（空 = 默认中文）。
                        languageTag = persona.languageTag.orEmpty()
                    )
                    // 还没有卡时，这里会从旧的一整段提示词里尽力拆一份出来（只读，不写库）。
                    val card = runCatching { personaCardRepository.cardOf(persona.id) }
                        .getOrDefault(PersonaCard())
                    val translation = PersonaTranslationCodec.decode(persona.translationJson)
                    val translationFresh = translation?.matches(
                        PersonaTranslationSource.hashOf(persona),
                        PersonaPromptLocalization.currentAppLanguage()
                    ) == true
                    _state.update {
                        it.copy(
                            form = form,
                            initialForm = form,
                            initialPrompt = persona.prompt,
                            card = card,
                            initialCard = card,
                            isDefault = persona.isDefault,
                            currentActivity = PersonaActivityCodec.decode(persona.activityJson),
                            translationLang = translation?.lang?.takeIf(String::isNotBlank),
                            translationFresh = translationFresh,
                            loading = false
                        )
                    }
                }
            } else {
                val initial = PersonaForm(avatarSeed = Random.nextInt(0, 13))
                _state.update {
                    it.copy(
                        form = initial,
                        initialForm = initial.copy(name = "", prompt = "", description = ""),
                        initialPrompt = "",
                        loading = false
                    )
                }
            }
            apiConfigRepository.observeAll().collect { configs ->
                _state.update { it.copy(configs = configs) }
            }
        }
    }

    fun update(transform: (PersonaForm) -> PersonaForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    /**
     * 本次编辑过程中新建、但还没保存的头像文件（审计 P2-2）。
     *
     * 选图是「立刻落盘」的；不记着它们，取消编辑或连选两张就会在 files/avatars 里留孤儿。
     * 只有「本次新建的」才进这个集合：数据库里已经保存过的那张由仓库层负责删。
     *
     * 这里直接用 `MediaStorage.deleteAvatar`（不查引用）是安全的：集合里只会出现在本页
     * `persistAvatar` 刚写出来的**全新文件**（每次都是新 UUID，数据库里不可能有行引用它）。
     * 凡是「库里已经引用过的文件」，删除一律走 `MediaJanitor` 的带守卫版本。
     */
    private val pendingAvatarUris = mutableSetOf<String>()

    /** ViewModel 是否已经销毁：选图协程晚到的登记据此改成「直接删文件」（审计 P2-12）。 */
    @Volatile
    private var draftWindowClosed = false

    /** 数据库里当前保存的头像（编辑开始时的那张）；取消编辑时它不能被删。 */
    private var savedAvatarUri: String? = null

    fun setAvatarUri(uri: String?) {
        // 审计 P2-12（0.11.1）：页面已经销毁时才落盘的头像，没人会在草稿集合里登记它；
        // 这里直接删掉刚写的文件（否则就是一个永远没人回收的孤儿文件）。
        if (draftWindowClosed) {
            val orphan = uri?.trim().orEmpty().takeIf { it.isNotEmpty() }
            if (orphan != null) MediaStorage.deleteAvatar(context, orphan)
            return
        }
        val previous = _state.value.form.avatarUri
        // 连选两张：前一张如果是本次草稿，立刻删掉（不留中间文件）。
        val stale = AvatarDraftFiles.onPicked(previous, pendingAvatarUris)
        stale.forEach { MediaStorage.deleteAvatar(context, it) }
        pendingAvatarUris.removeAll(stale.toSet())
        val next = uri?.trim().orEmpty()
        if (next.isNotEmpty() && next != savedAvatarUri) pendingAvatarUris += next
        update { it.copy(avatarUri = uri) }
    }

    /**
     * 取消编辑 / 返回 / ViewModel 销毁时调用：删掉本次新建但没被保存的头像文件。
     *
     * 幂等：保存成功后 pending 已经清空，再调一次什么都不做。
     */
    fun discardDraftFiles() {
        val stale = AvatarDraftFiles.onDiscard(savedAvatarUri, pendingAvatarUris)
        stale.forEach { MediaStorage.deleteAvatar(context, it) }
        pendingAvatarUris.removeAll(stale.toSet())
    }
    fun setAvatarSeed(seed: Int) = update { it.copy(avatarSeed = seed) }
    fun setPrompt(prompt: String) = update { it.copy(prompt = prompt) }
    fun resetPromptToSaved() = update { it.copy(prompt = _state.value.initialPrompt) }

    /** 修改结构化人设卡（身份/声音/性格/价值观/边界/生活/你们之间）。 */
    fun updateCard(transform: (PersonaCard) -> PersonaCard) {
        _state.update { it.copy(card = transform(it.card)) }
    }

    fun addTrait() {
        _state.update {
            it.copy(card = it.card.copy(traits = it.card.traits + PersonaTrait()))
        }
    }

    fun updateTrait(index: Int, transform: (PersonaTrait) -> PersonaTrait) {
        _state.update { state ->
            val traits = state.card.traits.toMutableList()
            if (index !in traits.indices) return@update state
            traits[index] = transform(traits[index])
            state.copy(card = state.card.copy(traits = traits))
        }
    }

    fun removeTrait(index: Int) {
        _state.update { state ->
            val traits = state.card.traits.toMutableList()
            if (index !in traits.indices) return@update state
            traits.removeAt(index)
            state.copy(card = state.card.copy(traits = traits))
        }
    }

    /** 「AI 补全」：用简介/提示词补一个世界预设（失败时保留角色名 + 空数组）。 */
    fun fillWorldWithAi() {
        val current = _state.value
        if (current.worldFilling) return
        val form = current.form
        val brief = listOf(form.description, form.prompt).firstOrNull { it.isNotBlank() }.orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(worldFilling = true) }
            val result = runCatching { worldPresetExpander.expand(brief = brief, name = form.name) }.getOrNull()
            _state.update { state ->
                state.copy(
                    worldFilling = false,
                    // 只填空白项：用户已经改过的世界不会被一次「AI 补全」冲掉（规范 §2.3）。
                    form = if (result == null) {
                        state.form
                    } else {
                        state.form.copy(
                            world = state.form.world.mergedWith(WorldEditorState.from(result.preset))
                        )
                    }
                )
            }
            events.send(
                PersonaEditorEvent.Message(
                    context.getString(
                        if (result == null || result.isFallback) {
                            R.string.guidance_world_ai_fallback
                        } else {
                            R.string.guidance_world_ai_llm
                        }
                    )
                )
            )
        }
    }

    /** 「总结」：粘贴的聊天记录 → 话风；失败走本地兜底并明确标注。 */
    fun summarizeSpeech() {
        val current = _state.value
        if (current.speechSummarizing) return
        val sample = current.form.speechSample.trim()
        if (sample.isBlank()) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_sample_required)))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(speechSummarizing = true) }
            val result = runCatching { speechStyleSummarizer.summarize(sample) }
                .getOrElse { SpeechStyleResult(SpeechStyle(), SpeechStyleSource.HEURISTIC) }
            _state.update { state ->
                state.copy(
                    speechSummarizing = false,
                    form = state.form.copy(
                        speechTone = result.style.tone,
                        speechPhrases = result.style.phrases,
                        speechHabits = result.style.habits.joinToString("\n"),
                        speechAvoid = result.style.avoid.joinToString("\n"),
                        speechSource = result.source
                    )
                )
            }
            if (result.isHeuristic) {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_summarize_failed)))
            }
        }
    }

    fun clearSpeech() {
        _state.update {
            it.copy(
                form = it.form.copy(
                    speechTone = "",
                    speechPhrases = emptyList(),
                    speechHabits = "",
                    speechAvoid = "",
                    speechSample = "",
                    speechSource = ""
                )
            )
        }
    }

    /**
     * 「从最近聊天学习话风」：读这个人格最近 50 条消息 → 一次总结 → 预览（不写库）。
     *
     * 四件事：
     * 1. 新建人格还没 id：提示先保存（没有聊天记录可读）；
     * 2. 加载中不重复触发（按钮变「正在看…」）；
     * 3. 没有消息 / 这次没学成：各自一句人话，不伪装成功；
     * 4. 学到就先**只**放进 [PersonaEditorUiState.learnedSpeech]，等用户点「应用」。
     *
     * 隐私：样本只在 [StyleDistillationRepository.distill] 内部活一次请求的时间，
     * 这里既不落库也不打印（日志里也没有正文）。
     */
    fun learnSpeechFromChat() {
        val current = _state.value
        if (current.speechLearning) return
        val id = current.personaId
        if (id == null) {
            viewModelScope.launch {
                events.send(
                    PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_learn_need_save))
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(speechLearning = true) }
            val distilled = styleDistillationRepository.distill(id)
            when (distilled) {
                is StyleDistillationRepository.Distillation.Ready -> {
                    val preview = LearnedSpeechUi(
                        tone = distilled.style.tone,
                        phrases = distilled.style.phrases,
                        habits = distilled.style.habits,
                        avoid = distilled.style.avoid,
                        source = distilled.source,
                        messageCount = distilled.messageCount
                    )
                    _state.update { it.copy(speechLearning = false, learnedSpeech = preview) }
                }

                StyleDistillationRepository.Distillation.NoMessages -> {
                    _state.update { it.copy(speechLearning = false, learnedSpeech = null) }
                    events.send(
                        PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_learn_empty))
                    )
                }

                StyleDistillationRepository.Distillation.Failed -> {
                    _state.update { it.copy(speechLearning = false, learnedSpeech = null) }
                    events.send(
                        PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_learn_failed))
                    )
                }
            }
        }
    }

    /**
     * 预览里点「应用」：立刻把学到的说话方式写进人格（`speechStyleJson` + 一个版本）。
     *
     * 不是「填进表单等你再保存」：用户点的是「应用」，所以当场生效；表单也同步改成新值，
     * 免得页面看起来还是旧话风（`initialForm` 一起改，避免出现「没动过却显示未保存」）。
     */
    fun applyLearnedSpeech() {
        val current = _state.value
        val preview = current.learnedSpeech ?: return
        val id = current.personaId
        if (id == null) {
            _state.update { it.copy(learnedSpeech = null) }
            viewModelScope.launch {
                events.send(
                    PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_learn_need_save))
                )
            }
            return
        }
        if (preview.isEmpty) {
            _state.update { it.copy(learnedSpeech = null) }
            return
        }
        val json = SpeechStyleCodec.encode(preview.toStyle())
        viewModelScope.launch {
            _state.update { it.copy(speechLearning = true) }
            val result = personaRepository.applySpeechStyle(id, json)
            if (result.isSuccess) {
                val habitsText = preview.habits.joinToString("\n")
                val avoidText = preview.avoid.joinToString("\n")
                // 表单与「初始值」一起改：数据库已经是新话风了，页面不该还显示「有未保存的改动」。
                _state.update { state ->
                    state.copy(
                        speechLearning = false,
                        learnedSpeech = null,
                        form = state.form.copy(
                            speechTone = preview.tone,
                            speechPhrases = preview.phrases,
                            speechHabits = habitsText,
                            speechAvoid = avoidText,
                            speechSource = preview.source
                        ),
                        initialForm = state.initialForm.copy(
                            speechTone = preview.tone,
                            speechPhrases = preview.phrases,
                            speechHabits = habitsText,
                            speechAvoid = avoidText,
                            speechSource = preview.source
                        )
                    )
                }
                events.send(
                    PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_learn_applied))
                )
            } else {
                _state.update { it.copy(speechLearning = false) }
                events.send(
                    PersonaEditorEvent.Message(
                        ErrorText.of(
                            context,
                            result.exceptionOrNull(),
                            R.string.guidance_speech_learn_failed
                        )
                    )
                )
            }
        }
    }

    /**
     * 预览里点「取消」：只关掉预览，**不写库**（连 `speechStyleJson` 都不碰）。
     *
     * 这是这个功能唯一的「什么都没发生」路径：取消一次不该留下任何痕迹。
     */
    fun dismissLearnedSpeech() {
        _state.update { it.copy(learnedSpeech = null) }
    }

    /**
     * 「重新翻译」：清空缓存并立刻译一次（`spec/persona-translation/README.md` §4）。
     *
     * 手动触发不受「自动翻译人设」开关影响；结果只更新这一页的状态与一句提示，
     * 成功后下次打开聊天就会用新译文（提示词是在生成时读缓存的）。
     */
    fun retranslate() {
        val id = _state.value.personaId ?: return
        if (_state.value.translating) return
        viewModelScope.launch {
            _state.update { it.copy(translating = true) }
            val outcome = translationCoordinator.ensureTranslated(id, force = true)
            val persona = runCatching { personaRepository.getById(id) }.getOrNull()
            val translation = PersonaTranslationCodec.decode(persona?.translationJson)
            _state.update { state ->
                state.copy(
                    translating = false,
                    translationLang = translation?.lang?.takeIf(String::isNotBlank)
                        ?: state.translationLang.takeIf { outcome != PersonaTranslationOutcome.TRANSLATED },
                    translationFresh = translation != null && persona != null &&
                        translation.matches(
                            PersonaTranslationSource.hashOf(persona),
                            PersonaPromptLocalization.currentAppLanguage()
                        )
                )
            }
            events.send(PersonaEditorEvent.Message(context.getString(translationMessage(outcome))))
        }
    }

    /** 语言标签的可读名字（「中文」「English」），界面显示「已翻译成 …」。 */
    fun languageLabel(tag: String): String = translationCoordinator.languageLabel(tag)

    private fun translationMessage(outcome: PersonaTranslationOutcome): Int = when (outcome) {
        PersonaTranslationOutcome.TRANSLATED -> R.string.persona_editor_translation_ok
        PersonaTranslationOutcome.FAILED -> R.string.persona_editor_translation_failed
        PersonaTranslationOutcome.SAME_LANGUAGE -> R.string.persona_editor_translation_skipped
        PersonaTranslationOutcome.DISABLED -> R.string.persona_editor_translation_disabled
        PersonaTranslationOutcome.CACHED -> R.string.persona_editor_translation_ok
        PersonaTranslationOutcome.MISSING -> R.string.persona_editor_persona_missing
    }

    // ------------------------------------------------------------ 人格进化（0.13）

    /**
     * 订阅当前人格的待审进化补丁，摊平成界面能直接排版的行。
     *
     * 只订阅、不轮询：7 天过期由仓库在下一次评估时清掉，界面不用自己过滤。
     * 新建人格（personaId == null）没有待审列表，直接保持空。
     */
    private fun observePending(personaId: String?) {
        pendingJob?.cancel()
        pendingJob = null
        _state.update { it.copy(pendingChanges = emptyList()) }
        if (personaId == null) return
        pendingJob = viewModelScope.launch {
            personaEvolutionRepository.observePending(personaId).collect { patches ->
                val rows = patches.flatMap { patch ->
                    personaEvolutionRepository.describe(patch).map { change ->
                        PendingChangeUi(
                            patchId = patch.id,
                            field = change.field,
                            op = change.op,
                            value = change.value,
                            reason = change.reason
                        )
                    }
                }
                _state.update { it.copy(pendingChanges = rows) }
            }
        }
    }

    /**
     * 接受一条待审变化：写回人格，并把表单里的四个可进化字段对齐到最新人格。
     *
     * 取舍：**表单本身有未保存编辑时不覆盖 form**，只把 initialForm 的四个字段对齐新人格。
     * form 里是用户正在敲的内容，被一次后台写回冲掉比「暂时看到旧值」更糟；
     * 页面上会先提示已经写进人设，保存或重新进入后就能看到新值。
     * 没有未保存编辑时 form 与 initialForm 一起刷新，页面立刻显示最新值。
     * （人格卡 card 的编辑与这四项无关，不阻止 form 刷新。）
     */
    fun acceptPendingChange(patchId: String) {
        if (patchId.isBlank() || _state.value.pendingBusy) return
        _state.update { it.copy(pendingBusy = true) }
        viewModelScope.launch {
            val result = personaEvolutionRepository.accept(patchId)
            val persona = result.getOrNull()
            if (persona == null) {
                _state.update { it.copy(pendingBusy = false) }
                events.send(
                    PersonaEditorEvent.Message(
                        ErrorText.of(
                            context,
                            result.exceptionOrNull(),
                            R.string.persona_evolution_accept_failed
                        )
                    )
                )
                return@launch
            }
            _state.update { state ->
                val formDirty = state.form != state.initialForm
                state.copy(
                    pendingBusy = false,
                    form = if (formDirty) state.form else state.form.withEvolution(persona),
                    initialForm = state.initialForm.withEvolution(persona),
                    // 仓库已经删掉这条补丁；先乐观移除，等 Room 的 Flow 推回权威列表。
                    pendingChanges = state.pendingChanges.filterNot { it.patchId == patchId }
                )
            }
            events.send(
                PersonaEditorEvent.Message(
                    context.getString(
                        if (_state.value.serverMode) R.string.persona_evolution_accepted_server
                        else R.string.persona_evolution_accepted
                    )
                )
            )
        }
    }

    /**
     * 拒绝一条待审变化：只丢掉提议，人设一动不动。
     *
     * 服务端模式会先调服务端 `reject`：失败时**保留**这条待审并提示，不能在本机假装已经拒绝
     * （否则下一次拉服务端待审列表它又回来了，用户会以为按钮没生效）。
     */
    fun rejectPendingChange(patchId: String) {
        if (patchId.isBlank() || _state.value.pendingBusy) return
        _state.update { it.copy(pendingBusy = true) }
        viewModelScope.launch {
            val result = personaEvolutionRepository.reject(patchId)
            if (result.isFailure) {
                _state.update { it.copy(pendingBusy = false) }
                events.send(
                    PersonaEditorEvent.Message(
                        ErrorText.of(
                            context,
                            result.exceptionOrNull(),
                            R.string.persona_evolution_reject_failed
                        )
                    )
                )
                return@launch
            }
            _state.update { state ->
                state.copy(
                    pendingBusy = false,
                    pendingChanges = state.pendingChanges.filterNot { it.patchId == patchId }
                )
            }
            events.send(PersonaEditorEvent.Message(context.getString(R.string.persona_evolution_rejected)))
        }
    }

    /**
     * 人格实体 → 表单里的四个可进化字段（只动这四项，别的编辑一概不碰）。
     *
     * 列表用当前语言的分隔符回显，和初次加载时保持一致（[PersonaForm.joinList]）。
     */
    private fun PersonaForm.withEvolution(persona: PersonaEntity): PersonaForm {
        val separator = context.getString(R.string.persona_editor_list_separator)
        return copy(
            traitsText = PersonaForm.joinList(PersonaProfile.decodeList(persona.traits), separator),
            likesText = PersonaForm.joinList(PersonaProfile.decodeList(persona.likes), separator),
            behaviorsText = PersonaForm.joinList(PersonaProfile.decodeList(persona.behaviors), separator),
            quirks = persona.quirks.orEmpty()
        )
    }

    private fun buildWorldJson(form: PersonaForm): String? {
        val world = form.world.toPreset()
        return if (world.isEmpty() || world.worldName.isBlank()) null else WorldPresetCodec.encode(world)
    }

    private fun buildSpeechJson(form: PersonaForm): String? {
        val speech = SpeechStyle(
            tone = form.speechTone,
            phrases = form.speechPhrases,
            habits = form.speechHabits.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
            avoid = form.speechAvoid.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        )
        return if (speech.isEmpty()) null else SpeechStyleCodec.encode(speech)
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val form = current.form
        // 0.13：名字和提示词不再是保存闸门（真机反馈「一项没填就保存不了」）。
        // 名字空 → 本地化默认名；提示词空 → 用名字 + 简介生成一句。规则在 domain 里，
        // 与仓储层共用；这里先算出来，是为了让「保存后能正常聊天」不依赖调用方记得兜底。
        val name = PersonaDraftDefaults.name(form.name, context.getString(R.string.draft_default_name))
        val prompt = PersonaDraftDefaults.prompt(
            rawPrompt = form.prompt,
            name = form.name,
            description = form.description,
            separator = context.getString(R.string.draft_default_prompt_separator),
            fallback = context.getString(R.string.draft_default_prompt)
        )
        // 作息时间：非法格式以前会被静默存成 null（→ 用默认 23:30–08:00），
        // 用户以为设了 24:00，实际却在 23:30 就睡了。这里显式拦下并提示。
        if (form.sleepEnabled) {
            val invalid = listOf(form.sleepAt to R.string.persona_editor_sleep_time_invalid,
                form.wakeAt to R.string.persona_editor_sleep_time_invalid)
                .firstOrNull { (text, _) -> text.isNotBlank() && parseClock(text) == null }
            if (invalid != null) {
                viewModelScope.launch {
                    events.send(PersonaEditorEvent.Message(context.getString(invalid.second)))
                }
                return
            }
        }
        // 可视化编辑器也要校验：问题定位到「哪一段的第几行」，不静默丢条目（规范 §2.3）。
        val worldIssues = form.world.issues()
        if (worldIssues.isNotEmpty()) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(WorldEditorErrors.message(context, worldIssues)))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = personaRepository.save(
                PersonaDraft(
                    id = personaId,
                    name = name,
                    description = form.description,
                    avatarUri = form.avatarUri,
                    avatarSeed = form.avatarSeed,
                    prompt = prompt,
                    category = form.category,
                    defaultApiConfigId = form.defaultApiConfigId,
                    modelOverride = form.modelOverride.ifBlank { null },
                    // 0.15：模型优化档位跟着表单走（表单里永远是三个合法值之一）。
                    modelProfile = form.modelProfile,
                    traits = form.traits,
                    likes = form.likes,
                    behaviors = form.behaviors,
                    backstory = form.backstory.ifBlank { null },
                    quirks = form.quirks.ifBlank { null },
                    proactiveLevel = form.proactiveLevel,
                    memoryRetention = form.memoryRetention,
                    extractionEvery = form.extractionEvery,
                    sleepEnabled = form.sleepEnabled,
                    wakeThreshold = form.wakeThreshold,
                    quietSleepMinutes = parseClock(form.sleepAt),
                    quietWakeMinutes = parseClock(form.wakeAt),
                    worldJson = buildWorldJson(form)?.takeIf { it.isNotBlank() },
                    speechStyleJson = buildSpeechJson(form)?.takeIf { it.isNotBlank() },
                    // 0.15：人格语言跟着表单走；空串交给仓储层按「没选过」处理。
                    languageTag = form.languageTag.takeIf { it.isNotBlank() }
                )
            )
            if (result.isFailure) {
                _state.update { it.copy(saving = false) }
                events.send(
                    PersonaEditorEvent.Message(
                        ErrorText.of(context, result.exceptionOrNull(), R.string.persona_editor_save_failed)
                    )
                )
                return@launch
            }
            val id = result.getOrThrow()
            // 保存成功：只有被采纳的那张头像该留下（审计 P2-2：连选两张时前一张是孤儿）。
            val adoptedAvatar = _state.value.form.avatarUri
            AvatarDraftFiles.onSaved(adoptedAvatar, pendingAvatarUris)
                .forEach { MediaStorage.deleteAvatar(context, it) }
            pendingAvatarUris.clear()
            savedAvatarUri = adoptedAvatar
            // 只有用户真的动过卡、且卡不是空的才写回：
            // 空卡代表「留空沿用提示词原文」，不能拿占位渲染去覆盖旧设定。
            val card = current.card
            if (card != current.initialCard && !card.isEmpty()) {
                val reason = if (current.personaId == null) {
                    PersonaRevisionReason.CARD_CREATED
                } else {
                    PersonaRevisionReason.CARD_EDITED
                }
                val cardResult = personaCardRepository.saveCard(id, card, reason)
                if (cardResult.isFailure) {
                    _state.update { it.copy(saving = false) }
                    events.send(
                        PersonaEditorEvent.Message(
                            context.getString(
                                R.string.persona_editor_card_save_failed,
                                ErrorText.of(
                                    context,
                                    cardResult.exceptionOrNull(),
                                    R.string.persona_editor_unknown_error
                                )
                            )
                        )
                    )
                    events.send(PersonaEditorEvent.Saved(id))
                    return@launch
                }
            }
            _state.update { it.copy(saving = false) }
            events.send(PersonaEditorEvent.Saved(id))
        }
    }

    fun getSavedPrompt(): String = _state.value.initialPrompt

    override fun onCleared() {
        // 审计 P2-12（0.11.1）：先立旗子，之后到达的 setAvatarUri 会直接删文件而不是登记。
        draftWindowClosed = true
        // 兜底（审计 P2-2）：用户直接按返回键 / 进程回收时，界面可能没机会调 discardDraftFiles。
        discardDraftFiles()
        super.onCleared()
    }

    internal companion object {
        /** 空的睡眠时间 = 不自定义，交给默认 23:30–08:00。 */
        fun parseClock(text: String): Int? {
            val match = Regex("""(\d{1,2})\s*[:：]\s*(\d{1,2})""").find(text.trim()) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            // 「24:00」是午夜（= 00:00）。以前这里直接拒掉它，于是 `quietSleepMinutes` 存成
            // null → SleepPolicy 回退默认 23:30 → 设了「24:00 睡」的人 23:40 就已经在睡，
            // 表现为「只读不回」。现在明确接受 24:00（仅此一个 24 点整），其余 24 点一律非法。
            if (hour == 24 && minute == 0) return 0
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        fun formatClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
