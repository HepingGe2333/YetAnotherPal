import SwiftUI

/// 设置页里的「版本」小节（0.14.2）：当前版本 / 手动检查 / 自动检查开关 / 预览版开关 / 上次检查。
///
/// 为什么单独一个文件、而不是写在 `SettingsScreen` 里：
/// 更新检查的状态在 `UpdateCenter`（一个 `ObservableObject`）里，而 `SettingsScreen` 只观察
/// `AppEnvironment`；把它写成独立视图之后，`@ObservedObject` 才能让「正在检查…」「上次检查」
/// 这些字在检查结束时立刻刷新，不需要让整个 AppEnvironment 跟着转发一遍。
struct UpdateSettingsSection: View {
    @ObservedObject var updates: UpdateCenter
    let accent: Color
    /// 手动检查的结果（只用来显示横幅）。有新版本时**不**调用 ——
    /// 那一条由根视图的弹窗负责，两条提示同时出现只会重复。
    let onResult: (MFAlert) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("版本"))

            MFCard {
                currentVersionRow

                MFRowDivider(leadingInset: 0)

                checkButton

                MFRowDivider(leadingInset: 0)

                MFToggleRow(
                    icon: "arrow.down.circle",
                    title: L("自动检查更新"),
                    subtitle: L("开着的时候，每次打开 App 会安静地问一次 GitHub；24 小时内不会重复问。"),
                    isOn: Binding(
                        get: { updates.autoCheckEnabled },
                        set: { updates.setAutoCheckEnabled($0) }
                    ),
                    tint: accent
                )

                MFRowDivider(leadingInset: 0)

                MFToggleRow(
                    icon: "testtube.2",
                    title: L("包含预览版"),
                    subtitle: L("预览版还没正式发布，可能不稳定。"),
                    isOn: Binding(
                        get: { updates.includesPrerelease },
                        set: { updates.setIncludesPrerelease($0) }
                    ),
                    tint: accent
                )

                MFRowDivider(leadingInset: 0)

                lastCheckLine
            }
        }
    }

    // MARK: - 行

    private var currentVersionRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "app.badge")
                .font(.system(size: 15))
                .foregroundStyle(accent)
                .frame(width: 22, height: 20)
            Text(L("当前版本"))
                .font(.system(.body, design: .serif))
            Spacer(minLength: 4)
            Text("v\(updates.currentVersion)")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 8)
    }

    private var checkButton: some View {
        Button(action: checkForUpdates) {
            HStack(spacing: 12) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 15))
                    .foregroundStyle(accent)
                    .frame(width: 22, height: 20)
                Text(updates.isChecking ? L("正在检查…") : L("检查更新"))
                    .font(.system(.body, design: .serif))
                Spacer(minLength: 4)
                if updates.isChecking {
                    ProgressView().scaleEffect(0.7)
                } else {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(updates.isChecking)
    }

    private var lastCheckLine: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(updates.lastCheckText)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let result = updates.lastResultText {
                Text(result)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: - 动作

    /// 手动检查：绕过节流；结果里只有「没有更新」和「失败」需要横幅，新版本交给弹窗。
    private func checkForUpdates() {
        guard !updates.isChecking else { return }
        Task { @MainActor in
            switch await updates.checkNow() {
            case .updateAvailable:
                break
            case .upToDate:
                onResult(MFAlert(
                    title: L("已经是最新版本"),
                    message: Lf("当前版本 v%@，GitHub 上还没有更新的正式版。", updates.currentVersion)
                ))
            case .failed(let error):
                onResult(MFAlert(title: L("检查更新失败"), message: error.description))
            case .throttled:
                break
            }
        }
    }
}
