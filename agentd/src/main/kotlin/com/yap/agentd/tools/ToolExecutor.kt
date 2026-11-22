package com.yap.agentd.tools

import com.yap.agentd.evolution.PersonaEvolutionRules
import com.yap.agentd.experience.SessionRecall
import com.yap.agentd.llm.LlmContentPart
import com.yap.agentd.llm.LlmUsage
import com.yap.agentd.media.MediaLookup
import com.yap.agentd.media.MediaUnderstanding
import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.persona.QuietHours
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.search.Providers
import com.yap.agentd.search.SearchException
import com.yap.agentd.search.SearchRequest
import com.yap.agentd.search.SearchLimits
import com.yap.agentd.search.WebSearchService
import com.yap.agentd.store.BlockProposal
import com.yap.agentd.store.DiaryNote
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.PendingPatch
import com.yap.agentd.store.Sticker
import com.yap.agentd.store.StoredIntent
import com.yap.agentd.store.StoreJson
import com.yap.agentd.store.StorePersonaNotFoundException
import com.yap.agentd.sticker.StickerComposeException
import com.yap.agentd.sticker.StickerComposer
import com.yap.agentd.sticker.StickerSources
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import com.yap.app.domain.ToolArgClamp
import java.time.Clock
import java.util.UUID

/**
 * 服务端工具执行器：**模型说要做什么，这里真的去做**（`LlmReplyGenerator` 的工具回路调用它）。
 *
 * 三条硬规则：
 * 1. **永远返回一条工具结果，不抛给调用方**：模型给了坏参数也要回
 *    `{"ok":false,"error":"…"}`，让它在下一轮自己纠正；抛出异常会让整轮回复挂掉。
 * 2. **数值一律夹取**（`:agentcore` 的 [ToolArgClamp]）：模型给 `minutes:3000` 不能真的
 *    让「过三天再回」落库。
 * 3. **不泄漏密钥**：`web_search` / `web_fetch` 的 provider/key 只从**服务端环境**读
 *    （与 `ToolsRoutes` 同一套 `YAP_AGENTD_SEARCH_*`），模型参数里没有 key 这一格。
 *
 * 副作用（心情/活动/记忆/日记/表情）直接写进 [FileStore] —— 服务端是唯一真相源，
 * 工具执行不需要客户端参与（`docs/design/server-authoritative.md` §3.5）。
 *
 * @param env 服务端环境变量读取器（测试注入固定表）
 */
class ToolExecutor(
    private val store: FileStore,
    private val search: WebSearchService = WebSearchService(),
    private val env: (String) -> String? = { System.getenv(it) },
    private val clock: Clock = Clock.systemDefaultZone(),
    /**
     * 0.14.1：`listen` / `watch` 的媒体解析层（音频 base64、视频抽帧）。
     *
     * 默认用系统 `ffmpeg`（`--ffmpeg` / `YAP_FFMPEG`，见 [com.yap.agentd.media.FfmpegFrameExtractor]）；
     * 没有解码器时 `watch` 会明确失败。测试注入假抽取器/假媒体服务。
     */
    private val media: MediaUnderstanding = MediaUnderstanding(store),
    /**
     * 0.15：`memorize` 的会话历史检索（关键词 + 主题段 + 时间衰减）。
     * 测试可以注入更小的扫描上限；生产就是默认实现。
     */
    private val recall: SessionRecall = SessionRecall(store, clock)
) {

    /**
     * 一次工具调用的上下文（哪个人格、哪段会话）。
     *
     * [outgoing] 只在**这一轮生成**内共享：`send_message` 每调一次发一条，靠它限制「一轮最多 4 条」。
     * 每次生成都会新建一个 [Context]，所以上一次聊天的计数不会漏到下一次。
     */
    data class Context(
        val personaId: String,
        val sessionId: String,
        val userMessageId: String? = null,
        val outgoing: OutgoingMessages = OutgoingMessages()
    )

    /**
     * 一条已经「发出去」的消息：正文 + 提前分配好的 messageId。
     *
     * 0.16：`send_message` 的工具结果要把 messageId 回给模型（「已发送，messageId=…」），所以 id
     * 在执行工具时就用 [FileStore.newMessageId] 分配好；`ChatService` 落库时**用同一个 id**。
     * 这样模型看到的消息 id 与客户端之后收到的那条真消息一致，不是编的。
     */
    data class SentMessage(val text: String, val messageId: String)

    /** 一轮里已经发出的短消息（`send_message`）；跨工具调用共享，线程模型是「单轮内串行」。 */
    class OutgoingMessages(private val max: Int = MAX_MESSAGES_PER_TURN) {
        private val sent = ArrayList<SentMessage>()

        fun canSend(): Boolean = sent.size < max

        fun remaining(): Int = (max - sent.size).coerceAtLeast(0)

        fun add(text: String, messageId: String): Boolean {
            if (!canSend()) return false
            sent += SentMessage(text, messageId)
            return true
        }

        /** 本轮已经发出的消息（按发送顺序；`ChatService` 按它逐条落库）。 */
        fun all(): List<SentMessage> = sent.toList()
    }

    /**
     * 一次工具调用的结果。
     *
     * @param contentForModel 回灌给模型的正文（中文、短、可执行）
     * @param summary 写进 SSE `tool` 事件与 `toolTraceJson` 的一句话（给人看）
     * @param staySilent 这一轮到此为止、不落 assistant 消息（只有 `stay_silent` 会置位）
     * @param attachments 要挂到 assistant 消息上的媒体引用（`sticker.send`）
     */
    data class Outcome(
        val name: String,
        val ok: Boolean,
        val summary: String,
        val contentForModel: String,
        val staySilent: Boolean = false,
        val attachments: List<String> = emptyList(),
        /** `send_message` 真的发出去的那一条（其它工具为 null）；生成器按顺序收集成多条消息。 */
        val sentMessage: String? = null,
        /** 与 [sentMessage] 配套的 messageId（0.16：工具结果里要回给模型）。 */
        val sentMessageId: String? = null,
        /**
         * 0.16：模型调用了 `think` —— 这一轮**后续**请求的 `reasoning_effort` 升到 `high`。
         *
         * 由 `LlmReplyGenerator` 读走（它持有一轮的思考强度状态）；工具本身没有任何副作用。
         */
        val deepThinking: Boolean = false,
        /**
         * 0.14.1：要给模型看的**多模态内容**（`listen` 的音频 / `watch` 的关键帧）。
         *
         * `role=tool` 的结果只能放字符串，所以生成器会把它们作为一条额外的 user 消息回灌
         * （见 `LlmReplyGenerator`）；工具本身仍然返回正常的文字结果。
         */
        val modelParts: List<LlmContentPart> = emptyList(),
        /** `toolTraceJson` 里的一行（客户端 `ToolTraceItem` 同形）。 */
        val trace: ToolTrace
    )

    data class ToolTrace(
        val name: String,
        val title: String,
        val subtitle: String?,
        val detail: String,
        val ok: Boolean
    )

    /** 工具痕迹的 JSON（客户端 `ToolTraceCodec.decode` 能直接读）。 */
    fun encodeTrace(traces: List<ToolTrace>): String? {
        if (traces.isEmpty()) return null
        val items = traces.mapTo(mutableListOf<JsonValue>()) { trace ->
            val fields = linkedMapOf<String, JsonValue>(
                "name" to StoreJson.str(trace.name),
                "title" to StoreJson.str(trace.title),
                "detail" to StoreJson.str(trace.detail),
                "ok" to StoreJson.bool(trace.ok)
            )
            trace.subtitle?.takeIf { it.isNotBlank() }?.let { fields["subtitle"] = StoreJson.str(it) }
            JsonValue.Obj(fields)
        }
        return Json.stringify(JsonValue.Arr(items))
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 执行一次工具调用。**不抛异常**：不认识的工具、坏参数、上游失败都变成 `ok=false` 的结果。
     *
     * @param argumentsJson 模型给的原始参数文本（可能是坏 JSON）
     */
    fun execute(name: String, argumentsJson: String, context: Context): Outcome {
        val args = parseArgs(argumentsJson)
        return try {
            when (name.trim()) {
                ServerToolCatalog.THINK -> think(args, context)
                ServerToolCatalog.STAY_SILENT -> staySilent(args, context)
                ServerToolCatalog.DEFER_REPLY -> deferReply(args, context)
                ServerToolCatalog.SET_MOOD -> setMood(args, context)
                ServerToolCatalog.SET_ACTIVITY -> setActivity(args, context)
                ServerToolCatalog.DIARY -> diary(args, context)
                ServerToolCatalog.REMEMBER -> remember(args, context)
                ServerToolCatalog.RECALL -> recall(args, context)
                ServerToolCatalog.MEMORIZE -> memorize(args, context)
                ServerToolCatalog.WEB_SEARCH -> webSearch(args)
                ServerToolCatalog.WEB_FETCH -> webFetch(args)
                ServerToolCatalog.STICKER -> sticker(args, context)
                ServerToolCatalog.SEND_MESSAGE -> sendMessage(args, context)
                ServerToolCatalog.BLOCK_USER -> blockUser(args, context)
                ServerToolCatalog.UPDATE_PERSONA -> updatePersona(args, context)
                ServerToolCatalog.SET_QUIET_HOURS -> setQuietHours(args, context)
                ServerToolCatalog.LISTEN -> listen(args, context)
                ServerToolCatalog.WATCH -> watch(args, context)
                else -> failure(name, "不认识的工具：$name", "没有这个工具")
            }
        } catch (missing: StorePersonaNotFoundException) {
            failure(name, "人格不存在", "找不到这个人格")
        } catch (searchFailure: SearchException) {
            failure(name, describeSearchFailure(searchFailure), "没能联网")
        } catch (failure: Exception) {
            // 工具自己的 bug 也不该打挂这一轮回复：回一条「没做成」，让模型决定怎么说。
            failure(name, "${failure::class.simpleName}: ${failure.message}", "工具执行失败")
        }
    }

    // ------------------------------------------------------------------ think（0.16）

    /**
     * `think`：**没有副作用**，只把这一轮后续请求的思考强度升到 `high`。
     *
     * 为什么服务端要执行它：思考强度是请求参数（`reasoning_effort`），模型自己改不了；
     * 它只能通过「调用一个工具」来表达「这里需要想清楚」，由 `LlmReplyGenerator` 在下一轮
     * 请求里换成 `high`。这一轮结束后生成器重建状态，自然回到默认 `low`。
     *
     * 工具结果仍然是一条普通回执（协议完整），不写任何存储、不发 SSE 卡片以外的东西。
     */
    private fun think(args: JsonValue.Obj, context: Context): Outcome {
        val question = args.stringOrNull("question").orEmpty().trim().take(120)
        return Outcome(
            name = ServerToolCatalog.THINK,
            ok = true,
            summary = "多想了一会儿",
            contentForModel = "好，这一轮后面的回复可以想得更深一点；想清楚之后用 `send_message` 把话说出来。",
            deepThinking = true,
            trace = ToolTrace(
                name = ServerToolCatalog.THINK,
                title = "想了想",
                subtitle = question.ifBlank { null },
                detail = question,
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ stay_silent / defer_reply

    private fun staySilent(args: JsonValue.Obj, context: Context): Outcome {
        // 规范 §4：连续使用超过 2 轮会被强制要求回一句（否则「可以不回」会变成「一直不回」）。
        val state = store.autoState(context.personaId)
        if (state.silentLimitReached) {
            return failure(
                ServerToolCatalog.STAY_SILENT,
                "你已经连续 ${state.consecutiveSilent} 轮没说话了，这一轮必须回一句短的",
                "这轮得说点什么"
            )
        }
        val reason = args.stringOrNull("reason").orEmpty()
        store.recordSilentTurn(context.personaId)
        return Outcome(
            name = ServerToolCatalog.STAY_SILENT,
            ok = true,
            summary = "这一轮不回了",
            contentForModel = "好，这一轮就不回了（不产生任何消息）。",
            staySilent = true,
            trace = ToolTrace(
                name = ServerToolCatalog.STAY_SILENT,
                title = "没说话",
                subtitle = reason.take(40).ifBlank { null },
                detail = reason,
                ok = true
            )
        )
    }

    /**
     * `defer_reply`：写一条 `PENDING` 念头，到点由心跳变成主动消息。
     *
     * 分钟数夹到 5–720（`ToolArgClamp`），`latestAt` 是「再晚就作废」的窗口（+30 分钟，与客户端一致）。
     */
    private fun deferReply(args: JsonValue.Obj, context: Context): Outcome {
        val minutes = ToolArgClamp.clampDeferMinutes(args.intOrNull("minutes") ?: ToolArgClamp.MINUTES_DEFAULT)
        val reason = args.stringOrNull("reason").orEmpty().trim().take(80)
        val now = clock.millis()
        val earliestAt = now + minutes * 60_000L
        val intent = StoredIntent(
            id = UUID.randomUUID().toString(),
            personaId = context.personaId,
            sessionId = context.sessionId,
            kind = StoredIntent.KIND_REACH_OUT,
            earliestAt = earliestAt,
            latestAt = earliestAt + DEFER_WINDOW_MS,
            note = reason,
            hint = "",
            status = StoredIntent.STATUS_PENDING,
            createdAt = now,
            sentAt = null
        )
        store.saveIntent(intent)
        val summary = "过 $minutes 分钟再看"
        return Outcome(
            name = ServerToolCatalog.DEFER_REPLY,
            ok = true,
            summary = summary,
            contentForModel = "好，这一轮先不回；大约 $minutes 分钟后你会再想起这件事" +
                (if (reason.isNotBlank()) "（原因：${reason}）" else "") + "。",
            trace = ToolTrace(
                name = ServerToolCatalog.DEFER_REPLY,
                title = "等会儿再说",
                subtitle = summary,
                detail = reason,
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ set_mood / set_activity

    private fun setMood(args: JsonValue.Obj, context: Context): Outcome {
        val label = (args.stringOrNull("label") ?: args.stringOrNull("mood")).orEmpty().trim().take(20)
        if (label.isEmpty()) return failure(ServerToolCatalog.SET_MOOD, "缺少 label", "没写心情")
        val intensity = (args.intOrNull("intensity") ?: 3).coerceIn(1, 5)
        val valence = args.intOrNull("valence")?.coerceIn(-2, 2) ?: com.yap.agentd.store.Mood.defaultValence(label)
        val arousal = args.intOrNull("arousal")?.coerceIn(0, 5) ?: com.yap.agentd.store.Mood.defaultArousal(intensity)
        val dominance = args.intOrNull("dominance")?.coerceIn(0, 5) ?: com.yap.agentd.store.Mood.defaultDominance()
        val reason = args.stringOrNull("reason").orEmpty().trim()
        val mood = store.setMood(
            personaId = context.personaId,
            label = label,
            intensity = intensity,
            valence = valence,
            arousal = arousal,
            dominance = dominance,
            reason = reason
        )
        val summary = "心情：${mood.label}（强度 ${mood.intensity}/5）"
        return Outcome(
            name = ServerToolCatalog.SET_MOOD,
            ok = true,
            summary = summary,
            contentForModel = "记住了：你现在的心情是「${mood.label}」（强度 ${mood.intensity}/5，" +
                "愉快度 ${mood.valence} / 唤醒度 ${mood.arousal} / 掌控感 ${mood.dominance}）。",
            trace = ToolTrace(
                name = ServerToolCatalog.SET_MOOD,
                title = "心情变了",
                subtitle = mood.label,
                detail = reason,
                ok = true
            )
        )
    }

    private fun setActivity(args: JsonValue.Obj, context: Context): Outcome {
        val text = args.stringOrNull("activity").orEmpty().trim()
        if (text.isEmpty()) return failure(ServerToolCatalog.SET_ACTIVITY, "缺少 activity", "没写在忙什么")
        val minutes = ToolArgClamp.clampActivityMinutes(args.intOrNull("minutes") ?: ToolArgClamp.ACTIVITY_MINUTES_DEFAULT)
        val kind = ToolArgClamp.normalizeActivityKind(args.stringOrNull("kind"))
        val availability = ActivityAvailability.parse(args.stringOrNull("availability"))
        val activity = ActivityState.fromMinutes(kind, text, minutes, availability, clock.millis())
        store.updateActivity(context.personaId, ActivityState.encode(activity))
        val summary = "在忙：${activity.activity.take(30)}"
        return Outcome(
            name = ServerToolCatalog.SET_ACTIVITY,
            ok = true,
            summary = summary,
            contentForModel = "记下了：你接下来大约 $minutes 分钟在「${activity.activity}」" +
                "（这段时间${if (availability == ActivityAvailability.BUSY) "很忙，回复会更短更晚" else "可以回，但慢一点"}）。",
            trace = ToolTrace(
                name = ServerToolCatalog.SET_ACTIVITY,
                title = "在忙",
                subtitle = activity.activity.take(40),
                detail = "$minutes 分钟",
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ diary

    private fun diary(args: JsonValue.Obj, context: Context): Outcome {
        val action = args.stringOrNull("action").orEmpty().trim().lowercase()
        return when (action) {
            "add" -> {
                val content = args.stringOrNull("content").orEmpty().trim()
                if (content.isEmpty()) {
                    return failure(ServerToolCatalog.DIARY, "add 需要 content", "没写内容")
                }
                val now = clock.millis()
                val remindAt = args.longOrNull("remindAt")?.takeIf { it > now }
                    ?: args.intOrNull("remindInMinutes")?.let { minutes ->
                        now + minutes.coerceIn(DiaryNote.MIN_REMIND_MINUTES, DiaryNote.MAX_REMIND_MINUTES) * 60_000L
                    }
                val note = store.upsertDiaryNote(
                    personaId = context.personaId,
                    noteId = null,
                    content = content,
                    remindAt = remindAt
                )
                val summary = if (note.remindAt != null) "记下了，到点提醒你" else "记下了"
                Outcome(
                    name = ServerToolCatalog.DIARY,
                    ok = true,
                    summary = summary,
                    contentForModel = "记在备忘里了（id=${note.id}）" +
                        (note.remindAt?.let { "，到 $it 会提醒你" } ?: "") + "。",
                    trace = ToolTrace(
                        name = ServerToolCatalog.DIARY,
                        title = "写日记",
                        subtitle = note.content.take(40),
                        detail = note.content,
                        ok = true
                    )
                )
            }

            "list" -> {
                val limit = (args.intOrNull("limit") ?: DIARY_DEFAULT_LIMIT).coerceIn(1, 50)
                val notes = store.diaryNotes(context.personaId, limit = limit)
                val lines = if (notes.isEmpty()) {
                    "（备忘本是空的）"
                } else {
                    notes.joinToString("\n") { note ->
                        "- ${note.id}｜${note.content.take(60)}" +
                            (note.remindAt?.let { "（提醒：$it）" } ?: "")
                    }
                }
                Outcome(
                    name = ServerToolCatalog.DIARY,
                    ok = true,
                    summary = "翻了翻备忘（${notes.size} 条）",
                    contentForModel = "你记过这些：\n$lines",
                    trace = ToolTrace(
                        name = ServerToolCatalog.DIARY,
                        title = "翻日记",
                        subtitle = "${notes.size} 条",
                        detail = lines,
                        ok = true
                    )
                )
            }

            "remove" -> {
                val noteId = args.stringOrNull("noteId").orEmpty().trim()
                if (noteId.isEmpty()) {
                    return failure(ServerToolCatalog.DIARY, "remove 需要 noteId", "没给要撕的 id")
                }
                val deleted = store.deleteDiaryNote(context.personaId, noteId)
                Outcome(
                    name = ServerToolCatalog.DIARY,
                    ok = deleted,
                    summary = if (deleted) "撕掉了" else "没找到那条",
                    contentForModel = if (deleted) "已经撕掉 $noteId。" else "没找到 $noteId（可能已经删了）。",
                    trace = ToolTrace(
                        name = ServerToolCatalog.DIARY,
                        title = "撕日记",
                        subtitle = noteId,
                        detail = "",
                        ok = deleted
                    )
                )
            }

            else -> failure(ServerToolCatalog.DIARY, "action 只能是 add/list/remove", "日记参数不对")
        }
    }

    // ------------------------------------------------------------------ remember / recall

    private fun remember(args: JsonValue.Obj, context: Context): Outcome {
        val content = args.stringOrNull("content").orEmpty().trim()
        if (content.isEmpty()) return failure(ServerToolCatalog.REMEMBER, "缺少 content", "没写要记什么")
        val importance = ToolArgClamp.clampImportance(args.intOrNull("importance") ?: ToolArgClamp.IMPORTANCE_DEFAULT)
        val tags = args.stringListOrEmpty("tags")
        // 记忆的 importance 是 0–100（客户端列），工具参数是 1–5（给模型看的），这里换算。
        val stored = store.saveMemory(context.personaId, memoryId = null) { _, _ ->
            com.yap.agentd.store.Memory(
                id = "",
                personaId = context.personaId,
                content = content,
                importance = (importance * 20).coerceIn(0, 100),
                tags = tags,
                createdAt = 0,
                updatedAt = 0
            )
        }
        val summary = "记住了：${content.take(30)}"
        return Outcome(
            name = ServerToolCatalog.REMEMBER,
            ok = true,
            summary = summary,
            contentForModel = "记住了（id=${stored.id}）：$content",
            trace = ToolTrace(
                name = ServerToolCatalog.REMEMBER,
                title = "记住了",
                subtitle = content.take(40),
                detail = content,
                ok = true
            )
        )
    }

    /**
     * `recall`：按关键词/标签翻长期记忆。
     *
     * 0.14 修掉的三个检索 bug：
     * 1. **只取最近 500 条**（原实现 `memories(limit = 500)`）：老记忆会被静默丢掉，
     *    现在扫全量（记忆索引常驻内存，扫一遍不读盘）；
     * 2. **整句当子串**：问「那家店 怕冷」永远匹配不到任何一条，现在按空白/标点拆成词元，
     *    命中任意一个就计分（整句命中额外加权）；
     * 3. **只按重要度排序**：命中多的应该排在前面，同分再看重要度与新旧；重复内容去重，
     *    超长结果按总字数截断，避免把一条记忆塞满整个提示词。
     */
    private fun recall(args: JsonValue.Obj, context: Context): Outcome {
        val query = args.stringOrNull("query").orEmpty().trim().take(MAX_RECALL_QUERY_CHARS)
        if (query.isEmpty()) return failure(ServerToolCatalog.RECALL, "缺少 query", "没写要找什么")
        val limit = (args.intOrNull("limit") ?: RECALL_DEFAULT_LIMIT).coerceIn(1, 12)
        val tokens = tokenizeQuery(query)
        val hits = store.memories(context.personaId, limit = Int.MAX_VALUE)
            .asSequence()
            .filter { it.content.isNotBlank() }
            .map { memory -> memory to matchScore(memory, tokens, query) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<com.yap.agentd.store.Memory, Int>> { it.second }
                    .thenByDescending { it.first.importance }
                    .thenByDescending { it.first.updatedAt }
            )
            // 去重：内容完全一样的两条（抽取/导入常见）只留一条。
            .distinctBy { it.first.content.trim() }
            .take(limit)
            .map { it.first }
            .toList()
        val lines = ArrayList<String>()
        var used = 0
        for (memory in hits) {
            val line = "- ${memory.content.replace('\n', ' ').take(MAX_RECALL_LINE_CHARS)}"
            if (used + line.length > MAX_RECALL_RESULT_CHARS) break
            lines += line
            used += line.length
        }
        val text = if (lines.isEmpty()) "（没翻到什么）" else lines.joinToString("\n")
        return Outcome(
            name = ServerToolCatalog.RECALL,
            ok = true,
            summary = "想起了 ${hits.size} 条",
            contentForModel = "关于「$query」，你记得：\n$text",
            trace = ToolTrace(
                name = ServerToolCatalog.RECALL,
                title = "想了想",
                subtitle = query.take(30),
                detail = text,
                ok = true
            )
        )
    }

    /** 查询拆词：空白与中英文标点都当分隔符；单字词元保留（中文人名/地名常常只有一个字有用）。 */
    private fun tokenizeQuery(query: String): List<String> = query
        .split(Regex("[\\s,，、。;；:：!！?？/\\\\|()（）\\[\\]【】]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_RECALL_TOKENS)

    /** 命中打分：整句命中 3 分，每个词元正文命中 2 分、标签命中 1 分。 */
    private fun matchScore(memory: com.yap.agentd.store.Memory, tokens: List<String>, query: String): Int {
        var score = 0
        if (memory.content.contains(query, ignoreCase = true)) score += 3
        if (memory.tags.any { it.contains(query, ignoreCase = true) }) score += 2
        for (token in tokens) {
            if (memory.content.contains(token, ignoreCase = true)) score += 2
            if (memory.tags.any { it.contains(token, ignoreCase = true) }) score += 1
        }
        return score
    }

    // ------------------------------------------------------------------ memorize（0.15）

    /**
     * `memorize`：在**这段会话的历史原文**里检索（0.15 上下文/记忆 v2）。
     *
     * 回灌形状是设计文档的注入示例 `<回忆：某段时间的对话摘要 + 关键原句>`：
     * 模型拿到之后可以顺着那件事说，而不是编一句「我记得你说过…」。
     * 没有命中也要回一条**诚实**的结果（「没翻到」），它会说「想不太起来了」。
     */
    private fun memorize(args: JsonValue.Obj, context: Context): Outcome {
        val query = args.stringOrNull("query").orEmpty().trim().take(MAX_MEMORIZE_QUERY_CHARS)
        if (query.isEmpty()) return failure(ServerToolCatalog.MEMORIZE, "缺少 query", "没写要找什么")
        val limit = (args.intOrNull("limit") ?: SessionRecall.DEFAULT_LIMIT)
            .coerceIn(1, SessionRecall.MAX_LIMIT)
        val found = recall.recall(
            sessionId = context.sessionId,
            query = query,
            limit = limit,
            now = clock.millis()
        )
        val summary = if (found.hits.isEmpty()) "想了想，没想起来" else "想起了 ${found.hits.size} 段"
        return Outcome(
            name = ServerToolCatalog.MEMORIZE,
            ok = true,
            summary = summary,
            contentForModel = if (found.hits.isEmpty()) {
                "你翻了翻聊天记录，关于「$query」没找到什么。就当想不起来了，别编。"
            } else {
                "你翻了翻聊天记录，关于「$query」想起了这些（时间段 + 摘要 + 关键原句）：\n${found.text}\n" +
                    "顺着这些说，别把时间说错，也别补没发生过的细节；想不起来的就老实说想不起来，别编。"
            },
            trace = ToolTrace(
                name = ServerToolCatalog.MEMORIZE,
                title = "想了想",
                subtitle = query.take(30),
                detail = found.text,
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ send_message / block_user（0.14）

    /**
     * `send_message`：一轮里逐条发短消息（`spec/experience/README.md` §4）。
     *
     * 每调用一次就是**一条独立 assistant 消息**（由 `ChatService` 落库并逐条发 `message.created`）。
     * 超过 120 字的截断而不是失败：模型偶尔会多写一句，截断至少还能发出去半句；
     * 超过 4 条则必须失败，否则「连发」会变成刷屏。
     */
    private fun sendMessage(args: JsonValue.Obj, context: Context): Outcome {
        val raw = args.stringOrNull("text") ?: args.stringOrNull("message")
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) {
            return failure(ServerToolCatalog.SEND_MESSAGE, "text 不能为空", "没写要发什么")
        }
        val clamped = text.take(MAX_SEND_MESSAGE_CHARS)
        // 0.16：id 先分配、落库时用同一个（工具结果里的 messageId 因此是真的）。
        val messageId = store.newMessageId()
        if (!context.outgoing.add(clamped, messageId)) {
            return failure(
                ServerToolCatalog.SEND_MESSAGE,
                "这一轮已经发了 $MAX_MESSAGES_PER_TURN 条，别再拆了；剩下的话留到下一轮说",
                "发得太密了"
            )
        }
        val index = context.outgoing.all().size
        val remaining = context.outgoing.remaining()
        return Outcome(
            name = ServerToolCatalog.SEND_MESSAGE,
            ok = true,
            summary = "发出第 $index 条",
            contentForModel = "已发送，messageId=$messageId（这一轮第 $index 条）。" +
                "这就是你要说的话本身，不要再用普通文字重复它。" +
                (if (remaining > 0) "这一轮还能再发 $remaining 条。" else "这一轮已经发满 $MAX_MESSAGES_PER_TURN 条。"),
            sentMessage = clamped,
            sentMessageId = messageId,
            trace = ToolTrace(
                name = ServerToolCatalog.SEND_MESSAGE,
                title = "发了消息",
                subtitle = clamped.take(30),
                detail = clamped,
                ok = true
            )
        )
    }

    /**
     * `block_user`：两轮确认之后才真的拉黑（`spec/experience/README.md` §2/§9）。
     *
     * - 第一次（没有 `confirm`）：只记一条 10 分钟有效的提议，**不生效**；
     * - 之后的一轮（新的 `userMessageId`）带 `confirm=true`：写 `blockedUntil`；
     * - 同一轮里连调两次不算「之后的轮次」：提议记着那一轮的用户消息 id，id 相同就拒绝；
     * - 确认时以提议里的 `hours` 为准（confirm 那次调用里的 `hours` 不参与计算）；
     * - 没有 `unblock`：到点自动解除（用户也不能手动解除）。
     *
     * 回灌给模型的正文是**规范 §9 定死的 JSON**（字段一个不多一个不少）：
     * `{"status":"pending_confirmation","expiresAt":…}` / `{"status":"blocked","blockedUntil":…}`；
     * 拒绝时是 `{"status":"rejected","code":"…"}`（code 与客户端 `BlockPolicy.REJECT_*` 同名）。
     */
    private fun blockUser(args: JsonValue.Obj, context: Context): Outcome {
        val persona = store.persona(context.personaId)
            ?: return failure(ServerToolCatalog.BLOCK_USER, "找不到这个人格", "拉黑失败")
        val now = clock.millis()
        if (persona.isBlockedAt(now)) {
            val until = persona.blockedUntil ?: now
            return rejected(
                ServerToolCatalog.BLOCK_USER,
                code = "already_blocked",
                message = "你已经在拉黑对方了（到 $until 自动解除），不需要再拉一次",
                summary = "已经在拉黑"
            )
        }
        val requestedHours = (args.intOrNull("hours") ?: BlockProposal.MIN_HOURS)
            .coerceIn(BlockProposal.MIN_HOURS, BlockProposal.MAX_HOURS)
        val reason = args.stringOrNull("reason").orEmpty().trim().take(BlockProposal.MAX_REASON_CHARS)
        val confirm = args.boolOrNull("confirm") ?: false
        val userMessageId = context.userMessageId.orEmpty()

        if (!confirm) {
            // 未过期就复用同一条（不续期）；过期/没有才新建（过期的顺手清掉）。
            val stored = store.blockProposal(context.personaId)
            if (stored != null && stored.isExpired(now)) store.clearBlockProposal(context.personaId)
            val existing = stored?.takeIf { !it.isExpired(now) }
            val proposal = existing ?: BlockProposal(
                proposalId = UUID.randomUUID().toString(),
                personaId = context.personaId,
                userMessageId = userMessageId,
                hours = requestedHours,
                reason = reason,
                proposedAt = now
            )
            if (existing == null) store.saveBlockProposal(proposal)
            return Outcome(
                name = ServerToolCatalog.BLOCK_USER,
                ok = true,
                summary = "提议拉黑 ${proposal.hours} 小时（还没生效）",
                contentForModel = statusJson("pending_confirmation", "expiresAt", proposal.expiresAt),
                trace = ToolTrace(
                    name = ServerToolCatalog.BLOCK_USER,
                    title = "想清静一会儿",
                    subtitle = "${proposal.hours} 小时（待确认）",
                    detail = proposal.reason,
                    ok = true
                )
            )
        }

        val proposal = store.blockProposal(context.personaId)
            ?: return rejected(
                ServerToolCatalog.BLOCK_USER,
                code = "no_pending_proposal",
                message = "没有待确认的拉黑提议：先在不带 confirm 的情况下调用一次，下一轮再确认",
                summary = "没有待确认的提议"
            )
        if (proposal.isExpired(now)) {
            store.clearBlockProposal(context.personaId)
            return rejected(
                ServerToolCatalog.BLOCK_USER,
                code = "proposal_expired",
                message = "上次的拉黑提议已经超过 10 分钟作废了；真要拉黑请重新提议一次",
                summary = "提议过期了"
            )
        }
        if (proposal.userMessageId.isNotBlank() && proposal.userMessageId == userMessageId) {
            return rejected(
                ServerToolCatalog.BLOCK_USER,
                code = "need_more_user_messages",
                message = "拉黑不能在同一轮里确认：要等对方再发一条消息，下一轮你再确认",
                summary = "还差一轮"
            )
        }
        // 确认以提议里的时长为谁（提议才是「说好的那件事」）；理由用这次的，没写就沿用它。
        val hours = proposal.hours.coerceIn(BlockProposal.MIN_HOURS, BlockProposal.MAX_HOURS)
        val until = now + hours * 60L * 60L * 1000L
        val updated = store.blockPersona(
            personaId = context.personaId,
            until = until,
            reason = reason.ifBlank { proposal.reason }
        )
        store.clearBlockProposal(context.personaId)
        return Outcome(
            name = ServerToolCatalog.BLOCK_USER,
            ok = true,
            summary = "拉黑了 $hours 小时",
            contentForModel = statusJson("blocked", "blockedUntil", until),
            sentMessage = null,
            trace = ToolTrace(
                name = ServerToolCatalog.BLOCK_USER,
                title = "拉黑了对方",
                subtitle = "$hours 小时",
                detail = updated.blockReason,
                ok = true
            )
        )
    }

    /** 规范 §9 的统一工具回灌：`{"status": …, "<key>": …}`（只有这两个键）。 */
    private fun statusJson(status: String, key: String, value: Long): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                "status" to StoreJson.str(status),
                key to StoreJson.num(value)
            )
        )
    )

    /**
     * 被拒绝的拉黑确认：`{"status":"rejected","code":"…"}`（code 是稳定字符串，不翻译）。
     *
     * `ok = true`：这不是「工具坏了」，而是一次成功的协议级拒绝（客户端 `block_user` 同样走
     * success()，两端 trace 一致，用户看不到红色失败痕迹）。模型看 content 里的 `status` 自己纠正。
     */
    private fun rejected(name: String, code: String, message: String, summary: String): Outcome = Outcome(
        name = name,
        ok = true,
        summary = summary,
        contentForModel = Json.stringify(
            JsonValue.Obj(
                linkedMapOf(
                    "status" to StoreJson.str("rejected"),
                    "code" to StoreJson.str(code)
                )
            )
        ),
        trace = ToolTrace(
            name = name,
            title = "没做成",
            subtitle = summary,
            detail = message,
            ok = false
        )
    )

    // ------------------------------------------------------------------ web_search / web_fetch

    private fun webSearch(args: JsonValue.Obj): Outcome {
        val query = args.stringOrNull("query").orEmpty().trim()
        if (query.isEmpty()) return failure(ServerToolCatalog.WEB_SEARCH, "缺少 query", "没写搜索词")
        val count = ToolArgClamp.clampCount(args.intOrNull("count") ?: SearchLimits.DEFAULT_COUNT)
        val provider = Providers.normalize(env(ENV_SEARCH_PROVIDER) ?: ServerToolCatalogProvider)
        val baseUrl = env(ENV_SEARCH_BASE).orEmpty()
        val apiKey = env(ENV_SEARCH_KEY).orEmpty()
        val hits = search.search(
            SearchRequest(query = query, count = count, provider = provider, baseUrl = baseUrl, apiKey = apiKey)
        )
        val lines = if (hits.isEmpty()) {
            "（没有结果）"
        } else {
            hits.joinToString("\n") { "${it.title}｜${it.url}｜${it.snippet.take(120)}" }
        }
        return Outcome(
            name = ServerToolCatalog.WEB_SEARCH,
            ok = true,
            summary = "搜到 ${hits.size} 条",
            contentForModel = "搜索「$query」的结果：\n$lines",
            trace = ToolTrace(
                name = ServerToolCatalog.WEB_SEARCH,
                title = "联网搜索",
                subtitle = query.take(30),
                detail = lines,
                ok = true
            )
        )
    }

    private fun webFetch(args: JsonValue.Obj): Outcome {
        val url = args.stringOrNull("url").orEmpty().trim()
        if (url.isEmpty()) return failure(ServerToolCatalog.WEB_FETCH, "缺少 url", "没给链接")
        val page = search.webFetch(url, config = null)
        return Outcome(
            name = ServerToolCatalog.WEB_FETCH,
            ok = true,
            summary = "读了 ${page.text.length} 字",
            contentForModel = "《${page.title.ifBlank { page.url }}》正文：\n${page.text.take(6000)}",
            trace = ToolTrace(
                name = ServerToolCatalog.WEB_FETCH,
                title = "打开链接",
                subtitle = page.title.take(40).ifBlank { page.url.take(40) },
                detail = page.text.take(400),
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ sticker

    /**
     * `sticker`：`send` 发一张**已存在**的表情；`list` 列出最近的。
     *
     * 服务端不做 `make`（图像合成需要解码/编码 GIF，且违背「零第三方依赖」）——
     * 工具描述里也没有它，模型不该调用；万一调了，这里回一条诚实的结果而不是假装发过。
     */
    private fun sticker(args: JsonValue.Obj, context: Context): Outcome {
        val action = args.stringOrNull("action").orEmpty().trim().lowercase()
        return when (action) {
            "send" -> {
                val stickerId = args.stringOrNull("stickerId").orEmpty().trim()
                if (stickerId.isEmpty()) {
                    return failure(ServerToolCatalog.STICKER, "send 需要 stickerId", "没给表情 id")
                }
                val sticker = store.sticker(stickerId)
                    ?: return failure(ServerToolCatalog.STICKER, "没有这个表情：$stickerId", "没这个表情")
                val mediaId = store.stickerMediaId(stickerId)
                    ?: return failure(ServerToolCatalog.STICKER, "这个表情没有文件：$stickerId", "表情文件丢了")
                Outcome(
                    name = ServerToolCatalog.STICKER,
                    ok = true,
                    summary = "发了个表情",
                    contentForModel = "已经发出表情（id=${sticker.id}），这一条消息里就带着它，别再说一遍文字。",
                    attachments = listOf(MediaUrl.url(mediaId)),
                    trace = ToolTrace(
                        name = ServerToolCatalog.STICKER,
                        title = "发表情",
                        subtitle = sticker.file,
                        detail = "",
                        ok = true
                    )
                )
            }

            "list" -> {
                val limit = (args.intOrNull("limit") ?: STICKER_DEFAULT_LIMIT).coerceIn(1, 30)
                val stickers = store.stickers(context.personaId, limit = Int.MAX_VALUE)
                    .sortedWith(compareByDescending<Sticker> { it.createdAt })
                    .take(limit)
                val lines = if (stickers.isEmpty()) {
                    "（还没有表情）"
                } else {
                    stickers.joinToString("\n") { "${it.id}｜${it.mime}｜${it.width}x${it.height}" }
                }
                Outcome(
                    name = ServerToolCatalog.STICKER,
                    ok = true,
                    summary = "有 ${stickers.size} 个表情",
                    contentForModel = "你可以发的表情：\n$lines",
                    trace = ToolTrace(
                        name = ServerToolCatalog.STICKER,
                        title = "翻表情",
                        subtitle = "${stickers.size} 个",
                        detail = lines,
                        ok = true
                    )
                )
            }

            "make" -> makeSticker(args, context)

            else -> failure(ServerToolCatalog.STICKER, "action 只能是 send/list/make", "表情参数不对")
        }
    }

    // ------------------------------------------------------------------ update_persona / set_quiet_hours（0.14.1）

    /**
     * `update_persona`：模型想改自己的「四组值」或「称呼」时调用。
     *
     * 走的**不是直接写库**，而是与人格进化完全相同的待审补丁（`pending_patches.json`）：
     * - 只允许 `traits` / `likes` / `behaviors` / `quirks`（增删，一次 ≤3 条，规则与进化一致）
     *   以及 `address`（他怎么叫对方，≤20 字）；
     * - **用户手写的人设正文一律不碰**：名字、描述、世界、话风、关系细节都不在这些字段里；
     * - 用户点「接受」时才会 `version + 1` 写进人格（`accept` 由 `EvolutionService` 回放）。
     *
     * 为什么用待审补丁而不是直接生效（`spec/experience` 没有规定这一点，这里与现有进化保持一致）：
     * 模型「觉得自己变了」是主观判断，0.13 起的人格进化已经建立了「提议 → 用户点头 → 写库」的
     * 通路，`update_persona` 只是换了个入口（模型主动提，而不是评估器批量提），没必要再造一条
     * 直接改人设的路径；用户对「他是谁」始终有最终决定权。
     */
    private fun updatePersona(args: JsonValue.Obj, context: Context): Outcome {
        val persona = store.persona(context.personaId)
            ?: return failure(ServerToolCatalog.UPDATE_PERSONA, "找不到这个人格", "改不了人设")
        val now = clock.millis()
        val current = PersonaFields.current(persona)
        val reason = args.stringOrNull("reason").orEmpty().trim().take(PERSONA_REASON_MAX_CHARS)
        val changes = buildList {
            for (value in args.stringListOrEmpty("addTraits")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.ADD, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("removeTraits")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.REMOVE, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("addLikes")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.LIKES, PersonaEvolutionRules.Op.ADD, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("removeLikes")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.LIKES, PersonaEvolutionRules.Op.REMOVE, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("addBehaviors")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.BEHAVIORS, PersonaEvolutionRules.Op.ADD, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("removeBehaviors")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.BEHAVIORS, PersonaEvolutionRules.Op.REMOVE, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("addQuirks")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.QUIRKS, PersonaEvolutionRules.Op.ADD, value, reason, 1.0))
            }
            for (value in args.stringListOrEmpty("removeQuirks")) {
                add(PersonaEvolutionRules.Change(PersonaEvolutionRules.Field.QUIRKS, PersonaEvolutionRules.Op.REMOVE, value, reason, 1.0))
            }
        }
        // 规则层是不可信的模型输出的唯一闸门：条数/长度/去重/是否存在都在这里过滤。
        val sanitized = PersonaEvolutionRules.sanitize(changes, current)
        val requestedAddress = args.stringOrNull("address").orEmpty().trim().take(PersonaFields.MAX_ADDRESS_CHARS)
        val address = requestedAddress.takeIf { it.isNotEmpty() && it != PersonaFields.address(persona) }
        if (sanitized.isEmpty() && address == null) {
            return failure(
                ServerToolCatalog.UPDATE_PERSONA,
                "这次没有真正需要改的地方（要么和现在一样、要么超出允许的字段/条数）；" +
                    "允许改的只有 traits/likes/behaviors/quirks 与 address（称呼），名字和正文不要动",
                "没有可改的"
            )
        }
        store.dropExpiredPendingPatches(context.personaId, now)
        val patchJson = PersonaEvolutionRules.encodePatch(sanitized, address)
        val existing = store.pendingPatches(context.personaId, now).firstOrNull { it.patchJson == patchJson }
        val patch = existing ?: store.putPendingPatch(
            PendingPatch(
                id = UUID.randomUUID().toString(),
                personaId = context.personaId,
                sessionId = context.sessionId,
                patchJson = patchJson,
                summary = PersonaEvolutionRules.summary(sanitized, address).take(PERSONA_SUMMARY_MAX_CHARS),
                reason = reason.ifBlank { DEFAULT_PERSONA_REASON },
                createdAt = now,
                expiresAt = now + com.yap.agentd.evolution.EvolutionService.PENDING_TTL_MS
            )
        )
        val summary = PersonaEvolutionRules.summary(sanitized, address)
        return Outcome(
            name = ServerToolCatalog.UPDATE_PERSONA,
            ok = true,
            summary = "提请了人设改动（待确认）",
            contentForModel = "已经提了一条待审的改动（patchId=${patch.id}）：$summary。" +
                "对方点头之后才会真的写进你的人设，现在先照常说话，别解释这个流程。",
            trace = ToolTrace(
                name = ServerToolCatalog.UPDATE_PERSONA,
                title = "想改改自己",
                subtitle = summary.take(PERSONA_SUMMARY_MAX_CHARS),
                detail = reason,
                ok = true
            )
        )
    }

    /**
     * `set_quiet_hours`：写人格的安静时段（`quietSleepMinutes` / `quietWakeMinutes`，当天第几分钟）。
     *
     * 为什么直接写库（而不是待审）：安静时段是**行为开关**（到点不主动开口），不是「他是谁」；
     * 而且 `SleepPolicy` 只认这两个分钟数，模型说错时间最多让他早睡/晚睡一会儿，用户随时能在
     * 客户端改回来（备份也带着这两列）。写入时 `version + 1`，与其它人格写入保持同一条版本线。
     *
     * 跨夜正确性：这里只存分钟数并校验「开始 != 结束」，`[start, end)` 是否跨午夜由
     * [QuietHours.contains] / `SleepPolicy.window` 判断（23:30–08:00 覆盖 00:00–07:59）。
     */
    private fun setQuietHours(args: JsonValue.Obj, context: Context): Outcome {
        val persona = store.persona(context.personaId)
            ?: return failure(ServerToolCatalog.SET_QUIET_HOURS, "找不到这个人格", "设不了安静时段")
        val start = args.stringOrNull("start")
        val end = args.stringOrNull("end")
        val hours = QuietHours.of(start, end)
            ?: return failure(
                ServerToolCatalog.SET_QUIET_HOURS,
                "start / end 需要是 HH:MM（例如 23:30 / 08:00），且不能相同",
                "时间写得不对"
            )
        val version = PersonaFields.version(persona) + 1
        val extras = PersonaFields.withVersion(
            PersonaFields.withQuietHours(persona.extraFields, hours),
            version
        )
        store.updatePersona(context.personaId) { existing, _ -> existing.copy(extraFields = extras) }
        return Outcome(
            name = ServerToolCatalog.SET_QUIET_HOURS,
            ok = true,
            summary = "安静时段：${hours.text()}",
            contentForModel = "好：你的安静时段是 ${hours.text()}（本地时间，跨夜也算对）。" +
                "这段时间你不会主动找他；他发来的消息你照样看得到，想回也随时能回。",
            trace = ToolTrace(
                name = ServerToolCatalog.SET_QUIET_HOURS,
                title = "定了安静时段",
                subtitle = hours.text(),
                detail = "",
                ok = true
            )
        )
    }

    /**
     * `sticker.make`（0.14.1）：基底图 + 叠图 + 叠字 → 一张符合 `spec/stickers` 上限的新表情。
     *
     * 素材三选一：`baseStickerId`（已有的表情）/ `url`（公开图片，过 `WebUrlPolicy`）/ 都没有时用
     * 内置空白底（纯色底 + 大字，meme 风格）。`overlayStickerId` 是可选叠图。
     * 生成结果走媒体表（`kind=STICKER`），所以 `stickerId`/`mediaId` 就是 `/v1/media/<id>` 的 id；
     * 结果回灌「做好了（id=…）」，模型可以接着用 `sticker send` 发出去。
     */
    private fun makeSticker(args: JsonValue.Obj, context: Context): Outcome {
        val baseStickerId = args.stringOrNull("baseStickerId")?.trim().orEmpty()
        val url = args.stringOrNull("url")?.trim().orEmpty()
        val overlayStickerId = args.stringOrNull("overlayStickerId")?.trim().orEmpty()
        val text = args.stringOrNull("text")
        val position = args.stringOrNull("position")?.trim()?.uppercase() ?: "CENTER"
        val base = when {
            baseStickerId.isNotEmpty() -> StickerSources.fromSticker(store, baseStickerId)
                ?: return failure(ServerToolCatalog.STICKER, "没有这个表情：$baseStickerId", "没这个表情")

            url.isNotEmpty() -> try {
                StickerSources.fromUrl(url)
            } catch (invalid: StickerComposeException) {
                return failure(ServerToolCatalog.STICKER, invalid.message ?: "图片用不了", "图下载不了")
            }

            else -> null
        }
        val overlay = if (overlayStickerId.isEmpty()) {
            null
        } else {
            StickerSources.fromSticker(store, overlayStickerId)
                ?: return failure(ServerToolCatalog.STICKER, "没有这个表情：$overlayStickerId", "没这个表情")
        }
        val composed = try {
            StickerComposer.compose(base, overlay, text, position)
        } catch (failed: StickerComposeException) {
            return failure(ServerToolCatalog.STICKER, failed.message ?: "做不出来", "做不了新表情")
        }
        val sticker = store.createSticker(
            personaId = context.personaId,
            mime = "image/gif",
            data = composed.gif,
            width = composed.width,
            height = composed.height,
            frames = composed.frames,
            source = Sticker.SOURCE_MODEL_MADE,
            createdBy = Sticker.CREATED_BY_MODEL
        )
        val summary = "做好了表情（${composed.frames} 帧，${composed.gif.size / 1024} KB）"
        return Outcome(
            name = ServerToolCatalog.STICKER,
            ok = true,
            summary = summary,
            contentForModel = "做好了（id=${sticker.id}）：${composed.width}x${composed.height}，" +
                "${composed.frames} 帧，${composed.gif.size / 1024} KB。" +
                "它已经在你的表情里了；想发出去就再调用 sticker send（stickerId=${sticker.id}）。",
            trace = ToolTrace(
                name = ServerToolCatalog.STICKER,
                title = "做了个表情",
                subtitle = "${composed.width}x${composed.height} · ${composed.frames} 帧",
                detail = text?.take(80).orEmpty(),
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ listen / watch（0.14.1）

    /**
     * `listen`（`spec/audio-video`）：把会话里最近/指定的一条语音 base64 后作为 `input_audio`
     * 交给模型。**不伪造转写**：模型听不了时上游会报错（`LlmReplyGenerator` 会把它翻成
     * 「这个模型听不了语音」），这里只负责把真实字节送过去。
     */
    private fun listen(args: JsonValue.Obj, context: Context): Outcome {
        val audioId = args.stringOrNull("audioId") ?: args.stringOrNull("mediaId")
        return when (val lookup = media.listen(context.sessionId, audioId)) {
            is MediaLookup.Failure -> failure(ServerToolCatalog.LISTEN, lookup.reason, "没听到")
            is MediaLookup.Audio -> {
                val payload = lookup.payload
                Outcome(
                    name = ServerToolCatalog.LISTEN,
                    ok = true,
                    summary = "听了一遍语音（${payload.bytes / 1024} KB）",
                    contentForModel = "我听了一遍你发来的这段语音（${payload.bytes / 1024} KB）。" +
                        "听到的内容就在下一条消息里；别复述「我调用了工具」。",
                    modelParts = listOf(LlmContentPart.InputAudio(payload.base64, payload.format)),
                    trace = ToolTrace(
                        name = ServerToolCatalog.LISTEN,
                        title = "听语音",
                        subtitle = "${payload.format} · ${payload.bytes / 1024} KB",
                        detail = "",
                        ok = true
                    )
                )
            }

            is MediaLookup.Video -> failure(ServerToolCatalog.LISTEN, "这不是语音", "没听到")
        }
    }

    /**
     * `watch`（`spec/audio-video`）：用系统 `ffmpeg` 抽 ≤8 帧 JPEG，作为多个 `image_url` 交给模型。
     *
     * 没有 ffmpeg 时 [MediaLookup.Failure] 的原因是固定的一句「服务器没有视频解码器，看不了视频」，
     * 工具失败、模型据此老实说看不了（绝不伪造帧）。
     */
    private fun watch(args: JsonValue.Obj, context: Context): Outcome {
        val videoId = args.stringOrNull("videoId") ?: args.stringOrNull("mediaId")
        return when (val lookup = media.watch(context.sessionId, videoId)) {
            is MediaLookup.Failure -> failure(ServerToolCatalog.WATCH, lookup.reason, "没看成")
            is MediaLookup.Video -> {
                val frames = lookup.frames
                val parts = frames.jpegs.map { jpeg ->
                    LlmContentPart.ImageUrl(
                        "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
                    )
                }
                Outcome(
                    name = ServerToolCatalog.WATCH,
                    ok = true,
                    summary = "看了 ${frames.frameCount} 帧",
                    contentForModel = "我看了这段视频的 ${frames.frameCount} 帧画面（只抽了关键帧、没有声音）。" +
                        "看到的内容就在下一条消息里；别复述「我调用了工具」。",
                    modelParts = parts,
                    trace = ToolTrace(
                        name = ServerToolCatalog.WATCH,
                        title = "看视频",
                        subtitle = "${frames.frameCount} 帧",
                        detail = "",
                        ok = true
                    )
                )
            }

            is MediaLookup.Audio -> failure(ServerToolCatalog.WATCH, "这不是视频", "没看成")
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun failure(name: String, message: String, summary: String): Outcome = Outcome(
        name = name,
        ok = false,
        summary = summary,
        contentForModel = "工具调用没有成功：$message",
        trace = ToolTrace(
            name = name,
            title = "没做成",
            subtitle = summary,
            detail = message,
            ok = false
        )
    )

    /** 模型参数：坏 JSON 当成空对象（执行器会按「缺字段」回一条失败结果）。 */
    private fun parseArgs(raw: String): JsonValue.Obj {
        val text = raw.trim().ifEmpty { "{}" }
        return runCatching { Json.parse(text) as? JsonValue.Obj }.getOrNull() ?: JsonValue.Obj()
    }

    private fun JsonValue.Obj.stringOrNull(key: String): String? =
        (fields[key] as? JsonValue.Str)?.value

    private fun JsonValue.Obj.intOrNull(key: String): Int? = when (val value = fields[key]) {
        is JsonValue.Num -> value.asInt
        is JsonValue.Str -> value.value.trim().toIntOrNull()
        else -> null
    }

    private fun JsonValue.Obj.boolOrNull(key: String): Boolean? = when (val value = fields[key]) {
        is JsonValue.Bool -> value.value
        is JsonValue.Str -> value.value.trim().lowercase().let {
            when (it) {
                "true", "1", "yes", "是" -> true
                "false", "0", "no", "否" -> false
                else -> null
            }
        }
        else -> null
    }

    private fun JsonValue.Obj.longOrNull(key: String): Long? = when (val value = fields[key]) {
        is JsonValue.Num -> value.asLong
        is JsonValue.Str -> value.value.trim().toLongOrNull()
        else -> null
    }

    private fun JsonValue.Obj.stringListOrEmpty(key: String): List<String> {
        val value = fields[key] ?: return emptyList()
        return when (value) {
            is JsonValue.Arr -> value.items.mapNotNull { (it as? JsonValue.Str)?.value?.trim()?.takeIf(String::isNotEmpty) }
            is JsonValue.Str -> value.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    companion object {
        /** `defer_reply` 的「再晚就作废」窗口（与客户端 `DeferReply` 一致：+30 分钟）。 */
        const val DEFER_WINDOW_MS: Long = 30 * 60 * 1000L

        /** 一条「连发」消息的字数上限（规范 §4：≤120 字）。 */
        const val MAX_SEND_MESSAGE_CHARS = 120

        /** 一轮最多发几条（规范 §4：最多 4 条/轮）。 */
        const val MAX_MESSAGES_PER_TURN = 4

        const val DIARY_DEFAULT_LIMIT = 20
        const val RECALL_DEFAULT_LIMIT = 5

        /** recall 的查询最长多少字（再长也不会是「关键词」，只会把匹配变慢）。 */
        const val MAX_RECALL_QUERY_CHARS = 200

        /** `memorize` 的查询上限（与 recall 同一口径：再长就不是关键词了）。 */
        const val MAX_MEMORIZE_QUERY_CHARS = 200

        /** recall 最多拆几个词元。 */
        const val MAX_RECALL_TOKENS = 12

        /** recall 每条结果截断到多少字。 */
        const val MAX_RECALL_LINE_CHARS = 80

        /** recall 结果总字数上限（别让一次回忆塞满提示词）。 */
        const val MAX_RECALL_RESULT_CHARS = 2_000
        const val STICKER_DEFAULT_LIMIT = 12

        /** `update_persona` 的 reason 上限与补丁摘要上限。 */
        const val PERSONA_REASON_MAX_CHARS = 120
        const val PERSONA_SUMMARY_MAX_CHARS = 160

        const val DEFAULT_PERSONA_REASON = "这几轮聊下来，他觉得自己有点变化（需要你点头才会写进人设）。"

        /** 与 `ToolsRoutes` 共用同一套环境变量（key 永不出现在模型参数里）。 */
        const val ENV_SEARCH_PROVIDER = "YAP_AGENTD_SEARCH_PROVIDER"
        const val ENV_SEARCH_BASE = "YAP_AGENTD_SEARCH_BASE"
        const val ENV_SEARCH_KEY = "YAP_AGENTD_SEARCH_KEY"

        /** 没配 provider 时的默认（与 `ToolsRoutes.DEFAULT_SEARCH_PROVIDER` 一致）。 */
        val ServerToolCatalogProvider: String = Providers.MESSAGEAPI

        /** 当前会话里最后一条用户消息 id（工具上下文用得到，也方便测试断言）。 */
        fun lastUserMessageId(store: FileStore, sessionId: String): String? =
            store.latestMessages(sessionId, 20).lastOrNull { it.role == MessageRole.USER }?.id

        /** 一次工具调用大概值多少 token（用量统计的兜底；网关没报 usage 时用）。 */
        fun estimateToolTokens(content: String): Int = LlmUsage.estimate(content)

        /** 附件引用归一（工具返回的媒体引用统一成 `/v1/media/<id>`）。 */
        fun canonicalMedia(reference: String?): String? = MediaUrl.canonical(reference)

        /** 搜索错误转成一句人话（`SearchException` 的 message 里没有 key）。 */
        fun describeSearchFailure(failure: SearchException): String =
            failure.error.message.ifBlank { "搜索失败（${failure.error.kind}）" }
    }
}
