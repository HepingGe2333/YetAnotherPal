import Foundation
import XCTest
@testable import YapKit

/// 0.14.2「反馈」：校验 / 编码 / 本机保存 / 中英文案。
///
/// 这一组测试只覆盖 `FeedbackService.swift` 的纯逻辑与落盘（不打开浏览器）：
/// 1. 内容 1–1000 字、联系方式 ≤200 字；空内容 / 超限都不让提交；
/// 2. GitHub Issue 链接必须是 **https + 有 host**，并且中文 / `&` / `#` / 换行都能原样带过去；
/// 3. 落盘文件名是 `feedback-<时间戳>.json`，JSON 里有 appVersion / 平台 / 时间，且是原子写；
/// 4. 反馈页的每一句文案在两份 `Localizable.strings` 里都成对存在（英文没有残留中文）。
final class FeedbackTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-feedback-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private func makeRecord(
        kind: FeedbackKind = .bug,
        content: String = "发消息之后闪退了一次",
        contact: String? = "me@example.com",
        createdAt: Int64 = 1_794_500_000_000
    ) -> FeedbackRecord {
        FeedbackRecord(
            kind: kind,
            content: content,
            contact: contact,
            appVersion: "0.14.2",
            platform: "iOS 18.2",
            createdAt: createdAt
        )
    }

    // MARK: - 1. 校验

    func testValidationAcceptsNormalDraft() {
        XCTAssertNil(FeedbackDraft(content: "有个 bug").validate())
        XCTAssertNil(FeedbackDraft(content: "有 bug", contact: "").validate(), "联系方式可以不填")
        XCTAssertNil(FeedbackDraft(content: "  \n 有 bug \n ").validate(), "首尾空白不算内容")
    }

    func testValidationRejectsEmptyContent() {
        XCTAssertEqual(FeedbackDraft(content: "").validate(), .emptyContent)
        XCTAssertEqual(FeedbackDraft(content: " \n\t ").validate(), .emptyContent)
    }

    func testValidationEnforcesContentLength() {
        let atLimit = String(repeating: "字", count: FeedbackDraft.maxContentLength)
        XCTAssertNil(FeedbackDraft(content: atLimit).validate(), "正好 1000 字可以提交")
        let overLimit = String(repeating: "字", count: FeedbackDraft.maxContentLength + 1)
        XCTAssertEqual(
            FeedbackDraft(content: overLimit).validate(),
            .contentTooLong(limit: FeedbackDraft.maxContentLength)
        )
        XCTAssertEqual(FeedbackDraft.maxContentLength, 1000, "产品文案写的是 1000 字")
    }

    func testValidationEnforcesContactLength() {
        let atLimit = String(repeating: "m", count: FeedbackDraft.maxContactLength)
        XCTAssertNil(FeedbackDraft(content: "有 bug", contact: atLimit).validate())
        let overLimit = String(repeating: "m", count: FeedbackDraft.maxContactLength + 1)
        XCTAssertEqual(
            FeedbackDraft(content: "有 bug", contact: overLimit).validate(),
            .contactTooLong(limit: FeedbackDraft.maxContactLength)
        )
    }

    func testRecordMakeTrimsAndOmitsBlankContact() {
        let record = try? XCTUnwrap(FeedbackRecord.make(
            draft: FeedbackDraft(kind: .suggestion, content: "  想要夜间模式  ", contact: "   "),
            appVersion: "0.14.2",
            platform: "iOS 18.2",
            createdAt: 1
        ))
        XCTAssertEqual(record?.content, "想要夜间模式")
        XCTAssertNil(record?.contact, "空白联系方式 = 没填")
        XCTAssertEqual(record?.kind, .suggestion)

        XCTAssertNil(FeedbackRecord.make(
            draft: FeedbackDraft(content: ""),
            appVersion: "0.14.2", platform: "iOS 18.2", createdAt: 1
        ), "无效草稿不能造记录")
    }

    // MARK: - 2. 编码 / https

    func testIssueURLIsHTTPSWithHost() throws {
        let url = try XCTUnwrap(FeedbackComposer.issueURL(for: makeRecord()))
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, "github.com")
        XCTAssertTrue(FeedbackComposer.isSecureIssueURL(url))
        XCTAssertFalse(FeedbackComposer.isSecureIssueURL(URL(string: "http://github.com")!))
        XCTAssertFalse(FeedbackComposer.isSecureIssueURL(URL(string: "javascript:alert(1)")!))
        XCTAssertTrue(url.absoluteString.hasPrefix("https://github.com/"))
    }

    /// 中文 / `&` / `#` / 换行 / 百分号都要能原样带进 query（不是拼字符串，走 URLComponents）。
    func testIssueURLPercentEncodesContentAndRoundTrips() throws {
        let content = "点「发送」之后 & 闪退 #1\n第二次也这样 100% 复现"
        let record = makeRecord(content: content, contact: "me+test@example.com")
        let url = try XCTUnwrap(FeedbackComposer.issueURL(for: record))

        // 圆括号 / query 里不能出现裸的 `#`（那会被当成 fragment，内容被截断）。
        let afterQuery = try XCTUnwrap(url.absoluteString.split(separator: "?", maxSplits: 1).last)
        XCTAssertFalse(afterQuery.contains("#"), "query 里的 # 必须被编码：\(url.absoluteString)")

        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let items = try XCTUnwrap(components.queryItems)
        let query = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
        let body = try XCTUnwrap(query["body"])
        let title = try XCTUnwrap(query["title"])
        XCTAssertTrue(body.contains(content), "正文要能原样还原：\\(body)")
        XCTAssertTrue(body.contains("me+test@example.com"))
        XCTAssertTrue(body.contains("0.14.2"))
        XCTAssertTrue(body.contains("iOS 18.2"))
        XCTAssertTrue(title.contains("问题反馈"), "标题带类型前缀：\\(title)")
    }

    func testIssueTitleExcerptIsFlattenedAndBounded() {
        let long = "第一行\n" + String(repeating: "长", count: FeedbackComposer.titleExcerptLimit * 2)
        let title = FeedbackComposer.issueTitle(for: makeRecord(content: long))
        XCTAssertFalse(title.contains("\n"), "标题不能带换行：\\(title)")
        let excerpt = FeedbackComposer.excerpt(long)
        XCTAssertLessThanOrEqual(excerpt.count, FeedbackComposer.titleExcerptLimit + 1,
                                 "摘要超过上限（+1 是省略号）")
        XCTAssertTrue(excerpt.hasSuffix("…"))
        XCTAssertEqual(FeedbackComposer.excerpt("  一句  很短的话 \n"), "一句 很短的话")
    }

    // MARK: - 3. 本机保存

    func testSaveWritesTimestampedJSONAtomically() throws {
        let store = FeedbackStore(directory: directory)
        let record = makeRecord()
        let url = try store.save(record)

        XCTAssertEqual(url.lastPathComponent, "feedback-1794500000000.json")
        XCTAssertEqual(url.deletingLastPathComponent().standardizedFileURL,
                       directory.standardizedFileURL)
        let data = try Data(contentsOf: url)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(object["kind"] as? String, "BUG")
        XCTAssertEqual(object["content"] as? String, "发消息之后闪退了一次")
        XCTAssertEqual(object["contact"] as? String, "me@example.com")
        XCTAssertEqual(object["appVersion"] as? String, "0.14.2")
        XCTAssertEqual(object["platform"] as? String, "iOS 18.2")
        XCTAssertEqual(object["createdAt"] as? Int64, 1_794_500_000_000)
        XCTAssertNotNil(object["createdAtISO"])

        // 原子写：目标目录里只剩这一个文件（没有 .tmp / 半截文件）。
        let files = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertEqual(files, ["feedback-1794500000000.json"])
        XCTAssertEqual(store.fileURLs().count, 1)
    }

    func testSecondFeedbackAtSameMillisecondDoesNotOverwrite() throws {
        let store = FeedbackStore(directory: directory)
        let first = try store.save(makeRecord(content: "第一条"))
        let second = try store.save(makeRecord(content: "第二条"))
        XCTAssertNotEqual(first, second, "同一毫秒的两次提交要各自留一份")
        XCTAssertTrue(second.lastPathComponent.hasPrefix("feedback-1794500000000-2"))
        XCTAssertEqual(store.fileURLs().count, 2)
        XCTAssertTrue(try String(contentsOf: first, encoding: .utf8).contains("第一条"))
        XCTAssertTrue(try String(contentsOf: second, encoding: .utf8).contains("第二条"))
    }

    func testRecordJSONOmitsMissingContact() throws {
        let record = makeRecord(contact: nil)
        let object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: record.jsonData()) as? [String: Any]
        )
        XCTAssertNil(object["contact"], "没留联系方式就不写这个键")
    }

    // MARK: - 4. 中英文案

    /// 反馈页 / 校验提示 / Issue 正文用到的 key 必须两份资源都有，英文没有残留中文，
    /// 并且占位符一一对应（`%d` 不能只在一边）。
    func testFeedbackCopyExistsInBothLanguages() throws {
        let keys = [
            "问题反馈", "功能建议", "其他反馈",
            "反馈内容不能为空。", "反馈内容最多 %d 字。", "联系方式最多 %d 字。",
            "类型：%@", "内容：\n%@", "联系方式：%@", "（未填）",
            "App 版本：%@", "平台：%@", "时间：%@", "（由 App 内「反馈」自动生成。）",
            "反馈", "反馈类型", "反馈内容", "联系方式（可选）", "提交",
            "遇到问题、想要的功能，都可以写在这里。最多 %d 字。",
            "愿意的话可以留个联系方式（邮箱、社交账号都行），方便回访。",
            "已保存，可在浏览器提交。",
            "已保存到本机，但没能打开浏览器；可以稍后到 GitHub 提交。",
            "没能保存反馈", "本机存储写入失败，这次反馈没有留下；稍后再试一次。",
            "遇到问题或想要什么，写给我们"
        ]
        let zh = try I18NTestResources.map("zh-Hans")
        let en = try I18NTestResources.map("en")

        for key in keys {
            XCTAssertNotNil(zh[key], "zh-Hans 里缺「\\(key)」")
            let english = try XCTUnwrap(en[key], "en 里缺「\\(key)」")
            XCTAssertFalse(I18NTestResources.containsCJK(english),
                           "「\\(key)」的英文还没翻译：\\(english)")
            XCTAssertEqual(
                LocalizationResourceTests.placeholders(in: zh[key] ?? ""),
                LocalizationResourceTests.placeholders(in: english),
                "「\\(key)」的占位符两份不一致"
            )
        }

        // 关键文案的语义（不是只比 key 存在）：
        XCTAssertEqual(zh["已保存，可在浏览器提交。"], "已保存，可在浏览器提交。")
        XCTAssertEqual(en["已保存，可在浏览器提交。"], "Saved. You can submit it in the browser.")
        XCTAssertEqual(zh["反馈内容不能为空。"], "反馈内容不能为空。")
        XCTAssertEqual(en["反馈内容不能为空。"], "Feedback can't be empty.")
        // `%d` 的两份值都还带参数（删掉参数会在英文里显示成「at most characters」）。
        XCTAssertEqual(LocalizationResourceTests.placeholders(in: en["反馈内容最多 %d 字。"] ?? ""), ["%d"])
        XCTAssertEqual(LocalizationResourceTests.placeholders(in: en["联系方式最多 %d 字。"] ?? ""), ["%d"])
    }
}
