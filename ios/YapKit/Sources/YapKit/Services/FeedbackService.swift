import Foundation

// ---------------------------------------------------------------------------
// 0.14.2「反馈」：把用户的问题/建议**先落一份到本机**，再打开一个预填好的 GitHub Issue。
//
// 为什么是这个顺序（而不是直接让用户去 GitHub）：
// 1. 用户可能没网、没 GitHub 账号、或者浏览器打不开 —— 但「我说过的这段话」不该丢；
//    本机那份 `Application Support/feedback/feedback-<ts>.json` 是最后的凭据。
// 2. GitHub 的 `issues/new?title=&body=` 链接能把内容整段预填好，用户只要点「Submit」。
//    链接必须是我们自己拼的 **https** 地址（见 `FeedbackComposer.isSecureIssueURL`）：
//    这个字符串最终交给系统 `openURL`，任何非 https 的东西都不该从这条路出去。
// 3. 落盘必须**原子**：写到一半被杀进程不能留下半截 JSON（下次读不出来，用户会以为存了）。
//
// 边界：
// - 这个文件只做「校验 / 落盘 / 拼链接」三件事，不碰 UI、不打开浏览器（App 层用
//   `openURL` 环境值打开），所以它能在 macOS 上直接 `swift test`。
// - 反馈内容**不出本机**（除了用户自己点开的那条 GitHub 链接）；不上传、不进日志。
// - 内容上限 1000 字、联系方式 200 字：与产品文案里写的数字同源（`maxContentLength`）。
// ---------------------------------------------------------------------------

/// 反馈类型（GitHub Issue 标题的前缀由它决定）。
public enum FeedbackKind: String, CaseIterable, Sendable {
    /// 用起来不对 / 崩了 / 和预期不一样。
    case bug = "BUG"
    /// 想要的新功能 / 改进建议。
    case suggestion = "SUGGESTION"
    /// 其它（表扬、疑问、说不清的）。
    case other = "OTHER"

    /// 宽容解析（坏数据 / 未来的新类型 → `other`，绝不抛）。
    public init(stored: String?) {
        let key = (stored ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        self = FeedbackKind(rawValue: key) ?? .other
    }

    public var stored: String { rawValue }

    /// 界面上的类型名（也是 Issue 标题前缀）。
    public var label: String {
        switch self {
        case .bug: return String(localized: "问题反馈")
        case .suggestion: return String(localized: "功能建议")
        case .other: return String(localized: "其他反馈")
        }
    }
}

/// 校验失败的原因（界面把它渲染成一句能看懂的话；不抛异常，方便 UI 直接用）。
public enum FeedbackValidationError: Error, Equatable, Sendable {
    case emptyContent
    case contentTooLong(limit: Int)
    case contactTooLong(limit: Int)

    /// 给用户看的一句话（中英文案在 `Localizable.strings`；Kit 侧走 `String(localized:)`）。
    public var message: String {
        switch self {
        case .emptyContent:
            return String(localized: "反馈内容不能为空。")
        case .contentTooLong(let limit):
            return String(format: String(localized: "反馈内容最多 %d 字。"), limit)
        case .contactTooLong(let limit):
            return String(format: String(localized: "联系方式最多 %d 字。"), limit)
        }
    }
}

/// 一次还没提交的反馈（界面上的三个输入框）。
public struct FeedbackDraft: Equatable, Sendable {
    /// 内容上限：产品文案里写的就是这个数字（`FeedbackCopy.contentHint` 的 `%d`）。
    public static let maxContentLength = 1000
    /// 联系方式上限：邮箱/社交账号都够，又不至于让人粘贴一整段。
    public static let maxContactLength = 200

    public var kind: FeedbackKind
    public var content: String
    /// 可选的联系方式：空串 = 不想留。
    public var contact: String

    public init(kind: FeedbackKind = .bug, content: String = "", contact: String = "") {
        self.kind = kind
        self.content = content
        self.contact = contact
    }

    /// 去掉首尾空白后的正文（校验与落盘都用它）。
    public var trimmedContent: String {
        content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public var trimmedContact: String {
        contact.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 能不能提交；`nil` = 通过。
    ///
    /// 用 `String.count`（字素簇）而不是 UTF-16 长度：用户数的是「字」，
    /// emoji 被算成两个字会让人莫名其妙地超限。
    public func validate() -> FeedbackValidationError? {
        let text = trimmedContent
        guard !text.isEmpty else { return .emptyContent }
        guard text.count <= Self.maxContentLength else {
            return .contentTooLong(limit: Self.maxContentLength)
        }
        guard trimmedContact.count <= Self.maxContactLength else {
            return .contactTooLong(limit: Self.maxContactLength)
        }
        return nil
    }
}

/// 落盘的那一份反馈（本机 JSON；**不含任何密钥**，只有用户自己写的内容与构建信息）。
public struct FeedbackRecord: Equatable, Sendable {
    public var kind: FeedbackKind
    public var content: String
    public var contact: String?
    public var appVersion: String
    public var platform: String
    /// 毫秒时间戳（与全库一致）。
    public var createdAt: Int64

    public init(
        kind: FeedbackKind,
        content: String,
        contact: String? = nil,
        appVersion: String,
        platform: String,
        createdAt: Int64
    ) {
        self.kind = kind
        self.content = content
        self.contact = contact
        self.appVersion = appVersion
        self.platform = platform
        self.createdAt = createdAt
    }

    /// 从草稿造一份可落盘的记录；草稿无效时返回 `nil`（调用方先跑 `validate()`）。
    public static func make(
        draft: FeedbackDraft,
        appVersion: String,
        platform: String,
        createdAt: Int64
    ) -> FeedbackRecord? {
        guard draft.validate() == nil else { return nil }
        let contact = draft.trimmedContact
        return FeedbackRecord(
            kind: draft.kind,
            content: draft.trimmedContent,
            contact: contact.isEmpty ? nil : contact,
            appVersion: appVersion,
            platform: platform,
            createdAt: createdAt
        )
    }

    /// 文件名：`feedback-<毫秒时间戳>.json`（排序 = 时间序）。
    public var fileName: String { "feedback-\(createdAt).json" }

    /// 写入文件的 JSON（字段名固定，方便用户在 Finder 里直接看）。
    public var jsonObject: [String: Any] {
        var object: [String: Any] = [
            "kind": kind.stored,
            "content": content,
            "appVersion": appVersion,
            "platform": platform,
            "createdAt": createdAt,
            "createdAtISO": Self.isoText(millis: createdAt)
        ]
        if let contact { object["contact"] = contact }
        return object
    }

    /// 稳定、带缩进、键排序的 JSON（测试与人工排查都读它）。
    public func jsonData() throws -> Data {
        try JSONSerialization.data(
            withJSONObject: jsonObject,
            options: [.prettyPrinted, .sortedKeys]
        )
    }

    /// 毫秒时间戳 → ISO-8601（本机时区带偏移），给人看的。
    static func isoText(millis: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }
}

/// 本机反馈目录（`Application Support/feedback/`）。
///
/// 为什么不放 Documents：反馈是**诊断材料**，不该出现在「文件」App 里被随手删掉；
/// 也不该进备份（换机带走的应该是人格，不是一份旧 bug 报告）。
public final class FeedbackStore {
    /// 目录名（App 内固定；测试用临时目录）。
    public static let directoryName = "feedback"

    private let directory: URL
    private let fileManager: FileManager

    /// - Parameters:
    ///   - directory: 目标目录，默认 `Application Support/feedback`。
    ///   - fileManager: 便于测试注入（生产用 `.default`）。
    public init(
        directory: URL = FeedbackStore.defaultDirectory(),
        fileManager: FileManager = .default
    ) {
        self.directory = directory
        self.fileManager = fileManager
    }

    /// `Application Support/feedback`（App Sandbox 内；不存在时由 `save` 创建）。
    public static func defaultDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent(directoryName, isDirectory: true)
    }

    /// 这条记录最终写到哪个文件（同一毫秒重复提交时加 `-2` / `-3` 后缀，不覆盖旧文件）。
    public func url(for record: FeedbackRecord) -> URL {
        let base = directory.appendingPathComponent(record.fileName)
        guard fileManager.fileExists(atPath: base.path) else { return base }
        let stem = base.deletingPathExtension().lastPathComponent
        for index in 2...999 {
            let candidate = directory.appendingPathComponent("\(stem)-\(index).json")
            if !fileManager.fileExists(atPath: candidate.path) { return candidate }
        }
        return directory.appendingPathComponent("\(stem)-\(UUID().uuidString).json")
    }

    /// 原子写入（`.atomic` = 先写临时文件再 rename，杀进程也不会留下半截 JSON）。
    ///
    /// - Returns: 真正写出的文件地址（调用方可以把它显示在日志/诊断页里）。
    @discardableResult
    public func save(_ record: FeedbackRecord) throws -> URL {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = url(for: record)
        try record.jsonData().write(to: target, options: [.atomic])
        return target
    }

    /// 本机现有的反馈文件（按文件名 = 时间序；给「诊断/导出」这类只读场景用）。
    public func fileURLs() -> [URL] {
        let names = (try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil
        )) ?? []
        return names
            .filter { $0.lastPathComponent.hasPrefix("feedback-") && $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
}

/// 拼 GitHub Issue 预填链接（纯函数，无网络）。
public enum FeedbackComposer {

    /// 仓库（与 `UpdateChecker` 的 Releases 地址同一个仓库）。
    public static let repositorySlug = "HepingGe2333/YetAnotherPal"

    /// 新建 Issue 的地址（**只允许 https**；见 `isSecureIssueURL`）。
    public static let newIssueURLString = "https://github.com/HepingGe2333/YetAnotherPal/issues/new"

    /// 标题里正文摘要的长度（GitHub 标题太长会被截断，60 字够看出是哪一类问题）。
    public static let titleExcerptLimit = 60

    /// 拼好的预填链接；不合法（不是 https / 没有 host）时返回 `nil`。
    public static func issueURL(for record: FeedbackRecord) -> URL? {
        guard var components = URLComponents(string: newIssueURLString) else { return nil }
        components.queryItems = [
            URLQueryItem(name: "title", value: issueTitle(for: record)),
            URLQueryItem(name: "body", value: issueBody(for: record))
        ]
        guard let url = components.url else { return nil }
        return isSecureIssueURL(url) ? url : nil
    }

    /// https + 有 host 才算合法。
    ///
    /// 为什么单独抽出来：这个 URL 会被 App 层交给系统打开，任何 `http` / `javascript:` /
    /// 空 host 的字符串都不该从这条路出去。拼装之后**再校验一次**（而不是只信常量）。
    public static func isSecureIssueURL(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https" else { return false }
        guard let host = url.host, !host.isEmpty else { return false }
        return true
    }

    /// Issue 标题：`问题反馈：<正文摘要>`。
    public static func issueTitle(for record: FeedbackRecord) -> String {
        String(
            format: String(localized: "%@：%@"),
            record.kind.label,
            excerpt(record.content)
        )
    }

    /// Issue 正文：类型 / 内容 / 联系方式 / 版本 / 平台 / 时间（Markdown，一行一项）。
    public static func issueBody(for record: FeedbackRecord) -> String {
        var lines: [String] = [
            String(format: String(localized: "类型：%@"), record.kind.label),
            String(format: String(localized: "内容：\n%@"), record.content),
            String(
                format: String(localized: "联系方式：%@"),
                record.contact ?? String(localized: "（未填）")
            ),
            String(format: String(localized: "App 版本：%@"), record.appVersion),
            String(format: String(localized: "平台：%@"), record.platform),
            String(format: String(localized: "时间：%@"), FeedbackRecord.isoText(millis: record.createdAt))
        ]
        lines.append("")
        lines.append(String(localized: "（由 App 内「反馈」自动生成。）"))
        return lines.joined(separator: "\n")
    }

    /// 正文摘要：把换行压成空格、去掉首尾空白、截到 `titleExcerptLimit` 个字。
    public static func excerpt(_ content: String) -> String {
        let flattened = content
            .replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let compact = flattened
            .split(whereSeparator: { $0 == " " || $0 == "\t" })
            .joined(separator: " ")
        guard compact.count > titleExcerptLimit else { return compact }
        return String(compact.prefix(titleExcerptLimit)) + "…"
    }

    // MARK: - 构建信息

    /// 本机 App 版本（`CFBundleShortVersionString`；没有 bundle 时返回空串）。
    public static func appVersion(bundle: Bundle = .main) -> String {
        let version = bundle.infoDictionary?["CFBundleShortVersionString"] as? String
        return (version ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 平台描述（`iOS 18.2` / `macOS 15.1`；测试可以注入固定值）。
    public static var platform: String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let numbers = "\(version.majorVersion).\(version.minorVersion)"
        #if os(iOS)
        return "iOS \(numbers)"
        #elseif os(macOS)
        return "macOS \(numbers)"
        #elseif os(watchOS)
        return "watchOS \(numbers)"
        #elseif os(tvOS)
        return "tvOS \(numbers)"
        #else
        return "unknown \(numbers)"
        #endif
    }
}

/// 反馈页要显示的所有文案（Kit 侧唯一来源；两份 `Localizable.strings` 必须成对）。
///
/// 为什么放在 Kit 而不是 View 里：校验失败的提示是由 `FeedbackValidationError` 产出的，
/// 它和页面标题/按钮必须在同一个文件里，才不会出现「校验说 1000、页面写 2000」。
public enum FeedbackCopy {
    public static let screenTitle = String(localized: "反馈")
    public static let kindLabel = String(localized: "反馈类型")
    public static let contentLabel = String(localized: "反馈内容")
    public static let contactLabel = String(localized: "联系方式（可选）")
    public static let submit = String(localized: "提交")
    public static let contentHint = String(
        format: String(localized: "遇到问题、想要的功能，都可以写在这里。最多 %d 字。"),
        FeedbackDraft.maxContentLength
    )
    public static let contactHint = String(localized: "愿意的话可以留个联系方式（邮箱、社交账号都行），方便回访。")
    public static let savedHint = String(localized: "已保存，可在浏览器提交。")
    public static let savedNotOpenedHint = String(localized: "已保存到本机，但没能打开浏览器；可以稍后到 GitHub 提交。")
    public static let saveFailedTitle = String(localized: "没能保存反馈")
    public static let saveFailedHint = String(localized: "本机存储写入失败，这次反馈没有留下；稍后再试一次。")
    /// 设置页里那一行的副标题。
    public static let settingsSubtitle = String(localized: "遇到问题或想要什么，写给我们")
}
