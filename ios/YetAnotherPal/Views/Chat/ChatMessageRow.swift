import SwiftUI

/// 一条消息的气泡、以及聊天气泡上那些「附属的小东西」。
///
/// 排版规则直接对齐 Android 的 `ChatScreen.kt`：收到的靠左、发出的靠右，
/// 气泡样式跟设置走（柔和 / 紧凑 / 描边），长按弹菜单而不弹系统选字工具条。
/// 颜色全部从这里出，深色模式跟随系统（`MFTheme` 里那几支）。

// MARK: - 日期分隔条

struct ChatDateDivider: View {
    let label: String

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack {
            Spacer()
            Text(label)
                .font(.caption2)
                .foregroundStyle(MFTheme.subtleText(scheme))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(
                    scheme == .dark ? Color.white.opacity(0.08) : Color.white.opacity(0.75),
                    in: Capsule()
                )
            Spacer()
        }
        .padding(.vertical, 10)
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - 气泡

struct ChatBubbleRow: View {

    let message: MessageEntity
    /// 1.9：这条用户消息要不要在右下角显示「已读」（见 `ChatViewModel.showsReadBadge`）。
    let showsRead: Bool
    let bubbleStyle: BubbleStyle
    let accent: Color
    let animationsEnabled: Bool
    /// 自己的头像（设置里的「我的头像」）。还没有就退回首字/人形图标。
    let myAvatarUri: String?
    let onOpenLink: (String) -> Void
    /// 点开第几张图（附件数组 + 下标）。
    let onOpenImage: ([MessageAttachment], Int) -> Void
    /// 长按「添加到我的表情」（0.13 表情包）。
    let onAddSticker: () -> Void
    let onCopy: () -> Void
    let onDelete: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var traceExpanded = false

    private var outgoing: Bool { message.role == .user }
    private var isOutlined: Bool { bubbleStyle == .outlined }
    /// 出向气泡用强调色底 + 白字；描边样式下两边都用正常文字色。
    private var usesAccentFill: Bool { outgoing && !isOutlined }
    private var traces: [ToolTraceItem] { ToolTraceCodec.decode(message.toolTraceJson) }
    private var shape: ChatBubbleShape { ChatBubbleShape.of(bubbleStyle, outgoing: outgoing) }
    private var attachments: [MessageAttachment] { AttachmentsCodec.decode(message.attachmentsJson) }
    /// 图片附件（0.13 起 `attachments` 还可能带语音/视频）。
    private var imageAttachments: [MessageAttachment] { attachments.filter { $0.isImage } }
    /// 语音/视频附件：一条消息最多各一段（`AttachmentsCodec.maxAudioPerMessage`）。
    private var playableAttachments: [MessageAttachment] { attachments.filter { $0.isPlayableMedia } }
    /// 这条消息是不是「纯表情」：只有表情附件、没有正文（0.13）。纯表情不套气泡底，
    /// 直接贴在聊天里（240 px），与微信/QQ 的观感一致。
    private var isStickerOnly: Bool {
        !attachments.isEmpty && attachments.allSatisfy { $0.isSticker } && message.content.isEmpty
    }
    private var hasStickerAttachment: Bool {
        attachments.contains { $0.isSticker }
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            if outgoing { Spacer(minLength: 40) }
            // 0.14 拉黑（`spec/experience/README.md` §2）：气泡**左侧**一个红色感叹号，
            // 气泡下方一句「对方已将你拉黑」。只认消息自己的 `blocked` 列：
            // 解除之后这几条仍然带标记（那是当时发生过的事，模型永远不会看到它们）。
            if BlockPolicy.showsBlockedIndicator(role: message.role, blocked: message.blocked) {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.system(size: 15))
                    .foregroundStyle(Color.red)
                    .padding(.bottom, 2)
                    .accessibilityLabel(Text(L("对方已将你拉黑")))
            }
            VStack(alignment: .trailing, spacing: 3) {
                bubble
                if message.blocked && message.role == .user {
                    Text(L("对方已将你拉黑"))
                        .font(.caption2)
                        .foregroundStyle(.red)
                }
            }
            if outgoing {
                // 自己那一侧的小头像：这是「我」在这个对话里存在过的证据（1.9.1 新增）。
                MFAvatar(myAvatarUri: myAvatarUri, size: 26)
                    .padding(.bottom, 2)
            }
            if !outgoing { Spacer(minLength: 52) }
        }
        .padding(.vertical, bubbleStyle == .compact ? 1 : 2)
    }

    private var bubble: some View {
        bubbleContent
            .padding(.horizontal, isStickerOnly ? 0 : padH)
            .padding(.vertical, isStickerOnly ? 0 : padV)
            .background(isStickerOnly ? Color.clear : fill)
            .clipShape(shape)
            .overlay(shape.strokeBorder(isStickerOnly ? Color.clear : borderColor, lineWidth: borderWidth))
            .contextMenu { menu }
    }

    /// 气泡里「按顺序堆起来的东西」单独抽出来：
    /// 一条链上写太多修饰符时，类型检查器会在整段表达式上放弃（报 failed to produce diagnostic）。
    private var bubbleContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            if isStickerOnly, let sticker = attachments.first {
                StickerBubbleView(attachment: sticker)
                    .padding(.bottom, 2)
            } else if !attachments.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    if !playableAttachments.isEmpty {
                        ForEach(Array(playableAttachments.enumerated()), id: \.offset) { _, attachment in
                            ChatPlayableMediaView(
                                attachment: attachment,
                                captionColor: contentColor.opacity(0.78)
                            )
                        }
                    }
                    if !imageAttachments.isEmpty {
                        ChatAttachmentsView(attachments: imageAttachments) { index in
                            onOpenImage(imageAttachments, index)
                        }
                    }
                }
                .padding(.bottom, message.content.isEmpty ? 0 : 6)
            }
            if !message.content.isEmpty {
                ChatMarkdownView(text: message.content, isOutgoing: usesAccentFill, onOpenLink: onOpenLink)
            }
            if !traces.isEmpty && message.status == .complete {
                tracesSection
                    .padding(.top, 6)
            }
            footer
                .padding(.top, 5)
        }
    }

    // MARK: 样式

    private var padH: CGFloat {
        switch bubbleStyle {
        case .compact: return 12
        case .soft, .outlined: return 14
        }
    }

    private var padV: CGFloat {
        switch bubbleStyle {
        case .compact: return 8
        case .soft, .outlined: return 10
        }
    }

    private var fill: Color {
        if isOutlined {
            return outgoing ? accent.opacity(0.10) : MFTheme.incomingBubble(scheme).opacity(0.78)
        }
        return outgoing ? accent : MFTheme.incomingBubble(scheme)
    }

    private var borderColor: Color {
        if isOutlined {
            return outgoing ? accent.opacity(0.45) : Color.primary.opacity(0.14)
        }
        return outgoing ? .clear : Color.primary.opacity(0.07)
    }

    private var borderWidth: CGFloat {
        isOutlined ? 1 : (outgoing ? 0 : 0.7)
    }

    private var contentColor: Color {
        usesAccentFill ? .white : .primary
    }

    private var footerColor: Color {
        usesAccentFill ? Color.white.opacity(0.62) : MFTheme.subtleText(scheme)
    }

    private var traceTint: Color {
        usesAccentFill ? Color.white.opacity(0.75) : MFTheme.subtleText(scheme)
    }

    // MARK: 动作痕迹

    private var tracesSection: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button {
                withAnimation(animationsEnabled ? .easeInOut(duration: 0.16) : nil) {
                    traceExpanded.toggle()
                }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 10))
                    Text("· " + (ToolTraceCodec.summarize(traces).isEmpty ? L("动作痕迹") : ToolTraceCodec.summarize(traces)))
                        .font(.caption2)
                        .lineLimit(1)
                    Image(systemName: traceExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                }
                .foregroundStyle(traceTint)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(traceExpanded ? L("收起动作痕迹") : L("展开动作痕迹")))

            if traceExpanded {
                ChatToolTraceDetail(items: traces, tint: traceTint)
            }
        }
    }

    // MARK: 时间

    private var footer: some View {
        HStack(spacing: 4) {
            if message.status == .stopped {
                Text(L("已停止 ·"))
            }
            if message.edited {
                Text(L("已编辑 ·"))
            }
            Text(MFDate.time(message.createdAt))
            // 「已读」只在他这一轮没回的时候出现：那是一个**信号**，不是每条消息的装饰。
            if showsRead {
                Text(L("· 已读"))
                    .accessibilityLabel(Text(L("已读")))
            }
        }
        .font(.caption2)
        .foregroundStyle(footerColor)
    }

    // MARK: 长按菜单

    @ViewBuilder
    private var menu: some View {
        if !message.content.isEmpty {
            Button(action: onCopy) {
                Label(L("复制"), systemImage: "doc.on.doc")
            }
        }
        if hasStickerAttachment {
            Button(action: onAddSticker) {
                Label(L("添加到我的表情"), systemImage: "face.smiling")
            }
        }
        Button(role: .destructive, action: onDelete) {
            Label(L("删除这条消息"), systemImage: "trash")
        }
    }
}

// MARK: - 正在流式的那个气泡

/// 生成中的气泡：先用三个点表示「正在输入」，一旦有字就换成正文 + 闪烁光标。
///
/// 与真实消息分开画（而不是先造一条假消息）：它还没有 id、没有时间戳、也可能被中途取消，
/// 混进 `messages` 里会让「最后一条助手消息」这种判断出错。
struct ChatStreamingBubble: View {

    let text: String
    let traces: [ToolTraceItem]
    let bubbleStyle: BubbleStyle
    let animationsEnabled: Bool
    let onOpenLink: (String) -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var tracesExpanded = false

    private var shape: ChatBubbleShape { ChatBubbleShape.of(bubbleStyle, outgoing: false) }

    var body: some View {
        HStack(spacing: 0) {
            if text.isEmpty {
                // 0.14 §1：生成中**只显示输入指示**，不再画一个带「…」的占位气泡。
                // 三个点直接贴在聊天里（没有气泡底、没有时间戳），首字一到就换成下面的真气泡。
                ChatTypingRow(traces: traces, animationsEnabled: animationsEnabled)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    ChatMarkdownView(text: text, isOutgoing: false, onOpenLink: onOpenLink)
                    ChatBlinkingCursor(animationsEnabled: animationsEnabled)
                        .padding(.top, 1)
                    if !traces.isEmpty {
                        tracesSection
                            .padding(.top, 6)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(MFTheme.incomingBubble(scheme))
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.07), lineWidth: 0.7))
            }

            Spacer(minLength: 52)
        }
        .padding(.vertical, 2)
    }

    /// 生成中但还没有正文：只有三个点（+ 正在跑的工具痕迹），**没有气泡**。
    ///
    /// 为什么连痕迹也不套气泡：这一版的要求是「生成中只显示输入指示」；
    /// 一旦有真字，整个东西才变成一条消息（那是下面的分支）。
    private struct ChatTypingRow: View {
        let traces: [ToolTraceItem]
        let animationsEnabled: Bool

        @Environment(\.colorScheme) private var scheme

        var body: some View {
            VStack(alignment: .leading, spacing: 4) {
                ChatTypingDots(animationsEnabled: animationsEnabled)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                if !traces.isEmpty {
                    HStack(spacing: 5) {
                        Image(systemName: "sparkles").font(.system(size: 10))
                        Text(ToolTraceCodec.summarize(traces).isEmpty ? L("正在忙") : ToolTraceCodec.summarize(traces))
                            .font(.caption2)
                            .lineLimit(1)
                    }
                    .foregroundStyle(MFTheme.subtleText(scheme))
                    .padding(.horizontal, 12)
                }
            }
        }
    }

    private var tracesSection: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button {
                withAnimation(animationsEnabled ? .easeInOut(duration: 0.16) : nil) {
                    tracesExpanded.toggle()
                }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "sparkles").font(.system(size: 10))
                    Text(ToolTraceCodec.summarize(traces).isEmpty ? L("正在忙") : ToolTraceCodec.summarize(traces))
                        .font(.caption2)
                        .lineLimit(1)
                    Image(systemName: tracesExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                }
                .foregroundStyle(MFTheme.subtleText(scheme))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if tracesExpanded {
                ChatToolTraceDetail(items: traces, tint: MFTheme.subtleText(scheme))
            }
        }
    }
}

// MARK: - 动作痕迹明细

struct ChatToolTraceDetail: View {

    let items: [ToolTraceItem]
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                VStack(alignment: .leading, spacing: 2) {
                    Text(headline(item))
                        .font(.caption2)
                        .foregroundStyle(item.ok ? tint : Color.red)
                        .lineLimit(2)
                    if !item.detail.isEmpty {
                        Text(item.detail)
                            .font(.caption2)
                            .foregroundStyle((item.ok ? tint : Color.red).opacity(0.85))
                            .lineLimit(3)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func headline(_ item: ToolTraceItem) -> String {
        var parts: [String] = [item.title.isEmpty ? item.name : item.title]
        if let subtitle = item.subtitle, !subtitle.isEmpty { parts.append(subtitle) }
        if !item.ok { parts.append(L("失败")) }
        return parts.joined(separator: " · ")
    }
}

// MARK: - 小动画

/// 「正在输入」的三个点。
struct ChatTypingDots: View {

    let animationsEnabled: Bool

    @State private var animate = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 6, height: 6)
                    .opacity(animationsEnabled ? (animate ? 1 : 0.25) : 0.6)
                    .animation(
                        animationsEnabled
                            ? .easeInOut(duration: 0.6).repeatForever(autoreverses: true).delay(Double(index) * 0.14)
                            : nil,
                        value: animate
                    )
            }
        }
        .frame(height: 20)
        .onAppear { if animationsEnabled { animate = true } }
        .accessibilityLabel(Text(L("正在输入")))
    }
}

/// 流式输出时跟在正文后面的光标。
struct ChatBlinkingCursor: View {

    let animationsEnabled: Bool

    @State private var dim = false

    var body: some View {
        Text("▍")
            .font(.footnote)
            .foregroundStyle(MFTheme.defaultAccent.opacity(animationsEnabled ? (dim ? 0.2 : 1) : 0.85))
            .animation(
                animationsEnabled ? .easeInOut(duration: 0.7).repeatForever(autoreverses: true) : nil,
                value: dim
            )
            .onAppear { if animationsEnabled { dim = true } }
            .accessibilityHidden(true)
    }
}

// MARK: - 气泡形状

/// 四个角可以不同圆角的圆角矩形。
///
/// 为什么不用 `RoundedRectangle`：柔和样式的气泡要「靠人那一侧收窄」（收到的是左下角、
/// 发出的是右下角），这是 Telegram 那套观感的关键一笔；`UnevenRoundedRectangle` 要 iOS 17。
/// 注意要符合 `InsettableShape`：`strokeBorder` 只对它可用，而且描边会画在路径**内侧**，
/// 气泡尺寸不会被一条 1pt 的边框撑胖。
struct ChatBubbleShape: InsettableShape {

    var topLeft: CGFloat
    var topRight: CGFloat
    var bottomLeft: CGFloat
    var bottomRight: CGFloat

    static func of(_ style: BubbleStyle, outgoing: Bool) -> ChatBubbleShape {
        switch style {
        case .soft:
            return outgoing
                ? ChatBubbleShape(topLeft: 21, topRight: 21, bottomLeft: 21, bottomRight: 7)
                : ChatBubbleShape(topLeft: 21, topRight: 21, bottomLeft: 7, bottomRight: 21)
        case .compact:
            return outgoing
                ? ChatBubbleShape(topLeft: 12, topRight: 12, bottomLeft: 12, bottomRight: 4)
                : ChatBubbleShape(topLeft: 12, topRight: 12, bottomLeft: 4, bottomRight: 12)
        case .outlined:
            return ChatBubbleShape(topLeft: 18, topRight: 18, bottomLeft: 18, bottomRight: 18)
        }
    }

    func inset(by amount: CGFloat) -> ChatBubbleShape {
        ChatBubbleShape(
            topLeft: max(topLeft - amount, 0),
            topRight: max(topRight - amount, 0),
            bottomLeft: max(bottomLeft - amount, 0),
            bottomRight: max(bottomRight - amount, 0)
        )
    }

    func path(in rect: CGRect) -> Path {
        let tl = min(topLeft, min(rect.width, rect.height) / 2)
        let tr = min(topRight, min(rect.width, rect.height) / 2)
        let bl = min(bottomLeft, min(rect.width, rect.height) / 2)
        let br = min(bottomRight, min(rect.width, rect.height) / 2)

        var path = Path()
        path.move(to: CGPoint(x: rect.minX + tl, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - tr, y: rect.minY))
        path.addArc(
            center: CGPoint(x: rect.maxX - tr, y: rect.minY + tr),
            radius: tr, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - br))
        path.addArc(
            center: CGPoint(x: rect.maxX - br, y: rect.maxY - br),
            radius: br, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.minX + bl, y: rect.maxY))
        path.addArc(
            center: CGPoint(x: rect.minX + bl, y: rect.maxY - bl),
            radius: bl, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + tl))
        path.addArc(
            center: CGPoint(x: rect.minX + tl, y: rect.minY + tl),
            radius: tl, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false
        )
        path.closeSubpath()
        return path
    }
}
