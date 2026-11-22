import Foundation
import XCTest
@testable import YapKit

/// 转写进**上下文**的那几行字（0.15 `AsrTranscriptContext`）。
///
/// 三条要钉住的事：
/// 1. 措辞就是「他说：…」——模型看到的是「对方说过的原话」，不是「我听到了」；
/// 2. 没有转写时，提示词与 0.13/0.14 **逐字相同**（老对话不受影响）；
/// 3. 失败时明确写「（这段语音没有转写）」——不伪造，也不让模型以为自己在猜。
final class AsrTranscriptContextTests: XCTestCase {

    private func audio(transcript: String? = nil) -> MessageAttachment {
        let base = MessageAttachment.audio(uri: "file:///audio/audio-abc.m4a", durationMs: 3_200)
        guard let transcript else { return base }
        return base.withTranscript(transcript)
    }

    private func video(transcript: String? = nil) -> MessageAttachment {
        let base = MessageAttachment.video(uri: "file:///video/video-abc.mp4", durationMs: 12_000)
        guard let transcript else { return base }
        return base.withTranscript(transcript)
    }

    // MARK: - 「他说：…」

    func testTranscriptLineUsesHeSaidPrefix() {
        let attachment = audio(transcript: "  今天有点累  ")
        XCTAssertEqual(AsrTranscriptContext.line(for: attachment), "他说：今天有点累")
        XCTAssertEqual(AsrTranscriptContext.transcriptText(for: attachment), "今天有点累")
    }

    func testNoTranscriptMeansNoLine() {
        XCTAssertEqual(AsrTranscriptContext.line(for: audio()), "")
        XCTAssertNil(AsrTranscriptContext.transcriptText(for: audio()))
        // 只有空白也算没有。
        let blank = MessageAttachment(
            uri: "file:///audio/a.m4a", kind: MessageAttachment.audioKind,
            durationMs: 1_000, transcript: "   "
        )
        XCTAssertEqual(AsrTranscriptContext.line(for: blank), "")
    }

    /// 视频与语音用同一句「他说」：容器信息在媒体那一行里，不需要在这行区分。
    func testVideoTranscriptUsesSameLine() {
        XCTAssertEqual(AsrTranscriptContext.line(for: video(transcript: "我在楼下")), "他说：我在楼下")
    }

    /// 超长转写要截断（历史里几条 5 分钟语音的转写能把上下文预算吃光）。
    func testLongTranscriptIsClipped() {
        let long = String(repeating: "字", count: AsrTranscriptContext.maxCharacters + 50)
        let text = AsrTranscriptContext.transcriptText(for: audio(transcript: long))
        XCTAssertEqual(text?.count, AsrTranscriptContext.maxCharacters + 1, "截断后带一个省略号")
        XCTAssertTrue(text?.hasSuffix("…") == true)
        XCTAssertEqual(AsrTranscriptContext.maxCharacters, 2_000)
    }

    // MARK: - 失败标记

    func testFailedTranscriptionIsMarkedAsMissing() {
        let failed = audio().markedTranscriptFailure()
        XCTAssertTrue(failed.transcriptIsMissing)
        XCTAssertEqual(AsrTranscriptContext.missingLine(for: failed), "（这段语音没有转写）")
        XCTAssertEqual(AsrTranscriptContext.missingLine(for: video().markedTranscriptFailure()), "（这段视频没有转写）")

        // 没试过（关着转写 / 老数据）不该被标成「没有转写」。
        XCTAssertEqual(AsrTranscriptContext.missingLine(for: audio()), "")
    }

    // MARK: - mediaLine

    /// 没有转写时：与 0.13/0.14 的 `mediaContextLine` 逐字相同（这就是「老对话不受影响」）。
    func testMediaLineEqualsLegacyLineWithoutTranscript() {
        let attachment = audio()
        XCTAssertEqual(AsrTranscriptContext.mediaLine(for: attachment), attachment.mediaDescriptionLine)
        XCTAssertEqual(attachment.mediaContextLine, attachment.mediaDescriptionLine)
        XCTAssertTrue(attachment.mediaContextLine.contains("listen"), attachment.mediaContextLine)
    }

    /// 有转写时：媒体那一行仍在（带 listen 提示），后面多一行「他说：…」。
    func testMediaLineAppendsTranscriptLine() {
        let attachment = audio(transcript: "今天有点累")
        let line = AsrTranscriptContext.mediaLine(for: attachment)
        let parts = line.components(separatedBy: "\n")
        XCTAssertEqual(parts.count, 2)
        XCTAssertTrue(parts[0].contains("一段语音"), parts[0])
        XCTAssertTrue(parts[0].contains("listen"), parts[0])
        XCTAssertEqual(parts[1], "他说：今天有点累")
        // 附件自己的 `mediaContextLine` 就是这一份（`PromptBuilder.history` 读的是它）。
        XCTAssertEqual(attachment.mediaContextLine, line)
    }

    func testMediaLineAppendsMissingMarkerAfterFailure() {
        let attachment = audio().markedTranscriptFailure()
        let parts = AsrTranscriptContext.mediaLine(for: attachment).components(separatedBy: "\n")
        XCTAssertEqual(parts.count, 2)
        XCTAssertEqual(parts[1], "（这段语音没有转写）")
        XCTAssertFalse(parts.contains("他说："), "失败时不能出现「他说」")
    }

    func testImageHasNoMediaLine() {
        let image = MessageAttachment.image(uri: "file:///a.jpg")
        XCTAssertEqual(AsrTranscriptContext.mediaLine(for: image), "")
        XCTAssertEqual(AsrTranscriptContext.line(for: image), "")
    }

    // MARK: - appending

    func testAppendingToTextKeepsOriginalTextAndAddsLines() {
        let attachments = [audio(transcript: "先这样"), video().markedTranscriptFailure()]
        let text = AsrTranscriptContext.appending(to: "看这个", attachments: attachments)
        XCTAssertEqual(text, "看这个\n他说：先这样\n（这段视频没有转写）")

        // 正文为空：只留那几行。
        XCTAssertEqual(
            AsrTranscriptContext.appending(to: "   ", attachments: attachments),
            "他说：先这样\n（这段视频没有转写）"
        )
        // 没有可加的内容：逐字返回原文。
        XCTAssertEqual(AsrTranscriptContext.appending(to: "看这个", attachments: [audio(), MessageAttachment.image(uri: "file:///a.jpg")]), "看这个")
    }

    // MARK: - PromptBuilder 的历史

    private func message(
        id: String,
        attachments: [MessageAttachment],
        content: String = "",
        role: MessageRole = .user,
        at: Int64 = 1_700_000_000_000
    ) -> MessageEntity {
        MessageEntity(
            id: id, sessionId: "s-1", personaId: "p-1", role: role,
            content: content, createdAt: at, updatedAt: at,
            attachmentsJson: AttachmentsCodec.encode(attachments)
        )
    }

    /// 历史里带转写的语音消息：正文里出现「他说：…」，同时**仍然**有媒体说明（可 listen）。
    func testPromptBuilderHistoryIncludesTranscript() {
        let messages = [message(id: "m-1", attachments: [audio(transcript: "今天有点累")])]
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, timestamped: false
        )
        let content = history.first?.content ?? ""
        XCTAssertTrue(content.contains("他说：今天有点累"), content)
        XCTAssertTrue(content.contains("一段语音"), content)
        XCTAssertTrue(content.contains("listen"), content)
        XCTAssertTrue(content.contains("媒体 id"), content)
    }

    /// 没有转写的老消息：历史与 0.13 的输出**逐字相同**（回归守卫）。
    func testPromptBuilderHistoryUnchangedWithoutTranscript() {
        let attachment = audio()
        let messages = [message(id: "m-1", attachments: [attachment])]
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, timestamped: false
        )
        XCTAssertEqual(history.first?.content, attachment.mediaDescriptionLine)
    }

    /// 失败的那条：历史里明确写「没有转写」，不写「他说」。
    func testPromptBuilderHistoryMarksMissingTranscript() {
        let messages = [message(id: "m-1", attachments: [audio().markedTranscriptFailure()])]
        let content = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, timestamped: false
        ).first?.content ?? ""
        XCTAssertTrue(content.contains("（这段语音没有转写）"), content)
        XCTAssertFalse(content.contains("他说："), content)
    }

    /// 附件里的音频片段（当轮 `mediaParts`）照旧进入 `WireMessage.audio`：
    /// 转写只是**多一行字**，不替代原始音频（模型仍然能亲耳听）。
    func testTranscriptDoesNotReplaceAudioParts() {
        let attachment = audio(transcript: "今天有点累")
        let messages = [message(id: "m-1", attachments: [attachment])]
        let parts = MediaWireParts(audio: [WireAudioPart(base64: "AAAA", format: "m4a")], images: [], note: "")
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, mediaParts: ["m-1": parts], timestamped: false
        )
        XCTAssertEqual(history.first?.audio.count, 1, "原始音频必须仍然交给模型")
        XCTAssertTrue((history.first?.content ?? "").contains("他说：今天有点累"))
    }
}
