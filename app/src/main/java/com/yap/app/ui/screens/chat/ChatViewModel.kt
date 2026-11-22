package com.yap.app.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.yap.app.domain.MessageBlockPaging
import androidx.lifecycle.viewModelScope
import com.yap.app.ui.common.ErrorText
import com.yap.app.R
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.local.ProactiveRuleEntity
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.AudioVideoRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.MediaJanitor
import com.yap.app.data.repo.StickerRepository
import com.yap.app.data.repo.MemoryRepository
import com.yap.app.data.repo.MoodRepository
import com.yap.app.data.repo.PersonaCardRepository
import com.yap.app.data.repo.PresenceRepository
import com.yap.app.data.repo.ProactiveRepository
import com.yap.app.data.repo.ProactiveScheduler
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PersonaTranslationCoordinator
import com.yap.app.data.remote.ServerTypingState
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MediaRejection
import com.yap.app.domain.MediaSendGuard
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.StickerSource
import com.yap.app.domain.ReadReceipt
import com.yap.app.domain.PersonaSwitchNotice
import com.yap.app.domain.MoodState
import com.yap.app.domain.Presence
import com.yap.app.domain.ExportFile
import com.yap.app.domain.GenerationEvent
import com.yap.app.domain.SilentReason
import com.yap.app.domain.ChatSleepHint
import com.yap.app.domain.SleepPolicy
import com.yap.app.domain.ToolTraceItem
import com.yap.app.domain.ProactiveProposal
import com.yap.app.util.ChatNotifier
import com.yap.app.util.HumanTiming
import com.yap.app.util.MediaStorage
import com.yap.app.util.VideoFramesExtractor
import com.yap.app.util.VoiceRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

data class ChatUiState(
    val session: ChatSessionEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val persona: PersonaEntity? = null,
    /** 他此刻的心情，标题栏上那枚小胶囊。 */
    val mood: MoodState = MoodState(),
    /** 他此刻在做什么，标题栏副标题那行小字。他自己的状态，界面只读。 */
    val presence: Presence = Presence(),
    val config: ApiConfigEntity? = null,
    val personas: List<PersonaEntity> = emptyList(),
    val apiConfigs: List<ApiConfigEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val draft: String = "",
    val images: List<MessageAttachment> = emptyList(),
    val streamingText: String = "",
    val streamingTraces: List<ToolTraceItem> = emptyList(),
    val isGenerating: Boolean = false,
    /**
     * 界面上要不要显示「正在输入…」。
     *
     * 本地模式 = 这一轮真的开始出字了（[hasStarted]）；服务端权威模式 = **只**看服务端发来的
     * `typing.started/typing.stopped`（§8）——客户端不自己加延迟、不自己播动画。
     */
    val typing: Boolean = false,
    /** 正在读库、组装并编码导出文本（在 IO 线程上）：界面显示进行中并避免重复点。 */
    val isExporting: Boolean = false,
    /**
     * 模型真的开始说话了（收到 Started）。
     *
     * 睡眠 / 免打扰 / defer_reply 都会在 Started 之前就结束生成：界面连「正在输入」都不该出现，
     * 只让用户消息自己因为 readAt 显示「已读」。所以打字状态必须等这个标记为真。
     */
    val hasStarted: Boolean = false,
    val switchingPersona: Boolean = false,
    val snackbar: String? = null,
    val showConfigPicker: Boolean = false,
    val showPersonaPicker: Boolean = false,
    val loaded: Boolean = false,
    /** 已加载的块之外还有更早的块（界面上「加载更早」那一行）。 */
    val hasMoreOlder: Boolean = false,
    /** 正在加载更早的那一块：列表顶部显示转圈，并且不再重复触发。 */
    val loadingOlder: Boolean = false,
    /**
     * 已加载的最新块之外还有更新的块（规范 §3 Newer）。
     *
     * 0.12 起这是**可以加载**的：底部显示「下面还有更新的消息」，滚到底会自动加载，
     * 点一下也加载（不再是「只能跳回最新」）。
     */
    val hasMoreNewer: Boolean = false,
    /** 正在加载更新的那一块。 */
    val loadingNewer: Boolean = false,
    /**
     * 窗口里最后一条「可计入」的助手消息时间（`role=ASSISTANT && status != ERROR`）。
     *
     * 已读判定（`ReadReceipt`）每条气泡只跟它比一次：以前每个气泡都扫一遍整个已加载窗口，
     * 把历史翻完之后是 O(n²)（审计 P2-10）。
     */
    val lastAssistantAt: Long? = null,
    /**
     * 输入框上方那一行「他睡着了（08:00 醒）；连着发几条可以叫醒他」；null = 不显示。
     *
     * 0.12 续 7：只在「这一轮因为睡着被静默」且他此刻仍在睡的时候出现。它不是消息气泡、
     * 不写库，收到回复或他睡醒之后自己消失（判定见 `ChatSleepHint`）。
     */
    val sleepHint: String? = null,
    /** 表情历史面板是否展开（输入框上方那块）。 */
    val showStickerPanel: Boolean = false,
    /** 面板切到「全部」了吗；false = 最近 30 个。 */
    val stickerShowAll: Boolean = false,
    /** 我的表情（按 createdAt 倒序）；面板里只渲染前 30 个或全部。 */
    val stickers: List<com.yap.app.data.local.StickerEntity> = emptyList(),
    /**
     * 正在录音的毫秒数（0 = 没在录）。
     *
     * 只驱动输入框上方那条「录音中 0:03 · 松开发送」的提示；到 5 分钟/8 MB 会被
     * [VoiceRecorder] 自动停并直接发送（规格：≤5 分钟自动停）。
     */
    val recordingElapsedMs: Long = 0L
) {
    val canSend: Boolean
        get() = session != null && config != null && persona != null && !isGenerating && !switchingPersona
    val canSendContent: Boolean
        get() = canSend && (draft.isNotBlank() || images.isNotEmpty())
}

data class ChatEphemeral(
    val draft: String = "",
    val images: List<MessageAttachment> = emptyList(),
    val streamingText: String = "",
    val streamingTraces: List<ToolTraceItem> = emptyList(),
    val isGenerating: Boolean = false,
    val hasStarted: Boolean = false,
    /** 服务端模式下的「他正在输入」：只由 typing 事件驱动（§8），离线同步不补动画。 */
    val serverTyping: Boolean = false,
    val switchingPersona: Boolean = false,
    val isExporting: Boolean = false,
    val snackbar: String? = null,
    val showConfigPicker: Boolean = false,
    val showPersonaPicker: Boolean = false,
    /**
     * 上一轮为什么没说话（`GenerationEvent.Silent` 带来的原因）；开始/正常结束一轮时清空。
     *
     * 只有 [SilentReason.ASLEEP] 会让聊天页显示那行睡眠提示，其余原因（免打扰、晚点回）
     * 只是「已读不回」，界面不解释。
     */
    val silentReason: SilentReason? = null,
    /** 表情面板的开关与「最近/全部」切换（纯界面状态，不进库）。 */
    val showStickerPanel: Boolean = false,
    val stickerShowAll: Boolean = false,
    /** 录音进行中的毫秒数；0 表示没在录。 */
    val recordingElapsedMs: Long = 0L
)

/** 本地分页状态：只影响聊天页，不进 Session。 */

sealed interface ChatEvent {
    data class OpenSession(val sessionId: String) : ChatEvent
    data class ExportReady(val file: ExportFile) : ChatEvent
    data object SessionDeleted : ChatEvent

    /**
     * 从检索结果跳进来时，要定位的那条消息已经进入窗口。
     *
     * 界面收到后滚到它、并给它约 2 秒的高亮。做成一次性事件而不是 UiState 字段：
     * 「定位」是一次动作，不该在每次重组时重新触发（返回列表再进来会重新滚一次）。
     */
    data class FocusMessage(val messageId: String) : ChatEvent
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val personaRepository: PersonaRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val presenceRepository: PresenceRepository,
    private val proactiveRepository: ProactiveRepository,
    private val settingsRepository: SettingsRepository,
    private val chatNotifier: ChatNotifier,
    /** 删草稿图片之前的引用守卫（0.12）：用户可能选了一张已经发过的图（内容寻址同名文件）。 */
    private val mediaJanitor: MediaJanitor,
    /** 打开会话前按需翻译人设（`spec/persona-translation/README.md` §2 第 1 条）。 */
    private val translationCoordinator: PersonaTranslationCoordinator,
    /** 表情包（`spec/stickers/README.md`）：历史面板、点选即发送、长按收藏。 */
    private val stickerRepository: StickerRepository,
    /** 语音/视频（0.13）：气泡播放要先把服务端媒体缓存成本地文件。 */
    private val audioVideoRepository: AudioVideoRepository,
    /**
     * 0.14.1：服务端模式「他正在输入」的进程级瞬时状态（§8）。
     *
     * 为什么放在 ViewModel 之外：轮询器在 Application 级运行时里跑，聊天页只是观察者；
     * 同一个会话的 typing 事件不该因为 Activity 重建而丢。
     */
    private val serverTypingState: ServerTypingState,
    /** 0.15 ASR：发送前把语音/视频转写成文字；关着/没 Key 时零请求直通。 */
    private val asrTranscriber: com.yap.app.data.repo.AsrTranscriber,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle.get<String>("sessionId"))

    private val ephemeral = MutableStateFlow(ChatEphemeral())
    private val eventsChannel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private val sessionFlow = chatRepository.observeSession(sessionId)

    /**
     * 区块分页状态（规范 §3）。
     *
     * 只记「已加载哪几块 + 两侧还有没有块 + 哪一侧在加载」，不记条数、不记 offset：
     * 一页就是一块，块编号是落库的，所以新消息进来不会让页边界滑动。
     */
    private val window = MutableStateFlow(MessageBlockPaging.State())

    /**
     * 已加载的那几块的消息（可观察）。
     *
     * 块内的编辑/删除、以及块里追加的新消息都会通过 Room 的失效通知回到这里；
     * 每次数据回来都重算一次两侧边界（`hasMoreOlder/Newer`）——不能只在首屏算一次，
     * 否则「下面还有更新的消息」在后台写入之后就不会再出现（旧实现踩过）。
     */
    private val messageFlow = window
        .map { it.blocks }
        .distinctUntilChanged()
        .flatMapLatest { blocks -> chatRepository.observeBlocks(sessionId, blocks) }
        .onEach { rows -> refreshBlockBoundaries(rows) }

    private val personaFlow = sessionFlow
        .map { it?.personaId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else personaRepository.observeById(id) }

    private val configFlow = sessionFlow
        .map { it?.apiConfigId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else apiConfigRepository.observeById(id) }

    private val moodFlow = personaFlow
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else moodRepository.observe(id) }

    /**
     * 「他正在……」：同样是照 [moodFlow] 的写法接线，换了个人就跟着换。
     * 这是从他自己的痕迹推出来的，界面只显示。
     */
    private val presenceFlow = personaFlow
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else presenceRepository.observe(id) }

    private val detailsFlow = combine(sessionFlow, messageFlow, personaFlow, configFlow) { session, messages, persona, config ->
        Details(session, messages, persona, config)
    }.combine(personaRepository.observeAll()) { details, personas ->
        details to personas
    }.combine(apiConfigRepository.observeAll()) { pair, configs ->
        DetailsBundle(pair.first, pair.second, configs)
    }.combine(settingsRepository.settings) { bundle, settings ->
        bundle to settings
    }.combine(stickerRepository.observeAll()) { (bundle, settings), stickers ->
        ScreenState(bundle, settings, stickers)
    }

    /** 心情和「他正在……」一起进界面：都是他自己的状态，界面看得到、改不了。 */
    private val ambientFlow = combine(moodFlow, presenceFlow) { mood, presence -> mood to presence }

    private val localFlow = combine(ephemeral, window) { local, blocks -> local to blocks }

    private val uiFlow = combine(
        detailsFlow.combine(ambientFlow) { screen, ambient -> screen to ambient },
        localFlow
    ) { (screen, ambient), (local, paging) ->
        val bundle = screen.bundle
        val details = bundle.details
        val settings = screen.settings
        // 已读判定用到的「最后一条可计入的助手消息」一次算好（审计 P2-10）：
        // 以前每个气泡都扫一遍整个窗口，翻完历史后每帧上万次比较。
        val lastAssistantAt = ReadReceipt.latestAssistantAt(
            details.messages
                .filter { it.role == MessageRole.ASSISTANT && it.status != MessageStatus.ERROR }
                .map { it.createdAt }
        )
        val presence = ambient.second ?: Presence()
        // §8：服务端权威模式下「正在输入」只听服务端事件；本地模式仍是「这一轮开始出字」。
        val typing = if (chatRepository.isServerMode()) {
            local.serverTyping
        } else {
            local.isGenerating && local.hasStarted
        }
        ChatUiState(
            session = details.session,
            messages = details.messages,
            persona = details.persona,
            mood = ambient.first ?: MoodState(),
            presence = presence,
            config = details.config,
            personas = bundle.personas,
            apiConfigs = bundle.apiConfigs,
            settings = settings,
            draft = local.draft,
            images = local.images,
            streamingText = local.streamingText,
            streamingTraces = local.streamingTraces,
            isGenerating = local.isGenerating,
            typing = typing,
            hasStarted = local.hasStarted,
            switchingPersona = local.switchingPersona,
            isExporting = local.isExporting,
            snackbar = local.snackbar,
            showConfigPicker = local.showConfigPicker,
            showPersonaPicker = local.showPersonaPicker,
            loaded = details.session != null,
            hasMoreOlder = paging.hasMoreOlder,
            loadingOlder = paging.loadingOlder,
            hasMoreNewer = paging.hasMoreNewer,
            loadingNewer = paging.loadingNewer,
            lastAssistantAt = lastAssistantAt,
            sleepHint = sleepHintText(local.silentReason, details.persona, presence),
            showStickerPanel = local.showStickerPanel,
            stickerShowAll = local.stickerShowAll,
            stickers = screen.stickers,
            recordingElapsedMs = local.recordingElapsedMs
        )
    }

    /**
     * 输入框上方那行睡眠提示的文案（0.12 续 7）。
     *
     * 出现条件全在 [ChatSleepHint]：这一轮因为睡着被静默，且他此刻仍在睡。
     * 醒来的钟点取自人格的作息（`SleepPolicy.wakeClockText`），没有作息的人不显示。
     * 不写库、不是气泡 —— 它只是界面对「已读但没回」的一句解释。
     */
    private fun sleepHintText(
        reason: SilentReason?,
        persona: PersonaEntity?,
        presence: Presence
    ): String? {
        if (!ChatSleepHint.visible(reason, presence)) return null
        val wakeClock = persona?.let {
            SleepPolicy.wakeClockText(it.sleepEnabled, it.quietSleepMinutes, it.quietWakeMinutes)
        } ?: return null
        return context.getString(R.string.chat_sleep_hint, wakeClock)
    }

    val uiState: StateFlow<ChatUiState> = uiFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState()
    )

    private var streamJob: Job? = null
    private var memoryJob: Job? = null

    /** ViewModel 是否已经销毁（[onCleared] 里立起）：选图协程据此决定「登记还是直接删文件」。 */
    @Volatile
    private var draftWindowClosed = false

    /**
     * 一次「发送 / 编辑重发」从落库到开始出字之间有几十毫秒空档。
     * 没有它，连着点两下会写进两条一样的消息——这是原来 isPreparing 顺带挡住的事。
     */
    private var operationInFlight = false

    /**
     * 从检索结果跳过来时要定位的消息 id（导航参数，可能没有）。
     *
     * 定位是**区块级**的：目标消息的 `blockIndex` 是落库的，所以只要把窗口换成「目标所在的那一块」，
     * 再等它真的出现在列表里即可（块内最多 [com.yap.app.domain.MessageBlocks.BLOCK_SIZE] 条，
     * 一次读取量与会话长度无关）。
     *
     * 为什么不「按 id 只查一条插进列表」：聊天页的列表是一段连续的块，孤立插一条会让上下文断开，
     * 往上滑还会跳；所以只能真的把窗口翻到那一块。
     */
    private val jumpTargetId: String? = savedStateHandle.get<String>("messageId")

    init {
        chatRepository.setOpenSession(sessionId)
        // 从首页点进这个会话时，也把它的那条系统通知撤掉（审计 P2-14：以前只有「从通知进来」才撤）。
        runCatching { chatNotifier.cancel(context, sessionId) }
        viewModelScope.launch {
            chatRepository.markRead(sessionId)
            initialPrepare()
        }
        // 服务端模式：先把最新一块拉进 Room。聊天页的窗口/块边界逻辑完全复用本地实现，
        // 这里只负责「首屏数据从服务端来」；失败安静（事件/下一次同步会补）。
        if (chatRepository.isServerMode()) {
            viewModelScope.launch {
                runCatching { chatRepository.openServerSession(sessionId) }
            }
        }
        // 0.14.1：前台收到 typing.started/stopped 就显示/收起输入指示（§8）。
        // 只观察本会话；离线同步回来的消息不经过这里，所以不会补打字动画。
        viewModelScope.launch {
            serverTypingState.observe(sessionId).collect { typing ->
                ephemeral.update { it.copy(serverTyping = typing) }
            }
        }
        // 开聊之前先把人设翻译好：异步、不阻塞首屏；失败静默（用原文）。
        // 同一进程内按 `personaId + lang + sourceHash` 去重，缓存命中时零请求。
        viewModelScope.launch {
            val personaId = runCatching { chatRepository.getSession(sessionId)?.personaId }.getOrNull()
                ?: return@launch
            translationCoordinator.prefetch(personaId)
        }
        // 首屏只加载**最新一块**（规范 §3）：最新块编号从块表观察，
        // 这样「会话从空变成有一块」「最新块被删空」都能重新定位。
        viewModelScope.launch {
            chatRepository.observeLastBlockIndex(sessionId).collect { last ->
                applyLatestBlock(last)
            }
        }
        startJumpToTarget()
    }

    /**
     * 滚到检索命中的那条消息：把窗口换成「目标那一块」并等它出现。
     *
     * 三种结局都安静收场：找到了 → 发事件；目标在首屏（最新块里）→ 一样发事件；
     * 目标不在了（删过 / 换过设备）→ 停在最新一屏，不报错 —— 用户自己会明白那条话没了。
     */
    private fun startJumpToTarget() {
        val target = jumpTargetId ?: return
        if (target.isBlank()) return
        viewModelScope.launch {
            val located = runCatching {
                chatRepository.blockContainingMessage(sessionId, target)
            }.getOrNull()
            if (located != null && window.value.newestBlock != located.blockIndex) {
                window.value = MessageBlockPaging.onJump(
                    targetBlock = located.blockIndex,
                    hasMoreOlder = located.hasMoreOlder,
                    hasMoreNewer = located.hasMoreNewer
                )
            }

            // 5 秒上限是为了它已经被删掉时不要把协程永远挂着（此时停在新的一屏即可）。
            val settled = withTimeoutOrNull(JUMP_TIMEOUT_MILLIS) {
                uiState.first { state -> state.messages.any { it.id == target } }
            }
            if (settled != null) eventsChannel.send(ChatEvent.FocusMessage(target))
        }
    }

    /**
     * 「回到最新」：只保留最新一块（首屏语义）。
     *
     * 0.12 起它不再是「往下看更新消息」的唯一入口——底部那一行会一块一块往下加载，
     * 这里留给「从很久以前跳回来」和「发新消息之前」用。
     */
    fun showLatest() {
        viewModelScope.launch {
            if (chatRepository.isServerMode()) {
                runCatching { chatRepository.loadServerPage(sessionId) }
            }
            val last = runCatching { chatRepository.lastBlockIndex(sessionId) }.getOrNull()
            val first = runCatching { chatRepository.firstBlockIndex(sessionId) }.getOrNull()
            window.value = MessageBlockPaging.onLatest(window.value, last, first)
        }
    }

    /**
     * 用户主动写消息（发送 / 编辑重发）之前，若停在更早的块里，先回到最新块。
     *
     * 留在旧块里的话：新写的消息落在最新块，列表里根本看不到——用户感知是
     * 「发出去的话消失了、他也不理我」；随后助手回复同样落在窗口之外。
     */
    private suspend fun leaveJumpWindowForNewMessage() {
        val last = runCatching { chatRepository.lastBlockIndex(sessionId) }.getOrNull()
        if (!MessageBlockPaging.isAtNewestBlock(window.value, last)) {
            val first = runCatching { chatRepository.firstBlockIndex(sessionId) }.getOrNull()
            window.value = MessageBlockPaging.onLatest(window.value, last, first)
        }
    }

    /**
     * 块表数据变化后重算两侧边界（规范 §3 的「还有没有上一块 / 下一块」）。
     *
     * @param rows 本次观察到的消息（没用上，但保留参数让调用点读起来就是「数据回来了」）
     */
    private suspend fun refreshBlockBoundaries(@Suppress("UNUSED_PARAMETER") rows: List<MessageEntity>) {
        val state = window.value
        if (!state.initialized) return
        val oldest = state.oldestBlock ?: return
        val newest = state.newestBlock ?: return
        val hasOlder = runCatching { chatRepository.blocksAround(sessionId, oldest).older != null }
            .getOrDefault(false)
        val hasNewer = runCatching { chatRepository.blocksAround(sessionId, newest).newer != null }
            .getOrDefault(false)
        window.update { MessageBlockPaging.onBoundaries(it, hasOlder, hasNewer) }
    }

    /**
     * 最新块编号变了（首屏、会话清空、最新块被删空）：重新定位窗口。
     *
     * - 还没定位过 → 首屏；
     * - 窗口里一块都没有（会话本来是空的，现在有消息了；或者被清空了）→ 重新首屏；
     * - 否则只重算边界，「下面还有更新的消息」会自己亮起来。
     */
    private suspend fun applyLatestBlock(last: Int?) {
        val current = window.value
        when {
            last == null -> window.value = MessageBlockPaging.onInitial(null, null)
            !current.initialized || current.blocks.isEmpty() -> {
                val first = runCatching { chatRepository.firstBlockIndex(sessionId) }.getOrNull()
                window.value = MessageBlockPaging.onInitial(last, first)
            }
            else -> refreshBlockBoundaries(emptyList())
        }
    }

    private suspend fun initialPrepare() {
        if (chatRepository.isServerMode()) return
        val session = chatRepository.getSession(sessionId) ?: return
        if (session.apiConfigId == null && apiConfigRepository.getAll().isNotEmpty()) {
            ephemeral.update { it.copy(showConfigPicker = true) }
        }
    }

    /**
     * 滚到顶部：加载 `blockIndex - 1` 那一块（规范 §3 Older）。
     *
     * 「同一时刻只加载一块」由 [MessageBlockPaging.beginLoadOlder] 保证：快速滚顶 / 反复触发时
     * 第二次直接返回，不会并发打两条查询、也不会把同一块插两遍。
     */
    fun loadOlder() {
        val (next, started) = MessageBlockPaging.beginLoadOlder(window.value)
        if (!started) return
        window.value = next
        viewModelScope.launch {
            try {
                val oldest = window.value.oldestBlock
                // 服务端模式：先让服务端把「上一块」写进 Room，再走同一套块边界查询。
                if (chatRepository.isServerMode() && oldest != null) {
                    chatRepository.loadServerPage(sessionId, beforeBlock = oldest)
                }
                // 「上一块」不是 oldest - 1：块删空之后编号会留缺口（规范 §1），要查。
                val previous = oldest?.let { chatRepository.blocksAround(sessionId, it).older }
                window.update { MessageBlockPaging.onOlderLoaded(it, previous) }
            } catch (cancel: CancellationException) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = true) }
                throw cancel
            } catch (error: Exception) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = true) }
            }
        }
    }

    /**
     * 滚到底（且不在最新块）：加载 `blockIndex + 1` 那一块（规范 §3 Newer）。
     *
     * 这是 0.12 新增的方向：以前只能「跳回最新」，中间那段要么看不到、要么得整段读进内存。
     * 现在一块一块往下走，读取量与「目标 → 最新」之间隔着多少历史无关。
     */
    fun loadNewer() {
        val (next, started) = MessageBlockPaging.beginLoadNewer(window.value)
        if (!started) return
        window.value = next
        viewModelScope.launch {
            try {
                val newest = window.value.newestBlock
                if (chatRepository.isServerMode() && newest != null) {
                    chatRepository.loadServerPage(sessionId, afterBlock = newest)
                }
                val following = newest?.let { chatRepository.blocksAround(sessionId, it).newer }
                window.update { MessageBlockPaging.onNewerLoaded(it, following) }
            } catch (cancel: CancellationException) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = false) }
                throw cancel
            } catch (error: Exception) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = false) }
            }
        }
    }

    fun onResume() {
        viewModelScope.launch { chatRepository.markRead(sessionId) }
    }

    fun setDraft(value: String) {
        ephemeral.update { it.copy(draft = value.take(32_000)) }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // 审计 P2-12（0.11.1）：落盘与登记必须在同一个不可取消区域里。
            // 以前 `persistChatImage` 已经写完文件、协程在 `ephemeral.update` 之前被取消
            //（选完图立刻按返回 / 切人格），返回的路径从未进入 `images`，`onCleared` 按
            // 已登记集合删除，自然删不到——每张 ≤4MB 纯增量泄漏。
            val added = withContext(NonCancellable) {
                val persisted = uris.take(MAX_IMAGES).mapNotNull { uri ->
                    MediaStorage.persistChatImage(context, uri).getOrNull()
                }
                if (persisted.isEmpty()) {
                    emptyList()
                } else if (draftWindowClosed) {
                    // ViewModel 已经销毁（onCleared 跑过）：文件不该留在磁盘上——
                    // 但仍要先查引用：这张图可能已经发给过某条消息（内容寻址同名文件）。
                    mediaJanitor.deleteAttachmentsIfUnreferenced(persisted)
                    emptyList()
                } else {
                    ephemeral.update { state ->
                        val merged = (state.images + persisted).take(MAX_IMAGES)
                        state.copy(images = merged)
                    }
                    persisted
                }
            }
            if (added.isEmpty()) {
                ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_image_load_failed)) }
            }
        }
    }

    // ---------------------------------------------------------------- 语音 / 视频（0.13）

    /** 按住说话的录音器：整个 ViewModel 一个（同时只可能录一段）。 */
    private val voiceRecorder = VoiceRecorder(context)

    /** 录音计时协程：只更新界面上的「0:03」。 */
    private var recordingJob: Job? = null

    /** 正在录音（界面据此把发送键换成「松开发送」的提示）。 */
    val isRecordingVoice: Boolean get() = voiceRecorder.isRecording

    /**
     * 开始录音（按住语音按钮）。
     *
     * 返回 false 表示开不了（没有麦克风权限 / 麦克风被占用 / 目录写不进去），
     * 界面提示一句即可。权限申请在界面层（Compose 的 RequestPermission），这里只接结果。
     */
    fun startVoiceRecording(): Boolean {
        if (voiceRecorder.isRecording) return true
        if (!voiceRecorder.start()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_voice_record_failed)) }
            return false
        }
        // 到 5 分钟 / 8 MB：系统自己停了，等于「松手」，直接发送这一段。
        voiceRecorder.onAutoStop = {
            viewModelScope.launch { finishVoiceRecording() }
        }
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            while (isActive && voiceRecorder.isRecording) {
                val elapsed = voiceRecorder.elapsedMs()
                ephemeral.update { it.copy(recordingElapsedMs = elapsed) }
                delay(RECORDING_TICK_MS)
            }
        }
        return true
    }

    /**
     * 松手：落盘 + 立刻发送。
     *
     * 太短的误触（< [AudioVideoPolicy.MIN_RECORDING_MS]）直接丢掉，不打扰用户；
     * 落盘失败说一句「没录上」，文件已经在 [VoiceRecorder.stop] 里只剩临时件，由落盘方删掉。
     */
    fun finishVoiceRecording() {
        if (!voiceRecorder.isRecording) return
        recordingJob?.cancel()
        recordingJob = null
        val clip = voiceRecorder.stop()
        ephemeral.update { it.copy(recordingElapsedMs = 0L) }
        if (clip == null) return
        if (clip.durationMs < AudioVideoPolicy.MIN_RECORDING_MS) {
            clip.tempFile.delete()
            return
        }
        viewModelScope.launch {
            // 与选图同一条规矩：落盘 + 登记在不可取消区域里，进程被杀也不会留下无主文件。
            val attachment = withContext(NonCancellable) {
                MediaStorage.persistRecordedAudio(context, clip.tempFile, clip.durationMs).getOrNull()
            }
            if (attachment == null) {
                ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_voice_record_failed)) }
                return@launch
            }
            if (draftWindowClosed) {
                mediaJanitor.deleteAttachmentsLater(listOf(attachment))
                return@launch
            }
            ephemeral.update { it.copy(images = it.images + attachment) }
            // 「松开发送」：录完就直接是一条语音消息，不需要再点一次发送。
            send()
        }
    }

    /** 用户拒绝了录音权限：只说一句，不再反复申请（系统也不再弹了）。 */
    fun onVoicePermissionDenied() {
        ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_voice_permission_denied)) }
    }

    /** 取消录音（滑开按钮 / 离开页面）：文件删掉，什么也不留下。 */
    fun cancelVoiceRecording() {
        recordingJob?.cancel()
        recordingJob = null
        voiceRecorder.cancel()
        ephemeral.update { it.copy(recordingElapsedMs = 0L) }
    }

    /**
     * 从系统选择器选了一段视频（`VideoOnly`）。
     *
     * 规矩（规格原文）：≤2 分钟、≤64 MiB，**不转码**，超了就拒绝并说清楚。
     * 时长先读一遍再复制：一段 10 分钟的视频不必先复制 300 MB 才发现超时。
     */
    fun addVideo(uri: Uri) {
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) {
                VideoFramesExtractor.durationMs(context, uri)
            }
            if (duration != null && duration > AudioVideoPolicy.VIDEO_MAX_DURATION_MS) {
                ephemeral.update {
                    it.copy(snackbar = context.getString(R.string.chat_video_too_long))
                }
                return@launch
            }
            val persisted = withContext(NonCancellable) {
                MediaStorage.persistVideo(context, uri, duration)
            }
            persisted.fold(
                onSuccess = { attachment ->
                    if (draftWindowClosed) {
                        mediaJanitor.deleteAttachmentsLater(listOf(attachment))
                        return@fold
                    }
                    ephemeral.update { it.copy(images = it.images + attachment) }
                },
                onFailure = { error ->
                    ephemeral.update {
                        it.copy(snackbar = ErrorText.of(context, error, R.string.chat_video_pick_failed))
                    }
                }
            )
        }
    }

    /**
     * 发送前的音视频校验：只查**本机真的有文件**的附件。
     *
     * 服务端引用（换过服务端/别的设备发来的）此时本机没有文件，尺寸与时长只能由上传/下载
     * 链路拿真实字节判断——那一条链路上传前也会再校验（[com.yap.app.data.remote.ServerAudioVideoUploader]）。
     */
    private fun mediaSendBlock(attachments: List<MessageAttachment>): MediaSendGuard.Rejection? {
        val candidates = attachments.filter { it.isAudioOrVideo }.mapNotNull { attachment ->
            val kind = attachment.mediaKind ?: return@mapNotNull null
            val file = MediaStorage.voiceVideoFile(context, attachment.uri) ?: return@mapNotNull null
            val duration = attachment.durationMs?.takeIf { it > 0L }
                ?: VideoFramesExtractor.durationMs(file)
            MediaSendGuard.Candidate(kind = kind, sizeBytes = file.length(), durationMs = duration)
        }
        return MediaSendGuard.firstRejection(candidates)
    }

    /** 超限/文件坏掉时的明确提示（文案与选片失败共用同一批资源）。 */
    private fun rejectionText(rejection: MediaSendGuard.Rejection): Int = when {
        MediaSendGuard.isUnreadable(rejection.reason) -> R.string.media_error_unreadable
        rejection.candidate.isAudio && rejection.reason == MediaRejection.TOO_LARGE ->
            R.string.media_error_audio_too_large

        rejection.candidate.isAudio && rejection.reason == MediaRejection.TOO_LONG ->
            R.string.media_error_audio_too_long

        rejection.reason == MediaRejection.TOO_LARGE -> R.string.media_error_video_too_large
        rejection.reason == MediaRejection.TOO_LONG -> R.string.media_error_video_too_long
        else -> R.string.media_error_unreadable
    }

    /** 气泡播放用：把一条语音/视频附件解析成本机可播放文件（服务端引用先缓存）。 */
    suspend fun playableFile(attachment: MessageAttachment): File? =
        audioVideoRepository.localFile(attachment)

    // ---------------------------------------------------------------- 表情包（spec/stickers）

    /**
     * 打开/收起「我的表情」面板。
     *
     * 它和草稿是两件事：点开面板不清空输入框里已经打了一半的字，收起也不把表情塞进草稿。
     */
    fun toggleStickerPanel() {
        ephemeral.update { it.copy(showStickerPanel = !it.showStickerPanel) }
    }

    fun closeStickerPanel() {
        ephemeral.update { it.copy(showStickerPanel = false) }
    }

    fun setStickerShowAll(showAll: Boolean) {
        ephemeral.update { it.copy(stickerShowAll = showAll) }
    }

    /** 相册导入一张图/GIF：走 sticker 自己的压缩管线，成功后刷新面板（Room 会自动推）。 */
    fun importSticker(uri: Uri) {
        viewModelScope.launch {
            val result = stickerRepository.import(uri, personaId = uiState.value.persona?.id)
            if (result.isFailure) {
                ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_sticker_panel_import_failed)) }
            }
        }
    }

    /**
     * 长按删掉一个表情（面板里唯一的长按动作）。
     *
     * 服务端模式下由仓储先 `DELETE /v1/stickers/{id}`，成功后才删本机缓存；失败提示一句
     * 「没删掉」——不能让用户以为删了、下次同步又回来。
     */
    fun deleteSticker(stickerId: String) {
        viewModelScope.launch {
            val result = stickerRepository.delete(stickerId)
            if (result.isFailure) {
                ephemeral.update {
                    it.copy(snackbar = context.getString(R.string.chat_sticker_delete_failed))
                }
            }
        }
    }

    /**
     * 点选一张表情**直接发出去**（规范：点选即发送，不进入文字草稿）。
     *
     * 与服务端模式的发送共用同一套编排（压缩/上传/SSE），区别只是附件是 `kind=STICKER`。
     */
    fun sendSticker(stickerId: String) {
        if (ephemeral.value.isGenerating || operationInFlight) return
        val sticker = uiState.value.stickers.firstOrNull { it.id == stickerId } ?: return
        val current = uiState.value
        if (!chatRepository.isServerMode() && current.config == null) {
            ephemeral.update {
                it.copy(showStickerPanel = false, snackbar = context.getString(R.string.chat_select_config_first))
            }
            return
        }
        val attachment = stickerRepository.attachmentOf(sticker)
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(showStickerPanel = false) }
            sendViaServer("", listOf(attachment))
            return
        }
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                leaveJumpWindowForNewMessage()
                val result = chatRepository.sendUserSticker(sessionId, stickerId)
                if (result.isFailure) {
                    ephemeral.update {
                        it.copy(snackbar = context.getString(R.string.chat_sticker_sending_failed))
                    }
                    return@launch
                }
                // 表情不算「读了几个字」：等他回话前的停顿按最短的来。
                ephemeral.update { it.copy(showStickerPanel = false, snackbar = null) }
                startGeneration(HumanTiming.readDelayMillis(textLength = 0, images = 1, paceFactor = 1f))
            } finally {
                operationInFlight = false
            }
        }
    }

    /** 长按「添加到我的表情」：把这条消息里的表情收进收藏。 */
    fun saveStickerFromMessage(messageId: String) {
        viewModelScope.launch {
            val result = stickerRepository.saveFromMessage(messageId, personaId = uiState.value.persona?.id)
            val text = if (result.isSuccess) {
                R.string.chat_sticker_saved
            } else {
                R.string.chat_sticker_save_failed
            }
            ephemeral.update { it.copy(snackbar = context.getString(text)) }
        }
    }

    /** 渲染用：把某个表情引用解成帧序列（文件或服务端媒体都走这一条）。 */
    suspend fun loadStickerFrames(uri: String): StickerSource? = stickerRepository.loadFrames(uri)

    /**
     * 从草稿里去掉一张图：文件已经落盘了，取消发送就得把它删掉，
     * 否则「选了又删」每来一次就多一个孤儿文件。
     */
    fun removeImage(uri: String) {
        val removed = ephemeral.value.images.filter { it.uri == uri }
        ephemeral.update { state -> state.copy(images = state.images.filterNot { it.uri == uri }) }
        // 0.12：草稿图片也要过引用守卫——同一张图可能已经发出去过（内容寻址 → 同一个文件）。
        viewModelScope.launch { mediaJanitor.deleteAttachmentsIfUnreferenced(removed) }
    }

    fun clearImages() {
        val removed = ephemeral.value.images
        ephemeral.update { it.copy(images = emptyList()) }
        viewModelScope.launch { mediaJanitor.deleteAttachmentsIfUnreferenced(removed) }
    }

    fun dismissSnackbar() {
        ephemeral.update { it.copy(snackbar = null) }
    }

    fun openConfigPicker() {
        ephemeral.update { it.copy(showConfigPicker = true) }
    }

    fun closeConfigPicker() {
        ephemeral.update { it.copy(showConfigPicker = false) }
    }

    fun openPersonaPicker() {
        ephemeral.update { it.copy(showPersonaPicker = true) }
    }

    fun closePersonaPicker() {
        ephemeral.update { it.copy(showPersonaPicker = false) }
    }

    fun send() {
        val text = ephemeral.value.draft
        val images = ephemeral.value.images
        if ((text.isBlank() && images.isEmpty()) || ephemeral.value.isGenerating || operationInFlight) return
        // 0.13：发送前用**现有元数据**再核一次语音/视频上限（选片时查过一次，但文件可能被替换）。
        // 超限就明确提示、不发送；服务端上传前还会再挡一次（`ServerAudioVideoUploader`）。
        mediaSendBlock(images)?.let { rejection ->
            ephemeral.update { it.copy(snackbar = context.getString(rejectionText(rejection))) }
            return
        }
        val current = uiState.value
        // 服务端模式不需要本地 API 配置：模型与密钥都在服务端。
        if (!chatRepository.isServerMode() && current.config == null) {
            ephemeral.update { it.copy(showConfigPicker = true, snackbar = context.getString(R.string.chat_select_config_first)) }
            return
        }
        if (chatRepository.isServerMode()) {
            sendViaServer(text, images)
            return
        }
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                // 审计 P1-1：定位窗口里发送 → 先回到最新一屏，否则自己刚发的那条落在窗口外。
                leaveJumpWindowForNewMessage()
                // 0.15 ASR：只对语音/视频调用；失败/未配置不阻断发送，只是没有转写。
                val enriched = images.map { attachment ->
                    if (attachment.isAudioOrVideo) asrTranscriber.enrich(attachment) else attachment
                }
                // 0.10.2：发送不再等「抽记忆」跑完。抽取是后台任务（读的是历史窗口），
                // 与「写一条用户消息」没有写冲突；以前在这里 join 会让发送键看起来坏掉
                // （最长等一次完整模型往返，期间没有任何 loading）。
                val result = chatRepository.sendUserMessage(sessionId, text, enriched)
                if (result.isFailure) {
                    ephemeral.update {
                        it.copy(snackbar = ErrorText.of(context, result.exceptionOrNull(), R.string.chat_send_failed))
                    }
                    return@launch
                }
                // 0.14：拉黑期间消息已经落库并标了 blocked，但这一轮不启动生成。
                if (chatRepository.isBlocked(sessionId)) {
                    ephemeral.update { it.copy(draft = "", images = emptyList(), snackbar = null) }
                    return@launch
                }
                ephemeral.update { it.copy(draft = "", images = emptyList(), snackbar = null) }
                // 首条消息的「正在输入」延迟由仓库在看到首批字/第一条连发时按真实字数计算；
                // 这里不再额外等一次，否则同一句话会被延迟两遍。
                startGeneration(0L)
            } finally {
                operationInFlight = false
            }
        }
    }

    /**
     * 服务端模式发送：SSE 事件 → 界面增量；消息落库由 ChatRepository/ServerChatGateway 负责。
     *
     * 失败时**草稿留在输入框**（[ephemeral] 的 draft/images 直到 accepted 才清空）：
     * 发送失败最常见的原因是没网，用户刚打的一段话不该被清掉；只有服务端确认 accepted
     * 之后才清空，因为那时服务端已经落库、重发会产生重复（幂等键只对同一 id 有效）。
     */
    private fun sendViaServer(text: String, images: List<MessageAttachment>) {
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                leaveJumpWindowForNewMessage()
                ephemeral.update {
                    it.copy(
                        isGenerating = true,
                        hasStarted = false,
                        streamingText = "",
                        streamingTraces = emptyList(),
                        snackbar = null
                    )
                }
                val enriched = images.map { attachment ->
                    if (attachment.isAudioOrVideo) asrTranscriber.enrich(attachment) else attachment
                }
                val result = chatRepository.sendServerMessage(sessionId, text, enriched) { event ->
                    when (event) {
                        // 0.14.1 §8：服务端确认落库不再等于「开始打字」——输入指示只听 typing 事件。
                        is com.yap.app.data.remote.ServerSendEvent.Accepted -> Unit

                        is com.yap.app.data.remote.ServerSendEvent.Delta ->
                            ephemeral.update { it.copy(streamingText = it.streamingText + event.text) }

                        // 0.14.1 §8：这条完整消息已经由网关**收到即落库**，界面靠 Room 直接上屏；
                        // 不再先塞进暂存气泡，也不在客户端补打字延迟/动画。
                        is com.yap.app.data.remote.ServerSendEvent.MessageCreated -> Unit

                        is com.yap.app.data.remote.ServerSendEvent.TypingStarted ->
                            serverTypingState.typingStarted(sessionId)

                        is com.yap.app.data.remote.ServerSendEvent.TypingStopped ->
                            serverTypingState.typingStopped(sessionId)

                        is com.yap.app.data.remote.ServerSendEvent.Tool ->
                            ephemeral.update {
                                it.copy(
                                    streamingTraces = it.streamingTraces + ToolTraceItem(
                                        name = event.name,
                                        title = event.name,
                                        subtitle = event.summary,
                                        detail = event.summary.orEmpty(),
                                        ok = event.status != "error"
                                    )
                                )
                            }

                        is com.yap.app.data.remote.ServerSendEvent.Done -> Unit
                        is com.yap.app.data.remote.ServerSendEvent.Error -> Unit
                    }
                }
                if (result.accepted) {
                    ephemeral.update { it.copy(draft = "", images = emptyList()) }
                    // M5：图片已经上传到服务端，本机这份压缩后的临时图不再被任何消息引用。
                    // 仍然走引用守卫（内容寻址下同一张图可能被别的本地消息用着）。
                    if (images.isNotEmpty()) {
                        runCatching { mediaJanitor.deleteAttachmentsIfUnreferenced(images) }
                    }
                }
                val error = result.error
                ephemeral.update {
                    it.copy(
                        isGenerating = false,
                        hasStarted = false,
                        streamingText = "",
                        streamingTraces = emptyList(),
                        // accepted 之前的失败才是「没发出去」；accepted 之后流断了只是回复没接上，
                        // 不能显示「发送失败」去误导用户重发（重发会产生第二条，虽然服务端幂等）。
                        snackbar = when {
                            error != null && !result.accepted ->
                                ErrorText.of(context, error, R.string.chat_send_failed)

                            !result.done -> context.getString(R.string.chat_server_reply_pending)
                            else -> null
                        }
                    )
                }
            } catch (cancel: CancellationException) {
                // 用户点了停止：SSE 连接关闭，服务端继续生成并落库；本机下一次同步/事件会拿到。
                serverTypingState.typingStopped(sessionId)
                ephemeral.update { it.copy(isGenerating = false, hasStarted = false, streamingText = "") }
                throw cancel
            } catch (error: Exception) {
                serverTypingState.typingStopped(sessionId)
                ephemeral.update {
                    it.copy(
                        isGenerating = false,
                        hasStarted = false,
                        streamingText = "",
                        streamingTraces = emptyList(),
                        snackbar = ErrorText.of(context, error, R.string.chat_send_failed)
                    )
                }
            } finally {
                // 流结束/失败都收掉指示器：typing.stopped 丢包时也不会一直转圈。
                serverTypingState.typingStopped(sessionId)
                operationInFlight = false
            }
        }
    }

    /**
     * @param readDelay 首个字出现前的「读消息」时间，让回复看起来不是瞬发的。
     */
    private fun startGeneration(readDelay: Long = 0L) {
        streamJob?.cancel()
        ephemeral.update {
            it.copy(
                isGenerating = true,
                // 从按下发送那一刻就显示「正在输入」——读消息的延迟也是生成的一部分。
                // 睡眠/免打扰/已读不回会在 Started 之前发 Silent，界面随即收掉。
                hasStarted = true,
                streamingText = "",
                streamingTraces = emptyList(),
                snackbar = null
            )
        }
        streamJob = viewModelScope.launch {
            try {
                if (readDelay > 0) kotlinx.coroutines.delay(readDelay)
                chatRepository.generateAssistant(sessionId).collect { event ->
                    when (event) {
                        // 新一轮真的开始出字了：上一轮的「他睡着了」提示立刻作废。
                        GenerationEvent.Started -> ephemeral.update {
                            it.copy(hasStarted = true, streamingText = "", silentReason = null)
                        }
                        is GenerationEvent.Delta -> ephemeral.update {
                            it.copy(streamingText = event.fullText, silentReason = null)
                        }
                        // 连发的一条完整消息落库了：收起暂存气泡，等下一段「正在输入」。
                        is GenerationEvent.MessageSent -> ephemeral.update {
                            it.copy(streamingText = "", streamingTraces = emptyList(), hasStarted = true)
                        }
                        is GenerationEvent.ToolCall -> ephemeral.update { it.copy(streamingTraces = event.traces) }
                        is GenerationEvent.Completed -> {
                            ephemeral.update {
                                it.copy(
                                    isGenerating = false,
                                    hasStarted = false,
                                    streamingText = "",
                                    streamingTraces = emptyList(),
                                    // 收到回复 = 他醒了 / 说话了：睡眠提示行随之消失。
                                    silentReason = null
                                )
                            }
                            event.proposal?.let { proposal -> saveProposal(proposal) }
                            if (event.autoMemoryPending) {
                                memoryJob = viewModelScope.launch { extractMemory(silent = false) }
                            }
                        }
                        is GenerationEvent.Failed -> {
                            // 错误气泡里的话可能比结构化错误更具体（例如「这个模型听不了语音」），
                            // 有落库文案就用它，让 snackbar 与气泡说同一句话。
                            val stored = ErrorText.stored(context, event.errorMessage?.errorMessage)
                            ephemeral.update {
                                it.copy(
                                    isGenerating = false,
                                    hasStarted = false,
                                    streamingText = "",
                                    silentReason = null,
                                    snackbar = stored ?: ErrorText.of(context, event.error)
                                )
                            }
                        }
                        GenerationEvent.Stopped -> ephemeral.update {
                            it.copy(isGenerating = false, hasStarted = false, streamingText = "", silentReason = null)
                        }
                        // 睡着 / 免打扰 / 他选择先不回：把「正在输入」收掉。
                        // 用户消息会因 readAt 非空显示「已读」——已读不代表会回。
                        // 原因记在 [ChatEphemeral.silentReason] 里：只有「睡着被静默」会让聊天页
                        // 在输入框上方显示一行提示，免打扰与 defer_reply 都不解释。
                        is GenerationEvent.Silent -> ephemeral.update {
                            it.copy(
                                isGenerating = false,
                                hasStarted = false,
                                streamingText = "",
                                streamingTraces = emptyList(),
                                silentReason = event.reason
                            )
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
            } catch (error: Exception) {
                ephemeral.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        snackbar = ErrorText.of(context, error, R.string.chat_generation_failed)
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        streamJob?.cancel()
        ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
    }

    fun editUserMessage(messageId: String, content: String) {
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
        if (ephemeral.value.isGenerating || operationInFlight) return
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                // 审计 P1-1：定位窗口里编辑重发 → 先回到最新一屏，不然改完的话看不见。
                leaveJumpWindowForNewMessage()
                // 同上：编辑重发也不等后台抽记忆。
                val result = chatRepository.editUserMessage(sessionId, messageId, content)
                if (result.isFailure) {
                    ephemeral.update { it.copy(snackbar = ErrorText.of(context, result.exceptionOrNull(), R.string.chat_edit_failed)) }
                    return@launch
                }
                startGeneration(HumanTiming.REGENERATE_MS)
            } finally {
                operationInFlight = false
            }
        }
    }

    fun deleteMessage(messageId: String) {
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
                .onFailure { error -> ephemeral.update { it.copy(snackbar = ErrorText.of(context, error, R.string.chat_delete_failed)) } }
        }
    }

    fun clearContext() {
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
        viewModelScope.launch {
            // 先停下正在跑的生成并**等它收尾**：仓库层还会用世代号 + 存在性校验兜底，
            // 但界面上不能出现「清空成功之后又冒出一条半截回复」。
            streamJob?.cancelAndJoin()
            ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
            val result = chatRepository.clearContext(sessionId)
            ephemeral.update {
                it.copy(
                    snackbar = result.exceptionOrNull()?.let { ErrorText.of(context, it) }
                        ?: context.getString(R.string.chat_context_cleared)
                )
            }
        }
    }

    fun selectConfig(configId: String) {
        viewModelScope.launch {
            val bound = chatRepository.bindSessionConfig(sessionId, configId)
            if (bound.isFailure) {
                ephemeral.update {
                    it.copy(
                        showConfigPicker = false,
                        snackbar = ErrorText.of(context, bound.exceptionOrNull(), R.string.chat_bind_failed)
                    )
                }
                return@launch
            }
            ephemeral.update {
                it.copy(
                    showConfigPicker = false,
                    snackbar = context.getString(R.string.chat_api_ready)
                )
            }
        }
    }

    fun switchPersona(personaId: String) {
        if (ephemeral.value.switchingPersona) return
        if (personaId == uiState.value.session?.personaId) {
            ephemeral.update { it.copy(showPersonaPicker = false) }
            return
        }
        viewModelScope.launch {
            streamJob?.cancel()
            ephemeral.update { it.copy(switchingPersona = true, isGenerating = false, streamingText = "") }
            val result = chatRepository.switchToPersona(personaId)
            if (result.success && result.session != null) {
                // 切走时草稿里那几张图属于上一段对话：文件留在磁盘上没人会再引用（审计 P2-1）。
                val abandoned = ephemeral.value.images
                ephemeral.update {
                    it.copy(
                        switchingPersona = false,
                        showPersonaPicker = false,
                        images = emptyList(),
                        draft = ""
                    )
                }
                mediaJanitor.deleteAttachmentsIfUnreferenced(abandoned)
                eventsChannel.send(ChatEvent.OpenSession(result.session.id))
            } else {
                ephemeral.update {
                    it.copy(switchingPersona = false, snackbar = switchNoticeText(result.notice))
                }
            }
        }
    }

    fun exportChat() {
        if (ephemeral.value.isExporting) return
        viewModelScope.launch {
            ephemeral.update { it.copy(isExporting = true, snackbar = context.getString(R.string.chat_export_in_progress)) }
            try {
                // 仓库内部切 Dispatchers.IO：读整段会话 + 组 DTO + JSON 编码都不在主线程。
                val result = chatRepository.exportSession(sessionId)
                result.onSuccess { eventsChannel.send(ChatEvent.ExportReady(it)) }
                    .onFailure { error ->
                        ephemeral.update {
                            it.copy(snackbar = ErrorText.of(context, error, R.string.chat_export_failed))
                        }
                    }
            } finally {
                ephemeral.update { it.copy(isExporting = false) }
            }
        }
    }

    private suspend fun saveProposal(proposal: ProactiveProposal) {
        val personaId = uiState.value.session?.personaId ?: return
        val result = proactiveRepository.createFromProposal(personaId, sessionId, proposal)
        result.onSuccess {
            // 不再需要用户批准：他直接安排好了，这里只是告诉他一声。
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_proactive_scheduled)) }
        }
    }

    /** 一次性引导卡片点「知道了」：以后不再显示。 */
    fun dismissBackgroundTip() {
        viewModelScope.launch {
            runCatching { settingsRepository.setBackgroundTipDismissed(true) }
        }
    }

    private suspend fun extractMemory(silent: Boolean) {
        val result = memoryRepository.maybeExtractMemories(sessionId)
        result.onSuccess { extraction ->
            if (extraction.added > 0) {
                ephemeral.update { it.copy(snackbar = memoryResultText(extraction)) }
            }
        }.onFailure { error ->
            if (!silent) {
                val reason = ErrorText.of(context, error, R.string.chat_unknown_error)
                ephemeral.update { state ->
                    state.copy(snackbar = context.getString(R.string.chat_memory_failed, reason))
                }
            }
        }
    }

    fun deleteSession() {
        // 服务端契约 §3.6 没有「删除单条会话」的接口（删人格会连带删会话）。
        // 本地只删缓存会让服务端留下一段再也同步不回来的会话，所以这里明确拒绝，
        // 而不是让用户以为删掉了、下次同步又冒出来。
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
        viewModelScope.launch {
            // 删除前先取消并等生成收尾：NonCancellable 的落库可能排在删除事务之后，
            // 仓库层的存在性/世代校验会丢弃它，这里等一等让顺序确定下来。
            streamJob?.cancelAndJoin()
            ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
            chatRepository.deleteSession(sessionId)
            eventsChannel.send(ChatEvent.SessionDeleted)
        }
    }

    fun markRead() {
        viewModelScope.launch { chatRepository.markRead(sessionId) }
    }

    /** 切人格时顺口提示的那一句：数据层给枚举，这里翻成当前语言。 */
    private fun switchNoticeText(notice: PersonaSwitchNotice?): String = when (notice) {
        PersonaSwitchNotice.API_NOT_BOUND -> context.getString(R.string.chat_persona_switch_api_not_bound)
        PersonaSwitchNotice.SESSION_CREATE_FAILED -> context.getString(R.string.chat_persona_switch_no_session)
        PersonaSwitchNotice.PERSONA_NOT_FOUND -> context.getString(R.string.error_persona_not_found)
        null -> context.getString(R.string.chat_persona_switch_failed)
    }

    /** 「记忆已更新（+2）」这类结果句：数量在数据层，说法在资源里。 */
    private fun memoryResultText(result: com.yap.app.data.repo.MemoryExtractionResult): String =
        when (result.outcome) {
            com.yap.app.data.repo.MemoryExtractionOutcome.ADDED ->
                context.getString(R.string.memory_extract_result_added, result.added)
            com.yap.app.data.repo.MemoryExtractionOutcome.REINFORCED ->
                context.getString(R.string.memory_extract_result_reinforced)
            com.yap.app.data.repo.MemoryExtractionOutcome.ECHOED ->
                context.getString(R.string.memory_extract_result_echoed)
            com.yap.app.data.repo.MemoryExtractionOutcome.NONE ->
                context.getString(R.string.memory_extract_result_none)
            com.yap.app.data.repo.MemoryExtractionOutcome.NOT_ENOUGH_CHAT ->
                context.getString(R.string.memory_extract_result_not_enough)
            com.yap.app.data.repo.MemoryExtractionOutcome.INTERVAL_NOT_REACHED ->
                run {
                    val every = result.every ?: 0
                    context.resources.getQuantityString(
                        R.plurals.memory_extract_result_interval,
                        every,
                        every
                    )
                }
        }

    override fun onCleared() {
        // 审计 P2-12（0.11.1）：先立起「草稿窗口已关」的旗子，再删已登记的图片。
        // `addImages` 的落盘+登记在 NonCancellable 里，看到旗子后会自己删掉刚写的文件，
        // 不会把路径登记进一个已经销毁的 ViewModel。
        draftWindowClosed = true
        streamJob?.cancel()
        memoryJob?.cancel()
        // 正在录的那一段随页面一起丢掉（临时文件由 cancel 删除）。
        runCatching { cancelVoiceRecording() }
        // 审计 P2-1：选了图但没发出去（返回键 / 切人格 / 从通知跳到别的会话）时，
        // 文件已经落在 files/media/chat-*.jpg 了。只有「点 ×」「清空」「发送成功」三条路
        // 会处理它们，退出编辑必须在这里兜一次，否则是纯增量、无上限的磁盘泄漏。
        // 发送成功时 images 已经被清空，所以这里不会删到已发出的图。
        // 0.12：走进程级作用域（viewModelScope 已被取消），引用守卫照样生效。
        mediaJanitor.deleteAttachmentsLater(ephemeral.value.images)
        // compare-and-clear：转场期间新 VM 可能已经打开了下一段会话，旧 VM 不能把它清掉。
        // 无条件 setOpenSession(null) 会让「正在读的会话」被判成没开着：未读 +1 并弹通知。
        chatRepository.clearOpenSession(sessionId)
        super.onCleared()
    }

    private companion object {
        const val MAX_IMAGES = 4

        /** 录音计时的刷新间隔：200ms 够顺滑，又不会每帧都重组。 */
        const val RECORDING_TICK_MS = 200L

        /** 定位消息最多等多久：窗口挪过去通常几十毫秒，留 5 秒足够；超时就当作「那条消息不在了」。 */
        const val JUMP_TIMEOUT_MILLIS = 5_000L
    }

    private data class Details(
        val session: ChatSessionEntity?,
        val messages: List<MessageEntity>,
        val persona: PersonaEntity?,
        val config: ApiConfigEntity?
    )

    private data class DetailsBundle(
        val details: Details,
        val personas: List<PersonaEntity>,
        val apiConfigs: List<ApiConfigEntity>
    )

    /** 聊天页需要的「库里的东西」：会话/人格/配置/设置/表情，合成一次给界面。 */
    private data class ScreenState(
        val bundle: DetailsBundle,
        val settings: AppSettings,
        val stickers: List<com.yap.app.data.local.StickerEntity>
    )
}
