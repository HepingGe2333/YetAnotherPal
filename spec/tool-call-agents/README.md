# 0.16（dev/0.15 内）Agent 行为完全工具调用化

## 1. 所有对外消息必须通过工具

- 模型不再直接输出 assistant 正文；每一条用户可见的消息都必须调用 `send_message(text)`。
- `stay_silent` 仍表示本轮不发送；`send_message` 每轮最多 4 条，每条 ≤120 字。
- 工具结果返回「已发送，messageId=…」，模型继续决定是否再发一条。
- 一轮结束时：有 send_message → 至少一条消息；只有 stay_silent → 已读不回；两者都没有 → 协议违规。
- **兼容回退**：不支持 tools 的模型 / 旧提示词仍可能直接输出正文；客户端把最终正文视为一条 `send_message`（标记 `legacy`），但计入日志与提示词迁移指标。
- 流式：文本 token 只用于输入指示；气泡在 `send_message` 工具完成后出现（可显示工具参数文本作为即时预览）。

## 2. 思考强度

- 默认 `reasoning_effort = low`（此前 off）。
- 模型调用 `think` 工具后，**该轮后续请求**升到 `high`；think 结果作为工具结果回灌，结束后回到 low。
- 仅主 LLM 会话允许自定义 API（baseUrl/model/key）；ASR/搜索等辅助能力独立配置。

## 3. 迁移兼容

- 旧版本上下文里的 assistant 消息没有 `toolTrace`；提示词注入「历史里有些话是旧版本直接说的，不代表你现在可以跳过工具」。
- 旧消息保留原样显示，不补造工具痕迹；新消息必须走 send_message。
- 备份 v7 仍可读；新字段（如 toolTrace）缺省不写。
- 旧客户端连新服务端：服务端仍接受纯文本消息，但生成端要求工具。

## 4. ASR（视频/语音转写）

- 视频理解需要音轨转写：新增 ASR 配置（provider/baseUrl/model/apiKey），用户在设置里填自己的 key；
  默认走 DeepSeek 官方兼容的 `/audio/transcriptions`（OpenAI Whisper 形状），**不把 key 写进日志/备份**。
- 发送视频/语音时，客户端调用 ASR 得到 transcript，作为附件上下文（「他说：…」）注入；原多模态仍保留。
- ASR 失败/未配置：不阻断发送，明确标记「没有转写」，不要伪造。
- 只有主 LLM 会话 API 支持自定义；ASR 是独立 key。
