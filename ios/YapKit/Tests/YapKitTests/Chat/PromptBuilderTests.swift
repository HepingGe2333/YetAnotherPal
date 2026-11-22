import Foundation
import XCTest
@testable import YapKit

/// 提示词拼装：段落顺序、关键标题、心情/状态/摘要块的位置、历史过滤与预算裁剪。
///
/// 为什么段落顺序也要测：这是一段几百字的字符串，重构时最容易「顺手」把工具说明挪到前面。
/// 那样不会报错、不会崩，只会让模型的行为悄悄变一点——所以顺序得由测试守着。
final class PromptBuilderTests: XCTestCase {

    private func fullInput() -> SystemPromptInput {
        SystemPromptInput(
            personaText: "【人格】你是小满。",
            memoriesBlock: "\n\n---\n## 你记得的事\n- 他怕冷",
            earlierMemoryBlock: "\n\n【更早之前你记得的】\n上次聊到搬家",
            moodBlock: "\n\n## 你现在的心情\n平静（强度 1/5，很淡）。",
            moodPaceBlock: "\n\n今天话可以少一点。",
            presenceBlock: "\n\n## 你此刻的状态\n他正在发呆。",
            moodNudgeBlock: "\n\n## 心里那句话\n说了 3 句话……",
            proactiveCapability: "## 你可以主动联系对方\n……",
            toolsCapability: "## 你可以调用这些工具\n……"
        )
    }

    // MARK: - 段落顺序（0.15：state_first）

    /// 0.15 的段顺序：状态（含心情/引导）→ 人设（身份/世界/话风/关系）→ 工具
    /// → 反 AI → 记忆/备忘录 → 语言 → 边界。
    ///
    /// 这条顺序是 Bench v2 搜出来的（`spec/context-profile/README.md`），由
    /// `PromptSection.stateFirstOrder` 一处定义；这里把它钉死，防止有人「顺手」挪回去。
    func testSystemPromptSegmentOrderIsStateFirst() {
        var input = fullInput()
        input.personaName = "林深"
        input.worldBlock = "\n\n# 你所在的世界\n当代，沿海城市。"
        input.speechStyleBlock = "\n\n# 你怎么说话\n- 句子短。"
        input.relationshipBlock = "\n\n# 你们的关系\n认识很多年。"
        input.guidanceBlock = PromptGuidance.section(.presentOnly)
        let text = PromptBuilder.systemPrompt(input)
        let firstPersonRuleText = PromptBuilder.firstPersonRule(name: "林深")
        let markers = [
            "## 你此刻的状态",          // 1. 状态
            "## 你现在的心情",
            "今天话可以少一点",
            "## 心里那句话",
            "# 引导",                   // 2. 引导（心情之后、话风之前）
            "【人格】",                  // 3. 人设
            "# 你所在的世界",
            "# 你怎么说话",
            "# 你们的关系",
            "\n\n---\n",                // 设定 → 规则的分界线
            "## 你可以主动联系对方",     // 4. 工具
            "## 你可以调用这些工具",
            "# 别像 AI",                // 5. 反 AI
            "## 你记得的事",             // 6. 记忆
            "【更早之前你记得的】",
            "# 边界"                    // 7. 边界（收尾）
        ]
        var lastIndex = text.startIndex
        for marker in markers {
            guard let range = text.range(of: marker, range: lastIndex..<text.endIndex) else {
                XCTFail("段落缺失或顺序错位：\(marker)")
                return
            }
            lastIndex = range.upperBound
        }
        // 状态在最前（时间/心情是第一眼的坐标）、身份紧随其后且带第一人称规则。
        XCTAssertTrue(text.hasPrefix("## 你此刻的状态"), "状态段必须打头：\(text.prefix(40))")
        XCTAssertTrue(text.contains(firstPersonRuleText))
        XCTAssertTrue(text.hasSuffix(PromptBuilder.defaultBoundary), "边界收尾")
    }

    /// 段顺序的**唯一真源**：`PromptSection.stateFirstOrder` 必须就是文档里那一行。
    ///
    /// 改顺序只能改这里（`systemPrompt` 与 `ContextAssemblerV2` 都读它），
    /// 所以这条测试红了就说明「文档、完整 prompt、v2 装配」三者之一漂了。
    func testPromptSectionOrderIsTheDocumentedOne() {
        XCTAssertEqual(PromptSection.stateFirstOrder, [
            .state, .guidance, .persona, .tools, .antiAI, .memory, .language, .boundary
        ])
        XCTAssertEqual(PromptSection.allCases.count, 8, "新增段必须同时进 stateFirstOrder")
        XCTAssertEqual(Set(PromptSection.stateFirstOrder).count, 8, "顺序表里不许有重复段")
        // 两段最容易被人「顺手」挪走的边界条件：状态在最前、边界在最后。
        XCTAssertEqual(PromptSection.stateFirstOrder.first, .state)
        XCTAssertEqual(PromptSection.stateFirstOrder.last, .boundary)
    }

    /// 引导段的位置：**心情之后、话风之前**（`spec/context-profile/README.md` §注入规则）。
    func testGuidanceSitsBetweenMoodAndVoice() throws {
        var input = fullInput()
        input.speechStyleBlock = "\n\n# 你怎么说话\n- 句子短。"
        input.guidanceBlock = PromptGuidance.section(.defensive)
        let text = PromptBuilder.systemPrompt(input)
        let mood = try XCTUnwrap(text.range(of: "## 你现在的心情"))
        let guidance = try XCTUnwrap(text.range(of: "# 引导"))
        let voice = try XCTUnwrap(text.range(of: "# 你怎么说话"))
        XCTAssertLessThan(mood.lowerBound, guidance.lowerBound, "引导在心情之后")
        XCTAssertLessThan(guidance.lowerBound, voice.lowerBound, "引导在话风之前")
    }

    func testSeparatorSitsBetweenStateAndCapabilities() throws {
        let text = PromptBuilder.systemPrompt(fullInput())
        let presence = try XCTUnwrap(text.range(of: "## 你此刻的状态"))
        let capability = try XCTUnwrap(text.range(of: "## 你可以主动联系对方"))
        // 记忆块自己开头也有一条 `---`（Android 的格式），所以从「此刻状态」之后再找分隔线。
        // 「设定」与「规则」之间只有一条分隔线，且它后面**紧接着**主动唤醒能力。
        let separator = try XCTUnwrap(
            text.range(of: "\n\n---\n", options: .backwards, range: presence.upperBound..<capability.lowerBound)
        )
        XCTAssertTrue(presence.lowerBound < separator.lowerBound)
        XCTAssertEqual(separator.upperBound, capability.lowerBound, "分隔线后面紧接着主动唤醒能力")
    }

    /// 空的可选段（没记忆、没摘要、没状态、没引导）不能留下多余的空行或标题。
    ///
    /// 永远存在的两段是「边界」（规范 §1.1 的硬约束）与「别像个 AI」（规范 §5 的反 AI 味约束）：
    /// 它们不因为字段都没填就消失。
    func testEmptyOptionalSegmentsAddNothing() {
        let text = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "人格", memoriesBlock: "", earlierMemoryBlock: "",
            moodBlock: "", moodPaceBlock: "", presenceBlock: "", moodNudgeBlock: "",
            proactiveCapability: "P", toolsCapability: "T"
        ))
        XCTAssertEqual(
            text,
            "# 你是谁\n" + PromptBuilder.firstPersonRule(name: "") + "\n\n人格"
                + "\n\n---\nP\n\nT\n\n" + AntiAIPrompt.text()
                + "\n\n" + PromptBuilder.defaultBoundary
        )
        XCTAssertFalse(text.contains("## 你此刻的状态"))
        XCTAssertFalse(text.contains(MoodPrompt.sectionTitle))
    }

    /// 默认输入里就带主动唤醒与工具能力（这两段不能忘）。
    func testDefaultInputIncludesCapabilityBlocks() {
        let text = PromptBuilder.systemPrompt(SystemPromptInput(personaText: "人格"))
        XCTAssertTrue(text.contains("## 你可以主动联系对方"))
        // 0.13：工具那一段的标题改成「# 你能做什么」，内容由 `ToolCatalog` 生成。
        XCTAssertTrue(text.contains(ToolCatalog.capabilitySection))
        XCTAssertTrue(text.contains("```yap-tool"))
        XCTAssertTrue(text.contains("```yap-wake"))
    }

    func testPresenceBlock() {
        XCTAssertEqual(PromptBuilder.presenceBlock(detail: nil), "")
        XCTAssertEqual(PromptBuilder.presenceBlock(detail: "   "), "")
        XCTAssertEqual(PromptBuilder.presenceBlock(detail: "他在发呆"), "\n\n## 你此刻的状态\n他在发呆")
    }

    // MARK: - 「## 语言」小节（0.10.0）

    /// 中文界面：指令是中文模板 + 语言名「中文」，压在边界**之前**（0.15 顺序）。
    func testLanguageSectionChinese() {
        let directive = String(
            format: "## 语言\n对方现在用%1$@跟你说话。请用同一种语言回答，专有名词可以保留原文。",
            "中文"
        )
        var input = fullInput()
        input.languageDirective = directive
        let text = PromptBuilder.systemPrompt(input)
        XCTAssertTrue(text.contains("\n\n" + directive + "\n\n" + PromptBuilder.defaultBoundary),
                      "语言段应紧挨着边界之前：\(String(text.suffix(200)))")
        XCTAssertTrue(text.hasSuffix(PromptBuilder.defaultBoundary), "边界永远收尾")
        XCTAssertTrue(text.contains("## 语言\n对方现在用中文跟你说话。"))
        XCTAssertEqual(PromptBuilder.languageSection(directive), "\n\n" + directive)
    }

    /// 英文界面：指令跟着界面语言换成英文模板，语言名是「English」——与 Android 对齐。
    func testLanguageSectionEnglish() {
        let directive = String(
            format: "## Language\nThey are speaking to you in %1$@ right now. Answer in the same language; proper nouns can stay as they are.",
            "English"
        )
        var input = fullInput()
        input.languageDirective = directive
        let text = PromptBuilder.systemPrompt(input)
        XCTAssertTrue(
            text.contains("\n\n## Language\nThey are speaking to you in English right now." +
                          " Answer in the same language; proper nouns can stay as they are."),
            "英文界面下语言小节格式变了：\(String(text.suffix(160)))"
        )
        XCTAssertTrue(text.hasSuffix(PromptBuilder.defaultBoundary), "边界永远收尾")
        // 前面的人格 / 记忆段落不受影响（脚手架主体仍是原来那些中文段落）。
        XCTAssertTrue(text.contains("## 你记得的事"))
    }

    /// 没传指令（老调用点、测试）：不加小节，也不会留下空标题。
    func testLanguageSectionOmittedWhenDirectiveEmpty() {
        var input = fullInput()
        input.languageDirective = ""
        XCTAssertEqual(PromptBuilder.languageSection(""), "")
        XCTAssertFalse(PromptBuilder.systemPrompt(input).contains("## 语言"))
        XCTAssertEqual(PromptBuilder.languageSection("   "), "")
    }

    // MARK: - 历史

    private func message(_ role: MessageRole, _ content: String, at: Int64,
                         status: MessageStatus = .complete,
                         attachments: String? = nil) -> MessageEntity {
        MessageEntity(
            id: UUID().uuidString, sessionId: "s-1", personaId: "p-1", role: role,
            content: content, createdAt: at, updatedAt: at, status: status,
            attachmentsJson: attachments
        )
    }

    func testHistoryKeepsOnlyConversationalMessages() {
        let messages = [
            message(.system, "系统提示不该进历史", at: 1),
            message(.user, "你好", at: 2),
            message(.assistant, "在", at: 3),
            message(.assistant, "", at: 4),                    // 空的丢掉
            message(.assistant, "失败的一句", at: 5, status: .error), // 失败气泡不是他真说过的话
            message(.user, "睡了吗", at: 6)
        ]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil)
        XCTAssertEqual(history.map(\.role), ["user", "assistant", "user"])
        // 0.15：每条前面带 `[HH:mm] 用户：` / `[HH:mm] 他：`（细节见 `HistoryTimestampTests`）。
        XCTAssertTrue(history[0].content.hasSuffix("用户：你好"), history[0].content)
        XCTAssertTrue(history[1].content.hasSuffix("他：在"), history[1].content)
        XCTAssertTrue(history[2].content.hasSuffix("用户：睡了吗"), history[2].content)
    }

    /// 已经被摘要盖住的那一段不再逐字塞进来（否则同一个意思会说两遍）。
    func testHistoryDropsSummarizedPart() {
        let messages = [
            message(.user, "旧消息", at: 100),
            message(.assistant, "旧回复", at: 101),
            message(.user, "新消息", at: 200)
        ]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: 101)
        XCTAssertEqual(history.count, 1)
        XCTAssertTrue(history[0].content.hasSuffix("用户：新消息"), history[0].content)
        // 0 / nil 表示没压过，全留。
        XCTAssertEqual(PromptBuilder.history(messages: messages, summarizedUpTo: 0).count, 3)
    }

    /// 图片消息：iOS 还没有附件存储，正文为空时至少告诉模型「发来一张图片」。
    func testAttachmentOnlyMessageGetsPlaceholder() {
        let messages = [message(.user, "", at: 1, attachments: #"[{"uri":"file:///x.jpg"}]"#)]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil)
        XCTAssertEqual(history.count, 1)
        XCTAssertTrue(history[0].content.hasSuffix("用户：（发来一张图片）"), history[0].content)
    }

    /// 字数预算：从最新往前留，最新那条即使超预算也要留着。
    func testTrimHistoryKeepsNewestWithinBudget() {
        let history = [
            WireMessage(role: "user", content: String(repeating: "a", count: 60)),
            WireMessage(role: "assistant", content: String(repeating: "b", count: 60)),
            WireMessage(role: "user", content: String(repeating: "c", count: 60))
        ]
        let trimmed = PromptBuilder.trimHistory(history, budget: 130)
        XCTAssertEqual(trimmed.count, 2)
        XCTAssertTrue(trimmed.last?.content.hasPrefix("c") == true)

        // 单条就超预算：也要留下那一条（否则这次请求没有意义）
        let huge = [WireMessage(role: "user", content: String(repeating: "x", count: 999))]
        XCTAssertEqual(PromptBuilder.trimHistory(huge, budget: 10).count, 1)
        XCTAssertEqual(PromptBuilder.trimHistory([], budget: 10), [])
    }

    func testWireMessagesPutsSystemFirst() {
        let messages = PromptBuilder.wireMessages(
            system: "S",
            history: [WireMessage(role: "user", content: "hi")]
        )
        XCTAssertEqual(messages.first?.role, "system")
        XCTAssertEqual(messages.first?.content, "S")
        XCTAssertEqual(messages.count, 2)
    }

    /// 量级哨兵：没有记忆、没有摘要、没有心情时，system 大概是「人格 + 两段能力说明」。
    ///
    /// 这个断言不是为了精确字数，而是防止有人不知不觉把某一段复制两遍
    /// （提示词每长 1KB，每一轮对话就多花一份输入钱）。
    func testSystemPromptSizeIsInExpectedRange() {
        let base = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: String(repeating: "人", count: 300)
        ))
        XCTAssertGreaterThan(base.count, 1_000, "能力说明 + 输出格式这一段不能丢")
        // 0.13 多了「别像个 AI」六条（约 300 字）：上限相应放宽，但仍然要短。
        // 0.15 又加了一条「不写旁白/舞台说明」的硬约束（约 70 字），上限同步放宽到 4 800。
        // 0.16 多了「怎么说话」三条（只能通过 send_message 说话 / stay_silent / 4 条 × 120 字，
        // 约 250 字）：上限同步放宽到 5 600，但仍然要短。
        XCTAssertLessThan(base.count, 5_600)
        // 两段能力说明各自都有量级在 600~900 字。
        XCTAssertGreaterThan(ToolCatalog.capabilityPrompt(webEnabled: true).count, 300)
        // 0.13 加了 diary / stay_silent 和每个工具的副作用句，上限相应放宽
        //（仍然要短，不能占满上下文）。
        // 0.14 加了两件工具（send_message / block_user），工具段自然变长。
        // 0.15 又加了一件 memorize + 一段 remember/memorize 的硬要求：上限提到 4 600。
        // 0.16 说话规则再 +250 字：上限提到 4 900。
        XCTAssertLessThan(ToolCatalog.capabilityPrompt(webEnabled: true).count, 4_900)
        XCTAssertGreaterThan(ProactiveCapabilityPrompt.text.count, 300)
        XCTAssertLessThan(ProactiveCapabilityPrompt.text.count, 1_500)
    }

    func testContextConstantsMatchAndroid() {
        XCTAssertEqual(PromptBuilder.contextCharBudget, 28_000)
        XCTAssertEqual(PromptBuilder.contextRecentLimit, 200)
    }

    // MARK: - 1.9：人格细分字段进提示词（§三）

    private func persona(
        traits: [String] = [],
        likes: [String] = [],
        behaviors: [String] = [],
        backstory: String? = nil,
        quirks: String? = nil,
        proactiveLevel: Int = 3,
        memoryRetention: Int = 3
    ) -> PersonaEntity {
        PersonaEntity(
            id: "p-1", name: "林岸", description: "住在海边的人",
            prompt: "你叫林岸，住在海边，说话慢一点。",
            traits: traits, likes: likes, behaviors: behaviors,
            backstory: backstory, quirks: quirks,
            proactiveLevel: proactiveLevel, memoryRetention: memoryRetention
        )
    }

    /// 五个字段全填：`# 你是谁` 下面按「性格 / 喜欢 / 习惯 / 过去 / 设定」分小节。
    func testPersonaSectionRendersEveryFilledField() {
        let section = PromptBuilder.personaSection(persona(
            traits: ["慢热", "嘴硬心软"],
            likes: ["夜里散步"],
            behaviors: ["先听完再给建议"],
            backstory: "在旧书店长大。",
            quirks: "叫他阿迟。"
        ))
        XCTAssertTrue(section.hasPrefix("# 你是谁"))
        XCTAssertTrue(section.contains("第一人称"), "身份段必须写明第一人称、不是助手")
        XCTAssertTrue(section.contains("你叫林岸，住在海边，说话慢一点。"), "自由文本提示词必须在")
        XCTAssertTrue(section.contains("### 性格"))
        XCTAssertTrue(section.contains("- 慢热"))
        XCTAssertTrue(section.contains("- 嘴硬心软"))
        XCTAssertTrue(section.contains("### 喜欢"))
        XCTAssertTrue(section.contains("- 夜里散步"))
        XCTAssertTrue(section.contains("### 习惯"))
        XCTAssertTrue(section.contains("- 先听完再给建议"))
        XCTAssertTrue(section.contains("### 过去"))
        XCTAssertTrue(section.contains("在旧书店长大。"))
        XCTAssertTrue(section.contains("### 设定"))
        XCTAssertTrue(section.contains("叫他阿迟。"))
        // 小节顺序固定：性格 → 喜欢 → 习惯 → 过去 → 设定。
        let order = ["### 性格", "### 喜欢", "### 习惯", "### 过去", "### 设定"]
        var cursor = section.startIndex
        for title in order {
            let range = try? XCTUnwrap(section.range(of: title, range: cursor..<section.endIndex))
            XCTAssertNotNil(range, "\(title) 没出现或顺序不对")
            if let range { cursor = range.upperBound }
        }
    }

    /// 没填的字段整节不出现（不占位、不留空标题）。
    func testPersonaSectionOmitsEmptyFields() {
        let section = PromptBuilder.personaSection(persona())
        XCTAssertEqual(
            section,
            "# 你是谁\n" + PromptBuilder.firstPersonRule(name: "林岸")
                + "\n\n你叫林岸，住在海边，说话慢一点。"
        )
        XCTAssertFalse(section.contains("### 性格"))
        XCTAssertFalse(section.contains("### 过去"))
    }

    /// `proactiveLevel` / `memoryRetention` 是行为参数，**不进提示词正文**。
    func testBehaviorParametersNeverLeakIntoPrompt() {
        let section = PromptBuilder.personaSection(persona(proactiveLevel: 5, memoryRetention: 5))
        XCTAssertFalse(section.contains("主动程度"))
        XCTAssertFalse(section.contains("记性"))
        XCTAssertFalse(section.contains("5"))

        // 拼进完整 system 之后也一样。
        let system = PromptBuilder.systemPrompt(SystemPromptInput(personaText: section))
        XCTAssertFalse(system.contains("主动程度"))
    }

    /// 老数据（只有一整段提示词）照样能用：不会凭空多出一个空标题。
    func testLegacyPersonaWithoutProfileFieldsStillRenders() {
        let legacy = PersonaEntity(id: "p-2", name: "他", description: "一个朋友", prompt: "")
        let section = PromptBuilder.personaSection(legacy)
        XCTAssertEqual(
            section,
            "# 你是谁\n" + PromptBuilder.firstPersonRule(name: "他") + "\n\n你叫他。一个朋友"
        )
        XCTAssertFalse(section.contains("###"))
    }

    // MARK: - 人设引导（0.12，`spec/persona-guidance/README.md` §1 / §6.3）

    /// 世界、话风、边界三节都在；常用语最多引用 6 条；不出现第三人称模板占位。
    func testGuidanceSectionsIncludeWorldStyleAndBoundary() {
        let world = WorldPreset(
            worldName: "临港新城·云筑建筑事务所",
            era: "当代，中国沿海新一线城市，2026 年",
            description: "事务所竞争激烈，甲方频繁改图。",
            schedule: [WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30",
                                         activity: "在事务所画图、审图")],
            relationships: [WorldRelationship(name: "母亲", relation: "母亲", note: "每周三晚打电话")],
            obligations: ["按节点交图"]
        )
        let style = SpeechStyle(
            tone: "简短直接，偏冷淡务实",
            phrases: ["在忙，晚点说", "别想太多", "你先睡吧", "嗯，知道了", "我到了", "烦",
                      "今天可能回不去吃饭了", "晚点给你电话"],
            habits: ["句子很短"],
            avoid: ["长篇大论"]
        )
        let now: Int64 = 1_795_000_000_000
        let input = SystemPromptInput(
            personaText: PromptBuilder.personaSection(
                PersonaEntity(id: "p-1", name: "林深", prompt: "你在事务所画图。")
            ),
            personaName: "林深",
            worldBlock: PromptBuilder.worldSection(world),
            stateBlock: PromptBuilder.stateSection(
                now: now,
                activity: PersonaActivity(
                    kind: .work, activity: "在事务所赶第三版方案", minutes: 90,
                    availability: .busy, startedAt: now
                )
            ),
            speechStyleBlock: PromptBuilder.speechStyleSection(style),
            relationshipBlock: PromptBuilder.relationshipSection(
                persona: PersonaEntity(id: "p-1", name: "林深", description: "认识很多年的朋友")
            )
        )
        let text = PromptBuilder.systemPrompt(input)

        // 六节齐全，且顺序固定（状态 → 身份 → 世界 → 话风 → 关系 → 边界，0.15 state_first）。
        let order = ["# 你此刻的状态", "# 你是谁", "# 你所在的世界", "# 你怎么说话",
                     "# 你们的关系", "# 边界"]
        for marker in order {
            XCTAssertTrue(text.contains(marker), "缺这一节：\(marker)")
        }
        var cursor = text.startIndex
        for marker in order {
            guard let range = text.range(of: marker, range: cursor..<text.endIndex) else {
                XCTFail("顺序错位：\(marker)")
                return
            }
            cursor = range.upperBound
        }
        // 世界细节、忙碌状态、常用语都在。
        XCTAssertTrue(text.contains("在事务所画图、审图"))
        XCTAssertTrue(text.contains("母亲"))
        XCTAssertTrue(text.contains("在事务所赶第三版方案"))
        XCTAssertTrue(text.contains("不谎称有空") || text.contains("明确说自己几点后再说"))
        // phrases 最多 6 条（§1.1 第 4 条 / §6.3）。
        let quotedPhrases = style.phrases.prefix(SpeechStyle.promptPhraseLimit)
        for phrase in quotedPhrases {
            XCTAssertTrue(text.contains("「\(phrase)」"), "前 6 条里缺：\(phrase)")
        }
        for phrase in style.phrases.dropFirst(SpeechStyle.promptPhraseLimit) {
            XCTAssertFalse(text.contains("「\(phrase)」"), "第 7 条以后不该进提示词：\(phrase)")
        }
        // 不出现第三人称模板：没有 `{name}` / `{age}` 这类未替换的占位符。
        XCTAssertFalse(text.contains("{name}"))
        XCTAssertFalse(text.contains("{age}"))
        XCTAssertFalse(text.contains("{occupation}"))
        // 反例写法明确写了「不要」。
        XCTAssertTrue(text.contains("不要用第三人称"))
    }

    /// 「此刻状态」没有活动时也要说清「没在忙什么」，不编造。
    func testStateSectionWithoutActivitySaysNotBusy() {
        let now: Int64 = 1_795_000_000_000
        let text = PromptBuilder.stateSection(now: now, activity: nil)
        XCTAssertTrue(text.contains("# 你此刻的状态"))
        XCTAssertTrue(text.contains("没在忙什么"))
        // 没有时间（now = 0）时整节不加：老调用点行为不变。
        XCTAssertEqual(PromptBuilder.stateSection(now: 0, activity: nil), "")
    }

    /// 过期的活动不再影响语气（§4：到 until 自动失效）。
    func testExpiredActivityIsIgnoredInPrompt() {
        let now: Int64 = 1_800_000_000_000
        let expired = PersonaActivity(
            kind: .work, activity: "在开会", minutes: 30, availability: .busy,
            startedAt: now - 60 * 60_000
        )
        XCTAssertFalse(expired.isActive(now: now))
        let text = PromptBuilder.stateSection(now: now, activity: expired)
        XCTAssertFalse(text.contains("在开会"))
        XCTAssertTrue(text.contains("没在忙什么"))
    }

    // MARK: - 图片消息（多模态报文）

    /// 图片以 data URL 塞进 `WireMessage.images`：与 Android 的 `WireMessage.images` 同一形状。
    func testHistoryCarriesImageDataURLsForRecentImageMessages() {
        let messages = [
            message(.user, "", at: 1, attachments: #"[{"uri":"file:///a.jpg"}]"#),
            message(.assistant, "看到了，是海吗？", at: 2)
        ]
        var asked: [String] = []
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil) { attachment in
            asked.append(attachment.uri)
            return "data:image/jpeg;base64,QUJD"
        }

        XCTAssertEqual(asked, ["file:///a.jpg"], "只有带图的消息才去解图")
        XCTAssertEqual(history.first?.images, ["data:image/jpeg;base64,QUJD"])
        XCTAssertTrue(history.first?.content.hasSuffix("（发来一张图片）") == true,
                      "正文为空时补一句话，别让模型只看到一张图")
        XCTAssertTrue(history[1].images.isEmpty, "助手的消息不带图")
    }

    /// 文件已经不在了（换机 / 从备份接回）：只剩那句「发来一张图片」，图不出现。
    func testHistoryFallsBackToPlaceholderWhenImageFileIsGone() {
        let messages = [message(.user, "", at: 1, attachments: #"[{"uri":"file:///gone.jpg"}]"#)]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil) { _ in nil }
        XCTAssertEqual(history.count, 1)
        XCTAssertTrue(history[0].content.hasSuffix("（发来一张图片）"), history[0].content)
        XCTAssertTrue(history[0].images.isEmpty)
    }

    /// 只带最近两条带图消息的图：base64 很占上下文，旧图不该再烧预算。
    func testHistoryOnlyAttachesImagesForLatestTwoImageMessages() {
        let messages = (1...4).map { index in
            message(.user, "", at: Int64(index), attachments: "[{\"uri\":\"file:///\(index).jpg\"}]")
        }
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil) { attachment in
            "data:image/jpeg;base64,\(attachment.uri)"
        }
        let withImages = history.filter { !$0.images.isEmpty }
        XCTAssertEqual(withImages.count, AttachmentsCodec.maxImageMessagesInContext)
        XCTAssertEqual(withImages.last?.images.first, "data:image/jpeg;base64,file:///4.jpg")
        // 没带图的那两条仍然保留「发来一张图片」，模型知道发生过这件事。
        let placeholders = history.filter { $0.images.isEmpty }.map(\.content)
        XCTAssertEqual(placeholders.count, 2)
        XCTAssertTrue(placeholders.allSatisfy { $0.hasSuffix("用户：（发来一张图片）") }, "\(placeholders)")
    }

    /// 一条消息最多带四张图（`MAX_IMAGES_PER_MESSAGE`），多余的不发。
    func testHistoryCapsImagesPerMessage() {
        let attachments = (1...5).map { MessageAttachment(uri: "file:///\($0).jpg") }
        let messages = [message(.user, "看这几张", at: 1,
                                attachments: AttachmentsCodec.encode(attachments))]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil) { attachment in
            attachment.uri
        }
        XCTAssertEqual(history.first?.images.count, AttachmentsCodec.maxImagesPerMessage)
    }

    /// 不给 resolver 时行为与 1.9.0 完全一致（老调用点不受影响）。
    func testHistoryWithoutResolverKeepsOldBehavior() {
        let messages = [message(.user, "", at: 1, attachments: #"[{"uri":"file:///a.jpg"}]"#)]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil)
        XCTAssertEqual(history.count, 1)
        XCTAssertTrue(history[0].content.hasSuffix("（发来一张图片）"), history[0].content)
        XCTAssertTrue(history.allSatisfy { $0.images.isEmpty })
    }
}
