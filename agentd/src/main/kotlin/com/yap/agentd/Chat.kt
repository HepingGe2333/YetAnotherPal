package com.yap.agentd

import com.yap.agentd.safety.CrisisClassifier
import com.yap.agentd.safety.SafetyGuard
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 一次回复生成请求。**提示词构造不在这里**：按契约，`agentd` 不读客户端传来的提示词，
 * 生产实现（下一阶段接 LLM）会在服务端按人格/记忆/心情自己组提示词；这里只把
 * 「会话 + 用户消息 + 最近历史」交给 [ReplyGenerator]。
 */
data class ReplyRequest(
    val sessionId: String,
    val personaId: String,
    val userMessage: Message,
    /** 最近若干条消息（升序，含刚落的用户消息，**已排除拉黑期间的消息**），供生成器参考。 */
    val history: List<Message>,
    /** 0.14：会话级「他的备忘录」（短期/长期摘要）；注入系统提示词的可变区域。 */
    val sessionMemory: com.yap.agentd.store.SessionMemory? = null,
    /** 0.14：最近一次心理活动（「你刚才心里想：…」）；过旧的不再注入。 */
    val mentalActivity: com.yap.agentd.store.MentalActivity? = null,
    /**
     * 0.15：这一轮工具回路最多几轮（模型/调用方指定）。
     *
     * `null` = 默认 5 轮；越界由 `ExperienceContract.clampToolRounds` 夹到 1..15
     * （客户端传 0/负数/100 都不会打乱服务端）。
     */
    val requestedToolRounds: Int? = null,
    /**
     * 0.15.2：这一轮的两段式危机判定结果（词表 + 一次模型确认，见 `safety/CrisisClassifier`）。
     *
     * 由 [ChatService] 在**生成之前**算好并传进来：生成器要在工具回路里决定「`stay_silent` /
     * `defer_reply` 能不能执行」，所以它必须比流式输出更早拿到结论。
     * `null` = 调用方没有判（直接调用生成器的测试/脚本）→ 生成器退回词表判定，行为与 0.15.1 一致。
     */
    val crisis: Boolean? = null
)

/**
 * 一次生成的产物（`ReplyGenerator` 的返回）。
 *
 * 为什么不再只返回 [Usage]：工具回路带来了三件「生成之外」的事，必须交给 [ChatService] 处理：
 * - [suppressed]（`stay_silent`）：**不落 assistant 消息**，只标已读；
 * - [attachments]（`sticker.send`）：assistant 消息要带上的媒体引用；
 * - [toolTraceJson]：这一轮做过什么（客户端同形，落进消息表）。
 *
 * 其余字段（[toolsUsed] / [elapsedMs] / [textLength] / [legacy]）只用于日志与测试断言，不进协议。
 */
data class ReplyOutcome(
    val usage: Usage,
    val suppressed: Boolean = false,
    val attachments: List<String> = emptyList(),
    val toolTraceJson: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
    val textLength: Int = 0,
    /**
     * 这一轮真正要逐条发出的消息（`send_message` 工具收集来的，按顺序）。
     *
     * 0.16 起这是**唯一**的正常出口：空列表 + 有正文 = legacy 兼容回退（见 [legacy]），
     * 由 `ChatService` 把已经流出的文字按空行拆成 1–3 条落库。
     */
    val messages: List<String> = emptyList(),
    /**
     * 与 [messages] 一一对应的 messageId（0.16）。
     *
     * `send_message` 执行时就把 id 分配好并回给了模型（「已发送，messageId=…」），
     * `ChatService` 落库时必须用同一个 id，否则工具结果里的 id 是编的。空列表 = 没有工具消息。
     */
    val messageIds: List<String> = emptyList(),
    /**
     * 0.16：这一轮的最终正文是**兼容回退**——模型一次 `send_message` 都没调用（旧模型/不支持
     * tools 的网关/关掉工具的降级路径）。`ChatService` 会把它当作一条 legacy assistant 消息落库
     * 并打一行日志计入提示词迁移指标；这不是正常路径，新提示词下不应出现。
     */
    val legacy: Boolean = false,
    /**
     * 0.15 UX：legacy 回退真正要落库的正文（可选）。
     *
     * 有协议提醒时，生成器会先让模型重试一次；如果重试仍然没调 `send_message`/`stay_silent`，
     * 就回退到**重试时最新写的那段正文**（与 Android/iOS 一致）。[Chat.kt] 用它而不是
     * `delta` 累加器，避免把提醒前后两段正文拼成一条消息。
     * `null` = 老生成器/没有提醒：调用方仍然按 `delta` 拼正文（行为不变）。
     */
    val legacyText: String? = null,
    /**
     * 0.15.1：这一轮模型**想**沉默（调用了 `stay_silent`；危机轮里 `defer_reply` 也算）。
     *
     * 危机轮里 `LlmReplyGenerator` 会把这两个工具拦下来不执行，所以 [suppressed] 可能仍然是
     * `false`；但聊天层需要知道「这本来是一轮沉默」，才能补 `message.created`、保证 `done`
     * 带真实消息 id（而不是回到「只已读」的旧形状）。
     */
    val silenceRequested: Boolean = false
)

/**
 * 可注入的回复生成器。
 *
 * - 接口保持最小：`generate(request) { event -> … }` 同步推送 [SseEvent]，返回 [ReplyOutcome]；
 * - 生产实现是 `llm/LlmReplyGenerator`（组提示词 → 流式 delta → 工具回路 → done）；
 * - 测试用假实现验证事件顺序、工具事件、客户端断开后继续生成。
 */
fun interface ReplyGenerator {
    fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): ReplyOutcome
}

/**
 * 默认生成器：把用户消息回显一句 + 一句固定中文。
 *
 * 用途只有两个：`curl` 冒烟、测试流式链路。**不是**真正的回复质量，也**不调 LLM**。
 * 用量按「字符数」粗略估算（不是分词器）：中文 1 字 ≈ 1 token，注释里写清楚了。
 */
class EchoReplyGenerator(
    private val chunkSize: Int = 16,
    private val fixedLine: String = "收到啦，我在。"
) : ReplyGenerator {

    override fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): ReplyOutcome {
        val echoed = request.userMessage.content.trim().let {
            if (it.length <= MAX_ECHO_CHARS) it else it.take(MAX_ECHO_CHARS) + "…"
        }
        val reply = buildString {
            append("你说：「").append(echoed).append("」\n")
            append(fixedLine)
            append("（agentd 回显回复：还没接 LLM，这一条用来验证流式与落库）")
        }
        reply.chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
            emit(SseEvent.Delta(chunk))
        }
        var promptChars = 0
        for (message in request.history) promptChars += message.content.length
        return ReplyOutcome(
            usage = Usage(promptTokens = promptChars, completionTokens = reply.length),
            textLength = reply.length
        )
    }

    companion object {
        /** 回显用户消息的上限：不让一条超长消息变成一条超长回复。 */
        const val MAX_ECHO_CHARS = 200
    }
}

/** `POST /v1/sessions/{id}/messages` 解析后的请求体（校验在 `AuthorityRoutes`）。 */
data class SendMessageRequest(
    val clientMessageId: String,
    val content: String,
    val attachments: List<String> = emptyList(),
    val replyTo: String? = null,
    /**
     * 0.15：这一轮最多用几轮工具（`null` = 默认 5，夹到 1..15）。
     *
     * 为什么让调用方/模型能指定：复杂任务（查资料 → 读正文 → 核对 → 再说）5 轮够用，
     * 但「帮我整理三天行程」这类可能要多查几次；默认值不能跟着上限一起放大。
     */
    val toolRounds: Int? = null
) {
    companion object {
        /** `clientMessageId` 的长度上限；它同时是幂等键。 */
        const val MAX_CLIENT_MESSAGE_ID_CHARS = 128

        /** 单条消息正文上限（1 MiB 的请求体上限之外，消息本身也有上限）。 */
        const val MAX_CONTENT_CHARS = 65_536

        /** 一条消息最多带几个附件（每项是媒体 id 或 `/v1/media/<id>`，落库统一成后者）。 */
        const val MAX_ATTACHMENTS = 8
    }
}

/**
 * 聊天发送流程：幂等 → 先落用户消息 → 生成 → 落 assistant 消息 → 累加用量。
 *
 * ### 幂等
 * `clientMessageId` 是唯一键（同一会话内）：
 * - 第一次：落用户消息 → `accepted` → 生成 → 落 assistant 消息（`replyTo` 指回用户消息）→ `done`；
 * - 重复（顺序请求）：找到用户消息与已生成的回复 → 直接 `accepted` + `done`，**不再调生成器**；
 * - 并发重复：同一个 key 用一把 `ReentrantLock` 串行化，后到的那个等前一个生成完再重放（也只生成一次）；
 * - 用户消息已落但回复缺失（进程在两步之间被杀）：补生成一次（这时没有别的办法知道上次生成到哪）。
 *
 * ### 客户端断开后继续生成
 * [SafeSink] 会把 `emit` 的任何异常吞掉并记一次日志，生成循环和落库**不会**因为写 socket 失败而中断。
 * 这就是契约里「客户端断开 SSE 时服务端继续生成并落库」的实现方式；HTTP 层的 `HttpSseSink`
 * 也只是「写失败就标记 broken」，不往外抛。
 *
 * ### 危机优先于沉默（0.15.1）
 * `stay_silent` 的「只已读不回」对危机轮**不成立**：用户这句话命中 `SafetyGuard` 的危机词表时，
 * 本轮必须落一条可见的 assistant 消息，SSE 用 `message.created` + 带真实 id 的 `done` 收尾。
 * 有正文用正文；没有正文就把安全提示作为唯一正文。`llm/LlmReplyGenerator` 还会在工具回路里
 * 直接拦掉 `stay_silent` / `defer_reply`（不执行、不发卡片、不写 defer 念头），这里再兜一次底，
 * 所以换掉生成器（测试/降级）也不会把这条安全底线漏掉。
 *
 * ### 完全工具调用化（0.16）
 * 正常路径只有一条：模型用 `send_message` 发声，这里按顺序落 N 条 assistant 消息（每条一个
 * `message.created`），最后用一个 `done` 收尾（`assistantMessageId` = 最后一条）。SSE 形状是
 * `accepted → message.created* → done`。三个兼容分支：
 * - **legacy**：整轮一次 `send_message` 都没有、但有正文（旧模型/旧提示词/关掉工具）→
 *   正文按空行拆成 1–3 条落库，仍发 `message.created`，并打一行「legacy 回退」日志；
 * - **只剩沉默**：`stay_silent` → 不落消息、`done.silent = true`（只已读）；
 * - **协议违规**：既没有 `send_message` 也没有 `stay_silent`（`defer_reply` 除外，它的语义本来就是
 *   这一轮不回）、连正文都没有 → 按沉默收尾并打日志，绝不落一条空消息污染历史。
 *
 * ### 危机两段式判定（0.15.2）
 * 「命中词表就算危机」太宽（「想死你了」「累得要死」），所以判定拆成两段：词表（`SafetyGuard`）
 * 先筛，**命中之后**才问一次模型（`safety/CrisisClassifier`，低温度、5 秒超时）。
 * 模型明确说「不是真实危机」→ 这一轮就是普通聊天，不做任何危机兜底（不补安全提示、不拦沉默工具）；
 * 模型说是、或者判定本身失败/超时/坏 JSON/没配置 → 按危机处理（安全优先）。
 *
 * 判定在**生成之前**做一次，结果随 [ReplyRequest.crisis] 交给生成器，原因有两条：
 * 生成器要在工具回路里提前决定「沉默工具能不能执行」；同一条用户消息也只能问一次模型。
 */
class ChatService(
    private val store: FileStore,
    private val generator: ReplyGenerator,
    private val historyLimit: Int = FileStore.BLOCK_SIZE,
    /**
     * 每条聊天结束后的自动动作（0.13：每 4 条用户消息判心情、每 20 条评估进化）。
     *
     * 契约要求「不依赖客户端连接」：这里在**反手回完 done 之后**同步跑一次（生产把它丢进
     * `AgentdServices` 的单线程执行器，见 `AutoTriggers`），失败只记日志，绝不影响聊天本身。
     */
    private val autoTriggers: com.yap.agentd.automation.AutoTriggers? = null,
    /**
     * 0.14 的体验功能：每条聊天后记一轮「他说了几轮」、按 5/30 条更新会话记忆、
     * 在心情激动/变化大时补一次心理活动。失败同样只记日志。
     */
    private val experience: com.yap.agentd.experience.ExperienceService? = null,
    /**
     * 0.14.1：服务端消息队列与 typing 指示器（`spec/experience` §8）。
     * `null` = 不启用延迟（落库即发 `message.created`，0.14 的旧行为；测试默认走这条）。
     */
    private val delivery: com.yap.agentd.delivery.MessageDelivery? = null,
    /**
     * 0.15（心情直觉化）：每条用户消息之后判定一次心情。
     *
     * 时机是「落完用户消息、组提示词之前」：这一轮回复的语气必须反映**刚看到这句话之后**的心情。
     * 失败只记日志（`MoodService` 自己会按时间衰减），绝不影响回复。
     */
    private val moodJudge: com.yap.agentd.mood.MoodService? = null,
    /**
     * 0.15.2（危机两段式）：词表命中后再问一次模型「这是不是真的危险」。
     *
     * `null` = 没有接模型（测试/降级）：退回 0.15.1 的纯词表判定，行为完全不变。
     * 判定在生成之前做一次（[CrisisClassifier.newRequest] 的请求级缓存），
     * 结果随 [ReplyRequest.crisis] 传给生成器，所以同一轮只会请求一次模型。
     */
    private val crisisClassifier: CrisisClassifier? = null
) {

    /** 同一 `(sessionId, clientMessageId)` 的并发生成只允许一个；生成完把锁从表里摘掉。 */
    private val inFlight = ConcurrentHashMap<String, ReentrantLock>()

    fun send(sessionId: String, request: SendMessageRequest, sink: SseSink) {
        val safe = SafeSink(sink)
        val key = "$sessionId|${request.clientMessageId}"
        val lock = inFlight.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            val existing = store.findUserMessageByClientMessageId(sessionId, request.clientMessageId)
            if (existing != null) {
                val replies = store.findAssistantReplies(sessionId, existing.id)
                safe.emit(SseEvent.Accepted(existing.id, existing.createdAt))
                if (replies.isNotEmpty()) {
                    // 幂等重放：不再调生成器，也不产生第二条用户消息。
                    // 0.14 连发的一轮有几条回复就逐条重放 message.created，最后用一个 done 收尾
                    // （assistantMessageId = 最后一条，usage 取自它）。
                    if (replies.size > 1) replies.forEach { safe.emit(SseEvent.MessageCreated(it)) }
                    val last = replies.last()
                    safe.emit(
                        SseEvent.Done(
                            last.id,
                            Usage(last.promptTokens ?: 0, last.completionTokens ?: 0)
                        )
                    )
                    return
                }
                // 用户消息在、回复不在：进程在两步之间被杀过，补生成一次。
                generateAndPersist(sessionId, existing, safe, request.toolRounds)
                return
            }
            val session = store.session(sessionId) ?: return
            // 0.14 拉黑：被拉黑期间用户仍可发；消息落库并永久标记 blocked，**不生成回复**。
            val blockedNow = store.persona(session.personaId)?.isBlockedAt(store.serverTime()) == true
            val userMessage = store.appendMessage(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = request.content,
                attachments = request.attachments,
                source = MessageSource.CHAT,
                clientMessageId = request.clientMessageId,
                blocked = blockedNow
            )
            safe.emit(SseEvent.Accepted(userMessage.id, userMessage.createdAt))
            if (blockedNow) {
                // 「对方已将你拉黑」：只把会话标已读，不产生 assistant 消息，也不跑任何自动判定。
                store.markSessionRead(sessionId)
                safe.emit(SseEvent.Done("", Usage.ZERO, silent = true))
                return
            }
            generateAndPersist(sessionId, userMessage, safe, request.toolRounds)
        } finally {
            // 先在还持锁时判断有没有排队者，再把锁从表里摘掉：并发重复请求会等在同一个锁对象上，
            // 等到的那个看到回复已存在就走重放路径，不会第二次调生成器。
            if (!lock.hasQueuedThreads()) inFlight.remove(key, lock)
            lock.unlock()
        }
    }

    private fun generateAndPersist(
        sessionId: String,
        userMessage: Message,
        sink: SafeSink,
        toolRounds: Int? = null
    ) {
        val session = store.session(sessionId) ?: return
        val now = store.serverTime()
        // 0.15：每条用户消息之后重新判一次心情（在任何提示词组装之前），
        // 失败按时间衰减、只记日志；同时推进自动判定游标，避免 AutoTriggers 再判一次。
        moodJudge?.let { judge ->
            runCatching { judge.evaluate(session.personaId, now) }
                .onFailure { failure ->
                    System.err.println(
                        "[agentd] 每条消息的心情判定失败：${failure::class.simpleName}: ${failure.message}"
                    )
                }
            runCatching { store.recordMoodAutoEval(session.personaId, now) }
        }
        // 0.15.2 危机两段式判定：词表先筛，命中后才问一次模型（同一请求内只问一次）。
        // 必须放在生成**之前**：生成器要靠这个结论决定 `stay_silent` / `defer_reply` 能不能执行。
        // 没有注入判定器（测试/降级）= 退回 0.15.1 的纯词表，行为一字不变。
        val crisisVerdict = crisisClassifier?.newRequest()?.verdict(userMessage.content)
        crisisVerdict?.takeIf { it.modelChecked }?.let { verdict ->
            val outcome = when {
                verdict.source == CrisisClassifier.Source.FALLBACK ->
                    "判定失败，按安全优先进入危机兜底"

                verdict.risk -> "模型判定为真实危机"
                else -> "模型判定为不是真实危机，不进入危机兜底"
            }
            val why = verdict.reason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            System.err.println("[agentd] 危机两段式判定（词表命中 → $outcome）$why")
        }
        val crisis = crisisVerdict?.risk ?: SafetyGuard.isCrisis(userMessage.content)
        // 模型上下文**永久排除**拉黑期间的消息（解除后也不可见）：includeBlocked = false。
        val history = store.latestMessages(sessionId, historyLimit, includeBlocked = false)
        val request = ReplyRequest(
            sessionId = sessionId,
            personaId = session.personaId,
            userMessage = userMessage,
            history = history,
            sessionMemory = store.sessionMemory(sessionId),
            mentalActivity = store.latestMentalActivity(session.personaId)
                ?.takeIf { now - it.at <= MENTAL_ACTIVITY_CONTEXT_MS },
            requestedToolRounds = toolRounds,
            // 同一轮只判一次：生成器复用这个结论，不会自己再问一次模型。
            crisis = crisis
        )
        val builder = StringBuilder()
        var outcome = ReplyOutcome(Usage.ZERO)
        var failed = false
        try {
            outcome = generator.generate(request) { event ->
                if (event is SseEvent.Delta) builder.append(event.text)
                sink.emit(event)
            }
        } catch (failure: com.yap.agentd.llm.LlmException) {
            // 0.13：LLM 失败/无配置是**可预期的错误**，必须给出明确 code（而不是笼统的
            // generation_failed），也绝不退回 Echo 假装成功。已经生成的部分仍然落库。
            failed = true
            System.err.println(
                "[agentd] 回复生成失败（${failure.code}，已生成的部分仍会落库）：${failure.userMessage()}" +
                    (failure.detail?.let { "｜detail=$it" } ?: "")
            )
            sink.emit(SseEvent.Error(failure.code, failure.userMessage()))
        } catch (failure: Exception) {
            failed = true
            System.err.println(
                "[agentd] 回复生成失败（已生成的部分仍会落库）：" +
                    "${failure::class.qualifiedName}: ${failure.message}"
            )
            sink.emit(SseEvent.Error("generation_failed", "生成回复失败，已保存已经生成的部分"))
        }
        val usage = outcome.usage
        // 0.15.1 危机优先于沉默：`crisis`（两段式判定，见上面 0.15.2 的注释）为真时，
        // 本轮**必须**留下可见的 assistant 消息。放在这里而不是只靠生成器拦截：
        // 生成器可替换（测试/降级），而这是安全底线。
        var replyTexts = when {
            outcome.suppressed -> emptyList()
            outcome.messages.isNotEmpty() -> outcome.messages
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // 连发是「每轮最多 4 条」，比纯文字按空行拆的 1–3 条宽一条。
                .take(com.yap.agentd.tools.ToolExecutor.MAX_MESSAGES_PER_TURN)
            else -> splitIntoMessages(outcome.legacyText ?: builder.toString())
        }
        // 危机轮里「不落 assistant 正文」的语义被覆盖：已经流出的正文照用，没有正文就用安全提示。
        // 这条 override 同时决定 SSE 形状——必须有 `message.created`，且 done 带真实消息 id。
        // 注意 [ReplyOutcome.silenceRequested]：生成器在危机轮会拦掉沉默工具，此时 `suppressed`
        // 是 false，但这一轮仍然是「本来想沉默」，SSE 形状必须和危机 override 一致。
        var crisisOverride = false
        if (crisis && (outcome.suppressed || outcome.silenceRequested)) {
            if (outcome.suppressed) {
                replyTexts = crisisSilenceTexts(builder.toString(), userMessage.content)
            }
            crisisOverride = true
        }
        // 0.15：`remember` / `diary` / `defer_reply` 说明「接下来的安排变了」→ 短期缓存立刻更新。
        val planChanged = outcome.toolsUsed.any { it in PLAN_CHANGING_TOOLS }
        if (outcome.suppressed && !crisis) {
            // `stay_silent`（spec/mood §4）：这一轮**没有 assistant 消息**，只把会话标已读；
            // 客户端 UI 不显示任何占位气泡，只看到「已读」。用量照记（模型确实被调用了）。
            if (usage.promptTokens > 0 || usage.completionTokens > 0) {
                store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
            }
            store.markSessionRead(sessionId)
            sink.emit(SseEvent.Done("", usage, silent = true))
            afterChat(session.personaId, sessionId, planChanged)
            return
        }
        if (failed && replyTexts.isEmpty()) {
            // 一个字都没生成出来：不落一条空 assistant 消息，避免污染历史。
            afterChat(session.personaId, sessionId, planChanged)
            return
        }
        if (replyTexts.isEmpty() && outcome.attachments.isEmpty()) {
            if (crisis) {
                // 危机轮：工具回路把轮数都用完、模型一个字都没说，也必须让用户看到一句安全提示。
                // 这条提示是**唯一**的 assistant 消息（不是追加），所以直接写进 replyTexts 走落库流程。
                replyTexts = listOf(SafetyGuard.crisisNote(userMessage.content))
                crisisOverride = true
            } else {
                // 工具回路把轮数都用完了、模型一个字都没说（只调工具、也没沉默）：同样不落空消息，
                // 用一条 `silent` 的 done 收尾（客户端只会看到「已读」，和 stay_silent 同一形状）。
                // 0.16 起这是**协议违规**（既没 send_message 也没 stay_silent）：记一行日志，
                // 方便从「他为什么不说话」查到提示词/模型是哪一侧退化了。
                // `defer_reply` 的语义本来就是「这一轮不回、过一会儿再说」，不算违规。
                val deferred = outcome.toolsUsed.any { it == com.yap.agentd.tools.ServerToolCatalog.DEFER_REPLY }
                if (!outcome.silenceRequested && !deferred) {
                    System.err.println(
                        "[agentd] 协议违规：这一轮既没有 send_message 也没有 stay_silent，" +
                            "模型一个字都没说；按沉默收尾（不落空消息）"
                    )
                }
                store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
                store.markSessionRead(sessionId)
                sink.emit(SseEvent.Done("", usage, silent = true))
                afterChat(session.personaId, sessionId, planChanged)
                return
            }
        }
        if (replyTexts.isEmpty() && crisis && outcome.attachments.isNotEmpty()) {
            // 只有贴纸/附件、没有正文：危机轮不能让「一张表情」成为唯一的回答。
            // 把安全提示作为正文挂在这条消息上（附件仍然带在最后一条，不另造气泡）。
            replyTexts = listOf(SafetyGuard.crisisNote(userMessage.content))
            crisisOverride = true
        }
        // 0.15 危机安全兜底：两段式判定为危机、而回复里没有安全锚点
        // （我在/别一个人/身边有人/120/110/医院/急诊/求助/家人/朋友）时，
        // 在**最后一条**回复末尾追加 1–2 句，并补一个 delta 让在线客户端也立刻看到。
        // 纯函数、无 IO（`SafetyGuard`），这里只负责把结果接进落库流程。
        // 连发已满 4 条时提示并进最后一条（规范 §4 的硬上限），不会多造第 5 个气泡。
        // 模型明确判定「不是真实危机」（risk=false）时整段跳过：那一轮的回复一个字都不改。
        if (crisis && replyTexts.isNotEmpty()) {
            val guarded = SafetyGuard.guard(userMessage.content, replyTexts)
            guarded.appended?.let { appended ->
                replyTexts = guarded.messages
                sink.emit(SseEvent.Delta(appended))
            }
        }
        // 0.16：生成器一次 `send_message` 都没调用、却有正文 → legacy 兼容回退（按空行拆）。
        // 日志是「提示词迁移指标」：新提示词/新模型下这一行不该出现。
        val legacyFallback = !failed && !outcome.suppressed && outcome.messages.isEmpty() && replyTexts.isNotEmpty()
        if (legacyFallback) {
            System.err.println(
                "[agentd] legacy 回退：这一轮没有调用 send_message，最终正文按 ${replyTexts.size} 条 " +
                    "assistant 消息落库（兼容旧模型/旧提示词；新提示词不应出现）"
            )
        }
        // 有附件但没有文字（sticker.send）：仍然落一条带附件的空正文消息（与 0.13 一致）。
        val textsToPersist = if (replyTexts.isEmpty()) listOf("") else replyTexts
        // 协议违规（既没有 send_message 也没有正文，且不是沉默工具）：上面已经按 silent 收尾；
        // 只有「带了附件、一个字没说」这种情况会走到落库，保持与 0.13 一致。
        // 危机 override 的那一条必须发 `message.created`（客户端按它上屏；done 只是收尾）。
        val multiMessage = outcome.messages.isNotEmpty() || textsToPersist.size > 1 || crisisOverride
        // 0.14.1（§8）：延迟由服务端在**入队时**决定。离线/未启用/失败时是 `null` = 立即上屏。
        // 失败时不延迟：已经流出去的半句话要立刻落库并发事件，不能排进队列等 4 秒。
        val visibleAts = if (failed) null else delivery?.visibleAts(session.personaId, textsToPersist)
        val delayed = visibleAts != null
        // 关键点：这里不检查 socket 是否还活着 —— 客户端断开也要落库。
        val persisted = ArrayList<Message>(textsToPersist.size)
        for ((index, text) in textsToPersist.withIndex()) {
            val isLast = index == textsToPersist.lastIndex
            persisted += store.appendMessage(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = text,
                // `send_message` 把 id 提前回给了模型；落库用同一个 id（legacy 回退时为空 → 存储自己发）。
                id = outcome.messageIds.getOrNull(index)?.takeIf { it.isNotBlank() },
                attachments = if (isLast) outcome.attachments else emptyList(),
                source = MessageSource.CHAT,
                replyTo = userMessage.id,
                // 用量记在最后一条：`done` 与幂等重放都读它。
                promptTokens = if (isLast) usage.promptTokens else null,
                completionTokens = if (isLast) usage.completionTokens else null,
                // `stay_silent` / `defer_reply` 被危机 override 时不能留下工具卡片：
                // `suppressed` 的那一轮整轮工具痕迹都不落（生成器拦截时本身也不会有这两条 trace）。
                toolTraceJson = if (isLast && !outcome.suppressed) outcome.toolTraceJson else null,
                visibleAt = visibleAts?.getOrNull(index),
                // 延迟投递的消息先不写 `message.created` 事件（到点由 MessageDelivery 补发）；
                // 立即路径保持现状（落库即发事件）。
                emitEvent = !delayed
            )
        }
        store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
        // 真的说了一句 → 连续沉默清零（`stay_silent` 的「不超过 2 轮」由它兜底）。
        store.recordAssistantReply(session.personaId)
        // 0.15 remember 可靠性兜底：回复已经落库之后，如果用户这句话带着「过敏/忌口/生日/
        // 纪念日/重要约定」而模型**没调 `remember`**，自动补一条 USER 记忆（同一条不重复写）。
        // 放在落库之后是有意的：聊天成功是主流程，兜底记忆是附加动作，失败也只记日志。
        val autoRemembered = autoRemember(session.personaId, userMessage, outcome.toolsUsed)
        if (!failed) {
            if (delayed) {
                // 前台在线：typing.started → 到 visibleAt 的 message.created → typing.stopped（每轮各一次）。
                // 写 socket 失败由 SafeSink 吞掉；投递队列本身已经持久化，进程被 kill 也能补发。
                try {
                    delivery!!.present(sessionId, session.personaId, persisted, sink)
                } catch (failure: Exception) {
                    System.err.println(
                        "[agentd] 延迟投递失败，改为立即下发：${failure::class.simpleName}: ${failure.message}"
                    )
                    persisted.forEach { sink.emit(SseEvent.MessageCreated(it)) }
                }
            } else if (multiMessage) {
                // 0.14 连发：逐条 `message.created`（带完整消息 DTO），最后只发一个 `done`。
                // 单条回复保持旧形状（只有 delta + done），老客户端不用改也能正常显示。
                persisted.forEach { sink.emit(SseEvent.MessageCreated(it)) }
            }
            sink.emit(SseEvent.Done(persisted.last().id, usage))
        }
        afterChat(session.personaId, sessionId, planChanged || autoRemembered)
    }

    /**
     * 「模型说记住了、其实没调 remember」的兜底（0.15）。
     *
     * 只有同时满足三件事才写：用户原句命中值得长期记住的模式、这一轮没调用过
     * `remember`、记忆库里还没有同一条。`importance` 取 4–5，标签带 `USER`。
     * 返回是否真的写了一条（供 `afterChat` 决定要不要立刻刷新短期记忆缓存）。
     *
     * **绝不抛**：写记忆失败只打日志，聊天已经成功了。
     */
    private fun autoRemember(
        personaId: String,
        userMessage: Message,
        toolsUsed: List<String>
    ): Boolean {
        if (toolsUsed.any { it == com.yap.agentd.tools.ServerToolCatalog.REMEMBER }) return false
        val draft = com.yap.agentd.memory.RememberFallback.detect(userMessage.content) ?: return false
        return runCatching {
            com.yap.agentd.memory.RememberFallback.saveIfMissing(store, personaId, draft)
        }.onFailure { failure ->
            System.err.println(
                "[agentd] 兜底记忆写入失败：${failure::class.simpleName}: ${failure.message}"
            )
        }.getOrNull() != null
    }

    /**
     * 危机轮里被 `stay_silent` 压掉正文时的兜底文本（0.15.1）。
     *
     * 规则只有两条，刻意不复杂：
     * - 模型已经流出了正文（哪怕只是半句）→ 用它，「有正文用正文」；缺安全锚点时后面的
     *   [SafetyGuard.guard] 会照常补上；
     * - 一个字都没有 → 安全提示就是**唯一**的 assistant 消息（不落空消息，也不追加在不存在的话后面）。
     */
    private fun crisisSilenceTexts(streamed: String, userMessage: String): List<String> {
        val body = streamed.trim()
        return if (body.isEmpty()) listOf(SafetyGuard.crisisNote(userMessage)) else listOf(body)
    }

    /**
     * 不走工具的回复按**空行**拆成 1–3 条短消息（`spec/experience/README.md` §4）。
     *
     * 只有一个段落就原样一条；超过 3 段时把多出来的并进最后一条——宁可最后一条长一点，
     * 也不要丢掉内容。
     */
    internal fun splitIntoMessages(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(EMPTY_LINE).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(trimmed)
        if (parts.size <= MAX_REPLY_MESSAGES) return parts
        return parts.take(MAX_REPLY_MESSAGES - 1) +
            parts.drop(MAX_REPLY_MESSAGES - 1).joinToString("\n\n")
    }

    /**
     * 聊天结束后的自动动作（0.13 / 0.14）。**在任何情况下都不抛**：
     * 情绪判定失败要按时间衰减、进化评估失败只丢一条日志，聊天本身已经成功回完了。
     */
    private fun afterChat(personaId: String, sessionId: String, planChanged: Boolean = false) {
        val triggers = autoTriggers
        if (triggers != null) {
            runCatching { triggers.onChatCompleted(personaId) }
                .onFailure { failure ->
                    System.err.println("[agentd] 自动判定触发失败：${failure::class.simpleName}: ${failure.message}")
                }
        }
        val experienceService = experience ?: return
        runCatching { experienceService.onChatCompleted(personaId, sessionId, planChanged) }
            .onFailure { failure ->
                System.err.println("[agentd] 体验功能触发失败：${failure::class.simpleName}: ${failure.message}")
            }
    }

    /** 写失败只记一次日志，绝不打断生成。 */
    private class SafeSink(private val sink: SseSink) : SseSink {
        private var failures = 0

        override fun emit(event: SseEvent) {
            try {
                sink.emit(event)
            } catch (failure: Exception) {
                failures += 1
                if (failures == 1) {
                    System.err.println(
                        "[agentd] SSE 写出失败，客户端可能已断开；继续生成并落库：" +
                            "${failure::class.qualifiedName}: ${failure.message}"
                    )
                }
            }
        }
    }

    companion object {
        /** 一轮最多拆成几条（规范 §4：1–3 条）。 */
        const val MAX_REPLY_MESSAGES = 3

        /** 心理活动注入下一轮的时效：太久以前的念头不再提（半小时）。 */
        const val MENTAL_ACTIVITY_CONTEXT_MS: Long = 30 * 60 * 1000L

        /** 会让「接下来的计划」变化的工具：短期缓存看到它们就提前更新。 */
        val PLAN_CHANGING_TOOLS: Set<String> = setOf(
            com.yap.agentd.tools.ServerToolCatalog.REMEMBER,
            com.yap.agentd.tools.ServerToolCatalog.DIARY,
            com.yap.agentd.tools.ServerToolCatalog.DEFER_REPLY
        )

        /** 空行（一个或多个，允许行内空白）。 */
        private val EMPTY_LINE = Regex("\\n[ \\t]*\\n")
    }
}
