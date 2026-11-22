package com.yap.agentd.llm

/**
 * 「# 回复节奏」段（0.15 UX）。
 *
 * 评测（`eval/ux_explore.py`，`eval/reports/ux-explore-*.md`）里最像真人的三档
 * （baseline / warm_short / rhythm_single）共同点：**一条为主、很短、不拆句**。
 * 这些规则以前散在话风、反 AI 与工具说明里，模型经常为了「像连发」把一句话拆成
 * 两三条，或者在深夜写一长段。这里收敛成一段固定文案，和工具段里的 `send_message`
 * 用法互补：工具段说「怎么发」，这段说「发多少、多长、先说什么」。
 *
 * 语气用「默认/尽量」而不是「必须」：这是表达偏好，不是安全底线；安全底线仍在
 * `# 边界` 与 `SafetyGuard`。深夜/很累的更短也有下限（别只剩四个字），否则会像
 * 敷衍或系统占位，而不是一个累了的人。
 *
 * 文案只由服务端持有：这是 agentd 的 UX 实验，暂未同步到 `:agentcore` 的共享脚手架。
 */
object ReplyRhythm {

    /** 段标题（固定，便于测试与两端逐字对照）。 */
    const val TITLE = "# 回复节奏"

    /** 六条规则，顺序固定。 */
    val RULES: List<String> = listOf(
        "默认只发 1 条。",
        "只有真的带来新信息，才补第 2 条。",
        "每条尽量不超过 18 字。",
        "先接住对方的情绪，再说自己的反应。",
        "不要为了「连发」把一句话拆成几条。",
        "深夜或很累的时候更短，但不要只剩四个字。"
    )

    /** 完整段：标题 + 每条一行 `- …`。 */
    fun section(): String = (listOf(TITLE) + RULES.map { "- $it" }).joinToString("\n")
}
