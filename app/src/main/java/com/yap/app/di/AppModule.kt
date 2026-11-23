package com.yap.app.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yap.app.data.local.ApiConfigDao
import com.yap.app.data.local.ApiUsageDao
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MemoryDao
import com.yap.app.data.local.MemoryTermDao
import com.yap.app.data.local.MemoryVectorDao
import com.yap.app.data.local.MemoryVersionDao
import com.yap.app.data.local.MemoryConsolidationDao
import com.yap.app.data.local.MemoryEchoDao
import com.yap.app.data.local.MediaReferenceDao
import com.yap.app.data.local.DiaryNoteDao
import com.yap.app.data.local.StickerDao
import com.yap.app.data.local.MentalActivityDao
import com.yap.app.data.local.SessionMemoryDao
import com.yap.app.data.local.MessageBlockDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.AgentIntentDao
import com.yap.app.data.local.AgentTickDao
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaPendingPatchDao
import com.yap.app.data.local.PersonaPresetDao
import com.yap.app.data.local.PersonaRevisionDao
import com.yap.app.data.local.ProactiveRuleDao
import com.yap.app.BuildConfig
import com.yap.app.core.AgentCoreClient
import com.yap.app.core.AgentCoreClients
import com.yap.app.data.ai.DefaultJsonLlmClient
import com.yap.app.data.ai.JsonLlmClient
import com.yap.app.data.repo.DefaultPersonaTranslationGateway
import com.yap.app.data.repo.MediaSenderUserAvatarPort
import com.yap.app.data.repo.SettingsUserAvatarPort
import com.yap.app.data.repo.PersonaTranslationGateway
import com.yap.app.data.repo.AndroidErasureFiles
import com.yap.app.data.repo.MediaReferenceSource
import com.yap.app.data.repo.RoomErasureStore
import com.yap.app.data.repo.RoomMediaReferenceSource
import com.yap.app.data.repo.RoomServerBootstrapLocal
import com.yap.app.data.repo.RoomServerChatSink
import com.yap.app.data.repo.RoomServerEventSink
import com.yap.app.data.repo.RoomServerStateSink
import com.yap.app.data.repo.PersonaEvolutionRepository
import com.yap.app.data.repo.PersonaEvolutionStore
import com.yap.app.data.repo.PersonaEvolutionTrigger
import com.yap.app.data.repo.RoomPersonaEvolutionStore
import com.yap.app.data.remote.CredentialBackend
import com.yap.app.data.remote.DataStoreServerLocalStore
import com.yap.app.data.remote.DataStoreServerPresenceStore
import com.yap.app.data.remote.ServerApiBootstrapRemote
import com.yap.app.data.remote.ServerApiRemote
import com.yap.app.data.remote.RepositoryServerModeControl
import com.yap.app.data.remote.ServerBootstrapLocal
import com.yap.app.data.remote.ServerBootstrapManager
import com.yap.app.data.remote.ServerBootstrapRemote
import com.yap.app.data.remote.ServerHistoryMediaSender
import com.yap.app.data.remote.ServerModeControl
import com.yap.app.data.remote.ServerModeSwitchCoordinator
import com.yap.app.data.remote.ServerSyncConsent
import com.yap.app.data.remote.ServerChatGateway
import com.yap.app.data.remote.ServerChatMediaSender
import com.yap.app.data.remote.ServerChatSink
import com.yap.app.data.remote.ServerEventNotifier
import com.yap.app.data.remote.ServerEventPoller
import com.yap.app.data.remote.ServerEventSink
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.data.remote.ServerPresenceStore
import com.yap.app.data.remote.UserAvatarCoordinator
import com.yap.app.data.remote.UserAvatarLocalDeleter
import com.yap.app.data.remote.ModeServerRemoteProvider
import com.yap.app.data.remote.ServerRemoteProvider
import com.yap.app.data.remote.ServerStateSink
import com.yap.app.data.remote.ServerModeStateSource
import com.yap.app.data.remote.ServerStateSource
import com.yap.app.data.remote.ServerSyncManager
import com.yap.app.data.remote.SharedPreferencesCredentialBackend
import com.yap.app.data.secure.SecretCipher
import com.yap.app.data.secure.StringCipher
import com.yap.app.util.ServerModeNotifier
import com.yap.app.domain.AppMemoryBudget
import com.yap.app.domain.DataEraser
import com.yap.app.domain.ErasureFiles
import com.yap.app.domain.ErasureStore
import com.yap.app.domain.SessionGenerationRegistry
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.ApiUsageRecorder
import com.yap.app.network.ClaudeAdapter
import com.yap.app.network.GeminiAdapter
import com.yap.app.network.LlmGatekeeper
import com.yap.app.network.OpenAiAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): YapDatabase =
        Room.databaseBuilder(context, YapDatabase::class.java, YapDatabase.NAME)
            .addMigrations(
                YapDatabase.MIGRATION_1_2,
                YapDatabase.MIGRATION_2_3,
                YapDatabase.MIGRATION_3_4,
                YapDatabase.MIGRATION_4_5,
                YapDatabase.MIGRATION_5_6,
                YapDatabase.MIGRATION_6_7,
                YapDatabase.MIGRATION_7_8,
                YapDatabase.MIGRATION_8_9,
                YapDatabase.MIGRATION_9_10,
                YapDatabase.MIGRATION_10_11,
                YapDatabase.MIGRATION_11_12,
                YapDatabase.MIGRATION_12_13,
                YapDatabase.MIGRATION_13_14,
                YapDatabase.MIGRATION_14_15,
                YapDatabase.MIGRATION_15_16,
                YapDatabase.MIGRATION_16_17,
                YapDatabase.MIGRATION_17_18
            )
            // 这里刻意不写 fallbackToDestructiveMigration()。
            // 对他来说，记忆就是「记得」本身：迁移写错时宁可当场崩溃让我们看见，
            // 也不能把用户几年攒下来的对话和记忆悄悄清空——那种损失用户根本无从察觉，
            // 只会觉得「他变了、他忘了我」，而这恰恰是最不能发生的事。
            .build()

    @Provides fun personaDao(db: YapDatabase): PersonaDao = db.personaDao()
    @Provides fun revisionDao(db: YapDatabase): PersonaRevisionDao = db.revisionDao()
    @Provides fun presetDao(db: YapDatabase): PersonaPresetDao = db.presetDao()
    @Provides fun apiConfigDao(db: YapDatabase): ApiConfigDao = db.apiConfigDao()
    @Provides fun chatSessionDao(db: YapDatabase): ChatSessionDao = db.chatSessionDao()
    @Provides fun messageDao(db: YapDatabase): MessageDao = db.messageDao()
    @Provides fun messageBlockDao(db: YapDatabase): MessageBlockDao = db.messageBlockDao()
    @Provides fun memoryDao(db: YapDatabase): MemoryDao = db.memoryDao()
    @Provides fun proactiveRuleDao(db: YapDatabase): ProactiveRuleDao = db.proactiveRuleDao()
    @Provides fun memoryEchoDao(db: YapDatabase): MemoryEchoDao = db.memoryEchoDao()
    @Provides fun memoryConsolidationDao(db: YapDatabase): MemoryConsolidationDao = db.memoryConsolidationDao()
    @Provides fun apiUsageDao(db: YapDatabase): ApiUsageDao = db.apiUsageDao()
    @Provides fun memoryTermDao(db: YapDatabase): MemoryTermDao = db.memoryTermDao()
    @Provides fun memoryVersionDao(db: YapDatabase): MemoryVersionDao = db.memoryVersionDao()
    @Provides fun memoryVectorDao(db: YapDatabase): MemoryVectorDao = db.memoryVectorDao()
    @Provides fun personaPendingPatchDao(db: YapDatabase): PersonaPendingPatchDao = db.personaPendingPatchDao()
    @Provides fun diaryNoteDao(db: YapDatabase): DiaryNoteDao = db.diaryNoteDao()
    @Provides fun stickerDao(db: YapDatabase): StickerDao = db.stickerDao()
    @Provides fun mentalActivityDao(db: YapDatabase): MentalActivityDao = db.mentalActivityDao()
    @Provides fun sessionMemoryDao(db: YapDatabase): SessionMemoryDao = db.sessionMemoryDao()

    /**
     * 表情包的「下载一张公开图片」端口（`sticker` 工具的 `make`）。
     *
     * 接口在 `data/remote`、实现是 [com.yap.app.data.remote.HttpStickerDownloader]（OkHttp + `WebUrlPolicy`）。
     * 抽接口是为了纯 JVM 单测能注入假下载器，生产装配只有这一份实现。
     */
    @Provides
    @Singleton
    fun provideStickerUrlDownloader(impl: com.yap.app.data.remote.HttpStickerDownloader):
        com.yap.app.data.remote.StickerUrlDownloader = impl

    @Provides fun mediaReferenceDao(db: YapDatabase): MediaReferenceDao = db.mediaReferenceDao()

    /**
     * 媒体引用计数的数据来源（0.12）：删文件之前「还有没有人引用」的唯一查询口。
     *
     * 接口在 data 层、实现打 Room + DataStore；抽接口只为了纯 JVM 单测能塞内存假实现
     * （见 `MediaReferenceGcTest`），生产装配没有第二份实现。
     */
    @Provides
    @Singleton
    fun provideMediaReferenceSource(impl: RoomMediaReferenceSource): MediaReferenceSource = impl

    @Provides fun agentIntentDao(db: YapDatabase): AgentIntentDao = db.agentIntentDao()

    @Provides fun agentTickDao(db: YapDatabase): AgentTickDao = db.agentTickDao()

    /**
     * 四条删除路径共用的清理器。
     *
     * [DataEraser] 本身是纯 Kotlin（在 `:agentcore`，两端共用同一份 class），所以不能在它头上写
     * `@Inject`；接口在 domain、实现（Room / Android 文件）在 data 层，这里做绑定。
     */
    @Provides
    @Singleton
    fun provideErasureStore(impl: RoomErasureStore): ErasureStore = impl

    @Provides
    @Singleton
    fun provideErasureFiles(impl: AndroidErasureFiles): ErasureFiles = impl

    @Provides
    @Singleton
    fun provideDataEraser(store: ErasureStore, files: ErasureFiles): DataEraser =
        DataEraser(store, files)

    /** 会话世代号：清空/删除时 bump，历史压缩与生成收尾写库前核对。 */
    @Provides
    @Singleton
    fun provideSessionGeneration(): SessionGenerationRegistry = SessionGenerationRegistry()

    /**
     * agent 决策层：默认进程内；只有构建开关 `AGENT_CORE_REMOTE=true` 时才走 `agentd`。
     *
     * 为什么在这里装配而不是让业务代码自己 new：
     * - 调用点（ChatRepository / ToolExecutor / AgentLoop / 心跳 / 主动判定）只依赖 [AgentCoreClient]，
     *   换实现不用改业务；
     * - 远程实现带了「失败回退进程内 + 记日志」，装配成单例后所有调用点共享同一个连接池与超时策略；
     * - 这里**没有**用户可见开关：远程模式是 `-PagentCoreRemote=true` 的构建配置，release 默认进程内。
     */
    @Provides
    @Singleton
    fun provideAgentCoreClient(okHttp: OkHttpClient): AgentCoreClient = AgentCoreClients.create(
        remoteEnabled = BuildConfig.AGENT_CORE_REMOTE,
        baseUrl = BuildConfig.AGENT_CORE_BASE_URL,
        httpClient = okHttp
    )

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    /**
     * 更新检查（0.14.2）：复用全 App 的 OkHttp（连接池共享），超时在 [UpdateChecker] 内部收紧。
     *
     * 不做成 `@Inject constructor` 是故意的：那是给单测留的「换 endpoint / 换 UA / 换 logger /
     * 换超时」这些构造参数的入口，不让 Hilt 去提供只属于测试的值。
     */
    @Provides
    @Singleton
    fun provideUpdateChecker(okHttp: OkHttpClient): com.yap.app.data.update.UpdateChecker =
        com.yap.app.data.update.UpdateChecker(okHttp)

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 全 App 唯一的 Coil [ImageLoader]。
     *
     * 之前没人提供它，Coil 就用默认值自己造了一个：内存缓存上限是「可用内存的 25%」，
     * 在一个 8GB 的机器上等于几百 MB 的额度——上限不是实际占用，但它意味着系统压力
     * 上来之前，图片缓存愿意替我们攒多少张已经翻过去的图。对「头像 + 聊天图片」这种
     * 场景，收成 `min(可用内存×12%, 48MB)` 更合理，剩下的内存还给系统。
     *
     * 其它几条都是有意为之：
     * - `respectCacheHeaders(false)`：本地文件和相册图片没有有意义的缓存头，
     *   关掉之后同一张头像不会因为服务端没给 `Cache-Control` 就反复解码；
     * - 磁盘 128MB：头像原始文件在 files/ 里，这层只是 Coil 的解码缓存；
     * - **位图缓存不碰**：沿用 Coil 默认的 BitmapPool，避免自己调错复用策略反而更抖。
     *
     * 所有 `AsyncImage` 最终都通过 `YapApplication` 的 `ImageLoaderFactory`
     * 拿到这一个实例（见 `YapApplication.newImageLoader`）。
     */
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val availableMemoryBytes = activityManager?.let { manager ->
            ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.availMem
        } ?: 0L
        // 0 会让 Coil 的 MemoryCache 计算出一个无效上限，这里至少给 1 字节（等于不缓存）。
        // Coil 的 MemoryCache 上限参数是 Int，48MB 远在 Int 范围内。
        val memoryCacheBytes = AppMemoryBudget.imageMemoryCacheBytes(availableMemoryBytes)
            .coerceAtLeast(1L)
            .toInt()
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(AppMemoryBudget.IMAGE_DISK_CACHE_DIR))
                    .maxSizeBytes(AppMemoryBudget.IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    /**
     * OpenAI 系 adapter 显式拿到记账器与并发闸：两者都不是可选项，
     * 所以这里直接塞进构造参数，而不是让 adapter 去某个静态句柄上「碰运气」。
     */
    @Provides
    @Singleton
    fun provideOpenAiAdapter(
        client: OkHttpClient,
        json: Json,
        recorder: ApiUsageRecorder,
        gatekeeper: LlmGatekeeper
    ): OpenAiAdapter = OpenAiAdapter(client, json, recorder, gatekeeper)

    @Provides
    @Singleton
    fun provideGeminiAdapter(): GeminiAdapter = GeminiAdapter()

    @Provides
    @Singleton
    fun provideClaudeAdapter(): ClaudeAdapter = ClaudeAdapter()

    @Provides
    @Singleton
    fun provideRegistry(openAi: OpenAiAdapter, gemini: GeminiAdapter, claude: ClaudeAdapter): AiProviderRegistry =
        AiProviderRegistry(openAi, gemini, claude)

    /**
     * 话风/世界补全用的一次性 JSON 调用。
     *
     * 接口只暴露「给我一段 JSON」，生产实现复用现有 API 配置与 Provider 适配器；
     * 单测里注入假实现就能把各种坏输出跑一遍（不需要网络、不需要数据库）。
     */
    @Provides
    @Singleton
    fun provideJsonLlmClient(impl: DefaultJsonLlmClient): JsonLlmClient = impl

    /**
     * 「按需翻译人设」的依赖面（`spec/persona-translation/README.md` §2）。
     *
     * 绑定成接口而不是直接用实现：协调器的去重/缓存规则必须在纯 JVM 单测里可跑，
     * 而生产实现背后是 Room + DataStore。
     */
    @Provides
    @Singleton
    fun providePersonaTranslationGateway(
        impl: DefaultPersonaTranslationGateway
    ): PersonaTranslationGateway = impl

    // ---------------------------------------------------------------- 服务端模式（M3/M4）

    /**
     * 服务端地址/令牌复用 API Key 的 Keystore AES-GCM 方案。
     *
     * 抽 [StringCipher] 接口只为了纯 JVM 单测：Keystore 在单测里不存在，但「地址/令牌
     * 是不是加密存的、解不开时怎么回退」必须能测。
     */
    @Provides
    @Singleton
    fun provideStringCipher(impl: SecretCipher): StringCipher = impl

    @Provides
    @Singleton
    fun provideCredentialBackend(impl: SharedPreferencesCredentialBackend): CredentialBackend = impl

    @Provides
    @Singleton
    fun provideServerLocalStore(impl: DataStoreServerLocalStore): ServerLocalStore = impl

    /**
     * 写路径的依赖面（M5）：`ServerWriteGateway` 只关心「能不能用 + 拿客户端」，
     * 抽接口是为了让纯 JVM 单测能用假实现覆盖成功/失败/404 语义。
     */
    @Provides
    @Singleton
    fun provideServerApiProvider(mode: com.yap.app.data.remote.ServerModeRepository):
        com.yap.app.data.remote.ServerApiProvider = mode

    /** 表情/日记拉取要按本机人格找服务端 id：生产实现复用写网关的映射。 */
    @Provides
    @Singleton
    fun provideServerPersonaIdMapper(
        gateway: com.yap.app.data.remote.ServerWriteGateway
    ): com.yap.app.data.remote.ServerPersonaIdMapper = gateway

    /** 表情/日记拉取要知道本机有哪些人格（生产实现只读 `PersonaDao`）。 */
    @Provides
    @Singleton
    fun provideServerCatalogPersonas(
        dao: com.yap.app.data.local.PersonaDao
    ): com.yap.app.data.remote.ServerCatalogPersonas =
        object : com.yap.app.data.remote.ServerCatalogPersonas {
            override suspend fun localPersonaIds(): List<String> =
                dao.getAll().map { it.id }
        }

    /** 表情/日记拉取时的字节落盘：复用 `ServerMediaStore` 的幂等/原子写入。 */
    @Provides
    @Singleton
    fun provideServerCatalogMedia(
        store: com.yap.app.data.remote.ServerMediaStore
    ): com.yap.app.data.remote.ServerCatalogMedia =
        com.yap.app.data.remote.ServerStickerMedia(store)

    @Provides
    @Singleton
    fun provideServerPresenceStore(impl: DataStoreServerPresenceStore): ServerPresenceStore = impl

    @Provides
    @Singleton
    fun provideServerStateSink(impl: RoomServerStateSink): ServerStateSink = impl

    @Provides
    @Singleton
    fun provideServerStateSource(impl: ServerModeStateSource): ServerStateSource = impl

    @Provides
    @Singleton
    fun provideServerChatSink(impl: RoomServerChatSink): ServerChatSink = impl

    @Provides
    @Singleton
    fun provideServerEventSink(impl: RoomServerEventSink): ServerEventSink = impl

    @Provides
    @Singleton
    fun provideServerEventNotifier(impl: ServerModeNotifier): ServerEventNotifier = impl

    /**
     * 「此刻可用的远程客户端」不是单例：设置页换地址后要立刻换。
     * 下游（同步/发送/事件）都拿这个 lambda，每次调用现向 [ServerModeRepository] 要。
     */
    @Provides
    @Singleton
    fun provideServerRemoteProvider(mode: ServerModeRepository): ServerRemoteProvider =
        ModeServerRemoteProvider(mode)

    @Provides
    @Singleton
    fun provideServerSyncManager(
        source: ServerStateSource,
        sink: ServerStateSink,
        localStore: ServerLocalStore
    ): ServerSyncManager = ServerSyncManager(source, sink, localStore)

    @Provides
    @Singleton
    fun provideServerChatGateway(
        remoteProvider: ServerRemoteProvider,
        localStore: ServerLocalStore,
        sink: ServerChatSink
    ): ServerChatGateway = ServerChatGateway(remoteProvider, localStore, sink)

    /**
     * 0.12.1：一次性 bootstrap（四象限全量搬运）。
     *
     * 三个依赖各自可替换：本地实现走 Room、远端实现每次现取 [ServerApi]、头像上传复用
     * 人格头像那条「压缩 → 上传 → 规范化引用」链路。编排逻辑本身在纯 JVM 单测里用假实现覆盖。
     */
    /**
     * 「我的头像」上传链路（0.12.1）：压缩→上传 USER_AVATAR→写设置→镜像；失败回滚旧头像。
     *
     * 装配成单例：端口自己无状态，失败回滚的语义只取决于传入的旧值。
     */
    @Provides
    @Singleton
    fun provideUserAvatarCoordinator(
        settings: SettingsUserAvatarPort,
        uploader: MediaSenderUserAvatarPort,
        mode: ServerModeRepository,
        mediaJanitor: com.yap.app.data.repo.MediaJanitor
    ): UserAvatarCoordinator = UserAvatarCoordinator(
        settings = settings,
        uploader = uploader,
        isServerMode = { mode.isEnabled() },
        deleter = UserAvatarLocalDeleter { uri -> mediaJanitor.deleteIfUnreferenced(uri) }
    )

    @Provides
    @Singleton
    fun provideServerBootstrapLocal(impl: RoomServerBootstrapLocal): ServerBootstrapLocal = impl

    @Provides
    @Singleton
    fun provideServerBootstrapRemote(mode: ServerModeRepository): ServerBootstrapRemote =
        ServerApiBootstrapRemote(mode)

    @Provides
    @Singleton
    fun provideServerBootstrapManager(
        local: ServerBootstrapLocal,
        remote: ServerBootstrapRemote,
        localStore: ServerLocalStore,
        mediaSender: ServerChatMediaSender,
        historyMedia: ServerHistoryMediaSender
    ): ServerBootstrapManager = ServerBootstrapManager(
        local = local,
        remote = remote,
        store = localStore,
        // 0.13：没有同意记录（或旧版本遗留配置）一律按「只同步之后的新内容」处理，
        // 任何自动路径都不能替用户把历史传上云。
        consentFor = { baseUrl ->
            localStore.syncConsent(baseUrl) ?: ServerSyncConsent.NEW_ONLY
        },
        historyMedia = historyMedia,
        uploadAvatar = { uri, personaId ->
            mediaSender.uploadPersonaAvatar(uri, personaId).getOrThrow()
        },
        uploadUserAvatar = { uri ->
            mediaSender.uploadUserAvatar(uri).getOrThrow()
        }
    )

    /**
     * 0.13 服务端开关的状态机（同意弹窗 / 历史媒体同步 / 切回本地全量下载与条数校验）。
     *
     * 三个依赖都可替换：仓库只提供「开关 + 地址」，本地存储记同意，runner 复用同一轮 bootstrap。
     */
    @Provides
    @Singleton
    fun provideServerModeControl(mode: com.yap.app.data.remote.ServerModeRepository): ServerModeControl =
        RepositoryServerModeControl(mode)

    @Provides
    @Singleton
    fun provideServerModeSwitchCoordinator(
        mode: ServerModeControl,
        localStore: ServerLocalStore,
        bootstrap: ServerBootstrapManager,
        llmConfig: com.yap.app.data.remote.ServerLlmConfigPusher
    ): ServerModeSwitchCoordinator = ServerModeSwitchCoordinator(
        mode = mode,
        store = localStore,
        runner = bootstrap,
        llmConfig = llmConfig
    )

    @Provides
    @Singleton
    fun provideServerEventPoller(
        remoteProvider: ServerRemoteProvider,
        localStore: ServerLocalStore,
        sink: ServerEventSink,
        notifier: ServerEventNotifier,
        /** 0.14.1：typing 事件只更新瞬时输入指示，不落库（§8）。 */
        typing: com.yap.app.data.remote.ServerTypingState
    ): ServerEventPoller = ServerEventPoller(remoteProvider, localStore, sink, notifier, typing)

    /**
     * 人格进化「接受 / 拒绝」的本地读写端口（0.13）。
     *
     * 抽接口只为纯 JVM 单测：真实实现是 Room，测试用内存假实现钉住
     * 「服务端失败时待审项还在」「本地模式行为不变」。
     */
    @Provides
    @Singleton
    fun providePersonaEvolutionStore(impl: RoomPersonaEvolutionStore): PersonaEvolutionStore = impl

    /**
     * 本地模式与服务端模式共用的「触发一轮进化评估」入口。
     *
     * 两种模式的触发点（发完用户消息 / 回复 done）都拿它，实现是同一个仓库实例，
     * 因此共享同一个游标与单飞锁——切换模式不会重复评估。
     */
    @Provides
    @Singleton
    fun providePersonaEvolutionTrigger(repo: PersonaEvolutionRepository): PersonaEvolutionTrigger = repo
}
