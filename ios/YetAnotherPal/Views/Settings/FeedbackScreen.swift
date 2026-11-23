import SwiftUI

/// 反馈页（0.14.2）：类型 + 内容（≤1000 字）+ 可选联系方式。
///
/// 提交动作分三步，顺序是产品决定（见 `YapKit/Services/FeedbackService.swift` 的文件头）：
/// 1. 先原子写一份 `Application Support/feedback/feedback-<ts>.json`（内容 + App 版本 + 平台 + 时间）；
/// 2. 再拼一个**预填好标题/正文**的 GitHub Issue 链接并交给系统打开；
/// 3. 提示「已保存，可在浏览器提交」。
///
/// 第 2 步失败不影响第 1 步：本地那份已经在了，提示改成「已保存到本机，但没能打开浏览器」。
struct FeedbackScreen: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var scheme

    @State private var kind: FeedbackKind = .bug
    @State private var content = ""
    @State private var contact = ""
    @State private var banner: MFAlert?
    /// 防止连点两次提交（两次会写出两份一样的 JSON）。
    @State private var submitting = false

    private var draft: FeedbackDraft {
        FeedbackDraft(kind: kind, content: content, contact: contact)
    }

    private var validationError: FeedbackValidationError? { draft.validate() }

    private var trimmedCount: Int { draft.trimmedContent.count }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                kindCard
                contentCard
                contactCard
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(FeedbackCopy.screenTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(FeedbackCopy.submit) { submit() }
                    .fontWeight(.medium)
                    .disabled(submitting || validationError != nil)
            }
        }
        .mfAlert($banner)
    }

    // MARK: - 类型

    private var kindCard: some View {
        MFCard {
            Text(FeedbackCopy.kindLabel)
                .font(.system(.body, design: .serif))
            Picker(selection: $kind) {
                ForEach(FeedbackKind.allCases, id: \.self) { item in
                    Text(item.label).tag(item)
                }
            } label: {
                Text(FeedbackCopy.kindLabel)
                    .font(.footnote)
            }
            .pickerStyle(.segmented)
            .padding(.top, 10)
        }
    }

    // MARK: - 内容

    private var contentCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(FeedbackCopy.contentLabel)
                    .font(.system(.body, design: .serif))
                Spacer()
                Text(Lcount("%d 字", trimmedCount))
                    .font(.caption)
                    .foregroundStyle(isOverLimit ? MFUI.danger : Color.secondary)
            }
            MFNote(text: FeedbackCopy.contentHint)

            ZStack(alignment: .topLeading) {
                if content.isEmpty {
                    Text(FeedbackCopy.contentHint)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 14)
                        .allowsHitTesting(false)
                }
                TextEditor(text: $content)
                    .font(.system(.body, design: .serif))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 180)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
            }
            .background(fieldBackground)
            .padding(.top, 10)
        }
    }

    private var isOverLimit: Bool {
        if case .contentTooLong = validationError { return true }
        return false
    }

    // MARK: - 联系方式

    private var contactCard: some View {
        MFCard {
            Text(FeedbackCopy.contactLabel)
                .font(.system(.body, design: .serif))
            MFNote(text: FeedbackCopy.contactHint)
            TextField(FeedbackCopy.contactLabel, text: $contact)
                .textFieldStyle(.plain)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.top, 10)
        }
    }

    // MARK: - 提交

    private func submit() {
        guard !submitting else { return }
        if let error = validationError {
            banner = MFAlert(title: FeedbackCopy.screenTitle, message: error.message)
            return
        }
        guard let record = FeedbackRecord.make(
            draft: draft,
            appVersion: FeedbackComposer.appVersion(),
            platform: FeedbackComposer.platform,
            createdAt: DomainClock.nowMillis
        ) else {
            banner = MFAlert(
                title: FeedbackCopy.screenTitle,
                message: FeedbackValidationError.emptyContent.message
            )
            return
        }

        submitting = true
        // 1. 先落本机（原子写）。这一步失败就什么都不做：没有本地凭据的反馈不算「已保存」。
        do {
            try FeedbackStore().save(record)
        } catch {
            submitting = false
            banner = MFAlert(
                title: FeedbackCopy.saveFailedTitle,
                message: FeedbackCopy.saveFailedHint
            )
            return
        }

        // 2. 再打开预填链接。拼不出合法 https 链接（或系统拒绝打开）时只保留本地那份。
        guard let url = FeedbackComposer.issueURL(for: record) else {
            finish(with: FeedbackCopy.savedNotOpenedHint)
            return
        }
        openURL(url) { accepted in
            Task { @MainActor in
                finish(with: accepted ? FeedbackCopy.savedHint : FeedbackCopy.savedNotOpenedHint)
            }
        }
    }

    private func finish(with message: String) {
        submitting = false
        banner = MFAlert(title: FeedbackCopy.screenTitle, message: message)
        // 已经落盘了，清空输入框避免用户再点一次写出重复文件。
        content = ""
        contact = ""
    }

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }
}
