import Foundation

// ---------------------------------------------------------------------------
// 更新渠道（0.14.2）：问一次 GitHub Releases「最新版是哪个」，再和本机版本比。
//
// 三条刻意的取舍写在这里，免得以后有人「顺手」改掉：
//
// 1. **按设置选接口**：只要正式版时用 `releases/latest`（GitHub 保证它指向最新正式版，
//    永远不含预发布）；设置里打开「包含预览版」时换成 `releases?per_page=20`，自己挑版本号
//    最大的那条 —— latest 接口根本不会返回预发布，不换接口那个开关等于没有。
// 2. **失败一律静默**：自动检查在每次进前台时跑，没网、DNS 不通、GitHub 挂了都不该
//    弹窗打扰用户。失败只变成 `UpdateCheckOutcome.failed`，设置页手动检查时才上屏。
// 3. **24 小时节流写进 UserDefaults**：自动检查是「进前台就跑」的，没有节流等于每次
//    切后台回来都发一次请求（GitHub 匿名调用限额 60 次/小时，一天能戳穿）。
//    手动检查永远绕过节流 —— 用户按了按钮就该真的去问一次。
//
// 依赖只有 URLSession（Foundation 自带），不引入任何第三方库。
// ---------------------------------------------------------------------------

// MARK: - 语义版本

/// 一个语义版本号：`1.2.3` / `v1.2.3-beta.2` / `2.0`（缺的段按 0） / `1.2.3+build.7`（build 忽略）。
///
/// 比较规则按 semver 2.0.0：
/// - 主/次/修订按数字比；
/// - **预发布版比同号正式版小**（`1.0.0-beta` < `1.0.0`）；
/// - 预发布标识符逐个比：纯数字按数值比，数字段小于字母段，段数少的小于段数多的。
///
/// 解析刻意宽松：带不带 `v` 前缀、少一段、尾巴带 build metadata 都认；
/// 真正读不懂（`abc`、`1.x`、空串）才返回 nil —— 远端 tag 起名五花八门，
/// 因为一个 tag 名字奇怪就报错，用户只会看到「检查更新失败」。
public struct SemanticVersion: Comparable, Sendable, CustomStringConvertible {

    public let major: Int
    public let minor: Int
    public let patch: Int
    /// 预发布标识符（`beta.2` → `["beta", "2"]`）；空 = 正式版。
    public let prerelease: [String]
    /// 用户看到的原始写法（`v1.2.3-beta.2`），只用于日志与排错。
    public let raw: String

    public init?(_ text: String) {
        let original = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !original.isEmpty else { return nil }
        var body = original
        if body.hasPrefix("v") || body.hasPrefix("V") {
            body.removeFirst()
        }
        // build metadata（`+build.7`）不参与比较，直接切掉。
        if let plus = body.firstIndex(of: "+") {
            body = String(body[body.startIndex..<plus])
        }

        var identifiers: [String] = []
        if let dash = body.firstIndex(of: "-") {
            let tail = String(body[body.index(after: dash)...])
            body = String(body[body.startIndex..<dash])
            identifiers = tail.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            // 空的预发布段（`1.0.0-`、`1.0.0-beta..1`）当读不懂：比较时分不清大小。
            guard !identifiers.contains(where: \.isEmpty) else { return nil }
            guard identifiers.allSatisfy({ Self.isValidIdentifier($0) }) else { return nil }
        }

        let components = body.split(separator: ".", omittingEmptySubsequences: false)
        guard !components.isEmpty else { return nil }
        var numbers: [Int] = []
        // 只认前三段；出现第四段（`1.2.3.4`）时忽略多余的部分，不因此拒绝比较。
        for component in components.prefix(3) {
            guard !component.isEmpty,
                  component.unicodeScalars.allSatisfy({ Self.isASCIIDigit($0) }),
                  let value = Int(component), value >= 0 else { return nil }
            numbers.append(value)
        }
        self.major = numbers.count > 0 ? numbers[0] : 0
        self.minor = numbers.count > 1 ? numbers[1] : 0
        self.patch = numbers.count > 2 ? numbers[2] : 0
        self.prerelease = identifiers
        self.raw = original
    }

    public var isPrerelease: Bool { !prerelease.isEmpty }

    /// `1.2.3` / `1.2.3-beta.2`（缺段补齐成三段，方便上屏与比较日志）。
    public var description: String {
        SemanticVersion.text(major: major, minor: minor, patch: patch, prerelease: prerelease)
    }

    // MARK: 比较

    public static func == (lhs: SemanticVersion, rhs: SemanticVersion) -> Bool {
        lhs.major == rhs.major && lhs.minor == rhs.minor && lhs.patch == rhs.patch
            && lhs.prerelease == rhs.prerelease
    }

    public static func < (lhs: SemanticVersion, rhs: SemanticVersion) -> Bool {
        if lhs.major != rhs.major { return lhs.major < rhs.major }
        if lhs.minor != rhs.minor { return lhs.minor < rhs.minor }
        if lhs.patch != rhs.patch { return lhs.patch < rhs.patch }
        switch (lhs.prerelease.isEmpty, rhs.prerelease.isEmpty) {
        case (true, false): return false   // 正式版 > 预发布版
        case (false, true): return true
        case (true, true): return false
        case (false, false): break
        }
        for (left, right) in zip(lhs.prerelease, rhs.prerelease) {
            if left == right { continue }
            let leftNumber = Int(left)
            let rightNumber = Int(right)
            if let leftNumber, let rightNumber { return leftNumber < rightNumber }
            // 数字标识符的优先级低于字母标识符（semver 2.0.0 §11.4.3）。
            if leftNumber != nil { return true }
            if rightNumber != nil { return false }
            return left < right
        }
        return lhs.prerelease.count < rhs.prerelease.count
    }

    // MARK: 内部

    private static func text(major: Int, minor: Int, patch: Int, prerelease: [String]) -> String {
        let core = "\(major).\(minor).\(patch)"
        return prerelease.isEmpty ? core : core + "-" + prerelease.joined(separator: ".")
    }

    private static func isASCIIDigit(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value >= 48 && scalar.value <= 57
    }

    private static func isValidIdentifier(_ identifier: String) -> Bool {
        identifier.unicodeScalars.allSatisfy { scalar in
            isASCIIDigit(scalar)
                || (scalar.value >= 65 && scalar.value <= 90)   // A-Z
                || (scalar.value >= 97 && scalar.value <= 122)  // a-z
                || scalar.value == 45                           // '-'
        }
    }
}

// MARK: - GitHub Releases 的一个版本

/// GitHub `GET /repos/{owner}/{repo}/releases/latest` 的一条记录（只取用得上的字段）。
public struct UpdateRelease: Equatable, Sendable {

    /// `tag_name`，例如 `v0.16.0`。版本比较以它为准。
    public let tagName: String
    /// `name`，发布标题；可能为空（GitHub 允许不填）。
    public let name: String
    /// `body`，发布说明（Markdown 原文）；可能为空。
    public let notes: String
    /// `html_url`，人看的发布页；解析不出来 / 不是 http(s) 时为 nil。
    public let htmlURL: URL?
    /// `published_at`；解析不出来时为 nil。
    public let publishedAt: Date?
    /// `prerelease`：这条发布被标成了预发布。
    public let isPrerelease: Bool

    public init(
        tagName: String,
        name: String = "",
        notes: String = "",
        htmlURL: URL? = nil,
        publishedAt: Date? = nil,
        isPrerelease: Bool = false
    ) {
        self.tagName = tagName
        self.name = name
        self.notes = notes
        self.htmlURL = htmlURL
        self.publishedAt = publishedAt
        self.isPrerelease = isPrerelease
    }

    /// 这条发布对应的版本号：先看 tag（`v0.16.0`），再退到标题。
    public var version: SemanticVersion? {
        SemanticVersion(tagName) ?? SemanticVersion(name)
    }

    /// 「去下载」要打开的地址：发布页优先，没有就退回仓库的 releases 列表。
    public var downloadURL: URL {
        htmlURL ?? UpdateChecker.releasesPageURL
    }

    /// 把 GitHub 的**单条** JSON（`GET /releases/latest`）解成这个结构。
    /// 字段缺失按空值处理；连 `tag_name` 都没有才算读不懂。
    public static func decode(_ data: Data) throws -> UpdateRelease {
        let payload: GitHubReleasePayload
        do {
            payload = try JSONDecoder().decode(GitHubReleasePayload.self, from: data)
        } catch {
            throw UpdateCheckError.decoding
        }
        guard let release = payload.toRelease() else { throw UpdateCheckError.decoding }
        return release
    }

    /// 把 GitHub 的**列表** JSON（`GET /releases?per_page=N`）解成若干条（跳过草稿 / 坏条目）。
    public static func decodeList(_ data: Data) throws -> [UpdateRelease] {
        let payloads: [GitHubReleasePayload]
        do {
            payloads = try JSONDecoder().decode([GitHubReleasePayload].self, from: data)
        } catch {
            throw UpdateCheckError.decoding
        }
        return payloads.compactMap { $0.toRelease() }
    }

    // MARK: 内部

    /// GitHub 的字段名（snake_case）；只列用得到的，其余忽略。
    private struct GitHubReleasePayload: Decodable {
        let tag_name: String?
        let name: String?
        let body: String?
        let html_url: String?
        let published_at: String?
        let prerelease: Bool?
        let draft: Bool?

        /// 草稿绝不提示（只有有推送权限的人看得到，但别让调试令牌漏出来）。
        func toRelease() -> UpdateRelease? {
            if draft == true { return nil }
            let tag = tag_name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !tag.isEmpty else { return nil }
            return UpdateRelease(
                tagName: tag,
                name: name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
                notes: body ?? "",
                htmlURL: UpdateRelease.validWebURL(html_url),
                publishedAt: UpdateRelease.parseTimestamp(published_at),
                isPrerelease: prerelease ?? false
            )
        }
    }

    /// 只接受 http(s)：任何人都不该因为一条 `javascript:` 或 `file:` 链接被弹窗带走。
    static func validWebURL(_ raw: String?) -> URL? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              let url = URL(string: text),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else { return nil }
        return url
    }

    static func parseTimestamp(_ raw: String?) -> Date? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            return nil
        }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let date = plain.date(from: text) { return date }
        // GitHub 偶尔会带小数秒；多给一次机会，读不出来就当没有时间。
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: text)
    }
}

// MARK: - 错误与结果

/// 检查更新失败的原因。文案是给人看的（设置页手动检查时上屏），自动检查不上屏。
public enum UpdateCheckError: Error, Equatable, Sendable, CustomStringConvertible {

    case network
    case timeout
    /// 非 2xx（401 限额、404 仓库改名、5xx GitHub 故障……）。
    case httpStatus(Int)
    /// 回复不是能读懂的 GitHub release JSON。
    case decoding

    public var description: String {
        switch self {
        case .network:
            return String(localized: "检查更新失败：网络连不上，之后再试。")
        case .timeout:
            return String(localized: "检查更新失败：等太久没有回应，之后再试。")
        case .httpStatus(let status):
            return String(
                format: String(localized: "检查更新失败：GitHub 返回了 %d。"), status
            )
        case .decoding:
            return String(localized: "检查更新失败：看不懂 GitHub 的回复。")
        }
    }
}

/// 一次检查的结果。**只有** `.updateAvailable` 会让界面弹窗；其余都在设置页里安静显示。
public enum UpdateCheckOutcome: Equatable, Sendable {

    case updateAvailable(UpdateRelease)
    /// 已经是最新的（或远端的版本号读不出来）。`latest` 是远端 tag，便于排查。
    case upToDate(latest: String?)
    /// 24 小时节流命中：这次没有发请求。
    case throttled(since: Date)
    case failed(UpdateCheckError)
}

// MARK: - 偏好（UserDefaults）

/// 更新检查的三项本地状态：自动检查开关 / 是否包含预览版 / 上次检查时间。
///
/// 放 UserDefaults 而不是 `AppSettings`：它们描述的是「这台设备多久问一次 GitHub」，
/// 不是「他是谁」；跟着备份搬到新手机上只会让新手机第一天就跳过检查，没有意义。
public final class UpdatePreferences {

    public enum Key {
        public static let autoCheckEnabled = "update_auto_check_enabled"
        public static let includesPrerelease = "update_includes_prerelease"
        public static let lastCheckAt = "update_last_check_at"
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// 自动检查（**默认开**）：每次进前台安静地问一次，24 小时内不重复。
    public var autoCheckEnabled: Bool {
        get {
            // `object(forKey:)` 而不是 `bool(forKey:)`：后者分不清「没设过」与「设成 false」，
            // 而没设过必须是「开」（默认开是产品要求）。
            defaults.object(forKey: Key.autoCheckEnabled) as? Bool ?? true
        }
        set { defaults.set(newValue, forKey: Key.autoCheckEnabled) }
    }

    /// 是否把预发布版也当成更新（**默认关**）：预览版可能不稳定，不该默认推给用户。
    public var includesPrerelease: Bool {
        get { defaults.object(forKey: Key.includesPrerelease) as? Bool ?? false }
        set { defaults.set(newValue, forKey: Key.includesPrerelease) }
    }

    /// 上一次真的发过请求的时间（失败也算：没网的时候不该每次进前台都戳一次）。
    public var lastCheckAt: Date? {
        get {
            guard defaults.object(forKey: Key.lastCheckAt) != nil else { return nil }
            let seconds = defaults.double(forKey: Key.lastCheckAt)
            guard seconds > 0 else { return nil }
            return Date(timeIntervalSince1970: seconds)
        }
        set {
            guard let newValue else {
                defaults.removeObject(forKey: Key.lastCheckAt)
                return
            }
            defaults.set(newValue.timeIntervalSince1970, forKey: Key.lastCheckAt)
        }
    }
}

// MARK: - 检查器

/// 问 GitHub「最新版是哪个」，并和本机版本比。
///
/// 用法（App 层见 `UpdateCenter`）：
/// ```swift
/// let checker = UpdateChecker(currentVersion: "0.14.2")
/// let outcome = await checker.check(manual: true)   // 手动：绕过节流
/// let silent = await checker.check()                // 自动：24h 节流内直接返回 .throttled
/// ```
///
/// 线程：没有可变内部状态（状态都在 `UpdatePreferences` 里），可以从任何线程调。
public final class UpdateChecker: @unchecked Sendable {

    /// GitHub Releases API（公开仓库，匿名可读；带上 User-Agent 是 GitHub 的要求）。
    public static let releasesURL = URL(
        string: "https://api.github.com/repos/HepingGe2333/YetAnotherPal/releases/latest"
    )!

    /// 人看的发布列表页：`html_url` 缺失时的兜底下载地址。
    public static let releasesPageURL = URL(
        string: "https://github.com/HepingGe2333/YetAnotherPal/releases"
    )!

    /// 「包含预览版」时用的列表接口。
    ///
    /// 为什么不能继续用 `/releases/latest`：GitHub 的 latest **永远只指向非草稿、非预发布**的
    /// 那一条，所以只在「忽略预览版」时才有意义。设置里打开预览版后必须换成列表接口，
    /// 自己挑版本号最大的那条（列表里草稿 / 预发布都在）。
    public static let releasesListURL = URL(
        string: "https://api.github.com/repos/HepingGe2333/YetAnotherPal/releases?per_page=20"
    )!

    /// 自动检查的节流窗口。
    public static let throttleInterval: TimeInterval = 24 * 60 * 60

    /// 默认请求超时。检查更新是「顺手问一句」，不该拖 30 秒。
    public static let defaultTimeout: TimeInterval = 10

    private let currentVersion: String
    private let session: URLSession
    private let preferences: UpdatePreferences
    private let releasesURL: URL
    private let userAgent: String
    private let timeout: TimeInterval
    private let now: @Sendable () -> Date

    public init(
        currentVersion: String,
        session: URLSession = .shared,
        preferences: UpdatePreferences = UpdatePreferences(),
        releasesURL: URL = UpdateChecker.releasesURL,
        userAgent: String? = nil,
        timeout: TimeInterval = UpdateChecker.defaultTimeout,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.currentVersion = currentVersion
        self.session = session
        self.preferences = preferences
        self.releasesURL = releasesURL
        self.userAgent = userAgent ?? "Yap-iOS/\(currentVersion)"
        self.timeout = timeout
        self.now = now
    }

    /// 查一次。
    ///
    /// - Parameter manual: `true` = 用户按了「检查更新」，**绕过** 24h 节流；
    ///   `false` = 自动检查，节流内直接返回 `.throttled`。
    public func check(manual: Bool = false) async -> UpdateCheckOutcome {
        let startedAt = now()
        if !manual, let last = preferences.lastCheckAt,
           startedAt.timeIntervalSince(last) < Self.throttleInterval {
            return .throttled(since: last)
        }
        let outcome = await fetch()
        // 失败也记下时间：否则「没网」时每次回前台都会再发一次，节流形同虚设。
        preferences.lastCheckAt = startedAt
        return outcome
    }

    // MARK: 内部

    private func fetch() async -> UpdateCheckOutcome {
        // 只看正式版：走 `/releases/latest`（GitHub 保证它指向最新正式版）。
        // 连预览版一起看：走列表接口，自己挑版本号最大的那条（latest 永远不会返回预发布）。
        if preferences.includesPrerelease {
            return await fetchNewestIncludingPrerelease()
        }
        return await fetchLatest()
    }

    private func fetchLatest() async -> UpdateCheckOutcome {
        switch await load(releasesURL) {
        case .failure(let error):
            return .failed(error)
        case .success(let data):
            guard let release = try? UpdateRelease.decode(data) else {
                return .failed(.decoding)
            }
            return evaluate(release)
        }
    }

    private func fetchNewestIncludingPrerelease() async -> UpdateCheckOutcome {
        switch await load(Self.releasesListURL) {
        case .failure(let error):
            return .failed(error)
        case .success(let data):
            guard let releases = try? UpdateRelease.decodeList(data) else {
                return .failed(.decoding)
            }
            guard let newest = Self.newest(of: releases) else {
                // 一条发布都没有：没有可比的版本，不算失败。
                return .upToDate(latest: nil)
            }
            return evaluate(newest)
        }
    }

    /// 列表接口里「版本号最大」的那一条：版本号相同比发布时间，再相同按 tag 名定序（保证确定性）。
    static func newest(of releases: [UpdateRelease]) -> UpdateRelease? {
        releases.max { lhs, rhs in
            let left = lhs.version
            let right = rhs.version
            switch (left, right) {
            case (nil, nil):
                return (lhs.publishedAt ?? .distantPast) < (rhs.publishedAt ?? .distantPast)
            case (nil, _):
                return true
            case (_, nil):
                return false
            case (let l?, let r?):
                if l != r { return l < r }
                let leftDate = lhs.publishedAt ?? .distantPast
                let rightDate = rhs.publishedAt ?? .distantPast
                if leftDate != rightDate { return leftDate < rightDate }
                return lhs.tagName < rhs.tagName
            }
        }
    }

    /// 发一次 GET 并做「HTTP 状态 → 错误原因」的换算（没有可变状态，可以从任何线程调）。
    private func load(_ url: URL) async -> Result<Data, UpdateCheckError> {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        // GitHub 对没有 User-Agent 的请求直接 403；Accept 只影响版本头的选择，写上更稳。
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        // 检查更新永远要新鲜的答案：命中本地缓存等于没检查。
        request.cachePolicy = .reloadIgnoringLocalCacheData

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            return .failure(error.code == .timedOut ? .timeout : .network)
        } catch {
            return .failure(.network)
        }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            return .failure(.httpStatus(http.statusCode))
        }
        return .success(data)
    }

    /// 远端 vs 本机：只有**严格更新**才提示；预发布版按设置决定要不要看。
    private func evaluate(_ release: UpdateRelease) -> UpdateCheckOutcome {
        guard let remote = release.version else {
            return .upToDate(latest: release.tagName)
        }
        // 两种情况都算预发布：GitHub 上标了 prerelease，或者 tag 自己带 `-beta.1` 这类后缀。
        if (release.isPrerelease || remote.isPrerelease) && !preferences.includesPrerelease {
            return .upToDate(latest: release.tagName)
        }
        guard let local = SemanticVersion(currentVersion) else {
            // 本机版本号读不出来（几乎只可能是测试宿主）：不提示，也不报错。
            return .upToDate(latest: release.tagName)
        }
        return remote > local ? .updateAvailable(release) : .upToDate(latest: release.tagName)
    }
}
