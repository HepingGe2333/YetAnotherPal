import Foundation

// ---------------------------------------------------------------------------
// 「他」的自主能力（插件式工具）协议。
//
// 来源：Android `domain/ToolProtocol.kt` + `domain/ToolTrace.kt`。逐字对照移植：
// 工具清单、给模型看的说明、代码块语法、回灌格式、动作卡片文案都不改——
// 提示词差一个字，模型的调用率就会差一截，这类改动必须两端一起做。
//
// 协议长相：
// 1. 客户端把工具清单（`ToolCatalog.all`）交给模型，并附上 `ToolCatalog.capabilityPrompt`；
// 2. 模型可以走原生 function calling，也可以输出文本代码块：
//
// ```yap-tool
// {"name":"web_search","arguments":{"query":"今天的天气"}}
// ```
//
// 3. 客户端执行后把结果回灌给模型，同时在聊天界面用 `ToolProtocol.describe` 渲染成动作卡片。
//
// 本文件只做协议解析与序列化，不碰网络、数据库和 UI；任何坏数据都不抛异常。
// ---------------------------------------------------------------------------

/// 一个可被模型调用的能力。
public struct ToolSpec: Equatable, Sendable {
    public var name: String
    /// 给模型看，中文，一句话 + 什么时候该用/不该用。
    public var description: String
    /// JSON Schema 字符串，直接塞进 OpenAI `tools[].function.parameters`。
    public var parametersJson: String
    /// 调用之后**世界会变什么**（一段一句，0.13 的「# 你能做什么」段用它生成）。
    ///
    /// 为什么它是工具定义的一部分而不是提示词里手写的一段：模型判断「该不该做」时
    /// 最先看的是副作用（会不会发消息、会不会改自己的人设、对方看不看得见）。
    /// 把它和 schema 放在一起，加一个工具就必然写清副作用，提示词不会漏。
    public var sideEffect: String

    public init(name: String, description: String, parametersJson: String, sideEffect: String = "") {
        self.name = name
        self.description = description
        self.parametersJson = parametersJson
        self.sideEffect = sideEffect
    }
}

public enum ToolCatalog {
    public static let think = "think"
    public static let webSearch = "web_search"
    public static let webFetch = "web_fetch"
    public static let remember = "remember"
    public static let updatePersona = "update_persona"
    public static let setQuietHours = "set_quiet_hours"
    /// 人设引导 §4：模型主动声明「我接下来这段时间在做什么」（非睡眠）。
    public static let setActivity = "set_activity"
    public static let setMood = "set_mood"
    public static let recall = "recall"
    /// 0.15：`memorize(query)` —— 在**会话历史**里想起以前说过的细节
    /// （`docs/design/context-memory-v2.md`「过去的细节通过主题总结检索 + memorize 工具取回」）。
    ///
    /// 与 `recall` 的分工必须写清：`recall` 翻的是**长期记忆 / 聊天记录 / 他看过的资料**（跨会话），
    /// `memorize` 翻的是**这一段会话自己的历史**（关键词 + 主题段 + 时间衰减）。
    /// 模型说「让我想想」时用它。
    public static let memorize = "memorize"
    /// 1.9：这一轮他选择先不说话（§四）。它不是「工具」，是**回复节奏**的一部分：
    /// 客户端拿到它就不再产生助手消息，只留一条到点的念头。
    public static let deferReply = "defer_reply"
    /// 0.13（多维情绪 / 可以不回）：**这一轮就不回**（`spec/mood/README.md` §4）。
    ///
    /// 与 `defer_reply` 的分工是规范钉死的：`defer_reply` 是「过一会儿再回」（会落念头），
    /// `stay_silent` 是「这轮不回」（不落消息、不留念头，只标已读）。
    public static let staySilent = "stay_silent"
    /// 0.13：他自己的日记板（`spec/diary/README.md`）：写一条、翻一翻、删一条。
    ///
    /// 它不是「记住对方的事」（那是 `remember`），而是**他自己的备忘录**：
    /// 内容只有他自己看得见，可以挂一个定时提醒（到点变成念头）。
    public static let diary = "diary"
    /// 0.13：表情包（`spec/stickers/README.md`）：发一个、做一个、收藏一个。
    public static let sticker = "sticker"
    /// 0.13：听一段语音（`spec/audio-video/README.md`）。
    ///
    /// 它**不是**「转写」：客户端把音频原始字节作为多模态输入交给模型，
    /// 模型听到的是声音本身（语气、停顿、环境音），而不是一段 ASR 文本。
    public static let listen = "listen"
    /// 0.13：看一段视频（抽 ≤8 张关键帧交给模型）。
    public static let watch = "watch"
    /// 0.14：一轮里发多条短消息（`spec/experience/README.md` §4）。
    ///
    /// 它不落库：工具只负责说「这条要发出去」，落库与节奏（打字延迟 / 连发间隔）
    /// 由 `ChatService` 统一做 —— 否则「逐条独立消息 + 间隔 0.8–2.5 秒」会散落在两处。
    public static let sendMessage = "send_message"
    /// 0.14：他自己决定暂时不理对方（`spec/experience/README.md` §2）。
    ///
    /// 两轮确认：第一次调用只是提议；**之后的一轮**（中间至少一条用户消息）再调用
    /// `confirm=true` 才生效。到期自动解除，没有 `unblock`。
    public static let blockUser = "block_user"

    /// 连发（规范 §4）：写清「一条一句」「最多 4 条」「每条 ≤120 字」。
    ///
    /// 0.16（`spec/tool-call-agents/README.md` §1）改了语义：它不再只是「想分几条时用」，
    /// 而是**唯一**能把话说出去的通道 —— 模型直接输出正文不再算发出去（客户端只做 legacy 兼容）。
    /// 所以这里必须明说「每一句都调用它」，否则模型会继续用正文回答，而用户那边一片安静。
    ///
    /// 与 Android `SEND_MESSAGE_DESCRIPTION` **逐字一致**（提示词差一个字，模型的调用率就会变）。
    private static let sendMessageDescription = "你要说的每一句话都用它发（这是唯一能把话传到对方那里的方式）：一次一条、" +
        "想到什么先发什么；每条最多 120 字，一轮最多 4 条。不要直接输出正文——正文不算发出去。"
        + "不想说话就用 stay_silent，别用空正文拖着。"
    /// 拉黑（规范 §2）：两轮确认必须写清，否则模型会以为一次调用就生效。
    /// 与 Android `BLOCK_USER_DESCRIPTION` 逐字一致：两轮确认必须写死，否则模型会自问自答。
    private static let blockUserDescription = "对方反复越界、你确实不想再收到消息时，可以拉黑 1–24 小时。必须分两轮：" +
        "第一次调用只提交提议（不生效）；等对方又发过至少一条消息之后，再调用 confirm=true 才生效。" +
        "不要在同一轮里连续确认；到期自动解除，没有 unblock 工具。"

    private static let thinkDescription = "需要认真想清楚、比较几种可能、或者信息不足时调用；日常闲聊不要调用，也不要为了显得深刻而调用。" +
        "如果这件事要连做几步（查资料 → 核对 → 记下来 → 再说），可以顺手给 rounds 说明你估计需要几步工具（1–15，默认 5）。"
    private static let webSearchDescription = "需要最新事实、新闻、价格、版本号、你不确定的信息时调用；纯聊天、或你本来就知道的常识不要调用。"
    private static let webFetchDescription = "已经拿到具体链接、需要读全文时调用；还没拿到链接就先 web_search，不要凭空编 URL。"
    private static let rememberDescription = "关于对方的长期事实（偏好、重要经历、你们之间的约定、重要的人）**必须调用 remember 写进去**——" +
        "只在正文里说「记住了 / 我记下了」不算记住，下一轮你不会记得；一次性的情绪、寒暄、临时安排不要记。"
    private static let updatePersonaDescription = "你对自己的设定有了新的认识、想调整表达方式或偏好时才调用；patch 只写要改的字段，不要整段重写。"
    private static let setQuietHoursDescription = "你想按自己的作息休息、或想避开某些时段时调用；对方正需要陪伴时不要随便设。"
    /// 与 `set_quiet_hours` 的分工必须写清（规范 §4 明文要求）：
    /// 安静时段是「不被打扰」，活动是「我在做事」——两者可以同时存在。
    private static let setActivityDescription = "你接下来这一段时间要做事（上班、通勤、吃饭、开会、赶工、做家务、看书、休息）时调用；" +
        "它只表示「我在做事、可能回得慢」，不是睡觉——睡觉请用 set_quiet_hours，两者可以同时存在。" +
        "写清楚做什么、大概多少分钟（15–480）、这段时间能不能回（availability 取 BUSY/LIMITED/FREE，默认 BUSY）；" +
        "同一件事不要反复调用，状态变了再更新。"
    private static let setMoodDescription = "你此刻的心情跟刚才不一样了才调用（开心、累、烦躁、想念……）；一次只写此刻真实的那一个词，别为了有变化而编。" +
        "如果你想把感受说得更准，可以一起给三维（valence/arousal/dominance）；同一轮里心情又变了就再调一次，以最后一次为准。"
    private static let memorizeDescription = "对方提起以前说过的事、你隐约记得但想不起细节时调用（「让我想想」）；" +
        "它只翻你们这一段会话里的原话，按关键词、话题和时间远近找；" +
        "要找跨会话的旧事或你记在长期记忆里的东西，用 recall；日常闲聊别为了显得记性好而调用。"
    private static let recallDescription = "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己看过什么的时候调用；depth 越深翻得越久也越慢（3 会翻到很早以前，甚至翻不出来）；日常闲聊不要调用。"
    private static let deferReplyDescription = "这一轮你想先不回、过一会儿再说时调用（现在不想说话、想等自己缓一缓、不知道怎么接）；" +
        "调用后这一轮不会发出任何消息，对方只会看到「已读」。不想回就别硬回，别用它来敷衍正在着急的人。"
    /// 使用条件写清（规范 §4）：累/在忙/对方只是随口一句/不想说话时；
    /// **不用于逃避重要话题**，**不要连续用超过 2 轮**；并明确与 `defer_reply` 的区别。
    private static let staySilentDescription = "这一轮你只想「已读」、不想回时调用" +
        "（累了、在忙、对方只是随口一句、不想说话）。" +
        "调用后这一轮不产生任何消息，也不留念头——对方只看到「已读」。" +
        "不要用它逃避重要或着急的话题，也别连续两轮都用；连续使用超过 2 轮会被强制要求回一句。" +
        "对方在等答案就回一句短的。它不是 defer_reply：defer_reply 是「过一会儿再回」" +
        "（会留下一个到点会想起的念头），stay_silent 是「这一轮不回」（什么都不留）。"

    /// 日记板（`spec/diary/README.md`）：他自己的备忘录，不是「记住对方的事」。
    /// 三种动作写清各自的必填项与后果，模型才不会把 remove 当成 list。
    private static let diaryDescription = "维护你自己那份日记板：写下想记住的事、翻一翻之前写的、删掉不想留的；" +
        "它是**你自己的备忘录**（对方看不见内容），和 remember（记住对方的事）不是一回事。" +
        "可以挂一个定时提醒：到点你会自己想起这条，再决定要不要主动开口。" +
        "内容最多 2000 字，一次只做一件事（add / list / remove）。"
    /// 表情包（`spec/stickers/README.md`）：三种动作写清各自的必填项与后果，
    /// 免得模型把「做一个」当成「发一个」（做了不一定会发出去）。
    private static let stickerDescription = "用表情说话：send 把已有表情发出去，make 做一个新表情，save 把消息里的表情收进你的收藏；" +
        "表情是压缩过的 GIF（≤320 px、≤60 帧、≤6 秒、≤512 KB），只能发库里有的 id。" +
        "make 可以下载公开图片（http/https，≤8 MB）、用已有表情当底、叠字或叠另一张表情；" +
        "做好了不会自动发出去，想发还得再调 send。一次只做一个动作（send / make / save）。"

    /// 语音与视频：写清「这是真的听到/看到，不是转写」，以及「不要假装听过」。
    /// 这两句直接决定模型会不会把「对方发了语音」敷衍成一句「嗯嗯知道啦」。
    private static let listenDescription = "对方发来语音、你想知道那段声音里到底说了什么、语气怎么样时调用；" +
        "你听到的是声音本身（不是文字转写），听完按你听到的回答。不填 audioId 就听最近那一段语音；" +
        "没有语音可听时会明确告诉你，别假装听过。"
    private static let watchDescription = "对方发来视频、你想知道画面里发生了什么时调用；" +
        "你会看到几帧关键画面（不是整段视频），看不到的地方老实说不确定。" +
        "不填 videoId 就看最近那一段视频；没有视频可看时会明确告诉你，别假装看过。"
    /// 连发的参数：只有正文（≤120 字，规范 §4）。
    private static let sendMessageParameters = """
    {
      "type": "object",
      "properties": {
        "text": {"type": "string", "description": "这一条要发出去的话，最多 120 字"}
      },
      "required": ["text"]
    }
    """

    /// 拉黑的参数（规范 §2：hours 1–24、reason 可选、confirm 可选）。
    private static let blockUserParameters = """
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

    private static let thinkParameters = """
    {
      "type": "object",
      "properties": {
        "question": {"type": "string", "description": "要思考的问题，尽量具体"},
        "rounds": {"type": "integer", "minimum": 1, "maximum": 15, "description": "可选：这一轮你预计还要几步工具（1–15，默认 5）。只是给自己留余量，不是必须用满"}
      },
      "required": ["question"]
    }
    """

    private static let webSearchParameters = """
    {
      "type": "object",
      "properties": {
        "query": {"type": "string", "description": "搜索关键词或一句话问题"},
        "count": {"type": "integer", "minimum": 1, "maximum": 10, "description": "要几条结果，默认 5，最多 10"}
      },
      "required": ["query"]
    }
    """

    private static let webFetchParameters = """
    {
      "type": "object",
      "properties": {
        "url": {"type": "string", "description": "要读取的完整 http/https 链接"}
      },
      "required": ["url"]
    }
    """

    private static let rememberParameters = """
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

    private static let updatePersonaParameters = """
    {
      "type": "object",
      "properties": {
        "patch": {"type": "object", "description": "只写要改的字段，例如 {\\"speechStyle\\":\\"话更少一些\\"}"},
        "reason": {"type": "string", "description": "为什么要改，一句话"}
      },
      "required": ["patch", "reason"]
    }
    """

    private static let setActivityParameters = """
    {
      "type": "object",
      "properties": {
        "activity": {"type": "string", "description": "你在做什么，一句话，例如「在事务所赶第三版方案」"},
        "minutes": {"type": "integer", "minimum": 15, "maximum": 480, "description": "大概还要多久，分钟；默认 60，最少 15，最多 480"},
        "kind": {"type": "string", "enum": ["WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER"], "description": "活动类型；不确定就写 OTHER"},
        "availability": {"type": "string", "enum": ["BUSY", "LIMITED", "FREE"], "description": "这段时间能不能回：BUSY 在忙，LIMITED 回得慢，FREE 有空；默认 BUSY"}
      },
      "required": ["activity"]
    }
    """

    private static let setQuietHoursParameters = """
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

    private static let setMoodParameters = """
    {
      "type": "object",
      "properties": {
        "mood": {"type": "string", "description": "一个词的此刻心情，例如 雀跃 / 有点累 / 低落 / 平静"},
        "intensity": {"type": "integer", "minimum": 1, "maximum": 5, "description": "有多浓，1 很淡 5 满脑子都是"},
        "reason": {"type": "string", "description": "为什么会这样，一句话，写给将来的自己看"},
        "valence": {"type": "integer", "minimum": -2, "maximum": 2, "description": "可选：愉快度，-2 很不悦 … 2 很愉快"},
        "arousal": {"type": "integer", "minimum": 0, "maximum": 5, "description": "可选：唤醒度，0 很没劲 … 5 很激动"},
        "dominance": {"type": "integer", "minimum": 0, "maximum": 5, "description": "可选：掌控感，0 被压住 … 5 掌控"}
      },
      "required": ["mood"]
    }
    """

    private static let deferReplyParameters = """
    {
      "type": "object",
      "properties": {
        "minutes": {"type": "integer", "minimum": 5, "maximum": 720, "description": "过多少分钟再考虑开口，默认 45"},
        "reason": {"type": "string", "description": "为什么现在不想说，一句话（只给自己看，不会发给他）"}
      }
    }
    """

    /// `stay_silent` 没有必填参数（规范 §4：「无参数或 reason 可选」）。
    private static let staySilentParameters = """
    {
      "type": "object",
      "properties": {
        "reason": {"type": "string", "description": "为什么这一轮不想说，一句话（只给自己看，不会发出去）"}
      }
    }
    """

    private static let diaryParameters = """
    {
      "type": "object",
      "properties": {
        "action": {"type": "string", "enum": ["add", "list", "remove"], "description": "add 写一条，list 翻一翻，remove 删一条"},
        "content": {"type": "string", "description": "add 必填：要写下的内容，最多 2000 字"},
        "noteId": {"type": "string", "description": "remove 必填：要删掉的那条笔记的 id（list 的结果里有）"},
        "remindAt": {"type": "integer", "description": "什么时候提醒你，毫秒时间戳；与 remindInMinutes 二选一，都不填就不提醒"},
        "remindInMinutes": {"type": "integer", "description": "多少分钟之后提醒你；与 remindAt 二选一"},
        "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "list 要几条，默认 20，最多 50"}
      },
      "required": ["action"]
    }
    """

    private static let memorizeParameters = """
    {
      "type": "object",
      "properties": {
        "query": {"type": "string", "description": "想想起什么，几个关键词或一句话，例如「怕冷」「上次说的那家店」「周末的计划」"}
      },
      "required": ["query"]
    }
    """

    private static let recallParameters = """
    {
      "type": "object",
      "properties": {
        "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
        "depth": {"type": "string", "description": "回忆深度：1/2/3 或 quick/normal/deep；1 随口想想只翻还在用的，2 认真回忆（默认）连沉下去的和最近聊天一起翻，3 翻箱倒柜连很久以前的都翻"}
      },
      "required": ["query"]
    }
    """

    private static let stickerParameters = """
    {
      "type": "object",
      "properties": {
        "action": {"type": "string", "enum": ["send", "make", "save"], "description": "send 发一个已有表情，make 做一个新表情，save 把消息里的表情收进收藏"},
        "stickerId": {"type": "string", "description": "send 必填：要发的表情 id（收藏/聊天里出现过的那个）"},
        "url": {"type": "string", "description": "make：要下载的公开图片链接（http/https）"},
        "baseStickerId": {"type": "string", "description": "make：用哪个已有表情当底；不填就用内置空白底"},
        "text": {"type": "string", "description": "make：叠在表情上的文字（最多 80 字）"},
        "overlayStickerId": {"type": "string", "description": "make：再叠上另一张表情"},
        "position": {"type": "string", "enum": ["TOP", "CENTER", "BOTTOM"], "description": "文字/叠图放哪：TOP 上、CENTER 中、BOTTOM 下（默认 BOTTOM）"},
        "fromMessageId": {"type": "string", "description": "save 必填：哪条消息里的表情（消息 id）"}
      },
      "required": ["action"]
    }
    """

    /// `listen` / `watch` 的参数形状：媒体 id 可选（不填就取最近一段）。
    private static let listenParameters = """
    {
      "type": "object",
      "properties": {
        "audioId": {"type": "string", "description": "要听哪一段语音的媒体 id；不填就听最近那一段"}
      }
    }
    """

    private static let watchParameters = """
    {
      "type": "object",
      "properties": {
        "videoId": {"type": "string", "description": "要看哪一段视频的媒体 id；不填就看最近那一段"}
      }
    }
    """

    // MARK: - 副作用（「# 你能做什么」段由这些句子生成）

    /// 一句话写清「调用之后世界会变什么」。
    ///
    /// 故意写成**具体的后果**而不是「会影响模型输出」这类空话：模型靠这些句子判断
    /// 「这件事该不该做」，也靠它们知道「失败时不要假装做过」。
    private static let sendMessageSideEffect = "对方会真的收到这一条（立刻出现）；它也是你唯一能说话的方式，正文不会发出去。"
        + "不要为了热闹而刷屏，一轮最多 4 条。"
    private static let blockUserSideEffect = "这是最后手段：先表达边界，别因为一次不高兴就拉黑；确认必须隔一轮。"
    private static let thinkSideEffect = "只在心里想一遍，不写库、不发消息、什么都不改。"
    private static let webSearchSideEffect = "会向搜索服务发一次查询（花一点额度），结果只给你自己看。"
    private static let webFetchSideEffect = "会联网读那个网页（花一点额度），不改本地任何数据。"
    private static let rememberSideEffect = "往你自己的长期记忆里写一条；对方看不到这条记录，但它会影响你以后怎么回应。"
    private static let updatePersonaSideEffect = "改你自己的人设（版本 +1），从这一轮之后一直生效；不要拿它当普通对话的回执。"
    private static let setQuietHoursSideEffect = "改你的作息（几点睡、几点醒），影响主动消息与夜里被叫醒的规则。"
    private static let setActivitySideEffect = "记下你接下来这段时间在做什么（15–480 分钟），影响语气与要不要主动开口。"
    private static let setMoodSideEffect = "改你此刻的心情，接下来几轮的语气跟着变。"
    private static let memorizeSideEffect = "只在你们这段会话的历史里翻一遍，不改任何东西、不发消息；你会拿到几句当时的原话。"
    private static let recallSideEffect = "只翻你自己的记忆与旧聊天，不改任何东西；翻不到就是翻不到。"
    private static let deferReplySideEffect = "这一轮不会发出任何消息（对方只看到「已读」），过一会儿你再决定要不要开口。"
    private static let staySilentSideEffect = "这一轮不会发出任何消息（对方只看到「已读」），也不会留下过一会儿要开口的念头；下次对方再说话时照常回。"
    private static let diarySideEffect = "写/删你自己那份日记板（对方看不到内容）；挂的定时提醒到点会变成你自己的念头。"
    private static let stickerSideEffect = "send 会真的发出一条表情消息（对方立刻看到）；make 只生成一个新表情（存进你的表情库，不会自动发）；save 只把消息里的表情收进收藏，不发消息。"
    private static let listenSideEffect = "只把那段语音的原始声音交给模型听一遍，不改库、不发消息；听到什么就是什么，没有转写文本。"
    private static let watchSideEffect = "只把那段视频的几帧画面交给模型看，不改库、不发消息；看不到的部分不要编。"

    public static let all: [ToolSpec] = [
        ToolSpec(name: think, description: thinkDescription, parametersJson: thinkParameters,
                 sideEffect: thinkSideEffect),
        ToolSpec(name: webSearch, description: webSearchDescription, parametersJson: webSearchParameters,
                 sideEffect: webSearchSideEffect),
        ToolSpec(name: webFetch, description: webFetchDescription, parametersJson: webFetchParameters,
                 sideEffect: webFetchSideEffect),
        ToolSpec(name: remember, description: rememberDescription, parametersJson: rememberParameters,
                 sideEffect: rememberSideEffect),
        ToolSpec(name: updatePersona, description: updatePersonaDescription,
                 parametersJson: updatePersonaParameters, sideEffect: updatePersonaSideEffect),
        ToolSpec(name: setQuietHours, description: setQuietHoursDescription,
                 parametersJson: setQuietHoursParameters, sideEffect: setQuietHoursSideEffect),
        ToolSpec(name: setActivity, description: setActivityDescription,
                 parametersJson: setActivityParameters, sideEffect: setActivitySideEffect),
        ToolSpec(name: setMood, description: setMoodDescription, parametersJson: setMoodParameters,
                 sideEffect: setMoodSideEffect),
        ToolSpec(name: recall, description: recallDescription, parametersJson: recallParameters,
                 sideEffect: recallSideEffect),
        ToolSpec(name: memorize, description: memorizeDescription, parametersJson: memorizeParameters,
                 sideEffect: memorizeSideEffect),
        ToolSpec(name: deferReply, description: deferReplyDescription,
                 parametersJson: deferReplyParameters, sideEffect: deferReplySideEffect),
        ToolSpec(name: staySilent, description: staySilentDescription,
                 parametersJson: staySilentParameters, sideEffect: staySilentSideEffect),
        ToolSpec(name: diary, description: diaryDescription, parametersJson: diaryParameters,
                 sideEffect: diarySideEffect),
        ToolSpec(name: sticker, description: stickerDescription, parametersJson: stickerParameters,
                 sideEffect: stickerSideEffect),
        ToolSpec(name: listen, description: listenDescription, parametersJson: listenParameters,
                 sideEffect: listenSideEffect),
        ToolSpec(name: watch, description: watchDescription, parametersJson: watchParameters,
                 sideEffect: watchSideEffect),
        // 0.14 的两件「节奏」工具（`spec/experience/README.md` §2/§4）。
        ToolSpec(name: sendMessage, description: sendMessageDescription,
                 parametersJson: sendMessageParameters, sideEffect: sendMessageSideEffect),
        ToolSpec(name: blockUser, description: blockUserDescription,
                 parametersJson: blockUserParameters, sideEffect: blockUserSideEffect)
    ]

    public static func byName(_ name: String) -> ToolSpec? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return all.first { $0.name == trimmed }
    }

    /// 转成 OpenAI 兼容的 tools 数组 JSON 字符串（`[{"type":"function","function":{...}}]`）。
    public static func toOpenAiToolsJson(_ specs: [ToolSpec] = all) -> String {
        let array: [[String: Any]] = specs.map { spec in
            [
                "type": "function",
                "function": [
                    "name": spec.name,
                    "description": spec.description,
                    "parameters": parametersObject(spec)
                ]
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return text
    }

    /// `parameters` 必须是 JSON 对象，坏数据时退化成一个空 object schema，避免整份 tools 失效。
    static func parametersObject(_ spec: ToolSpec) -> [String: Any] {
        let raw = spec.parametersJson.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = raw.isEmpty ? emptyParameters : raw
        if let data = source.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return object
        }
        if let data = emptyParameters.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return object
        }
        return ["type": "object", "properties": [String: Any]()]
    }

    private static let emptyParameters = #"{"type":"object","properties":{}}"#

    /// 「# 你能做什么」段的标题。0.13 起这段由工具定义生成（设计文档 §1），不再手写。
    public static let capabilitySection = "# 你能做什么"

    /// 0.16（`spec/tool-call-agents/README.md` §1）：所有对外消息必须走工具。
    ///
    /// 为什么紧贴在工具清单正上方：模型对「你能做什么」这一段的服从度最高，
    /// 而这段的反面（直接输出正文）此前是**允许**的 —— 不把新规则写在这里，
    /// 模型会继续用正文回答，然后用户那边一片安静（气泡永远不会出现）。
    ///
    /// 三条各自对应一条验收：每条消息必须调用 `send_message`、`stay_silent` 表示不发、
    /// 每轮 ≤4 条且每条 ≤120 字（数字与 `MessageRhythm` / `ExperienceContract` 同源）。
    public static let outboundProtocolTitle = "## 怎么说话"

    /// 中文的三条说话规则（英文版在 `ToolCatalogLanguage.englishOutboundProtocol`）。
    public static func outboundProtocolLines() -> [String] {
        [
            outboundProtocolTitle,
            "你现在**只能**通过 send_message 说话：每一句给对方看的话都必须调用 send_message(text)。"
                + "直接输出的正文不算发出去，对方看不到。",
            "这一轮不想说话就调用 stay_silent（只标已读、不发消息）。两个都没调用算协议违规。",
            "send_message 每轮最多 \(MessageRhythm.maxMessagesPerRound) 条、每条不超过 "
                + "\(MessageRhythm.maxMessageChars) 字；一次一条，想到什么先发什么，不要为了凑条数硬拆一句话。"
        ]
    }

    /// 这一轮真正交给模型的工具（联网关掉时把两个联网工具摘掉）。
    ///
    /// 它与 `toOpenAiToolsJson(_:)` 用的是**同一个数组**：提示词里说「你能用这些」，
    /// 原生 tools 里就必须正好是这些，否则模型会尝试调用一个它看不到的工具。
    public static func enabled(webEnabled: Bool = true) -> [ToolSpec] {
        guard !webEnabled else { return all }
        return all.filter { $0.name != webSearch && $0.name != webFetch }
    }

    /// 系统提示词里的「# 你能做什么」段：**从 `ToolSpec` 生成**，不手写第二份。
    ///
    /// 每条的形状是 `- name(必填, 可选?)：用途（副作用：…）`：
    /// - 参数列表由 `parametersJson` 的 `required` + `properties` 推出（顺序：必填在前，
    ///   其余按字母序，保证同样的 schema 每次生成同样的字符串，便于逐字比对与测试）；
    /// - 用途取 `description`（什么时候该用/别用），副作用取 `sideEffect`（调用后世界变什么）；
    /// - 末尾保留输出格式约定（代码块 + 结果回灌）。
    ///
    /// 历史形状对照：0.12 之前这段叫「## 你可以调用这些工具」且是手写的一串字；
    /// 现在工具集合与文案同源，加一个工具不会漏写说明（`ToolCatalogContractTests` 守着）。
    ///
    /// - Parameter webEnabled: 没配联网时不必勾引他去查，直接说明「你别装懂」。
    public static func capabilityPrompt(webEnabled: Bool = true) -> String {
        // 0.16：`capabilitySection` 必须是第一行（有测试逐字比对），说话规则紧跟在它下面
        //（见 `outboundProtocolLines` 的注释：模型对这一段服从度最高）。
        var lines: [String] = [capabilitySection] + outboundProtocolLines() + [
            "你只能通过下面这些工具做事；工具做不到的事就说做不到，失败也照实说，不要假装做过。",
            "关于对方的长期事实（偏好、经历、约定）必须调用 remember 写下来，只在正文里说「记住了」不算；"
                + "想起对方以前说过的事必须调用 memorize 去翻，不要凭印象编。"
        ]
        for spec in enabled(webEnabled: webEnabled) {
            lines.append("- " + signature(spec) + "：" + spec.description)
            if !spec.sideEffect.isEmpty { lines.append("  · 区别：" + spec.sideEffect) }
        }
        if !webEnabled {
            lines.append("- 你现在没有联网能力。涉及实时信息时，老实说自己不确定，别编。")
        }
        lines.append(contentsOf: [
            "",
            "不支持原生调用时，用代码块（可连写）：",
            "```\(ToolProtocol.blockLanguage)",
            #"{"name":"web_search","arguments":{"query":"今天的天气"}}"#,
            "```",
            "",
            "结果会交回给你；要接着说就再调用 send_message，别把代码块念给对方。"
        ])
        return lines.joined(separator: "\n")
    }

    /// `name(required, optional?)`：参数提示由 schema 推出，不另写一份。
    static func signature(_ spec: ToolSpec) -> String {
        let object = parametersObject(spec)
        let properties = (object["properties"] as? [String: Any]) ?? [:]
        let required = (object["required"] as? [String]) ?? []
        guard !properties.isEmpty else { return spec.name }
        var ordered: [String] = required.filter { properties[$0] != nil }
        ordered += properties.keys
            .filter { !ordered.contains($0) }
            .sorted()
        let display = ordered.map { name in
            required.contains(name) ? name : name + "?"
        }
        return spec.name + "(" + display.joined(separator: ", ") + ")"
    }
}

/// 一次工具调用。
public struct ToolInvocation: Equatable, Sendable {
    /// 原生调用带 id；代码块解析出来的自己生成（`call_block_1` 这种）。
    public var id: String
    public var name: String
    public var argumentsJson: String

    public init(id: String = "", name: String = "", argumentsJson: String = "{}") {
        self.id = id
        self.name = name
        self.argumentsJson = argumentsJson
    }

    public func argument(_ name: String) -> String? {
        guard let object = argumentsObject(), let value = object[name] as? String else { return nil }
        return value
    }

    /// 可选整数参数：没写 / 认不出返回 nil（调用方自己决定默认值）。
    ///
    /// 与 Android 的 `optionalIntArgument` 同义：`block_user` 的 `hours` 要看
    /// 「模型到底写没写」，才能区分「显式给了 12」与「没写、按 12 兜底」。
    public func optionalIntArgument(_ name: String) -> Int? {
        guard let object = argumentsObject() else { return nil }
        if let number = object[name] as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
            return number.intValue
        }
        if let text = object[name] as? String,
           let value = Int(text.trimmingCharacters(in: .whitespaces)) {
            return value
        }
        return nil
    }

    public func intArgument(_ name: String, default fallback: Int) -> Int {
        guard let object = argumentsObject() else { return fallback }
        if let number = object[name] as? NSNumber { return number.intValue }
        if let text = object[name] as? String, let value = Int(text.trimmingCharacters(in: .whitespaces)) {
            return value
        }
        return fallback
    }

    /// 布尔参数：认 true/false、1/0、开/关；没写或认不出返回 nil（调用方决定默认值）。
    ///
    /// 与 Android 的 `booleanArgument` 同一套宽容规则：模型经常把布尔写成字符串。
    public func boolArgument(_ name: String) -> Bool? {
        guard let object = argumentsObject() else { return nil }
        if let number = object[name] as? NSNumber {
            // JSON 里的 true/false 会被解析成 NSNumber（CoreFoundation 的 CFBoolean），
            // 所以先看它是不是布尔，再按 1/0 认；否则 `{"dnd":1}` 会被当成 true。
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
            return number.intValue != 0
        }
        if let text = object[name] as? String {
            switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "1", "true", "on", "yes", "开", "是": return true
            case "0", "false", "off", "no", "关", "否": return false
            default: return nil
            }
        }
        return nil
    }

    /// 参数 JSON 解析失败时退化成 nil，调用方按「参数缺失」处理。
    func argumentsObject() -> [String: Any]? {
        let raw = argumentsJson.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = (raw.isEmpty ? "{}" : raw).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }
}

/// 一次工具执行的结果。
public struct ToolResult: Equatable, Sendable {
    public var invocation: ToolInvocation
    public var ok: Bool
    /// 给界面看的一行中文，如「查了 3 个网页」「记住了：你怕冷」。
    public var summary: String
    /// 回灌给模型的文本（已裁剪，≤ 4000 字）。
    public var contentForModel: String
    public var errorMessage: String?
    /// 聊天里那张动作卡片的标题。
    ///
    /// 默认按工具名走 `ToolProtocol.describe`；但 Android 的 `ToolExecutor` 会按**结果**给不同的标题
    ///（「记住了」/「又确认了一次」/「这次先不记」/「想了想，没有改」……），这里留一个可选的覆盖，
    /// 免得把一个「我决定不记」显示成「记住了一件事」。
    public var traceTitle: String?
    /// 痕迹展开后的正文；不给就用 `contentForModel`（与 Android 的 `detail` 对应）。
    public var traceDetail: String?
    /// 这个工具**已经替这一轮发了一条消息**时，它的 id（0.13 表情包的 `sticker.send`）。
    ///
    /// 为什么需要它：`sticker.send` 的产物本身就是一条助手消息，模型很可能不再写正文；
    /// `ChatService` 原先「没有文字 = 走神了」的判定会把这一轮当成失败。有了这个 id，
    /// 聊天服务就能把「表情已经发出去了」当成正常完成（见 `ChatService.generate`）。
    /// 其它工具都是 nil（它们不直接产生消息）。
    public var producedMessageId: String?
    /// 这次工具调用的产物是**一句要发出去的话**（0.14 的 `send_message`）。
    ///
    /// 为什么工具不直接落库：连发的节奏（首条打字延迟、条与条之间 0.8–2.5 秒、
    /// 停止/失败时不发）属于聊天链路，只能由 `ChatService` 一处决定。
    /// 工具只交出文本，落库与停顿都在那边做 —— 这样「节奏」只有一份实现。
    public var outboundText: String?
    /// 这次工具调用要**额外交给模型的多模态内容**（0.13 的 `listen` / `watch`）。
    ///
    /// 为什么不能只用 `contentForModel`：`listen` 要让模型真的听到声音（`input_audio`），
    /// `watch` 要让模型真的看到画面（多张 `image_url`）。`contentForModel` 里那句
    /// 「我听了一遍…」只是**附带说明**，如果只有它，模型就只能靠编。
    /// `ChatService` 会把这里的内容拼进回灌的那条消息里。
    public var mediaParts: MediaWireParts?

    public init(
        invocation: ToolInvocation,
        ok: Bool,
        summary: String,
        contentForModel: String,
        errorMessage: String? = nil,
        traceTitle: String? = nil,
        traceDetail: String? = nil,
        producedMessageId: String? = nil,
        outboundText: String? = nil,
        mediaParts: MediaWireParts? = nil
    ) {
        self.invocation = invocation
        self.ok = ok
        self.summary = summary
        self.contentForModel = contentForModel
        self.errorMessage = errorMessage
        self.traceTitle = traceTitle
        self.traceDetail = traceDetail
        self.producedMessageId = producedMessageId
        self.outboundText = outboundText
        self.mediaParts = mediaParts
    }
}

public enum ToolProtocol {
    /// 给模型看的代码块语言。与 Android 的 `ToolProtocol.BLOCK_LANGUAGE` 逐字一致。
    public static let blockLanguage = "yap-tool"

    /// 兼容常量：0.12 更名前用的协议块标记，只用来解析旧会话/旧模型输出，
    /// 不再写进系统提示词；与 Android 的 `ToolProtocol.LEGACY_BLOCK_LANGUAGE` 逐字一致。
    public static let legacyBlockLanguage = "myfriend-tool"

    /// 两种标记都认（新的在前）：旧块不解析就会把 JSON 露给用户看。
    static let acceptedLanguages = [blockLanguage, legacyBlockLanguage]

    /// 回灌给模型的内容上限，避免一次搜索把上下文塞爆。
    static let maxContentChars = 4000
    /// 摘要/错误信息写进提示词时的长度上限，避免一次失败把上下文占满。
    static let maxSummaryChars = 200

    /// 只匹配闭合的块。未闭合的块交给 `visiblePrefix` 在流式阶段挡住。
    static let blockPattern = "```\\s*(?:\(acceptedLanguages.joined(separator: "|")))\\s*([\\s\\S]*?)```"

    /// 0.15：模型也会用 XML 标签包工具调用（评测里实际出现过）：
    /// `<tool_call>{…}</tool_call>` / `<tool_code>{…}</tool_code>` / `<tool>{…}</tool>`。
    ///
    /// 为什么要兼容而不是只认 `yap-tool`：真实模型（尤其 DeepSeek 系）会把工具调用写成它训练里
    /// 见过的 XML 形状，协议块没被解析时，XML 与 JSON 就会原样印在气泡里给用户看。
    /// 这里把它们**当成协议块**：解析成调用、从正文里整段移除（含未闭合的尾巴）。
    static let xmlTagNames = ["tool_call", "tool_code", "tool"]

    /// 闭合的 XML 工具块：起始标签 + 内容 + 同名结束标签（允许属性与空白）。
    static let xmlBlockPattern =
        "<\\s*(tool_call|tool_code|tool)\\b[^>]*>([\\s\\S]*?)<\\s*/\\s*\\1\\s*>"

    /// 未闭合的 XML 起始标签（流式阶段 / 模型忘了闭合）：从它开始整段都不该显示给用户。
    static let xmlOpenPattern = "<\\s*(tool_call|tool_code|tool)\\b"

    // MARK: 文本代码块

    /// 解析文本里的 ```yap-tool ... ``` 代码块，同时返回清理后的正文。
    ///
    /// - 一段文本里可以有多个块；
    /// - 坏块丢弃，但仍然从正文里移除（它本来就是给客户端看的协议块，不能显示给用户）；
    /// - 没有块时正文只做 trim。
    public static func extract(_ rawText: String) -> (body: String, calls: [ToolInvocation]) {
        if rawText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ("", [])
        }
        var body = rawText
        var calls: [ToolInvocation] = []
        if containsBlock(in: body) {
            // 逐块扫描：Swift 的 NSRegularExpression 不提供「边扫边拼」的便捷 API，
            // 所以自己维护 cursor，把块之间的正文原样保留（包括换行）。
            var cleaned = ""
            var cursor = body.startIndex
            var searchStart = body.startIndex
            while let match = firstMatch(in: body, from: searchStart) {
                cleaned += body[cursor..<match.range.lowerBound]
                cursor = match.range.upperBound
                searchStart = match.range.upperBound
                let payload = match.payload.trimmingCharacters(in: .whitespacesAndNewlines)
                if let call = parseBlock(payload, index: calls.count) { calls.append(call) }
            }
            cleaned += body[cursor...]
            body = cleaned
        }
        // 0.15：再收一遍 XML 标签形状（`<tool_call>` / `<tool_code>` / `<tool>`）。
        let xml = extractXmlBlocks(from: body, startingIndex: calls.count)
        body = xml.body
        calls.append(contentsOf: xml.calls)
        return (body.trimmingCharacters(in: .whitespacesAndNewlines), calls)
    }

    /// 把 XML 形状的工具块从正文里拆出来。
    ///
    /// - 闭合块：解析内容（JSON，或 JSON 前后有多余文字时取第一个 `{…}`）；
    /// - 未闭合的尾巴：**整段丢掉**（宁可这一条消息少半句，也不能把 `<tool_call>` 印给用户看）；
    /// - 解析失败也照样从正文移除：它本来就是协议内容，不是他说的话。
    static func extractXmlBlocks(
        from text: String,
        startingIndex: Int
    ) -> (body: String, calls: [ToolInvocation]) {
        guard text.contains("<") else { return (text, []) }
        var cleaned = ""
        var cursor = text.startIndex
        var calls: [ToolInvocation] = []
        var searchStart = text.startIndex
        guard let regex = try? Regex("(?i)" + xmlBlockPattern) else { return (text, []) }
        while true {
            let slice = text[searchStart...]
            guard let match = slice.firstMatch(of: regex), match.output.count > 2,
                  let payloadRange = match.output[2].range else { break }
            cleaned += text[cursor..<match.range.lowerBound]
            cursor = match.range.upperBound
            searchStart = match.range.upperBound
            let payload = String(text[payloadRange]).trimmingCharacters(in: .whitespacesAndNewlines)
            if let call = parseBlock(payload, index: startingIndex + calls.count) {
                calls.append(call)
            }
        }
        cleaned += text[cursor...]
        // 未闭合的起始标签：从这里到结尾全部不显示（也不解析：半截 JSON 不该被执行）。
        if let openRange = cleaned.range(of: xmlOpenPattern, options: [.regularExpression, .caseInsensitive]) {
            cleaned = String(cleaned[cleaned.startIndex..<openRange.lowerBound])
        }
        return (cleaned, calls)
    }

    /// 流式渲染时用：把还没闭合的工具块尾巴切掉，别让协议块露给用户看。
    ///
    /// 只有出现 ```yap-tool（或旧标记 ```myfriend-tool）时才截断，普通正文里的代码块不受影响。
    public static func visiblePrefix(_ rawText: String) -> String {
        if rawText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        var cut: String.Index?
        for language in acceptedLanguages {
            let marker = "```\(language)"
            guard let range = rawText.range(of: marker, options: [.caseInsensitive]) else { continue }
            cut = min(cut ?? range.lowerBound, range.lowerBound)
        }
        // 0.15：XML 形状的工具块同样不能露出来（流式时它可能只写了一半）。
        if let xmlRange = rawText.range(of: xmlOpenPattern, options: [.regularExpression, .caseInsensitive]) {
            cut = min(cut ?? xmlRange.lowerBound, xmlRange.lowerBound)
        }
        guard let lowerBound = cut else { return rawText }
        return String(rawText[rawText.startIndex..<lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 解析单个代码块的内容；形状为 `{name,arguments}` 或 `{tool,args,parameters}`。
    static func parseBlock(_ payload: String, index: Int) -> ToolInvocation? {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        // XML 标签里模型常加一句说明（`现在查一下 {"name":…}`）：取第一个 `{` 到最后一个 `}`。
        var jsonText = trimmed
        if !trimmed.hasPrefix("{"),
           let start = trimmed.firstIndex(of: "{"),
           let end = trimmed.lastIndex(of: "}"),
           start < end {
            jsonText = String(trimmed[start...end])
        }
        guard let data = jsonText.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let name = (root["name"] as? String) ?? (root["tool"] as? String)
        guard let rawName = name?.trimmingCharacters(in: .whitespacesAndNewlines), !rawName.isEmpty else {
            return nil
        }
        let arguments = root["arguments"] ?? root["args"] ?? root["parameters"]
        return ToolInvocation(
            id: "call_block_\(index + 1)",
            name: rawName,
            argumentsJson: argumentsJson(from: arguments)
        )
    }

    /// arguments 为对象时序列化回字符串，为字符串时原样用；其它情况按空参数处理。
    ///
    /// 对象会按键名排序输出（`sortedKeys`）：Swift 的 Dictionary 本来就不保序，
    /// 排序至少让「同样的参数」得到同样的字符串——调用去重靠它。
    static func argumentsJson(from value: Any?) -> String {
        guard let value, !(value is NSNull) else { return "{}" }
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "{}" : text
        }
        if JSONSerialization.isValidJSONObject(value),
           let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        return "{}"
    }

    // MARK: 原生 tool_calls

    /// 解析原生 tool_calls（OpenAI 风格 JSON 数组字符串），坏数据返回空列表。
    public static func parseNativeCalls(_ rawCallsJson: String?) -> [ToolInvocation] {
        guard let raw = rawCallsJson?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let array: [[String: Any]]
        if let list = root as? [[String: Any]] {
            array = list
        } else if let object = root as? [String: Any] {
            if let list = object["tool_calls"] as? [[String: Any]] {
                array = list
            } else if let list = object["calls"] as? [[String: Any]] {
                array = list
            } else {
                return []
            }
        } else {
            return []
        }
        var calls: [ToolInvocation] = []
        for (index, call) in array.enumerated() {
            // 标准形状把 name/arguments 放在 function 里；也容忍直接平铺的写法。
            let function = (call["function"] as? [String: Any]) ?? call
            guard let rawName = function["name"] as? String else { continue }
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty { continue }
            let arguments = function["arguments"] ?? call["arguments"] ?? call["args"]
            var id = (call["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if id.isEmpty { id = "call_native_\(index + 1)" }
            calls.append(ToolInvocation(id: id, name: name, argumentsJson: argumentsJson(from: arguments)))
        }
        return calls
    }

    // MARK: 回灌与展示

    /// 生成回灌给模型的一条 tool 结果消息体（OpenAI chat.completions 的 role=tool 形状）。
    public static func toolMessageJson(_ result: ToolResult) -> String {
        let rawId = result.invocation.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallbackName = result.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let callId = rawId.isEmpty ? "call_\(fallbackName.isEmpty ? "tool" : fallbackName)" : rawId
        return MiniJSON.object([
            ("role", .string("tool")),
            ("tool_call_id", .string(callId)),
            ("content", .string(toolMessageContent(result)))
        ])
    }

    /// 一轮工具执行完成后要回灌给模型的整段提示（模型不支持原生 tool role 时用文本形式）。
    public static func renderResultsForModel(_ results: [ToolResult]) -> String {
        results
            .filter { !$0.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || !$0.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map(renderOne)
            .joined(separator: "\n\n")
    }

    /// 工具在界面上的中文标题（「联网搜索」「去忙了」…）。
    ///
    /// 抽成公开函数是因为服务端模式的 `ServerChatGateway` 只拿到工具**名字**
    ///（SSE 的 `tool` 事件不带 `ToolResult`），但它要画同一张动作卡片。
    /// 让两处各写一份中文，第一次改动就会漂。
    public static func title(forTool name: String) -> String {
        switch name {
        case ToolCatalog.think: return String(localized: "想了想")
        case ToolCatalog.webSearch: return String(localized: "联网搜索")
        case ToolCatalog.webFetch: return String(localized: "读了网页")
        case ToolCatalog.remember: return String(localized: "记住了一件事")
        case ToolCatalog.updatePersona: return String(localized: "调整了自己的人设")
        case ToolCatalog.setQuietHours: return String(localized: "安排了自己的作息")
        case ToolCatalog.setActivity: return String(localized: "去忙了")
        case ToolCatalog.setMood: return String(localized: "心情变了")
        case ToolCatalog.recall: return String(localized: "回想了一下")
        // 「（他想了想）」是给用户看的一句：他在努力想起以前说过的话，而不是在查资料。
        case ToolCatalog.memorize: return String(localized: "（他想了想）")
        case ToolCatalog.deferReply: return String(localized: "决定先不回")
        // 这一轮他什么都没说：界面上不该出现任何占位气泡（规范 §4），
        // 这个标题只在「他最近在想什么」这类留痕里用得到。
        case ToolCatalog.staySilent: return String(localized: "这轮没说话")
        // 0.13 日记板：默认是「写日记」，翻/删由 `ToolResult.traceTitle` 按动作覆盖成
        // 「翻日记」「删了日记」（`spec/diary/README.md`：痕迹标题「写日记」「翻日记」）。
        case ToolCatalog.diary: return String(localized: "写日记")
        // 表情：默认「表情」，具体动作由 `ToolResult.traceTitle` 覆盖成
        // 「已发送表情」/「做好了表情」/「已收藏表情」。
        case ToolCatalog.sticker: return String(localized: "表情")
        // 0.13 音视频：听/看不是「工具调用」的黑箱，界面上就是一句「听了一遍」。
        case ToolCatalog.listen: return String(localized: "听了一遍")
        case ToolCatalog.watch: return String(localized: "看了几帧")
        // 0.14：连发与拉黑。连发的痕迹不占地方（它产生的是真消息），默认标题是「发了条消息」。
        case ToolCatalog.sendMessage: return String(localized: "发消息")
        case ToolCatalog.blockUser: return String(localized: "拉黑提议")
        default:
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? String(localized: "动作") : trimmed
        }
    }

    /// 聊天界面上的动作卡片文案：标题 + 副标题（可空）。
    public static func describe(_ result: ToolResult) -> (title: String, subtitle: String?) {
        let summary = result.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let error = result.errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines)
        // 失败时优先说明原因，成功时用结果摘要；think 这种没有副标题的工具固定返回 nil。
        let subtitle: String? = {
            if result.ok { return summary.isEmpty ? nil : summary }
            if let error, !error.isEmpty { return error }
            return summary.isEmpty ? nil : summary
        }()
        let title = title(forTool: result.invocation.name)
        if result.invocation.name == ToolCatalog.think { return (title, nil) }
        return (title, subtitle)
    }

    static func renderOne(_ result: ToolResult) -> String {
        var lines: [String] = []
        let name = result.invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
        lines.append("【工具结果】\(name.isEmpty ? "unknown" : name)")
        if let hint = argumentHint(result.invocation) { lines.append(hint) }
        let summary = result.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        if !summary.isEmpty {
            lines.append("摘要：\(String(summary.prefix(maxSummaryChars)))")
        }
        let content = String(result.contentForModel
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxContentChars))
        if result.ok {
            if !content.isEmpty { lines.append("结果：\(content)") }
        } else {
            let errorText = result.errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines)
            let reason = (errorText?.isEmpty == false)
                ? errorText!
                : (result.summary.isEmpty ? "执行失败" : result.summary)
            lines.append("失败：\(String(reason.prefix(maxSummaryChars)))")
            if !content.isEmpty { lines.append("结果：\(content)") }
        }
        return lines.joined(separator: "\n")
    }

    /// 把模型给的参数回显成一行中文，让模型知道这条结果是它哪次调用的。
    static func argumentHint(_ invocation: ToolInvocation) -> String? {
        switch invocation.name {
        case ToolCatalog.think:
            return invocation.argument("question").map { "问题：\(String($0.prefix(120)))" }
        case ToolCatalog.webSearch:
            return invocation.argument("query").map { "查询：\(String($0.prefix(120)))" }
        case ToolCatalog.webFetch:
            return invocation.argument("url").map { "链接：\(String($0.prefix(200)))" }
        case ToolCatalog.remember:
            return invocation.argument("content").map { "内容：\(String($0.prefix(120)))" }
        case ToolCatalog.recall:
            return invocation.argument("query").map { "想找：\(String($0.prefix(120)))" }
        case ToolCatalog.memorize:
            return invocation.argument("query").map { "想了想：\(String($0.prefix(120)))" }
        case ToolCatalog.deferReply:
            let minutes = invocation.intArgument("minutes", default: 45)
            return "过 \(minutes) 分钟再说"
        case ToolCatalog.staySilent:
            let reason = invocation.argument("reason") ?? ""
            return reason.isEmpty ? "这轮先不回" : "这轮不回：\(String(reason.prefix(60)))"
        case ToolCatalog.updatePersona:
            return invocation.argument("reason").map { "原因：\(String($0.prefix(120)))" }
        case ToolCatalog.setActivity:
            let text = invocation.argument("activity") ?? ""
            let minutes = invocation.intArgument("minutes", default: ToolArgClamp.activityMinutesDefault)
            if text.isEmpty { return nil }
            return "要去忙：\(String(text.prefix(80)))（约 \(ToolArgClamp.clampActivityMinutes(minutes)) 分钟）"
        case ToolCatalog.setQuietHours:
            let sleepAt = invocation.argument("sleep_at")
            let wakeAt = invocation.argument("wake_at")
            if (sleepAt?.isEmpty ?? true) && (wakeAt?.isEmpty ?? true) { return nil }
            return "作息：\(sleepAt ?? "?") 睡，\(wakeAt ?? "?") 醒"
        case ToolCatalog.diary:
            let action = (invocation.argument("action") ?? "").lowercased()
            switch action {
            case "list":
                let limit = invocation.intArgument("limit", default: DiaryLimits.listDefault)
                return "翻最近 \(DiaryLimits.clampListLimit(limit)) 条"
            case "remove":
                let noteId = invocation.argument("noteId") ?? ""
                return noteId.isEmpty ? "要删一条日记" : "删掉：\(String(noteId.prefix(40)))"
            default:
                let content = invocation.argument("content") ?? ""
                return content.isEmpty ? nil : "写下：\(String(content.prefix(80)))"
            }
        case ToolCatalog.listen:
            let audioId = invocation.argument("audioId") ?? ""
            return audioId.isEmpty ? "听最近那段语音" : "听语音：\(String(audioId.prefix(40)))"
        case ToolCatalog.watch:
            let videoId = invocation.argument("videoId") ?? ""
            return videoId.isEmpty ? "看最近那段视频" : "看视频：\(String(videoId.prefix(40)))"
        case ToolCatalog.sendMessage:
            // 与 Android `argumentHint` 一致："发：<前 80 字>"。
            return invocation.argument("text").map { "发：\(String($0.prefix(80)))" }
        case ToolCatalog.blockUser:
            // 与 Android 一致：只写「拉黑：N 小时」；confirm 与否由回灌给模型的话说明。
            let hours = invocation.intArgument("hours", default: 0)
            return hours <= 0 ? nil : "拉黑：\(BlockPolicy.clampHours(hours)) 小时"
        case ToolCatalog.sticker:
            let action = (invocation.argument("action") ?? "").lowercased()
            switch action {
            case "send":
                let stickerId = invocation.argument("stickerId") ?? ""
                return stickerId.isEmpty ? "要发一个表情" : "发表情：\(String(stickerId.prefix(40)))"
            case "make":
                var parts: [String] = []
                if let url = invocation.argument("url"), !url.isEmpty { parts.append("下载 \(String(url.prefix(80)))") }
                if let base = invocation.argument("baseStickerId"), !base.isEmpty { parts.append("底图 \(String(base.prefix(24)))") }
                if let text = invocation.argument("text"), !text.isEmpty { parts.append("叠字「\(String(text.prefix(30)))」") }
                if let overlay = invocation.argument("overlayStickerId"), !overlay.isEmpty { parts.append("叠图 \(String(overlay.prefix(24)))") }
                return parts.isEmpty ? "做一个表情" : parts.joined(separator: " · ")
            case "save":
                let messageId = invocation.argument("fromMessageId") ?? ""
                return messageId.isEmpty ? "收藏一个表情" : "收藏消息 \(String(messageId.prefix(40))) 里的表情"
            default:
                return nil
            }
        default:
            return nil
        }
    }

    /// 回灌内容为空时用错误原因或摘要兜底，保证模型一定能拿到一句可读的话。
    static func toolMessageContent(_ result: ToolResult) -> String {
        let content = String(result.contentForModel
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxContentChars))
        if !content.isEmpty { return content }
        let errorText = result.errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = (errorText?.isEmpty == false) ? errorText! : result.summary
        return String(fallback.prefix(maxContentChars))
    }

    // MARK: 内部

    /// 有没有闭合块：先粗判一次，常见路径（模型没调工具）不必走正则。
    static func containsBlock(in text: String) -> Bool {
        if text.range(of: blockPattern, options: [.regularExpression, .caseInsensitive]) != nil {
            return true
        }
        return text.range(of: xmlBlockPattern, options: [.regularExpression, .caseInsensitive]) != nil
    }

    struct BlockMatch {
        var range: Range<String.Index>
        var payload: String
    }

    /// 在 `from` 之后找第一个闭合块。用 Swift 的 Regex 比 NSRegularExpression 更好取 Range。
    static func firstMatch(in text: String, from start: String.Index) -> BlockMatch? {
        // `(?i)`：语言标记大小写不敏感（模型爱写 `Yap-Tool`）。
        guard let swiftRegex = try? Regex("(?i)" + blockPattern) else { return nil }
        let slice = text[start...]
        guard let match = slice.firstMatch(of: swiftRegex) else { return nil }
        let range = match.range
        guard let payloadRange = match.output.count > 1 ? match.output[1].range : nil else {
            return BlockMatch(range: range, payload: "")
        }
        return BlockMatch(range: range, payload: String(text[payloadRange]))
    }
}

// MARK: - ToolTrace

/// 他这一轮「做过什么」的痕迹：查了什么、记住了什么、想了什么。
///
/// 存进 `messages.toolTraceJson`，聊天里在气泡下方折成一行小字，
/// 点开能看到搜索词、命中的网页标题、失败原因——不是黑箱。
public struct ToolTraceItem: Equatable, Sendable {
    public var name: String
    /// 「联网搜索」
    public var title: String
    /// 「DeepSeek V4 发布」
    public var subtitle: String?
    /// 展开后的正文（查询词 + 结果摘要，或错误信息）
    public var detail: String
    public var ok: Bool

    public init(name: String = "", title: String = "", subtitle: String? = nil,
                detail: String = "", ok: Bool = true) {
        self.name = name
        self.title = title
        self.subtitle = subtitle
        self.detail = detail
        self.ok = ok
    }

    /// 从一次工具执行结果生成痕迹（Android 里是 ToolExecutor 顺手做的，这里给出同一个映射）。
    public static func from(_ result: ToolResult) -> ToolTraceItem {
        let described = ToolProtocol.describe(result)
        let title = (result.traceTitle?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap {
            $0.isEmpty ? nil : $0
        } ?? described.title
        let detail: String
        if let override = result.traceDetail {
            detail = override
        } else {
            detail = result.ok
                ? result.contentForModel
                : (result.errorMessage ?? result.summary)
        }
        return ToolTraceItem(
            name: result.invocation.name,
            title: title,
            subtitle: described.subtitle,
            detail: String(detail.prefix(600)),
            ok: result.ok
        )
    }
}

public enum ToolTraceCodec {

    /// 落库用：没有痕迹时写 nil（不写 `[]`，省得每次读还要判空一次）。
    public static func encode(_ items: [ToolTraceItem]) -> String? {
        if items.isEmpty { return nil }
        let objects: [String] = items.map { item in
            MiniJSON.object([
                ("name", .string(item.name)),
                ("title", .string(item.title)),
                ("subtitle", item.subtitle.map { MiniJSON.Value.string($0) }),
                ("detail", .string(item.detail)),
                ("ok", .bool(item.ok))
            ])
        }
        return "[" + objects.joined(separator: ",") + "]"
    }

    public static func decode(_ raw: String?) -> [ToolTraceItem] {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array.map { object in
            ToolTraceItem(
                name: object["name"] as? String ?? "",
                title: object["title"] as? String ?? "",
                subtitle: object["subtitle"] as? String,
                detail: object["detail"] as? String ?? "",
                ok: (object["ok"] as? NSNumber)?.boolValue ?? true
            )
        }
    }

    /// 气泡下面那一行：多个工具压成一句，如「联网搜索 2 次 · 想了想」。
    public static func summarize(_ items: [ToolTraceItem]) -> String {
        if items.isEmpty { return "" }
        var counts: [String: Int] = [:]
        var order: [String] = []
        for item in items {
            let title = item.title.isEmpty ? item.name : item.title
            if counts[title] == nil { order.append(title) }
            counts[title, default: 0] += 1
        }
        return order.map { title in
            let count = counts[title] ?? 1
            return count > 1 ? String(format: String(localized: "%@ %d 次"), title, count) : title
        }.joined(separator: " · ")
    }
}

// MARK: - 极小的有序 JSON 写出器

/// 为什么不用 `JSONSerialization` 写这两处：
/// `ToolTraceItem` 的字段顺序在 Android 那边是**声明顺序**（kotlinx 的默认行为），
/// 而 Swift 的 Dictionary 不保序、`JSONSerialization` 又没有「按我给的顺序写」的选项。
/// 备份互通不需要它（那份文件由 Backup/* 负责），但「同一个痕迹两端 dump 出来长得一样」
/// 在排查时值很多钱，所以这里用十几行换掉顺序的不确定性。
enum MiniJSON {
    indirect enum Value {
        case string(String)
        case bool(Bool)
        case int(Int)
    }

    /// `pairs` 里 value 为 nil 的项直接省略（对应 Android 的 `explicitNulls = false`）。
    static func object(_ pairs: [(String, Value?)]) -> String {
        var parts: [String] = []
        for (key, value) in pairs {
            guard let value else { continue }
            parts.append("\(quote(key)):\(render(value))")
        }
        return "{" + parts.joined(separator: ",") + "}"
    }

    static func render(_ value: Value) -> String {
        switch value {
        case .string(let text): return quote(text)
        case .bool(let flag): return flag ? "true" : "false"
        case .int(let number): return String(number)
        }
    }

    static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }
}
