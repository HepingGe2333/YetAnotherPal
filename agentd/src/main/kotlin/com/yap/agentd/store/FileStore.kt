package com.yap.agentd.store

import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import com.yap.agentd.store.StoreJson.optArr
import com.yap.agentd.store.StoreJson.optInt
import com.yap.agentd.store.StoreJson.optLong
import com.yap.agentd.store.StoreJson.optObj
import com.yap.agentd.store.StoreJson.optString
import com.yap.agentd.store.StoreJson.reqLong
import com.yap.agentd.store.StoreJson.reqString
import com.yap.agentd.store.StoreJson.stringList
import java.io.BufferedWriter
import java.io.IOException
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `agentd` 的服务端状态存储：**纯文件 + JSON，零第三方依赖**（不引 SQLite/JDBC）。
 *
 * 设计取舍（`docs/design/server-authoritative.md` §4 要求「先用 SQLite」，本阶段改成文件存储）：
 * - 这个服务是**一个人一台服务端**，写并发只有「一个聊天请求 + 一个心跳」量级，不需要数据库的
 *   事务/索引能力；而零第三方依赖是本模块的硬约束（发行包只有 `agentd.jar + agentcore.jar`），
 *   引 `sqlite-jdbc` 就会破坏它。代价是：没有事务、没有并发多写、查询靠扫块（见 [Risk] 说明）。
 * - 唯一真相源是磁盘：进程重启后全部从文件读回（`meta.json` 的 `version` 对不上就拒绝启动，
 *   不静默重建）。
 *
 * 目录结构（`--data <path>`，默认 `./agentd-data`）：
 * ```
 * <root>/
 *   meta.json                        {"version":1,"cursor":<long>,"seeded":<bool>}
 *   personas.json                    {"personas":[...]}
 *   sessions.json                    {"sessions":[...]}
 *   settings.json                    {"settings":{...}}
 *   api_configs.json                 {"configs":[...]}          ← 只放元数据，**没有 key**
 *   secrets.json                     {"keys":{...}}             ← 0600，仅服务端内部读，永不下发
 *   moods.json / presence.json       {"moods":[...]} / {"presence":[...]}
 *   usage.json                       {"usage":{personaId:{...}}}
 *   events.jsonl                     每行一个事件（增量游标；append-only）
 *   sessions/<sessionId>/block_index.json   {"sessionId":…,"blocks":[{index,firstMessageId,…}]}
 *   sessions/<sessionId>/blocks/<blockIndex>.jsonl   每行一条消息（append-only）
 *   media.json                      {"media":[{id,kind,personaId,mime,bytes,sha256,createdAt}]}
 *   media/<id>.<ext>                图片原始字节（扩展名由 mime 决定）
 * ```
 *
 * 写入的原子性：
 * - 所有「整体替换」的 JSON 文件（meta/personas/sessions/block_index/moods/…）都是
 *   **先写 `*.tmp` + `force`，再 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`**；
 * - 消息是 append-only：追加一行到 `.jsonl`，不重写历史文件；删除/标已读才整块重写（同样原子替换）；
 * - 所有写操作共用一把 [ReentrantLock]（单写锁），读也在同一把锁下，避免读到半个块表；
 * - 崩溃窗口：消息行已追加、但 `block_index.json`/`sessions.json` 还没落盘时进程被杀，块计数会少 1。
 *   恢复办法是删掉该会话的 `block_index.json` 让服务端按块文件重建（见 [rebuildBlockIndex]），
 *   这一步**不会**自动做（不静默改数据）。`events.jsonl` 的末尾半行在读取时会被跳过。
 *
 * 块维护（与 `spec/messages-blocks/README.md` 同构，`BLOCK_SIZE = 200`）：
 * - 追加：块满 200 → 开 `blockIndex + 1`；新消息进最新块；
 * - 删除：重算该块的 first/last/count；**块空了就删文件**，块表里删掉这一行 → 编号留空洞；
 * - **不重平衡**：块可以变短，只有最新块在追加时增长；「上一块/下一块」按块表里的相邻编号找，
 *   所以空洞不影响分页。
 */
class FileStore(
    val root: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
    seedIfEmpty: Boolean = true
) {

    companion object {
        /** 数据目录版本；结构一改就要 +1，并且 [FileStore] 要能读旧版本或明确拒绝。 */
        const val VERSION = 1

        /** 一块最多多少条消息（与客户端 `BLOCK_SIZE` 一致）。 */
        const val BLOCK_SIZE = 200

        /** 事件日志常驻内存的条数上限；更早的仍然按需从 `events.jsonl` 读回。 */
        const val MAX_EVENTS_IN_MEMORY = 20_000

        /** `GET /v1/state` 一次最多返回多少条消息（超出时 `hasMoreMessages = true`）。 */
        const val MAX_STATE_MESSAGES = 500

        /**
         * `personas.activityJson` 的长度上限（字符）。
         *
         * 活动是一句话（客户端 `PersonaActivity` 里 `activity` 最多 160 字），四格 JSON 撑不到
         * 1 KiB。上限存在的意义是「不许借这个字段往 personas.json 里塞大块数据」。
         */
        const val MAX_ACTIVITY_JSON_CHARS = 1024

        /** `personas.blockReason` 的长度上限（只给自己看的一句话，不该成为长文存储）。 */
        const val MAX_BLOCK_REASON_CHARS = 200

        /** 会话/人格 id 只允许 `[A-Za-z0-9_-]`：它们会拼进文件路径，不能接受 `..` / `/`。 */
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /** 消息 id 不拼路径，但要防超长/奇怪值；客户端 UUID 与服务端 UUID 都满足。 */
        private val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        const val META_FILE = "meta.json"
        const val PERSONAS_FILE = "personas.json"
        const val SESSIONS_FILE = "sessions.json"
        const val SETTINGS_FILE = "settings.json"
        const val API_CONFIGS_FILE = "api_configs.json"
        const val SECRETS_FILE = "secrets.json"
        const val MOODS_FILE = "moods.json"
        const val PRESENCE_FILE = "presence.json"
        const val USAGE_FILE = "usage.json"
        const val PROACTIVE_STATE_FILE = "proactive_state.json"
        const val MEMORIES_FILE = "memories.json"
        const val PRESETS_FILE = "presets.json"
        const val PUSH_TOKENS_FILE = "push_tokens.json"

        /** 0.13：人格进化待审补丁（`POST /v1/personas/{id}/evolution/evaluate` 的产物）。 */
        const val PENDING_PATCHES_FILE = "pending_patches.json"
        const val MEDIA_METADATA_FILE = "media.json"
        const val EVENTS_FILE = "events.jsonl"

        /** 0.13：表情包（`spec/stickers`）。字节在 `media/`，这里只有元数据。 */
        const val STICKERS_FILE = "stickers.json"

        /** 0.13：日记板（`spec/diary`）。 */
        const val DIARY_NOTES_FILE = "diary_notes.json"

        /** 0.13：`defer_reply` 留下的念头队列（到点由心跳变成主动消息）。 */
        const val INTENTS_FILE = "agent_intents.json"

        /** 0.13：自动判定的游标（每 4 条用户消息判心情、每 20 条评估进化）。 */
        const val AUTO_STATE_FILE = "auto_state.json"

        /** 0.14：`block_user` 还没确认的拉黑提议（两轮确认；10 分钟过期）。 */
        const val BLOCK_PROPOSALS_FILE = "block_proposals.json"

        /** 0.14：心理活动（他私下的念头 + 心情快照；每个人格只留最近 50 条）。 */
        const val MENTAL_ACTIVITIES_FILE = "mental_activities.json"

        /** 0.14：会话级「他的备忘录」（短期/长期摘要 + 0.15 的对话主题）。 */
        const val SESSION_MEMORY_FILE = "session_memory.json"

        /** 0.15：没发出去的主动消息（生成/落库/投递/推送四类失败，只留最近 200 条）。 */
        const val PROACTIVE_FAILURES_FILE = "proactive_failures.json"

        /**
         * 0.14.1：**客户端独有**的备份段（`proactiveRules[]` / `memoryConsolidations[]` 以及
         * 客户端将来新增的未知顶层段）原样存在这里，导出时原样带回（`spec/experience` §11）。
         *
         * 为什么单独一个文件而不是塞进 `settings.json`：它是**备份的透传数据**，不是服务端设置；
         * 单独文件也方便运维看懂「这些不是服务端的，是客户端带来的」。
         */
        const val CLIENT_EXTRAS_FILE = "client_extras.json"

        /** 一份备份里最多透传多少个客户端独有段（挡住「用未知段把数据目录撑爆」）。 */
        const val MAX_CLIENT_EXTRAS_SECTIONS = 64

        /** 透传的客户端段总大小上限（序列化后）；超出的段整段跳过，不影响导入。 */
        const val MAX_CLIENT_EXTRAS_BYTES = 8L * 1024 * 1024

        /**
         * 备份里媒体二进制的上限：单个文件 8 MiB、全部合计 128 MiB（按**原始字节**算）。
         *
         * 超限的媒体在导出时**跳过并计数**（`mediaSkipped`），不让一份备份因为一张大图
         * 变成几百 MiB 的 base64；导入时超过同样上限则拒绝整份备份（宁可报错，不要静默丢图）。
         */
        const val MAX_BACKUP_MEDIA_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_BACKUP_MEDIA_TOTAL_BYTES = 128L * 1024 * 1024

        /**
         * 当前导出的备份格式版本（与客户端 `BACKUP_FORMAT_VERSION` 对齐）。
         *
         * 0.13 起是 **6**：顶层加 `stickers[]` / `diaryNotes[]`，`media[]` 的 kind 扩到
         * `STICKER`/`AUDIO`/`VIDEO` 并改用 `fileName` 关联，段内字段名全部换成客户端 v6 的
         * （`unreadCount` / `attachmentsJson` / `toolTraceJson` …）。v1–v5 仍然可以导入。
         */
        const val BACKUP_FORMAT_VERSION = 7
        /** 跨版本/跨端备份格式标记；不随品牌更名，否则旧备份无法导入。 */
        const val BACKUP_FORMAT_NAME = "myfriend.backup"

        /** `GET /v1/settings` / 备份可携带的设置键；任何 API Key / secrets 都不在白名单里。 */
        val SETTINGS_WHITELIST: Set<String> = setOf(
            "themeMode",
            "accentColor",
            "fontScale",
            "bubbleStyle",
            "animationsEnabled",
            "hapticsEnabled",
            "chatBackgroundColor",
            "agentLoopEnabled",
            "activePersonaId",
            "autoTranslatePersona",
            // 用户自己的头像（`/v1/media/<id>`）。客户端切换远程后要能把它带到服务端，
            // 所以进白名单；它是**媒体引用**，不是密钥，随备份一起走。
            // `myAvatarUri` 是历史别名，读写都会被归一成 `userAvatarUri`（见 SETTINGS_ALIASES）。
            "userAvatarUri",
            "myAvatarUri"
        )

        /**
         * 备份导入的临时目录（相对数据目录）：请求体先流式落盘到这里，媒体逐条解码成
         * `media-<n>.bin`，提交时再原子移进 `media/`。崩溃留下的残渣在下次打开时清掉。
         */
        const val STAGING_DIR = ".staging"

        const val MODE_MERGE = "merge"
        const val MODE_REPLACE = "replace"

        /** 一条消息批量导入接口的单批上限（与 `CrudRoutes` 的校验共用一份）。 */
        const val MAX_IMPORT_MESSAGES = 200

        /** 一份备份里允许的最大消息条数（挡住「用 1 亿条消息的备份把内存/磁盘打满」）。 */
        const val MAX_BACKUP_MESSAGES = 1_000_000

        /** 0.14.1：`message_queue.json`（延迟投递队列，重启不丢）。 */
        const val MESSAGE_QUEUE_FILE = "message_queue.json"

        /** 队列里最多保留多少条待投递（单人部署的正常量级是 0–4 条）。 */
        const val MAX_PENDING_DELIVERIES = 1000

        /** 消息（`createdAt` 相同则按 id）的稳定顺序：块内、分页、备份都按它排。 */
        val MESSAGE_ORDER: Comparator<Message> = compareBy({ it.createdAt }, { it.id })

        /**
         * 设置键的历史别名 → 规范键。
         *
         * `myAvatarUri` 是最早讨论「我的头像」时用的名字，客户端最终统一到
         * `userAvatarUri`；服务端**接受两者、只存/只回规范键**，这样备份与跨端还原不会出现
         * 同一份头像两条记录。
         */
        val SETTINGS_ALIASES: Map<String, String> = mapOf("myAvatarUri" to "userAvatarUri")

        /** 设置键的规范形态（未知键原样返回，调用方再用白名单过滤）。 */
        fun canonicalSettingKey(key: String): String = SETTINGS_ALIASES[key] ?: key
        const val SESSIONS_DIR = "sessions"
        const val BLOCKS_DIR = "blocks"
        const val BLOCK_INDEX_FILE = "block_index.json"
        const val MEDIA_DIR = "media"

        /**
         * 播种人格的固定 id 与中英文名对。
         *
         * 为什么不用 Android 的 `SeedPersonas`：它从 `R.string` 读名字，`:agentd` 没有 Android 资源。
         * 服务端自己定义一套**稳定 id**（客户端可以把它们当成普通人格；名字与 Android 种子一致，
         * 便于两端对照），写进 README。
         */
        const val SEED_PERSONA_1_ID = "seed-linshen"
        const val SEED_PERSONA_2_ID = "seed-alan"
        const val SEED_PERSONA_3_ID = "seed-mo"
        val SEED_PERSONA_IDS = listOf(SEED_PERSONA_1_ID, SEED_PERSONA_2_ID, SEED_PERSONA_3_ID)

        /** 空目录播种时附带的示例会话（让 `curl` 冒烟不用先调「建会话」接口）。 */
        const val SEED_SESSION_ID = "seed-session-linshen"

        /** 安全 id 校验：路由层和存储层各挡一次（防路径穿越）。 */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        fun open(
            root: Path,
            clock: () -> Long = { System.currentTimeMillis() },
            seedIfEmpty: Boolean = true
        ): FileStore = FileStore(root, clock, seedIfEmpty)
    }

    // ------------------------------------------------------------------ 内存状态
    //
    // 消息正文不进内存（按块从磁盘读）；这里只缓存元数据，保证「一页一块」的内存占用与历史长度无关。

    private val lock = ReentrantLock()
    private val eventMonitor = Object()
    private val sessionsDir: Path = root.resolve(SESSIONS_DIR)
    private val mediaDir: Path = root.resolve(MEDIA_DIR)
    private val eventsFile: Path = root.resolve(EVENTS_FILE)

    private var meta: StoreMeta = StoreMeta()
    private val personas = mutableListOf<Persona>()
    private val sessions = LinkedHashMap<String, Session>()
    private val blockCache = HashMap<String, MutableList<BlockMeta>>()
    private val messageIndexCache = HashMap<String, SessionMessageIndex>()
    private val moods = LinkedHashMap<String, Mood>()
    private val presence = LinkedHashMap<String, Presence>()
    private val apiConfigs = LinkedHashMap<String, ApiConfig>()
    private var settings: JsonValue.Obj = JsonValue.Obj()
    private var usage = LinkedHashMap<String, UsageTotal>()
    private val proactiveStates = LinkedHashMap<String, ProactiveState>()
    private val memories = LinkedHashMap<String, Memory>()
    private val presets = LinkedHashMap<String, Preset>()
    /** 人格进化待审补丁（`pending_patches.json`）；`accept`/`reject` 后从内存与磁盘一起删。 */
    private val pendingPatches = LinkedHashMap<String, PendingPatch>()
    /** key = `platform|token`，保证「同一 token 重复注册」幂等。 */
    private val pushTokens = LinkedHashMap<String, PushRegistration>()

    /** 表情包元数据（`stickers.json`）；字节走 [media]，`Sticker.id` 就是媒体 id。 */
    private val stickers = LinkedHashMap<String, Sticker>()

    /** 日记板（`diary_notes.json`）。 */
    private val diaryNotes = LinkedHashMap<String, DiaryNote>()

    /** `defer_reply` 的念头队列（`agent_intents.json`）。 */
    private val intents = LinkedHashMap<String, StoredIntent>()

    /** 自动判定游标（`auto_state.json`）。 */
    private val autoStates = LinkedHashMap<String, AutoState>()

    /** 0.14：还没确认的拉黑提议（`block_proposals.json`，key = personaId）。 */
    private val blockProposals = LinkedHashMap<String, BlockProposal>()

    /** 0.14：心理活动（`mental_activities.json`）；每个人格只保留最近 50 条。 */
    private val mentalActivities = LinkedHashMap<String, MentalActivity>()

    /** 0.14：会话记忆（`session_memory.json`，key = sessionId）。 */
    private val sessionMemories = LinkedHashMap<String, SessionMemory>()

    /** 0.15：主动消息的失败记录（`proactive_failures.json`，key = id；只留最近 200 条）。 */
    private val proactiveFailures = LinkedHashMap<String, ProactiveFailure>()

    /**
     * 0.14.1：客户端独有的备份段（`proactiveRules` / `memoryConsolidations` / 未知顶层段），
     * **原样**往返（导入时抓下来、导出时原样写回）。键 → 原始 `JsonValue`。
     */
    private val clientExtras = LinkedHashMap<String, JsonValue>()

    /** 0.14.1：待投递消息（`message_queue.json`，key = deliveryId）。 */
    private val deliveries = LinkedHashMap<String, PendingDelivery>()

    /** 会话用户消息计数缓存：`sessionId → (总用户消息, 其中 blocked)`（见 `countUserMessagesInSession`）。 */
    private val userMessageCounts = HashMap<String, Pair<Long, Long>>()

    /**
     * 媒体元数据索引（`data/media.json`）。二进制在 [mediaDir] 里，文件名 `<id>.<ext>`。
     * 内存索引和磁盘清单一起更新（同一把写锁 + 原子写），重启后按清单读回。
     */
    private val media = LinkedHashMap<String, MediaItem>()

    /** 内存里的事件窗口（最早的会被裁剪；更早的从 [eventsFile] 读回）。 */
    private val events = ArrayDeque<StoredEvent>()

    private var cursor: Long = 0
    private var lastTimestamp: Long = 0

    /** 本次打开是否新建了数据目录并播种（`main` 用它打印一行提示）。 */
    val seeded: Boolean

    /** 媒体目录（`<root>/media/`）；上传的文件是 `<id>.<ext>`，清单在 [MEDIA_METADATA_FILE]。 */
    val mediaDirectory: Path get() = mediaDir

    init {
        Files.createDirectories(root)
        Files.createDirectories(sessionsDir)
        Files.createDirectories(mediaDir)
        // 上次导入/导出如果进程被杀，`.staging/` 里会留下请求体与媒体暂存文件：
        // 它们不是数据（提交前不落 `media/`），启动时直接清掉，避免占盘。
        deleteRecursively(root.resolve(STAGING_DIR))
        val metaJson = readJsonFile(root.resolve(META_FILE))
        if (metaJson == null) {
            if (hasExistingData()) {
                throw StoreCorruptionException(
                    "数据目录 $root 里有数据但缺少 $META_FILE；不要手工删除它（要么恢复它，要么换一个空目录）"
                )
            }
            if (seedIfEmpty) {
                seedFreshData()
                seeded = true
            } else {
                writeEmptyBaseline()
                seeded = false
            }
        } else {
            loadExistingData(metaJson)
            seeded = false
        }
    }

    // ================================================================== 读：元数据

    fun meta(): StoreMeta = lock.withLock { meta }

    /** 最新的全局事件游标（`GET /v1/state` 的 `serverCursor`）。 */
    fun cursor(): Long = lock.withLock { cursor }

    /** 服务端当前时间（毫秒）。测试注入假时钟，所以路由不要直接调 `System.currentTimeMillis()`。 */
    fun serverTime(): Long = clock()

    fun personas(): List<Persona> = lock.withLock { personas.toList() }

    fun persona(personaId: String): Persona? = lock.withLock { personas.firstOrNull { it.id == personaId } }

    fun defaultPersonaId(): String = lock.withLock { defaultPersonaIdLocked() }

    fun sessions(): List<Session> = lock.withLock { sessions.values.toList() }

    fun session(sessionId: String): Session? = lock.withLock {
        if (!isValidId(sessionId)) return@withLock null
        sessions[sessionId]
    }

    fun mood(personaId: String): Mood? = lock.withLock { moods[personaId] }

    fun moods(): List<Mood> = lock.withLock { moods.values.toList() }

    fun presence(personaId: String): Presence? = lock.withLock { presence[personaId] }

    /**
     * 读他此刻的活动（`personas.json` 里的 `activityJson`，原样返回）。
     *
     * 不做解析也不做过期判断：`ActivityState`（`proactive/`）负责解析，读取方按 `now` 判断
     * 「有没有过期」。没设过就是 `null`。
     */
    fun activity(personaId: String): String? = lock.withLock {
        personas.firstOrNull { it.id == personaId }?.activityJson
    }

    fun usage(personaId: String): UsageTotal = lock.withLock { usage[personaId] ?: UsageTotal() }

    fun usageAll(): Map<String, UsageTotal> = lock.withLock { LinkedHashMap(usage) }

    /**
     * 读某个人的主动记录；[today] 是**服务端本地自然日**（`yyyy-MM-dd`）。
     *
     * 跨日时只把计数当成 0（不立刻写盘）：没有真正开口的一天不该因为一次读就产生写操作。
     * `lastProactiveAt` 原样返回，冷却期不会在零点被清掉。
     */
    fun proactiveState(personaId: String, today: String): ProactiveState = lock.withLock {
        effectiveProactiveStateLocked(personaId, today)
    }

    /**
     * 落一次「真的主动开口了」：计数 +1、日期翻新、`lastProactiveAt=at`，**同一把存储锁内**
     * 更新内存并原子写 `proactive_state.json`。返回更新后的记录。
     */
    fun recordProactiveFired(personaId: String, at: Long, today: String): ProactiveState = lock.withLock {
        requirePersonaLocked(personaId)
        recordProactiveFiredLocked(personaId, at, today)
    }

    /**
     * 写入 / 清掉「他此刻在做什么」（`POST /v1/personas/{id}/activity` 的落库入口）。
     *
     * - [activityJson] 为 `null` 表示清掉（读到就是「没在忙」）；
     * - **同一把写锁内**改内存 + 原子重写 `personas.json`（与 `mood/presence/proactive_state`
     *   完全一致的做法：`*.tmp` → `ATOMIC_MOVE`，重启后读回同一份）；
     * - 顺手把 `updatedAt` 抬到新的时间戳：`GET /v1/state?since=` 靠 `updatedAt` 过滤人格，
     *   不抬的话客户端增量同步看不到活动变化。
     *
     * 参数校验只做「人格存在 + 长度上限」：结构归一化（枚举、分钟夹取）在路由层，
     * 存储层不猜调用方想表达什么。
     */
    fun updateActivity(personaId: String, activityJson: String?): Persona = lock.withLock {
        requirePersonaLocked(personaId)
        if (activityJson != null && activityJson.length > MAX_ACTIVITY_JSON_CHARS) {
            throw IllegalArgumentException("activityJson 最长 $MAX_ACTIVITY_JSON_CHARS 个字符")
        }
        val index = personas.indexOfFirst { it.id == personaId }
        val updated = personas[index].copy(
            activityJson = activityJson?.takeIf { it.isNotBlank() },
            updatedAt = nextTimestampLocked()
        )
        personas[index] = updated
        persistPersonasLocked()
        updated
    }

    /**
     * 新建人格。id 由调用方给（可空→生成），时间戳由存储层给；创建与落盘同锁。
     *
     * 路由层通过 [transform] 拿到 now，把请求字段拼成 [Persona]；这里再统一覆盖
     * `id/createdAt/updatedAt`，避免客户端伪造时间线。
     */
    fun createPersona(
        requestedId: String?,
        transform: (now: Long) -> Persona
    ): Persona = lock.withLock {
        val id = requestedId ?: newId()
        if (!isValidId(id)) throw IllegalArgumentException("非法的人格 id")
        if (personas.any { it.id == id }) throw IllegalArgumentException("人格 id 已存在：$id")
        val now = nextTimestampLocked()
        var persona = transform(now).copy(id = id, createdAt = now, updatedAt = now)
        if (persona.name.isBlank()) throw IllegalArgumentException("name 不能为空")
        if (persona.isDefault) {
            for (index in personas.indices) personas[index] = personas[index].copy(isDefault = false)
        }
        personas += persona
        persistPersonasLocked()
        persona
    }

    /**
     * 更新人格：读-改-写同一把锁。`transform` 收到当前值和 now，返回新值；核心 `id/createdAt`
     * 由存储层钉住，`updatedAt` 由存储层抬到 now（`/v1/state?since=` 靠它做增量）。
     */
    fun updatePersona(
        personaId: String,
        transform: (existing: Persona, now: Long) -> Persona
    ): Persona = lock.withLock {
        if (!isValidId(personaId)) throw IllegalArgumentException("非法的人格 id")
        val index = personas.indexOfFirst { it.id == personaId }
        if (index < 0) throw StorePersonaNotFoundException(personaId)
        val now = nextTimestampLocked()
        val updated = transform(personas[index], now).copy(
            id = personaId,
            createdAt = personas[index].createdAt,
            updatedAt = now
        )
        if (updated.name.isBlank()) throw IllegalArgumentException("name 不能为空")
        if (updated.isDefault) {
            for (i in personas.indices) {
                if (i != index) personas[i] = personas[i].copy(isDefault = false)
            }
        }
        personas[index] = updated
        persistPersonasLocked()
        updated
    }

    /**
     * 删除人格并级联清理：
     * - 该人格的会话目录（消息块 + 块表）与 `sessions.json` 行；
     * - 记忆、活动（活动就在 personas.json 里，随人格一起消失）、主动计数；
     * - 心情/在场（否则会留下无主记录）。
     *
     * **媒体文件不删**（`media/` 由客户端 GC，契约 §3.6）；usage / API 配置是服务端资源，
     * 也不跟着人格走。返回是否真的存在过。
     */
    fun deletePersona(personaId: String): Boolean = lock.withLock {
        if (!isValidId(personaId)) return@withLock false
        val index = personas.indexOfFirst { it.id == personaId }
        if (index < 0) return@withLock false
        personas.removeAt(index)

        val doomedSessions = sessions.values.filter { it.personaId == personaId }.map { it.id }
        for (sessionId in doomedSessions) {
            sessions.remove(sessionId)
            blockCache.remove(sessionId)
            messageIndexCache.remove(sessionId)
            invalidateUserMessageCountsLocked(sessionId)
            deleteRecursively(sessionDir(sessionId))
        }
        moods.remove(personaId)
        presence.remove(personaId)
        proactiveStates.remove(personaId)
        memories.entries.removeAll { it.value.personaId == personaId }
        // 0.14：心理活动、会话记忆、拉黑提议都跟着人格/会话一起消失（不留无主状态）。
        mentalActivities.entries.removeAll { it.value.personaId == personaId }
        sessionMemories.entries.removeAll { it.value.personaId == personaId }
        blockProposals.remove(personaId)
        proactiveFailures.entries.removeAll { it.value.personaId == personaId }
        // 待审的进化补丁跟着人格一起消失（否则会留下一条永远无法 accept 的孤儿）。
        val doomedPatches = pendingPatches.values.filter { it.personaId == personaId }.map { it.id }
        for (id in doomedPatches) pendingPatches.remove(id)
        if (doomedPatches.isNotEmpty()) persistPendingPatchesLocked()

        persistPersonasLocked()
        persistSessionsLocked()
        persistMoodsLocked()
        persistPresenceLocked()
        persistProactiveStateLocked()
        persistMemoriesLocked()
        persistMentalActivitiesLocked()
        persistSessionMemoryLocked()
        persistBlockProposalsLocked()
        persistProactiveFailuresLocked()
        true
    }

    fun apiConfigs(): List<ApiConfig> = lock.withLock { apiConfigs.values.toList() }

    fun apiKey(configId: String): String? = lock.withLock { readSecretsLocked()[configId] }

    fun settings(): JsonValue.Obj = lock.withLock { settings }

    /**
     * 对外可见的设置：只保留白名单键。
     *
     * 备份/HTTP 都走这里。即便有人在磁盘上手工塞了 `apiKey`，它也不会出现在响应里；
     * 写路径也只接受白名单（见 [mergeSettings]）。
     */
    fun publicSettings(): JsonValue.Obj = lock.withLock { publicSettingsLocked() }

    // ================================================================== 写：人格进化待审补丁

    /**
     * 某个人格的待审补丁（按 `createdAt` 倒序），**顺手清掉已过期的**。
     *
     * 过期清理放在读取路径而不是定时任务：服务端只有一个进程，没有后台 GC；
     * 读取时判断既简单又不会让「过期补丁一直挂着」。
     *
     * @param now 判定时刻（默认服务端时钟）
     */
    fun pendingPatches(personaId: String, now: Long = serverTime()): List<PendingPatch> = lock.withLock {
        dropExpiredPatchesLocked(personaId, now)
        pendingPatches.values
            .filter { it.personaId == personaId }
            .sortedWith(compareByDescending<PendingPatch> { it.createdAt }.thenBy { it.id })
    }

    fun pendingPatch(patchId: String): PendingPatch? = lock.withLock { pendingPatches[patchId] }

    /**
     * 落一条待审补丁；**同一人格里 `patchJson` 完全相同**的已存在时不重复堆，
     * 直接返回已有那条（客户端 `pendingEvolutionPatch` 的同一条规则）。
     */
    fun putPendingPatch(patch: PendingPatch): PendingPatch = lock.withLock {
        val existing = pendingPatches.values.firstOrNull {
            it.personaId == patch.personaId && it.patchJson == patch.patchJson
        }
        if (existing != null) return@withLock existing
        pendingPatches[patch.id] = patch
        persistPendingPatchesLocked()
        patch
    }

    fun deletePendingPatch(patchId: String): Boolean = lock.withLock {
        if (pendingPatches.remove(patchId) == null) {
            false
        } else {
            persistPendingPatchesLocked()
            true
        }
    }

    /** 删掉这个人格已过期的补丁，返回删掉几条（路由/测试可直接调）。 */
    fun dropExpiredPendingPatches(personaId: String, now: Long = serverTime()): Int = lock.withLock {
        dropExpiredPatchesLocked(personaId, now)
    }

    private fun dropExpiredPatchesLocked(personaId: String, now: Long): Int {
        val doomed = pendingPatches.values
            .filter { it.personaId == personaId && it.expiresAt in 1..now }
            .map { it.id }
        for (id in doomed) pendingPatches.remove(id)
        if (doomed.isNotEmpty()) persistPendingPatchesLocked()
        return doomed.size
    }

    // ================================================================== 读：记忆 / 预设 / push

    /**
     * 某个人格的记忆，按 `updatedAt` 倒序。
     *
     * @param since 只返回 `updatedAt > since` 的记忆（与 `/v1/state` 的增量语义一致）
     */
    fun memories(personaId: String, limit: Int = 200, since: Long = 0L): List<Memory> = lock.withLock {
        memories.values
            .filter { it.personaId == personaId && (since <= 0 || it.updatedAt > since) }
            .sortedWith(compareByDescending<Memory> { it.updatedAt }.thenByDescending { it.createdAt })
            .take(limit.coerceAtLeast(0))
    }

    fun memory(memoryId: String): Memory? = lock.withLock { memories[memoryId] }

    fun presets(): List<Preset> = lock.withLock {
        presets.values.sortedWith(compareByDescending<Preset> { it.updatedAt }.thenBy { it.name })
    }

    fun preset(presetId: String): Preset? = lock.withLock { presets[presetId] }

    // ================================================================== 读：媒体
    //
    // 二进制不常驻内存：清单在 `media.json`，字节按 id 从 `media/<id>.<ext>` 读。
    // 上传/去重/删除都在同一把写锁内完成，所以「同 sha256 只落一份」在并发上传下也成立。

    fun media(mediaId: String): MediaItem? = lock.withLock {
        if (!MediaUrl.isValidId(mediaId)) null else media[mediaId]
    }

    /** 全部媒体元数据（按创建时间升序）；备份与诊断用。 */
    fun mediaItems(): List<MediaItem> = lock.withLock {
        media.values.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    /** 读原始字节；未知 id / 文件缺失都返回 null（路由翻成 404，不把路径细节吐给客户端）。 */
    fun mediaBytes(mediaId: String): ByteArray? = lock.withLock {
        if (!MediaUrl.isValidId(mediaId)) return@withLock null
        val item = media[mediaId] ?: return@withLock null
        val file = mediaFile(item)
        if (!Files.exists(file)) {
            System.err.println("[agentd] 媒体元数据存在但文件缺失：$file")
            return@withLock null
        }
        try {
            Files.readAllBytes(file)
        } catch (failure: IOException) {
            System.err.println("[agentd] 读媒体文件失败：$file（${failure.message}）")
            null
        }
    }

    /** 当前有多少处引用（消息附件 / 人格头像 / 预设头像）；删除接口用它决定 409。 */
    fun mediaReferenceCount(mediaId: String): Int = lock.withLock {
        if (!MediaUrl.isValidId(mediaId)) 0 else mediaReferenceCountLocked(mediaId)
    }

    // ================================================================== 写：媒体

    /**
     * 保存一张媒体（上传接口的落盘入口）。
     *
     * - **id 由服务端生成**（UUID），扩展名只由 [mime] 决定，客户端不能影响路径；
     * - 同 `sha256` 已存在 → 直接复用，不重复落盘（返回 `created = false`）；
     * - 写文件是 `*.tmp` → `ATOMIC_MOVE`；清单 `media.json` 同样原子重写；
     * - 整段在写锁内，两次并发上传同内容只会有一个 `created = true`、一个文件。
     */
    fun saveMedia(
        kind: String,
        personaId: String?,
        mime: String,
        data: ByteArray,
        /** 客户端备份里的裸文件名（备份导入用）；`null` = 服务端自己收的媒体。 */
        fileName: String? = null,
        /**
         * 同 `sha256` 已存在时是否复用旧文件。
         *
         * 默认 true（上传去重：同一张图只落一份）。**表情新建时传 false**：两个表情可以是
         * 同一张 GIF 的两次导入，它们必须是两条独立的记录（id 不同），否则后一个会覆盖前一个。
         */
        dedupe: Boolean = true
    ): MediaSaveResult =
        lock.withLock {
            require(MediaKind.isValid(kind)) { "不支持的媒体用途：$kind" }
            require(MediaMime.isValid(mime)) { "不支持的媒体类型：$mime" }
            val persona = personaId?.trim()?.takeIf { it.isNotEmpty() }
            if (persona != null) {
                if (!isValidId(persona)) throw IllegalArgumentException("非法的 personaId")
                requirePersonaLocked(persona)
            }
            val safeName = fileName?.trim()?.takeIf {
                it.isNotEmpty() && MediaItem.FILE_NAME_PATTERN.matches(it)
            }
            val sha = sha256Hex(data)
            if (dedupe) media.values.firstOrNull { it.sha256 == sha }?.let { existing ->
                // 元数据在、文件被手工删掉时顺手补回：否则「上传成功但下载 404」很难排查。
                // 仍是 200（内容没有产生第二份），只是把丢失的字节写回去。
                if (!Files.exists(mediaFile(existing))) {
                    writeBytesLocked(mediaFile(existing), data)
                }
                // 备份导入带来原名时补上：换端之后那些 `attachmentsJson[].uri` 还要对得上。
                if (existing.fileName == null && safeName != null) {
                    val renamed = existing.copy(fileName = safeName)
                    media[renamed.id] = renamed
                    persistMediaLocked()
                    return@withLock MediaSaveResult(renamed, created = false)
                }
                return@withLock MediaSaveResult(existing, created = false)
            }
            val item = MediaItem(
                id = newId(),
                kind = kind,
                personaId = persona,
                mime = mime,
                bytes = data.size.toLong(),
                sha256 = sha,
                createdAt = clock(),
                fileName = safeName
            )
            writeBytesLocked(mediaFile(item), data)
            media[item.id] = item
            persistMediaLocked()
            MediaSaveResult(item, created = true)
        }

    /**
     * 删除一张媒体。被引用且没有 `force` 时**不删**，只返回引用数（路由回 409）。
     *
     * `force` 只删文件与清单，不发事件、不改消息里的引用字符串：那些消息会指向一个已删除的
     * `/v1/media/<id>`（客户端下载得到 404）。这是契约里「客户端触发 GC」的语义，
     * 服务端不替客户端改写历史消息。
     */
    fun deleteMedia(mediaId: String, force: Boolean): MediaDeleteResult = lock.withLock {
        if (!MediaUrl.isValidId(mediaId)) return@withLock MediaDeleteResult(false, false, 0)
        val item = media[mediaId] ?: return@withLock MediaDeleteResult(false, false, 0)
        val references = mediaReferenceCountLocked(mediaId)
        if (references > 0 && !force) return@withLock MediaDeleteResult(true, false, references)
        media.remove(mediaId)
        Files.deleteIfExists(mediaFile(item))
        persistMediaLocked()
        MediaDeleteResult(true, true, references)
    }

    fun pushTokens(): List<PushRegistration> = lock.withLock { pushTokens.values.toList() }

    // ================================================================== 写：记忆 / 预设 / push

    /**
     * 新建或更新一条记忆。`id == null` 新建；给了 id 就必须已经存在（路由层把不存在翻成 404）。
     *
     * 整个读-改-写都在存储锁内，路由层不要先 `memory()` 再拼一个完整对象写回（会丢并发更新）。
     */
    fun saveMemory(
        personaId: String,
        memoryId: String?,
        transform: (existing: Memory?, now: Long) -> Memory
    ): Memory = lock.withLock {
        requirePersonaLocked(personaId)
        val now = nextTimestampLocked()
        if (memoryId == null) {
            val memory = transform(null, now).copy(
                id = newId(),
                personaId = personaId,
                createdAt = now,
                updatedAt = now
            )
            memories[memory.id] = memory
            persistMemoriesLocked()
            return@withLock memory
        }
        if (!isValidId(memoryId)) throw IllegalArgumentException("非法的记忆 id")
        val existing = memories[memoryId] ?: throw StoreMemoryNotFoundException(memoryId)
        if (existing.personaId != personaId) throw StoreMemoryNotFoundException(memoryId)
        val updated = transform(existing, now).copy(
            id = existing.id,
            personaId = existing.personaId,
            createdAt = existing.createdAt,
            updatedAt = now
        )
        memories[updated.id] = updated
        persistMemoriesLocked()
        updated
    }

    fun deleteMemory(memoryId: String): Boolean = lock.withLock {
        if (!isValidId(memoryId)) return@withLock false
        if (memories.remove(memoryId) == null) return@withLock false
        persistMemoriesLocked()
        true
    }

    fun savePreset(
        presetId: String?,
        transform: (existing: Preset?, now: Long) -> Preset
    ): Preset = lock.withLock {
        val now = nextTimestampLocked()
        if (presetId == null) {
            val preset = transform(null, now).copy(
                id = newId(),
                createdAt = now,
                updatedAt = now
            )
            presets[preset.id] = preset
            persistPresetsLocked()
            return@withLock preset
        }
        if (!isValidId(presetId)) throw IllegalArgumentException("非法的预设 id")
        val existing = presets[presetId] ?: throw StorePresetNotFoundException(presetId)
        val updated = transform(existing, now).copy(
            id = existing.id,
            createdAt = existing.createdAt,
            updatedAt = now
        )
        presets[updated.id] = updated
        persistPresetsLocked()
        updated
    }

    fun deletePreset(presetId: String): Boolean = lock.withLock {
        if (!isValidId(presetId)) return@withLock false
        if (presets.remove(presetId) == null) return@withLock false
        persistPresetsLocked()
        true
    }

    /**
     * 注册/更新推送 token（同一 `platform + token` 只留一条）。幂等：重复注册只刷新 `updatedAt`。
     * 返回 `true` 表示新建，`false` 表示更新了既有注册。
     */
    fun registerPushToken(platform: String, token: String, bundleId: String?): Boolean = lock.withLock {
        val key = pushTokenKey(platform, token)
        val now = nextTimestampLocked()
        val existing = pushTokens[key]
        pushTokens[key] = PushRegistration(
            platform = platform,
            token = token,
            bundleId = bundleId ?: existing?.bundleId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        persistPushTokensLocked()
        existing == null
    }

    /** 注销 token；幂等（本来没有也返回 false，不报错）。platform 为 null 时删掉这个 token 的所有平台。 */
    fun unregisterPushToken(token: String, platform: String? = null): Boolean = lock.withLock {
        val keys = pushTokens.entries
            .filter { it.value.token == token && (platform == null || it.value.platform == platform) }
            .map { it.key }
        if (keys.isEmpty()) return@withLock false
        keys.forEach { pushTokens.remove(it) }
        persistPushTokensLocked()
        true
    }

    /** 合并设置：只写白名单键；未知键（包括 `apiKey` / `secrets`）静默忽略，绝不落盘。 */
    fun mergeSettings(values: Map<String, JsonValue>): JsonValue.Obj = lock.withLock {
        // 从白名单开始重建：即使旧盘上手工塞过 apiKey，下一次设置写入也会把它清掉。
        // 键一律先归一（`myAvatarUri` → `userAvatarUri`）：同一份头像只留一条记录。
        val updated = LinkedHashMap<String, JsonValue>()
        for ((key, value) in settings.fields) {
            val canonical = canonicalSettingKey(key)
            if (canonical in SETTINGS_WHITELIST) updated[canonical] = value
        }
        for ((key, value) in values) {
            val canonical = canonicalSettingKey(key)
            if (canonical in SETTINGS_WHITELIST) updated[canonical] = value
        }
        settings = JsonValue.Obj(updated)
        writeJsonLocked(root.resolve(SETTINGS_FILE), StoreJson.obj("settings" to settings))
        publicSettingsLocked()
    }

    // ================================================================== 读：块与消息

    /** 一个会话的块表（按 `index` 升序；允许编号空洞）。 */
    /** 客户端独有的备份段（只读快照；值保持原始 JSON，不解析）。 */
    fun clientExtras(): Map<String, JsonValue> = lock.withLock { LinkedHashMap(clientExtras) }

    fun clientExtrasSection(name: String): JsonValue? = lock.withLock { clientExtras[name] }

    fun blocks(sessionId: String): List<BlockMeta> = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).toList()
    }

    /** 最新块编号；没有消息时 -1。 */
    fun lastBlockIndex(sessionId: String): Int = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).lastOrNull()?.index ?: -1
    }

    /** 严格小于 [beforeBlock] 的最近一块；没有则 -1。 */
    fun previousBlockIndex(sessionId: String, beforeBlock: Int): Int = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).lastOrNull { it.index < beforeBlock }?.index ?: -1
    }

    /** 严格大于 [afterBlock] 的最近一块；没有则 -1。 */
    fun nextBlockIndex(sessionId: String, afterBlock: Int): Int = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).firstOrNull { it.index > afterBlock }?.index ?: -1
    }

    /** 读一整块（按写入顺序，即 `(createdAt, id)` 顺序）。 */
    fun blockMessages(sessionId: String, blockIndex: Int): List<Message> = lock.withLock {
        requireSessionLocked(sessionId)
        readBlockLocked(sessionId, blockIndex)
    }

    /**
     * 最新 [limit] 条消息（跨块向前取，按时间升序返回），生成器用它当上下文。
     * 内存占用与 [limit] 相关，与历史长度无关。
     *
     * @param includeBlocked 是否包含「拉黑期间落库」的用户消息。**模型上下文必须传 false**：
     *   这些消息是永久排除的（`spec/experience/README.md` §2），解除拉黑后也不可见。
     *   默认 true 是为了不改变既有调用方（客户端分页/同步要看到它们）。
     */
    fun latestMessages(
        sessionId: String,
        limit: Int = BLOCK_SIZE,
        includeBlocked: Boolean = true
    ): List<Message> = lock.withLock {
        requireSessionLocked(sessionId)
        if (limit <= 0) return@withLock emptyList()
        val blocks = blocksForLocked(sessionId)
        val collected = ArrayList<Message>()
        for (block in blocks.asReversed()) {
            val messages = readBlockLocked(sessionId, block.index)
                .filter { includeBlocked || !it.blocked }
            collected.addAll(0, messages)
            if (collected.size >= limit) break
        }
        if (collected.size > limit) collected.subList(collected.size - limit, collected.size).toList() else collected
    }

    /**
     * 一个会话里用户消息的累计条数（[includeBlocked] = false 时只数模型能看见的那些）。
     *
     * 会话记忆的节流（每 5 / 30 条）用它：被拉黑的消息永远不会进摘要，所以不计数。
     */
    fun countUserMessagesInSession(sessionId: String, includeBlocked: Boolean = false): Long = lock.withLock {
        requireSessionLocked(sessionId)
        val (total, blocked) = userMessageCounts.getOrPut(sessionId) { scanUserMessageCountsLocked(sessionId) }
        if (includeBlocked) total else total - blocked
    }

    /**
     * 一个会话的用户消息计数缓存 `(总条数, 其中 blocked 条数)`。
     *
     * 为什么缓存：会话记忆的节流按「5 / 30 条用户消息」判断，心跳每 60 秒会给每个会话查一次；
     * 每次都扫全部块等于每分钟把整段历史读一遍。缓存只在**第一次**扫描，之后追加/删除时增量维护，
     * 导入/重建块表这类「整段换血」的操作直接失效重算。
     */
    private fun scanUserMessageCountsLocked(sessionId: String): Pair<Long, Long> {
        var total = 0L
        var blocked = 0L
        for (block in blocksForLocked(sessionId)) {
            for (message in readBlockLocked(sessionId, block.index)) {
                if (message.role != MessageRole.USER) continue
                total += 1
                if (message.blocked) blocked += 1
            }
        }
        return total to blocked
    }

    /** 删掉/重写某会话的消息后让计数缓存失效（下次按块重算）。 */
    private fun invalidateUserMessageCountsLocked(sessionId: String) {
        userMessageCounts.remove(sessionId)
    }

    fun findMessage(sessionId: String, messageId: String): Message? = lock.withLock {
        requireSessionLocked(sessionId)
        for (block in blocksForLocked(sessionId).asReversed()) {
            readBlockLocked(sessionId, block.index).firstOrNull { it.id == messageId }?.let { return@withLock it }
        }
        null
    }

    /** 幂等查找：同一个 `clientMessageId` 已经落过的用户消息。 */
    fun findUserMessageByClientMessageId(sessionId: String, clientMessageId: String): Message? = lock.withLock {
        requireSessionLocked(sessionId)
        val messageId = messageIndexLocked(sessionId).clientMessageIds[clientMessageId] ?: return@withLock null
        findMessageLocked(sessionId, messageId)
    }

    /** 某条用户消息已经生成的 assistant 回复（重复 POST 时重放 `done`，不再调生成器）。 */
    fun findAssistantReply(sessionId: String, userMessageId: String): Message? = lock.withLock {
        requireSessionLocked(sessionId)
        val messageId = messageIndexLocked(sessionId).replies[userMessageId] ?: return@withLock null
        findMessageLocked(sessionId, messageId)
    }

    /**
     * 某条用户消息生成过的**全部** assistant 回复（0.14 连发：一轮可能落好几条）。
     *
     * 索引只记最后一条（`done` 重放要用它）；幂等重放要按顺序把每条都重发一遍，
     * 所以这里按块扫一遍 `replyTo`——重放是罕见路径，扫一次不值得再加一张索引。
     */
    fun findAssistantReplies(sessionId: String, userMessageId: String): List<Message> = lock.withLock {
        requireSessionLocked(sessionId)
        val replies = ArrayList<Message>()
        for (block in blocksForLocked(sessionId)) {
            replies += readBlockLocked(sessionId, block.index).filter { it.replyTo == userMessageId }
        }
        replies.sortedWith(MESSAGE_ORDER)
    }

    /**
     * `GET /v1/state` 的快照。
     *
     * `since <= 0`：首次同步，每会话只返回**最新一块**（与 §3.2 的「初始加载 = 最新一块」一致），
     * 更早的历史用 `GET /v1/sessions/{id}/messages` 分页拿。
     * `since > 0`：返回 `createdAt > since` 的消息，从最新块向前扫，最多 [MAX_STATE_MESSAGES] 条
     * （超出时 `messagesTruncated = true`）。
     */
    fun snapshot(
        since: Long,
        personaId: String? = null,
        maxMessages: Int = MAX_STATE_MESSAGES
    ): StateSnapshot = lock.withLock {
        // 契约 §3.1：`since` 过滤 `updatedAt > since` 的人格/会话，与 `createdAt > since` 的消息。
        // 心情/在场不做 since 过滤：客户端每次都需要「当前值」，而不是「这次变了的值」。
        val filteredPersonas = personas.filter {
            (personaId == null || it.id == personaId) && (since <= 0 || it.updatedAt > since)
        }
        val filteredSessions = sessions.values.filter {
            (personaId == null || it.personaId == personaId) && (since <= 0 || it.updatedAt > since)
        }
        val messages = ArrayList<Message>()
        var truncated = false
        for (session in filteredSessions) {
            if (messages.size >= maxMessages) {
                truncated = true
                break
            }
            val blocks = blocksForLocked(session.id)
            if (blocks.isEmpty()) continue
            if (since <= 0) {
                messages.addAll(readBlockLocked(session.id, blocks.last().index))
                if (blocks.size > 1) truncated = true
                if (messages.size > maxMessages) {
                    while (messages.size > maxMessages) messages.removeAt(messages.size - 1)
                    truncated = true
                }
            } else {
                val perSession = ArrayList<Message>()
                for (block in blocks.asReversed()) {
                    if (block.lastAt <= since) break
                    val older = readBlockLocked(session.id, block.index)
                        .filter { it.createdAt > since }
                    perSession.addAll(0, older)
                    if (messages.size + perSession.size >= maxMessages) {
                        truncated = true
                        break
                    }
                }
                messages.addAll(perSession)
            }
        }
        val targetPersona = personaId ?: defaultPersonaIdLocked()
        StateSnapshot(
            serverTime = clock(),
            personas = filteredPersonas,
            sessions = filteredSessions,
            messages = messages.sortedWith(compareBy({ it.createdAt }, { it.id })),
            mood = moods[targetPersona],
            presence = presence[targetPersona],
            serverCursor = cursor,
            messagesTruncated = truncated
        )
    }

    // ================================================================== 写：会话 / 消息

    /**
     * 「一人格一会话」的建会话入口（`POST /v1/sessions`）。
     *
     * 已存在该人格的会话就返回既有会话（不覆盖 title，避免客户端一次重试把用户改过的标题写回默认）。
     * 查找与创建在同一把锁内，两个并发 POST 不会建出两条。
     *
     * @return `created to session`
     */
    fun ensureSessionForPersona(personaId: String, title: String): Pair<Boolean, Session> = lock.withLock {
        requirePersonaLocked(personaId)
        sessions.values.firstOrNull { it.personaId == personaId }?.let { return@withLock false to it }
        val now = nextTimestampLocked()
        val session = Session(
            id = newId(),
            personaId = personaId,
            title = title,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = null,
            lastBlockIndex = -1,
            unread = 0,
            messageCount = 0
        )
        sessions[session.id] = session
        persistSessionsLocked()
        true to session
    }

    /** 建会话（已存在则原样返回，方便测试与后续「建会话」接口复用）。 */
    fun ensureSession(personaId: String, title: String, sessionId: String = newId()): Session = lock.withLock {
        if (!isValidId(sessionId)) throw IllegalArgumentException("非法的会话 id")
        sessions[sessionId]?.let { return@withLock it }
        val persona = personas.firstOrNull { it.id == personaId }
            ?: throw IllegalArgumentException("人格不存在：$personaId")
        val now = nextTimestampLocked()
        val session = Session(
            id = sessionId,
            personaId = persona.id,
            title = title,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = null,
            lastBlockIndex = -1,
            unread = 0,
            messageCount = 0
        )
        sessions[sessionId] = session
        persistSessionsLocked()
        session
    }

    /**
     * 追加一条消息（块规则见类注释）。
     *
     * @param clientMessageId 用户消息的幂等键（assistant 消息为 null）。
     * @param replyTo assistant 消息指回的用户消息 id。
     * @param promptTokens / completionTokens 那一次生成的用量（重放 `done` 时用）。
     * @param toolTraceJson 这一轮工具回路的痕迹（客户端同形 JSON 文本，可空）。
     * @param blocked 拉黑期间落库的用户消息（永久不进模型上下文）；assistant 消息永远是 false。
     * @param id 0.16：**预分配**的消息 id（`send_message` 在落库前就把 messageId 回给了模型，
     *   落库时必须用同一个）；`null` = 由存储自己生成（绝大多数调用方）。
     */
    fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        id: String? = null,
        attachments: List<String> = emptyList(),
        source: String = MessageSource.CHAT,
        clientMessageId: String? = null,
        replyTo: String? = null,
        readAt: Long? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        toolTraceJson: String? = null,
        blocked: Boolean = false,
        /** 0.14.1：这条消息「该出现在屏幕上」的时刻；`null` = 立即（`visibleAt = createdAt`）。 */
        visibleAt: Long? = null,
        /**
         * 0.14.1：是否在落库时立刻发 `message.created` 事件。
         *
         * 延迟投递的消息先不写事件（`false`），由 `MessageDelivery` 到点补发；离线/立即投递保持
         * `true`（现状）。事件游标是单调的，晚发只会让客户端晚看到一次，不会乱序。
         */
        emitEvent: Boolean = true
    ): Message = lock.withLock {
        if (!MessageRole.isValid(role)) throw IllegalArgumentException("非法的角色：$role")
        if (id != null && !MESSAGE_ID_PATTERN.matches(id)) throw IllegalArgumentException("非法的消息 id")
        val session = requireSessionLocked(sessionId)
        val blocks = blocksForLocked(sessionId)
        var block = blocks.lastOrNull()
        if (block == null || block.count >= BLOCK_SIZE) {
            val nextIndex = (block?.index ?: -1) + 1
            block = BlockMeta(
                index = nextIndex,
                firstMessageId = "",
                lastMessageId = "",
                firstAt = 0,
                lastAt = 0,
                count = 0
            )
            blocks.add(block)
        }
        val createdAt = nextTimestampLocked()
        val message = Message(
            id = id ?: newId(),
            sessionId = sessionId,
            blockIndex = block.index,
            role = role,
            content = content,
            createdAt = createdAt,
            readAt = readAt,
            attachments = attachments,
            source = source,
            clientMessageId = clientMessageId,
            replyTo = replyTo,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            toolTraceJson = toolTraceJson?.takeIf { it.isNotBlank() },
            blocked = blocked && role == MessageRole.USER,
            visibleAt = visibleAt ?: createdAt
        )
        appendLineLocked(blockFile(sessionId, block.index), StoreJson.stringify(message.toJson()))

        val position = blocks.indexOfFirst { it.index == block.index }
        blocks[position] = block.copy(
            firstMessageId = if (block.count == 0) message.id else block.firstMessageId,
            lastMessageId = message.id,
            firstAt = if (block.count == 0) createdAt else block.firstAt,
            lastAt = createdAt,
            count = block.count + 1
        )
        persistBlockIndexLocked(sessionId, blocks)

        val unreadDelta = if (role == MessageRole.ASSISTANT && readAt == null) 1 else 0
        val updated = session.copy(
            lastMessageAt = createdAt,
            lastBlockIndex = block.index,
            updatedAt = createdAt,
            unread = session.unread + unreadDelta,
            messageCount = session.messageCount + 1
        )
        sessions[sessionId] = updated
        persistSessionsLocked()
        indexForAppendLocked(sessionId, message)
        if (role == MessageRole.USER) {
            userMessageCounts[sessionId]?.let { (total, blocked) ->
                userMessageCounts[sessionId] = (total + 1) to (blocked + if (message.blocked) 1 else 0)
            }
        }

        if (emitEvent) {
            appendEventLocked(
                type = EventType.MESSAGE_CREATED,
                at = createdAt,
                sessionId = sessionId,
                personaId = session.personaId,
                payload = linkedMapOf("message" to message.toJson())
            )
        }
        message
    }

    // ------------------------------------------------------------------ 0.14.1：投递队列与 typing 事件

    /**
     * 补发一条 `message.created`（延迟投递到点时由 [com.yap.agentd.delivery.MessageDelivery] 调用）。
     *
     * `at` 用**投递时刻**而不是消息的 createdAt：事件流是「什么时候发生的」，客户端只看消息 id。
     */
    fun appendMessageCreatedEvent(
        sessionId: String,
        personaId: String,
        message: Message,
        at: Long = serverTime()
    ): StoredEvent = lock.withLock {
        appendEventLocked(
            type = EventType.MESSAGE_CREATED,
            at = at,
            sessionId = sessionId,
            personaId = personaId,
            payload = linkedMapOf("message" to message.toJson())
        )
    }

    /**
     * 发一条 typing 事件（`typing.started` / `typing.stopped`）。
     *
     * 它只是给前台客户端的指示器，不落消息；`payload` 是空对象（字段在事件的
     * `sessionId` / `personaId` / `at` 上，与 `/v1/events` 的其它事件同形）。
     */
    fun appendTypingEvent(sessionId: String, personaId: String, type: String, at: Long = serverTime()): StoredEvent =
        lock.withLock {
            require(type == EventType.TYPING_STARTED || type == EventType.TYPING_STOPPED) {
                "不是 typing 事件：$type"
            }
            appendEventLocked(
                type = type,
                at = at,
                sessionId = sessionId,
                personaId = personaId,
                payload = linkedMapOf()
            )
        }

    /** 一批待投递消息入队（同一把锁 + 原子重写 `message_queue.json`）。 */
    fun enqueueDeliveries(entries: List<PendingDelivery>): List<PendingDelivery> = lock.withLock {
        if (entries.isEmpty()) return@withLock emptyList()
        val added = ArrayList<PendingDelivery>(entries.size)
        for (entry in entries) {
            if (deliveries.size >= MAX_PENDING_DELIVERIES) {
                // 极端情况（客户端长期不在线 + 消息爆量）：丢掉最早的，保证新消息还能投递。
                val oldest = deliveries.values.minByOrNull { it.enqueuedAt } ?: break
                deliveries.remove(oldest.id)
            }
            deliveries[entry.id] = entry
            added += entry
        }
        persistDeliveriesLocked()
        added
    }

    fun pendingDeliveries(): List<PendingDelivery> = lock.withLock {
        deliveries.values.sortedWith(compareBy({ it.visibleAt }, { it.enqueuedAt }))
    }

    /** 到点（`visibleAt <= cutoff`）的待投递条目，按 `visibleAt` 升序。 */
    fun dueDeliveries(cutoff: Long): List<PendingDelivery> = lock.withLock {
        deliveries.values
            .filter { it.visibleAt <= cutoff }
            .sortedWith(compareBy({ it.visibleAt }, { it.enqueuedAt }))
    }

    fun completeDelivery(deliveryId: String): Boolean = lock.withLock {
        if (deliveries.remove(deliveryId) == null) return@withLock false
        persistDeliveriesLocked()
        true
    }

    /**
     * 主动消息的**原子落库入口**：每日上限 / 冷却复查 + 追加消息 + 更新主动记录，
     * 全部在同一把存储锁内完成。
     *
     * 为什么不能「路由先读一次、再 append、再 record」：
     * - 两个并发 tick（两个 `ProactiveTicker` 实例、或调试接口与定时器撞在一起）会同时读到
     *   `count=0`，各发一条；这里在锁内复查，后到的那个返回 `null`；
     * - 消息与计数必须一起可见：只写消息不写计数，重启后等于没发过（本次修复的缺陷）。
     *
     * @param dailyCap 今天允许的主动次数（引擎已经算过，包含心情修正）
     * @param minGapMs 距上次主动开口至少多久（冷却期）；`<= 0` 表示不检查
     * @return 真正落库的消息；被上限/冷却挡住时返回 `null`
     */
    fun appendProactiveMessageIfAllowed(
        sessionId: String,
        personaId: String,
        content: String,
        at: Long,
        today: String,
        dailyCap: Int,
        minGapMs: Long,
        /** 0.14.1：`visibleAt` / `emitEvent` 透传（延迟投递的主动消息先不写事件）。 */
        visibleAt: Long? = null,
        emitEvent: Boolean = true
    ): Message? = lock.withLock {
        requirePersonaLocked(personaId)
        val state = effectiveProactiveStateLocked(personaId, today)
        if (dailyCap <= 0 || state.proactiveCountToday >= dailyCap) return@withLock null
        if (minGapMs > 0 && state.lastProactiveAt > 0 && at - state.lastProactiveAt < minGapMs) {
            return@withLock null
        }
        // ReentrantLock 可重入：appendMessage 内部再取一次锁不会死锁，但整段仍是同一个临界区
        // （消息、会话、事件、主动计数在一次锁持有期间一起落盘）。
        val message = appendMessage(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = content,
            source = MessageSource.PROACTIVE,
            visibleAt = visibleAt,
            emitEvent = emitEvent
        )
        recordProactiveFiredLocked(personaId, at, today)
        message
    }

    /**
     * 硬删除一条消息：重算块边界；块空了就删块文件并在块表里留空洞（不重平衡）。
     * 返回是否真的删掉了。
     */
    fun deleteMessage(sessionId: String, messageId: String): Boolean = lock.withLock {
        val session = requireSessionLocked(sessionId)
        invalidateUserMessageCountsLocked(sessionId)
        val blocks = blocksForLocked(sessionId)
        for (block in blocks.asReversed()) {
            val messages = readBlockLocked(sessionId, block.index)
            val deleted = messages.firstOrNull { it.id == messageId } ?: continue
            val remaining = messages.filterNot { it.id == messageId }
            if (remaining.isEmpty()) {
                Files.deleteIfExists(blockFile(sessionId, block.index))
                blocks.removeAll { it.index == block.index }
            } else {
                rewriteBlockLocked(sessionId, block.index, remaining)
                val position = blocks.indexOfFirst { it.index == block.index }
                blocks[position] = block.copy(
                    firstMessageId = remaining.first().id,
                    lastMessageId = remaining.last().id,
                    firstAt = remaining.first().createdAt,
                    lastAt = remaining.last().createdAt,
                    count = remaining.size
                )
            }
            persistBlockIndexLocked(sessionId, blocks)

            val wasUnreadAssistant = deleted.role == MessageRole.ASSISTANT && deleted.readAt == null
            val lastBlock = blocks.lastOrNull()
            val updated = session.copy(
                updatedAt = nextTimestampLocked(),
                lastMessageAt = lastBlock?.lastAt,
                lastBlockIndex = lastBlock?.index ?: -1,
                unread = if (wasUnreadAssistant) maxOf(0, session.unread - 1) else session.unread,
                messageCount = maxOf(0, session.messageCount - 1)
            )
            sessions[sessionId] = updated
            persistSessionsLocked()
            indexForDeleteLocked(sessionId, deleted)
            return@withLock true
        }
        false
    }

    /**
     * 把会话里的 assistant 消息标成已读并清零未读计数；返回标记了几条。
     *
     * 兼容旧调用（全部标记）。HTTP 路由用 [markSessionReadUpTo] 拿 `readAt`。
     */
    fun markSessionRead(sessionId: String): Int = markSessionReadUpTo(sessionId, null).marked

    /**
     * `POST /v1/sessions/{id}/read` 的存储入口。
     *
     * @param upTo 只把 `createdAt <= upTo` 的未读 assistant 消息标已读（null = 全部）。
     *   无论标了几条，会话的 `unread` 都归零：客户端说「我看到了这里」，契约就是
     *   `unread = 0`；`upTo` 只决定哪些消息的 `readAt` 被补上。
     * @return 标记条数、用于响应的 `readAt`（服务端时钟）、更新后的会话
     */
    fun markSessionReadUpTo(sessionId: String, upTo: Long?): SessionReadResult = lock.withLock {
        val session = requireSessionLocked(sessionId)
        var marked = 0
        val readAt = clock()
        val blocks = blocksForLocked(sessionId)
        for (block in blocks.toList()) {
            val messages = readBlockLocked(sessionId, block.index)
            var changed = false
            val updatedMessages = messages.map { message ->
                if (message.role == MessageRole.ASSISTANT &&
                    message.readAt == null &&
                    (upTo == null || message.createdAt <= upTo)
                ) {
                    marked += 1
                    changed = true
                    message.copy(readAt = readAt)
                } else {
                    message
                }
            }
            if (changed) rewriteBlockLocked(sessionId, block.index, updatedMessages)
        }
        val updated = session.copy(unread = 0, updatedAt = nextTimestampLocked())
        sessions[sessionId] = updated
        persistSessionsLocked()
        SessionReadResult(marked = marked, readAt = readAt, session = updated)
    }

    // ================================================================== 写：批量历史消息导入

    /**
     * `POST /v1/sessions/{id}/messages/import` 的存储入口：**把客户端本地历史搬到服务端**。
     *
     * 语义（与 HTTP 契约一一对应，写在 `agentd/README.md`）：
     * - **幂等**：同 id 已存在就跳过（`skipped++`），不覆盖、不改 `readAt`；所以客户端重试/重推
     *   同一批永远安全；
     * - **原子**：一批要么全落，要么一条不落（校验先做，写盘在最后）；
     * - **不改已有消息**：走「插入到正确块」的路径，只有块内行数超过 [BLOCK_SIZE] 时才整会话重排
     *   （块编号是结构信息，消息正文/时间/已读状态不动）；
     * - **不发事件、不写 unread**：历史消息不该让会话显示「N 条未读」，也不该触发推送；
     *   会话的 `lastMessageAt` / `messageCount` / `lastBlockIndex` / `updatedAt` 仍然更新
     *   （否则 `/v1/sessions` 与 `/v1/state` 会自相矛盾）。
     *
     * @param incoming 已由路由层校验过 role/attachments 的消息（id 与 role 这里再挡一次）
     */
    fun importMessages(sessionId: String, incoming: List<ImportMessage>): MessageImportResult = lock.withLock {
        val session = requireSessionLocked(sessionId)
        invalidateUserMessageCountsLocked(sessionId)
        if (incoming.size > MAX_IMPORT_MESSAGES) {
            throw IllegalArgumentException("单批最多 $MAX_IMPORT_MESSAGES 条消息")
        }
        // 1) 校验（任何写之前）：id/role 合法、同批不重复。
        val batchIds = HashSet<String>(incoming.size * 2)
        for (message in incoming) {
            if (!MESSAGE_ID_PATTERN.matches(message.id)) {
                throw IllegalArgumentException("消息 id 不合法（只允许 [A-Za-z0-9_-]{1,128}）：${message.id.take(64)}")
            }
            if (!MessageRole.isValid(message.role)) throw IllegalArgumentException("非法的角色：${message.role}")
            if (message.createdAt < 0) throw IllegalArgumentException("createdAt 不能是负数")
            if (!batchIds.add(message.id)) throw IllegalArgumentException("同一批里消息 id 重复：${message.id}")
        }

        // 2) 幂等过滤：已存在的 id 只计数，不覆盖。
        val blocks = blocksForLocked(sessionId)
        val existingIds = HashSet<String>()
        for (block in blocks) {
            for (message in readBlockLocked(sessionId, block.index)) existingIds += message.id
        }
        val skipped = incoming.count { it.id in existingIds }
        val fresh = incoming.asSequence()
            .filter { it.id !in existingIds }
            .map { message ->
                Message(
                    id = message.id,
                    sessionId = sessionId,
                    blockIndex = 0, // 真实块号由 insertMessagesLocked 决定
                    role = message.role,
                    content = message.content,
                    createdAt = message.createdAt,
                    readAt = message.readAt,
                    attachments = message.attachments,
                    source = message.source,
                    clientMessageId = message.clientMessageId,
                    replyTo = message.replyTo,
                    promptTokens = null,
                    completionTokens = null
                )
            }
            .sortedWith(MESSAGE_ORDER)
            .toList()
        if (fresh.isEmpty()) {
            return@withLock MessageImportResult(
                imported = 0,
                skipped = skipped,
                firstBlock = null,
                lastBlock = null,
                messageCount = session.messageCount
            )
        }

        // 3) 落块 + 更新会话（块表、会话表各原子写一次）。
        val range = insertMessagesLocked(sessionId, blocks, fresh)
        persistBlockIndexLocked(sessionId, blocks)
        val lastBlock = blocks.lastOrNull()
        val updated = session.copy(
            lastMessageAt = lastBlock?.lastAt ?: session.lastMessageAt,
            lastBlockIndex = lastBlock?.index ?: -1,
            updatedAt = nextTimestampLocked(),
            messageCount = session.messageCount + fresh.size
        )
        sessions[sessionId] = updated
        persistSessionsLocked()
        for (message in fresh) indexForAppendLocked(sessionId, message)
        MessageImportResult(
            imported = fresh.size,
            skipped = skipped,
            firstBlock = range.first,
            lastBlock = range.second,
            messageCount = updated.messageCount
        )
    }

    /**
     * 把**已按 `(createdAt, id)` 排好序**的新消息插入块序列，返回被触及的块范围。
     *
     * 两条路径：
     * - 常见路径（客户端按时间顺序推历史、或服务端还没有更新的消息）：新消息落在某块的中间/末尾，
     *   只重写**被插到的那几块**；已有消息的 `blockIndex` 与内容都不变；
     * - 兜底路径：某块插入后超过 [BLOCK_SIZE] 时整会话按时间重排并重新切块（块编号会变，
     *   消息正文不变）。客户端按块分页，所以只要块表与块文件一致就不会读到坏形状。
     */
    private fun insertMessagesLocked(
        sessionId: String,
        blocks: MutableList<BlockMeta>,
        sorted: List<Message>
    ): Pair<Int, Int> {
        var firstTouched = Int.MAX_VALUE
        var lastTouched = Int.MIN_VALUE
        fun touch(blockIndex: Int) {
            firstTouched = minOf(firstTouched, blockIndex)
            lastTouched = maxOf(lastTouched, blockIndex)
        }

        // 每条新消息落到「第一块 last >= 它」的块；都比所有块新就追加到末尾。
        val inserts = LinkedHashMap<Int, MutableList<Message>>()
        val appended = ArrayList<Message>()
        var blockPosition = 0
        for (message in sorted) {
            while (blockPosition < blocks.size) {
                val block = blocks[blockPosition]
                val afterBlock = message.createdAt > block.lastAt ||
                    (message.createdAt == block.lastAt && message.id > block.lastMessageId)
                if (!afterBlock) break
                blockPosition += 1
            }
            if (blockPosition < blocks.size) {
                inserts.getOrPut(blocks[blockPosition].index) { ArrayList() }.add(message)
            } else {
                appended.add(message)
            }
        }

        var overflow = false
        for ((blockIndex, news) in inserts) {
            val position = blocks.indexOfFirst { it.index == blockIndex }
            val existing = readBlockLocked(sessionId, blockIndex)
            val merged = (existing + news).sortedWith(MESSAGE_ORDER)
            if (merged.size > BLOCK_SIZE) {
                overflow = true
                break
            }
            rewriteBlockLocked(sessionId, blockIndex, merged)
            blocks[position] = blocks[position].copy(
                firstMessageId = merged.first().id,
                lastMessageId = merged.last().id,
                firstAt = merged.first().createdAt,
                lastAt = merged.last().createdAt,
                count = merged.size
            )
            touch(blockIndex)
        }

        if (overflow) {
            // 整会话重排：按 id 去重后重写全部块（已重写过的块里可能已经有新消息，去重保证不重复）。
            val byId = LinkedHashMap<String, Message>()
            for (block in blocks) {
                for (message in readBlockLocked(sessionId, block.index)) byId[message.id] = message
            }
            for (message in sorted) byId[message.id] = message
            val ordered = byId.values.sortedWith(MESSAGE_ORDER)
            val oldIndexes = blocks.map { it.index }
            val metas = writeSessionBlocksLocked(sessionId, ordered)
            for (index in oldIndexes) {
                if (metas.none { it.index == index }) Files.deleteIfExists(blockFile(sessionId, index))
            }
            blocks.clear()
            blocks.addAll(metas)
            for (meta in metas) touch(meta.index)
            return firstTouched to lastTouched
        }

        if (appended.isNotEmpty()) {
            var remaining: List<Message> = appended
            val lastBlock = blocks.lastOrNull()
            if (lastBlock != null && lastBlock.count < BLOCK_SIZE) {
                val take = minOf(BLOCK_SIZE - lastBlock.count, remaining.size)
                val merged = (readBlockLocked(sessionId, lastBlock.index) + remaining.subList(0, take))
                    .sortedWith(MESSAGE_ORDER)
                rewriteBlockLocked(sessionId, lastBlock.index, merged)
                val position = blocks.indexOfFirst { it.index == lastBlock.index }
                blocks[position] = lastBlock.copy(
                    firstMessageId = merged.first().id,
                    lastMessageId = merged.last().id,
                    firstAt = merged.first().createdAt,
                    lastAt = merged.last().createdAt,
                    count = merged.size
                )
                touch(lastBlock.index)
                remaining = remaining.subList(take, remaining.size)
            }
            var nextIndex = (blocks.lastOrNull()?.index ?: -1) + 1
            for (chunk in remaining.chunked(BLOCK_SIZE)) {
                val blockIndex = nextIndex++
                rewriteBlockLocked(sessionId, blockIndex, chunk)
                blocks.add(
                    BlockMeta(
                        index = blockIndex,
                        firstMessageId = chunk.first().id,
                        lastMessageId = chunk.last().id,
                        firstAt = chunk.first().createdAt,
                        lastAt = chunk.last().createdAt,
                        count = chunk.size
                    )
                )
                touch(blockIndex)
            }
        }
        return firstTouched to lastTouched
    }

    // ================================================================== 写：心情 / 在场 / 用量 / 配置

    /**
     * 改心情（多维：`spec/mood/README.md` §1）。
     *
     * 只给 label/intensity 的旧调用方保持兼容：三个维度不传时按迁移规则补
     * （valence 由 label、arousal 由 intensity、dominance=3）。值真的变了才落盘并发
     * `mood.changed` 事件（事件 payload 里就是完整的 [Mood.toJson]，客户端能直接拿到多维值）。
     */
    fun setMood(
        personaId: String,
        label: String,
        intensity: Int,
        valence: Int? = null,
        arousal: Int? = null,
        dominance: Int? = null,
        reason: String? = null
    ): Mood = lock.withLock {
        requirePersonaLocked(personaId)
        val clampedIntensity = intensity.coerceIn(0, 5)
        val clampedValence = (valence ?: Mood.defaultValence(label)).coerceIn(-2, 2)
        val clampedArousal = (arousal ?: Mood.defaultArousal(clampedIntensity)).coerceIn(0, 5)
        val clampedDominance = (dominance ?: Mood.defaultDominance()).coerceIn(0, 5)
        val previous = moods[personaId]
        val newReason = (reason ?: previous?.takeIf { it.label == label }?.reason).orEmpty()
            .trim()
            .take(Mood.MAX_REASON_CHARS)
        if (previous != null &&
            previous.label == label &&
            previous.intensity == clampedIntensity &&
            previous.valence == clampedValence &&
            previous.arousal == clampedArousal &&
            previous.dominance == clampedDominance &&
            previous.reason == newReason
        ) {
            return@withLock previous
        }
        val now = nextTimestampLocked()
        val mood = Mood(
            personaId = personaId,
            label = label,
            intensity = clampedIntensity,
            updatedAt = now,
            valence = clampedValence,
            arousal = clampedArousal,
            dominance = clampedDominance,
            reason = newReason
        )
        moods[personaId] = mood
        persistMoodsLocked()
        appendEventLocked(
            type = EventType.MOOD_CHANGED,
            at = now,
            sessionId = null,
            personaId = personaId,
            payload = linkedMapOf("mood" to mood.toJson())
        )
        mood
    }

    /** 改在场状态；值真的变了才落盘并发 `presence.changed` 事件。 */
    fun setPresence(personaId: String, kind: String, detail: String = ""): Presence = lock.withLock {
        requirePersonaLocked(personaId)
        val previous = presence[personaId]
        if (previous != null && previous.kind == kind && previous.detail == detail) return@withLock previous
        val now = nextTimestampLocked()
        val next = Presence(personaId, kind, detail, now)
        presence[personaId] = next
        persistPresenceLocked()
        appendEventLocked(
            type = EventType.PRESENCE_CHANGED,
            at = now,
            sessionId = null,
            personaId = personaId,
            payload = linkedMapOf("presence" to next.toJson())
        )
        next
    }

    fun addUsage(personaId: String, promptTokens: Int, completionTokens: Int) = lock.withLock {
        val current = usage[personaId] ?: UsageTotal()
        usage[personaId] = current.plus(
            UsageTotal(
                promptTokens = promptTokens.toLong().coerceAtLeast(0),
                completionTokens = completionTokens.toLong().coerceAtLeast(0),
                replies = 1
            )
        )
        persistUsageLocked()
    }

    /** 落一条 API 配置（只有元数据；key 用 [setApiKey] 单独存）。 */
    fun putApiConfig(config: ApiConfig): ApiConfig = lock.withLock {
        val now = nextTimestampLocked()
        val stored = if (apiConfigs.containsKey(config.id)) {
            config.copy(createdAt = apiConfigs.getValue(config.id).createdAt, updatedAt = now)
        } else {
            config.copy(createdAt = now, updatedAt = now)
        }
        apiConfigs[stored.id] = stored
        persistApiConfigsLocked()
        stored
    }

    /**
     * 把明文 key 写进 `secrets.json`（0600）。**永远不要**把它放进 `api_configs.json` 或任何响应。
     */
    fun setApiKey(configId: String, key: String) = lock.withLock {
        val secrets = readSecretsLocked().toMutableMap()
        if (key.isEmpty()) secrets.remove(configId) else secrets[configId] = key
        persistSecretsLocked(secrets)
    }

    fun setSetting(key: String, value: JsonValue) = lock.withLock {
        val updated = LinkedHashMap(settings.fields)
        updated[key] = value
        settings = JsonValue.Obj(updated)
        writeJsonLocked(root.resolve(SETTINGS_FILE), StoreJson.obj("settings" to settings))
    }

    fun deleteSession(sessionId: String): Boolean = lock.withLock {
        if (sessions.remove(sessionId) == null) return@withLock false
        blockCache.remove(sessionId)
        messageIndexCache.remove(sessionId)
        invalidateUserMessageCountsLocked(sessionId)
        deleteRecursively(sessionDir(sessionId))
        // 0.14：会话没了，它的备忘录也没有意义了。
        if (sessionMemories.remove(sessionId) != null) persistSessionMemoryLocked()
        // 0.14.1：会话没了，它排队等发的消息也不该再补发（消息行本身随会话一起没了）。
        val doomedDeliveries = deliveries.values.filter { it.sessionId == sessionId }.map { it.id }
        if (doomedDeliveries.isNotEmpty()) {
            for (id in doomedDeliveries) deliveries.remove(id)
            persistDeliveriesLocked()
        }
        persistSessionsLocked()
        true
    }

    // ================================================================== 表情包（stickers）
    //
    // `spec/stickers/README.md`：表情是「压缩过的 GIF」，字节与图片走同一套媒体表，
    // 元数据单独一行。这样备份 v6 里 `stickers[].fileName` 与 `media[]` 的 `kind=STICKER`
    // 条目天然对得上，不需要第二套 id 映射。

    /** 表情列表（按 `createdAt` 升序；路由/工具按需倒序取最近的）。 */
    fun stickers(personaId: String? = null, limit: Int = Int.MAX_VALUE): List<Sticker> = lock.withLock {
        stickers.values
            .filter { personaId == null || it.personaId == personaId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .take(limit.coerceAtLeast(0))
    }

    fun sticker(stickerId: String): Sticker? = lock.withLock { stickers[stickerId] }

    /**
     * 新建一个表情：字节落进媒体表（`kind=STICKER`），元数据落 `stickers.json`。
     *
     * 上限按 `spec/stickers` 的 512 KB 在**解码后**挡一次（base64 的 4/3 膨胀不参与判断）。
     * 同 sha256 的图已存在时复用媒体文件（`saveMedia` 的行为），但表情行仍然新建
     * （两个表情可以是同一张图的两次导入，用户看到的是两条记录）。
     */
    fun createSticker(
        personaId: String?,
        mime: String,
        data: ByteArray,
        width: Int = 0,
        height: Int = 0,
        frames: Int = 1,
        source: String = Sticker.SOURCE_IMPORTED,
        createdBy: String = Sticker.CREATED_BY_USER,
        ownerUserId: String? = null
    ): Sticker = lock.withLock {
        val type = mime.trim().lowercase()
        require(type in Sticker.MIMES) { "表情只支持 GIF/PNG/WebP（收到 $type）" }
        require(data.isNotEmpty()) { "表情文件是空的" }
        require(data.size <= Sticker.MAX_FILE_BYTES) {
            "表情文件最大 ${Sticker.MAX_FILE_BYTES / 1024} KB（收到 ${data.size / 1024} KB）"
        }
        val persona = personaId?.trim()?.takeIf { it.isNotEmpty() }
        if (persona != null) {
            if (!isValidId(persona)) throw IllegalArgumentException("非法的 personaId")
            requirePersonaLocked(persona)
        }
        val saved = saveMedia(
            kind = MediaKind.STICKER,
            personaId = persona,
            mime = type,
            data = data,
            dedupe = false
        )
        val extension = MediaMime.extension(type) ?: "gif"
        val now = nextTimestampLocked()
        val row = Sticker(
            id = saved.item.id,
            ownerUserId = ownerUserId?.trim()?.takeIf { it.isNotEmpty() },
            personaId = persona,
            file = "${saved.item.id}.$extension",
            mime = type,
            width = width.coerceIn(0, Sticker.MAX_EDGE_PX * 8),
            height = height.coerceIn(0, Sticker.MAX_EDGE_PX * 8),
            frames = frames.coerceIn(1, Sticker.MAX_FRAMES),
            bytes = data.size.toLong(),
            source = Sticker.normalizeSource(source),
            createdBy = Sticker.normalizeCreatedBy(createdBy),
            createdAt = now
        )
        stickers[row.id] = row
        persistStickersLocked()
        row
    }

    /**
     * 把一个**已上传的**媒体登记成表情（`POST /v1/stickers {"mediaId":"…"}`）。
     *
     * 用途：客户端先 `POST /v1/media?kind=STICKER` 再建行；也用于把用户发来的表情
     * 收进「我的表情」（`sticker` 工具的 `save` 语义）。
     */
    fun registerSticker(
        mediaId: String,
        personaId: String? = null,
        width: Int = 0,
        height: Int = 0,
        frames: Int = 1,
        source: String = Sticker.SOURCE_IMPORTED,
        createdBy: String = Sticker.CREATED_BY_USER,
        ownerUserId: String? = null
    ): Sticker = lock.withLock {
        val item = media[mediaId] ?: throw IllegalArgumentException("媒体不存在：$mediaId")
        if (item.mime !in Sticker.MIMES) {
            throw IllegalArgumentException("这个媒体不是表情能用的类型：${item.mime}")
        }
        val persona = personaId?.trim()?.takeIf { it.isNotEmpty() } ?: item.personaId
        if (persona != null) {
            if (!isValidId(persona)) throw IllegalArgumentException("非法的 personaId")
            requirePersonaLocked(persona)
        }
        val existing = stickers[mediaId]
        if (existing != null) return@withLock existing
        val extension = MediaMime.extension(item.mime) ?: "gif"
        val row = Sticker(
            id = item.id,
            ownerUserId = ownerUserId?.trim()?.takeIf { it.isNotEmpty() },
            personaId = persona,
            file = "${item.id}.$extension",
            mime = item.mime,
            width = width,
            height = height,
            frames = frames,
            bytes = item.bytes,
            source = Sticker.normalizeSource(source),
            createdBy = Sticker.normalizeCreatedBy(createdBy),
            createdAt = nextTimestampLocked()
        )
        stickers[row.id] = row
        persistStickersLocked()
        row
    }

    /** 删一个表情：行 + 媒体文件一起删（消息里对它的引用会变成 404，这是客户端的 GC 语义）。 */
    fun deleteSticker(stickerId: String): Boolean = lock.withLock {
        if (stickers.remove(stickerId) == null) return@withLock false
        persistStickersLocked()
        deleteMedia(stickerId, force = true)
        true
    }

    /** 表情的原始字节（走媒体表，与 `GET /v1/media/{id}` 同一份文件）。 */
    fun stickerBytes(stickerId: String): ByteArray? = stickerMediaId(stickerId)?.let { mediaBytes(it) }

    /**
     * 表情对应的媒体 id。
     *
     * 正常情况就是表情 id（服务端建行时 `id = 媒体 id`）。**导入客户端 v6 备份**时两者可能不同：
     * 客户端那一行写的是 `id=sticker-fixture-1`，字节挂在 `media[]` 里另一条
     * （`id=media-sticker-1`、`fileName=sticker-fixture.gif`）。导入会把表情 id 归一成媒体 id
     * （见 `commitBackupStreamed`），这里再兜一层：按 `file`（裸文件名）找一遍。
     */
    fun stickerMediaId(stickerId: String): String? = lock.withLock {
        if (media.containsKey(stickerId)) return@withLock stickerId
        val sticker = stickers[stickerId] ?: return@withLock null
        media.values.firstOrNull { it.exportFileName == sticker.file }?.id
    }

    // ================================================================== 日记板（diary）
    //
    // `spec/diary/README.md`：他自己的备忘录。增删查 + 到点提醒（只触发一次）。

    /** 某个人的日记（按 `createdAt` 倒序 = 最近的在前）。 */
    fun diaryNotes(personaId: String, limit: Int = 20, since: Long = 0L): List<DiaryNote> = lock.withLock {
        diaryNotes.values
            .filter { it.personaId == personaId && it.updatedAt > since }
            .sortedWith(compareByDescending<DiaryNote> { it.createdAt }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))
    }

    fun diaryNote(personaId: String, noteId: String): DiaryNote? = lock.withLock {
        diaryNotes[noteId]?.takeIf { it.personaId == personaId }
    }

    /**
     * 新建或更新一条日记。
     *
     * @param noteId `null` = 新建（id 由服务端生成）；给了 id 就必须属于 [personaId]，
     *   否则抛 [StoreDiaryNoteNotFoundException]（路由翻 404）。
     * @param content `null` = 不改（更新时）；新建时必须给。
     * @param remindAt 新的提醒时刻；`null` + [clearReminder]=false 表示不改，
     *   [clearReminder]=true 表示清掉提醒。
     */
    fun upsertDiaryNote(
        personaId: String,
        noteId: String?,
        content: String?,
        remindAt: Long?,
        clearReminder: Boolean = false
    ): DiaryNote = lock.withLock {
        requirePersonaLocked(personaId)
        val now = nextTimestampLocked()
        if (noteId == null) {
            val text = content?.trim().orEmpty()
            if (text.isEmpty()) throw IllegalArgumentException("日记内容不能为空")
            if (text.length > DiaryNote.MAX_CONTENT_CHARS) {
                throw IllegalArgumentException("日记内容最长 ${DiaryNote.MAX_CONTENT_CHARS} 个字符")
            }
            val note = DiaryNote(
                id = newId(),
                personaId = personaId,
                content = text,
                createdAt = now,
                remindAt = remindAt,
                remindedAt = null,
                updatedAt = now
            )
            diaryNotes[note.id] = note
            persistDiaryNotesLocked()
            return@withLock note
        }
        if (!isValidId(noteId)) throw IllegalArgumentException("非法的日记 id")
        val existing = diaryNotes[noteId]?.takeIf { it.personaId == personaId }
            ?: throw StoreDiaryNoteNotFoundException(noteId)
        val text = content?.trim()?.takeIf { it.isNotEmpty() } ?: existing.content
        if (text.length > DiaryNote.MAX_CONTENT_CHARS) {
            throw IllegalArgumentException("日记内容最长 ${DiaryNote.MAX_CONTENT_CHARS} 个字符")
        }
        // 提醒时间真的改了才清 `remindedAt`：否则「改正文」会把已经提醒过的标记抹掉、明天再提醒一次。
        val nextRemindAt = when {
            clearReminder -> null
            remindAt != null -> remindAt
            else -> existing.remindAt
        }
        val reminderChanged = nextRemindAt != existing.remindAt
        val updated = existing.copy(
            content = text,
            remindAt = nextRemindAt,
            remindedAt = if (reminderChanged) null else existing.remindedAt,
            updatedAt = now
        )
        diaryNotes[updated.id] = updated
        persistDiaryNotesLocked()
        updated
    }

    fun deleteDiaryNote(personaId: String, noteId: String): Boolean = lock.withLock {
        val existing = diaryNotes[noteId]?.takeIf { it.personaId == personaId } ?: return@withLock false
        diaryNotes.remove(existing.id)
        persistDiaryNotesLocked()
        true
    }

    /**
     * 到点且还没提醒过的日记（跨人格，按 `remindAt` 升序）。
     *
     * 只看 `remindAt <= now`：过期太久的（>24h）由 [expireOverdueDiaryReminders] 清标记，
     * **不补发**——「周四提醒你打电话」，周六才发出来只会让人莫名其妙。
     */
    fun dueDiaryReminders(now: Long, limit: Int = 50): List<DiaryNote> = lock.withLock {
        diaryNotes.values
            .filter { it.isDue(now) && now - (it.remindAt ?: 0L) <= DiaryNote.CATCH_UP_WINDOW_MS }
            .sortedWith(compareBy({ it.remindAt ?: 0L }, { it.id }))
            .take(limit.coerceAtLeast(0))
    }

    /** 写 `remindedAt`（防重复）。返回更新后的行；不存在返回 null。 */
    fun markDiaryReminded(noteId: String, at: Long): DiaryNote? = lock.withLock {
        val existing = diaryNotes[noteId] ?: return@withLock null
        val updated = existing.copy(remindedAt = at, updatedAt = maxOf(existing.updatedAt, at))
        diaryNotes[noteId] = updated
        persistDiaryNotesLocked()
        updated
    }

    /**
     * 把过期太久（>24h）的提醒标记成「已提醒」，但不产生消息。
     *
     * 返回清理条数。这样同一行不会在每次心跳里被反复捡起来。
     */
    fun expireOverdueDiaryReminders(now: Long): Int = lock.withLock {
        val doomed = diaryNotes.values.filter {
            val remindAt = it.remindAt ?: return@filter false
            it.remindedAt == null && remindAt <= now && now - remindAt > DiaryNote.CATCH_UP_WINDOW_MS
        }
        if (doomed.isEmpty()) return@withLock 0
        for (note in doomed) {
            diaryNotes[note.id] = note.copy(remindedAt = now, updatedAt = maxOf(note.updatedAt, now))
        }
        persistDiaryNotesLocked()
        doomed.size
    }

    // ================================================================== 念头（agent_intents）
    //
    // `defer_reply` 的服务端落点：模型说「过一会儿再说」之后，到点由心跳变成主动消息。

    fun saveIntent(intent: StoredIntent): StoredIntent = lock.withLock {
        intents[intent.id] = intent
        persistIntentsLocked()
        intent
    }

    fun intent(intentId: String): StoredIntent? = lock.withLock { intents[intentId] }

    fun pendingIntents(personaId: String? = null): List<StoredIntent> = lock.withLock {
        intents.values
            .filter { it.status == StoredIntent.STATUS_PENDING }
            .filter { personaId == null || it.personaId == personaId }
            .sortedWith(compareBy({ it.earliestAt }, { it.id }))
    }

    /** 到点（`earliestAt <= now`）的念头，按时间升序。 */
    fun dueIntents(now: Long, limit: Int = 20): List<StoredIntent> = lock.withLock {
        intents.values
            .filter { it.isDue(now) }
            .sortedWith(compareBy({ it.earliestAt }, { it.id }))
            .take(limit.coerceAtLeast(0))
    }

    fun markIntentSent(intentId: String, at: Long): Boolean = lock.withLock {
        val existing = intents[intentId] ?: return@withLock false
        intents[intentId] = existing.copy(status = StoredIntent.STATUS_SENT, sentAt = at)
        persistIntentsLocked()
        true
    }

    /** 过窗口太久的念头作废（不补发）；返回作废条数。 */
    fun expireIntents(now: Long): Int = lock.withLock {
        val doomed = intents.values.filter { it.isExpired(now) }
        if (doomed.isEmpty()) return@withLock 0
        for (intent in doomed) {
            intents[intent.id] = intent.copy(status = StoredIntent.STATUS_EXPIRED)
        }
        persistIntentsLocked()
        doomed.size
    }

    // ================================================================== 自动判定游标（auto_state）

    fun autoState(personaId: String): AutoState = lock.withLock {
        autoStates[personaId] ?: AutoState(personaId = personaId)
    }

    /** 记一条用户消息（自动判定的计数）；累计条数与游标分开存，重启后仍然准。 */
    fun recordUserMessageForAuto(personaId: String): AutoState = lock.withLock {
        val previous = autoStates[personaId] ?: AutoState(personaId = personaId)
        val updated = previous.copy(userMessagesSeen = previous.userMessagesSeen + 1)
        autoStates[personaId] = updated
        persistAutoStateLocked()
        updated
    }

    fun recordMoodAutoEval(personaId: String, at: Long): AutoState = lock.withLock {
        val previous = autoStates[personaId] ?: AutoState(personaId = personaId)
        val updated = previous.copy(moodCursor = previous.userMessagesSeen, moodEvaluatedAt = at)
        autoStates[personaId] = updated
        persistAutoStateLocked()
        updated
    }

    fun recordEvolutionAutoEval(personaId: String, at: Long): AutoState = lock.withLock {
        val previous = autoStates[personaId] ?: AutoState(personaId = personaId)
        val updated = previous.copy(evolutionCursor = previous.userMessagesSeen, evolutionEvaluatedAt = at)
        autoStates[personaId] = updated
        persistAutoStateLocked()
        updated
    }

    /** 记一次 `stay_silent`（连续沉默轮数 +1）。 */
    fun recordSilentTurn(personaId: String): AutoState = lock.withLock {
        val previous = autoStates[personaId] ?: AutoState(personaId = personaId)
        val updated = previous.copy(consecutiveSilent = previous.consecutiveSilent + 1)
        autoStates[personaId] = updated
        persistAutoStateLocked()
        updated
    }

    /** 真的回了一句：连续沉默轮数清零；累计回复条数 +1（心理活动的「每 3 轮」按它算）。 */
    fun recordAssistantReply(personaId: String): AutoState = lock.withLock {
        val previous = autoStates[personaId] ?: AutoState(personaId = personaId)
        val updated = previous.copy(
            consecutiveSilent = 0,
            assistantReplies = previous.assistantReplies + 1
        )
        autoStates[personaId] = updated
        persistAutoStateLocked()
        updated
    }

    /** 记一次心理活动：游标推进到当前回复条数（下一轮重新数）。 */
    fun recordMentalActivity(personaId: String, at: Long): AutoState = lock.withLock {
        val previous = autoStates[personaId] ?: AutoState(personaId = personaId)
        val updated = previous.copy(mentalCursor = previous.assistantReplies, mentalEvaluatedAt = at)
        autoStates[personaId] = updated
        persistAutoStateLocked()
        updated
    }

    /** 磁盘上真实的用户消息条数（游标丢失/首次运行时用它对齐，避免重复判定一整个历史）。 */
    fun countUserMessages(personaId: String): Long = lock.withLock {
        var total = 0L
        for (session in sessions.values) {
            if (session.personaId != personaId) continue
            for (block in blocksForLocked(session.id)) {
                total += readBlockLocked(session.id, block.index).count { it.role == MessageRole.USER }
            }
        }
        total
    }

    // ================================================================== 0.14 体验功能
    //
    // 三块状态（`spec/experience/README.md`）：拉黑（含两轮确认的提议）、心理活动、会话记忆。
    // 它们都「服务端自己跑、客户端只同步」，所以都落盘、都有明确的上限，不依赖任何连接。

    // ------------------------------------------------------------------ 拉黑

    /**
     * 把一个用户消息标记成「拉黑期间落库」之后，用这个查「这个人格此刻是否被拉黑」。
     *
     * 到期**自动解除**：只判断 `blockedUntil > now`，不需要任何人来改数据；心跳会顺手把
     * 过期字段真的清掉（见 [expireBlocks]），这样 `/v1/state` 不会一直返回一个过去的时刻。
     */
    fun isBlocked(personaId: String, now: Long = serverTime()): Boolean = lock.withLock {
        personas.firstOrNull { it.id == personaId }?.isBlockedAt(now) ?: false
    }

    /** 写拉黑（`blockedUntil` + 理由）。返回更新后的人格。 */
    fun blockPersona(personaId: String, until: Long, reason: String): Persona = lock.withLock {
        val index = personas.indexOfFirst { it.id == personaId }
        if (index < 0) throw StorePersonaNotFoundException(personaId)
        val now = nextTimestampLocked()
        val updated = personas[index].copy(
            blockedUntil = until,
            blockReason = reason.trim().take(MAX_BLOCK_REASON_CHARS),
            updatedAt = now
        )
        personas[index] = updated
        persistPersonasLocked()
        updated
    }

    /** 解除拉黑（正常情况下到期自动解除；这里给心跳清过期字段用）。 */
    fun clearBlock(personaId: String): Persona? = lock.withLock {
        val index = personas.indexOfFirst { it.id == personaId }
        if (index < 0) return@withLock null
        val existing = personas[index]
        if (existing.blockedUntil == null && existing.blockReason.isBlank()) return@withLock existing
        val updated = existing.copy(
            blockedUntil = null,
            blockReason = "",
            updatedAt = nextTimestampLocked()
        )
        personas[index] = updated
        persistPersonasLocked()
        updated
    }

    /** 把所有已经到期的拉黑字段清掉，返回清了几个人格（心跳每拍调一次）。 */
    fun expireBlocks(now: Long = serverTime()): Int = lock.withLock {
        val expiring = personas.filter { it.blockedUntil != null && it.blockedUntil <= now }
        if (expiring.isEmpty()) return@withLock 0
        val nowStamp = nextTimestampLocked()
        for (existing in expiring) {
            val index = personas.indexOfFirst { it.id == existing.id }
            if (index < 0) continue
            personas[index] = existing.copy(blockedUntil = null, blockReason = "", updatedAt = nowStamp)
        }
        persistPersonasLocked()
        expiring.size
    }

    /** 覆盖保存一个拉黑提议（同一个人格只留最新一条）。 */
    fun saveBlockProposal(proposal: BlockProposal): BlockProposal = lock.withLock {
        blockProposals[proposal.personaId] = proposal
        persistBlockProposalsLocked()
        proposal
    }

    fun blockProposal(personaId: String): BlockProposal? = lock.withLock { blockProposals[personaId] }

    /** 丢弃某个提议（确认生效 / 用户换了话题之后）。 */
    fun clearBlockProposal(personaId: String): Boolean = lock.withLock {
        if (blockProposals.remove(personaId) == null) return@withLock false
        persistBlockProposalsLocked()
        true
    }

    /** 清掉超时（10 分钟）的提议；返回清理条数。 */
    fun expireBlockProposals(now: Long = serverTime()): Int = lock.withLock {
        val doomed = blockProposals.values.filter { it.isExpired(now) }.map { it.personaId }
        if (doomed.isEmpty()) return@withLock 0
        for (personaId in doomed) blockProposals.remove(personaId)
        persistBlockProposalsLocked()
        doomed.size
    }

    // ------------------------------------------------------------------ 心理活动

    /**
     * 写一条心理活动；每个人格只保留最近 [MentalActivity.KEEP_RECENT] 条（规范 §3）。
     *
     * 上限在写入时裁剪，而不是读取时：`mental_activities.json` 是一个长期文件，
     * 不裁剪会无限长大。
     */
    fun saveMentalActivity(activity: MentalActivity): MentalActivity = lock.withLock {
        mentalActivities[activity.id] = activity.copy(
            thought = activity.thought.trim().replace('\n', ' ').take(MentalActivity.MAX_THOUGHT_CHARS)
        )
        val doomed = mentalActivities.values
            .filter { it.personaId == activity.personaId }
            .sortedWith(compareBy({ it.at }, { it.id }))
            .dropLast(MentalActivity.KEEP_RECENT)
        for (old in doomed) mentalActivities.remove(old.id)
        persistMentalActivitiesLocked()
        mentalActivities.getValue(activity.id)
    }

    /** 某人格的心理活动（最近的在前）。 */
    fun mentalActivities(personaId: String, limit: Int = MentalActivity.KEEP_RECENT): List<MentalActivity> =
        lock.withLock {
            mentalActivities.values
                .filter { it.personaId == personaId }
                .sortedWith(compareByDescending<MentalActivity> { it.at }.thenByDescending { it.id })
                .take(limit.coerceAtLeast(0))
        }

    /** 全部心理活动（备份导出用；按时间升序）。 */
    fun allMentalActivities(): List<MentalActivity> = lock.withLock {
        mentalActivities.values.sortedWith(compareBy({ it.at }, { it.id }))
    }

    /** 最近一条心理活动（提示词注入「你刚才心里想：…」用）。 */
    fun latestMentalActivity(personaId: String): MentalActivity? = lock.withLock {
        mentalActivities.values
            .filter { it.personaId == personaId }
            .maxWithOrNull(compareBy({ it.at }, { it.id }))
    }

    // ------------------------------------------------------------------ 会话记忆

    fun sessionMemory(sessionId: String): SessionMemory? = lock.withLock { sessionMemories[sessionId] }

    /** 全部会话记忆（备份导出用；按 sessionId 升序保证稳定）。 */
    fun allSessionMemories(): List<SessionMemory> = lock.withLock {
        sessionMemories.values.sortedBy { it.sessionId }
    }

    /** 写入/覆盖一段会话记忆（摘要为空也算「写过」，游标照存）。 */
    fun saveSessionMemory(memory: SessionMemory): SessionMemory = lock.withLock {
        val normalized = memory.copy(
            shortSummary = memory.shortSummary.trim().take(SessionMemory.MAX_SHORT_CHARS),
            longSummary = memory.longSummary.trim().take(SessionMemory.MAX_LONG_CHARS)
        )
        sessionMemories[memory.sessionId] = normalized
        persistSessionMemoryLocked()
        normalized
    }

    /** 删除某会话的记忆（会话被删时级联清掉，避免留下无主摘要）。 */
    fun deleteSessionMemory(sessionId: String): Boolean = lock.withLock {
        if (sessionMemories.remove(sessionId) == null) return@withLock false
        persistSessionMemoryLocked()
        true
    }

    // ================================================================== 0.15：主动消息失败记录

    /**
     * 记一条主动消息失败（生成/落库/投递/推送四个阶段都走这里）。
     *
     * 保留策略：**只留最近 [ProactiveFailure.KEEP_RECENT] 条**（跨人格，按 `at` 从新到旧）。
     * 它是诊断数据不是业务数据——无限增长只会让数据目录越来越难排查。
     * 返回值只用于测试/日志断言。
     */
    fun recordProactiveFailure(failure: ProactiveFailure): ProactiveFailure = lock.withLock {
        val normalized = failure.copy(detail = failure.detail.take(ProactiveFailure.MAX_DETAIL_CHARS))
        proactiveFailures[normalized.id] = normalized
        val doomed = proactiveFailures.values
            .sortedWith(compareByDescending<ProactiveFailure> { it.at }.thenByDescending { it.id })
            .drop(ProactiveFailure.KEEP_RECENT)
        for (old in doomed) proactiveFailures.remove(old.id)
        persistProactiveFailuresLocked()
        normalized
    }

    /** 最近的失败记录（新的在前）。[personaId] 非空时只看那个人格。 */
    fun proactiveFailures(
        personaId: String? = null,
        limit: Int = ProactiveFailure.KEEP_RECENT
    ): List<ProactiveFailure> = lock.withLock {
        proactiveFailures.values
            .filter { personaId == null || it.personaId == personaId }
            .sortedWith(compareByDescending<ProactiveFailure> { it.at }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))
    }

    /** 全部失败记录（备份导出用；按时间升序）。 */
    fun allProactiveFailures(): List<ProactiveFailure> = lock.withLock {
        proactiveFailures.values.sortedWith(compareBy({ it.at }, { it.id }))
    }

    // ================================================================== 备份（流式导出）

    /**
     * 流式导出备份 v6（`GET /v1/backup`）——**不构造整份 `JsonValue` 树、不构造整份字符串**。
     *
     * 为什么必须流式：备份里的 `media[].data` 是 base64（单张 ≤8 MiB、合计 ≤128 MiB），
     * 0.12.0 的实现把整份备份先拼成 `JsonValue.Obj`、再 `stringify` 成 `String`、再
     * `toByteArray` 写 socket —— 10 万条消息 + 500 张图时峰值是几百 MB。现在改成：
     *
     * 1. 在写锁内**只取元数据快照**（人格/会话/记忆/预设/设置/媒体清单，都是小对象）；
     * 2. 消息段按 `(createdAt, id)` 全局有序输出，每个会话一个惰性游标（一次读一块 ≤200 条），
     *    k 路归并 → 内存与「历史总长度」无关；
     * 3. 媒体段逐文件读、**边读边 base64 编码**（每次 24 KiB 原始字节），只做一次 sha256 校验
     *    预扫（同样是流式摘要），校验不过就跳过并计入 `mediaSkipped`；
     * 4. 直接写进调用方给的可追加对象（生产是 socket 上的 chunked writer）。
     *
     * 锁的取舍：0.12.0 在整个导出期间持锁；现在只在取快照时持锁，消息/媒体按块/按文件读取
     * （每次 `blocks()`/`blockMessages()` 自己短暂持锁）。好处是 100 MB 备份不会让聊天卡住，
     * 代价是导出期间新追加的消息**可能落在**快照与读取之间（备份仍是自洽的合法 JSON，只是
     * 不保证严格时间点一致；导出期间被删除的会话会被跳过）。
     *
     * 输出与 0.12.0 逐字节兼容：字段顺序、转义规则与 `Json.stringify` 一致。
     */
    fun writeBackup(appVersion: String, exportedAt: Long, out: Appendable) {
        val snapshot = lock.withLock {
            val mediaFiles = media.values.associate { it.id to it.exportFileName }
            val mediaInfo = media.values.associate { it.id to (it.exportFileName to it.mime) }
            ExportSnapshot(
                personas = personas.map { persona ->
                    BackupV6.persona(
                        persona = persona,
                        mood = moods[persona.id],
                        avatarFileName = mediaFiles[MediaUrl.idOf(persona.avatarUrl)]
                    )
                },
                sessions = sessions.values.map { BackupV6.session(it) },
                sessionIds = sessions.keys.toList(),
                sessionPersonas = sessions.values.associate { it.id to it.personaId },
                mediaInfo = mediaInfo,
                memories = memories.values.sortedBy { it.createdAt }.map { BackupV6.memory(it) },
                presets = presets.values.map { preset ->
                    BackupV6.preset(preset, mediaFiles[MediaUrl.idOf(preset.avatarUri)])
                },
                apiConfigs = apiConfigs.values.map { BackupV6.apiConfig(it) },
                stickers = stickers.values
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    .map { BackupV6.sticker(it) },
                diaryNotes = diaryNotes.values
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    .map { BackupV6.diaryNote(it) },
                intents = intents.values
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    .map { BackupV6.intent(it) },
                mentalActivities = mentalActivities.values
                    .sortedWith(compareBy({ it.at }, { it.id }))
                    .map { BackupV6.mentalActivity(it) },
                sessionMemories = sessionMemories.values
                    .sortedBy { it.sessionId }
                    .map { BackupV6.sessionMemory(it) },
                proactiveFailures = proactiveFailures.values
                    .sortedWith(compareBy({ it.at }, { it.id }))
                    .map { BackupV6.proactiveFailure(it) },
                settings = BackupV6.settings(publicSettingsLocked()),
                media = media.values.sortedWith(compareBy({ it.createdAt }, { it.id })).toList(),
                clientExtras = LinkedHashMap(clientExtras)
            )
        }

        val writer = JsonStreamWriter(out)
        writer.startObject()
        writer.key("format")
        writer.stringValue(BACKUP_FORMAT_NAME)
        writer.key("formatVersion")
        writer.number(BACKUP_FORMAT_VERSION)
        writer.key("appVersion")
        writer.stringValue(appVersion)
        writer.key("exportedAt")
        writer.number(exportedAt)
        writer.key("apiKeysOmitted")
        writer.bool(true)
        writer.key("personas")
        writer.value(jsonArray(snapshot.personas))
        writer.key("memories")
        writer.value(jsonArray(snapshot.memories))
        writer.key("sessions")
        writer.value(jsonArray(snapshot.sessions))
        writer.key("messages")
        writer.startArray()
        writeMessagesStreamed(writer, snapshot)
        writer.endArray()
        writer.key("media")
        writer.startArray()
        val mediaSkipped = writeMediaStreamed(writer, snapshot.media)
        writer.endArray()
        writer.key("stickers")
        writer.value(jsonArray(snapshot.stickers))
        writer.key("diaryNotes")
        writer.value(jsonArray(snapshot.diaryNotes))
        writer.key("presets")
        writer.value(jsonArray(snapshot.presets))
        // 客户端独有的两段（`spec/experience` §11）：**原样**写回导入时存下的值；
        // 服务端从没收到过就写空数组（键集仍与客户端 v6 一致）。
        writer.key("proactiveRules")
        writer.value(snapshot.clientExtras["proactiveRules"] ?: jsonArray(emptyList()))
        writer.key("agentIntents")
        writer.value(jsonArray(snapshot.intents))
        // 服务端专有数据统一放可选的 `extensions`：客户端 `ignoreUnknownKeys` 会忽略它，
        // 服务端自己再导入时读回来。旧的顶层 `mentalActivities` / `sessionMemory` /
        // `mediaSkipped` 仍然**接受导入**（0.14.0 的备份文件不能被新版本读丢）。
        writer.key("extensions")
        writer.startObject()
        writer.key("mediaSkipped")
        writer.number(mediaSkipped)
        if (snapshot.mentalActivities.isNotEmpty()) {
            writer.key("mentalActivities")
            writer.value(jsonArray(snapshot.mentalActivities))
        }
        if (snapshot.sessionMemories.isNotEmpty()) {
            writer.key("sessionMemory")
            writer.value(jsonArray(snapshot.sessionMemories))
        }
        // 0.15：主动消息的失败记录（诊断数据；客户端忽略，服务端换机后还能查）。
        if (snapshot.proactiveFailures.isNotEmpty()) {
            writer.key("proactiveFailures")
            writer.value(jsonArray(snapshot.proactiveFailures))
        }
        writer.endObject()
        writer.key("memoryConsolidations")
        writer.value(snapshot.clientExtras["memoryConsolidations"] ?: jsonArray(emptyList()))
        writer.key("apiConfigs")
        writer.value(jsonArray(snapshot.apiConfigs))
        writer.key("settings")
        writer.value(snapshot.settings)
        // 客户端将来新增的未知顶层段：导入时抓下来，导出时原样带回（不解析、不替换）。
        for ((key, value) in snapshot.clientExtras) {
            if (key == "proactiveRules" || key == "memoryConsolidations") continue
            writer.key(key)
            writer.value(value)
        }
        writer.endObject()
    }

    /** 导出开始时在锁内取的元数据快照（消息与媒体字节故意不在里面）。 */
    private class ExportSnapshot(
        val personas: List<JsonValue>,
        val sessions: List<JsonValue>,
        val sessionIds: List<String>,
        /** `sessionId → personaId`：v6 的 `messages[].personaId` 要用（服务端的消息里没有这一格）。 */
        val sessionPersonas: Map<String, String>,
        /** `mediaId → (裸文件名, mime)`：把附件引用翻译成 v6 的 `attachmentsJson`。 */
        val mediaInfo: Map<String, Pair<String, String>>,
        val memories: List<JsonValue>,
        val presets: List<JsonValue>,
        val apiConfigs: List<JsonValue>,
        val stickers: List<JsonValue>,
        val diaryNotes: List<JsonValue>,
        val intents: List<JsonValue>,
        /** 0.14：心理活动与会话记忆（客户端忽略，服务端往返用）。 */
        val mentalActivities: List<JsonValue> = emptyList(),
        val sessionMemories: List<JsonValue> = emptyList(),
        /** 0.15：主动消息失败记录（服务端专有诊断数据）。 */
        val proactiveFailures: List<JsonValue> = emptyList(),
        val settings: JsonValue.Obj,
        val media: List<MediaItem>,
        /** 0.14.1：客户端独有的备份段（原样透传）。 */
        val clientExtras: Map<String, JsonValue> = emptyMap()
    )

    private fun jsonArray(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items.toMutableList())

    /**
     * 消息段：按 `(createdAt, id)` 全局有序输出，但**不把 10 万条消息读进内存**——
     * 每个会话一个惰性游标（一次一块 = 最多 [BLOCK_SIZE] 条），用优先队列做 k 路归并。
     * 内存上限 ≈ 会话数 × 1 条消息，与历史长度无关。
     */
    private fun writeMessagesStreamed(writer: JsonStreamWriter, snapshot: ExportSnapshot) {
        val queue = PriorityQueue(compareBy<MessageHead> { it.message.createdAt }.thenBy { it.message.id })
        for (sessionId in snapshot.sessionIds) {
            val cursor = SessionMessageCursor(sessionId)
            cursor.advance()?.let { queue.add(MessageHead(it, cursor)) }
        }
        while (queue.isNotEmpty()) {
            val head = queue.poll()
            val message = head.message
            val personaId = snapshot.sessionPersonas[message.sessionId].orEmpty()
            writer.element()
            writer.value(BackupV6.message(message, personaId, attachmentsJson(message, snapshot.mediaInfo)))
            head.cursor.advance()?.let { queue.add(MessageHead(it, head.cursor)) }
        }
    }

    /**
     * 服务端附件（`/v1/media/<id>`）→ 客户端 v6 的 `attachmentsJson`（裸文件名 + mime）。
     *
     * 找不到媒体的附件**直接不写**（客户端 `BackupAttachmentsPortable.portable` 同一规则：
     * 换台手机也恢复不出来的引用不该留在备份里）。
     */
    private fun attachmentsJson(message: Message, mediaInfo: Map<String, Pair<String, String>>): String? {
        if (message.attachments.isEmpty()) return null
        val items = ArrayList<JsonValue>()
        for (attachment in message.attachments) {
            val id = MediaUrl.idOf(attachment) ?: continue
            val (fileName, mime) = mediaInfo[id] ?: continue
            val fields = linkedMapOf<String, JsonValue>(
                "uri" to StoreJson.str(fileName),
                "mime" to StoreJson.str(mime)
            )
            media[id]?.let { item ->
                when (item.kind) {
                    MediaKind.AUDIO -> fields["kind"] = StoreJson.str("AUDIO")
                    MediaKind.VIDEO -> fields["kind"] = StoreJson.str("VIDEO")
                    MediaKind.STICKER -> fields["kind"] = StoreJson.str("STICKER")
                }
            }
            items += JsonValue.Obj(fields)
        }
        return if (items.isEmpty()) null else StoreJson.stringify(JsonValue.Arr(items))
    }

    private class MessageHead(val message: Message, val cursor: SessionMessageCursor)

    /** 一个会话的消息游标：按块前进，内存里最多一块（[BLOCK_SIZE] 条）。 */
    private inner class SessionMessageCursor(private val sessionId: String) {
        private val blockIndexes: List<Int> = try {
            blocks(sessionId).map { it.index }
        } catch (_: Exception) {
            emptyList() // 导出期间会话被删掉：跳过它，不要打挂整份备份
        }
        private var blockPosition = 0
        private var buffer: List<Message> = emptyList()
        private var messagePosition = 0

        fun advance(): Message? {
            while (true) {
                if (messagePosition < buffer.size) return buffer[messagePosition++]
                if (blockPosition >= blockIndexes.size) return null
                buffer = try {
                    blockMessages(sessionId, blockIndexes[blockPosition++])
                } catch (_: Exception) {
                    emptyList()
                }
                messagePosition = 0
            }
        }
    }

    /**
     * 媒体段：逐文件读、流式 base64。
     *
     * 先流式计算一次 sha256（与 `media.json` 的元数据比对），再流式编码；两步都只占 8–24 KiB
     * 缓冲。读不出/超限/sha 对不上的条目**跳过并计数**（`mediaSkipped`），不让一张图毁掉整份
     * 备份，也不让导入端因为坏条目整份拒绝。
     *
     * 字段是 v6 的 `id/kind/personaId/fileName/mime/bytes/data`；多写一个 `sha256`
     * （客户端会忽略）是为了服务端自己再导入时还能校验字节。
     */
    private fun writeMediaStreamed(writer: JsonStreamWriter, items: List<MediaItem>): Int {
        var total = 0L
        var skipped = 0
        for (item in items) {
            if (item.bytes > MAX_BACKUP_MEDIA_FILE_BYTES) {
                skipped += 1
                continue
            }
            val file = try {
                mediaFile(item)
            } catch (_: StoreCorruptionException) {
                skipped += 1
                continue
            }
            if (!Files.exists(file)) {
                System.err.println("[agentd] 备份跳过文件缺失的媒体：${item.id}")
                skipped += 1
                continue
            }
            val actualSize = try {
                Files.size(file)
            } catch (_: IOException) {
                -1L
            }
            if (actualSize < 0 ||
                actualSize > MAX_BACKUP_MEDIA_FILE_BYTES ||
                total + actualSize > MAX_BACKUP_MEDIA_TOTAL_BYTES
            ) {
                skipped += 1
                continue
            }
            val actualSha = sha256OfFile(file)
            if (actualSha == null || actualSha != item.sha256) {
                System.err.println("[agentd] 备份跳过内容与元数据不一致的媒体：${item.id}")
                skipped += 1
                continue
            }
            total += actualSize
            writer.element()
            writer.startObject()
            writer.key("id")
            writer.stringValue(item.id)
            writer.key("kind")
            writer.stringValue(item.kind)
            writer.key("personaId")
            if (item.personaId == null) writer.nullValue() else writer.stringValue(item.personaId)
            writer.key("fileName")
            writer.stringValue(item.exportFileName)
            writer.key("mime")
            writer.stringValue(item.mime)
            writer.key("bytes")
            writer.number(item.bytes)
            writer.key("data")
            writer.beginLongString()
            appendBase64(writer, file)
            writer.endLongString()
            writer.key("sha256")
            writer.stringValue(item.sha256)
            writer.endObject()
        }
        return skipped
    }

    /**
     * 分块 base64：每次读 3 的整数倍字节，编码后的片段可以直接拼接（等价于整份编码），
     * 因此不需要把 8 MiB 图片一次读进内存。
     */
    private fun appendBase64(writer: JsonStreamWriter, file: Path) {
        val chunk = ByteArray(3 * 8192)
        Files.newInputStream(file).use { input ->
            while (true) {
                var read = 0
                while (read < chunk.size) {
                    val n = input.read(chunk, read, chunk.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) return
                val slice = if (read == chunk.size) chunk else chunk.copyOf(read)
                writer.stringChunk(Base64.getEncoder().encodeToString(slice))
                if (read < chunk.size) return
            }
        }
    }

    // ================================================================== 备份（流式导入）

    /**
     * `POST /v1/backup` 的入口：解析信封 `{"mode":"merge"|"replace","data":{…}}` 并导入。
     *
     * 输入是 [Reader]（生产是「请求体先流式落到的临时文件」、handler 直调路径是 `StringReader`），
     * 所以**整份备份从不进内存**。约束：`mode` 必须出现在 `data` 之前（我们自己的导出与
     * 0.12.x 客户端的 `JSON.stringify` 都保持这个顺序），否则 400 —— 见 README 的「风险」。
     */
    fun importBackupRequest(input: Reader, bareMode: String = MODE_MERGE): BackupImportResult {
        val reader = BackupJsonReader(input)
        reader.beginObject()
        val firstKey = reader.nextKey() ?: throw BackupValidationException("备份文件是空的")
        // 裸备份文件（客户端导出的 `android-export-v6.json` 原样 POST）：第一个键就是
        // `format` / `formatVersion` / `personas`…，此时用 [bareMode] 作为导入模式。
        if (firstKey != "mode" && firstKey != "data") {
            if (bareMode != MODE_MERGE && bareMode != MODE_REPLACE) {
                throw BackupValidationException("mode 只能是 merge 或 replace")
            }
            val staging = root.resolve(STAGING_DIR).resolve("import-${UUID.randomUUID()}")
            Files.createDirectories(staging)
            try {
                val parsed = parseBackupBody(reader, staging, firstKey)
                return lock.withLock { commitBackupStreamed(bareMode, parsed) }
            } finally {
                deleteRecursively(staging)
                runCatching { Files.deleteIfExists(root.resolve(STAGING_DIR)) }
            }
        }
        var mode: String? = null
        var result: BackupImportResult? = null
        var key: String? = firstKey
        while (key != null) {
            when (key) {
                "mode" -> {
                    mode = (reader.readValue() as? JsonValue.Str)?.value
                        ?: throw BackupValidationException("mode 必须是字符串")
                }

                "data" -> {
                    val requested = mode
                        ?: throw BackupValidationException("备份请求里 mode 必须出现在 data 之前")
                    if (result != null) throw BackupValidationException("备份请求里 data 出现了两次")
                    result = importBackupData(requested, reader)
                }

                else -> reader.skipValue()
            }
            key = reader.nextKey() ?: break
        }
        reader.expectEnd()
        return result ?: throw BackupValidationException("缺少字段 data（备份对象）")
    }

    /**
     * 流式导入一份备份。
     *
     * 分两段：
     * 1. **解析 + 字段校验 + 暂存**（不持写锁）：人格/会话/记忆/预设是小对象，留在内存；
     *    消息**逐条写进 `.staging/` 的 JSONL**（每条一个 `Message` 对象，随写随弃）；
     *    媒体 `data` 逐条 base64 解码到 `.staging/media-<n>.bin`（不产生 ByteArray 大块）。
     * 2. **引用校验 + 落盘**（持写锁）：所有校验通过后才动磁盘；媒体用原子 rename 移进
     *    `media/`，会话的块文件整份重写（块编号按最终顺序重排，与 0.12.0 语义一致）。
     *
     * 任何一步失败都会删掉整个 `.staging/` 子目录：**不会留下一半数据**，修好请求可以整份重试。
     */
    private fun importBackupData(mode: String, reader: BackupJsonReader): BackupImportResult {
        if (mode != "merge" && mode != "replace") {
            throw BackupValidationException("mode 只能是 merge 或 replace")
        }
        val staging = root.resolve(STAGING_DIR).resolve("import-${UUID.randomUUID()}")
        Files.createDirectories(staging)
        try {
            val parsed = parseBackupStream(reader, staging)
            return lock.withLock { commitBackupStreamed(mode, parsed) }
        } finally {
            deleteRecursively(staging)
            // 空目录也不留：导入失败时数据目录里应该「什么都没多出来」（测试与运维都靠这个）。
            runCatching { Files.deleteIfExists(root.resolve(STAGING_DIR)) }
        }
    }

    /** 流式解析后的中间形态：只有小对象在内存里，消息在 `.staging/` 下的 JSONL、媒体在暂存文件里。 */
    private class StreamedBackup(
        val personas: List<Persona>,
        val sessions: List<Session>,
        val memories: List<Memory>,
        val presets: List<Preset>,
        val settings: JsonValue.Obj?,
        val media: List<StreamedMedia>,
        /** sessionId → 该会话的导出消息（每行一个 `Message` JSON）。 */
        val messageSpools: Map<String, Path>,
        val messageCount: Int,
        /** 0.13（v6）：表情行；字节在 [media] 里（`kind=STICKER`）。 */
        val stickers: List<Sticker> = emptyList(),
        /** 0.13（v6）：日记板。 */
        val diaryNotes: List<DiaryNote> = emptyList(),
        /** `defer_reply` 的念头（服务端 v6 起也写进备份）。 */
        val intents: List<StoredIntent> = emptyList(),
        /** 0.14：心理活动（服务端扩展段，客户端忽略）。 */
        val mentalActivities: List<MentalActivity> = emptyList(),
        /** 0.14：会话记忆（服务端扩展段，客户端忽略）。 */
        val sessionMemories: List<SessionMemory> = emptyList(),
        /** 0.15：主动消息失败记录（服务端扩展段，客户端忽略）。 */
        val proactiveFailures: List<ProactiveFailure> = emptyList(),
        /** 0.14.1：客户端独有的顶层段（`proactiveRules` / `memoryConsolidations` / 未知段），原样透传。 */
        val clientExtras: Map<String, JsonValue> = emptyMap()
    )

    /** 备份里一条已解码到暂存文件的媒体（提交时原子移进 `media/`）。 */
    private class StreamedMedia(val item: MediaItem, val staged: Path)

    private fun parseBackupStream(reader: BackupJsonReader, staging: Path): StreamedBackup {
        reader.beginObject()
        return parseBackupBody(reader, staging, null)
    }

    /**
     * 解析备份对象的主体（`{` 已经读掉）。
     *
     * @param firstKey 已经读出来的第一个键（裸备份文件由 [importBackupRequest] 先读一个键来判断
     *   「这是 `{mode,data}` 信封还是一份裸备份」；对象**已经 begin**，所以这里不能再 begin 一次）。
     */
    private fun parseBackupBody(
        reader: BackupJsonReader,
        staging: Path,
        firstKey: String?
    ): StreamedBackup {
        val where = "backup"
        val now = clock()
        var format: String? = null
        var version: Int? = null
        val personas = ArrayList<Persona>()
        val sessions = ArrayList<Session>()
        val memories = ArrayList<Memory>()
        val presets = ArrayList<Preset>()
        var settings: JsonValue.Obj? = null
        val media = ArrayList<StreamedMedia>()
        val stickers = ArrayList<Sticker>()
        val diary = ArrayList<DiaryNote>()
        val intents = ArrayList<StoredIntent>()
        val mentalActivities = ArrayList<MentalActivity>()
        val sessionMemories = ArrayList<SessionMemory>()
        val proactiveFailures = ArrayList<ProactiveFailure>()
        val clientExtras = LinkedHashMap<String, JsonValue>()
        var clientExtrasBytes = 0L
        val messageSpools = LinkedHashMap<String, Path>()
        val spoolWriters = LinkedHashMap<String, BufferedWriter>()
        val messageIds = HashSet<String>()
        var messageCount = 0
        var mediaTotal = 0L

        // 客户端独有段抓取：原样保留 JSON 值；超量只跳过该段（它不是服务端数据，不该让导入失败）。
        fun captureClientExtra(name: String, value: JsonValue) {
            if (name.isBlank()) return
            if (name !in clientExtras && clientExtras.size >= MAX_CLIENT_EXTRAS_SECTIONS) {
                System.err.println("[agentd] 备份里的客户端独有段超过 $MAX_CLIENT_EXTRAS_SECTIONS 个，跳过 $name")
                return
            }
            val size = runCatching { StoreJson.stringify(value).toByteArray(StandardCharsets.UTF_8).size.toLong() }
                .getOrDefault(0L)
            if (size <= 0L || clientExtrasBytes + size > MAX_CLIENT_EXTRAS_BYTES) {
                System.err.println("[agentd] 备份里的客户端独有段 $name 超过 ${MAX_CLIENT_EXTRAS_BYTES / (1024 * 1024)} MiB，跳过")
                return
            }
            clientExtrasBytes += size
            clientExtras[name] = value
        }

        fun spoolFor(sessionId: String): BufferedWriter = spoolWriters.getOrPut(sessionId) {
            // 文件名用 sessionId 的 sha256 前缀（sessionId 本身已经过正则校验，这里再隔离一层）。
            val digest = sha256Hex(sessionId.toByteArray(StandardCharsets.UTF_8)).take(16)
            val file = staging.resolve("messages-$digest.jsonl")
            messageSpools[sessionId] = file
            Files.newBufferedWriter(file, StandardCharsets.UTF_8)
        }

        try {
            var pendingKey: String? = firstKey
            while (true) {
                val key = pendingKey ?: reader.nextKey() ?: break
                pendingKey = null
                when (key) {
                    // 已知但服务端不建模的顶层元数据/服务端段：**必须先读掉**，否则会掉进
                    // 下面的 `else` 被当成「客户端独有段」透传，导出时就出现重复键
                    // （0.14.1 的备份对称测试正是这样抓到它的）。
                    "appVersion", "exportedAt", "apiKeysOmitted", "apiConfigs", "mediaSkipped" ->
                        reader.readValue()

                    "format" -> format = (reader.readValue() as? JsonValue.Str)?.value
                    "formatVersion" -> version = (reader.readValue() as? JsonValue.Num)?.asInt
                        ?: throw BackupValidationException("备份的 formatVersion 必须是整数")

                    "personas" -> readBackupSection(reader, "personas") { obj, index ->
                        personas += parseBackupPersona(obj, "$where.personas[$index]", now)
                    }

                    "sessions" -> readBackupSection(reader, "sessions") { obj, index ->
                        sessions += parseBackupSession(obj, "$where.sessions[$index]", now)
                    }

                    "messages" -> {
                        reader.beginArray()
                        var index = 0
                        while (reader.hasNextElement()) {
                            val obj = reader.readValue() as? JsonValue.Obj
                                ?: throw BackupValidationException("备份 messages 第 ${index + 1} 项不是对象")
                            val message = parseBackupMessage(obj, "$where.messages[$index]", now)
                            if (!messageIds.add(message.id)) {
                                throw BackupValidationException("备份 messages 里 id=${message.id} 重复")
                            }
                            messageCount += 1
                            if (messageCount > MAX_BACKUP_MESSAGES) {
                                throw BackupValidationException("备份里的消息超过 $MAX_BACKUP_MESSAGES 条上限")
                            }
                            val spool = spoolFor(message.sessionId)
                            spool.write(StoreJson.stringify(message.toJson()))
                            spool.newLine()
                            index += 1
                        }
                    }

                    "memories" -> readBackupSection(reader, "memories") { obj, index ->
                        memories += parseBackupMemory(obj, "$where.memories[$index]", now)
                    }

                    "presets" -> readBackupSection(reader, "presets") { obj, index ->
                        presets += parseBackupPreset(obj, "$where.presets[$index]", now)
                    }

                    "media" -> {
                        reader.beginArray()
                        var index = 0
                        while (reader.hasNextElement()) {
                            val entry = readStreamedMedia(reader, staging, index, now)
                            mediaTotal += entry.item.bytes
                            if (mediaTotal > MAX_BACKUP_MEDIA_TOTAL_BYTES) {
                                throw BackupValidationException(
                                    "备份 media 总量超过 ${MAX_BACKUP_MEDIA_TOTAL_BYTES / (1024 * 1024)} MiB 上限"
                                )
                            }
                            media += entry
                            index += 1
                        }
                    }

                    "settings" -> {
                        val raw = reader.readValue() as? JsonValue.Obj
                            ?: throw BackupValidationException("备份的 settings 必须是对象")
                        settings = settingsFromBackup(raw)
                    }

                    // v6 的两段：表情行与日记板。缺失/空数组都是正常的（v1–v5 没有它们）。
                    "stickers" -> readBackupSection(reader, "stickers") { obj, index ->
                        stickers += parseBackupSticker(obj, "$where.stickers[$index]")
                    }

                    "diaryNotes" -> readBackupSection(reader, "diaryNotes") { obj, index ->
                        diary += parseBackupDiaryNote(obj, "$where.diaryNotes[$index]", now)
                    }

                    "agentIntents" -> readBackupSection(reader, "agentIntents") { obj, index ->
                        parseBackupIntent(obj, "$where.agentIntents[$index]", now)?.let { intents += it }
                    }

                    // 0.14 的服务端扩展段：缺省/空数组都是正常的（客户端导出的 v6 没有它们）。
                    "mentalActivities" -> readBackupSection(reader, "mentalActivities") { obj, index ->
                        mentalActivities += parseBackupMentalActivity(obj, "$where.mentalActivities[$index]")
                    }

                    "sessionMemory" -> readBackupSection(reader, "sessionMemory") { obj, index ->
                        sessionMemories += parseBackupSessionMemory(obj, "$where.sessionMemory[$index]")
                    }

                    // 0.15：主动消息失败记录（顶层只作为旧文件的兼容读入口；新文件写在 extensions 里）。
                    "proactiveFailures" -> readBackupSection(reader, "proactiveFailures") { obj, index ->
                        proactiveFailures += parseBackupProactiveFailure(obj, "$where.proactiveFailures[$index]")
                    }

                    // 0.14.1：客户端独有的段 —— 不解析、不替换，原样存进 `client_extras.json`。
                    "proactiveRules", "memoryConsolidations" -> captureClientExtra(key, reader.readValue())

                    // 服务端专有数据（0.14.1 起写在这里；旧的顶层同名字段仍然可读）。
                    "extensions" -> readServerExtensions(
                        value = reader.readValue(),
                        where = "$where.extensions",
                        mentalActivities = mentalActivities,
                        sessionMemories = sessionMemories,
                        proactiveFailures = proactiveFailures
                    )

                    // 客户端将来新增的其它段：同样原样透传（不认识的段不能一导入就丢）。
                    else -> captureClientExtra(key, reader.readValue())
                }
            }
        } finally {
            for (writer in spoolWriters.values) runCatching { writer.close() }
        }

        if (format != null && format != BACKUP_FORMAT_NAME) {
            throw BackupValidationException("不是 Yap 备份（format=$format）")
        }
        if (version == null) throw BackupValidationException("备份缺少 formatVersion")
        // v1–v5 仍可读（老备份别丢），只有比本服务新的版本才拒绝（列 7 起）。
        if (version < 1 || version > BACKUP_FORMAT_VERSION) {
            throw UnsupportedBackupVersionException(version)
        }

        requireUniqueIds("personas", personas.map { it.id })
        requireUniqueIds("sessions", sessions.map { it.id })
        requireUniqueIds("memories", memories.map { it.id })
        requireUniqueIds("presets", presets.map { it.id })
        requireUniqueIds("media", media.map { it.item.id })
        requireUniqueIds("stickers", stickers.map { it.id })
        requireUniqueIds("diaryNotes", diary.map { it.id })
        requireUniqueIds("agentIntents", intents.map { it.id })
        // `extensions` 与旧的顶层字段可能同时出现（0.14.0 的文件 + 新文件各一份）：
        // 按 id/sessionId 去重，保留先出现的（不因为「同一份数据写了两次」拒绝整份导入）。
        if (mentalActivities.size > 1) {
            val unique = LinkedHashMap<String, MentalActivity>()
            for (activity in mentalActivities) unique.putIfAbsent(activity.id, activity)
            mentalActivities.clear()
            mentalActivities += unique.values
        }
        if (sessionMemories.size > 1) {
            val unique = LinkedHashMap<String, SessionMemory>()
            for (memory in sessionMemories) unique.putIfAbsent(memory.sessionId, memory)
            sessionMemories.clear()
            sessionMemories += unique.values
        }
        if (proactiveFailures.size > 1) {
            val unique = LinkedHashMap<String, ProactiveFailure>()
            for (failure in proactiveFailures) unique.putIfAbsent(failure.id, failure)
            proactiveFailures.clear()
            proactiveFailures += unique.values
        }
        requireUniqueIds("mentalActivities", mentalActivities.map { it.id })
        requireUniqueIds("sessionMemory", sessionMemories.map { it.sessionId })
        requireUniqueIds("proactiveFailures", proactiveFailures.map { it.id })

        return StreamedBackup(
            personas = personas,
            sessions = sessions,
            memories = memories,
            presets = presets,
            settings = settings,
            media = media,
            messageSpools = messageSpools,
            messageCount = messageCount,
            stickers = stickers,
            diaryNotes = diary,
            intents = intents,
            mentalActivities = mentalActivities,
            sessionMemories = sessionMemories,
            proactiveFailures = proactiveFailures,
            clientExtras = clientExtras
        )
    }

    /** 读一个「对象数组」段：一次只持有一个元素，交给 [consume] 校验/收集。 */
    private inline fun readBackupSection(
        reader: BackupJsonReader,
        name: String,
        consume: (JsonValue.Obj, Int) -> Unit
    ) {
        reader.beginArray()
        var index = 0
        while (reader.hasNextElement()) {
            val obj = reader.readValue() as? JsonValue.Obj
                ?: throw BackupValidationException("备份 $name 第 ${index + 1} 项不是对象")
            consume(obj, index)
            index += 1
        }
    }

    /**
     * 0.14.1：服务端专有的 `extensions` 段（客户端忽略它，服务端自己读回来）。
     *
     * 只有两个可加数组有意义：`mentalActivities[]` / `sessionMemory[]`；`mediaSkipped` 是导出统计，
     * 导入时重算，直接忽略。未来新增的服务端键也走 `else -> 忽略`（前向兼容：新服务端的备份
     * 被旧服务端读时不会因为不认识的键整份失败）。
     */
    private fun readServerExtensions(
        value: JsonValue,
        where: String,
        mentalActivities: MutableList<MentalActivity>,
        sessionMemories: MutableList<SessionMemory>,
        proactiveFailures: MutableList<ProactiveFailure> = ArrayList()
    ) {
        val obj = value as? JsonValue.Obj
            ?: throw BackupValidationException("备份的 extensions 必须是对象")
        for ((name, raw) in obj.fields) {
            when (name) {
                "mediaSkipped" -> Unit
                "mentalActivities" -> {
                    val items = raw as? JsonValue.Arr
                        ?: throw BackupValidationException("$where.mentalActivities 必须是数组")
                    items.items.forEachIndexed { index, item ->
                        val element = item as? JsonValue.Obj
                            ?: throw BackupValidationException("$where.mentalActivities 第 ${index + 1} 项不是对象")
                        mentalActivities += parseBackupMentalActivity(element, "$where.mentalActivities[$index]")
                    }
                }

                "sessionMemory" -> {
                    val items = raw as? JsonValue.Arr
                        ?: throw BackupValidationException("$where.sessionMemory 必须是数组")
                    items.items.forEachIndexed { index, item ->
                        val element = item as? JsonValue.Obj
                            ?: throw BackupValidationException("$where.sessionMemory 第 ${index + 1} 项不是对象")
                        sessionMemories += parseBackupSessionMemory(element, "$where.sessionMemory[$index]")
                    }
                }

                "proactiveFailures" -> {
                    val items = raw as? JsonValue.Arr
                        ?: throw BackupValidationException("$where.proactiveFailures 必须是数组")
                    items.items.forEachIndexed { index, item ->
                        val element = item as? JsonValue.Obj
                            ?: throw BackupValidationException("$where.proactiveFailures 第 ${index + 1} 项不是对象")
                        proactiveFailures += parseBackupProactiveFailure(element, "$where.proactiveFailures[$index]")
                    }
                }

                else -> Unit
            }
        }
    }

    /** 设置段：只留白名单键，并把历史别名归一成规范键（`myAvatarUri` → `userAvatarUri`）。 */
    private fun settingsFromBackup(raw: JsonValue.Obj): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        for ((key, value) in raw.fields) {
            val canonical = canonicalSettingKey(key)
            if (canonical in SETTINGS_WHITELIST) fields[canonical] = value
        }
        return JsonValue.Obj(fields)
    }

    /**
     * 流式解析一条媒体：`data` 边读边 base64 解码到暂存文件，其余字段与字段顺序无关
     * （`data` 出现在 `sha256` 之前也可以）。校验规则与 0.12.0 的 `parseBackupMedia` 一致。
     */
    private fun readStreamedMedia(
        reader: BackupJsonReader,
        staging: Path,
        index: Int,
        now: Long
    ): StreamedMedia {
        val where = "backup.media[$index]"
        val staged = staging.resolve("media-$index.bin")
        var id: String? = null
        var kind: String? = null
        var mime: String? = null
        var personaId: String? = null
        var declaredBytes: Long? = null
        var declaredSha: String? = null
        var createdAt: Long? = null
        var fileName: String? = null
        var actualBytes = 0L
        var dataSeen = false

        reader.beginObject()
        while (true) {
            val key = reader.nextKey() ?: break
            when (key) {
                "id" -> id = (reader.readValue() as? JsonValue.Str)?.value
                "kind" -> kind = (reader.readValue() as? JsonValue.Str)?.value
                "mime" -> mime = (reader.readValue() as? JsonValue.Str)?.value
                "personaId" -> personaId = (reader.readValue() as? JsonValue.Str)?.value
                    ?.takeIf { it.isNotBlank() }

                "bytes" -> declaredBytes = (reader.readValue() as? JsonValue.Num)?.asLong
                "sha256" -> declaredSha = (reader.readValue() as? JsonValue.Str)?.value?.trim()?.lowercase()
                "createdAt" -> createdAt = (reader.readValue() as? JsonValue.Num)?.asLong
                // v6：客户端备份里的裸文件名（与 avatarUri / attachmentsJson[].uri / stickers[].fileName 对齐）。
                "fileName" -> {
                    val raw = (reader.readValue() as? JsonValue.Str)?.value?.trim().orEmpty()
                    // 只接受裸文件名；带目录/协议的一律丢掉（备份不该把设备路径带进来）。
                    fileName = raw.takeIf { MediaItem.FILE_NAME_PATTERN.matches(it) }
                }
                "data" -> {
                    if (dataSeen) throw BackupValidationException("$where 的 data 出现了两次")
                    dataSeen = true
                    Files.createDirectories(staged.parent)
                    actualBytes = Files.newOutputStream(staged).use { output ->
                        reader.readBase64To(output, MAX_BACKUP_MEDIA_FILE_BYTES, "$where.data")
                    }
                }

                else -> reader.skipValue()
            }
        }

        val mediaId = id ?: throw BackupValidationException("$where 缺少 id")
        if (!MediaUrl.isValidId(mediaId)) throw BackupValidationException("$where 的 id 不合法")
        val kindValue = kind ?: throw BackupValidationException("$where 缺少 kind")
        if (!MediaKind.isValid(kindValue)) throw BackupValidationException("$where 的 kind 不合法：$kindValue")
        val mimeValue = mime ?: throw BackupValidationException("$where 缺少 mime")
        if (!MediaMime.isValid(mimeValue)) throw BackupValidationException("$where 的 mime 不合法：$mimeValue")
        if (personaId != null && !isValidId(personaId)) {
            throw BackupValidationException("$where 的 personaId 不合法")
        }
        if (!dataSeen) throw BackupValidationException("$where 缺少 data")
        val actualSha = sha256OfFile(staged)
            ?: throw BackupValidationException("$where 的 data 读不回来")
        if (declaredSha != null && declaredSha != actualSha) {
            throw BackupValidationException("$where 的 sha256 与 data 不一致")
        }
        if (declaredBytes != null && declaredBytes != actualBytes) {
            throw BackupValidationException("$where 的 bytes 与 data 长度不一致")
        }
        val item = MediaItem(
            id = mediaId,
            kind = kindValue,
            personaId = personaId,
            mime = mimeValue,
            bytes = actualBytes,
            sha256 = actualSha,
            createdAt = createdAt ?: now,
            fileName = fileName
        )
        return StreamedMedia(item, staged)
    }

    /**
     * 引用校验 + 落盘（**在写锁内**）。
     *
     * 校验顺序与 0.12.0 一致：人格/会话唯一性、一人格一会话、会话→人格、消息→会话、
     * 记忆→人格、`settings.activePersonaId`、媒体 id 冲突与 persona 引用。全部通过后才动磁盘。
     */
    private fun commitBackupStreamed(mode: String, parsed: StreamedBackup): BackupImportResult {
        val finalPersonas = LinkedHashMap<String, Persona>()
        if (mode == "merge") personas.forEach { finalPersonas[it.id] = it }
        for (persona in parsed.personas) finalPersonas[persona.id] = persona

        val finalSessions = LinkedHashMap<String, Session>()
        if (mode == "merge") sessions.forEach { (id, session) -> finalSessions[id] = session }
        for (session in parsed.sessions) finalSessions[session.id] = session
        val duplicatePersona = finalSessions.values
            .groupBy { it.personaId }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (duplicatePersona != null) {
            throw BackupValidationException(
                "人格 ${duplicatePersona.key} 只能有一个会话，备份里有 ${duplicatePersona.value.size} 个"
            )
        }
        for (session in finalSessions.values) {
            if (finalPersonas[session.personaId] == null) {
                throw BackupValidationException("会话 ${session.id} 引用了不存在的人格 ${session.personaId}")
            }
        }
        for (sessionId in parsed.messageSpools.keys) {
            if (finalSessions[sessionId] == null) {
                throw BackupValidationException("消息引用了不存在的会话 $sessionId")
            }
        }
        val finalMemories = LinkedHashMap<String, Memory>()
        if (mode == "merge") memories.forEach { (id, memory) -> finalMemories[id] = memory }
        for (memory in parsed.memories) finalMemories[memory.id] = memory
        for (memory in parsed.memories) {
            if (finalPersonas[memory.personaId] == null) {
                throw BackupValidationException("记忆 ${memory.id} 引用了不存在的人格 ${memory.personaId}")
            }
        }
        val finalPresets = LinkedHashMap<String, Preset>()
        if (mode == "merge") presets.forEach { (id, preset) -> finalPresets[id] = preset }
        for (preset in parsed.presets) finalPresets[preset.id] = preset
        val activePersonaId = parsed.settings?.fields?.get("activePersonaId")
        if (activePersonaId is JsonValue.Str && finalPersonas[activePersonaId.value] == null) {
            throw BackupValidationException("settings.activePersonaId 引用了不存在的人格 ${activePersonaId.value}")
        }
        for (entry in parsed.media) {
            val existing = media[entry.item.id]
            if (existing != null && existing.sha256 != entry.item.sha256) {
                throw BackupValidationException("媒体 ${entry.item.id} 已存在且内容不同，不能用备份覆盖")
            }
            val personaId = entry.item.personaId
            if (personaId != null && finalPersonas[personaId] == null) {
                throw BackupValidationException("媒体 ${entry.item.id} 引用了不存在的人格 $personaId")
            }
        }

        // v6 的两段跨表引用：表情与日记都必须挂在存在的人格下（与记忆同一规则）。
        for (sticker in parsed.stickers) {
            val personaId = sticker.personaId
            if (personaId != null && finalPersonas[personaId] == null) {
                throw BackupValidationException("表情 ${sticker.id} 引用了不存在的人格 $personaId")
            }
        }
        for (note in parsed.diaryNotes) {
            if (finalPersonas[note.personaId] == null) {
                throw BackupValidationException("日记 ${note.id} 引用了不存在的人格 ${note.personaId}")
            }
        }
        // 0.14：心理活动挂人格、会话记忆挂会话；后者还要和会话的人格一致（防串记忆）。
        for (activity in parsed.mentalActivities) {
            if (finalPersonas[activity.personaId] == null) {
                throw BackupValidationException("心理活动 ${activity.id} 引用了不存在的人格 ${activity.personaId}")
            }
        }
        for (memory in parsed.sessionMemories) {
            val session = finalSessions[memory.sessionId]
                ?: throw BackupValidationException("会话记忆引用了不存在的会话 ${memory.sessionId}")
            if (session.personaId != memory.personaId) {
                throw BackupValidationException(
                    "会话记忆 ${memory.sessionId} 的人格 ${memory.personaId} 与会话的 ${session.personaId} 不一致"
                )
            }
        }

        // 受影响会话：导入的会话 + 导入消息落在的会话（replace 时是所有最终会话）。
        val affectedSessions = LinkedHashSet<String>()
        if (mode == "replace") {
            affectedSessions += finalSessions.keys
        } else {
            parsed.sessions.forEach { affectedSessions += it.id }
            parsed.messageSpools.keys.forEach { affectedSessions += it }
        }
        affectedSessions.retainAll(finalSessions.keys)
        // merge 语义需要旧消息：必须在删会话目录**之前**读出来。
        val existingPerSession = HashMap<String, List<Message>>()
        if (mode == "merge") {
            for (sessionId in affectedSessions) {
                if (sessions.containsKey(sessionId)) {
                    existingPerSession[sessionId] = readSessionMessagesLocked(sessionId)
                }
            }
        }

        // ---- 校验全部通过，开始改内存/落盘 ----
        if (mode == "replace") {
            deleteRecursively(sessionsDir)
            Files.createDirectories(sessionsDir)
            blockCache.clear()
            messageIndexCache.clear()
            userMessageCounts.clear()
            moods.clear()
            presence.clear()
            proactiveStates.clear()
            stickers.clear()
            diaryNotes.clear()
            intents.clear()
            autoStates.clear()
            mentalActivities.clear()
            sessionMemories.clear()
            blockProposals.clear()
            proactiveFailures.clear()
            deliveries.clear()
        } else {
            for (sessionId in affectedSessions) {
                deleteRecursively(sessionDir(sessionId))
                blockCache.remove(sessionId)
                messageIndexCache.remove(sessionId)
                invalidateUserMessageCountsLocked(sessionId)
            }
        }

        if (mode == "replace") {
            // replace = 用备份整个替换：旧媒体也清掉，避免留下再也没人引用的孤儿文件。
            deleteRecursively(mediaDir)
            Files.createDirectories(mediaDir)
            media.clear()
        }
        for (entry in parsed.media) {
            val existing = media[entry.item.id]
            if (existing == null || !Files.exists(mediaFile(existing))) {
                moveStagedMedia(entry.staged, mediaFile(entry.item))
            }
            media[entry.item.id] = entry.item
        }
        persistMediaLocked()

        // 「裸文件名 → 媒体 id」：v6 的 avatarUri / attachmentsJson[].uri / stickers[].fileName
        // 全靠它接回服务端的 `/v1/media/<id>`。先来先得（同名文件以先导入的为准）。
        val mediaByFileName = LinkedHashMap<String, String>()
        for (item in media.values.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            mediaByFileName.putIfAbsent(item.exportFileName, item.id)
        }

        // 人格头像：客户端备份给的是裸文件名，换成 `/v1/media/<id>`；解析不了就保留原名（不报错）。
        for ((personaId, persona) in finalPersonas) {
            val raw = BackupV6.extraStringOrNull(persona.extraFields, "avatarUri") ?: continue
            val mediaId = mediaByFileName[raw] ?: continue
            val extras = LinkedHashMap(persona.extraFields)
            extras.remove("avatarUri")
            finalPersonas[personaId] = persona.copy(
                avatarUrl = MediaUrl.url(mediaId),
                extraFields = extras
            )
        }

        // 只重写受影响会话的块文件：消息按 (createdAt, id) 重排后按 BLOCK_SIZE 重新切块。
        for (sessionId in affectedSessions) {
            val template = finalSessions.getValue(sessionId)
            val merged = LinkedHashMap<String, Message>()
            existingPerSession[sessionId]?.forEach { merged[it.id] = it }
            for (message in readMessageSpool(parsed.messageSpools[sessionId])) {
                merged[message.id] = message.copy(
                    attachments = message.attachments.mapNotNull { resolveAttachment(it, mediaByFileName) }
                )
            }
            val ordered = merged.values.sortedWith(MESSAGE_ORDER)
            val metas = writeSessionBlocksLocked(sessionId, ordered)
            blockCache[sessionId] = metas.toMutableList()
            persistBlockIndexLocked(sessionId, metas)
            val last = ordered.lastOrNull()
            val unread = ordered.count { it.role == MessageRole.ASSISTANT && it.readAt == null }
            finalSessions[sessionId] = template.copy(
                lastMessageAt = last?.createdAt,
                lastBlockIndex = if (ordered.isEmpty()) -1 else (ordered.size - 1) / BLOCK_SIZE,
                unread = unread,
                messageCount = ordered.size.toLong(),
                updatedAt = maxOf(template.updatedAt, last?.createdAt ?: 0L)
            )
        }

        personas.clear()
        personas.addAll(finalPersonas.values)
        sessions.clear()
        sessions.putAll(finalSessions)
        memories.clear()
        memories.putAll(finalMemories)
        presets.clear()
        presets.putAll(finalPresets)

        // 人格上带回来的心情（客户端把「此刻的心情」当人格的一部分）：写回 `moods.json`。
        for (persona in parsed.personas) {
            val label = BackupV6.extraStringOrNull(persona.extraFields, "mood")?.takeIf { it.isNotBlank() }
                ?: continue
            val intensity = BackupV6.extraInt(persona.extraFields, "moodIntensity") ?: 2
            moods[persona.id] = Mood(
                personaId = persona.id,
                label = label,
                intensity = intensity.coerceIn(0, 5),
                updatedAt = BackupV6.extraLong(persona.extraFields, "moodUpdatedAt") ?: persona.updatedAt,
                valence = Mood.defaultValence(label),
                arousal = Mood.defaultArousal(intensity),
                dominance = Mood.defaultDominance(),
                reason = BackupV6.extraString(persona.extraFields, "moodReason").take(Mood.MAX_REASON_CHARS)
            )
        }

        // v6 的表情 / 日记 / 念头：merge 按 id 覆盖，replace 已经清空。
        for (sticker in parsed.stickers) {
            // 客户端 v6 的 `stickers[].id` 与 `media[]` 的 `id` 是两套编号，靠 `fileName` 关联。
            // 服务端内部约定「表情 id = 媒体 id」，所以这里按文件名归一一次；找不到字节就原样保留
            // （一条没有文件的表情行，比让整份备份失败好）。
            val mediaId = mediaByFileName[sticker.file]
            val normalized = if (mediaId != null && mediaId != sticker.id) sticker.copy(id = mediaId) else sticker
            stickers[normalized.id] = normalized
        }
        for (note in parsed.diaryNotes) diaryNotes[note.id] = note
        for (intent in parsed.intents) {
            // 念头是易失的：人格已经不在了就丢掉，不让它把整份导入顶回去。
            if (finalPersonas[intent.personaId] != null) intents[intent.id] = intent
        }
        // 0.14：心理活动按 id 覆盖；会话记忆按 sessionId 覆盖（merge 时旧会话保留其余状态）。
        for (activity in parsed.mentalActivities) {
            if (finalPersonas[activity.personaId] != null) mentalActivities[activity.id] = activity
        }
        for (memory in parsed.sessionMemories) {
            if (finalSessions[memory.sessionId] != null) sessionMemories[memory.sessionId] = memory
        }
        // 0.15：主动消息失败记录也按 id 覆盖（人格已经不在了就丢掉，它只是诊断数据）。
        for (failure in parsed.proactiveFailures) {
            if (finalPersonas[failure.personaId] != null) proactiveFailures[failure.id] = failure
        }
        // 与运行时同一套保留策略：只留最近 200 条（导入一份别的备份不该把上限撑破）。
        val doomedFailures = proactiveFailures.values
            .sortedWith(compareByDescending<ProactiveFailure> { it.at }.thenByDescending { it.id })
            .drop(ProactiveFailure.KEEP_RECENT)
        for (old in doomedFailures) proactiveFailures.remove(old.id)
        // 心理活动仍然只保留最近 50 条/人格（导入一份别的备份不该把上限撑破）。
        for (personaId in finalPersonas.keys) {
            val doomed = mentalActivities.values
                .filter { it.personaId == personaId }
                .sortedWith(compareBy({ it.at }, { it.id }))
                .dropLast(MentalActivity.KEEP_RECENT)
            for (old in doomed) mentalActivities.remove(old.id)
        }

        // 合并设置的语义：备份里给了 settings 才改；只接白名单键。
        if (parsed.settings != null) {
            val merged = LinkedHashMap<String, JsonValue>()
            if (mode == "merge") {
                for ((key, value) in publicSettingsLocked().fields) merged[key] = value
            }
            for ((key, value) in parsed.settings.fields) {
                if (key in SETTINGS_WHITELIST) merged[key] = value
            }
            settings = JsonValue.Obj(merged)
        } else if (mode == "replace") {
            settings = JsonValue.Obj()
        }

        persistPersonasLocked()
        persistSessionsLocked()
        persistMoodsLocked()
        persistPresenceLocked()
        persistProactiveStateLocked()
        persistMemoriesLocked()
        persistPresetsLocked()
        persistStickersLocked()
        persistDiaryNotesLocked()
        persistIntentsLocked()
        persistMentalActivitiesLocked()
        persistSessionMemoryLocked()
        persistBlockProposalsLocked()
        persistProactiveFailuresLocked()
        // 0.14.1：客户端独有段的透传。replace = 备份就是新状态（旧透传清掉）；
        // merge = 按段覆盖，没提到的段保留（与其它 merge 语义一致）。
        if (mode == "replace") clientExtras.clear()
        clientExtras.putAll(parsed.clientExtras)
        persistClientExtrasLocked()
        writeJsonLocked(root.resolve(SETTINGS_FILE), StoreJson.obj("settings" to settings))
        lastTimestamp = maxOf(lastTimestamp, computeLastTimestampLocked())
        for (memory in memories.values) lastTimestamp = maxOf(lastTimestamp, memory.updatedAt)
        for (preset in presets.values) lastTimestamp = maxOf(lastTimestamp, preset.updatedAt)

        return BackupImportResult(
            mode = mode,
            personas = parsed.personas.size,
            sessions = parsed.sessions.size,
            messages = parsed.messageCount,
            memories = parsed.memories.size,
            presets = parsed.presets.size,
            media = parsed.media.size,
            settings = parsed.settings != null,
            stickers = parsed.stickers.size,
            diaryNotes = parsed.diaryNotes.size,
            mentalActivities = parsed.mentalActivities.size,
            sessionMemories = parsed.sessionMemories.size,
            proactiveFailures = parsed.proactiveFailures.size
        )
    }

    /**
     * 备份里的一条附件引用 → 服务端的 `/v1/media/<id>`。
     *
     * 三种输入都认：已经是 `/v1/media/<id>`（服务端 v5 备份）、裸媒体 id、
     * v6 的裸文件名（`attachmentsJson[].uri`）。都解析不出来就丢掉这条附件
     * （客户端 `BackupAttachmentsPortable.portable` 同样的取舍：恢复不出来的引用不写进历史）。
     */
    private fun resolveAttachment(reference: String, mediaByFileName: Map<String, String>): String? {
        val text = reference.trim()
        if (text.isEmpty()) return null
        MediaUrl.idOf(text)?.let { id ->
            if (media.containsKey(id)) return MediaUrl.url(id)
        }
        val byName = mediaByFileName[text] ?: return null
        return MediaUrl.url(byName)
    }

    private fun requireUniqueIds(section: String, ids: List<String>) {
        val duplicated = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicated != null) {
            throw BackupValidationException("备份 $section 里 id=${duplicated.key} 重复")
        }
    }

    /** 读回一个会话的暂存消息（每行一个 `Message`）；没有消息时返回空表。 */
    private fun readMessageSpool(file: Path?): List<Message> {
        if (file == null || !Files.exists(file)) return emptyList()
        val result = ArrayList<Message>()
        var lineNumber = 0
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (line.isBlank()) continue
                val where = "$file 第 $lineNumber 行"
                val parsed = try {
                    StoreJson.parse(line, where) as? JsonValue.Obj
                        ?: throw StoreCorruptionException("$where 不是 JSON 对象")
                } catch (failure: StoreCorruptionException) {
                    // 暂存文件是我们自己刚写的：读不回来说明磁盘/内存出了问题，按备份不合法上报。
                    throw BackupValidationException("备份消息暂存文件读不回来：${failure.message}")
                }
                result += Message.fromJson(parsed, where)
            }
        }
        return result
    }

    /** 会话磁盘上的全部消息（按块顺序）。merge 导入需要它来保留未提及的旧消息。 */
    private fun readSessionMessagesLocked(sessionId: String): List<Message> {
        val result = ArrayList<Message>()
        for (block in blocksForLocked(sessionId)) {
            result.addAll(readBlockLocked(sessionId, block.index))
        }
        return result
    }

    /** 把一个按顺序排好的消息表整份写成块文件，返回新的块表。 */
    private fun writeSessionBlocksLocked(sessionId: String, ordered: List<Message>): List<BlockMeta> {
        val metas = ArrayList<BlockMeta>()
        var start = 0
        while (start < ordered.size) {
            val end = minOf(start + BLOCK_SIZE, ordered.size)
            val blockIndex = start / BLOCK_SIZE
            val slice = ordered.subList(start, end)
            val text = buildString {
                for (message in slice) {
                    append(StoreJson.stringify(message.copy(blockIndex = blockIndex).toJson()))
                    append('\n')
                }
            }
            writeTextLocked(blockFile(sessionId, blockIndex), text)
            metas += BlockMeta(
                index = blockIndex,
                firstMessageId = slice.first().id,
                lastMessageId = slice.last().id,
                firstAt = slice.first().createdAt,
                lastAt = slice.last().createdAt,
                count = slice.size
            )
            start = end
        }
        return metas
    }

    /** 暂存媒体 → `media/<id>.<ext>`：同一文件系统，优先原子 rename。 */
    private fun moveStagedMedia(staged: Path, target: Path) {
        Files.createDirectories(target.parent)
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 流式 SHA-256（小写十六进制）；文件不存在/读失败返回 null（导出侧把它当成「跳过这张图」）。 */
    private fun sha256OfFile(file: Path): String? {
        if (!Files.exists(file)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            hex(digest.digest())
        } catch (failure: IOException) {
            System.err.println("[agentd] 读媒体文件失败：$file（${failure.message}）")
            null
        }
    }

    // ================================================================== 事件（增量游标）

    /** 游标大于 [since] 的事件（最多 [limit] 条），同步读。 */
    fun eventsSince(since: Long, limit: Int = 200): List<StoredEvent> = lock.withLock {
        eventsSinceLocked(since, limit)
    }

    /**
     * 长轮询：有事件立即返回；否则最多等 [timeoutMs]（毫秒）。
     *
     * 实现是「事件条件变量 + 250ms 兜底轮询」：写事件时 `notifyAll` 唤醒等待者，但等待者**不持锁**
     * 阻塞，所以不会和写锁形成交叉死锁；250ms 的兜底上限保证「通知早于 wait」这种丢唤醒最多晚
     * 250ms，而不是等满整个超时。
     *
     * 注意：等待期间会占住一个 HTTP worker（`AgentdServer.WORKER_THREADS` 个固定线程），
     * 生产上长轮询/SSE 应该移出工作池 —— 这是本阶段的已知限制，写在 README 里。
     */
    fun awaitEvents(since: Long, timeoutMs: Long, limit: Int = 200): EventWaitResult {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000
        while (true) {
            val page = lock.withLock { eventsSinceLocked(since, limit) }
            if (page.isNotEmpty()) return EventWaitResult(page, page.last().cursor)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return EventWaitResult(emptyList(), since)
            val waitMs = minOf(remaining / 1_000_000, 250L).coerceAtLeast(1L)
            try {
                synchronized(eventMonitor) { eventMonitor.wait(waitMs) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return EventWaitResult(emptyList(), since)
            }
        }
    }

    // ================================================================== 内部：备份字段解析

    // 字段级校验（persona / session / message / memory / preset / tags）在流式导入里复用；
    // 0.12.1 起不再有「整份 JsonValue 备份」那条路径，所以这里只有逐条解析。

    /**
     * 备份里的一行人格。
     *
     * 两种形状都认：
     * - **服务端 v5**：`avatarUrl` 是 `/v1/media/<id>`，客户端字段在 `extraFields`；
     * - **客户端 v6**：`avatarUri` 是**裸文件名**（真正字节在 `media[]` 里），多出
     *   `traits/likes/behaviors/personaCardJson/mood/...` 等列。
     *
     * `avatarUri` 先原样留在 `extraFields` 里；[commitBackupStreamed] 在媒体表就绪后用
     * 「文件名 → 媒体 id」把它解析成 `avatarUrl`（那时候才知道媒体 id 是什么）。
     */
    private fun parseBackupPersona(obj: JsonValue.Obj, where: String, now: Long): Persona {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val name = obj.optString("name", where)?.trim().orEmpty()
        if (name.isEmpty()) throw BackupValidationException("$where 缺少 name")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        val isDefault = obj.optBool("isDefault", where) ?: false
        val avatarUrl = obj.optString("avatarUrl", where)?.takeIf { it.isNotBlank() }
            ?: MediaUrl.canonical(obj.optString("avatarUri", where)?.takeIf { MediaUrl.idOf(it) != null })
        return Persona(
            id = id,
            name = name,
            nameEn = obj.optString("nameEn", where) ?: "",
            description = obj.optString("description", where) ?: "",
            avatarUrl = avatarUrl,
            isDefault = isDefault,
            createdAt = createdAt,
            updatedAt = updatedAt,
            activityJson = obj.optString("activityJson", where)?.takeIf { it.isNotBlank() },
            // 0.14：拉黑状态随备份带走（客户端忽略这两个可加键）。
            blockedUntil = obj.optLong("blockedUntil", where)?.takeIf { it > 0 },
            blockReason = obj.optString("blockReason", where).orEmpty().take(MAX_BLOCK_REASON_CHARS),
            extraFields = obj.fields.filterKeys { it !in Persona.RESERVED_KEYS }
        )
    }

    private fun parseBackupSession(obj: JsonValue.Obj, where: String, now: Long): Session {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        return Session(
            id = id,
            personaId = personaId,
            title = obj.optString("title", where) ?: "",
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessageAt = obj.optLong("lastMessageAt", where),
            lastBlockIndex = obj.optInt("lastBlockIndex", where) ?: -1,
            // 兼容客户端备份的 `unreadCount`。
            unread = obj.optInt("unread", where) ?: obj.optInt("unreadCount", where) ?: 0,
            messageCount = obj.optLong("messageCount", where) ?: 0,
            // 客户端 v6 的会话列（summary / pinned / apiConfigId / overrides…）：原样留着，
            // 再导出时字段一个不少。
            extraFields = obj.fields.filterKeys { it !in Session.RESERVED_KEYS }
        )
    }

    private fun parseBackupMessage(obj: JsonValue.Obj, where: String, now: Long): Message {
        val id = obj.reqString("id", where)
        if (!MESSAGE_ID_PATTERN.matches(id)) throw BackupValidationException("$where 的 id 不合法")
        val sessionId = obj.reqString("sessionId", where)
        if (!isValidId(sessionId)) throw BackupValidationException("$where 的 sessionId 不合法")
        val role = obj.reqString("role", where)
        if (!MessageRole.isValid(role)) throw BackupValidationException("$where 的 role 不合法")
        val content = obj.reqString("content", where)
        val createdAt = obj.optLong("createdAt", where) ?: now
        val blockIndex = obj.optInt("blockIndex", where) ?: 0
        if (blockIndex < 0) throw BackupValidationException("$where 的 blockIndex 不能是负数")
        val attachments = when {
            obj.fields.containsKey("attachments") -> obj.stringList("attachments", where)
            // 客户端 v6：附件是 `attachmentsJson`（`[{"uri":"裸文件名","mime":..}]`）。
            // 这里先取出裸文件名，等媒体表就绪后在提交阶段解析成 `/v1/media/<id>`。
            else -> parseAttachmentsJson(obj.fields["attachmentsJson"], where)
        }
        return Message(
            id = id,
            sessionId = sessionId,
            blockIndex = blockIndex,
            role = role,
            content = content,
            createdAt = createdAt,
            readAt = obj.optLong("readAt", where),
            attachments = attachments,
            source = obj.optString("source", where) ?: MessageSource.IMPORT,
            clientMessageId = obj.optString("clientMessageId", where),
            replyTo = obj.optString("replyTo", where),
            promptTokens = obj.optInt("promptTokens", where),
            completionTokens = obj.optInt("completionTokens", where),
            toolTraceJson = obj.optString("toolTraceJson", where)?.takeIf { it.isNotBlank() },
            blocked = obj.optBool("blocked", where) ?: false
        )
    }

    /**
     * 客户端 `attachmentsJson` → 附件引用列表。
     *
     * 这一阶段只做「形状」：`uri` 先原样返回（可能是裸文件名，也可能是历史设备路径），
     * 提交阶段再由 [resolveAttachment] 用「文件名 → 媒体 id」换成服务端的 `/v1/media/<id>`。
     * 坏 JSON 不抛异常（一条脏附件不该让整份备份失败）。
     */
    private fun parseAttachmentsJson(value: JsonValue?, where: String): List<String> {
        val text = when (value) {
            null, JsonValue.Null -> return emptyList()
            is JsonValue.Str -> value.value.trim()
            is JsonValue.Arr -> StoreJson.stringify(value)
            else -> throw BackupValidationException("$where 的 attachmentsJson 必须是字符串")
        }
        if (text.isEmpty()) return emptyList()
        val parsed = runCatching { com.yap.app.core.json.Json.parse(text) }.getOrNull() ?: return emptyList()
        val items = (parsed as? JsonValue.Arr)?.items ?: return emptyList()
        val result = ArrayList<String>()
        for (item in items) {
            val uri = ((item as? JsonValue.Obj)?.fields?.get("uri") as? JsonValue.Str)?.value?.trim()
            if (!uri.isNullOrEmpty()) result += uri
        }
        return result
    }

    private fun parseBackupMemory(obj: JsonValue.Obj, where: String, now: Long): Memory {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        val content = obj.reqString("content", where)
        if (content.isBlank()) throw BackupValidationException("$where 的 content 不能为空")
        val importance = obj.optInt("importance", where) ?: 50
        if (importance !in 0..100) throw BackupValidationException("$where 的 importance 必须在 0–100")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        return Memory(
            id = id,
            personaId = personaId,
            content = content,
            importance = importance,
            tags = parseTags(obj.fields["tags"], where),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun parseBackupPreset(obj: JsonValue.Obj, where: String, now: Long): Preset {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val name = obj.optString("name", where)?.trim().orEmpty()
        if (name.isEmpty()) throw BackupValidationException("$where 缺少 name")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        return Preset(
            id = id,
            name = name,
            description = obj.optString("description", where) ?: "",
            prompt = obj.optString("prompt", where) ?: "",
            category = obj.optString("category", where) ?: "",
            avatarUri = obj.optString("avatarUri", where),
            avatarSeed = obj.optInt("avatarSeed", where) ?: 0,
            createdAt = createdAt,
            updatedAt = updatedAt,
            worldJson = obj.optString("worldJson", where)?.takeIf { it.isNotBlank() },
            speechStyleJson = obj.optString("speechStyleJson", where)?.takeIf { it.isNotBlank() },
            extraFields = obj.fields.filterKeys { it !in Preset.RESERVED_KEYS }
        )
    }

    /**
     * v6 的表情行（客户端 `BackupSticker`）。
     *
     * `fileName` / `file` 两个字段取非空的那个（Android 写 `fileName`，iOS 写 `file`）；
     * 文件本体在 `media[]` 里，导入后 `Sticker.id` 就是那条媒体的 id。
     * 这里**不校验文件在不在**：允许「只有元数据没有字节」的坏备份被降级成一条没有文件的
     * 表情行——那比让整份备份失败好（客户端也会跳过没有字节的行）。
     */
    private fun parseBackupSticker(obj: JsonValue.Obj, where: String): Sticker {
        val id = obj.reqString("id", where)
        if (!MediaUrl.isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val name = (obj.optString("fileName", where) ?: obj.optString("file", where))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "$id.gif"
        if (!MediaItem.FILE_NAME_PATTERN.matches(name)) {
            throw BackupValidationException("$where 的 fileName 不是裸文件名")
        }
        val mime = obj.optString("mime", where)?.trim()?.lowercase()?.ifEmpty { null }
            ?: MediaMime.GIF
        if (!MediaMime.isValid(mime)) throw BackupValidationException("$where 的 mime 不合法：$mime")
        val personaId = obj.optString("personaId", where)?.trim()?.takeIf { it.isNotEmpty() }
        if (personaId != null && !isValidId(personaId)) {
            throw BackupValidationException("$where 的 personaId 不合法")
        }
        return Sticker(
            id = id,
            ownerUserId = obj.optString("ownerUserId", where)?.trim()?.takeIf { it.isNotEmpty() },
            personaId = personaId,
            file = name,
            mime = mime,
            width = (obj.optInt("width", where) ?: 0).coerceAtLeast(0),
            height = (obj.optInt("height", where) ?: 0).coerceAtLeast(0),
            frames = (obj.optInt("frames", where) ?: 1).coerceAtLeast(1),
            bytes = (obj.optLong("bytes", where) ?: 0L).coerceAtLeast(0),
            source = Sticker.normalizeSource(obj.optString("source", where)),
            createdBy = Sticker.normalizeCreatedBy(obj.optString("createdBy", where)),
            createdAt = obj.optLong("createdAt", where) ?: 0L
        )
    }

    private fun parseBackupDiaryNote(obj: JsonValue.Obj, where: String, now: Long): DiaryNote {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        val content = obj.optString("content", where).orEmpty()
        if (content.length > DiaryNote.MAX_CONTENT_CHARS) {
            throw BackupValidationException("$where 的 content 超过 ${DiaryNote.MAX_CONTENT_CHARS} 字")
        }
        val createdAt = obj.optLong("createdAt", where) ?: now
        return DiaryNote(
            id = id,
            personaId = personaId,
            content = content,
            createdAt = createdAt,
            remindAt = obj.optLong("remindAt", where),
            remindedAt = obj.optLong("remindedAt", where),
            updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        )
    }

    /** v6 的 `agentIntents[]`；`status` 认不出时退成 `PENDING`（宁可多问一句，不要静默丢念头）。 */
    private fun parseBackupIntent(obj: JsonValue.Obj, where: String, now: Long): StoredIntent? {
        val id = obj.optString("id", where)?.trim().orEmpty()
        if (!isValidId(id)) return null
        val personaId = obj.optString("personaId", where)?.trim().orEmpty()
        if (!isValidId(personaId)) return null
        val createdAt = obj.optLong("createdAt", where) ?: now
        val status = obj.optString("status", where)?.trim()?.uppercase()
            ?.takeIf { it in setOf(StoredIntent.STATUS_PENDING, StoredIntent.STATUS_SENT, StoredIntent.STATUS_EXPIRED) }
            ?: StoredIntent.STATUS_PENDING
        return StoredIntent(
            id = id,
            personaId = personaId,
            sessionId = obj.optString("sessionId", where)?.takeIf { isValidId(it) },
            kind = obj.optString("kind", where)?.trim()?.ifEmpty { null } ?: StoredIntent.KIND_REACH_OUT,
            earliestAt = obj.optLong("earliestAt", where) ?: createdAt,
            latestAt = obj.optLong("latestAt", where) ?: 0L,
            note = obj.optString("note", where).orEmpty(),
            hint = obj.optString("hint", where).orEmpty(),
            status = status,
            createdAt = createdAt,
            sentAt = obj.optLong("sentAt", where)
        )
    }

    /** 0.14：备份里的心理活动（服务端扩展段）。坏 id / 人格不存在在提交阶段拒绝。 */
    private fun parseBackupMentalActivity(obj: JsonValue.Obj, where: String): MentalActivity {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        return MentalActivity(
            id = id,
            personaId = personaId,
            sessionId = obj.optString("sessionId", where).orEmpty(),
            at = obj.optLong("at", where) ?: 0L,
            thought = obj.optString("thought", where).orEmpty().take(MentalActivity.MAX_THOUGHT_CHARS),
            moodLabel = obj.optString("moodLabel", where).orEmpty(),
            moodValence = (obj.optLong("moodValence", where) ?: 0L).toInt(),
            moodArousal = (obj.optLong("moodArousal", where) ?: 2L).toInt(),
            moodDominance = (obj.optLong("moodDominance", where) ?: 3L).toInt(),
            moodIntensity = (obj.optLong("moodIntensity", where) ?: 2L).toInt()
        )
    }

    /** 0.14：备份里的会话记忆（服务端扩展段）。 */
    private fun parseBackupSessionMemory(obj: JsonValue.Obj, where: String): SessionMemory {
        val sessionId = obj.reqString("sessionId", where)
        if (!isValidId(sessionId)) throw BackupValidationException("$where 的 sessionId 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        return SessionMemory(
            sessionId = sessionId,
            personaId = personaId,
            shortSummary = obj.optString("shortSummary", where).orEmpty().take(SessionMemory.MAX_SHORT_CHARS),
            shortUpdatedAt = obj.optLong("shortUpdatedAt", where) ?: 0L,
            shortUserMessages = obj.optLong("shortUserMessages", where) ?: 0L,
            longSummary = obj.optString("longSummary", where).orEmpty().take(SessionMemory.MAX_LONG_CHARS),
            longUpdatedAt = obj.optLong("longUpdatedAt", where) ?: 0L,
            longUserMessages = obj.optLong("longUserMessages", where) ?: 0L,
            // 0.15 的可加键：旧备份没有就是默认值（消费点判定「要不要更新」会退回按条数触发）。
            longCheckedUserMessages = obj.optLong("longCheckedUserMessages", where) ?: 0L,
            topics = SessionMemory.parseTopics(obj.fields["topics"], where),
            topicUpdatedAt = obj.optLong("topicUpdatedAt", where) ?: 0L,
            topicUserMessages = obj.optLong("topicUserMessages", where) ?: 0L,
            topicCheckedUserMessages = obj.optLong("topicCheckedUserMessages", where) ?: 0L
        )
    }

    /** 0.15：备份里的一条主动消息失败记录（服务端扩展段）。 */
    private fun parseBackupProactiveFailure(obj: JsonValue.Obj, where: String): ProactiveFailure {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        val stage = obj.optString("stage", where).orEmpty().takeIf { it.isNotBlank() }
            ?: ProactiveFailure.Stage.GENERATION
        return ProactiveFailure(
            id = id,
            personaId = personaId,
            sessionId = obj.optString("sessionId", where)?.takeIf { it.isNotBlank() },
            at = obj.optLong("at", where) ?: 0L,
            stage = if (stage in ProactiveFailure.Stage.ALL) stage else ProactiveFailure.Stage.GENERATION,
            reason = obj.optString("reason", where).orEmpty().take(80),
            detail = obj.optString("detail", where).orEmpty().take(ProactiveFailure.MAX_DETAIL_CHARS),
            text = obj.optString("text", where).orEmpty().take(500)
        )
    }

    private fun parseTags(value: JsonValue?, where: String): List<String> = when (value) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.Arr -> value.items.map { item ->
            (item as? JsonValue.Str)?.value ?: throw BackupValidationException("$where 的 tags 里有非字符串元素")
        }
        is JsonValue.Str -> {
            val text = value.value.trim()
            if (text.isEmpty()) {
                emptyList()
            } else {
                val parsed = try {
                    com.yap.app.core.json.Json.parse(text)
                } catch (_: JsonParseException) {
                    // 也接受逗号分隔的旧写法；不把一条脏标签当成整份备份失败。
                    return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                (parsed as? JsonValue.Arr)?.items?.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw BackupValidationException("$where 的 tags 里有非字符串元素")
                } ?: listOf(text)
            }
        }
        else -> throw BackupValidationException("$where 的 tags 必须是数组或字符串")
    }

    private fun JsonValue.Obj.optBool(key: String, where: String): Boolean? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Bool)?.value
            ?: throw BackupValidationException("$where 的字段 $key 不是布尔")
    }

    private fun publicSettingsLocked(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap<String, JsonValue>().apply {
            for ((key, value) in settings.fields) {
                val canonical = canonicalSettingKey(key)
                if (canonical in SETTINGS_WHITELIST) put(canonical, value)
            }
        }
    )

    private fun allMessagesLocked(): List<Message> {
        val result = ArrayList<Message>()
        for (session in sessions.values) {
            for (block in blocksForLocked(session.id)) {
                result.addAll(readBlockLocked(session.id, block.index))
            }
        }
        return result.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    // ================================================================== 内部：媒体

    /** 引用统计：消息附件 / 人格头像 / 预设头像。`/v1/media/<id>` 与裸 id 都认。 */
    private fun mediaReferenceCountLocked(mediaId: String): Int {
        var references = 0
        for (message in allMessagesLocked()) {
            for (attachment in message.attachments) {
                if (MediaUrl.idOf(attachment) == mediaId) references += 1
            }
        }
        for (persona in personas) {
            if (MediaUrl.idOf(persona.avatarUrl) == mediaId) references += 1
        }
        for (preset in presets.values) {
            if (MediaUrl.idOf(preset.avatarUri) == mediaId) references += 1
        }
        return references
    }

    // ================================================================== 内部：加载与播种

    private fun hasExistingData(): Boolean =
        Files.exists(root.resolve(PERSONAS_FILE)) ||
            Files.exists(root.resolve(SESSIONS_FILE)) ||
            Files.exists(root.resolve(MEMORIES_FILE)) ||
            Files.exists(root.resolve(PRESETS_FILE)) ||
            Files.exists(root.resolve(PUSH_TOKENS_FILE)) ||
            Files.exists(root.resolve(MEDIA_METADATA_FILE)) ||
            Files.exists(root.resolve(STICKERS_FILE)) ||
            Files.exists(root.resolve(DIARY_NOTES_FILE)) ||
            Files.exists(root.resolve(MENTAL_ACTIVITIES_FILE)) ||
            Files.exists(root.resolve(SESSION_MEMORY_FILE)) ||
            Files.exists(root.resolve(PROACTIVE_FAILURES_FILE)) ||
            Files.exists(eventsFile) ||
            Files.newDirectoryStream(sessionsDir).use { it.iterator().hasNext() } ||
            Files.newDirectoryStream(mediaDir).use { it.iterator().hasNext() }

    private fun loadExistingData(metaJson: JsonValue) {
        val where = "${root.resolve(META_FILE)}"
        val parsed = StoreMeta.fromJson(metaJson as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象"), where)
        if (parsed.version != VERSION) {
            throw StoreCorruptionException("$where 的 version=${parsed.version} 不受支持（本版本只读 $VERSION）")
        }
        meta = parsed
        cursor = parsed.cursor

        val personasJson = readJsonFile(root.resolve(PERSONAS_FILE))
            ?: throw StoreCorruptionException("数据目录缺少 $PERSONAS_FILE（不能静默重建：那会丢掉人格）")
        val personasWhere = "${root.resolve(PERSONAS_FILE)}"
        val personaObj = personasJson as? JsonValue.Obj ?: throw StoreCorruptionException("$personasWhere 不是 JSON 对象")
        for ((index, item) in personaObj.optArr("personas", personasWhere).withIndex()) {
            val obj = item as? JsonValue.Obj
                ?: throw StoreCorruptionException("$personasWhere 第 ${index + 1} 个人格不是对象")
            personas += Persona.fromJson(obj, "$personasWhere 第 ${index + 1} 个人格")
        }

        val sessionsJson = readJsonFile(root.resolve(SESSIONS_FILE))
            ?: throw StoreCorruptionException("数据目录缺少 $SESSIONS_FILE（不能静默重建：那会丢掉会话）")
        val sessionsWhere = "${root.resolve(SESSIONS_FILE)}"
        val sessionObj = sessionsJson as? JsonValue.Obj ?: throw StoreCorruptionException("$sessionsWhere 不是 JSON 对象")
        for ((index, item) in sessionObj.optArr("sessions", sessionsWhere).withIndex()) {
            val obj = item as? JsonValue.Obj
                ?: throw StoreCorruptionException("$sessionsWhere 第 ${index + 1} 个会话不是对象")
            val session = Session.fromJson(obj, "$sessionsWhere 第 ${index + 1} 个会话")
            sessions[session.id] = session
        }

        loadOptionalObjectList(root.resolve(MOODS_FILE), "moods") { obj, where ->
            val mood = Mood.fromJson(obj, where)
            moods[mood.personaId] = mood
        }
        loadOptionalObjectList(root.resolve(PRESENCE_FILE), "presence") { obj, where ->
            val value = Presence.fromJson(obj, where)
            presence[value.personaId] = value
        }
        loadOptionalObjectList(root.resolve(API_CONFIGS_FILE), "configs") { obj, where ->
            val config = ApiConfig.fromJson(obj, where)
            apiConfigs[config.id] = config
        }
        readJsonFile(root.resolve(USAGE_FILE))?.let { json ->
            val where = "${root.resolve(USAGE_FILE)}"
            val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
            for ((personaId, total) in obj.optObj("usage", where)?.fields.orEmpty()) {
                usage[personaId] = UsageTotal.fromJson(total as? JsonValue.Obj ?: throw StoreCorruptionException("$where 的 usage.$personaId 不是对象"), "$where 的 usage.$personaId")
            }
        }
        readJsonFile(root.resolve(SETTINGS_FILE))?.let { json ->
            val where = "${root.resolve(SETTINGS_FILE)}"
            val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
            settings = obj.optObj("settings", where) ?: JsonValue.Obj()
        }
        loadOptionalObjectList(root.resolve(PROACTIVE_STATE_FILE), "states") { obj, where ->
            val state = ProactiveState.fromJson(obj, where)
            proactiveStates[state.personaId] = state
        }
        loadOptionalObjectList(root.resolve(MEMORIES_FILE), "memories") { obj, where ->
            val memory = Memory.fromJson(obj, where)
            memories[memory.id] = memory
        }
        loadOptionalObjectList(root.resolve(PRESETS_FILE), "presets") { obj, where ->
            val preset = Preset.fromJson(obj, where)
            presets[preset.id] = preset
        }
        loadOptionalObjectList(root.resolve(PENDING_PATCHES_FILE), "patches") { obj, where ->
            val patch = PendingPatch.fromJson(obj, where)
            pendingPatches[patch.id] = patch
        }
        loadOptionalObjectList(root.resolve(MEDIA_METADATA_FILE), "media") { obj, where ->
            val item = MediaItem.fromJson(obj, where)
            media[item.id] = item
        }
        loadOptionalObjectList(root.resolve(PUSH_TOKENS_FILE), "tokens") { obj, where ->
            val token = PushRegistration.fromJson(obj, where)
            pushTokens[pushTokenKey(token.platform, token.token)] = token
        }
        loadOptionalObjectList(root.resolve(STICKERS_FILE), "stickers") { obj, where ->
            val sticker = Sticker.fromJson(obj, where)
            stickers[sticker.id] = sticker
        }
        loadOptionalObjectList(root.resolve(DIARY_NOTES_FILE), "notes") { obj, where ->
            val note = DiaryNote.fromJson(obj, where)
            diaryNotes[note.id] = note
        }
        loadOptionalObjectList(root.resolve(INTENTS_FILE), "intents") { obj, where ->
            val intent = StoredIntent.fromJson(obj, where)
            intents[intent.id] = intent
        }
        loadOptionalObjectList(root.resolve(AUTO_STATE_FILE), "states") { obj, where ->
            val state = AutoState.fromJson(obj, where)
            autoStates[state.personaId] = state
        }
        loadOptionalObjectList(root.resolve(BLOCK_PROPOSALS_FILE), "proposals") { obj, where ->
            val proposal = BlockProposal.fromJson(obj, where)
            blockProposals[proposal.personaId] = proposal
        }
        loadOptionalObjectList(root.resolve(MENTAL_ACTIVITIES_FILE), "activities") { obj, where ->
            val activity = MentalActivity.fromJson(obj, where)
            mentalActivities[activity.id] = activity
        }
        loadOptionalObjectList(root.resolve(SESSION_MEMORY_FILE), "memories") { obj, where ->
            val memory = SessionMemory.fromJson(obj, where)
            sessionMemories[memory.sessionId] = memory
        }
        loadOptionalObjectList(root.resolve(PROACTIVE_FAILURES_FILE), "failures") { obj, where ->
            val failure = ProactiveFailure.fromJson(obj, where)
            proactiveFailures[failure.id] = failure
        }
        loadOptionalObjectList(root.resolve(MESSAGE_QUEUE_FILE), "deliveries") { obj, where ->
            val entry = PendingDelivery.fromJson(obj, where)
            deliveries[entry.id] = entry
        }
        readJsonFile(root.resolve(CLIENT_EXTRAS_FILE))?.let { json ->
            val where = "${root.resolve(CLIENT_EXTRAS_FILE)}"
            val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
            val sections = obj.fields["sections"] as? JsonValue.Obj ?: return@let
            for ((key, value) in sections.fields) {
                if (key.isBlank()) continue
                clientExtras[key] = value
            }
        }
        if (Files.exists(root.resolve(SECRETS_FILE))) {
            // 0600 是承诺，不是建议：打开时再收紧一次（有人复制/解压可能带上更宽的权限）。
            setPosixPermissions(root.resolve(SECRETS_FILE), "rw-------")
        }

        loadEventsLocked()
        lastTimestamp = computeLastTimestampLocked()
        if (meta.cursor < cursor) {
            persistMetaLocked()
        }
    }

    private fun loadOptionalObjectList(
        file: Path,
        arrayKey: String,
        consume: (JsonValue.Obj, String) -> Unit
    ) {
        val json = readJsonFile(file) ?: return
        val where = "$file"
        val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
        for ((index, item) in obj.optArr(arrayKey, where).withIndex()) {
            val element = item as? JsonValue.Obj
                ?: throw StoreCorruptionException("$where 的 $arrayKey 第 ${index + 1} 项不是对象")
            consume(element, "$where 的 $arrayKey 第 ${index + 1} 项")
        }
    }

    private fun loadEventsLocked() {
        if (!Files.exists(eventsFile)) return
        var maxCursor = 0L
        var lineNumber = 0
        Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (line.isBlank()) continue
                val event = try {
                    val parsed = StoreJson.parse(line, "$eventsFile 第 $lineNumber 行")
                    StoredEvent.fromJson(
                        parsed as? JsonValue.Obj
                            ?: throw StoreCorruptionException("$eventsFile 第 $lineNumber 行不是 JSON 对象"),
                        "$eventsFile 第 $lineNumber 行"
                    )
                } catch (failure: StoreCorruptionException) {
                    // 只容忍末尾半行（进程被杀时 append 到一半）；中间坏行说明文件被改过，照样跳过并告警。
                    System.err.println("[agentd] 跳过事件日志坏行：$eventsFile 第 $lineNumber 行（${failure.message}）")
                    continue
                }
                maxCursor = maxOf(maxCursor, event.cursor)
                if (events.size >= MAX_EVENTS_IN_MEMORY) events.removeFirst()
                events.addLast(event)
            }
        }
        cursor = maxOf(cursor, maxCursor)
    }

    private fun computeLastTimestampLocked(): Long {
        var latest = 0L
        for (persona in personas) latest = maxOf(latest, persona.updatedAt)
        for (session in sessions.values) {
            latest = maxOf(latest, session.updatedAt, session.lastMessageAt ?: 0L)
        }
        for (mood in moods.values) latest = maxOf(latest, mood.updatedAt)
        for (value in presence.values) latest = maxOf(latest, value.updatedAt)
        for (event in events) latest = maxOf(latest, event.at)
        return latest
    }

    private fun seedFreshData() {
        val now = nextTimestampLocked()
        personas += Persona(
            id = SEED_PERSONA_1_ID,
            name = "林深",
            nameEn = "Elias",
            description = "一个安静、温柔的倾听者。适合在夜里说说话。",
            avatarUrl = null,
            isDefault = true,
            createdAt = now,
            updatedAt = now
        )
        personas += Persona(
            id = SEED_PERSONA_2_ID,
            name = "阿岚",
            nameEn = "Finn",
            description = "直率、鲜活的行动派。会把沉闷的日子搅出一点风。",
            avatarUrl = null,
            isDefault = false,
            createdAt = now,
            updatedAt = now
        )
        personas += Persona(
            id = SEED_PERSONA_3_ID,
            name = "墨",
            nameEn = "Mo",
            description = "冷静、清晰的思考伙伴。擅长把复杂的问题拆开。",
            avatarUrl = null,
            isDefault = false,
            createdAt = now,
            updatedAt = now
        )
        sessions[SEED_SESSION_ID] = Session(
            id = SEED_SESSION_ID,
            personaId = SEED_PERSONA_1_ID,
            title = "和林深的对话",
            createdAt = now,
            updatedAt = now,
            lastMessageAt = null,
            lastBlockIndex = -1,
            unread = 0,
            messageCount = 0
        )
        moods[SEED_PERSONA_1_ID] = Mood(SEED_PERSONA_1_ID, "平静", 2, now)
        presence[SEED_PERSONA_1_ID] = Presence(SEED_PERSONA_1_ID, "IDLE", "在家，安静地待着", now)
        meta = StoreMeta(version = VERSION, cursor = cursor, seeded = true)
        persistMetaLocked()
        persistPersonasLocked()
        persistSessionsLocked()
        persistMoodsLocked()
        persistPresenceLocked()
    }

    private fun writeEmptyBaseline() {
        meta = StoreMeta(version = VERSION, cursor = 0, seeded = false)
        persistMetaLocked()
        persistPersonasLocked()
        persistSessionsLocked()
    }

    // ================================================================== 内部：块

    private fun blocksForLocked(sessionId: String): MutableList<BlockMeta> = blockCache.getOrPut(sessionId) {
        loadBlockIndexLocked(sessionId).toMutableList()
    }

    private fun loadBlockIndexLocked(sessionId: String): List<BlockMeta> {
        val file = blockIndexFile(sessionId)
        val json = readJsonFile(file)
        if (json == null) {
            // 块表丢了（或从没写过）：按块文件重建一次，不静默丢消息。
            // 空会话不写文件：读路径不产生写操作（下次仍然是「没有块表 + 没有块文件」）。
            val rebuilt = rebuildBlockIndexLocked(sessionId)
            if (rebuilt.isNotEmpty()) persistBlockIndexLocked(sessionId, rebuilt)
            return rebuilt
        }
        val where = "$file"
        val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
        return obj.optArr("blocks", where).mapIndexed { index, item ->
            BlockMeta.fromJson(
                item as? JsonValue.Obj ?: throw StoreCorruptionException("$where 的 blocks 第 ${index + 1} 项不是对象"),
                "$where 的 blocks 第 ${index + 1} 项"
            )
        }.sortedBy { it.index }
    }

    /** 扫描 `blocks/` 目录里的 `*.jsonl` 重建块表（只有块表丢失/被要求时才做，O(历史)）。 */
    fun rebuildBlockIndex(sessionId: String): List<BlockMeta> = lock.withLock {
        requireSessionLocked(sessionId)
        val rebuilt = rebuildBlockIndexLocked(sessionId)
        blockCache[sessionId] = rebuilt.toMutableList()
        messageIndexCache.remove(sessionId)
        persistBlockIndexLocked(sessionId, rebuilt)
        rebuilt
    }

    private fun rebuildBlockIndexLocked(sessionId: String): List<BlockMeta> {
        invalidateUserMessageCountsLocked(sessionId)
        val dir = blockDir(sessionId)
        if (!Files.exists(dir)) return emptyList()
        val indexes = Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { path ->
                val name = path.fileName.toString()
                if (!name.endsWith(".jsonl")) return@mapNotNull null
                name.removeSuffix(".jsonl").toIntOrNull()
            }.sorted()
        }
        return indexes.mapNotNull { index ->
            val messages = readBlockLocked(sessionId, index)
            if (messages.isEmpty()) return@mapNotNull null
            BlockMeta(
                index = index,
                firstMessageId = messages.first().id,
                lastMessageId = messages.last().id,
                firstAt = messages.first().createdAt,
                lastAt = messages.last().createdAt,
                count = messages.size
            )
        }
    }

    private fun readBlockLocked(sessionId: String, blockIndex: Int): List<Message> {
        val file = blockFile(sessionId, blockIndex)
        if (!Files.exists(file)) return emptyList()
        val messages = ArrayList<Message>(BLOCK_SIZE)
        var lineNumber = 0
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (line.isBlank()) continue
                val where = "$file 第 $lineNumber 行"
                try {
                    val parsed = StoreJson.parse(line, where) as? JsonValue.Obj
                        ?: throw StoreCorruptionException("$where 不是 JSON 对象")
                    messages += Message.fromJson(parsed, where)
                } catch (failure: StoreCorruptionException) {
                    // 典型的「末尾半行」：append 到一半进程被杀。跳过并告警，不让整块读不出来。
                    System.err.println("[agentd] 跳过消息坏行：$where（${failure.message}）")
                }
            }
        }
        return messages
    }

    private fun rewriteBlockLocked(sessionId: String, blockIndex: Int, messages: List<Message>) {
        val text = buildString {
            for (message in messages) append(StoreJson.stringify(message.toJson())).append('\n')
        }
        writeTextLocked(blockFile(sessionId, blockIndex), text)
    }

    private fun persistBlockIndexLocked(sessionId: String, blocks: List<BlockMeta>) {
        writeJsonLocked(
            blockIndexFile(sessionId),
            StoreJson.obj(
                "sessionId" to StoreJson.str(sessionId),
                "blocks" to StoreJson.arr(blocks.map { it.toJson() })
            )
        )
    }

    // ================================================================== 内部：消息索引（幂等查找）

    /**
     * 每个会话的 `clientMessageId → messageId` 与 `userMessageId → assistantMessageId` 索引。
     * 第一次用到时扫一遍块建立（O(历史)），之后随追加/删除增量维护。
     */
    private class SessionMessageIndex(
        val clientMessageIds: MutableMap<String, String> = HashMap(),
        val replies: MutableMap<String, String> = HashMap()
    )

    private fun messageIndexLocked(sessionId: String): SessionMessageIndex = messageIndexCache.getOrPut(sessionId) {
        val index = SessionMessageIndex()
        for (block in blocksForLocked(sessionId)) {
            for (message in readBlockLocked(sessionId, block.index)) {
                message.clientMessageId?.let { index.clientMessageIds[it] = message.id }
                message.replyTo?.let { index.replies[it] = message.id }
            }
        }
        index
    }

    private fun indexForAppendLocked(sessionId: String, message: Message) {
        val index = messageIndexCache[sessionId] ?: return
        message.clientMessageId?.let { index.clientMessageIds[it] = message.id }
        message.replyTo?.let { index.replies[it] = message.id }
    }

    private fun indexForDeleteLocked(sessionId: String, message: Message) {
        val index = messageIndexCache[sessionId] ?: return
        message.clientMessageId?.let { index.clientMessageIds.remove(it) }
        message.replyTo?.let { index.replies.remove(it) }
    }

    private fun findMessageLocked(sessionId: String, messageId: String): Message? {
        for (block in blocksForLocked(sessionId).asReversed()) {
            readBlockLocked(sessionId, block.index).firstOrNull { it.id == messageId }?.let { return it }
        }
        return null
    }

    // ================================================================== 内部：事件写入

    private fun eventsSinceLocked(since: Long, limit: Int): List<StoredEvent> {
        if (limit <= 0) return emptyList()
        val oldestInMemory = events.firstOrNull()?.cursor
        // 游标比内存窗口还老：说明有事件被裁剪了，回文件里找（内存窗口只是加速）。
        if (oldestInMemory != null && since + 1 < oldestInMemory) {
            return readEventsFromFileLocked(since, limit)
        }
        if (oldestInMemory == null && since < cursor) {
            return readEventsFromFileLocked(since, limit)
        }
        return events.filter { it.cursor > since }.take(limit)
    }

    private fun readEventsFromFileLocked(since: Long, limit: Int): List<StoredEvent> {
        if (!Files.exists(eventsFile)) return emptyList()
        val result = ArrayList<StoredEvent>(minOf(limit, 64))
        Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8).use { reader ->
            while (result.size < limit) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parsed = try {
                    StoreJson.parse(line, "$eventsFile（增量回读）") as? JsonValue.Obj
                        ?: continue
                } catch (_: StoreCorruptionException) {
                    continue
                }
                val event = try {
                    StoredEvent.fromJson(parsed, "$eventsFile（增量回读）")
                } catch (_: StoreCorruptionException) {
                    continue
                }
                if (event.cursor > since) result += event
            }
        }
        return result
    }

    private fun appendEventLocked(
        type: String,
        at: Long,
        sessionId: String?,
        personaId: String?,
        payload: LinkedHashMap<String, JsonValue>
    ): StoredEvent {
        val nextCursor = cursor + 1
        val event = StoredEvent(nextCursor, type, at, sessionId, personaId, JsonValue.Obj(payload))
        appendLineLocked(eventsFile, StoreJson.stringify(event.toJson()))
        cursor = nextCursor
        if (events.size >= MAX_EVENTS_IN_MEMORY) events.removeFirst()
        events.addLast(event)
        meta = meta.copy(cursor = cursor)
        persistMetaLocked()
        synchronized(eventMonitor) { eventMonitor.notifyAll() }
        return event
    }

    // ================================================================== 内部：持久化

    private fun persistMetaLocked() = writeJsonLocked(root.resolve(META_FILE), meta.toJson())

    private fun persistPersonasLocked() = writeJsonLocked(
        root.resolve(PERSONAS_FILE),
        StoreJson.obj("personas" to StoreJson.arr(personas.map { it.toJson() }))
    )

    private fun persistSessionsLocked() = writeJsonLocked(
        root.resolve(SESSIONS_FILE),
        StoreJson.obj("sessions" to StoreJson.arr(sessions.values.map { it.toJson() }))
    )

    private fun persistMoodsLocked() = writeJsonLocked(
        root.resolve(MOODS_FILE),
        StoreJson.obj("moods" to StoreJson.arr(moods.values.map { it.toJson() }))
    )

    private fun persistPresenceLocked() = writeJsonLocked(
        root.resolve(PRESENCE_FILE),
        StoreJson.obj("presence" to StoreJson.arr(presence.values.map { it.toJson() }))
    )

    private fun persistUsageLocked() = writeJsonLocked(
        root.resolve(USAGE_FILE),
        StoreJson.obj(
            "usage" to StoreJson.obj(
                LinkedHashMap<String, JsonValue>().apply {
                    for ((personaId, total) in usage) put(personaId, total.toJson())
                }
            )
        )
    )

    private fun persistApiConfigsLocked() = writeJsonLocked(
        root.resolve(API_CONFIGS_FILE),
        StoreJson.obj("configs" to StoreJson.arr(apiConfigs.values.map { it.toJson() }))
    )

    private fun persistProactiveStateLocked() = writeJsonLocked(
        root.resolve(PROACTIVE_STATE_FILE),
        StoreJson.obj("states" to StoreJson.arr(proactiveStates.values.map { it.toJson() }))
    )

    private fun persistMemoriesLocked() = writeJsonLocked(
        root.resolve(MEMORIES_FILE),
        StoreJson.obj("memories" to StoreJson.arr(memories.values.map { it.toJson() }))
    )

    private fun persistPresetsLocked() = writeJsonLocked(
        root.resolve(PRESETS_FILE),
        StoreJson.obj("presets" to StoreJson.arr(presets.values.map { it.toJson() }))
    )

    private fun persistPushTokensLocked() = writeJsonLocked(
        root.resolve(PUSH_TOKENS_FILE),
        StoreJson.obj("tokens" to StoreJson.arr(pushTokens.values.map { it.toJson() }))
    )

    private fun persistPendingPatchesLocked() = writeJsonLocked(
        root.resolve(PENDING_PATCHES_FILE),
        StoreJson.obj(
            "patches" to StoreJson.arr(
                pendingPatches.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.toJson() }
            )
        )
    )

    private fun persistStickersLocked() = writeJsonLocked(
        root.resolve(STICKERS_FILE),
        StoreJson.obj(
            "stickers" to StoreJson.arr(
                stickers.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.toJson() }
            )
        )
    )

    private fun persistDiaryNotesLocked() = writeJsonLocked(
        root.resolve(DIARY_NOTES_FILE),
        StoreJson.obj(
            "notes" to StoreJson.arr(
                diaryNotes.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.toJson() }
            )
        )
    )

    private fun persistIntentsLocked() = writeJsonLocked(
        root.resolve(INTENTS_FILE),
        StoreJson.obj(
            "intents" to StoreJson.arr(
                intents.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.toJson() }
            )
        )
    )

    private fun persistAutoStateLocked() = writeJsonLocked(
        root.resolve(AUTO_STATE_FILE),
        StoreJson.obj(
            "states" to StoreJson.arr(
                autoStates.values.sortedBy { it.personaId }.map { it.toJson() }
            )
        )
    )

    private fun persistBlockProposalsLocked() = writeJsonLocked(
        root.resolve(BLOCK_PROPOSALS_FILE),
        StoreJson.obj(
            "proposals" to StoreJson.arr(
                blockProposals.values.sortedBy { it.personaId }.map { it.toJson() }
            )
        )
    )

    private fun persistMentalActivitiesLocked() = writeJsonLocked(
        root.resolve(MENTAL_ACTIVITIES_FILE),
        StoreJson.obj(
            "activities" to StoreJson.arr(
                mentalActivities.values.sortedWith(compareBy({ it.at }, { it.id })).map { it.toJson() }
            )
        )
    )

    private fun persistSessionMemoryLocked() = writeJsonLocked(
        root.resolve(SESSION_MEMORY_FILE),
        StoreJson.obj(
            "memories" to StoreJson.arr(
                sessionMemories.values.sortedBy { it.sessionId }.map { it.toJson() }
            )
        )
    )

    private fun persistProactiveFailuresLocked() = writeJsonLocked(
        root.resolve(PROACTIVE_FAILURES_FILE),
        StoreJson.obj(
            "failures" to StoreJson.arr(
                proactiveFailures.values
                    .sortedWith(compareBy({ it.at }, { it.id }))
                    .map { it.toJson() }
            )
        )
    )

    private fun persistDeliveriesLocked() = writeJsonLocked(
        root.resolve(MESSAGE_QUEUE_FILE),
        StoreJson.obj(
            "deliveries" to StoreJson.arr(
                deliveries.values.sortedWith(compareBy({ it.visibleAt }, { it.enqueuedAt })).map { it.toJson() }
            )
        )
    )

    private fun persistClientExtrasLocked() = writeJsonLocked(
        root.resolve(CLIENT_EXTRAS_FILE),
        StoreJson.obj(
            "sections" to JsonValue.Obj(LinkedHashMap(clientExtras))
        )
    )

    private fun persistMediaLocked() = writeJsonLocked(
        root.resolve(MEDIA_METADATA_FILE),
        StoreJson.obj(
            "media" to StoreJson.arr(
                media.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.toJson() }
            )
        )
    )

    private fun readSecretsLocked(): Map<String, String> {
        val file = root.resolve(SECRETS_FILE)
        val json = readJsonFile(file) ?: return emptyMap()
        val where = "$file"
        val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
        val keys = obj.optObj("keys", where) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((configId, value) in keys.fields) {
            val secret = (value as? JsonValue.Str)?.value
                ?: throw StoreCorruptionException("$where 的 keys.$configId 不是字符串")
            result[configId] = secret
        }
        return result
    }

    private fun persistSecretsLocked(secrets: Map<String, String>) {
        val file = root.resolve(SECRETS_FILE)
        val keys = JsonValue.Obj()
        for ((configId, secret) in secrets) keys.fields[configId] = JsonValue.Str(secret)
        writeJsonLocked(file, StoreJson.obj("keys" to keys), posixMode = "rw-------")
    }

    /** 原子写一份 JSON：`*.tmp` → `force` → `ATOMIC_MOVE`。 */
    private fun writeJsonLocked(file: Path, value: JsonValue, posixMode: String? = null) {
        writeTextLocked(file, StoreJson.stringify(value), posixMode)
    }

    private fun writeTextLocked(file: Path, text: String, posixMode: String? = null) {
        writeBytesLocked(file, text.toByteArray(StandardCharsets.UTF_8), posixMode)
    }

    /**
     * 原子写任意字节：`*.tmp` → `ATOMIC_MOVE`。JSON 与媒体文件共用同一条路径。
     *
     * 不调 `force(true)`：原子性由 rename 保证；force 只影响「断电后能否保住最后几次写」，
     * 而每次写都 fsync 会让「一条消息写 4 个文件」变成 4 次磁盘同步（并发追加测试会慢一个数量级）。
     * 取舍写在 README 的「风险」里：进程崩溃安全，机器掉电可能丢最后几条（媒体可能是最后一张图）。
     */
    private fun writeBytesLocked(file: Path, bytes: ByteArray, posixMode: String? = null) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        if (posixMode != null) setPosixPermissions(temp, posixMode)
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            // 某些文件系统（网络盘）不支持原子 rename：退化成普通替换，至少不会留下半截文件。
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        if (posixMode != null) setPosixPermissions(file, posixMode)
    }

    private fun appendLineLocked(file: Path, line: String) {
        Files.createDirectories(file.parent)
        Files.write(
            file,
            (line + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
    }

    private fun readJsonFile(file: Path): JsonValue? {
        if (!Files.exists(file)) return null
        val text = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (failure: IOException) {
            throw StoreCorruptionException("读不了 $file：${failure.message}")
        }
        if (text.isBlank()) return null
        return StoreJson.parse(text, "$file")
    }

    private fun setPosixPermissions(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (_: UnsupportedOperationException) {
            // 非 POSIX 文件系统（Windows）：没有 0600 这个概念，忽略。
        } catch (_: IOException) {
            System.err.println("[agentd] 设置文件权限失败：$path（$mode）")
        }
    }

    // ================================================================== 内部：路径与 id

    private fun pushTokenKey(platform: String, token: String): String = "$platform|$token"

    private fun sessionDir(sessionId: String): Path {
        if (!isValidId(sessionId)) throw IllegalArgumentException("非法的会话 id")
        return sessionsDir.resolve(sessionId)
    }

    private fun blockDir(sessionId: String): Path = sessionDir(sessionId).resolve(BLOCKS_DIR)

    private fun blockFile(sessionId: String, blockIndex: Int): Path =
        blockDir(sessionId).resolve("$blockIndex.jsonl")

    private fun blockIndexFile(sessionId: String): Path =
        sessionDir(sessionId).resolve(BLOCK_INDEX_FILE)

    private fun requireSessionLocked(sessionId: String): Session {
        if (!isValidId(sessionId)) throw IllegalArgumentException("非法的会话 id")
        return sessions[sessionId] ?: throw StoreSessionNotFoundException(sessionId)
    }

    private fun requirePersonaLocked(personaId: String) {
        if (personas.none { it.id == personaId }) throw IllegalArgumentException("人格不存在：$personaId")
    }

    /**
     * 读路径的跨日归零：日期不是 [today] 时计数当 0，其余字段（尤其 `lastProactiveAt`）保留。
     * 不写盘——只读不该产生副作用。
     */
    private fun effectiveProactiveStateLocked(personaId: String, today: String): ProactiveState {
        val stored = proactiveStates[personaId]
        if (stored == null) return ProactiveState(personaId = personaId, proactiveCountDate = today)
        return if (stored.proactiveCountDate == today) {
            stored
        } else {
            stored.copy(proactiveCountToday = 0, proactiveCountDate = today)
        }
    }

    /** 写路径：跨日归零后 +1，并原子落盘。调用方必须已经持有 [lock]。 */
    private fun recordProactiveFiredLocked(personaId: String, at: Long, today: String): ProactiveState {
        val previous = effectiveProactiveStateLocked(personaId, today)
        val updated = previous.copy(
            lastProactiveAt = at,
            proactiveCountToday = previous.proactiveCountToday + 1,
            proactiveCountDate = today
        )
        proactiveStates[personaId] = updated
        persistProactiveStateLocked()
        return updated
    }

    private fun defaultPersonaIdLocked(): String =
        personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id ?: ""

    private fun nextTimestampLocked(): Long {
        lastTimestamp = maxOf(clock(), lastTimestamp + 1)
        return lastTimestamp
    }

    /** 媒体文件路径：`media/<id>.<ext>`；扩展名只由 mime 决定（id 已按正则校验过）。 */
    private fun mediaFile(item: MediaItem): Path {
        val extension = MediaMime.extension(item.mime)
            ?: throw StoreCorruptionException("媒体 ${item.id} 的 mime 不受支持：${item.mime}")
        return mediaDir.resolve("${item.id}.$extension")
    }

    /** 原始字节的 SHA-256（小写十六进制）。用于去重、备份校验与下载 ETag。 */
    private fun sha256Hex(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 摘要 → 小写十六进制（备份校验、媒体去重、下载 ETag 都用同一份格式）。 */
    private fun hex(digest: ByteArray): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            builder.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return builder.toString()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    /**
     * 0.16：预分配一个消息 id。
     *
     * `send_message` 必须在**落库之前**把 messageId 回给模型（「已发送，messageId=…」），
     * 所以由存储统一发 id，随后 `appendMessage(..., id = …)` 用同一个。格式与自动生成的完全一致，
     * 客户端/备份/分页都看不出区别。
     */
    fun newMessageId(): String = newId()

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

/** 会话不存在（路由层翻成 404，不是 500）。 */
class StoreSessionNotFoundException(val sessionId: String) : NoSuchElementException("没有这个会话：$sessionId")
