import Foundation

/// 应用装配：把 YapKit 里的仓储、服务、客户端接到一起。
///
/// 为什么集中在一个 `@MainActor final class` 里而不是散在各个 View：
/// - 依赖图是有顺序的（数据库 → 仓储 → 服务 → 聊天），散着装配最容易出现「某个 View 里 new 了一个
///   独立的 MemoryRepository」，于是两个实例各自缓存、行为不一致；
/// - 只有一处能拿到 API Key（Keychain），也只有一处知道「现在是哪个 API 配置」；
/// - 测试与预览可以直接注入假的 `ChatService`。
///
/// 这个文件只做装配，不写业务逻辑：任何「怎么回一句话」「怎么挑记忆」都在 YapKit 里。
@MainActor
final class AppEnvironment: ObservableObject {
    /// 见文件末尾 `AppEnvironmentBox` 注释。
    private let envBox = AppEnvironmentBox()

    // MARK: - 存储

    let stores: DatabaseStores
    let settings: SettingsStore
    let apiKeys: ApiKeyStore

    // MARK: - 网络与记账

    let webClient: OpenAICompatibleClient
    let usageRecorder: UsageRecorder
    /// 全局 LLM 并发闸（后台 2 + 全局 3，聊天保底 1）。界面不需要它，
    /// 但装配层持有它才有地方接超时日志。
    let gate: LlmConcurrencyGate
    /// 他说不说话的那些节奏（被叫醒、`defer_reply`）。
    let rhythm: ChatRhythmService
    /// agent 决策层（0.11.0）：睡眠判定 / defer 计划 / 主动上限 / 工具参数夹取。
    ///
    /// 装配层只造**一个**实例再分发：远程模式下它持有一条 `URLSession`，
    /// 每个服务各造一份会让超时与连接各自独立、联调日志也散掉。
    /// 选择顺序见 `AgentCoreClientFactory`（环境变量 → Info.plist → 本机）；
    /// 默认本机实现，零网络请求。
    let agentCore: any AgentCoreClient

    // MARK: - 服务

    let memoryRepository: MemoryRepository
    let vectorIndex: MemoryVectorIndex
    let moodRepository: MoodRepository
    let tickStore: AgentTickStore
    let intents: AgentIntentSupport
    let presence: PresenceRepository
    let extraction: MemoryExtraction
    let consolidator: MemoryConsolidator
    let recall: RecallService
    let agentLoop: AgentLoop
    let heartbeat: HeartbeatController
    let backup: BackupService
    /// 更新渠道（0.14.2）：设置页的「检查更新」、前台静默检查与新版本弹窗共用这一份状态。
    ///
    /// 不放进 `YapKit`：它要跟着 `scenePhase` 走（SwiftUI 才知道前台/后台），
    /// 而「问哪一版是最新」的逻辑本身已经在 Kit 的 `UpdateChecker` 里。
    let updates: UpdateCenter
    let dataReset: DataReset
    let chat: ChatService
    /// 人设卡：结构化编辑 + `update_persona` 工具 + 提示词渲染都走它。
    let personaCards: PersonaCardRepository
    /// 联网工具（DeepSeek Search / Fetch）。`web_search` / `web_fetch` 用得到。
    let webTools: WebToolsClient
    /// 聊天侧工具执行器（remember / recall / set_mood / set_quiet_hours / update_persona / web_*）。
    let toolExecutor: ToolExecutor
    /// 主动唤醒规则落库（回复里的 `yap-wake` 提案）。
    let proactiveRules: ProactiveRuleService
    /// 表情包（0.13）：导入 / 制作 / 发送 / 收藏。本地模式落 `Application Support/stickers`，
    /// 服务端模式上传成 `/v1/media/<id>`（`kind=STICKER`）。
    let stickers: StickerService
    /// 服务端表情/日记的拉取缓存与写路径（0.13）：服务端模式下他随身带的东西在服务端。
    let serverCatalog: ServerCatalogService?
    /// 历史压缩（滚动摘要）的落库编排。
    let historyCompression: HistoryCompressionService
    /// 人格进化（0.13）：人格页「他最近的变化」读它、接受/拒绝也调它
    ///（`docs/design/persona-evolution.md` §2）。
    let personaEvolution: PersonaEvolutionService
    /// 自动情绪判定（schema v17，`spec/mood/README.md` §2）。
    let moodJudgement: MoodJudgementService
    /// 拉黑（0.14，`spec/experience/README.md` §2）：工具提议 / 两轮确认 / 到期解除。
    let blockRepository: BlockRepository
    /// 会话备忘录（0.14，`spec/experience/README.md` §6）。
    let sessionMemory: SessionMemoryService
    /// 心理活动（0.14，`spec/experience/README.md` §3）。
    let mentalActivity: MentalActivityService
    /// 回复落库之后那两件后台事（抽记忆 / 压摘要）。
    let chatBackground: ChatBackgroundService
    /// 后台调用用的模型配置（会话上填的模型优先，其次当前生效的 API 配置）。
    ///
    /// 抽记忆 / 压摘要 / 人格进化三条后台链路共用同一个解析：它们都是「用一次额外的
    /// 模型调用从对话里提炼东西」，配置口径必须一致，否则换个入口就换个模型。
    private let backgroundConfig: (ChatSessionEntity) -> CompressionConfig?

    /// 设置变了要通知界面重绘；View 用 `@EnvironmentObject` 读它。
    @Published private(set) var currentSettings: AppSettings

    // MARK: - 服务端模式（M3/M4）
    //
    // 装配层只做三件事：读配置、造三件套（同步 / 事件 / 聊天）、把它们接到生命周期上。
    // 真正的逻辑都在 YapKit（`ServerSyncService` / `ServerEventService` /
    // `ServerChatGateway`），这里不复制任何一条业务规则。

    /// 服务端模式的设置来源（令牌在 Keychain，开关/地址在 UserDefaults）。
    let serverCredentials: ServerCredentialStore
    /// 「切换云端时的同步同意」记录（按 baseURL，值 FULL / NEW_ONLY）。
    let serverConsent: ServerSyncConsentStore
    /// 当前生效的服务端配置（设置页改完会重建）。
    @Published private(set) var serverConfig: ServerConfig
    /// 服务端模式是不是真的可用（开关 + 合法地址）。界面用它决定走哪条链路。
    @Published private(set) var serverModeActive: Bool
    /// 服务端下发的心情/在场（M4：这两种东西在服务端模式下是「下发」的，不是本机推导的）。
    @Published private(set) var serverLiveState: ServerLiveState

    /// 当前生效的传输层客户端（地址/令牌一变就整体重建）。
    ///
    /// 0.13 第二阶段新增：云端情绪/进化/配置推送都要「同一个客户端」，
    /// 现造会每次 new 一个 `URLSession`（连接池、Cookie、取消语义各自一份）。
    private(set) var serverClient: ServerAPIClient?
    private(set) var serverSync: ServerSyncService?
    private(set) var serverEvents: ServerEventService?
    private(set) var serverChat: ServerChatGateway?
    /// 服务端写路径（M5：人格/记忆/预设/设置/备份）。服务端模式没起来时为 nil。
    private(set) var serverWrites: ServerWriteService?
    /// 首次连上某台服务端时的双向全量同步（0.12.1；每个 baseURL 只跑一次）。
    private(set) var serverBootstrap: ServerBootstrapService?
    /// 我的头像上传（`USER_AVATAR`）。服务端模式没起来时为 nil。
    private(set) var avatarUploader: ServerUserAvatarUploader?
    /// 服务端媒体的「落地」同步（0.13 第二阶段）：`/v1/media/<id>` → 本机持久目录。
    /// 服务端模式没起来时为 nil。
    private(set) var serverMediaSync: ServerMediaSyncService?
    /// 上一次把本机模型配置推给服务端的结果（只用于日志/设置页的调试信息，不是错误）。
    private(set) var serverLlmConfigPushMessage: String?
    /// 媒体落地同步正在跑（避免每次进聊天页都并发一批下载）。
    private var isMediaSyncRunning = false
    /// 上次表情/日记缓存刷新的时间（进程内节流，不落库）。
    private var lastServerCatalogSyncAt: Int64 = 0
    /// 首次同步的进度（设置页显示成一行；跑完/失败后清空）。
    @Published private(set) var serverBootstrapProgress: ServerBootstrapProgress?
    /// 首次同步失败的原因（设置页一条提示；重试成功后清掉）。
    @Published private(set) var serverBootstrapError: String?
    /// 服务端推送回来的数据版本：bootstrap 拉完 / 每次增量同步写库后 +1。
    /// 首页与聊天页用它触发一次重载（不用等各自的轮询拍）。
    @Published private(set) var serverDataVersion = 0
    /// 首次同步正在跑（避免两次并发；`activateServerMode` 会被冷启动 + 回前台各调一次）。
    private var isBootstrapping = false
    /// 上一次「轻量增量同步」的时间（5 分钟节流用）。
    private var lastServerSyncAttemptAt: Int64 = 0
    /// 前台每 5 分钟一次的轻量增量同步。
    private var serverSyncPulseTask: Task<Void, Never>?
    /// 服务端写失败要显示的那句话（设置页顶部一条提示；成功保存会清掉）。
    @Published private(set) var serverWriteError: String?
    /// 设置推送串行化：连点开关时只让最后一次生效（避免旧请求把新值盖回去）。
    private var settingsPushTask: Task<Void, Never>?
    /// 前台事件长轮询任务（进后台就取消）。
    private var serverEventTask: Task<Void, Never>?
    /// 「App 在前台吗」的线程安全镜像。
    ///
    /// 为什么不用 `isForeground` 那个 `private(set) var`：它属于 `@MainActor`，
    /// 而服务端事件流在**后台线程**上问这一句（决定要不要弹通知）。跨隔离域读一个
    /// 可变属性是数据竞争，所以这里单独一份加锁的布尔值，主线程写、任意线程读。
    private let serverForeground = ServerForegroundFlag()

    // MARK: - 前台心跳与补算的节流状态（0.10.2 审计 P1-2 / P1-3）

    /// 「补算」的重入保护：冷启动 `.task` 与回前台 `onChange` 会同时命中同一次启动，
    /// 没有它就会跑两遍（两次心跳 + 两次每日整理 + 两条主动消息）。
    private let catchUpGuard = CatchUpGuard()
    /// 每日整理同理：BGProcessingTask 与回前台补算可能撞上。
    private var isConsolidating = false
    /// 前台 30 秒一拍的定时器（只在 App 前台时存在）。
    private var foregroundPulseTask: Task<Void, Never>?
    /// 当前是不是前台（`scenePhase` 的镜像；定时器循环里用它再确认一次）。
    private(set) var isForeground = false

    /// 启动失败时留给根视图显示（数据库打不开、没有可写目录……）。
    let bootError: String?

    // MARK: - 构造

    init() {
        // 装配期就要在闭包里问「此刻是不是服务端模式」：局部别名在 init 前半段可读
        //（`envBox.env` 在装配完成后（见 init 末尾）才赋值，闭包真正调用时已经就绪）。
        let envBoxRef = envBox
        var bootError: String?
        var stores: DatabaseStores?
        do {
            stores = try DatabaseStores.open(path: Self.databasePath())
        } catch {
            bootError = Lf("打不开本地数据库：%@", error.localizedDescription)
        }

        // 数据库都打不开时，整个 App 仍然要能显示一句话而不是闪退。
        // 这些「空壳」对象只是为了让属性初始化器有值；`bootError != nil` 时根视图不会用它们。
        let fallbackStores = stores ?? (try! DatabaseStores.open(path: ":memory:"))
        self.stores = fallbackStores
        self.bootError = bootError

        let settings = SettingsStore()
        self.settings = settings
        // 未签名的模拟器构建拿不到 Keychain entitlement；只有「DEBUG + 模拟器」才允许
        // 把 Key 临时放进 UserDefaults 调通链路。真机 / Release 上写失败会抛错
        //（`KeychainFallbackPolicy`，见 0.10.2 审计 P1-5）。
        let apiKeys = ApiKeyStore(backend: ResilientKeychain())
        self.apiKeys = apiKeys
        // agent 决策层：默认本机（零网络）。要让 App 走远程，用环境变量/Info.plist 选择，
        // 例如 `SIMCTL_CHILD_YAP_AGENT_CORE=remote` + `..._URL=http://127.0.0.1:8788`。
        let agentCore = AgentCoreClientFactory.make(log: { DebugSeeding.log($0) })
        self.agentCore = agentCore
        let loadedSettings = settings.load()
        self.currentSettings = loadedSettings

        let recorder = UsageRecorder(stores: fallbackStores)
        self.usageRecorder = recorder

        // 1.9：预算闸、额度、拦截全部删除（§五）。请求前唯一要过的关是并发闸。
        let gate = LlmConcurrencyGate.shared
        let webClient = OpenAICompatibleClient(
            session: URLSession(configuration: Self.sessionConfiguration()),
            usage: recorder,
            gate: gate
        )
        self.gate = gate
        self.webClient = webClient

        let vectorIndex = MemoryVectorIndex(stores: fallbackStores)
        let memoryRepository = MemoryRepository(
            stores: fallbackStores,
            vectorIndex: vectorIndex
        )
        let moodRepository = MoodRepository(stores: fallbackStores)
        // 0.14 拉黑（`spec/experience/README.md` §2）：提议存在 `personas.blockProposalJson`
        //（Android 18.json 的同一列），跨进程也能确认（10 分钟内）。
        let blockRepository = BlockRepository(stores: fallbackStores)
        self.blockRepository = blockRepository
        let tickStore = AgentTickStore(database: fallbackStores.database)
        let intents = AgentIntentSupport(stores: fallbackStores, ticks: tickStore)
        let presence = PresenceRepository(stores: fallbackStores, ticks: tickStore, intents: intents)
        // 适配器只负责搬参数/翻错误；Key 与记账都在 `OpenAICompatibleClient` 那一侧。
        let completion = CompletionClientAdapter(client: webClient)

        self.vectorIndex = vectorIndex
        self.memoryRepository = memoryRepository
        self.moodRepository = moodRepository
        self.tickStore = tickStore
        self.intents = intents
        self.presence = presence

        let extraction = MemoryExtraction(
            stores: fallbackStores,
            memoryRepository: memoryRepository,
            completion: completion,
            // 写回被并发抢走时只进日志（后台任务失败不该弹错给用户）。
            log: { DebugSeeding.log($0) }
        )
        let consolidator = MemoryConsolidator(
            stores: fallbackStores,
            memoryRepository: memoryRepository,
            completion: completion
        )
        let recall = RecallService(
            stores: fallbackStores,
            memoryRepository: memoryRepository
        )
        self.extraction = extraction
        self.consolidator = consolidator
        self.recall = recall

        let rhythm = ChatRhythmService(
            stores: fallbackStores,
            mood: moodRepository,
            ticks: tickStore,
            intents: intents
        )
        self.rhythm = rhythm

        // 0.14：先备好「怎么把一段会话解析成一次后台请求」，摘要 / 心理活动 / 历史压缩共用。
        let resolveCompressionConfig: (ChatSessionEntity) -> CompressionConfig? = { session in
            guard let resolved = AppEnvironment.resolveConfig(
                stores: fallbackStores, settings: settings, apiKeys: apiKeys
            ) else { return nil }
            let model = session.model.trimmingCharacters(in: .whitespacesAndNewlines)
            return CompressionConfig(
                config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
                model: model.isEmpty ? resolved.model : model
            )
        }
        // 0.14 会话备忘录（每 5 / 30 条用户消息）与心理活动（每 3 分钟 / 3 轮）。
        // 两者都用同一个 completion 与并发闸；失败只落日志、不影响回复。
        let sessionMemory = SessionMemoryService(
            stores: fallbackStores,
            summarizer: LocalSessionSummarizer(
                completion: completion,
                resolveConfig: { session in session.flatMap(resolveCompressionConfig) },
                log: { DebugSeeding.log($0) }
            ),
            log: { DebugSeeding.log($0) }
        )
        self.sessionMemory = sessionMemory
        let mentalActivity = MentalActivityService(
            stores: fallbackStores,
            mood: moodRepository,
            generator: LocalMentalThoughtGenerator(
                completion: completion,
                resolveConfig: { session in
                    guard let session else { return nil }
                    return resolveCompressionConfig(session)
                },
                log: { DebugSeeding.log($0) }
            ),
            log: { DebugSeeding.log($0) }
        )
        self.mentalActivity = mentalActivity

        // `AgentLoop` 的「主动消息送达」回调要问一句「这段会话现在开着吗」，
        // 但 `chat` 是在它之后才装配好的。用一个极小的引用盒子把这层先后顺序接起来
        //（比给 AgentLoop 开一个可变属性更不容易踩到并发语义）。
        let chatBox = ChatServiceBox()
        let agentLoop = AgentLoop(
            stores: fallbackStores,
            settings: settings,
            completion: completion,
            memoryRepository: memoryRepository,
            intents: intents,
            ticks: tickStore,
            presence: presence,
            mood: moodRepository,
            agentCore: agentCore,
            // 他自己想改人设时（`persona_patch` 动作）真的写进人设卡；iOS 没有 pending 表，直接生效。
            personaPatchApplier: { personaId, patchJson, reason in
                switch PersonaCardRepository(stores: fallbackStores)
                    .applyPatch(personaId: personaId, patchJson: patchJson, reason: reason) {
                case .success(let changed):
                    DebugSeeding.log("[agent] persona_patch changed=\(changed)")
                    return changed
                case .failure(let error):
                    DebugSeeding.log("[agent] persona_patch 失败：\(error.localizedDescription)")
                    return false
                }
            },
            // 他主动说了一句话：不在看的会话弹一条「不露正文」的本地通知（点开进对应聊天）。
            onMessageDelivered: { message, session in
                Task { @MainActor in
                    guard let persona = (try? fallbackStores.personas.byId(message.personaId)) ?? nil else { return }
                    AppNotifications.shared.notifyMessage(
                        personaName: persona.name,
                        personaId: persona.id,
                        sessionId: session.id,
                        isSessionOpen: chatBox.chat?.isSessionOpen(session.id) ?? false
                    )
                }
            },
            // 服务端模式：他在服务端那边独立运行，本机不启动自主决策（连一拍都不跑）。
            isServerMode: { envBoxRef.env?.serverModeActive ?? false }
        )
        self.agentLoop = agentLoop

        let heartbeat = HeartbeatController(
            stores: fallbackStores,
            settings: settings,
            intents: intents,
            ticks: tickStore,
            mood: moodRepository,
            agentCore: agentCore,
            tickRunner: { now in
                let resolved = AppEnvironment.resolveConfig(stores: fallbackStores, settings: settings, apiKeys: apiKeys)
                guard let resolved else { return nil }
                let completionConfig = CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey)
                return await agentLoop.tick(config: completionConfig, model: resolved.model, now: now)
            },
            // 低电量模式下把前台账拍放宽到 2 分钟（`ForegroundPulse.intervalMillis` 与
            // 概率换算用的是同一个数）：省电优先，但也别让「他」彻底不说话。
            // 0.14：活跃窗口里递一拍心理活动（判定在服务里；这里只是把「他还在聊」这个信号给它）。
            mentalActivityRunner: { personaId, sessionId, now in
                await mentalActivity.runIfNeeded(personaId: personaId, sessionId: sessionId, now: now)
            },
            block: blockRepository,
            isLowPower: { ProcessInfo.processInfo.isLowPowerModeEnabled },
            // 服务端模式：本机这一拍什么都不做（双份主动消息的另一半就在这里被挡掉）。
            isServerMode: { envBoxRef.env?.serverModeActive ?? false },
            // 每一拍的结论都进日志：真机上「他从不主动」全靠这一行定位
            //（`ProactiveDiagnosis` 给出七道闸里到底哪一道关着）。
            log: { DebugSeeding.log($0) }
        )
        self.heartbeat = heartbeat

        // ---- 0.9.1：工具执行器 / 人设卡 / 主动唤醒规则 / 历史压缩 ----
        //
        // 装配顺序有讲究：ToolExecutor 要 memory / recall / mood / 人设卡 / 念头，
        // 而聊天服务只要一个 `ToolRunning`；这样聊天链路不必知道「工具背后有几个仓储」。
        let webTools = WebToolsClient(session: URLSession(configuration: Self.sessionConfiguration()))
        let personaCards = PersonaCardRepository(stores: fallbackStores)
        let proactiveRules = ProactiveRuleService(stores: fallbackStores)
        let historyCompression = HistoryCompressionService(
            stores: fallbackStores,
            completion: completion,
            resolveConfig: resolveCompressionConfig,
            log: { DebugSeeding.log($0) }
        )
        // 联网打分的「用完即弃」调用：与聊天/抽取共用同一个 completion 与并发闸。
        let webSearchScorer = WebSearchScorer(
            stores: fallbackStores,
            memory: memoryRepository,
            completion: completion,
            log: { DebugSeeding.log($0) }
        )
        // 服务端模式下的活动上报：init 里不能捕获 self，用局部转发变量在装配完成后赋值。
        let box = envBox
        // 表情/日记（0.13）：服务端模式下「他随身带的东西」在服务端，本机只做缓存。
        // `provider` 每次现问一次 `env`（模式可运行中切换），不把客户端拍死在装配时。
        let serverCatalog = ServerCatalogService(
            stores: fallbackStores,
            provider: { [box] in
                guard let env = box.env,
                      env.serverModeActive,
                      env.serverConfig.isUsable else { return nil }
                return ServerAPIClient(
                    config: env.serverConfig,
                    session: URLSession(configuration: AppEnvironment.sessionConfiguration())
                )
            },
            log: { DebugSeeding.log($0) }
        )
        self.serverCatalog = serverCatalog
        // 表情包（0.13）：服务端模式的「上传 / 下载 / 建行 / 删除」闭包在装配时注入。
        // Kit 层不知道现在是不是服务端模式，所以这里每次调用都重新问一次 `env`（模式可切换）。
        let stickerService = StickerService(
            stores: fallbackStores,
            session: URLSession(configuration: Self.sessionConfiguration()),
            serverUploader: { data, personaId in
                guard let env = box.env else { throw StickerError.writeFailed }
                let config = await MainActor.run { env.serverModeActive ? env.serverConfig : nil }
                guard let config else {
                    // 不是服务端模式却走到了上传：说明装配层/调用点判断错了，明确失败。
                    throw StickerError.writeFailed
                }
                let api = ServerAPIClient(
                    config: config, session: URLSession(configuration: AppEnvironment.sessionConfiguration())
                )
                let media = try await api.uploadSticker(bytes: data, personaId: personaId)
                return media.url
            },
            serverDownloader: { uri in
                guard let env = box.env else { throw StickerError.unreadableImage }
                let config = await MainActor.run { env.serverModeActive ? env.serverConfig : nil }
                guard let config else { throw StickerError.unreadableImage }
                let api = ServerAPIClient(
                    config: config, session: URLSession(configuration: AppEnvironment.sessionConfiguration())
                )
                return try await api.mediaData(uri: uri)
            },
            // 服务端模式下新建/删除表情要先过服务端（本机只是缓存）。
            serverCatalog: serverCatalog,
            log: { DebugSeeding.log($0) }
        )
        self.stickers = stickerService
        let toolExecutor = ToolExecutor(
            stores: fallbackStores,
            apiKeys: apiKeys,
            memory: memoryRepository,
            recallService: recall,
            mood: moodRepository,
            personaCards: personaCards,
            intents: intents,
            stickers: stickerService,
            block: blockRepository,
            webTools: webTools,
            scorer: webSearchScorer,
            agentCore: agentCore,
            // 服务端模式下把 `set_activity` 上报给服务端（写路径最小闭环）。
            // 这里的闭包要读「现在的模式」，所以通过弱引用问 self，而不是在装配时拍死。
            activityReported: { activity, personaId in
                box.env?.reportServerActivity(activity, personaId: personaId)
            },
            log: { DebugSeeding.log($0) }
        )
        // 人格进化（0.13）：与抽记忆/压摘要共用同一个 completion 与并发闸。
        let personaEvolution = PersonaEvolutionService(
            stores: fallbackStores,
            completion: completion,
            log: { DebugSeeding.log($0) }
        )
        // 自动情绪判定（0.13 schema v17）：每 4 条用户消息 / 24 小时判一次心情，
        // 结果写 `personas.mood*` 三列并在下一轮提示词里生效。
        let moodJudgement = MoodJudgementService(
            stores: fallbackStores,
            completion: completion,
            mood: moodRepository,
            log: { DebugSeeding.log($0) }
        )
        self.moodJudgement = moodJudgement
        let chatBackground = ChatBackgroundService(
            stores: fallbackStores,
            extraction: extraction,
            compression: historyCompression,
            resolveConfig: resolveCompressionConfig,
            evolution: personaEvolution,
            moodJudgement: moodJudgement,
            sessionMemory: sessionMemory,
            mentalActivity: mentalActivity,
            // 服务端模式：抽记忆/压摘要/进化/情绪都由服务端独立完成，本机不再各跑一遍。
            isServerMode: { envBoxRef.env?.serverModeActive ?? false },
            log: { DebugSeeding.log($0) }
        )
        self.webTools = webTools
        self.personaCards = personaCards
        self.proactiveRules = proactiveRules
        self.toolExecutor = toolExecutor
        self.historyCompression = historyCompression
        self.personaEvolution = personaEvolution
        self.chatBackground = chatBackground
        self.backgroundConfig = resolveCompressionConfig

        let backup = BackupService(
            stores: fallbackStores,
            settings: settings,
            vectorIndex: vectorIndex,
            appVersion: Self.appVersion
        )
        self.backup = backup
        // 更新检查：版本号只从 bundle 取（与设置页头部显示的是同一个数字）。
        self.updates = UpdateCenter(currentVersion: Self.appVersion)
        // 媒体清理收进 `DataReset`（0.10.2 审计 P1-7）：设置页与备份页两个入口都只调它，
        // 谁也不会再忘记删图片/头像文件。
        self.dataReset = DataReset(
            stores: fallbackStores,
            settings: settings,
            apiKeys: apiKeys,
            media: .shared,
            stickerStorage: .shared,
            // 0.13：语音/视频目录也随「清空聊天 / 删除全部数据」一起清。
            audioVideo: .shared
        )

        self.chat = ChatService(
            stores: fallbackStores,
            apiKeys: apiKeys,
            settings: settings,
            client: webClient,
            memory: memoryRepository,
            tools: toolExecutor,
            background: chatBackground,
            presenceDetail: { [weak presence] personaId in
                presence?.current(personaId: personaId).detail
            },
            mood: moodRepository,
            block: blockRepository,
            memo: SessionMemoryRepository(stores: fallbackStores),
            mind: MentalActivityRepository(stores: fallbackStores),
            rhythm: rhythm,
            proactive: proactiveRules,
            // 人设卡有内容就用人设卡渲染（Android 的 `PersonaCardRepository.systemText`）；
            // 空卡时它自己会回落到用户手写的那段 prompt，所以这里不用判断。
            personaText: { [weak personaCards] persona in
                personaCards?.systemText(persona) ?? PromptBuilder.personaSection(persona)
            },
            log: { DebugSeeding.log($0) },
            // 语言跟随系统（含 per-app language）：每次请求现读，用户改完语言不用重启装配。
            languageDirective: { AppLanguage.languageDirective },
            agentCore: agentCore
        )
        chatBox.chat = self.chat

        // ---- 服务端模式 ----
        // 令牌与 API Key 共用同一份 Keychain 分区；开关/地址在 UserDefaults。
        // 这里只把「设置来源」建出来；真正的客户端/服务在 `applyServerConfig()` 里装配
        //（它要在 init 完全结束之后才碰 self，见那边的注释）。
        let serverCredentials = ServerCredentialStore(keychain: ResilientKeychain())
        self.serverCredentials = serverCredentials
        self.serverConsent = ServerSyncConsentStore(database: fallbackStores.database)
        let loadedServerConfig = (try? ServerConfig.loadForApp(credentials: serverCredentials))
            ?? ServerConfig(baseURL: nil, token: nil, enabled: false)
        self.serverConfig = loadedServerConfig
        self.serverModeActive = loadedServerConfig.isUsable
        self.serverLiveState = ServerLiveState()

        if bootError == nil {
            // 0.12 续 7：先把老库里「从没配置过的默认睡眠」关掉，再谈播种。
            // 与是否服务端模式无关 —— 看的是**本地**库里已有的人格；只跑一次，
            // 失败只写一行日志，不挡启动。种子人格本来就是 sleepEnabled = false。
            SleepDefaultsMigration.runIfNeeded(
                stores: fallbackStores,
                isMigrated: { settings.hasMigratedSleepDefaultsV1 },
                markMigrated: { settings.markSleepDefaultsMigratedV1() },
                log: { DebugSeeding.log($0) }
            )
            // 服务端模式下**不再种本地人格**：人和会话都是服务端下发的，
            // 本机再种一份会变成「同一个人出现两次」（本地一个、服务端一个不同 id 的）。
            // 已有本地人格的情况属于 M5（人格/记忆 CRUD 上移）的迁移范围，这里不做合并：
            // 同步只按 id 命中，命中不了的服务端人格会作为新的人格出现（见交付报告的已知限制）。
            if !loadedServerConfig.isUsable {
                seedIfNeeded()
            } else {
                DebugSeeding.log("[server] 服务端模式：跳过本地人格种子（人以服务端为准）")
            }
            bindMissingApiConfigs()
            ensureEveryPersonaHasSession()
            // 并发闸超时只写进日志：用户不该看见「他排队超时了」。
            gate.onTimeout = { lane, waited in
                DebugSeeding.log("[gate] \(lane.label)等了 \(waited / 1000) 秒没拿到名额，这次先不做")
            }
        }

        // 服务端模式：读设置 → 造客户端 → 决定走哪条链路。
        // 放在 init 最后一行的理由：`applyServerConfig()` 会捕获 self（事件流要知道
        // 「前台吗 / 这个会话开着吗」），而捕获 self 必须等所有存储属性都初始化完。
        applyServerConfig()
        box.env = self
    }

    // MARK: - 服务端模式（M3/M4）

    /// 读设置并重建服务端三件套。设置页改完地址/令牌/开关之后调它。
    ///
    /// 三件事按顺序：
    /// 1. `ServerConfig.loadForApp`：设备设置（Keychain 令牌 + UserDefaults 开关/地址）优先，
    ///    其次环境变量 / Info.plist（与 P1a 的 `ServerConfig.load` 同一套校验，https 规则不变）；
    /// 2. 合法就造一个 `ServerAPIClient` 与同步/事件/聊天三件套；不合法就把它们清空；
    /// 3. 把「为什么没起来」写进 Debug 日志（配置错误里绝不含令牌）。
    func applyServerConfig() {
        let loaded: ServerConfig
        do {
            loaded = try ServerConfig.loadForApp(credentials: serverCredentials)
        } catch {
            // 地址不合法（http 明文 / 格式错 / Release 里写 http）：服务端模式直接不可用，
            // 界面会在「测试连接」处显示原因。
            serverConfig = ServerConfig(baseURL: nil, token: nil, enabled: false)
            serverModeActive = false
            serverSync = nil
            serverEvents = nil
            serverChat = nil
            serverClient = nil
            serverWrites = nil
            serverBootstrap = nil
            avatarUploader = nil
            serverMediaSync = nil
            serverBootstrapProgress = nil
            serverBootstrapError = nil
            stopServerSyncPulse()
            // 服务端媒体缓存也一起关掉：没有地址/令牌时不该再拿旧客户端去取图。
            ServerMediaCache.shared.configure(client: nil)
            DebugSeeding.log("[server] 配置不合法：\(error.localizedDescription)")
            return
        }
        serverConfig = loaded
        guard loaded.isUsable else {
            serverModeActive = false
            serverSync = nil
            serverEvents = nil
            serverChat = nil
            serverClient = nil
            serverWrites = nil
            serverBootstrap = nil
            avatarUploader = nil
            serverMediaSync = nil
            serverBootstrapProgress = nil
            serverBootstrapError = nil
            stopServerSyncPulse()
            ServerMediaCache.shared.configure(client: nil)
            DebugSeeding.log("[server] 服务端模式未启用（继续用本地模式）")
            return
        }

        let session = URLSession(configuration: Self.sessionConfiguration())
        let client = ServerAPIClient(config: loaded, session: session)
        serverClient = client
        // 图片渲染层（`AttachmentThumbnail`）拿不到 AppEnvironment，只能从进程级缓存拿客户端；
        // 配置变了就换掉，关掉服务端模式就置空（不准再往旧地址发请求）。
        ServerMediaCache.shared.configure(client: client)
        let outbox = ServerOutboxStore(database: stores.database)
        let chatGateway = ServerChatGateway(
            client: client,
            stores: stores,
            outbox: outbox,
            log: { DebugSeeding.log($0) }
        )
        serverSync = ServerSyncService(
            client: client,
            stores: stores,
            log: { DebugSeeding.log($0) }
        )
        // 这两件事会被服务端事件流在**后台线程**上问，所以只捕获「已经造好的对象」
        // 与「加锁的布尔值」，不捕获 self（跨隔离域读 @MainActor 的可变状态是数据竞争）：
        // - `ChatService.isSessionOpen` 自己带锁，任意线程可调；
        // - `ServerForegroundFlag` 也是带锁的。
        let chatRef = chat
        let foreground = serverForeground
        serverEvents = ServerEventService(
            client: client,
            stores: stores,
            notifier: ServerNotifications(isSessionOpen: { sessionId in
                chatRef.isSessionOpen(sessionId)
            }),
            isSessionOpen: { sessionId in
                chatRef.isSessionOpen(sessionId)
            },
            isForeground: { foreground.isActive },
            onLiveState: { [weak self] state in
                Task { @MainActor in self?.serverLiveState = state }
            },
            // 0.14.1 §8：`message.created` 一到就重读缓存直接上屏（不等 20 秒的消息轮询）。
            // typing 状态在上面 `onLiveState` 里已经发布，聊天页据此收起/显示输入指示。
            onEventReport: { [weak self] report in
                guard !report.newMessages.isEmpty else { return }
                Task { @MainActor in self?.serverDataVersion &+= 1 }
            },
            log: { DebugSeeding.log($0) }
        )
        serverChat = chatGateway
        // 0.13 第二阶段：服务端媒体落地（下载到本机持久目录 + 改库）。失败只计数，不阻断。
        serverMediaSync = ServerMediaSyncService(
            downloader: client,
            stores: stores,
            log: { DebugSeeding.log($0) }
        )
        serverWrites = ServerWriteService(
            client: client,
            stores: stores,
            memoryRepository: memoryRepository,
            backup: backup
        )
        // 我的头像：压缩（复用选图产物）→ `POST /v1/media?kind=USER_AVATAR` → 写设置。
        avatarUploader = ServerUserAvatarUploader(uploader: client)
        // 历史媒体逐项上传（「同意并同步」时跑）：逐项进度转成设置页那一行。
        // 只捕获弱引用 self：actor 里不该持有整棵依赖图。
        let historyMedia = ServerHistoryMediaUploader(
            uploader: client,
            stores: stores,
            baseURL: client.config.baseURL,
            log: { DebugSeeding.log($0) },
            progress: { [weak self] item in
                Task { @MainActor in
                    self?.serverBootstrapProgress = ServerBootstrapProgress(
                        stage: .pushing,
                        mode: .push,
                        completed: item.completed,
                        total: item.total,
                        detail: item.detail,
                        at: DomainClock.nowMillis
                    )
                }
            }
        )
        // 首次全量同步：每个 baseURL 一次；进度回调切回主线程发布给设置页。
        // 这里只捕获「弱引用 self」，不把整棵依赖图塞进 actor。
        serverBootstrap = ServerBootstrapService(
            client: client,
            stores: stores,
            settings: settings,
            memories: memoryRepository,
            avatarUploader: avatarUploader,
            mediaUploader: historyMedia,
            log: { DebugSeeding.log($0) },
            progress: { [weak self] progress in
                Task { @MainActor in
                    self?.serverBootstrapProgress = progress
                }
            }
        )
        serverModeActive = true
        DebugSeeding.log("[server] 服务端模式已启用：\(loaded.baseURL?.absoluteString ?? "-")")
        // 表情/日记：连上就拉一次（之后由 5 分钟增量同步的节流路径刷新）。
        Task { await syncServerCatalog(force: true) }
        // 重启进程后待发队列里可能有上次没发出去的消息：先把气泡补回界面。
        let restored = chatGateway.recoverPendingMessages()
        if restored > 0 { DebugSeeding.log("[server] 恢复了 \(restored) 条待发消息") }
    }

    /// 服务端模式下的「发送」入口：服务端可用就走服务端，否则走本地（行为一字未改）。
    ///
    /// 界面（`ChatViewModel.send`）只认这一个入口，所以「哪种模式」的判断只在这里出现一次。
    func sendChecked(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) -> ChatService.SendOutcome {
        if let gateway = serverChat, gateway.isActive {
            return gateway.sendChecked(sessionId: sessionId, text: text, attachments: attachments)
        }
        return chat.sendChecked(sessionId: sessionId, text: text, attachments: attachments)
    }

    /// 带图片的发送入口（服务端模式要先压缩 → 上传 → 再发）。
    ///
    /// 本地模式完全不变：附件已经在选图时压好、落盘，直接走原链路。
    func sendCheckedWithMedia(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment]
    ) async -> ChatService.SendOutcome {
        if let gateway = serverChat, gateway.isActive {
            return await gateway.sendCheckedWithMedia(
                sessionId: sessionId, text: text, attachments: attachments
            )
        }
        return chat.sendChecked(sessionId: sessionId, text: text, attachments: attachments)
    }

    /// 进聊天页：服务端模式下把它该做的事做掉（拉最新块 / 标已读 / 补发队列）。
    ///
    /// 失败只写日志：这些都是「让内容更新」的辅助动作，不该在打开页面时弹错误
    ///（本地模式照旧读缓存，界面上不会空白）。
    func prepareServerChat(sessionId: String) async {
        guard let gateway = serverChat, gateway.isActive else { return }
        // 0) 本地会话还没和服务端会话对上号（第一次进服务端模式、或本地模式先建的会话）：
        //    先 `POST /v1/sessions` 建/取回它，把服务端 id 记到本机那条会话上。
        //    没有这一步，后面所有写路径都会打到本机 UUID 上（服务端 404）。
        if let session = ((try? stores.sessions.byId(sessionId)) ?? nil),
           session.serverSessionId == nil || session.serverSessionId?.isEmpty == true {
            do {
                _ = try await gateway.ensureSession(personaId: session.personaId, title: session.title)
            } catch {
                DebugSeeding.log("[server] 建会话失败：\(error.localizedDescription)")
            }
        }
        do {
            try await gateway.loadLatestBlock(sessionId: sessionId)
        } catch {
            DebugSeeding.log("[server] 拉会话块失败：\(error.localizedDescription)")
        }
        await gateway.markRead(sessionId: sessionId)
        if !gateway.pendingEntries(sessionId: sessionId).isEmpty {
            _ = await gateway.flushOutbox()
        }
        // 0.13 第二阶段：这一页里如果有服务端附件，顺手搬成本机文件（后台跑，不阻塞进页面）。
        scheduleServerMediaSync(sessionId: sessionId)
    }

    /// 服务端模式下往前翻一块（聊天页上滑到底）。
    ///
    /// 用的是**服务端的块号**（`ServerChatGateway` 自己记着），不是本地块号：
    /// 本地块由 `MessageBlockStore` 按写入顺序切，两边块号不是一回事。
    func loadOlderServerBlock(sessionId: String) async {
        guard let gateway = serverChat, gateway.isActive else { return }
        do {
            try await gateway.loadOlderBlockRememberingPosition(sessionId: sessionId)
        } catch {
            DebugSeeding.log("[server] 翻更早的块失败：\(error.localizedDescription)")
        }
    }

    /// 服务端说这个会话上面还有没有更早的块（本地缓存可能还没拉到那么早）。
    func serverHasMoreOlder(sessionId: String) -> Bool {
        guard let gateway = serverChat, gateway.isActive else { return false }
        return gateway.hasOlderOnServer(sessionId: sessionId)
    }

    /// 同步一次服务端状态（启动 / 回前台 / 事件流空闲时）。
    @discardableResult
    /// 拉一次服务端的表情 / 日记缓存（带节流：它们不是实时内容，10 分钟一次足够）。
    ///
    /// 失败只记日志：聊天同步不能被「表情列表没拉到」拖住。
    func syncServerCatalog(force: Bool = false) async {
        guard serverModeActive, let catalog = serverCatalog else { return }
        let now = DomainClock.nowMillis
        if !force, lastServerCatalogSyncAt > 0, now - lastServerCatalogSyncAt < 10 * 60_000 {
            return
        }
        lastServerCatalogSyncAt = now
        let stickers = await catalog.syncStickers()
        let writes = serverWrites
        let pairs: [(localId: String, serverId: String)] =
            ((try? stores.personas.all()) ?? []).map { persona in
                (localId: persona.id, serverId: writes?.resolvedServerPersonaId(persona.id) ?? persona.id)
            }
        let notes = await catalog.syncDiary(personaIds: pairs)
        if stickers > 0 || notes > 0 {
            DebugSeeding.log("[server] 表情/日记缓存已更新：表情 \(stickers) · 日记 \(notes)")
        }
    }

    func syncServerNow() async -> ServerSyncOutcome? {
        guard let sync = serverSync else { return nil }
        let outcome = await sync.syncNow()
        switch outcome {
        case .synced(let report):
            if report.sessions > 0 || report.messages > 0 { serverDataVersion &+= 1 }
            DebugSeeding.log("[server] 同步完成：会话 \(report.sessions) / 消息 \(report.messages)")
            // 同步可能有新消息带附件：让落地同步也跑一拍（正在跑就跳过）。
            if report.messages > 0 { scheduleServerMediaSync() }
            // 表情/日记跟着状态一起刷新（有节流；失败只记日志）。
            await syncServerCatalog()
        case .failed(let message, let attempts):
            DebugSeeding.log("[server] 同步失败（\(attempts) 次）：\(message)")
        case .skipped(let reason):
            DebugSeeding.log("[server] 同步跳过：\(reason)")
        }
        return outcome
    }

    /// 起前台事件订阅（长轮询）。重复调用是安全的。
    func startServerEvents() {
        guard let events = serverEvents, serverModeActive else { return }
        guard serverEventTask == nil else { return }
        serverEventTask = Task { [weak events] in
            await events?.runLoop()
        }
        DebugSeeding.log("[server] 事件订阅已启动")
    }

    func stopServerEvents() {
        serverEventTask?.cancel()
        serverEventTask = nil
        // 0.14.1 §8：输入指示是瞬时状态，进后台就清掉。否则回前台时
        // 离线同步下来的消息前面会凭空演一次「正在输入…」。
        if let events = serverEvents {
            Task { await events.clearTyping() }
        }
        // 增量同步的 5 分钟一拍也是前台才做的事：进后台一起停。
        stopServerSyncPulse()
    }

    /// 服务端模式的一次「进前台」全套动作：首次全量（如需要）+ 轻量增量 + 起事件流 + 补发队列。
    ///
    /// 顺序有意为之：
    /// 1. **先 bootstrap**（换了一台服务端、两边数据不一样时那次全量）；它会把本机/服务端的
    ///    历史对齐，不先做的话后面的增量同步只搬「游标之后」的几条；
    /// 2. 再增量同步（把离线期间的整段状态补齐）；
    /// 3. 再起事件流（从这里开始只收增量），否则事件流会一边补历史一边收增量，游标语义会乱；
    /// 4. 最后补发队列（离线时排队的消息）。
    func activateServerMode() async {
        guard serverModeActive else { return }
        // 0) 本机部署的服务端要靠这份配置去调模型（云端情绪/进化端点都在服务端跑）：
        //    先推，再谈同步 —— 否则「首次进云端模式」时服务端还没有模型可用。
        //    远程服务器一律不推（`isLoopback`），失败只记日志。
        await pushLocalModelConfigIfNeeded()
        await runServerBootstrapIfNeeded()
        await quickServerSync(minIntervalMillis: 20_000)
        if let gateway = serverChat, gateway.isActive {
            _ = await gateway.flushOutbox()
        }
        startServerEvents()
        startServerSyncPulse()
        // 媒体落地：把服务端下发的头像/图片/表情/语音/视频搬成本机文件（不阻塞界面）。
        scheduleServerMediaSync()
    }

    // MARK: - 本机模型配置推送（0.13 第二阶段）

    /// 连上服务端之后，如果它就跑在本机上，把手机上的模型配置推过去（远程不发，失败只记日志）。
    func pushLocalModelConfigIfNeeded() async {
        guard serverModeActive,
              let client = serverAPIClientForPush(),
              ServerLlmConfigPusher.shouldPush(config: client.config) else {
            serverLlmConfigPushMessage = nil
            return
        }
        let resolved = Self.resolveConfig(stores: stores, settings: settings, apiKeys: apiKeys)
        let pusher = ServerLlmConfigPusher(
            client: client,
            apiConfig: resolved?.config,
            apiKey: resolved?.apiKey,
            log: { DebugSeeding.log($0) }
        )
        let outcome = await pusher.pushIfNeeded()
        // 只有「真的推上去了」才在界面上留一行；跳过与失败都只进日志
        //（远程服务器不推是规矩，不是问题；失败也不该让用户以为连接坏了）。
        serverLlmConfigPushMessage = outcome.didPush ? outcome.message : nil
    }

    /// 当前连接（没起来时为 nil）：配置推送 / 云端情绪 / 云端进化共用同一个客户端。
    private func serverAPIClientForPush() -> ServerAPIClient? {
        guard serverModeActive, let client = serverClient, client.config.isUsable else { return nil }
        return client
    }

    // MARK: - 服务端媒体落地（0.13 第二阶段）

    /// 后台跑一轮媒体落地同步。重复调用是安全的：正在跑就跳过这一轮。
    func scheduleServerMediaSync(sessionId: String? = nil) {
        guard serverModeActive, let sync = serverMediaSync, !isMediaSyncRunning else { return }
        isMediaSyncRunning = true
        Task { [weak self] in
            let report = await sync.syncMessageAttachments(sessionId: sessionId)
            if report.messagesUpdated > 0 {
                await MainActor.run {
                    // 附件引用变了（`/v1/media/<id>` → `file://`）：让界面重载一次气泡。
                    self?.serverDataVersion &+= 1
                }
            }
            await MainActor.run { self?.isMediaSyncRunning = false }
        }
    }

    /// 一次把消息附件、头像、表情都搬一遍（首次连上服务端 / 设置页「整理」时用）。
    func runServerMediaSyncNow() async {
        guard serverModeActive, let sync = serverMediaSync, !isMediaSyncRunning else { return }
        isMediaSyncRunning = true
        defer { isMediaSyncRunning = false }
        let report = await sync.syncAll()
        if report.downloaded > 0 || report.avatarsUpdated > 0 || report.stickersUpdated > 0 {
            serverDataVersion &+= 1
        }
    }

    // MARK: - 首次全量同步（0.12.1）

    /// 如果这台服务端还没做过首次同步，就做一次（幂等；已经有完成标记时立刻返回 nil）。
    ///
    /// - Parameter force: 设置页「重新同步」用；生产路径不要传。
    @discardableResult
    func runServerBootstrapIfNeeded(force: Bool = false) async -> ServerBootstrapOutcome? {
        guard serverModeActive, let bootstrap = serverBootstrap else { return nil }
        if !force, await bootstrap.isBootstrapped() { return nil }
        guard !isBootstrapping else { return nil }
        isBootstrapping = true
        defer { isBootstrapping = false }
        serverBootstrapError = nil
        // 同意方式按 baseURL 从簿记里读：没有记录（0.12 升级上来的用户）按「只同步新内容」，
        // 绝不在没有点头的情况下把历史与媒体传上去。
        let consent = ServerSyncConsentGate.activationConsent(
            stored: serverConsent.decision(forBaseURL: serverConfig.baseURL)
        )
        let outcome = await bootstrap.bootstrap(force: force, consent: consent)
        switch outcome {
        case .completed(let report):
            serverBootstrapProgress = nil
            serverBootstrapError = nil
            // 服务端拉回来的设置（主题/字号/我的头像）要立刻被界面看到。
            reloadSettings()
            ensureEveryPersonaHasSession()
            bindMissingApiConfigs()
            serverDataVersion &+= 1
            DebugSeeding.log("[server] 首次同步完成：\(report.describe)")
        case .failed(let message, let attempts):
            serverBootstrapProgress = nil
            // 直接显示 Kit 给的那句话（`ServerBootstrapService` 的失败文案本来就是中文）：
            // 这里不拼中文前缀 —— App 层不许新増裸中文，本地化资源又不在本次改动范围。
            serverBootstrapError = message
            DebugSeeding.log("[server] 首次同步失败（\(attempts) 次）：\(message)")
        case .skipped(let reason):
            serverBootstrapProgress = nil
            DebugSeeding.log("[server] 首次同步跳过：\(reason)")
        case .cancelled:
            serverBootstrapProgress = nil
            serverBootstrapError = nil
            DebugSeeding.log("[server] 首次同步被取消：仍按当前模式使用")
        }
        return outcome
    }

    // MARK: - 切换云端：同意 / 全量下载（0.13）

    /// 一次「拨到云端」的结果（设置页据此决定开关停在哪儿）。
    enum ServerActivationOutcome: Equatable {
        /// 同步完成并已切到服务端模式。
        case activated(ServerBootstrapReport)
        /// 用户取消了进度：**不切换模式**（已上传的媒体保留在服务端）。
        case cancelled
        /// 失败：保持本地模式，把原因显示出来。
        case failed(message: String)
    }

    /// 一次「拨回本地」的结果。
    enum ServerDeactivationOutcome: Equatable {
        /// 全量下载 + 条数校验通过，已经关闭服务端模式。
        case disabled(ServerRestoreReport)
        /// 下载/校验失败：**保持服务端模式**（否则会永久缺一段历史）。
        case failed(message: String)
    }

    /// 设置页拨开关前的判定：要不要弹同意弹窗。
    func serverSyncConsentPlan(baseURLText: String) -> ServerSyncConsentPlan {
        ServerSyncConsentGate.plan(
            baseURLText: baseURLText,
            stored: serverConsent.decision(forBaseURLText: baseURLText)
        )
    }

    /// 已经在同一台服务端上同意过（设置页据此显示「不必再问」）。
    func recordedServerConsent(baseURLText: String) -> ServerSyncConsentDecision? {
        serverConsent.decision(forBaseURLText: baseURLText)
    }

    /// 记下用户在弹窗上的选择（取消不落记录：下次还会问）。
    func recordServerConsent(
        _ choice: ServerSyncConsentChoice,
        baseURLText: String
    ) -> ServerSyncConsentResolution {
        let resolution = ServerSyncConsentGate.resolve(choice, baseURLText: baseURLText)
        if case .proceed(let url, let decision) = resolution {
            do {
                try serverConsent.record(decision, forBaseURLText: url)
            } catch {
                DebugSeeding.log("[server] 同意记录写入失败（这次照样同步）：\(error.localizedDescription)")
            }
        }
        return resolution
    }

    /// 「同意并同步」/「只同步之后的新内容」：先把这次同步跑完，成功才切模式。
    ///
    /// 为什么不能像老路径那样「先切模式，再在后台 bootstrap」：
    /// 规范要求**取消不切换模式**。先切模式的话，用户一取消，界面已经进了服务端模式
    /// （聊天走服务端、本机缓存还没补齐），那是他最不想要的状态。
    ///
    /// - Parameters:
    ///   - baseURLText: 设置页里的地址；不合法时直接返回失败，不写任何东西。
    ///   - token: 新令牌（nil = 沿用 Keychain 里已保存的那个）。
    ///   - consent: 用户选择的同意方式（决定要不要搬历史与媒体）。
    func enableServerModeWithConsent(
        baseURLText: String,
        token: String?,
        consent: ServerSyncConsentDecision
    ) async -> ServerActivationOutcome {
        let url: URL
        do {
            url = try ServerConfig.validatedURL(baseURLText)
        } catch {
            return .failed(message: error.localizedDescription)
        }
        // 地址与令牌先落盘（失败时用户不用重填），但**开关先保持关闭**。
        serverCredentials.setBaseURLText(url.absoluteString)
        do {
            try serverCredentials.setToken(token)
        } catch {
            return .failed(message: error.localizedDescription)
        }
        serverCredentials.setModeEnabled(false)
        applyServerConfig()

        // 用一份显式「可用」的配置造临时服务：开关还没打开，但这次同步要真的发请求。
        let config = ServerConfig(baseURL: url, token: token, enabled: true)
        let client = ServerAPIClient(
            config: config,
            session: URLSession(configuration: Self.sessionConfiguration())
        )
        let media = ServerHistoryMediaUploader(
            uploader: client,
            stores: stores,
            baseURL: url,
            log: { DebugSeeding.log($0) },
            progress: { [weak self] item in
                Task { @MainActor in
                    self?.serverBootstrapProgress = ServerBootstrapProgress(
                        stage: .pushing,
                        mode: .push,
                        completed: item.completed,
                        total: item.total,
                        detail: item.detail,
                        at: DomainClock.nowMillis
                    )
                }
            }
        )
        let bootstrap = ServerBootstrapService(
            client: client,
            stores: stores,
            settings: settings,
            memories: memoryRepository,
            avatarUploader: ServerUserAvatarUploader(uploader: client),
            mediaUploader: media,
            log: { DebugSeeding.log($0) },
            progress: { [weak self] progress in
                Task { @MainActor in self?.serverBootstrapProgress = progress }
            }
        )
        let outcome = await bootstrap.bootstrap(force: true, consent: consent)
        serverBootstrapProgress = nil

        switch outcome {
        case .completed(let report):
            serverBootstrapError = nil
            serverCredentials.setModeEnabled(true)
            applyServerConfig()
            reloadSettings()
            ensureEveryPersonaHasSession()
            bindMissingApiConfigs()
            serverDataVersion &+= 1
            await activateServerMode()
            DebugSeeding.log("[server] 已切到服务端模式（\(consent.rawValue)）：\(report.describe)")
            return .activated(report)
        case .cancelled:
            // 取消：已上传的媒体留在服务端，但本机仍是本地模式（上传器已还原本地引用）。
            serverBootstrapError = nil
            DebugSeeding.log("[server] 同步被取消，仍用本地模式")
            return .cancelled
        case .failed(let message, _):
            serverBootstrapError = message
            DebugSeeding.log("[server] 切换失败，仍用本地模式：\(message)")
            return .failed(message: message)
        case .skipped(let reason):
            serverBootstrapError = reason
            return .failed(message: reason)
        }
    }

    /// 拨回本地：先全量下载 + 校验，通过才关闭服务端模式。
    ///
    /// 失败时**什么都不关**（`Task` 里已经拉回来的数据保留在本地缓存，下次再试会更快）。
    func disableServerModeDownloadingAll() async -> ServerDeactivationOutcome {
        guard serverModeActive else {
            // 本来就没开（开关显示与实际不一致时）：直接把开关落成关，没有数据要下载。
            serverCredentials.setModeEnabled(false)
            applyServerConfig()
            return .disabled(ServerRestoreReport())
        }
        guard let bootstrap = serverBootstrap else {
            // 理论上不会发生（服务端模式可用就一定造了 bootstrap）；保守地保持原状。
            return .failed(message: L("当前没有连着服务端"))
        }
        serverBootstrapError = nil
        let outcome = await bootstrap.downloadAll()
        serverBootstrapProgress = nil
        switch outcome {
        case .completed(let report):
            serverCredentials.setModeEnabled(false)
            applyServerConfig()
            stopServerEvents()
            stopServerSyncPulse()
            reloadSettings()
            ensureEveryPersonaHasSession()
            bindMissingApiConfigs()
            serverDataVersion &+= 1
            serverBootstrapError = nil
            DebugSeeding.log("[server] 全量下载通过校验，已切回本地：\(report.describe)")
            return .disabled(report)
        case .failed(let message, _):
            // 保持服务端模式：客户端不能在没有完整数据的情况下关掉最后一条同步通道。
            // 只把原因交给设置页的 result（不写 serverBootstrapError：那里的「再试一次」
            // 是重跑 bootstrap，不是重跑全量下载，会误导用户）。
            serverDataVersion &+= 1
            DebugSeeding.log("[server] 全量下载失败，仍保持服务端模式：\(message)")
            return .failed(message: message)
        case .skipped(let reason):
            return .failed(message: reason)
        case .cancelled:
            return .failed(message: L("已取消，仍用本机模式"))
        }
    }

    /// 设置页「重新同步一次」：清掉完成标记重跑（幂等，不会造出第二份）。
    func redoServerBootstrap() async {
        await runServerBootstrapIfNeeded(force: true)
    }

    /// 轻量增量同步（带节流）。
    ///
    /// 为什么需要节流：`activateServerMode` 在冷启动与回前台各会调一次，前台还有 5 分钟一拍；
    /// 没有节流时三处会同时各打一次 `/v1/state`（幂等但白花流量）。
    @discardableResult
    func quickServerSync(minIntervalMillis: Int64 = 20_000) async -> ServerSyncOutcome? {
        guard serverModeActive, serverSync != nil else { return nil }
        let now = DomainClock.nowMillis
        if lastServerSyncAttemptAt > 0, now - lastServerSyncAttemptAt < minIntervalMillis {
            return nil
        }
        lastServerSyncAttemptAt = now
        return await syncServerNow()
    }

    /// 前台每 5 分钟一次的轻量增量（只拉增量，不重跑全量）。
    func startServerSyncPulse() {
        guard serverModeActive else { return }
        guard serverSyncPulseTask == nil else { return }
        serverSyncPulseTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 5 * 60 * 1_000_000_000)
                } catch {
                    return
                }
                guard let self, !Task.isCancelled else { return }
                guard self.isForeground, self.serverModeActive else { return }
                await self.quickServerSync(minIntervalMillis: 0)
            }
        }
        DebugSeeding.log("[server] 增量同步已排上：前台每 5 分钟一次")
    }

    func stopServerSyncPulse() {
        serverSyncPulseTask?.cancel()
        serverSyncPulseTask = nil
    }

    // MARK: - 我的头像（USER_AVATAR）

    /// 上传「我的头像」并写进设置，返回 `/v1/media/<id>`。
    ///
    /// 只在服务端模式下调用；本地模式仍走原来的 `file://` 路径（行为一字未改）。
    /// 上传成功以 `POST /v1/media` 的 2xx 为准（设置接口静默忽略未知键，不能拿它当证据）。
    /// - Returns: 写进设置的 `/v1/media/<id>`；服务端模式没起来时返回 nil（调用方是设置页，
    ///   它本来就只在 `serverModeActive` 时进来）。
    func uploadMyAvatar(localURI: String) async throws -> String? {
        guard serverModeActive, let uploader = avatarUploader else { return nil }
        let result = try await uploader.upload(localURI: localURI)
        updateSettings { $0.myAvatarUri = result.uri }
        DebugSeeding.log("[server] 我的头像已上传：\(result.uri)")
        return result.uri
    }

    /// `set_activity` 在服务端模式下要上报给服务端（规范 §4：主动引擎据此决定要不要开口）。
    ///
    /// 失败只记日志：本地那份活动已经写进人格了，界面上「在忙」照样显示；
    /// 下一次同步会被服务端权威值覆盖，不需要在这里回滚用户看得见的东西。
    func reportServerActivity(_ activity: PersonaActivity, personaId: String) {
        guard serverModeActive else { return }
        // `ServerChatGateway` 不暴露 client，这里单独造一个同配置的（成本只是一次 init）。
        let api = ServerAPIClient(config: serverConfig, session: URLSession(configuration: Self.sessionConfiguration()))
        Task { @MainActor in
            do {
                _ = try await api.reportActivity(
                    personaId: personaId,
                    activity: activity.activity,
                    kind: activity.kind.stored,
                    minutes: activity.minutes,
                    availability: activity.availability.rawValue
                )
                DebugSeeding.log("[server] 活动已上报：\(activity.activity)")
            } catch {
                DebugSeeding.log("[server] 活动上报失败（只记日志）：\(error.localizedDescription)")
            }
        }
    }

    /// 注册推送 token（M6 的脚手架：服务端有 token 就交给可插拔的 `PushSender`）。
    func registerServerPushToken(_ token: String) async {
        guard serverModeActive else { return }
        let api = ServerAPIClient(config: serverConfig, session: URLSession(configuration: Self.sessionConfiguration()))
        do {
            let result = try await api.registerPushToken(
                token: token,
                bundleId: Bundle.main.bundleIdentifier
            )
            DebugSeeding.log("[server] 推送 token 已注册：created=\(result.created)")
        } catch {
            DebugSeeding.log("[server] 推送 token 注册失败：\(error.localizedDescription)")
        }
    }

    /// 设置页「测试连接」用：只打 `/health`，不触发同步。
    func testServerConnection() async -> Result<ServerHealth, Error> {
        let api = ServerAPIClient(config: serverConfig, session: URLSession(configuration: Self.sessionConfiguration()))
        do {
            return .success(try await api.testConnection())
        } catch {
            return .failure(error)
        }
    }

    /// 设置页保存服务端设置：写 Keychain / UserDefaults，然后立刻生效。
    ///
    /// - Throws: 地址不合法（http 明文 / 格式错）时抛给界面显示中文原因；令牌已经写进 Keychain 了。
    func saveServerSettings(enabled: Bool, baseURLText: String, token: String?) throws {
        serverCredentials.setModeEnabled(enabled)
        serverCredentials.setBaseURLText(baseURLText)
        try serverCredentials.setToken(token)
        applyServerConfig()
        stopServerEvents()
        if serverModeActive {
            Task { await activateServerMode() }
        }
    }

    // MARK: - 设置

    /// 设置改动后统一走这里：落盘并刷新内存快照。
    ///
    /// 1.9 起不再需要「顺手更新额度闸」那一步 —— 那个闸已经整段删掉了（§五）。
    func updateSettings(_ mutate: (inout AppSettings) -> Void) {
        var next = currentSettings
        mutate(&next)
        // 服务端模式：设置也要以服务端为准。先推服务端，成功再写本机；失败保持原值
        // 并在设置页顶部说明（不回退成「悄悄只写本地」）。
        if serverModeActive, let writes = serverWrites {
            let previous = currentSettings
            settingsPushTask?.cancel()
            settingsPushTask = Task { [weak self] in
                guard let self, !Task.isCancelled else { return }
                do {
                    _ = try await writes.saveSettings(next)
                    guard !Task.isCancelled else { return }
                    self.settings.save(next)
                    self.currentSettings = next
                    self.serverWriteError = nil
                } catch {
                    guard !Task.isCancelled else { return }
                    // 本机保持服务端上一次确认过的值（不写 next）。
                    self.currentSettings = previous
                    self.serverWriteError = L("没能保存") + "：" + error.localizedDescription
                    DebugSeeding.log("[server] 设置保存失败：\(error.localizedDescription)")
                }
            }
            // 乐观地把界面切到新值：请求还在路上时开关不该弹回去；失败会改回 previous。
            currentSettings = next
            refreshProactivePulse()
            return
        }
        settings.save(next)
        currentSettings = next
        // 「常驻循环」这类开关刚被打开时，前台心跳可能还没起来（冷启动时没有 API 配置 /
        // 没有人格，`startForegroundPulse` 会直接放弃）。这里重新评估一次，幂等。
        refreshProactivePulse()
    }

    // MARK: - API 配置

    /// 当前生效的 API 配置：优先取默认配置，其次第一条。
    var activeApiConfig: ApiConfigEntity? {
        // `api_configs` 没有 `isDefault` 这一列（Android 也一样）：默认配置就是「最近改过的那个」，
        // 用户在设置里选中它时会被 `updatedAt` 顶到最前面。
        (try? stores.apiConfigs.allOrderedByUpdatedAt().first) ?? nil
    }

    /// 解析出「这次请求用哪个配置、哪个 Key、哪个模型」。
    /// 返回 nil 表示「还没配 API」，界面据此显示引导而不是发一个注定失败的请求。
    func resolvedChatConfig() -> ResolvedConfig? {
        Self.resolveConfig(stores: stores, settings: settings, apiKeys: apiKeys)
    }

    /// 主动唤醒的「现在试一次」：走心跳平时那条开口路径（同一套人格提示词、同一个落库与通知回调），
    /// 只生成一条消息、**不改规则的排期**；失败原因由 `AgentLoop` 写回规则的 `lastError`。
    func triggerProactiveRule(_ ruleId: String) async -> AgentLoop.ProactiveFireOutcome {
        guard let resolved = resolvedChatConfig() else {
            return .failure(L("还没有可用的 API 配置"))
        }
        return await agentLoop.fireProactiveRule(
            ruleId: ruleId,
            config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
            model: resolved.model
        )
    }

    struct ResolvedConfig {
        var config: ApiConfigEntity
        var model: String
        var apiKey: String?
    }

    static func resolveConfig(
        stores: DatabaseStores,
        settings: SettingsStore,
        apiKeys: ApiKeyStore
    ) -> ResolvedConfig? {
        let all = (try? stores.apiConfigs.allOrderedByUpdatedAt()) ?? []
        guard let config = all.first else { return nil }
        let persona = (try? stores.personas.byId(settings.load().activePersonaId ?? "")) ?? nil
        let model = persona?.modelOverride?.isEmpty == false
            ? persona!.modelOverride!
            : config.model
        return ResolvedConfig(
            config: config,
            model: model.isEmpty ? config.model : model,
            apiKey: apiKeys.apiKey(for: config.id)
        )
    }

    // MARK: - 首次启动

    /// 第一次装上时给三个能直接对话的示例人格；已经有数据就什么都不做。
    ///
    /// 两套人设随系统语言二选一（`SeedPersonas`）：中文系统得到「林深 / 阿岚 / 墨」，
    /// 英文系统得到 Rowan / Juno / Ellis。判定只看「库里有没有人」这一件事——
    /// 所以只影响新安装，已有数据不迁移、不改名，切语言也不会重写它们。
    private func seedIfNeeded() {
        // 播种逻辑在 Kit（`PersonaSeeding`，有单测）：首次启动与「删除全部本地数据之后」
        // 走的是同一份代码、同一套固定 id（0.11.0 审计 P2-13）。
        let outcome = (try? PersonaSeeding.seedIfNeeded(
            stores: stores,
            language: AppLanguage.current.seedLanguage,
            sessionTitle: L("新的对话"),
            apiConfigId: defaultApiConfigId(),
            now: DomainClock.nowMillis
        )) ?? PersonaSeeding.Outcome()
        guard !outcome.skipped, let first = outcome.personaIds.first else { return }
        updateSettings { $0.activePersonaId = first }
    }

    /// 「删除全部本地数据」的唯一入口（设置页与备份页都调它）。
    ///
    /// 为什么不让两个 View 各自调 `dataReset.clearAll()`：清空之后还要做几件装配层的事，
    /// 任何一处漏掉都会让用户看到不一致的状态（0.11.0 审计 P2-13）：
    /// 1. 按**当前语言**重新播种三位示例人格（不留一个空白 App）；
    /// 2. 设置刚被清空，把「正在聊的人」指回第一个种子人格；
    /// 3. 给每个人格补上会话与 API 配置绑定（清空之后通常还没有配置，这一步是幂等的）。
    @discardableResult
    func clearAllData() -> ClearSummary {
        dataReset.reseed = ReseedConfig(
            language: AppLanguage.current.seedLanguage,
            sessionTitle: L("新的对话"),
            apiConfigId: nil,
            now: DomainClock.nowMillis
        )
        let summary = dataReset.clearAll()
        dataReset.reseed = nil
        // 服务端图片的本地缓存也一起清（服务端那一份不动；下次用到会重新下载）。
        ServerMediaCache.shared.clear()
        reloadSettings()
        if let first = (try? stores.personas.all(orderBy: "createdAt ASC"))?.first {
            updateSettings { $0.activePersonaId = first.id }
        }
        ensureEveryPersonaHasSession()
        bindMissingApiConfigs()
        heartbeat.clearCache()
        DebugSeeding.log("[reset] 全部删除：重新播种 \(summary.seededPersonas) 个人格、\(summary.seededSessions) 段会话")
        return summary
    }

    /// 从磁盘重新读一遍设置（清空数据、导入备份之后用：这两件事会让设置整体变掉）。
    func reloadSettings() {
        currentSettings = settings.load()
        refreshProactivePulse()
    }

    // MARK: - 会话与 API 配置的绑定

    /// 当前默认 API 配置的 id（新建会话要用它，否则发消息会报「请先选择 API 配置」）。
    func defaultApiConfigId() -> String? {
        activeApiConfig?.id
    }

    /// 这段会话还在不在（不建新的）。
    ///
    /// 0.11.1 审计 P2-2：通知深链在 push 之前必须校验 —— 会话可能已经被删掉
    /// （清空全部数据、删人格、替换导入），直接跳进去只会得到空白页。
    func sessionExists(id: String) -> Bool {
        ((try? stores.sessions.byId(id)) ?? nil) != nil
    }

    /// 取这个人格的那一段会话；没有就建一段（1.9 起「一人一段」，§二）。
    ///
    /// 为什么把「取」和「建」合成一个入口：首页、聊天页、`defer_reply`、心跳都要拿到
    /// 「他的那一段」，任何一处自己 `new ChatSessionEntity` 都会撞唯一索引。
    @discardableResult
    func session(forPersona personaId: String) -> ChatSessionEntity? {
        if let existing = (try? stores.sessions.session(forPersona: personaId)) ?? nil {
            return existing
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let session = ChatSessionEntity(
            id: UUID().uuidString,
            personaId: personaId,
            apiConfigId: defaultApiConfigId(),
            // 会话标题是落库的数据：建的时候用当时的界面语言，之后不随语言切换重写。
            title: L("新的对话"),
            createdAt: now,
            updatedAt: now
        )
        do {
            try stores.sessions.upsert(session)
            return session
        } catch {
            // 并发下另一个调用点刚建好：再取一次，别把错误甩给界面。
            return (try? stores.sessions.session(forPersona: personaId)) ?? nil
        }
    }

    /// 迁移用：给每个还没有会话的人格补一段（老库升上来、或从备份里接回一个新人格）。
    private func ensureEveryPersonaHasSession() {
        let personas = (try? stores.personas.all()) ?? []
        for persona in personas {
            if ((try? stores.sessions.session(forPersona: persona.id)) ?? nil) == nil {
                _ = session(forPersona: persona.id)
            }
        }
    }

    /// 删掉一个人，连同他的聊天、记忆、主动节奏与念头。
    ///
    /// 为什么装配层要有这个方法：首页（长按删除）与人格列表（卡片删除）都要做同一串动作，
    /// 而「删记忆必须先删倒排索引与向量索引」这种顺序一旦两处各写一份，迟早漏一个。
    /// 删掉一个人，连同他的聊天、记忆、回声、思考留痕与记忆版本。
    ///
    /// 级联清单一律走 `PersonaDeletion`（Kit，有单测）：0.10.1 这里漏了 `memory_echoes`、
    /// `agent_ticks`、`memory_versions`，也处理「删的是默认人格」——
    /// 删完之后库里一个默认人格都没有，`defaultPersona()` 返回 nil（0.10.2 安全审计 P1-6）。
    func deletePersona(_ persona: PersonaEntity) throws {
        let outcome = PersonaDeletion.delete(
            personaId: persona.id,
            stores: stores,
            memoryRepository: memoryRepository,
            activePersonaId: currentSettings.activePersonaId
        )
        guard outcome.personaDeleted else {
            throw PersonaDeletionError.failed
        }
        finishPersonaDeletion(persona, outcome: outcome)
    }

    // MARK: - M5 写路径（服务端模式优先，本地模式完全不变）

    /// 保存人格：服务端模式先 POST（id 由服务端生成/确认），成功后写本地缓存。
    func savePersona(_ persona: PersonaEntity, isNew: Bool) async throws -> PersonaEntity {
        if serverModeActive, let writes = serverWrites {
            let saved = try await writes.savePersona(persona, isNew: isNew)
            ensureEveryPersonaHasSession()
            return saved
        }
        try stores.personas.upsert(persona)
        return persona
    }

    /// 删除人格（服务端模式先 DELETE，再本地级联；失败本地一个都不动）。
    func deletePersonaAsync(_ persona: PersonaEntity) async throws {
        guard serverModeActive, let writes = serverWrites else {
            try deletePersona(persona)
            return
        }
        let outcome = try await writes.deletePersona(
            id: persona.id,
            activePersonaId: currentSettings.activePersonaId
        )
        finishPersonaDeletion(persona, outcome: outcome)
    }

    // MARK: - 人格进化在云端模式（0.13）

    /// 云端模式下的进化编排（接受/拒绝 + 评估触发）。
    ///
    /// 为什么每次现造、不存成属性：`serverWrites` 会随服务端地址/令牌改动整体重建，
    /// 拍死一份会拿着旧客户端往后端发请求。单飞闸在 `personaEvolution` 里
    /// （与本地模式共用），所以现造也不会多评估一次。
    func cloudPersonaEvolution() -> CloudPersonaEvolution? {
        guard serverModeActive, let client = serverAPIClientForPush() else { return nil }
        let writes = serverWrites
        return CloudPersonaEvolution(
            client: client,
            evolution: personaEvolution,
            stores: stores,
            serverPersonaId: { [weak writes] localId in
                writes?.resolvedServerPersonaId(localId) ?? localId
            },
            log: { DebugSeeding.log($0) }
        )
    }

    /// 云端模式下的情绪判定编排（0.13 第二阶段）。
    ///
    /// 与进化同一条理由：现造而不是存属性 —— `serverConfig` 一变，旧客户端就指错了地方。
    func cloudMood() -> CloudMoodJudgement? {
        guard serverModeActive, let client = serverAPIClientForPush() else { return nil }
        let writes = serverWrites
        return CloudMoodJudgement(
            client: client,
            stores: stores,
            mood: moodRepository,
            serverPersonaId: { [weak writes] localId in
                writes?.resolvedServerPersonaId(localId) ?? localId
            },
            log: { DebugSeeding.log($0) }
        )
    }

    /// 云端模式：一轮助手回复**落库之后**，按与本地模式同一节流请服务端评估一次人格进化。
    ///
    /// 与抽记忆/压摘要同一套规矩：不挡回复（起 Task）、失败只写日志、绝不抛给界面。
    /// 第二阶段起评估在**服务端**做（不需要本机 API 配置），所以这里不再要求有模型配置；
    /// 服务端没配时它回 503，我们只留一行日志（云端功能自己会退化成「不做」）。
    func triggerPersonaEvolutionForServerReply(sessionId: String, personaId: String) {
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let cloud = cloudPersonaEvolution() else { return }
        Task { await cloud.evaluateIfNeeded(personaId: personaId) }
    }

    /// 云端模式：一轮助手回复落库之后按 4 条节流请服务端判一次情绪，结果写本地缓存。
    ///
    /// 情绪是「给界面与离线提示词用」的缓存，所以失败什么都不影响（`CloudMoodJudgement`
    /// 自己已经把失败吞掉并写日志）。
    func triggerCloudMoodForServerReply(sessionId: String, personaId: String) {
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let cloud = cloudMood() else { return }
        Task { await cloud.evaluateIfNeeded(personaId: personaId) }
    }

    /// 手动记一条记忆：服务端生成 id 后再写本地缓存（本地模式仍是原来的同步写入）。
    func addMemory(
        personaId: String,
        content: String,
        category: MemoryCategory,
        importance: Int
    ) async throws -> MemoryEntity {
        if serverModeActive, let writes = serverWrites {
            return try await writes.addMemory(
                personaId: personaId,
                content: content,
                category: category,
                importance: importance
            )
        }
        switch memoryRepository.addDetailed(
            personaId: personaId,
            content: content,
            category: category,
            importance: importance
        ) {
        case .success(let id):
            guard let memory = (try? stores.memories.byId(id)) ?? nil else {
                throw MemoryRepositoryError.memoryMissing
            }
            return memory
        case .failure(let error):
            throw error
        }
    }

    /// 忘掉一条记忆。
    func deleteMemory(_ memory: MemoryEntity) async throws {
        if serverModeActive, let writes = serverWrites {
            try await writes.deleteMemory(id: memory.id)
            return
        }
        memoryRepository.delete(memory)
    }

    /// 服务端模式下打开记忆页/预设列表时拉一次服务端内容并刷新本地缓存。
    func refreshMemoriesFromServer(personaId: String) async {
        guard serverModeActive, let writes = serverWrites else { return }
        do {
            let count = try await writes.refreshMemories(personaId: personaId)
            DebugSeeding.log("[server] 记忆缓存已刷新：\(count) 条")
        } catch {
            DebugSeeding.log("[server] 记忆刷新失败（读缓存）：\(error.localizedDescription)")
        }
    }

    func refreshPresetsFromServer() async {
        guard serverModeActive, let writes = serverWrites else { return }
        do {
            let count = try await writes.refreshPresets()
            DebugSeeding.log("[server] 预设缓存已刷新：\(count) 个")
        } catch {
            DebugSeeding.log("[server] 预设刷新失败（读缓存）：\(error.localizedDescription)")
        }
    }

    func savePreset(_ preset: PersonaPresetEntity, isNew: Bool) async throws -> PersonaPresetEntity {
        if serverModeActive, let writes = serverWrites {
            return try await writes.savePreset(preset, isNew: isNew)
        }
        try stores.presets.upsert(preset)
        return preset
    }

    func deletePreset(id: String) async throws {
        if serverModeActive, let writes = serverWrites {
            try await writes.deletePreset(id: id)
            return
        }
        try stores.presets.delete(id: id)
    }

    /// 导出备份：服务端模式取服务端那份（本机缓存不再自己拼一份）。
    func exportBackup() async throws -> BackupExportResult {
        if serverModeActive, let writes = serverWrites {
            return BackupExportResult(text: try await writes.exportBackup())
        }
        return try backup.exportWithWarnings()
    }

    /// 导入备份：服务端模式先让服务端接（merge/replace），成功后再导入本地缓存。
    func importBackup(text: String, mode: ImportMode) async throws -> ImportSummary {
        let summary: ImportSummary
        if serverModeActive, let writes = serverWrites {
            summary = try await writes.importBackup(text: text, mode: mode)
        } else {
            summary = try backup.importBackup(text: text, mode: mode)
        }
        // 导入会把设置/人格整体换掉：内存快照与心跳缓存都要重读。
        reloadSettings()
        heartbeat.clearCache()
        ensureEveryPersonaHasSession()
        bindMissingApiConfigs()
        return summary
    }

    /// 删除之后的共同收尾（本地模式与服务端模式都必须走同一串：媒体引用、默认人格、心跳）。
    private func finishPersonaDeletion(_ persona: PersonaEntity, outcome: PersonaDeletion.Outcome) {
        // 人已经删了，再决定头像文件要不要删：先删库行、再查引用，只有零引用才删文件。
        // 头像按内容哈希去重，可能与预设头像、我的头像或某条消息的图共用同一个文件；
        // 这里必须走 Kit 的 `deleteIfUnreferenced`，不能在 App 层复制一份引用查询。
        MediaStorage.shared.deleteIfUnreferenced(
            persona.avatarUri,
            references: MediaReferences(stores: stores, settings: settings)
        )
        // 「正在聊的人」若指向被删的人：交还给新的默认人格（一个都不剩时清空）。
        if outcome.activePersonaWasDeleted {
            updateSettings { $0.activePersonaId = outcome.nextDefaultPersonaId }
        }
        // 心跳快照里还有那个人的心情/互动时间：清掉，下一拍重新读。
        heartbeat.clearCache()
        DebugSeeding.log("[persona] 删掉了 \(persona.id)：回声 \(outcome.echoes) 条、思考 \(outcome.ticks) 条、记忆版本 \(outcome.memoryVersions) 条")
    }

    /// 把「没有绑定 API 配置」的会话补上默认配置。
    ///
    /// 什么时候会出现这种会话：从 Android 备份接回来（那边可能没绑）、老版本建的、
    /// 以及「先建会话再配 API」的顺序。不补的话，用户点进会话发消息只会得到一句
    /// 「请先为该会话选择 API 配置」——那是内部错误的措辞，不该甩给用户。
    func bindMissingApiConfigs() {
        guard let configId = defaultApiConfigId() else { return }
        let sessions = (try? stores.sessions.all()) ?? []
        for session in sessions where (session.apiConfigId ?? "").isEmpty {
            var fixed = session
            fixed.apiConfigId = configId
            try? stores.sessions.upsert(fixed)
        }
    }

    // MARK: - 后台心跳与补算

    /// 最近一次每日整理的时间（补算判据）。
    ///
    /// 取「所有会话里最新的一条整理记录」而不是当前人格：换人格之后仍然不该一天整理两次。
    func lastConsolidatedAt() -> Int64? {
        let personas = (try? stores.personas.all()) ?? []
        var latest: Int64?
        for persona in personas {
            guard let record = (try? stores.memoryConsolidations.latest(personaId: persona.id)) ?? nil else { continue }
            if latest == nil || record.at > latest! { latest = record.at }
        }
        return latest
    }

    /// 上一次「想一下」的时间（心跳 / 自主循环共用 `agent_ticks`）。
    func lastHeartbeatAt() -> Int64? {
        agentLoop.lastTickAt()
    }

    /// 现在该补什么？纯判定（`BackgroundCatchUp`，有单测），这里只把库里的时间喂进去。
    func catchUpPlan(isForeground: Bool = true) -> BackgroundCatchUpPlan {
        BackgroundCatchUp.plan(
            lastHeartbeatAt: lastHeartbeatAt(),
            lastConsolidatedAt: lastConsolidatedAt(),
            now: DomainClock.nowMillis,
            isForeground: isForeground
        )
    }

    /// App 回到前台时补算一次：错过了每日整理就补整理，错过太多拍就补一次「想一下」。
    ///
    /// 这是 iOS 上唯一能「补回错过的时间」的地方（系统给的后台时间片不可控），
    /// 而且只补**一次**：不假装能补回 6 小时里的 12 拍——那样只会一口气烧掉一堆 token。
    ///
    /// **重入保护**（0.10.2 审计 P1-2）：冷启动时 `.task` 与 `scenePhase == .active` 会各触发
    /// 一次，两次读到同一个 `lastHeartbeatAt` / `lastConsolidatedAt`，于是都判「该补」。
    /// 现在同一时刻只允许一次，且两次之间至少隔 `CatchUpGuard.defaultMinimumIntervalMillis`。
    func performCatchUp() async {
        let now = DomainClock.nowMillis
        guard catchUpGuard.begin(now: now) else {
            DebugSeeding.log("[bg] 已经在补算了（或 30 秒内刚补过），这次不重复")
            return
        }
        defer { catchUpGuard.finish() }
        let plan = catchUpPlan(isForeground: true)
        DebugSeeding.log("[bg] 回到前台补算：\(plan.note)")
        if plan.shouldConsolidate {
            _ = await runDailyConsolidation()
        }
        if plan.shouldTick {
            _ = await runBackgroundTick()
        }
    }

    // MARK: - 前台 30 秒一拍（0.10.2 审计 P1-3）

    /// 进前台：起定时器。已经在跑就什么都不做（重复调用是安全的）。
    ///
    /// 为什么是 `Task` + `Task.sleep` 而不是 `Timer`：它跟着 `scenePhase` 启停，
    /// 进后台时 `cancel()` 立刻生效；`Timer` 在后台会被系统挂着，开销和语义都更差。
    func startForegroundPulse() {
        isForeground = true
        serverForeground.set(true)
        guard foregroundPulseTask == nil else { return }
        let hasConfig = resolvedChatConfig() != nil
        let hasPersona = currentPersona() != nil
        guard ForegroundPulse.shouldRun(isActive: true, hasApiConfig: hasConfig, hasPersona: hasPersona) else {
            DebugSeeding.log("[bg] 前台心跳没起来：\(hasConfig ? "还没有可用人格" : "还没有 API 配置")")
            return
        }
        foregroundPulseTask = Task { [weak self] in
            while !Task.isCancelled {
                let interval = self?.heartbeat.checkIntervalMillis() ?? HeartbeatController.beatIntervalMillis
                do {
                    try await Task.sleep(nanoseconds: UInt64(max(interval, 0)) * 1_000_000)
                } catch {
                    return
                }
                guard let self, !Task.isCancelled else { return }
                // `scenePhase` 已经变了但 `onChange` 还没跑完时，别多跑一拍。
                guard self.isForeground else { return }
                _ = await self.runBackgroundTick()
            }
        }
        DebugSeeding.log("[bg] 前台心跳已启动：每 \(foregroundPulseIntervalMillis / 1000) 秒一拍")
    }

    /// 进后台：停定时器（系统会挂起进程，留着只是白占资源）。
    func stopForegroundPulse() {
        isForeground = false
        serverForeground.set(false)
        foregroundPulseTask?.cancel()
        foregroundPulseTask = nil
        // 回到前台时重新读一次真实状态（心情、最后互动），别拿几分钟前的快照掷骰子。
        heartbeat.clearCache()
        DebugSeeding.log("[bg] 前台心跳已停止")
    }

    /// 当前一拍的间隔（低电量 2 分钟 / 平时 30 秒）。日志与界面用。
    var foregroundPulseIntervalMillis: Int64 { heartbeat.checkIntervalMillis() }

    /// 主动对话此刻被哪道闸挡着（`ProactiveDiagnosis`，只读）。
    ///
    /// 设置页 / 调试面板用它回答「他为什么不主动找我」；`runBackgroundTick` 在
    /// 「没有 API 配置」时也会把它写进日志，免得用户只看到一句「这次不想事情」。
    func proactiveDiagnosis() -> ProactiveDiagnosis {
        heartbeat.explain()
    }

    /// API 配置 / 人格 / 设置在运行中变了之后，重新评估前台心跳该不该跑。
    ///
    /// 真机 bug（0.13 第一批）：冷启动时还没有 API 配置，`startForegroundPulse()` 直接放弃；
    /// 用户在设置里加完配置后什么都没重新触发它 —— 于是「主动消息」要等到 App 被杀掉重开、
    /// 或切一次后台再回来才开始工作。现在每个「能让条件成立」的写路径都调这里（幂等）。
    func refreshProactivePulse() {
        guard isForeground else { return }
        startForegroundPulse()
    }

    /// 跑一次「想一下」：到点的主动规则变成念头 → 心跳掷一次 → 该说话就写消息（通知在 `AgentLoop` 回调里）。
    ///
    /// - Returns: 这次有没有真的动过（BGTask 的 `success` 用它）。
    @discardableResult
    func runBackgroundTick() async -> Bool {
        // 服务端模式：主动由服务端负责，本机不跑心跳（否则会出现双份主动消息）。
        guard LocalAutonomy.shouldRunLocalTick(serverModeActive: serverModeActive) else {
            DebugSeeding.log("[bg] 服务端模式：跳过本机心跳（主动由服务端负责）")
            return false
        }
        guard resolvedChatConfig() != nil else {
            DebugSeeding.log("[bg] 还没有 API 配置，这次不想事情 · \(proactiveDiagnosis().logLine())")
            return false
        }
        let now = DomainClock.nowMillis
        let materialized = intents.materializeDueRules(now: now)
        if materialized > 0 { DebugSeeding.log("[bg] \(materialized) 条主动规则到点，转成了念头") }
        let beat = await heartbeat.beat(now: now)
        DebugSeeding.log("[bg] 心跳：\(beat.fired ? "起心动念" : "没动") · \(beat.why)")
        return beat.fired || beat.acted
    }

    /// 每日整理一次（`BGProcessingTask` 与「补算」共用）。失败不抛：只写日志。
    @discardableResult
    func runDailyConsolidation() async -> Bool {
        // 服务端模式：每日整理由服务端负责；本机再整理一遍只会烧一次模型调用。
        guard LocalAutonomy.localAutonomyAllowed(serverModeActive: serverModeActive) else {
            DebugSeeding.log("[bg] 服务端模式：跳过本机每日整理")
            return false
        }
        // 「昨日整理」一天只该有一次：BGProcessingTask 与回前台补算会撞车
        //（两次整理 = 两次模型调用 + 两条 memory_consolidations 记录）。
        guard !isConsolidating else {
            DebugSeeding.log("[bg] 已经在整理记忆了，这次跳过")
            return false
        }
        guard let resolved = resolvedChatConfig() else {
            DebugSeeding.log("[bg] 还没有 API 配置，这次不整理记忆")
            return false
        }
        guard let persona = currentPersona() else { return false }
        isConsolidating = true
        defer { isConsolidating = false }
        let result = await consolidator.consolidate(
            personaId: persona.id,
            config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
            model: resolved.model
        )
        DebugSeeding.log("[bg] 每日整理：\(result.describe())")
        return result.didAnything
    }

    /// 启动自检：清掉库里那些不像记忆的旧摘要（带工具协议形状的残句）。
    @discardableResult
    func repairHistorySummariesIfNeeded() -> Int {
        let cleared = historyCompression.repairStaleSummaries()
        if cleared > 0 { DebugSeeding.log("[history] 清掉了 \(cleared) 条坏掉的旧摘要") }
        return cleared
    }

    /// 当前人格：设置里选中的那个；没有就用最近更新的那个。
    func currentPersona() -> PersonaEntity? {
        if let activeId = currentSettings.activePersonaId,
           let persona = (try? stores.personas.byId(activeId)) ?? nil {
            return persona
        }
        return (try? stores.personas.defaultPersona()) ?? nil
    }

    // MARK: - 路径与配置

    private static func databasePath() throws -> String {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("yap.db").path
    }

    /// 网络配置：超时、缓存策略。
    ///
    /// 禁用 URLSession 的透明重试：聊天是流式请求，重试会让「同一句话被回两次」。
    private static func sessionConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 900
        config.waitsForConnectivity = false
        config.urlCache = nil
        return config
    }

    /// 显示用的版本号：**只从 bundle 取**（`MARKETING_VERSION`），代码里不写死。
    /// 万一取不到（预览、测试宿主没有 Info.plist）再退回当前版本，免得界面上出现空白。
    static var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0.10.0"
    }
}


/// 装配期的一个小盒子：`AgentLoop` 的回调要读 `chat`，而 `chat` 在它之后才建好。
private final class ChatServiceBox {
    var chat: ChatService?
}

/// 「App 在前台吗」的线程安全镜像（服务端事件流会在后台线程问它）。
final class ServerForegroundFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = true

    func set(_ active: Bool) {
        lock.lock()
        value = active
        lock.unlock()
    }

    var isActive: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// `init` 里不能捕获 `self`（会触发「used before being initialized」）。
/// 用一个小盒子在装配完成后回填：闭包捕获盒子（无 `self`），盒子在 init 末尾拿到 self。
final class AppEnvironmentBox {
    weak var env: AppEnvironment?
}
