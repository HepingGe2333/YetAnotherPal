import Combine
import SwiftUI

// ---------------------------------------------------------------------------
// 更新渠道的界面状态（0.14.2）。
//
// 分工：
// - `UpdateChecker`（YapKit）只做「问 GitHub + 比版本」，不碰界面；
// - 这里（App 层）负责三件事：设置页的「检查更新」与两个开关、前台启动的静默检查、
//   以及把「有新版本」变成一次弹窗（弹窗本体在 `RootView`，见 `UpdateAlertModifier`）。
//
// 为什么自动检查放在 App 层：它要跟着 `scenePhase` 走（冷启动一次、从后台回来一次），
// 而 `scenePhase` 只有 SwiftUI 看得见。Kit 里不做定时器 —— 检查更新不值得一个后台任务。
//
// 注意：Xcode 工程把 `YapKit/Sources/YapKit` 直接编进 App target（同一个模块），
// 所以这里**不需要**（也不能）`import YapKit`。
// ---------------------------------------------------------------------------

@MainActor
final class UpdateCenter: ObservableObject {

    /// 本机版本（`CFBundleShortVersionString`）。
    let currentVersion: String

    /// 正在检查（设置页那一行显示转圈）。
    @Published private(set) var isChecking = false
    /// 有新版本、等着弹窗的那一条；nil = 没有待弹的更新。
    @Published private(set) var pendingUpdate: UpdateRelease?
    /// 上一次真的发过请求的时间（节流游标）。
    @Published private(set) var lastCheckAt: Date?
    /// 上一次检查的结果（设置页显示「已经是最新」或失败原因）。
    @Published private(set) var lastOutcome: UpdateCheckOutcome?
    /// 自动检查开关（默认开，24 小时节流）。
    @Published private(set) var autoCheckEnabled: Bool
    /// 是否把预览版也当更新（默认关）。
    @Published private(set) var includesPrerelease: Bool

    private let preferences: UpdatePreferences
    private let checker: UpdateChecker

    init(
        currentVersion: String,
        preferences: UpdatePreferences = UpdatePreferences()
    ) {
        self.currentVersion = currentVersion
        self.preferences = preferences
        self.checker = UpdateChecker(currentVersion: currentVersion, preferences: preferences)
        self.autoCheckEnabled = preferences.autoCheckEnabled
        self.includesPrerelease = preferences.includesPrerelease
        self.lastCheckAt = preferences.lastCheckAt
    }

    // MARK: - 检查

    /// 手动检查（设置页按了按钮）：**绕过节流**，结果交给调用方显示在横幅里。
    ///
    /// 有新版本时不返回横幅文案 —— 弹窗由根视图负责，两条提示同时出现只会重复。
    func checkNow() async -> UpdateCheckOutcome {
        guard !isChecking else {
            return lastOutcome ?? .throttled(since: lastCheckAt ?? Date())
        }
        isChecking = true
        let outcome = await checker.check(manual: true)
        isChecking = false
        apply(outcome)
        return outcome
    }

    /// 前台静默检查：冷启动一次、从后台回来一次；24 小时内不会真的发第二次请求。
    ///
    /// 失败完全静默（不弹窗、不打扰）；有新版本才把 `pendingUpdate` 交给根视图弹窗。
    func checkOnForeground() async {
        guard autoCheckEnabled, !isChecking else { return }
        let outcome = await checker.check(manual: false)
        if case .throttled = outcome { return }
        apply(outcome)
    }

    private func apply(_ outcome: UpdateCheckOutcome) {
        lastCheckAt = preferences.lastCheckAt
        lastOutcome = outcome
        if case .updateAvailable(let release) = outcome {
            pendingUpdate = release
        }
    }

    // MARK: - 开关

    func setAutoCheckEnabled(_ enabled: Bool) {
        preferences.autoCheckEnabled = enabled
        autoCheckEnabled = enabled
        // 节流游标（上次检查时间）刻意保留：开关关掉再打开时，用户不该立刻又被发一次请求，
        // 也不该在设置页看到「还没检查过」这种明明检查过的状态。
    }

    func setIncludesPrerelease(_ enabled: Bool) {
        preferences.includesPrerelease = enabled
        includesPrerelease = enabled
    }

    func dismissPendingUpdate() {
        pendingUpdate = nil
    }

    // MARK: - 文案

    /// 设置页「上次检查」那一行；从没检查过时是「还没检查过」。
    var lastCheckText: String {
        guard let lastCheckAt else { return L("还没检查过") }
        let moment = formatBackupMoment(Int64(lastCheckAt.timeIntervalSince1970 * 1000))
        return Lf("上次检查：%@", moment)
    }

    /// 上一次检查的结果说明；节流与「还没检查过」不显示。
    var lastResultText: String? {
        guard let lastOutcome else { return nil }
        switch lastOutcome {
        case .updateAvailable(let release):
            return Lf("有新的版本 v%@", release.version?.description ?? release.tagName)
        case .upToDate:
            return Lf("已经是最新版本（v%@）", currentVersion)
        case .throttled:
            return nil
        case .failed(let error):
            // 失败原因由 Kit 给出（中英都在资源里），这里不重复拼句子。
            return error.description
        }
    }

    /// 弹窗标题：「有新的版本 v0.16.0」。
    var alertTitle: String {
        guard let release = pendingUpdate else { return L("检查更新") }
        return Lf("有新的版本 v%@", release.version?.description ?? release.tagName)
    }

    /// 弹窗正文：发布标题 + 说明（截断）+ 一句「怎么装」。
    var alertMessage: String {
        guard let release = pendingUpdate else { return "" }
        var parts: [String] = []
        if !release.name.isEmpty, release.name != release.tagName {
            parts.append(release.name)
        }
        let notes = Self.notesPreview(release.notes)
        if !notes.isEmpty {
            parts.append(notes)
        }
        parts.append(L("下载地址会在浏览器里打开；装好再回来，他还是原来的样子。"))
        return parts.joined(separator: "\n\n")
    }

    /// 发布说明只截前 600 个字符：`alert` 不是阅读器，看全文可以点「去下载」。
    private static func notesPreview(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let limit = 600
        guard trimmed.count > limit else { return trimmed }
        return String(trimmed.prefix(limit)) + "…"
    }
}

// MARK: - 弹窗

/// 把「有新版本」挂到根视图上：不管这次检查是自动还是手动触发的，都只弹一次。
///
/// 为什么放在根视图而不是设置页：自动检查发生在冷启动 / 回前台，那时候用户可能在首页、
/// 在聊天里，未必在设置页；弹窗挂在根视图上才不会漏。
struct UpdateAlertModifier: ViewModifier {
    @ObservedObject var center: UpdateCenter
    @Environment(\.openURL) private var openURL

    func body(content: Content) -> some View {
        content.alert(
            center.alertTitle,
            isPresented: Binding(
                get: { center.pendingUpdate != nil },
                set: { presented in if !presented { center.dismissPendingUpdate() } }
            ),
            presenting: center.pendingUpdate
        ) { release in
            Button(L("去下载")) {
                center.dismissPendingUpdate()
                openURL(release.downloadURL)
            }
            Button(L("稍后"), role: .cancel) {
                center.dismissPendingUpdate()
            }
        } message: { _ in
            Text(center.alertMessage)
        }
    }
}

extension View {
    /// 根视图挂一次就够：所有页面共享同一个待弹的更新。
    func updateAlert(_ center: UpdateCenter) -> some View {
        modifier(UpdateAlertModifier(center: center))
    }
}
