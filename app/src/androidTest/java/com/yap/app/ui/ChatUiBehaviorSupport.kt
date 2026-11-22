package com.yap.app.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.ProviderType
import com.yap.app.data.local.SeedPersonas
import com.yap.app.data.prefs.AppSettings
import com.yap.app.domain.PersonaTranslation
import com.yap.app.data.ai.PersonaTranslationResult
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.MediaJanitor
import com.yap.app.data.repo.StickerRepository
import com.yap.app.data.repo.PersonaTranslationCoordinator
import com.yap.app.data.repo.PersonaTranslationGateway
import com.yap.app.data.repo.MemoryRepository
import com.yap.app.data.repo.MessageBlockWriter
import com.yap.app.data.repo.MoodRepository
import com.yap.app.data.repo.PersonaCardRepository
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PresenceRepository
import com.yap.app.data.repo.ProactiveRepository
import com.yap.app.data.remote.ServerTypingState
import com.yap.app.ui.screens.chat.ChatScreen
import com.yap.app.ui.screens.chat.ChatUiState
import com.yap.app.ui.screens.chat.ChatViewModel
import com.yap.app.ui.theme.YapTheme
import com.yap.app.util.ChatNotifier
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 一条播种好的会话：人格 + 会话 + 按 `m000..mNNN` 命名的消息。
 *
 * 消息正文就是它的 id：Compose 的文本断言要求精确匹配，
 * 用「m000」这种字符串既好读，也不会跟日期分隔条、时间戳撞上。
 */
data class SeededConversation(
    val persona: PersonaEntity,
    val session: ChatSessionEntity,
    val messageIds: List<String>
) {
    val newestId: String get() = messageIds.last()
    val oldestId: String get() = messageIds.first()

    fun idAt(index: Int): String = messageIds[index]
}

/**
 * 聊天页 UI 行为测试的夹具。
 *
 * 为什么是「真依赖图 + 真 Room」而不是假 Repo：
 *
 * - 这些测试要钉的恰恰是**分页契约**（首屏只读最新一块、上/下各一块、定位跳到目标块），
 *   而块窗口是在 `ChatRepository` + `MessageBlockWriter` + 真实 SQLite 上算出来的：
 *   换成内存假实现，等于把要验证的东西替换掉了；
 * - `ChatViewModel` 的构造参数是 11 个具体仓库（没有一个接口），
 *   所以夹具只能从 Hilt 图里拿真货（见 [ChatUiBehaviorSupport] 的用法）。
 *
 * 夹具只对 `ChatScreen` 的输出做空回调：测试不跳转，也不碰导航。
 */
class ChatUiHarness(
    val context: Context,
    private val db: YapDatabase,
    private val blockWriter: MessageBlockWriter,
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
    /** 0.12：删草稿图片前的引用守卫（合成一个真实现，避免测试里手拼假守卫）。 */
    private val mediaJanitor: MediaJanitor,
    /** 0.13 表情包：ChatViewModel 需要它来开历史面板 / 点选发送（UI 测试只用空库）。 */
    private val stickerRepository: StickerRepository,
    /** 0.13 语音/视频：ChatViewModel 用它把附件解析成本机可播放文件（UI 测试不会真的播）。 */
    private val audioVideoRepository: com.yap.app.data.repo.AudioVideoRepository,
    /**
     * 0.12 人格提示词翻译（spec/persona-translation §2）：这里给一个「不翻译」的实现。
     *
     * UI 行为测试要钉的是分页/定位/失败重试这些界面契约；翻译会发网络请求、还会写
     * `translationJson`，把它接进这些测试只会让它们变慢变脆。默认实现让协调器直接
     * 返回 MISSING（零请求），需要测翻译时单独注入。
     */
    private val translationCoordinator: PersonaTranslationCoordinator = offlineTranslationCoordinator(),
    /** 0.14.1：服务端模式的「正在输入」瞬时状态（UI 测试默认没有 typing 事件）。 */
    private val serverTypingState: ServerTypingState = ServerTypingState()
) {

    val personaDao get() = db.personaDao()
    val sessionDao get() = db.chatSessionDao()
    val messageDao get() = db.messageDao()
    val apiConfigDao get() = db.apiConfigDao()
    val database get() = db

    suspend fun seedPersona(name: String = DEFAULT_PERSONA_NAME): PersonaEntity {
        // 种子的文案（名字/简介/提示词/分类）都来自当前语言资源，是「真实人格」的最短路径。
        val template = SeedPersonas.build(context, System.currentTimeMillis()).first()
        val persona = template.copy(id = UUID.randomUUID().toString(), name = name)
        personaDao.insert(persona)
        return persona
    }

    suspend fun seedApiConfig(name: String = DEFAULT_CONFIG_NAME): ApiConfigEntity {
        val now = System.currentTimeMillis()
        // 直接落一行配置：UI 只关心「有没有绑定配置」（标题栏那枚点、发送键是否可点），
        // 密钥留空不影响这条路径，也避免在测试里搬 SecretCipher。
        val config = ApiConfigEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            provider = ProviderType.OPENAI,
            baseUrl = "https://example.invalid/v1",
            apiKeyCipher = null,
            model = "test-model",
            customHeadersJson = "{}",
            allowInsecureHttp = false,
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            frequencyPenalty = null,
            presencePenalty = null,
            createdAt = now,
            updatedAt = now
        )
        apiConfigDao.insert(config)
        return config
    }

    /**
     * 播一段会话：`messageCount` 条消息按 `m000..mNNN` 落库，时间戳是「当天 10:00 + n 秒」。
     *
     * 时间戳特意压在同一个自然日的上午：日期分隔条只会出现一条，
     * 列表下标（`load-earlier` / 分隔条 / 消息）才是可预测的。
     */
    suspend fun seedConversation(
        messageCount: Int,
        withConfig: Boolean = true,
        personaName: String = DEFAULT_PERSONA_NAME
    ): SeededConversation {
        val persona = seedPersona(personaName)
        val config = if (withConfig) seedApiConfig() else null
        val session = chatRepository.createSession(persona.id, config?.id).getOrThrow()

        val baseTime = sameDayBaseTime()
        val ids = (0 until messageCount).map { index -> messageId(index) }
        ids.forEachIndexed { index, id ->
            blockWriter.append(
                MessageEntity(
                    id = id,
                    sessionId = session.id,
                    personaId = persona.id,
                    // 一来一往：分页断言与气泡左右无关，但真话轮次更像真实会话。
                    role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                    content = id,
                    createdAt = baseTime + index * 1_000L,
                    updatedAt = baseTime + index * 1_000L,
                    status = MessageStatus.COMPLETE,
                    errorMessage = null,
                    model = null,
                    edited = false
                )
            )
        }
        return SeededConversation(persona, session, ids)
    }

    /** 往已有会话里追加一条消息（块维护照旧走生产写入器）。 */
    suspend fun appendMessage(session: ChatSessionEntity, id: String, createdAt: Long) {
        blockWriter.append(
            MessageEntity(
                id = id,
                sessionId = session.id,
                personaId = session.personaId,
                role = MessageRole.USER,
                content = id,
                createdAt = createdAt,
                updatedAt = createdAt,
                status = MessageStatus.COMPLETE,
                errorMessage = null,
                model = null,
                edited = false
            )
        )
    }

    /**
     * 造一个挂在测试自有 [ViewModelStore] 上的 ViewModel。
     *
     * 不直接 `ChatViewModel(...)` 而绕一层 store：测试结束 `store.clear()` 会走 `onCleared`，
     * 把 `viewModelScope` 里那些「观察块 / 标记已读」的协程收掉，
     * 免得它们活到下一个测试、在清库之后还去写库。
     */
    fun viewModel(
        store: ViewModelStore,
        sessionId: String,
        focusMessageId: String? = null
    ): ChatViewModel {
        val args = buildMap<String, Any?> {
            put("sessionId", sessionId)
            if (focusMessageId != null) put("messageId", focusMessageId)
        }
        val handle = SavedStateHandle(args)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                savedStateHandle = handle,
                chatRepository = chatRepository,
                personaRepository = personaRepository,
                apiConfigRepository = apiConfigRepository,
                memoryRepository = memoryRepository,
                personaCardRepository = personaCardRepository,
                moodRepository = moodRepository,
                presenceRepository = presenceRepository,
                proactiveRepository = proactiveRepository,
                settingsRepository = settingsRepository,
                chatNotifier = chatNotifier,
                mediaJanitor = mediaJanitor,
                stickerRepository = stickerRepository,
                audioVideoRepository = audioVideoRepository,
                translationCoordinator = translationCoordinator,
                serverTypingState = serverTypingState,
                asrTranscriber = com.yap.app.data.repo.AsrTranscriber(
                    configs = object : com.yap.app.data.repo.AsrConfigSource {
                        override suspend fun availability(): com.yap.app.domain.AsrAvailability =
                            com.yap.app.domain.AsrAvailability.Disabled
                    },
                    media = com.yap.app.data.repo.AsrMediaSource { null },
                    client = com.yap.app.network.AsrClient(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json)
                ),
                context = context
            ) as T
        }
        return ViewModelProvider(store, factory)[ChatViewModel::class.java]
    }

    /** 「清空全部聊天与记忆」（设置页那条入口最终调的就是它）。 */
    suspend fun clearAllChatData() = chatRepository.clearAllChatData()

    /** 「删除全部本地数据」（设置页那条入口最终调的就是它）。 */
    suspend fun wipeAllData() = chatRepository.wipeAllData()

    /** 启动编排里补会话那一步：一人一段，缺的补上。 */
    suspend fun ensureSessionsForAll(): Int = personaRepository.ensureSessionsForAll()

    /** 偏好恢复默认（`deleteAllData` 的最后一步）。 */
    suspend fun resetSettings(preserveSeedMarker: Boolean = true) =
        settingsRepository.resetAll(preserveSeedMarker = preserveSeedMarker)

    companion object {
        /**
         * 测试用的「不翻译」协调器：网关永远说「没有人格」，于是协调器直接返回 MISSING，
         * 不发请求、不写库、不碰设置。
         */
        fun offlineTranslationCoordinator(): PersonaTranslationCoordinator =
            PersonaTranslationCoordinator(
                object : PersonaTranslationGateway {
                    override suspend fun persona(id: String): PersonaEntity? = null

                    override suspend fun writeCache(
                        personaId: String,
                        translationJson: String?,
                        lang: String?
                    ) = Unit

                    override suspend fun autoTranslateEnabled(): Boolean = false

                    override suspend fun translate(
                        persona: PersonaEntity,
                        targetLang: String
                    ): PersonaTranslationResult = PersonaTranslationResult(
                        translation = PersonaTranslation(),
                        ok = false,
                        skipped = true
                    )

                    override fun appLanguage(): String = "en"
                }
            )

        const val DEFAULT_PERSONA_NAME = "测试联系人"
        const val DEFAULT_CONFIG_NAME = "测试配置"

        fun messageId(index: Int): String = "m%03d".format(index)

        /** 当天上午 10 点：450 条消息（每条 +1s）不会跨日，日期分隔条稳定只有一条。 */
        fun sameDayBaseTime(): Long {
            val zone = ZoneId.systemDefault()
            return LocalDate.now(zone).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        }
    }
}

/**
 * 只装主题 + `ChatScreen` 的宿主。
 *
 * `ChatScreen` 是唯一入口（`ChatMessageList` 是 private），所以 UI 断言只能落在
 * 真实页面渲染出来的语义树上：消息正文、入口文案、输入框、失败提示都是真控件。
 */
@Composable
fun ChatUiHost(viewModel: ChatViewModel) {
    YapTheme(AppSettings(animationsEnabled = false)) {
        Surface(Modifier.fillMaxSize()) {
            ChatScreen(
                onBack = {},
                onOpenPersona = {},
                onOpenMemory = {},
                onOpenEvolution = {},
                onOpenApiConfigs = {},
                onOpenProactive = {},
                onSwitchSession = {},
                onOpenSettings = {},
                onOpenLink = {},
                viewModel = viewModel
            )
        }
    }
}

/** 聊天消息列表（LazyColumn）——只有它带 `scrollToIndex`，所以能唯一定位。 */
fun ComposeTestRule.chatList() = onNode(hasScrollToIndexAction())

/** 等 ViewModel 真的读到会话（`loaded`）并满足 [predicate]。 */
fun ComposeTestRule.awaitChatState(
    viewModel: ChatViewModel,
    timeoutMillis: Long = 15_000L,
    predicate: (ChatUiState) -> Boolean
) {
    waitUntil(timeoutMillis) { predicate(viewModel.uiState.value) }
}
