package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `MessageAttachment.transcript` 的兼容性与归一规则（0.15 ASR）。
 *
 * 关键约束：
 * - 0.15 之前的老 `attachmentsJson` 里没有 `transcript` / `transcriptState`，必须照常解出来；
 * - 没有转写时**不要**往 JSON 里平白加字段（`encodeDefaults=false` + null 值不写）；
 * - 「有文本」与「状态 OK」必须一致：有文本强制 OK，没文本却写 OK 降级成 FAILED；
 * - 图片/表情不承载转写。
 */
class AsrAttachmentTest {

    private fun audio(
        transcript: String? = null,
        state: String? = null,
        uri: String = "file:///tmp/voice.m4a"
    ) = MessageAttachment(
        uri = uri,
        mime = "audio/m4a",
        kind = MessageAttachment.KIND_AUDIO,
        durationMs = 3_000L,
        transcript = transcript,
        transcriptState = state
    )

    // ---------------------------------------------------------------- 老 JSON 兼容

    @Test
    fun `0_15 之前的老 attachmentsJson 照常解码，转写字段为 null`() {
        val oldJson = """[{"uri":"file:///tmp/v.m4a","mime":"audio/m4a","kind":"AUDIO","durationMs":3000}]"""

        val attachment = AttachmentsCodec.decode(oldJson).single()

        assertEquals("file:///tmp/v.m4a", attachment.uri)
        assertEquals(MessageAttachment.KIND_AUDIO, attachment.kind)
        assertNull(attachment.transcript)
        assertNull(attachment.transcriptState)
    }

    @Test
    fun `没有转写时写回 JSON 不会平白多出 transcript 字段`() {
        val old = AttachmentsCodec.decode(
            """[{"uri":"file:///tmp/v.m4a","mime":"audio/m4a","kind":"AUDIO","durationMs":3000}]"""
        ).single()

        val encoded = AttachmentsCodec.encode(listOf(old)).orEmpty()

        assertFalse("老数据不应因为重新落库而多两个字段：$encoded", encoded.contains("transcript"))
    }

    @Test
    fun `带转写的附件能读出来，写回仍然保留`() {
        val json = """[{"uri":"file:///tmp/v.m4a","mime":"audio/m4a","kind":"AUDIO","durationMs":3000,"transcript":"今天有点累","transcriptState":"OK"}]"""

        val decoded = AttachmentsCodec.decode(json).single()
        assertEquals("今天有点累", decoded.transcript)
        assertEquals(AsrTranscriptState.OK, decoded.transcriptState)

        val encoded = AttachmentsCodec.encode(listOf(decoded)).orEmpty()
        assertTrue(encoded.contains("\"transcript\":\"今天有点累\""))
        assertTrue(encoded.contains("\"transcriptState\":\"OK\""))
    }

    // ---------------------------------------------------------------- 归一

    @Test
    fun `转写文本 trim 与截断，空白视为没有`() {
        val trimmed = AsrAttachments.normalize(audio(transcript = "  今天有点累  ", state = "ok"))
        assertEquals("今天有点累", trimmed.transcript)
        assertEquals(AsrTranscriptState.OK, trimmed.transcriptState)

        val long = "字".repeat(AsrDefaults.MAX_TRANSCRIPT_CHARS + 50)
        val capped = AsrAttachments.normalize(audio(transcript = long, state = AsrTranscriptState.OK))
        assertEquals(AsrDefaults.MAX_TRANSCRIPT_CHARS, capped.transcript?.length)

        val blank = AsrAttachments.normalize(audio(transcript = "   ", state = AsrTranscriptState.OK))
        assertNull(blank.transcript)
        assertEquals("状态 OK 但没有文本必须降级为 FAILED", AsrTranscriptState.FAILED, blank.transcriptState)
    }

    @Test
    fun `未知状态被清掉，有文本没状态补 OK`() {
        val unknown = AsrAttachments.normalize(audio(transcript = null, state = "MAYBE"))
        assertNull(unknown.transcriptState)

        val textOnly = AsrAttachments.normalize(audio(transcript = "你好", state = null))
        assertEquals(AsrTranscriptState.OK, textOnly.transcriptState)
    }

    @Test
    fun `图片上的转写被清掉`() {
        val image = MessageAttachment(
            uri = "file:///tmp/a.jpg",
            mime = "image/jpeg",
            kind = MessageAttachment.KIND_IMAGE,
            transcript = "不该出现在图片上",
            transcriptState = AsrTranscriptState.OK
        )

        val normalized = AsrAttachments.normalize(image)

        assertNull(normalized.transcript)
        assertNull(normalized.transcriptState)
    }

    // ---------------------------------------------------------------- 结果写回

    @Test
    fun `成功写 OK，失败写 FAILED，跳过写 SKIPPED，且都不伪造文本`() {
        val success = AsrAttachments.apply(audio(), AsrOutcome.Transcribed("  今天有点累  "))
        assertEquals("今天有点累", success.transcript)
        assertEquals(AsrTranscriptState.OK, success.transcriptState)

        val emptySuccess = AsrAttachments.apply(audio(), AsrOutcome.Transcribed("   "))
        assertNull(emptySuccess.transcript)
        assertEquals(AsrTranscriptState.FAILED, emptySuccess.transcriptState)

        val failed = AsrAttachments.apply(audio(), AsrOutcome.Failed(AsrFailure.SERVER_ERROR, httpStatus = 500))
        assertNull(failed.transcript)
        assertEquals(AsrTranscriptState.FAILED, failed.transcriptState)
        assertEquals("file:///tmp/voice.m4a", failed.uri)

        val skipped = AsrAttachments.apply(audio(), AsrOutcome.Skipped(AsrFailure.DISABLED))
        assertNull(skipped.transcript)
        assertEquals(AsrTranscriptState.SKIPPED, skipped.transcriptState)
    }

    @Test
    fun `跳过早先转写过的附件时保留旧文本`() {
        val existing = audio(transcript = "之前的内容", state = AsrTranscriptState.OK)
        val result = AsrAttachments.apply(existing, AsrOutcome.Skipped(AsrFailure.KEY_MISSING))
        assertEquals("之前的内容", result.transcript)
        assertEquals(AsrTranscriptState.OK, result.transcriptState)
    }

    @Test
    fun `状态工具函数只认三个常量`() {
        assertEquals(AsrTranscriptState.OK, AsrTranscriptState.normalize(" ok "))
        assertEquals(AsrTranscriptState.FAILED, AsrTranscriptState.normalize("FAILED"))
        assertEquals(AsrTranscriptState.SKIPPED, AsrTranscriptState.normalize("skipped"))
        assertNull(AsrTranscriptState.normalize("done"))
        assertNull(AsrTranscriptState.normalize(null))
        assertTrue(AsrTranscriptState.wasAttempted(AsrTranscriptState.FAILED))
        assertFalse(AsrTranscriptState.wasAttempted(null))
    }

    // ---------------------------------------------------------------- 探测音频

    @Test
    fun `探测音频是合法的 1 秒 16kHz 单声道 PCM16 WAV`() {
        val wav = AsrProbeAudio.wavBytes(seconds = 1)
        assertEquals(44 + 16_000 * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(16_000, header.getInt(24)) // sample rate
        assertEquals(16_000 * 2, header.getInt(28)) // byte rate
        assertEquals(2, header.getShort(32).toInt()) // block align
        assertEquals(16, header.getShort(34).toInt()) // bits per sample
        assertEquals(16_000 * 2, header.getInt(40))
    }
}
