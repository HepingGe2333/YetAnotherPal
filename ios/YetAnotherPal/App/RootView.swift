import SwiftUI

/// 根视图：负责「打不开数据库」这种启动失败，以及把 `AppEnvironment` 传给所有页面。
struct RootView: View {
    @EnvironmentObject private var env: AppEnvironment
    @State private var path = NavigationPath()

    var body: some View {
        if let error = env.bootError {
            BootFailureView(message: error)
        } else {
            NavigationStack(path: $path) {
                HomeView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .chat(let sessionId):
                            ChatScreen(sessionId: sessionId)
                        case .chatAt(let sessionId, let messageId):
                            ChatScreen(sessionId: sessionId, focusMessageId: messageId)
                        case .search:
                            SearchScreen()
                        case .browser(let url):
                            InAppBrowserView(url: url)
                        case .settings:
                            SettingsScreen()
                        case .personas:
                            PersonaListScreen()
                        case .personaEditor(let personaId):
                            PersonaEditorScreen(personaId: personaId)
                        case .memories:
                            MemoryListScreen()
                        case .backups:
                            BackupScreen()
                        case .proactive:
                            ProactiveListScreen()
                        }
                    }
            }
            .environment(\.mfNavigationPath, $path)
            // 新版本弹窗挂在根视图：自动检查发生在冷启动 / 回前台，那时候用户不一定在设置页。
            .updateAlert(env.updates)
            .onReceive(AppNotifications.shared.$pendingSessionId) { sessionId in
                // 点开通知 → 进他那段聊天（0.11.1 审计 P2-2）。
                guard let sessionId, !sessionId.isEmpty else { return }
                openPendingSession(sessionId)
            }
            .task {
                // 冷启动时点通知进会话（0.11.0 审计 P2-7）：delegate 可能早在首帧之前就收到了响应，
                // 那一帧的 `@Published` 没人订阅 —— 所以先看持久化的待办，消费一次就清掉。
                // `consumeIfSessionExists` 取走即清（幂等），并会丢弃指向已删会话的深链。
                if let sessionId = AppNotifications.shared.pendingDeepLink.consumeIfSessionExists({
                    env.sessionExists(id: $0)
                }) {
                    AppNotifications.shared.pendingSessionId = nil
                    path.append(Route.chat(sessionId: sessionId))
                }
                // 调试启动助手：Debug 包里读环境变量（建本地 API 配置、可选自动发一句话、直接进会话页）。
                // Release 包里 `DebugSeeding.bootstrap` 是空实现，这段等于不存在。
                DebugSeeding.bootstrap(env: env, path: $path)
            }
        }
    }

    /// 消费一条待打开的通知深链（0.11.1 审计 P2-2）。
    ///
    /// 两件事必须一起做，否则「点一次通知」会变成「以后每次冷启动都跳旧会话」：
    /// 1. **消费即清**：温启动（App 已在运行/后台）那次点击也会写 UserDefaults，
    ///    原来只有根视图首次 `.task` 才 `consume()`，键留着 → 下次冷启动再跳一次。
    /// 2. **打开前校验会话存在**：不存在就丢弃、留在首页（不 push 一个空白聊天页）。
    private func openPendingSession(_ sessionId: String) {
        AppNotifications.shared.pendingDeepLink.clear()
        AppNotifications.shared.pendingSessionId = nil
        guard env.sessionExists(id: sessionId) else {
            DebugSeeding.log("[deeplink] 会话已经不在了（\(sessionId)），丢弃并回首页")
            return
        }
        path.append(Route.chat(sessionId: sessionId))
    }
}

private struct BootFailureView: View {
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(.secondary)
            Text(L("打不开他"))
                .font(.system(.title3, design: .serif))
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }
}
