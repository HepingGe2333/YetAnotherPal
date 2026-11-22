package com.yap.app.domain

import java.util.Locale

/**
 * 语音 / 视频转写（ASR）的**纯策略**。
 *
 * 产品定位：ASR 是「让模型读懂语音」的一条可选链路，不是主 LLM 的一部分。
 * 它与网页搜索同一类配置（provider 地址 + 模型 + 独立 Key），默认关；打开之后，
 * 用户发语音/视频时先转成文字，再把「他说：…」注入上下文。
 *
 * 三条硬规则（写在这里，让界面与聊天层共用同一份）：
 * 1. **失败不阻断发送**：转写失败、没配置、Key 解不开，语音照发，只是标记「没有转写」；
 * 2. **不伪造**：没有转写就只能是 null，模型看到的也是「没有转写」，绝不允许补一句猜的；
 * 3. **独立 Key**：只读 [com.yap.app.data.prefs.AsrSettingsRepository] 里那份加密 Key，
 *    不回退到主对话配置的 API Key（两者计费、权限、可撤销性都不同）。
 *
 * 本文件不碰 Android / 网络：默认值、URL 拼接、错误分类、上下文文案、测试音频全部是纯函数，
 * 这样 JVM 单测能钉住「base 带不带 /v1」「多大算超限」「失败怎么标记」这些边界。
 */
object AsrDefaults {

    /** 默认地址：DeepSeek 官方（OpenAI 兼容的 `/audio/transcriptions` 形状）。 */
    const val BASE_URL = "https://api.deepseek.com"

    /** 默认模型：OpenAI 兼容的转写模型名。用户可改，空值回落成这个。 */
    const val MODEL = "whisper-1"

    /** 转写语言。语音大多是中文；先固定 zh，留参数是为了将来能按界面语言给值。 */
    const val LANGUAGE = "zh"

    /** 单次转写的总超时：60 秒。 */
    const val TIMEOUT_SECONDS = 60L

    /** 端点后缀（相对 base）。 */
    const val ENDPOINT_SUFFIX = "/audio/transcriptions"

    /** 转写文本入库上限：5 分钟语音也到不了这个量级，超出的部分只是把上下文撑大。 */
    const val MAX_TRANSCRIPT_CHARS = 4_000

    /** baseUrl 输入框的长度上限（防止把一整段 JSON 粘进来）。 */
    const val MAX_BASE_URL_CHARS = 500

    /** 模型名长度上限（与 API 配置的 `MODEL_ID_TOO_LONG` 同量级）。 */
    const val MAX_MODEL_CHARS = 100

    /** 响应体上限：转写结果是纯文本，1 MiB 足够，超出视为坏响应。 */
    const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024

    /** 错误响应只留这么多字符进日志（且会先把 Key 抹掉）。 */
    const val MAX_LOGGED_BODY_CHARS = 200

    /** 测试按钮发出的探测音频时长：1 秒足够验证地址/鉴权，又不会等太久。 */
    const val PROBE_SECONDS = 1
}

/**
 * ASR 地址的规范化与端点拼接。
 *
 * 规则与 `NetworkGuard.normalizeBaseUrl` 保持同一种口味（用户经常直接把 LLM 配置里的
 * base 复制过来，可能带着 `/v1`、`/chat/completions` 甚至完整 endpoint），但这里**额外**
 * 允许完整 `/audio/transcriptions`：粘完整地址也应当能用。
 */
object AsrUrls {

    /** 用户可能粘进来的「非 base 尾巴」；从右往左剥，最后重新拼我们的后缀。 */
    private val KNOWN_SUFFIXES = listOf(
        "/chat/completions",
        "/models",
        AsrDefaults.ENDPOINT_SUFFIX
    )

    /**
     * 归一 base：去空白、去尾斜杠、剥掉已知 endpoint 后缀；空值回落默认地址。
     *
     * `https://api.openai.com/v1` 会原样保留 `/v1`（OpenAI 的标准路径就是 `/v1/audio/transcriptions`），
     * 而默认的 `https://api.deepseek.com` 不加 `/v1`，与需求里的 `{base}/audio/transcriptions` 一致。
     */
    fun normalizeBaseUrl(raw: String?): String {
        var base = raw?.trim().orEmpty().take(AsrDefaults.MAX_BASE_URL_CHARS)
        if (base.isEmpty()) return AsrDefaults.BASE_URL
        base = base.trimEnd('/')
        var changed = true
        while (changed) {
            changed = false
            KNOWN_SUFFIXES.forEach { suffix ->
                if (base.endsWith(suffix, ignoreCase = true)) {
                    base = base.dropLast(suffix.length).trimEnd('/')
                    changed = true
                }
            }
        }
        return base.ifEmpty { AsrDefaults.BASE_URL }
    }

    /** 最终请求地址：`{base}/audio/transcriptions`。 */
    fun endpoint(raw: String?): String = normalizeBaseUrl(raw) + AsrDefaults.ENDPOINT_SUFFIX
}

/** 模型名归一：去空白、截断、空值回落默认。 */
object AsrModels {
    fun normalize(raw: String?): String {
        val value = raw?.trim().orEmpty().take(AsrDefaults.MAX_MODEL_CHARS)
        return value.ifEmpty { AsrDefaults.MODEL }
    }
}

/**
 * 转写失败 / 跳过的**结构化原因**。
 *
 * 与 `NetworkErrorKind` 分开：那是主链路（模型请求）的跨端契约，ASR 是 0.15 新增的独立能力，
 * 不该为了它去改 `agentcore` 的公共枚举。界面按这个枚举映射 `R.string.*`，数据层只说原因。
 */
enum class AsrFailure {
    /** 开关关着。 */
    DISABLED,

    /** 没有配 Key。 */
    KEY_MISSING,

    /** 密文在，但这台设备的 Keystore 解不开（换机/恢复备份）。 */
    KEY_UNRECOVERABLE,

    /** 这条附件不是语音/视频。 */
    NOT_MEDIA,

    /** 读不到媒体字节（文件丢了、服务端媒体下载失败）。 */
    UNREADABLE_MEDIA,

    /** 字节为空。 */
    EMPTY_MEDIA,

    /** 超过音频 8 MiB / 视频 64 MiB 的上限（与发送上限同一套值）。 */
    TOO_LARGE,

    /** mime 不在可转写的白名单里。 */
    UNSUPPORTED_FORMAT,

    /** baseUrl 不合法（含 release 包拒绝明文 HTTP）。 */
    INVALID_URL,

    /** 60 秒内没有响应。 */
    TIMEOUT,

    /** 连不上 / 连接中断。 */
    NETWORK,

    /** 401 / 403：Key 不对或没有权限。 */
    UNAUTHORIZED,

    /** 429：限流。 */
    RATE_LIMITED,

    /** 其它 4xx：请求形状/模型名/音频格式不对。 */
    BAD_REQUEST,

    /** 5xx：服务端出错。 */
    SERVER_ERROR,

    /** 响应体超过 1 MiB。 */
    RESPONSE_TOO_LARGE,

    /** 返回的不是能解析出 text 的 JSON。 */
    UNPARSABLE,

    /** 解析成功但 text 为空（静音、或模型没识别出内容）。 */
    EMPTY_TRANSCRIPT
}

/** 一次转写的结果。[Failed.detail] 只给日志/调试看，界面一律按 [reason] 映射资源。 */
sealed interface AsrOutcome {
    /** 成功：拿到了非空转写文本。 */
    data class Transcribed(val text: String) : AsrOutcome

    /** 没发出请求（关着 / 没 Key / 太大 / 格式不支持）：不算失败，也不该报警。 */
    data class Skipped(val reason: AsrFailure) : AsrOutcome

    /** 真的失败了（网络、鉴权、超时、坏响应）。 */
    data class Failed(
        val reason: AsrFailure,
        val httpStatus: Int? = null,
        val detail: String? = null
    ) : AsrOutcome
}

/** 成功时拿到的那段文本；跳过/失败一律 null（**绝不返回空串伪装成转写**）。 */
val AsrOutcome.transcriptOrNull: String?
    get() = (this as? AsrOutcome.Transcribed)?.text

/** 这条附件在库里的转写状态。null = 从来没有尝试过（0.15 之前的老数据）。 */
object AsrTranscriptState {
    /** 转写成功（`transcript` 一定有值）。 */
    const val OK = "OK"

    /** 尝试过但失败/没转出文字（`transcript` 为 null）。 */
    const val FAILED = "FAILED"

    /** 没尝试（开关关着 / 没 Key / 超限）：`transcript` 为 null。 */
    const val SKIPPED = "SKIPPED"

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        return value.takeIf { it == OK || it == FAILED || it == SKIPPED }
    }

    fun isKnown(raw: String?): Boolean = normalize(raw) != null

    /** 尝试过（无论成功失败）；界面据此区分「没试过」和「没转出来」。 */
    fun wasAttempted(raw: String?): Boolean = normalize(raw) != null
}

/**
 * 附件上的转写字段的读写规则。
 *
 * - `transcript`：trim + 截断 [AsrDefaults.MAX_TRANSCRIPT_CHARS]；空串视为 null；
 * - `transcriptState`：只认三个常量；有文本就是 [AsrTranscriptState.OK]，
 *   没文本却写着 OK 属于坏数据，降级成 FAILED（而不是假装转写过）；
 * - 图片/表情不承载转写：读历史时把这两个字段清掉（老数据本来也没有）。
 */
object AsrAttachments {

    /** 把一次转写结果写回附件；失败保留原附件、只改状态字段。 */
    fun apply(attachment: MessageAttachment, outcome: AsrOutcome): MessageAttachment {
        if (!attachment.isAudioOrVideo) return attachment
        return when (outcome) {
            is AsrOutcome.Transcribed -> {
                val text = normalizeText(outcome.text)
                if (text == null) {
                    attachment.copy(transcript = null, transcriptState = AsrTranscriptState.FAILED)
                } else {
                    attachment.copy(transcript = text, transcriptState = AsrTranscriptState.OK)
                }
            }
            is AsrOutcome.Skipped -> {
                // 之前已经转写过就保留：关掉开关/换设备不等于「把历史里的转写抹掉」。
                if (asrTranscriptOrNull(attachment) != null) {
                    attachment
                } else {
                    attachment.copy(transcript = null, transcriptState = AsrTranscriptState.SKIPPED)
                }
            }
            is AsrOutcome.Failed -> attachment.copy(
                transcript = null,
                transcriptState = AsrTranscriptState.FAILED
            )
        }
    }

    /** 转写文本归一；空串/纯空白返回 null。 */
    fun normalizeText(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.length <= AsrDefaults.MAX_TRANSCRIPT_CHARS) {
            value
        } else {
            value.take(AsrDefaults.MAX_TRANSCRIPT_CHARS)
        }
    }

    /** 这条附件的有效转写文本（图片/表情一律 null）。 */
    fun asrTranscriptOrNull(attachment: MessageAttachment): String? =
        if (attachment.isAudioOrVideo) normalizeText(attachment.transcript) else null

    /**
     * 读/写共用的归一：不丢字段，只把非法/不一致的转写字段收敛。
     *
     * 与 [MediaTypes.normalizeForWrite] 分工：那边管 kind/mime/sha256，这里管 ASR 两个字段。
     */
    fun normalize(attachment: MessageAttachment): MessageAttachment {
        if (!attachment.isAudioOrVideo) {
            if (attachment.transcript == null && attachment.transcriptState == null) return attachment
            return attachment.copy(transcript = null, transcriptState = null)
        }
        val text = normalizeText(attachment.transcript)
        val state = when {
            text != null -> AsrTranscriptState.OK
            attachment.transcriptState == AsrTranscriptState.OK -> AsrTranscriptState.FAILED
            else -> AsrTranscriptState.normalize(attachment.transcriptState)
        }
        if (text == attachment.transcript && state == attachment.transcriptState) return attachment
        return attachment.copy(transcript = text, transcriptState = state)
    }
}

/**
 * 送进模型上下文的转写文案。
 *
 * 需求里的「他说：…」就出现在这里：语音/视频的占位正文后面补一句，让模型知道
 * 这是**对方说的话**，而不是系统的旁白。没有转写时明确写「没有转写」，
 * 让模型自己选择「请对方重发/打字」，而不是让它以为听到了内容。
 */
object AsrContext {

    /** 转写句子的前缀（模型侧协议的一部分，不翻译）。 */
    const val TRANSCRIPT_PREFIX = "他说："

    /** 没有转写时的明确标记（模型侧协议的一部分，不翻译）。 */
    const val NO_TRANSCRIPT = "没有转写"

    /**
     * 一句转写说明：
     * - 有转写 → `他说：…`；
     * - 尝试过但失败/跳过 → `没有转写`；
     * - 从没尝试（老数据/开关从没开过）→ null，调用方保持原占位正文不变。
     */
    fun sentence(attachment: MessageAttachment): String? {
        val text = AsrAttachments.asrTranscriptOrNull(attachment) ?: return when {
            attachment.transcriptState == AsrTranscriptState.FAILED ||
                attachment.transcriptState == AsrTranscriptState.SKIPPED -> NO_TRANSCRIPT
            else -> null
        }
        return TRANSCRIPT_PREFIX + text
    }

    /**
     * 把转写补进一条媒体的占位正文。
     *
     * 例：`（对方发来一段 3 秒的语音）（他说：今天有点累）`。
     * 没有转写也没有尝试过时原样返回 [base]。
     */
    fun annotated(base: String, attachment: MessageAttachment): String {
        val sentence = sentence(attachment) ?: return base
        return if (base.isBlank()) "（$sentence）" else "$base（$sentence）"
    }

    /** 当前轮媒体块里追加在 mediaId 之后的一小段（不含句号/括号，调用方自己拼）。 */
    fun inlineSuffix(attachment: MessageAttachment): String? = sentence(attachment)
}

/**
 * 测试按钮发出的探测音频：1 秒 440 Hz 低音量正弦波、16 kHz 单声道 PCM16 WAV。
 *
 * 为什么不是静音：部分后端会把全零音频判成坏文件；一段真实波形至少能走到「解码 → 转写」，
 * 从而验证地址、Key、模型名三件事。探测音频**不写盘、不进库**，只活一次请求。
 */
object AsrProbeAudio {

    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val FREQUENCY_HZ = 440.0
    /** 振幅只取满量程的 ~3%，听不见刺耳，又远大于噪声门限。 */
    private const val AMPLITUDE = 1_000.0

    /** 生成 WAV 字节；返回的是完整文件（RIFF 头 + PCM 数据）。 */
    fun wavBytes(
        seconds: Int = AsrDefaults.PROBE_SECONDS,
        sampleRate: Int = SAMPLE_RATE
    ): ByteArray {
        val safeSeconds = seconds.coerceIn(1, 5)
        val sampleCount = sampleRate * safeSeconds
        val dataBytes = sampleCount * CHANNELS * (BITS_PER_SAMPLE / 8)
        val totalBytes = 44 + dataBytes
        val out = ByteArray(totalBytes)
        var offset = 0
        fun writeAscii(value: String) {
            value.forEach { char -> out[offset++] = char.code.toByte() }
        }
        fun writeIntLE(value: Int) {
            out[offset++] = (value and 0xFF).toByte()
            out[offset++] = ((value ushr 8) and 0xFF).toByte()
            out[offset++] = ((value ushr 16) and 0xFF).toByte()
            out[offset++] = ((value ushr 24) and 0xFF).toByte()
        }
        fun writeShortLE(value: Int) {
            out[offset++] = (value and 0xFF).toByte()
            out[offset++] = ((value ushr 8) and 0xFF).toByte()
        }

        writeAscii("RIFF")
        writeIntLE(totalBytes - 8)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1) // PCM
        writeShortLE(CHANNELS)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8))
        writeShortLE(CHANNELS * (BITS_PER_SAMPLE / 8))
        writeShortLE(BITS_PER_SAMPLE)
        writeAscii("data")
        writeIntLE(dataBytes)
        for (index in 0 until sampleCount) {
            val angle = 2.0 * Math.PI * FREQUENCY_HZ * index / sampleRate
            val sample = (Math.sin(angle) * AMPLITUDE).toInt()
            writeShortLE(sample and 0xFFFF)
        }
        return out
    }
}

/** 设置页要显示的 ASR 配置状态（**不含明文 Key**，只有「有没有」与脱敏串）。 */
data class AsrSettingsState(
    val enabled: Boolean = false,
    val baseUrl: String = AsrDefaults.BASE_URL,
    val model: String = AsrDefaults.MODEL,
    /** 有可用 Key（能解密出非空明文）。 */
    val hasKey: Boolean = false,
    /** 界面展示用：`••••1234`；没配为空串。 */
    val maskedKey: String = "",
    /** 密文在但解不开：界面提示「重新填一次」。 */
    val keyUnrecoverable: Boolean = false
)

/** 一次 ASR 调用实际要用的配置（**含明文 Key**：只在内存里活一次请求，不进 toString）。 */
class ResolvedAsrConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String
) {
    override fun toString(): String =
        "ResolvedAsrConfig(baseUrl=$baseUrl, model=$model, hasKey=${apiKey.isNotBlank()})"
}

/** ASR 现在能不能调用；不能时给出一个人话原因。 */
sealed interface AsrAvailability {
    data class Ready(val config: ResolvedAsrConfig) : AsrAvailability
    data object Disabled : AsrAvailability
    data object KeyMissing : AsrAvailability
    data object KeyUnrecoverable : AsrAvailability
}
