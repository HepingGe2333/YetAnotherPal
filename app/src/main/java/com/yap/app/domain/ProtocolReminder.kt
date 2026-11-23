package com.yap.app.domain

/**
 * 0.16 工具协议的「正文确认循环」（`spec/tool-call-agents/README.md` §1）。
 *
 * 背景：一轮结束时既没有 `send_message` / `stay_silent`、也没有任何别的工具调用，却直接写出了
 * 正文——这是协议违规。0.15 的做法是「提醒一次，仍然违规就 legacy 落库」，用户看到的是
 * 「他不太守规矩」的那条回复。0.16 起改成**逐段确认**：
 *
 * 1. 把模型刚才那段正文当作 **assistant 消息**塞回对话；
 * 2. 追加一条**不可见**的 `user` 提醒（只有模型能看见，不落库、不给用户看）；
 * 3. 带上工具重新请求；
 * 4. 这次正确调用了工具（发消息 / 沉默 / 别的工具）→ 正常走；
 *    仍然只有正文 → 再问一次（同一轮最多 [MAX_ATTEMPTS] 次）；
 * 5. [MAX_ATTEMPTS] 轮之后仍然没有工具调用 → **不落库、不走 legacy**，这一轮按
 *    [SilentReason.PROTOCOL_DROPPED] 结束，日志打 `protocol_dropped`（次数 + 字数）。
 *
 * 也就是说：正文永远不能自动变成用户可见的消息；`send_message` 是唯一的出口。
 *
 * 边界（都不因为确认循环而改变）：
 * - 危机轮：判定为危机时**不提醒**——危机里最要紧的是把话尽快说出来，多一轮请求只会拖慢它；
 *   这一轮的正文与安全提示仍然必须落库，**不能被 dropped**（见 [shouldDrop] 的 `crisis` 参数）；
 * - 沉默：`stay_silent` / `defer_reply` 已经发生过的轮次不提醒（已经明确「不回」了）；
 * - 已经发过消息的轮次不提醒（正文只是「剩下的半句」，会并进最后一条）；
 * - 拉黑：拉黑在更早的分支就返回了，这里根本不会走到。
 *
 * 为什么放在 domain：提醒文案、该不该问、该不该丢都是可以逐条断言的规则；`ChatRepository` 是
 * Android 依赖很重的类，JVM 单测跑不动（本仓库的 `*ContractTest` 都是这个路子）。
 */
object ProtocolReminder {

    /** 同一轮最多追问几次（规范：2 轮提醒；第 3 次仍然只有正文就丢弃）。 */
    const val MAX_ATTEMPTS = 2

    /** `logcat` 里标记「问过模型一次」的稳定字样（只进本地日志，界面上看不见）。 */
    const val LOG_MARKER = "protocol_reminder"

    /** `logcat` 里标记「正文被丢弃」的稳定字样（只进本地日志，界面上看不见）。 */
    const val DROP_LOG_MARKER = "protocol_dropped"

    /**
     * 中文人格的提醒原文（需求给定的逐字文案）。
     *
     * 只说「这段话要不要发、要发就调哪个工具」，不复述人设、不评价他的回复内容——提醒是
     * **操作性**的，让它开始自我解释只会再浪费一轮。
     */
    const val TEXT_ZH =
        "你刚才输出的不是工具调用。请确认：这段话要发给用户吗？要发→必须调用 send_message(text) " +
            "把它发出去；不想发→调用 stay_silent；只是思考→不要输出正文。"

    /** 非中文人格的提醒原文（与 [TEXT_ZH] 一一对应）。 */
    const val TEXT_EN =
        "Your last output was not a tool call. Confirm: should this text be sent to the user? " +
            "To send → you MUST call send_message(text) and send it; to not send → call stay_silent; " +
            "if it was only thinking → do not output plain text."

    /** 按人格语言选提醒原文。 */
    fun text(language: PromptLanguage = PromptLanguage.ZH): String =
        if (language == PromptLanguage.ZH) TEXT_ZH else TEXT_EN

    /**
     * 这一轮要不要（再）问一次。
     *
     * @param hasToolCalls 这次响应里有没有任何工具调用（有就不用问：它已经在用工具了）
     * @param body 这次响应里的正文（空白 = 没有正文，不算违规）
     * @param messagesSent 这一轮已经通过 `send_message` 落库的条数
     * @param silenced 这一轮已经 `stay_silent`
     * @param deferred 这一轮已经 `defer_reply`
     * @param crisis 这一轮被判成危机（危机优先：不拖慢，而且正文必须落库）
     * @param attempts 这一轮已经问过几次（最多 [MAX_ATTEMPTS] 次）
     */
    fun shouldRemind(
        hasToolCalls: Boolean,
        body: String,
        messagesSent: Int = 0,
        silenced: Boolean = false,
        deferred: Boolean = false,
        crisis: Boolean = false,
        attempts: Int = 0
    ): Boolean {
        if (attempts >= MAX_ATTEMPTS) return false
        if (hasToolCalls) return false
        if (body.isBlank()) return false
        if (messagesSent > 0) return false
        if (silenced || deferred || crisis) return false
        return true
    }

    /**
     * 这一轮最终是不是必须**丢弃正文**（不落库、不走 legacy）。
     *
     * 判定只有一条：**正文没有通过 `send_message` 确认过**。按 0.16 的产品要求，正文永远不能
     * 自动变成一条用户可见的消息——问过几次只影响日志，不影响「丢不丢」；循环问完仍然只有正文，
     * 或者这一轮干脆没经过确认（工具轮用尽后补写的正文），都一样丢掉。
     *
     * 两个例外：
     * - 已经用 `send_message` 发过消息：剩下的正文只是「半句话」，并进最后一条（既有行为，
     *   见 `ChatRepository`），不算违规落库；
     * - 危机轮：危机优先（0.15）要求这一轮必须有可见的安全提示，丢掉会把安全网一起丢掉。
     *
     * @param body 这一轮最终正文（已去掉主动唤醒代码块）
     * @param messagesSent 这一轮已经通过 `send_message` 落库的条数
     * @param crisis 这一轮被判成危机
     */
    fun shouldDrop(
        body: String,
        messagesSent: Int = 0,
        crisis: Boolean = false
    ): Boolean {
        if (crisis) return false
        if (messagesSent > 0) return false
        return body.isNotBlank()
    }

    /** 日志里那一行：带第几次与正文长度，不含正文本身（回复内容不该出现在日志里）。 */
    fun logLine(attempt: Int, chars: Int): String =
        "$LOG_MARKER attempt=${attempt.coerceAtLeast(1)}/$MAX_ATTEMPTS chars=$chars; " +
            "reply had plain text but no tool call, asked the model to confirm and send it"

    /** 丢弃时的那一行：同样只记「问过几次 + 正文长度」，不含正文本身。 */
    fun dropLogLine(attempts: Int, chars: Int): String =
        "$DROP_LOG_MARKER attempts=${attempts.coerceAtLeast(0)}/$MAX_ATTEMPTS chars=$chars; " +
            "reply never called send_message, dropped without saving"
}
