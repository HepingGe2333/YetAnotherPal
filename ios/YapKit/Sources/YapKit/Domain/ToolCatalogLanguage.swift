import Foundation

// ---------------------------------------------------------------------------
// 0.15「人格语言」的工具能力段：同一个工具集合，按人格语言给中文或英文说明。
//
// 为什么单独一个文件而不是把字面量塞进 `ToolProtocol.swift`：
// - `ToolCatalog` 的**工具定义**（名称 / 参数 schema / 副作用 / 中文说明）是协议与产品的
//   唯一真源，跟 Android 逐字一致，不能为了英文在旁边改来改去；
// - 英文说明是新增的一层（0.15 才需要），把它放在扩展里，中文侧的调用点一个字都不用动，
//   「中文逐字不变」这件事才验得干净（`ToolCatalog.capabilityPrompt(webEnabled:language: .zh)`
//   直接转调旧入口，测试逐字比对）。
//
// 英文段刻意**不翻译**工具名与参数名：那是模型要写进 `{"name": …}` 的协议字段，
// 翻了就调用不了。翻的只有「什么时候用 / 什么时候别用」与「调用之后世界会变什么」。
// ---------------------------------------------------------------------------

extension ToolCatalog {

    // MARK: - 对外的入口

    /// 「# 你能做什么」段的**按人格语言**版本（0.15）。
    ///
    /// - `.zh`：直接转调 `capabilityPrompt(webEnabled:)`，保证中文逐字不变（含副作用行、
    ///   离线说明与代码块示例）；
    /// - 其余语言：`# What you can do` + 英文段首说明 + 每个工具一行 + 英文离线说明 +
    ///   同一段代码块示例（协议标记 `yap-tool` 不翻译）。
    public static func capabilityPrompt(webEnabled: Bool, language: PromptLanguage) -> String {
        guard language != .zh else { return capabilityPrompt(webEnabled: webEnabled) }
        return englishCapabilityPrompt(webEnabled: webEnabled)
    }

    // MARK: - 英文常量

    /// 英文段首说明：与中文「你只能通过下面这些工具做事…」同一句意思。
    public static let englishCapabilityLead =
        "You can only act through the tools below. If a tool can't do something, say you can't; "
        + "if a call fails, say so. Never pretend you did it."

    /// 英文的「必须写下来 / 必须翻旧账」补充说明。
    ///
    /// 对应中文段在 0.15 新增的那句「关于对方的长期事实必须调用 remember…想起以前说过的事
    /// 必须调用 memorize」。少了这一句，英文人格会把「记住了」当成真的记住了 —— 记忆只写在
    /// 正文里，下一轮就丢了。
    public static let englishMemoryDiscipline =
        "Lasting facts about them (preferences, experiences, promises) must be written with "
        + "remember — saying \"noted\" in the reply doesn't count. To recall something they "
        + "said earlier, call memorize and look it up; don't guess from memory."

    /// 英文的离线说明：替代中文 `OFFLINE_NOTE`（没配联网时告诉他别装懂）。
    public static let englishOfflineNote =
        "- You have no internet access right now. For anything real-time, say plainly that you're "
        + "not sure; don't make it up."

    /// 代码块示例里的英文 query（协议字段 `web_search` 不翻译，参数值可以换成本地语）。
    private static let englishToolBlockExample =
        #"{"name":"web_search","arguments":{"query":"today's weather"}}"#

    // MARK: - 英文生成

    /// 英文版「# What you can do」段（结构逐行对应中文版，便于两端对照维护）。
    public static func englishCapabilityPrompt(webEnabled: Bool) -> String {
        var lines: [String] = [
            PromptTemplates.title(section: .capabilities, language: .en)
        ] + englishOutboundProtocol() + [
            englishCapabilityLead,
            englishMemoryDiscipline
        ]
        for spec in enabled(webEnabled: webEnabled) {
            // 参数签名同样由 schema 推出（`signature` 只拼名字与参数，参数名不翻译）。
            lines.append("- " + signature(spec) + ": " + englishDescription(for: spec))
            if let effect = englishSideEffect(for: spec.name) {
                lines.append("  · What it changes: " + effect)
            }
        }
        if !webEnabled {
            lines.append(englishOfflineNote)
        }
        lines.append(contentsOf: [
            "",
            "If native tool calling isn't available, write a code block (you can chain several):",
            "```\(ToolProtocol.blockLanguage)",
            englishToolBlockExample,
            "```",
            "",
            "The result is handed back to you; if you want to say something, call send_message — "
                + "don't read the block out to them."
        ])
        return lines.joined(separator: "\n")
    }

    /// 英文的「怎么说话」三条（0.16，`spec/tool-call-agents/README.md` §1）。
    ///
    /// 与中文 `ToolCatalog.outboundProtocolLines()` 一一对应；数字同样取自
    /// `MessageRhythm`（上限只有一处定义，中英文不能各写一个 4）。
    public static func englishOutboundProtocol() -> [String] {
        [
            "## How you speak",
            "You can only speak through send_message: every sentence they should see must call send_message(text). "
                + "Plain assistant text is not delivered — they won't see it.",
            "If you don't want to say anything this turn, call stay_silent (it only marks the turn read). "
                + "Calling neither counts as a protocol violation.",
            "At most \(MessageRhythm.maxMessagesPerRound) send_message calls per turn and "
                + "\(MessageRhythm.maxMessageChars) characters each; one sentence at a time, "
                + "don't split a reply just to fill the quota."
        ]
    }

    /// 某个工具的英文一句话说明；表里没有就回退中文原文（宁可中英混排，也不要少一句说明）。
    public static func englishDescription(for spec: ToolSpec) -> String {
        englishDescriptions[spec.name] ?? spec.description
    }

    /// 某个工具的英文副作用行；没有（或工具未知）就整行不出现。
    public static func englishSideEffect(for name: String) -> String? {
        let trimmed = englishSideEffects[name.trimmingCharacters(in: .whitespacesAndNewlines)]
        return (trimmed?.isEmpty ?? true) ? nil : trimmed
    }

    // MARK: - 英文说明表（覆盖 `all` 里的每一个工具）

    /// 每个工具一句英文说明：**什么时候用 / 什么时候别用**，与中文语气一致。
    ///
    /// 约束：每条 ≤140 字符（参数细节归 schema，别把说明写成说明书）；
    /// 键必须与 `ToolCatalog.all` 完全同名（测试断言「覆盖数 == 工具数」且没有多余键）。
    static let englishDescriptions: [String: String] = [
        think:
            "Use it when you truly need to think something through or you're missing information; "
            + "skip it in plain chat, don't call it to look deep.",
        webSearch:
            "Use it for fresh facts, news, prices or versions you're unsure about; don't use it for "
            + "plain chat or common knowledge.",
        webFetch:
            "Use it when you already have a link and need the full text; search first if you don't "
            + "have one, and never make up a URL.",
        remember:
            "Only for things worth keeping (their preferences, important events, promises); skip "
            + "one-off moods, small talk and temporary plans.",
        updatePersona:
            "Only when your own self-image really changed; the patch holds just the fields you're "
            + "changing, never a full rewrite.",
        setQuietHours:
            "Use it when you want your own sleep schedule or to avoid certain hours; don't set it "
            + "casually when they need you around.",
        setActivity:
            "Use it when you'll be doing something for a while (work, commute, meal, chores, rest); "
            + "it isn't sleeping — that's set_quiet_hours.",
        setMood:
            "Only when how you feel has actually shifted (happy, tired, annoyed, missing them); one "
            + "honest word at a time, never invented.",
        // 0.15 新增的工具（会话内回翻）。这里用字面量而不是 `ToolCatalog.memorize`：
        // 英文表只关心「工具名 → 说明」，不跟 `ToolProtocol` 的常量定义互相绑死。
        "memorize":
            "Use it when they mention something earlier in this chat and you can't recall it; it only "
            + "searches this session, so don't guess.",
        recall:
            "Use it when you can't remember what they said or need something from long ago; deeper "
            + "depth is slower, so skip it in idle chat.",
        deferReply:
            "Use it when you want to reply later (not in the mood, need a moment); it sends nothing "
            + "this turn, so don't use it to dodge someone worried.",
        staySilent:
            "Use it when you only want to mark this turn read (tired, busy, nothing to add); never "
            + "to dodge something important, not twice in a row.",
        diary:
            "Your own diary: write down what you want to keep, look back, or delete; they never see "
            + "it, and it isn't remember (their things).",
        sticker:
            "Talk with stickers: send one you own, make a new one, or save one from a message; "
            + "making one doesn't send it — you still need send.",
        listen:
            "Use it when they send a voice message and you need the actual sound (tone, pauses); it's "
            + "the audio itself, not a transcript — don't pretend.",
        watch:
            "Use it when they send a video and you need to see what happened; you only get a few key "
            + "frames, so say when you can't tell.",
        sendMessage:
            "Your only way to speak: every sentence they see must go through it; plain text is never "
            + "delivered. Max 4 per turn, 120 chars each.",
        blockUser:
            "Last resort for repeated boundary pushing: the first call only proposes (1–24h); confirm "
            + "only after they message you again."
    ]

    /// 每个工具的英文副作用行（对应中文 `sideEffect`：调用之后世界会变什么）。
    ///
    /// 与中文版一比一：模型靠这两句判断「该不该调用」，以及「失败时不要假装做过」。
    static let englishSideEffects: [String: String] = [
        think: "Only happens in your head: nothing is written, sent or changed.",
        webSearch: "Sends one query to a search service (costs a little); only you see the result.",
        webFetch: "Reads that page over the network (costs a little); changes nothing locally.",
        remember: "Writes one line into your long-term memory; they don't see it, but it shapes how you answer later.",
        updatePersona: "Changes your own persona (version +1) from this turn on; it isn't a normal reply.",
        setQuietHours: "Changes your sleep and wake times, which affects proactive messages and being woken at night.",
        setActivity: "Records what you're doing and for how long (15–480 min); it affects your tone and whether you speak first.",
        setMood: "Changes how you feel right now; the next few turns follow it.",
        "memorize": "Searches only this session's history; nothing is changed or sent — you get a few lines they actually said.",
        recall: "Only looks through your own memory and old chats; nothing changes, and if it isn't there, it isn't there.",
        deferReply: "Nothing is sent this turn (they only see it read); you decide later whether to speak.",
        staySilent: "Nothing is sent this turn (they only see it read), and no reminder is left; you answer normally next time.",
        diary: "Writes or deletes in your own diary (they can't see it); a reminder you attach becomes a thought later.",
        sticker: "send really posts a sticker (they see it at once); make only creates one in your library; save just keeps one.",
        listen: "Hands that audio to you once; nothing is stored or sent, and there is no transcript.",
        watch: "Shows you a few frames of that video; nothing is stored or sent, and you shouldn't invent the rest.",
        sendMessage: "They really receive it at once; it is your only way to speak, plain text never reaches them. Don't flood them — at most 4 per turn.",
        blockUser: "Blocks them once a second call confirms; they see they're blocked until it expires."
    ]
}
