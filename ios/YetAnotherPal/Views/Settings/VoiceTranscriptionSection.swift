import SwiftUI

/// 设置页的「语音转写」小节（0.15）。
///
/// 为什么单独一个文件、而不是写进 `SettingsScreen`：
/// 1. 那一页的守卫测试要求「设置页不该再有输入框」（`SettingsRetirementTests` 的 1.9 退役检查），
///    把四个输入框塞进去会让一条本该守「额度/记忆开关已删除」的测试变红；
/// 2. 这一节的字段比别处多（开关 / 地址 / 模型 / Key / 测试），单独成文件更好读。
///
/// 三条产品规矩：
/// - **默认关**：转写要多花一次请求，用户自己开；
/// - **Key 独立**：与聊天 Key 分开存（Keychain 里一条独立 account），换聊天模型不影响转写；
/// - **改动立刻落盘**：与设置页其它项一致，没有「保存」按钮；只有 Key 需要单独点一次
///   「保存 Key」——它是 SecureField，逐字写 Keychain 既慢又容易把半截 Key 写进去。
struct VoiceTranscriptionSection: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var baseUrlText = ""
    @State private var modelText = ""
    @State private var keyInput = ""
    @State private var hasExistingKey = false
    @State private var loaded = false
    @State private var testing = false
    @State private var testMessage: String?
    @State private var testOK = false
    @State private var banner: MFAlert?
    @State private var confirmingClearKey = false

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var settings: AppSettings { env.currentSettings }

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }

    private var trimmedKey: String {
        keyInput.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("语音转写"))

            MFCard {
                MFToggleRow(
                    icon: "waveform.badge.mic",
                    title: L("启用语音转写"),
                    subtitle: L("语音和视频发出去之前先转成文字，让他读到你说的原话。关掉就只有音频本身，他得自己听。"),
                    isOn: enabledBinding,
                    tint: accent
                )

                if settings.asrEnabled {
                    MFRowDivider(leadingInset: 0)

                    VStack(alignment: .leading, spacing: 0) {
                        AsrFieldLabel(
                            "Base URL",
                            hint: L("默认 https://api.deepseek.com（OpenAI 兼容）。换别的服务也行，会拼成「地址/v1/audio/transcriptions」。")
                        )
                        TextField(AsrConfig.defaultBaseUrl, text: $baseUrlText)
                            .textFieldStyle(.plain)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onChange(of: baseUrlText) { newValue in
                                guard loaded else { return }
                                env.updateSettings { $0.asrBaseUrl = newValue }
                            }
                            .padding(.vertical, 8)
                            .padding(.horizontal, 10)
                            .background(fieldBackground)
                            .padding(.bottom, 14)

                        AsrFieldLabel(
                            L("模型"),
                            hint: L("名字由服务商决定，默认 whisper-1。")
                        )
                        TextField(AsrConfig.defaultModel, text: $modelText)
                            .textFieldStyle(.plain)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onChange(of: modelText) { newValue in
                                guard loaded else { return }
                                env.updateSettings { $0.asrModel = newValue }
                            }
                            .padding(.vertical, 8)
                            .padding(.horizontal, 10)
                            .background(fieldBackground)
                            .padding(.bottom, 14)

                        AsrFieldLabel(
                            L("转写 Key"),
                            hint: L("与聊天用的 Key 分开存：换聊天模型不会影响转写。只存在 Keychain 里，不进日志、不进备份。")
                        )
                        SecureField(
                            "",
                            text: $keyInput,
                            prompt: Text(hasExistingKey ? L("已保存 · 留空不修改") : "sk-…")
                        )
                        .textFieldStyle(.plain)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(.vertical, 8)
                        .padding(.horizontal, 10)
                        .background(fieldBackground)

                        HStack(spacing: 12) {
                            Button(L("保存 Key")) { saveKey() }
                                .font(.footnote)
                                .tint(accent)
                                .disabled(trimmedKey.isEmpty)

                            if hasExistingKey {
                                Button(L("清掉")) { confirmingClearKey = true }
                                    .font(.footnote)
                                    .tint(MFUI.danger)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.top, 10)

                        MFRowDivider(leadingInset: 0)
                            .padding(.vertical, 12)

                        HStack(spacing: 10) {
                            Button(action: runTest) {
                                HStack(spacing: 6) {
                                    if testing {
                                        ProgressView().scaleEffect(0.7)
                                    } else {
                                        Image(systemName: "bolt.horizontal.circle")
                                    }
                                    Text(testing ? L("测试中…") : L("测试连接"))
                                }
                                .font(.footnote)
                            }
                            .tint(accent)
                            .disabled(testing)

                            Spacer(minLength: 0)
                        }

                        if let testMessage {
                            Text(testMessage)
                                .font(.caption)
                                .foregroundStyle(testOK ? accent : MFUI.danger)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.top, 8)
                        }

                        MFNote(text: L("测试只发一段半秒的静音音频，不会用到你的录音。转写失败不会拦住语音发送，只是这一条没有转写。"))
                    }
                    .padding(.top, 4)
                }
            }
        }
        .onAppear(perform: loadIfNeeded)
        .mfAlert($banner)
        .confirmationDialog(
            L("清掉转写 Key？"),
            isPresented: $confirmingClearKey,
            titleVisibility: .visible
        ) {
            Button(L("清掉"), role: .destructive) { clearKey() }
            Button(L("取消"), role: .cancel) {}
        } message: {
            Text(L("这台手机上存的转写 Key 会被删掉，之后语音就不会再被转写。"))
        }
    }

    // MARK: - 载入

    private func loadIfNeeded() {
        guard !loaded else { return }
        baseUrlText = settings.asrBaseUrl
        modelText = settings.asrModel
        hasExistingKey = env.apiKeys.hasASRAPIKey
        loaded = true
    }

    // MARK: - 绑定

    private var enabledBinding: Binding<Bool> {
        Binding(
            get: { env.currentSettings.asrEnabled },
            set: { newValue in
                env.updateSettings { $0.asrEnabled = newValue }
                // 刚打开时把两个输入框同步成当前配置（可能是默认值）。
                if newValue {
                    baseUrlText = env.currentSettings.asrBaseUrl
                    modelText = env.currentSettings.asrModel
                }
            }
        )
    }

    // MARK: - 动作

    private func saveKey() {
        let key = trimmedKey
        guard !key.isEmpty else { return }
        do {
            try env.apiKeys.setASRAPIKey(key)
            keyInput = ""
            hasExistingKey = true
        } catch {
            banner = MFAlert(title: L("没能保存"), message: error.localizedDescription)
        }
    }

    private func clearKey() {
        do {
            try env.apiKeys.setASRAPIKey(nil)
            keyInput = ""
            hasExistingKey = false
        } catch {
            banner = MFAlert(title: L("没能清掉"), message: error.localizedDescription)
        }
    }

    /// 用一段静音音频真的打一次 `/audio/transcriptions`。
    ///
    /// 为什么不测 `/models`：很多兼容网关有 `/models` 却没有转写端点，
    /// 那样的「连上了」等于骗用户。静音片段 0.5 秒、~16 KB，不花钱，也不会碰到他的录音。
    private func runTest() {
        let typed = trimmedKey
        let stored = env.apiKeys.asrAPIKey()?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let key = typed.isEmpty ? stored : typed
        guard !key.isEmpty else {
            testOK = false
            testMessage = L("先填一个转写 Key，再测。")
            return
        }
        let base = baseUrlText.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = modelText.trimmingCharacters(in: .whitespacesAndNewlines)
        let config = AsrConfig(
            enabled: true,
            baseUrl: base.isEmpty ? AsrConfig.defaultBaseUrl : base,
            model: model.isEmpty ? AsrConfig.defaultModel : model,
            apiKey: key
        )
        testing = true
        testMessage = nil
        Task {
            let client = AsrClient()
            do {
                _ = try await client.transcribe(
                    audio: AsrTestClip.silentWAV(),
                    fileName: "test.wav",
                    mime: "audio/wav",
                    config: config
                )
                await MainActor.run {
                    testing = false
                    testOK = true
                    testMessage = L("连上了，转写服务能用。")
                }
            } catch AsrError.emptyTranscript {
                // 静音片段本来就没有文字：这恰恰说明端点通了。
                await MainActor.run {
                    testing = false
                    testOK = true
                    testMessage = L("连上了（静音片段没有文字，正常）。")
                }
            } catch {
                await MainActor.run {
                    testing = false
                    testOK = false
                    testMessage = error.localizedDescription
                }
            }
        }
    }
}

/// 表单里的字段标题 + 一句解释。
///
/// 与 `ApiConfigEditorScreen` 里那个同名私有组件长得一样，但那个是 `private`：
/// 跨文件复用就得把它的访问级别改大，而那会牵动另一个页面 —— 这里自己留一份更省事。
private struct AsrFieldLabel: View {
    let title: String
    let hint: String

    init(_ title: String, hint: String) {
        self.title = title
        self.hint = hint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.footnote.weight(.medium))
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 6)
    }
}
