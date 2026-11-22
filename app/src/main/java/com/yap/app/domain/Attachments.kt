package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条消息里附带的媒体，`uri` 指向应用私有目录中的副本
 * （服务端模式是 `/v1/media/<id>`；本地模式是 `file://` 路径）。
 *
 * [kind] 的取值（0.12 只有图片，所以老数据解出来是 `null`，等价于「普通图片」）：
 * - `null`：普通图片（与 0.12 的 `attachmentsJson` 完全兼容）；
 * - `"IMAGE"`：显式标注的普通图片（0.13 起新写入的图片可以带上，渲染与 null 相同）；
 * - `"STICKER"`：表情包（`spec/stickers/README.md`）。它也是 `image/gif`，
 *   但渲染方式、气泡尺寸、长按菜单、上传用途（`kind=STICKER`）都不同，所以必须能区分；
 * - `"AUDIO"`：语音（`spec/audio-video/README.md`），mime 是 `audio/m4a|mp4|mpeg`；
 * - `"VIDEO"`：视频，mime 是 `video/mp4|quicktime`。
 *
 * [durationMs] 只对语音/视频有意义（毫秒）；图片与表情为 `null`。
 * 它是**媒体本身的时长**，不是消息创建时间，也不参与排序。
 *
 * [serverMediaId] 是 0.13 新增的「这份字节在服务端的 id」（`/v1/media/<id>` 里的 `<id>`）。
 * 同步把服务端媒体落到本机之后，[uri] 会改成 `file://` 本地路径，而这里保留服务端 id：
 * 回推/去重/换设备重新下载都要靠它，不能因为「本机已经有文件」就把服务端身份丢掉。
 * 本地模式的历史数据没有这个字段，解出来是 `null`（与 0.12 完全兼容）。
 *
 * 0.15 起：
 * - 新写入的附件**一律显式写 [kind]**（普通图片写 `IMAGE`，不再留 null），[mime] 必须与 kind 同族；
 *   未知 kind / mime 不匹配的附件由 [AttachmentsCodec.encode] 拒绝，不再静默当图片；
 * - [sha256] 记录这份字节的内容哈希（`/v1/media` 下载后由服务端 ETag 校验过的那份），
 *   下次同步命中本地文件时重算比对，本地副本被改坏就删掉重下。旧数据没有这个字段 → 跳过校验；
 * - [transcript] / [transcriptState]（0.15 ASR）：语音/视频转写出的文字与「有没有尝试过」。
 *   两个字段都可空，老 JSON 解出来是 null（= 从没转写过），写回时 `encodeDefaults=false` 也不会
 *   给老数据平白加字段。规则见 [AsrAttachments]：失败只能是「没有转写」，绝不用空串冒充。
 */
@Serializable
data class MessageAttachment(
    val uri: String,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val kind: String? = null,
    val durationMs: Long? = null,
    val serverMediaId: String? = null,
    /** 原始字节的 SHA-256（64 位小写 hex）；0.15 新增，可空 = 旧数据没有哈希。 */
    val sha256: String? = null,
    /**
     * 语音/视频的 ASR 转写文本（0.15）。
     *
     * null = 没有转写：可能是 0.15 之前的老数据、开关没开、还没转写，或者转写失败。
     * 想区分「没试过」与「试过但没转出来」看 [transcriptState]；界面与模型都**不能**把
     * 没有转写当成「对方什么都没说」，所以失败时这里必须是 null 而不是空串。
     */
    val transcript: String? = null,
    /** 转写尝试的结果：[AsrTranscriptState.OK] / `FAILED` / `SKIPPED`；null = 从没尝试。 */
    val transcriptState: String? = null
) {
    /**
     * 图片类 mime。
     *
     * 注意 `video/quicktime` 以 `video/` 开头，不会落进这里；这个判定在 0.12 就存在，
     * 现在多了一层保险：视频 mime 显式排除。
     */
    val isImage: Boolean get() = mime.startsWith("image/") && !isVideo

    /** 表情包：显式标了 `kind=STICKER`，或干脆是 `image/gif`（老客户端/服务端只给 mime）。 */
    val isSticker: Boolean
        get() = kind == KIND_STICKER || mime.equals(RenderMime.GIF, ignoreCase = true)

    /** 普通聊天图片：是图片且不是表情包（「图片消息」的逻辑不该把表情也当成照片）。 */
    val isChatImage: Boolean get() = isImage && !isSticker

    /** 语音：显式 `kind=AUDIO`，或 mime 本身是音频（服务端/旧客户端只给 mime 的情况）。 */
    val isAudio: Boolean
        get() = kind == KIND_AUDIO || mime.startsWith("audio/")

    /** 视频：显式 `kind=VIDEO`，或 mime 本身是视频。 */
    val isVideo: Boolean
        get() = kind == KIND_VIDEO || mime.startsWith("video/")

    /** 需要模型「听/看」的媒体（聊天图片另走视觉通道，不在此列）。 */
    val isAudioOrVideo: Boolean get() = isAudio || isVideo

    /** 这条附件对应的多模态种类；图片/表情没有媒体种类。 */
    val mediaKind: AudioVideoKind?
        get() = when {
            isAudio -> AudioVideoKind.AUDIO
            isVideo -> AudioVideoKind.VIDEO
            else -> null
        }

    /**
     * 落库/传输时用的规范 kind：**永远显式**（0.15 起）。
     *
     * 老数据 `kind=null` 等价于普通图片，写回时落成 `IMAGE`；表情/语音/视频按 mime 兜底判一次
     * （与服务端/备份的既有约定一致：`image/gif` = 表情）。
     */
    val canonicalKind: String
        get() = when {
            isSticker -> KIND_STICKER
            isAudio -> KIND_AUDIO
            isVideo -> KIND_VIDEO
            else -> KIND_IMAGE
        }

    companion object {
        // 常量只在这里定义一次：媒体类型判定（MediaTypes）依赖它们做 kind 白名单。
        const val KIND_IMAGE = MediaKinds.IMAGE
        const val KIND_STICKER = MediaKinds.STICKER
        const val KIND_AUDIO = MediaKinds.AUDIO
        const val KIND_VIDEO = MediaKinds.VIDEO
    }
}

/** 附件/表情会遇到的 MIME 常量（只收录真正被比较的那几个，避免到处写裸字符串）。 */
object RenderMime {
    const val GIF = "image/gif"
}

object AttachmentsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 写：每条附件都必须能过 [MediaTypes.normalizeForWrite]（kind 显式、mime 同族、sha256 归一）。
     *
     * 类型不合法（未知 kind / mime 与 kind 不同族 / mime 无法识别）的条目**直接丢掉**：
     * 0.15 起不许再「拿不准就当普通图片」，否则一条语音会静默变成一张加载不出来的图。
     * 全部被丢掉时返回 null（与空列表同义）。
     */
    fun encode(items: List<MessageAttachment>): String? {
        if (items.isEmpty()) return null
        val normalized = items
            .mapNotNull { MediaTypes.normalizeForWrite(it) }
            .map { AsrAttachments.normalize(it) }
        if (normalized.isEmpty()) return null
        return runCatching { json.encodeToString(normalized) }.getOrNull()
    }

    /**
     * 读：宽容但**不认识就拒绝**。
     *
     * - 老数据 `kind=null` 仍按 mime 判（`image/` 前缀 = 图片、`image/gif` = 表情、音视频按前缀）；
     * - 显式 kind 必须属于 `IMAGE/STICKER/AUDIO/VIDEO`（`AVATAR` 不是附件类型），
     *   与 mime 不同族的条目丢掉——这是「未知类型拒绝而不是猜」的读路径落点；
     * - 空 uri 的脏附件照旧丢掉；kind/mime/sha256 在这里做一次大小写/空白归一。
     */
    fun decode(raw: String?): List<MessageAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<MessageAttachment>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.uri.isNotBlank() }
            .mapNotNull { attachment ->
                val descriptor = MediaTypes.attachmentDescriptor(attachment.kind, attachment.mime)
                    ?: return@mapNotNull null
                val normalizedDuration = attachment.durationMs?.takeIf { it > 0L }
                val normalizedServerId = attachment.serverMediaId?.trim()?.takeIf { it.isNotEmpty() }
                val normalizedSha = MediaHashes.normalize(attachment.sha256)
                // 老数据（kind=null）保持 null：写回时由 encode 补显式 kind，读出来不改写历史形状。
                val normalizedKind = if (attachment.kind == null) null else descriptor.kind
                // 0.15 ASR：转写字段宽容读、非法值收敛（图片上的转写直接清掉）。
                val asrNormalized = AsrAttachments.normalize(attachment)
                if (normalizedKind == attachment.kind &&
                    descriptor.mime == attachment.mime &&
                    normalizedDuration == attachment.durationMs &&
                    normalizedServerId == attachment.serverMediaId &&
                    normalizedSha == attachment.sha256 &&
                    asrNormalized.transcript == attachment.transcript &&
                    asrNormalized.transcriptState == attachment.transcriptState
                ) {
                    attachment
                } else {
                    attachment.copy(
                        kind = normalizedKind,
                        mime = descriptor.mime,
                        durationMs = normalizedDuration,
                        serverMediaId = normalizedServerId,
                        sha256 = normalizedSha,
                        transcript = asrNormalized.transcript,
                        transcriptState = asrNormalized.transcriptState
                    )
                }
            }
    }
}
