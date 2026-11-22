package com.yap.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天系统提示词的拼装（`spec/persona-guidance/README.md` §1 / §6.3），
 * 0.15 按 `spec/context-profile/README.md` 的 **state_first** 顺序重排：
 *
 * ```
 * # 你此刻的状态   —— 时间、当前活动、心情、安静时段           （当前状态）
 * # 引导          —— g12 / g17：这一轮怎么拿捏语气              （当前状态的一部分）
 * # 你是谁        —— 身份、第一人称、不是助手                    （人设）
 * # 你所在的世界   —— WorldPreset                              （人设）
 * # 你怎么说话     —— SpeechStyle                               （人设）
 * # 你们的关系     —— 关系、称呼、你们之间                       （人设）
 * # 你能做什么     —— 工具清单                                  （工具）
 * # 别像 AI       —— 反 AI 五条                                 （反 AI）
 * （可变部分：记忆 / 念头 —— 由 ContextAssembly 插在这里）
 * ## 语言         —— 语言指令
 * # 边界          —— 不暴露 AI、不旁白、不总结人设、不替用户决定
 * ```
 *
 * 为什么状态在前：Bench v2 的坐标下降里 state_first 比「人设在前」高（85.3 vs 84.3，
 * 硬失败率 23.3% vs 26.7%）。指引只说「调顺序」，所以每段**内容来源不变**，
 * 只是从「人设 → 状态」改成「状态 → 人设」。
 *
 * 这一段里所有**固定中文**都来自 [Scaffold]：它是 `spec/persona-guidance/scaffold.json` 的逐字副本，
 * 由 `PersonaPromptScaffoldContractTest` 双向比对（spec 有的键必须有常量、常量不许比 spec 多一个字符）。
 * 为什么要绕这么一圈：同一份脚手架在 iOS 端也有一份副本，规范 §1 要求「两端逐字一致」，
 * 靠人去核对两边的中文字符串迟早会漂；写成「spec 文件 + 两端各自的常量 + 读取 spec 的契约测试」
 * 之后，任何一端改了一个标点都会立刻变红。运行时的值（名字、世界、心情……）只用 `{占位符}` 填进去，
 * 不在业务代码里另写一套中文。
 *
 * 这是一个纯函数：时间、世界、话风、心情都由调用方传进来，不读库、不读 Android 资源，
 * 所以「提示词里到底写了什么」可以逐条断言，而不是靠肉眼看日志。
 *
 * 刻意不出现「{名字}觉得」这种第三人称模板句：负向指令也只写「用第三人称称呼自己」，
 * 不把具体名字和「觉得」拼在一起，免得提示词本身变成反例。
 */
object PromptBuilder {

    /**
     * 规范中文脚手架（`spec/persona-guidance/scaffold.json`）的逐字副本。
     *
     * 规则只有一条：**这里每个字符都必须和 spec 的 JSON 一模一样**，包括「」这类全角符号，
     * 包括 `{until} 结束` 中间的半角空格。占位符（`{name}` / `{meta}` / `{era}` …）刻意保留成
     * `{键名}` 的形状，运行时由 [PromptBuilder] 用 `replace` 填，别在这里提前拼好。
     *
     * 键名与 spec 的 `sections` / `lines` 一一对应：`identity` → [SECTION_IDENTITY]，
     * `identity_body` → [IDENTITY_BODY]，`voice_rules` → [VOICE_RULES]，以此类推。
     * [VALUES_BY_KEY] 把这张对应关系显式列出来，契约测试照着它和 JSON 双向比对。
     */
    object Scaffold {

        // ---------------------------------------------------------- sections

        const val SECTION_IDENTITY = "# 你是谁"
        const val SECTION_WORLD = "# 你所在的世界"
        const val SECTION_STATE = "# 你此刻的状态"
        const val SECTION_VOICE = "# 你怎么说话"
        const val SECTION_RELATIONSHIP = "# 你们的关系"
        const val SECTION_BOUNDARIES = "# 边界"

        // ------------------------------------------------------------- lines

        const val IDENTITY_BODY =
            "你不是助手，你是{name}本人。用第一人称「我」说话；不要用第三人称称呼自己，也不要旁白式描述自己。"
        const val IDENTITY_BODY_WITH_META =
            "你不是助手，你是{name}本人（{meta}）。用第一人称「我」说话；不要用第三人称称呼自己，也不要旁白式描述自己。"

        const val WORLD_EMPTY = "你没有特别的世界设定，按普通人的日常来。"
        const val WORLD_NAME = "你所在的地方：{worldName}。"
        const val WORLD_INTRO = "{era}。{description}"
        const val WORLD_NOTES = "其他背景：{notes}"
        const val WORLD_HINT = "这些是你的生活背景：只在跟话题相关时带一句，不要主动汇报日程，也不要念设定。"

        const val STATE_LINE = "现在是{weekday} {time}。"
        const val STATE_IDLE = "你没在忙什么。"
        const val STATE_BUSY =
            "你在{activity}，大概 {until} 结束。这段时间你不太有空：回复短一点，明确说自己几点后再说；不要谎称有空，也不要主动开新话题。"
        const val STATE_LIMITED = "你在{activity}，大概 {until} 结束。可以回，但会慢一点。"
        const val STATE_MOOD = "心情：{mood}（强度 {intensity}/5）。"

        val VOICE_RULES = listOf(
            "句子短（1-2 句），少感叹号，少书面词。",
            "情绪压着说：先给事实，被戳到才多露半句。"
        )
        const val VOICE_TONE = "你的说话风格：{tone}。"
        const val VOICE_PHRASES = "你平时会说的话（自然使用，最多带 1 处，不要每句都套）：{phrases}。"
        const val VOICE_NO_NARRATION = "不要写动作描写、不要括号旁白。"

        const val RELATIONSHIP_DEFAULT = "你们是要好的朋友。"

        val BOUNDARY_RULES = listOf(
            "不解释 AI 身份、不复述人设、不总结这段对话，也不替对方做决定。",
            "不写动作描写、不写括号里的神态；不要用第三人称讲自己。",
            "不知道的事就说「不清楚」「回头再说」，不要编造现实世界的事实。",
            "不要心理分析对方，也不要每句都用同一个口头禅开头。"
        )

        /**
         * spec 键名 → 常量值（`sections` 与 `lines` 的键名原样使用）。
         *
         * 它同时是「每个键都有常量」的编译期保证（少了常量这里就编不过）和契约测试的入口：
         * 键集合必须与 JSON 完全相等，值必须逐字相等。字符串键的值是 `String`，
         * `voice_rules` / `boundary_rules` 的值是 `List<String>`。
         */
        val VALUES_BY_KEY: Map<String, Any> = linkedMapOf(
            "identity" to SECTION_IDENTITY,
            "world" to SECTION_WORLD,
            "state" to SECTION_STATE,
            "voice" to SECTION_VOICE,
            "relationship" to SECTION_RELATIONSHIP,
            "boundaries" to SECTION_BOUNDARIES,
            "identity_body" to IDENTITY_BODY,
            "identity_body_with_meta" to IDENTITY_BODY_WITH_META,
            "world_empty" to WORLD_EMPTY,
            "world_name" to WORLD_NAME,
            "world_intro" to WORLD_INTRO,
            "world_notes" to WORLD_NOTES,
            "world_hint" to WORLD_HINT,
            "state_line" to STATE_LINE,
            "state_idle" to STATE_IDLE,
            "state_busy" to STATE_BUSY,
            "state_limited" to STATE_LIMITED,
            "state_mood" to STATE_MOOD,
            "voice_rules" to VOICE_RULES,
            "voice_tone" to VOICE_TONE,
            "voice_phrases" to VOICE_PHRASES,
            "voice_no_narration" to VOICE_NO_NARRATION,
            "relationship_default" to RELATIONSHIP_DEFAULT,
            "boundary_rules" to BOUNDARY_RULES
        )
    }

    /** 身份/世界/话风/状态/关系/可变部分/语言段的原料。 */
    data class Inputs(
        val name: String = "",
        val age: String = "",
        val role: String = "",
        /** 人设卡里的「和你的关系」。 */
        val relation: String = "",
        /** 怎么称呼对方。 */
        val address: String = "",
        val card: PersonaCard = PersonaCard(),
        /** 旧版一整段提示词：只有在没有结构化人设卡时才作为正文出现。 */
        val legacyPrompt: String = "",
        /** `PersonaProfile.promptBlock`（性格/喜欢/习惯/过去/设定）。 */
        val profileBlock: String = "",
        val world: WorldPreset? = null,
        val speech: SpeechStyle? = null,
        val activity: PersonaActivity? = null,
        val now: Long = 0L,
        val zone: ZoneId = ZoneId.systemDefault(),
        /** `MoodPrompt.line(...)` 的原文：标题会被剥掉，正文拆成「心情 + 强度」并进「你此刻的状态」。 */
        val moodBlock: String = "",
        /**
         * 多维心情（schema v17，`spec/mood/README.md` §3）。
         *
         * 有它就用「心情 + 强度 + 三个维度 + 行为提示」的多维段；没有（老调用方、人格卡预览）
         * 才退回 [moodBlock] 的单行渲染。两者都不会同时出现，免得提示词里写两遍心情。
         */
        val moodDetail: MoodReading? = null,
        /** 现在是不是他平时的睡觉时间（安静时段）。 */
        val asleep: Boolean = false,
        /** 额外状态（presence / 今天话多话少），一行一句，并进状态段。 */
        val extraStatus: String = "",
        /**
         * 「# 你能做什么」段（工具用途）。
         *
         * 内容由 [ToolCatalog.capabilityPrompt] 从工具注册表生成，这里只负责放在「关系」之后、
         * 记忆/念头之前：模型先知道自己是谁、在哪、能做什么，再看这一轮想起了什么。
         */
        val capabilitiesSection: String = "",
        /**
         * 模型优化档（schema v20，[ModelProfile]）：决定 `# 工具使用` 这一段注入不注入。
         *
         * 只影响这一段；其它段（人设/世界/话风/记忆/边界）与 [ModelProfile.GLM] 一律无关，
         * 所以「换优化档」不会让人格说话换一个样子。
         */
        val modelProfile: ModelProfile = ModelProfile.AUTO,
        /** 这一轮真正要发出去的 model 名；[ModelProfile.AUTO] 靠它推断（含 `glm` → GLM）。 */
        val modelName: String = "",
        /** 配置里有没有联网能力：没有时 `# 工具使用` 不提 `web_search`。 */
        val webToolsEnabled: Boolean = true,
        /** 记忆、念头……放在边界之前。 */
        val variableBlocks: List<String> = emptyList(),
        /** 语言指令（`LanguageDirective.section`），放在边界之前。 */
        val languageSection: String = "",
        /**
         * 0.15：人格自己选定的提示词语言（`PromptLanguage`，与界面语言无关）。
         *
         * 只影响**规则段**（别像 AI / 边界 / 语言指令 / 工具说明）；身份、世界、话风这些
         * 数据段是用户自己写的中文，原样保留——翻译它们属于人格翻译缓存的职责，
         * 不能在这里偷偷改写成英文，否则模型会拿着半英半中的「他」说话。
         */
        val language: PromptLanguage = PromptLanguage.ZH
    )

    private const val LEGACY_MAX = 1600
    private const val PROFILE_MAX = 1200
    private const val BOND_MAX = 600

    /** 话风里「习惯 / 不要」各最多进几行：再多就变成一份说明书，模型会照抄句式。 */
    private const val MAX_SPEECH_TRAITS = 6

    /** 名字全空时的兜底称呼：模板里 `{name}` 后面还有「本人」，所以这里给一个能读通的词。 */
    private const val UNNAMED = "对方的那个人"

    /** 人设卡里「小习惯」的固定前缀（有学来的话风时作为一句补充）。 */
    private const val QUIRK_PREFIX = "- 小习惯："

    /** 安静时段的固定句子：它不在脚手架里（§6.3 把它归入「已经睡了…」的自由尾巴），保留原文。 */
    private const val ASLEEP_SENTENCE = "这个点你已经睡了，现在是被消息叫醒的；回得短、慢一点也正常。"

    /**
     * `MoodPrompt.line` 的第二行固定形状：`有点累（强度 3/5，明显）。原因：…`
     *
     * group(1) = 心情词，group(2) = 强度数字，group(3) = 括号之后剩下的「原因 + 行为提示」。
     */
    private val MOOD_LINE = Regex(
        "(.*?)（强度\\s*(\\d+)\\s*/\\s*5[^）]*）(.*)",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** 日期只要 `yyyy-MM-dd`：星期与时区偏移各自单独拼，避免受 Locale 影响。 */
    private val YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to "周一",
        DayOfWeek.TUESDAY to "周二",
        DayOfWeek.WEDNESDAY to "周三",
        DayOfWeek.THURSDAY to "周四",
        DayOfWeek.FRIDAY to "周五",
        DayOfWeek.SATURDAY to "周六",
        DayOfWeek.SUNDAY to "周日"
    )

    /**
     * 完整系统提示词（state_first）：当前状态 + 引导 → 人设 → 工具 → 反 AI → 记忆/念头 → 语言 → 边界。
     *
     * 这是不开 [ContextAssembly] 的调用方（测试生成、主动消息预览）用的整份拼装；
     * 聊天主路径由 [ContextAssembly] 按同一顺序拼，记忆段插在反 AI 之后。
     */
    fun system(input: Inputs): String = buildString {
        append(stateBodyWithHeading(input))
        appendSection(guidanceBlock(input))
        appendSection(rhythmBlock(input))
        appendSection(personaBlock(input))
        appendSection(toolsBlock(input))
        appendSection(antiAiSection(input.language))
        input.variableBlocks.map { it.trim() }.filter { it.isNotBlank() }.forEach { appendSection(it) }
        appendSection(input.languageSection.trim())
        appendSection(boundaries(input.language))
    }.trim()

    /**
     * 状态 + 人设 + 工具（不含引导/反 AI/记忆/语言/边界）；
     * 给主动思考这类需要自己接尾巴的调用方用（`PersonaCardRepository.systemText`）。
     *
     * 顺序与 [system] 一致：状态在最前，人设与工具依次跟上；只是没有「这一轮」才需要的
     * 引导与反 AI 段——主动消息没有对话搭档，不该背一份「不要总结对方」。
     */
    fun base(input: Inputs): String = buildString {
        appendSection(stateBodyWithHeading(input))
        appendSection(personaBlock(input))
        appendSection(toolsBlock(input))
    }.trim()

    // -------------------------------------------------- 0.15 可组合段（state_first）

    /**
     * 状态段（含 `# 你此刻的状态` 标题）。
     *
     * 与 [currentStateBody] 的区别只有标题：聊天主路径用 [currentStateBody]，
     * 让 [ContextAssembly] 用共享契约的标题来拼；其它调用方（[system] / [base]）用这一份。
     */
    fun stateBodyWithHeading(input: Inputs): String =
        Scaffold.SECTION_STATE + "\n" + statusBody(input)

    /** 人设四段：身份 → 世界 → 话风 → 关系（内容来源不变，只负责拼装）。 */
    fun personaBlock(input: Inputs): String = listOf(
        identitySection(input),
        worldSection(input.world),
        speechSection(input),
        relationshipSection(input)
    ).joinToString("\n\n").trim()

    /**
     * 工具段：`# 你能做什么`（工具清单，永远有）+ `# 工具使用`（DeepSeek 专属规则）。
     *
     * 0.15 的模型优化开关只改第二段：GLM（含 AUTO + 模型名含 glm）是 lean，不注入；
     * DeepSeek 注入。两段内容都不为空时用空行隔开，和别的段一样由 [appendSection] 处理。
     */
    fun toolsBlock(input: Inputs): String = listOf(
        input.capabilitiesSection.trim(),
        ModelProfilePrompt.toolsSection(
            profile = input.modelProfile,
            modelName = input.modelName,
            webEnabled = input.webToolsEnabled,
            language = input.language
        )
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    /** 语言小节（`## 语言` / `## Language`）：去掉调用方可能带的前导换行。 */
    fun languageBlock(input: Inputs): String = input.languageSection.trim()

    /** 拼一段：空段整段跳过，段之间固定两个换行。 */
    private fun StringBuilder.appendSection(text: String) {
        val block = text.trim()
        if (block.isEmpty()) return
        if (isNotEmpty()) append("\n\n")
        append(block)
    }

    // ------------------------------------------------------------------ 引导

    /** `spec/context-profile/README.md` 的「# 引导」段标题（不在 scaffold.json 里，单独一段）。 */
    const val SECTION_GUIDANCE = "# 引导"

    /** 英文人格的引导段标题（与 [SECTION_GUIDANCE] 对应）。 */
    private const val SECTION_GUIDANCE_EN = "# Guidance"

    /**
     * g12：心情偏负 / 低唤醒时「有点防备，不轻易示弱」。
     *
     * 逐字固定（`spec/context-profile/README.md`「注入规则」）：第一条是这一轮的总调子，
     * 后三条是具体的说话动作。只在 [guidanceBlock] 里渲染，不在别处另写一份。
     */
    val GUIDANCE_G12: List<String> = listOf(
        "这一轮：有点防备，不轻易示弱",
        "用短句挡一下",
        "被问急了才多露半句",
        "不要掏心掏肺。"
    )

    /** g17：其余情况「只聊此刻，不翻旧账」。 */
    val GUIDANCE_G17: List<String> = listOf(
        "这一轮：只聊此刻，不翻旧账",
        "回应现在这句话",
        "不延伸过去",
        "不要反问。"
    )

    /**
     * 「# 引导」段：按这一轮的心情选 g12 或 g17（`spec/context-profile/README.md`）。
     *
     * 判定只有一条线，且不改心情数值/判定逻辑：
     * - 愉快度 < 0 或唤醒度 ≤ 2（偏低/低能量）→ g12（有点防备）；
     * - 其余（心情明显不错、能量也够）→ g17（只聊此刻）；
     * - 没有多维心情（老调用方、人格卡预览、读库失败）→ g17：**判不了就不假装他防备**。
     *
     * 位置固定：心情之后（状态段末尾）、话风之前（[ContextAssembly] / [system] 里的状态块最后一段）。
     */
    fun guidanceBlock(input: Inputs): String {
        // 空标签 = 还没有心情（与服务端 `PromptGuidance.select` 同一口径：判不出就当没有）。
        val mood = input.moodDetail?.takeIf { !it.isEmpty() }
        val guarded = mood != null && (mood.valence < 0 || mood.arousal < 2)
        val lines = if (input.language == PromptLanguage.ZH) {
            if (guarded) GUIDANCE_G12 else GUIDANCE_G17
        } else {
            if (guarded) GUIDANCE_G12_EN else GUIDANCE_G17_EN
        }
        val title = if (input.language == PromptLanguage.ZH) SECTION_GUIDANCE else SECTION_GUIDANCE_EN
        return buildString {
            append(title)
            lines.forEachIndexed { index, line ->
                if (index == 0) append("\n").append(line) else append("\n- ").append(line)
            }
        }
    }

    // ------------------------------------------------------------------ 回复节奏

    /**
     * 「# 回复节奏」段标题（0.16 的「像人」节奏，与工具协议同一条线）。
     *
     * 不在 `scaffold.json` 里，所以单独一段、不进 [Scaffold.VALUES_BY_KEY]；位置固定：
     * 紧跟「# 引导」之后、人设之前——先定这一轮的调子（引导），再定这一轮**发几条、多长**
     * （节奏），然后才是「他是谁」。两段都回答「这一轮怎么说」，连读最自然；
     * 它们也紧挨着工具段（`# 你能做什么`），发消息的规则与工具协议在提示词里是一路的。
     */
    const val SECTION_RHYTHM = "# 回复节奏"

    /** 英文人格的节奏段标题（与 [SECTION_RHYTHM] 对应）。 */
    const val SECTION_RHYTHM_EN = "# Reply pacing"

    /**
     * 节奏段的中文条目（逐条可断言）。
     *
     * 为什么这几条：0.16 起每一句话都要走 `send_message`，于是「一轮几条」变成模型可以直接
     * 决定的事——它很容易为了显得热络而连发、或者把一句话拆成两条。这五条把「像人」的默认值
     * 写死：一条、短、先接情绪、不拆句、深夜更短但不敷衍。
     *
     * 18 字是**提示词层面的目标**，不是硬限制：`send_message` 的硬上限仍是
     * [ExperienceRhythm.MAX_SEND_MESSAGE_CHARS]（120 字），超了要能把话说完而不是被丢掉。
     */
    val RHYTHM_ZH: List<String> = listOf(
        "默认只发 1 条。",
        "只有真的有新信息（新想法、新问题、没说完的那半句）才发第 2 条。",
        "每条不超过 18 个字，短句更像随口说的。",
        "先接住对方的情绪，再说你自己的事。",
        "不要为了发两条而把一句话拆开。",
        "深夜、或者你累了，可以更短，但别只回四个字。"
    )

    /** 节奏段的英文条目（与 [RHYTHM_ZH] 一一对应，非中文人格用）。 */
    val RHYTHM_EN: List<String> = listOf(
        "Send one message by default.",
        "Only send a second when it truly adds something new (a new thought, a question, the rest of a sentence).",
        "Keep each message under 18 characters; short lines sound more offhand.",
        "Acknowledge their feeling first, then say your own part.",
        "Don't split one sentence into two just to send more.",
        "Late at night or when tired you can be shorter, but never reply with just four characters."
    )

    /**
     * 「# 回复节奏」段（按人格语言选）。
     *
     * 纯函数、不读时钟：同一个「他」在任何时刻拿到的节奏规则都一样；深夜更短那一条
     * 由他自己按状态段里的时间判断，这里只给规则，不在这里做时间分支——否则同一份提示词
     * 会因为「现在几点」出现两种字面，契约测试就没法逐字钉住。
     */
    fun rhythmBlock(input: Inputs): String {
        val zh = input.language == PromptLanguage.ZH
        val title = if (zh) SECTION_RHYTHM else SECTION_RHYTHM_EN
        val lines = if (zh) RHYTHM_ZH else RHYTHM_EN
        return buildString {
            append(title)
            lines.forEach { line -> append("\n- ").append(line) }
        }
    }

    private val GUIDANCE_G12_EN: List<String> = listOf(
        "This turn: a little guarded; don't show weakness easily",
        "Deflect with short sentences",
        "Only open up half a line if they push",
        "Don't pour your heart out."
    )

    private val GUIDANCE_G17_EN: List<String> = listOf(
        "This turn: only talk about right now; don't dredge up the past",
        "Answer this sentence",
        "Don't extend into the past",
        "Don't ask questions back."
    )

    /**
     * 0.15：语言小节按**人格自己的语言**生成（与界面语言无关）。
     *
     * 这是 0.15 最关键的一处「跟随人格」：以前这段来自 `R.string.prompt_language_directive`，
     * 用户把界面切成英文，中文人格就开始用英文思考。现在语言是人格属性
     * （`personas.languageTag`），指令由共享契约 [PromptTemplates.directiveForPersona] 给。
     *
     * @param personaTag 人格上的语言标签；空/认不出按中文
     */
    fun languageSectionFor(personaTag: String?): String {
        val language = PromptLanguage.resolve(personaTag)
        val title = if (language == PromptLanguage.ZH) PromptContract.Section.LANGUAGE else LANGUAGE_EN
        return title + "\n- " + PromptTemplates.directiveForPersona(personaTag)
    }

    /** 英文人格的语言小节标题（中文标题来自共享契约，不在这里重复）。 */
    private const val LANGUAGE_EN = "## Language"

    /**
     * 0.15：把已经拼好的提示词里的**段标题**换成英文（人格语言不是中文时）。
     *
     * 为什么用替换而不是给每段都加一个语言分支：段标题是提示词里最稳的字面量
     * （共享契约 + 脚手架逐字固定），一次替换能同时覆盖「完整设定」「当前状态」
     * 与 [ContextAssembly] 后面追加的记忆/主题段；正文（用户写的人设、模型的回忆）
     * 一律不动——那是数据，翻译属于人格翻译缓存的职责。
     *
     * 只替换完整标题行（行首行尾），不会误伤正文里恰好出现的同样文字。
     */
    fun localizeSectionTitles(text: String, language: PromptLanguage): String {
        if (language == PromptLanguage.ZH || text.isBlank()) return text
        var result = text
        SECTION_TITLES_EN.forEach { (zh, en) ->
            result = result.replace(Regex("(?m)^" + Regex.escape(zh) + "$"), en)
        }
        return result
    }

    /** 段标题的中英对照（只覆盖有固定字面量的标题；数据段正文不翻译）。 */
    private val SECTION_TITLES_EN: Map<String, String> = linkedMapOf(
        Scaffold.SECTION_IDENTITY to "# Who you are",
        Scaffold.SECTION_WORLD to "# The world you live in",
        Scaffold.SECTION_STATE to "# Your state right now",
        Scaffold.SECTION_VOICE to "# How you speak",
        Scaffold.SECTION_RELATIONSHIP to "# Your relationship",
        SECTION_GUIDANCE to SECTION_GUIDANCE_EN,
        PromptContract.Section.MEMO to PromptTemplates.title(
            PromptTemplates.CoreSection.MEMO,
            PromptLanguage.EN
        ),
        MemoryContract.Injection.LONG_TERM_TITLE to "## Long-term memory",
        MemoryContract.Injection.SHORT_TERM_TITLE to "## Short-term memory",
        MemoryContract.Injection.TOPIC_TITLE to "## Topics",
        PromptContract.Section.MENTAL_ACTIVITY to PromptTemplates.title(
            PromptTemplates.CoreSection.MENTAL_ACTIVITY,
            PromptLanguage.EN
        )
    )

    /**
     * 0.15 上下文装配的**第一段**（state_first）：**当前状态**正文（不含 `# 你此刻的状态` 标题）。
     *
     * 标题由调用方（[ContextAssembly]）用共享契约 [PromptContract.Section.STATE] 补，
     * 保证「状态段写什么」与「标题在哪里」各只有一份；[guidanceBlock] 紧接其后单独成段。
     */
    fun currentStateBody(input: Inputs): String = statusBody(input)

    /** 「别像 AI」段的标题（规范 §5；不在 `scaffold.json` 里，所以单独一段）。 */
    const val SECTION_ANTI_AI = "# 别像 AI"

    /**
     * 反 AI 味约束（`spec/mood/README.md` §5；0.15 收敛成 **core 五条**，
     * `spec/context-profile/README.md`「反 AI 规则 core」）。
     *
     * 为什么单独一段而不是并进「你怎么说话」：话风是**用户/模型学来的数据**（句子短、少感叹号），
     * 这一段是**产品级的行为禁忌**（不反问、不总结、不列点、不总安慰、不旁白），
     * 对所有人生效、也不该被用户填的话风覆盖。state_first 顺序里放在工具之后、记忆之前，
     * 与「越靠前越强」一致；它不占用脚手架六段的位置（标题也不在 scaffold.json 里）。
     *
     * 0.15 删掉的两条（Bench v2 实测偏弱、还和话风段重复）：
     * 「句子可以短、可以不完整」「不主动给建议、清单或心理分析」。留下五条逐字固定，
     * 中英文人格各有一一对应的五条（[ANTI_AI_RULES] / [EN_ANTI_AI_RULES]）。
     */
    fun antiAiSection(language: PromptLanguage = PromptLanguage.ZH): String {
        val title = if (language == PromptLanguage.ZH) {
            SECTION_ANTI_AI
        } else {
            PromptTemplates.title(PromptTemplates.CoreSection.ANTI_AI, language)
        }
        val rules = if (language == PromptLanguage.ZH) ANTI_AI_RULES else EN_ANTI_AI_RULES
        return buildString {
            append(title)
            rules.forEach { append("\n- ").append(it) }
        }
    }

    /**
     * 反 AI core 五条（中文人格，逐字固定）。
     *
     * 顺序 = 最常犯 → 最隐蔽：反问 → 总结 → 列点 → 总安慰 → 旁白。
     * 公开是为了让契约测试逐字比对，而不是在测试里再抄一份中文。
     */
    val ANTI_AI_RULES: List<String> = listOf(
        "不要每句话都反问；不需要每轮都把话题抛回去。",
        "不要总结对方说的话，也不要说「所以你的意思是…」。",
        "不要列点、不要「首先/其次/最后」，不要写成一段说明书。",
        "不要每次都认同或安慰；可以不同意、可以只回一句、也可以不回（stay_silent）。",
        // 0.15 实测：模型反复写「沉默了一会儿」「叹了口气」这类旁白/舞台说明。
        // 这一条放在最后（最隐蔽、也最破坏「像人」的一条）：他是在聊天，不是在演。
        "不写动作、神态或心理描写的旁白（如「沉默了一会儿」「叹了口气」「苦笑」）；直接说话，不要舞台说明。"
    )

    /** 反 AI core 五条（英文人格）：与 [ANTI_AI_RULES] 一一对应，顺序不变。 */
    val EN_ANTI_AI_RULES: List<String> = listOf(
        "Don't end every sentence with a question; you don't have to throw the topic back every turn.",
        "Don't summarize what they said, and don't say \"so what you mean is…\".",
        "No bullet lists, no \"first / second / finally\", no manual-style paragraphs.",
        "Don't agree or comfort every time; you may disagree, reply with one sentence, " +
            "or not reply at all (stay_silent).",
        "No narration, stage directions, or descriptions of your actions/face " +
            "(\"stayed silent for a while\", \"sighed\"). Just talk."
    )

    /**
     * §1.1 / §1.2 的边界：放在整份提示词最后，约束最容易被忘掉的那几件事。
     *
     * 逐条来自 [Scaffold.BOUNDARY_RULES]（旧版本这里是 6 条自己写的中文；规范定稿成 4 条之后，
     * 「常用语最多带一处」并进了 [Scaffold.VOICE_PHRASES]，「不替自己旁白」并进了第 2 条）。
     */
    fun boundaries(language: PromptLanguage = PromptLanguage.ZH): String =
        if (language == PromptLanguage.ZH) {
            buildString {
                append(Scaffold.SECTION_BOUNDARIES)
                Scaffold.BOUNDARY_RULES.forEach { append("\n- ").append(it) }
            }
        } else {
            PromptTemplates.render(PromptTemplates.CoreSection.BOUNDARIES, language)
        }

    // ------------------------------------------------------------------ 各段

    /**
     * §6.3 的「你是谁」段。
     *
     * 脚手架只给了一行：`你不是助手，你是{name}本人（{meta}）。…`
     * 人设卡的性格/作息、`profileBlock`、旧版整段提示词都接在这一行之后——它们是**数据**，
     * 不是规范文案，所以不会和 spec 的键发生冲突。
     */
    private fun identitySection(input: Inputs): String {
        val name = input.name.trim()
        val age = input.age.trim()
        val role = input.role.trim()
        val who = name.ifBlank { UNNAMED }
        // {meta}：年龄在前、身份在后，空的那半不进括号（避免「（，项目负责人）」）。
        val meta = listOf(age, role).filter { it.isNotBlank() }.joinToString("，")
        val headline = if (meta.isBlank()) {
            fill(Scaffold.IDENTITY_BODY, "name" to who)
        } else {
            fill(Scaffold.IDENTITY_BODY_WITH_META, "name" to who, "meta" to meta)
        }
        val body = buildString {
            appendLine(headline)
            cardLines(input.card).forEach(::appendLine)
            profileBody(input.profileBlock)?.let { appendLine(it) }
            if (input.card.isEmpty() && input.legacyPrompt.isNotBlank()) {
                appendLine(input.legacyPrompt.trim().take(LEGACY_MAX))
            }
        }.trim()
        return Scaffold.SECTION_IDENTITY + "\n" + body
    }

    /**
     * 世界段：唯一实现在 [WorldPreset.renderForPrompt]（那里还要处理日程/关系/义务/notes 的格式），
     * 这里只负责「没有世界时也要有一段」：脚手架给了 [Scaffold.WORLD_EMPTY]，
     * 所以不能再像以前那样整段省略——省略之后模型只会看到前面一句身份，世界感彻底没有。
     */
    private fun worldSection(world: WorldPreset?): String {
        val rendered = world?.renderForPrompt().orEmpty().trim()
        return rendered.ifBlank { Scaffold.SECTION_WORLD + "\n" + Scaffold.WORLD_EMPTY }
    }

    /** 状态段正文（不含标题）：时间 → 在做什么 → 心情 → 已经睡了 → 额外状态。 */
    private fun statusBody(input: Inputs): String {
        val parts = buildList {
            clockLine(input.now, input.zone)?.let { add(it) }
            add(activityForPrompt(input.activity, input.now, input.zone))
            val moodDetail = input.moodDetail
            if (moodDetail != null) {
                // 多维心情段（规范 §3）：标题由「你此刻的状态」承担，这里只加正文行。
                addAll(MoodDimensions.promptLines(moodDetail))
            } else {
                addAll(moodLines(input.moodBlock))
            }
            if (input.asleep) add(ASLEEP_SENTENCE)
            val extra = stripHeading(input.extraStatus)
            if (extra.isNotBlank()) add(extra)
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * §6.3 的「你怎么说话」段。
     *
     * 有学来的话风时，正文完全由 [SpeechStyle.renderForPrompt] 生产（它也只读脚手架），
     * 人设卡里的「小习惯」作为一句补充。没有话风时，仍要给脚手架的两条 [Scaffold.VOICE_RULES]
     * 和 [Scaffold.VOICE_NO_NARRATION]，再把人设卡里的语速/句子/口头禅/小习惯作为额外的行接上——
     * 这些行是用户填的数据，不是规范文案。
     */
    private fun speechSection(input: Inputs): String {
        val speech = input.speech
        val quirks = input.card.voice.quirks.trim().take(100)
        if (speech != null && !speech.isEmpty()) {
            // 学来的「语言习惯 / 不要出现的表达」是用户自己填的**数据**，脚手架没有对应行，
            // 但把它们丢掉会让用户改了半天却完全不影响模型。所以作为数据行接在脚手架之后。
            val dataLines = buildList {
                speech.habits.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_SPEECH_TRAITS)
                    .forEach { add("- 习惯：$it") }
                speech.avoid.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_SPEECH_TRAITS)
                    .forEach { add("- 不要：$it") }
                if (quirks.isNotBlank()) add(QUIRK_PREFIX + quirks)
            }
            val body = (listOf(speech.renderForPrompt()) + dataLines).joinToString("\n")
            return Scaffold.SECTION_VOICE + "\n" + body
        }
        // 没有学来的话风：用旧人设卡里的语速/句子/口头禅，再兜一句通用要求。
        val voice = input.card.voice
        val body = buildList {
            addAll(Scaffold.VOICE_RULES)
            if (voice.pace.isNotBlank()) add("- 语速：${voice.pace.trim().take(80)}")
            if (voice.sentence.isNotBlank()) add("- 句子：${voice.sentence.trim().take(80)}")
            val catchphrases = voice.catchphrases.map { it.trim() }.filter { it.isNotBlank() }
                .distinct().take(SpeechStyle.MAX_PROMPT_PHRASES)
            if (catchphrases.isNotEmpty()) {
                // 复用脚手架的 voice_phrases 句型：口头禅的「参考不是模板」这层意思只写一次。
                add(fill(Scaffold.VOICE_PHRASES, "phrases" to catchphrases.joinToString("、") { "「$it」" }))
            }
            if (quirks.isNotBlank()) add(QUIRK_PREFIX + quirks)
            add(Scaffold.VOICE_NO_NARRATION)
        }.joinToString("\n")
        return Scaffold.SECTION_VOICE + "\n" + body
    }

    /** §6.3 的「你们的关系」段：任何一项都没有时，用脚手架的 [Scaffold.RELATIONSHIP_DEFAULT]。 */
    private fun relationshipSection(input: Inputs): String {
        val relation = input.relation.trim()
        val address = input.address.trim()
        val bond = input.card.bond.trim().take(BOND_MAX)
        val lines = buildList {
            if (relation.isNotBlank()) add("- 你是对方的$relation。")
            if (address.isNotBlank()) add("- 你称呼对方「$address」。")
            if (bond.isNotBlank()) add(bond)
        }
        val body = if (lines.isEmpty()) Scaffold.RELATIONSHIP_DEFAULT else lines.joinToString("\n")
        return Scaffold.SECTION_RELATIONSHIP + "\n" + body
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 时间句；拿不到时间（`now <= 0`）或时区换算失败时返回 `null`，由调用方整行省略。
     *
     * 规范依据是 `spec/context-time/README.md`：**每一轮请求**都注入请求发出瞬间的本地时间
     * （`YYYY-MM-DD 周X HH:mm` + 时区偏移 `UTC±HH:mm`），不缓存、不按会话保存。
     * 所以这里全部由调用方传进来的 `now` / `zone` 现算，类里没有任何时间缓存字段——
     * 跨天或改时区后同一会话的下一条消息就会拿到新的日期与偏移。
     *
     * 为什么不新增一个脚手架键：`spec/persona-guidance/scaffold.json` 的 `state_line` 只有
     * `{weekday}` / `{time}` 两个占位符，而本轮不改 `spec/`，契约测试要求常量与 spec 逐字一致。
     * 于是把两个占位符的**填充内容**加宽：`{weekday}` 填「日期 + 星期」，`{time}` 填「时刻（UTC±HH:mm）」，
     * 渲染出来是 `现在是2026-11-18 周三 19:40（UTC+08:00）。`——模板仍然只有一个，中文固定文案没有第二份。
     */
    private fun clockLine(now: Long, zone: ZoneId): String? {
        val parts = clockParts(now, zone) ?: return null
        return fill(Scaffold.STATE_LINE, "weekday" to parts.first, "time" to parts.second)
    }

    /**
     * 聊天之外的地方（主动消息的处境描述）共用同一份时间格式：`YYYY-MM-DD 周X HH:mm（UTC±HH:mm）`。
     *
     * 只给一行文本、不带「现在是…。」的脚手架句式——那边自己有一句「现在：」；
     * 但**日期/星期/时刻/偏移的写法只有这一处**，两端不会各写一套。
     *
     * @return 拿不到时间（`now <= 0` / 时区换算失败）时返回 `null`
     */
    fun clockText(now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val parts = clockParts(now, zone) ?: return null
        return "${parts.first} ${parts.second}"
    }

    /** `(日期 + 星期, 时刻 + 时区偏移)`：两个时钟渲染入口的唯一真源。 */
    private fun clockParts(now: Long, zone: ZoneId): Pair<String, String>? {
        if (now <= 0L) return null
        val zoned = runCatching { Instant.ofEpochMilli(now).atZone(zone) }.getOrNull() ?: return null
        val weekday = WEEKDAYS[zoned.dayOfWeek] ?: zoned.dayOfWeek.name
        val day = zoned.format(YYYY_MM_DD)
        val offset = zoneOffsetLabel(zoned.offset)
        return "$day $weekday" to "${zoned.format(HH_MM)}（$offset）"
    }

    /** `UTC+08:00` / `UTC-05:30` / `UTC`：偏移按规范写成 `UTC±HH:mm`，不依赖 Locale。 */
    private fun zoneOffsetLabel(offset: java.time.ZoneOffset): String {
        val totalMinutes = offset.totalSeconds / 60
        if (totalMinutes == 0) return "UTC"
        val sign = if (totalMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(totalMinutes)
        return "UTC%s%02d:%02d".format(Locale.ROOT, sign, abs / 60, abs % 60)
    }

    /**
     * 当前活动那一句：过期/没设 → [Scaffold.STATE_IDLE]；BUSY/LIMITED 用脚手架的句子。
     *
     * 为什么这里自己拼而不是直接调 `PersonaActivity.renderForPrompt()`：那一份历史上是给界面/
     * 其它展示用的句子，措辞早于脚手架；提示词必须逐字用 [Scaffold.STATE_BUSY] /
     * [Scaffold.STATE_LIMITED]。这一句只有这一个实现，`PersonaActivity` 想复用可以直接调它
     * （公开是为了让「状态句怎么写」永远不会出现第二份）。
     *
     * FREE 是 Android 的扩展（§4 里 FREE 表示「不影响回复节奏」），套 state_limited 的「回得慢」
     * 会自相矛盾，所以保留原来的「随时能聊」说法，并且只在下面这一处出现。
     */
    fun activityForPrompt(activity: PersonaActivity?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (activity == null || !activity.isActive(now)) return Scaffold.STATE_IDLE
        val doing = activityWord(activity.activity)
        val until = activity.formatUntil(zone)
        return when (activity.availability) {
            ActivityAvailability.BUSY ->
                fill(Scaffold.STATE_BUSY, "activity" to doing, "until" to until)
            ActivityAvailability.LIMITED ->
                fill(Scaffold.STATE_LIMITED, "activity" to doing, "until" to until)
            ActivityAvailability.FREE ->
                "你在$doing，预计 $until 结束，手头是这件事，但随时能聊。"
        }
    }

    /**
     * 填进 `{activity}` 的那一段话。
     *
     * 脚手架写的是「你在{activity}」，而活动数据自己常以「在」「去」开头（「在事务所赶图」），
     * 直接填会变成「你在在事务所」。所以去掉开头的一个「在/去」；去掉之后空了就退回原文。
     */
    private fun activityWord(raw: String): String {
        val text = raw.trim().take(120)
        val stripped = when {
            text.startsWith("在") -> text.removePrefix("在").trim()
            text.startsWith("去") -> text.removePrefix("去").trim()
            else -> text
        }
        return stripped.ifBlank { text }
    }

    /**
     * 把 `MoodPrompt.line` 的正文拆成脚手架的心情句（`心情：{mood}（强度 {intensity}/5）。`）。
     *
     * 拆不出来（例如没有强度、是基准心情「平静。没有特别的起伏。」）时，把整段原文塞进 `{mood}`：
     * 少一个「（强度 N/5）」不要紧，丢掉心情才要紧。
     *
     * 括号之后的「原因：…」和 MoodPrompt 给的那句行为提示原样接在下一行——脚手架没有对应占位符，
     * 但它们是数据（同 `extraStatus`），不是规范文案，所以不违反「固定中文只来自脚手架」。
     */
    private fun moodLines(block: String): List<String> {
        val text = stripHeading(block)
        if (text.isBlank()) return emptyList()
        val match = MOOD_LINE.matchEntire(text)
        val mood = match?.groupValues?.get(1)?.trim().orEmpty()
        val intensity = match?.groupValues?.get(2)?.trim().orEmpty()
        if (match == null || mood.isBlank() || intensity.isBlank()) {
            val prefix = Scaffold.STATE_MOOD.substringBefore("{mood}") // 「心情：」
            val tail = if (text.endsWith("。")) "" else "。"
            return listOf(prefix + text + tail)
        }
        val line = fill(Scaffold.STATE_MOOD, "mood" to mood, "intensity" to intensity)
        val rest = match.groupValues[3].trim().removePrefix("。").trim()
        return if (rest.isBlank()) listOf(line) else listOf(line, rest)
    }

    /** 人设卡里能进身份段的几行（性格/在意/边界/作息/此刻/最近在意）。 */
    private fun cardLines(card: PersonaCard): List<String> = buildList {
        if (card.traits.isNotEmpty()) {
            add("- 性格：" + card.traits.map { it.name.trim() }.filter { it.isNotBlank() }.take(6).joinToString("、"))
        }
        if (card.values.isNotEmpty()) {
            add("- 在意：" + card.values.map { it.trim() }.filter { it.isNotBlank() }.take(6).joinToString("、"))
        }
        if (card.boundaries.isNotEmpty()) {
            add("- 你守着的边界：" + card.boundaries.map { it.trim() }.filter { it.isNotBlank() }.take(6).joinToString("；"))
        }
        if (card.life.schedule.isNotBlank()) {
            add("- 作息：${card.life.schedule.trim().take(120)}")
        }
        if (card.life.doing.isNotBlank()) {
            add("- 平时这个时候：${card.life.doing.trim().take(120)}")
        }
        if (card.life.caresAbout.isNotBlank()) {
            add("- 最近在意：${card.life.caresAbout.trim().take(120)}")
        }
    }.filter { it.length > 4 }

    /** 人设卡的 `## 你是谁` 与本节撞标题：剥掉标题，只留正文。 */
    private fun profileBody(block: String): String? {
        val text = block.trim().take(PROFILE_MAX)
        if (text.isBlank()) return null
        val body = text.lineSequence()
            .dropWhile { it.isBlank() || it.startsWith("## ") || it.startsWith("# ") }
            .joinToString("\n")
            .trim()
        return body.ifBlank { null }
    }

    /** `MoodPrompt.line` / presence 都自带 `## 标题`；并进状态段时剥掉。 */
    private fun stripHeading(block: String): String =
        block.lineSequence()
            .dropWhile { it.isBlank() || it.startsWith("## ") || it.startsWith("# ") }
            .joinToString("\n")
            .trim()

    /** 把脚手架里的 `{键}` 换成运行时值：只做这一种替换，从不反向解析模板。 */
    private fun fill(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { text, (key, value) -> text.replace("{$key}", value) }
}
