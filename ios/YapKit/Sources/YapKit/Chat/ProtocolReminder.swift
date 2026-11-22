import Foundation

// ---------------------------------------------------------------------------
// 工具协议违规时的**自动提醒**（0.15.7）。
//
// 背景（`spec/tool-call-agents/README.md` §1）：0.16 起「话只能通过 `send_message` 说出去」，
// 模型直接写正文不再算发出去——客户端只为兼容老模型保留 legacy 回退。
// 但在真实链路里仍然会看到这种情况：模型读到了工具清单，写了一句挺自然的话，
// 然后**忘了调用工具**。以前这一轮会直接按 legacy 回退落库：消息照样发出去，
// 用户的体验没问题，可协议从此没人执行，日志里只能看到「又走了一次旧协议」。
//
// 现在的做法：先**在后台提醒模型一次**，给它一次机会把话用正确的方式说出来：
//
// 1. 把模型刚才写的正文当成一条 assistant 消息放回 `conversation`；
// 2. 追加一条**不可见**的 user 提醒（本文件这段文字）；
// 3. 带着 tools 重新请求一次（同一轮最多 1 次）；
// 4. 正确调用（`send_message`）/ 沉默（`stay_silent`）/ 用别的工具说话 → 按正常路径走；
//    仍然只输出正文 → 才退回 legacy 落库。
//
// 三条硬约束（都写在 `ChatService` 的调用点上）：
// - 提醒**不显示、不落库**：它只活在这一次请求的 `conversation` 数组里；
// - 不改变危机 / 拉黑 / 沉默的限制：提醒只是多了一次「再说一遍」的机会，
//   原来会被拦下来的轮次（拉黑、睡眠、危机优先）在提醒前后完全一样；
// - 日志只记一行 `protocol_reminder`（字数 + 结果），**不带正文**。
//
// 为什么提醒里要写「要发消息→send_message(text)」这种近乎命令式的句子：
// 模型不是不懂规则，而是「说完就停」的习惯太强。给它三个**穷举**的出口
// （发 / 不发 / 做别的）比再解释一遍「为什么」有效得多。
// ---------------------------------------------------------------------------

public enum ProtocolReminder {

    /// 日志标签（排查「还有多少轮在走旧协议」时用它搜索）。
    public static let logTag = "protocol_reminder"

    /// 中文提醒（逐字对应需求原文）。
    public static let chineseText =
        "（系统提醒）你刚才直接输出了正文，但没有调用工具。"
        + "请明确：要发消息→send_message(text)；不想发→stay_silent；"
        + "要做别的事→对应工具。不要直接输出正文。"

    /// 英文提醒：与中文一一对应（英文人格读到中文提醒会把它当设定，而不是指令）。
    public static let englishText =
        "(System reminder) You just wrote a reply but didn't call any tool. "
        + "Be explicit: to send a message → send_message(text); "
        + "to say nothing this turn → stay_silent; "
        + "to do something else → the matching tool. Don't output the reply body directly."

    /// 按人格语言取提醒文字。
    public static func text(language: PromptLanguage = .zh) -> String {
        language == .zh ? chineseText : englishText
    }

    /// 按人格取提醒文字（唯一真源：`PersonaEntity.languageTag`）。
    public static func text(for persona: PersonaEntity) -> String {
        text(language: PersonaLanguagePrompt.language(for: persona))
    }
}
