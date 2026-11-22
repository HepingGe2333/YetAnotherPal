package com.yap.app.domain

/**
 * 0.16（dev/0.15 内）「所有对外消息都必须通过工具发出去」的协议（`spec/tool-call-agents/README.md` §1）。
 *
 * 三件事以前是可以混着来的，现在只剩一条正路：
 *
 * 1. **新路径**：模型每一句给用户看的话都调用 `send_message(text)`；聊天层落库后把
 *    「已发送，messageId=…」回灌给它，它再决定要不要再发一条（每轮 ≤ [MAX_PER_ROUND] 条、
 *    每条 ≤ [MAX_CHARS] 字，这两个数字来自 [ExperienceRhythm]，不在这里另写一份）。
 * 2. **沉默**：`stay_silent` 仍然是「这一轮只已读」——不落任何 assistant 消息。
 * 3. **兼容回退**（只给不守规矩的模型/旧提示词）：最终正文没有配任何 `send_message` 时，
 *    整段落成**一条** `legacy` 消息，并在 `toolTrace` 里标出来，供提示词迁移与日志统计。
 *
 * 为什么把文案放在 domain 而不是 ChatRepository：它们要能逐条断言（本仓库的 `*ContractTest`
 * 都是一样的做法），而 ChatRepository 是 Android 依赖很重的类，JVM 单测跑不动。
 * 这里只有常量与纯函数，不读时钟、不读资源、不碰数据库。
 */
object AgentReplyProtocol {

    /** 新路径的工具名（与 [ToolCatalog.SEND_MESSAGE] 同一个字符串，不另写一份）。 */
    const val TOOL = ToolCatalog.SEND_MESSAGE

    /** 一轮最多发几条（规范 §1：4 条）。 */
    const val MAX_PER_ROUND = ExperienceRhythm.MAX_MESSAGES_PER_ROUND

    /** 每条最多多少字（规范 §1：120 字）。 */
    const val MAX_CHARS = ExperienceRhythm.MAX_SEND_MESSAGE_CHARS

    /** `toolTrace` 里的「回复通道」标记：新路径（send_message，用户看不见这一条痕迹）。 */
    const val CHANNEL_TOOL = "send_message"

    /** `toolTrace` 里的「回复通道」标记：兼容回退（正文直出，旧版本/不守规矩的模型）。 */
    const val CHANNEL_LEGACY = "legacy"

    /**
     * 能力段最前面的一条硬规则（中文）。
     *
     * 为什么放在「# 你能做什么」的第一行而不是工具描述里：模型读工具描述时已经在挑工具了，
     * 只有「这一轮没有正文通道」这层元规则能阻止它顺手写一段正文。
     */
    const val RULE_ZH =
        "你不能直接输出正文：每一句给对方看的话都必须调用 send_message(text) 发出去（一轮最多 4 条、每条不超过 120 字）；" +
            "这一轮不想说话就调用 stay_silent；两者都没有等于没回。"

    /** 同一条硬规则（非中文人格：`capabilityPrompt` 的 else 分支统一用英文脚手架）。 */
    const val RULE_EN =
        "You cannot reply with plain text: every message the user sees MUST be sent with send_message(text) " +
            "(at most 4 per turn, 120 characters each). To stay read-only this turn, call stay_silent. " +
            "Doing neither means you did not reply."

    /**
     * 迁移提示（规范 §3）：历史里存在「旧版本直接说」的 assistant 消息时注入。
     *
     * 只说「历史不代表现在」，不补造任何工具痕迹——旧消息原样显示、原样留在上下文里。
     */
    const val MIGRATION_ZH =
        "历史里有些话是旧版本直接说的，不代表你现在可以跳过工具：从这一轮起，所有回复都必须调用 send_message。"

    /** [MIGRATION_ZH] 的英文版。 */
    const val MIGRATION_EN =
        "Some earlier messages were written directly by an older version. That does not mean you may skip tools now: " +
            "from this turn on, every message must be sent with send_message."

    /** 能力段里要插入的那一行（按人格语言选）。 */
    fun rule(language: PromptLanguage = PromptLanguage.ZH): String =
        if (language == PromptLanguage.ZH) RULE_ZH else RULE_EN

    /**
     * 这一轮要不要在提示词里补迁移说明。
     *
     * @param hasLegacyDirectReply 上下文里有没有「旧版本直接说」的 assistant 消息
     *        （判定见 [ToolTraceCodec.isLegacyReply]）；没有就返回 null，正常对话一个字都不多。
     */
    fun migrationNote(
        hasLegacyDirectReply: Boolean,
        language: PromptLanguage = PromptLanguage.ZH
    ): String? {
        if (!hasLegacyDirectReply) return null
        return if (language == PromptLanguage.ZH) MIGRATION_ZH else MIGRATION_EN
    }

    /**
     * `send_message` 成功后回灌给模型的话（规范 §1：「已发送，messageId=…」）。
     *
     * 带上序号与剩余额度，模型自己就能判断还能发几条，不用去数上面的结果。
     */
    fun sentResult(index: Int, messageId: String): String {
        val safeIndex = index.coerceAtLeast(1)
        return "已发送，messageId=$messageId（第 $safeIndex/$MAX_PER_ROUND 条）。" +
            "不要重复这句；还有话说就继续调用 send_message，没有就结束这一轮，不要再输出正文。"
    }

    /** 发满 4 条之后回灌给模型的话：不许再造第 5 个气泡，也不许改用正文。 */
    fun tooManyResult(): String =
        "这一轮已经发满 $MAX_PER_ROUND 条了，别再调用 send_message；剩下的话留到下一轮自然说，" +
            "不要改写成正文。"

    /** 空正文的回灌：让对方读到一句完整的话，而不是一个空字符串。 */
    fun blankResult(): String = "这条消息是空的，没有发出去。写一句完整的话再调用 send_message。"

    /**
     * 这一轮的最终正文算不算**兼容回退**（规范 §1）。
     *
     * @param messagesSent 这一轮已经通过 `send_message` 落库的条数
     */
    fun isLegacyFallback(messagesSent: Int): Boolean = messagesSent <= 0

    /** 日志里标记兼容回退的那一行（只进 logcat，界面不显示）。 */
    fun legacyLogLine(messagesSent: Int, chars: Int): String =
        "legacy reply channel: body fell back to direct text " +
            "(send_message=$messagesSent, chars=$chars); prompt path expects send_message"
}
