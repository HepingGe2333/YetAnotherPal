import PhotosUI
import SwiftUI

/// 设置主页。
///
/// 1.9 的瘦身（§七）：只剩外观 / API 配置 / 联系人 / 用量 / 主动 / 备份 / 危险区。
/// 记忆开关、读消息的节奏、额度、每天主动次数**全部删除** —— 用户面对的是一个联系人，
/// 不是一个可以调参数的机器人。
///
/// 顺序按 iOS 用户真正会碰到的频率排：先外观，再「能不能用」的 API，然后是人与用量，
/// 最后才是会删东西的两颗按钮。
///
/// 所有改动都走 `env.updateSettings { }`，点一下立刻落盘、立刻生效；
/// 没有「保存」按钮，也没有「未保存修改」这种需要用户额外理解的中间态。
struct SettingsScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var personas: [PersonaEntity] = []
    @State private var configs: [ApiConfigEntity] = []
    @State private var banner: MFAlert?
    @State private var pendingAction: PendingAction?
    /// 设置「我的头像」用的相册选择项（读完就清空）。
    @State private var myAvatarPick: PhotosPickerItem?

    private var settings: AppSettings { env.currentSettings }

    /// 「我的头像」那一行的滚动锚点（Debug 截图用）。
    private static let myAvatarAnchor = "settings-my-avatar"
    /// 「主动唤醒」入口的滚动锚点（Debug 截图用）。
    private static let proactiveAnchor = "settings-proactive"
    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    if let error = env.serverWriteError {
                        // 服务端模式：设置没写进服务端时明确说一句（本机保持上一次确认的值）。
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(MFUI.danger)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    appearanceSection
                    apiSection
                    ServerModeSection()
                    personaSection
                    SettingsUsageSection()
                    proactiveSection
                    backupSection
                    updateSection
                    feedbackSection
                    dangerSection
                    footer
                }
                .padding(.horizontal, MFTheme.gutter)
                .padding(.bottom, 40)
            }
            .background(MFTheme.canvas(scheme).ignoresSafeArea())
            .onAppear {
                reload()
                // Debug：截图验收用（`YAP_DEBUG_OPEN=settings-avatar|settings-proactive`）。
                if DebugSeeding.openSettingsAvatar {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            proxy.scrollTo(Self.myAvatarAnchor, anchor: .center)
                        }
                    }
                } else if DebugSeeding.openSettingsProactive {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            proxy.scrollTo(Self.proactiveAnchor, anchor: .center)
                        }
                    }
                }
            }
        }
        .navigationTitle(L("设置"))
        .navigationBarTitleDisplayMode(.inline)
        .mfAlert($banner)
        .confirmationDialog(
            pendingAction?.title ?? "",
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { presented in if !presented { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(pendingAction?.confirmTitle ?? L("确定"), role: .destructive) {
                guard let action = pendingAction else { return }
                pendingAction = nil
                perform(action)
            }
            Button(L("取消"), role: .cancel) { pendingAction = nil }
        } message: {
            Text(pendingAction?.message ?? "")
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text("Yap")
                    .font(.system(size: 30, weight: .bold, design: .serif))
                Spacer()
                Text("v\(AppEnvironment.appVersion)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(L("留一处安静的地方，慢慢说话"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            Text(summaryLine)
                .font(.caption)
                .foregroundStyle(accent)
                .padding(.top, 2)
        }
        .padding(.top, 8)
    }

    private var summaryLine: String {
        let memoryCount = (try? env.stores.memories.count()) ?? 0
        // 三段各自处理单复数，再拼起来；不要把三个数字塞进一句话里（英语的单复数会顾此失彼）。
        return [
            Lcount("%d 个人", personas.count),
            Lcount("%d 个 API 配置", configs.count),
            Lcount("%d 条记忆", memoryCount)
        ].joined(separator: " · ")
    }

    // MARK: - 外观

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("外观"))

            MFCard {
                VStack(alignment: .leading, spacing: 8) {
                    MFRowTitle(L("主题"), hint: L("跟随系统最省心；浅色与深色只影响这套界面。"))
                    MFChoiceBar(
                        options: [
                            (ThemeMode.system, L("跟随系统")),
                            (ThemeMode.light, L("浅色")),
                            (ThemeMode.dark, L("深色"))
                        ],
                        selection: binding(\.themeMode),
                        accent: accent
                    )
                }

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 10) {
                    MFRowTitle(L("主色"), hint: L("用在发送气泡、按钮与高亮上。"))
                    HStack(spacing: 10) {
                        ForEach(MFUI.accentChoices, id: \.self) { argb in
                            accentDot(argb)
                        }
                        Spacer(minLength: 0)
                        ColorPicker(L("自定义主色"), selection: accentBinding, supportsOpacity: false)
                            .labelsHidden()
                    }
                }
                .padding(.vertical, 12)

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(L("字号"))
                            .font(.system(.body, design: .serif))
                        Spacer()
                        Text("\(Int((settings.fontScale * 100).rounded()))%")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    HStack(spacing: 10) {
                        Text("A").font(.system(size: 12)).foregroundStyle(.secondary)
                        Slider(value: fontScaleBinding, in: 0.85...1.3, step: 0.05)
                            .tint(accent)
                        Text("A").font(.system(size: 18)).foregroundStyle(.secondary)
                    }
                    Text(L("只改字的大小，不改布局。0.85 到 1.3 之间。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 12)

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 8) {
                    MFRowTitle(L("气泡样式"), hint: L("圆角与留白密度。"))
                    MFChoiceBar(
                        options: [
                            (BubbleStyle.soft, L("柔和")),
                            (BubbleStyle.compact, L("紧凑")),
                            (BubbleStyle.outlined, L("描边"))
                        ],
                        selection: binding(\.bubbleStyle),
                        accent: accent
                    )
                }
                .padding(.vertical, 12)

                MFRowDivider(leadingInset: 0)

                MFToggleRow(
                    icon: "sparkles",
                    title: L("界面动画"),
                    subtitle: L("关掉后页面与气泡的过渡更克制。"),
                    isOn: binding(\.animationsEnabled),
                    tint: accent
                )
                MFRowDivider()
                MFToggleRow(
                    icon: "hand.tap",
                    title: L("触感反馈"),
                    subtitle: L("长按、切人格时的轻微振动。"),
                    isOn: binding(\.hapticsEnabled),
                    tint: accent
                )
            }
        }
    }

    private func accentDot(_ argb: Int64) -> some View {
        let selected = settings.accentColor == argb
        return Button {
            env.updateSettings { $0.accentColor = argb }
        } label: {
            Circle()
                .fill(Color(argb: argb))
                .frame(width: 26, height: 26)
                .overlay(
                    Circle()
                        .strokeBorder(Color.primary.opacity(selected ? 0.55 : 0), lineWidth: 1.5)
                        .padding(-3)
                )
                .overlay(
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .opacity(selected ? 1 : 0)
                )
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(L("主色")))
    }

    // MARK: - API 配置

    private var apiSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("API 配置"))

            MFCard(padding: 6) {
                if configs.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("还没有 API 配置"))
                            .font(.system(.body, design: .serif))
                        Text(L("没有它他就没法开口说话。填一个 OpenAI 兼容的服务就行：OpenAI、DeepSeek、OpenRouter 都可以。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(10)
                    MFRowDivider(leadingInset: 8)
                } else {
                    ForEach(configs, id: \.id) { config in
                        NavigationLink(destination: ApiConfigEditorScreen(configId: config.id)) {
                            apiConfigRow(config)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button {
                                pendingAction = .deleteConfig(config)
                            } label: {
                                Label(L("删除这个配置"), systemImage: "trash")
                            }
                        }
                        MFRowDivider(leadingInset: 52)
                    }
                }

                NavigationLink(destination: ApiConfigEditorScreen(configId: nil)) {
                    HStack(spacing: 10) {
                        Image(systemName: "plus.circle")
                            .font(.system(size: 16))
                            .foregroundStyle(accent)
                        Text(L("添加 API 配置"))
                            .font(.system(.body, design: .serif))
                            .foregroundStyle(accent)
                        Spacer()
                    }
                    .padding(.vertical, 12)
                    .padding(.horizontal, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            MFNote(text: L("Key 存在这台手机的 Keychain 里：不进备份、不进数据库，也不会显示回来。"))
        }
    }

    private func apiConfigRow(_ config: ApiConfigEntity) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "network")
                .font(.system(size: 14))
                .foregroundStyle(accent)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(config.name.isEmpty ? L("未命名配置") : config.name)
                        .font(.system(.body, design: .serif))
                        .lineLimit(1)
                    if config.id == env.activeApiConfig?.id {
                        Text(L("默认"))
                            .font(.caption2)
                            .foregroundStyle(accent)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(accent.opacity(0.14), in: Capsule())
                    }
                }
                Text("\(MFProviderLabel.text(config.provider)) · \(config.model.isEmpty ? "还没填模型" : config.model)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 8)
        .contentShape(Rectangle())
    }

    // MARK: - 联系人（人格）

    /// 1.9 把「人格管理」从首页挪到了设置里（首页现在就是联系人列表，§七）：
    /// 人格是「他是谁」这件事，属于设置，不属于每天要看的那个列表。
    private var personaSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("联系人"))

            MFCard(padding: 6) {
                NavigationLink(value: Route.personas) {
                    HStack(spacing: 12) {
                        Image(systemName: "person.2")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Lcount("管理联系人（%d 个）", personas.count))
                                .font(.system(.body, design: .serif))
                            Text(L("改他的性格、记性、作息；也可以新建或删除一个人。"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                NavigationLink(value: Route.memories) {
                    HStack(spacing: 12) {
                        Image(systemName: "brain")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("他记得的事"))
                                .font(.system(.body, design: .serif))
                            Text(L("记忆一直在自己整理：留下重要的，把不重要的慢慢淡下去。"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                NavigationLink(destination: PresetListScreen()) {
                    HStack(spacing: 12) {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("人格预设"))
                                .font(.system(.body, design: .serif))
                            Text(L("一整套设定：他是谁、他在什么世界、他怎么说话。可以应用或复制到人身上。"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                myAvatarRow
            }

            MFNote(text: L("这一版不再有「记忆开关」和「回复节奏」：他记不记得、什么时候回，都由他自己决定。"))

            MFCard {
                MFToggleRow(
                    icon: "character.book.closed",
                    title: L("自动翻译人设"),
                    subtitle: L("界面是英文时，进聊天前把他的人设、世界与话风翻成英文；只发设定，不发聊天记录。"),
                    isOn: binding(\.autoTranslatePersona),
                    tint: accent
                )
            }
        }
    }

    // MARK: - 我的头像

    /// 「我的头像」：安静的一行。只在聊天里你自己那一侧显示，图片不跟着备份走。
    ///
    /// 为什么不单开一页：换头像就是一次选择，多一层页面只会让它显得像个功能。
    private var myAvatarRow: some View {
        HStack(spacing: 12) {
            MFAvatar(myAvatarUri: settings.myAvatarUri, size: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text(L("我的头像"))
                    .font(.system(.body, design: .serif))
                Text(settings.myAvatarUri == nil
                     ? L("给自己选一张；聊天里你那一侧会显示。")
                     : L("只在你自己那一侧显示。图片不跟着备份走。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 6)

            HStack(spacing: 12) {
                if settings.myAvatarUri != nil {
                    Button(L("移除")) { removeMyAvatar() }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .buttonStyle(.plain)
                }
                PhotosPicker(selection: $myAvatarPick, matching: .images, photoLibrary: .shared()) {
                    Text(settings.myAvatarUri == nil ? L("选择") : L("换一张"))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(accent)
                }
                .onChange(of: myAvatarPick) { item in
                    importMyAvatar(item)
                }
            }
        }
        .padding(10)
        .contentShape(Rectangle())
        .id(Self.myAvatarAnchor)
    }

    private func importMyAvatar(_ item: PhotosPickerItem?) {
        guard let item else { return }
        myAvatarPick = nil
        Task { @MainActor in
            let outcome = await ImageImport.importOne(item, kind: .avatar)
            switch outcome {
            case .success(let attachment):
                if env.serverModeActive {
                    // 服务端模式：设置里存的是 `/v1/media/<id>`，别的设备也看得到。
                    await uploadMyAvatar(attachment)
                    return
                }
                let previous = env.currentSettings.myAvatarUri
                env.updateSettings { $0.myAvatarUri = attachment.uri }
                // 新头像已经落库，再删旧图；只删零引用的（旧图可能与人格/预设头像共用）。
                MediaStorage.shared.deleteIfUnreferenced(
                    previous,
                    references: MediaReferences(stores: env.stores, settings: env.settings)
                )
            case .failure(let message):
                banner = MFAlert(title: L("这张图用不了"), message: message)
            }
        }
    }

    /// 服务端模式下选头像：本地选图（已压缩到 ≤512/≤400KB）→ `POST /v1/media?kind=USER_AVATAR`
    /// → 设置里写 `/v1/media/<id>`。失败时本机设置不动，只把原因显示出来。
    private func uploadMyAvatar(_ attachment: MessageAttachment) async {
        let previous = env.currentSettings.myAvatarUri
        do {
            guard let uri = try await env.uploadMyAvatar(localURI: attachment.uri) else { return }
            // 换下来的是本机文件就删掉（只删零引用的；服务端路径不能被本地媒体清理碰到）。
            MediaStorage.shared.deleteIfUnreferenced(
                previous,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
            // 刚上传的这份本机副本已经不再被设置引用：同样按引用计数清理。
            MediaStorage.shared.deleteIfUnreferenced(
                attachment.uri,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
            DebugSeeding.log("[server] 我的头像已换成 \(uri)")
        } catch {
            // 上传失败：清掉刚落的临时文件，设置保持原样。
            MediaStorage.shared.deleteIfUnreferenced(
                attachment.uri,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
            banner = MFAlert(
                title: L("这张图用不了"),
                message: error.localizedDescription
            )
        }
    }

    private func removeMyAvatar() {
        let previous = env.currentSettings.myAvatarUri
        if env.serverModeActive, let writes = env.serverWrites {
            // 服务端模式：删除必须**显式**推一个 null 上去。缺键在服务端是「别动它」，
            // 只写本机的话，下次拉设置又会被装回来。
            Task { @MainActor in
                var cleared = env.currentSettings
                cleared.myAvatarUri = nil
                do {
                    _ = try await writes.clearUserAvatar(from: cleared)
                    env.updateSettings { $0.myAvatarUri = nil }
                    MediaStorage.shared.deleteIfUnreferenced(
                        previous,
                        references: MediaReferences(stores: env.stores, settings: env.settings)
                    )
                } catch {
                    banner = MFAlert(title: L("没能删掉"), message: error.localizedDescription)
                }
            }
            return
        }
        env.updateSettings { $0.myAvatarUri = nil }
        // 设置里已经不再引用它；仍要确认人格/预设/消息没有引用同一张图。
        MediaStorage.shared.deleteIfUnreferenced(
            previous,
            references: MediaReferences(stores: env.stores, settings: env.settings)
        )
    }

    // MARK: - 主动

    private var proactiveSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("主动"))

            MFCard {
                MFToggleRow(
                    icon: "bolt.heart",
                    title: L("常驻思考"),
                    subtitle: L("开着的时候，他会在前台每 30 秒想一次事情，进后台后登记一次机会式刷新。"),
                    isOn: binding(\.agentLoopEnabled),
                    tint: accent
                )

                NavigationLink(value: Route.proactive) {
                    HStack(spacing: 12) {
                        Image(systemName: "alarm")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("主动唤醒"))
                                .font(.system(.body, design: .serif))
                            Text(L("他会按自己的节奏来找你；时间不是准点，是一段窗口。"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFNote(
                    text: L("每天最多主动几次，现在由他自己决定（联系人 → 主动程度）。")
                        + LText.sentenceSeparator + L("iOS 不保证后台准时：系统可能几十分钟到几小时才给一次机会，也可能一天都不给。")
                        + LText.sentenceSeparator + L("所以他有时候像睡着了——不是不理你。")
                )
            }
        }
        .id(Self.proactiveAnchor)
    }

    // MARK: - 备份

    private var backupSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("备份"))

            MFCard(padding: 6) {
                NavigationLink(value: Route.backups) {
                    HStack(spacing: 12) {
                        Image(systemName: "shippingbox")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("把他带走 / 接他回来"))
                                .font(.system(.body, design: .serif))
                            Text(L("一份 JSON，装着他、你们的对话和他记得的事。密钥不在里面。"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - 更新检查

    /// 0.14.2：版本小节（当前版本 / 手动检查 / 自动检查 / 预览版 / 上次检查）。
    private var updateSection: some View {
        UpdateSettingsSection(updates: env.updates, accent: accent) { alert in
            banner = alert
        }
    }

    // MARK: - 反馈

    /// 0.14.2：反馈入口。内容先落本机再打开预填的 GitHub Issue（见 `FeedbackScreen`）。
    private var feedbackSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: FeedbackCopy.screenTitle)

            MFCard {
                NavigationLink(destination: FeedbackScreen()) {
                    HStack(spacing: 10) {
                        Image(systemName: "envelope")
                            .font(.system(size: 14))
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(FeedbackCopy.screenTitle)
                                .font(.system(.body, design: .serif))
                            Text(FeedbackCopy.settingsSubtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - 危险区

    private var dangerSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("危险区"))

            MFCard {
                MFNote(text: L("清空之前，先把他带走。删掉的东西不会自己回来。"), icon: "exclamationmark.triangle")

                Button {
                    pendingAction = .clearChats
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .frame(width: 22)
                        Text(L("清空全部聊天与记忆"))
                            .font(.system(.body, design: .serif))
                        Spacer()
                    }
                    .foregroundStyle(MFUI.danger)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                Button {
                    pendingAction = .clearAll
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "delete.left")
                            .font(.system(size: 14))
                            .frame(width: 22)
                        Text(L("删除全部本地数据"))
                            .font(.system(.body, design: .serif))
                        Spacer()
                    }
                    .foregroundStyle(MFUI.danger)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFNote(
                    text: L("「清空」只清会话、消息与记忆，人格和 API 配置留着；「删除全部」会连人格、配置与设置一起删，App 回到刚装上的样子。")
                )
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("关于 Yap"))
                .font(.system(.footnote, design: .serif))
                .foregroundStyle(.secondary)
            Text(L("他在这台手机上的一切都只存在这台手机上：对话、记忆、用量都不上传。"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    // MARK: - 绑定与动作

    private func binding<Value>(_ keyPath: WritableKeyPath<AppSettings, Value>) -> Binding<Value> {
        Binding(
            get: { env.currentSettings[keyPath: keyPath] },
            set: { newValue in
                env.updateSettings { $0[keyPath: keyPath] = newValue }
            }
        )
    }

    private var fontScaleBinding: Binding<Double> {
        Binding(
            get: { Double(env.currentSettings.fontScale) },
            set: { value in
                env.updateSettings { $0.fontScale = Float(value) }
            }
        )
    }

    private var accentBinding: Binding<Color> {
        Binding(
            get: { Color(argb: env.currentSettings.accentColor) },
            set: { color in
                env.updateSettings { $0.accentColor = Int64.argb(from: color) }
            }
        )
    }

    private func reload() {
        personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
        configs = (try? env.stores.apiConfigs.allOrderedByUpdatedAt()) ?? []
    }

    private func perform(_ action: PendingAction) {
        switch action {
        case .deleteConfig(let config):
            deleteConfig(config)
        case .clearChats:
            clearChats()
        case .clearAll:
            clearAll()
        }
    }

    private func deleteConfig(_ config: ApiConfigEntity) {
        do {
            try env.stores.apiConfigs.delete(id: config.id)
            try? env.apiKeys.deleteKeys(for: config.id)
            reload()
            banner = MFAlert(
                title: L("删掉了"),
                message: Lf("「%@」已经从这台手机上删掉。", config.name.isEmpty ? L("这个配置") : config.name)
            )
        } catch {
            banner = MFAlert(title: L("没能删掉"), message: error.localizedDescription)
        }
    }

    private func clearChats() {
        do {
            // 消息要删了，它们带的图片文件也别留在沙盒里（人格头像不动：人还在）。
            // 这一步现在由 `DataReset` 统一做（0.10.2 审计 P1-7）：设置页与备份页不再各写一份。
            let summary = try env.dataReset.clearChats()
            reload()
            banner = MFAlert(title: L("清空了"), message: summary.describeChats())
        } catch {
            banner = MFAlert(title: L("没能清空"), message: error.localizedDescription)
        }
    }

    private func clearAll() {
        // 全部数据都删：附件目录（消息图 + 头像）由 `DataReset` 一起清掉。
        // 清空之后按当前语言重新播种人格（0.11.0 审计 P2-13）：入口只有 `AppEnvironment`。
        let summary = env.clearAllData()
        // 清空会重置 SettingsStore；AppEnvironment 的内存快照要跟着换一份，
        // 否则界面上还挂着已经不存在的人格与主题。
        env.reloadSettings()
        reload()
        banner = MFAlert(title: L("都删掉了"), message: summary.describeAll())
    }

    /// 三颗会删东西的按钮共用同一个 `confirmationDialog`。
    ///
    /// SwiftUI 里同一层视图挂多个 `confirmationDialog` 时只有最后一个可靠地弹出来，
    /// 所以这里用一个动作枚举 + 一个弹窗，文案按枚举分支给。
    private enum PendingAction {
        case deleteConfig(ApiConfigEntity)
        case clearChats
        case clearAll

        var title: String {
            switch self {
            case .deleteConfig(let config):
                let name = config.name.isEmpty ? L("这个配置") : config.name
                return Lf("删除「%@」？", name)
            case .clearChats:
                return L("清空全部聊天与记忆？")
            case .clearAll:
                return L("删除全部本地数据？")
            }
        }

        var confirmTitle: String {
            switch self {
            case .deleteConfig: return L("删除")
            case .clearChats: return L("清空")
            case .clearAll: return L("全部删除")
            }
        }

        var message: String {
            switch self {
            case .deleteConfig:
                return L("这台手机上存的 Key 也会一起删掉。备份里本来就没有密钥，删了就只能重新填一次。")
            case .clearChats:
                return L("所有会话、消息和长期记忆会被删除。人格与 API 配置会留着。这个动作没法撤销——想留个底的话，先把他带走。")
            case .clearAll:
                return L("人格、聊天、记忆、预设、主动节奏、API 配置与设置都会删掉，App 回到刚装上的样子。想留个底的话，先用上面的「把他带走」。")
            }
        }
    }
}

/// 卡片里的小标题 + 一句解释（主题、主色、气泡样式这些「成组」的项用）。
struct MFRowTitle: View {
    let title: String
    let hint: String

    init(_ title: String, hint: String) {
        self.title = title
        self.hint = hint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.system(.body, design: .serif))
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
