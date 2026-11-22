import PhotosUI
import SwiftUI

/// 人格编辑页（= 联系人编辑，1.9 §七）。
///
/// 1.9 把「他是谁」从一个巨大的提示词框拆成了分节填写：名字 / 一句话介绍 / 性格 /
/// 喜欢的事 / 行为方式 / 成长经历 / 个人设定，再接三个行为参数（主动程度、记性、
/// 抽记忆频率）与三条睡眠参数（会不会睡、睡的时间段、唤醒阈值）。
///
/// 为什么分节而不是继续写一段话：
/// - 用户填得动。一段 800 字的提示词对大多数人是一堵墙，「性格：慢热、嘴硬心软」是一次点击；
/// - 模型用得上。这些字段会被拼成 `## 你是谁` 下的小节（`PromptBuilder.personaSection`），
///   结构化的小节比一整段话更容易被遵守；
/// - 值可校验。1–5、3–12、1–20 这些范围由界面自己夹住，不用等备份导入时才发现写歪了。
///
/// 提示词全文仍然保留在最下面（可折叠的「高级」）：老用户的那一整段话不会丢。
struct PersonaEditorScreen: View {
    let personaId: String?

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    // MARK: 表单状态

    @State private var name = ""
    @State private var summary = ""
    /// 他思考和说话用的语言（0.15：语言是**人格属性**，不是页面属性）。
    ///
    /// 默认中文（产品默认人格语言）；载入时按 `persona.languageTag` 解析，保存时写回
    /// `PromptLanguage.code`。它与 App 的界面语言完全无关——界面切英文，这个中文人格也不会改口。
    @State private var promptLanguage: PromptLanguage = PersonaLanguagePrompt.fallback
    /// 0.15（schema v20）：模型优化档位（自动 / DeepSeek / GLM）。默认自动。
    @State private var modelProfile: ModelProfile = .auto
    /// 头像：从相册选一张（压到 ≤512px 存沙盒），nil = 用名字首字。
    @State private var avatarUri: String?
    @State private var avatarSeed = 0
    /// 载入时的原头像：保存时用它判断「旧图要不要删」。
    @State private var originalAvatarUri: String?
    @State private var avatarPick: PhotosPickerItem?
    @State private var isImportingAvatar = false
    @State private var prompt = ""
    @State private var modelOverride = ""
    @State private var traits: [String] = []
    @State private var likes: [String] = []
    @State private var behaviors: [String] = []
    @State private var backstory = ""
    @State private var quirks = ""
    // 人设引导（0.12）：世界设定 / 话风。都是「可留空」的补充设定，提示词里空着就整节不出现。
    /// 世界设定的可视化编辑器状态（`spec/persona-guidance/README.md` §2.3：不是 JSON 文本框）。
    @State private var worldEditor = WorldEditorState()
    /// 载入时的世界 JSON：用来判断「这次有没有真的动过」（不动就不写库，别把版本/时间戳白推一次）。
    @State private var initialWorldJson: String?
    /// 提示词翻译（`spec/persona-translation/README.md`）：缓存状态 + 「重新翻译」的进行中标志。
    @State private var translationSummary = ""
    @State private var translationBusy = false
    @State private var speechTone = ""
    @State private var speechPhrases: [String] = []
    @State private var speechHabits: [String] = []
    @State private var speechAvoid: [String] = []
    @State private var speechSource: SpeechStyleSource = .model
    @State private var initialSpeechStyleJson: String?
    /// 用户粘贴的聊天记录：**只在这个页面的内存里**，不落库、不进日志、不进备份。
    @State private var transcript = ""
    @State private var worldBusy = false
    @State private var styleBusy = false
    /// 0.15.7「从最近聊天学习话风」：取样 + 总结中。
    @State private var distillBusy = false
    /// 0.15.7：总结好了、等用户点头的那一份（预览；**取消就什么都不写**）。
    @State private var distillPreview: SpeechStyleSummary?
    @State private var distillPreviewShown = false
    /// 他此刻在做什么（只读：`set_activity` 由他自己写，用户不能改）。
    @State private var activityLine = ""
    @State private var proactiveLevel = PersonaFieldLimits.proactiveLevelDefault
    @State private var memoryRetention = PersonaFieldLimits.memoryRetentionDefault
    @State private var extractionEvery = PersonaFieldLimits.extractionEveryDefault
    /// 新建人格默认不睡（0.12 续 7）：睡眠是可选功能，用户在这里打开才算数。
    /// 已有的人格在 `loadIfNeeded()` 里用实体值覆盖（用户自己设过的一律照旧）。
    @State private var sleepEnabled = false
    @State private var dndOnSleep = false
    @State private var wakeThreshold = PersonaFieldLimits.wakeThresholdDefault
    @State private var sleepTime = PersonaEditorScreen.time(hour: 23, minute: 30)
    @State private var wakeTime = PersonaEditorScreen.time(hour: 8, minute: 0)
    @State private var promptExpanded = false
    // 结构化人设卡：`card` 是编辑中的卡，`initialCard` 是载入时的那份——
    // 只有「真的动过 + 卡非空」才写库，空卡继续沿用手写提示词（与 Android 编辑页同一条规则）。
    @State private var card = PersonaCard()
    @State private var initialCard = PersonaCard()
    @State private var cardExpanded = false
    @State private var loaded = false
    @State private var banner: MFAlert?
    /// 他最近的变化（0.13 人格进化）：待确认的 `persona_pending_patches`。
    /// 空 = 不显示这一张卡（没有变化时不占地方）。
    @State private var pendingChanges: [PersonaPendingPatchEntity] = []
    /// 接受/拒绝请求还在路上：按钮先禁用，避免连点两条。
    @State private var pendingBusy = false

    private var isNew: Bool { personaId == nil }
    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedPrompt: String { prompt.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                identityCard
                languageCard
                worldCard
                speechCard
                translationCard
                characterCard
                recentChangesCard
                storyCard
                personaCardCard
                behaviorCard
                sleepCard
                modelProfileCard
                advancedCard
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(isNew ? L("新联系人") : L("编辑联系人"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("保存")) { save() }
                    .fontWeight(.medium)
                    .disabled(!canSave)
            }
        }
        .onAppear(perform: loadIfNeeded)
        // 选了新头像又取消 / 保存失败：落盘的那张新图没人引用，离开页面时删掉。
        // 保存成功的路径会把 `originalAvatarUri` 同步成新值，这里不会误删。
        .onDisappear(perform: discardUnsavedAvatar)
        .mfAlert($banner)
        // 0.15.7：从最近聊天学话风的预览（取消 = 什么都不写，样本也不留在任何地方）。
        .sheet(isPresented: $distillPreviewShown) { distillPreviewSheet }
    }

    // MARK: - 名字 / 一句话介绍

    private var identityCard: some View {
        MFCard {
            avatarRow

            MFRowDivider(leadingInset: 0)

            FieldCaption(L("名字"), hint: L("他会怎么称呼自己。留空也行，会先叫「未命名」。"))
            TextField(L("比如：林深"), text: $name)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldCaption(L("一句话介绍"), hint: L("给别人看的一句话，也能帮他记住自己是谁。可以留空。"))
            TextField(L("比如：一个温柔而安静的朋友"), text: $summary)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
        }
    }

    // MARK: - 他用的语言（0.15）

    /// 他思考和说话用的语言。
    ///
    /// 为什么放在「名字 / 一句话介绍」下面而不是设置页：它和名字一样是「他是谁」的一部分，
    /// 而不是 App 的偏好。界面上刻意不出现「提示词语言」这种工程词——用户理解的是
    /// 「这个人说哪种话」，提示词只是实现细节。
    ///
    /// 选项固定两个（产品默认中文）：语言列表拉长成十项只会让人犹豫；
    /// 万一从备份里读到别的标签（比如 `ja`），补一项进来原样保留，不悄悄改成中文。
    private var languageCard: some View {
        MFCard {
            Text(L("他用的语言"))
                .font(.system(.body, design: .serif))
            MFNote(text: L("跟 App 的界面语言无关：他一直用这个语言说话。"))
            MFChoiceBar(
                options: languageOptions.map { ($0, languageLabel($0)) },
                selection: $promptLanguage,
                accent: accent
            )
            .padding(.top, 10)
        }
    }

    private var languageOptions: [PromptLanguage] {
        var options: [PromptLanguage] = [.zh, .en]
        if !options.contains(promptLanguage) { options.append(promptLanguage) }
        return options
    }

    /// 选项名用各自语言自己的说法（中文 / English），翻译文件里只放这两条。
    private func languageLabel(_ language: PromptLanguage) -> String {
        switch language {
        case .zh: return L("中文")
        case .en: return L("English")
        // 备份/服务端带回来的其它语言：直接显示母语名，没有对应的界面文案也不报错。
        default: return language.nativeName
        }
    }

    // MARK: - 头像

    /// 头像这一行：预览 + 「从相册选一张 / 换一张 / 恢复默认」。
    ///
    /// 为什么不做成一整页的「头像设置」：换个头像就是一次选择，不该多一层页面。
    /// 压缩（长边 ≤ 512、JPEG）与落盘都在 `ImageImport` 里，这里只负责把 uri 记进表单。
    private var avatarRow: some View {
        HStack(alignment: .top, spacing: 14) {
            MFAvatar(
                personaName: trimmedName.isEmpty ? L("他") : trimmedName,
                avatarUri: avatarUri,
                avatarSeed: avatarSeed == 0 ? Self.avatarSeed(for: trimmedName.isEmpty ? L("他") : trimmedName) : avatarSeed,
                size: 62
            )
            .overlay(alignment: .bottomTrailing) {
                if isImportingAvatar {
                    ProgressView()
                        .scaleEffect(0.6)
                        .frame(width: 22, height: 22)
                        .background(.thinMaterial, in: Circle())
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(L("头像"))
                    .font(.system(.body, design: .serif))
                Text(avatarUri == nil
                     ? L("没选就先用名字的首字。")
                     : L("这张图只存在这台手机上，不跟着备份走。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 14) {
                    PhotosPicker(selection: $avatarPick, matching: .images, photoLibrary: .shared()) {
                        Text(avatarUri == nil ? L("从相册选一张") : L("换一张"))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(accent)
                    }
                    .disabled(isImportingAvatar)
                    .onChange(of: avatarPick) { item in
                        importAvatar(item)
                    }

                    if avatarUri != nil {
                        Button(L("恢复默认")) { removeAvatar() }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .buttonStyle(.plain)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
    }

    private func importAvatar(_ item: PhotosPickerItem?) {
        guard let item else { return }
        avatarPick = nil
        isImportingAvatar = true
        Task { @MainActor in
            let outcome = await ImageImport.importOne(item, kind: .avatar)
            isImportingAvatar = false
            switch outcome {
            case .success(let attachment):
                avatarUri = attachment.uri
            case .failure(let message):
                banner = MFAlert(title: L("这张图用不了"), message: message)
            }
        }
    }

    /// 离开编辑页时，把「选了但没保存」的头像文件清掉（只删我们自己目录里、且已零引用的）。
    ///
    /// 这里也要查引用：选图按内容哈希去重，刚选的那张可能本来就属于另一条消息/人格。
    private func discardUnsavedAvatar() {
        guard avatarUri != originalAvatarUri else { return }
        MediaStorage.shared.deleteIfUnreferenced(
            avatarUri,
            references: MediaReferences(stores: env.stores, settings: env.settings)
        )
        avatarUri = originalAvatarUri
    }

    private func removeAvatar() {
        if avatarUri != nil, avatarUri != originalAvatarUri {
            // 刚选的新图还没落库，删之前仍要确认没有别的记录引用它。
            MediaStorage.shared.deleteIfUnreferenced(
                avatarUri,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
        }
        avatarUri = nil
    }

    // MARK: - 性格 / 喜欢 / 习惯

    // MARK: - 世界设定（人设引导 §2）

    private var worldCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(L("世界设定"))
                    .font(.system(.body, design: .serif))
                Spacer()
                if worldBusy {
                    ProgressView().controlSize(.mini)
                } else {
                    Button(L("AI 补全")) { Task { await expandWorld() } }
                        .font(.footnote)
                        .tint(accent)
                        .disabled(worldIsEmptyInput)
                }
            }
            MFNote(text: L("他在哪儿生活、平时怎么过。留空就只有人格，没有世界。"))

            WorldPresetEditorView(state: $worldEditor, accent: accent)
                .padding(.top, 10)

            MFNote(text: L("AI 只是帮你起个头：随时可以手填、改，也可以完全不用它。补全只用上面的介绍与「个人设定」，不会上传对话。"))
                .padding(.top, 10)

            NavigationLink(destination: PresetListScreen()) {
                HStack(spacing: 6) {
                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 12))
                    Text(L("从人格预设里拿一份设定"))
                        .font(.footnote)
                }
                .foregroundStyle(accent)
            }
            .buttonStyle(.plain)
            .padding(.top, 8)

            if !worldIsCleared {
                Button(L("清空世界设定"), role: .destructive) { clearWorld() }
                    .font(.footnote)
                    .padding(.top, 8)
            }
        }
    }

    // MARK: - 话风（人设引导 §3）

    private var speechCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(L("话风"))
                    .font(.system(.body, design: .serif))
                Spacer()
                if styleBusy {
                    ProgressView().controlSize(.mini)
                } else {
                    Button(L("总结话风")) { Task { await summarizeStyle() } }
                        .font(.footnote)
                        .tint(accent)
                        .disabled(transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            MFNote(text: L("他怎么说话：句子长短、口头禅、不说什么。留空就用默认的短句口语。"))

            FieldCaption(
                L("粘贴一段聊天记录"),
                hint: L("支持「昵称：内容」或多行纯文本；只用来总结风格，不会存下来、也不会进备份。")
            )
            multilineField($transcript, height: 96, placeholder: L("林深：在忙，晚点说\n林深：你先睡吧，不用等我"))
                .padding(.bottom, 6)
            HStack(spacing: 12) {
                Button(L("清空记录")) { transcript = "" }
                    .font(.footnote)
                    .disabled(transcript.isEmpty)
                Spacer()
                Text(Lf("%d 字", transcript.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.bottom, 14)

            // 0.15.7：不想粘贴记录的人也能学——用**他和这个人最近真的聊过的话**。
            // 总结好之后先给一份预览，点「应用」才写库（取消什么都不改）。
            if personaId != nil {
                MFRowDivider(leadingInset: 0)
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("从最近聊天学习"))
                            .font(.footnote.weight(.medium))
                        Text(Lf("看他最近说的 %d 条消息，学他怎么说话。", StyleDistillationService.defaultMessageLimit))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer()
                    if distillBusy {
                        ProgressView().controlSize(.mini)
                    } else {
                        Button(L("学一下")) { Task { await distillStyle() } }
                            .font(.footnote)
                            .tint(accent)
                            .disabled(styleBusy)
                    }
                }
                .padding(.top, 10)
            }

            if speechSource == .heuristic {
                MFNote(
                    text: L("这是本地粗略总结，可以手动修改。"),
                    icon: "exclamationmark.triangle"
                )
                .padding(.bottom, 8)
            }

            FieldCaption(L("说话风格"), hint: L("一句话概括，例如「简短直接，偏冷淡务实」。"))
            TextField(L("比如：简短直接，偏冷淡务实"), text: $speechTone)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 8)

            TagEditor(
                title: L("常用语"),
                hint: L("最多 12 条；提示词里只引用前 6 条，一次回复最多自然带 1 处。"),
                suggestions: [],
                limit: SpeechStyle.maxPhrases,
                values: $speechPhrases,
                accent: accent
            )
            MFRowDivider(leadingInset: 0)
            TagEditor(
                title: L("语言习惯"),
                hint: L("句子长短、标点、语气词、表情这些。"),
                suggestions: [],
                limit: SpeechStyle.maxHabits,
                values: $speechHabits,
                accent: accent
            )
            MFRowDivider(leadingInset: 0)
            TagEditor(
                title: L("不要出现"),
                hint: L("他绝不会说的话的样子。"),
                suggestions: [],
                limit: SpeechStyle.maxAvoid,
                values: $speechAvoid,
                accent: accent
            )

            MFNote(text: L("聊天记录只在这一次总结里用：不落库、不进日志、不进备份。"))
            if !speechIsCleared {
                Button(L("清空话风"), role: .destructive) { clearSpeech() }
                    .font(.footnote)
                    .padding(.top, 8)
            }
        }
    }

    // MARK: - 提示词翻译（`spec/persona-translation/README.md`）

    /// 一张只读的卡片：现在有没有译文、以及「重新翻译」。
    ///
    /// 自动翻译在进聊天前发生（`ChatService` 里 kick 一次，不阻塞首屏）；
    /// 这里的手动按钮是「我刚改了设定、想立刻看到译文」时的入口（§4）。
    private var translationCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(L("提示词翻译"))
                    .font(.system(.body, design: .serif))
                Spacer()
                if translationBusy {
                    ProgressView().controlSize(.mini)
                } else {
                    Button(L("重新翻译")) { Task { await retranslate() } }
                        .font(.footnote)
                        .tint(accent)
                        .disabled(personaId == nil)
                }
            }
            MFNote(text: L("界面是英文时，进聊天前会把人设、世界与话风翻成英文；只发这些设定，不发聊天记录与记忆。"))
            Text(translationSummary.isEmpty ? L("现在用的是原文") : translationSummary)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 6)
        }
    }

    private func retranslate() async {
        guard let personaId else { return }
        guard let resolved = guidanceConfig() else {
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先用原文；等配好模型再点一次「重新翻译」。")
            )
            return
        }
        translationBusy = true
        let service = PersonaTranslationService.makeDefault(stores: env.stores, settings: env.settings)
        try? service.clearTranslation(personaId: personaId)
        let lang = PersonaTranslationLanguage.current()
        let outcome = await service.ensureTranslation(
            personaId: personaId, lang: lang,
            config: resolved.config, model: resolved.model
        )
        translationBusy = false
        switch outcome {
        case .translated:
            translationSummary = Lf("已翻译成 %@，下次聊天就用它。", lang)
        case .cached:
            translationSummary = Lf("已有 %@ 的翻译缓存。", lang)
        case .skippedSameLanguage:
            translationSummary = L("界面语言与设定语言相同，不需要翻译。")
        case .disabled:
            translationSummary = L("设置里关掉了「自动翻译人设」。")
        case .noConfig:
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先用原文；等配好模型再点一次「重新翻译」。")
            )
        case .failed:
            translationSummary = L("翻译失败，可重试；现在仍然用原文。")
        }
        loadTranslationSummary()
    }

    private func loadTranslationSummary() {
        guard let personaId,
              let persona = (try? env.stores.personas.byId(personaId)) ?? nil else {
            translationSummary = ""
            return
        }
        let source = PersonaTranslatableSource(persona: persona)
        let lang = PersonaTranslationLanguage.current()
        if let cached = PersonaTranslation.decode(persona.translationJson),
           cached.isValid(for: source, lang: lang) {
            translationSummary = Lf("已有 %@ 的翻译缓存。", lang)
        } else {
            translationSummary = ""
        }
    }

    private var characterCard: some View {
        MFCard {
            TagEditor(
                title: L("性格"),
                hint: Lf("他是怎样的人。点一个加上，也可以自己写。最多 %d 个。", PersonaFieldLimits.maxTraits),
                suggestions: Self.traitSuggestions,
                limit: PersonaFieldLimits.maxTraits,
                values: $traits,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            TagEditor(
                title: L("喜欢的事"),
                hint: Lf("他乐意做的事、爱聊的话题。最多 %d 个。", PersonaFieldLimits.maxLikes),
                suggestions: Self.likeSuggestions,
                limit: PersonaFieldLimits.maxLikes,
                values: $likes,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            TagEditor(
                title: L("行为方式"),
                hint: Lf("他习惯怎么相处，比如「先听完再给建议」。最多 %d 个。", PersonaFieldLimits.maxBehaviors),
                suggestions: Self.behaviorSuggestions,
                limit: PersonaFieldLimits.maxBehaviors,
                values: $behaviors,
                accent: accent
            )
        }
    }

    // MARK: - 他最近的变化（0.13 人格进化）

    /// 「他最近的变化」：模型在对话里对自己提出的、**等他点头**的小改动。
    ///
    /// 三个约束直接写在界面上：
    /// - 只有四个字段（性格 / 喜欢 / 习惯 / 个人设定），名字、简介、手写提示词、世界设定
    ///   永远不会被它改（白名单在 `PersonaEvolution.sanitize`，这一层只是展示）；
    /// - 接受才写回人格并 `version+1`；拒绝只丢掉这条提议；
    /// - 7 天没人管会自动过期（读取时清理），不会堆成永远没人点的死按钮。
    ///
    /// **两种模式都显示**（0.13 补齐云端）：本地模式的接受写本机人格；服务端模式下
    /// 接受要先 `POST /v1/personas`（`CloudPersonaEvolution`），服务端成功之后才回填
    /// 本地缓存并 `version+1`，失败时这一条留在原地（可以再点一次）。
    @ViewBuilder
    private var recentChangesCard: some View {
        if !pendingChanges.isEmpty {
            MFCard {
                HStack(alignment: .firstTextBaseline) {
                    Text(L("他最近的变化"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                    if pendingBusy {
                        ProgressView().controlSize(.mini)
                    } else {
                        Text(Lf("%d 条待确认", pendingChanges.count))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                MFNote(text: env.serverModeActive
                    ? L("这些是他自己在对话里提出的小变化。接受后会先同步到他的云端档案；拒绝就丢掉；7 天不管会自动过期。")
                    : L("这些是他自己在对话里提出的小变化。接受才会写进他的人格，拒绝就丢掉；7 天不管会自动过期。"))
                ForEach(pendingChanges, id: \.id) { patch in
                    MFRowDivider(leadingInset: 0)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(patch.summary)
                            .font(.callout)
                        if !patch.reason.isEmpty {
                            Text(patch.reason)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        HStack(spacing: 12) {
                            Button(L("接受")) { decide(patch, accept: true) }
                                .font(.footnote)
                                .tint(accent)
                                .disabled(pendingBusy)
                            Button(L("不用了")) { decide(patch, accept: false) }
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .disabled(pendingBusy)
                            Spacer()
                        }
                        .padding(.top, 2)
                    }
                    .padding(.vertical, 6)
                }
            }
        }
    }

    /// 接受 / 拒绝一条待确认的变化。
    ///
    /// 两种模式走两条写路径，**界面上的结果是一样的**：
    /// - 本地模式：直接写本机人格（`PersonaEvolutionService.approve`）；
    /// - 服务端模式：先 `POST /v1/personas`（`CloudPersonaEvolution`），服务端成功之后
    ///   才回填本地缓存并 `version+1`；失败时这一条**留在原地**，用户可以再点一次。
    ///
    /// 接受之后要**同步本地表单状态**：这个页面的 `traits/likes/behaviors/quirks` 是打开时
    /// 读的一份快照，不同步的话，用户接着点「保存」会拿旧值把刚接受的变化盖回去。
    private func decide(_ patch: PersonaPendingPatchEntity, accept: Bool) {
        guard !pendingBusy else { return }
        pendingBusy = true
        guard env.serverModeActive else {
            defer { pendingBusy = false }
            decideLocally(patch, accept: accept)
            return
        }
        guard let cloud = env.cloudPersonaEvolution() else {
            pendingBusy = false
            banner = MFAlert(
                title: accept ? L("这次没接受成") : L("这次没拒绝成"),
                message: L("云端连接还没准备好，稍后再试。")
            )
            return
        }
        // 云端要等一次网络往返：期间按钮保持禁用（`pendingBusy`），避免连点两条。
        Task { @MainActor in
            defer { pendingBusy = false }
            do {
                if accept {
                    let result = try await cloud.approve(patchId: patch.id)
                    applyAccepted(result.persona, applied: result.applied)
                } else {
                    try await cloud.reject(patchId: patch.id)
                }
            } catch {
                banner = MFAlert(
                    title: accept ? L("这次没接受成") : L("这次没拒绝成"),
                    message: error.localizedDescription
                )
            }
            reloadPendingChanges()
        }
    }

    /// 本地模式：同步写库（与 0.13 之前的实现逐字一致）。
    private func decideLocally(_ patch: PersonaPendingPatchEntity, accept: Bool) {
        do {
            if accept {
                let result = try env.personaEvolution.approve(patchId: patch.id)
                applyAccepted(result.persona, applied: result.applied)
            } else {
                try env.personaEvolution.reject(patchId: patch.id)
            }
        } catch {
            banner = MFAlert(
                title: accept ? L("这次没接受成") : L("这次没拒绝成"),
                message: error.localizedDescription
            )
        }
        reloadPendingChanges()
    }

    /// 接受成功后的统一收尾：同步表单快照 + 告诉用户「第几版了」。
    private func applyAccepted(_ persona: PersonaEntity, applied: Int) {
        guard applied > 0 else {
            banner = MFAlert(
                title: L("这条已经不成立了"),
                message: L("他的人格里本来就是这样，已经把这条提议丢掉了。")
            )
            return
        }
        traits = persona.traits
        likes = persona.likes
        behaviors = persona.behaviors
        quirks = persona.quirks ?? ""
        banner = MFAlert(
            title: L("已经写进他的人格"),
            message: Lf("现在是他第 %d 版。", persona.version)
        )
    }

    private func reloadPendingChanges() {
        if let personaId {
            pendingChanges = env.personaEvolution.pending(personaId: personaId)
        }
    }

    /// 云端模式：先把服务端那份待审列表拉下来覆盖本地缓存，再重读（服务端是权威）。
    ///
    /// 为什么要这一步：待审补丁可能在**另一台设备**上被接受/拒绝，也可能已经 7 天过期。
    /// 不拉一次的话，这个页面会给人看一堆点不动的旧按钮。
    private func refreshPendingFromServerIfNeeded() {
        guard env.serverModeActive, let personaId, let cloud = env.cloudPersonaEvolution() else { return }
        Task { @MainActor in
            _ = await cloud.refreshPending(personaId: personaId)
            reloadPendingChanges()
        }
    }

    // MARK: - 成长经历 / 个人设定

    private var storyCard: some View {
        MFCard {
            FieldCaption(L("成长经历"), hint: Lf("他过去发生过什么。≤ %d 字。", PersonaFieldLimits.backstoryMax))
            TextEditor(text: $backstory)
                .font(.system(.body, design: .serif))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 110)
                .padding(8)
                .background(fieldBackground)
                .overlay(alignment: .bottomTrailing) {
                    Text("\(backstory.count)/\(PersonaFieldLimits.backstoryMax)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(6)
                }
                .padding(.bottom, 14)

            FieldCaption(L("个人设定"), hint: Lf("称呼、口头禅、忌讳这些细节。≤ %d 字。", PersonaFieldLimits.quirksMax))
            TextEditor(text: $quirks)
                .font(.system(.body, design: .serif))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 90)
                .padding(8)
                .background(fieldBackground)
        }
    }

    // MARK: - 人设卡（结构化）

    /// 结构化人设卡：身份 / 说话方式 / 性格 / 在意的事 / 边界 / 生活 / 你们之间。
    ///
    /// 为什么和上面的「性格 / 喜欢 / 行为方式」并存：上面那三组是 1.9 的**五段人设**（会拼成
    /// `## 你是谁` 的小节，永远追加在提示词后面）；人设卡更细，而且会**替掉提示词全文**——
    /// 系统提示里只留一条短策略，正文按预算进对话。两边都填时以卡为准，卡空着就继续用手写提示词。
    private var personaCardCard: some View {
        MFCard {
            Button {
                withAnimation(.easeInOut(duration: 0.16)) { cardExpanded.toggle() }
            } label: {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("人设卡"))
                            .font(.system(.body, design: .serif))
                        Text(card.isEmpty
                             ? L("可选：空着就继续用提示词全文")
                             : L("身份 · 说话方式 · 性格 · 在意的事 · 边界 · 生活 · 你们之间"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Image(systemName: cardExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if cardExpanded {
                MFRowDivider(leadingInset: 0)
                cardEditor
            }
        }
    }

    private var cardEditor: some View {
        VStack(alignment: .leading, spacing: 14) {
            MFNote(text: L("填了卡，他的人格就按这些字段来：系统提示里只放一条短策略，正文按预算进对话。保存时提示词全文会被卡的镜像替换；卡空着就继续用你手写的那段。"))
                .padding(.top, 10)

            cardGroupLabel(L("身份"))
            cardIdentityFields

            cardGroupLabel(L("说话方式"))
            cardVoiceFields

            cardGroupLabel(L("性格标签"))
            cardTraitsEditor

            cardGroupLabel(L("在意的事"))
            TagEditor(
                title: L("他在意的事"),
                hint: Lf("他看重什么、会为什么认真。最多 %d 条。", PersonaCardLimits.maxListItems),
                suggestions: [],
                limit: PersonaCardLimits.maxListItems,
                values: $card.values,
                accent: accent
            )

            cardGroupLabel(L("边界"))
            TagEditor(
                title: L("他不会做的事"),
                hint: Lf("明确的边界，比如「不假装记得没说过的事」。最多 %d 条。", PersonaCardLimits.maxListItems),
                suggestions: [],
                limit: PersonaCardLimits.maxListItems,
                values: $card.boundaries,
                accent: accent
            )

            cardGroupLabel(L("生活"))
            cardLifeFields

            cardGroupLabel(L("你们之间"))
            cardField(
                L("他对你的认知与变化"),
                hint: Lf("他怎么理解你、这段关系里发生过什么。≤ %d 字。", PersonaCardLimits.bondMax),
                text: cardText(\.bond, limit: PersonaCardLimits.bondMax),
                minHeight: 110
            )
        }
        .padding(.top, 12)
    }

    // 小段拆开写：一个巨大的 `VStack` 会让 SwiftUI 的类型检查慢到离谱。
    private var cardIdentityFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            cardField(
                L("名字"),
                hint: L("人设卡里他自己的名字。留空时用「身份 / 职业」当称呼。"),
                placeholder: L("比如：林岸"),
                text: cardText(\.identity.name, limit: PersonaCardLimits.nameMax)
            )
            cardField(
                L("年龄感"),
                placeholder: L("比如：二十多岁"),
                text: cardText(\.identity.age, limit: PersonaCardLimits.nameMax)
            )
            cardField(
                L("身份 / 职业"),
                placeholder: L("比如：开旧书店的人"),
                text: cardText(\.identity.role, limit: PersonaCardLimits.roleMax)
            )
            cardField(
                L("和你的关系"),
                placeholder: L("比如：认识很多年的朋友"),
                text: cardText(\.identity.relation, limit: PersonaCardLimits.nameMax)
            )
        }
    }

    private var cardVoiceFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            cardField(
                L("语速"),
                placeholder: L("比如：慢，偶尔停下来想想"),
                text: cardText(\.voice.pace, limit: PersonaCardLimits.fieldMax)
            )
            cardField(
                L("句子长短"),
                placeholder: L("比如：短句，一次说一两句"),
                text: cardText(\.voice.sentence, limit: PersonaCardLimits.fieldMax)
            )
            TagEditor(
                title: L("口头禅"),
                hint: Lf("他常挂在嘴边的短句。最多 %d 条。", PersonaCardLimits.maxListItems),
                suggestions: [],
                limit: PersonaCardLimits.maxListItems,
                values: $card.voice.catchphrases,
                accent: accent
            )
            cardField(
                L("怎么称呼你"),
                placeholder: L("比如：喊你名字的后两个字"),
                text: cardText(\.voice.address, limit: PersonaCardLimits.nameMax)
            )
            cardField(
                L("语气小习惯"),
                placeholder: L("比如：少用感叹号，爱用省略号"),
                text: cardText(\.voice.quirks, limit: PersonaCardLimits.fieldMax)
            )
        }
    }

    private var cardLifeFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            cardField(
                L("作息"),
                placeholder: L("比如：凌晨两点睡，中午起"),
                text: cardText(\.life.schedule, limit: PersonaCardLimits.fieldMax)
            )
            cardField(
                L("现在在"),
                placeholder: L("比如：把店里的旧书重新分类"),
                text: cardText(\.life.doing, limit: PersonaCardLimits.fieldMax)
            )
            cardField(
                L("最近在意"),
                placeholder: L("比如：你最近睡得好不好"),
                text: cardText(\.life.caresAbout, limit: PersonaCardLimits.fieldMax)
            )
        }
    }

    private var cardTraitsEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(L("他有哪些性格，以及有多强（0–1）"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 4)
                Text("\(card.traits.count)/\(PersonaCardLimits.maxTraits)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if card.traits.isEmpty {
                Text(L("还没有标签。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ForEach(card.traits.indices, id: \.self) { index in
                cardTraitRow(index)
            }
            if card.traits.count < PersonaCardLimits.maxTraits {
                Button {
                    card.traits.append(PersonaTrait())
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                            .font(.system(size: 10, weight: .bold))
                        Text(L("添加一条"))
                    }
                    .font(.footnote)
                    .foregroundStyle(accent)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func cardTraitRow(_ index: Int) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                TextField(L("比如：嘴硬心软"), text: cardTraitNameBinding(index))
                    .textFieldStyle(.plain)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)
                Button {
                    if card.traits.indices.contains(index) { card.traits.remove(at: index) }
                } label: {
                    Image(systemName: "minus.circle")
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(L("删掉这个性格")))
            }
            HStack(spacing: 8) {
                Text(L("强度"))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Slider(value: cardTraitStrengthBinding(index), in: 0...1)
                    .tint(accent)
                Text(cardTraitStrengthText(index))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(width: 26, alignment: .trailing)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(Color.secondary.opacity(0.06))
        )
    }

    private func cardGroupLabel(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.top, 6)
    }

    /// 人设卡里某个文本字段的绑定：编辑时顺手把长度夹到上限（上限与 `PersonaCardLimits` 一致）。
    private func cardText(_ keyPath: WritableKeyPath<PersonaCard, String>, limit: Int) -> Binding<String> {
        Binding(
            get: { card[keyPath: keyPath] },
            set: { card[keyPath: keyPath] = String($0.prefix(limit)) }
        )
    }

    @ViewBuilder
    private func cardField(
        _ title: String,
        hint: String = "",
        placeholder: String = "",
        text: Binding<String>,
        limit: Int = PersonaCardLimits.fieldMax,
        minHeight: CGFloat = 0
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                    .font(.footnote.weight(.medium))
                Spacer(minLength: 4)
                Text("\(text.wrappedValue.count)/\(limit)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if !hint.isEmpty {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if minHeight > 0 {
                TextEditor(text: text)
                    .font(.system(.body, design: .serif))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: minHeight)
                    .padding(8)
                    .background(fieldBackground)
            } else {
                TextField(placeholder, text: text)
                    .textFieldStyle(.plain)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)
            }
        }
    }

    private func cardTraitNameBinding(_ index: Int) -> Binding<String> {
        Binding(
            get: { card.traits.indices.contains(index) ? card.traits[index].name : "" },
            set: { value in
                guard card.traits.indices.contains(index) else { return }
                card.traits[index].name = String(value.prefix(PersonaCardLimits.nameMax))
            }
        )
    }

    private func cardTraitStrengthBinding(_ index: Int) -> Binding<Double> {
        Binding(
            get: { card.traits.indices.contains(index) ? Double(card.traits[index].strength) : 0.5 },
            set: { value in
                guard card.traits.indices.contains(index) else { return }
                card.traits[index].strength = min(max(Float(value), 0), 1)
            }
        )
    }

    private func cardTraitStrengthText(_ index: Int) -> String {
        let strength = card.traits.indices.contains(index) ? card.traits[index].strength : 0.5
        return String(format: "%.1f", strength)
    }

    // MARK: - 行为参数

    private var behaviorCard: some View {
        MFCard {
            LevelSliderRow(
                title: L("主动程度"),
                hint: ProactiveLevel.description(proactiveLevel),
                value: Binding(
                    get: { Double(proactiveLevel) },
                    set: { proactiveLevel = Int($0.rounded()) }
                ),
                range: 1...5,
                step: 1,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            LevelSliderRow(
                title: L("记性"),
                hint: Self.memoryRetentionText(memoryRetention),
                value: Binding(
                    get: { Double(memoryRetention) },
                    set: { memoryRetention = Int($0.rounded()) }
                ),
                range: 1...5,
                step: 1,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            LevelSliderRow(
                title: L("多久记一次"),
                hint: Lcount("每聊 %d 条消息，他会在后台回头看一眼，把值得记的事记下来。", extractionEvery),
                value: Binding(
                    get: { Double(extractionEvery) },
                    set: { extractionEvery = Int($0.rounded()) }
                ),
                range: 3...12,
                step: 1,
                accent: accent
            )
        }
    }

    // MARK: - 睡眠

    private var sleepCard: some View {
        MFCard {
            // 当前活动只读：`set_activity` 是他自己的动作，界面只显示「他正在做什么」。
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(L("他正在做什么"))
                    .font(.system(.body, design: .serif))
                Spacer()
                Text(activityLine.isEmpty ? L("没在忙什么") : activityLine)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            MFNote(text: L("这一栏由他自己更新（他会在对话里说自己接下来要忙什么），你不用填。"))
            MFRowDivider(leadingInset: 0)
            Toggle(isOn: $sleepEnabled) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("他会自己睡觉"))
                        .font(.system(.body, design: .serif))
                    Text(sleepEnabled
                         ? L("到点就去睡。睡着时你发消息他多半不回，除非连着叫醒他几次。")
                         : L("他不睡，任何时间都会回你。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(accent)

            if sleepEnabled {
                MFRowDivider(leadingInset: 0)

                HStack {
                    Text(L("几点睡"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                    DatePicker("", selection: $sleepTime, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                }
                .padding(.vertical, 8)

                MFRowDivider(leadingInset: 0)

                HStack {
                    Text(L("几点醒"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                    DatePicker("", selection: $wakeTime, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                }
                .padding(.vertical, 8)

                MFRowDivider(leadingInset: 0)

                LevelSliderRow(
                    title: L("叫醒他要几条消息"),
                    hint: Lcount("睡着的时候，你连着发满 %d 条，他会被叫醒（并有点不好意思）。1–20。", wakeThreshold),
                    value: Binding(
                        get: { Double(wakeThreshold) },
                        set: { wakeThreshold = Int($0.rounded()) }
                    ),
                    range: 1...20,
                    step: 1,
                    accent: accent
                )

                // 免打扰是**他自己**的决定（§6.1）：模型可以在对话/独处时打开或关掉，
                // 用户不能直接改。所以这里只读地显示一句现状，不给开关。
                HStack(spacing: 8) {
                    Image(systemName: dndOnSleep ? "moon.zzz.fill" : "moon.zzz")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Text(dndOnSleep
                         ? L("睡前免打扰：开着（他自己选的）。睡着时你发什么都叫不醒他，消息留到醒了再看。")
                         : L("睡前免打扰：关着（他自己选的）。睡着时连着发几条能把他叫醒。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 10)

                MFNote(text: Lf("现在：%@ 睡 → %@ 醒", clock(sleepMinutes), clock(wakeMinutes)))
            }
        }
    }

    // MARK: - 高级（提示词全文 + 模型）

    /// 0.15：模型优化开关（提示词里要不要注入「# 工具使用」）。
    ///
    /// 放在「睡眠」之后、「高级」之前：它属于「他怎么用模型」，不属于人设正文，
    /// 但也不像提示词全文那样只有少数人会改。
    private var modelProfileCard: some View {
        MFCard {
            ModelProfilePicker(selection: $modelProfile, accent: accent)
            MFNote(text: L("换模型之后如果觉得他不太会用工具，可以先切到 DeepSeek；用 GLM 时选精简更稳。"))
        }
    }

    private var advancedCard: some View {
        MFCard {
            Button {
                withAnimation(.easeInOut(duration: 0.16)) { promptExpanded.toggle() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("提示词全文（高级）"))
                            .font(.system(.body, design: .serif))
                        Text(L("可以留空：会按上面的名字和一句话介绍生成一段；上面也没填就用默认人设。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: promptExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if promptExpanded {
                MFRowDivider(leadingInset: 0)
                HStack(alignment: .firstTextBaseline) {
                    Text(L("他是谁、怎么说话、什么不做。原文会完整保存。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(Lcount("%d 字", prompt.count))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 8)
                .padding(.bottom, 8)

                TextEditor(text: $prompt)
                    .font(.system(.body, design: .serif))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 200)
                    .padding(8)
                    .background(fieldBackground)

                MFRowDivider(leadingInset: 0)

                FieldCaption(L("模型覆盖（可留空）"), hint: L("留空就用 API 配置里的模型；填了只对这个人生效。"))
                TextField(modelHint, text: $modelOverride)
                    .textFieldStyle(.plain)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)

                MFNote(text: L("改完之后版本号会 +1，方便你在备份里分辨是哪一版的他。"))
            }
        }
    }

    private var modelHint: String {
        if let model = env.activeApiConfig?.model, !model.isEmpty {
            return Lf("留空使用 %@", model)
        }
        return L("留空使用 API 配置中的模型")
    }

    // MARK: - 人设引导：编辑中的世界 / 话风 / 活动

    /// 多行输入框（世界描述、日程、聊天记录都用它）。
    private func multilineField(_ text: Binding<String>, height: CGFloat, placeholder: String) -> some View {
        ZStack(alignment: .topLeading) {
            if text.wrappedValue.isEmpty {
                Text(placeholder)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 14)
            }
            TextEditor(text: text)
                .font(.footnote)
                .scrollContentBackground(.hidden)
                .frame(minHeight: height)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
        }
        .background(fieldBackground)
    }

    /// 编辑中的世界预设（可视化编辑器的状态 → 结构；空的整块就是空预设）。
    private var editingWorld: WorldPreset { worldEditor.preset }

    private var editingSpeechStyle: SpeechStyle {
        SpeechStyle(
            tone: speechTone,
            phrases: speechPhrases,
            habits: speechHabits,
            avoid: speechAvoid,
            source: speechSource
        )
    }

    /// 世界输入全空时不显示「清空」按钮，也不发补全请求。
    private var worldIsCleared: Bool {
        editingWorld.isEmpty
    }

    /// AI 补全要一句「角色简介」：优先用一句话介绍，其次用个人设定；
    /// 两者都空就没有可补全的输入（按钮置灰）。
    private var worldIsEmptyInput: Bool {
        let summaryText = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let quirksText = quirks.trimmingCharacters(in: .whitespacesAndNewlines)
        return summaryText.isEmpty && quirksText.isEmpty
    }

    private var speechIsCleared: Bool {
        editingSpeechStyle.isEmpty
    }

    /// 把世界结构写进编辑器状态（载入与 AI 补全共用一条路径，避免两边长得不一样）。
    private func apply(world: WorldPreset?) {
        worldEditor = WorldEditorState(preset: world)
    }

    private func apply(style: SpeechStyle?) {
        speechTone = style?.tone ?? ""
        speechPhrases = style?.phrases ?? []
        speechHabits = style?.habits ?? []
        speechAvoid = style?.avoid ?? []
        speechSource = style?.source ?? .model
    }

    private func clearWorld() {
        apply(world: nil)
        initialWorldJson = nil
    }

    private func clearSpeech() {
        apply(style: nil)
        transcript = ""
        initialSpeechStyleJson = nil
    }

    /// 这次请求用哪份配置：与聊天同一个口径（`AppEnvironment.resolveConfig`）。
    private func guidanceConfig() -> (config: CompletionConfig, model: String)? {
        guard let resolved = env.resolvedChatConfig() else { return nil }
        return (CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey), resolved.model)
    }

    private func expandWorld() async {
        guard let resolved = guidanceConfig() else {
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先在设置里配一个模型，再来补全世界；下面的字段随时可以手填。")
            )
            return
        }
        let summaryText = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let quirksText = quirks.trimmingCharacters(in: .whitespacesAndNewlines)
        let brief = [summaryText, quirksText].filter { !$0.isEmpty }.joined(separator: "\n")
        worldBusy = true
        let expander = WorldPresetExpander.makeDefault()
        let result = await expander.expand(
            personaSummary: brief,
            config: resolved.config,
            model: resolved.model
        )
        worldBusy = false
        // 补全只填空白项：用户已经写下的名字/描述/日程不会被覆盖（§2.3）。
        worldEditor = worldEditor.mergingBlanks(from: result.preset)
        if !result.usedModel {
            banner = MFAlert(
                title: L("这次没补成"),
                message: L("已经先填了一份本地草稿（只有你自己的描述），可以直接改，或者稍后再试。")
            )
        }
    }

    private func summarizeStyle() async {
        guard let resolved = guidanceConfig() else {
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先在设置里配一个模型，再来总结话风；也可以手填风格与常用语。")
            )
            return
        }
        styleBusy = true
        let summarizer = SpeechStyleSummarizer.makeDefault()
        let result = await summarizer.summarize(
            transcript: transcript,
            config: resolved.config,
            model: resolved.model
        )
        styleBusy = false
        apply(style: result.style)
        // 聊天记录用完即弃（§3.3）：总结完就把它从界面状态里清掉，没有第二处引用。
        transcript = ""
        if !result.usedModel {
            banner = MFAlert(
                title: L("这次是本地粗略总结"),
                message: L("没有用上模型，按字面规则大致总结了一版；常用语只有重复出现的句子，可以手动补。")
            )
        }
    }

    // MARK: - 从最近聊天学习话风（0.15.7）

    /// 取样 + 总结（**不写库**）：拿到结果就弹预览，用户点「应用」才生效。
    ///
    /// 为什么要有这一步而不是直接写：话风是「他是谁」的一部分，模型总结出来的东西
    /// 偶尔会把对方的口头禅算到他头上（或者反过来）。先看一眼再决定，比事后回滚便宜。
    private func distillStyle() async {
        guard let personaId else { return }
        guard let resolved = guidanceConfig() else {
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先在设置里配一个模型，再来学话风；也可以手填风格与常用语。")
            )
            return
        }
        distillBusy = true
        defer { distillBusy = false }
        let service = StyleDistillationService.makeDefault(stores: env.stores)
        let result = await service.distill(
            personaId: personaId,
            config: resolved.config,
            model: resolved.model
        )
        switch result {
        case .noMessages:
            banner = MFAlert(
                title: L("还没什么可学的"),
                message: L("你们最近还没聊几句。等聊过一段再来，他会更像他自己。")
            )
        case .failed:
            banner = MFAlert(
                title: L("这次没学成"),
                message: L("读聊天记录的时候出了点问题，稍后再试一次。")
            )
        case .ready(let summary):
            distillPreview = summary
            distillPreviewShown = true
        }
    }

    /// 用户点了「应用」：写 `speechStyleJson` + 顶一版，然后把表单同步成新的那份。
    ///
    /// 同步这一步不能少：`initialSpeechStyleJson` 也要一起更新，否则用户接着点「保存」时
    /// 会拿旧快照再写一次（版本号白跳两格）。
    private func applyDistilledStyle(_ summary: SpeechStyleSummary) {
        guard let personaId else { return }
        let service = StyleDistillationService.makeDefault(stores: env.stores)
        do {
            let result = try service.apply(personaId: personaId, style: summary.style)
            apply(style: summary.style)
            initialSpeechStyleJson = result.json
            distillPreviewShown = false
            banner = MFAlert(
                title: result.applied ? L("学会了他现在的说法") : L("跟现在一样"),
                message: result.applied
                    ? Lf("已经存好了，现在是他第 %d 版。", result.version)
                    : L("总结出来的话风跟他现在的一样，就没有再改。")
            )
        } catch {
            banner = MFAlert(title: L("没应用成"), message: error.localizedDescription)
        }
    }

    /// 预览里的一行：小标题 + 正文（不用 `FieldCaption`：它的 `hint` 为空时会多留一行空白）。
    private func previewRow(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// 预览面板：只显示**总结结果**，不显示聊天原文（隐私：§3.3）。
    private var distillPreviewSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(L("他会这样说话"))
                        .font(.system(.title3, design: .serif))
                    if let summary = distillPreview {
                        if !summary.usedModel {
                            MFNote(
                                text: L("这次没连上模型，是按字面规则粗略总结的；可以应用之后再手改。"),
                                icon: "exclamationmark.triangle"
                            )
                        }
                        if !summary.style.tone.isEmpty {
                            previewRow(L("说话风格"), summary.style.tone)
                        }
                        if !summary.style.phrases.isEmpty {
                            previewRow(L("常用语"),
                                       summary.style.phrases.map { "「\($0)」" }.joined(separator: "、"))
                        }
                        if !summary.style.habits.isEmpty {
                            previewRow(L("语言习惯"), summary.style.habits.joined(separator: "；"))
                        }
                        if !summary.style.avoid.isEmpty {
                            previewRow(L("不要出现"), summary.style.avoid.joined(separator: "、"))
                        }
                        MFNote(text: L("只用了你们最近的聊天，原文不存、也不进日志。"))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            .background(MFTheme.canvas(scheme).ignoresSafeArea())
            .navigationTitle(L("从最近聊天学到的"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("取消")) { distillPreviewShown = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("应用")) {
                        if let summary = distillPreview { applyDistilledStyle(summary) }
                    }
                    .fontWeight(.medium)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - 载入与保存

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        guard let personaId, let persona = (try? env.stores.personas.byId(personaId)) ?? nil else {
            return
        }
        name = persona.name
        summary = persona.description
        // 0.15：提示词语言是人格属性，读回来时按标签解析（空 / 认不出 = 中文）。
        promptLanguage = PersonaLanguagePrompt.language(for: persona)
        modelProfile = persona.modelProfile
        avatarUri = persona.avatarUri
        originalAvatarUri = persona.avatarUri
        avatarSeed = persona.avatarSeed
        prompt = persona.prompt
        modelOverride = persona.modelOverride ?? ""
        traits = persona.traits
        likes = persona.likes
        behaviors = persona.behaviors
        backstory = persona.backstory ?? ""
        quirks = persona.quirks ?? ""
        proactiveLevel = persona.proactiveLevel
        memoryRetention = persona.memoryRetention
        extractionEvery = persona.extractionEvery
        sleepEnabled = persona.sleepEnabled
        dndOnSleep = persona.dndOnSleep
        wakeThreshold = persona.wakeThreshold
        sleepTime = Self.time(fromMinutes: SleepSchedule.sleepMinutes(persona))
        wakeTime = Self.time(fromMinutes: SleepSchedule.wakeMinutes(persona))
        // 人设引导：世界 / 话风 / 当前活动。坏 JSON 等于没有（宽容解析），界面不报错。
        initialWorldJson = persona.worldJson
        apply(world: WorldPreset.decode(persona.worldJson))
        initialSpeechStyleJson = persona.speechStyleJson
        apply(style: SpeechStyle.decode(persona.speechStyleJson))
        loadTranslationSummary()
        let now = DomainClock.nowMillis
        if let activity = PersonaActivity.decode(persona.activityJson, now: now) {
            activityLine = Lf("%@（到 %@ 结束）", activity.activity, activity.untilClockText())
        } else {
            activityLine = ""
        }
        // 人设卡：有卡读卡，没卡从旧提示词里临时拆一份（只读，不写库，与 Android 一致）。
        let loadedCard = env.personaCards.readCard(persona)
        card = loadedCard
        initialCard = loadedCard
        cardExpanded = !loadedCard.isEmpty
        // 提示词只有真写过才展开：默认收起，别让一屏全是字号很小的正文。
        promptExpanded = !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && isNew == false
        // 人格进化：读取待确认的变化（7 天过期的顺手清掉）。
        pendingChanges = env.personaEvolution.pending(personaId: persona.id)
        // 云端模式：服务端是待审补丁的权威，打开页面时拉一次覆盖本地缓存。
        refreshPendingFromServerIfNeeded()
    }

    /// 什么都可以空着（0.13 真机反馈第一批）：名字空 → 保存成「未命名」，
    /// 提示词空 → 由名字 + 一句话介绍生成，两样都空就用系统默认人设文本。
    ///
    /// 以前这里要求名字非空才让点保存，用户只是想先建一个人、回头再写，却被表单挡住。
    private var canSave: Bool { true }

    private func save() {
        Task { @MainActor in await saveAsync() }
    }

    @MainActor
    private func saveAsync() async {
        let now = DomainClock.nowMillis
        let existing: PersonaEntity? = personaId.flatMap { try? env.stores.personas.byId($0) } ?? nil
        // 空字段的兜底（`PersonaDraft`，Kit 里的纯函数，编辑页与提示词渲染共用同一套规则）：
        // 名字空 → 本地化的「未命名」；提示词空 → 由名字 + 介绍生成，两样都空用系统默认。
        let resolvedName = PersonaDraft.resolvedName(trimmedName, fallback: L("未命名"))
        let resolvedDescription = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedPrompt = PersonaDraft.resolvedPrompt(
            prompt, name: trimmedName, description: resolvedDescription
        )
        var entity = existing ?? PersonaEntity(
            id: UUID().uuidString,
            name: resolvedName,
            createdAt: now,
            updatedAt: now
        )
        let promptChanged = entity.prompt != resolvedPrompt
        entity.name = resolvedName
        entity.description = resolvedDescription
        // 0.15：写规范语言码（`zh` / `en`），不写显示名；空人格默认中文也会显式写 `zh`。
        entity.languageTag = promptLanguage.code
        // 0.15（v20）：模型优化档位跟人格一起保存（提示词拼装时按它决定要不要注入工具规则）。
        entity.modelProfile = modelProfile
        entity.avatarUri = avatarUri
        entity.prompt = resolvedPrompt
        entity.modelOverride = modelOverride.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? nil
            : modelOverride.trimmingCharacters(in: .whitespacesAndNewlines)
        // 0.12：存储与哈希保留原文（上限只作为编辑器提示，不再截断）。
        entity.traits = PersonaTags.normalize(traits)
        entity.likes = PersonaTags.normalize(likes)
        entity.behaviors = PersonaTags.normalize(behaviors)
        entity.backstory = PersonaEntity.clean(backstory)
        entity.quirks = PersonaEntity.clean(quirks)
        entity.proactiveLevel = PersonaFieldLimits.clampLevel(proactiveLevel)
        entity.memoryRetention = PersonaFieldLimits.clampLevel(memoryRetention)
        entity.extractionEvery = PersonaFieldLimits.clampExtractionEvery(extractionEvery)
        entity.sleepEnabled = sleepEnabled
        entity.dndOnSleep = dndOnSleep
        entity.wakeThreshold = PersonaFieldLimits.clampWakeThreshold(wakeThreshold)
        if sleepEnabled {
            entity.quietSleepMinutes = sleepMinutes
            entity.quietWakeMinutes = wakeMinutes
        }
        // 人设引导：世界与话风整组落库（空 = 清空），活动不动——那是他自己的状态。
        let worldJson = editingWorld.isEmpty ? nil : editingWorld.jsonString()
        let speechJson = editingSpeechStyle.isEmpty ? nil : editingSpeechStyle.jsonString()
        // 只有真的动过才写：`initialWorldJson` / `initialSpeechStyleJson` 是载入时的原文，
        // 没改过就原样留着（免得每次保存都把两段 JSON 重新序列化一遍、把 updatedAt 白推一次）。
        if PromptGuidanceJSON.clean(initialWorldJson) != worldJson {
            entity.worldJson = worldJson
        }
        if PromptGuidanceJSON.clean(initialSpeechStyleJson) != speechJson {
            entity.speechStyleJson = speechJson
        }
        entity.updatedAt = now
        if existing == nil {
            entity.version = max(entity.version, 1)
            entity.avatarSeed = Self.avatarSeed(for: resolvedName)
        } else if promptChanged {
            // 提示词变了才算新的一版；只改性格或作息不该把版本号顶上去。
            entity.version = max(entity.version, 1) + 1
        }

        do {
            // 服务端模式：先 POST（新建由服务端生成 id），成功后本地缓存才更新；
            // 本地模式这个方法内部就是原来的 upsert，行为不变。
            let saved = try await env.savePersona(entity, isNew: existing == nil)
            // 结构化人设卡：只有「用户真的动过、且卡非空」才写库。
            // 空卡代表「我还是想用上面手写的那段提示词」，不能拿空镜像把它抹掉（Android 同一条规则）。
            if card != initialCard && !card.isEmpty {
                var finalCard = card
                // 卡里没填名字时用上面填的人格名——否则系统提示里的稳定策略会退化成通用兜底。
                if finalCard.identity.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    finalCard.identity.name = resolvedName
                }
                // 走 saveCardFields：它会在落库前按卡片上限收敛（去空项、夹强度、截长度），
                // 并把 prompt 重写成卡的镜像。卡没变化时它什么也不写。
                if case .failure(let error) = env.personaCards.saveCardFields(
                    personaId: saved.id,
                    identity: finalCard.identity,
                    voice: finalCard.voice,
                    traits: finalCard.traits,
                    values: finalCard.values,
                    boundaries: finalCard.boundaries,
                    life: finalCard.life,
                    bond: finalCard.bond,
                    reason: L("编辑人设卡")
                ) {
                    banner = MFAlert(
                        title: L("人设卡没写进去"),
                        message: Lf("人格已经保存了，但结构化人设卡写入失败：%@", error.localizedDescription)
                    )
                    return
                }
            }
            // 换了头像：新头像已经落库，再删旧文件；只删零引用的
            // （旧图可能同时被预设头像 / 我的头像 / 另一条消息共享）。
            if originalAvatarUri != avatarUri {
                MediaStorage.shared.deleteIfUnreferenced(
                    originalAvatarUri,
                    references: MediaReferences(stores: env.stores, settings: env.settings)
                )
                originalAvatarUri = avatarUri
            }
            // 一人一段（§二）：创建人格的同时就把他那一段会话建好，
            // 否则首页点进去还要再补一次，补失败就成了「点了没反应」。
            _ = env.session(forPersona: saved.id)
            if env.currentSettings.activePersonaId == nil {
                env.updateSettings { $0.activePersonaId = saved.id }
            }
            dismiss()
        } catch {
            banner = MFAlert(title: L("没能保存"), message: error.localizedDescription)
        }
    }

    // MARK: - 工具

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }

    private var sleepMinutes: Int { Self.minutesOfDay(sleepTime) }
    private var wakeMinutes: Int { Self.minutesOfDay(wakeTime) }

    private func clock(_ minutes: Int) -> String {
        ServiceTime.clockText(minutes)
    }

    private static func memoryRetentionText(_ level: Int) -> String {
        switch min(max(level, 1), 5) {
        case 1: return L("忘得快：很久以前的事会慢慢淡掉。")
        case 2: return L("记性一般。")
        case 3: return L("正常记性：重要的事记得住。")
        case 4: return L("记性好，很久以前提过的事都还在。")
        default: return L("什么都记得，包括你随口说过的话。")
        }
    }

    private static let traitSuggestions = [
        L("慢热"), L("嘴硬心软"), L("话少"), L("爱开玩笑"), L("安静"), L("容易吃醋"),
        L("理性"), L("爱撒娇"), L("倔"), L("温柔"), L("怕冷"), L("好奇心重")
    ]
    private static let likeSuggestions = [
        L("夜里散步"), L("旧书"), L("下雨天"), L("咖啡"), L("猫"), L("听歌"), L("做饭"), L("看球")
    ]
    private static let behaviorSuggestions = [
        L("先听完再给建议"), L("不主动打断"), L("有话直说"), L("拐弯抹角"), L("先道歉再讲道理"), L("只在深夜说真心话")
    ]

    /// 头像底色的种子：与名字绑定、与进程无关（`hashValue` 每次启动都会变，不能用）。
    private static func avatarSeed(for name: String) -> Int {
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return sum == 0 ? 1 : sum
    }

    private static func minutesOfDay(_ date: Date) -> Int {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    fileprivate static func time(hour: Int, minute: Int) -> Date {
        Calendar.current.date(
            bySettingHour: hour,
            minute: minute,
            second: 0,
            of: Date()
        ) ?? Date()
    }

    fileprivate static func time(fromMinutes minutes: Int) -> Date {
        let wrapped = ((minutes % 1440) + 1440) % 1440
        return time(hour: wrapped / 60, minute: wrapped % 60)
    }
}
