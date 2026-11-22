import Foundation

// ---------------------------------------------------------------------------
// 消息附件（图片）。
//
// 来源：Android `domain/Attachments.kt`。这里**逐字段照抄**，因为 `messages.attachmentsJson`
// 会跟着备份在两端之间原样搬运（图片二进制不搬，元数据搬）：
//
//   [{"uri":"file:///…/chat-xxxx.jpg","mime":"image/jpeg","width":1600,"height":1200}]
//
// 字段名 `uri` / `mime` / `width` / `height` 是两端唯一的契约，一个字母都不能改。
// Android 侧用 kotlinx.serialization（`ignoreUnknownKeys = true`、`encodeDefaults = false`、
// `explicitNulls = false`），所以 iOS 这边也要做到三件同样的事：
//
// 1. **默认值不写进 JSON**（`mime = image/jpeg`、宽高 = 0 时省略）；
// 2. **未知字段忽略**（新版本多写的字段，老版本读得进）；
// 3. **坏数据不崩**：字段缺失/类型不对就整段当没有（Android 的 `runCatching` 同样整段丢掉），
//    `uri` 为空的条目再过滤掉。
//
// 0.15 起 `kind` 与 `mime` 是**必须明确**的协议字段（`MediaType`）：
// - 新写入一律写 `kind`（普通图片写 `IMAGE`），不再留 null；
// - 读入时显式 kind 必须是 `IMAGE` / `STICKER` / `AUDIO` / `VIDEO` 之一、且 mime 与它匹配，
//   否则**拒绝这条附件**（整段按坏数据处理，绝不「猜成图片」）；
// - 只有 0.13 及更早的数据（没写 kind）才按 mime 推断，属于兼容读取。
//
// 图片文件本身存在 App 沙盒（`Application Support/attachments/`），
// `uri` 是 `file://` 绝对路径 —— 换机/重装就失效，所以二进制**不进备份**。
// ---------------------------------------------------------------------------

/// 一条消息里附带的一张图片（0.13 起也用来带表情：`kind=STICKER` + `mime=image/gif`）。
public struct MessageAttachment: Equatable, Codable, Sendable {

    /// Android 的默认 MIME：压缩后我们统一写 JPEG。
    public static let defaultMime = "image/jpeg"

    /// 表情附件的 `kind` 值（与 agentd 的 `MediaKind.STICKER` 一致）。
    ///
    /// 为什么同时写 `kind` 和 mime：两端都是「看到 `image/gif` 就当表情」也能工作，
    /// 但服务端媒体/同步链路只带 mediaId 时会把 mime 补成默认的 `image/jpeg`；
    /// 多一个显式 `kind` 让「这条消息带的是表情」不再依赖 mime 猜。
    /// Android 的解码器 `ignoreUnknownKeys = true`，读到这个字段会忽略，不会炸。
    public static let stickerKind = "STICKER"

    /// 0.13 音视频模态（`spec/audio-video/README.md`）：`kind` 的三个取值。
    ///
    /// 服务端媒体（`/v1/media/<id>`）的同步链路只带 mediaId，mime 会被补成默认的
    /// `image/jpeg`；没有显式 `kind` 的话，语音/视频回到客户端就变成了「坏图」。
    /// 所以 `kind` 是**协议字段**，不是装饰：IMAGE / AUDIO / VIDEO 与服务端
    /// `MediaKind` 逐字对应。
    public static let imageKind = "IMAGE"
    public static let audioKind = "AUDIO"
    public static let videoKind = "VIDEO"

    /// `file://` 绝对路径，指向 App 私有目录里的副本（或 `/v1/media/<id>`）。
    public var uri: String
    public var mime: String
    /// 图片解码后的像素宽（0 = 不知道）。Android 目前恒写 0，iOS 写真实值方便气泡排版；
    /// 两端解码器都把 0 当「不知道」，所以不影响互通。
    public var width: Int
    public var height: Int
    /// 附件种类（`IMAGE` / `AUDIO` / `VIDEO` / `STICKER`）；nil = 按 mime 判断
    /// （老的图片附件没有这一列）。
    ///
    /// 只在新版本写、老版本忽略，所以「加了字段」不会破坏备份互通：
    /// 编码时 nil 不写进 JSON，已有图片的 `attachmentsJson` 一个字节都不变。
    public var kind: String?
    /// 音频/视频的时长（毫秒）；nil = 不知道（图片恒为 nil）。
    ///
    /// 与 `kind` 一样只在新版本写、老版本忽略：`durationMs` 为空时一个字节都不写。
    public var durationMs: Int?
    /// 这份附件在服务端上的媒体 id（0.13 第二阶段）。
    ///
    /// 为什么本地已经有文件了还要留它：
    /// - 重新上传（转发/补发）时可以直接复用服务端那一份，不必再传一遍 64 MB 的视频；
    /// - 本机文件被清掉（换机、清缓存）时还能凭它重新拉一次；
    /// - 排查「这张图是哪来的」时不用去猜文件名。
    ///
    /// 与 `kind` 同一条兼容规矩：nil 不写进 JSON，老版本读到会当未知字段忽略。
    public var serverMediaId: String?

    /// 这段语音/视频的**语音转写**（ASR，0.15）。nil = 没有转写。
    ///
    /// 为什么把它放进附件而不是新开一张表：
    /// - 转写属于「这句话是怎么来的」，和音频是同一条消息的一部分；
    /// - `messages.attachmentsJson` 本来就跟着备份走，放进来就自动跨设备；
    /// - 老版本读到未知字段会忽略（Android `ignoreUnknownKeys = true`），不破坏互通。
    ///
    /// 只有**真的转写成功**才写；失败时保持 nil（绝不拿「我听到了…」这种话编一段）。
    /// 与 `kind` 同一条编码规矩：nil 不写进 JSON，老数据的字节一个都不变。
    public var transcript: String?

    /// 转写状态（0.15）。只有「试过但没成功」会写进 JSON（`FAILED`），
    /// 用来在气泡上标一句「没有转写」，而不是把 nil 含糊地当成「没试过」。
    ///
    /// 成功时状态由 `transcript` 本身表达，不额外写 `OK`（少一个字段、少一次兼容问题）。
    public var transcriptState: AsrTranscriptState?

    public init(
        uri: String,
        mime: String = MessageAttachment.defaultMime,
        width: Int = 0,
        height: Int = 0,
        kind: String? = nil,
        durationMs: Int? = nil,
        serverMediaId: String? = nil,
        transcript: String? = nil,
        transcriptState: AsrTranscriptState? = nil
    ) {
        self.uri = uri
        self.mime = mime
        self.width = width
        self.height = height
        // 0.15：**显式传入的 kind 归一成规范大写**；没传就保持 nil。
        //
        // 为什么不在初始化时补成「按 mime 推断」：0.13 及更早的数据没有 kind，读进来
        // 必须保持「没写」这个事实（`AttachmentsCodec.decode` 的 legacy 判定、
        // 以及「旧数据重新编码一个字节都不多」这条兼容性测试都依赖它）。
        // 新写入的显式 kind 由构造点负责（`image(uri:)` / `audio(...)` / `video(...)` /
        // 表情附件 / 备份导出时的 `portableAttachments`）。
        let explicit = kind?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.kind = explicit.isEmpty ? nil : (MediaType.parse(explicit)?.rawValue ?? explicit)
        self.durationMs = durationMs
        self.serverMediaId = serverMediaId
        // 转写：空白一律算「没有」；有文字就没有「失败」这回事（成功状态由文字本身表达）。
        let spoken = transcript?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.transcript = spoken.isEmpty ? nil : spoken
        if spoken.isEmpty {
            // 只会持久化「试过但失败」：`.succeeded` 而没有文字是自相矛盾的输入，
            // 这里不当成失败标记（宁可什么都不标，也不给用户看一个假的「没有转写」）。
            self.transcriptState = transcriptState == .failed ? .failed : nil
        } else {
            self.transcriptState = .succeeded
        }
    }

    /// 一段语音（AAC 单声道 16 kHz、`.m4a`）。
    public static func audio(
        uri: String,
        durationMs: Int,
        mime: String = AudioVideoLimits.audioMime
    ) -> MessageAttachment {
        MessageAttachment(uri: uri, mime: mime, width: 0, height: 0,
                          kind: audioKind, durationMs: max(0, durationMs))
    }

    /// 一段视频（不转码，`.mp4`/`.mov`）。
    public static func video(
        uri: String,
        durationMs: Int,
        width: Int = 0,
        height: Int = 0,
        mime: String = AudioVideoLimits.videoMime
    ) -> MessageAttachment {
        MessageAttachment(uri: uri, mime: mime, width: width, height: height,
                          kind: videoKind, durationMs: max(0, durationMs))
    }

    /// 一张普通图片（`kind=IMAGE`）。0.15 起新写入不再留 `kind = nil`。
    public static func image(
        uri: String,
        mime: String = MessageAttachment.defaultMime,
        width: Int = 0,
        height: Int = 0
    ) -> MessageAttachment {
        MessageAttachment(uri: uri, mime: mime, width: width, height: height,
                          kind: imageKind)
    }

    /// `kind` 归一化（大写、去空白）；nil = 没写。
    public var normalizedKind: String? {
        let trimmed = kind?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    public var isImage: Bool {
        switch mediaType {
        // 表情也是图片（渲染按 `isSticker` 先分流，但「这是一张图」这件事仍然成立）。
        case .image, .sticker: return true
        case .audio, .video, .avatar: return false
        // 老数据没写 kind：mime 是图片就当图片。
        case nil: return mime.lowercased().hasPrefix("image/")
        }
    }

    /// 一段语音：显式 `kind=AUDIO` 优先；没写 kind 时按 mime 推断。
    ///
    /// 服务端媒体只带 mediaId 时，mime 会被补成默认的 `image/jpeg`，所以**显式 kind
    /// 必须压过 mime** —— 这是 0.13 音视频能正常渲染的前提，0.15 的校验不会改变它。
    public var isAudio: Bool {
        switch mediaType {
        case .audio: return true
        case .image, .sticker, .video, .avatar: return false
        case nil: return mime.lowercased().hasPrefix("audio/")
        }
    }

    /// 一段视频：显式 `kind=VIDEO` 优先；没写 kind 时按 mime 推断。
    public var isVideo: Bool {
        switch mediaType {
        case .video: return true
        case .image, .sticker, .audio, .avatar: return false
        case nil: return mime.lowercased().hasPrefix("video/")
        }
    }

    /// 语音或视频（两者共用「一段媒体」的气泡与上传链路）。
    public var isPlayableMedia: Bool { isAudio || isVideo }

    /// 时长（秒）；不知道时 nil。
    public var durationSeconds: Double? {
        guard let durationMs, durationMs > 0 else { return nil }
        return Double(durationMs) / 1000
    }

    /// 有没有真的转写出文字（空白算没有）。
    public var hasTranscript: Bool {
        !(transcript ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 「试过转写但没成功」的那条附件：界面上标一句「没有转写」。
    ///
    /// 与「还没试过」（nil 且没有状态）刻意分开：只有真的发过请求、失败了的才标记，
    /// 关着转写的旧消息不会被误标成「没有转写」。
    public var transcriptIsMissing: Bool {
        isPlayableMedia && !hasTranscript && transcriptState == .failed
    }

    /// 是不是表情：显式 `kind=STICKER` 优先；没写 kind 时只有 GIF 算表情。
    public var isSticker: Bool {
        switch mediaType {
        case .sticker: return true
        case .image, .audio, .video, .avatar: return false
        case nil: return StickerLimits.isGIFMime(mime)
        }
    }

    enum CodingKeys: String, CodingKey {
        case uri, mime, width, height, kind, durationMs, serverMediaId
        case transcript, transcriptState
    }

    /// 手写解码（不用合成实现）：合成实现遇到缺字段会整条失败，
    /// 而 Android 的语义是「缺字段用默认值」。类型不对才让整段解码失败。
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uri = try container.decode(String.self, forKey: .uri)
        mime = try container.decodeIfPresent(String.self, forKey: .mime) ?? Self.defaultMime
        width = try container.decodeIfPresent(Int.self, forKey: .width) ?? 0
        height = try container.decodeIfPresent(Int.self, forKey: .height) ?? 0
        let rawKind = try container.decodeIfPresent(String.self, forKey: .kind)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        kind = (rawKind?.isEmpty == false) ? rawKind : nil
        let rawDuration = try container.decodeIfPresent(Int.self, forKey: .durationMs) ?? 0
        durationMs = rawDuration > 0 ? rawDuration : nil
        let rawServerMediaId = try container.decodeIfPresent(String.self, forKey: .serverMediaId)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        serverMediaId = (rawServerMediaId?.isEmpty == false) ? rawServerMediaId : nil
        transcript = try container.decodeIfPresent(String.self, forKey: .transcript)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // 不认识的状态值一律当「没写」：未来版本多写一个状态，不能让老版本整条附件作废。
        transcriptState = (try container.decodeIfPresent(String.self, forKey: .transcriptState))
            .flatMap(AsrTranscriptState.init(stored:))
        if transcript?.isEmpty == true { transcript = nil }
        if transcript != nil { transcriptState = .succeeded }
        if transcript == nil && transcriptState != .failed { transcriptState = nil }
    }

    /// 手写编码：与 Android 的 `encodeDefaults = false` 对齐 —— 默认值不写进 JSON。
    /// 这样同一个附件在两端写出来的字符串形状一致（也就少一次「为什么备份不一样」的排查）。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(uri, forKey: .uri)
        if mime != Self.defaultMime { try container.encode(mime, forKey: .mime) }
        if width != 0 { try container.encode(width, forKey: .width) }
        if height != 0 { try container.encode(height, forKey: .height) }
        if let kind, !kind.isEmpty { try container.encode(kind, forKey: .kind) }
        if let durationMs, durationMs > 0 { try container.encode(durationMs, forKey: .durationMs) }
        if let serverMediaId, !serverMediaId.isEmpty {
            try container.encode(serverMediaId, forKey: .serverMediaId)
        }
        // 转写：有文字才写；状态只写「失败」（成功由文字本身表达，少一个字段）。
        if let transcript, !transcript.isEmpty { try container.encode(transcript, forKey: .transcript) }
        if transcriptState == .failed { try container.encode(transcriptState, forKey: .transcriptState) }
    }
}

/// `messages.attachmentsJson` 的编解码。
///
/// 为什么不直接用 `JSONDecoder` 解 `[MessageAttachment]`：
/// Android 的 `runCatching { json.decodeFromString<List<…>>(raw) }.getOrDefault(emptyList())`
/// 会把**整段**坏数据当空；而 `JSONDecoder` 的报错粒度是「一条」，容易变成
/// 「只丢一张图、剩下的照用」——那和 Android 就不是同一套语义了。这里用
/// `JSONSerialization` 自己走一遍，逐条与 Kotlin 的行为对齐。
public enum AttachmentsCodec {

    /// 一条消息最多带几张图（Android `ChatRepository.MAX_IMAGES_PER_MESSAGE`）。
    public static let maxImagesPerMessage = 4

    /// 一条消息最多带几段语音 / 几段视频（0.13）。
    ///
    /// 为什么是 1：一段 5 分钟的语音 base64 就有 ~10 MB，两段会把一次请求推到
    /// 上下文与 body 上限附近；视频同理（8 帧 × 1024px JPEG）。产品上「一句话 = 一段语音」
    /// 也更接近真实聊天。
    public static let maxAudioPerMessage = 1
    public static let maxVideoPerMessage = 1

    /// 最近几条带图的消息会真的把图发给模型（Android `MAX_IMAGE_MESSAGES_IN_CONTEXT`）：
    /// base64 很占上下文，历史里的图不该全带上。
    public static let maxImageMessagesInContext = 2

    /// 两端认的附件 mime（`spec/audio-video/README.md`：图片 4 种、音频 3 种、视频 2 种）。
    ///
    /// 服务端 `MediaMime.ALL` 是同一组值；不在这里的格式一律拒绝（不让一个注定
    /// 415/413 的请求发出去）。
    public static let allowedMimes: Set<String> = [
        "image/jpeg", "image/png", "image/webp", "image/gif",
        "audio/m4a", "audio/mp4", "audio/mpeg",
        "video/mp4", "video/quicktime"
    ]

    /// 这个 mime 是不是两端都认的附件格式。
    public static func isAllowedMime(_ mime: String) -> Bool {
        allowedMimes.contains(mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }

    /// 按 mime 推附件种类（解码老数据 / 外部数据时用）。
    ///
    /// 与 `MediaType.inferred(fromMime:)` 同一张表：这里只多一层「泛型 image/*」的宽松
    /// （老代码依赖它把未知图片格式当 IMAGE），严格校验走 `MessageAttachment.kindCheck`。
    public static func kind(forMime mime: String) -> String? {
        switch mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "audio/m4a", "audio/mp4", "audio/mpeg": return MessageAttachment.audioKind
        case "video/mp4", "video/quicktime": return MessageAttachment.videoKind
        case "image/jpeg", "image/png", "image/webp": return MessageAttachment.imageKind
        case "image/gif": return MessageAttachment.stickerKind
        default: return nil
        }
    }

    /// 编码；空列表返回 nil（Android 也是如此：`attachmentsJson = null` 表示「没有附件」）。
    public static func encode(_ items: [MessageAttachment]) -> String? {
        guard !items.isEmpty else { return nil }
        let encoder = JSONEncoder()
        encoder.outputFormatting = []
        guard let data = try? encoder.encode(items) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 解码；任何坏数据都返回空数组，**不抛异常、不崩**。
    ///
    /// 0.15 起多一道**类型校验**（`MessageAttachment.kindCheck`）：
    /// - 显式 `kind` 不在 `IMAGE/STICKER/AUDIO/VIDEO` 里 → 整段作废；
    /// - `mime` 为空、或与 `kind` 不匹配 → 整段作废；
    /// - 没写 `kind` 的老数据按 mime 推断（`image/gif` → STICKER），认不出来也作废。
    ///
    /// 为什么仍然「整段作废」而不是只丢坏的那条：Android 的 `runCatching` 就是整段语义，
    /// 两端读同一份 `attachmentsJson` 必须得到同一个结果（否则换端后同一条消息的图片
    /// 会一边有一边没有）。
    public static func decode(_ raw: String?) -> [MessageAttachment] {
        decodeReport(raw).attachments
    }

    /// 解码 + 拒绝计数（给「有一条附件没接回来」这类诊断用；界面路径用 `decode`）。
    public struct DecodeReport: Equatable, Sendable {
        public var attachments: [MessageAttachment]
        /// 因为类型不合法被拒掉的条目数（>0 时整段为空）。
        public var rejected: Int
        /// 第一条被拒的原因（中文；排查用）。
        public var rejectionReason: String?

        public init(
            attachments: [MessageAttachment] = [],
            rejected: Int = 0,
            rejectionReason: String? = nil
        ) {
            self.attachments = attachments
            self.rejected = rejected
            self.rejectionReason = rejectionReason
        }
    }

    /// 带拒绝原因的解码。除类型校验外与 `decode` 完全一致。
    public static func decodeReport(_ raw: String?) -> DecodeReport {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let data = text.data(using: .utf8) else { return DecodeReport() }
        guard let root = try? JSONSerialization.jsonObject(with: data) else { return DecodeReport() }
        guard let array = root as? [Any] else { return DecodeReport() }

        var result: [MessageAttachment] = []
        for element in array {
            guard let object = element as? [String: Any] else { return DecodeReport(rejected: 1) }
            guard let attachment = decodeOne(object) else { return DecodeReport(rejected: 1) }
            switch attachment.kindCheck {
            case .explicit, .legacy:
                break
            case .invalid(let why):
                return DecodeReport(rejected: 1, rejectionReason: why)
            }
            result.append(attachment)
        }
        // `uri` 全是空白的条目没有意义（Android 同样过滤）。
        let filtered = result.filter {
            !$0.uri.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return DecodeReport(attachments: filtered, rejected: 0, rejectionReason: nil)
    }

    /// 解一条；`nil` = 这条是坏数据，整段作废（与 Kotlin 的整段 `runCatching` 一致）。
    private static func decodeOne(_ object: [String: Any]) -> MessageAttachment? {
        // `uri` 在 Kotlin 里是必填的：缺了或类型不对，整段就是坏数据。
        guard let uri = object["uri"] as? String else { return nil }
        var mime = MessageAttachment.defaultMime
        if let rawMime = object["mime"], !(rawMime is NSNull) {
            guard let value = rawMime as? String else { return nil }
            mime = value
        }
        guard let width = try? optionalInt(object["width"]),
              let height = try? optionalInt(object["height"]) else { return nil }
        var kind: String?
        if let rawKind = object["kind"], !(rawKind is NSNull) {
            guard let value = rawKind as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            kind = trimmed.isEmpty ? nil : trimmed
        }
        // `durationMs`：与宽度/高度同一套宽容语义（缺省/null = 不知道，类型不对 = 坏数据）。
        guard let rawDuration = try? optionalInt(object["durationMs"]) else { return nil }
        let durationMs = rawDuration > 0 ? rawDuration : nil
        // `serverMediaId`：0.13 第二阶段新增。类型不对算坏数据（与其它字段同一套语义）。
        var serverMediaId: String?
        if let raw = object["serverMediaId"], !(raw is NSNull) {
            guard let value = raw as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            serverMediaId = trimmed.isEmpty ? nil : trimmed
        }
        // `transcript` / `transcriptState`：0.15 语音转写。
        // - 文字：类型不对算坏数据（与 uri/mime 同一套语义）；
        // - 状态：不认识的值当「没写」（未来版本新增状态不该让老版本整段作废）。
        var transcript: String?
        if let raw = object["transcript"], !(raw is NSNull) {
            guard let value = raw as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            transcript = trimmed.isEmpty ? nil : trimmed
        }
        var transcriptState: AsrTranscriptState?
        if let raw = object["transcriptState"], !(raw is NSNull) {
            guard let value = raw as? String else { return nil }
            transcriptState = AsrTranscriptState(stored: value)
        }
        return MessageAttachment(
            uri: uri, mime: mime, width: width, height: height, kind: kind,
            durationMs: durationMs, serverMediaId: serverMediaId,
            transcript: transcript, transcriptState: transcriptState
        )
    }

    /// 这条消息有没有图片。
    public static func hasImages(_ raw: String?) -> Bool {
        !decode(raw).isEmpty
    }

    // MARK: 底层

    /// 取一个可缺省的整数字段：没写 / 写了 null 都当默认值 0；
    /// 写了别的类型（字符串、小数以外的怪东西）算坏数据。
    private static func optionalInt(_ raw: Any?) throws -> Int {
        guard let raw, !(raw is NSNull) else { return 0 }
        // `JSONSerialization` 把 true/false 也给成 NSNumber，所以要单独把布尔挑出去。
        guard let number = raw as? NSNumber, !(raw is Bool) else {
            throw AttachmentDecodeError.wrongType
        }
        let double = number.doubleValue
        guard double.isFinite, double >= Double(Int.min), double <= Double(Int.max) else {
            throw AttachmentDecodeError.wrongType
        }
        return Int(double)
    }

    private enum AttachmentDecodeError: Error {
        case wrongType
    }
}
