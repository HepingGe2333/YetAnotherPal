import Foundation

// ---------------------------------------------------------------------------
// 0.16「Agent 行为完全工具调用化」里**客户端这一侧**的契约
//（`spec/tool-call-agents/README.md` §1 / §3）。
//
// 提示词负责「让模型想这么说话」，这个文件负责「客户端怎么认、怎么回灌、怎么兜底」：
//
// 1. 每条可见消息都必须由 `send_message` 产生；工具结果回灌 `messageId`，模型可以接着发；
// 2. 最终正文里**没有** `send_message` 时只做兼容回退（落一条 `legacy` 消息并记日志），
//    它不是新路径 —— 老模型 / 老提示词还会这么说话，聊天不能因此变哑；
// 3. 只有 `stay_silent` 时保持「已读不回」（在 `ChatService` 里拦截，不经过这里）；
// 4. 旧版本写下的 assistant 消息没有工具痕迹：提示词补一句「那是旧版本直接说的」，
//    但**不去补造**痕迹（历史是历史，不重写）。
//
// 为什么这些判断要做成纯函数：它们决定「这一轮算不算新协议」「要不要给模型补迁移说明」，
// 是最容易在重构里悄悄漂掉的两件事，必须能被单测直接钉住（不必构造一条聊天链路）。
// ---------------------------------------------------------------------------

public enum OutboundProtocol {

    /// 把话说出去的工具名（`send_message`）。
    public static let speechToolName = ToolCatalog.sendMessage

    /// `toolTraceJson` 的「新协议：这一轮没有别的工具痕迹」标记：`[]`。
    ///
    /// 为什么需要它：`toolTraceJson == nil` 是**旧版本**直接说话的标记（§3 的迁移判据）；
    /// 新协议下「只发了消息、没查没记」的一轮同样没有工具痕迹，若也写 nil，
    /// 迁移说明就会对全新的会话一直重复。写一个空数组把两种情况分开：
    /// - `nil` → 旧版本说的 → 注入迁移说明；
    /// - `[]` → 新协议用 `send_message` 发的 → 不注入。
    ///
    /// 空数组解码出来就是「没有痕迹」，界面不会因此多一行小字。
    public static let toolProtocolTraceMarker = "[]"

    /// 这一轮已经用 `send_message` 说过话时，回灌给模型的那句（§1：工具结果带 messageId）。
    ///
    /// 为什么要带 id：模型需要知道「这句话真的出去了」（而不是被截断/被拒绝），
    /// 才敢接着发下一句或结束这一轮；失败与记 id 的分支都在 `ChatService` 一处收口。
    public static func sentReceipt(messageId: String) -> String {
        let id = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        return "已发送，messageId=\(id)。这一句已经出现在对方的聊天里了。"
            + "想接着说下一句就再调用一次 send_message；说完了就直接结束这一轮，不要再输出正文。"
    }

    /// 兼容回退的日志标记（§1：正文被当成一条 `send_message` 时要**标记 legacy**）。
    public static let legacyFallbackLogTag = "legacy 回退"

    /// 这条 assistant 消息是不是「旧版本直接说的」：没有 `toolTraceJson`（§3）。
    ///
    /// 失败态的半句不算：它根本没被对方当成一句话读过，不该被当成「历史里的旧话」。
    public static func isLegacyAssistantMessage(_ message: MessageEntity) -> Bool {
        guard message.role == .assistant, message.status != .error else { return false }
        let raw = (message.toolTraceJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return raw.isEmpty
    }

    /// 上下文里有几条旧版本直接说的 assistant 消息（0 = 不注入迁移说明）。
    public static func legacyAssistantMessageCount(_ messages: [MessageEntity]) -> Int {
        messages.reduce(0) { $0 + (isLegacyAssistantMessage($1) ? 1 : 0) }
    }

    /// 旧上下文里**没有工具痕迹**时补进 system 的那一段（§3：迁移兼容）。
    ///
    /// 只说不许什么、不解释版本号：模型不需要知道版本，只需要知道
    /// 「历史里那些直接说的话不是许可」。
    public static func migrationNote(language: PromptLanguage) -> String {
        switch language {
        case .zh:
            return "# 历史说明\n"
                + "聊天记录里有些话是旧版本的他直接说的（那时候还没有 send_message）。"
                + "这不代表你现在可以跳过工具：从现在起，每一句给对方看的话都要调用 send_message 发出去。"
        default:
            return "# About the history\n"
                + "Some messages in the chat history were spoken directly by an older version "
                + "(there was no send_message back then). That doesn't mean you can skip tools now: "
                + "from now on every visible message must be sent with send_message."
        }
    }
}
