import Foundation

// ---------------------------------------------------------------------------
// 设置：放 UserDefaults（Android 端是 DataStore）。
//
// 为什么不进 SQLite：设置是「一台设备上的偏好」，不是「他的一部分」。它不需要事务、
// 不需要按 persona 查，也不进备份的数据库快照 —— 备份里它单独是一段 JSON（BackupSettings）。
//
// 键名用字段名的 snake_case（与 Android AppSettings 的字段同名），这样两端对「哪个设置」
// 有一个共同的叫法。
//
// 1.9 的瘦身（§一）：记忆开关、读消息节奏、月度额度与单价、「每天最多主动几次」全部删除。
// 记忆一律内置开启；节奏交给模型（`defer_reply`）；主动上限由人格的 `proactiveLevel` 决定；
// 额度这件事在产品里根本不存在了。留在 UserDefaults 里的旧键不再被读取，也就自然失效——
// 这里刻意不去「清理」它们：多写一段删除旧键的代码，等于把这些键名又请回源码里。
// ---------------------------------------------------------------------------

public enum ThemeMode: String, CaseIterable, Sendable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case dark = "DARK"

    public init(stored raw: String?) { self = backupEnumOr(raw, .system) }
    public var stored: String { rawValue }
}

public enum BubbleStyle: String, CaseIterable, Sendable {
    case soft = "SOFT"
    case compact = "COMPACT"
    case outlined = "OUTLINED"

    public init(stored raw: String?) { self = backupEnumOr(raw, .soft) }
    public var stored: String { rawValue }
}

/// 一台设备上的全部设置。
///
/// 字段与 Android `data/prefs/AppSettings.kt` 一一对应；其中两项是**内部状态**而不是设置项：
/// `heartbeatLastResetAt` / `heartbeatLastFiredAt` 是冲动曲线的进度，界面里没有它们，
/// 但它们必须跟着 App 重启活下来，所以也放在这里存。
public struct AppSettings: Equatable, Sendable {
    public var themeMode: ThemeMode
    /// ARGB（Long）。iOS 侧用 Int64 接，避免 UInt32 与 Int 来回转丢符号位。
    public var accentColor: Int64
    public var fontScale: Float
    public var bubbleStyle: BubbleStyle
    public var animationsEnabled: Bool
    public var chatBackgroundColor: Int64?
    /// 设备上的沙盒路径，换机/重装就失效 —— 不进备份。
    public var chatBackgroundImageUri: String?
    /// 「我的头像」：用户自己的那一张。同样是设备上的沙盒路径，不进备份、不进数据库。
    ///
    /// 为什么放设置而不新建一张表：它描述的是「这台设备上的我」，不是人格的一部分。
    /// Android 端目前还没有这一项，所以键名不必对齐；语义就是「用户头像」。
    public var myAvatarUri: String?
    public var activePersonaId: String?
    public var hapticsEnabled: Bool
    /// 让他常驻后台继续「活着」（Android 是前台服务；iOS 只能机会式刷新）。
    public var agentLoopEnabled: Bool
    /// 是否还显示「把他留在后台」的引导卡片（纯本地状态，不进备份）。
    public var backgroundTipDismissed: Bool
    /// 冲动曲线走到哪儿了：概率时钟上一次被重置的时刻（内部状态）。
    public var heartbeatLastResetAt: Int64
    /// 上一次他真的因为这股冲动想了一回的时刻；nil = 还没发生过（内部状态）。
    public var heartbeatLastFiredAt: Int64?
    /// 「自动翻译人设」（默认开，`spec/persona-translation/README.md` §6）：
    /// 进聊天前把人设/世界/话风翻成当前界面语言；关掉就永远用原文。
    public var autoTranslatePersona: Bool

    // MARK: 语音转写（ASR，0.15）

    /// 语音/视频发出去之前先转成文字（**默认关**：这是要花另一次请求的事，得用户自己开）。
    ///
    /// 只影响「有没有转写」：关掉时语音/视频照发不误（模型仍然能听，见 `MediaModality`）。
    public var asrEnabled: Bool
    /// 转写服务的 OpenAI 兼容地址（默认 DeepSeek 官方；空 = 用默认值）。
    public var asrBaseUrl: String
    /// 转写模型名（默认 `whisper-1`；空 = 用默认值）。
    public var asrModel: String
    /// 转写 Key **不在这里**：它只存在 Keychain（`ApiKeyStore.asrAPIKey()`），
    /// 不进 UserDefaults、不进备份、也不进日志。见 `Data/ApiKeyStore.swift`。

    public init(
        themeMode: ThemeMode = .system,
        accentColor: Int64 = 0xFFC76F4E,
        fontScale: Float = 1,
        bubbleStyle: BubbleStyle = .soft,
        animationsEnabled: Bool = true,
        chatBackgroundColor: Int64? = nil,
        chatBackgroundImageUri: String? = nil,
        myAvatarUri: String? = nil,
        activePersonaId: String? = nil,
        hapticsEnabled: Bool = true,
        agentLoopEnabled: Bool = true,
        backgroundTipDismissed: Bool = false,
        heartbeatLastResetAt: Int64 = 0,
        heartbeatLastFiredAt: Int64? = nil,
        autoTranslatePersona: Bool = true,
        asrEnabled: Bool = false,
        asrBaseUrl: String = AsrConfig.defaultBaseUrl,
        asrModel: String = AsrConfig.defaultModel
    ) {
        self.themeMode = themeMode
        self.accentColor = accentColor
        self.fontScale = fontScale
        self.bubbleStyle = bubbleStyle
        self.animationsEnabled = animationsEnabled
        self.chatBackgroundColor = chatBackgroundColor
        self.chatBackgroundImageUri = chatBackgroundImageUri
        self.myAvatarUri = myAvatarUri
        self.activePersonaId = activePersonaId
        self.hapticsEnabled = hapticsEnabled
        self.agentLoopEnabled = agentLoopEnabled
        self.backgroundTipDismissed = backgroundTipDismissed
        self.heartbeatLastResetAt = heartbeatLastResetAt
        self.heartbeatLastFiredAt = heartbeatLastFiredAt
        self.autoTranslatePersona = autoTranslatePersona
        self.asrEnabled = asrEnabled
        self.asrBaseUrl = asrBaseUrl
        self.asrModel = asrModel
    }
}

// MARK: - 备份 ⇄ 本机

public extension AppSettings {
    /// 可移植的那部分。设备路径与纯本地的引导状态不带走（见 BackupSettings 注释）。
    /// 语音转写**不进备份**：Key 本来就在 Keychain 里（备份只读数据库 + 白名单设置字段），
    /// 地址与模型也只是「这台设备上怎么连服务」的偏好，换机时重填一次比带一份陌生地址更安全。
    func toBackupSettings() -> BackupSettings {
        BackupSettings(
            themeMode: themeMode.stored,
            accentColor: accentColor,
            fontScale: fontScale,
            bubbleStyle: bubbleStyle.stored,
            animationsEnabled: animationsEnabled,
            hapticsEnabled: hapticsEnabled,
            chatBackgroundColor: chatBackgroundColor,
            agentLoopEnabled: agentLoopEnabled,
            activePersonaId: activePersonaId
        )
    }

    /// 接回备份里的设置。
    ///
    /// 与 Android 的 `BackupSettings.toAppSettings()` 同样的边界处理：
    /// 枚举不认识就用默认档、`fontScale` 夹在 0.85–1.3。**本机不可移植的字段保持原样**。
    ///
    /// v1 备份里的那串开关（记忆、节奏、额度、主动次数）不会出现在 `BackupSettings` 上，
    /// 读 v1 文件时它们连解码都不会发生 —— 这就是「v1 的开关字段忽略」的实现方式。
    func applying(_ backup: BackupSettings) -> AppSettings {
        var updated = self
        updated.themeMode = ThemeMode(stored: backup.themeMode)
        updated.accentColor = backup.accentColor
        updated.fontScale = min(max(backup.fontScale, 0.85), 1.3)
        updated.bubbleStyle = BubbleStyle(stored: backup.bubbleStyle)
        updated.animationsEnabled = backup.animationsEnabled
        updated.hapticsEnabled = backup.hapticsEnabled
        updated.chatBackgroundColor = backup.chatBackgroundColor
        updated.agentLoopEnabled = backup.agentLoopEnabled
        updated.activePersonaId = backup.activePersonaId
        // 以下留给本机：chatBackgroundImageUri / backgroundTipDismissed / heartbeat 状态。
        return updated
    }
}

// MARK: - UserDefaults 读写

/// 设置的持久化入口。
///
/// 每次 `load()` 都读一遍 UserDefaults（内存缓存，成本可忽略），保证「别处改了设置」
/// 立刻能看到；`save(_:)` 全量写回，不做差量 —— 字段少，简单比聪明可靠。
public final class SettingsStore {

    /// 键名一律 snake_case，与 Android 字段同名。改键名等于让用户升级后丢设置，别改。
    public enum Key: String, CaseIterable {
        case themeMode = "theme_mode"
        case accentColor = "accent_color"
        case fontScale = "font_scale"
        case bubbleStyle = "bubble_style"
        case animationsEnabled = "animations_enabled"
        case chatBackgroundColor = "chat_background_color"
        case chatBackgroundImageUri = "chat_background_image_uri"
        case myAvatarUri = "my_avatar_uri"
        case activePersonaId = "active_persona_id"
        case hapticsEnabled = "haptics_enabled"
        case agentLoopEnabled = "agent_loop_enabled"
        case backgroundTipDismissed = "background_tip_dismissed"
        case heartbeatLastResetAt = "heartbeat_last_reset_at"
        case heartbeatLastFiredAt = "heartbeat_last_fired_at"
        case autoTranslatePersona = "auto_translate_persona"
        case asrEnabled = "asr_enabled"
        case asrBaseUrl = "asr_base_url"
        case asrModel = "asr_model"
    }

    public let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: 读

    public func load() -> AppSettings {
        let fallback = AppSettings()
        return AppSettings(
            themeMode: ThemeMode(stored: string(.themeMode)),
            accentColor: integer(.accentColor) ?? fallback.accentColor,
            fontScale: float(.fontScale) ?? fallback.fontScale,
            bubbleStyle: BubbleStyle(stored: string(.bubbleStyle)),
            animationsEnabled: bool(.animationsEnabled, fallback.animationsEnabled),
            chatBackgroundColor: integer(.chatBackgroundColor),
            chatBackgroundImageUri: string(.chatBackgroundImageUri),
            myAvatarUri: string(.myAvatarUri),
            activePersonaId: string(.activePersonaId),
            hapticsEnabled: bool(.hapticsEnabled, fallback.hapticsEnabled),
            agentLoopEnabled: bool(.agentLoopEnabled, fallback.agentLoopEnabled),
            backgroundTipDismissed: bool(.backgroundTipDismissed, fallback.backgroundTipDismissed),
            heartbeatLastResetAt: integer(.heartbeatLastResetAt) ?? fallback.heartbeatLastResetAt,
            heartbeatLastFiredAt: integer(.heartbeatLastFiredAt),
            autoTranslatePersona: bool(.autoTranslatePersona, fallback.autoTranslatePersona),
            // 语音转写：没写过 / 被清成空串都回到代码里的默认值
            //（用户在设置页把地址删空 = 「用默认地址」，不该变成「一个空地址」）。
            asrEnabled: bool(.asrEnabled, fallback.asrEnabled),
            asrBaseUrl: nonEmptyString(.asrBaseUrl) ?? fallback.asrBaseUrl,
            asrModel: nonEmptyString(.asrModel) ?? fallback.asrModel
        )
    }

    // MARK: 写

    public func save(_ settings: AppSettings) {
        setString(settings.themeMode.stored, .themeMode)
        setInteger(settings.accentColor, .accentColor)
        defaults.set(Double(settings.fontScale), forKey: Key.fontScale.rawValue)
        setString(settings.bubbleStyle.stored, .bubbleStyle)
        defaults.set(settings.animationsEnabled, forKey: Key.animationsEnabled.rawValue)
        setInteger(settings.chatBackgroundColor, .chatBackgroundColor)
        setString(settings.chatBackgroundImageUri, .chatBackgroundImageUri)
        setString(settings.myAvatarUri, .myAvatarUri)
        setString(settings.activePersonaId, .activePersonaId)
        defaults.set(settings.hapticsEnabled, forKey: Key.hapticsEnabled.rawValue)
        defaults.set(settings.agentLoopEnabled, forKey: Key.agentLoopEnabled.rawValue)
        defaults.set(settings.backgroundTipDismissed, forKey: Key.backgroundTipDismissed.rawValue)
        setInteger(settings.heartbeatLastResetAt, .heartbeatLastResetAt)
        setInteger(settings.heartbeatLastFiredAt, .heartbeatLastFiredAt)
        defaults.set(settings.autoTranslatePersona, forKey: Key.autoTranslatePersona.rawValue)
        defaults.set(settings.asrEnabled, forKey: Key.asrEnabled.rawValue)
        setString(settings.asrBaseUrl, .asrBaseUrl)
        setString(settings.asrModel, .asrModel)
    }

    /// 恢复默认：清掉所有键，而不是写一份默认值（两者在「以后加字段」时行为不同：
    /// 清键能让新字段取到代码里的默认值；写死默认值会把它们钉在旧值上）。
    public func reset() {
        for key in Key.allCases {
            defaults.removeObject(forKey: key.rawValue)
        }
        // 一次性迁移标记不是设置项（`Key` 里没有它），但「恢复默认」之后也该重来一次：
        // 重跑对已经改过的人不再成立，是无害的。
        defaults.removeObject(forKey: Self.sleepDefaultsMigratedV1Key)
        // 0.13：人格进化的评估游标（每 20 条 / 24 小时的调度状态）也不是设置项，
        // 但「恢复默认 / 删除全部数据」之后不该留着上一位人格的进度。
        defaults.removeObject(forKey: UserDefaultsEvolutionCursorStore.key)
    }

    // MARK: - 一次性迁移标记（0.12 续 7）

    /// 键名与 Android DataStore 上的同名，排查时一眼能认出。
    ///
    /// 为什么不放进 [Key] 枚举：那是「用户可见的设置项」，会跟着 `load()` / `save()` /
    /// 备份与设置页走；迁移标记是**内部状态**，没有界面、也不该进备份。
    static let sleepDefaultsMigratedV1Key = "sleep_defaults_migrated_v1"

    /// 「把没配置过的默认睡眠关掉」跑过没有。
    public var hasMigratedSleepDefaultsV1: Bool {
        defaults.bool(forKey: Self.sleepDefaultsMigratedV1Key)
    }

    public func markSleepDefaultsMigratedV1() {
        defaults.set(true, forKey: Self.sleepDefaultsMigratedV1Key)
    }

    // MARK: 底层

    private func string(_ key: Key) -> String? {
        defaults.string(forKey: key.rawValue)
    }

    /// 读一个「空串等于没写」的字符串（地址/模型名这类有默认值的字段用）。
    private func nonEmptyString(_ key: Key) -> String? {
        guard let value = defaults.string(forKey: key.rawValue)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }

    private func bool(_ key: Key, _ fallback: Bool) -> Bool {
        guard defaults.object(forKey: key.rawValue) != nil else { return fallback }
        return defaults.bool(forKey: key.rawValue)
    }

    private func integer(_ key: Key) -> Int64? {
        (defaults.object(forKey: key.rawValue) as? NSNumber)?.int64Value
    }

    private func float(_ key: Key) -> Float? {
        (defaults.object(forKey: key.rawValue) as? NSNumber).map { Float($0.doubleValue) }
    }

    private func setString(_ value: String?, _ key: Key) {
        if let value {
            defaults.set(value, forKey: key.rawValue)
        } else {
            defaults.removeObject(forKey: key.rawValue)
        }
    }

    private func setInteger(_ value: Int64?, _ key: Key) {
        if let value {
            defaults.set(NSNumber(value: value), forKey: key.rawValue)
        } else {
            defaults.removeObject(forKey: key.rawValue)
        }
    }
}
