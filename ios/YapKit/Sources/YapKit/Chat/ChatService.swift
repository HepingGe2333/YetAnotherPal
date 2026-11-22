import Foundation

// ---------------------------------------------------------------------------
// 聊天主链路（只做**无状态兼容模式**）。
//
// 来源：Android `data/repo/ChatRepository.kt` 的
//   sendUserMessage → generateAssistant → saveInterrupted/saveFailed
// 以及它依赖的那几件小事（下一次时间戳、自动标题、未读计数、生成后异步触发的后台任务）。
//
// 刻意**没有**搬过来的部分（见交付报告）：
//   - 图片消息：`AttachmentsCodec`/`MediaStorage` 还没移植，`attachmentsJson` 原样保留但不发给模型；
//     1.9 起「原生 Harness」整组删除（实体、备份、DDL 都不再有那三列），相关分支也就不存在了；
//   - 主动唤醒提案的**落库**（ProactiveProtocol）：正文里的 `yap-wake` 代码块会被剥掉
//     （不能显示给用户），提案 JSON 通过 `ChatCompletion.proactiveProposalJson` 交回给上层；
//   - 通知、前台状态：属于 App 层，用 `chatEvents` 出来的事件驱动。
//
// 保险栓与 Android 一致：模型没吐出任何文字、或者一轮工具跑完仍然没说话，都按失败处理并落库，
// 界面上看到的是「一条失败的回复 + 可以重试」，而不是一个静默消失的气泡。
// ---------------------------------------------------------------------------

/// 这一轮他**选择不回**：不产生助手消息，只留下「已读」或者什么反应都没有。
///
/// 1.9 新增的三种「沉默」都要能被界面认出来（§四、§六）：不是失败、不是还在输入，
/// 而是「这条消息他看到了，但这一次没回」。
public enum ChatNoReplyReason: Equatable, Sendable {
    /// 在睡、开了免打扰：不回复、不唤醒、不留已读。
    case sleepingDoNotDisturb
    /// 在睡、没开免打扰，但被叫醒的连续消息数还不够。
    case sleepingWaiting(wakeAt: String)
    /// 模型用 `defer_reply` 说「现在不想说话」：过 `minutes` 分钟再决定开不开口。
    case deferred(minutes: Int, reason: String)
    /// 模型用 `stay_silent` 说「这一轮就不回」：不落消息、不留念头，只标已读
    ///（`spec/mood/README.md` §4）。
    case stayedSilent
    /// 0.14：他在拉黑期（`spec/experience/README.md` §2）。用户消息照常落库并标 `blocked`，
    /// 但这一轮**不生成、不回**——界面看到的是「对方已将你拉黑」，不是「正在输入」。
    case blocked

    /// 界面要不要在他没回的时候显示「已读」。
    public var showsRead: Bool {
        if case .sleepingDoNotDisturb = self { return false }
        // 拉黑期间的消息不会进模型上下文（永久排除），所以也不算「已读」。
        if case .blocked = self { return false }
        return true
    }

    /// 界面**不显示**任何解释性文案（§四）：沉默就是沉默。
    public func describe() -> String { "" }

    /// 输入框上方那行提示要用的「几点醒」（0.12 续 7）；不该显示时返回 `nil`。
    ///
    /// 只有一种情况该显示：这一轮因为「睡着、还没到唤醒阈值」被静默（`.sleepingWaiting`），
    /// 且**此刻**他确实还在睡。所以：
    /// - 免打扰（`.sleepingDoNotDisturb`）不显示 —— 那时候叫多少条都不会醒，
    ///   说「发几条就能叫醒」是错的；
    /// - `defer_reply` 不显示 —— 他醒着，只是决定晚点再说；
    /// - 他已经醒了（时间走过起床点）不显示 —— 界面每 30 秒重算一次 presence，会自动消失。
    ///
    /// 返回的是「08:00」这样的钟点，不是整句：整句要按界面语言拼（`ChatViewModel`）。
    public func sleepHintWakeText(
        persona: PersonaEntity?,
        now: Int64 = DomainClock.nowMillis
    ) -> String? {
        guard case .sleepingWaiting(let wakeAt) = self, let persona else { return nil }
        let minute = ServiceTime.minuteOfDay(now)
        guard SleepSchedule.isAsleep(persona, minuteOfDay: minute) else { return nil }
        return wakeAt
    }
}

/// 生成过程的对外事件。
///
/// 为什么要流式事件而不是回调闭包：界面要「边流边画」，同时又要能被打断。
/// `AsyncThrowingStream` 天然带取消（用户点停止 → 取消 Task → 流结束）。
public enum ChatEvent: Equatable, Sendable {
    /// 当前**可见**的正文全文（不含协议块）。与 Android 的 `GenerationEvent.Delta` 同义：
    /// 传全文而不是增量，界面直接覆盖最后一条气泡，避免增量拼错。
    ///
    /// 0.16（`spec/tool-call-agents/README.md` §1）：新协议下模型不再直接输出正文，
    /// 所以这条事件多数时候是**空串**，只用来表示「他还在输入」；
    /// 真正的气泡由 `send_message` 落库后的 `.appended` 产生。
    /// 只有兼容回退（老模型 / 老提示词）时它才会带着正文出现。
    case delta(String)
    /// 他开始用一个工具（界面可以在这里显示「正在联网搜索…」）。
    case toolStarted(ToolTraceItem)
    /// 一个工具跑完了（成功或失败都算，失败时 trace.ok = false）。
    case toolFinished(ToolTraceItem)
    /// 这一轮结束：助手消息已落库。
    case done(ChatCompletion)
    /// 这一轮他没有说话（睡着了 / 免打扰 / 主动选择晚点回）。界面上只会看到「已读」。
    case noReply(ChatNoReplyReason)
    /// 0.14 连发：这一轮又落了一条**独立**的助手消息（`send_message` / 空行拆分）。
    ///
    /// 界面收到就立刻重读消息列表：多条气泡要逐条出现，而不是等这一轮结束一起冒出来。
    ///
    /// 0.16：`send_message` 完成（落库）后走的就是这条事件 —— 气泡在工具完成之后才出现。
    case appended(MessageEntity)
}

/// 一轮生成的结果。
public struct ChatCompletion: Equatable, Sendable {
    /// 已经落库的助手消息（`status` 为 COMPLETE / STOPPED / ERROR）。
    public var message: MessageEntity
    /// 这一轮用过的工具痕迹（已写进 `message.toolTraceJson`）。
    public var toolTraces: [ToolTraceItem]
    /// 该不该在后台抽取记忆（Android 是 `pendingMemory`：新增消息数到阈值）。
    public var pendingMemory: Bool
    /// 回复里带的主动唤醒提案（已从正文剥掉），上层可据此建规则；没有就是 nil。
    public var proactiveProposalJson: String?

    public init(
        message: MessageEntity,
        toolTraces: [ToolTraceItem] = [],
        pendingMemory: Bool = false,
        proactiveProposalJson: String? = nil
    ) {
        self.message = message
        self.toolTraces = toolTraces
        self.pendingMemory = pendingMemory
        self.proactiveProposalJson = proactiveProposalJson
    }
}

/// 工具执行器。
///
/// `ToolExecutor`（联网搜索、读网页、记住、改人设、设作息、设心情、回忆）是另一批模块的活；
/// 聊天服务只负责「按协议呼出去、把结果回灌」。默认实现老老实实说自己做不了，
/// 这样模型和用户都不会被一个静默空结果骗过去。
public protocol ToolRunning: AnyObject {
    /// - Parameter userMessageId: 这一轮那条用户消息的 id（工具据此判断「是不是下一轮」，
    ///   例如 `block_user` 的两轮确认；没有用户消息时为 nil）。
    func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult
}

/// 默认工具执行器：iOS 还没接入任何工具。
public final class UnavailableToolRunner: ToolRunning {
    public init() {}

    public func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?
    ) async -> ToolResult {
        let name = invocation.name
        return ToolResult(
            invocation: invocation,
            ok: false,
            summary: String(localized: "这个工具还没接上"),
            contentForModel: "工具「\(name)」在当前版本里还用不了。不要假装做过，直接用自己的话回答。",
            errorMessage: String(format: String(localized: "iOS 端还没有接入工具：%@"), name)
        )
    }
}

/// 生成结束后要在后台做的事（记忆抽取、历史压缩）。
///
/// 为什么用回调而不是直接依赖 `MemoryExtraction` / `HistoryCompressor`：
/// 这两件事都是「另一条链路」（要模型、要一次完整的后台调用），把它们塞进聊天服务会让
/// 「回一句话」依赖「整理记忆」的实现细节。聊天服务只负责**说一声「该做了」**，
/// 而且是在回复落库之后（不阻塞回复，慢也晚不了一毫秒）。
public protocol ChatBackgroundTasks: AnyObject {
    /// 这一轮聊完了：考虑从这段对话里抽取记忆。
    func memoryExtractionNeeded(sessionId: String, personaId: String)
    /// 这一轮聊完了：考虑把已经聊过去的一段压成滚动摘要。
    func historyCompressionNeeded(sessionId: String)
    /// 这一轮聊完了：考虑跑一次人格进化评估（每 20 条用户消息或 24 小时，
    /// 见 `docs/design/persona-evolution.md`）。默认什么都不做，老调用点不用改。
    func personaEvolutionNeeded(sessionId: String, personaId: String)
    /// 这一轮聊完了：考虑跑一次**自动情绪判定**（每 4 条用户消息或 24 小时，
    /// 见 `spec/mood/README.md` §2）。默认什么都不做，老调用点不用改。
    func moodJudgementNeeded(sessionId: String, personaId: String)
    /// 这一轮聊完了：考虑更新会话备忘录（每 5 / 30 条用户消息，0.14 规范 §6）。
    func sessionMemoryNeeded(sessionId: String, personaId: String)
    /// 这一轮聊完了：考虑做一次心理活动（每 3 分钟 / 3 轮，0.14 规范 §3）。
    func mentalActivityNeeded(sessionId: String, personaId: String)
}

public extension ChatBackgroundTasks {
    /// 默认实现：没接进化评估时，回复绝不会因此变慢或失败。
    func personaEvolutionNeeded(sessionId: String, personaId: String) {}
    /// 默认实现：没接情绪判定时，回复绝不会因此变慢或失败。
    func moodJudgementNeeded(sessionId: String, personaId: String) {}
    /// 默认实现：没接备忘录时，回复绝不会因此变慢或失败。
    func sessionMemoryNeeded(sessionId: String, personaId: String) {}
    /// 默认实现：没接心理活动时，回复绝不会因此变慢或失败。
    func mentalActivityNeeded(sessionId: String, personaId: String) {}
}

/// 默认空实现：功能没接上时，回复绝不会因此变慢或失败。
public final class NoBackgroundTasks: ChatBackgroundTasks {
    public init() {}
    public func memoryExtractionNeeded(sessionId: String, personaId: String) {}
    public func historyCompressionNeeded(sessionId: String) {}
}

public final class ChatService {

    /// 一次回复里默认最多连着用几轮工具，用完就必须说话。
    ///
    /// 0.15 起转发共享契约：默认 5、模型可以用 `think.rounds` 指定、硬上限 15
    ///（`ExperienceContract.clampToolRounds`；与 Android / agentd 同一份数字）。
    public static let maxToolRounds = ExperienceContract.DEFAULT_TOOL_ROUNDS
    /// 自动记忆的间隔兜底值：人格上没填（老数据）时用这个（§三：`extractionEvery` 默认 6）。
    public static let memoryInterval = PersonaFieldLimits.extractionEveryDefault
    /// 消息长度上限（Android 的 `require(content.length <= 32_000)`）。
    public static let maxMessageLength = 32_000

    /// 这一轮的工具轮数上限：默认 5；模型在 `think.rounds` 里自报时夹到 1..15。
    ///
    /// 纯函数（不碰网络/时钟），所以「默认 5 / 模型指定 / 上限 15」能被直接钉住，
    /// 不用构造一整条聊天链路。`nil`/0/负数 = 没指定（保持默认），不把一次坏参数变成「只能 1 轮」。
    public static func toolRoundLimit(requested: Int?) -> Int {
        guard let requested, requested > 0 else {
            return ExperienceContract.shared.clampToolRounds(requested: nil)
        }
        return ExperienceContract.shared.clampToolRounds(requested: requested)
    }

    private let stores: DatabaseStores
    private let apiKeys: ApiKeyStore
    private let settings: SettingsProviding
    /// 记账在**客户端**那一侧（`OpenAICompatibleClient` 持有 `UsageReporter`），
    /// 与 Android 的 `OpenAiAdapter(reporting)` 完全对应：聊天服务不碰钱，只把 kind 传下去。
    ///
    /// 记忆召回。默认直接用 Services 那份 `MemoryRepository`（候选并集 → 本地重排 → 字数预算），
    /// 测试可以传一个「什么都搜不到」的实例，把注意力集中在对话本身。
    private let memory: MemoryRepository
    private let client: ChatCompleting
    private let tools: ToolRunning
    private let background: ChatBackgroundTasks
    private let now: () -> Int64
    /// 「他此刻在做什么」的来源。`PresenceRepository` 已经落在 Services 里，
    /// 但它还要 `AgentTickStoring` / `AgentIntentSupport` 两个依赖，装配属于 App 层的事；
    /// 这里留一个闭包，App 传 `{ presence.current(personaId: $0).detail }` 即可，nil = 不加这一段。
    private let presenceDetail: ((String) -> String?)?
    /// 心情：直接复用 Services 的 `MoodRepository`（读时顺手让时间冲淡、并落库）。
    private let mood: MoodRepository
    /// 拉黑（0.14）：读「此刻是不是在拉黑期」、把用户消息标成 blocked。
    private let block: BlockRepository
    /// 会话备忘录（0.14）：每轮读一次，作为「你的备忘录」注入 system。
    private let memo: SessionMemoryRepository
    /// 心理活动（0.14）：读最近一句念头注入 system（生成在后台，见 `MentalActivityService`）。
    private let mind: MentalActivityRepository
    /// 等一会儿（打字延迟 / 连发间隔）。生产是 `Task.sleep`；单测注入记录器，断言「延迟了多久」。
    private let sleep: (UInt64) async -> Void
    /// 说话节奏：被叫醒后的心情拉回、以及 `defer_reply` 落的念头。nil = 不落（测试常用）。
    private let rhythm: ChatRhythmHandling?
    /// 主动唤醒规则的落库（回复里剥出来的 `yap-wake` 提案）。nil = 只把 JSON 交给上层、不落库。
    private let proactive: ProactiveRuleWriting?
    /// 人格文本的来源：人设卡渲染（`PersonaCardRepository.systemText`）就接在这里；
    /// nil = 回落到 `PromptBuilder.personaSection(persona)`（1.9 的「## 你是谁」五小节）。
    private let personaText: ((PersonaEntity) -> String)?
    /// 排查用日志（App 层写进 Documents/debug.log）。nil = 不记。
    private let log: ((String) -> Void)?
    /// 「## 语言」小节的完整文本（App 层按界面语言从资源里取）。
    /// Kit 不自己读 `Locale` 与资源：提示词是产品，模板的决定权在装配层；nil = 不加这一节。
    private let languageDirective: (() -> String?)?
    /// 附件文件（沙盒里的图片）：把历史里最近几张图读成 data URL 交给多模态报文。
    /// 默认用 App 的 `Application Support/attachments`；单测可以换成临时目录。
    private let media: MediaStorage
    /// 语音/视频文件（`Application Support/audio|video`）：校验与读取原始字节。
    /// 单测可以换成临时目录。
    private let audioVideo: AudioVideoStore
    /// 把当轮附件变成多模态片段（音频 base64 / 视频关键帧）。
    ///
    /// 抽象成协议是为了单测不必真的解一段视频：生产用 `LocalMediaPartsBuilder`，
    /// 测试注入假实现断言「模型收到了哪些 parts」。
    private let mediaParts: MediaPartsBuilding
    /// agent 决策层（0.11.0）：睡眠判定与 `defer_reply` 计划走它。
    /// 默认本机实现（零网络）；App 装配时按 `AgentCoreClientFactory` 的选择结果注入。
    private let agentCore: any AgentCoreClient
    /// 当前界面语言（`zh` / `en`）。
    ///
    /// 只用于「提示词拼装时读哪一份译文缓存」；**触发翻译**在视图层（进聊天前 kick 一次，
    /// 见 `Views/Persona/PersonaTranslationTrigger.swift`），这样聊天生成不会被翻译拖慢，
    /// 单测里也不会因为翻译多发一次网络请求。
    private let translationLanguage: () -> String
    /// 危机两段式判定（0.15）：词表第一段 + 一次低温度模型复核 + 按消息 id 缓存。
    ///
    /// 放在 ChatService 上的原因只有缓存：它是**有状态**的（同一条消息重试不再问第二次），
    /// 每轮新建一个就等于每轮重问。判定逻辑本身在 `CrisisClassifier`（纯决策 + 可注入 provider），
    /// 这里只负责在正确的位置把当前 API 配置接成 `CrisisJudgeProvider`。
    private let crisis: CrisisClassifier

    private let lock = NSLock()
    private var runningGenerations: [String: RunningGeneration] = [:]
    private var openSessionId: String?

    public init(
        stores: DatabaseStores,
        apiKeys: ApiKeyStore,
        settings: SettingsProviding,
        client: ChatCompleting,
        memory: MemoryRepository? = nil,
        tools: ToolRunning = UnavailableToolRunner(),
        background: ChatBackgroundTasks = NoBackgroundTasks(),
        presenceDetail: ((String) -> String?)? = nil,
        mood: MoodRepository? = nil,
        block: BlockRepository? = nil,
        memo: SessionMemoryRepository? = nil,
        mind: MentalActivityRepository? = nil,
        sleep: ((UInt64) async -> Void)? = nil,
        rhythm: ChatRhythmHandling? = nil,
        proactive: ProactiveRuleWriting? = nil,
        personaText: ((PersonaEntity) -> String)? = nil,
        log: ((String) -> Void)? = nil,
        languageDirective: (() -> String?)? = nil,
        media: MediaStorage = .shared,
        audioVideo: AudioVideoStore = .shared,
        mediaParts: (any MediaPartsBuilding)? = nil,
        agentCore: any AgentCoreClient = AgentCoreClientFactory.makeNative(),
        translationLanguage: (() -> String)? = nil,
        crisis: CrisisClassifier = CrisisClassifier(),
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.stores = stores
        self.apiKeys = apiKeys
        self.settings = settings
        self.client = client
        self.memory = memory ?? MemoryRepository(stores: stores)
        self.tools = tools
        self.background = background
        self.presenceDetail = presenceDetail
        self.mood = mood ?? MoodRepository(stores: stores, now: now)
        self.block = block ?? BlockRepository(stores: stores, now: now)
        self.memo = memo ?? SessionMemoryRepository(stores: stores, now: now)
        self.mind = mind ?? MentalActivityRepository(stores: stores, now: now)
        self.sleep = sleep ?? { try? await Task.sleep(nanoseconds: $0) }
        self.rhythm = rhythm
        self.proactive = proactive
        self.personaText = personaText
        self.log = log
        self.languageDirective = languageDirective
        self.media = media
        self.audioVideo = audioVideo
        self.mediaParts = mediaParts ?? LocalMediaPartsBuilder(store: audioVideo)
        self.agentCore = agentCore
        self.translationLanguage = translationLanguage ?? { PersonaTranslationLanguage.current() }
        self.crisis = crisis
        self.now = now
    }

    // MARK: - 会话打开状态（未读计数用）

    public func setOpenSession(_ sessionId: String?) {
        lock.lock()
        openSessionId = sessionId
        lock.unlock()
    }

    public func isSessionOpen(_ sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return openSessionId == sessionId
    }

    /// 有没有一轮生成正在跑（界面用来决定「发送」按钮是箭头还是方块）。
    public func isGenerating(sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return runningGenerations[sessionId] != nil
    }

    // MARK: - 发送 / 停止

    /// `send` 的结果：要么已经开始生成，要么**压根没落库**。
    ///
    /// 为什么需要这个区分（0.10.2 审计 P1-10）：参数/配置类错误（消息过长、会话被删、
    /// 配置没了）发生在落库之前，原来界面拿到的是「一条只会抛错的流」，
    /// 而它在此之前已经把草稿与已选图片清空了 → 用户刚打的字和刚选的图不可恢复，
    /// 磁盘上还留下没人引用的孤儿图。
    public enum SendOutcome {
        case started(AsyncThrowingStream<ChatEvent, Error>)
        /// 没落库：调用方应当把草稿/图片还给界面，并给一个可重试的提示。
        case rejected(Error)

        /// 一条「什么都不发生」的流（0.14 拉黑：消息落库了，但没有生成）。
        static func emptyStream() -> AsyncThrowingStream<ChatEvent, Error> {
            AsyncThrowingStream { continuation in continuation.finish() }
        }
    }

    /// 发一条用户消息，然后开始生成回复。
    ///
    /// 顺序与 Android 一致：**先把用户消息落库**，再生成。这样即使网络立刻失败，
    /// 用户那句「我说的」也还在，历史不会缺一块。
    ///
    /// 参数/配置类错误（没选 API、没填 Key、消息为空）会在流里以 `ApiException` 抛出，
    /// 且**不会**留下半截消息。
    ///
    /// 图片：`attachments` 是已经压好、存进沙盒的附件（`MediaStorage.saveImage` 的产物），
    /// 落库时写进 `messages.attachmentsJson`。正文可以为空——只发一张图也是一句话。
    public func send(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) -> AsyncThrowingStream<ChatEvent, Error> {
        switch sendChecked(sessionId: sessionId, text: text, attachments: attachments) {
        case .started(let stream):
            return stream
        case .rejected(let error):
            return Self.failedStream(error)
        }
    }

    /// 与 `send` 同一条路径，但**告诉调用方这次有没有落库**。
    ///
    /// 界面用这个入口：`.rejected` 时不改输入状态（草稿与图片引用原样留着，
    /// 文件也还在磁盘上，所以既不用重新压图，也不会产生孤儿文件）。
    public func sendChecked(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) -> SendOutcome {
        do {
            try appendUserMessage(sessionId: sessionId, text: text, attachments: attachments)
        } catch {
            return .rejected(error)
        }
        // 0.14 拉黑：消息照常落库（并标 blocked），但这一轮**不生成**。
        // 返回一条立刻结束的流，界面靠消息自带的 `blocked` 显示「对方已将你拉黑」。
        if let session = try? stores.sessions.byId(sessionId),
           block.isBlocked(personaId: session.personaId) {
            return .started(SendOutcome.emptyStream())
        }
        return .started(generate(sessionId: sessionId))
    }

    /// 停止当前这一轮。
    ///
    /// 已经吐出来的正文会被落成一条 `STOPPED` 消息（与 Android 的 `saveInterrupted` 一致）——
    /// 用户看到的是「他话说了一半」，而不是清空。
    public func stop(sessionId: String) {
        runningTask(sessionId)?.cancel()
    }

    // MARK: - 运行中任务表（锁只在这几个同步小函数里碰）

    /// 一轮正在跑的生成。用一个 class 当把手，是为了让「注册」可以发生在 Task 启动**之前**：
    /// 否则一轮极快的生成可能在注册之前就结束了，留下一个永远「正在生成」的假状态。
    private final class RunningGeneration {
        var task: Task<Void, Never>?
    }

    private func registerGeneration(_ generation: RunningGeneration, sessionId: String) {
        lock.lock()
        runningGenerations[sessionId] = generation
        lock.unlock()
    }

    private func attach(_ task: Task<Void, Never>, to generation: RunningGeneration) {
        lock.lock()
        generation.task = task
        lock.unlock()
    }

    /// 只有「还是这一轮」时才清掉；否则会误删下一轮的把手。
    private func clearGeneration(_ generation: RunningGeneration, sessionId: String) {
        lock.lock()
        if runningGenerations[sessionId] === generation {
            runningGenerations[sessionId] = nil
        }
        lock.unlock()
    }

    private func runningTask(_ sessionId: String) -> Task<Void, Never>? {
        lock.lock()
        defer { lock.unlock() }
        return runningGenerations[sessionId]?.task
    }

    // MARK: - 用户消息落库

    private func appendUserMessage(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) throws {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // 与 Android 一致：正文可以为空，只要至少有一张图/一段语音/一段视频。
        let images = attachments.filter { $0.isImage }.prefix(AttachmentsCodec.maxImagesPerMessage)
        let audios = Array(attachments.filter { $0.isAudio }.prefix(AttachmentsCodec.maxAudioPerMessage))
        let videos = Array(attachments.filter { $0.isVideo }.prefix(AttachmentsCodec.maxVideoPerMessage))
        // 语音/视频的时长与体积在**落库之前**校验：超限的附件不该先变成一条发不出去的消息，
        // 让用户点一次发送才看到「太长」。
        for attachment in audios + videos {
            try validatePlayableAttachment(attachment)
        }
        let keptAttachments = Array(images) + audios + videos
        guard !content.isEmpty || !keptAttachments.isEmpty else {
            throw ApiException(statusCode: nil, message: String(localized: "消息不能为空"))
        }
        guard content.count <= Self.maxMessageLength else {
            throw ApiException(statusCode: nil, message: String(localized: "消息过长"))
        }
        guard let session = try stores.sessions.byId(sessionId) else {
            throw ApiException(statusCode: nil, message: String(localized: "会话不存在"))
        }
        guard let configId = session.apiConfigId, !configId.isEmpty else {
            throw ApiException(statusCode: nil, message: String(localized: "请先为该会话选择 API 配置"))
        }
        guard try stores.apiConfigs.byId(configId) != nil else {
            throw ApiException(statusCode: nil, message: String(localized: "API 配置已不存在"))
        }
        let persona = try stores.personas.byId(session.personaId)
        let timestamp = try nextTimestamp(sessionId: sessionId)
        // 0.14 拉黑：这条用户消息是在拉黑期发的吗？落库时就定下来，之后不再改。
        // 「到期解除」不会把已落库的 blocked 清掉 —— 模型永久看不到它（规范 §2）。
        let blockedDuringSend = block.isBlocked(personaId: session.personaId, now: timestamp)
        let isFirstUserMessage = try stores.messages.count(
            where: "sessionId = ? AND role = ?", [.text(sessionId), .text(MessageRole.user.stored)]
        ) == 0
        let message = MessageEntity(
            id: UUID().uuidString,
            sessionId: sessionId,
            personaId: session.personaId,
            role: .user,
            content: content,
            createdAt: timestamp,
            updatedAt: timestamp,
            status: .complete,
            model: nil,
            edited: false,
            attachmentsJson: AttachmentsCodec.encode(keptAttachments),
            blocked: blockedDuringSend
        )
        // 首页那一行预览：只有附件的时候写「图片 / 语音 / 视频」（与 Android 的 `ifBlank` 同义）。
        let flattened = content.replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let fallbackPreview: String = {
            if !audios.isEmpty { return String(localized: "语音") }
            if !videos.isEmpty { return String(localized: "视频") }
            return String(localized: "图片")
        }()
        let preview = flattened.isEmpty ? fallbackPreview : flattened
        try stores.database.inTransaction {
            try stores.messages.upsert(message)
            // 在事务里**重读**一次会话：上面那次读是为了校验（配置、人格），而这两条语句之间
            // 后台任务可能已经写过摘要 / 抽记忆计数（它们写自己那几列，0.10.2 审计 P1-1）。
            // 拿旧快照整行写回会把它们冲掉；事务持有连接锁，所以这里读到的就是最新值。
            var updated = (try stores.sessions.byId(sessionId)) ?? session
            updated.lastMessagePreview = String(preview.prefix(90))
            updated.lastMessageAt = timestamp
            updated.unreadCount = 0
            updated.updatedAt = timestamp
            if isFirstUserMessage {
                updated.title = Self.autoTitle(content.isEmpty ? fallbackPreview : content)
            }
            _ = persona
            try stores.sessions.upsert(updated)
        }
    }

    /// 语音/视频附件的落库前校验：格式、时长、体积（服务端媒体不读本地文件）。
    ///
    /// `byteCount == 0` 有两种可能：文件已经不在本机（换机/清缓存），或者这是
    /// `/v1/media/<id>` 的服务端引用（字节在服务端）。后者不该在这里被拒。
    private func validatePlayableAttachment(_ attachment: MessageAttachment) throws {
        if ServerMediaReference.mediaId(from: attachment.uri) != nil {
            guard AttachmentsCodec.isAllowedMime(AudioVideoLimits.canonicalMime(attachment.mime)) else {
                throw ApiException(
                    statusCode: nil,
                    message: String(format: String(localized: "不支持这种附件格式（%@）"), attachment.mime)
                )
            }
            return
        }
        let bytes = audioVideo.byteCount(for: attachment)
        if let rejection = MediaAttachmentValidation.rejection(attachment: attachment, byteCount: bytes) {
            throw ApiException(statusCode: nil, message: rejection)
        }
        guard bytes > 0 else {
            throw ApiException(statusCode: nil, message: String(localized: "这个文件读不出来，重新录/选一次试试"))
        }
    }

    // MARK: - 生成

    private func generate(sessionId: String) -> AsyncThrowingStream<ChatEvent, Error> {
        AsyncThrowingStream { continuation in
            // 同一个会话只允许一轮在跑：再点一次发送不该让两条流互相踩。
            runningTask(sessionId)?.cancel()

            let generation = RunningGeneration()
            registerGeneration(generation, sessionId: sessionId)
            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                await self.runGeneration(sessionId: sessionId, continuation: continuation)
                self.clearGeneration(generation, sessionId: sessionId)
            }
            attach(task, to: generation)
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runGeneration(
        sessionId: String,
        continuation: AsyncThrowingStream<ChatEvent, Error>.Continuation
    ) async {
        var shownText = ""
        var traces: [ToolTraceItem] = []
        /// 这一轮是不是真的把音频/视频交给了模型（4xx 时要据此给出「听不了/看不了」的明确报错）。
        var sentAudio = false
        var sentVideo = false
        /// 这一轮工具里「已经替他说了一条消息」的那条（表情包的 `sticker.send`）。
        var producedMessage: MessageEntity?
        var completed = false
        var personaId = ""
        var model = ""
        /// 0.14：这一轮「首字显示前」的打字延迟有没有付过（每一轮工具回路重新计）。
        var typingDelayApplied = false
        /// 0.14：这一轮已经用 `send_message` 发出去几条（每轮最多 4 条）。
        var sentCount = 0
        /// 0.14：这一轮总共落了几条助手消息（工具连发 + 正文拆分），用于「只有首条付打字延迟」。
        var deliveredCount = 0
        /// 0.15：这一轮模型有没有调用过 `remember`。
        ///
        /// 用途只有一处：高置信自动记忆（`HighConfidenceMemory`）只在模型**没调** `remember`
        /// 时兜底补一条。调过就不再补——哪怕那次调用最后被筛查拒了（「这次先不记」是它自己的判断）。
        var rememberRequested = false
        do {
            let resolved = try resolveSession(sessionId: sessionId)
            let session = resolved.session
            let persona = resolved.persona
            let config = resolved.config
            personaId = persona.id
            model = session.model.isEmpty ? config.model : session.model

            // 0.14 拉黑：这一轮不生成（用户消息已经落库并标 blocked）。
            // 放在最前面：连模型都不叫，省一次请求，也不会让「正在输入」闪一下。
            if let blockState = block.state(personaId: persona.id, now: now()) {
                if let log {
                    log("[chat] 他在拉黑期（还有 \(max(0, (blockState.until - now()) / 60_000)) 分钟），这一轮不回")
                }
                continuation.yield(.noReply(.blocked))
                continuation.finish()
                return
            }

            // 0.11.0 P2-1：只从 SQLite 捞「最近 N 条」，不再整段读进内存再截尾巴。
            // 几千条的会话里这一步省下的是三份大字符串（`content` / `attachmentsJson` /
            // `toolTraceJson`）的全量物化 —— 每一轮回复都要付一次。
            // 0.14：拉黑期间的消息**永久不进模型上下文**（规范 §2）。在 SQL 里就筛掉，
            // 而不是读 200 条再 filter —— 拉黑很长时后者等于这一轮没有上下文可用。
            let recent = try stores.messages.recentVisible(
                sessionId: sessionId, limit: PromptBuilder.contextRecentLimit
            )
            guard recent.contains(where: { $0.role == .user }) else {
                throw ApiException(statusCode: nil, message: String(localized: "还没有可以回复的消息"))
            }

            // 睡眠/免打扰/唤醒阈值（§六）。判定放在「模型看到消息」之后、真正生成之前：
            // 这一步决定这一轮到底要不要说话，以及要不要留「已读」。
            let sleepTimestamp = now()
            let recentUserCount = (try? stores.messages.countUserMessages(
                personaId: persona.id, since: sleepTimestamp - SleepSchedule.wakeWindowMillis
            )) ?? 1
            // 0.11.0：判定走 agent 决策层（`AgentCoreClient`）。默认是本机实现，
            // 结果与原来的 `SleepGate.decide` 逐字一致；配成远程时由 `agentd` 判定，
            // 失败由 `FallbackAgentCoreClient` 回退本机。这里的 `try?` 只兜「严格远程」
            // 这种故意让错误冒出来的联调模式 —— 聊天不该因为内核不可用而失败。
            let sleepInput = SleepInput(
                sleepEnabled: persona.sleepEnabled,
                dndOnSleep: persona.dndOnSleep,
                sleepMinutes: persona.quietSleepMinutes,
                wakeMinutes: persona.quietWakeMinutes,
                nowMinutes: ServiceTime.minuteOfDay(sleepTimestamp),
                userMessagesInWindow: recentUserCount,
                wakeThreshold: persona.wakeThreshold
            )
            // 0.11.1 审计 P2-1：严格模式（`remote-strict`）的失败必须被看见。`decide` 在严格模式
            // 下记一条可见诊断后把错误原样抛出 —— 于是聊天会显示「生成失败」，而不是静默换成
            // 本机判定（那会让「agentd 真的被用上了」的联调验收变成假阳性）。
            // 普通 `remote` 模式仍静默回退本机（每次失败由 `FallbackAgentCoreClient` 记日志）。
            let sleepDecision = try await agentCore.decide(
                endpoint: "sleep-decision",
                nativeFallback: AgentCoreNativeFallback.sleepDecision(sleepInput)
            ) {
                try await agentCore.sleepDecision(sleepInput).replyDecision
            }
            guard sleepDecision.shouldReply else {
                if sleepDecision.marksRead {
                    // 看到了就是已读 —— 哪怕这一轮不回。免打扰是唯一的例外（§6.2）。
                    _ = try? stores.messages.markUserMessagesRead(sessionId: sessionId, at: sleepTimestamp)
                }
                let reason: ChatNoReplyReason
                switch sleepDecision {
                case .sleepingDoNotDisturb:
                    reason = .sleepingDoNotDisturb
                default:
                    reason = .sleepingWaiting(wakeAt: SleepSchedule.wakeClockText(persona))
                }
                continuation.yield(.noReply(reason))
                continuation.finish()
                return
            }
            if sleepDecision == .wokenUp {
                // 被叫醒了：心情拉回 + 留一句「本来在睡」（界面不提示这件事）。
                rhythm?.handleWokenUp(personaId: persona.id, at: sleepTimestamp)
            }

            // 模型真的会读到这些消息了：把用户消息标成已读（§2.3）。
            _ = try? stores.messages.markUserMessagesRead(sessionId: sessionId, at: now())

            let mediaStorage = media
            // 0.13 音视频：只有**当轮那一条**消息会把原始媒体交给模型。
            //
            // 历史里的语音/视频只留一行说明（`PromptBuilder.history` 会补「这条消息是一段语音…」）：
            // 每次都重发 base64 等于拿几 MB 的旧音频去烧上下文，而那些内容模型早就听过了。
            let currentUserMessage = recent.last(where: { $0.role == .user })
            /// 这一轮用户说的那句原文：高置信自动记忆与危机兜底都只看它（不改写、不猜）。
            let currentUserText = currentUserMessage?.content
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            /// 0.15 危机优先于沉默：用户这一轮说了「不想活 / 自杀 / 安眠药 / 割腕……」时，
            /// 下面的 `stay_silent` / `defer_reply` 都不再允许「这一轮什么都不回」。
            ///
            /// 只判一次：这一轮后面所有分支用的是同一句话（模型看到的就是它），
            /// 不在中途重算，免得出现「前半轮算危机、后半轮不算」的漂移。
            ///
            /// 0.15 两段式（体验审计跟进）：第一段仍是 `SafetyGuard.isCrisis` 的词表漏斗
            /// （不命中 = 零请求），命中之后才用**当前 API 配置**发一次低温度 completion
            /// 复核「这是不是真实的危机」（`CrisisClassifier`，超时 5s、结论按消息 id 缓存）。
            /// 结论同时决定两件事：`stay_silent` / `defer_reply` 要不要让路，以及回复末尾
            /// 要不要补安全提示 —— 两处必须用同一个布尔值，否则「模型判了 false、追加那句仍然生效」。
            ///
            /// 为什么是 `Task` 而不是当场 `await`（与 Android `CrisisClassifier.start` 同一种做法）：
            /// 词表不命中时它已经完成（零请求、零延迟），命中时最多 5 秒；如果挡在这里，
            /// 用户会先盯着「正在输入」等一次判定请求。放到后台跑，第一句回复不必等它，
            /// 真正需要结论的地方（沉默工具 / 收尾补提示）再 `await`。
            let crisisVerdict = Task { [crisis] in
                await crisis.isCrisis(
                    userText: currentUserText,
                    messageId: currentUserMessage?.id,
                    judge: crisisJudge(
                        config: config,
                        apiKey: resolved.apiKey,
                        model: model,
                        usageContext: UsageContext(personaId: persona.id, sessionId: sessionId)
                    )
                )
            }
            var currentMediaParts: [String: MediaWireParts] = [:]
            if let currentUserMessage {
                let playable = AttachmentsCodec.decode(currentUserMessage.attachmentsJson)
                    .filter { $0.isPlayableMedia }
                if !playable.isEmpty,
                   let parts = await mediaParts.parts(for: playable), !parts.isEmpty {
                    currentMediaParts[currentUserMessage.id] = parts
                    sentAudio = !parts.audio.isEmpty
                    sentVideo = !parts.images.isEmpty
                }
            }
            // 「这一轮」的坐标只取一次时钟：状态段那行「现在是…」、心情判定、历史时间戳
            // 都必须是同一个瞬间，否则跨零点的对话会出现「状态写 23:59、历史写 00:01」。
            let timestamp = now()
            // 0.15：提示词语言是**人格属性**，与页面语言无关（`languageTag`，schema v19）。
            let personaLanguage = PersonaLanguagePrompt.language(for: persona)
            let history = PromptBuilder.history(
                messages: recent,
                summarizedUpTo: session.historySummarizedUpTo,
                // 只有最近两条带图消息会真的把图交给模型（见 `AttachmentsCodec`）。
                imageResolver: { attachment in mediaStorage.dataURL(for: attachment) },
                // 当轮那一条的语音/视频（历史里不重复发 base64）。
                mediaParts: currentMediaParts,
                // 0.15：`[21:04] 用户：…` —— 每条历史都带本地时刻（跨天带日期）。
                now: timestamp,
                language: personaLanguage
            )
            // 用最近一句对方说的话当检索线索，挑「此刻最相关」的记忆。
            // 1.9 起记忆**内置开启**：没有开关可关（§一）。
            let recallQuery = String(
                (recent.last { $0.role == .user }?.content ?? "").prefix(300)
            )
            let memories: [MemoryEntity] = memory.getRelevant(
                personaId: persona.id, query: recallQuery
            )
            let memoriesBlock = memory.formatForPrompt(memories)

            // 心情只读一次：提示词那一段与「他今天话多话少」都由这一份状态换算出来。
            // `MoodRepository` 会顺手做两件事（与 Android 一致）：让时间冲淡、把「已经问过心情」记下来。
            let moodState = mood.current(personaId: persona.id, now: timestamp)
            let nudge = mood.reflectionNudge(personaId: persona.id, now: timestamp)
            // 0.14 §3：「你刚才心里想：…」——最近一次心理活动（不新鲜 / 没有就是空串）。
            // 0.15：非中文人格用对应语言的注入行（念头本身是数据，不翻译）。
            let mentalActivityBlock: String
            if personaLanguage == .zh {
                mentalActivityBlock = mind.promptLine(personaId: persona.id, now: timestamp)
            } else if let latest = mind.latest(personaId: persona.id),
                      timestamp - latest.at <= MentalActivityPolicy.promptFreshnessMillis {
                let line = PersonaLanguagePrompt.mentalActivityLine(
                    thought: latest.thought, language: personaLanguage
                )
                mentalActivityBlock = line.isEmpty ? "" : "\n\n" + line
            } else {
                mentalActivityBlock = ""
            }
            // 拉黑提示：让他知道「这段时间我没理他」不是「他没说话」。
            let blockNote = block.state(personaId: persona.id, now: timestamp).map {
                "\n\n" + BlockPolicy.promptNote($0, now: timestamp)
            } ?? ""

            // 提示词翻译（`spec/persona-translation/README.md`）：用**已有缓存**里的译文拼提示词。
            // 触发（kick）在进聊天时由视图层做，所以这一轮不会被翻译拖慢：
            // 第一次进来通常还没译文（用原文），翻译落地后下一轮回复就用译文。
            let translationLang = translationLanguage()
            let speakingPersona = PersonaTranslation.applyingCached(to: persona, lang: translationLang)

            // 人设引导（`spec/persona-guidance/README.md` §1）：世界 → 此刻状态 → 话风 → 关系，
            // 四段都从人格上那三个 JSON 列读出来；坏 JSON / 空字段都安静地整节不出现。
            let world = WorldPreset.decode(speakingPersona.worldJson)
            let speechStyle = SpeechStyle.decode(speakingPersona.speechStyleJson)
            let activity = PersonaActivity.decode(speakingPersona.activityJson, now: timestamp)
            // 0.15 上下文装配 v2（`docs/design/context-memory-v2.md` + `spec/context-profile/README.md`）：
            // 当前状态 → 引导 → 人设 → 工具 → 反AI → 记忆/备忘录 → 语言 → 边界 → 最近历史。
            //
            // 这里只负责**填字段**：段怎么排由 `PromptBuilder.PromptSection.stateFirstOrder`
            // 一处决定，记忆与主题由 `ContextAssemblerV2` 按预算插进去（都在它那里裁）。
            let sessionMemo = memo.memory(sessionId: sessionId)
            let promptSections = Dictionary(
                uniqueKeysWithValues: PromptBuilder.sections(SystemPromptInput(
                    personaText: personaText?(speakingPersona)
                        ?? PromptBuilder.personaSection(speakingPersona),
                    personaName: speakingPersona.name,
                    worldBlock: PromptBuilder.worldSection(world),
                    stateBlock: PromptBuilder.stateSection(
                        now: timestamp,
                        activity: activity,
                        presenceDetail: presenceDetail?(persona.id)
                    ),
                    speechStyleBlock: PromptBuilder.speechStyleSection(speechStyle),
                    relationshipBlock: PromptBuilder.relationshipSection(persona: speakingPersona),
                    boundaryBlock: PersonaLanguagePrompt.boundariesSection(language: personaLanguage),
                    antiAIPrompt: PersonaLanguagePrompt.antiAISection(language: personaLanguage),
                    mentalActivityBlock: mentalActivityBlock,
                    moodBlock: PersonaLanguagePrompt.moodSection(moodState, language: personaLanguage),
                    moodPaceBlock: MoodBehavior.promptLine(moodState, now: timestamp) + blockNote,
                    // 0.15 profile：这一轮的语气分寸（心情偏负/低唤醒 → g12，否则 g17）。
                    guidanceBlock: PromptGuidance.section(for: moodState, language: personaLanguage),
                    moodNudgeBlock: nudge ?? "",
                    proactiveCapability: ProactiveCapabilityPrompt.text,
                    toolsCapability: PersonaLanguagePrompt.capabilitiesSection(
                        webEnabled: config.webToolsEnabled, language: personaLanguage
                    ),
                    // 0.15 模型优化开关（schema v20）：DeepSeek 档注入 `# 工具使用`，
                    // GLM 走 lean（不注入），AUTO 按**当前实际模型名**推断。
                    // 与 App 上显示的那个档位同源：模型名取 session.model（可覆盖配置）；
                    // 没联网时那一段不提 web_search（与 Android `ModelProfilePrompt` 逐字一致）。
                    toolUsageBlock: ModelProfilePrompt.toolsSection(
                        profile: speakingPersona.modelProfile,
                        modelName: model,
                        webEnabled: config.webToolsEnabled,
                        language: personaLanguage
                    ),
                    // 0.15.7「# 回复节奏」：默认只发一条、每条 ≤18 字、先接情绪再说自己。
                    // 跟着人格语言走（中文人格读中文段，英文人格读英文段）。
                    replyPacingBlock: ReplyPacing.section(language: personaLanguage),
                    // 「用对方的语言回答」= 人格语言（不是页面语言）。
                    languageDirective: PersonaLanguagePrompt.languageSection(language: personaLanguage)
                )).map { ($0.section, $0.text) }
            )
            // 长期记忆：稳定事实（记忆库）+ 会话长期摘要 + 「更早记得的」滚动摘要，合计 ≤500。
            // 短期记忆：这几轮定下的计划/待办（≤200）。
            let longTerm = [
                memoriesBlock,
                sessionMemo?.longSummary ?? "",
                HistoryCompression.memoryBlock(
                    summary: session.historySummary,
                    summarizedUpTo: session.historySummarizedUpTo
                )
            ].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                .joined(separator: "\n")
            let assembled = ContextAssemblerV2.assemble(ContextV2Input(
                sections: promptSections,
                longTermMemory: longTerm,
                shortTermMemory: sessionMemo?.shortSummary ?? "",
                topics: sessionMemo?.topics ?? [],
                history: history,
                contextWindowTokens: PromptBuilder.contextTokenBudget(configured: nil),
                maxOutputTokens: config.inferenceParams().maxOutputTokens ?? ApiDefaults.maxOutputTokens,
                practicalChars: PromptBuilder.contextCharBudget,
                language: personaLanguage
            ))
            // 0.16 迁移兼容（`spec/tool-call-agents/README.md` §3）：上下文里还有旧版本直接说的
            // assistant 消息（没有 `toolTraceJson`）时，在 system 末尾补一句「那是旧版本说的，
            // 不代表你现在可以跳过工具」。旧消息本身**一个字都不改**（不补造痕迹）。
            var systemPrompt = assembled.system
            let legacyCount = OutboundProtocol.legacyAssistantMessageCount(recent)
            if legacyCount > 0 {
                systemPrompt += "\n\n" + OutboundProtocol.migrationNote(language: personaLanguage)
                log?("[chat] 迁移提示：上下文里有 \(legacyCount) 条旧版本直接说的消息，已补一句历史说明")
            }
            var conversation = [WireMessage(role: "system", content: systemPrompt)] + assembled.history
            var round = 0
            // 模型可以在 `think` 里说明这轮要几步工具；没说明就按默认 5，硬上限 15。
            var toolRoundLimit = ExperienceContract.shared.clampToolRounds(requested: nil)
            var executed = Set<String>()
            var finalBody = ""
            // 0.15.7 工具协议自动提醒（`ProtocolReminder`）：同一轮最多提醒 1 次。
            //
            // `protocolReminderTried` 是本轮已经用掉这次机会；`protocolReminderFallbackRaw`
            // 是提醒**之前**模型写下的那段原文——只在「提醒之后它还是什么都没说」时兜底，
            // 免得一次提醒把用户本来能看到的那句话弄丢。
            var protocolReminderTried = false
            var protocolReminderFallbackRaw = ""
            // 提醒之后的**那一次**请求不往界面上推字：模型刚才那句话已经显示过了，
            // 再推一遍会让同一句话闪两次（真机上是可见的抖动），而且还会再付一次打字延迟。
            // 只在这一次请求内为 true；工具回路恢复正常流式。
            var suppressStreaming = false
            // 0.16 思考强度（`spec/tool-call-agents/README.md` §2）：这一轮默认 low；
            // 模型调用 `think` 之后，这一轮的**后续请求**升到 high。
            // 变量在 `runGeneration` 里，所以下一轮（下一次生成）自然回到 low。
            var effort = ReasoningEffort.defaultEffort

            while true {
                var buffer = ""
                // 只有主 LLM 会话设置思考强度：辅助调用（危机判定、记忆抽取……）不经过这里。
                var requestParams = config.inferenceParams()
                requestParams.reasoningEffort = effort
                let stream = client.streamChat(
                    config: config,
                    apiKey: resolved.apiKey,
                    model: model,
                    messages: conversation,
                    params: requestParams,
                    extraHeaders: [:],
                    kind: UsageKind.chat,
                    usageContext: UsageContext(personaId: persona.id, sessionId: sessionId)
                )
                do {
                    for try await delta in stream {
                        if Task.isCancelled { throw CancellationError() }
                        buffer += delta
                        // 只把「还没写到的工具块」之前的部分推到界面，协议块永不示人。
                        let visible = ToolProtocol.visiblePrefix(buffer)
                        // 提醒后的重试只做协议判定，不再推字（见 `suppressStreaming`）。
                        if !suppressStreaming, visible != shownText {
                            // 0.14 §5：首字到达前先「打一会儿字」。
                            //
                            // 字数只能用**这一刻已经到达的字数**估（流式里没法预知整句长度）；
                            // 有工具回路时每轮重新付一次，多条 send_message 的精确延迟在落库前算。
                            // 失败与停止不会走到这里（流已经 throw / cancel），所以天然不延迟。
                            if !typingDelayApplied, !visible.isEmpty {
                                typingDelayApplied = true
                                await sleep(TypingDelay.nanos(
                                    seconds: TypingDelay.seconds(charCount: visible.count, mood: moodState)
                                ))
                            }
                            shownText = visible
                            continuation.yield(.delta(visible))
                        }
                    }
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    // 这一轮真的带了音频/视频时，provider 的 4xx 翻译成「这个模型听不了语音」
                    // 这种能看懂的错，而不是「请求被服务拒绝（400）」。不伪造转写、不静默降级。
                    throw MediaModality.map(error: error, hadAudio: sentAudio, hadVideo: sentVideo)
                }
                if Task.isCancelled { throw CancellationError() }
                // 这一次请求的「别推字」窗口到此为止（工具回路照常流式）。
                suppressStreaming = false

                let (body, calls) = ToolProtocol.extract(buffer)

                // `stay_silent`（`spec/mood/README.md` §4）：这一轮他只标已读、不产生消息。
                //
                // 与 `defer_reply` 一样必须在执行其它工具之前拦下来：决定了不回，就不该再顺手
                // 记一笔、查一次网页，更不该把半句话落成消息。区别只有一处：
                // **不落念头**（`defer_reply` 会留一个到点的 `agent_intents`）。
                //
                // 连续上限（≤2 轮）也在这里守：第 3 轮再调用时**不认**这次沉默，
                // 而是把「这轮得说句话」回灌给模型，让它继续生成——沉默是态度，不是掉线。
                if let silent = calls.first(where: {
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.staySilent
                }) {
                    let silentAt = now()
                    // 0.15 危机优先于沉默（体验审计 P0）：用户这一轮说的是「不想活 / 自杀 /
                    // 安眠药……」时，`stay_silent` 的「这一轮可以不回」不再成立 ——
                    // 求助被已读不回，比任何语气问题都严重。
                    //
                    // 这一轮**必须**留下一条看得见的助手消息：
                    // - 模型写了正文就用正文（缺安全锚点才补 1–2 句，已有锚点一字不改）；
                    // - 只有工具 / 什么都没写，就用本地化安全提示当唯一正文；
                    // - 已经用 `send_message` 发满 4 条时并进最后一条，不造第 5 个气泡。
                    // 留痕规则也一起改：不显示 `stay_silent` 工具卡（它根本没执行）、
                    // 不记「连续沉默」（他其实说过了话），计数清零。
                    if await crisisVerdict.value {
                        let reply = try deliverCrisisSafetyReply(
                            body: body,
                            userText: currentUserText,
                            language: personaLanguage,
                            sentCount: sentCount,
                            producedMessage: producedMessage,
                            sessionId: sessionId,
                            personaId: persona.id,
                            model: model,
                            traces: traces,
                            continuation: continuation
                        )
                        producedMessage = reply
                        completed = true
                        log?("[chat] 危机优先于沉默：stay_silent 不生效，这一轮照常回一句")
                        rhythm?.handleReplied(personaId: persona.id, at: silentAt)
                        continuation.yield(.done(ChatCompletion(
                            message: reply,
                            toolTraces: traces,
                            pendingMemory: false,
                            proactiveProposalJson: nil
                        )))
                        continuation.finish()
                        return
                    }
                    // 0.15 与 Android `StaySilent.canHonor` 对齐：这一轮**已经发出去过消息**时，
                    // 不再认这次沉默 —— 话都说了，再「装没回」只会让对方以为那条消息发错了。
                    let spokeThisTurn = producedMessage != nil
                    let allowed = (rhythm?.canStaySilent(personaId: persona.id, now: silentAt) ?? true)
                        && !spokeThisTurn
                    if allowed {
                        rhythm?.handleStayedSilent(
                            personaId: persona.id, sessionId: sessionId, at: silentAt
                        )
                        if let log {
                            let reason = StaySilent.cleanReason(silent.argument("reason"))
                            log("[chat] stay_silent：这一轮只标已读"
                                + (reason.isEmpty ? "" : "（\(reason)）"))
                        }
                        // 0.15 严格语义（体验审计 P1）：`stay_silent` 是「这一轮不回」，
                        // 模型同一轮里顺手写出来的正文**一个字都不该留给用户**。
                        // 流式阶段那段文字可能已经推给界面了（协议块永远在正文之后才到），
                        // 所以这里先推一次空正文把它抹掉，再发「没回」：
                        // - 不落库（下面直接 return，不经过 `saveAssistant`）；
                        // - 不留工具卡片（`stay_silent` 不执行、不进 `traces`）；
                        // - 只留下已读（用户消息在本轮开始时就标过了）。
                        shownText = ""
                        continuation.yield(.delta(""))
                        continuation.yield(.noReply(.stayedSilent))
                        continuation.finish()
                        return
                    }
                    // 已经连着沉默两轮 / 这一轮已经发过消息：这次不认，逼他说一句（哪怕很短）。
                    round += 1
                    if round > toolRoundLimit {
                        // 他还是坚持不说：按「模型没有返回任何文字」走失败流，
                        // 至少让用户可以重试，而不是静默地把这一轮吞掉。
                        throw ApiException(
                            statusCode: nil,
                            message: String(localized: "他这次一直没说话，再发一条试试")
                        )
                    }
                    let refusal = ToolResult(
                        invocation: silent,
                        ok: false,
                        summary: String(localized: "这一轮得说句话"),
                        contentForModel: spokeThisTurn
                            ? "你这一轮已经发出去过消息了，不能再用 stay_silent 装没回。"
                                + "把剩下的话说完（哪怕只有两三个字），或者直接结束这一轮。"
                            : "你刚刚已经连着两轮只标已读、没有说话了。这一轮必须说点什么，"
                                + "哪怕只有两三个字（「嗯」「在」「累」都行），别再用 stay_silent。",
                        errorMessage: spokeThisTurn
                            ? String(localized: "这一轮已经发过消息了，不能再沉默")
                            : String(localized: "不能连续沉默超过两轮")
                    )
                    shownText = ""
                    continuation.yield(.delta(""))
                    conversation.append(WireMessage(
                        role: "assistant",
                        content: buffer.trimmingCharacters(in: .whitespacesAndNewlines)
                    ))
                    conversation.append(MultimodalPayload.wireMessage(
                        role: "user",
                        content: ToolProtocol.renderResultsForModel([refusal]),
                        parts: nil
                    ))
                    continue
                }

                // `defer_reply`（§四）：这一轮他选择不说话。
                //
                // 必须在执行其它工具之前拦下来：他要是已经决定不回，就不该再顺手记一笔、
                // 查一次网页，更不该把半句话落成一条助手消息。用户消息此时已经是已读。
                if let deferred = calls.first(where: {
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.deferReply
                }) {
                    // 同一个 now 同时用于「计划」与「落库」：两次取时钟可能差几毫秒，
                    // 会让 earliestAt 与 agent_intents.createdAt 对不上。
                    let deferAt = now()
                    // 0.15 危机优先于沉默：用户这一轮在说「不想活」时，「过一会儿再说」
                    // 等于把求助晾在一边。这一轮照常落一条看得见的回复，并且：
                    // - **不留念头、不排窗口**（不调 `decide("defer-plan")`、不写 `agent_intents`）；
                    // - 连续沉默清零（他其实说过了话）。
                    if await crisisVerdict.value {
                        let reply = try deliverCrisisSafetyReply(
                            body: body,
                            userText: currentUserText,
                            language: personaLanguage,
                            sentCount: sentCount,
                            producedMessage: producedMessage,
                            sessionId: sessionId,
                            personaId: persona.id,
                            model: model,
                            traces: traces,
                            continuation: continuation
                        )
                        producedMessage = reply
                        completed = true
                        log?("[chat] 危机优先于沉默：defer_reply 不生效，这一轮照常回一句（不留念头）")
                        rhythm?.handleReplied(personaId: persona.id, at: deferAt)
                        continuation.yield(.done(ChatCompletion(
                            message: reply,
                            toolTraces: traces,
                            pendingMemory: false,
                            proactiveProposalJson: nil
                        )))
                        continuation.finish()
                        return
                    }
                    let deferMinutes = deferred.intArgument("minutes", default: DeferReply.defaultMinutes)
                    let deferReason = deferred.argument("reason")
                    // 0.11.0：夹取 / trim / 窗口计算走 agent 决策层（`/v1/core/defer-plan`）。
                    // 失败时的兜底只为「严格远程」联调模式准备，正常装配下有 Fallback 顶着。
                    let plan = try await agentCore.decide(
                        endpoint: "defer-plan",
                        nativeFallback: AgentCoreNativeFallback.deferPlan(
                            minutes: deferMinutes, reason: deferReason, now: deferAt
                        )
                    ) {
                        try await agentCore.deferPlan(
                            minutes: deferMinutes, reason: deferReason, now: deferAt
                        )
                    }
                    rhythm?.handleDeferredReply(
                        personaId: persona.id, sessionId: sessionId,
                        plan: plan, at: deferAt
                    )
                    continuation.yield(.noReply(.deferred(minutes: plan.minutes, reason: plan.reason)))
                    continuation.finish()
                    return
                }

                // 0.15 高置信自动记忆的判据：这一轮**模型有没有自己调 `remember`**。
                // 放在「轮数上限」判定之前：最后一轮里写的 `remember` 虽然不再执行，
                // 但模型确实做了这件事，自动兜底就不该再写一条重复的。
                if calls.contains(where: {
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.remember
                }) {
                    rememberRequested = true
                }

                if calls.isEmpty || round >= toolRoundLimit {
                    let pendingBody = body.isEmpty
                        ? (calls.isEmpty ? buffer.trimmingCharacters(in: .whitespacesAndNewlines) : "")
                        : body
                    // 0.15.7 工具协议自动提醒：这一轮**只有正文、没有任何工具调用**，
                    // 而且还没有用 `send_message` 发出去过任何一条 —— 先提醒模型一次，
                    // 让它把这句话用正确的方式说出来（见 `ProtocolReminder` 文件头）。
                    //
                    // 只提醒一次、且要求还有轮数余量（`round < toolRoundLimit`）：
                    // 提醒之后总要留一次「带着 tools 重新请求」的机会，否则提醒没有意义。
                    if calls.isEmpty,
                       !pendingBody.isEmpty,
                       !protocolReminderTried,
                       sentCount == 0,
                       producedMessage == nil,
                       round < toolRoundLimit {
                        protocolReminderTried = true
                        protocolReminderFallbackRaw = buffer
                        round += 1
                        if let log {
                            log("[chat] \(ProtocolReminder.logTag)：这一轮只输出了正文、没有调用工具，"
                                + "先提醒模型一次（\(pendingBody.count) 字）")
                        }
                        // 正文当成 assistant 交回去，再追加一条**不可见**的 user 提醒：
                        // 它只活在这一次请求的 `conversation` 里，不显示、不落库。
                        conversation.append(WireMessage(role: "assistant", content: pendingBody))
                        conversation.append(WireMessage(
                            role: "user",
                            content: ProtocolReminder.text(language: personaLanguage)
                        ))
                        // 界面上刚才那段文字**先留着**：提醒之后模型通常只是补一次工具调用；
                        // 真的改了措辞时，收尾那句 `finalText != shownText` 会把它替换掉。
                        // 这样既不会闪两次，也不会为同一句话付两次打字延迟。
                        suppressStreaming = true
                        continue
                    }
                    finalBody = pendingBody
                    break
                }
                round += 1
                // 模型自报的轮数（`think.rounds`）：只用来放宽/收窄这一次的上限，不越过 15。
                if let thinkCall = calls.first(where: {
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.think
                }) {
                    // `rounds` 可能是数字或数字字符串（模型两种都写过）：用工具参数的宽容解析，
                    // 0/没给都当「没指定」（保持默认 5），不把一次坏参数变成「只能 1 轮」。
                    toolRoundLimit = Self.toolRoundLimit(
                        requested: thinkCall.intArgument("rounds", default: 0)
                    )
                    // 0.16 思考强度（`spec/tool-call-agents/README.md` §2）：模型按了 `think`，
                    // 这一轮**后续请求**就升到 high（想清楚再开口，而不是每句话都多花钱）。
                    if effort != ReasoningEffort.afterThink {
                        effort = ReasoningEffort.afterThink
                        log?("[chat] 用了 think：这一轮后续请求 reasoning_effort=high")
                    }
                }
                var results: [ToolResult] = []
                for call in calls {
                    if Task.isCancelled { throw CancellationError() }
                    let fingerprint = call.name.trimmingCharacters(in: .whitespacesAndNewlines)
                        + "|" + call.argumentsJson.trimmingCharacters(in: .whitespacesAndNewlines)
                    if executed.contains(fingerprint) {
                        // 同一个调用这一轮已经做过：别再执行一次，只告诉它「刚做过」，
                        // 否则模型会陷进「查了又查」的循环。
                        results.append(ToolResult(
                            invocation: call,
                            ok: true,
                            summary: "刚刚已经做过",
                            contentForModel: "这个调用你刚刚已经做过了，结果就在上面。直接用那些结果把话说完。"
                        ))
                        continue
                    }
                    // 0.14 §4：`send_message` 每轮最多 4 条。超出的这次**不执行**，
                    // 只回灌一句「已经发够了」——比直接把多出来的消息丢掉更容易让模型收敛。
                    if call.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.sendMessage,
                       sentCount >= MessageRhythm.maxMessagesPerRound {
                        results.append(ToolResult(
                            invocation: call,
                            ok: false,
                            summary: String(localized: "这一轮已经发够了"),
                            contentForModel: "这一轮你已经发了 \(sentCount) 条消息（上限 \(MessageRhythm.maxMessagesPerRound) 条），"
                                + "这一条没有发出去。把还没说完的留到下一轮。",
                            errorMessage: String(localized: "一轮最多发 4 条")
                        ))
                        continue
                    }
                    executed.insert(fingerprint)
                    // 0.16（`spec/tool-call-agents/README.md` §1）：「流式 token 只做输入指示，
                    // 气泡在 `send_message` 完成后出现（**可参数预览**）」。
                    // 参数里的 `text` 就是马上要出现的那一句，所以先把它交给界面当预览；
                    // 真正的消息仍然等工具跑完、由 `deliverOutbound` 落库后推 `.appended`。
                    if call.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.sendMessage,
                       let preview = MessageRhythm.cleanSendText(call.argument("text")) {
                        continuation.yield(.toolStarted(ToolTraceItem(
                            name: ToolCatalog.sendMessage,
                            title: ToolProtocol.title(forTool: ToolCatalog.sendMessage),
                            subtitle: preview,
                            detail: "",
                            ok: true
                        )))
                    }
                    var result = await tools.run(
                        call, persona: persona, sessionId: sessionId,
                        userMessageId: currentUserMessage?.id
                    )
                    // 0.14 §4：`send_message` 的产物由**这里**落库（工具只交出文本），
                    // 这样才能按「打字延迟 / 连发间隔」控制每一条出现的时机。
                    if let outbound = result.outboundText,
                       let delivered = await deliverOutbound(
                           outbound,
                           sessionId: sessionId,
                           personaId: persona.id,
                           model: model,
                           mood: moodState,
                           payTypingDelay: deliveredCount == 0 && !typingDelayApplied,
                           continuation: continuation
                       ) {
                        sentCount += 1
                        producedMessage = delivered
                        // 0.16（§1）：工具结果回灌 `messageId`，模型据此知道这句真的发出去了、
                        // 可以接着发下一句（或者结束这一轮）。
                        result.contentForModel = OutboundProtocol.sentReceipt(messageId: delivered.id)
                        // 痕迹的展开正文用**发出去的那句话**，不把内部 id 露给用户。
                        result.traceDetail = outbound
                    }
                    if let producedId = result.producedMessageId,
                       let produced = (try? stores.messages.byId(producedId)) ?? nil {
                        producedMessage = produced
                    }
                    let trace = ToolTraceItem.from(result)
                    traces.append(trace)
                    // `send_message` 的「开始」已经带着参数预览发过了（同 name+title，
                    // 界面按这两项去重/替换），这里不再重复发一条空的开始事件。
                    if result.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
                        != ToolCatalog.sendMessage {
                        continuation.yield(.toolStarted(ToolTraceItem(
                            name: trace.name, title: trace.title, subtitle: nil, detail: "", ok: true
                        )))
                    }
                    continuation.yield(.toolFinished(trace))
                    results.append(result)
                }
                // 过程话不算数：先把气泡里的临时文字清干净，等最后那一轮的真话。
                shownText = ""
                // 下一轮的正文是「新的一条消息」：首字延迟重新算（规范 §5：连发之间同一公式）。
                typingDelayApplied = false
                continuation.yield(.delta(""))
                // `listen` / `watch` 的结果不只是文字：音频片段与视频关键帧要真的进这一条消息，
                // 否则模型只能看到「我听了一遍…」这句自己都没底的说明。
                var toolMedia = MediaWireParts()
                for result in results {
                    guard let parts = result.mediaParts, !parts.isEmpty else { continue }
                    toolMedia = toolMedia + parts
                    if result.invocation.name == ToolCatalog.listen, !parts.audio.isEmpty {
                        sentAudio = true
                    }
                    if result.invocation.name == ToolCatalog.watch, !parts.images.isEmpty {
                        sentVideo = true
                    }
                }
                conversation.append(WireMessage(role: "assistant", content: buffer.trimmingCharacters(in: .whitespacesAndNewlines)))
                conversation.append(MultimodalPayload.wireMessage(
                    role: "user",
                    content: ToolProtocol.renderResultsForModel(results)
                        + "\n\n（以上是你自己刚做完的事，对方看不见细节。现在接着把话说完，别重复，也别提工具。）",
                    parts: toolMedia.isEmpty ? nil : toolMedia
                ))
            }

            // 0.15.7：提醒之后模型**什么都没说**（既没有工具调用，也没有正文）时，
            // 用提醒之前那段正文兜底 —— 一次提醒不该让用户本来能看到的那句话消失。
            // 提醒之后仍然只写正文的情况走上面那条 `finalBody`，用的是它最新写的那段。
            let remindedOnly = finalBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !protocolReminderFallbackRaw.isEmpty
                // 工具已经替他说过话（`send_message` / 表情）时不能用提醒前那段兜底：
                // 否则同一句话会落成两条消息（用户看到两个一模一样的空气泡）。
                && producedMessage == nil
                && sentCount == 0
            let rawFinal = remindedOnly
                ? protocolReminderFallbackRaw
                : finalBody.trimmingCharacters(in: .whitespacesAndNewlines)
            if remindedOnly {
                log?("[chat] \(ProtocolReminder.logTag)：提醒之后没有输出，按提醒前的正文收尾")
            }
            // 主动唤醒提案（`yap-wake`）：代码块不能给用户看，解析成规则交回上层并落库。
            let extracted = ProactiveProtocol.extract(rawFinal)
            var finalText = extracted.body
            let proposal = extracted.proposal
            let proposalJson = extracted.payloadJson
            // 0.14 §4：4 条已经发满了，模型最后又补了一句正文时，**把它并进最后一条**，
            // 而不是再造第 5 个气泡（「每轮最多 4 条」是硬上限）。
            if MessageRhythm.maxMessagesPerRound - deliveredCount <= 0,
               !finalText.isEmpty,
               let lastDelivered = producedMessage {
                var merged = lastDelivered
                merged.content = (lastDelivered.content + "\n\n"
                    + finalText.trimmingCharacters(in: .whitespacesAndNewlines))
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if (try? stores.messages.upsert(merged)) != nil {
                    producedMessage = merged
                    finalText = ""
                    continuation.yield(.appended(merged))
                }
            }
            if finalText.isEmpty, let producedMessage {
                // 这一轮已经用工具发出去了一条消息（表情包的 `sticker.send`，或连发到上限后
                // 把正文并进最后一条）：模型不再写正文是正常用法，不是「走神」。
                // 直接把那条消息当成这一轮的产物结束掉，不要再落一条空助手消息。
                completed = true
                // 0.15 危机兜底：这一轮的话全从工具发出去的（连发 / 表情），安全锚点也得补上，
                // 否则用户看到的就是「他说了句别的，没接住我这句」。
                var finalMessage = producedMessage
                let anchored = SafetyGuard.appendingSafetyNetIfNeeded(
                    reply: finalMessage.content,
                    userMessage: currentUserText,
                    language: personaLanguage,
                    crisis: await crisisVerdict.value
                )
                if anchored != finalMessage.content {
                    finalMessage.content = anchored
                    if (try? stores.messages.upsert(finalMessage)) != nil {
                        continuation.yield(.appended(finalMessage))
                    } else {
                        // 没落库就保持库里那一份：库里存的与界面看到的必须是同一句话。
                        finalMessage = producedMessage
                    }
                }
                // 回复已经落库：高置信自动记忆兜底（模型这一轮没调 `remember` 时）。
                autoRememberIfNeeded(
                    userText: currentUserText,
                    personaId: persona.id,
                    sessionId: sessionId,
                    rememberRequested: rememberRequested
                )
                // 他其实说过了（用表情 / 连发）：连续沉默清零。
                rhythm?.handleReplied(personaId: persona.id, at: now())
                continuation.yield(.done(ChatCompletion(
                    message: finalMessage,
                    toolTraces: traces,
                    pendingMemory: false,
                    proactiveProposalJson: proposalJson
                )))
                continuation.finish()
                return
            }
            if finalText.isEmpty && proposalJson == nil {
                throw ApiException(
                    statusCode: nil,
                    message: traces.isEmpty
                    ? String(localized: "模型没有返回任何文字")
                    : String(localized: "他查完了东西，怕是走神了，再发一条试试")
                )
            }
            // 0.15 危机兜底（`SafetyGuard`）：用户这一轮说了「不想活 / 自杀 / 安眠药……」
            // 而这句话里没有任何安全锚点时，在正文末尾追加 1–2 句（只追加、不改写）。
            // 放在落库之前：用户看到的和库里存的必须是同一份（否则重进会话就消失了）。
            if !finalText.isEmpty {
                finalText = SafetyGuard.appendingSafetyNetIfNeeded(
                    reply: finalText,
                    userMessage: currentUserText,
                    language: personaLanguage,
                    crisis: await crisisVerdict.value
                )
            }
            // 最终正文可能和流里那一份不一样（去掉代码块、trim）：补一次，界面才对得上库里的内容。
            if finalText != shownText {
                shownText = finalText
                continuation.yield(.delta(finalText))
            }

            // 0.16 兼容回退（`spec/tool-call-agents/README.md` §1）：模型这一轮**没有**调用
            // `send_message`（也没有用表情之类的工具替他说话），却写了正文。
            //
            // 这条正文不是新路径 —— 客户端仍然把它当成一句话落库（老模型 / 老提示词还会这么说话，
            // 聊天不能因此变哑），但必须在日志里留下标记，否则「还有多少轮在走旧协议」永远看不出来。
            if sentCount == 0, producedMessage == nil {
                log?("[chat] \(OutboundProtocol.legacyFallbackLogTag)：这一轮没有 send_message，"
                    + "最终正文按旧协议落库（\(finalText.count) 字）")
            }

            // 0.14 §4：不走工具的回复按空行拆成 1–3 条（用了 `send_message` 时，正文也算一条，
            // 总数仍然不超过 4 条）。每一条都是**独立的助手消息**，逐条出现。
            let remainingSlots = MessageRhythm.maxMessagesPerRound - deliveredCount
            let chunkLimit = sentCount > 0
                ? min(max(remainingSlots, 1), 1)
                : min(MessageRhythm.maxSplitMessages, max(remainingSlots, 1))
            let chunks = MessageRhythm.split(finalText, max: chunkLimit)
            guard !chunks.isEmpty else {
                throw ApiException(statusCode: nil, message: String(localized: "回复没有保存成功，请重试"))
            }
            var saved: MessageEntity?
            for (index, chunk) in chunks.enumerated() {
                if Task.isCancelled { throw CancellationError() }
                let isLast = index == chunks.count - 1
                if deliveredCount == 0 && !typingDelayApplied {
                    // 首条：这一条此刻已知长度，按规范 §5 的完整公式延迟。
                    await sleep(TypingDelay.nanos(
                        seconds: TypingDelay.seconds(charCount: chunk.count, mood: moodState)
                    ))
                    typingDelayApplied = true
                } else if deliveredCount > 0 {
                    // 连发之间：0.8–2.5 秒（按长度与心情），期间界面显示「正在输入」。
                    await sleep(TypingDelay.nanos(
                        seconds: MessageRhythm.gapSeconds(charCount: chunk.count, mood: moodState)
                    ))
                }
                guard let message = try saveAssistant(
                    sessionId: sessionId,
                    personaId: persona.id,
                    content: chunk,
                    status: .complete,
                    errorMessage: nil,
                    model: model,
                    // 动作痕迹挂在最后一条上：与 `.done.toolTraces` 对齐，界面不会重复显示。
                    traces: isLast ? traces : []
                ) else {
                    throw ApiException(statusCode: nil, message: String(localized: "回复没有保存成功，请重试"))
                }
                deliveredCount += 1
                saved = message
                // 多条时才通知界面重读：单条走 `.done` 的统一重读，避免多余的一次查询。
                if chunks.count > 1 { continuation.yield(.appended(message)) }
            }
            guard let saved else {
                throw ApiException(statusCode: nil, message: String(localized: "回复没有保存成功，请重试"))
            }
            completed = true
            // 0.15 高置信自动记忆：回复已经落库了，再看要不要替模型补一条 `remember`。
            // 顺序刻意放在「回复落库之后」：这一步写库失败也绝不能让用户少看到那句话。
            autoRememberIfNeeded(
                userText: currentUserText,
                personaId: persona.id,
                sessionId: sessionId,
                rememberRequested: rememberRequested
            )

            let count = try stores.messages.count(sessionId: sessionId)
            let freshSession = try stores.sessions.byId(sessionId)
            // 抽记忆的频率现在归人格（`extractionEvery`，会话可覆盖，§三）：
            // 记性好、话多的人可以抽得勤一点，不用再让用户在设置里猜这个数字。
            let extractionEvery = (freshSession ?? session).effectiveExtractionEvery(persona: persona)
            let pendingMemory = count - (freshSession?.lastMemoryExtractedCount ?? 0) >= extractionEvery

            // 主动唤醒规则：他自己安排的节奏**直接生效**（与 Android 一致，不等用户点头）。
            // 落库失败不影响这一轮回复，但一定要留一行日志——不静默。
            if let proposal {
                persistProactiveProposal(
                    proposal: proposal, personaId: persona.id, sessionId: sessionId
                )
            }

            // 回复已经落库了，两件后台的事各自异步跑：慢也晚不了一毫秒回话，失败也当没发生。
            background.historyCompressionNeeded(sessionId: sessionId)
            if pendingMemory {
                background.memoryExtractionNeeded(sessionId: sessionId, personaId: persona.id)
            }
            // 人格进化（0.13）：节奏判定在服务里（每 20 条用户消息 / 24 小时），
            // 这里只负责「聊完这一轮，看看要不要评估」——不挡回复、失败只写日志。
            background.personaEvolutionNeeded(sessionId: sessionId, personaId: persona.id)
            // 自动情绪判定（0.13 schema v17）：每 4 条用户消息 / 24 小时一次，
            // 结果写库并在下一轮提示词里生效（`spec/mood/README.md` §2）。
            background.moodJudgementNeeded(sessionId: sessionId, personaId: persona.id)
            // 0.14 会话备忘录（每 5 / 30 条用户消息）：失败保留旧摘要，不阻塞回复。
            background.sessionMemoryNeeded(sessionId: sessionId, personaId: persona.id)
            // 0.14 心理活动（活跃窗口内每 3 分钟 / 3 轮）：生成在后台，下一轮提示词里生效。
            background.mentalActivityNeeded(sessionId: sessionId, personaId: persona.id)
            // 他说了话：连续沉默清零（`stay_silent` 的「连续 ≤2 轮」）。
            rhythm?.handleReplied(personaId: persona.id, at: timestamp)

            continuation.yield(.done(ChatCompletion(
                message: saved,
                toolTraces: traces,
                pendingMemory: pendingMemory,
                proactiveProposalJson: proposalJson
            )))
            continuation.finish()
        } catch is CancellationError {
            // 用户点了停止：把已经说出来的那半句落库，流正常结束（界面看消息状态就知道是停的）。
            //
            // 0.14 §1：**一个字都没吐出来时不落消息**。以前这里会存一条空的 STOPPED 消息，
            // 界面上就是一个空气泡（「不产生空消息」）。
            if !completed, !shownText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                _ = try? saveAssistant(
                    sessionId: sessionId,
                    personaId: personaId,
                    content: shownText,
                    status: .stopped,
                    errorMessage: nil,
                    model: model,
                    traces: traces
                )
            }
            continuation.finish()
        } catch {
            // 取消有时会以 `URLError.cancelled` 的形式冒出来：仍然按「用户停止」处理，
            // 否则界面会把一次主动停止显示成「网络连接中断」。
            if Task.isCancelled {
                if !completed, !shownText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    _ = try? saveAssistant(
                        sessionId: sessionId,
                        personaId: personaId,
                        content: shownText,
                        status: .stopped,
                        errorMessage: nil,
                        model: model,
                        traces: traces
                    )
                }
                continuation.finish()
                return
            }
            let message: String
            if let api = error as? ApiException {
                message = api.message
            } else {
                let localized = (error as NSError).localizedDescription
                message = localized.isEmpty ? String(localized: "生成失败") : localized
            }
            var saved: MessageEntity?
            // 0.14 §1：失败时同样不产生空消息。已经吐出来的半句仍然按失败落库
            //（那是「他说了一半，然后断了」，不是占位）；一个字都没有就只把错误抛给界面。
            if !completed, !shownText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                saved = try? saveAssistant(
                    sessionId: sessionId,
                    personaId: personaId,
                    content: shownText,
                    status: .error,
                    errorMessage: String(message.prefix(800)),
                    model: model,
                    traces: traces
                )
            }
            if let saved {
                continuation.yield(.done(ChatCompletion(
                    message: saved, toolTraces: traces, pendingMemory: false, proactiveProposalJson: nil
                )))
            }
            continuation.finish(throwing: error)
        }
    }

    // MARK: - 危机第二段判定（0.15）

    /// 把**当前这一轮**解析出来的 API 配置接成第二段判定用的 provider。
    ///
    /// 为什么不是构造时注入一个固定 provider：配置可以在两个会话之间完全不同
    ///（model / baseUrl / Key 都是会话级的），固定的 provider 会拿另一份配置去问。
    /// 这里每次生成拼一个闭包，成本是一次捕获，没有网络开销。
    ///
    /// 参数与聊天请求同源（`config` / `apiKey` / `model`），但采样刻意不同：
    /// 温度 0、输出上限 200 —— 判定要的是稳定的一个 JSON，不是创作。
    /// `kind` 用 `UNKNOWN`（与 Android `DefaultCrisisModel` 一致）：它是安全判定，
    /// 不该被算进「聊天」，也不该出现在「判断值不值得记」那一栏里。
    private func crisisJudge(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        usageContext: UsageContext
    ) -> CrisisJudgeProvider {
        { request in
            try await self.client.complete(
                config: config,
                apiKey: apiKey,
                model: model,
                messages: [
                    WireMessage(role: "system", content: request.system),
                    WireMessage(role: "user", content: request.userText)
                ],
                params: InferenceParams(
                    temperature: CrisisJudgement.temperature,
                    maxOutputTokens: CrisisJudgement.maxOutputTokens
                ),
                kind: UsageKind.unknown,
                usageContext: usageContext
            )
        }
    }

    // MARK: - 解决配置 / Key

    private struct ResolvedSession {
        var session: ChatSessionEntity
        var persona: PersonaEntity
        var config: ApiConfigEntity
        var apiKey: String?
    }

    private func resolveSession(sessionId: String) throws -> ResolvedSession {
        guard let session = try stores.sessions.byId(sessionId) else {
            throw ApiException(statusCode: nil, message: String(localized: "会话不存在"))
        }
        guard let persona = try stores.personas.byId(session.personaId) else {
            throw ApiException(statusCode: nil, message: String(localized: "人格不存在"))
        }
        guard let configId = session.apiConfigId, !configId.isEmpty else {
            throw ApiException(statusCode: nil, message: String(localized: "请先为该会话选择 API 配置"))
        }
        guard let config = try stores.apiConfigs.byId(configId) else {
            throw ApiException(statusCode: nil, message: String(localized: "API 配置已不存在"))
        }
        let key = apiKeys.apiKey(for: config.id)
        // 没有 Key 也不让请求白跑一趟：Android 会照发然后拿 401，
        // 但用户看到的同样是一句「API Key 无效」，不如在本地就把话说清楚（少一次往返、少一次计费）。
        let trimmedKey = key?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmedKey.isEmpty && !Self.hasCustomAuthorization(config) {
            throw ApiException(
                    statusCode: nil,
                    message: String(localized: "还没有填 API Key：请在设置里为这个 API 配置补上密钥")
                )
        }
        return ResolvedSession(
            session: session,
            persona: persona,
            config: config,
            apiKey: trimmedKey.isEmpty ? nil : trimmedKey
        )
    }

    /// 自定义 Header 里带了 Authorization 时，主 Key 可以为空（与 Android 的 header 优先级一致）。
    static func hasCustomAuthorization(_ config: ApiConfigEntity) -> Bool {
        guard let data = config.customHeadersJson.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return object.keys.contains { $0.caseInsensitiveCompare("Authorization") == .orderedSame }
    }

    // MARK: - 连发（0.14）

    /// 把一句 `send_message` 的正文按节奏落成**独立**助手消息，并让界面立刻看到它。
    ///
    /// 延迟规则（规范 §4/§5）：
    /// - 这一轮的第一条：`clamp(0.4 + 字数×0.025 + 心情修正, 0.4, 4.0)` 秒；
    /// - 之后每一条：0.8–2.5 秒（按长度与心情）。
    ///
    /// 停止/失败不会走到这里：工具回路里每次 await 之后都会检查取消，
    /// 所以用户点停止时还没发出去的那几句**不会**被发出去（也不延迟）。
    private func deliverOutbound(
        _ text: String,
        sessionId: String,
        personaId: String,
        model: String,
        mood: MoodState,
        payTypingDelay: Bool,
        continuation: AsyncThrowingStream<ChatEvent, Error>.Continuation
    ) async -> MessageEntity? {
        let seconds = payTypingDelay
            ? TypingDelay.seconds(charCount: text.count, mood: mood)
            : MessageRhythm.gapSeconds(charCount: text.count, mood: mood)
        await sleep(TypingDelay.nanos(seconds: seconds))
        if Task.isCancelled { return nil }
        guard let saved = try? saveAssistant(
            sessionId: sessionId,
            personaId: personaId,
            content: text,
            status: .complete,
            errorMessage: nil,
            model: model,
            traces: [],
            // 0.16：这句话是新协议发出去的 → 在 `toolTraceJson` 里留一个空数组标记，
            // 免得下次读历史时被当成「旧版本直接说的」（见 `OutboundProtocol.toolProtocolTraceMarker`）。
            toolProtocol: true
        ) else { return nil }
        continuation.yield(.appended(saved))
        return saved
    }

    // MARK: - 危机优先于沉默（0.15）

    /// 危机这一轮本该沉默（`stay_silent` / `defer_reply`）时的兜底：**必须**留下一条看得见的消息。
    ///
    /// 为什么在这里而不是改 `SafetyGuard`：`SafetyGuard` 只回答「要不要补安全提示」，
    /// 「这一轮到底落不落消息」是聊天链路的决定（与工具拦截、4 条上限、落库顺序绑在一起）。
    ///
    /// 正文怎么定（只做加法，不改写模型的话）：
    /// 1. 模型这一轮写了正文 → 用它；只在**缺安全锚点**时补 1–2 句（已有锚点一字不改，也不重复补）；
    /// 2. 只有工具 / 什么都没写 → 本地化的安全提示（`SafetyGuard.safetyNetText`）就是唯一正文。
    ///
    /// 落到哪一条：
    /// - 这一轮已经发过话、且那条话里**已有安全锚点** → 直接用它收尾，不再补第二遍；
    /// - 这一轮已经用 `send_message` 发满 4 条 → 并进**最后一条**（「每轮最多 4 条」是硬上限，
    ///   不能为了这句提示造第 5 个气泡）；
    /// - 一条都没发（或还有额度）→ 正常落一条助手消息。
    ///
    /// 合并写库失败时宁可多一个气泡也不丢安全提示：安全信息比条数上限重要。
    private func deliverCrisisSafetyReply(
        body: String,
        userText: String,
        language: PromptLanguage,
        sentCount: Int,
        producedMessage: MessageEntity?,
        sessionId: String,
        personaId: String,
        model: String,
        traces: [ToolTraceItem],
        continuation: AsyncThrowingStream<ChatEvent, Error>.Continuation
    ) throws -> MessageEntity {
        // 主动唤醒的 `yap-wake` 代码块同样不能露给用户（与正常回复同一条规则）。
        let trimmedBody = ProactiveProtocol.extract(body).body
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let note = SafetyGuard.safetyNetText(language: language)
        let content = trimmedBody.isEmpty
            ? note
            : SafetyGuard.appendingSafetyNetIfNeeded(
                reply: trimmedBody, userMessage: userText, language: language, crisis: true
            )
        // 这一轮没有正文、但话已经用 `send_message` 说过了：那句里已经有安全锚点时，
        // 再补一条纯提示气泡就是重复（「不重复」同样适用于工具发出去的话）。
        if trimmedBody.isEmpty, let last = producedMessage,
           SafetyGuard.hasSafetyAnchor(last.content) {
            return last
        }
        if sentCount >= MessageRhythm.maxMessagesPerRound, let last = producedMessage {
            var merged = last
            let head = last.content.trimmingCharacters(in: .whitespacesAndNewlines)
            merged.content = (head.isEmpty ? content : head + "\n\n" + content)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if (try? stores.messages.upsert(merged)) != nil {
                continuation.yield(.appended(merged))
                return merged
            }
            log?("[chat] 危机安全提示并进最后一条失败，改为单独落一条")
        }
        guard let saved = try saveAssistant(
            sessionId: sessionId,
            personaId: personaId,
            content: content,
            status: .complete,
            errorMessage: nil,
            model: model,
            traces: traces
        ) else {
            throw ApiException(statusCode: nil, message: String(localized: "回复没有保存成功，请重试"))
        }
        // 让界面此刻就显示这一条（与正常回复「先推正文、再 done」的顺序一致）。
        continuation.yield(.delta(saved.content))
        return saved
    }

    // MARK: - 高置信自动记忆（0.15）

    /// 模型这一轮没调 `remember`，而用户的话里明确有高置信事实时，替它补写一条 `USER` 记忆。
    ///
    /// 为什么需要这条兜底：提示词已经写了「只在正文里说『记住了』不算」，但模型遵守率不是 100%，
    /// 而「对方对花生过敏」这种信息漏一次的代价太大。规则本身在
    /// `HighConfidenceMemory`（纯函数），这里只负责「什么时候写、写到哪、写失败怎么办」：
    /// - **调过就不补**：模型自己记了（哪怕被筛查判成「这次先不记」）就不再插手，避免一条事实写两遍；
    /// - **重复就不补**：仓储里已有同一件事（归一化后包含）时跳过；
    /// - **失败只写日志**：记忆写不进去不该影响这一轮已经落库的回复。
    private func autoRememberIfNeeded(
        userText: String,
        personaId: String,
        sessionId: String,
        rememberRequested: Bool
    ) {
        guard !rememberRequested else { return }
        guard let hit = HighConfidenceMemory.detect(userText) else { return }
        let existing = (try? memory.getAllForPersona(personaId))?.map(\.content) ?? []
        guard !HighConfidenceMemory.isDuplicate(of: hit.content, among: existing) else {
            log?("[chat] 高置信自动记忆：已经记过「\(hit.content)」，跳过")
            return
        }
        let written = memory.addDetailed(
            personaId: personaId,
            content: hit.content,
            category: .fact,
            importance: hit.importance,
            drawer: MemoryDrawer.user.rawValue,
            sourceQuote: String(userText.prefix(300)),
            sourceSessionId: sessionId,
            tags: hit.tags
        )
        switch written {
        case .success(let id):
            log?("[chat] 高置信自动记忆已写入 \(id)（\(hit.rule.rawValue)，"
                + "importance \(hit.importance)）：\(hit.content)")
        case .failure(let error):
            log?("[chat] 高置信自动记忆没写成：\(error.localizedDescription)")
        }
    }

    // MARK: - 落库

    /// - Parameter toolProtocol: 这条消息是不是**新协议**（`send_message`）发出去的。
    ///   是的话即使没有工具痕迹也写一个 `[]` 标记（见 `OutboundProtocol.toolProtocolTraceMarker`），
    ///   这样「`toolTraceJson == nil`」就只剩一个含义：旧版本直接说的。
    @discardableResult
    private func saveAssistant(
        sessionId: String,
        personaId: String,
        content: String,
        status: MessageStatus,
        errorMessage: String?,
        model: String,
        traces: [ToolTraceItem],
        toolProtocol: Bool = false
    ) throws -> MessageEntity? {
        // 0.14 §1「不产生空消息」：空正文一律不落库（失败/停止也一样）。
        if content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        let timestamp = try nextTimestamp(sessionId: sessionId)
        let message = MessageEntity(
            id: UUID().uuidString,
            sessionId: sessionId,
            personaId: personaId,
            role: .assistant,
            content: content,
            createdAt: timestamp,
            updatedAt: timestamp,
            status: status,
            errorMessage: errorMessage,
            model: model,
            edited: false,
            toolTraceJson: traces.isEmpty
                ? (toolProtocol ? OutboundProtocol.toolProtocolTraceMarker : nil)
                : ToolTraceCodec.encode(traces)
        )
        try stores.database.inTransaction {
            try stores.messages.upsert(message)
            guard var session = try stores.sessions.byId(sessionId) else { return }
            let preview = String(content.replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
            session.lastMessagePreview = preview.isEmpty
                ? (status == .error ? String(localized: "回复失败") : nil)
                : preview
            session.lastMessageAt = timestamp
            session.updatedAt = timestamp
            if isSessionOpen(sessionId) {
                session.unreadCount = 0
            } else {
                session.unreadCount += 1
            }
            try stores.sessions.upsert(session)
        }
        return message
    }

    private func refreshPreview(sessionId: String) throws {
        guard var session = try stores.sessions.byId(sessionId) else { return }
        let latest = try stores.messages.latest(sessionId: sessionId)
        let preview = String((latest?.content ?? "").replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        session.lastMessagePreview = preview.isEmpty
            ? (latest?.status == .error ? String(localized: "回复失败") : nil)
            : preview
        session.lastMessageAt = latest?.createdAt
        session.updatedAt = now()
        try stores.sessions.upsert(session)
    }

    /// 消息时间戳严格递增：同一毫秒里连发两条时，历史顺序才不会乱。
    private func nextTimestamp(sessionId: String) throws -> Int64 {
        let latest = try stores.messages.latest(sessionId: sessionId)
        let timestamp = now()
        if let latest, timestamp <= latest.createdAt { return latest.createdAt + 1 }
        return timestamp
    }

    static func autoTitle(_ content: String) -> String {
        let flattened = content
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if flattened.count <= 26 { return flattened }
        return String(flattened.prefix(26)) + "…"
    }

    // MARK: - 主动唤醒提案

    /// 把回复里的主动唤醒代码块剥掉，正文交回界面，提案 JSON 交回上层。
    ///
    /// 真正干活的是 `ProactiveProtocol.extract`（三种语言标记都认：新的 `yap-wake`、
    /// 更名前的 `myfriend-wake`、更早 iOS 用的 `myfriend-proactive`）。这里保留一个同名入口，是为了让已有调用点与测试
    /// 不必关心协议细节。
    static func extractProactive(_ rawText: String) -> (text: String, proposalJson: String?) {
        let extracted = ProactiveProtocol.extract(rawText)
        return (extracted.body, extracted.payloadJson)
    }

    /// 把提案写成一条 `proactive_rules`（同一节奏已有就更新，不堆重复）。
    private func persistProactiveProposal(
        proposal: ProactiveProposal,
        personaId: String,
        sessionId: String
    ) {
        guard let proactive else {
            log?("[proactive] 收到提案，但这一层没有接规则落库：\(proposal.kind.stored)")
            return
        }
        switch proactive.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now()
        ) {
        case .success(let id):
            log?("[proactive] 规则已落库 \(id)（\(proposal.kind.stored)）")
        case .failure(let error):
            log?("[proactive] 规则没落库：\(error.localizedDescription)")
        }
    }

    // MARK: - 失败流

    /// 还没开始生成就失败（参数不对）：给一条只带错误的流，界面按同样的方式处理。
    static func failedStream(_ error: Error) -> AsyncThrowingStream<ChatEvent, Error> {
        AsyncThrowingStream { continuation in
            continuation.finish(throwing: error)
        }
    }
}
