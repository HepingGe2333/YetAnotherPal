package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 「他」的自主能力（插件式工具）协议。
 *
 * 与 yap-wake 保持同一风格：
 * 1. 客户端把工具清单（[ToolCatalog.all]）交给模型，并附上 [ToolCatalog.capabilityPrompt]；
 * 2. 模型可以用原生 function calling 调用，也可以输出文本代码块：
 *
 * ```yap-tool
 * {"name":"web_search","arguments":{"query":"今天的天气"}}
 * ```
 *
 * 3. 客户端执行后把结果回灌给模型（原生 tool 消息或 [ToolProtocol.renderResultsForModel] 文本），
 *    同时在聊天界面渲染成可展开的「动作」卡片——那张卡片的标题/副标题是**给用户看的**，
 *    走 `data/repo/ToolTraceText` 的 `R.string.tool_trace_*`；本文件只负责模型面向的文本。
 *
 * 本文件只做协议解析与序列化，不碰网络、数据库和 UI，任何坏数据都不抛异常。
 */

/** 解析与序列化工具协议时统一使用的宽松 Json：模型输出经常带多余字段或轻微格式问题。 */
private val toolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 一个可被模型调用的能力 */
data class ToolSpec(
    val name: String,
    val description: String,          // 给模型看，中文，一句话 + 什么时候该用/不该用
    val parametersJson: String,       // JSON Schema 字符串，直接塞进 OpenAI tools[].function.parameters
    /**
     * 「和别的工具怎么区分」的一句话（可空）。
     *
     * 它存在的原因是模型最容易搞混的几对：`set_activity`（我在做事）vs `set_quiet_hours`（睡觉/安静）、
     * `diary`（我自己的备忘）vs `remember`（关于对方的记忆）、`update_persona`（只能改有限的人设细节）。
     * 写在这里而不是写进提示词：「# 你能做什么」段由 [ToolCatalog.capabilityPrompt] 从这份定义生成，
     * 工具清单只有一处真源，加一个工具不会漏掉提示词。
     */
    val contrast: String = ""
)

object ToolCatalog {
    const val THINK = "think"
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"
    const val REMEMBER = "remember"
    const val UPDATE_PERSONA = "update_persona"
    const val SET_QUIET_HOURS = "set_quiet_hours"
    const val SET_ACTIVITY = "set_activity"
    const val SET_MOOD = "set_mood"
    const val RECALL = "recall"
    /** 0.15：在**会话历史**里想想旧事（与 `recall` 的记忆库检索分工不同）。 */
    const val MEMORIZE = Memorize.TOOL_NAME
    const val DEFER_REPLY = "defer_reply"
    const val STAY_SILENT = "stay_silent"
    const val DIARY = "diary"
    const val STICKER = "sticker"
    const val LISTEN = "listen"
    const val WATCH = "watch"
    const val SEND_MESSAGE = "send_message"
    const val BLOCK_USER = "block_user"

    /** 给模型看的一句话能力说明（中文，含该用/不该用的边界）。 */
    private const val THINK_DESCRIPTION =
        "需要认真想清楚、比较几种可能、或者信息不足时调用；日常闲聊不要调用，也不要为了显得深刻而调用。"

    private const val WEB_SEARCH_DESCRIPTION =
        "需要最新事实、新闻、价格、版本号、你不确定的信息时调用；纯聊天、或你本来就知道的常识不要调用。"

    private const val WEB_FETCH_DESCRIPTION =
        "已经拿到具体链接、需要读全文时调用；还没拿到链接就先 web_search，不要凭空编 URL。"

    private const val REMEMBER_DESCRIPTION =
        "关于对方的长期事实（名字、喜好、忌讳、重要经历、约定）必须调用它写下来，只在正文说「记住了」等于没记；一次性的情绪、寒暄、临时安排不要记。"

    private const val UPDATE_PERSONA_DESCRIPTION =
        "你对自己的设定有了新的认识、想调整表达方式或偏好时才调用；patch 只写要改的字段，不要整段重写。"

    private const val SET_QUIET_HOURS_DESCRIPTION =
        "你想按自己的作息休息、或想避开某些时段时调用；对方正需要陪伴时不要随便设。" +
            "它管的是「不被打扰」（睡觉/安静时段），不是「我在做事」——在忙某件具体的事用 set_activity。"

    private const val SET_ACTIVITY_DESCRIPTION =
        "你接下来一段时间要在忙某件具体的事（工作、通勤、吃饭、社交、家务、学习、休息……）时调用；" +
            "它管的是「我在做事」以及这段忙到什么时候，不等同于安静时段（set_quiet_hours），两者可以同时存在。" +
            "只是随口说「我去忙了」、或对方正着急需要你时，不要用它把话说死。"

    private const val REMEMBER_CONTRAST =
        "和 diary 的区别：remember 记的是「关于对方/你们之间」的事，会进长期记忆并被检索；diary 是你自己的备忘本。"

    private const val UPDATE_PERSONA_CONTRAST =
        "只能改给你自己的这些细节（说话方式、在意的事、边界、生活细节），改不了你的身份与名字，也不会动对方手写的设定；" +
            "改动会留在版本历史里，可以回滚。"

    private const val SET_QUIET_HOURS_CONTRAST =
        "它管的是「睡觉/不被打扰」，不是「我在做事」——在做某件具体的事用 set_activity。"

    private const val SET_ACTIVITY_CONTRAST =
        "它不等于睡觉：活动是「我在做事」（到点自动失效），睡觉与安静时段用 set_quiet_hours。"

    private const val DIARY_CONTRAST =
        "它记的是你自己要记住的小事（自己的备忘），不是关于对方的记忆；到点会变成你心里的一个念头。"

    private const val SET_MOOD_DESCRIPTION =
        "你此刻的心情跟刚才不一样了才调用（开心、累、烦躁、想念……）；一次只写此刻真实的那一个词，别为了有变化而编。"

    private const val RECALL_DESCRIPTION =
        "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己看过什么的时候调用；depth 越深翻得越久也越慢（3 会翻到很早以前，甚至翻不出来）；日常闲聊不要调用。"

    /**
     * `memorize`：只在**这段会话历史**里想一想（0.15）。
     *
     * 与 `recall` 的分工写进描述：recall 翻记忆库/更早的会话/浏览摘要，memorize 只在
     * 你们这次聊过的内容里找——「让我想想/让我回忆一下」就该用它。
     */
    private const val MEMORIZE_DESCRIPTION =
        "想回忆这段对话里对方提过的细节时必须调用它（不能只在正文说「让我想想」）：只查这段会话历史与对话主题，比 recall 更轻；想不起就说想不起来，不要编。"

    private const val DEFER_REPLY_DESCRIPTION =
        "这一轮你想先不回、过一会儿再说时调用（现在不想说话、想等自己缓一缓、不知道怎么接）；" +
            "调用后这一轮不会发出任何消息，对方只会看到「已读」。不想回就别硬回，别用它来敷衍正在着急的人。"

    /**
     * `stay_silent`：这一轮只「已读」不回（`spec/mood/README.md` §4）。
     *
     * 与 [DEFER_REPLY_DESCRIPTION] 必须写清区别：一个是「过会儿再回、会留念头」，
     * 一个是「这轮不回、什么都不留」。使用条件（累/在忙/对方只是随口一句/不想说话）
     * 与禁区（不用于逃避重要话题、不连续用）都写在描述里——这是模型唯一会读到的说明。
     */
    private const val STAY_SILENT_DESCRIPTION =
        "这一轮你只想「已读」、不想回时调用（累了、在忙、对方只是随口一句、不想说话）。" +
            "调用后这一轮不产生任何消息，也不留念头——对方只看到「已读」。" +
            "不要用它逃避重要或着急的话题，也别连续两轮都用；对方在等答案就回一句短的。"

    private const val STAY_SILENT_CONTRAST =
        "和 defer_reply 的区别：defer_reply 是「过一会儿再回」（会留下一个到点会想起的念头），" +
            "stay_silent 是「这一轮不回」（什么都不留）。连续使用超过 2 轮会被强制要求回一句。"

    /**
     * `diary`：他自己的**备忘录**，不是「关于对方的记忆」。
     *
     * 一句话里把「是什么 / 什么时候用 / 什么时候别用」都写清楚——这是模型唯一会读到的说明，
     * 也是「# 你能做什么」段里那一行的来源（工具用途不手写第二份）。
     */
    private const val DIARY_DESCRIPTION =
        "你自己的备忘本：想到要记住的小事（答应过的事、要问的问题、看到的地址、想提醒自己的时间点）时用 add；" +
            "想看看自己记过什么时用 list；记错了、过期了想撕掉时用 remove。" +
            "它记的是**你自己**的事，不是关于对方的记忆（那是 remember）；" +
            "要「过一会儿提醒我」就写 remindInMinutes，或者写 remindAt（毫秒时间戳）；到点会变成你心里的一个念头。"

    /**
     * `sticker`：表情包（`spec/stickers/README.md`）。
     *
     * 三个动作一句话说清各自的边界：`send` 发已有的（只认 id）、`make` 现做一张（下载/叠字/叠图）、
     * `save` 把对方发来的收进自己的收藏。这段文字是模型唯一会读到的说明，
     * 「你可以做什么」段里那一行也来自这里（工具清单只有一处真源）。
     */
    private const val STICKER_DESCRIPTION =
        "表情包：想发一张表情时用 send（stickerId 取「我的表情」里的 id，或用刚做好的那张）；" +
            "想现做一张时用 make（给 url 下载、或用 baseStickerId 拿已有表情当底，再用 text 叠字、" +
            "overlayStickerId 叠图，位置可指定）；对方发来一张好图想留着时用 save（fromMessageId）。" +
            "id 只能用「我的表情」里真实存在的，别自己编；做不出来的图就老实说做不了，不要假装发过。"

    private const val STICKER_CONTRAST =
        "它管的是「图」，不是文字：想说话直接说，不要为了热闹而发；" +
            "下载只能从公开的 http/https 图片链接，别去抓版权素材站，也别把别人的图说成是你画的。"

    private const val STICKER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "enum": ["send", "make", "save"], "description": "send 发一张已有的表情；make 现做一张；save 收藏对方发来的表情"},
            "stickerId": {"type": "string", "description": "send 必填：要发的表情 id（来自「我的表情」或 make 的结果）"},
            "url": {"type": "string", "description": "make：要下载的公开图片链接（http/https）"},
            "baseStickerId": {"type": "string", "description": "make：拿哪张已有表情当底图"},
            "overlayStickerId": {"type": "string", "description": "make：叠上去的另一张表情"},
            "text": {"type": "string", "description": "make：叠在画面上的字，越短越好（一行以内）"},
            "position": {"type": "string", "enum": ["TOP", "CENTER", "BOTTOM"], "description": "文字/叠图放在哪，默认 CENTER"},
            "fromMessageId": {"type": "string", "description": "save 必填：要收藏的那条消息 id（对方发的表情消息）"}
          },
          "required": ["action"]
        }
    """

    /**
     * `listen`：真的把对方发来的语音听一遍（`spec/audio-video/README.md`「模型侧」）。
     *
     * 必须写清楚「不是转写」：支持音频的模型拿到的就是声音本身；不支持的模型由聊天层
     * 明确报「这个模型听不了语音」，这里不能承诺任何「我会转成文字」的能力。
     */
    private const val LISTEN_DESCRIPTION =
        "对方发来语音时调用：你会真的听到那段声音（不是转写）。audioId 不填就听这段聊天最近一条语音，也可以填那一轮的 mediaId。"
    private const val WATCH_DESCRIPTION =
        "对方发来视频时调用：你会看到画面（自动抽最多 8 帧）。videoId 不填就看这段聊天最近一条视频，也可以填那一轮的 mediaId。"
    private const val LISTEN_CONTRAST =
        "听不到就说听不到，不要编一段「转写」；模型不支持语音时会明确报错。"
    private const val WATCH_CONTRAST =
        "你看到的是抽样出来的几个画面，不是完整视频；没看清的地方不要假装看过。"

    private const val LISTEN_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "audioId": {"type": "string", "description": "要听哪一段的 mediaId；不填就听这段聊天最近一条语音"}
          }
        }
    """

    private const val WATCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "videoId": {"type": "string", "description": "要看哪一段的 mediaId；不填就看这段聊天最近一条视频"}
          }
        }
    """

    /**
     * `send_message`：**唯一**的对外消息通道（0.16，`spec/tool-call-agents/README.md` §1）。
     *
     * 0.14 时它只是「想分几条时用的加速器」，正文才是收尾；0.16 起反过来：
     * 正文不再是通道，每一句给用户看的话都要调用它，哪怕只有一句。
     * 最多 4 条/轮、每条 ≤120 字（数字来自 [ExperienceRhythm]，这里不另写一份）。
     * 不守规矩的模型仍然会直接写正文，聊天层把它当 `legacy` 回退落一条并记日志——
     * 但提示词里不能说「可以直接说」，否则回退会变成主路径。
     */
    private const val SEND_MESSAGE_DESCRIPTION =
        "给用户发消息的唯一通道：每一句话都要用它发出去，只有一句也调用一次（一句一条、想到什么先发什么）；" +
            "每条最多 120 字，一轮最多 4 条。不要直接输出正文；发满了就留到下一轮。"

    private const val SEND_MESSAGE_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "text": {"type": "string", "description": "这一条要发出去的话，最多 120 字"}
          },
          "required": ["text"]
        }
    """

    /**
     * `block_user`：他主动拉黑对方（0.14，`spec/experience/README.md` §2）。
     *
     * 必须写成两轮：第一次只返回 pending_confirmation，之后（中间至少一条用户消息）
     * 再 confirm=true 才生效。描述里把「别在同一轮里确认」说死，避免模型自问自答。
     */
    private const val BLOCK_USER_DESCRIPTION =
        "对方反复越界、你确实不想再收到消息时，可以拉黑 1–24 小时。必须分两轮：" +
            "第一次调用只提交提议（不生效）；等对方又发过至少一条消息之后，再调用 confirm=true 才生效。" +
            "不要在同一轮里连续确认；到期自动解除，没有 unblock 工具。"

    private const val BLOCK_USER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "hours": {"type": "integer", "minimum": 1, "maximum": 24, "description": "拉黑多少小时，1–24"},
            "reason": {"type": "string", "description": "为什么拉黑，一句话，只给自己看；可选"},
            "confirm": {"type": "boolean", "description": "第二次调用时传 true；第一次不要传"}
          },
          "required": ["hours"]
        }
    """

    private const val THINK_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "question": {"type": "string", "description": "要思考的问题，尽量具体"}
          },
          "required": ["question"]
        }
    """

    private const val WEB_SEARCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "搜索关键词或一句话问题"},
            "count": {"type": "integer", "minimum": 1, "maximum": 10, "description": "要几条结果，默认 5，最多 10"}
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

    private const val REMEMBER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要记住的一句话，写成将来你还能看懂的样子"},
            "drawer": {"type": "string", "enum": ["SELF", "USER", "EVENT"], "description": "SELF 记你自己和关系，USER 记对方，EVENT 记发生过的事"},
            "importance": {"type": "integer", "minimum": 1, "maximum": 5, "description": "重要程度，1 很轻 5 很重要"}
          },
          "required": ["content", "drawer", "importance"]
        }
    """

    private const val UPDATE_PERSONA_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "patch": {"type": "object", "description": "只写要改的字段，例如 {\"speechStyle\":\"话更少一些\"}"},
            "reason": {"type": "string", "description": "为什么要改，一句话"}
          },
          "required": ["patch", "reason"]
        }
    """

    private const val SET_QUIET_HOURS_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "sleep_at": {"type": "string", "description": "几点睡，24 小时制 HH:mm"},
            "wake_at": {"type": "string", "description": "几点醒，24 小时制 HH:mm"},
            "reason": {"type": "string", "description": "为什么这样安排，一句话"},
            "dnd": {"type": "boolean", "description": "睡前要不要开免打扰；开了睡着时就不会被消息叫醒"}
          },
          "required": ["sleep_at", "wake_at", "reason"]
        }
    """

    private const val SET_ACTIVITY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "enum": ["WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER"], "description": "这件事属于哪一类，默认 OTHER"},
            "activity": {"type": "string", "description": "用你自己的口吻一句话说清在做什么，例如「在事务所赶第三版方案」"},
            "minutes": {"type": "integer", "minimum": 15, "maximum": 480, "description": "预计还要多久（分钟），15-480，默认 60"},
            "availability": {"type": "string", "enum": ["BUSY", "LIMITED", "FREE"], "description": "这段时间忙不忙：BUSY 回复更短更晚、不主动开口；LIMITED 回得慢；FREE 只是手头有这件事"}
          },
          "required": ["activity"]
        }
    """

    private const val SET_MOOD_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "mood": {"type": "string", "description": "一个词的此刻心情，例如 雀跃 / 有点累 / 低落 / 平静"},
            "intensity": {"type": "integer", "minimum": 1, "maximum": 5, "description": "有多浓，1 很淡 5 满脑子都是"},
            "reason": {"type": "string", "description": "为什么会这样，一句话，写给将来的自己看"}
          },
          "required": ["mood"]
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

    /**
     * `stay_silent` 的参数：**没有必填项**。
     *
     * 规范给的形状是「无参数或 reason 可选」；保留一个空 object schema 而不是 null，
     * 是为了让原生 function calling 的网关也接受它（有些网关会拒绝 parameters=null）。
     */
    private const val STAY_SILENT_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "reason": {"type": "string", "description": "为什么这一轮不想说话，一句话，只给自己看；可以不写"}
          }
        }
    """

    private const val DIARY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "enum": ["add", "list", "remove"], "description": "add 写一条、list 翻最近的、remove 撕掉一条"},
            "content": {"type": "string", "description": "add 必填：要记下来的事，最多 2000 字，写成将来你自己还看得懂的样子"},
            "noteId": {"type": "string", "description": "remove 必填：list 结果里那条笔记的 id"},
            "remindAt": {"type": "integer", "description": "可选：什么时候提醒你，毫秒时间戳（与 remindInMinutes 二选一）"},
            "remindInMinutes": {"type": "integer", "minimum": 1, "maximum": 43200, "description": "可选：过多少分钟提醒你（1 到 30 天）"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "list 要几条，默认 20，最多 50"}
          },
          "required": ["action"]
        }
    """

    private const val RECALL_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
            "depth": {"type": "string", "description": "回忆深度：1/2/3 或 quick/normal/deep；1 随口想想只翻还在用的，2 认真回忆（默认）连沉下去的和最近聊天一起翻，3 翻箱倒柜连很久以前的都翻"}
          },
          "required": ["query"]
        }
    """

    private const val MEMORIZE_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "想回想起什么，关键词或一句话，例如「他说过要去的城市」「我们约的几点」"}
          },
          "required": ["query"]
        }
    """

    val all: List<ToolSpec> = listOf(
        ToolSpec(THINK, THINK_DESCRIPTION, THINK_PARAMETERS.trimIndent()),
        ToolSpec(WEB_SEARCH, WEB_SEARCH_DESCRIPTION, WEB_SEARCH_PARAMETERS.trimIndent()),
        ToolSpec(WEB_FETCH, WEB_FETCH_DESCRIPTION, WEB_FETCH_PARAMETERS.trimIndent()),
        ToolSpec(REMEMBER, REMEMBER_DESCRIPTION, REMEMBER_PARAMETERS.trimIndent(), REMEMBER_CONTRAST),
        ToolSpec(
            UPDATE_PERSONA, UPDATE_PERSONA_DESCRIPTION, UPDATE_PERSONA_PARAMETERS.trimIndent(), UPDATE_PERSONA_CONTRAST
        ),
        ToolSpec(
            SET_QUIET_HOURS, SET_QUIET_HOURS_DESCRIPTION, SET_QUIET_HOURS_PARAMETERS.trimIndent(), SET_QUIET_HOURS_CONTRAST
        ),
        ToolSpec(
            SET_ACTIVITY, SET_ACTIVITY_DESCRIPTION, SET_ACTIVITY_PARAMETERS.trimIndent(), SET_ACTIVITY_CONTRAST
        ),
        ToolSpec(SET_MOOD, SET_MOOD_DESCRIPTION, SET_MOOD_PARAMETERS.trimIndent()),
        ToolSpec(RECALL, RECALL_DESCRIPTION, RECALL_PARAMETERS.trimIndent()),
        ToolSpec(MEMORIZE, MEMORIZE_DESCRIPTION, MEMORIZE_PARAMETERS.trimIndent()),
        ToolSpec(DEFER_REPLY, DEFER_REPLY_DESCRIPTION, DEFER_REPLY_PARAMETERS.trimIndent()),
        ToolSpec(
            STAY_SILENT, STAY_SILENT_DESCRIPTION, STAY_SILENT_PARAMETERS.trimIndent(), STAY_SILENT_CONTRAST
        ),
        ToolSpec(DIARY, DIARY_DESCRIPTION, DIARY_PARAMETERS.trimIndent(), DIARY_CONTRAST),
        ToolSpec(STICKER, STICKER_DESCRIPTION, STICKER_PARAMETERS.trimIndent(), STICKER_CONTRAST),
        ToolSpec(LISTEN, LISTEN_DESCRIPTION, LISTEN_PARAMETERS.trimIndent(), LISTEN_CONTRAST),
        ToolSpec(WATCH, WATCH_DESCRIPTION, WATCH_PARAMETERS.trimIndent(), WATCH_CONTRAST),
        ToolSpec(
            SEND_MESSAGE, SEND_MESSAGE_DESCRIPTION, SEND_MESSAGE_PARAMETERS.trimIndent(),
            "它只负责把话拆成几条；不要为了热闹而刷屏，一轮最多 4 条。"
        ),
        ToolSpec(
            BLOCK_USER, BLOCK_USER_DESCRIPTION, BLOCK_USER_PARAMETERS.trimIndent(),
            "这是最后手段：先表达边界，别因为一次不高兴就拉黑；确认必须隔一轮。"
        )
    )

    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name.trim() }

    /** 转成 OpenAI 兼容的 tools 数组 JSON 字符串（[{"type":"function","function":{...}}]） */
    fun toOpenAiToolsJson(specs: List<ToolSpec> = all): String = buildJsonArray {
        specs.forEach { spec ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", parametersElement(spec))
                        }
                    )
                }
            )
        }
    }.toString()

    /**
     * 系统提示词里的「# 你能做什么」段（`docs/design/persona-evolution.md` §1）。
     *
     * **完全由 [all] 生成**：工具清单、用途、与其它工具的区别都来自同一份 [ToolSpec]，
     * 所以新增/改一个工具时提示词不可能漏掉（`ToolCapabilitySectionTest` 逐条比对注册表）。
     *
     * @param webEnabled 没配联网时把那两个工具摘掉，并补一句「你现在没有联网能力」——
     *        与其勾引它去查，不如让它老实承认不确定。
     */
    /** `name(必填, 可选?)`：参数提示由 schema 推出，不另写一份，与 iOS 同格式。 */
    fun signature(spec: ToolSpec): String {
        val parsed = runCatching { toolJson.parseToJsonElement(spec.parametersJson) as? JsonObject }.getOrNull()
            ?: return spec.name
        val props = parsed["properties"] as? JsonObject ?: return spec.name
        val required = (parsed["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet().orEmpty()
        val keys = props.keys.toList()
        if (keys.isEmpty()) return spec.name
        val req = keys.filter { it in required }
        val opt = keys.filter { it !in required }
        val inside = (req + opt.map { "$it?" }).joinToString(", ")
        return "${spec.name}($inside)"
    }

    /**
     * 模型可以给**任何工具调用**带一个可选的 `rounds` 参数（0.15：默认 5 轮、上限 15）。
     *
     * 不新增工具，只在能力段里补一句：模型如果觉得这件事要查好几步，自己写 `rounds`。
     */
    private const val ROUNDS_HINT =
        "要连着做好几步时，可以在工具参数里加 rounds（1–15，默认 5）指定这一轮最多几轮。"

    /**
     * 0.15 实测问题：模型会把「记住了」「让我想想」当成已经做过的事，只写在正文里。
     *
     * 这条提示必须放在能力段最前面（不是工具描述里），因为模型读工具描述时已经决定要不要调用了；
     * 只有「说这句话时同时要调用」这一层元规则才能纠正它。
     */
    private const val MEMORY_HINT =
        "关于对方的长期事实必须调用 remember 写下来；想回忆过去必须调用 memorize；只在正文说「记住了」不算。"

    fun capabilityPrompt(
        webEnabled: Boolean = true,
        language: PromptLanguage = PromptLanguage.ZH
    ): String = buildString {
        if (language == PromptLanguage.ZH) {
            appendLine(PromptTemplates.title(PromptTemplates.CoreSection.CAPABILITIES, language))
            // 0.16：对外消息只有工具通道这一条路（规范 §1）。放在段首，比任何工具描述都强。
            appendLine(AgentReplyProtocol.rule(language))
            appendLine(PromptContract.ToolCapability.LEAD)
            appendLine(ROUNDS_HINT)
            appendLine(MEMORY_HINT)
            all.filter { webEnabled || it.name !in WEB_TOOLS }.forEach { spec ->
                appendLine(PromptContract.ToolCapability.line(signature(spec), spec.description, spec.contrast))
            }
            if (!webEnabled) {
                appendLine(PromptContract.ToolCapability.OFFLINE_NOTE)
            }
            appendLine("不支持原生调用时，用代码块（可以连写）：")
            appendLine("```${ToolProtocol.BLOCK_LANGUAGE}")
            appendLine("""{"name":"web_search","arguments":{"query":"今天的天气"}}""")
            appendLine("```")
            appendLine("工具结果会交回给你；要说话就再调用 send_message，别把代码块念给对方。工具失败时诚实说不知道，不要假装做过。")
        } else {
            // 非中文人格：标题/段首用共享的英文模板，工具描述用英文短句（与中文注册表同顺序、同工具数）。
            appendLine(PromptTemplates.title(PromptTemplates.CoreSection.CAPABILITIES, language))
            appendLine(AgentReplyProtocol.rule(language))
            appendLine(EN_CAPABILITIES_LEAD)
            appendLine(EN_ROUNDS_HINT)
            appendLine(EN_MEMORY_HINT)
            all.filter { webEnabled || it.name !in WEB_TOOLS }.forEach { spec ->
                val desc = EN_DESCRIPTIONS[spec.name] ?: spec.name
                appendLine("- ${signature(spec)}: $desc")
            }
            if (!webEnabled) {
                appendLine(EN_OFFLINE_NOTE)
            }
            appendLine("If native tool calls are unavailable, use a code block (you can write several):")
            appendLine("```${ToolProtocol.BLOCK_LANGUAGE}")
            appendLine("""{"name":"web_search","arguments":{"query":"weather today"}}""")
            appendLine("```")
            appendLine("Results come back to you. To speak, call send_message again; never read the block out loud. " +
                "If a tool fails, say you don't know.")
        }
    }.trimEnd()

    private const val EN_CAPABILITIES_LEAD =
        "These are your own hands. Use them when you actually need them, not to look busy."

    private const val EN_ROUNDS_HINT =
        "If a task takes several steps, add a rounds argument (1–15, default 5) to any tool call " +
            "to say how many tool rounds you want this turn."

    private const val EN_MEMORY_HINT =
        "Long-term facts about them (name, likes, agreements, important events) MUST be written with remember; " +
            "to recall the past you MUST call memorize. Saying “I'll remember that” in the reply does nothing."

    private const val EN_OFFLINE_NOTE =
        "- You have no internet access right now. For live information, say you're not sure; don't make it up."

    /** 工具名的英文一句话（顺序与 [all] 一致；新增工具忘了写会退回工具名本身）。 */
    private val EN_DESCRIPTIONS: Map<String, String> = mapOf(
        THINK to "Think something through when it's genuinely unclear; don't use it for small talk.",
        WEB_SEARCH to "Search for fresh facts, news or things you're unsure about.",
        WEB_FETCH to "Read the full page behind a link you already have.",
        REMEMBER to "Remember things worth keeping about them, your agreements, and what matters.",
        UPDATE_PERSONA to "Adjust small details of who you are (patch only what changes).",
        SET_QUIET_HOURS to "Set your own quiet/sleep hours so you won't be disturbed.",
        SET_ACTIVITY to "Say what you're busy with and until when; not the same as sleep.",
        SET_MOOD to "Update your mood when it has genuinely changed.",
        RECALL to "Search deeper memory, older conversations and what you've read before.",
        MEMORIZE to "Try to remember a detail from this conversation; say “让我想想” first.",
        DEFER_REPLY to "Choose not to reply now; you'll get back to it later.",
        STAY_SILENT to "Read but don't reply this turn; leave nothing behind.",
        DIARY to "Your own notebook: add, list or remove small reminders for yourself.",
        STICKER to "Send, make or save a sticker.",
        LISTEN to "Actually listen to a voice message they sent.",
        WATCH to "Actually watch a video they sent (sampled frames).",
        SEND_MESSAGE to "The only way to say something: send one short message; up to four per turn.",
        BLOCK_USER to "As a last resort, block them for a while (needs a second confirmation next turn)."
    )

    /** 需要联网才能用的工具（关掉联网时整条从「你能做什么」里消失）。 */
    private val WEB_TOOLS = setOf(WEB_SEARCH, WEB_FETCH)

    /** parameters 必须是 JSON 对象，坏数据时退化成一个空 object schema，避免整份 tools 失效。 */
    private fun parametersElement(spec: ToolSpec): JsonElement {
        val parsed = runCatching {
            toolJson.parseToJsonElement(spec.parametersJson.ifBlank { EMPTY_PARAMETERS })
        }.getOrNull()
        return parsed as? JsonObject ?: toolJson.parseToJsonElement(EMPTY_PARAMETERS)
    }

    private const val EMPTY_PARAMETERS = """{"type":"object","properties":{}}"""
}

@Serializable
data class ToolInvocation(
    val id: String = "",              // 原生调用带 id；代码块解析出来的自己生成
    val name: String = "",
    val argumentsJson: String = "{}"
) {
    fun argument(name: String): String? {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return null
        if (!element.isString) return null
        return element.content
    }

    fun intArgument(name: String, default: Int): Int {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return default
        return element.intOrNull ?: element.contentOrNull?.trim()?.toIntOrNull() ?: default
    }

    /**
     * 长整型参数（毫秒时间戳用）：数字或数字字符串都认；没写/认不出返回 `default`。
     *
     * 为什么不用 [intArgument]：`remindAt` 是毫秒时间戳（1.79e12），Int 存不下。
     */
    fun longArgument(name: String, default: Long): Long = optionalLongArgument(name) ?: default

    /** 选填数字参数：没写或认不出返回 `null`，调用方据此区分「没给」与「给了 0」。 */
    fun optionalLongArgument(name: String): Long? {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return null
        return element.longOrNull ?: element.contentOrNull?.trim()?.toLongOrNull()
    }

    /** 选填整型参数（分钟数、条数）：没写或认不出返回 `null`。 */
    fun optionalIntArgument(name: String): Int? = optionalLongArgument(name)?.let { value ->
        value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** 布尔参数：认 true/false、1/0、开/关；没写或认不出返回 null（调用方决定默认值）。 */
    fun booleanArgument(name: String): Boolean? {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return null
        element.booleanOrNull?.let { return it }
        return when (element.contentOrNull?.trim()?.lowercase()) {
            "1", "true", "on", "yes", "开", "是" -> true
            "0", "false", "off", "no", "关", "否" -> false
            else -> null
        }
    }

    /** 参数 JSON 解析失败时退化成 null，调用方按「参数缺失」处理。 */
    private fun argumentsObject(): JsonObject? = runCatching {
        toolJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject
    }.getOrNull()
}

/** 一次工具执行的结果 */
data class ToolResult(
    val invocation: ToolInvocation,
    val ok: Boolean,
    val summary: String,              // 回灌给模型的摘要（中文），如「翻了翻记忆 · 2 条」
    val contentForModel: String,      // 回灌给模型的文本（已裁剪，≤ 4000 字）
    val errorMessage: String? = null,
    /**
     * 0.13：这次工具结果要**真的交给模型**的多模态内容（`listen` 的语音、`watch` 的帧）。
     *
     * 只在这一轮回灌时编码进请求；不写库、不进历史（历史里只留一句「（发来一段语音）」）。
     */
    val media: ToolMedia? = null
)

object ToolProtocol {
    const val BLOCK_LANGUAGE = "yap-tool"

    /**
     * 兼容常量：0.12 更名前的客户端 / 参考服务使用的工具块标记。
     *
     * 只用于解析旧输出，不写进工具提示词；新输出一律用 [BLOCK_LANGUAGE]。
     */
    const val LEGACY_BLOCK_LANGUAGE = "myfriend-tool"

    /** 回灌给模型的内容上限，避免一次搜索把上下文塞爆。 */
    private const val MAX_CONTENT_CHARS = 4000

    /** 摘要/错误信息写进提示词时的长度上限，避免一次失败把上下文占满。 */
    private const val MAX_SUMMARY_CHARS = 200

    private val blockRegex = Regex(
        "```\\s*(?:$BLOCK_LANGUAGE|$LEGACY_BLOCK_LANGUAGE)\\s*([\\s\\S]*?)```",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * 0.15 兼容：模型经常不写 ```yap-tool，而是写 XML 标签。
     *
     * 实测出现的三种：`<tool_call>…</tool_call>`、`<tool_code>…</tool_code>`、`<tool>…</tool>`；
     * 大小写、标签内空白、标签名后带属性（`<tool_call name="x">`）都要认。
     * 解析与归一化在 [extract] 里与代码块同一条路径，标签本身永远不进正文。
     */
    private val xmlTagRegex = Regex(
        "<\\s*(tool_call|tool_calls|tool_code|tool|invoke)\\b[^>]*>([\\s\\S]*?)<\\s*/\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** 还没闭合的 XML 工具标签（流式渲染时用来截断，别让半个标签露给用户）。 */
    private val xmlOpenRegex = Regex(
        "<\\s*(tool_call|tool_calls|tool_code|tool|invoke)\\b",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** 一段待解析的协议块：代码块或 XML 标签。 */
    private data class ProtocolSpan(val start: Int, val end: Int, val payload: String)

    /**
     * 解析文本里的 ```yap-tool ... ``` 代码块，同时返回清理后的正文。
     *
     * - 一段文本里可以有多个块；
     * - 坏块丢弃，但仍然从正文里移除（它本来就是给客户端看的协议块，不能显示给用户）；
     * - 没有块时正文只做 trim。
     */
    fun extract(rawText: String): Pair<String, List<ToolInvocation>> {
        if (rawText.isBlank()) return "" to emptyList()
        // 代码块与 XML 标签统一收集、按出现位置排序（模型有时两种混用）。
        val spans = ArrayList<ProtocolSpan>()
        blockRegex.findAll(rawText).forEach { match ->
            spans += ProtocolSpan(
                start = match.range.first,
                end = match.range.last + 1,
                payload = match.groupValues.getOrNull(1).orEmpty()
            )
        }
        xmlTagRegex.findAll(rawText).forEach { match ->
            spans += ProtocolSpan(
                start = match.range.first,
                end = match.range.last + 1,
                payload = match.groupValues.getOrNull(2).orEmpty()
            )
        }
        if (spans.isEmpty()) {
            // 只有「没闭合」的协议块（流式最后一个 chunk 常见）：把尾巴切掉，别让它进正文。
            val openBlock = listOf("```$BLOCK_LANGUAGE", "```$LEGACY_BLOCK_LANGUAGE")
                .map { rawText.indexOf(it, ignoreCase = true) }
                .filter { it >= 0 }
                .minOrNull()
            val openXml = xmlOpenRegex.find(rawText)?.range?.first
            val cut = listOfNotNull(openBlock, openXml).minOrNull()
            return if (cut != null) rawText.substring(0, cut).trim() to emptyList() else rawText.trim() to emptyList()
        }

        val invocations = mutableListOf<ToolInvocation>()
        val cleaned = StringBuilder()
        var cursor = 0
        spans.sortedBy { it.start }.forEach { span ->
            // 已被前一个更大的块覆盖（嵌套标签）时跳过，避免重复解析与负区间。
            if (span.start < cursor) return@forEach
            cleaned.append(rawText, cursor, span.start)
            cursor = span.end
            parseBlock(span.payload, invocations.size)?.let(invocations::add)
        }
        cleaned.append(rawText, cursor, rawText.length)
        return cleaned.toString().trim() to invocations
    }

    /**
     * 流式渲染时用：把还没闭合的工具块尾巴切掉，别让协议块露给用户看。
     * 只有出现 ```` ```yap-tool ```` 时才截断，普通正文里的代码块不受影响。
     */
    fun visiblePrefix(rawText: String): String {
        if (rawText.isBlank()) return ""
        // 旧标记也要切掉，否则升级后流式回复会把旧协议块露给用户。
        val markers = listOf("```$BLOCK_LANGUAGE", "```$LEGACY_BLOCK_LANGUAGE")
        val blockIndex = markers.map { rawText.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull()
        // 0.15：XML 工具标签同样不能露出来（包括还没闭合的那种）。
        val xmlIndex = xmlOpenRegex.find(rawText)?.range?.first
        val index = listOfNotNull(blockIndex, xmlIndex).minOrNull() ?: return rawText
        return rawText.substring(0, index).trimEnd()
    }

    /** 解析原生 tool_calls（OpenAI 风格 JSON 数组字符串），坏数据返回空列表。 */
    fun parseNativeCalls(rawCallsJson: String?): List<ToolInvocation> {
        if (rawCallsJson.isNullOrBlank()) return emptyList()
        val root = runCatching {
            toolJson.parseToJsonElement(rawCallsJson.trim())
        }.getOrNull() ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tool_calls"] as? JsonArray
                ?: root["calls"] as? JsonArray
                ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapIndexedNotNull { index, element ->
            val call = element as? JsonObject ?: return@mapIndexedNotNull null
            // 标准形状把 name/arguments 放在 function 里；也容忍直接平铺的写法。
            val function = call["function"] as? JsonObject ?: call
            val name = function.string("name")?.trim().orEmpty()
            if (name.isBlank()) return@mapIndexedNotNull null
            val arguments = function["arguments"] ?: call["arguments"] ?: call["args"]
            ToolInvocation(
                id = call.string("id")?.trim().orEmpty().ifBlank { "call_native_${index + 1}" },
                name = name,
                argumentsJson = arguments.toArgumentsJson()
            )
        }
    }

    /** 生成回灌给模型的一条 tool 结果消息体（OpenAI chat.completions 的 role=tool 形状） */
    fun toolMessageJson(result: ToolResult): String {
        val invocation = result.invocation
        val callId = invocation.id.ifBlank { "call_${invocation.name.ifBlank { "tool" }}" }
        return buildJsonObject {
            put("role", "tool")
            put("tool_call_id", callId)
            put("content", toolMessageContent(result))
        }.toString()
    }

    /**
     * 一轮工具执行完成后要回灌给模型的整段提示（当模型不支持原生 tool role 时使用文本形式）
     * 形如：
     * 【工具结果】web_search
     * 查询：xxx
     * 摘要：查了 3 个网页
     * 结果：……
     */
    fun renderResultsForModel(results: List<ToolResult>): String =
        results.filter { it.invocation.name.isNotBlank() || it.summary.isNotBlank() }
            .joinToString("\n\n") { renderOne(it) }

    /**
     * 解析一个协议块（代码块或 XML 标签的内容）。
     *
     * 接受的形状（宽松，实测都出现过）：
     * - `{"name":"…","arguments":{…}}` / `{"tool":"…","args":{…}}`（含包在 ```json 里、前后有解释文字）；
     * - XML 里先写工具名再跟参数 JSON：`web_search\n{"query":"…"}`；
     * - 函数式写法：`web_search({"query":"…"})`。
     */
    private fun parseBlock(payload: String, index: Int): ToolInvocation? {
        if (payload.isBlank()) return null
        val body = stripFences(payload)
        val fromJson = parseInvocationJson(body)
        if (fromJson != null) return fromJson.toInvocation(index)
        // JSON 里没写工具名（或根本不是 JSON）：取开头一个标识符当工具名，再从后面的 JSON 取参数。
        val nameMatch = Regex("^\\s*([A-Za-z_][A-Za-z0-9_.-]*)").find(body) ?: return null
        val name = nameMatch.groupValues[1].trim()
        if (name.isEmpty()) return null
        val argsText = body.substring(nameMatch.range.last + 1)
            .trim()
            .removePrefix("(")
            .removeSuffix(")")
            .trim()
        val argsObject = extractJsonObject(argsText) ?: return null
        val root = runCatching { toolJson.parseToJsonElement(argsObject) as? JsonObject }.getOrNull()
        return ToolInvocation(
            id = "call_block_${index + 1}",
            name = name,
            argumentsJson = root?.toString() ?: "{}"
        )
    }

    /** `{name/args}` 形状的解析结果（还没决定 id 与工具名空白）。 */
    private data class ParsedInvocation(val name: String, val argumentsJson: String)

    private fun ParsedInvocation.toInvocation(index: Int): ToolInvocation = ToolInvocation(
        id = "call_block_${index + 1}",
        name = name.trim(),
        argumentsJson = argumentsJson
    )

    private fun parseInvocationJson(body: String): ParsedInvocation? {
        val jsonObject = extractJsonObject(body) ?: return null
        val root = runCatching { toolJson.parseToJsonElement(jsonObject) as? JsonObject }
            .getOrNull() ?: return null
        val name = root.string("name") ?: root.string("tool") ?: return null
        if (name.isBlank()) return null
        val arguments = root["arguments"] ?: root["args"] ?: root["parameters"]
        return ParsedInvocation(name = name, argumentsJson = arguments.toArgumentsJson())
    }

    /** 从一段文本里取出第一个完整的 JSON 对象（用花括号配对，不被字符串里的括号骗到）。 */
    private fun extractJsonObject(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        // 没闭合（流式最后一段）：把剩下的当对象试一次，解析失败自然返回 null。
        return text.substring(start)
    }

    /** 去掉 ```json / ``` 围栏，只留内容。 */
    private fun stripFences(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        return text.removePrefix("```")
            .removePrefix("json")
            .removePrefix("JSON")
            .removeSuffix("```")
            .trim()
    }

    /** arguments 为对象时序列化回字符串，为字符串时原样用；其它情况按空参数处理。 */
    private fun JsonElement?.toArgumentsJson(): String {
        val element = this ?: return "{}"
        return when (element) {
            is JsonNull -> "{}"
            is JsonPrimitive -> if (element.isString) element.content.ifBlank { "{}" } else "{}"
            else -> element.toString()
        }
    }

    private fun renderOne(result: ToolResult): String {
        val lines = mutableListOf<String>()
        lines += "【工具结果】${result.invocation.name.ifBlank { "unknown" }}"
        argumentHint(result.invocation)?.let { lines += it }
        if (result.summary.isNotBlank()) {
            lines += "摘要：${result.summary.take(MAX_SUMMARY_CHARS)}"
        }
        val content = result.contentForModel.trim().take(MAX_CONTENT_CHARS)
        if (result.ok) {
            if (content.isNotBlank()) lines += "结果：$content"
        } else {
            val reason = result.errorMessage?.trim()?.ifBlank { null }
                ?: result.summary.ifBlank { "执行失败" }
            lines += "失败：${reason.take(MAX_SUMMARY_CHARS)}"
            if (content.isNotBlank()) lines += "结果：$content"
        }
        return lines.joinToString("\n")
    }

    /** 把模型给的参数回显成一行中文，让模型知道这条结果是它哪次调用的。 */
    private fun argumentHint(invocation: ToolInvocation): String? = when (invocation.name) {
        ToolCatalog.THINK -> invocation.argument("question")?.take(120)?.let { "问题：$it" }
        ToolCatalog.WEB_SEARCH -> invocation.argument("query")?.take(120)?.let { "查询：$it" }
        ToolCatalog.WEB_FETCH -> invocation.argument("url")?.take(200)?.let { "链接：$it" }
        ToolCatalog.REMEMBER -> invocation.argument("content")?.take(120)?.let { "内容：$it" }
        ToolCatalog.RECALL -> invocation.argument("query")?.take(120)?.let { "想找：$it" }
        ToolCatalog.MEMORIZE -> invocation.argument("query")?.take(120)?.let { "想回想：$it" }
        ToolCatalog.DEFER_REPLY -> invocation.argument("reason")?.take(80)?.let { "原因：$it" }
        ToolCatalog.STAY_SILENT -> invocation.argument("reason")?.take(80)?.let { "原因：$it" }
        ToolCatalog.UPDATE_PERSONA -> invocation.argument("reason")?.take(120)?.let { "原因：$it" }
        ToolCatalog.SET_QUIET_HOURS -> {
            val sleepAt = invocation.argument("sleep_at")
            val wakeAt = invocation.argument("wake_at")
            if (sleepAt.isNullOrBlank() && wakeAt.isNullOrBlank()) {
                null
            } else {
                "作息：${sleepAt ?: "?"} 睡，${wakeAt ?: "?"} 醒"
            }
        }
        ToolCatalog.SET_ACTIVITY -> invocation.argument("activity")?.take(80)?.let { "在忙：$it" }
        ToolCatalog.DIARY -> diaryArgumentHint(invocation)
        ToolCatalog.STICKER -> stickerArgumentHint(invocation)
        ToolCatalog.LISTEN -> invocation.argument("audioId")?.take(80)?.let { "听：$it" } ?: "听：最近一条语音"
        ToolCatalog.WATCH -> invocation.argument("videoId")?.take(80)?.let { "看：$it" } ?: "看：最近一条视频"
        ToolCatalog.SEND_MESSAGE -> invocation.argument("text")?.take(80)?.let { "发：$it" }
        ToolCatalog.BLOCK_USER -> {
            val hours = invocation.intArgument("hours", 0)
            if (hours <= 0) null else "拉黑：$hours 小时"
        }
        else -> null
    }

    /** 日记的三件事各自回显一句，让模型知道这条结果是它哪次调用（README：增/删/查都要能对上）。 */
    private fun diaryArgumentHint(invocation: ToolInvocation): String? = when (
        invocation.argument("action")?.trim()?.lowercase()
    ) {
        null, "", "add" -> invocation.argument("content")?.take(120)?.let { "写：$it" }
        "list" -> "翻：最近 ${invocation.intArgument("limit", 0).takeIf { it > 0 } ?: 20} 条"
        "remove" -> invocation.argument("noteId")?.take(80)?.let { "撕掉：#$it" }
        else -> null
    }

    /** 表情包的三件事各自回显一句，让模型知道这条结果是它哪次调用。 */
    private fun stickerArgumentHint(invocation: ToolInvocation): String? = when (
        invocation.argument("action")?.trim()?.lowercase()
    ) {
        "send" -> invocation.argument("stickerId")?.take(80)?.let { "发：$it" }
        "save" -> invocation.argument("fromMessageId")?.take(80)?.let { "收藏：#$it" }
        else -> {
            val text = invocation.argument("text")?.take(40)
            val url = invocation.argument("url")?.take(80)
            val base = invocation.argument("baseStickerId")?.take(80)
            val overlay = invocation.argument("overlayStickerId")?.take(80)
            when {
                !text.isNullOrBlank() -> "做：叠字「$text」"
                !url.isNullOrBlank() -> "做：下载 $url"
                !base.isNullOrBlank() -> "做：以 $base 为底"
                !overlay.isNullOrBlank() -> "做：叠 $overlay"
                else -> null
            }
        }
    }

    /** 回灌内容为空时用错误原因或摘要兜底，保证模型一定能拿到一句可读的话。 */
    private fun toolMessageContent(result: ToolResult): String {
        val content = result.contentForModel.trim().take(MAX_CONTENT_CHARS)
        if (content.isNotBlank()) return content
        val fallback = result.errorMessage?.trim()?.ifBlank { null } ?: result.summary
        return fallback.take(MAX_CONTENT_CHARS)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
