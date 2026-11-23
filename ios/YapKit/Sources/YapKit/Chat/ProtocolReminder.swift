import Foundation

// ---------------------------------------------------------------------------
// 工具协议**确认循环**（`ProtocolReminder`，与 Android `domain/ProtocolReminder.kt` /
// agentd `LlmReplyGenerator` 同一套语义）。
//
// 背景（`spec/tool-call-agents/README.md` §1）：0.16 起「话只能通过 `send_message` 说出去」，
// 模型直接写正文不再算发出去。真实链路里仍然会看到这种情况：模型读到了工具清单，
// 写了一句挺自然的话，然后**忘了调用工具**。
//
// 产品要求（0.15.0 收紧）：**每一段非工具输出都先问模型「要不要发送」**，
// 只有 `send_message` 确认才发；不能自动 legacy 发送。于是这里是一个最多 **2 轮**的确认循环：
//
// 1. 把模型刚才写的正文当成一条 assistant 消息放回 `conversation`；
// 2. 追加一条**不可见**的 user 提醒（本文件这段文字）；
// 3. 带着 tools 重新请求一次；
// 4. 正确调用 `send_message` / 沉默（`stay_silent`）/ 用别的工具说话 → 按正常路径走；
// 5. 仍然只输出正文 → 再问一次；**2 轮之后仍然没有工具调用** → 整段丢弃：
//    不落库、不 legacy、不显示，按 silent 结束并记 `protocol_dropped`。
//
// 边界（都与 Android 逐条对应，`shouldRemind` / `shouldDrop` 就是这些规则的唯一真源）：
// - 危机轮：**不提醒**（危机里最要紧的是把话尽快说出来），正文与安全提示仍然必须落库，
//   **不能被 dropped**；这一点由 `shouldDrop(crisis:)` 保证；
// - `stay_silent` / `defer_reply` 已经发生过的轮次不提醒（已经明确「不回」了）；
// - 这一轮已经发过消息时不再提醒：剩下的正文只是「半句话」，会并进最后一条；
// - 工具轮用尽之后才补写的正文同样不落库、不 legacy（`shouldDrop` 只看有没有 `send_message`，
//   不看问过几次）；
// - 拉黑在更早的分支就返回了，这里根本不会走到。
//
// 三条硬约束（都写在 `ChatService` 的调用点上）：
// - 提醒**不显示、不落库**：它只活在这一次请求的 `conversation` 数组里；
// - 日志只记 `protocol_reminder` / `protocol_dropped`（第几次 + 字数），**不带正文**；
// - 确认循环不改变 4 条上限、工具轮上限、连续沉默与安全兜底。
//
// 为什么提醒里要写「要发消息→send_message(text)」这种近乎命令式的句子：
// 模型不是不懂规则，而是「说完就停」的习惯太强。给它三个**穷举**的出口
// （发 / 不发 / 只是思考）比再解释一遍「为什么」有效得多。
// ---------------------------------------------------------------------------

public enum ProtocolReminder {

    /// 确认提醒的日志标签（排查「模型被问了几次才按协议说话」时用它搜索）。
    public static let logTag = "protocol_reminder"

    /// 整段丢弃的日志标签（0.15.0：没有 `send_message` 确认的正文一律不发）。
    public static let droppedLogTag = "protocol_dropped"

    /// 同一轮最多问几次「这段话要不要发」。
    ///
    /// 2 轮是一个刻意的上限：第一轮是「提醒」，第二轮是「最后确认」；
    /// 再问下去只会把一次走神拖成一整轮空转（每一次都要真实计费）。
    public static let maxConfirmationRounds = 2

    /// 中文确认提醒（需求给定的逐字文案）。
    public static let chineseText =
        "你刚才输出的不是工具调用。请确认：这段话要发给用户吗？"
        + "要发→必须调用 send_message(text)；不想发→stay_silent；只是思考→不要输出正文。"

    /// 英文确认提醒：与中文一一对应（与 Android `TEXT_EN` 同义）。
    public static let englishText =
        "Your last output was not a tool call. Confirm: should this text be sent to the user? "
        + "To send → you MUST call send_message(text) and send it; to not send → call stay_silent; "
        + "if it was only thinking → do not output plain text."

    /// 按人格语言取提醒文字。
    public static func text(language: PromptLanguage = .zh) -> String {
        language == .zh ? chineseText : englishText
    }

    /// 按人格取提醒文字（唯一真源：`PersonaEntity.languageTag`）。
    public static func text(for persona: PersonaEntity) -> String {
        text(language: PersonaLanguagePrompt.language(for: persona))
    }

    // MARK: - 该不该问 / 该不该丢（纯规则，与 Android 同名函数逐条对应）

    /// 这一轮要不要（再）问一次。
    ///
    /// - `hasToolCalls`：这次响应里有没有任何工具调用（有就不用问：它已经在用工具了）；
    /// - `body`：这次响应里的正文（空白 = 没有正文，不算违规）；
    /// - `messagesSent`：这一轮已经通过 `send_message` 落库的条数；
    /// - `silenced` / `deferred`：这一轮已经明确「不回」；
    /// - `crisis`：危机优先（不拖慢，而且正文必须落库）；
    /// - `attempts`：这一轮已经问过几次（最多 `maxConfirmationRounds` 次）。
    public static func shouldRemind(
        hasToolCalls: Bool,
        body: String,
        messagesSent: Int = 0,
        silenced: Bool = false,
        deferred: Bool = false,
        crisis: Bool = false,
        attempts: Int = 0
    ) -> Bool {
        if attempts >= maxConfirmationRounds { return false }
        if hasToolCalls { return false }
        if body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
        if messagesSent > 0 { return false }
        if silenced || deferred || crisis { return false }
        return true
    }

    /// 这一轮最终是不是必须**丢弃正文**（不落库、不走 legacy）。
    ///
    /// 判定只有一条：**正文没有通过 `send_message` 确认过**。按产品要求，正文永远不能
    /// 自动变成一条用户可见的消息——问过几次只影响日志，不影响「丢不丢」；循环问完仍然
    /// 只有正文，或者这一轮干脆没经过确认（工具轮用尽后补写的正文），都一样丢掉。
    ///
    /// 两个例外：
    /// - 已经用 `send_message` 发过消息：剩下的正文只是「半句话」，并进最后一条（既有行为）；
    /// - 危机轮：危机优先（0.15）要求这一轮必须有可见的安全提示，丢掉会把安全网一起丢掉。
    ///
    /// - Parameters:
    ///   - body: 这一轮最终正文（已去掉主动唤醒代码块）
    ///   - messagesSent: 这一轮已经通过 `send_message` 落库的条数
    ///   - crisis: 这一轮被判成危机
    public static func shouldDrop(
        body: String,
        messagesSent: Int = 0,
        crisis: Bool = false
    ) -> Bool {
        if crisis { return false }
        if messagesSent > 0 { return false }
        return !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 提醒日志：带第几次与正文字数，不含正文本身（回复内容不该出现在日志里）。
    public static func logLine(attempt: Int, chars: Int) -> String {
        "\(logTag) attempt=\(max(attempt, 1))/\(maxConfirmationRounds) chars=\(chars); "
            + "reply had plain text but no tool call, asked the model to confirm and send it"
    }

    /// 丢弃日志：同样只记「问过几次 + 正文字数」，不含正文本身。
    public static func dropLogLine(attempts: Int, chars: Int) -> String {
        "\(droppedLogTag) attempts=\(max(attempts, 0))/\(maxConfirmationRounds) chars=\(chars); "
            + "reply never called send_message, dropped without saving"
    }
}
