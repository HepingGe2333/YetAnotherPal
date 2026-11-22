import Foundation
import XCTest
@testable import YapKit

/// `MessageAttachment.transcript` / `transcriptState` 的**兼容性**（0.15 语音转写）。
///
/// 附件 JSON 是跨端契约（`messages.attachmentsJson` 会跟着备份在 iOS/Android 之间原样搬运），
/// 所以「加一个字段」必须满足三条：
/// 1. **老数据一个字节都不变**：没有转写的附件重新编码，形状与 0.13/0.14 完全一致；
/// 2. **老版本读得进**：未知字段被忽略（Android `ignoreUnknownKeys = true`）；
/// 3. **坏数据不崩**：类型不对按整段坏数据处理（与其它字段同一套语义）。
final class AttachmentTranscriptTests: XCTestCase {

    // MARK: - 老 JSON 兼容

    /// 0.13 的语音附件（没有 transcript 字段）读进来 = 「没转写」，重新编码不多一个字节。
    func testLegacyAudioJSONHasNoTranscriptAndReencodesIdentically() throws {
        let raw = #"[{"uri":"file:///audio/audio-1.m4a","mime":"audio/m4a","kind":"AUDIO","durationMs":12000}]"#
        let decoded = try XCTUnwrap(AttachmentsCodec.decode(raw).first)
        XCTAssertNil(decoded.transcript)
        XCTAssertNil(decoded.transcriptState)
        XCTAssertFalse(decoded.hasTranscript)
        XCTAssertFalse(decoded.transcriptIsMissing, "老数据没试过转写，不该被标成「没有转写」")

        let reencoded = try XCTUnwrap(AttachmentsCodec.encode([decoded]))
        XCTAssertFalse(reencoded.contains("transcript"), "没有转写时不该写出字段：\(reencoded)")
        XCTAssertEqual(AttachmentsCodec.decode(reencoded), [decoded])
    }

    /// 成功转写：文字进 JSON、状态归一成 `.succeeded`（不再多写一个 `OK` 字段）。
    func testTranscribedAudioRoundTrips() throws {
        let attachment = MessageAttachment.audio(uri: "file:///audio/a.m4a", durationMs: 3_200)
            .withTranscript("  今天有点累，想早点睡  ")
        XCTAssertEqual(attachment.transcript, "今天有点累，想早点睡", "存之前要 trim")
        XCTAssertEqual(attachment.transcriptState, .succeeded)
        XCTAssertTrue(attachment.hasTranscript)
        XCTAssertFalse(attachment.transcriptIsMissing)

        let encoded = try XCTUnwrap(AttachmentsCodec.encode([attachment]))
        XCTAssertTrue(encoded.contains("今天有点累"), encoded)
        XCTAssertFalse(encoded.contains("\"transcriptState\""), "成功状态由文字本身表达：\(encoded)")

        let decoded = try XCTUnwrap(AttachmentsCodec.decode(encoded).first)
        XCTAssertEqual(decoded.transcript, "今天有点累，想早点睡")
        XCTAssertEqual(decoded.transcriptState, .succeeded)
        XCTAssertTrue(decoded.isAudio, "转写不该改变 kind/mime")
        XCTAssertEqual(decoded.durationMs, 3_200, "转写不该改变时长")
    }

    /// 失败标记：没有文字、但有 `transcriptState = FAILED`（界面据此显示「没有转写」）。
    func testFailedTranscriptionPersistsMarkerOnly() throws {
        let attachment = MessageAttachment.audio(uri: "file:///audio/a.m4a", durationMs: 800)
            .markedTranscriptFailure()
        XCTAssertNil(attachment.transcript)
        XCTAssertEqual(attachment.transcriptState, .failed)
        XCTAssertTrue(attachment.transcriptIsMissing)

        let encoded = try XCTUnwrap(AttachmentsCodec.encode([attachment]))
        XCTAssertTrue(encoded.contains("FAILED"), encoded)
        XCTAssertFalse(encoded.contains("\"transcript\""), "失败时不该写 transcript 字段：\(encoded)")

        let decoded = try XCTUnwrap(AttachmentsCodec.decode(encoded).first)
        XCTAssertNil(decoded.transcript)
        XCTAssertEqual(decoded.transcriptState, .failed)
        XCTAssertTrue(decoded.transcriptIsMissing)
    }

    /// 视频也能带转写（`他说：…` 与语音同一句，容器信息在媒体行里）。
    func testVideoTranscriptRoundTrips() throws {
        let video = MessageAttachment.video(uri: "file:///video/v.mp4", durationMs: 15_000)
            .withTranscript("我在楼下等你")
        let encoded = try XCTUnwrap(AttachmentsCodec.encode([video]))
        let decoded = try XCTUnwrap(AttachmentsCodec.decode(encoded).first)
        XCTAssertEqual(decoded.transcript, "我在楼下等你")
        XCTAssertTrue(decoded.isVideo)
        XCTAssertTrue(decoded.hasTranscript)
    }

    // MARK: - 宽容与严格

    /// 空白转写 = 没有转写（不写字段）。
    func testBlankTranscriptIsTreatedAsAbsent() throws {
        let attachment = MessageAttachment(
            uri: "file:///audio/a.m4a", kind: MessageAttachment.audioKind,
            durationMs: 1_000, transcript: "   \n  "
        )
        XCTAssertNil(attachment.transcript)
        XCTAssertNil(attachment.transcriptState)
    }

    /// 未来版本多写一个状态：老版本忽略它，而不是整条附件作废。
    func testUnknownTranscriptStateIsIgnored() throws {
        let raw = #"[{"uri":"file:///audio/a.m4a","mime":"audio/m4a","kind":"AUDIO","transcriptState":"PENDING"}]"#
        let decoded = try XCTUnwrap(AttachmentsCodec.decode(raw).first)
        XCTAssertNil(decoded.transcriptState)
        XCTAssertEqual(decoded.uri, "file:///audio/a.m4a")
    }

    /// 状态大小写/空白都容忍。
    func testTranscriptStateParsingIsLenient() {
        XCTAssertEqual(AsrTranscriptState(stored: "failed"), .failed)
        XCTAssertEqual(AsrTranscriptState(stored: " FAILED "), .failed)
        XCTAssertEqual(AsrTranscriptState(stored: "ok"), .succeeded)
        XCTAssertNil(AsrTranscriptState(stored: "nope"))
        XCTAssertNil(AsrTranscriptState(stored: nil))
        XCTAssertEqual(AsrTranscriptState.failed.stored, "FAILED")
    }

    /// `transcript` 类型不对 = 整段坏数据（与 mime/width 同一套语义），不崩也不部分保留。
    func testWrongTranscriptTypeDropsWholePayload() {
        XCTAssertTrue(
            AttachmentsCodec.decode(#"[{"uri":"file:///a.m4a","transcript":123}]"#).isEmpty
        )
        XCTAssertTrue(
            AttachmentsCodec.decode(#"[{"uri":"file:///a.m4a","transcriptState":true}]"#).isEmpty
        )
    }

    /// 两个字段都缺 / 都是 null：与老数据完全一样。
    func testNullTranscriptFieldsAreTreatedAsAbsent() throws {
        let raw = #"[{"uri":"file:///a.m4a","mime":"audio/m4a","kind":"AUDIO","transcript":null,"transcriptState":null}]"#
        let decoded = try XCTUnwrap(AttachmentsCodec.decode(raw).first)
        XCTAssertNil(decoded.transcript)
        XCTAssertNil(decoded.transcriptState)
        XCTAssertFalse(decoded.transcriptIsMissing)
    }

    // MARK: - 图片/表情不受影响

    /// 图片附件即使被误塞了转写也不写出去（`hasTranscript` 仍按字段说话，但气泡/上下文只看
    /// 语音/视频）。这里钉的是「转写只属于可播放媒体」这条语义。
    func testImageAttachmentsAreNeverMarkedAsMissingTranscript() {
        let image = MessageAttachment.image(uri: "file:///a.jpg")
        XCTAssertFalse(image.transcriptIsMissing)
        XCTAssertFalse(image.hasTranscript)
    }

    // MARK: - 备份

    /// 备份导出/导入必须把转写（与失败标记）一起带走。
    func testPortableAndRestoredAttachmentsKeepTranscript() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-asr-backup-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let fileName = "audio-deadbeef-0000.m4a"
        let fileURL = root.appendingPathComponent(fileName)
        try Data([0x00, 0x01, 0x02]).write(to: fileURL)

        let attachment = MessageAttachment.audio(uri: fileURL.absoluteString, durationMs: 4_000)
            .withTranscript("备份也要带着这句话")
        let raw = try XCTUnwrap(AttachmentsCodec.encode([attachment]))

        let portable = try XCTUnwrap(
            BackupMediaRestorer.portableAttachments(raw, allowedNames: [fileName])
        )
        let portableAttachment = try XCTUnwrap(AttachmentsCodec.decode(portable).first)
        XCTAssertEqual(portableAttachment.transcript, "备份也要带着这句话")
        XCTAssertEqual(portableAttachment.transcriptState, .succeeded)
        XCTAssertEqual(portableAttachment.uri, fileName, "导出后 uri 是裸文件名")

        let restored = try XCTUnwrap(
            BackupMediaRestorer.restoredAttachments(
                portable,
                restoredByFileName: [
                    fileName: RestoredBackupMedia(
                        kind: .audio,
                        fileName: fileName,
                        fileURL: URL(fileURLWithPath: "/new/\(fileName)"),
                        uri: "file:///new/\(fileName)",
                        mime: "audio/m4a"
                    )
                ]
            )
        )
        let restoredAttachment = try XCTUnwrap(AttachmentsCodec.decode(restored).first)
        XCTAssertEqual(restoredAttachment.transcript, "备份也要带着这句话")
        XCTAssertEqual(restoredAttachment.uri, "file:///new/\(fileName)")
    }
}
