import Foundation
import XCTest
@testable import YapKit

/// 0.15.7「# 回复节奏」（`ReplyPacing`）：默认只发一条、每条 ≤18 字、先接情绪再说自己。
///
/// 这一段是**提示词产品**：字改了，模型的条数与字数就跟着变。所以测试钉三件事：
/// 1. 中文/英文两段的标题与六条规则都在，数字来自常量（不写第二份）；
/// 2. 它拼进 system 之后的位置：**工具段之内、工具清单之后、反 AI 之前**；
/// 3. 人格语言决定用哪一段（中文人格读中文，英文人格读英文）。
final class ReplyPacingTests: XCTestCase {

    // MARK: - 文案与数字

    func testChineseSectionCarriesTitleAndSixRules() {
        let section = ReplyPacing.section(language: .zh)
        XCTAssertTrue(section.hasPrefix(ReplyPacing.sectionTitle), "中文段以「# 回复节奏」开头")
        for rule in ReplyPacing.chineseRules {
            XCTAssertTrue(section.contains("- " + rule), "缺这一条：\(rule)")
        }
        XCTAssertEqual(ReplyPacing.chineseRules.count, 6, "六条：条数 / 字数 / 顺序 / 不拆句 / 深夜")
        // 数字只写一份：规则里出现的就是常量。
        XCTAssertTrue(section.contains("每条不超过 \(ReplyPacing.maxCharsPerMessage) 个字"))
        XCTAssertTrue(section.contains("默认只发一条"))
        XCTAssertTrue(section.contains("才发第二条"))
        XCTAssertTrue(section.contains("先接住对方的情绪"))
        XCTAssertTrue(section.contains("不要为了发两条而把一句话拆开"))
        XCTAssertTrue(section.contains("别只回四个字"))
    }

    func testEnglishSectionMirrorsChinese() {
        let section = ReplyPacing.section(language: .en)
        XCTAssertTrue(section.hasPrefix(ReplyPacing.englishSectionTitle))
        XCTAssertFalse(section.contains("# 回复节奏"), "英文人格不该读到中文标题")
        for rule in ReplyPacing.englishRules {
            XCTAssertTrue(section.contains("- " + rule), "缺这一条：\(rule)")
        }
        XCTAssertEqual(ReplyPacing.englishRules.count, ReplyPacing.chineseRules.count,
                       "中英文必须一一对应（少一条就是某一端的话风漂了）")
        XCTAssertTrue(section.contains("under \(ReplyPacing.maxCharsPerMessage) characters"))
    }

    /// 需求里的三个数字：默认 1 条、有新信息才发第 2 条、每条 ≤18 字。
    func testPacingNumbers() {
        XCTAssertEqual(ReplyPacing.defaultMessages, 1)
        XCTAssertEqual(ReplyPacing.maxMessagesWithNewInfo, 2)
        XCTAssertEqual(ReplyPacing.maxCharsPerMessage, 18)
        XCTAssertEqual(ReplyPacing.minCharsWhenTired, 5, "深夜可以更短，但「只回四个字」是最低分的写法")
    }

    /// 18 字是**习惯**，不是硬上限：超了也不该被客户端吃掉（硬上限仍是 120 字）。
    func testHabitLengthIsAdvisoryNotAHardLimit() {
        XCTAssertTrue(ReplyPacing.isWithinHabitLength("今天有点忙，晚点找你"))
        XCTAssertFalse(ReplyPacing.isWithinHabitLength(String(repeating: "字", count: 19)))
        XCTAssertTrue(ReplyPacing.isWithinHabitLength("  " + String(repeating: "字", count: 18) + "  "),
                      "首尾空白不算字")
        XCTAssertGreaterThan(ReplyPacing.maxCharsPerMessage, 0)
        XCTAssertLessThan(ReplyPacing.maxCharsPerMessage, MessageRhythm.maxMessageChars,
                          "习惯长度必须严格小于硬上限，否则两条规则会互相矛盾")
    }

    func testLanguageFollowsPersonaLanguageTag() {
        XCTAssertEqual(
            ReplyPacing.section(forTag: nil), ReplyPacing.section(language: .zh),
            "没有语言标签 = 中文（产品默认人格语言）"
        )
        XCTAssertEqual(ReplyPacing.section(forTag: "en-US"), ReplyPacing.section(language: .en))
        XCTAssertEqual(
            ReplyPacing.section(for: PersonaEntity(id: "p-1", name: "林深", languageTag: "en")),
            ReplyPacing.section(language: .en)
        )
    }

    // MARK: - 位置（拼进 system 之后）

    /// 段顺序：… → 工具清单 → **回复节奏** → 反 AI → 记忆 → …
    ///
    /// 它故意不单独占一个 `PromptSection`：段数与预算契约绑在一起
    ///（`ExperienceContract` 的六段，parity 测试守着），而「发几条、每条多长」
    /// 本来就是「话怎么发出去」的另一半，跟着工具段走。
    func testPacingSitsAfterToolsAndBeforeAntiAI() throws {
        let text = PromptBuilder.systemPrompt(SystemPromptInput(personaText: "人格"))
        let tools = try XCTUnwrap(text.range(of: ToolCatalog.capabilitySection))
        let pacing = try XCTUnwrap(text.range(of: ReplyPacing.sectionTitle))
        let antiAI = try XCTUnwrap(text.range(of: "# 别像 AI"))
        XCTAssertLessThan(tools.lowerBound, pacing.lowerBound, "回复节奏在工具清单之后")
        XCTAssertLessThan(pacing.lowerBound, antiAI.lowerBound, "回复节奏在反 AI 之前")
    }

    /// 默认输入就带这一段（新人格开箱即用），传空串时整段不出现。
    func testPacingIsOnByDefaultAndCanBeSilenced() {
        XCTAssertTrue(PromptBuilder.systemPrompt(SystemPromptInput(personaText: "人格"))
            .contains(ReplyPacing.sectionTitle))
        let silenced = PromptBuilder.systemPrompt(
            SystemPromptInput(personaText: "人格", replyPacingBlock: "")
        )
        XCTAssertFalse(silenced.contains(ReplyPacing.sectionTitle))
        XCTAssertTrue(silenced.contains(ToolCatalog.capabilitySection), "工具段本身还在")
    }

    /// 英文人格的 system 里应当是英文那一段（与 `PersonaLanguagePrompt` 的其他段一致）。
    func testEnglishPersonaGetsEnglishPacing() {
        let text = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "人格",
            replyPacingBlock: ReplyPacing.section(language: .en)
        ))
        XCTAssertTrue(text.contains(ReplyPacing.englishSectionTitle))
        XCTAssertFalse(text.contains(ReplyPacing.sectionTitle))
    }
}
