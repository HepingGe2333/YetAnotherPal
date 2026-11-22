import Foundation

// ---------------------------------------------------------------------------
// 「# 回复节奏」段（0.15.7 UX 探索的结论，`eval/reports/ux-explore-20261123-*.md`）。
//
// 一句话说清它是什么：**这一轮他发几条、每条多长、先说什么**。
//
// 为什么单独一段、而且放在工具段之后：
// - 它约束的是**输出形状**（条数 / 字数 / 先后顺序），与「你能做什么」（工具清单）
//   是同一件事的两面：工具决定「话怎么发出去」，这一段决定「发出去的话长什么样」。
//   所以紧跟在工具段之后、反 AI 之前——模型刚读完「每一句都用 send_message 发」，
//   紧接着读「默认只发一条」，两条规则才是连续的；
// - 反 AI 那一段讲的是「别像 AI」（不总结、不列点），这一段讲的是「像人一样短」，
//   分开写比塞进反 AI 更容易被遵守：一条规则只讲一件事。
//
// 数值来自 2026-11-23 的 UX 探索（8 场景 × 14 方案 × 2 样本，Judge 1–5）：
// 平均字数 13.2、平均条数 1 的 `warm_short` 得分最高（4.64），
// 「每条 4–5 字」的 `mood` / `late` 方案明显更低（4.16 / 4.11）——
// 太短会显得敷衍，太长会像在写小作文。18 字是这两端的中间值（`maxCharsPerMessage`）。
//
// 与 `MessageRhythm` 的分工：那边是**硬上限**（一轮 ≤4 条、每条 ≤120 字，超了会被拒），
// 这边是**习惯**（默认 1 条、≤18 字）。硬上限守「不能发生的事」，这一段守「平时长什么样」；
// 两者都要有，否则模型会拿 120 字的上限当目标。
//
// 语言：中文人格读中文段，非中文人格读英文段（`PromptLanguage`）。
// 与 `PersonaLanguagePrompt` 同一思路：中文侧不复制第二份文案，英文侧由本类型持有唯一写法。
// ---------------------------------------------------------------------------

public enum ReplyPacing {

    /// 中文段标题。
    public static let sectionTitle = "# 回复节奏"

    /// 英文段标题。
    ///
    /// 它不在 `PromptTemplates` 的六个核心段里（那是 Kotlin 真源的逐字镜像，不在这里加），
    /// 所以英文写法由本类型持有唯一一份。
    public static let englishSectionTitle = "# Reply pacing"

    // MARK: - 数字（测试与界面提示都读它，不写第二份）

    /// 一条消息的**习惯**长度上限（字）。硬上限是 `MessageRhythm.maxMessageChars`（120）。
    public static let maxCharsPerMessage = 18

    /// 默认发几条：一条。第二条要有新信息才发。
    public static let defaultMessages = 1

    /// 有**新信息**时最多发几条（新想法 / 新问题 / 没说完的那半句）。
    public static let maxMessagesWithNewInfo = 2

    /// 深夜 / 累的时候可以更短，但**不能短到这个字数以下**（「只回 4 字」是最低分的写法）。
    public static let minCharsWhenTired = 5

    // MARK: - 文案

    /// 中文六条（顺序即优先级：先「发几条」，再「多长」，再「先说什么」）。
    public static let chineseRules: [String] = [
        "默认只发一条。",
        "只有真的有新信息（新想法、新问题、没说完的那半句）才发第二条。",
        "每条不超过 \(maxCharsPerMessage) 个字，短句更像随口说的。",
        "先接住对方的情绪，再说你自己的事。",
        "不要为了发两条而把一句话拆开。",
        "深夜、或者你累了，可以更短，但别只回四个字。"
    ]

    /// 英文六条：与中文**一一对应**（不是另一套意思）。
    public static let englishRules: [String] = [
        "Send one message by default.",
        "Send a second only when you actually have something new "
            + "(a new thought, a new question, the other half of what you were saying).",
        "Keep each message under \(maxCharsPerMessage) characters; short lines sound off the cuff.",
        "Acknowledge how they feel first, then say your own part.",
        "Don't split one sentence into two just to send more.",
        "Late at night, or when you're tired, you can be even shorter — "
            + "but never just four characters."
    ]

    /// 整段（带标题、**不带**前导空行；拼装由 `PromptBuilder` 负责）。
    public static func section(language: PromptLanguage = .zh) -> String {
        let title = language == .zh ? sectionTitle : englishSectionTitle
        let rules = language == .zh ? chineseRules : englishRules
        return ([title] + rules.map { "- \($0)" }).joined(separator: "\n")
    }

    /// 按人格取语言（唯一真源：`PersonaEntity.languageTag`）。
    public static func section(for persona: PersonaEntity) -> String {
        section(language: PersonaLanguagePrompt.language(for: persona))
    }

    /// 按人格语言标签取整段（给只拿到标签的调用方，比如提示词预览与测试）。
    public static func section(forTag tag: String?) -> String {
        section(language: PersonaLanguagePrompt.language(forTag: tag))
    }

    /// 一条消息是否落在「习惯长度」内（编辑器/测试用；**不**用于拒绝落库）。
    ///
    /// 刻意不做运行时拦截：18 字是给人的感觉定的，模型偶尔写长一句不该被客户端吃掉半句。
    /// 真正的硬上限仍然只有 `MessageRhythm.maxMessageChars`。
    public static func isWithinHabitLength(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).count <= maxCharsPerMessage
    }
}
