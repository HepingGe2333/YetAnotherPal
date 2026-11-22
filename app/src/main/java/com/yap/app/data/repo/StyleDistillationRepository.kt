package com.yap.app.data.repo

import com.yap.app.data.ai.SpeechStyleResult
import com.yap.app.data.ai.SpeechStyleSummarizer
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.StyleDistillation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从「他最近跟你说过的话」里学说话方式（人格编辑页的「从最近聊天学习话风」）。
 *
 * 三段：
 * 1. **取样**：当前人格那一段会话里最近 [StyleDistillation.MAX_MESSAGES] 条消息（user + assistant，
 *    只取有正文的），由 [StyleDistillation] 纯函数拼成「用户：…／他：…」的样本；
 * 2. **总结**：调用**现有的** [SpeechStyleSummarizer]（同一份规范 §6.1 提示词），
 *    模型可用就给 `llm`、不可用就本地兜底给 `heuristic`——界面据此标一句「本地粗略总结」；
 * 3. **应用**：由编辑页在用户点「应用」之后调 `PersonaRepository.applySpeechStyle`（写
 *    `speechStyleJson` + 记一个版本）。**取样与总结本身不写任何东西**：点「取消」就是什么都没发生。
 *
 * 隐私（与 [SpeechStyleSummarizer] 同一条约束）：样本只在内存里活一次请求的时间，
 * **不落库、不写日志、不进备份**。这个类里没有任何 `Log.` 调用，也没有把样本交给
 * 除 summarizer 以外的任何人。
 *
 * 为什么「失败」也是一个显式结果：没有消息、调用失败、模型给出的东西解析不出来，
 * 界面要说的话不一样（「还没有聊天记录」/「这次没学成，再试一次」），不能都吞成 null。
 */
@Singleton
class StyleDistillationRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val sessionDao: ChatSessionDao,
    private val summarizer: SpeechStyleSummarizer
) {

    /** 一次蒸馏的结论。 */
    sealed interface Distillation {
        /** 还没有可学的聊天记录（没消息，或只有表情/图片这种没有正文的消息）。 */
        data object NoMessages : Distillation

        /** 学到了：[style] 是预览内容，[source] 是 `llm` / `heuristic`（见 `SpeechStyleSource`）。 */
        data class Ready(
            val style: SpeechStyle,
            val source: String,
            val messageCount: Int
        ) : Distillation

        /** 取样读库失败，或者总结这一步抛了异常。 */
        data object Failed : Distillation
    }

    /**
     * 读最近聊天 → 拼样本 → 要一次总结。
     *
     * 一次只发**一个**请求（`SpeechStyleSummarizer` 内部可能失败后本地兜底，不会再发第二次）；
     * 样本用完即弃，不留在任何字段里。
     */
    suspend fun distill(personaId: String): Distillation {
        if (personaId.isBlank()) return Distillation.NoMessages
        val session = runCatching { sessionDao.latestForPersona(personaId) }.getOrNull()
            ?: return Distillation.NoMessages
        val recent = runCatching {
            // 多取几条：只发表情/图片的消息没有正文，要在拼样本时被跳掉。
            messageDao.recentBySession(session.id, StyleDistillation.MAX_MESSAGES * 2)
        }.getOrNull() ?: return Distillation.Failed
        return distillMessages(recent)
    }

    /**
     * 核心：**已经读到的**消息（库里是「最近在前」的顺序）→ 结论。
     *
     * 单独拆出来是为了能单测「样本组装 / 成功 / 本地兜底 / 没有消息」这四件事——
     * 它们都不需要 Room，也不该为了测试去起一个内存数据库（那会反过来验证 Room）。
     */
    suspend fun distillMessages(recent: List<MessageEntity>): Distillation {
        // 库里的顺序是「最近在前」；样本要按时间正序读，语气才连贯。
        val messages = recent.asReversed().mapNotNull(::toMessage)
        if (!StyleDistillation.hasEnough(messages)) return Distillation.NoMessages
        val sample = StyleDistillation.sample(messages)
        if (sample.isBlank()) return Distillation.NoMessages
        // 样本只在这里出现一次，用完即弃：不落库、不写日志、不缓存。
        val result = runCatching { summarizer.summarize(sample) }.getOrNull()
            ?: return Distillation.Failed
        return Distillation.Ready(
            style = result.style,
            source = result.source,
            messageCount = messages.count { it.text.isNotBlank() }
        )
    }

    /**
     * 一条消息能不能进样本：只要 user / assistant 的正文。
     *
     * 跳过：空正文（只有表情/图片的消息）、系统角色（状态行不是他说的话）、
     * 拉黑期间的消息（`recentBySession` 已经过滤，这里只是兜底）。
     */
    private fun toMessage(entity: MessageEntity): StyleDistillation.Message? {
        if (entity.content.isBlank()) return null
        val fromUser = when (entity.role) {
            MessageRole.USER -> true
            MessageRole.ASSISTANT -> false
            else -> return null
        }
        return StyleDistillation.Message(fromUser = fromUser, text = entity.content)
    }

    /** 只是给调用方一个「这句话是模型总结还是本地兜底」的判断，不参与落库。 */
    fun isHeuristic(result: SpeechStyleResult): Boolean = result.isHeuristic
}
