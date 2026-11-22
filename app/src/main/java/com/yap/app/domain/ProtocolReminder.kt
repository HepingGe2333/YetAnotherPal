package com.yap.app.domain

/**
 * 0.16 工具协议的「先提醒一次，再谈回退」（`spec/tool-call-agents/README.md` §1）。
 *
 * 背景：一轮结束时既没有 `send_message` / `stay_silent`、也没有任何别的工具调用，却直接写出了
 * 正文——这是协议违规。以前这种回复会直接走**兼容回退**（正文落一条 `legacy` 消息），
 * 用户看到的是「他不太守规矩」的那条回复。0.16 起多一步：
 *
 * 1. 把模型刚才那段正文当作 **assistant 消息**塞回对话；
 * 2. 追加一条**不可见**的 `user` 提醒（只有模型能看见，不落库、不给用户看）；
 * 3. 带上工具重新请求一次（同一轮最多重试 [MAX_ATTEMPTS] 次）；
 * 4. 这次正确调用了工具（发消息 / 沉默 / 别的工具）→ 正常走；仍然只有正文 → 才走兼容回退。
 *
 * 边界（都不因为这次提醒而改变）：
 * - 危机轮：判定为危机时**不提醒**——危机里最要紧的是把话尽快说出来，多一次请求只会拖慢它；
 * - 沉默：`stay_silent` / `defer_reply` 已经发生过的轮次不提醒（已经明确「不回」了）；
 * - 已经发过消息的轮次不提醒（正文只是「剩下的半句」，会并进最后一条）；
 * - 拉黑：拉黑在更早的分支就返回了，这里根本不会走到。
 *
 * 为什么放在 domain：提醒文案与「该不该提醒」都是可以逐条断言的规则；`ChatRepository` 是
 * Android 依赖很重的类，JVM 单测跑不动（本仓库的 `*ContractTest` 都是这个路子）。
 */
object ProtocolReminder {

    /** 同一轮最多提醒几次（规范：1 次）。 */
    const val MAX_ATTEMPTS = 1

    /** `logcat` 里标记这次提醒的稳定字样（只进本地日志，界面上看不见）。 */
    const val LOG_MARKER = "protocol_reminder"

    /**
     * 中文人格的提醒原文（需求给定的逐字文案）。
     *
     * 只说「你应该调哪个工具」，不复述人设、不评价他的回复内容——提醒是**操作性**的，
     * 让它开始自我解释只会再浪费一轮。
     */
    const val TEXT_ZH =
        "（系统提醒）你刚才直接输出了正文，但没有调用工具。请明确：要发消息→调用 send_message(text)；" +
            "不想发→调用 stay_silent；要做别的事→调用对应工具。不要直接输出正文。"

    /** 非中文人格的提醒原文（与 [TEXT_ZH] 一一对应）。 */
    const val TEXT_EN =
        "(System reminder) You just wrote plain text without calling any tool. Be explicit: to speak → call " +
            "send_message(text); to stay silent → call stay_silent; to do something else → call the matching tool. " +
            "Do not output plain text."

    /** 按人格语言选提醒原文。 */
    fun text(language: PromptLanguage = PromptLanguage.ZH): String =
        if (language == PromptLanguage.ZH) TEXT_ZH else TEXT_EN

    /**
     * 这一轮要不要（再）提醒一次。
     *
     * @param hasToolCalls 这次响应里有没有任何工具调用（有就不用提醒：它已经在用工具了）
     * @param body 这次响应里的正文（空白 = 没有正文，不算违规）
     * @param messagesSent 这一轮已经通过 `send_message` 落库的条数
     * @param silenced 这一轮已经 `stay_silent`
     * @param deferred 这一轮已经 `defer_reply`
     * @param crisis 这一轮被判成危机（危机优先：不拖慢）
     * @param alreadyReminded 同一轮是否已经提醒过（最多 [MAX_ATTEMPTS] 次）
     */
    fun shouldRemind(
        hasToolCalls: Boolean,
        body: String,
        messagesSent: Int = 0,
        silenced: Boolean = false,
        deferred: Boolean = false,
        crisis: Boolean = false,
        alreadyReminded: Boolean = false
    ): Boolean {
        if (alreadyReminded) return false
        if (hasToolCalls) return false
        if (body.isBlank()) return false
        if (messagesSent > 0) return false
        if (silenced || deferred || crisis) return false
        return true
    }

    /** 日志里那一行：带次数与正文长度，不含正文本身（回复内容不该出现在日志里）。 */
    fun logLine(attempt: Int, chars: Int): String =
        "$LOG_MARKER attempt=$attempt chars=$chars; reply had plain text but no tool call, asked the model to use a tool"
}
