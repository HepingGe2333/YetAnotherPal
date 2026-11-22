package com.yap.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.yap.app.R
import com.yap.app.data.local.AgentTickDao
import com.yap.app.data.local.AgentTickEntity
import com.yap.app.data.local.ApiConfigDao
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageBlockDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageSearchRow
import com.yap.app.data.local.MessageSearchSql
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.SearchBlockRow
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.core.AgentCoreClient
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.NotificationDeepLink
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.AudioVideoKind
import com.yap.app.domain.AudioVideoText
import com.yap.app.domain.AgentReplyProtocol
import com.yap.app.domain.AutoMemory
import com.yap.app.domain.BlockPolicy
import com.yap.app.domain.CurrentMedia
import com.yap.app.domain.ExperienceRhythm
import com.yap.app.domain.MediaErrorTokens
import com.yap.app.domain.VideoFramePlan
import com.yap.app.domain.ChatNotificationPolicy
import com.yap.app.domain.DataEraser
import com.yap.app.domain.ExportJsonStream
import com.yap.app.domain.SessionGenerationRegistry
import com.yap.app.domain.ChatExport
import com.yap.app.domain.ExportFile
import com.yap.app.domain.GenerationEvent
import com.yap.app.domain.HistoryCompression
import com.yap.app.domain.HistoryTimestamps
import com.yap.app.domain.LlmLane
import com.yap.app.domain.ImageContextBudget
import com.yap.app.domain.InterruptedReply
import com.yap.app.domain.MessageOrder
import com.yap.app.domain.MoodRecovery
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.StickerPromptBlock
import com.yap.app.domain.MoodBehavior
import com.yap.app.domain.MoodLexicon
import com.yap.app.domain.ModelProfile
import com.yap.app.domain.MoodPrompt
import com.yap.app.domain.OpenSessionTracker
import com.yap.app.domain.PersonaActivityCodec
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.PersonaPromptLocalization
import com.yap.app.domain.PromptBuilder
import com.yap.app.domain.ContextAssembly
import com.yap.app.domain.PromptLanguage
import com.yap.app.domain.PromptTemplates
import com.yap.app.domain.ToolRounds
import com.yap.app.domain.PersonaSwitchNotice
import com.yap.app.domain.PersonaSwitchResult
import com.yap.app.domain.DomainErrorCode
import com.yap.app.domain.NetworkErrorKind
import com.yap.app.domain.BlockSearch
import com.yap.app.domain.RankInput
import com.yap.app.domain.ReasoningEffort
import com.yap.app.domain.SearchFilter
import com.yap.app.domain.SearchRanking
import com.yap.app.domain.SearchSnippet
import com.yap.app.domain.UserError
import com.yap.app.domain.UserErrorTokens
import com.yap.app.domain.SleepDecision
import com.yap.app.domain.SleepInput
import com.yap.app.domain.SleepPolicy
import com.yap.app.domain.CrisisClassifier
import com.yap.app.domain.CrisisPriority
import com.yap.app.domain.CrisisVerdict
import com.yap.app.domain.SafetyGuard
import com.yap.app.domain.SilentReason
import com.yap.app.domain.SpeechStyleCodec
import com.yap.app.domain.StaySilent
import com.yap.app.domain.ToolCatalog
import com.yap.app.domain.ToolProtocol
import com.yap.app.domain.ToolResult
import com.yap.app.domain.ToolTraceCodec
import com.yap.app.domain.ToolTraceItem
import com.yap.app.domain.WorldPresetCodec
import com.yap.app.network.ApiException
import com.yap.app.network.toUserError
import com.yap.app.network.userError
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.StreamEvent
import com.yap.app.network.WireAudio
import com.yap.app.network.WireMessage
import com.yap.app.network.inferenceParams
import com.yap.app.util.ChatNotifier
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「会话/人格」这一类命中：标题、人格名或最后一条预览里有关键词。
 *
 * 检索页把它和「消息」分两个页签展示：用户找「那段对话」和找「那句话」是两种意图，
 * 混在一列里就会互相挤占视线。
 */
@Serializable
data class SessionSearchHit(
    val sessionId: String,
    val personaId: String = "",
    val title: String,
    val personaName: String,
    val personaAvatarUri: String? = null,
    val personaAvatarSeed: Int = 0,
    val preview: String? = null,
    val lastMessageAt: Long? = null
)

/** 相邻块：编号可能不连续（块删空后留下空洞，规范 §1）。 */
data class BlocksAround(val older: Int?, val newer: Int?)

/** 从检索结果定位时要加载的块（`hasMoreOlder/Newer` 决定两端要不要给加载入口）。 */
data class BlockWindow(
    val blockIndex: Int,
    val hasMoreOlder: Boolean,
    val hasMoreNewer: Boolean
)

/**
 * 一次检索请求：关键词 + 过滤 + **键集游标**。
 *
 * 过滤在 SQL 与内存里各做一次（[com.yap.app.domain.SearchFilter]），
 * 这样即使将来 SQL 变了，也不会因为「过滤漏在某一层」而把别人的消息露出来。
 *
 * 0.12 起没有 `offset`：翻页用上一页最后一条的
 * [com.yap.app.domain.BlockSearch.Cursor]，所以翻页期间新增消息不会让结果重复或乱序，
 * 也不需要「候选上限」。
 */
data class SearchQuery(
    val text: String,
    val personaId: String? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int = SEARCH_PAGE_SIZE,
    val cursor: BlockSearch.Cursor? = null
)

/** 检索页的一页消息结果（分页单位：[SEARCH_PAGE_SIZE] 条，键集游标翻页）。 */
data class SearchPage(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    /** 全部扫完之后的命中总数——**不受页大小、也没有 500 条上限**。 */
    val total: Int = 0,
    val hasMore: Boolean = false,
    /** 下一页的游标；没有下一页时为 null。 */
    val nextCursor: BlockSearch.Cursor? = null
)

/**
 * 一条消息命中。
 *
 * [snippet] / [highlights] 由 [SearchSnippet] 按 `spec/search/README.md` 生成，
 * 界面只要把高亮区间换成 SpanStyle，不需要自己再截一遍（以前界面自己截，
 * 高亮偏移和截断逻辑各写一份，中文多字节时经常错位）。
 */
data class SearchHit(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val personaId: String,
    val personaName: String,
    val personaAvatarUri: String? = null,
    val personaAvatarSeed: Int = 0,
    val snippet: String,
    val highlights: List<IntRange>,
    val createdAt: Long,
    val role: String
)

/** 检索页每页条数：30 条大约 2-3 屏，滚到底再要下一页。 */
const val SEARCH_PAGE_SIZE = 30

@Serializable
private data class ExportMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String
)

@Serializable
private data class ExportSession(
    val sessionId: String,
    val title: String,
    val personaName: String,
    val personaCategory: String,
    val personaVersion: Int,
    val apiConfigName: String? = null,
    val model: String,
    val createdAt: Long,
    val messages: List<ExportMessage>
)

@Serializable
private data class ExportBundle(
    val format: String = "yap.chats",
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val sessions: List<ExportSession>
)

@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val blockDao: MessageBlockDao,
    /** 块元数据的唯一写入入口（规范 §2）。 */
    private val blockWriter: MessageBlockWriter,
    private val personaDao: PersonaDao,
    private val apiConfigDao: ApiConfigDao,
    private val database: YapDatabase,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val registry: AiProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val openSessionTracker: OpenSessionTracker,
    private val toolExecutor: ToolExecutor,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val presenceRepository: PresenceRepository,
    /** 0.14 心理活动（活跃窗口每 3 分钟/3 轮一次）；异步、失败不阻塞回复。 */
    private val mentalActivityRepository: MentalActivityRepository,
    /** 0.14/0.15 会话备忘录（短期 5 轮 / 长期 15–40 轮 / 主题总结）；异步、失败保留旧摘要。 */
    private val sessionMemoryRepository: SessionMemoryRepository,
    /** 0.15：`memorize` 工具「想起来的事」留到下一轮上下文里注入一次。 */
    private val memorizeRepository: MemorizeRepository,
    private val historyCompressor: HistoryCompressor,
    /** 人格进化评估（每 20 条用户消息或 24 小时一次，结果只进待审表）。 */
    private val personaEvolution: PersonaEvolutionRepository,
    /**
     * 自动情绪判定（0.15：每条用户消息后一次，`spec/mood/README.md` §2）。
     *
     * 异步、不 await：判定再慢也晚不了一毫秒回话，失败按时间衰减、用户看不到。
     */
    private val moodJudgement: MoodJudgementCoordinator,
    /**
     * 0.15 危机两段式判定：词表命中后再问一次独立模型。
     *
     * 用 `start()` 先在后台发起、真的需要结论时再 await：多问一次模型不会挡在第一句回复前面；
     * 超时/失败按危机处理（[CrisisClassifier] 内部兜底）。
     */
    private val crisisClassifier: CrisisClassifier,
    private val sessionGeneration: SessionGenerationRegistry,
    private val dataEraser: DataEraser,
    /** 删图片文件之前的引用守卫（0.12）：共享文件还有别的消息/头像引用时不删。 */
    private val mediaJanitor: MediaJanitor,
    private val agentTickDao: AgentTickDao,
    /** agent 决策（睡眠判定）：默认进程内，可切 agentd；失败会自动回退，见 [AgentCoreClient]。 */
    private val agentCore: AgentCoreClient,
    @ApplicationContext private val appContext: Context,
    private val chatNotifier: ChatNotifier,
    private val foregroundTracker: com.yap.app.util.AppForegroundTracker,
    /** 服务端模式开关/配置（M3）：默认关闭，本地模式路径完全不受影响。 */
    private val serverMode: com.yap.app.data.remote.ServerModeRepository,
    /** 服务端发送/分页编排（M3）；不可用时所有方法都返回 failure，不会碰网络。 */
    private val serverChat: com.yap.app.data.remote.ServerChatGateway,
    /** 服务端模式的图片上传（M5）：先压缩再上传，附件里落 `/v1/media/<id>`。 */
    private val serverMediaSender: com.yap.app.data.remote.ServerChatMediaSender,
    /** 表情包（0.13）：导入/收藏/发送，以及服务端模式下的 `kind=STICKER` 上传。 */
    private val stickerRepository: com.yap.app.data.repo.StickerRepository,
    /** 语音/视频（0.13）：这一轮的媒体要真的以多模态 parts 发给模型。 */
    private val audioVideoRepository: AudioVideoRepository,
    private val json: Json
) {
    /**
     * 正在跑的生成任务（按 sessionId）。
     *
     * 删除 / 清空上下文时按会话取消它：`NonCancellable` 的收尾写入虽然不响应取消，
     * 但配合世代号与存在性校验（[writeStillValid]）就不会写进已删会话。
     * 这里只是「尽快停下来」，正确性由世代号保证。
     */
    private val activeGenerations = ConcurrentHashMap<String, Job>()

    fun observeSessions(): Flow<List<ChatSessionEntity>> = sessionDao.observeAll()
    fun observeSession(id: String): Flow<ChatSessionEntity?> = sessionDao.observeById(id)

    // ---------------------------------------------------------------- 区块分页（v11）
    //
    // 规范 `spec/messages-blocks/README.md` §3：一页 = 一块；首屏只读最新块，滚到顶读
    // `blockIndex - 1`，滚到底（且不在最新块）读 `blockIndex + 1`。永远按
    // `(sessionId, blockIndex)` 定位，不用 offset，所以「一页读多少行」与会话长度无关。

    /** 「读某块」：返回这一块的消息，按 `(createdAt, id)` 升序。 */
    suspend fun blockOf(sessionId: String, blockIndex: Int): List<MessageEntity> =
        messageDao.blockOf(sessionId, blockIndex)

    /**
     * 聊天页观察「已加载的那几块」。
     *
     * 用可观察查询而不是快照：块内编辑/删除、以及块里追加的新消息都要反映到列表里。
     * 空列表直接给空流（Room 对 `IN ()` 的行为不保证）。
     */
    fun observeBlocks(sessionId: String, blockIndices: List<Int>): Flow<List<MessageEntity>> {
        val distinct = blockIndices.distinct().sorted()
        if (distinct.isEmpty()) return flowOf(emptyList())
        return messageDao.observeBlocks(sessionId, distinct)
    }

    /** 会话里编号最大的块（空会话为 null）。 */
    suspend fun lastBlockIndex(sessionId: String): Int? = blockDao.lastBlockIndex(sessionId)

    /** 会话里编号最小的块（空会话为 null）。块编号有空洞时它不等于 0。 */
    suspend fun firstBlockIndex(sessionId: String): Int? = blockDao.firstBlockIndex(sessionId)

    /**
     * 可观察的「最新块」：聊天页靠它在「会话从空变成有一块」「最新块被删空」时重新定位。
     */
    fun observeLastBlockIndex(sessionId: String): Flow<Int?> =
        blockDao.observeLastBlock(sessionId).map { it.firstOrNull() }

    /** 相邻块（规范 §3 的「上一块 / 下一块」；编号有空洞时不是 ±1）。 */
    suspend fun blocksAround(sessionId: String, blockIndex: Int): BlocksAround = BlocksAround(
        older = blockDao.blockBefore(sessionId, blockIndex),
        newer = blockDao.blockAfter(sessionId, blockIndex)
    )

    /**
     * 从检索结果跳到某条消息：定位到它所在的那一块（不再按「条数窗口」算）。
     *
     * 目标消息的 `blockIndex` 是落库的，所以一次查询（`getById`）+ 两次相邻块查询就够了；
     * 块内最多 BLOCK_SIZE 条，定位的读取量与会话长度无关。
     */
    suspend fun blockContainingMessage(sessionId: String, messageId: String): BlockWindow? {
        val target = messageDao.getById(messageId) ?: return null
        if (target.sessionId != sessionId) return null
        val around = blocksAround(sessionId, target.blockIndex)
        return BlockWindow(
            blockIndex = target.blockIndex,
            hasMoreOlder = around.older != null,
            hasMoreNewer = around.newer != null
        )
    }

    suspend fun getSession(id: String): ChatSessionEntity? = sessionDao.getById(id)
    suspend fun getPersona(id: String): PersonaEntity? = personaDao.getById(id)
    /**
     * 这段会话对应的人格现在是否处于拉黑中。
     *
     * 读路径顺手把过期拉黑清掉；UI 在发送后调用它决定还要不要启动生成。
     */
    suspend fun isBlocked(sessionId: String, now: Long = System.currentTimeMillis()): Boolean {
        val session = runCatching { sessionDao.getById(sessionId) }.getOrNull() ?: return false
        runCatching { personaDao.clearExpiredBlocks(now) }
        val persona = runCatching { personaDao.getById(session.personaId) }.getOrNull() ?: return false
        return BlockPolicy.isBlocked(persona.blockedUntil, now)
    }

    suspend fun getConfig(id: String?): com.yap.app.data.local.ApiConfigEntity? =
        id?.let { apiConfigDao.getById(it) }

    /** 现在是不是服务端模式（地址合法且开关打开）。 */
    fun isServerMode(): Boolean = serverMode.isEnabled()

    /**
     * 服务端模式的建会话：先 `POST /v1/sessions`，成功后把服务端会话落本地缓存。
     *
     * 为什么失败不回退到本地建会话：服务端 id 与本地 id 是两套世界，本地偷偷建一条会让
     * 「发消息」落到一个服务端不存在的会话上。失败原样返回，调用方（首页/启动补会话）
     * 保持已有的本地缓存可读，等下次同步恢复。
     */
    suspend fun createServerSession(personaId: String, title: String? = null): Result<ChatSessionEntity> =
        runCatching {
            val created = serverChat.createSession(personaId, title).getOrThrow()
            val server = created.session
            if (server.id.isBlank()) userError(DomainErrorCode.SESSION_NOT_FOUND)
            // 一人一段：本机可能已有同一人格的会话（id 与服务端不同）。复用本地那一行，
            // 把服务端 id 记为映射，绝不能让 REPLACE 把本地历史删掉。
            val local = sessionDao.getById(server.id) ?: sessionDao.latestForPersona(personaId)
            val merged = com.yap.app.data.remote.ServerMergePlan.session(
                local = local,
                server = server
            )
            if (local == null) sessionDao.insert(merged) else sessionDao.update(merged)
            // 本地一人一段的唯一索引意味着本地 id 可能和服务端 id 不同：记下映射，
            // 之后发送/分页/事件都用本地 id 进、服务端 id 出。
            serverChat.registerSessionMapping(merged.id, server.id)
            sessionDao.getById(merged.id) ?: merged
        }

    /**
     * 用户点选一张表情直接发出去（`spec/stickers/README.md`「点选即发送，不进入文字草稿」）。
     *
     * 本地模式：写一条 `attachmentsJson = [{kind=STICKER, mime=image/gif}]` 的用户消息；
     * 服务端模式：先把 GIF 以 `kind=STICKER` 传到 `/v1/media`，消息里落服务端媒体引用
     * （本地 `file://` 路径绝不发给服务端）。
     *
     * 失败时把原因原样返回，界面只提示一句「表情没发出去」——表情不是文字，不该往草稿里塞东西。
     */
    suspend fun sendUserSticker(sessionId: String, stickerId: String): Result<Unit> = runCatching {
        val sticker = stickerRepository.get(stickerId)
            ?: userError(DomainErrorCode.MESSAGE_EMPTY)
        val attachment = stickerRepository.attachmentOf(sticker)
        if (isServerMode()) {
            val result = sendServerMessage(sessionId, "", listOf(attachment))
            if (!result.accepted) throw (result.error ?: userError(DomainErrorCode.TOOL_FAILED))
            return@runCatching
        }
        sendUserMessage(sessionId, "", listOf(attachment)).getOrThrow()
    }

    /**
     * 服务端模式发送（SSE）。UI 只消费 [onEvent]，消息落库由 gateway/sink 完成。
     *
     * M5 起图片**先压缩、再上传**，然后把 `/v1/media/<id>` 放进 `attachments`：
     * 本地 `file://` 路径绝不发给服务端（服务端拿不到这台机器的文件，也不该拿到系统路径）。
     *
     * 只有图片、没有文字时发一个不可见字符：服务端要求 `content` 非空，而界面上这条消息
     * 本来就只显示图——不能让用户看到「图片」两个字，也不能因为空正文把图片消息挡住。
     */
    suspend fun sendServerMessage(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList(),
        onEvent: (com.yap.app.data.remote.ServerSendEvent) -> Unit = {}
    ): com.yap.app.data.remote.ServerSendResult {
        val content = text.trim()
        val media = selectAttachments(attachments)
        if (content.isEmpty() && media.isEmpty()) {
            return com.yap.app.data.remote.ServerSendResult(
                accepted = false,
                done = false,
                error = userError(DomainErrorCode.MESSAGE_EMPTY)
            )
        }
        if (content.length > 32_000) {
            return com.yap.app.data.remote.ServerSendResult(
                accepted = false,
                done = false,
                error = userError(DomainErrorCode.MESSAGE_TOO_LONG)
            )
        }
        val personaId = runCatching { sessionDao.getById(sessionId)?.personaId }.getOrNull()
        // 表情与普通图片走**两条**上传通道：表情已经是压好的 GIF（kind=STICKER，不再重编码），
        // 聊天图片要先压成 JPEG（kind=CHAT_IMAGE）。顺序按原附件顺序拼回去，消息里不会错位。
        val mediaRefs = uploadServerAttachments(media, personaId).getOrElse { error ->
            return com.yap.app.data.remote.ServerSendResult(
                accepted = false,
                done = false,
                error = error
            )
        }
        // 服务端只回附件引用（/v1/media/<id>），不带 mime/kind/duration；语音/视频的这部分
        // 元数据只有客户端知道。等用户消息落库后补写一次，否则服务端模式里「语音」会渲染成图片。
        var serverUserMessageId: String? = null
        val result = serverChat.send(
            sessionId = sessionId,
            content = content.ifEmpty { IMAGE_ONLY_CONTENT },
            attachments = mediaRefs,
            onEvent = { event ->
                when (event) {
                    is com.yap.app.data.remote.ServerSendEvent.Accepted ->
                        serverUserMessageId = event.userMessageId

                    else -> Unit
                }
                onEvent(event)
            }
        )
        if (result.accepted) {
            runCatching {
                patchServerAttachments(
                    messageId = serverUserMessageId,
                    mediaRefs = mediaRefs,
                    local = media
                )
            }
        }
        // 0.13 起：服务端模式下**情绪判定与人格进化由服务端自己做**（它独立运行，有自己的
        // 节流与模型），客户端不再在回复后触发评估——否则同一条回复会被两端各判一次。
        // 客户端只保留「手动评估/接受/拒绝」这类用户显式动作（人格编辑页）。
        return result
    }

    /**
     * 服务端模式：把「引用 + 本地元数据」写回刚同步下来的用户消息。
     *
     * 只补语音/视频/表情这几类**靠 mime 认不出来**的附件；普通图片的默认 mime 已经够用。
     * 任何一步失败都只是「这条消息的媒体类型丢了」，不该让已经发出去的消息变成失败。
     */
    private suspend fun patchServerAttachments(
        messageId: String?,
        mediaRefs: List<String>,
        local: List<MessageAttachment>
    ) {
        val id = messageId?.takeIf { it.isNotBlank() } ?: return
        if (mediaRefs.isEmpty() || local.isEmpty()) return
        val metaByRef = mutableMapOf<String, MessageAttachment>()
        local.forEachIndexed { index, attachment ->
            mediaRefs.getOrNull(index)?.let { ref -> metaByRef[ref] = attachment }
        }
        if (metaByRef.isEmpty()) return
        val existing = messageDao.getById(id) ?: return
        val current = AttachmentsCodec.decode(existing.attachmentsJson)
        if (current.isEmpty()) return
        val merged = current.map { attachment ->
            // 0.13：附件同步时已经落成本地 `file://`，此时 uri 不再是服务端引用；
            // 按 `serverMediaId` 再匹配一次，否则语音/视频的 mime/kind/duration 会在这一轮丢掉。
            val meta = metaByRef[attachment.uri]
                ?: attachment.serverMediaId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { id -> metaByRef[com.yap.app.data.remote.ServerMediaRef.PREFIX + id] }
                ?: return@map attachment
            attachment.copy(
                mime = meta.mime,
                kind = meta.canonicalKind,
                durationMs = meta.durationMs,
                serverMediaId = attachment.serverMediaId
                    ?: com.yap.app.data.remote.ServerMediaRef.idOf(meta.uri),
                // 0.15：服务端返回/下载校验过的内容哈希跟着附件走。
                sha256 = meta.sha256 ?: attachment.sha256
            )
        }
        messageDao.updateAttachments(id, AttachmentsCodec.encode(merged))
    }

    /** 附件已有服务端身份、且本机没有可读文件时可直接复用的引用（否则 null）。 */
    private fun existingServerRef(attachment: MessageAttachment): String? {
        val id = attachment.serverMediaId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val local = runCatching { MediaStorage.fileOf(attachment.uri) }.getOrNull()
        if (local != null && local.isFile && local.length() > 0L) return null
        return com.yap.app.data.remote.ServerMediaRef.PREFIX + id
    }

    /**
     * 服务端模式的附件上传：按原顺序逐张上传，表情走 `kind=STICKER`、图片走 `kind=CHAT_IMAGE`。
     *
     * 逐张上传（而不是「先批量图片、再批量表情」）是为了**保住顺序**：一条消息里
     * 「一张图 + 一张表情」的先后在气泡里看得见，拼错顺序不会报错，只会让人以为发错了。
     * 服务端对同内容媒体本来就做去重，多一次请求的代价可以接受。
     */
    private suspend fun uploadServerAttachments(
        attachments: List<MessageAttachment>,
        personaId: String?
    ): Result<List<String>> {
        if (attachments.isEmpty()) return Result.success(emptyList())
        val refs = mutableListOf<String>()
        for (attachment in attachments) {
            // 0.13：只有 `serverMediaId`、本机没有文件（别的设备发来的/本地文件被清理）时，
            // 直接把服务端引用放回消息里——服务端已经有这份字节，不必先下载再上传一遍。
            val existingRef = existingServerRef(attachment)
            if (existingRef != null) {
                refs += existingRef
                continue
            }
            // 0.13：语音/视频**不压缩、不转码**，按 kind=AUDIO|VIDEO 原样上传。
            if (attachment.isAudioOrVideo) {
                val uploaded = serverMediaSender.uploadVoiceVideo(listOf(attachment), personaId).getOrElse {
                    return Result.failure(it)
                }
                refs += uploaded
            } else if (attachment.isSticker) {
                val api = serverMode.api() ?: return Result.failure(
                    com.yap.app.data.remote.ServerApiException("server mode is not available", code = "server_unavailable")
                )
                val uploader = com.yap.app.data.remote.ServerStickerUploader(
                    api = api,
                    readBytes = { uri ->
                        com.yap.app.data.remote.StickerFileReader.read(uri, MediaStorage.stickerDirectory(appContext))
                    }
                )
                val uploaded = uploader.uploadStickers(listOf(attachment), personaId).getOrElse {
                    return Result.failure(it)
                }
                refs += uploaded
            } else {
                val uploaded = serverMediaSender.uploadChatImages(listOf(attachment), personaId).getOrElse {
                    return Result.failure(it)
                }
                refs += uploaded
            }
        }
        return Result.success(refs)
    }

    /** 「没有文字的消息」在列表/标题里显示什么：表情/语音/视频/图片各说一句。 */
    private fun attachmentPreview(attachments: List<MessageAttachment>): String = when {
        attachments.any { it.isSticker } -> appContext.getString(R.string.chat_preview_sticker)
        attachments.any { it.isAudio } -> appContext.getString(R.string.chat_preview_audio)
        attachments.any { it.isVideo } -> appContext.getString(R.string.chat_preview_video)
        attachments.isNotEmpty() -> appContext.getString(R.string.chat_preview_image)
        else -> ""
    }

    /**
     * 一条消息实际要带的附件：图片最多 [MAX_IMAGES_PER_MESSAGE] 张，语音与视频各最多一段。
     *
     * 顺序保持用户选择的顺序（气泡里「先语音后视频」看得见）；多出来的直接丢掉，
     * 对应 UI 的选择上限（聊天图片 4 张、语音和视频各一次）。
     */
    private fun selectAttachments(attachments: List<MessageAttachment>): List<MessageAttachment> =
        com.yap.app.domain.AttachmentSelection.select(attachments, MAX_IMAGES_PER_MESSAGE)

    /** 服务端模式打开会话：拉最新一块写进 Room（之后的窗口逻辑仍走本地块表）。 */
    suspend fun openServerSession(sessionId: String): Result<Unit> =
        serverChat.openSession(sessionId).map { }

    /** 服务端模式前翻/后翻：拉一块写进 Room，再让现有窗口按块编号定位。 */
    suspend fun loadServerPage(
        sessionId: String,
        beforeBlock: Int? = null,
        afterBlock: Int? = null
    ): Result<Unit> = serverChat.loadPage(sessionId, beforeBlock, afterBlock).map { }

    /**
     * 取这个人格的那一段会话。
     *
     * 1.9 一人一段：已经有就返回已有，没有才建。调用方不再需要区分「新建」与「继续」，
     * 界面上的「新建对话」也就没有存在意义了。
     */
    suspend fun createSession(
        personaId: String,
        apiConfigId: String? = null,
        title: String? = null
    ): Result<ChatSessionEntity> = runCatching {
        sessionDao.latestForPersona(personaId)?.let { return@runCatching it }
        // 服务端模式：会话必须在服务端存在（id 由服务端分配），不能先在本机造一条。
        if (serverMode.isEnabled()) {
            return@runCatching createServerSession(personaId, title).getOrThrow()
        }
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val configId = apiConfigId ?: persona.defaultApiConfigId
        val config = configId?.let { apiConfigDao.getById(it) }
        val model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config?.model.orEmpty()
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            personaId = persona.id,
            apiConfigId = config?.id,
            model = model,
            // 会话标题会落库、也会进搜索与模型上下文：写入时按当前界面语言取，老库里的中文原样显示。
            title = title?.trim()?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.chat_session_default_title, persona.name),
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            pinned = false,
            createdAt = now,
            updatedAt = now,
            lastPersonaVersion = persona.version,
            lastMemoryExtractedCount = 0
        )
        // 幂等 upsert：`index_chat_sessions_personaId_unique` 下，两个调用方（首页点联系人 /
        // 启动补会话 / 主动消息）同时发现「还没有会话」时，只有一方插得进去。
        // 以前走 ABORT 会抛 SQLiteConstraintException，首页直接报「打开失败」（审计 P2-6）；
        // 走 REPLACE 更糟：会把对方刚建的那一行整行删掉再插（消息成孤儿）。
        // IGNORE + 回读：撞了就说明别人已经建好了，直接返回那一行。
        val inserted = sessionDao.insertIgnore(session)
        if (inserted != -1L) return@runCatching session
        sessionDao.latestForPersona(personaId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
    }

    suspend fun getOrCreateForPersona(personaId: String): ChatSessionEntity =
        sessionDao.latestForPersona(personaId) ?: createSession(personaId).getOrThrow()

    suspend fun switchToPersona(personaId: String): PersonaSwitchResult {
        val persona = personaDao.getById(personaId)
            ?: return PersonaSwitchResult(false, null, PersonaSwitchNotice.PERSONA_NOT_FOUND)
        val session = runCatching { getOrCreateForPersona(personaId) }.getOrElse {
            return PersonaSwitchResult(false, null, PersonaSwitchNotice.SESSION_CREATE_FAILED)
        }
        settingsRepository.setActivePersonaId(personaId)
        val config = getConfig(session.apiConfigId)
        return if (config == null) {
            PersonaSwitchResult(true, session, PersonaSwitchNotice.API_NOT_BOUND)
        } else {
            PersonaSwitchResult(true, session)
        }
    }

    suspend fun sendUserMessage(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ): Result<Unit> = runCatching {
        val content = text.trim()
        // 0.13：图片、语音、视频都能作为「没有正文的一条消息」发出去；一条消息最多带
        // 一段语音 + 一条视频（再多没有意义，只会把请求撑大）。
        val media = selectAttachments(attachments)
        if (content.isBlank() && media.isEmpty()) userError(DomainErrorCode.MESSAGE_EMPTY)
        if (content.length > 32_000) userError(DomainErrorCode.MESSAGE_TOO_LONG)
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        if (session.apiConfigId == null) userError(DomainErrorCode.API_CONFIG_REQUIRED)
        apiConfigDao.getById(session.apiConfigId) ?: userError(DomainErrorCode.API_CONFIG_GONE)
        val now = nextTimestamp(sessionId)
        runCatching { personaDao.clearExpiredBlocks(now) }
        val persona = personaDao.getById(session.personaId)
        val blocked = BlockPolicy.isBlocked(persona?.blockedUntil, now)
        val isFirstUserMessage = messageDao.countUserMessages(sessionId) == 0
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = session.personaId,
            role = MessageRole.USER,
            content = content,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            model = null,
            edited = false,
            attachmentsJson = AttachmentsCodec.encode(media),
            blocked = blocked
        )
        database.withTransaction {
            // 写入统一走块维护（规范 §2 Append）：落进最后一块，满了才开新块。
            blockWriter.append(message)
            // 消息预览同样是落库数据：图片这类「没有文字的消息」用资源生成，不写死中文。
            val preview = content.replace('\n', ' ').trim().take(90)
                .ifBlank { attachmentPreview(media) }
            val title = if (isFirstUserMessage) {
                autoTitle(content.ifBlank { attachmentPreview(media) })
            } else {
                session.title
            }
            sessionDao.update(
                session.copy(
                    title = title,
                    lastMessagePreview = preview,
                    lastMessageAt = now,
                    unreadCount = 0,
                    updatedAt = now
                )
            )
        }
        // 0.13 服务端模式：情绪判定与人格进化都由服务端独立负责，客户端不再触发。
        // 这里再挡一次是必要的：本地发送路径在服务端模式下通常不会被走到，但导入/补数据等
        // 路径仍可能落一条用户消息，不能让它们把「自动情绪/进化」带起来（=双份判定）。
        if (!isServerMode() && !blocked) {
            // 人格进化：条件到了（20 条用户消息 / 隔了 24 小时）就在后台回顾一次，
            // 结果只写进「待审的变化」，不会直接改他的人设，也不会拖慢这条消息的发送。
            runCatching { personaEvolution.ensureAsync(session.personaId, now) }
            // 自动情绪判定：条件到了（4 条用户消息 / 24 小时）就在后台判一次，
            // 结果写进多维心情、下一轮提示词里生效；没有 API 配置或失败时按时间衰减。
            runCatching { moodJudgement.ensureAsync(session.personaId, now) }
        }
    }

    fun generateAssistant(sessionId: String): Flow<GenerationEvent> = flow {
        // 这一轮属于哪个世代：期间若有清空 / 删除 / 整库替换，收尾写入就必须丢弃。
        val generation = sessionGeneration.current(sessionId)
        val session = sessionDao.getById(sessionId)
            ?: run {
                emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.SESSION_NOT_FOUND), null))
                return@flow
            }
        var persona = personaDao.getById(session.personaId)
            ?: run {
                emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.PERSONA_NOT_FOUND), null))
                return@flow
            }
        // 0.14：拉黑期间模型不看、不回；用户消息仍然已经落库并标了 blocked。
        runCatching { personaDao.clearExpiredBlocks(System.currentTimeMillis()) }
        persona = personaDao.getById(session.personaId) ?: persona
        if (BlockPolicy.isBlocked(persona.blockedUntil, System.currentTimeMillis())) {
            emit(GenerationEvent.Silent(SilentReason.BLOCKED))
            return@flow
        }
        val configId = session.apiConfigId
            ?: run {
                emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.API_CONFIG_REQUIRED), null))
                return@flow
            }
        // 安全 P2-6：配置在、但 Key 解不出来（Keystore 被系统清掉 / 换机迁移异常）时说清楚，
        // 不再让请求带着空 Authorization 发出去、最后变成一句「API Key 无效（401）」。
        val resolved = try {
            apiConfigRepository.resolve(configId)
        } catch (error: Exception) {
            emit(GenerationEvent.Failed(error.toUserError(), null))
            return@flow
        } ?: run {
            emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.API_CONFIG_GONE), null))
            return@flow
        }
        val config = resolved.config
        // 只读最近 N 条：长会话不再每次把整段历史读进内存（更早的部分已经收成摘要了）。
        val recentHistory = messageDao.recentBySession(sessionId, CONTEXT_RECENT_LIMIT)
        // 这一轮带的语音/视频：只在这一轮真的发出去（历史里不重复 base64）。
        // 记录「当前在飞的模态」，是为了 provider 4xx 时能给一句准确的话（听不了/看不了）。
        var mediaKindInFlight: AudioVideoKind? = recentHistory
            .firstOrNull { it.role == MessageRole.USER }
            ?.let { AttachmentsCodec.decode(it.attachmentsJson) }
            ?.firstNotNullOfOrNull { it.mediaKind }
        if (recentHistory.none { it.role == MessageRole.USER }) {
            emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.NOTHING_TO_REPLY), null))
            return@flow
        }
        // 这一轮「对方说了什么」：安全兜底与高置信自动记忆都要看用户原话（最近一条 user 消息）。
        // recentBySession 按 createdAt 倒序返回，所以最近一条 user 消息就是 firstOrNull。
        val latestUserMessage = recentHistory.firstOrNull { it.role == MessageRole.USER }
        val latestUserText = latestUserMessage?.content.orEmpty()
        // 0.15 危机两段式判定（见 CrisisClassifier）：
        // 第一段是词表（纯函数、零网络）；命中之后才在后台问一次独立模型（低温度、5s 预算）。
        // 这里只**发起**请求、不等待：拼提示词与首轮流式的时间足够它跑完，真正需要结论的地方
        // （拦「不回」的工具、决定要不要补安全提示）在下面对 `await()`。词表没命中时
        // `start()` 当场返回「不是危机」，一次请求都不会发，正常对话一个字节都不变。
        val crisisJudgement: Deferred<CrisisVerdict> =
            crisisClassifier.start(latestUserMessage?.id.orEmpty(), latestUserText)
        val now0 = System.currentTimeMillis()
        // 睡眠 / 免打扰 / 唤醒阈值（规格 §六）。判定是纯函数，这里只负责拿数据、落痕迹。
        val sleepDecision = evaluateSleep(persona, sessionId, now0)
        // asleep && !woken 的等价写法（决策数据类只暴露 wake / moodRelaxed）：
        if (!sleepDecision.wake && !sleepDecision.moodRelaxed) {
            // 他确实看到了（已读），但这会儿不会回。界面只显示「已读」，不显示任何解释。
            // 免打扰是例外：连「已读」都不留 —— 那是「他不想被打扰」的一部分（规格 §6.2）。
            if (sleepDecision.marksRead) {
                runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
            }
            // 0.12 续 7：告诉界面「这一轮是被睡眠吞掉的」。聊天页据此在输入框上方显示一行
            // 「他睡着了（08:00 醒）；连着发几条可以叫醒他」——免打扰不显示（叫多少条都不会醒）。
            emit(
                GenerationEvent.Silent(
                    if (sleepDecision.dnd) SilentReason.DND else SilentReason.ASLEEP
                )
            )
            return@flow
        }
        if (sleepDecision.moodRelaxed) {
            // 被连着叫醒：心情先缓一缓，并在他自己的思考留痕里写一句，用户看不见细节。
            runCatching {
                val mood = moodRepository.current(persona.id, now0)
                val recovered = MoodRecovery.afterWake(mood, now0)
                // 走仓库而不是直接写 DAO：醒来时标签可能变了，三个维度要跟着重新折算，
                // 否则提示词里会出现「平静（愉快度 -2）」这种自相矛盾的组合。
                moodRepository.setMood(
                    personaId = persona.id,
                    label = recovered.label,
                    intensity = recovered.intensity,
                    reason = recovered.reason,
                    now = now0
                )
                agentTickDao.insert(
                    AgentTickEntity(
                        id = UUID.randomUUID().toString(),
                        personaId = persona.id,
                        at = now0,
                        thought = "被对方连着几条消息叫醒了",
                        actionJson = """{"actions":[]}""",
                        outcome = "被你叫醒了，缓了缓"
                    )
                )
            }
        }
        // 模型看到这些消息的那一刻就是「已读」；在读历史之前写，保证这一轮一定带上这次看到的消息。
        runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
        val wireMessages = buildContext(session, persona, config, recentHistory)
        val adapter = registry.adapterFor(config.provider)
        // 连发与首条延迟都要看此刻的心情：低唤醒更慢、高唤醒更快（规范 §5）。
        val moodForTiming = runCatching { moodRepository.reading(persona.id) }.getOrNull()
        val lowEnergy = moodForTiming?.let {
            it.arousal <= 1 || MoodLexicon.isLowEnergy(it.label)
        } ?: false
        emit(GenerationEvent.Started)
        val traces = mutableListOf<ToolTraceItem>()
        // `sticker` / 媒体工具的附件属于最后一条助手消息；连发时先攒着。
        val toolAttachments = mutableListOf<MessageAttachment>()
        var completed = false
        var shownText = ""
        val sentMessages = mutableListOf<MessageEntity>()
        var lastSaved: MessageEntity? = null
        var messagesThisRound = 0
        // 首条消息的「正在输入」只付一次；连发之间改用更短的 gap（规范 §4/§5）。
        // 0.14.1 §8：服务端权威模式下**延迟由服务端在入队时决定**，客户端一行都不加。
        // 把标记预置成 true，等于跳过下面所有 ExperienceRhythm 延迟（不到达/不连发）。
        var typingDelayApplied = isServerMode()
        try {
            val conversation = wireMessages.toMutableList()
            val executed = mutableSetOf<String>()
            var finalBody = ""
            var round = 0
            // 这一轮最多连着用几轮工具：默认 5，模型可以在参数里要更多，但硬顶 15（共享契约）。
            var maxToolRounds = ToolRounds.DEFAULT
            var deferred = false
            // `stay_silent`：这一轮只标已读、不落任何 assistant 消息（与 defer 的区别是「什么都不留」）。
            var silenced = false
            // 0.15 危机优先：危机轮里模型调了 `stay_silent` / `defer_reply`——两个工具都被拦下
            // （不执行、不写念头、不排窗口、不涨沉默计数），这一轮必须以一条可见消息结束。
            var crisisForcedReply = false
            // 0.15：这一轮模型有没有自己调用 `remember`（调用过就不再自动补记忆）。
            var rememberCalled = false
            // 0.16 思考强度（`spec/tool-call-agents/README.md` §2）：默认 low；
            // 这一轮调用过 `think` 之后，后续请求升到 high（think 结果回灌后才值得多想）。
            // 变量每轮 generateAssistant 重新初始化，所以「结束后恢复 low」是自然结果，不需要收尾复位。
            var reasoningEffort = ReasoningEffort.DEFAULT
            var thinkCalledThisTurn = false
            while (true) {
                val buffer = StringBuilder()
                adapter.streamChat(
                    config = config,
                    apiKey = resolved.apiKey,
                    model = session.model.ifBlank { config.model },
                    messages = conversation,
                    params = config.inferenceParams(reasoningEffort = reasoningEffort),
                    kind = com.yap.app.domain.UsageKind.CHAT,
                    lane = LlmLane.CHAT,
                    sessionId = sessionId,
                    personaId = persona.id
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Text -> {
                            buffer.append(event.value)
                            // 0.16 完全工具调用化：流式 token **只**当「正在输入」的输入指示，
                            // 不再把模型正文当气泡预览——用户看到的每一条消息都来自
                            // `send_message` 落库后的 GenerationEvent.MessageSent。
                            // （到这里为止界面只显示 TypingRow；协议块与正文都不会先露出来。）
                        }
                        StreamEvent.Done -> Unit
                    }
                }
                val (body, calls) = ToolProtocol.extract(buffer.toString())
                // 0.15：模型可以在任何一个工具参数里带 rounds（1–15，默认 5）。
                // 取「这一轮见过的最大值」：先说要 8 轮、后面某个调用忘了带参数，不该把预算缩回 5。
                calls.forEach { call ->
                    // 数字（原生 tool_calls）与字符串（代码块）都认，见 ToolRounds.requested。
                    maxToolRounds = ToolRounds.merge(maxToolRounds, ToolRounds.requested(call))
                }
                // stay_silent 是「这一轮不回」的控制信号，不算一次工具轮：
                // 即使轮数刚好用尽，也不能把同一响应里的正文落库（规范：工具 + 正文只沉默）。
                val silenceInCalls = calls.any(StaySilent::isSilence)
                if (calls.isEmpty() || (round >= maxToolRounds && !silenceInCalls)) {
                    finalBody = body.ifBlank { if (calls.isEmpty()) buffer.toString().trim() else "" }
                    break
                }
                round++
                val results = mutableListOf<ToolResult>()
                // 0.15：同一批里既 `send_message` 又 `stay_silent` 时，先执行沉默——
                // 否则消息已经上屏，「这一轮只已读」就破了（见 StaySilent.planBatch）。
                val batch = StaySilent.planBatch(calls)
                for (call in batch.calls) {
                    if (silenced) {
                        // 这一轮已经决定不回：后面的工具一律不执行（尤其不能再把 send_message 发出去），
                        // 但每个调用都要给模型一个结果，免得它以为工具坏了反复重试。
                        results += ToolResult(
                            invocation = call,
                            ok = true,
                            summary = "这一轮已经决定不回",
                            contentForModel = "这一轮已经决定不回，别再执行别的工具，也别再输出任何文字。"
                        )
                        continue
                    }
                    // 0.15 危机优先：危机轮里「不回」的工具不执行——`defer_reply` 的念头/延后窗口、
                    // `stay_silent` 的连续计数与「已读不回」卡片都不该出现。拦在这里（执行之前），
                    // 而不是执行完再覆盖语义；同批里别的工具（比如 `send_message`）照常执行，
                    // 已经写出来的话仍然会发给对方。
                    if (CrisisPriority.blocksTool(crisisJudgement.await().isCrisis, call.name)) {
                        crisisForcedReply = true
                        results += ToolResult(
                            invocation = call,
                            ok = true,
                            summary = "这一轮不能只已读",
                            contentForModel = CrisisPriority.blockedModelText()
                        )
                        continue
                    }
                    // 这一轮已经发过消息：沉默收不回已经上屏的话，按「拒绝沉默」处理（与连续超限同一条路径）。
                    if (StaySilent.isSilence(call) && !StaySilent.canHonor(sentMessages.isNotEmpty())) {
                        results += ToolResult(
                            invocation = call,
                            ok = false,
                            summary = "已经发过消息",
                            contentForModel = "这一轮你已经发出过消息了，现在不能再沉默；把剩下的话说完。"
                        )
                        continue
                    }
                    // 模型自己调用过 remember：这一轮的高置信自动记忆不再重复写。
                    if (call.name.trim().equals(ToolCatalog.REMEMBER, ignoreCase = true)) rememberCalled = true
                    // 0.16：`send_message` 是**唯一**的对外消息通道（`spec/tool-call-agents/README.md` §1）。
                    // 聊天层直接落库，不交给通用工具执行器（它没有「发消息」的写路径）。
                    if (call.name.trim().equals(ToolCatalog.SEND_MESSAGE, ignoreCase = true)) {
                        val text = ExperienceRhythm.clampSendText(call.argument("text").orEmpty())
                        val tooMany = messagesThisRound >= AgentReplyProtocol.MAX_PER_ROUND
                        if (text.isBlank() || tooMany) {
                            results += ToolResult(
                                invocation = call,
                                ok = false,
                                summary = if (tooMany) "本轮已经发了 ${AgentReplyProtocol.MAX_PER_ROUND} 条" else "消息是空的",
                                contentForModel = if (tooMany) {
                                    AgentReplyProtocol.tooManyResult()
                                } else {
                                    AgentReplyProtocol.blankResult()
                                }
                            )
                            continue
                        }
                        messagesThisRound++
                        // 首条付完整打字延迟；连发之间只停 0.8–2.5 秒。
                        if (!typingDelayApplied) {
                            typingDelayApplied = true
                            kotlinx.coroutines.delay(
                                ExperienceRhythm.typingDelayMillis(
                                    charCount = text.length,
                                    arousal = moodForTiming?.arousal,
                                    lowEnergy = lowEnergy
                                )
                            )
                        } else if (sentMessages.isNotEmpty()) {
                            shownText = ""
                            emit(GenerationEvent.Delta(""))
                            kotlinx.coroutines.delay(
                                ExperienceRhythm.sendGapMillis(
                                    charCount = text.length,
                                    arousal = moodForTiming?.arousal,
                                    lowEnergy = lowEnergy
                                )
                            )
                        }
                        val saved = saveAssistantMessage(
                            sessionId = sessionId,
                            personaId = persona.id,
                            content = text,
                            model = session.model.ifBlank { config.model },
                            toolAttachments = emptyList(),
                            toolTraceJson = null,
                            // 新路径标记：这条消息是 send_message 发出去的。它同时让
                            // 「历史里有没有旧版本直接说的消息」可以在下一轮判定（见 buildContext）。
                            replyChannel = ToolTraceCodec.CHANNEL_TOOL,
                            generation = generation
                        )
                        if (saved == null) {
                            completed = true
                            emit(GenerationEvent.Stopped)
                            return@flow
                        }
                        sentMessages += saved
                        lastSaved = saved
                        notifyAssistant(persona, sessionId, saved)
                        emit(GenerationEvent.MessageSent(saved))
                        results += ToolResult(
                            invocation = call,
                            ok = true,
                            summary = "已发出第 ${sentMessages.size} 条",
                            // 规范 §1：工具结果要回灌「已发送，messageId=…」，模型据此继续决定。
                            contentForModel = AgentReplyProtocol.sentResult(
                                index = sentMessages.size,
                                messageId = saved.id
                            )
                        )
                        continue
                    }
                    val fingerprint = call.name.trim() + "|" + call.argumentsJson.trim()
                    if (!executed.add(fingerprint)) {
                        // 同一个调用这一轮已经做过：别再执行一次，只告诉它「刚做过」，
                        // 否则模型会陷进「查了又查」的循环。
                        results += ToolResult(
                            invocation = call,
                            ok = true,
                            summary = "刚刚已经做过",
                            contentForModel = "这个调用你刚刚已经做过了，结果就在上面。直接用那些结果把话说完。"
                        )
                    } else {
                        val execution = toolExecutor.execute(
                            invocation = call,
                            persona = persona,
                            configId = config.id,
                            sessionId = sessionId
                        )
                        // 「已读不回」不产生任何用户可见的东西：成功时这一轮没有 assistant 消息，
                        // 连动作卡片也不该留下；被拒绝的那次同样不显示（沉默不是一个发生过的动作）。
                        if (!call.name.trim().equals(ToolCatalog.STAY_SILENT, ignoreCase = true)) {
                            traces += execution.trace
                        }
                        toolAttachments += execution.attachments
                        if (call.name.trim().equals(ToolCatalog.DEFER_REPLY, ignoreCase = true)) deferred = true
                        if (call.name.trim().equals(ToolCatalog.STAY_SILENT, ignoreCase = true) && execution.result.ok) {
                            silenced = true
                        }
                        // 0.16 思考强度（规范 §2）：think 的结果回灌之后，**这一轮后续请求**升到 high；
                        // 失败/被拦下的 think 不升（没真的想，不值得多花）。
                        if (call.name.trim().equals(ToolCatalog.THINK, ignoreCase = true) && execution.result.ok) {
                            thinkCalledThisTurn = true
                            reasoningEffort = ReasoningEffort.forRequest(thinkCalledThisTurn)
                        }
                        results += execution.result
                    }
                }
                if (crisisForcedReply) {
                    // 危机优先（0.15）：这一轮不能停在「已读 / 晚点说」。模型在同一个响应里写的
                    // 正文照用；一个字都没写（只调了工具）时 finalBody 为空，下面会用安全提示
                    // 作为唯一正文。不再向模型要下一轮——危机里多等一轮没有意义。
                    finalBody = body.trim()
                    break
                }
                if (deferred) {
                    // 模型已经明确选择不回：没必要再问一轮，也不该让它再说一句话。
                    finalBody = ""
                    break
                }
                if (silenced) {
                    // stay_silent：立刻结束，不再向模型要下一轮。
                    // 即使模型在同一个响应里还写了文字，那些字也只停在暂存气泡里，不会落库。
                    finalBody = ""
                    break
                }
                // 过程话不算数：先把气泡里的临时文字清干净，等最后那一轮的真话。
                shownText = ""
                emit(GenerationEvent.Delta(""))
                emit(GenerationEvent.ToolCall(traces.toList()))
                // 工具结果里可能带多模态内容（listen 的语音 / watch 的帧）：只在这一轮回灌。
                val toolMedia = results.mapNotNull { it.media }.filterNot { it.isEmpty }
                val toolImages = toolMedia.flatMap { it.images }
                val toolAudios = toolMedia.flatMap { it.audios }
                    .map { clip -> WireAudio(base64 = clip.base64, format = clip.format) }
                val toolCaptions = toolMedia.mapNotNull { it.caption.takeIf { caption -> caption.isNotBlank() } }
                if (mediaKindInFlight == null) {
                    mediaKindInFlight = when {
                        toolAudios.isNotEmpty() -> AudioVideoKind.AUDIO
                        toolImages.isNotEmpty() -> AudioVideoKind.VIDEO
                        else -> null
                    }
                }
                conversation += WireMessage("assistant", buffer.toString().trim())
                conversation += WireMessage(
                    role = "user",
                    content = buildString {
                        append(ToolProtocol.renderResultsForModel(results))
                        if (toolCaptions.isNotEmpty()) {
                            append('\n')
                            append(toolCaptions.joinToString("\n"))
                        }
                        // 0.16：不要再让模型「把话说完」——说完意味着写正文。
                        // 要说的话仍然只能通过 send_message；不想说就结束这一轮。
                        append(
                            "\n\n（以上是你自己刚做完的事，对方看不见细节。要说话就继续调用 send_message，" +
                                "别重复已经发过的；不想说或说完了就结束这一轮，不要再输出正文。）"
                        )
                    },
                    images = toolImages,
                    audios = toolAudios
                )
            }
            if (deferred) {
                // defer_reply：这一轮他明确选择不回。工具已经写了念头、标了已读，
                // 这里不能再落一条空助手消息——「没有消息」本身就是他的回答。
                runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
                completed = true
                // 与睡眠无关（他醒着，只是决定晚点再说）：界面不显示睡眠提示行。
                emit(GenerationEvent.Silent(SilentReason.DEFERRED))
                return@flow
            }
            if (silenced) {
                // stay_silent：只把用户消息标成已读，然后结束这一轮。
                // 不写念头、不排窗口、不落消息——界面上连一个占位气泡都不会有，
                // 用户看到的只有「已读」。
                runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
                completed = true
                emit(GenerationEvent.Silent(SilentReason.STAY_SILENT))
                return@flow
            }
            val rawFinalText = finalBody.trim()
            val (finalText, proposal) = ProactiveProtocol.extract(rawFinalText)
            // 按界面语言取的危机提示句：既用于「缺锚点时补一句」，也用于危机轮完全没正文时的兜底。
            val crisisNote = appContext.getString(R.string.safety_crisis_note)
            // 危机两段式的最终结论：词表命中 + 模型说「确实是危机」/ 判定不可用（安全优先）。
            // 模型明确说 risk=false 时，下面的危机兜底（补提示、不许沉默）完全不生效。
            // 大多数情况下这一行不花时间：判定在拼提示词之前就已经发出去，首轮流式早就跑完了。
            val crisisTurn = crisisJudgement.await().isCrisis
            // 危机安全兜底（0.15）：用户说了危机语句、而他这一轮的话里没有任何安全锚点时，
            // 只在末尾补一句很短的提示——不覆盖他原本的话，也不改人设语气。
            // 第二段明确判成「不是危机」时整段跳过（词表只负责让它值得看一眼）。
            val safetyNote = if (!crisisTurn) {
                null
            } else {
                SafetyGuard.noteIfNeeded(
                    userMessage = latestUserText,
                    reply = (sentMessages.map { it.content } + finalText).joinToString("\n"),
                    note = crisisNote
                )
            }
            var replyText = finalText
            if (safetyNote != null) {
                if (replyText.isNotBlank()) {
                    replyText = replyText.trimEnd() + "\n" + safetyNote
                } else if (sentMessages.isNotEmpty()) {
                    // 正文已经用 send_message 一条条发出去了：把提示补到最后一条上，
                    // 不再造第 5 个气泡（规范 §4 的硬上限）。
                    val previous = sentMessages.last()
                    val merged = previous.copy(
                        content = previous.content.trimEnd() + "\n" + safetyNote,
                        updatedAt = nextTimestamp(sessionId)
                    )
                    runCatching {
                        messageDao.update(merged)
                        sentMessages[sentMessages.lastIndex] = merged
                        lastSaved = merged
                        refreshPreview(session)
                    }
                }
            }
            // 危机优先（0.15）：模型这一轮一个字都没写（只调了 `stay_silent` / `defer_reply`
            // 或纯工具），也还没发出任何消息——安全提示就是这一轮唯一的正文，不能只留「已读」。
            // 已经用 `send_message` 发过时走上面那条「并进最后一条」，这里不再补，避免第 5 个气泡。
            // 非危机轮 `CrisisPriority.replyBody` 原样返回，空流仍然是空流。
            if (crisisTurn && sentMessages.isEmpty()) {
                replyText = CrisisPriority.replyBody(crisisTurn, replyText, crisisNote)
            }
            // 0.16 兼容回退（`spec/tool-call-agents/README.md` §1）：正文不再是正常通道。
            // 旧版本 / 不守规矩的模型仍然会直接写出正文，这时整段落成**一条** legacy 消息，
            // 并在 toolTrace 里标上 `legacy`（界面不显示，迁移判定与日志用它）。
            // 已经用 send_message 发过时，剩下的正文并进最后一条，绝不造第 5 个气泡。
            val pendingAttachments = toolAttachments.take(MAX_IMAGES_PER_MESSAGE)
            // 拆条函数仍然过一遍：回退路径保留「空行分段」的旧排版，只是合并成一条，不再拆成多个气泡。
            // （`ExperienceRhythm.splitPlainReply` 仍是唯一实现，测试与老路径都指向它。）
            val legacyText = ExperienceRhythm.splitPlainReply(replyText).joinToString("\n\n")
            // 只发了一张表情、没写正文是合法的一轮（「点选即发送」另一种形态）：
            // 有附件就不算空流，否则会把刚发出去的表情一起丢掉。
            if (legacyText.isBlank() && proposal == null && toolAttachments.isEmpty() && sentMessages.isEmpty()) {
                throw ApiException(NetworkErrorKind.EmptyStream)
            }
            // 协议违规的判定先做完，才决定要不要走兼容回退。
            val legacyFallback = AgentReplyProtocol.isLegacyFallback(sentMessages.size)
            if (legacyText.isNotBlank() && !legacyFallback) {
                // 已经发过消息：任何剩余正文并进最后一条（安全提示也走这里），不下发第 5 个气泡。
                val previous = sentMessages.last()
                val merged = previous.copy(
                    content = (previous.content + "\n\n" + legacyText.trim()).trim(),
                    updatedAt = nextTimestamp(sessionId)
                )
                runCatching {
                    messageDao.update(merged)
                    sentMessages[sentMessages.lastIndex] = merged
                    lastSaved = merged
                    refreshPreview(session)
                }
            }
            if (legacyText.isNotBlank() && legacyFallback) {
                // 兼容回退：模型没有走工具，正文直接落一条 legacy 消息（规范 §1 的回退条款）。
                if (!typingDelayApplied) {
                    typingDelayApplied = true
                    kotlinx.coroutines.delay(
                        ExperienceRhythm.typingDelayMillis(
                            charCount = legacyText.length,
                            arousal = moodForTiming?.arousal,
                            lowEnergy = lowEnergy
                        )
                    )
                }
                // 日志里留一笔：这条回复没走 send_message。它不代表新提示词路径成功。
                android.util.Log.i(
                    "ChatRepository",
                    AgentReplyProtocol.legacyLogLine(sentMessages.size, legacyText.length)
                )
                val saved = saveAssistantMessage(
                    sessionId = sessionId,
                    personaId = persona.id,
                    content = legacyText.trim(),
                    model = session.model.ifBlank { config.model },
                    toolAttachments = pendingAttachments,
                    toolTraceJson = ToolTraceCodec.encode(traces),
                    replyChannel = ToolTraceCodec.CHANNEL_LEGACY,
                    generation = generation
                )
                if (saved == null) {
                    // 会话没了 / 世代过期：这次回复作废，界面收成「已停止」而不是假装成功。
                    completed = true
                    emit(GenerationEvent.Stopped)
                    return@flow
                }
                sentMessages += saved
                lastSaved = saved
                notifyAssistant(persona, sessionId, saved)
                emit(GenerationEvent.MessageSent(saved))
                shownText = ""
            }
            if (legacyText.isBlank() && pendingAttachments.isNotEmpty()) {
                // 只有表情/附件、没有正文：仍然是一条完整的助手消息，不能当空流丢掉。
                if (!typingDelayApplied) {
                    typingDelayApplied = true
                    kotlinx.coroutines.delay(
                        ExperienceRhythm.typingDelayMillis(
                            charCount = 0,
                            arousal = moodForTiming?.arousal,
                            lowEnergy = lowEnergy
                        )
                    )
                } else if (sentMessages.isNotEmpty()) {
                    shownText = ""
                    emit(GenerationEvent.Delta(""))
                    kotlinx.coroutines.delay(
                        ExperienceRhythm.sendGapMillis(
                            charCount = 0,
                            arousal = moodForTiming?.arousal,
                            lowEnergy = lowEnergy
                        )
                    )
                }
                val saved = saveAssistantMessage(
                    sessionId = sessionId,
                    personaId = persona.id,
                    // 只有表情、但这一轮触发过危机兜底时，把那句提示带上；正常情况仍然是空正文。
                    content = if (safetyNote != null && sentMessages.isEmpty()) safetyNote else "",
                    model = session.model.ifBlank { config.model },
                    toolAttachments = pendingAttachments,
                    toolTraceJson = ToolTraceCodec.encode(traces),
                    // 附件消息来自工具（表情/媒体），不是「直接说的正文」；标 status 以免被当成旧话。
                    replyChannel = ToolTraceCodec.CHANNEL_STATUS,
                    generation = generation
                ) ?: run {
                    completed = true
                    emit(GenerationEvent.Stopped)
                    return@flow
                }
                sentMessages += saved
                lastSaved = saved
                notifyAssistant(persona, sessionId, saved)
                emit(GenerationEvent.MessageSent(saved))
                shownText = ""
            }
            val saved = lastSaved ?: throw ApiException(NetworkErrorKind.EmptyStream)
            completed = true
            // 他回了一句：连续「已读不回」的计数清零（规范 §4 的连续性只按连续轮次算）。
            runCatching { settingsRepository.resetStaySilentStreak() }
            // 0.15 高置信自动记忆：只在正文说「记住了」不算；模型这一轮没有调用 remember 时，
            // 由规则把明确的长期事实（过敏/忌口/生日/纪念日/约定）兜底写进记忆抽屉。
            // 写在回复落库之后，失败只记日志，不回滚、不影响这一轮。
            runCatching { autoRemember(persona.id, sessionId, latestUserText, rememberCalled) }
            // 这一轮聊完了：让他在后台把「已经聊过去的那一段」收成一段记忆。
            // 异步、不 await：压缩再慢也晚不了一毫秒回话，失败也当没发生。
            // 服务端模式下上下文由服务端维护，本机不再压缩（否则两边各花一次模型调用）。
            if (!isServerMode()) {
                historyCompressor.ensureAsync(sessionId)
                // 0.14：心理活动与会话备忘录都在收尾异步触发；失败保留旧值。
                runCatching { mentalActivityRepository.ensureAsync(sessionId, justSent = true) }
                runCatching { sessionMemoryRepository.ensureAsync(sessionId) }
            }
            // 记忆内置开启；抽不抽由人格的「抽记忆频率」决定（会话可覆盖）。
            val count = messageDao.countVisibleInSession(sessionId)
            val freshSession = sessionDao.getById(sessionId)
            val every = (freshSession?.extractionEveryOverride ?: persona.extractionEvery).coerceIn(3, 12)
            val pendingMemory = count - (freshSession?.lastMemoryExtractedCount ?: 0) >= every
            emit(GenerationEvent.Completed(saved, pendingMemory, proposal, sentMessages.toList()))
        } catch (cancel: CancellationException) {
            // 0.11.0（审计 P2-8）：这一轮到底有没有「值得落库的东西」由 InterruptedReply 决定。
            // 0.14 再收紧一步：停止时不落空消息——没有可见正文就什么都不写。
            if (!completed) {
                when (InterruptedReply.decide(shownText, traces.isNotEmpty())) {
                    InterruptedReply.Outcome.DISCARD -> Unit
                    InterruptedReply.Outcome.STATUS_ONLY -> Unit
                    InterruptedReply.Outcome.SAVE_TEXT -> withContext(NonCancellable) {
                        saveInterrupted(
                            sessionId = sessionId,
                            personaId = persona.id,
                            partial = shownText,
                            model = session.model.ifBlank { config.model },
                            toolTraceJson = ToolTraceCodec.encode(traces),
                            generation = generation
                        )
                    }
                }
            }
            throw cancel
        } catch (error: Exception) {
            if (!completed) {
                val userFacing = error.toUserError()
                // 这一轮带了语音/视频、provider 回 4xx：明确说「这个模型听不了/看不了」，
                // 不伪造转写、也不把它落到「未知错误」。
                val storedError = mediaFailureToken(error, mediaKindInFlight)
                    ?: UserErrorTokens.encode(userFacing)
                val saved = withContext(NonCancellable) {
                    saveFailed(
                        sessionId = sessionId,
                        personaId = persona.id,
                        partial = shownText,
                        errorText = storedError,
                        model = session.model.ifBlank { config.model },
                        toolTraceJson = ToolTraceCodec.encode(traces),
                        generation = generation
                    )
                }
                emit(GenerationEvent.Failed(userFacing, saved))
            }
        }
    }
        // 注册 / 注销「这段会话正在跑生成」，删除与清空按 sessionId 取消它。
        .onStart {
            currentCoroutineContext()[Job]?.let { job -> activeGenerations[sessionId] = job }
        }
        .onCompletion {
            activeGenerations.remove(sessionId, currentCoroutineContext()[Job])
        }

    /**
     * 落一条完整的 assistant 消息（0.14 连发也走这里）。
     *
     * 与旧的「最后一并写」不同：每条消息独立成行、独立时间戳，块维护统一走
     * [MessageBlockWriter]；会话预览/未读也按最后一条更新。
     *
     * @param replyChannel 0.16：这条消息的「回复通道」（[ToolTraceCodec.CHANNEL_TOOL] /
     *        [ToolTraceCodec.CHANNEL_LEGACY] / [ToolTraceCodec.CHANNEL_STATUS]）。
     *        它会作为一条不可见的标记并进 `toolTraceJson`，让下一轮能判定「历史里有没有
     *        旧版本直接说的消息」；空串表示这条写入不带标记（保留给内部调用）。
     */
    private suspend fun saveAssistantMessage(
        sessionId: String,
        personaId: String,
        content: String,
        model: String,
        toolAttachments: List<MessageAttachment>,
        toolTraceJson: String?,
        generation: Long,
        replyChannel: String = ""
    ): MessageEntity? = withContext(NonCancellable) {
        if (!writeStillValid(sessionId, generation)) return@withContext null
        val now = nextTimestamp(sessionId)
        val assistant = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = personaId,
            role = MessageRole.ASSISTANT,
            content = content,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            model = model,
            edited = false,
            attachmentsJson = AttachmentsCodec.encode(toolAttachments),
            toolTraceJson = ToolTraceCodec.withChannel(toolTraceJson, replyChannel)
        )
        database.withTransaction {
            blockWriter.append(assistant)
            val preview = content.replace('\n', ' ').trim().take(100)
                .ifBlank {
                    if (toolAttachments.isNotEmpty()) attachmentPreview(toolAttachments)
                    else appContext.getString(R.string.chat_preview_proactive)
                }
            val fresh = sessionDao.getById(sessionId)
            if (fresh != null) {
                sessionDao.update(
                    fresh.copy(
                        lastMessagePreview = preview,
                        lastMessageAt = now,
                        updatedAt = now
                    )
                )
            }
            if (ChatNotificationPolicy.shouldMarkUnread(openSessionTracker.openSessionId, sessionId)) {
                sessionDao.incrementUnread(sessionId)
            } else {
                sessionDao.markRead(sessionId)
            }
        }
        assistant
    }

    private fun notifyAssistant(persona: PersonaEntity, sessionId: String, message: MessageEntity) {
        if (!ChatNotificationPolicy.shouldNotify(
                openSessionTracker.openSessionId,
                sessionId,
                foregroundTracker.isForeground
            )
        ) return
        runCatching {
            chatNotifier.notifyMessage(appContext, persona, sessionId, message.content)
        }
    }

    /**
     * 高置信自动记忆（0.15）：规则命中后写一条 `USER` 记忆，并在日志里标 `auto`。
     *
     * 三件事缺一不可：用户这句话确实包含明确的长期事实（[AutoMemory.detect]）、
     * 这一轮模型没有自己调用 `remember`（调用方判断）、已有的记忆里没有同一件事（仓储去重）。
     * 只写一条、内容用原句；失败不抛出——记忆是锦上添花，不能把这一轮回复弄成失败。
     */
    private suspend fun autoRemember(
        personaId: String,
        sessionId: String,
        userMessage: String,
        rememberCalled: Boolean
    ) {
        val hit = AutoMemory.detectThisTurn(userMessage, rememberCalled) ?: return
        memoryRepository.addAutoMemory(
            personaId = personaId,
            content = hit.content,
            importance = hit.importance,
            sourceQuote = userMessage.take(120),
            sourceSessionId = sessionId,
            tags = hit.tags
        ).onSuccess { added ->
            if (added) {
                android.util.Log.i(
                    "ChatRepository",
                    "auto remember(kind=${hit.kind.tag}, importance=${hit.importance}): ${hit.content}"
                )
            } else {
                android.util.Log.d(
                    "ChatRepository",
                    "auto remember skipped (duplicate, kind=${hit.kind.tag})"
                )
            }
        }.onFailure { error ->
            android.util.Log.w("ChatRepository", "auto remember failed: ${error.javaClass.simpleName}")
        }
    }

    /**
     * 收尾写入（成功 / 中断 / 失败）之前的最后一道检查：会话还在、世代没变。
     *
     * 两件事缺一不可：`NonCancellable` 让写入不受取消影响（这是为了「停止生成」也能留下
     * 已收到的半截话），但取消之后用户可能已经删了会话——存在性 + 世代号负责别写孤儿。
     */
    private suspend fun writeStillValid(sessionId: String, generation: Long): Boolean {
        if (!sessionGeneration.isCurrent(sessionId, generation)) return false
        return sessionDao.getById(sessionId) != null
    }

    /**
     * 按会话取消正在跑的生成。
     *
     * 删除 / 清空上下文先调它，让 UI 立刻停下；真正的正确性由 [writeStillValid] 保证
     * （取消是异步的，`NonCancellable` 的收尾可能仍然排在删除事务之后）。
     */
    fun cancelGeneration(sessionId: String) {
        activeGenerations.remove(sessionId)?.cancel()
    }

    private suspend fun saveInterrupted(
        sessionId: String,
        personaId: String,
        partial: String,
        model: String,
        toolTraceJson: String? = null,
        generation: Long
    ) {
        // 删除会话 / 清空上下文之后不再把半截回复写回去。
        if (!writeStillValid(sessionId, generation)) return
        val now = nextTimestamp(sessionId)
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = personaId,
            role = MessageRole.ASSISTANT,
            content = partial,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.STOPPED,
            errorMessage = null,
            model = model,
            edited = false,
            // 0.16：失败/中断是状态消息，不是「旧版本直接说的正文」——
            // 标 status 免得下一轮把它们当成迁移提示的依据。
            toolTraceJson = ToolTraceCodec.withChannel(toolTraceJson, ToolTraceCodec.CHANNEL_STATUS)
        )
        database.withTransaction {
            blockWriter.append(message)
            val fresh = sessionDao.getById(sessionId) ?: return@withTransaction
            sessionDao.update(
                fresh.copy(
                    // 只有工具调用、没有正文的中断：预览保持上一句，不写一个空预览（P2-8）。
                    lastMessagePreview = if (partial.isBlank()) {
                        fresh.lastMessagePreview
                    } else {
                        partial.replace('\n', ' ').trim().take(100)
                    },
                    lastMessageAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private suspend fun saveFailed(
        sessionId: String,
        personaId: String,
        partial: String,
        errorText: String,
        model: String,
        toolTraceJson: String? = null,
        generation: Long
    ): MessageEntity? {
        // 同上：会话没了（或世代过期）就只把错误交给界面，不落一条孤儿错误消息。
        if (!writeStillValid(sessionId, generation)) return null
        val now = nextTimestamp(sessionId)
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = personaId,
            role = MessageRole.ASSISTANT,
            content = partial,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.ERROR,
            errorMessage = errorText,
            model = model,
            edited = false,
            // 0.16：错误气泡同样是状态消息（不是回退正文），标 status 以免污染迁移判定。
            toolTraceJson = ToolTraceCodec.withChannel(toolTraceJson, ToolTraceCodec.CHANNEL_STATUS)
        )
        database.withTransaction {
            blockWriter.append(message)
            val fresh = sessionDao.getById(sessionId)
            if (fresh != null) {
                sessionDao.update(
                    fresh.copy(
                        lastMessagePreview = partial.replace('\n', ' ').trim().take(100)
                            .ifBlank { appContext.getString(R.string.chat_preview_failed) },
                        lastMessageAt = now,
                        updatedAt = now
                    )
                )
                if (openSessionTracker.openSessionId != sessionId) sessionDao.incrementUnread(sessionId)
            }
        }
        return message
    }

    suspend fun editUserMessage(sessionId: String, messageId: String, newContent: String): Result<Unit> = runCatching {
        val content = newContent.trim()
        if (content.isBlank()) userError(DomainErrorCode.MESSAGE_EMPTY)
        if (content.length > 32_000) userError(DomainErrorCode.MESSAGE_TOO_LONG)
        val message = messageDao.getById(messageId) ?: userError(DomainErrorCode.MESSAGE_NOT_FOUND)
        if (message.role != MessageRole.USER || message.sessionId != sessionId) {
            userError(DomainErrorCode.ONLY_USER_MESSAGE_EDITABLE)
        }
        val now = System.currentTimeMillis()
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        database.withTransaction {
            // 编辑用户消息按规范 §2「按锚点插入」处理：先移除旧行，再以同一个
            // `(createdAt, id)` 落回锚点消息所在的那一块（位置不变，块元数据由统一规则重算）。
            // 不直接 UPDATE content：否则「编辑」与「删除 + 锚点插入」会各算一套块元数据。
            blockWriter.replaceAtAnchor(
                message.copy(content = content, edited = true, updatedAt = now)
            )
            // 「删除这条消息之后的消息」按稳定序 (createdAt, id) 判断（审计 P2-7）：
            // 以前只删 createdAt 严格更晚的，后台消息刚好落在同一毫秒时旧回复会留下来，
            // 编辑重发后对话读起来是错位的。
            blockWriter.deleteAfterCursor(sessionId, message.createdAt, message.id)
            val latest = messageDao.latestInSession(sessionId)
            sessionDao.update(
                session.copy(
                    lastMessagePreview = latest?.content?.replace('\n', ' ')?.trim()?.take(100),
                    lastMessageAt = latest?.createdAt,
                    updatedAt = now
                )
            )
        }
        refreshPreview(session)
    }

    suspend fun deleteMessage(messageId: String): Result<Unit> = runCatching {
        val message = messageDao.getById(messageId) ?: return@runCatching
        blockWriter.deleteMessage(message)
        // 消息没了，它带着的图片文件也不该继续占地方。
        deleteAttachmentFiles(listOf(message))
        val session = sessionDao.getById(message.sessionId) ?: return@runCatching
        refreshPreview(session)
    }

    suspend fun clearContext(sessionId: String): Result<Unit> = runCatching {
        // 0.15：清空上下文也把「想起来、还没注入」的那段回忆一起丢掉。
        runCatching { memorizeRepository.clearPending(sessionId) }
        // 先让「这段会话的后台写入」全部作废：生成收尾（NonCancellable）与历史压缩
        // 都可能排在删除事务之后，把半截回复或旧摘要写回来。世代号 + 取消双保险。
        sessionGeneration.bump(sessionId)
        cancelGeneration(sessionId)
        historyCompressor.cancel(sessionId)
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        val now = System.currentTimeMillis()
        // 先取一遍要删的消息，事务提交后把它们的图片文件一并清掉（事务里不做文件 IO）。
        val doomed = messageDao.getBySession(sessionId)
        database.withTransaction {
            blockWriter.deleteSession(sessionId)
            // 0.14：清空上下文后旧念头/旧备忘录也不能再注入。
            mentalActivityRepository.deleteBySession(sessionId)
            sessionMemoryRepository.deleteBySession(sessionId)
            sessionDao.update(
                session.copy(
                    lastMessagePreview = null,
                    lastMessageAt = null,
                    unreadCount = 0,
                    lastMemoryExtractedCount = 0,
                    // 消息都删了：那段「更早之前你记得的」也跟着作废，别让新对话背着旧摘要。
                    historySummary = null,
                    historySummarizedUpTo = null,
                    historySummaryAt = null,
                    updatedAt = now
                )
            )
        }
        deleteAttachmentFiles(doomed)
    }

    suspend fun renameSession(sessionId: String, title: String): Result<Unit> = runCatching {
        val value = title.trim()
        if (value.isBlank()) userError(DomainErrorCode.TITLE_EMPTY)
        sessionDao.rename(sessionId, value.take(120), System.currentTimeMillis())
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned, System.currentTimeMillis())
    }

    /**
     * 标已读：本地缓存立刻清未读；服务端模式下再尽力 `POST /v1/sessions/{id}/read`。
     *
     * 服务端回执失败**不影响**本地已读（用户已经在看消息了）；失败只记一行日志，
     * 下次同步会用服务端的 unread 覆盖。日志不打印地址/令牌。
     */
    suspend fun markRead(sessionId: String) {
        sessionDao.markRead(sessionId)
        if (!serverMode.isEnabled()) return
        runCatching {
            serverChat.markRead(sessionId).getOrThrow()
        }.onFailure { error ->
            android.util.Log.w("ChatRepository", "server markRead failed: ${error.javaClass.simpleName}")
        }
    }

    /**
     * 通知深链的落点校验（安全 P2-7 / 审计 P2-6）：这个 `extra_session_id` 能不能直接打开。
     *
     * 任意应用都能给 exported 的 `MainActivity` 塞这个 extra，所以字符串一律不可信：
     * 会话必须真的存在，而且属于当前 `activePersonaId`。任何一条不满足都返回 null，
     * 界面停在首页（不导航、不 `markRead`、也不撤别人的通知）。
     */
    suspend fun resolveNotificationSession(raw: String?): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val session = sessionDao.getById(candidate) ?: return null
        val active = runCatching { settingsRepository.settings.first().activePersonaId }.getOrNull()
        return NotificationDeepLink.sessionIdToOpen(candidate, session.personaId, active)
    }

    /** 打开某段会话（原子写入，见 [OpenSessionTracker]）。 */
    fun setOpenSession(id: String?) {
        openSessionTracker.open(id)
    }

    /**
     * 「我的这段会话还开着吗」——VM 销毁时的 compare-and-clear。
     *
     * 只有当前打开的仍是 [sessionId] 才清空：转场期间新 VM 可能已经写入了下一段会话，
     * 旧 VM 的 `onCleared` 不能把它一起清掉（否则用户正看着的会话被判成没开着，
     * 未读 +1 并且弹一条「你正在读的消息」的通知）。
     */
    fun clearOpenSession(sessionId: String) {
        openSessionTracker.clearIfOpen(sessionId)
    }

    fun isSessionOpen(sessionId: String): Boolean = openSessionTracker.isOpen(sessionId)

    /** 当前屏幕上看的是哪段会话（没有为 null）。通知判定统一走 [ChatNotificationPolicy]。 */
    fun openSessionId(): String? = openSessionTracker.openSessionId

    suspend fun deleteSession(sessionId: String) {
        // 与 clearContext 同一套顺序：先 bump（让在途写入一定作废），再取消。
        sessionGeneration.bump(sessionId)
        cancelGeneration(sessionId)
        historyCompressor.cancel(sessionId)
        val doomed = messageDao.getBySession(sessionId)
        database.withTransaction {
            blockWriter.deleteSession(sessionId)
            sessionDao.deleteById(sessionId)
            // 0.14：会话级心理活动与备忘录跟着会话一起走。
            mentalActivityRepository.deleteBySession(sessionId)
            sessionMemoryRepository.deleteBySession(sessionId)
        }
        deleteAttachmentFiles(doomed)
        // 会话彻底没了：世代记录也没必要留着（不然后台写入的 key 会无限增长）。
        sessionGeneration.forget(sessionId)
    }

    /**
     * 删掉这些消息带在身上的图片文件（只删 App 自己媒体目录里的）。
     *
     * **必须在数据库行删掉之后调用**（0.12 媒体去重）：聊天图按内容寻址命名，
     * 同一个 `chat-<sha>.<ext>` 可能被别的消息引用；先删行再查引用，
     * 本次要删的那条消息自己才不会被算成「还有人在用」（否则文件永远删不掉），
     * 而别人还在用的那一份也不会被误删。
     */
    private suspend fun deleteAttachmentFiles(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        runCatching {
            val attachments = messages.flatMap { AttachmentsCodec.decode(it.attachmentsJson) }
            if (attachments.isNotEmpty()) mediaJanitor.deleteAttachmentsIfUnreferenced(attachments)
        }
    }

    suspend fun bindSessionConfig(sessionId: String, configId: String): Result<ChatSessionEntity> = runCatching {
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        val config = apiConfigDao.getById(configId) ?: userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
        val persona = personaDao.getById(session.personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config.model
        val now = System.currentTimeMillis()
        database.withTransaction {
            sessionDao.rebindConfig(sessionId, config.id, model, now)
            if (persona.defaultApiConfigId == null) {
                personaDao.update(
                    persona.copy(
                        defaultApiConfigId = config.id,
                        updatedAt = now
                    )
                )
            }
        }
        sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
    }

    /**
     * 检索页的「消息」页签：**逐块扫描 + 键集游标，无候选上限**（规范 §4）。
     *
     * ## 一次检索的完整路径
     *
     * 1. [MessageSearchSql.blocksQuery] 枚举（可过滤人格/时间的）块——一条 SQL，只回
     *    `(sessionId, blockIndex)`，不读正文；
     * 2. 对每一块调 [MessageSearchSql.blockScanQuery]：**一条 SQL 只读一块**（`blockIndex = ?`），
     *    块内命中由 [BlockSearch] 用 [SearchRanking] 的同一套词元规则判定并打分；
     * 3. 遍历时只维护「这一页 + 一条 frontier」（固定大小的堆），一块读完立即丢掉，
     *    所以峰值内存 ≈ 一块 + 一页，与会话总长度无关；
     * 4. 翻页用键集游标 `(matchedTermCount, relevance, createdAt, id)`：下一页重走一遍块，
     *    只保留**严格排在游标之后**的行。这就是「结果完整且不重复」的保证，
     *    也是 0.11 的「候选上限 500」被删掉的原因。
     *
     * 返回的 `total` 是这一遍扫描的**全部**命中数（不是候选池大小）：界面显示「共 N 条」时
     * 不再需要附加一句「只显示最近的部分」。
     *
     * 只读本地库：不联网、不写库、不改任何状态。
     */
    suspend fun searchMessages(request: SearchQuery): Result<SearchPage> = runCatching {
        val text = request.text.trim()
        if (text.isEmpty()) return@runCatching SearchPage()
        val limit = request.limit.coerceIn(1, 100)
        val filter = SearchFilter(
            personaId = request.personaId,
            since = request.since,
            until = request.until
        )
        val page = BlockSearch.page(
            source = blockScanSource(text = text, filter = filter),
            query = text,
            filter = filter,
            limit = limit,
            cursor = request.cursor
        )
        SearchPage(
            query = text,
            hits = page.hits.map { hit -> toSearchHit(hit.payload, text) },
            total = page.totalMatches,
            hasMore = page.hasMore,
            nextCursor = page.nextCursor
        )
    }

    /**
     * 检索的数据源：枚举块 → 一块一块读。
     *
     * 做成匿名实现而不是往 DAO 里塞循环，是为了让「每条 SQL 只读一块」在源码上就是显然的
     * （`BlockSearchTest` 用假数据源断言同一个形状：每次 `messages()` 只拿到一个块键）。
     */
    private fun blockScanSource(
        text: String,
        filter: SearchFilter
    ): BlockSearch.BlockScanSource<MessageSearchRow> = object : BlockSearch.BlockScanSource<MessageSearchRow> {
        override suspend fun blocks(filter: SearchFilter): List<BlockSearch.BlockKey> {
            val query = MessageSearchSql.blocksQuery(filter.personaId, filter.since, filter.until)
            if (query.sql.isEmpty()) return emptyList()
            return blockDao.searchBlocks(query.toSupportQuery()).map { row: SearchBlockRow ->
                BlockSearch.BlockKey(row.sessionId, row.blockIndex)
            }
        }

        override suspend fun messages(block: BlockSearch.BlockKey): List<Pair<RankInput, MessageSearchRow>> {
            val query = MessageSearchSql.blockScanQuery(
                text = text,
                sessionId = block.sessionId,
                blockIndex = block.blockIndex,
                since = filter.since,
                until = filter.until
            )
            if (query.sql.isEmpty()) return emptyList()
            return messageDao.searchBlockMessages(query.toSupportQuery()).map { row ->
                RankInput(
                    id = row.messageId,
                    content = row.content,
                    createdAt = row.createdAt,
                    personaId = row.personaId
                ) to row
            }
        }
    }

    /**
     * 检索页的「会话 / 人格」页签。
     *
     * 会话表很小（一人一段），所以照旧在内存里过滤：标题、人格名、最后一条预览。
     * 消息正文的命中在「消息」页签里，这里只回答「哪段对话值得点进去」。
     */
    suspend fun searchSessions(
        text: String,
        personaId: String? = null,
        since: Long? = null
    ): List<SessionSearchHit> {
        val term = text.trim()
        if (term.isEmpty()) return emptyList()
        val personas = personaDao.getAll().associateBy { it.id }
        return sessionDao.getAll()
            .asSequence()
            .filter { personaId == null || it.personaId == personaId }
            .filter { since == null || (it.lastMessageAt ?: it.updatedAt) >= since }
            .mapNotNull { session ->
                val persona = personas[session.personaId]
                val titleHit = session.title.indexOf(term, ignoreCase = true) >= 0
                val personaHit = persona?.name?.indexOf(term, ignoreCase = true)?.let { it >= 0 } == true
                val previewHit = session.lastMessagePreview?.indexOf(term, ignoreCase = true)?.let { it >= 0 } == true
                if (!titleHit && !personaHit && !previewHit) return@mapNotNull null
                SessionSearchHit(
                    sessionId = session.id,
                    personaId = session.personaId,
                    title = session.title,
                    personaName = persona?.name.orEmpty(),
                    personaAvatarUri = persona?.avatarUri,
                    personaAvatarSeed = persona?.avatarSeed ?: 0,
                    preview = session.lastMessagePreview,
                    lastMessageAt = session.lastMessageAt
                )
            }
            .sortedWith(
                compareByDescending<SessionSearchHit> { it.lastMessageAt ?: 0L }
                    .thenBy { it.sessionId }
            )
            .toList()
    }

    private fun toSearchHit(row: MessageSearchRow, query: String): SearchHit {
        val snippet = SearchSnippet.build(row.content, query)
        return SearchHit(
            messageId = row.messageId,
            sessionId = row.sessionId,
            sessionTitle = row.sessionTitle,
            personaId = row.personaId,
            personaName = row.personaName,
            personaAvatarUri = row.personaAvatarUri,
            personaAvatarSeed = row.personaAvatarSeed,
            snippet = snippet.text,
            highlights = snippet.highlights,
            createdAt = row.createdAt,
            role = row.role
        )
    }

    private fun com.yap.app.data.local.SqlQuery.toSupportQuery(): SimpleSQLiteQuery =
        SimpleSQLiteQuery(sql, args.toTypedArray())

    suspend fun exportSession(sessionId: String): Result<ExportFile> = runCatching {
        // 组装 + JSON 编码都可能在主线程上几十毫秒到几百毫秒（几百上千条消息），
        // 一律下沉到 IO；界面只负责把结果写进用户选的文件（同样在 IO 上）。
        withContext(Dispatchers.IO) {
            val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
            val persona = personaDao.getById(session.personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
            val config = session.apiConfigId?.let { apiConfigDao.getById(it) }
            val messages = messageDao.getBySession(sessionId)
            val dto = ExportSession(
                sessionId = session.id,
                title = session.title,
                personaName = persona.name,
                personaCategory = persona.category,
                personaVersion = persona.version,
                apiConfigName = config?.name,
                model = session.model,
                createdAt = session.createdAt,
                messages = messages.map {
                    ExportMessage(
                        role = it.role.name.lowercase(),
                        content = it.content,
                        createdAt = it.createdAt,
                        status = it.status.name
                    )
                }
            )
            val bundle = ExportBundle(
                exportedAt = System.currentTimeMillis(),
                sessions = listOf(dto)
            )
            ExportFile(
                fileName = "yap-chat-${sanitizeFile(persona.name)}-${System.currentTimeMillis()}.json",
                content = json.encodeToString(ExportBundle.serializer(), bundle)
            )
        }
    }

    /** 「导出全部对话」的默认文件名（界面先拿它去开系统文件选择器）。 */
    fun suggestedAllChatsFileName(): String = "yap-all-chats-${System.currentTimeMillis()}.json"

    /**
     * 「导出全部对话」：边读库、边序列化、边**分批**交给 [writeChunk] 写文件。
     *
     * 不再先拼一个几 MB～几十 MB 的字符串：峰值只跟单条会话和
     * [ExportJsonStream.DEFAULT_BATCH_CHARS] 有关。调用方负责把 [writeChunk]
     * 落到 `contentResolver.openOutputStream(uri)`（本函数已经在 IO 上执行）。
     *
     * @return 建议的文件名
     */
    suspend fun exportAllChatsTo(writeChunk: suspend (String) -> Unit): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val exportedAt = System.currentTimeMillis()
            val fileName = "yap-all-chats-$exportedAt.json"
            val personas = personaDao.getAll().associateBy { it.id }
            val configs = apiConfigDao.getAll().associateBy { it.id }
            val sessions = sessionDao.getAll()
            val stream = ExportJsonStream { chunk -> writeChunk(chunk) }
            stream.start(schemaVersion = EXPORT_SCHEMA_VERSION, exportedAt = exportedAt)
            sessions.forEach { session ->
                val persona = personas[session.personaId]
                val dto = ExportSession(
                    sessionId = session.id,
                    title = session.title,
                    personaName = persona?.name ?: "",
                    personaCategory = persona?.category ?: "",
                    personaVersion = persona?.version ?: 0,
                    apiConfigName = session.apiConfigId?.let { configs[it]?.name },
                    model = session.model,
                    createdAt = session.createdAt,
                    messages = messageDao.getBySession(session.id).map {
                        ExportMessage(it.role.name.lowercase(), it.content, it.createdAt, it.status.name)
                    }
                )
                stream.writeSession(json.encodeToString(ExportSession.serializer(), dto))
            }
            stream.finish()
            fileName
        }
    }

    /**
     * 「清空全部聊天与记忆」。
     *
     * 清单在 [com.yap.app.domain.ErasurePlan]：除了消息/会话/记忆，还包括
     * `memory_echoes`（原话）、`memory_consolidations`（整理摘要）、`memory_versions`
     * （记忆旧正文）、`memory_vectors`，以及聊天图片文件（不碰聊天背景与头像）。
     */
    suspend fun clearAllChatData() {
        // 文件清理（删聊天图）在 IO 上做：`ErasureFiles` 是非挂起接口，那里的引用查询会阻塞
        // 调用线程（见 `AndroidErasureFiles`），不能让它阻塞主线程。
        withContext(Dispatchers.IO) { dataEraser.clearChatsAndMemories() }
        // 0.14：这两张新表不在 agentcore 的 `ErasableTable` 清单里，按同一语义显式清空。
        runCatching { mentalActivityRepository.deleteAll() }
        runCatching { sessionMemoryRepository.deleteAll() }
        // 0.13：语音/视频文件不在 `DataEraser` 的清单里（那是 agentcore 的跨端契约），
        // 在这里按同一套引用守卫收尾，否则「清空聊天」之后语音还留在沙盒里。
        runCatching { mediaJanitor.clearVoiceVideoExcept(emptySet()) }
        // 审计 P2-2（0.11.1）：与 wipeAllData 对齐——表都删了，还挂着的通知与被认为
        // 「仍开着」的会话都要收掉，否则点旧通知会跳进一个不存在的会话、未读判定也拿旧值比较。
        openSessionTracker.clearAll()
        runCatching { chatNotifier.cancelAll(appContext) }
    }

    /**
     * 「删除全部本地数据」。
     *
     * 除聊天与记忆外，还清 `agent_ticks` / `agent_intents` / `persona_pending_patches` /
     * 人格版本历史 / 主动节奏 / 人格预设 / 人格 / API 配置，以及整个媒体目录。
     */
    suspend fun wipeAllData() {
        // 整棵媒体目录的删除是阻塞文件 IO；与其他删除路径一样挪到 IO 上。
        withContext(Dispatchers.IO) { dataEraser.wipeAllData() }
        runCatching { mentalActivityRepository.deleteAll() }
        runCatching { sessionMemoryRepository.deleteAll() }
        // 表情行与表情文件不归 `DataEraser` 管（那张表在 `agentcore` 的清单里不存在，而且
        // 文件目录也不在 `MediaStorage.ownedRoots` 里），所以在这里显式清一遍。
        runCatching { stickerRepository.wipeAll() }
        openSessionTracker.clearAll()
        // 「删除全部数据」之后不该还挂着任何一条他的通知（审计 P2-14：以前 cancel 只支持单会话）。
        runCatching { chatNotifier.cancelAll(appContext) }
    }

    /**
     * 睡眠判定：把人格的作息、免打扰、唤醒阈值和「最近 30 分钟几条消息」交给 agent 决策层。
     *
     * 最近消息数包含刚刚发来的这一条——所以「第 N 条叫醒」的语义是直观的，
     * 不需要额外建表记「叫了几次」，30 分钟窗口自己会滚动清零。
     *
     * 0.11.0：判定本身改为经由 [AgentCoreClient]（默认进程内委托 `InProcessAgentCore`，
     * 打开构建开关时请求 `agentd` 的同名接口，失败自动回退）。这里只负责取数据与落痕迹，
     * 规则仍只有一份（`:agentcore`），行为与 0.10.x 逐字一致。
     */
    private suspend fun evaluateSleep(
        persona: PersonaEntity,
        sessionId: String,
        now: Long
    ): SleepDecision {
        val minute = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .let { it.hour * 60 + it.minute }
        // 不睡觉的人不需要查「最近几条」：判定输入里 sleepEnabled=false 已经足够。
        val recent = if (persona.sleepEnabled) {
            runCatching {
                messageDao.countUserMessagesSince(sessionId, now - SleepPolicy.WAKE_WINDOW_MS)
            }.getOrDefault(0)
        } else {
            0
        }
        // 放到 IO：远程模式下这是一次同步 HTTP（最长 1.5 秒），而这条 flow 是从
        // viewModelScope（Main）collect 的；留在主线程会触发 NetworkOnMainThreadException
        // 并被客户端当成「远程失败」，于是永远静默回退。进程内实现只是多一次调度，可忽略。
        return withContext(Dispatchers.IO) {
            agentCore.sleepDecision(
                SleepInput(
                    sleepEnabled = persona.sleepEnabled,
                    dndOnSleep = persona.dndOnSleep,
                    sleepMinutes = persona.quietSleepMinutes,
                    wakeMinutes = persona.quietWakeMinutes,
                    nowMinutes = minute,
                    userMessagesInWindow = recent,
                    wakeThreshold = persona.wakeThreshold
                )
            )
        }
    }

    private suspend fun buildContext(
        session: ChatSessionEntity,
        rawPersona: PersonaEntity,
        config: com.yap.app.data.local.ApiConfigEntity,
        recentMessages: List<MessageEntity>
    ): List<WireMessage> {
        // 提示词里用的「他」：有匹配当前语言的翻译缓存就用译文，否则原样
        // （`spec/persona-translation/README.md` §1；缓存的写入口在
        // [PersonaTranslationCoordinator]，这里只读）。
        val persona = PersonaPromptLocalization.effective(rawPersona)
        // 这一轮的时间：状态段的时钟、心情/念头的读取、以及历史时间戳（[HistoryTimestamps]）
        // 全都用同一个 now，避免「现在 19:40」和「[19:41] 用户：」这种一毫秒的错位。
        val now = System.currentTimeMillis()
        // 近处这一段按时间正序排好；已经被摘要盖住的那部分不再逐字塞进来
        // ——它已经在 system 里那一段「更早之前你记得的」里了，同一个意思说两遍只会让他混乱。
        val summarizedUpTo = session.historySummarizedUpTo?.takeIf { it > 0L }
        val visible = recentMessages
            .asReversed()
            .filter { summarizedUpTo == null || it.createdAt > summarizedUpTo }
        // 用最近一句对方说的话当检索线索，挑「此刻最相关」的记忆。
        val recallQuery = visible.lastOrNull { it.role == MessageRole.USER }?.content?.take(300).orEmpty()
        // 1.9：记忆内置开启；「记性」决定此刻能想起多少条（1 忘得快，5 什么都记得）。
        val memoryLimit = 4 + persona.memoryRetention.coerceIn(1, 5) * 2
        val memories = memoryRepository.getRelevant(persona.id, query = recallQuery, limit = memoryLimit)
        // 只带着最近两条消息里的图片（审计 P2-9）：先按「数量预算」挑消息，再逐张解码，
        // 别为裁掉的消息解一遍。真正的双重限制（条数 / 单图尺寸 / 总字符预算）在
        // ImageContextBudget 里，纯逻辑单测钉住了上界。
        val latestUser = visible.lastOrNull { it.role == MessageRole.USER }
        // 附件只解一次：同一轮里预览、图片预算、占位正文都要用它。
        val attachmentsByMessage = visible.associate { it.id to AttachmentsCodec.decode(it.attachmentsJson) }
        val imageOwners = ImageContextBudget.ownersWithImages(
            visible.map {
                ImageContextBudget.Candidate(
                    messageId = it.id,
                    isUser = it.role == MessageRole.USER,
                    hasImages = attachmentsByMessage[it.id].orEmpty().any { attachment ->
                        attachment.isChatImage || attachment.isSticker
                    }
                )
            }
        )
        // 0.13：这一轮（最新一条用户消息）带的语音/视频要真的以多模态 parts 发出去。
        // 只发「当轮」：历史里同样的附件只留一句占位正文，base64 绝不重复（否则聊两句就把请求撑爆）。
        val roundMedia = audioVideoRepository.mediaInMessage(latestUser)
        var roundAudios: List<WireAudio> = emptyList()
        val roundFrames = mutableListOf<String>()
        var roundCaption: String? = null
        roundMedia.forEach { media ->
            when (media.kind) {
                AudioVideoKind.AUDIO -> audioVideoRepository.audioClip(media)?.let { clip ->
                    // 超过 8 MiB 的语音读不出来（见 MediaPayload.audioPart）：宁可这一轮听不到，
                    // 也不把一段 10MB 的 base64 硬塞给 provider。
                    roundAudios = listOf(WireAudio(base64 = clip.base64, format = clip.format))
                }
                AudioVideoKind.VIDEO -> {
                    val frames = audioVideoRepository.videoFrames(media)
                    if (frames.isNotEmpty()) {
                        roundFrames += frames
                        val duration = audioVideoRepository.videoDurationMs(media)
                            ?: media.attachment.durationMs
                        roundCaption = VideoFramePlan.caption(frames.size, duration ?: 0L)
                    }
                }
            }
        }
        val currentMediaBlock = AudioVideoText.currentBlock(
            roundMedia.map { media ->
                CurrentMedia(
                    mediaId = audioVideoRepository.mediaIdOf(media),
                    kind = media.kind,
                    durationMs = media.attachment.durationMs,
                    mime = media.attachment.mime
                )
            }
        )
        // 整个上下文里所有 data URL 的字符预算：塞不下就不再带（宁可少一张，也别把堆撑爆）。
        var usedImageChars = 0
        val history = visible.filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) &&
                (it.content.isNotBlank() || !it.attachmentsJson.isNullOrBlank()) &&
                it.status != MessageStatus.ERROR
        }.map { message ->
            val outgoing = message.role == MessageRole.USER
            val messageAttachments = attachmentsByMessage[message.id].orEmpty()
            val audio = messageAttachments.firstOrNull { it.isAudio }
            val video = messageAttachments.firstOrNull { it.isVideo }
            val isRound = outgoing && message.id == latestUser?.id
            val images = if (outgoing && message.id in imageOwners) {
                buildList {
                    // 语音/视频不进图片预算：它们的体积由 8 MiB / 8 帧单独管。
                    val visuals = messageAttachments
                        .filter { it.isChatImage || it.isSticker }
                        .take(ImageContextBudget.imagesForMessage(Int.MAX_VALUE))
                    for (attachment in visuals) {
                        // 发送前按目标尺寸重编码（长边 ≤1024、单张 ≤320KB），并且守住整体预算。
                        val dataUrl = MediaStorage.toContextDataUrl(attachment) ?: continue
                        if (!ImageContextBudget.acceptsEncoded(usedImageChars, dataUrl)) continue
                        add(dataUrl)
                        usedImageChars += dataUrl.length
                    }
                }
            } else {
                emptyList()
            }
            val baseContent = message.content.ifBlank {
                when {
                    // 只有语音/视频的消息：告诉模型这条消息里带着什么（历史里也一样，
                    // 让他在很久以后还能认出「那天你发过一段语音」）。
                    audio != null -> AudioVideoText.audioPlaceholder(audio.durationMs)
                    video != null -> AudioVideoText.videoPlaceholder(video.durationMs)
                    images.isNotEmpty() -> "（发来一张图片）"
                    // 0.13：表情消息常常没有正文（点选即发送）。模型读历史时要能认出「他/我发了一张表情」，
                    // 否则那条消息在上下文里就是个空气泡。
                    messageAttachments.any { it.isSticker } ->
                        if (outgoing) "（对方发来一张表情）" else "（你发了一张表情）"
                    else -> ""
                }
            }
            // 「视频：N 帧，时长 X 秒」写进正文，模型才知道自己看的是抽样画面。
            val body = if (isRound && roundCaption != null) {
                "$baseContent\n$roundCaption"
            } else {
                baseContent
            }
            // 0.15（`spec/context-profile/README.md` history=timestamped）：
            // 每条聊天原文前加 `[HH:mm] 用户：` / `[HH:mm] 他：`，跨天补 `MM-dd`。
            // 只给用户/人格说过的话加——工具结果与系统消息由各自的协议行负责，不在这里糊时间。
            val content = HistoryTimestamps.decorate(
                content = body,
                createdAt = message.createdAt,
                now = now,
                zone = ZoneId.systemDefault(),
                outgoing = outgoing
            )
            WireMessage(
                role = if (outgoing) "user" else "assistant",
                content = content,
                images = images + if (isRound) roundFrames.toList() else emptyList(),
                audios = if (isRound) roundAudios else emptyList()
            )
        }
        // 心情只读一次库：提示词那一行和「今天话多话少」都由这一个状态换算得出，不再各自去查。
        val mood = moodRepository.current(persona.id)
        val moodLine = MoodPrompt.line(mood)
        val moodNudge = moodRepository.reflectionNudge(persona.id).orEmpty()
        // 心情也决定他今天话多话少：只补很短的一行，不动人格设定，也不改回复长度的硬逻辑。
        val moodPace = MoodBehavior.promptLine(mood)
        // 他此刻在做什么：一行，很短，像他自己状态，不像状态栏。
        val presenceLine = presenceLine(persona.id)
        // 更早之前他记得的：只有真的压过（有摘要、也有压到哪一条的时间点）才注入。
        val memoryOfEarlier = HistoryCompression.memoryBlock(session.historySummary, session.historySummarizedUpTo)
        // 0.15：刚想过的私密念头 + 三段会话缓存（长期 / 短期 / 主题总结）。
        val mentalBlock = runCatching { mentalActivityRepository.promptBlock(persona.id, now) }.getOrDefault("")
        val memo = runCatching { sessionMemoryRepository.snapshot(session.id) }
            .getOrDefault(SessionMemoryRepository.Snapshot())
        // 上一轮 `memorize` 想起来的事：只注入一次，取走即清空。
        val memorized = runCatching { memorizeRepository.takePending(session.id) }.getOrNull().orEmpty()
        // 拉黑提示：即使这一轮不生成，主动/心理活动的上下文也能知道他「为什么不想理对方」。
        val blockNote = persona.blockedUntil
            ?.takeIf { BlockPolicy.isBlocked(it, now) }
            ?.let { "\n\n" + BlockPolicy.promptNote(it, persona.blockReason, now) }
            .orEmpty()
        // 多维心情（schema v17）：与 moodLine 来自同一个实体，但会带上 valence/arousal/dominance。
        // 读失败（老库/坏数据）时返回按旧标签折算的基准值，不会让提示词少一段。
        val moodReading = runCatching { moodRepository.reading(persona.id, now) }.getOrNull()
        val card = personaCardRepository.readCard(persona)
        val minuteOfDay = runCatching {
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        }.getOrDefault(12 * 60)
        val asleep = runCatching { personaCardRepository.isAsleep(persona, minuteOfDay) }.getOrDefault(false)
        // 表情清单：最近 20 张，只给 id 与「动图/静态」，不给文件路径（模型不需要、也不该看到本机路径）。
        val stickerPromptBlock = runCatching {
            StickerPromptBlock.build(stickerRepository.recent(20).map { it.toRecord() })
        }.getOrDefault("")
        // 0.15：提示词语言由**人格自己**决定（与界面语言无关）。
        val personaLanguage = PromptLanguage.resolve(persona.languageTag)
        // 0.16 迁移兼容（`spec/tool-call-agents/README.md` §3）：上下文里还有「旧版本直接说的」
        // assistant 消息时补一句提醒。旧消息原样保留、不补造任何工具痕迹——只提醒它「从现在起必须走工具」。
        val legacyContext = ToolTraceCodec.hasLegacyReply(
            visible.filter { it.role == MessageRole.ASSISTANT }.map { it.toolTraceJson }
        )
        val migrationNote = AgentReplyProtocol.migrationNote(legacyContext, personaLanguage)
        val promptInputs = PromptBuilder.Inputs(
            name = persona.name,
            age = card.identity.age,
            role = card.identity.role,
            relation = card.identity.relation,
            address = card.voice.address,
            card = card,
            legacyPrompt = persona.prompt,
            profileBlock = PersonaProfile.promptBlock(persona),
            world = WorldPresetCodec.decode(persona.worldJson),
            speech = SpeechStyleCodec.decode(persona.speechStyleJson),
            activity = PersonaActivityCodec.decode(persona.activityJson),
            now = now,
            zone = ZoneId.systemDefault(),
            moodBlock = moodLine,
            moodDetail = moodReading,
            asleep = asleep,
            extraStatus = listOf(moodPace, presenceLine(persona.id)).filter { it.isNotBlank() }
                .joinToString("\n"),
            // 「# 你能做什么」：从 ToolProtocol 的工具注册表生成；标题/描述按人格语言。
            capabilitiesSection = ToolCatalog.capabilityPrompt(
                webEnabled = config.webToolsEnabled,
                language = personaLanguage
            ),
            // 0.15 模型优化开关（schema v20）：DeepSeek 才追加「# 工具使用」规则段，
            // GLM 是 lean；AUTO 按这一轮真正要发的 model 名推断（含 glm → GLM）。
            modelProfile = ModelProfile.fromStored(persona.modelProfile),
            modelName = session.model.ifBlank { config.model },
            webToolsEnabled = config.webToolsEnabled,
            languageSection = PromptBuilder.languageSectionFor(persona.languageTag),
            language = personaLanguage
        )
        // 0.15 上下文装配 v2 + state_first profile（docs/design/context-memory-v2.md、
        // spec/context-profile/README.md）：
        // 当前状态 → 引导 → 人设 → 工具 → 反 AI → 备忘录 → 附加块 → 语言 → 边界 → 剩余预算给原文。
        // 每段都由 PromptBuilder 的可组合段函数生成，这里只负责把它们按顺序交给 ContextAssembly。
        val assembly = ContextAssembly.assemble(
            ContextAssembly.Inputs(
                state = PromptBuilder.currentStateBody(promptInputs),
                guidance = PromptBuilder.guidanceBlock(promptInputs),
                persona = PromptBuilder.personaBlock(promptInputs),
                tools = PromptBuilder.toolsBlock(promptInputs),
                antiAi = PromptBuilder.antiAiSection(personaLanguage),
                memo = ContextAssembly.Memo(
                    longTerm = memo.longSummary,
                    shortTerm = memo.shortSummary,
                    topics = memo.topics
                ),
                language = PromptBuilder.languageBlock(promptInputs),
                boundaries = PromptBuilder.boundaries(personaLanguage),
                extras = listOfNotNull(
                    // 0.16 迁移提示：只在历史里真有旧版本直出的消息时出现（否则一个字都不多）。
                    migrationNote,
                    // 长期记忆库（他不是摘要，检索出来的是「他记得的具体事」）。
                    memoryRepository.formatForPrompt(memories).takeIf { memories.isNotEmpty() },
                    // 这一轮带语音/视频：给模型 mediaId 与类型，它才知道可以 listen/watch。
                    currentMediaBlock.takeIf { it.isNotBlank() },
                    memoryOfEarlier.takeIf { it.isNotBlank() },
                    // 上一轮 `memorize` 的 <回忆：…>（只注入一次）。
                    memorized.takeIf { it.isNotBlank() },
                    mentalBlock.takeIf { it.isNotBlank() },
                    blockNote.takeIf { it.isNotBlank() },
                    // 「我的表情」：`sticker` 工具的 send 只认 id，模型必须看得到有哪些 id 可用。
                    stickerPromptBlock.takeIf { it.isNotBlank() },
                    "---\n" + ProactiveProtocol.CAPABILITY_PROMPT,
                    moodNudge.takeIf { it.isNotBlank() }
                )
            )
        )
        val trimmed = ContextAssembly.trimHistory(history, assembly.historyCharLimit) { it.content.length }
        // 0.15：人格语言不是中文时，段标题也换成英文（正文是用户写的数据，不翻译）。
        val system = PromptBuilder.localizeSectionTitles(assembly.system, personaLanguage)
        return listOf(WireMessage("system", system)) + trimmed
    }

    /**
     * 「他此刻在做什么」的那一行。
     *
     * 只是让语气对得上此刻：他在忙、刚睡醒、正想着事，回话的样子都不一样。
     * 用 [PresenceRepository.current] 要一个一次性快照（不订阅、不留协程），
     * 拿到的是他自己视角的说法，直接拼进 system 不会像在讲别人。
     * 拿不到或者没内容就当没有这一行——状态不该成为聊天里的一个障碍。
     */
    /**
     * 「这个模型听不了语音 / 看不了视频」的落库令牌。
     *
     * 只认「这一轮真的带了媒体」+「provider 回 4xx」这两个条件：
     * - 400 / 415 / 422：绝大多数 OpenAI 兼容网关在收到不认识的 content part 时给这三个码；
     * - 413：请求太大（8 MiB 音频的 base64 会更大），说法不同；
     * - 其它（401/403/404/429/5xx）与媒体能力无关，按原错误显示。
     *
     * 返回 null 表示「不是媒体能力问题」，调用方回落成普通的错误令牌。
     */
    private fun mediaFailureToken(error: Throwable, kind: AudioVideoKind?): String? {
        val media = kind ?: return null
        val api = error as? ApiException ?: return null
        val status = (api.kind as? NetworkErrorKind.Http)?.status ?: return null
        return when (status) {
            413 -> MediaErrorTokens.encodeTooLarge(media)
            400, 415, 422 -> MediaErrorTokens.encode(media)
            else -> null
        }
    }

    private suspend fun presenceLine(personaId: String): String = runCatching {
        // 这一段现在并进 PromptBuilder 的「你此刻的状态」，只返回正文，不再自己带标题。
        presenceRepository.current(personaId).detail.trim()
    }.getOrDefault("")

    private suspend fun refreshPreview(session: ChatSessionEntity) {
        val latest = messageDao.latestInSession(session.id)
        val now = System.currentTimeMillis()
        sessionDao.updatePreview(
            session.id,
            latest?.content?.replace('\n', ' ')?.trim()?.take(100)?.ifBlank {
                if (latest?.status == MessageStatus.ERROR) {
                    appContext.getString(R.string.chat_preview_failed)
                } else {
                    null
                }
            },
            latest?.createdAt,
            now
        )
    }

    private suspend fun nextTimestamp(sessionId: String): Long =
        MessageOrder.nextTimestamp(
            latestCreatedAt = messageDao.latestInSession(sessionId)?.createdAt,
            now = System.currentTimeMillis()
        )

    private fun autoTitle(content: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 26) flat else flat.take(26) + "…"
    }

    private fun sanitizeFile(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(30).ifBlank { "chat" }

    private companion object {

        /** 聊天导出的 schema 版本（与 `ExportBundle` 的默认值一致）。 */
        const val EXPORT_SCHEMA_VERSION = 1

        /** 一条消息最多带几张图（与 `ImageContextBudget` 的预算一致）。 */
        const val MAX_IMAGES_PER_MESSAGE = ImageContextBudget.MAX_IMAGES_PER_MESSAGE

        /**
         * 服务端消息正文不能为空；只有图片时发这个不可见分隔符（U+2063）。
         *
         * 用不可见字符而不是「图片」两个字：界面上这条消息本来只该显示图，
         * 多一行「图片」会像系统占位符，破坏「他收到的是你拍的东西」这件事。
         */
        const val IMAGE_ONLY_CONTENT = "\u2063"

        /**
         * 装配上下文时最多从库里读多少条消息。
         *
         * 不是「只记得这么多」——更早的部分由 [HistoryCompressor] 收成一段摘要放进 system；
         * 这里只是别让一次聊天把整段历史读进内存（几百条够覆盖两万字预算了）。
         */
        const val CONTEXT_RECENT_LIMIT = 200
    }
}
