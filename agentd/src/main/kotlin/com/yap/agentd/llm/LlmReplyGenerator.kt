package com.yap.agentd.llm

import com.yap.agentd.ReplyGenerator
import com.yap.agentd.ReplyOutcome
import com.yap.agentd.ReplyRequest
import com.yap.agentd.SseEvent
import com.yap.agentd.Usage
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.safety.SafetyGuard
import com.yap.agentd.store.FileStore
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.agentd.tools.ToolExecutor
import com.yap.app.domain.ExperienceContract
import java.time.Clock

/**
 * 真正的回复生成器：**服务端组提示词 → 调 LLM（带工具回路）→ 流式 emit delta → 返回用量**。
 *
 * 取代 0.12 的 [com.yap.agentd.EchoReplyGenerator]（回显）作为 `Main` 的默认实现。
 * 与 `ChatService` 的分工不变：这里只负责「生成」，落库/幂等/断开继续生成都在 `ChatService`。
 *
 * ### 工具回路（0.13 新增）
 * ```
 * 第 1 轮：messages + tools[] → 模型可能同时给文字和 tool_calls
 *          → 每个 tool_call 真的执行（[ToolExecutor]），emit `tool` 事件
 *          → assistant(tool_calls) + tool(结果) 追加进 messages
 * 第 2 轮：再问一次 …… 最多 5 轮（模型/调用方可指定，硬上限 15）
 * ```
 * 0.16「完全工具调用化」（`spec/tool-call-agents/README.md` §1）：**正文不再是出口**。
 * 模型每一句给用户看的话都必须调用 `send_message`（工具结果回 `已发送，messageId=…`），
 * 这里按顺序收集成多条消息交给 `ChatService`；最后一段直接写出来的正文只当「正在输入」，
 * 只有整轮一次 `send_message` 都没调时才作为 **legacy 兼容回退**（`ReplyOutcome.legacy`）。
 *
 * 0.16 思考强度（§2）：主聊天每条请求都带 `reasoning_effort`（服务端配置或默认 `low`）；
 * 同一轮里模型调用 `think` 之后，后续请求换成 `high`；`generate()` 结束即丢弃状态，
 * 下一轮回到默认值。辅助调用（情绪/进化/主动消息）不走这里，也不带这一格。
 *
 * 四条刻意的边界：
 * - **默认 5 轮、上限 15 轮**（0.15，`ExperienceContract.clampToolRounds`）：默认值覆盖
 *   「查一下 → 再确认一下 → 顺手记一笔 → 说话」；模型/调用方可以按任务复杂度要更多，
 *   但最后一轮不再给 tools，逼它用现有信息说话；
 * - **超时停止**：整个回路共享 [deadlineMs]（默认 2 分钟），到点就带着已生成的文字收尾；
 * - **`stay_silent` 立即结束**：这一轮不落 assistant 消息（由 `ChatService` 处理），
 *   也不继续问模型（「不想说话」再问一次只会得到一句敷衍）；
 * - **危机优先于沉默**（0.15.1）：用户这句话被判定为危机时，`stay_silent` /
 *   `defer_reply` **不执行**——不记连续沉默、不写 defer 念头、不发工具卡片；同一响应里模型
 *   写过的正文照用，然后**立即结束这一轮**（和客户端 `CrisisPriority` 一致：危机里多等一轮
 *   没有意义）。仍然没有正文时由 `ChatService` 用安全提示兜底。安全底线不能交给
 *   「模型这次有没有听话」。0.15.2 起「是不是危机」由 `ChatService` 用两段式判定
 *   （`SafetyGuard` 词表 + 一次模型确认，见 `safety/CrisisClassifier`）算好放在
 *   `ReplyRequest.crisis` 里传进来；生成器自己不再发判定请求。
 *
 * ### 与客户端断开的关系
 * [ChatService] 的 `SafeSink` 会吞掉写失败；这里照常执行工具、落库。工具副作用（心情、
 * 活动、记忆、日记）**不依赖客户端连接**——这是「服务端独立运行」的一部分。
 */
class LlmReplyGenerator(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** 是否把工具表交给模型（测试/降级时可以关掉，只做纯文字回复）。 */
    private val toolEnabled: Boolean = true,
    private val promptBuilder: ChatPromptBuilder = defaultPromptBuilder(clock.zone, toolEnabled),
    private val tools: ToolExecutor = ToolExecutor(store, clock = clock),
    /** 工具回路的硬超时（毫秒）；到点停止继续问模型，用已经拿到的文字收尾。 */
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    /**
     * 0.15：服务端/测试指定的工具轮数（`null` = 用 `ExperienceContract.DEFAULT_TOOL_ROUNDS`）。
     *
     * 优先级：`ReplyRequest.requestedToolRounds`（模型/调用方这一轮指定的）> 本参数 > 默认 5；
     * 最终一律过 `ExperienceContract.clampToolRounds` 夹到 1..15。
     */
    private val toolRounds: Int? = null
) : ReplyGenerator {

    override fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): ReplyOutcome {
        // 配置缺失是「可预期的失败」：抛 LlmException(NOT_CONFIGURED)，由 ChatService 回 SSE error。
        val config = llm.config()
        val persona = store.persona(request.personaId)
            ?: throw IllegalStateException("会话引用了不存在的人格：${request.personaId.take(64)}")
        // 0.15：工具轮数的唯一口径（默认 5 / 模型可指定 / 上限 15）。
        val maxRounds = ExperienceContract.clampToolRounds(request.requestedToolRounds ?: toolRounds)
        // 0.15.1：危机优先于沉默。0.15.2 起危机判定变成两段（`SafetyGuard` 词表 + 一次模型确认），
        // 由 `ChatService` 在生成之前算好并通过 `request.crisis` 传进来——生成器不能自己再判一次，
        // 否则同一轮会多打一次模型。直接调用生成器的测试/脚本没传（null）时退回词表，行为与 0.15.1 一致。
        val crisis = request.crisis ?: SafetyGuard.isCrisis(request.userMessage.content)
        val now = clock.millis()
        val systemMessages = promptBuilder.chatMessages(
            persona = persona,
            mood = store.mood(request.personaId),
            activity = ActivityState.decode(persona.activityJson),
            history = request.history,
            now = now,
            sessionMemory = request.sessionMemory,
            mentalActivity = request.mentalActivity
        )
        val messages = systemMessages.toMutableList()
        val startedAt = now
        val deadline = if (deadlineMs > 0) now + deadlineMs else Long.MAX_VALUE

        var usage = LlmUsage.ZERO
        val traces = ArrayList<ToolExecutor.ToolTrace>()
        val attachments = LinkedHashSet<String>()
        var suppressed = false
        // 这一轮模型「想沉默」：普通轮里是 `stay_silent` 真的执行了；危机轮里是被拦截的
        // `stay_silent` / `defer_reply`。聊天层据此把 SSE 形状切成「有正文、非 silent」。
        var silenceRequested = false
        // 危机轮里拦下过「不回」的工具：同一响应正文照用，然后立刻收尾（不再问模型）。
        var crisisForcedReply = false
        var textSoFar = StringBuilder()
        // 0.14 连发：`send_message` 每调一次收集一条；`OutgoingMessages` 负责「每轮最多 4 条」。
        val outgoing = ToolExecutor.OutgoingMessages()
        // 0.16：每条消息带着提前分配好的 messageId（工具结果已经把它回给了模型）。
        val sentMessages = ArrayList<ToolExecutor.SentMessage>()
        // 0.16 思考强度：默认取服务端配置（没配 = low）；`think` 之后同一轮的后续请求升到 high。
        val baseEffort = config.reasoningEffort ?: LlmDefaults.DEFAULT_REASONING_EFFORT
        var deepThinking = false

        var round = 0
        var sentMultimodal = false
        while (round < maxRounds) {
            round += 1
            if (clock.millis() > deadline) break
            // 最后一轮不再给 tools：让它只能用已经查到的信息说话，而不是无限查下去。
            val useTools = toolEnabled && round < maxRounds
            val effort = if (deepThinking) LlmDefaults.REASONING_EFFORT_HIGH else baseEffort
            val turn = try {
                llm.chat(
                    config = config,
                    messages = messages,
                    tools = if (useTools) toolsJson else null,
                    reasoningEffort = effort,
                    onDelta = { delta ->
                        // 逐段下发：客户端拿到的就是模型真实吐出的内容（不是攒完再切块）。
                        textSoFar.append(delta)
                        emit(SseEvent.Delta(delta))
                    }
                )
            } catch (failure: LlmException) {
                // 0.14.1（`spec/audio-video`）：不支持音频/图片的模型，上游通常回 400。
                // 这时要给一句明确的话（「这个模型听不了语音」），而不是笼统的「模型接口返回错误」。
                if (sentMultimodal && failure.kind == LlmErrorKind.UPSTREAM && isModalityRejection(failure.detail)) {
                    throw LlmException(
                        LlmErrorKind.UPSTREAM,
                        "这个模型听不了语音/看不了图片（provider 拒绝了多模态输入）",
                        detail = failure.detail,
                        cause = failure
                    )
                }
                throw failure
            }
            usage = usage.plus(turn.usage)
            if (turn.toolCalls.isEmpty()) break
            messages += LlmMessage.assistant(turn.text, toolCalls = turn.toolCalls)
            var silentThisRound = false
            // 一轮里的多模态工具结果（listen/watch）先收集，等所有 tool 消息回完再作为**一条**
            // user 消息送进去：OpenAI 要求 assistant(tool_calls) 后面紧跟对应的 tool 消息，
            // 中间插 user 消息会让严格实现拒绝整轮请求。
            val roundParts = ArrayList<LlmContentPart>()
            val roundTexts = ArrayList<String>()
            for (call in turn.toolCalls) {
                if (crisis && isSilencingTool(call.name)) {
                    // 0.15.1 危机优先于沉默：不执行 `stay_silent` / `defer_reply`。
                    // 三件事都不能发生：不记「连续沉默」、不写 defer 念头、不发工具卡片。
                    // 每个 tool_call 仍然补一条结果（协议完整、日志里能看出为什么没沉默），
                    // 但这一轮**不会**再问模型：同一响应里写过的正文照用，没有正文由
                    // `ChatService` 用安全提示兜底（不依赖模型这次是否配合）。
                    crisisForcedReply = true
                    silenceRequested = true
                    messages += LlmMessage.tool(CRISIS_SILENCE_REFUSAL, call.id)
                    continue
                }
                emit(SseEvent.Tool(call.name, "running", null))
                val outcome = tools.execute(
                    name = call.name,
                    argumentsJson = call.arguments,
                    context = ToolExecutor.Context(
                        personaId = request.personaId,
                        sessionId = request.sessionId,
                        userMessageId = request.userMessage.id,
                        outgoing = outgoing
                    )
                )
                traces += outcome.trace
                emit(SseEvent.Tool(call.name, "done", outcome.summary))
                messages += LlmMessage.tool(outcome.contentForModel, call.id)
                if (outcome.modelParts.isNotEmpty()) {
                    roundParts += outcome.modelParts
                    roundTexts += outcome.contentForModel
                }
                attachments += outcome.attachments
                // `send_message` 的正文 + 预先分配的 id（`ChatService` 用同一个 id 落库）。
                outcome.sentMessage?.let { text ->
                    sentMessages += ToolExecutor.SentMessage(
                        text = text,
                        messageId = outcome.sentMessageId ?: ""
                    )
                }
                if (outcome.deepThinking) deepThinking = true
                if (outcome.staySilent) {
                    silentThisRound = true
                    silenceRequested = true
                }
            }
            if (roundParts.isNotEmpty()) {
                messages += LlmMessage.userWithParts(roundTexts.joinToString("\n"), roundParts)
                sentMultimodal = true
            }
            if (crisisForcedReply) {
                // 危机轮到此为止：不落 `suppressed`（正文要留），也不向模型要下一轮。
                break
            }
            if (silentThisRound) {
                // 0.15 严格语义：**只要这一轮调用了 `stay_silent`，就不落任何 assistant 正文**。
                // 模型可能先说了半句再调工具（流式下这半句已经作为 delta 发出去了，收不回来），
                // 但落库必须是「什么都没说」：否则库里有正文、`done.silent` 又为 true，
                // 客户端同步/重放时会把这段文字重新捡起来，和「只标已读」自相矛盾。
                // 已流出的 delta 只当「正在输入」的进度，`ChatService` 会按 suppressed 整轮丢弃。
                suppressed = true
                break
            }
            if (clock.millis() > deadline) break
        }

        val toolTraceJson = tools.encodeTrace(traces)
        return ReplyOutcome(
            usage = Usage(promptTokens = usage.promptTokens, completionTokens = usage.completionTokens),
            suppressed = suppressed,
            // `stay_silent` 时不带附件、不留文字；其它情况把 sticker 之类的附件带上。
            attachments = if (suppressed) emptyList() else attachments.toList(),
            toolTraceJson = toolTraceJson,
            toolsUsed = traces.map { it.name },
            elapsedMs = clock.millis() - startedAt,
            textLength = textSoFar.length,
            // 用了 `send_message` 就以逐条消息为准（已流出的文字只当「正在输入」的进度）。
            messages = if (suppressed) emptyList() else sentMessages.map { it.text },
            // 与 messages 一一对应；落库时用这些 id，工具结果里的 messageId 才是真的。
            messageIds = if (suppressed) emptyList() else sentMessages.map { it.messageId },
            // 整轮一次 `send_message` 都没调、却有正文：这是 legacy 兼容回退（旧模型/旧提示词）。
            legacy = !suppressed && sentMessages.isEmpty() && textSoFar.isNotBlank(),
            silenceRequested = silenceRequested
        )
    }

    /** 想「这一轮不说话」的工具：危机轮里一律不执行（见类注释）。 */
    private fun isSilencingTool(name: String): Boolean {
        val tool = name.trim()
        return tool == ServerToolCatalog.STAY_SILENT || tool == ServerToolCatalog.DEFER_REPLY
    }

    /**
     * 上游的 400 是不是「不支持这种模态」。
     *
     * 各家措辞不同（`audio` / `image` / `input_audio` / `modality` / `unsupported content type`…），
     * 只在多模态请求之后才做这个判断；其它 400（参数错、额度）照原样上报。
     */
    private fun isModalityRejection(detail: String?): Boolean {
        val text = detail?.lowercase().orEmpty()
        if (text.isBlank()) return false
        return listOf("audio", "image", "modality", "multimodal", "unsupported content").any { it in text }
    }

    /** tools 数组只序列化一次（每次生成都一样）。 */
    private val toolsJson: String = ServerToolCatalog.toOpenAiToolsJson()

    companion object {
        /** 关掉工具时的「你能做什么」：**诚实地说没有工具**，而不是把 tools[] 藏起来让模型瞎猜。 */
        val NO_TOOLS_CAPABILITIES: String = """
            # 你能做什么
            - 你现在没有可用的工具：不能联网搜索、不能打开链接、不能记事。
            - 对方可能发图片或文件，但你暂时看不到内容，不要假装看见了。
            - 不要假装查过，也不要编造实时信息（新闻、价格、天气）；不知道就说「不清楚」。
        """.trimIndent()

        /** 默认提示词构造器：工具开着就用注册表生成能力段，关掉就用诚实版。 */
        fun defaultPromptBuilder(zone: java.time.ZoneId, toolEnabled: Boolean): ChatPromptBuilder =
            if (toolEnabled) {
                ChatPromptBuilder(zone)
            } else {
                ChatPromptBuilder(zone, capabilities = NO_TOOLS_CAPABILITIES)
            }

        /**
         * 危机轮里被拦下的「不回」工具的回执（0.15.1）。
         *
         * 这一轮不会再问模型（危机里多等一轮没有意义，和客户端 `CrisisPriority` 一致），
         * 所以模型其实看不到这句话；保留它是为了让工具结果协议完整：每个 `tool_call` 都有
         * 同一 id 的回执，日志/追踪里也能看出「这一轮为什么没沉默、由什么兜底」。
         */
        const val CRISIS_SILENCE_REFUSAL: String =
            "现在不能沉默，也不能等一会儿再说：对方刚才的话里有自伤/轻生的风险。" +
                "这一轮必须直接用一句短话回应他，并把话往安全锚点上带" +
                "（我在 / 别一个人 / 身边有人 / 120 / 医院 / 家人朋友）。"

        /**
         * 工具回路的默认轮数（0.15 起 5 轮，唯一口径在 `:agentcore`）。
         *
         * 保留这个常量只是为了老调用方/测试，值不再自己写死一份。
         */
        const val MAX_TOOL_ROUNDS: Int = ExperienceContract.DEFAULT_TOOL_ROUNDS

        /** 整个工具回路的硬超时：2 分钟（单次请求的超时仍是 5 分钟，见 `LlmClient`）。 */
        const val DEFAULT_DEADLINE_MS: Long = 2 * 60 * 1000L
    }
}
