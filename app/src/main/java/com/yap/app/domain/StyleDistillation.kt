package com.yap.app.domain

import com.yap.app.data.local.PersonaEntity

/**
 * 从**自己最近的聊天记录**里学话风（`spec/persona-guidance/README.md` §3 的另一种取样方式）。
 *
 * 以前只有一条路：用户把微信/QQ 聊天记录粘进编辑页，再点「总结」。但这个人格本来就在跟用户聊天——
 * 那几十条消息才是最像「他」的语料，用户不该为了让他说话像自己而再去别的 App 里复制粘贴。
 *
 * 这里只做**纯字符串处理**（不读库、不联网、不落盘）：
 * 1. 取最近 [MAX_MESSAGES] 条消息（时间正序），跳过空正文（只发表情/图片的那些）；
 * 2. 每条压成一行：用户说的写「用户：…」、他说的写「他：…」；
 * 3. 单条截到 [MAX_CHARS_PER_LINE] 字、整体不超过 [MAX_TOTAL_CHARS] 字
 *    （与 [com.yap.app.data.ai.SpeechStyleSummarizer.MAX_SAMPLE_CHARS] 同一量级，一次请求装得下）。
 *
 * 隐私：这段样本只在内存里活一次请求的时间，**不落库、不写日志、不进备份**；
 * 这个类本身也没有任何可以落库的入口（只有 `String` 进、`String` 出）。
 */
object StyleDistillation {

    /** 一次最多看几条消息（够看出说话习惯，又不至于把一次请求撑爆）。 */
    const val MAX_MESSAGES = 50

    /** 单条消息最多留多少字（长段落对「说话方式」没有额外信息量）。 */
    const val MAX_CHARS_PER_LINE = 120

    /** 整段样本最多多少字（与 [com.yap.app.data.ai.SpeechStyleSummarizer.MAX_SAMPLE_CHARS] 对齐）。 */
    const val MAX_TOTAL_CHARS = 8_000

    /** 用户那句话的前缀（样本里的人称固定成「用户」/「他」，不写真实名字）。 */
    const val USER_PREFIX = "用户："

    /** 他说的那句话的前缀。 */
    const val PERSONA_PREFIX = "他："

    /** 一条待学习的消息：[fromUser] 为 true 表示这句是用户说的。 */
    data class Message(val fromUser: Boolean, val text: String)

    /**
     * 拼样本。
     *
     * @param messages 时间正序的消息（调用方负责按时间排好；顺序会影响「他」的语气连贯性）
     * @return 形如「用户：在吗\n他：嗯」的样本；没有任何可用正文时返回空串
     */
    fun sample(messages: List<Message>): String {
        val picked = pick(messages)
        if (picked.isEmpty()) return ""
        val builder = StringBuilder()
        for (line in picked) {
            if (builder.isNotEmpty()) builder.append('\n')
            // 最后一行可能刚好把预算用满：截断而不是整条丢掉（丢掉的是「他」最近说的话，最可惜）。
            val left = MAX_TOTAL_CHARS - builder.length
            if (left <= 0) break
            builder.append(if (line.length <= left) line else line.take(left))
        }
        return builder.toString().trim()
    }

    /**
     * 取最近 [MAX_MESSAGES] 条里**有正文**的那些，并按预算逐条压成一行。
     *
     * 返回的列表已经是最终要发的行（截断过、跳过空白），方便测试逐条断言。
     */
    fun pick(messages: List<Message>): List<String> {
        if (messages.isEmpty()) return emptyList()
        val recent = messages.takeLast(MAX_MESSAGES.coerceAtLeast(0))
        val lines = ArrayList<String>(recent.size)
        var used = 0
        for (message in recent) {
            val text = flatten(message.text)
            if (text.isEmpty()) continue
            val line = (if (message.fromUser) USER_PREFIX else PERSONA_PREFIX) + clip(text)
            if (used + line.length + 1 > MAX_TOTAL_CHARS) break
            lines += line
            used += line.length + 1
        }
        return lines
    }

    /** 有没有足够的东西可学：至少 [MIN_MESSAGES] 条有正文的消息（太少总结不出习惯）。 */
    fun hasEnough(messages: List<Message>): Boolean =
        messages.takeLast(MAX_MESSAGES).count { flatten(it.text).isNotEmpty() } >= MIN_MESSAGES

    /** 至少要几条消息才值得跑一次（一条「在吗」总结不出说话方式）。 */
    const val MIN_MESSAGES = 4

    /** 压成一行：换行/连续空白折成一个空格，去掉首尾空白。 */
    fun flatten(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    /** 单条截断：超长时保留前 120 字并补省略号（宁可截断也不整条丢掉）。 */
    private fun clip(text: String): String =
        if (text.length <= MAX_CHARS_PER_LINE) text else text.take(MAX_CHARS_PER_LINE - 1).trimEnd() + "…"
}

/**
 * 「应用学到的说话方式」时怎么改人格（纯函数，方便逐条断言）。
 *
 * 为什么不直接改 `PersonaEditorViewModel` 里的表单：应用是**立刻落库**的动作
 * （用户点了「应用」就该生效，而不是还要再按一次保存），所以它同时要做三件事：
 * 写 `speechStyleJson`、让人格进入新版本（版本历史里能看到这一版）、更新 `updatedAt`。
 * 这三件事放在这里，`PersonaRepository` 只负责「服务端先接受、再写本机」的事务。
 */
object SpeechStyleApply {

    /**
     * 应用之后的人格；[style] 为空（什么都没有）时返回 null —— 不写库、也不产生一个空版本。
     *
     * @param now 调用方给的时间（纯函数不读时钟）
     */
    fun next(
        persona: PersonaEntity,
        style: SpeechStyle,
        now: Long
    ): PersonaEntity? {
        // 先规整：模型/本地兜底都可能给「只有一个空格」的条目，那不是话风。
        val clean = SpeechStyle(
            tone = style.tone.trim(),
            phrases = style.phrases.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            habits = style.habits.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            avoid = style.avoid.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        )
        if (clean.isEmpty()) return null
        val json = SpeechStyleCodec.encode(clean)
        if (json.isBlank() || json == "{}") return null
        return persona.copy(
            speechStyleJson = json,
            version = persona.version + 1,
            updatedAt = now
        )
    }
}
