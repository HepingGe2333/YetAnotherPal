import SwiftUI

@main
struct YetAnotherPalApp: App {

    @StateObject private var env = AppEnvironment()
    @Environment(\.scenePhase) private var scenePhase
    /// 上一次的 `scenePhase`。
    ///
    /// 为什么需要它：冷启动时 SwiftUI 会让 `inactive → active` 走一次 `onChange`，
    /// 而 `.task` 已经在同一个启动里补算过了——不区分「真正从后台回来」就会跑两遍
    ///（两次心跳 + 两次每日整理，0.10.2 审计 P1-2）。
    @State private var lastScenePhase: ScenePhase = .inactive

    init() {
        // 通知 delegate **必须**在启动完成前装配（0.11.0 审计 P2-7）：
        // `UNUserNotificationCenter` 只在 `didFinishLaunching` 返回前交出「点击冷启动」
        // 那一次响应；SwiftUI 的 `.task` 要等首帧之后才跑，那时回调已经错过，
        // 于是「点通知冷启动」只会打开首页。这里提前设好，配合 `PendingDeepLink` 落盘。
        AppNotifications.shared.configure()
        // 后台调度必须在启动完成前注册（标识符与 Config/Info.plist 里的一致）。
        // 模拟器上系统不会执行这两种任务，`BackgroundScheduler` 会直接跳过注册；
        // 注册失败也不影响前台，一切只是「少了一次后台机会」。
        BackgroundScheduler.shared.register()
        // 0.11.1 审计 P2-1：`remote-strict` 的失败必须可见。把决策层诊断接到 Debug 日志上
        // （Release 包里 `DebugSeeding.log` 是空实现，但计数仍在 `AgentCoreDiagnostics` 里可查）。
        AgentCoreDiagnostics.shared.setSink { message in
            DebugSeeding.log(message)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(env)
                .preferredColorScheme(colorScheme(for: env.currentSettings.themeMode))
                .dynamicTypeSize(dynamicType(for: env.currentSettings.fontScale))
                .tint(Color(argb: env.currentSettings.accentColor))
                .task {
                    // 通知权限（第一次启动问一次）+ 后台任务真的能拿到 AppEnvironment。
                    // delegate 已在 `init()` 里装配过；这里只补「权限申请」和后台任务的装配。
                    AppNotifications.shared.configure()
                    BackgroundScheduler.shared.attach(env: env)
                    // 启动自检：清掉库里那些「不像记忆」的旧摘要（1.7 事故的脏数据不会自己消失）。
                    _ = env.repairHistorySummariesIfNeeded()
                    // 打开 App 时补算错过的时间（iOS 不允许常驻后台，只能在这里补）。
                    await env.performCatchUp()
                    // 之后每 30 秒一拍（低电量 2 分钟），与 Android 的前台服务同节拍。
                    env.startForegroundPulse()
                    // 服务端模式（M3/M4）：同步一次状态、起前台事件长轮询、补发待发消息。
                    // 本地模式下 `activateServerMode()` 立刻返回，什么都不做。
                    await env.activateServerMode()
                    // 冷启动安静地问一次「有没有新版本」（24h 节流；失败完全静默）。
                    // 单独开一个 Task：检查更新不该拖慢启动，更不该让 `.task` 等网络。
                    Task { await env.updates.checkOnForeground() }
                    lastScenePhase = scenePhase
                }
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active:
                        AppNotifications.shared.setAppActive(true)
                        // 只有「从后台真的回来」才补算；冷启动那一次已经由上面的 `.task` 做过了。
                        if lastScenePhase == .background {
                            Task { await env.performCatchUp() }
                        }
                        env.startForegroundPulse()
                        // 回前台：服务端模式重新同步 + 重新起事件流（进后台时被取消了）。
                        Task { await env.activateServerMode() }
                        // 回前台也顺手问一次更新（同一个 24h 节流）；失败不打扰。
                        Task { await env.updates.checkOnForeground() }
                    case .background:
                        AppNotifications.shared.setAppActive(false)
                        env.stopForegroundPulse()
                        // 事件长轮询是前台才做的事：进后台立刻停，省电也省流量。
                        env.stopServerEvents()
                        // 进后台前把下一次后台时间排上；拿不到就算了。
                        BackgroundScheduler.shared.scheduleNext()
                    default:
                        break
                    }
                    lastScenePhase = phase
                }
        }
    }

    /// 字号缩放 → 系统的动态字号档位。
    ///
    /// 用系统档位而不是到处乘 `fontScale`：这样图标、行高、间距会一起跟着变，
    /// 也不会出现「字放大了但气泡没变大」这种错位。
    private func dynamicType(for scale: Float) -> DynamicTypeSize {
        switch scale {
        case ..<0.9: return .small
        case ..<1.05: return .large
        case ..<1.2: return .xLarge
        default: return .xxLarge
        }
    }

    /// 主题模式 → SwiftUI 的 `ColorScheme`。跟随系统时传 nil，交给系统决定。
    private func colorScheme(for mode: ThemeMode) -> ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
