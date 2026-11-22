import Foundation

// 来源：app/src/main/java/com/yap/app/ui/screens/settings/SettingsViewModel.kt 的两个动作
//（`clearChats` → `ChatRepository.clearAllChatData`、`deleteAllData` → 各仓储 wipe + `settings.resetAll`）。
//
// Android 的设置页把「清空」分成两件事，文案写得很清楚：
//
// 1. **清空全部聊天与记忆**（保留人格与 API 配置）：messages / sessions / memories（连带索引）；
// 2. **删除全部本地数据**（人格、会话、记忆、预设、主动唤醒规则、API 配置与设置全部移除）。
//
// 移植时把这两个动作都放在 `DataReset` 里，名字直接对应，不做「一个函数猜用户想清多少」。
//
// 关于「保留 API Key」：Android 的**全部删除**其实会把 api_configs 一起删掉
// （`chatRepository.wipeAllData()` 里最后一句 `apiConfigDao.deleteAllApiConfigs()`），
// 密钥随配置一起消失；真正「保留 Key」的是**清空聊天与记忆**那个动作。
// iOS 这边因此按同一套语义实现：
// - `clearChats()` 不碰 api_configs / Keychain；
// - `clearAll()` 删 api_configs，并在删之前把对应 Keychain 条目清掉（不留孤儿密钥）。
//
// 图片：Android 会 `MediaStorage.clear(context)`。iOS 的图片也在沙盒里，而 `MediaStorage`
// 本来就在 Kit 层，所以直接把 `media` 传进来，两个清空入口共用同一份清理
//（0.10.2 审计 P1-7：原来这条清理挂在两个 View 上，备份页漏掉了）。

/// 清空动作的结果：给界面一句「删掉了多少」的中文，也方便单测断言。
public struct ClearSummary: Equatable, Sendable {
    public var messages: Int
    public var sessions: Int
    public var memories: Int
    public var personas: Int
    public var presets: Int
    public var rules: Int
    public var intents: Int
    public var consolidations: Int
    public var apiConfigs: Int
    public var usage: Int
    /// 哪几张表没删成功（`clearAll` 逐表吞错，但要如实告诉用户是哪些）。
    public var warnings: [String]
    /// 清空之后重新播种了几个人格（0.11.0 P2-13：不留一个空白 App）。
    public var seededPersonas: Int
    /// 重新播种时建了几段会话（一人一段）。
    public var seededSessions: Int

    public init(
        messages: Int = 0,
        sessions: Int = 0,
        memories: Int = 0,
        personas: Int = 0,
        presets: Int = 0,
        rules: Int = 0,
        intents: Int = 0,
        consolidations: Int = 0,
        apiConfigs: Int = 0,
        usage: Int = 0,
        warnings: [String] = [],
        seededPersonas: Int = 0,
        seededSessions: Int = 0
    ) {
        self.messages = messages
        self.sessions = sessions
        self.memories = memories
        self.personas = personas
        self.presets = presets
        self.rules = rules
        self.intents = intents
        self.consolidations = consolidations
        self.apiConfigs = apiConfigs
        self.usage = usage
        self.warnings = warnings
        self.seededPersonas = seededPersonas
        self.seededSessions = seededSessions
    }

    public func describeChats() -> String {
        let counts = PluralText.list([
            PluralText.format("%d 段对话", sessions),
            PluralText.format("%d 句话", messages),
            PluralText.format("%d 条记忆", memories)
        ])
        return String(format: String(localized: "所有聊天与记忆已清空（%@）"), counts)
    }

    public func describeAll() -> String {
        var parts = [
            PluralText.format("%d 个人格", personas),
            PluralText.format("%d 条记忆", memories),
            PluralText.format("%d 段对话", sessions),
            PluralText.format("%d 句话", messages)
        ]
        if apiConfigs > 0 { parts.append(PluralText.format("%d 个 API 配置", apiConfigs)) }
        var text = String(
            format: String(localized: "所有本地数据已删除（%@）"),
            PluralText.list(parts)
        )
        if !warnings.isEmpty {
            // 静默半成功比报错更糟（0.10.2 审计 P2-12）：哪几张表没删掉要如实说。
            // 计数单独挑模板（`PluralText.template`）而不是整句 `String(format:)`：
            // 英文要的是「1 table couldn't be cleared / 2 tables couldn't be cleared」，
            // 中文只有一种说法，`.one` 变体的值与正文相同。
            let template = PluralText.template("；有 %d 张表没删成功：%@，其余数据已删除", warnings.count)
            text += String(
                format: template,
                warnings.count,
                warnings.joined(separator: String(localized: "、"))
            )
        }
        return text
    }
}

/// 能一起清掉设置的东西。
///
/// `SettingsStore.reset()` 是「清键」而不是「写默认值」——两者在「以后加字段」时行为不同：
/// 清键能让新字段取到代码里的默认值；写死默认值会把它们钉在旧值上。
public protocol SettingsResetting {
    func resetAll()
}

extension SettingsStore: SettingsResetting {
    public func resetAll() {
        reset()
    }
}

/// 「删除全部本地数据」之后重新播种的参数。
///
/// 为什么由装配层传进来而不是在 `DataReset` 里读 `Bundle`：Kit 不认识界面语言，
/// 而单测要能指定语言（断言「清空后是当前语言的种子」）。
public struct ReseedConfig {
    public var language: SeedLanguage
    /// 新建会话的标题（界面语言的一句话）。
    public var sessionTitle: String
    /// 新建会话绑定的 API 配置（清空之后通常已经没有了，传 nil）。
    public var apiConfigId: String?
    /// 播种时间（测试注入固定时钟）。
    public var now: Int64

    public init(language: SeedLanguage, sessionTitle: String, apiConfigId: String? = nil,
                now: Int64 = DomainClock.nowMillis) {
        self.language = language
        self.sessionTitle = sessionTitle
        self.apiConfigId = apiConfigId
        self.now = now
    }
}

public final class DataReset {

    /// 清空之后是否重新播种示例人格。
    ///
    /// 装配层在调用 `clearAll()` 之前设好它（`AppEnvironment.clearAllData()`）；
    /// 测试可以直接构造。nil = 不播种（与 0.10.2 的行为一致，只有单测会用）。
    public var reseed: ReseedConfig?

    private let stores: DatabaseStores
    private let settings: SettingsProviding
    private let vectorIndex: MemoryVectorIndex
    /// App 层传 `ApiKeyStore` 进来：全部删除时把配置对应的 Keychain 条目一起清掉。
    private let apiKeys: ApiKeyStore?
    /// 附件文件（聊天图 / 头像）的清理。
    ///
    /// 为什么收进 `DataReset` 而不是让两个界面各调一次（0.10.2 审计 P1-7）：
    /// 0.10.1 的媒体清理是一个**可选回调**，装配层根本没传，真正干活的是设置页与备份页两个
    /// View —— 而备份页那条路径忘了调，于是「删除全部本地数据」之后图片与头像文件还留在
    /// `Application Support/attachments/` 里。收进这里，两个入口只调 `DataReset` 就不会再漏。
    private let media: MediaStorage?
    /// 表情文件目录（「删除全部数据」时一起清；`nil` = 测试没注入，只清库行）。
    private let stickerStorage: StickerStorage?
    /// 语音/视频目录（0.13）：删聊天/删全部数据时一起清（否则几十 MB 的录音永远留在沙盒里）。
    private let audioVideo: AudioVideoStore?

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        vectorIndex: MemoryVectorIndex? = nil,
        apiKeys: ApiKeyStore? = nil,
        media: MediaStorage? = nil,
        stickerStorage: StickerStorage? = nil,
        audioVideo: AudioVideoStore? = nil
    ) {
        self.stores = stores
        self.settings = settings
        self.vectorIndex = vectorIndex ?? MemoryVectorIndex(stores: stores)
        self.apiKeys = apiKeys
        self.media = media
        self.stickerStorage = stickerStorage
        self.audioVideo = audioVideo
    }

    /// 媒体引用查询（不新增表）：删文件前必须问「还有没有别的记录引用它」。
    private func mediaReferences() -> MediaReferences {
        MediaReferences(stores: stores, settings: settings)
    }

    /// 清空聊天与记忆时要清的图片（头像不动：人还在）。
    ///
    /// 必须先删消息行（调用方已删），再逐个查引用：聊天图按内容哈希去重，可能与头像共用文件。
    private func clearChatMedia() {
        media?.deleteAllChatImages(references: mediaReferences())
        // 语音/视频没有「引用计数」这一层（文件名带 UUID、不做内容去重），删聊天就整目录清掉。
        audioVideo?.deleteAll()
    }

    /// 「删除全部本地数据」时要清的图片（聊天图 + 头像）。
    ///
    /// 引用计数版：**设置也要先清**（`myAvatarUri` 是引用之一），否则「我的头像」文件
    /// 会被当成仍有引用而留下。调用顺序在 `clearAll()` 里固定：库行 → 设置 → 媒体。
    private func clearAllMedia() {
        media?.deleteAll(references: mediaReferences())
        audioVideo?.deleteAll()
    }

    // MARK: - 清空聊天与记忆（保留人格与 API 配置）

    /// 与 Android 的「清空全部聊天与记忆」一致：消息、会话、记忆（连带倒排/向量/回声）。
    /// 人格、API 配置、预设、主动规则、用量都留着。
    public func clearChats() throws -> ClearSummary {
        var summary = ClearSummary()
        summary.messages = (try? stores.messages.count()) ?? 0
        summary.sessions = (try? stores.sessions.count()) ?? 0
        summary.memories = (try? stores.memories.count()) ?? 0
        try stores.database.inTransaction {
            try stores.messages.deleteAll()
            try stores.sessions.deleteAll()
            try stores.memories.deleteAll()
            try stores.memoryTerms.deleteAll()
            try stores.memoryVersions.deleteAll()
            MemoryEchoStore(database: stores.database).deleteAll()
            try stores.memoryConsolidations.deleteAll()
            _ = try? stores.memoryVectors.deleteAll()
            // 0.14：心理活动与会话备忘录是聊天的一部分（会话没了它们就是孤儿）。
            try stores.mentalActivities.deleteAll()
            try stores.sessionMemory.deleteAll()
            // 聊天都删了：待发队列里的消息也不该在下次补发时冒出来。
            try ServerOutboxStore(database: stores.database).deleteAll()
        }
        clearChatMedia()
        return summary
    }

    // MARK: - 删除全部本地数据

    /// 与 Android 的「删除全部本地数据」一致：
    /// 规则 → 聊天/记忆/人格/API 配置 → 用量 → 预设 → 图片 → 设置。
    ///
    /// 顺序有意义：先清规则与排期，再清聊天/人格，最后清模板与设置。
    /// 任何一步失败都继续往下清（与 Android 的 runCatching per step 同义）：
    /// 用户点的是「都删掉」，不该因为一张表的锁失败就半途停下。
    @discardableResult
    public func clearAll() -> ClearSummary {
        var summary = ClearSummary()
        summary.messages = (try? stores.messages.count()) ?? 0
        summary.sessions = (try? stores.sessions.count()) ?? 0
        summary.memories = (try? stores.memories.count()) ?? 0
        summary.personas = (try? stores.personas.count()) ?? 0
        summary.presets = (try? stores.presets.count()) ?? 0
        summary.rules = (try? stores.proactiveRules.count()) ?? 0
        summary.intents = (try? stores.agentIntents.count()) ?? 0
        summary.consolidations = (try? stores.memoryConsolidations.count()) ?? 0
        summary.apiConfigs = (try? stores.apiConfigs.count()) ?? 0
        summary.usage = (try? stores.apiUsage.count()) ?? 0

        // Keychain 里的密钥随配置一起走：先读 id 再删库，最后清 Keychain。
        let configIds = ((try? stores.apiConfigs.all()) ?? []).map(\.id)

        // 逐表吞错（与 Android 的 runCatching per step 同义：用户点的是「都删掉」，
        // 不该因为一张表的锁失败就半途停下），但把失败的表收集起来告诉用户。
        var failures: [String] = []
        func attempt(_ label: String, _ body: () throws -> Void) {
            do { try body() } catch { failures.append(label) }
        }
        _ = try? stores.database.inTransaction {
            // 1) 规则与排期
            attempt("proactive_rules") { try stores.proactiveRules.deleteAll() }
            attempt("agent_intents") { try stores.agentIntents.deleteAll() }
            // 2) 聊天与记忆（子表先走）
            attempt("messages") { try stores.messages.deleteAll() }
            attempt("chat_sessions") { try stores.sessions.deleteAll() }
            attempt("memory_terms") { try stores.memoryTerms.deleteAll() }
            attempt("memory_versions") { try stores.memoryVersions.deleteAll() }
            attempt("memory_echoes") { MemoryEchoStore(database: stores.database).deleteAll() }
            attempt("memories") { try stores.memories.deleteAll() }
            attempt("memory_consolidations") { try stores.memoryConsolidations.deleteAll() }
            attempt("memory_vectors") { try stores.memoryVectors.deleteAll() }
            // 2.5) 服务端模式的客户端侧簿记：同步游标与待发队列
            //（留着游标会让「删除全部数据」之后再同步只拿增量，用户的对话就永远补不回来）。
            attempt("server_sync_state") { try ServerSyncStateStore(database: stores.database).deleteAll() }
            attempt("server_outbox") { try ServerOutboxStore(database: stores.database).deleteAll() }
            // 2.6) 0.13 的两张新表：日记板（他自己的便条）与待确认的人格变化。
            // 它们跟着「全部数据」一起清：留着只会是没人能再看到的孤儿行。
            attempt("diary_notes") { try stores.diaryNotes.deleteAll() }
            attempt("persona_pending_patches") { try stores.personaPendingPatches.deleteAll() }
            // 2.7) 表情包（用户自己的收藏）：库行与文件都跟着「全部数据」一起清。
            attempt("stickers") { try stores.stickers.deleteAll() }
            // 2.8) 0.14 的两张新表：心理活动与会话备忘录。
            attempt("mental_activities") { try stores.mentalActivities.deleteAll() }
            attempt("session_memory") { try stores.sessionMemory.deleteAll() }
            // 3) 人格与配置
            attempt("personas") { try stores.personas.deleteAll() }
            attempt("api_configs") { try stores.apiConfigs.deleteAll() }
            // 4) 用量 / 预设
            attempt("api_usage") { try stores.apiUsage.deleteAll() }
            attempt("persona_presets") { try stores.presets.deleteAll() }
        }
        // 思考留痕（表补上之前是进程内实现）
        AgentTickStore(database: stores.database).deleteAll()

        if let apiKeys {
            for id in configIds {
                try? apiKeys.deleteKeys(for: id)
            }
            // 语音转写的 Key 是**全局一条**、不属于任何配置：全部删除时也要清掉，
            // 否则「回到刚装上的样子」之后 Keychain 里还留着一条没人认识的密钥。
            try? apiKeys.deleteASRAPIKey()
        }
        // 设置先清：清完这里，「正在聊的人 / 心跳进度 / 主题」一起回到出厂；
        // 同时 `myAvatarUri` 不再是一条引用，下面的零引用判定才能删掉用户头像文件。
        if let resetting = settings as? SettingsResetting {
            resetting.resetAll()
        } else {
            settings.save(AppSettings())
        }
        // 表情文件与聊天附件分开：表情目录里只有表情，整目录清空即可（先清库行再做，
        // 与 `clearAllMedia` 的「先库后文件」同一顺序）。
        stickerStorage?.deleteAll()
        // 库行与设置都清完，才逐个文件查引用：零引用的删掉，仍被引用的留着。
        clearAllMedia()
        vectorIndex.resetEnsured()
        if let reseed {
            // 0.10.2 审计 P2-13：清空之后立刻按当前语言重新播种三位示例人格。
            // 不这么做的话，用户在「删除全部本地数据」之后会看到一个空 App，
            // 下次启动种子人格又回来了 —— 同一台设备两种「出厂状态」。
            do {
                let outcome = try PersonaSeeding.seed(
                    stores: stores,
                    language: reseed.language,
                    sessionTitle: reseed.sessionTitle,
                    apiConfigId: reseed.apiConfigId,
                    now: reseed.now
                )
                summary.seededPersonas = outcome.personaIds.count
                summary.seededSessions = outcome.sessions
            } catch {
                failures.append("personas（重新播种失败）")
            }
        }
        summary.warnings = failures
        return summary
    }
}
