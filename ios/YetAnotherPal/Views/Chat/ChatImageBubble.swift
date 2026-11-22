import AVKit
import SwiftUI

/// 聊天气泡里的图片：缩略图 + 点击全屏。
///
/// 一条消息最多四张图（`AttachmentsCodec.maxImagesPerMessage`）。一张时按原比例铺开，
/// 多张时排成两列小方格 —— 与 Android 的图片气泡同一套观感，也不至于让一条消息占满一屏。
///
/// 缩略图一律按**显示尺寸**解码（`AttachmentThumbnail` → `MediaStorage.thumbnail`），
/// 文件被清掉时显示「图片已不在本机」，不崩、不空白。
struct ChatAttachmentsView: View {

    let attachments: [MessageAttachment]
    /// 点第几张（下标）。
    let onOpen: (Int) -> Void

    private var singleSize: CGSize {
        guard let first = attachments.first else { return CGSize(width: 200, height: 150) }
        return Self.thumbnailSize(for: first)
    }

    var body: some View {
        if attachments.count == 1 {
            image(at: 0, size: singleSize, corner: 12)
        } else {
            grid
        }
    }

    private var grid: some View {
        let columns = 2
        let side: CGFloat = 106
        let rows = Int(ceil(Double(attachments.count) / Double(columns)))
        return VStack(spacing: 4) {
            ForEach(0..<rows, id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(0..<columns, id: \.self) { column in
                        let index = row * columns + column
                        if index < attachments.count {
                            image(at: index, size: CGSize(width: side, height: side), corner: 10)
                        } else {
                            Color.clear.frame(width: side, height: side)
                        }
                    }
                }
            }
        }
    }

    private func image(at index: Int, size: CGSize, corner: CGFloat) -> some View {
        Button {
            onOpen(index)
        } label: {
            AttachmentThumbnail(
                uri: attachments[index].uri,
                pointSize: max(size.width, size.height),
                contentMode: attachments.count == 1 ? .fit : .fill
            ) { missing in
                AttachmentPlaceholder(missing: missing)
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(L("图片，点开看大图")))
    }

    /// 按附件记录的真实像素宽高算缩略图占位（没有宽高时按 4:3）。
    static func thumbnailSize(for attachment: MessageAttachment) -> CGSize {
        let maxWidth: CGFloat = 232
        let maxHeight: CGFloat = 280
        guard attachment.width > 0, attachment.height > 0 else {
            return CGSize(width: maxWidth, height: maxWidth * 0.75)
        }
        let ratio = CGFloat(attachment.width) / CGFloat(attachment.height)
        let clamped = min(max(ratio, 0.5), 2.4)
        if clamped >= 1 {
            let width = maxWidth
            return CGSize(width: width, height: max(96, width / clamped))
        }
        let height = maxHeight
        return CGSize(width: max(96, height * clamped), height: height)
    }
}

// MARK: - 语音 / 视频（0.13）

/// 一条消息里的一段语音或视频。
///
/// 为什么气泡分成两种而不是一个「播放器」：
/// - 语音是一行小条（播放键 + 时长），像微信那样占的地方最小；
/// - 视频要有封面（首帧）+ 时长角标，点开全屏。
///
/// 两者的文件来源是同一套（`MediaPlayback`）：本地沙盒文件，或服务端
/// `/v1/media/<id>`（先带 Authorization 下载成带扩展名的缓存文件再播）。
struct ChatPlayableMediaView: View {

    let attachment: MessageAttachment
    /// 转写那几行的颜色：出向气泡是强调色底（白字），入向是纸面（深字），
    /// 所以由调用方（`ChatMessageRow`）按气泡样式传进来，而不是在这里猜。
    var captionColor: Color = .secondary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if attachment.isAudio {
                ChatAudioBubble(attachment: attachment)
            } else if attachment.isVideo {
                ChatVideoBubble(attachment: attachment)
            }
            // 0.15 语音转写：有转写就把原话放在气泡下面（不用点播放也能知道说了什么）；
            // 试过但没转出来时明确标一句「没有转写」—— 不假装这段语音是文字。
            if attachment.hasTranscript, let transcript = attachment.transcript {
                Text(transcript)
                    .font(.caption2)
                    .foregroundStyle(captionColor)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 232, alignment: .leading)
            } else if attachment.transcriptIsMissing {
                Text(L("没有转写"))
                    .font(.caption2)
                    .foregroundStyle(captionColor)
            }
        }
    }
}

/// 语音气泡：播放/暂停 + 时长。播放中再点一下暂停（再点继续）。
struct ChatAudioBubble: View {

    let attachment: MessageAttachment

    @Environment(\.colorScheme) private var scheme
    @State private var player: AVPlayer?
    @State private var item: AVPlayerItem?
    @State private var isPlaying = false
    @State private var isPreparing = false
    @State private var missing = false

    private var durationText: String { attachment.durationText }

    var body: some View {
        HStack(spacing: 10) {
            Button {
                toggle()
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.primary.opacity(0.08))
                        .frame(width: 34, height: 34)
                    if isPreparing {
                        ProgressView().scaleEffect(0.7)
                    } else {
                        Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Color.primary.opacity(0.8))
                    }
                }
                .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(isPlaying ? L("暂停语音") : L("播放语音")))

            // 一根「声波」：纯装饰，但能让语音气泡一眼被认出来（不必真的画波形）。
            HStack(spacing: 2.5) {
                ForEach(0..<12, id: \.self) { index in
                    Capsule()
                        .fill(Color.primary.opacity(isPlaying ? 0.55 : 0.28))
                        .frame(width: 2.5, height: barHeight(index))
                }
            }
            .frame(height: 20)
            .animation(.easeInOut(duration: 0.2), value: isPlaying)

            Text(missing ? L("语音已不在本机") : durationText)
                .font(.caption.monospacedDigit())
                .foregroundStyle(MFTheme.subtleText(scheme))
        }
        .frame(width: 176, alignment: .leading)
        .onDisappear { stop() }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { note in
            guard let item, note.object as? AVPlayerItem === item else { return }
            isPlaying = false
            item.seek(to: .zero)
        }
    }

    private func barHeight(_ index: Int) -> CGFloat {
        // 固定的一组高度（不随录音变化）：它只是「这是一段声音」的形状，不是真波形。
        let pattern: [CGFloat] = [7, 13, 18, 11, 16, 9, 15, 20, 12, 17, 10, 14]
        return pattern[index % pattern.count]
    }

    private func toggle() {
        if isPlaying {
            player?.pause()
            isPlaying = false
            return
        }
        if let player {
            player.play()
            isPlaying = true
            return
        }
        isPreparing = true
        Task { @MainActor in
            guard let url = await MediaPlayback.playableFileURL(for: attachment) else {
                isPreparing = false
                missing = true
                return
            }
            let item = AVPlayerItem(url: url)
            let player = AVPlayer(playerItem: item)
            self.item = item
            self.player = player
            self.isPreparing = false
            player.play()
            self.isPlaying = true
        }
    }

    private func stop() {
        player?.pause()
        player = nil
        item = nil
        isPlaying = false
    }
}

/// 视频气泡：首帧封面 + 时长角标；点开全屏播放。
struct ChatVideoBubble: View {

    let attachment: MessageAttachment

    @Environment(\.colorScheme) private var scheme
    @Environment(\.displayScale) private var displayScale
    @State private var poster: CGImage?
    @State private var missing = false
    @State private var showPlayer = false

    private var size: CGSize {
        // 有宽高就按比例（上限 232×280，与图片气泡同一套观感）；没有就给 16:9。
        guard attachment.width > 0, attachment.height > 0 else {
            return CGSize(width: 232, height: 130)
        }
        let ratio = CGFloat(attachment.width) / CGFloat(attachment.height)
        let clamped = min(max(ratio, 0.5), 2.4)
        if clamped >= 1 {
            return CGSize(width: 232, height: max(96, 232 / clamped))
        }
        return CGSize(width: max(96, 280 * clamped), height: 200)
    }

    var body: some View {
        Button {
            guard !missing else { return }
            showPlayer = true
        } label: {
            ZStack {
                if let poster {
                    Image(decorative: poster, scale: 1)
                        .resizable()
                        .scaledToFill()
                } else {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(scheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.06))
                }
                if missing {
                    VStack(spacing: 6) {
                        Image(systemName: "video.slash")
                            .font(.system(size: 22, weight: .light))
                        Text(L("视频已不在本机"))
                            .font(.caption2)
                    }
                    .foregroundStyle(.secondary)
                } else {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(.white.opacity(0.92))
                        .shadow(radius: 4)
                }
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Text(attachment.durationText)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.black.opacity(0.45), in: Capsule())
                    }
                }
                .padding(6)
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(L("视频，点开播放")))
        .task(id: attachment.uri) { await loadPoster() }
        .fullScreenCover(isPresented: $showPlayer) {
            ChatVideoPlayerSheet(attachment: attachment)
        }
    }

    private func loadPoster() async {
        guard poster == nil, !missing else { return }
        let target = Int(max(size.width, size.height) * displayScale)
        let image = await MediaPlayback.videoPosterImage(
            for: attachment, maxPixel: max(320, min(target, 1024))
        )
        if let image {
            poster = image
        } else {
            missing = true
        }
    }
}

/// 全屏播放（`AVPlayerViewController` 的 SwiftUI 外壳 `VideoPlayer`）。
struct ChatVideoPlayerSheet: View {

    let attachment: MessageAttachment

    @Environment(\.dismiss) private var dismiss
    @State private var url: URL?
    @State private var failed = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            Group {
                if let url {
                    VideoPlayer(player: AVPlayer(url: url))
                        .ignoresSafeArea()
                } else if failed {
                    VStack(spacing: 8) {
                        Image(systemName: "video.slash")
                            .font(.system(size: 32, weight: .light))
                        Text(L("视频已不在本机"))
                            .font(.footnote)
                    }
                    .foregroundStyle(.white.opacity(0.8))
                } else {
                    ProgressView().tint(.white)
                }
            }
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(.black.opacity(0.45), in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            .padding(.trailing, 14)
            .accessibilityLabel(Text(L("关闭")))
        }
        .statusBarHidden(true)
        .task { await load() }
    }

    private func load() async {
        guard url == nil else { return }
        if let local = await MediaPlayback.playableFileURL(for: attachment) {
            url = local
        } else {
            failed = true
        }
    }
}

// MARK: - 全屏查看

/// 全屏看图的入口参数：点的是哪条消息里的第几张。
struct ChatImagePreviewItem: Identifiable {
    let id: String
    let attachments: [MessageAttachment]
    let index: Int

    init(message: MessageEntity, attachments: [MessageAttachment], index: Int) {
        self.id = "\(message.id)#\(index)"
        self.attachments = attachments
        self.index = index
    }
}

/// 全屏看图：黑色底、左右翻页、双指缩放/拖动、双重点一下复位、右上角关闭。
///
/// 为什么自己做而不是 `QuickLook`：需要「关掉就回到聊天」这一下足够快，
/// 也不想为了看一张图去接一整套预览框架。
struct ChatImageViewer: View {

    let attachments: [MessageAttachment]
    @State private var index: Int

    @Environment(\.dismiss) private var dismiss

    init(attachments: [MessageAttachment], initialIndex: Int) {
        self.attachments = attachments
        _index = State(initialValue: min(max(initialIndex, 0), max(attachments.count - 1, 0)))
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()

            TabView(selection: $index) {
                ForEach(attachments.indices, id: \.self) { position in
                    ZoomableAttachmentImage(attachment: attachments[position])
                        .tag(position)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: attachments.count > 1 ? .automatic : .never))
            .ignoresSafeArea()

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(.black.opacity(0.45), in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            .padding(.trailing, 14)
            .accessibilityLabel(Text(L("关闭")))
        }
        .statusBarHidden(true)
    }
}

/// 一张可以缩放/拖动的图。
private struct ZoomableAttachmentImage: View {

    let attachment: MessageAttachment

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    private let minScale: CGFloat = 1
    private let maxScale: CGFloat = 5

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                AttachmentThumbnail(
                    uri: attachment.uri,
                    pointSize: max(proxy.size.width, proxy.size.height)
                ) { missing in
                    if missing {
                        VStack(spacing: 8) {
                            Image(systemName: "photo.badge.exclamationmark")
                                .font(.system(size: 34, weight: .light))
                            Text(L("图片已不在本机"))
                                .font(.footnote)
                        }
                        .foregroundStyle(.white.opacity(0.75))
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .scaledToFit()
                .scaleEffect(scale)
                .offset(offset)
                .frame(width: proxy.size.width, height: proxy.size.height)
                .gesture(magnification)
                .simultaneousGesture(drag)
                .onTapGesture(count: 2) { toggleZoom() }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(max(lastScale * value, minScale), maxScale)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= minScale { reset() }
            }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > minScale else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }

    private func toggleZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            if scale > minScale {
                reset()
            } else {
                scale = 2.4
                lastScale = scale
            }
        }
    }

    private func reset() {
        withAnimation(.easeInOut(duration: 0.2)) {
            scale = minScale
            lastScale = minScale
            offset = .zero
            lastOffset = .zero
        }
    }
}
