import CoreGraphics
import CryptoKit
import Foundation

// ---------------------------------------------------------------------------
// 语音与视频附件（0.13，`spec/audio-video/README.md`）。
//
// 与图片那条链路（`MediaStorage`）刻意分开的原因：
// - 目录不同：规格要求落在 `Application Support/audio` 与 `Application Support/video`；
// - 处理不同：图片要解码 + 重压成 JPEG，音频/视频**不转码**（录音已经是 AAC 16 kHz、
//   视频要原样保留），所以只需要「校验 → 原子写 → 归属守卫」；
// - 单位不同：音频/视频还有时长（`durationMs`），限制是「时长 + 体积」两条同时成立。
//
// 纯逻辑（上限、拒绝文案、候选 id）与 IO 分开：单测可以在临时目录里跑，
// 不必真的录音或解一段视频。
// ---------------------------------------------------------------------------

/// 语音/视频的硬上限（两端同一组数字，改了就是协议变更）。
public enum AudioVideoLimits {

    /// 语音最长 5 分钟（`spec/audio-video/README.md`）。
    public static let audioMaxDurationMs = 5 * 60 * 1000
    /// 视频最长 2 分钟。
    public static let videoMaxDurationMs = 2 * 60 * 1000
    /// 语音单文件上限 8 MiB（服务端 `MediaRoutes.MAX_AUDIO_BYTES` 同值）。
    public static let audioMaxBytes = 8 * 1024 * 1024
    /// 视频单文件上限 64 MiB（服务端 `MediaRoutes.MAX_VIDEO_BYTES` 同值）。
    public static let videoMaxBytes = 64 * 1024 * 1024

    /// 录音参数：AAC 单声道 16 kHz。
    public static let audioSampleRate: Double = 16_000
    public static let audioChannels = 1
    public static let audioBitRate = 32_000

    /// 录音落盘的格式与 mime。
    public static let audioMime = "audio/m4a"
    /// 视频不转码：iPhone 相册里的视频通常是 H.264/HEVC 的 `.mov`/`.mp4`。
    /// 默认写 `.mp4`（规格要求），文件本身是什么容器就按 mime 记什么。
    public static let videoMime = "video/mp4"

    /// 允许的音频 / 视频 mime（与 `AttachmentsCodec.allowedMimes` 的对应子集一致）。
    public static let audioMimes: Set<String> = ["audio/m4a", "audio/mp4", "audio/mpeg"]
    public static let videoMimes: Set<String> = ["video/mp4", "video/quicktime"]

    public enum Kind: String, Sendable {
        case audio = "AUDIO"
        case video = "VIDEO"

        public var maxDurationMs: Int {
            switch self {
            case .audio: return AudioVideoLimits.audioMaxDurationMs
            case .video: return AudioVideoLimits.videoMaxDurationMs
            }
        }

        public var maxBytes: Int {
            switch self {
            case .audio: return AudioVideoLimits.audioMaxBytes
            case .video: return AudioVideoLimits.videoMaxBytes
            }
        }

        public var defaultMime: String {
            switch self {
            case .audio: return AudioVideoLimits.audioMime
            case .video: return AudioVideoLimits.videoMime
            }
        }

        public var allowedMimes: Set<String> {
            switch self {
            case .audio: return AudioVideoLimits.audioMimes
            case .video: return AudioVideoLimits.videoMimes
            }
        }

        public var kindValue: String {
            switch self {
            case .audio: return MessageAttachment.audioKind
            case .video: return MessageAttachment.videoKind
            }
        }

        /// 文件名的前缀（`audio-…` / `video-…`）。
        public var fileNamePrefix: String {
            rawValue.lowercased() + "-"
        }

        public var displayName: String {
            switch self {
            case .audio: return String(localized: "语音")
            case .video: return String(localized: "视频")
            }
        }
    }

    /// 这个附件属于哪一类（不是音频/视频就 nil）。
    public static func kind(of attachment: MessageAttachment) -> Kind? {
        if attachment.isAudio { return .audio }
        if attachment.isVideo { return .video }
        return nil
    }

    /// 这个 mime 应该落成什么扩展名（落盘与播放都用它）。
    public static func fileExtension(forMime mime: String) -> String? {
        switch mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "audio/m4a", "audio/mp4": return "m4a"
        case "audio/mpeg": return "mp3"
        case "video/mp4": return "mp4"
        case "video/quicktime": return "mov"
        default: return nil
        }
    }

    /// 播放/上传时用的规范 mime（`audio/x-m4a` 这类变体归一）。
    public static func canonicalMime(_ raw: String) -> String {
        let mime = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch mime {
        case "audio/x-m4a", "audio/aac", "audio/x-aac": return "audio/m4a"
        case "audio/mp4a-latm": return "audio/mp4"
        case "video/quicktime", "video/mov": return "video/quicktime"
        default: return mime
        }
    }

    /// 录音到点自动停：时长到上限就自己收尾（不在 UI 上等用户松手）。
    public static func shouldAutoStop(elapsedMs: Int) -> Bool {
        elapsedMs >= audioMaxDurationMs
    }

    /// 时长文本 `0:07` / `1:23`（气泡上显示的就是它）。
    public static func durationText(_ durationMs: Int?) -> String {
        guard let durationMs, durationMs > 0 else { return "--:--" }
        let totalSeconds = Int((Double(durationMs) / 1000).rounded())
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// 超限时给用户看的一句话；没超限返回 nil。
    ///
    /// 判据是「时长 + 体积」两条，都按**原始字节**算：音频/视频不重压，
    /// 超限就是超限，不做「悄悄压一下再发」这种用户不知道的事。
    public static func rejection(
        durationMs: Int?,
        bytes: Int,
        kind: Kind
    ) -> String? {
        if let durationMs, durationMs > kind.maxDurationMs {
            let minutes = kind.maxDurationMs / 60_000
            return String(format: String(localized: "%@最长 %d 分钟"), kind.displayName, minutes)
        }
        if bytes > kind.maxBytes {
            let megabytes = kind.maxBytes / (1024 * 1024)
            return String(format: String(localized: "%@超过 %d MB"), kind.displayName, megabytes)
        }
        return nil
    }
}

/// 一条待发送/已落库的媒体附件的校验（不存在 IO，单测直接喂数字）。
public enum MediaAttachmentValidation {

    /// 这个附件现在能不能发（超时长 / 超体积 / 格式不认）。
    public static func rejection(attachment: MessageAttachment, byteCount: Int) -> String? {
        let mime = AudioVideoLimits.canonicalMime(attachment.mime)
        if let kind = AudioVideoLimits.kind(of: attachment) {
            guard kind.allowedMimes.contains(mime) else {
                return String(format: String(localized: "%@的格式不支持（%@）"), kind.displayName, mime)
            }
            return AudioVideoLimits.rejection(
                durationMs: attachment.durationMs, bytes: byteCount, kind: kind
            )
        }
        guard AttachmentsCodec.isAllowedMime(mime) else {
            return String(format: String(localized: "不支持这种附件格式（%@）"), mime)
        }
        return nil
    }
}

// MARK: - 媒体引用

public extension MessageAttachment {

    /// 这条附件在**上下文/工具参数**里的短 id。
    ///
    /// - 服务端媒体：`/v1/media/<id>` 里的 id；
    /// - 本地媒体：文件名去掉扩展名（`audio-3f2a…-UUID`），足够唯一，也能被 `listen`/`watch` 找回。
    ///
    /// 为什么不直接用 `uri`：本地 `uri` 是一整条 `file://` 绝对路径（还会随容器 UUID 变化），
    /// 让它出现在提示词里既浪费 token，换机之后也对不上。
    var mediaRefId: String? {
        if let id = ServerMediaReference.mediaId(from: uri) { return id }
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let url = URL(string: trimmed), url.isFileURL else { return nil }
        let name = url.deletingPathExtension().lastPathComponent
        return name.isEmpty ? nil : name
    }

    /// 气泡上显示的时长（`0:07` / `1:23`）；不知道时 `--:--`。
    var durationText: String { AudioVideoLimits.durationText(durationMs) }

    /// 提示词里那一行「这条消息带的是什么媒体」（**不含**转写）。
    ///
    /// 模型必须知道「这条消息不是文字，而是一段语音/视频」：否则它会以为对方发了空消息，
    /// 或者把「我听了一遍」当成真读到了转写文字。
    ///
    /// 0.15 起它只是「基础那一行」：真正的 `mediaContextLine` 在它后面再接一行
    /// 「他说：…」（见 `AsrTranscriptContext.mediaLine(for:)`）。
    var mediaDescriptionLine: String {
        guard isPlayableMedia else { return "" }
        let duration = durationSeconds.map { String(format: "%.1f", $0) } ?? "?"
        let kindText = isAudio ? String(localized: "语音") : String(localized: "视频")
        let id = mediaRefId ?? "?"
        let tool = isAudio ? "listen(audioId: \"\(id)\")" : "watch(videoId: \"\(id)\")"
        return String(
            format: String(localized: "（这条消息是一段%@，时长 %@ 秒；媒体 id：%@。想听/看就用 %@，别猜内容。）"),
            kindText, duration, id, tool
        )
    }

    /// 提示词里那一行「这条消息带的是什么媒体」+（有的话）转写。
    ///
    /// 0.15 语音转写：转写成功时多一行「他说：…」；试过但失败时多一行「（这段语音没有转写）」。
    /// 没有转写（关着 / 没试过）时与 0.13/0.14 的提示词**逐字相同** —— 老数据不受影响。
    ///
    /// 为什么挂在附件上（而不是改 `PromptBuilder`）：当轮消息与历史消息都读这一个属性，
    /// 转写因此同时进入「这一轮」和「历史占位」，不需要在两条链路各写一遍。
    /// 原始 audio / frames 的发送逻辑完全不动（转写只是多一行字，不替代音频）。
    var mediaContextLine: String {
        AsrTranscriptContext.mediaLine(for: self)
    }
}

// MARK: - 发给模型的多模态片段

/// 一段音频的多模态输入（OpenAI 兼容：`content[]` 里的 `input_audio`）。
public struct WireAudioPart: Equatable, Sendable {
    /// 原始字节的 base64（**不带** `data:` 前缀）。
    public var base64: String
    /// provider 认的格式名：`m4a` / `mp4` / `mpeg`。
    public var format: String

    public init(base64: String, format: String) {
        self.base64 = base64
        self.format = format
    }

    /// mime → provider 的 `format` 字段。
    public static func format(forMime mime: String) -> String? {
        switch AudioVideoLimits.canonicalMime(mime) {
        case "audio/m4a", "audio/mp4": return "m4a"
        case "audio/mpeg": return "mpeg"
        case "video/mp4", "video/quicktime": return "m4a"
        default: return nil
        }
    }
}

/// 一轮消息里要交给模型的多模态内容（音频片段 + 视频关键帧 + 一句文字说明）。
///
/// 同一份类型既给「当轮附件」（`PromptBuilder.history`）用，也给 `listen`/`watch`
/// 的工具结果（`ToolResult.mediaParts`）用：两条路最终都变成 `WireMessage` 的
/// `audio` / `images`，模型收到的形状完全一致。
public struct MediaWireParts: Equatable, Sendable {
    public var audio: [WireAudioPart]
    /// 已经编码好的 data URL（视频关键帧 / 图片）。
    public var images: [String]
    /// 追加到正文后面的一句说明（例如「视频：8 帧，时长 12 秒」）。
    public var note: String

    public init(audio: [WireAudioPart] = [], images: [String] = [], note: String = "") {
        self.audio = audio
        self.images = images
        self.note = note
    }

    public var isEmpty: Bool { audio.isEmpty && images.isEmpty && note.isEmpty }

    public static func + (lhs: MediaWireParts, rhs: MediaWireParts) -> MediaWireParts {
        MediaWireParts(
            audio: lhs.audio + rhs.audio,
            images: lhs.images + rhs.images,
            note: [lhs.note, rhs.note].filter { !$0.isEmpty }.joined(separator: "\n")
        )
    }
}

/// 「把附件变成多模态片段」的抽象：生产用 `LocalMediaPartsBuilder`，
/// 测试注入假实现（不必真的解码一段视频）。
public protocol MediaPartsBuilding: AnyObject {
    func parts(for attachments: [MessageAttachment]) async -> MediaWireParts?
}

/// 从本机/服务端媒体读出字节、抽关键帧，拼成多模态片段。
public final class LocalMediaPartsBuilder: MediaPartsBuilding {

    private let store: AudioVideoStore
    private let frames: VideoFrameExtracting

    public init(
        store: AudioVideoStore = .shared,
        frames: VideoFrameExtracting = AVVideoFrameExtractor()
    ) {
        self.store = store
        self.frames = frames
    }

    public func parts(for attachments: [MessageAttachment]) async -> MediaWireParts? {
        var result = MediaWireParts()
        for attachment in attachments {
            if attachment.isAudio {
                guard let data = store.data(for: attachment),
                      data.count <= AudioVideoLimits.audioMaxBytes,
                      let format = WireAudioPart.format(forMime: attachment.mime) else { continue }
                result.audio.append(WireAudioPart(base64: data.base64EncodedString(), format: format))
            } else if attachment.isVideo {
                guard let url = store.fileURL(for: attachment) else { continue }
                let duration = attachment.durationSeconds
                let images = await VideoFramePolicy.jpegDataURLs(
                    for: url, durationSeconds: duration, extractor: frames
                )
                guard !images.isEmpty else { continue }
                result.images.append(contentsOf: images)
                result.note = VideoFramePolicy.note(
                    frameCount: images.count,
                    durationSeconds: duration ?? VideoFramePolicy.estimatedDuration(images.count)
                )
            }
        }
        return result.isEmpty ? nil : result
    }
}

// MARK: - 播放（本地文件 / 带鉴权的服务端媒体）

/// 语音/视频播放要的一个「能直接交给 `AVPlayer` 的本地文件 URL」。
///
/// - 本地附件：`Application Support/audio|video` 里的文件；
/// - 服务端附件（`/v1/media/<id>`）：先带 Authorization 下载，再落成带扩展名的缓存文件
///   （`ServerMediaCache.playableFileURL`）。
///
/// 放在 Kit 里而不是视图里：两个气泡（语音/视频）+ 未来的全屏播放都要同一套解析，
/// 各写一份迟早出现「视频能放、语音放不了」这种一半好的 bug。
public enum MediaPlayback {

    /// 本机文件（服务端媒体返回 nil）。
    public static func localFileURL(for attachment: MessageAttachment) -> URL? {
        guard attachment.isPlayableMedia,
              ServerMediaReference.mediaId(from: attachment.uri) == nil else { return nil }
        return AudioVideoStore.shared.fileURL(for: attachment)
    }

    /// 可播放的本地 URL（本地直接给；服务端先下载缓存）。
    public static func playableFileURL(
        for attachment: MessageAttachment,
        store: AudioVideoStore = .shared
    ) async -> URL? {
        if let local = store.fileURL(for: attachment) { return local }
        guard attachment.isPlayableMedia,
              ServerMediaReference.mediaId(from: attachment.uri) != nil else { return nil }
        return try? await ServerMediaCache.shared.playableFileURL(
            for: attachment.uri, mime: attachment.mime
        )
    }

    /// 视频首帧（气泡封面）：按显示尺寸解码，文件不在/解不出来返回 nil（界面显示占位）。
    public static func videoPosterImage(
        for attachment: MessageAttachment,
        maxPixel: Int,
        store: AudioVideoStore = .shared,
        frames: VideoFrameExtracting = AVVideoFrameExtractor()
    ) async -> CGImage? {
        guard attachment.isVideo, let url = await playableFileURL(for: attachment, store: store) else {
            return nil
        }
        let key = "video-poster:\(attachment.uri)#\(maxPixel)"
        if let cached = ImageThumbnailCache.shared.image(forKey: key) { return cached }
        guard let data = await frames.firstFrameJPEG(of: url, maxPixel: maxPixel),
              let image = ImageThumbnailDecoder.thumbnail(data: data, maxPixelSize: maxPixel) else {
            return nil
        }
        ImageThumbnailCache.shared.store(image, forKey: key)
        return image
    }
}

// MARK: - 沙盒存储

/// 语音/视频文件本体：`Application Support/audio` 与 `Application Support/video`。
///
/// 三条规则与图片一致（`MediaStorage`）：**原子写**、**目录归属守卫**、**不进整机备份**。
/// 视频**不转码**（规格明确要求）：相册里的文件原样拷贝，超限就拒绝。
public final class AudioVideoStore {

    public enum StorageError: LocalizedError, Equatable {
        case empty
        case tooLarge(String)
        case unsupportedMime(String)
        case writeFailed

        public var errorDescription: String? {
            switch self {
            case .empty: return String(localized: "这个文件是空的")
            case .tooLarge(let message): return message
            case .unsupportedMime(let mime):
                return String(format: String(localized: "不支持这种附件格式（%@）"), mime)
            case .writeFailed: return String(localized: "文件没能存到本机")
            }
        }
    }

    public let audioDirectory: URL
    public let videoDirectory: URL

    private let fileManager: FileManager

    public init(
        audioDirectory: URL,
        videoDirectory: URL,
        fileManager: FileManager = .default
    ) {
        self.audioDirectory = audioDirectory
        self.videoDirectory = videoDirectory
        self.fileManager = fileManager
        for directory in [audioDirectory, videoDirectory] {
            try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            Self.applyBackupExclusion(directory)
        }
    }

    /// App 里的默认实例：`Application Support/audio|video`。
    public static let shared: AudioVideoStore = {
        let fallback = FileManager.default.temporaryDirectory
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fallback
        return AudioVideoStore(
            audioDirectory: base.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: base.appendingPathComponent("video", isDirectory: true)
        )
    }()

    private static func applyBackupExclusion(_ directory: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = directory
        try? target.setResourceValues(values)
    }

    // MARK: 写

    /// 存一段录音（`.m4a`）。失败时抛 `StorageError`（界面把原因显示出来）。
    public func saveAudio(data: Data, durationMs: Int, mime: String = AudioVideoLimits.audioMime) throws -> MessageAttachment {
        let canonical = AudioVideoLimits.canonicalMime(mime)
        guard AudioVideoLimits.audioMimes.contains(canonical) else {
            throw StorageError.unsupportedMime(canonical)
        }
        guard !data.isEmpty else { throw StorageError.empty }
        if let rejection = AudioVideoLimits.rejection(
            durationMs: durationMs, bytes: data.count, kind: .audio
        ) {
            throw StorageError.tooLarge(rejection)
        }
        let target = try write(
            data, kind: .audio, mime: canonical, fileExtension: AudioVideoLimits.fileExtension(forMime: canonical) ?? "m4a"
        )
        return .audio(uri: target.absoluteString, durationMs: durationMs, mime: canonical)
    }

    /// 存一段视频：**原样拷贝**（不转码），体积按源文件字节判断。
    ///
    /// - Parameters:
    ///   - sourceURL: 相册/文件选择器给的临时文件（可能在 `tmp/`，随时会被清）。
    ///   - durationMs: 由调用方用 `AVAsset` 读出来（这里不依赖 AVFoundation，纯 IO 可单测）。
    public func importVideo(
        at sourceURL: URL,
        durationMs: Int,
        width: Int = 0,
        height: Int = 0,
        mime: String = AudioVideoLimits.videoMime
    ) throws -> MessageAttachment {
        let canonical = AudioVideoLimits.canonicalMime(mime)
        guard AudioVideoLimits.videoMimes.contains(canonical) else {
            throw StorageError.unsupportedMime(canonical)
        }
        let size = (try? fileManager.attributesOfItem(atPath: sourceURL.path))?[.size] as? NSNumber
        let bytes = size?.intValue ?? 0
        guard bytes > 0 else { throw StorageError.empty }
        if let rejection = AudioVideoLimits.rejection(
            durationMs: durationMs, bytes: bytes, kind: .video
        ) {
            throw StorageError.tooLarge(rejection)
        }
        guard let data = try? Data(contentsOf: sourceURL), data.count == bytes else {
            throw StorageError.writeFailed
        }
        let target = try write(
            data, kind: .video, mime: canonical,
            fileExtension: AudioVideoLimits.fileExtension(forMime: canonical) ?? "mp4"
        )
        return .video(
            uri: target.absoluteString, durationMs: durationMs,
            width: width, height: height, mime: canonical
        )
    }

    private func write(
        _ data: Data,
        kind: AudioVideoLimits.Kind,
        mime: String,
        fileExtension: String
    ) throws -> URL {
        let directory = directory(for: kind).standardizedFileURL
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined().prefix(16)
        let fileName = "\(kind.fileNamePrefix)\(hash)-\(UUID().uuidString).\(fileExtension)"
        guard Self.isSafeFileName(fileName) else { throw StorageError.writeFailed }
        let target = directory.appendingPathComponent(fileName, isDirectory: false).standardizedFileURL
        // 归属守卫：写之前确认目标确实在这个目录里（数据只能决定文件名，不能决定路径）。
        guard AttachmentLocation.isWithin(directory, target) else { throw StorageError.writeFailed }
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: target, options: .atomic)
        } catch {
            throw StorageError.writeFailed
        }
        return target
    }

    // MARK: 读

    /// `uri` → 真实文件。只认本机文件，并用目录归属守卫挡住 `..` / 软链。
    public func fileURL(for attachment: MessageAttachment) -> URL? {
        guard let kind = AudioVideoLimits.kind(of: attachment) else { return nil }
        return fileURL(for: attachment.uri, kind: kind)
    }

    public func fileURL(for uriString: String, kind: AudioVideoLimits.Kind) -> URL? {
        let directory = directory(for: kind)
        guard let url = AttachmentLocation.resolve(uriString, directory: directory, fileExists: { path in
            self.fileManager.fileExists(atPath: path)
        }) else { return nil }
        guard AttachmentLocation.isWithin(directory, url) else { return nil }
        return url
    }

    /// 原始字节（多模态 base64 / 上传都用它）；读不出或超限返回 nil。
    public func data(for attachment: MessageAttachment) -> Data? {
        guard let kind = AudioVideoLimits.kind(of: attachment),
              let url = fileURL(for: attachment),
              let data = try? Data(contentsOf: url),
              !data.isEmpty,
              data.count <= kind.maxBytes else { return nil }
        return data
    }

    public func byteCount(for attachment: MessageAttachment) -> Int {
        guard let url = fileURL(for: attachment) else { return 0 }
        return ((try? fileManager.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.intValue ?? 0
    }

    // MARK: 删

    /// 只删「自己目录里」的文件（与 `MediaStorage.deleteFileIfOwned` 同一条守卫）。
    public func deleteIfOwned(_ attachment: MessageAttachment) {
        guard let kind = AudioVideoLimits.kind(of: attachment),
              let url = fileURL(for: attachment),
              AttachmentLocation.isWithin(directory(for: kind), url) else { return }
        try? fileManager.removeItem(at: url)
    }

    public func deleteIfOwned(_ attachments: [MessageAttachment]) {
        for attachment in attachments where attachment.isPlayableMedia {
            deleteIfOwned(attachment)
        }
    }

    /// 删掉两个目录里的全部语音/视频（「清空聊天」/「删除全部数据」用）。
    public func deleteAll() {
        for directory in [audioDirectory, videoDirectory] {
            try? fileManager.removeItem(at: directory)
            try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            Self.applyBackupExclusion(directory)
        }
    }

    public func totalBytesOnDisk() -> Int {
        [audioDirectory, videoDirectory].reduce(0) { partial, directory in
            guard let items = try? fileManager.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: [.fileSizeKey]
            ) else { return partial }
            return partial + items.reduce(0) { sum, url in
                sum + ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            }
        }
    }

    // MARK: 内部

    public func directory(for kind: AudioVideoLimits.Kind) -> URL {
        switch kind {
        case .audio: return audioDirectory
        case .video: return videoDirectory
        }
    }

    /// 裸文件名守卫：不能带路径分隔符、不能是 `..`、不能过长。
    static func isSafeFileName(_ raw: String) -> Bool {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.utf8.count <= 200 else { return false }
        if name.contains("/") || name.contains("\\") { return false }
        if name == "." || name == ".." || name.contains("..") { return false }
        return true
    }
}
