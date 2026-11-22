import Foundation
import XCTest
@testable import YapKit

/// 语音转写（ASR）的**配置面**：默认值、UserDefaults 键、备份边界、以及那条独立 Key 的去处。
///
/// 这一组测试守的是「用户的秘密不会被顺手写出去」：
/// - 开关关、地址 `https://api.deepseek.com`、模型 `whisper-1` 是代码里的默认值，
///   没动过设置的用户不该在 UserDefaults 里留下任何键；
/// - Key 只存在 Keychain（`ApiKeyStore.asrAPIKey()`），**不进 UserDefaults、不进备份、不进日志**；
/// - 「删除全部本地数据」要把这条 Key 也清掉（否则「回到刚装上的样子」之后还留着一把密钥）。
final class AsrSettingsTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: SettingsStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        suiteName = "myfriend.tests.asr.\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = SettingsStore(defaults: defaults)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        try super.tearDownWithError()
    }

    // MARK: - 默认值

    func testDefaultsMatchRequirement() {
        let settings = AppSettings()
        XCTAssertFalse(settings.asrEnabled, "语音转写默认关")
        XCTAssertEqual(settings.asrBaseUrl, "https://api.deepseek.com")
        XCTAssertEqual(settings.asrBaseUrl, AsrConfig.defaultBaseUrl)
        XCTAssertEqual(settings.asrModel, "whisper-1")
        XCTAssertEqual(settings.asrModel, AsrConfig.defaultModel)
        XCTAssertEqual(store.load(), settings, "空 UserDefaults 必须给出同一份默认值")
    }

    func testEmptyDefaultsDoNotWriteAnyKey() {
        _ = store.load()
        for key in SettingsStore.Key.allCases {
            XCTAssertNil(defaults.object(forKey: key.rawValue), "只读一次不该写 \(key.rawValue)")
        }
    }

    func testKeysAreSnakeCaseAndStable() {
        XCTAssertEqual(SettingsStore.Key.asrEnabled.rawValue, "asr_enabled")
        XCTAssertEqual(SettingsStore.Key.asrBaseUrl.rawValue, "asr_base_url")
        XCTAssertEqual(SettingsStore.Key.asrModel.rawValue, "asr_model")
    }

    // MARK: - 读写

    func testRoundTripsEveryASRField() {
        var settings = AppSettings()
        settings.asrEnabled = true
        settings.asrBaseUrl = "https://asr.example.com/v1"
        settings.asrModel = "whisper-large-v3"
        store.save(settings)

        let loaded = store.load()
        XCTAssertTrue(loaded.asrEnabled)
        XCTAssertEqual(loaded.asrBaseUrl, "https://asr.example.com/v1")
        XCTAssertEqual(loaded.asrModel, "whisper-large-v3")
        XCTAssertTrue(defaults.bool(forKey: "asr_enabled"))
        XCTAssertEqual(defaults.string(forKey: "asr_base_url"), "https://asr.example.com/v1")
        XCTAssertEqual(defaults.string(forKey: "asr_model"), "whisper-large-v3")
    }

    /// 空串 = 「用默认值」：用户在设置页把地址/模型删空之后，不该得到一个空地址。
    func testBlankBaseUrlAndModelFallBackToDefaults() {
        store.save(AppSettings(asrBaseUrl: "   ", asrModel: ""))
        let loaded = store.load()
        XCTAssertEqual(loaded.asrBaseUrl, AsrConfig.defaultBaseUrl)
        XCTAssertEqual(loaded.asrModel, AsrConfig.defaultModel)
    }

    func testResetClearsASRKeysSoDefaultsComeBack() {
        store.save(AppSettings(asrEnabled: true, asrBaseUrl: "https://x.example.com", asrModel: "m"))
        store.reset()
        XCTAssertEqual(store.load(), AppSettings())
        XCTAssertNil(defaults.object(forKey: "asr_base_url"))
        XCTAssertNil(defaults.object(forKey: "asr_model"))
        XCTAssertNil(defaults.object(forKey: "asr_enabled"))
    }

    // MARK: - 备份边界

    /// 备份里不该出现转写的任何一项（Key 本来就在 Keychain 之外，地址/模型也不带走）。
    func testBackupSettingsCarryNoASRConfiguration() throws {
        let settings = AppSettings(asrEnabled: true, asrBaseUrl: "https://asr.example.com", asrModel: "whisper-large-v3")
        let backup = settings.toBackupSettings()
        let data = try JSONEncoder().encode(backup)
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertFalse(json.contains("asr"), "备份里不该有 ASR 字段：\(json)")
        XCTAssertFalse(json.contains("whisper"), "备份里不该有转写模型：\(json)")
        XCTAssertFalse(json.contains("transcription"), "备份里不该有转写配置：\(json)")

        // 从备份接回设置时，本机的 ASR 配置保持原样（与头像/背景图同样的边界）。
        let restored = settings.applying(backup)
        XCTAssertEqual(restored.asrBaseUrl, "https://asr.example.com")
        XCTAssertEqual(restored.asrModel, "whisper-large-v3")
        XCTAssertTrue(restored.asrEnabled)
    }

    // MARK: - 独立 Key

    func testASRKeyLivesInKeychainOnly() throws {
        let keychain = InMemoryKeychain()
        let apiKeys = ApiKeyStore(backend: keychain)
        let secret = "sk-asr-only-in-keychain"

        try apiKeys.setASRAPIKey(secret)
        XCTAssertEqual(apiKeys.asrAPIKey(), secret)
        XCTAssertTrue(apiKeys.hasASRAPIKey)

        // UserDefaults 里不能出现 Key（一个键都不能带它）。
        store.save(AppSettings(asrEnabled: true))
        let snapshot = defaults.dictionaryRepresentation().description
        XCTAssertFalse(snapshot.contains(secret), "UserDefaults 里出现了 ASR Key：\(snapshot)")
        XCTAssertNil(SettingsStore.Key(rawValue: "asr_api_key"), "ASR Key 不该有 UserDefaults 键")

        // 只存了那一条，且不碰主 Key / 联网 Key。
        XCTAssertEqual(keychain.storedCount, 1)
        XCTAssertNil(apiKeys.apiKey(for: "a-1"))
        XCTAssertNil(apiKeys.webToolsAPIKey(for: "a-1"))

        try apiKeys.deleteASRAPIKey()
        XCTAssertNil(apiKeys.asrAPIKey())
        XCTAssertFalse(apiKeys.hasASRAPIKey)
        XCTAssertEqual(keychain.storedCount, 0)
    }

    func testASRKeyAccountNameAndTrimming() throws {
        XCTAssertEqual(ApiKeyStore.Account.asrAPIKey, "asr-api-key")
        let keychain = InMemoryKeychain()
        let apiKeys = ApiKeyStore(backend: keychain)
        try apiKeys.setASRAPIKey("  sk-padded \n")
        XCTAssertEqual(apiKeys.asrAPIKey(), "sk-padded", "存之前要 trim")
        // 空串 = 清除（与主 Key 同语义）。
        try apiKeys.setASRAPIKey("")
        XCTAssertNil(apiKeys.asrAPIKey())
        try apiKeys.setASRAPIKey("   ")
        XCTAssertNil(apiKeys.asrAPIKey())
        XCTAssertEqual(keychain.storedCount, 0)
    }

    // MARK: - 配置映射

    func testAsrConfigFromSettings() {
        var settings = AppSettings()
        settings.asrEnabled = true
        settings.asrBaseUrl = "https://asr.example.com"
        settings.asrModel = "whisper-1"

        let withoutKey = AsrConfig.from(settings: settings, apiKey: nil)
        XCTAssertTrue(withoutKey.enabled)
        XCTAssertFalse(withoutKey.hasAPIKey)
        XCTAssertFalse(withoutKey.isReady, "没 Key 时不能算「能转写」")

        let withKey = AsrConfig.from(settings: settings, apiKey: " sk-1 ")
        XCTAssertEqual(withKey.trimmedAPIKey, "sk-1")
        XCTAssertTrue(withKey.isReady)
        XCTAssertEqual(withKey.resolvedBaseUrl, "https://asr.example.com")
        XCTAssertEqual(withKey.resolvedModel, "whisper-1")
        XCTAssertEqual(withKey.resolvedLanguage, "zh")

        // 空地址/模型 → 默认值（双保险：SettingsStore 已经回落过一层）。
        let blank = AsrConfig(enabled: true, baseUrl: " ", model: "", apiKey: "k")
        XCTAssertEqual(blank.resolvedBaseUrl, AsrConfig.defaultBaseUrl)
        XCTAssertEqual(blank.resolvedModel, AsrConfig.defaultModel)
    }

    // MARK: - 删除全部数据

    /// 「删除全部本地数据」要把这条全局 Key 也清掉（它不属于任何 API 配置）。
    func testClearAllRemovesASRKey() throws {
        let temp = try TempDatabase()
        defer { temp.cleanUp() }
        let keychain = InMemoryKeychain()
        let apiKeys = ApiKeyStore(backend: keychain)
        // 库里真的有一条配置：`clearAll` 会按 id 把它的 Key 一起清掉。
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "a-1", name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", updatedAt: 1
        ))
        try apiKeys.setASRAPIKey("sk-asr")
        try apiKeys.setAPIKey("sk-main", for: "a-1")
        XCTAssertEqual(keychain.storedCount, 2)

        let reset = DataReset(
            stores: temp.stores,
            settings: InMemorySettings(AppSettings()),
            apiKeys: apiKeys
        )
        _ = reset.clearAll()

        XCTAssertFalse(apiKeys.hasASRAPIKey, "删除全部数据之后不该留下转写 Key")
        XCTAssertEqual(keychain.storedCount, 0)
    }

    /// 「清空全部聊天与记忆」保留配置与 Key（包括转写 Key）。
    func testClearChatsKeepsASRKey() throws {
        let temp = try TempDatabase()
        defer { temp.cleanUp() }
        let keychain = InMemoryKeychain()
        let apiKeys = ApiKeyStore(backend: keychain)
        try apiKeys.setASRAPIKey("sk-asr")

        let reset = DataReset(
            stores: temp.stores,
            settings: InMemorySettings(AppSettings()),
            apiKeys: apiKeys
        )
        _ = try reset.clearChats()
        XCTAssertTrue(apiKeys.hasASRAPIKey)
    }
}
