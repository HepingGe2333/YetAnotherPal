import Foundation
import XCTest
@testable import YapKit

/// 0.15.7「从最近聊天学习话风」（`StyleDistillationService`）。
///
/// 这一层只做「取样 → 总结 → 应用」三段，总结本身仍然走 `SpeechStyleSummarizer`
/// （提示词与兜底不复制第二份）。所以测试钉的是：
/// 1. **样本**：最近 50 条、user/assistant 都算、坏消息跳过、长消息截断、总量封顶；
/// 2. **总结**：成功走模型、坏输出走本地兜底、没消息/空结果给 `.noMessages`；
/// 3. **应用**：点「应用」才写 `speechStyleJson` + 版本号 +1，取消（不调用 apply）什么都不变；
/// 4. **隐私**：日志里永不出现聊天原文。
final class StyleDistillationTests: XCTestCase {

    private var db: TempDatabase!
    private var logs: [String] = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        logs = []
    }

    override func tearDownWithError() throws {
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func config(key: String? = "sk-test") -> CompletionConfig {
        CompletionConfig(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", apiKey: key
        )
    }

    private func service(_ response: String = "{}", configKey: String? = "sk-test") -> StyleDistillationService {
        StyleDistillationService(
            stores: db.stores,
            summarizer: SpeechStyleSummarizer(
                completion: FakeCompletionClient(text: response),
                log: { [weak self] line in self?.logs.append(line) }
            ),
            log: { [weak self] line in self?.logs.append(line) },
            now: { TestData.now }
        )
    }

    private func seedPersona(version: Int = 3, style: SpeechStyle? = nil) throws -> PersonaEntity {
        var persona = TestData.persona()
        persona.version = version
        persona.speechStyleJson = style?.jsonString()
        try db.stores.personas.upsert(persona)
        return persona
    }

    private func seedSession(id: String = "session-1", lastMessageAt: Int64 = TestData.now) throws {
        var session = TestData.session()
        session.id = id
        session.lastMessageAt = lastMessageAt
        try db.stores.sessions.upsert(session)
    }

    /// 逐条落一条对话（`at` 递增 1000ms）。
    @discardableResult
    private func seedDialogue(
        _ lines: [(MessageRole, String)],
        sessionId: String = "session-1",
        startingAt: Int64 = TestData.now - Int64(100 * 1000),
        idPrefix: String = "m"
    ) throws -> [MessageEntity] {
        var seeded: [MessageEntity] = []
        for (index, line) in lines.enumerated() {
            let message = TestData.message(
                id: "\(idPrefix)-\(index)",
                sessionId: sessionId,
                role: line.0,
                content: line.1,
                createdAt: startingAt + Int64(index) * 1_000
            )
            try db.stores.messages.upsert(message)
            seeded.append(message)
        }
        return seeded
    }

    // MARK: - 1. 取样

    func testSamplePutsSpeakerLabelsInTimeOrder() throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([
            (.user, "今天加班到十点"),
            (.assistant, "又加班，烦不烦"),
            (.user, "还行吧，习惯了")
        ])

        let sample = try XCTUnwrap(service().sample(personaId: "persona-1"))
        XCTAssertEqual(
            sample.transcript.components(separatedBy: "\n"),
            ["用户：今天加班到十点", "他：又加班，烦不烦", "用户：还行吧，习惯了"]
        )
        XCTAssertEqual(sample.messageCount, 3)
        XCTAssertEqual(sample.droppedMessages, 0)
        XCTAssertFalse(sample.truncated)
    }

    /// 只学**最近** 50 条：更早的那些不进来（样本里能看出「哪几条被留下了」）。
    func testSampleKeepsOnlyTheLatestMessages() throws {
        try seedPersona()
        try seedSession()
        let lines: [(MessageRole, String)] = (0..<60).map { index in
            (index % 2 == 0 ? .user : .assistant, "第 \(index) 句")
        }
        try seedDialogue(lines)

        let sample = try XCTUnwrap(service().sample(personaId: "persona-1"))
        XCTAssertEqual(sample.messageCount, StyleDistillationService.defaultMessageLimit)
        XCTAssertEqual(StyleDistillationService.defaultMessageLimit, 50)
        let rows = sample.transcript.components(separatedBy: "\n")
        XCTAssertEqual(rows.count, 50)
        // SQL 只取了最近 50 条，所以更早的 10 条根本没进内存（`droppedMessages` 只统计
        // `compose` 自己丢掉的那些，见下面的纯函数用例）。
        XCTAssertTrue(rows.first?.hasSuffix("第 10 句") ?? false, "留下的是最近的 50 条：\(rows.first ?? "-")")
        XCTAssertTrue(rows.last?.hasSuffix("第 59 句") ?? false)
    }

    /// 一条几百字的消息只留前 120 字：学的是「怎么开口」，不是整段正文。
    func testSampleTruncatesLongMessages() throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.assistant, String(repeating: "很长的句子", count: 100))])

        let sample = try XCTUnwrap(service().sample(personaId: "persona-1"))
        XCTAssertTrue(sample.truncated)
        let line = try XCTUnwrap(sample.transcript.components(separatedBy: "\n").first)
        XCTAssertEqual(line.prefix(2), "他：")
        XCTAssertEqual(line.dropFirst(2).count, StyleDistillationService.maxCharsPerMessage,
                       "单条截到 \(StyleDistillationService.maxCharsPerMessage) 字")
    }

    /// 换行压成一行：样本是「一行一句」的聊天记录形状（总结器的启发式也按行读）。
    func testSampleFlattensMultilineMessages() throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.assistant, "今天有点累\n先睡了")])

        let sample = try XCTUnwrap(service().sample(personaId: "persona-1"))
        XCTAssertEqual(sample.transcript, "他：今天有点累 先睡了")
    }

    /// 系统消息 / 失败气泡 / 拉黑期间的都跳过（它们不是他说过的话）。
    func testSampleSkipsUnusableMessages() throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.user, "在吗"), (.assistant, "在")])
        var system = TestData.message(
            id: "m-sys", role: .system, content: "系统提示", createdAt: TestData.now - 5_000
        )
        system.blocked = false
        try db.stores.messages.upsert(system)
        var blocked = TestData.message(
            id: "m-blocked", role: .user, content: "拉黑时说的", createdAt: TestData.now - 4_000
        )
        blocked.blocked = true
        try db.stores.messages.upsert(blocked)
        var failed = TestData.message(
            id: "m-failed", role: .assistant, content: "失败的半句", createdAt: TestData.now - 3_000
        )
        failed.status = .error
        try db.stores.messages.upsert(failed)

        let sample = try XCTUnwrap(service().sample(personaId: "persona-1"))
        XCTAssertEqual(sample.transcript.components(separatedBy: "\n"), ["用户：在吗", "他：在"])
        XCTAssertEqual(sample.messageCount, 2)
    }

    func testSampleReturnsNilWithoutMessages() throws {
        try seedPersona()
        try seedSession()
        XCTAssertNil(service().sample(personaId: "persona-1"))
    }

    /// 样本总字数有上限：再多消息也不会超过总结器的 8000 字（从最旧的开始丢）。
    func testComposeRespectsTotalBudget() {
        let messages = (0..<80).map { index in
            TestData.message(
                id: "m-\(index)", role: index % 2 == 0 ? .user : .assistant,
                content: String(repeating: "字", count: 110),
                createdAt: Int64(index)
            )
        }
        let sample = try? XCTUnwrap(StyleDistillationService.compose(messages, limit: 80))
        guard let sample else { return XCTFail("这次应当有样本") }
        XCTAssertLessThanOrEqual(sample.transcript.count,
                                StyleDistillationService.maxSampleChars + 40,
                                "样本不该超过总结器的上限")
        XCTAssertTrue(sample.truncated, "被预算截过要标出来")
        XCTAssertGreaterThan(sample.droppedMessages, 0)

        // 纯函数自己也守 50 条上限：60 条进、50 条出，多出来的 10 条算「丢掉」。
        let many = (0..<60).map { index in
            TestData.message(
                id: "m-\(index)", role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句", createdAt: Int64(index)
            )
        }
        let limited = try? XCTUnwrap(StyleDistillationService.compose(many, limit: 50))
        XCTAssertEqual(limited?.messageCount, 50)
        XCTAssertEqual(limited?.droppedMessages, 10)
    }

    // MARK: - 2. 总结

    func testDistillUsesModelOutput() async throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.assistant, "在忙，晚点说"), (.assistant, "你先睡吧")])
        let json = """
        {"tone":"简短直接，偏冷淡务实","phrases":["在忙，晚点说","你先睡吧"],"habits":["句子很短"],"avoid":["长篇大论"]}
        """
        let result = await service(json).distill(
            personaId: "persona-1", config: config(), model: "deepseek-chat"
        )
        guard case .ready(let summary) = result else {
            return XCTFail("应当拿到总结：\(result)")
        }
        XCTAssertTrue(summary.usedModel)
        XCTAssertEqual(summary.style.tone, "简短直接，偏冷淡务实")
        XCTAssertEqual(summary.style.phrases, ["在忙，晚点说", "你先睡吧"])
        XCTAssertEqual(summary.style.source, .model)
    }

    /// 坏输出 / 没 Key → 本地兜底（`usedModel == false`），界面据此提示「可手动修改」。
    func testDistillFallsBackToHeuristic() async throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.assistant, "在忙，晚点说"), (.assistant, "在忙，晚点说"), (.assistant, "烦")])
        let result = await service("这不是 JSON").distill(
            personaId: "persona-1", config: config(), model: ""
        )
        guard case .ready(let summary) = result else {
            return XCTFail("坏输出也要给一份可以改的兜底：\(result)")
        }
        XCTAssertFalse(summary.usedModel)
        XCTAssertEqual(summary.style.source, .heuristic)
        XCTAssertTrue(summary.style.phrases.contains("在忙，晚点说"))

        let noKey = await service("不该用到").distill(
            personaId: "persona-1", config: config(key: nil), model: ""
        )
        guard case .ready(let fallback) = noKey else { return XCTFail("没 Key 也要有兜底") }
        XCTAssertFalse(fallback.usedModel)
        XCTAssertEqual(fallback.message, "没有可用的 API 配置")
    }

    func testDistillWithoutMessagesReturnsNoMessages() async throws {
        try seedPersona()
        try seedSession()
        let result = await service().distill(personaId: "persona-1", config: config(), model: "")
        XCTAssertEqual(result, .noMessages)
        XCTAssertTrue(logs.contains { $0.contains("没有可用的聊天记录") })
    }

    /// 模型给的是「合法但空」（`{}`）：当成读不懂 → 本地兜底，不能把界面清空。
    ///
    /// 这条与「没消息」是两件事：有记录就一定能给出一份可以手改的草稿，
    /// 只有**真的没有聊天记录**时才提示「还没什么可学的」。
    func testDistillWithEmptyModelOutputFallsBackToHeuristic() async throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.assistant, "嗯"), (.assistant, "嗯")])
        let result = await service("{}").distill(personaId: "persona-1", config: config(), model: "")
        guard case .ready(let summary) = result else {
            return XCTFail("空 JSON 也要给一份可以改的兜底：\(result)")
        }
        XCTAssertFalse(summary.usedModel)
        XCTAssertEqual(summary.style.source, .heuristic)
        XCTAssertFalse(summary.style.isEmpty)
    }

    // MARK: - 3. 应用 / 取消

    /// 预览（`distill`）**不写库**：库里的话风与版本号都没变。这就是「取消不写」。
    func testDistillDoesNotWriteStyle() async throws {
        try seedPersona(version: 3)
        try seedSession()
        try seedDialogue([(.assistant, "在忙，晚点说")])
        let json = #"{"tone":"简短直接","phrases":["在忙，晚点说"]}"#
        _ = await service(json).distill(personaId: "persona-1", config: config(), model: "")

        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(persona.speechStyleJson, "预览阶段不写库")
        XCTAssertEqual(persona.version, 3, "版本号也不动")
    }

    func testApplyWritesSpeechStyleAndBumpsVersion() throws {
        try seedPersona(version: 3)
        let style = SpeechStyle(
            tone: "简短直接，偏冷淡务实",
            phrases: ["在忙，晚点说", "你先睡吧"],
            habits: ["句子很短"],
            avoid: ["长篇大论"]
        )
        let result = try service().apply(personaId: "persona-1", style: style)

        XCTAssertTrue(result.applied)
        XCTAssertEqual(result.version, 4, "写成功就顶一版（界面上是「现在是他第 4 版」）")
        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertEqual(persona.version, 4)
        XCTAssertEqual(persona.updatedAt, TestData.now)
        let stored = try XCTUnwrap(SpeechStyle.decode(persona.speechStyleJson))
        XCTAssertEqual(stored.tone, style.tone)
        XCTAssertEqual(stored.phrases, style.phrases)
        XCTAssertEqual(stored.habits, style.habits)
        XCTAssertEqual(stored.avoid, style.avoid)
        XCTAssertEqual(result.json, persona.speechStyleJson)
    }

    /// 同一份话风点两次「应用」不该顶两次版本号。
    func testApplyIsNoOpWhenStyleIsUnchanged() throws {
        let style = SpeechStyle(tone: "简短直接", phrases: ["在忙，晚点说"])
        try seedPersona(version: 5, style: style)
        let result = try service().apply(personaId: "persona-1", style: style)

        XCTAssertFalse(result.applied)
        XCTAssertEqual(result.version, 5)
        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertEqual(persona.version, 5)
    }

    func testApplyRejectsEmptyStyle() throws {
        try seedPersona(version: 1)
        XCTAssertThrowsError(try service().apply(personaId: "persona-1", style: SpeechStyle())) { error in
            XCTAssertEqual(error as? StyleDistillationError, .emptyStyle)
        }
        XCTAssertNil(try db.stores.personas.byId("persona-1")?.speechStyleJson)
    }

    func testApplyFailsForMissingPersona() throws {
        XCTAssertThrowsError(try service().apply(personaId: "nobody", style: SpeechStyle(tone: "冷"))) { error in
            XCTAssertEqual(error as? StyleDistillationError, .personaNotFound)
        }
    }

    // MARK: - 4. 隐私（§3.3）

    /// 日志里绝不能出现聊天原文：只允许条数、截断与「成功/兜底」。
    func testLogsNeverContainChatText() async throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([
            (.user, "他今天说要辞职"),
            (.assistant, "真的假的，那你打算怎么办")
        ])
        _ = await service("坏输出").distill(personaId: "persona-1", config: config(), model: "")
        _ = await service("坏输出").distill(personaId: "persona-1", config: config(key: nil), model: "")
        XCTAssertFalse(logs.isEmpty)
        for line in logs {
            XCTAssertFalse(line.contains("他今天说要辞职"), "日志里出现了聊天原文：\(line)")
            XCTAssertFalse(line.contains("真的假的"), "日志里出现了聊天原文：\(line)")
        }
        XCTAssertTrue(logs.contains { $0.contains("取到 2 条") }, "应当只记条数：\(logs)")
    }

    /// 样本只在内存里：`StyleDistillationSample` 不落库、也不出现在人格/会话表里。
    func testSampleIsNotPersisted() throws {
        try seedPersona()
        try seedSession()
        try seedDialogue([(.user, "别存我"), (.assistant, "好")])
        let sample = try XCTUnwrap(service().sample(personaId: "persona-1"))
        XCTAssertTrue(sample.transcript.contains("别存我"))

        // 库里能搜到的只有那两条真正的消息，没有「样本」这种行。
        let all = try db.stores.messages.bySession("session-1")
        XCTAssertEqual(all.map(\.content), ["别存我", "好"])
        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(persona.speechStyleJson)
    }
}
