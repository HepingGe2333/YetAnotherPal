import Foundation

// ---------------------------------------------------------------------------
// 0.16 思考强度（`spec/tool-call-agents/README.md` §2）。
//
// 规则只有三句：
// 1. 主 LLM 会话的请求默认带 `reasoning_effort=low`（此前是 off）；
// 2. 模型调用 `think` 之后，**这一轮的后续请求**升到 `high`（想清楚再开口）；
// 3. 这一轮结束就回到 `low`（下一轮重新从默认档开始）。
//
// 为什么把它做成一个只有两档的枚举，而不是让调用方随便传字符串：
// - 两档的**语义**是产品决定（默认省、想清楚时贵一点），写成枚举之后
//   「默认 low / think 之后 high」只有一处定义，聊天链路与测试不会各写一个 "low"；
// - 它只出现在**请求报文的参数**里（`InferenceParams.reasoningEffort`），
//   不进数据库、不进备份 —— 档位属于「这次怎么问」，不属于「他是谁」。
//
// 谁用、谁不用：只有主 LLM 会话（`ChatService.generate`）设置它。
// ASR / 搜索 / 记忆抽取 / 危机判定这些辅助调用保持 nil（= 不发这个字段），
// 它们要的是稳定与便宜，不是「想得更深」。
// ---------------------------------------------------------------------------

public enum ReasoningEffort: String, CaseIterable, Equatable, Sendable {
    /// 默认档：日常聊天不该为每一句话多付一份思考 token。
    case low
    /// 模型自己按了 `think` 才升上来的档：这一轮后面的请求都按「认真想」发。
    case high

    /// 这一轮第一次请求的档位（`spec/tool-call-agents/README.md` §2：默认 low）。
    public static let defaultEffort: ReasoningEffort = .low

    /// 调用 `think` 之后，这一轮后续请求的档位。
    public static let afterThink: ReasoningEffort = .high

    /// 报文里的字段值（`reasoning_effort`）。
    public var wireValue: String { rawValue }
}
