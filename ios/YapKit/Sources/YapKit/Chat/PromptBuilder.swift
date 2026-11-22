import Foundation

// ---------------------------------------------------------------------------
// 无状态模式的 system 提示词拼装。
//
// 来源：Android `data/repo/ChatRepository.kt` 的 `buildContext`（STATELESS 分支）
// 加上它引用的几段渲染：
//   - 人格文本：`PersonaCardRepository.systemText`（这里由调用方给进来，原因见下）
//   - 记忆块：`MemoryRepository.formatForPrompt`（Services/MemoryRepository.swift 已有）
//   - 会话滚动摘要：`HistoryCompression.memoryBlock`（Domain 已有）
//   - 心情：`MoodRepository`（Services）→ 这里只接收渲染好的两段字符串
//   - 此刻状态：`PresenceRepository.current().detail`（由 App 层注入 `presenceDetail` 闭包）
//   - 主动唤醒能力：`ProactiveProtocol.CAPABILITY_PROMPT`（iOS 还没移植，常量先放这里）
//   - 工具能力：`ToolCatalog.capabilityPrompt`（Chat/ToolProtocol.swift）
//
// **分段顺序是有意义的**，不是审美。0.15 按 Bench v2 的搜索结果改成了 **state_first**
//（`spec/context-profile/README.md`，`eval/reports/context-search-combine-*.md`：
// 5 样本 85.3 vs 旧顺序 84.3，硬失败率 23.3% vs 26.7%）：
//
// ```
// 当前状态（时间/活动/心情/引导）→ 人设（身份/世界/话风/关系）→ 工具（含回复节奏）
// → 反 AI → 记忆/备忘录 → 语言 → 边界
// ```
//
// 0.15.7「# 回复节奏」（`ReplyPacing`）**没有**新增一个段：它排在工具段里面、
// 工具清单之后。理由是段数属于预算契约（`ExperienceContract` 的六段，parity 测试守着），
// 而「发几条、每条多长」本来就是「话怎么发出去」的另一半，跟着工具段走最顺。
//
// 为什么「状态在前」：模型最早读到的应该是「现在是几点、他在做什么、他什么心情、
// 这一轮怎么说话」——这些是**这一轮**的坐标；人设是常年不变的背景。先给坐标，
// 回复才落在当下；先给人设，模型容易从「角色介绍」开始演。
//
// 顺序只在 `PromptSection.stateFirstOrder` 一处定义（`sections(_:)` 按它渲染，
// `ContextAssemblerV2` 也按它拼）。改顺序等于改提示词，必须两端一起改。
//
// 为什么要一个「拼装器」而不是在聊天服务里 `append`：这个字符串是**产品**——
// 段落、标题、空行都有人对着两端逐字比对；把它做成纯函数，测试才能钉住
// 「段落顺序」和「关键标题字符串」这两件最容易在重构里悄悄漂掉的事。
// ---------------------------------------------------------------------------

/// 一次 system 拼装用到的全部输入。
///
/// 每个字段都是「已经渲染好的文本片段」——这个类型不查库、不读时钟，
/// 所以它能被单测直接构造，也能被后台任务（主动消息）复用。
public struct SystemPromptInput {
    /// 人格文本（Android: `personaCardRepository.systemText(persona)`）。
    /// 有结构化人设卡时应当是人设卡渲染结果；iOS 的人设卡渲染还没移植，
    /// 调用方现在传 `persona.prompt`（Android 里那份 prompt 本身就是人设卡的镜像）。
    public var personaText: String
    /// 人格名字：只用于「你不是助手，你是 X 本人」这一句与反例里的第三人称提醒。空 = 不点名。
    public var personaName: String
    /// 世界预设那一段（`WorldPreset.renderForPrompt()`，人设引导 §1/§2）。
    public var worldBlock: String
    /// 「# 你此刻的状态」整段（时间 + 当前活动 + 他在做什么）。
    /// 空串 = 不加（老调用点 / 测试不必关心时间）。
    public var stateBlock: String
    /// 话风那一段（`SpeechStyle.renderForPrompt()`，人设引导 §3）。
    public var speechStyleBlock: String
    /// 「# 你们的关系」那一段（关系、亲密度、称呼）。
    public var relationshipBlock: String
    /// 「# 边界」那一段：可以覆盖，默认是 `PromptBuilder.boundarySection`。
    public var boundaryBlock: String
    /// 「# 别像个 AI」那一段（`spec/mood/README.md` §5 的反 AI 味约束）。
    ///
    /// 默认就是 `AntiAIPrompt.text()`；传空串 = 这一轮不加（只有测试会这么用）。
    public var antiAIPrompt: String
    /// 记忆块（Android: `memoryRepository.formatForPrompt(memories)`），空串表示这次没挑到记忆。
    public var memoriesBlock: String
    /// 会话滚动摘要块（`HistoryCompression.memoryBlock(session.historySummary, …)`）。
    public var earlierMemoryBlock: String
    /// 「你的备忘录」块（0.14，`spec/experience/README.md` §6）：短期摘要在前、长期在后。
    ///
    /// 它与记忆块的区别：记忆是「长期记得的事」，备忘录是「这几轮聊到哪」。
    /// 属于**可变区域**（几分钟就换一份），所以不写进人设（`# 你是谁`）里。
    public var memoBlock: String
    /// 「你刚才心里想：…」（0.14，`spec/experience/README.md` §3）：
    /// 最近一次心理活动的一句私密念头；不新鲜 / 没有时为空串。
    ///
    /// 放在心情两段之后：先告诉他「此刻什么心情」，再告诉他「刚才心里在想什么」，
    /// 这样那句念头读起来像自省，而不是一句突然出现的旁白。
    public var mentalActivityBlock: String
    /// 心情那一段（`MoodPrompt.line(state)`）。
    public var moodBlock: String
    /// 心情驱动的语气分寸（`MoodBehavior.promptLine(mood)`）。
    public var moodPaceBlock: String
    /// 「# 引导」那一段（`PromptGuidance.section(...)`，0.15 profile）。
    ///
    /// 位置固定：心情之后、话风之前（见 `PromptSection.stateFirstOrder`）。
    /// 空串 = 这一轮不加（只有测试与「引导静音」的特殊场景会这么用）。
    public var guidanceBlock: String
    /// 「他此刻在做什么」那一段；为空表示不加（状态不该成为聊天里的障碍）。
    ///
    /// 0.12 起由 `stateBlock` 承担（它把时间、活动、在场合成一段）；
    /// 这个字段保留是为了老调用点与测试不用改签名 —— `stateBlock` 为空时仍然会用它。
    public var presenceBlock: String
    /// 周期性心情引导（`MoodPrompt.reflectionNudge`）；不该问的时候是空串。
    public var moodNudgeBlock: String
    /// 主动唤醒能力说明。
    public var proactiveCapability: String
    /// 工具能力说明（`ToolCatalog.capabilityPrompt(webEnabled:)`）。
    public var toolsCapability: String
    /// 「# 工具使用」段（0.15 模型优化开关，`ModelProfilePrompt`）。
    ///
    /// 空串 = 这一轮不注入（GLM 的 lean 档 / 老调用点 / 测试）。它是**工具段的一部分**
    /// （排在能力清单之后），所以段顺序与 `ContextAssemblerV2` 都不需要为它改一行。
    public var toolUsageBlock: String
    /// 「# 回复节奏」段（0.15.7，`ReplyPacing`）：默认只发一条、每条 ≤18 字、先接情绪。
    ///
    /// 位置固定：**工具段之内、工具清单之后**（见 `PromptSection.text(.tools)`）。
    /// 它与「你能做什么」是同一件事的两面——工具决定「话怎么发出去」，
    /// 这一段决定「发出去的话长什么样」——所以紧挨着读，模型才会把两条规则连起来。
    /// 空串 = 这一轮不加（只有测试与「提示词瘦身」的特殊场景会这么用）。
    public var replyPacingBlock: String
    /// 「## 语言」小节的完整文本（模板由 App 层按界面语言从资源里取，见 `AppLanguage.languageDirective`）。
    /// 空串 = 不加这一节（老调用点与测试不用改签名）。
    public var languageDirective: String

    public init(
        personaText: String = "",
        personaName: String = "",
        worldBlock: String = "",
        stateBlock: String = "",
        speechStyleBlock: String = "",
        relationshipBlock: String = "",
        boundaryBlock: String = PromptBuilder.defaultBoundary,
        antiAIPrompt: String = AntiAIPrompt.text(),
        memoriesBlock: String = "",
        earlierMemoryBlock: String = "",
        memoBlock: String = "",
        mentalActivityBlock: String = "",
        moodBlock: String = "",
        moodPaceBlock: String = "",
        guidanceBlock: String = "",
        presenceBlock: String = "",
        moodNudgeBlock: String = "",
        proactiveCapability: String = ProactiveCapabilityPrompt.text,
        toolsCapability: String = ToolCatalog.capabilityPrompt(),
        toolUsageBlock: String = "",
        replyPacingBlock: String = ReplyPacing.section(language: .zh),
        languageDirective: String = ""
    ) {
        self.personaText = personaText
        self.personaName = personaName
        self.worldBlock = worldBlock
        self.stateBlock = stateBlock
        self.speechStyleBlock = speechStyleBlock
        self.relationshipBlock = relationshipBlock
        self.boundaryBlock = boundaryBlock
        self.antiAIPrompt = antiAIPrompt
        self.memoriesBlock = memoriesBlock
        self.earlierMemoryBlock = earlierMemoryBlock
        self.memoBlock = memoBlock
        self.mentalActivityBlock = mentalActivityBlock
        self.moodBlock = moodBlock
        self.moodPaceBlock = moodPaceBlock
        self.guidanceBlock = guidanceBlock
        self.presenceBlock = presenceBlock
        self.moodNudgeBlock = moodNudgeBlock
        self.proactiveCapability = proactiveCapability
        self.toolsCapability = toolsCapability
        self.toolUsageBlock = toolUsageBlock
        self.replyPacingBlock = replyPacingBlock
        self.languageDirective = languageDirective
    }
}

/// 「你可以主动联系对方」这一段。
///
/// 从 0.9.1 起真正的文案在 `Services/ProactiveRuleService.swift` 的 `ProactiveProtocol.capabilityPrompt`
/// （与 Android 的 `ProactiveProtocol.CAPABILITY_PROMPT` 逐字一致，代码块语言是 `yap-wake`）。
/// 这里保留一个同名入口，是为了不让 `PromptBuilder` 的调用点因为「文案搬家」而改一遍签名：
/// 文案只有一个来源，**协议与落库**属于 Proactive 模块。
public enum ProactiveCapabilityPrompt {

    public static let blockLanguage = ProactiveProtocol.blockLanguage

    public static let text: String = ProactiveProtocol.capabilityPrompt
}

/// system 提示词里的**可组合段**（0.15，`spec/context-profile/README.md`）。
///
/// 一个枚举值 = 一段有独立语义的文本：状态（时间/活动/心情/引导）、引导、人设
///（身份/世界/话风/关系）、工具、反 AI、记忆/备忘录、语言、边界。
///
/// 为什么值得一个类型：改顺序（这次从「人设在前」改成「状态在前」）不许碰任何一段的
/// 内容来源，只许碰这个数组。测试直接钉 `stateFirstOrder`，`ContextAssemblerV2` 也读它，
/// 所以「完整 prompt」与「v2 装配的 prompt」不可能有两种顺序。
public enum PromptSection: String, CaseIterable, Sendable {

    /// 当前状态：时间、此刻的活动/在场、心情、心情分寸、心里那句话、心情引导。
    case state
    /// 这一轮的语气分寸（`# 引导`，`PromptGuidance`）。
    case guidance
    /// 人设：身份 / 世界 / 话风 / 关系。
    case persona
    /// 主动唤醒能力 + 工具清单 + 回复节奏（0.15.7：默认只发一条、每条 ≤18 字）。
    case tools
    /// 反 AI 味（core 五条）。
    case antiAI
    /// 记忆 / 更早记得的 / 备忘录（v2 里由 `ContextAssemblerV2` 按预算裁剪）。
    case memory
    /// 「用对方的语言回答」。
    case language
    /// 边界（硬约束，永远收尾）。
    case boundary

    /// **唯一的顺序定义**：state_first
    ///（状态 → 引导 → 人设 → 工具 → 反AI → 记忆 → 语言 → 边界）。
    public static let stateFirstOrder: [PromptSection] = [
        .state, .guidance, .persona, .tools, .antiAI, .memory, .language, .boundary
    ]

    /// 这一段的文本（不带前导空行、不带段间分隔线；空串 = 这一轮不出现）。
    func text(_ input: SystemPromptInput) -> String {
        switch self {
        case .state:
            return PromptBuilder.stack([
                PromptBuilder.stateSection(input.stateBlock, legacyPresence: input.presenceBlock),
                input.moodBlock,
                input.moodPaceBlock,
                input.mentalActivityBlock,
                input.moodNudgeBlock
            ])
        case .guidance:
            return input.guidanceBlock
        case .persona:
            return PromptBuilder.stack([
                PromptBuilder.identityBlock(input.personaText, name: input.personaName),
                input.worldBlock,
                input.speechStyleBlock,
                input.relationshipBlock
            ])
        case .tools:
            return PromptBuilder.stack([
                input.proactiveCapability, input.toolsCapability,
                input.toolUsageBlock, input.replyPacingBlock
            ])
        case .antiAI:
            return input.antiAIPrompt
        case .memory:
            return PromptBuilder.stack([
                input.memoriesBlock, input.earlierMemoryBlock, input.memoBlock
            ])
        case .language:
            return input.languageDirective
        case .boundary:
            return input.boundaryBlock
        }
    }
}

public enum PromptBuilder {

    /// 一次回复最多带多少字的对话正文。与 Android 的 `CONTEXT_CHAR_BUDGET` 一致。
    ///
    /// 它是**产品上限**（历史再长也只逐字带这么多，其余靠摘要），不是模型窗口：
    /// 窗口上限见 `contextTokenBudget(configured:)`（0.13 起默认 1M，`spec/api-defaults`）。
    public static let contextCharBudget = 28_000

    /// 上下文预算（tokens）：默认 1M 上限，配置里显式给的值优先且不超过 1M。
    public static func contextTokenBudget(configured: Int? = nil) -> Int {
        ApiDefaults.contextTokenLimit(configured: configured)
    }

    /// 「这个窗口还能带多少字历史」：窗口换算结果与产品上限取小。
    ///
    /// 默认窗口（1M）下结果就是 `contextCharBudget`（28 000）——1M 不会让请求体真的胀到 1M；
    /// 只有在有人把窗口配得很小时（比如 20K）才会真的收紧历史。
    public static func historyCharBudget(contextTokens: Int = ApiDefaults.contextWindowTokens) -> Int {
        min(contextCharBudget, ApiDefaults.historyCharBudget(contextTokens: contextTokens))
    }

    /// 装配上下文时最多从库里读多少条消息（Android: `CONTEXT_RECENT_LIMIT`）。
    public static let contextRecentLimit = 200

    /// 分段之间的固定分隔：一个显眼的横线，让「设定」与「规则」在长上下文里也分得开。
    static let sectionSeparator = "\n\n---\n"

    /// 把若干「已经带前导换行的片段」叠成一段：非空的按 `\n\n` 连接、首尾空白去掉。
    ///
    /// 各段渲染器（`stateSection` / `moodBlock` / `worldSection` …）历史上都会自己带
    /// `"\n\n"` 前缀（那是给「字符串相加」的旧拼法用的）。这里统一在**段内**再拼一次，
    /// 所以顺序变了也不会出现「黏在一起」或「三个空行」。
    static func stack(_ pieces: [String]) -> String {
        pieces
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
    }

    // MARK: - system

    /// 把各段拼起来（顺序见 `PromptSection.stateFirstOrder`）。
    ///
    /// 这是一个**薄壳**：真正的分段在 `sections(_:)` 里，这里只负责用一条横线把
    /// 「设定区」与「规则区」分开（`---` 出现在工具段之前，与 0.13 的形状一致）。
    /// 拆开的原因是 0.15 的 `ContextAssemblerV2` 要按**同一个顺序**把记忆/主题塞进去：
    /// 顺序只能有一处定义，否则「完整 prompt」与「v2 装配的 prompt」会悄悄分叉。
    public static func systemPrompt(_ input: SystemPromptInput) -> String {
        joined(sections(input))
    }

    /// 按固定顺序返回**每一段的文本**（空段不出现）。
    ///
    /// 这就是「可组合段」：调用方既可以整体拼（`systemPrompt`），也可以只取其中几段
    ///（`ContextAssemblerV2` 拿状态/引导/人设/工具/反AI/语言/边界，记忆由它自己按预算裁）。
    /// 每段都**不带**前导空行、不带分隔线——拼接是调用方的事。
    public static func sections(_ input: SystemPromptInput) -> [(section: PromptSection, text: String)] {
        PromptSection.stateFirstOrder.map { section in
            (section, section.text(input))
        }
    }

    /// 只取某几段拼起来（`ContextAssemblerV2` 与老调用点用）。
    public static func prompt(_ input: SystemPromptInput, sections wanted: [PromptSection]) -> String {
        joined(sections(input).filter { wanted.contains($0.section) })
    }

    /// 按 `stateFirstOrder` 拼：段与段之间一个空行；工具段之前换成一条横线
    ///（「设定」与「规则」的分界，0.13 起就是这个形状，只在顺序上改过）。
    static func joined(_ sections: [(section: PromptSection, text: String)]) -> String {
        var text = ""
        for entry in sections {
            let block = entry.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !block.isEmpty else { continue }
            if text.isEmpty {
                text = block
                continue
            }
            text += entry.section == .tools ? sectionSeparator : "\n\n"
            text += block
        }
        return text
    }

    /// 「用对方的语言回答」这一节。
    ///
    /// 为什么放在最后一节：前面的段落都是「他是谁 / 他记得什么 / 他能做什么」，
    /// 「怎么说」属于输出规则，压在尾巴上模型更容易当成指令而不是设定。
    ///
    /// **指令本身随界面语言本地化**（与 Android `LanguageDirective.section` 对齐）：
    /// 中文界面发 `## 语言` + 中文说明，英文界面发 `## Language` + 英文说明。
    /// Kit 不读资源、也不读 `Locale`：模板由 App 层取好、拼好语言名再传进来，
    /// 这样「模板内容」归资源守卫管、「拼在 system 末尾」归这里管，两边都能单测。
    public static func languageSection(_ directive: String) -> String {
        let trimmed = directive.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        return "\n\n" + trimmed
    }

    // MARK: - 人设引导（0.12，`spec/persona-guidance/README.md`）

    /// 「# 边界」那一段（规范 §1.1 的七条强制规则里，属于「不许做什么」的那些）。
    ///
    /// 为什么写死在这里而不是让调用方传：这七条是**所有端一致**的硬约束，
    /// 让它可覆盖只会让某一端悄悄少一条；`SystemPromptInput.boundaryBlock` 只用于
    /// 特殊场景（比如主动消息要更短的一段），默认永远是这一段。
    ///
    /// 文案里刻意写出**反例**（§1.2）：模型对「不要写成 …」的服从度明显高于抽象要求。
    public static let defaultBoundary = PersonaPromptScaffold.boundariesText()

    /// 第一人称规则（§1.1 第 1 条）。`name` 空时不点名，避免出现「你是 本人」这种句子。
    ///
    /// 文案逐字来自 `spec/persona-guidance/scaffold.json` 的 `identity_body` /
    /// `identity_body_with_meta`：`meta`（年龄、职业这类补充）由调用方给，空时不带括号。
    public static func firstPersonRule(name: String, meta: String = "") -> String {
        PersonaPromptScaffold.identityLine(name: name, meta: meta)
    }

    /// 把「人格正文」包成 `# 你是谁` 一节；正文自己已经带了第一人称规则时不重复写。
    ///
    /// 为什么需要判断「已经带了」：`personaSection` 会带上规则，人设卡渲染的正文不会；
    /// 两者都可能从 App 层传进来，重复两遍规则会白烧 token 且显得啰嗦。
    public static func identityBlock(_ personaText: String, name: String) -> String {
        let body = personaText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return "" }
        if body.contains("第一人称") { return body }
        return "# 你是谁\n" + firstPersonRule(name: name) + "\n\n" + body
    }

    /// 世界预设那一段。
    ///
    /// 没有预设时**仍然出现**这一节、只写 `world_empty` 那一句（脚手架 `world_empty`）：
    /// 模型需要知道「这里没有设定」，而不是自己猜一个世界出来。
    public static func worldSection(_ world: WorldPreset?) -> String {
        let rendered = world?.renderForPrompt() ?? ""
        if !rendered.isEmpty { return "\n\n" + rendered }
        return "\n\n" + PersonaPromptScaffold.worldSection
            + "\n" + PersonaPromptScaffold.worldEmpty
    }

    /// 话风那一段（空风格返回空串）。
    public static func speechStyleSection(_ style: SpeechStyle?) -> String {
        guard let style else { return "" }
        let rendered = style.renderForPrompt()
        return rendered.isEmpty ? "" : "\n\n" + rendered
    }

    /// 「# 你们的关系」：称呼、关系、亲密度。
    ///
    /// 数据来源是结构化人设卡的 `identity.relation` / `voice.address` / `bond`，
    /// 再退回人格的一句话描述。全都为空就整节不出现（不写「你们关系很好」这种空话）。
    public static func relationshipSection(persona: PersonaEntity) -> String {
        var parts: [String] = []
        let card = PersonaCardCodec.decode(persona.personaCardJson)
        let relation = card.identity.relation.trimmingCharacters(in: .whitespacesAndNewlines)
        if !relation.isEmpty { parts.append("你和他：\(relation)。") }
        let address = card.voice.address.trimmingCharacters(in: .whitespacesAndNewlines)
        if !address.isEmpty { parts.append("你叫他「\(address)」。") }
        let bond = card.bond.trimmingCharacters(in: .whitespacesAndNewlines)
        if !bond.isEmpty { parts.append(bond) }
        let description = persona.description.trimmingCharacters(in: .whitespacesAndNewlines)
        if parts.isEmpty, !description.isEmpty { parts.append(description) }
        // 一句结构化关系都没有时用脚手架里的兜底（不能留一个只有标题的空段落）。
        if parts.isEmpty { parts.append(PersonaPromptScaffold.relationshipDefault) }
        return "\n\n" + PersonaPromptScaffold.relationshipSection + "\n"
            + parts.joined(separator: "\n")
    }

    /// 「# 你此刻的状态」整段：现在是几点、他在做什么、忙不忙。
    ///
    /// 规范 §6.3 的形状：
    /// `现在是{weekday HH:mm}。{activity 或「没在忙什么」}{；心情：…}{；已经睡了…}`
    /// 这里只负责「时间 + 活动 + 在场」；心情由 `MoodPrompt` 那两段跟在后面。
    ///
    /// 时间（`spec/context-time/README.md`）：**每次拼提示词都重新取**，第一行就是
    /// `现在是 2026-11-17 周二 02:42（UTC+08:00）。`。`now` 由调用方在**发请求那一刻**
    /// 传进来（`ChatService` 每轮取一次），所以跨天 / 换时区之后这里自然是对的。
    /// 工具回路不会重建 system，因此这一行在一次回复里只出现一次。
    public static func stateSection(
        now: Int64,
        activity: PersonaActivity?,
        presenceDetail: String? = nil,
        calendar: Calendar = ServiceTime.calendar()
    ) -> String {
        guard now > 0 else { return "" }
        // 第一行逐字来自脚手架 `state_line`（`现在是{weekday} {time}。`）：
        // 0.13 把日期并进 `{weekday}`、把 UTC 偏移并进 `{time}`，模板本身没动。
        var line = PersonaPromptScaffold.fill(PersonaPromptScaffold.stateLine, [
            "weekday": PromptClock.dateWeekdayText(now, calendar: calendar),
            "time": PromptClock.clockWithOffsetText(now, calendar: calendar)
        ])
        if let activity, activity.isActive(now: now) {
            line += activity.renderStateLine(now: now, calendar: calendar)
        } else {
            line += PersonaPromptScaffold.stateIdle
        }
        var lines = [PersonaPromptScaffold.stateSection, line]
        let presence = (presenceDetail ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !presence.isEmpty { lines.append("（你自己的状态：\(presence)）") }
        return "\n\n" + lines.joined(separator: "\n")
    }

    /// system 里那一段：优先用渲染好的 `stateBlock`；老调用点只给了 `presenceBlock` 时保持原样。
    static func stateSection(_ rendered: String, legacyPresence: String) -> String {
        if !rendered.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return rendered }
        return legacyPresence
    }

    /// 把「边界」那一段拼进 system；传空串 = 整节不出现（只有测试会这么用）。
    public static func boundaryBlock(_ custom: String) -> String {
        let trimmed = custom.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "" : "\n\n" + trimmed
    }

    /// 把「# 别像个 AI」那一段拼进 system；传空串 = 整节不出现。
    public static func antiAIBlock(_ custom: String) -> String {
        let trimmed = custom.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "" : "\n\n" + trimmed
    }

    // MARK: - 人格小节（1.9）

    /// 把人格的自由文本提示词 + 五个结构化字段拼成「# 你是谁」下的一段。
    ///
    /// 为什么要有这一层：1.9 把「他是谁」拆成了可单独填的字段（性格 / 喜欢 / 习惯 / 过去 / 设定），
    /// 但用户仍然可以直接写一整段提示词。两者都在时，原则是「自由文本是主体，结构化字段是补充」：
    /// 主体先出现（模型的「自我」优先），小节跟在后面，空字段整节不出现——不占位、不留空标题。
    ///
    /// `proactiveLevel` 与 `memoryRetention` **刻意不进这里**：它们是行为参数（每天主动几次、
    /// 记忆淡得快不快），不是「他是谁」的描述。让他读到「我是 3 级主动」只会让语气变形。
    public static func personaSection(_ persona: PersonaEntity) -> String {
        let body = personaProfileBody(persona)
        guard !body.isEmpty else { return "" }
        return "# 你是谁\n" + firstPersonRule(name: persona.name) + "\n\n" + body
    }

    /// 只有「他是谁」的正文，不带 `## 你是谁` 这个标题 —— 给已经有自己标题的拼装器用
    /// （比如 `AgentPromptBuilder` 的「你是你自己」那段，它自己会写标题）。
    public static func personaProfileBody(_ persona: PersonaEntity) -> String {
        var lines: [String] = []
        let prompt = persona.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = persona.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let description = persona.description.trimmingCharacters(in: .whitespacesAndNewlines)

        // 0.13：空提示词不再等于「没有身份」（真机反馈：只填了名字/什么都没填的人，
        // 系统提示里连 `# 你是谁` 都没有，模型于是自称助手）。
        // 非空时保留原文；空时由名字 + 介绍生成，两样都空就用系统默认人设文本。
        if !prompt.isEmpty {
            lines.append(prompt)
        } else {
            lines.append(PersonaDraft.generatedPrompt(name: name, description: description))
        }

        func appendSection(_ title: String, _ rawContent: String) {
            let content = rawContent.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !content.isEmpty else { return }
            lines.append("### \(title)")
            lines.append(content)
        }

        if !persona.traits.isEmpty {
            appendSection("性格", persona.traits.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !persona.likes.isEmpty {
            appendSection("喜欢", persona.likes.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !persona.behaviors.isEmpty {
            appendSection("习惯", persona.behaviors.map { "- \($0)" }.joined(separator: "\n"))
        }
        appendSection("过去", persona.backstory ?? "")
        appendSection("设定", persona.quirks ?? "")

        return lines.joined(separator: "\n\n")
    }

    /// 「他此刻在做什么」那一段。空 detail 返回空串（不加这一段）。
    public static func presenceBlock(detail: String?) -> String {
        let trimmed = (detail ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "" }
        return "\n\n## 你此刻的状态\n\(trimmed)"
    }

    // MARK: - 对话历史

    /// 把库里的消息变成要发出去的消息序列。
    ///
    /// - 已经被滚动摘要盖住的那部分不再逐字塞进来（它已经在 system 里那段「更早记得的」里了，
    ///   同一个意思说两遍只会让他混乱）；
    /// - 只带 USER / ASSISTANT，空的、失败态的都不要（失败气泡不是他真说过的话）。
    ///
    /// 图片消息：正文为空时补一句「（发来一张图片）」，保证模型至少知道发生过这件事；
    /// 真正的图片以 data URL 塞进 `WireMessage.images`（多模态报文已支持），
    /// 但**只带最近 `AttachmentsCodec.maxImageMessagesInContext` 条带图的消息**——
    /// base64 会让请求体膨胀 4/3，把整段历史里的图全带上等于把上下文预算烧在旧图上。
    /// 文件已经被清掉（换机、从备份接回）时 `imageResolver` 返回 nil，这条就只剩那句「发来一张图片」。
    ///
    /// 语音/视频（0.13）：**历史只带一句说明，不带二进制**。
    /// - 每条带语音/视频的消息都会在正文后面补一行「这条消息是一段语音，时长 12 秒；
    ///   媒体 id：…；想听就用 listen」——模型因此知道「这条消息不是文字」，
    ///   也知道要用哪个 id 去听/看；
    /// - 真正的 `input_audio`（base64）与视频关键帧只给**当轮那一条**消息
    ///   （`mediaParts` 里按消息 id 给）：历史里的语音如果再发一遍，一次请求会被
    ///   几段几十 MB 的 base64 撑爆，而它们早就被模型听过了。
    ///
    /// 时间戳（0.15，`history=timestamped`）：每条历史前面加 `[21:04] 用户：` /
    /// `[21:06] 他：`，跨天换 `[11-19 21:04]`（见 `HistoryTimestamp`）。
    /// - `now` 是这一轮发请求的时刻（「今天」的参照），由 `ChatService` 传；
    /// - 工具结果 / 系统消息**不加**：它们不是他说过的话（而且工具回路里的那几条
    ///   WireMessage 是在 `history` 之外自己拼的，永远走不到这里）；
    /// - `timestamped: false` 只给老调用点/测试用（回到 0.14 的样子）。
    public static func history(
        messages: [MessageEntity],
        summarizedUpTo: Int64?,
        imageResolver: ((MessageAttachment) -> String?)? = nil,
        mediaParts: [String: MediaWireParts] = [:],
        now: Int64 = 0,
        language: PromptLanguage = .zh,
        timestamped: Bool = true,
        calendar: Calendar = ServiceTime.calendar()
    ) -> [WireMessage] {
        let cutoff = summarizedUpTo.flatMap { $0 > 0 ? $0 : nil }
        // 先筛出「真的要发给模型的那几条」，再定哪几条的图值得带上（与 Android 的
        // `buildContext` 同一顺序：先过滤、再决定给谁解码，别为已经被裁掉的消息解一遍图）。
        var visible: [MessageEntity] = []
        for message in messages {
            if let cutoff, message.createdAt <= cutoff { continue }
            guard message.role == .user || message.role == .assistant else { continue }
            // 0.14：拉黑期间收下的消息**永久不进模型上下文**（解除后也不可见）。
            guard !message.blocked else { continue }
            guard message.status != .error else { continue }
            let hasAttachments = !(message.attachmentsJson ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let content = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if content.isEmpty && !hasAttachments { continue }
            visible.append(message)
        }

        let imageOwnerIds: Set<String> = {
            guard imageResolver != nil else { return [] }
            let owners = visible.filter { message in
                message.role == .user
                    && !(message.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            return Set(owners.suffix(AttachmentsCodec.maxImageMessagesInContext).map(\.id))
        }()

        var result: [WireMessage] = []
        for message in visible {
            let decoded = AttachmentsCodec.decode(message.attachmentsJson)

            var images: [String] = []
            if let imageResolver, imageOwnerIds.contains(message.id) {
                images = decoded
                    .filter { $0.isImage }
                    .prefix(AttachmentsCodec.maxImagesPerMessage)
                    .compactMap { imageResolver($0) }
            }

            // 这条消息带的是语音/视频吗：正文后面补一行说明（历史里**只有这一行**）。
            var text = message.content
            for attachment in decoded where attachment.isPlayableMedia {
                let line = attachment.mediaContextLine
                guard !line.isEmpty else { continue }
                text = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? line
                    : text + "\n" + line
            }

            // 只有当轮那一条消息会真的带上二进制（音频/base64、视频关键帧）。
            let parts = mediaParts[message.id]
            var audio: [WireAudioPart] = []
            if let parts {
                audio = parts.audio
                images.append(contentsOf: parts.images)
                let note = parts.note.trimmingCharacters(in: .whitespacesAndNewlines)
                if !note.isEmpty {
                    text = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? note
                        : text + "\n" + note
                }
            }

            // 正文为空时补一句「（发来一张图片）」：模型至少知道发生过这件事。
            // 语音/视频消息的正文里已经有 `mediaContextLine`，不会走到这里。
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                text = "（发来一张图片）"
            }

            // 0.15：给「他说过的话」前面挂上本地时刻与说话人（工具/系统消息不加）。
            if timestamped {
                let prefix = HistoryTimestamp.prefix(
                    createdAt: message.createdAt, now: now, role: message.role,
                    language: language, calendar: calendar
                )
                if !prefix.isEmpty { text = prefix + text }
            }

            result.append(WireMessage(
                role: message.role == .user ? "user" : "assistant",
                content: text,
                images: images,
                audio: audio
            ))
        }
        return result
    }

    /// 按字数预算从**最新**往前留（Android `trimHistory`）。
    ///
    /// 「至少留一条」是刻意的：最新那句就算超预算也必须发出去，否则这次请求没有意义。
    public static func trimHistory(_ history: [WireMessage], budget: Int = PromptBuilder.contextCharBudget) -> [WireMessage] {
        if history.isEmpty { return history }
        var kept: [WireMessage] = []
        var chars = 0
        for message in history.reversed() {
            if !kept.isEmpty && chars + message.content.count > budget { break }
            kept.insert(message, at: 0)
            chars += message.content.count
        }
        return kept
    }

    /// 一次请求要发的完整消息列表：一条 system + 裁剪过的历史。
    public static func wireMessages(system: String, history: [WireMessage],
                                    budget: Int = PromptBuilder.contextCharBudget) -> [WireMessage] {
        [WireMessage(role: "system", content: system)] + trimHistory(history, budget: budget)
    }

    /// 按**模型窗口**装配（`spec/api-defaults/README.md` 的「上下文预算计算使用 1M 上限」）。
    ///
    /// 聊天走这一条：窗口 → 字数预算 → 裁剪，默认 1M 下结果与旧的 28 000 字上限相同，
    /// 窗口被配小时才会真的少带历史。
    public static func wireMessages(
        system: String,
        history: [WireMessage],
        contextTokens: Int
    ) -> [WireMessage] {
        wireMessages(
            system: system, history: history,
            budget: historyCharBudget(contextTokens: contextTokens)
        )
    }
}
