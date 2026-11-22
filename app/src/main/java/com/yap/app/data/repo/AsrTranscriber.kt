package com.yap.app.data.repo

import com.yap.app.domain.AsrAttachments
import com.yap.app.domain.AsrAvailability
import com.yap.app.domain.AsrDefaults
import com.yap.app.domain.AsrFailure
import com.yap.app.domain.AsrOutcome
import com.yap.app.domain.AsrProbeAudio
import com.yap.app.domain.AsrTranscriptState
import com.yap.app.domain.AudioVideoKind
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.ResolvedAsrConfig
import com.yap.app.network.AsrClient
import com.yap.app.network.AsrRequest
import javax.inject.Inject
import javax.inject.Singleton

/** 「现在能不能转写」的来源（生产实现是 [com.yap.app.data.prefs.AsrSettingsRepository]）。 */
interface AsrConfigSource {
    suspend fun availability(): AsrAvailability
}

/**
 * 读一条附件的原始字节。
 *
 * 生产实现转给 [AudioVideoRepository.readBytes]：它已经处理「本地文件 / 服务端媒体下载」
 * 与 8 MiB / 64 MiB 的大小守卫。抽成接口只为了纯 JVM 单测能塞假字节。
 */
fun interface AsrMediaSource {
    suspend fun read(attachment: MessageAttachment): ByteArray?
}

/**
 * 发送链路的语音/视频转写服务（0.15）。
 *
 * ## 它做什么
 *
 * 1. 读 ASR 配置：关着 / 没 Key / Key 解不开 → [AsrOutcome.Skipped]，**不读文件、不发请求**；
 * 2. 读媒体字节（失败 → [AsrOutcome.Failed]，标记「没有转写」）；
 * 3. 调 [AsrClient]（OpenAI 兼容 `POST {base}/audio/transcriptions`，60 秒超时）；
 * 4. [enrich] 把结果写回附件：成功写 `transcript` + `transcriptState=OK`；
 *    失败写 null + `FAILED`；跳过写 null + `SKIPPED`。**绝不伪造文本**。
 *
 * ## 聊天层接入点（0.15，尚未接线，留给协调者合并）
 *
 * - **发送前**（[com.yap.app.ui.screens.chat.ChatViewModel] 的 `sendMedia`/`send`：
 *   在落库/上传**之前**，对每个附件调用 `asrTranscriber.enrich(attachment)`。
 *   `enrich` 永不抛异常，失败只是多两个字段，所以不需要 try/catch，也不该阻断发送；
 * - **上下文**（[ChatRepository] 的 `buildContext`）：
 *   - 历史/当轮占位正文：把 `AudioVideoText.audioPlaceholder(...)` / `videoPlaceholder(...)`
 *     换成 `AsrContext.annotated(base, attachment)`，模型就能看到「他说：…」/「没有转写」；
 *   - 当前轮媒体块（`AudioVideoText.currentBlock`）：每行 mediaId 之后可追加
 *     `AsrContext.sentence(attachment)`；
 *   - **原 `input_audio` / 视频帧仍然保留**（转写只是补充，不替代「真的听/看」）。
 *
 * 这样做的原因：ASR 是**增强**，任何一步失败都只能降级成「这条语音没有文字」，
 * 不能把用户已经录好的语音挡在发送之外。
 */
@Singleton
class AsrTranscriber @Inject constructor(
    private val configs: AsrConfigSource,
    private val media: AsrMediaSource,
    private val client: AsrClient
) {

    /** 转写一条语音/视频；结果可能是成功、跳过或失败，**从不抛异常**（取消除外）。 */
    suspend fun transcribe(attachment: MessageAttachment): AsrOutcome {
        if (!attachment.isAudioOrVideo) return AsrOutcome.Skipped(AsrFailure.NOT_MEDIA)
        val availability = configs.availability()
        val config = when (availability) {
            is AsrAvailability.Ready -> availability.config
            AsrAvailability.Disabled -> return AsrOutcome.Skipped(AsrFailure.DISABLED)
            AsrAvailability.KeyMissing -> return AsrOutcome.Skipped(AsrFailure.KEY_MISSING)
            AsrAvailability.KeyUnrecoverable -> return AsrOutcome.Skipped(AsrFailure.KEY_UNRECOVERABLE)
        }
        val kind = attachment.mediaKind ?: return AsrOutcome.Skipped(AsrFailure.NOT_MEDIA)
        val bytes = media.read(attachment) ?: return AsrOutcome.Failed(AsrFailure.UNREADABLE_MEDIA)
        return client.transcribe(
            AsrRequest(
                baseUrl = config.baseUrl,
                model = config.model,
                apiKey = config.apiKey,
                bytes = bytes,
                kind = kind,
                mime = attachment.mime
            )
        )
    }

    /**
     * 转写并把结果写回附件（发送链路唯一需要调用的一行）。
     *
     * 成功时保留原 `uri`/`mime`/`kind`/`durationMs`，只多两个转写字段；
     * 失败/跳过时附件**原样可发送**，只是 `transcript` 为 null。
     */
    suspend fun enrich(attachment: MessageAttachment): MessageAttachment {
        val outcome = transcribe(attachment)
        return AsrAttachments.apply(attachment, outcome)
    }

    /**
     * 设置页「测试」：用当前输入（还没保存也行）发 1 秒探测音频，验证地址 / Key / 模型名。
     *
     * 探测音频是内存里生成的 WAV（[AsrProbeAudio]），不写盘、不进库、不落日志。
     * 返回里若 `text` 为空是正常的（测试音频是 440 Hz 单音），界面按「接口可达」展示。
     */
    suspend fun probe(config: ResolvedAsrConfig): AsrOutcome = client.transcribe(
        AsrRequest(
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = config.apiKey,
            bytes = AsrProbeAudio.wavBytes(AsrDefaults.PROBE_SECONDS),
            kind = AudioVideoKind.AUDIO,
            mime = PROBE_MIME
        )
    )

    internal companion object {
        /** 探测音频的 mime：OpenAI 兼容的转写接口都收 wav。 */
        const val PROBE_MIME = "audio/wav"

        /** 这个状态表示「试过但没有文字」；界面据此显示「没有转写」。 */
        fun isMissingTranscript(attachment: MessageAttachment): Boolean =
            attachment.transcript.isNullOrBlank() && AsrTranscriptState.wasAttempted(attachment.transcriptState)
    }
}
