import Foundation

/// API Key 的存放：Keychain（service `com.yap.app.ios`）。
///
/// 与 Android 的差别：Android 把 Keystore 加密后的密文塞进 DataStore 字符串
/// （所以数据库/备份里到处是 `apiKeyCipher`）；iOS 有 Keychain，Key 直接放系统安全区，
/// 数据库里**不存任何与 Key 有关的字段**（`ApiConfigEntity.apiKeyCipher` 在 iOS 上留空，
/// 只为对齐 Room 的列、以及将来从 Android 迁移时能放下密文引用）。
///
/// 这也解释了备份为什么天然安全：备份读的是数据库 + 显式白名单字段，Keychain 不在其中。
public final class ApiKeyStore {

    /// 与 App 的 bundle id 对齐：profile 里所有 Key 都挂在这个 service 下。
    public static let service = "com.yap.app.ios"

    /// account 命名：一个配置一条 Key，删配置时按 id 删得掉，不会误伤别的配置。
    public enum Account {
        public static func apiKey(configId: String) -> String { "api-key.\(configId)" }
        public static func webToolsAPIKey(configId: String) -> String { "web-tools-api-key.\(configId)" }
        /// 语音转写（ASR）的**独立** Key：不属于任何一条 API 配置，全局只有一条。
        public static let asrAPIKey = "asr-api-key"
    }

    private let backend: KeychainBackend
    private let service: String

    public init(backend: KeychainBackend = SystemKeychain(), service: String = ApiKeyStore.service) {
        self.backend = backend
        self.service = service
    }

    // MARK: 主 Key

    /// 写入（或清除）某个配置的主 Key。
    ///
    /// 空字符串视为「清除」：用户在设置页把输入框删空，不应该留下一条空 Key——
    /// 空 Key 发请求只会得到 401，不如明确表示「还没填」。
    public func setAPIKey(_ key: String?, for configId: String) throws {
        try write(key, account: Account.apiKey(configId: configId))
    }

    public func apiKey(for configId: String) -> String? {
        backend.get(service: service, account: Account.apiKey(configId: configId))
    }

    /// 这个配置到底有没有可用的 Key：**以能不能从 Keychain 读出非空 Key 为准**。
    ///
    /// 0.10.2 安全审计 P2-6 记的是 Android 那边「只看密文是否非空」的毛病
    ///（Keystore 密钥被系统清掉后 UI 仍说「已保存」，发消息只会得到 401）。
    /// iOS 的实体里本来就不存密文（`apiKeyCipher` 恒空），这里把「已配置」的定义
    /// 写死成读 Keychain 本身；以后谁想改成看数据库字段，这条测试会当场变红。
    ///
    /// 读不到（Keychain 读失败 / 条目被系统清掉）与「没填过」在这里是同一个结果：
    /// 界面显示「未填写」并允许重新输入 —— 对用户来说那就是「需要重新填一次」的明确提示，
    /// 不会像 Android 那样「假装已配置」。
    public func isConfigured(for configId: String) -> Bool {
        guard let key = apiKey(for: configId) else { return false }
        return !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: 联网工具专用 Key（留空则复用主 Key，与 Android 同语义）

    public func setWebToolsAPIKey(_ key: String?, for configId: String) throws {
        try write(key, account: Account.webToolsAPIKey(configId: configId))
    }

    public func webToolsAPIKey(for configId: String) -> String? {
        backend.get(service: service, account: Account.webToolsAPIKey(configId: configId))
    }

    // MARK: 语音转写专用 Key（0.15，全局一条）

    /// 写/清语音转写的 Key。
    ///
    /// 为什么单独一条、不挂在某条 API 配置下：转写可以走完全另一家服务（本地 whisper、
    /// Groq、OpenAI…），「换聊天模型」不该把转写搞坏；反过来也一样。
    /// 空字符串视为清除（与主 Key 同一语义：空 Key 发请求只会得到 401）。
    public func setASRAPIKey(_ key: String?) throws {
        try write(key, account: Account.asrAPIKey)
    }

    public func asrAPIKey() -> String? {
        backend.get(service: service, account: Account.asrAPIKey)
    }

    /// 有没有可用的转写 Key（以「Keychain 里读得出非空字符串」为准）。
    public var hasASRAPIKey: Bool {
        guard let key = asrAPIKey() else { return false }
        return !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public func deleteASRAPIKey() throws {
        try backend.delete(service: service, account: Account.asrAPIKey)
    }

    // MARK: 生命周期

    /// 删配置时调用：主 Key 与联网 Key 一起清掉，不留孤儿条目。
    public func deleteKeys(for configId: String) throws {
        try backend.delete(service: service, account: Account.apiKey(configId: configId))
        try backend.delete(service: service, account: Account.webToolsAPIKey(configId: configId))
    }

    private func write(_ key: String?, account: String) throws {
        let trimmed = key?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else {
            try backend.delete(service: service, account: account)
            return
        }
        try backend.set(trimmed, service: service, account: account)
    }
}
