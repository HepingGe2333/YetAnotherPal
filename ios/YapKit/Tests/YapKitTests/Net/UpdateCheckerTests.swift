import Foundation
import XCTest
@testable import YapKit

/// 更新渠道（0.14.2）：语义版本比较、GitHub 解析、预发布过滤、24h 节流、失败静默、文案中英。
///
/// 网络一律走 `UpdateStubURLProtocol`（假 URLProtocol）：不发真请求，也能测到
/// 「User-Agent / Accept / 超时 / HTTP 状态码」这些真链路上的细节。
final class UpdateCheckerTests: XCTestCase {

    /// 每个用例的 UserDefaults 域（用例结束整份删掉，节流游标不会串到别的用例里）。
    private var suiteNames: [String] = []

    override func tearDown() {
        for name in suiteNames {
            UserDefaults(suiteName: name)?.removePersistentDomain(forName: name)
        }
        suiteNames.removeAll()
        UpdateStubURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - 夹具

    private func makePreferences() -> UpdatePreferences {
        // 每个用例一份独立的 UserDefaults 域：节流游标绝不能跨用例串。
        let suiteName = "yap.update.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        suiteNames.append(suiteName)
        return UpdatePreferences(defaults: defaults)
    }

    private let fixedNow = Date(timeIntervalSince1970: 1_795_000_000)

    private func makeChecker(
        currentVersion: String = "0.14.2",
        preferences: UpdatePreferences,
        now: Date? = nil
    ) -> UpdateChecker {
        let moment = now ?? fixedNow
        return UpdateChecker(
            currentVersion: currentVersion,
            session: UpdateStubURLProtocol.makeSession(),
            preferences: preferences,
            now: { moment }
        )
    }

    private func releaseJSON(
        tag: String,
        name: String = "",
        body: String = "",
        htmlURL: String? = "https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v9.9.9",
        prerelease: Bool = false,
        publishedAt: String? = "2026-11-20T10:00:00Z"
    ) -> String {
        var fields: [String] = []
        fields.append("\"tag_name\":\(quoted(tag))")
        fields.append("\"name\":\(quoted(name))")
        fields.append("\"body\":\(quoted(body))")
        if let htmlURL { fields.append("\"html_url\":\(quoted(htmlURL))") }
        if let publishedAt { fields.append("\"published_at\":\(quoted(publishedAt))") }
        fields.append("\"prerelease\":\(prerelease)")
        return "{" + fields.joined(separator: ",") + "}"
    }

    private func releaseListJSON(_ items: [String]) -> String {
        "[" + items.joined(separator: ",") + "]"
    }

    private func quoted(_ text: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [text], options: [])
        let array = String(data: data, encoding: .utf8)!
        // ["…"] → "…"：只去掉最外层的方括号。
        return String(array.dropFirst().dropLast())
    }

    // MARK: - 语义版本

    func testVersionParsingIsTolerant() {
        XCTAssertEqual(SemanticVersion("1.2.3")?.description, "1.2.3")
        XCTAssertEqual(SemanticVersion("v1.2.3")?.description, "1.2.3")
        XCTAssertEqual(SemanticVersion("  V2.0  ")?.description, "2.0.0")
        XCTAssertEqual(SemanticVersion("1.2.3+build.7")?.description, "1.2.3")
        XCTAssertEqual(SemanticVersion("1.2.3-beta.2")?.prerelease, ["beta", "2"])
        XCTAssertTrue(SemanticVersion("1.2.3-beta.2")!.isPrerelease)
        XCTAssertFalse(SemanticVersion("1.2.3")!.isPrerelease)
        // 读不懂的写法不给假答案。
        XCTAssertNil(SemanticVersion(""))
        XCTAssertNil(SemanticVersion("   "))
        XCTAssertNil(SemanticVersion("latest"))
        XCTAssertNil(SemanticVersion("1.x"))
        XCTAssertNil(SemanticVersion("v"))
    }

    func testVersionOrderingComparesNumbersNotStrings() {
        XCTAssertTrue(SemanticVersion("1.10.0")! > SemanticVersion("1.9.9")!)
        XCTAssertTrue(SemanticVersion("v2.0")! > SemanticVersion("1.99.99")!)
        XCTAssertTrue(SemanticVersion("0.15.1")! > SemanticVersion("0.15.0")!)
        XCTAssertFalse(SemanticVersion("0.15.0")! > SemanticVersion("0.15.0")!)
        // 缺段按 0：1.2 == 1.2.0。
        XCTAssertEqual(SemanticVersion("1.2")!, SemanticVersion("1.2.0")!)
        // 第四段是营销号数字：不参与比较，也不因此报错。
        XCTAssertEqual(SemanticVersion("1.2.3.4")!, SemanticVersion("1.2.3")!)
    }

    func testPrereleaseOrderingFollowsSemver() {
        XCTAssertTrue(SemanticVersion("1.0.0-beta")! < SemanticVersion("1.0.0")!)
        XCTAssertTrue(SemanticVersion("1.0.0-alpha")! < SemanticVersion("1.0.0-beta")!)
        XCTAssertTrue(SemanticVersion("1.0.0-beta.2")! > SemanticVersion("1.0.0-beta.1")!)
        XCTAssertTrue(SemanticVersion("1.0.0-beta.10")! > SemanticVersion("1.0.0-beta.9")!)
        // 数字标识符 < 字母标识符；段数少的 < 段数多的。
        XCTAssertTrue(SemanticVersion("1.0.0-1")! < SemanticVersion("1.0.0-alpha")!)
        XCTAssertTrue(SemanticVersion("1.0.0-alpha")! < SemanticVersion("1.0.0-alpha.1")!)
        XCTAssertTrue(SemanticVersion("1.0.0-rc.1")! < SemanticVersion("1.0.1")!)
    }

    // MARK: - GitHub JSON

    func testReleaseJSONParsesEveryField() throws {
        let json = releaseJSON(
            tag: "v0.16.0",
            name: "秋日更新",
            body: "## 新东西\n- 更安静",
            htmlURL: "https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v0.16.0",
            prerelease: true,
            publishedAt: "2026-11-20T10:00:00Z"
        )
        let release = try UpdateRelease.decode(Data(json.utf8))
        XCTAssertEqual(release.tagName, "v0.16.0")
        XCTAssertEqual(release.name, "秋日更新")
        XCTAssertEqual(release.notes, "## 新东西\n- 更安静")
        XCTAssertEqual(release.htmlURL?.absoluteString,
                       "https://github.com/HepingGe2333/YetAnotherPal/releases/tag/v0.16.0")
        // 2026-11-20T10:00:00Z
        XCTAssertEqual(release.publishedAt, Date(timeIntervalSince1970: 1_795_168_800))
        XCTAssertTrue(release.isPrerelease)
        XCTAssertEqual(release.version?.description, "0.16.0")
    }

    func testReleaseJSONWithoutOptionalFieldsStillParses() throws {
        let json = #"{"tag_name":"v0.16.0"}"#
        let release = try UpdateRelease.decode(Data(json.utf8))
        XCTAssertEqual(release.name, "")
        XCTAssertEqual(release.notes, "")
        XCTAssertNil(release.htmlURL)
        XCTAssertNil(release.publishedAt)
        XCTAssertFalse(release.isPrerelease)
        // 没有 html_url 时回落到仓库的 releases 页，按钮永远有地方可去。
        XCTAssertEqual(release.downloadURL, UpdateChecker.releasesPageURL)
    }

    func testReleaseJSONRejectsJunk() {
        XCTAssertThrowsError(try UpdateRelease.decode(Data("不是 JSON".utf8)))
        XCTAssertThrowsError(try UpdateRelease.decode(Data("[]".utf8)))
        // 没有 tag_name 就没有版本可比，算读不懂。
        XCTAssertThrowsError(try UpdateRelease.decode(Data(#"{"name":"x"}"#.utf8)))
    }

    func testReleaseJSONIgnoresNonWebDownloadURL() throws {
        let json = releaseJSON(tag: "v0.16.0", htmlURL: "javascript:alert(1)")
        let release = try UpdateRelease.decode(Data(json.utf8))
        XCTAssertNil(release.htmlURL, "非 http(s) 的链接不能被弹窗拿去打开")
        XCTAssertEqual(release.downloadURL, UpdateChecker.releasesPageURL)
    }

    // MARK: - 检查（真链路：URLRequest → URLProtocol）

    func testNewerReleaseProducesUpdate() async throws {
        UpdateStubURLProtocol.enqueue(.success(
            status: 200,
            body: releaseJSON(tag: "v0.16.0", name: "秋日更新", body: "更安静了")
        ))
        let preferences = makePreferences()
        let outcome = await makeChecker(preferences: preferences).check(manual: true)

        guard case .updateAvailable(let release) = outcome else {
            return XCTFail("应当是「有新版本」，实际：\(outcome)")
        }
        XCTAssertEqual(release.tagName, "v0.16.0")
        XCTAssertEqual(release.name, "秋日更新")
        XCTAssertEqual(release.notes, "更安静了")

        let request = try XCTUnwrap(UpdateStubURLProtocol.requests.first)
        XCTAssertEqual(request.url, UpdateChecker.releasesURL)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "Yap-iOS/0.14.2")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/vnd.github+json")
        XCTAssertEqual(request.timeoutInterval, UpdateChecker.defaultTimeout)
    }

    func testSameOrOlderReleaseIsUpToDate() async throws {
        for tag in ["v0.14.2", "v0.14.1", "0.14"] {
            UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: tag)))
        }
        let preferences = makePreferences()
        let checker = makeChecker(preferences: preferences)
        for tag in ["v0.14.2", "v0.14.1", "0.14"] {
            let outcome = await checker.check(manual: true)
            guard case .upToDate(let latest) = outcome else {
                return XCTFail("\(tag) 不该提示更新，实际：\(outcome)")
            }
            XCTAssertEqual(latest, tag)
        }
    }

    func testPrereleaseIgnoredByDefaultButVisibleWhenEnabled() async throws {
        let preferences = makePreferences()
        UpdateStubURLProtocol.enqueue(.success(
            status: 200, body: releaseJSON(tag: "v0.16.0-beta.1", prerelease: true)
        ))
        let defaultOutcome = await makeChecker(preferences: preferences).check(manual: true)
        guard case .upToDate = defaultOutcome else {
            return XCTFail("默认应当忽略预发布版，实际：\(defaultOutcome)")
        }

        preferences.includesPrerelease = true
        UpdateStubURLProtocol.enqueue(.success(
            status: 200,
            body: releaseListJSON([
                releaseJSON(tag: "v0.15.1"),
                releaseJSON(tag: "v0.16.0-beta.1", prerelease: true)
            ])
        ))
        let enabledOutcome = await makeChecker(preferences: preferences).check(manual: true)
        guard case .updateAvailable(let release) = enabledOutcome else {
            return XCTFail("打开「包含预览版」后应当提示，实际：\(enabledOutcome)")
        }
        XCTAssertEqual(release.tagName, "v0.16.0-beta.1")
        // 「包含预览版」必须换成列表接口：`/releases/latest` 永远不会返回预发布。
        XCTAssertEqual(UpdateStubURLProtocol.requests.last?.url, UpdateChecker.releasesListURL)
    }

    /// 列表接口：挑版本号最大的那条（而不是接口返回的第一条），草稿与读不懂的 tag 跳过。
    func testPrereleaseListPicksHighestVersionAndSkipsDrafts() async throws {
        let preferences = makePreferences()
        preferences.includesPrerelease = true
        let draft = releaseJSON(tag: "v0.17.0", prerelease: true).dropLast() + #","draft":true}"#
        let broken = #"{"name":"没有 tag"}"#
        UpdateStubURLProtocol.enqueue(.success(
            status: 200,
            body: releaseListJSON([
                releaseJSON(tag: "v0.15.1"),
                String(draft),
                broken,
                releaseJSON(tag: "v0.16.0-rc.1", prerelease: true)
            ])
        ))
        let outcome = await makeChecker(currentVersion: "0.14.2", preferences: preferences).check(manual: true)
        guard case .updateAvailable(let release) = outcome else {
            return XCTFail("应当挑出版本号最大的预发布，实际：\(outcome)")
        }
        XCTAssertEqual(release.tagName, "v0.16.0-rc.1")
    }

    /// 列表为空 / 全是草稿：没有可比版本，安静地当作「已是最新」，不报错。
    func testEmptyReleaseListIsNotAnError() async throws {
        let preferences = makePreferences()
        preferences.includesPrerelease = true
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: "[]"))
        let outcome = await makeChecker(preferences: preferences).check(manual: true)
        guard case .upToDate(let latest) = outcome else {
            return XCTFail("空列表应当是「已是最新」，实际：\(outcome)")
        }
        XCTAssertNil(latest)
    }

    /// GitHub 上没勾 prerelease，但 tag 自己写着 `-beta.1`：同样按预发布处理。
    func testPrereleaseTagSuffixIsAlsoFiltered() async throws {
        UpdateStubURLProtocol.enqueue(.success(
            status: 200, body: releaseJSON(tag: "v0.16.0-beta.1", prerelease: false)
        ))
        let outcome = await makeChecker(preferences: makePreferences()).check(manual: true)
        guard case .upToDate = outcome else {
            return XCTFail("应当忽略带 -beta 后缀的 tag，实际：\(outcome)")
        }
    }

    func testHTTPFailureIsReportedAndSilent() async throws {
        UpdateStubURLProtocol.enqueue(.success(status: 500, body: "{}"))
        let outcome = await makeChecker(preferences: makePreferences()).check(manual: false)
        XCTAssertEqual(outcome, .failed(.httpStatus(500)))
    }

    func testTimeoutIsMappedToTimeoutError() async throws {
        UpdateStubURLProtocol.enqueue(.failure(URLError(.timedOut)))
        let outcome = await makeChecker(preferences: makePreferences()).check(manual: true)
        XCTAssertEqual(outcome, .failed(.timeout))
    }

    func testNetworkFailureIsMappedToNetworkError() async throws {
        UpdateStubURLProtocol.enqueue(.failure(URLError(.notConnectedToInternet)))
        let outcome = await makeChecker(preferences: makePreferences()).check(manual: true)
        XCTAssertEqual(outcome, .failed(.network))
    }

    func testUnreadablePayloadIsReported() async throws {
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: "{\"tag_name\":123}"))
        let outcome = await makeChecker(preferences: makePreferences()).check(manual: true)
        XCTAssertEqual(outcome, .failed(.decoding))
    }

    // MARK: - 24h 节流

    func testAutoCheckIsThrottledFor24Hours() async throws {
        let preferences = makePreferences()
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: "v0.14.2")))
        let checker = makeChecker(preferences: preferences)

        let first = await checker.check(manual: false)
        guard case .upToDate = first else { return XCTFail("第一次应当真的去问，实际：\(first)") }
        XCTAssertEqual(UpdateStubURLProtocol.requests.count, 1)

        let second = await checker.check(manual: false)
        guard case .throttled(let since) = second else {
            return XCTFail("24 小时内第二次应当被节流，实际：\(second)")
        }
        XCTAssertEqual(since, fixedNow)
        XCTAssertEqual(UpdateStubURLProtocol.requests.count, 1, "节流时不该再发请求")
        XCTAssertEqual(preferences.lastCheckAt, fixedNow)
    }

    func testThrottleExpiresAfter24Hours() async throws {
        let preferences = makePreferences()
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: "v0.14.2")))
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: "v0.14.2")))

        let firstMoment = fixedNow
        let firstChecker = makeChecker(preferences: preferences, now: firstMoment)
        _ = await firstChecker.check(manual: false)

        let laterMoment = firstMoment.addingTimeInterval(UpdateChecker.throttleInterval + 1)
        let laterChecker = makeChecker(preferences: preferences, now: laterMoment)
        _ = await laterChecker.check(manual: false)
        XCTAssertEqual(UpdateStubURLProtocol.requests.count, 2, "过了 24 小时应当重新问一次")
        XCTAssertEqual(preferences.lastCheckAt, laterMoment)
    }

    func testManualCheckBypassesThrottle() async throws {
        let preferences = makePreferences()
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: "v0.14.2")))
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: "v0.14.2")))
        let checker = makeChecker(preferences: preferences)

        _ = await checker.check(manual: false)
        _ = await checker.check(manual: false)   // 被节流，不发请求
        XCTAssertEqual(UpdateStubURLProtocol.requests.count, 1)

        _ = await checker.check(manual: true)    // 用户按了按钮：照样去问
        XCTAssertEqual(UpdateStubURLProtocol.requests.count, 2)
    }

    func testFailedAttemptStillRecordsCheckTime() async throws {
        let preferences = makePreferences()
        UpdateStubURLProtocol.enqueue(.failure(URLError(.notConnectedToInternet)))
        let checker = makeChecker(preferences: preferences)
        _ = await checker.check(manual: false)

        XCTAssertEqual(preferences.lastCheckAt, fixedNow, "失败也要记时间，否则没网时会每进前台戳一次")
        UpdateStubURLProtocol.enqueue(.success(status: 200, body: releaseJSON(tag: "v0.16.0")))
        let second = await checker.check(manual: false)
        guard case .throttled = second else {
            return XCTFail("失败之后 24h 内也不该重复请求，实际：\(second)")
        }
        XCTAssertEqual(UpdateStubURLProtocol.requests.count, 1)
    }

    // MARK: - 偏好默认值

    func testPreferencesDefaultToAutoCheckOnAndNoPrerelease() {
        let preferences = makePreferences()
        XCTAssertTrue(preferences.autoCheckEnabled, "自动检查默认开")
        XCTAssertFalse(preferences.includesPrerelease, "预览版默认关")
        XCTAssertNil(preferences.lastCheckAt)

        preferences.autoCheckEnabled = false
        preferences.includesPrerelease = true
        XCTAssertFalse(preferences.autoCheckEnabled)
        XCTAssertTrue(preferences.includesPrerelease)
    }

    // MARK: - 文案（中英）

    /// 更新相关的用户可见文案在两份资源里都要有、英文不能留中文、占位符要一致。
    func testUpdateCopyExistsInBothLanguagesWithoutLeftoverChinese() throws {
        let zh = try I18NTestResources.map("zh-Hans")
        let en = try I18NTestResources.map("en")
        let keys = [
            "版本",
            "检查更新",
            "正在检查…",
            "自动检查更新",
            "包含预览版",
            "还没检查过",
            "已经是最新版本",
            "检查更新失败",
            "去下载",
            "稍后",
            "有新的版本 v%@",
            "上次检查：%@",
            "已经是最新版本（v%@）",
            "检查更新失败：网络连不上，之后再试。",
            "检查更新失败：GitHub 返回了 %d。",
            "下载地址会在浏览器里打开；装好再回来，他还是原来的样子。"
        ]
        for key in keys {
            let zhValue = try XCTUnwrap(zh[key], "中文资源缺 key：\(key)")
            let enValue = try XCTUnwrap(en[key], "英文资源缺 key：\(key)")
            XCTAssertFalse(enValue.isEmpty)
            XCTAssertFalse(I18NTestResources.containsCJK(enValue), "英文资源里还留着中文：\(enValue)")
            // 中文资源的约定：值就是 key 本身。
            XCTAssertEqual(zhValue, key)
            XCTAssertEqual(Self.placeholders(in: zhValue), Self.placeholders(in: enValue),
                           "占位符不一致：\(key)")
        }
    }

    private static func placeholders(in text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: #"%(?:\d+\$)?[ @d]"#)
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, range: range).compactMap { match in
            guard let matchRange = Range(match.range, in: text) else { return nil }
            return String(text[matchRange])
        }
    }
}

// MARK: - 假传输

/// 只服务更新检查的假 URLProtocol：按队列回放响应，并记下每次真的发出去的 `URLRequest`。
///
/// 为什么不用现成的 `StubURLProtocol`：那个是给 SSE 流式聊天用的（按块写回、带延迟），
/// 这里只需要「一次请求 → 一份 JSON / 一个错误」，另写一个更小、更不容易互相影响。
final class UpdateStubURLProtocol: URLProtocol {

    enum Stub {
        case success(status: Int, body: String)
        case failure(Error)
    }

    private static let lock = NSLock()
    private static var queue: [Stub] = []
    private static var recorded: [URLRequest] = []

    static func enqueue(_ stubs: Stub...) {
        lock.lock()
        queue.append(contentsOf: stubs)
        lock.unlock()
    }

    static func reset() {
        lock.lock()
        queue.removeAll()
        recorded.removeAll()
        lock.unlock()
    }

    static var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UpdateStubURLProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 10
        return URLSession(configuration: configuration)
    }

    private static func next() -> Stub? {
        lock.lock()
        defer { lock.unlock() }
        guard !queue.isEmpty else { return nil }
        return queue.removeFirst()
    }

    private static func record(_ request: URLRequest) {
        lock.lock()
        recorded.append(request)
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.record(request)
        let stub = Self.next() ?? .failure(URLError(.notConnectedToInternet))
        switch stub {
        case .success(let status, let body):
            let response = HTTPURLResponse(
                url: request.url ?? UpdateChecker.releasesURL,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        case .failure(let error):
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
