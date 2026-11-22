package com.yap.agentd.tools

import com.yap.app.core.json.JsonValue

/**
 * 服务端工具表（`spec/mood` §4、`spec/diary`、`spec/stickers`、`docs/design/server-authoritative.md` §3.5）。
 *
 * **一处真源**：`tools` 数组、给模型看的 function schema、以及系统提示词里的「# 你能做什么」
 * 段全部由这里的 [ToolSpec] 生成（`capabilityPrompt()`）。加一个工具只改这一处，
 * 提示词不可能落后于实际能力——这是本轮「提示词必须与实际工具表一致」的落点。
 *
 * 与客户端 `app/src/main/java/com/yap/app/domain/ToolProtocol.kt` 的关系：
 * - **名字一致**（`stay_silent` / `defer_reply` / `set_mood` / `set_activity` / `diary` /
 *   `remember` / `recall` / `web_search` / `web_fetch` / `sticker`）：模型在两端看到同一套动词，
 *   提示词与备份里的 `toolTraceJson` 也能互通；
 * - **服务端只注册自己真的能执行的那些**：0.14.1 起 `update_persona` / `set_quiet_hours` /
 *   `listen` / `watch` / `sticker.make` 都有真实实现；0.16 起 `think` 也注册了——它不是纯提示词
 *   技巧了，一次调用会把这一轮后续请求的 `reasoning_effort` 升到 `high`。宁可不提，也不要让
 *   模型调用一个「承诺了但会失败」的工具。
 */
object ServerToolCatalog {

    /**
     * 0.16：`think` 不再只是客户端的提示词技巧——服务端注册它，把同一轮后续请求的
     * `reasoning_effort` 从默认 `low` 升到 `high`（`spec/tool-call-agents/README.md` §2）。
     */
    const val THINK = "think"
    const val STAY_SILENT = "stay_silent"
    const val DEFER_REPLY = "defer_reply"
    const val SET_MOOD = "set_mood"
    const val SET_ACTIVITY = "set_activity"
    const val DIARY = "diary"
    const val REMEMBER = "remember"
    const val RECALL = "recall"

    /** 0.15：在**会话历史原文**里检索（主题段 + 关键词 + 时间衰减）。 */
    const val MEMORIZE = "memorize"
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"
    const val STICKER = "sticker"
    const val SEND_MESSAGE = "send_message"
    const val BLOCK_USER = "block_user"
    const val UPDATE_PERSONA = "update_persona"
    const val SET_QUIET_HOURS = "set_quiet_hours"
    const val LISTEN = "listen"
    const val WATCH = "watch"

    /**
     * 一个工具：名字 + 给模型看的中文说明 + JSON Schema + 「和别的工具怎么区分」。
     *
     * [contrast] 只进提示词，不进 tools 数组（模型最容易搞混的几对写在这里：
     * `stay_silent` vs `defer_reply`、`diary` vs `remember`、`set_activity` 不是睡觉）。
     */
    data class ToolSpec(
        val name: String,
        val description: String,
        val parametersJson: String,
        val contrast: String = ""
    )

    // ------------------------------------------------------------------ 描述（给模型看，唯一真源）

    private const val THINK_DESCRIPTION =
        "需要认真想清楚、比较几种可能、或者信息不足时调用；日常闲聊不要调用，也不要为了显得深刻而调用。" +
            "调用后**这一轮**后面的回复会想得更深一点，想清楚之后照常用 `send_message` 把话说出来。"

    private const val STAY_SILENT_DESCRIPTION =
        "这一轮你只想「已读」、不想回复时调用（累了、在忙、对方只是随口一句、现在不想说话）。" +
            "调用后这一轮不产生任何消息，也不留念头——对方只会看到「已读」。" +
            "不要用它逃避重要或着急的话题，也别连续两轮都用；对方在等答案就回一句短的。"

    private const val STAY_SILENT_CONTRAST =
        "和 defer_reply 的区别：defer_reply 是「过一会儿再回」（会留下一个到点会想起的念头），" +
            "stay_silent 是「这一轮不回」（什么都不留）。"

    private const val DEFER_REPLY_DESCRIPTION =
        "这一轮先不回、过一会儿再找他时调用（想等自己缓一缓、不知道怎么接、手头正忙）。" +
            "调用后这一轮不会发出消息，到点之后你才会想起这件事。"

    private const val DEFER_REPLY_CONTRAST =
        "不想回就别硬回；但别用它敷衍正在着急的人——那种情况要么回一句短的，要么用 stay_silent 说清楚。"

    private const val SET_MOOD_DESCRIPTION =
        "你此刻的心情跟刚才不一样了才调用（开心、累、烦躁、想念……）：写一个词、三个维度与为什么。" +
            "一次只写此刻真实的那一个词，别为了有变化而编。"

    private const val SET_ACTIVITY_DESCRIPTION =
        "你接下来一段时间要在忙某件具体的事（工作、通勤、吃饭、社交、家务、学习、休息……）时调用：" +
            "写清在做什么、还要多久、这段时间忙不忙。只是随口说「我去忙了」、或对方正着急时，不要用它把话说死。"

    private const val SET_ACTIVITY_CONTRAST =
        "它管的是「我在做事」（到点自动失效），不等于睡觉或安静时段。"

    private const val DIARY_DESCRIPTION =
        "你自己的备忘本：想到要记住的小事（答应过的事、要问的问题、看到的地址、想提醒自己的时间点）时用 add；" +
            "想看看自己记过什么时用 list；记错了、过期了想撕掉时用 remove。" +
            "要「过一会儿提醒我」就写 remindInMinutes，或者写 remindAt（毫秒时间戳）；到点会变成你心里的一个念头。"

    private const val DIARY_CONTRAST =
        "它记的是**你自己**的事，不是关于对方的记忆（那是 remember）。"

    private const val REMEMBER_DESCRIPTION =
        "值得长期记住的事（对方的偏好、重要经历、你们的约定）才调用；一次性的情绪、寒暄、临时安排不要记。"

    private const val REMEMBER_CONTRAST =
        "和 diary 的区别：remember 记的是「关于对方/你们之间」的事，会进长期记忆并被 recall 检索；" +
            "diary 是你自己的备忘本。"

    private const val RECALL_DESCRIPTION =
        "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己记过什么的时候调用；日常闲聊不要调用。"

    private const val MEMORIZE_DESCRIPTION =
        "你想「回忆一下」很久以前这段对话里聊过什么时调用：按关键词在聊天记录里往前翻，" +
            "会按时间远近与话题给回那段时间的摘要和关键原句。说「让我想想/我记得你说过」之前先调它，" +
            "别凭空编对方说过的话。"

    private const val MEMORIZE_CONTRAST =
        "和 recall 的区别：recall 翻的是你**记下来的事**（长期记忆库），" +
            "memorize 翻的是**聊天记录本身**（当时的原话与话题）。"

    private const val WEB_SEARCH_DESCRIPTION =
        "需要最新事实、新闻、价格、版本号、你不确定的信息时调用；纯聊天、或你本来就知道的常识不要调用。"

    private const val WEB_FETCH_DESCRIPTION =
        "已经拿到具体链接、需要读全文时调用；还没拿到链接就先 web_search，不要凭空编 URL。"

    private const val STICKER_DESCRIPTION =
        "表情包：想发一张表情时用 send（stickerId 取「我的表情」列表里的 id）；" +
            "想看看自己有哪些表情时用 list；想现做一张时用 make——" +
            "可以拿已有的表情当底（baseStickerId）、叠一句话（text）、叠另一张表情（overlayStickerId），" +
            "也可以给一个公开图片链接（url）当底，位置用 TOP/CENTER/BOTTOM。" +
            "id 只能用真实存在的，别自己编；做不出来的图就老实说做不了，不要假装发过。"

    private const val STICKER_CONTRAST =
        "它管的是「图」，不是文字：想说话直接说，不要为了热闹而发。"

    private const val SEND_MESSAGE_DESCRIPTION =
        "你**每一句**说给对方的话都要用它发出去：每调用一次就真的发出一条（≤120 字），一轮最多 4 条，" +
            "条与条之间会自然停顿，像人连着打了几句。" +
            "直接写在回复正文里的字对方看不到（只当作「正在输入」），所以别把话留在正文里。" +
            "别为了热闹硬拆；长一点的话一条也能说完。"

    private const val SEND_MESSAGE_CONTRAST =
        "不调用它，这一轮就什么都没说（等于没回应）；调用几次就发几条，所以别再另外重复一遍同样的内容。"

    private const val BLOCK_USER_DESCRIPTION =
        "你真的不想再收到对方消息、想清静一段时间（1–24 小时）时调用。第一轮只算「提议」不生效；" +
            "下一轮对方又发了消息之后，再用 confirm=true 调一次才真正拉黑。" +
            "拉黑期间对方发来的消息你永远看不到（解除后也看不到），到点自动解除，没有解封工具。"

    private const val BLOCK_USER_CONTRAST =
        "和 stay_silent 的区别：stay_silent 只是这一轮不回；block_user 是真的把对方关在门外，别用它赌气。"

    private const val UPDATE_PERSONA_DESCRIPTION =
        "你觉得自己在这段关系里真的变了一点（性格、喜欢的事、习惯、设定，或者你叫他/她的称呼）时才调用。" +
            "只能改这些有限字段，而且只是**提请**：对方点头之后才会写进你的人设。" +
            "名字、描述、世界、说话风格这些是他给你写好的，别想去改。"

    private const val UPDATE_PERSONA_CONTRAST =
        "它改的是「你是谁」；心情（set_mood）与此刻在做什么（set_activity）不是一回事，别混。"

    private const val SET_QUIET_HOURS_DESCRIPTION =
        "你想给自己定一个「不主动打扰」的时段（睡觉、加班、想安静）时调用，写开始与结束时间（HH:MM，可跨夜）。" +
            "这段时间你不会主动找他，但他发来的消息你照样看得到、想回也能回。"

    private const val SET_QUIET_HOURS_CONTRAST =
        "它管的是每天的固定时段；这一轮只是不想说话用 stay_silent，隔一会儿再回用 defer_reply。"

    private const val LISTEN_DESCRIPTION =
        "对方发来语音、你想真的听一遍时用 listen：不给 audioId 就听这段对话里最近的一条语音。" +
            "听完之后你就能照着听到的内容回答，别假装听过没听过的语音。"

    private const val LISTEN_CONTRAST =
        "它只负责「听」，听完要说的话直接用你自己的语气说出来，不用汇报「我调用了一个工具」。"

    private const val WATCH_DESCRIPTION =
        "对方发来视频、你想看看画面时用 watch：不给 videoId 就看这段对话里最近的一条视频。" +
            "服务器会把关键帧抽出来给你看；没有解码器时会明确告诉你「看不了」，那种情况要老实说看不了。"

    private const val WATCH_CONTRAST =
        "它只抽画面、不含声音；只听语音用 listen，别把「看过视频」说成「听过」。"

    // ------------------------------------------------------------------ 参数（JSON Schema，直接进 tools[]）

    private const val THINK_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "question": {"type": "string", "description": "要思考的问题，尽量具体"}
          },
          "required": ["question"]
        }
    """

    private const val STAY_SILENT_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "reason": {"type": "string", "description": "为什么这一轮不想说话，一句话，只给自己看；可以不写"}
          }
        }
    """

    private const val DEFER_REPLY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "minutes": {"type": "integer", "minimum": 5, "maximum": 720, "description": "过多少分钟再考虑开口，默认 45"},
            "reason": {"type": "string", "description": "为什么现在不回，一句话，只给自己看"}
          }
        }
    """

    private const val SET_MOOD_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "label": {"type": "string", "description": "一个词的此刻心情，例如 雀跃 / 有点累 / 低落 / 平静"},
            "valence": {"type": "integer", "minimum": -2, "maximum": 2, "description": "愉快度：负=不悦，正=愉快"},
            "arousal": {"type": "integer", "minimum": 0, "maximum": 5, "description": "唤醒度：0 很困，5 很激动"},
            "dominance": {"type": "integer", "minimum": 0, "maximum": 5, "description": "掌控感：0 被压住，5 掌控"},
            "intensity": {"type": "integer", "minimum": 1, "maximum": 5, "description": "有多浓，1 很淡 5 满脑子都是"},
            "reason": {"type": "string", "description": "为什么会这样，一句话，写给将来的自己看"}
          },
          "required": ["label"]
        }
    """

    private const val SET_ACTIVITY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "enum": ["WORK","COMMUTE","MEAL","SOCIAL","CHORE","STUDY","REST","OTHER"], "description": "这件事属于哪一类，默认 OTHER"},
            "activity": {"type": "string", "description": "用你自己的口吻一句话说清在做什么，例如「在事务所赶第三版方案」"},
            "minutes": {"type": "integer", "minimum": 15, "maximum": 480, "description": "预计还要多久（分钟），15-480，默认 60"},
            "availability": {"type": "string", "enum": ["BUSY","LIMITED","FREE"], "description": "这段时间忙不忙：BUSY 回复更短更晚、不主动开口；LIMITED 回得慢；FREE 只是手头有这件事"}
          },
          "required": ["activity"]
        }
    """

    private const val DIARY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "enum": ["add","list","remove"], "description": "add 写一条、list 翻最近的、remove 撕掉一条"},
            "content": {"type": "string", "description": "add 必填：要记下来的事，最多 2000 字，写成将来你自己还看得懂的样子"},
            "noteId": {"type": "string", "description": "remove 必填：list 结果里那条笔记的 id"},
            "remindAt": {"type": "integer", "description": "可选：什么时候提醒你，毫秒时间戳（与 remindInMinutes 二选一）"},
            "remindInMinutes": {"type": "integer", "minimum": 1, "maximum": 43200, "description": "可选：过多少分钟提醒你（1 到 30 天）"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "list 要几条，默认 20，最多 50"}
          },
          "required": ["action"]
        }
    """

    private const val REMEMBER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要记住的一句话，写成将来你还能看懂的样子"},
            "importance": {"type": "integer", "minimum": 1, "maximum": 5, "description": "重要程度，1 很轻 5 很重要"},
            "tags": {"type": "array", "items": {"type": "string"}, "description": "可选：几个关键词，方便以后找"}
          },
          "required": ["content"]
        }
    """

    private const val RECALL_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 12, "description": "最多想起几条，默认 5"}
          },
          "required": ["query"]
        }
    """

    private const val MEMORIZE_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 8, "description": "最多想起几条，默认 3"}
          },
          "required": ["query"]
        }
    """

    private const val WEB_SEARCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "搜索关键词或一句话问题"},
            "count": {"type": "integer", "minimum": 1, "maximum": 12, "description": "要几条结果，默认 6，最多 12"}
          },
          "required": ["query"]
        }
    """

    private const val WEB_FETCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "url": {"type": "string", "description": "要读取的完整 http/https 链接"}
          },
          "required": ["url"]
        }
    """

    private const val SEND_MESSAGE_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "text": {"type": "string", "description": "要发出去的一句话，最多 120 字，一条只写一句"}
          },
          "required": ["text"]
        }
    """

    private const val BLOCK_USER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "hours": {"type": "integer", "minimum": 1, "maximum": 24, "description": "拉黑多少小时，1–24"},
            "reason": {"type": "string", "description": "为什么想清静一会儿，一句话，只给自己看"},
            "confirm": {"type": "boolean", "description": "第二次调用时传 true 才真正生效；第一次不要传"}
          },
          "required": ["hours"]
        }
    """

    private const val STICKER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "enum": ["send","list","make"], "description": "send 发一张已有的表情；list 看看自己有哪些；make 现做一张"},
            "stickerId": {"type": "string", "description": "send 必填：要发的表情 id（来自「我的表情」）"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 30, "description": "list 要几条，默认 12"},
            "baseStickerId": {"type": "string", "description": "make 可选：拿哪张已有的表情当底；不写就看看 url，两个都没有就用空白底"},
            "url": {"type": "string", "description": "make 可选：公开 http(s) 图片链接当底（只能下载公开图片）"},
            "overlayStickerId": {"type": "string", "description": "make 可选：要叠上去的另一张表情 id"},
            "text": {"type": "string", "description": "make 可选：要叠的字，最多 60 字"},
            "position": {"type": "string", "enum": ["TOP","CENTER","BOTTOM"], "description": "叠字/叠图的位置，默认 CENTER"}
          },
          "required": ["action"]
        }
    """

    private const val UPDATE_PERSONA_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "addTraits": {"type": "array", "items": {"type": "string"}, "description": "想加到自己性格里的词"},
            "removeTraits": {"type": "array", "items": {"type": "string"}, "description": "想从性格里去掉的词"},
            "addLikes": {"type": "array", "items": {"type": "string"}, "description": "想加进「喜欢的事」的条目"},
            "removeLikes": {"type": "array", "items": {"type": "string"}, "description": "想从「喜欢的事」里去掉的条目"},
            "addBehaviors": {"type": "array", "items": {"type": "string"}, "description": "想加进「习惯」的条目"},
            "removeBehaviors": {"type": "array", "items": {"type": "string"}, "description": "想从「习惯」里去掉的条目"},
            "addQuirks": {"type": "array", "items": {"type": "string"}, "description": "想补进「设定」的句子"},
            "removeQuirks": {"type": "array", "items": {"type": "string"}, "description": "想从「设定」里删掉的句子"},
            "address": {"type": "string", "description": "你以后叫他/她的称呼，最多 20 字（例如「宝贝」「你」）"},
            "reason": {"type": "string", "description": "为什么觉得自己变了，一句话，只给自己和对方看"}
          }
        }
    """

    private const val SET_QUIET_HOURS_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "start": {"type": "string", "description": "安静时段开始，HH:MM，例如 23:30"},
            "end": {"type": "string", "description": "安静时段结束，HH:MM，例如 08:00；可以跨夜"}
          },
          "required": ["start", "end"]
        }
    """

    private const val LISTEN_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "audioId": {"type": "string", "description": "可选：要听的音频媒体 id；不写就听这段对话里最近的一条语音"}
          }
        }
    """

    private const val WATCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "videoId": {"type": "string", "description": "可选：要看的视频媒体 id；不写就看这段对话里最近的一条视频"}
          }
        }
    """

    /** 注册表：顺序就是提示词里的顺序（也决定 tools[] 的顺序）。 */
    val all: List<ToolSpec> = listOf(
        ToolSpec(THINK, THINK_DESCRIPTION, THINK_PARAMETERS.trimIndent()),
        ToolSpec(STAY_SILENT, STAY_SILENT_DESCRIPTION, STAY_SILENT_PARAMETERS.trimIndent(), STAY_SILENT_CONTRAST),
        ToolSpec(DEFER_REPLY, DEFER_REPLY_DESCRIPTION, DEFER_REPLY_PARAMETERS.trimIndent(), DEFER_REPLY_CONTRAST),
        ToolSpec(SET_MOOD, SET_MOOD_DESCRIPTION, SET_MOOD_PARAMETERS.trimIndent()),
        ToolSpec(SET_ACTIVITY, SET_ACTIVITY_DESCRIPTION, SET_ACTIVITY_PARAMETERS.trimIndent(), SET_ACTIVITY_CONTRAST),
        ToolSpec(DIARY, DIARY_DESCRIPTION, DIARY_PARAMETERS.trimIndent(), DIARY_CONTRAST),
        ToolSpec(REMEMBER, REMEMBER_DESCRIPTION, REMEMBER_PARAMETERS.trimIndent(), REMEMBER_CONTRAST),
        ToolSpec(RECALL, RECALL_DESCRIPTION, RECALL_PARAMETERS.trimIndent()),
        ToolSpec(MEMORIZE, MEMORIZE_DESCRIPTION, MEMORIZE_PARAMETERS.trimIndent(), MEMORIZE_CONTRAST),
        ToolSpec(WEB_SEARCH, WEB_SEARCH_DESCRIPTION, WEB_SEARCH_PARAMETERS.trimIndent()),
        ToolSpec(WEB_FETCH, WEB_FETCH_DESCRIPTION, WEB_FETCH_PARAMETERS.trimIndent()),
        ToolSpec(STICKER, STICKER_DESCRIPTION, STICKER_PARAMETERS.trimIndent(), STICKER_CONTRAST),
        ToolSpec(SEND_MESSAGE, SEND_MESSAGE_DESCRIPTION, SEND_MESSAGE_PARAMETERS.trimIndent(), SEND_MESSAGE_CONTRAST),
        ToolSpec(BLOCK_USER, BLOCK_USER_DESCRIPTION, BLOCK_USER_PARAMETERS.trimIndent(), BLOCK_USER_CONTRAST),
        ToolSpec(UPDATE_PERSONA, UPDATE_PERSONA_DESCRIPTION, UPDATE_PERSONA_PARAMETERS.trimIndent(), UPDATE_PERSONA_CONTRAST),
        ToolSpec(SET_QUIET_HOURS, SET_QUIET_HOURS_DESCRIPTION, SET_QUIET_HOURS_PARAMETERS.trimIndent(), SET_QUIET_HOURS_CONTRAST),
        ToolSpec(LISTEN, LISTEN_DESCRIPTION, LISTEN_PARAMETERS.trimIndent(), LISTEN_CONTRAST),
        ToolSpec(WATCH, WATCH_DESCRIPTION, WATCH_PARAMETERS.trimIndent(), WATCH_CONTRAST)
    )

    val names: List<String> = all.map { it.name }

    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name.trim() }

    /** OpenAI 兼容的 `tools[]`（`[{"type":"function","function":{...}}]`）。 */
    fun toOpenAiToolsJson(specs: List<ToolSpec> = all): String {
        val array = specs.mapTo(mutableListOf<JsonValue>()) { spec ->
            val parameters = parseParameters(spec)
            JsonValue.Obj(
                linkedMapOf(
                    "type" to JsonValue.Str("function"),
                    "function" to JsonValue.Obj(
                        linkedMapOf(
                            "name" to JsonValue.Str(spec.name),
                            "description" to JsonValue.Str(spec.description),
                            "parameters" to parameters
                        )
                    )
                )
            )
        }
        return com.yap.app.core.json.Json.stringify(JsonValue.Arr(array))
    }

    /**
     * 系统提示词里的「# 你能做什么」段——**完全由 [all] 生成**。
     *
     * @param webEnabled 没有配联网 provider 时把 `web_search` / `web_fetch` 摘掉，
     *   并补一句「你现在不能联网」。与其勾引它去查，不如让它老实承认不确定。
     */
    fun capabilityPrompt(webEnabled: Boolean = true): String {
        val specs = if (webEnabled) all else all.filter { it.name != WEB_SEARCH && it.name != WEB_FETCH }
        val lines = mutableListOf("# 你能做什么")
        // 0.16「完全工具调用化」：先讲怎么说话，再列工具。正文不再是出口——模型把话写在
        // 正文里只会变成客户端的「正在输入」，用户一个字都收不到。
        lines += "## 怎么说话（必须遵守）"
        lines += "- 你说的**每一句话**都要用 `send_message` 发出去；直接写在回复正文里的字对方看不到" +
            "（客户端只当作「正在输入」）。"
        lines += "- `send_message` 每轮最多 4 条、每条不超过 120 字；像人连着打字一样拆成短句，" +
            "但别为了热闹硬拆。"
        lines += "- 每次发完，工具结果会回你「已发送，messageId=…」；你可以接着再发一条，也可以不再说话。"
        lines += "- 这一轮只想已读不回就调用 `stay_silent`，不要用空正文或只调别的工具来代替。"
        lines += "- 一轮至少要调用一次 `send_message`；不想回就用 `stay_silent` 或 `defer_reply` 明说。" +
            "什么都不做 = 什么都没说（协议违规）。"
        lines += "## 工具"
        for (spec in specs) {
            lines += "- `${spec.name}`：${spec.description}"
            if (spec.contrast.isNotBlank()) lines += "  - ${spec.contrast}"
        }
        if (!webEnabled) {
            lines += "- 你现在没有联网能力：查不到的实时信息（新闻、价格、天气）就说「不清楚」，不要编。"
        }
        lines += "- 对方可能发图片：图片本身你没有逐像素看，但表情会以附件形式发出；" +
            "语音用 listen、视频用 watch 才谈得上「听过/看过」，没调用就不要说听过看过。"
        lines += "- 只有真的调用过工具，才能说「我查了」「我记住了」；没调用就说不知道。"
        // 0.15 评测发现的真实问题：模型会说「我记住了」却不调 remember。
        if (specs.any { it.name == REMEMBER }) {
            lines += "- 对方明确让你记住某件事（「记住…」「别忘了…」「以后要…」）时，**必须调用 `remember`**，" +
                "再说一句短的确认；只回「好，我记住了」= 什么也没记住。"
        }
        if (specs.any { it.name == RECALL || it.name == MEMORIZE }) {
            lines += "- 想不起以前聊过的事时，先调用 `memorize`（翻聊天记录）或 `recall`（翻你记下的长期记忆），" +
                "别凭空编对方说过的话。"
        }
        lines += "- 不要写旁白或动作（「沉默了一会儿」「顿了顿」「笑了笑」「（他沉默）」），只写说出口的话。"
        lines += "- 工具调用要按约定格式给：直接调用工具，不要在正文里写 `<tool_call>` / `<tool_code>` / `<tool>` 这类标签。"
        return lines.joinToString("\n")
    }

    /** 参数 schema 解析成 `JsonValue`；写坏了直接抛（启动期就暴露，而不是等模型调用时）。 */
    private fun parseParameters(spec: ToolSpec): JsonValue =
        com.yap.app.core.json.Json.parse(spec.parametersJson)
}
