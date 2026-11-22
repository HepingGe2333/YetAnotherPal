package com.yap.agentd.delivery

import com.yap.agentd.SseEvent
import com.yap.agentd.SseSink
import com.yap.agentd.store.EventType
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.PendingDelivery
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 服务端消息投递队列与 typing 指示器（`spec/experience` §8）。
 *
 * 规则（与客户端契约一致）：
 * 1. **延迟由服务端在入队时决定**：每条要发出的 assistant 消息 `visibleAt = now + TypingDelay`，
 *    客户端不再自己加延迟，收到 `message.created` 一律直接上屏；
 * 2. **前台在线**：入队即发 `typing.started`（带 personaId/sessionId）→ 到 `visibleAt` 发
 *    `message.created` → 全部发完发 `typing.stopped`（每轮各一次）；
 * 3. **离线**：消息直接落库（`visibleAt = createdAt`），不发 typing；连上后同步直接显示；
 * 4. **队列持久化**：待投递条目写 `message_queue.json`，重启不丢；按会话 FIFO，同一会话同时
 *    只有一条正在「输入」（[sessionLocks] 串行化同一会话的投递）；
 * 5. `GET /v1/sessions/{id}/messages` 直接返回、**永不延迟**（历史/同步路径不经过这里）。
 *
 * 前台路径（[present]）由 `ChatService` 同步调用：它用注入的 [Sleeper] 等到 `visibleAt`，
 * 把 `message.created` 同时写进 `events.jsonl`（`/v1/events` 长轮询能收到）和当次 SSE 流。
 * 后台线程（[start]）只做**崩溃恢复**：前台没来得及投递的条目（进程被 kill）在重启后补发。
 */
class MessageDelivery(
    private val store: FileStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    val presence: ClientPresence = ClientPresence(clock),
    /** 关掉时完全退回「立即上屏」（测试/降级用；生产恒为 true）。 */
    private val delayEnabled: Boolean = true,
    private val sleeper: Sleeper = Sleeper.REAL,
    /** 恢复线程的轮询间隔（毫秒）。 */
    private val pollIntervalMs: Long = 250L,
    /** 恢复线程只处理「已经到点还没投递」的条目；前台路径会先 claim，避免重复。 */
    private val recoveryGraceMs: Long = 0L,
    /**
     * 「打字延迟」的随机源：返回 0..1，生产用真随机，测试传固定值（0.5 = 不抖动）。
     *
     * 0.15 起延迟公式是「均值 0.6–0.9 秒 × (1 ± 35%)」，没有随机源就无法做可复现的时序测试。
     */
    private val random: () -> Double = { kotlin.random.Random.nextDouble() }
) {

    /** 同一会话的投递串行化（`ReentrantLock` 可重入；同一时间只有一条在「输入」）。 */
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** 前台正在处理的条目：恢复线程跳过它们，避免同一批事件发两次。 */
    private val claimed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 延迟是否启用（`spec/experience` §8 的服务端权威模式恒为 true）。 */
    fun isDelayEnabled(): Boolean = delayEnabled

    /** 现在要不要延迟：启用 + 客户端在线。 */
    fun shouldDelay(now: Long = clock.millis()): Boolean = delayEnabled && presence.isOnline(now)

    /**
     * 为这一轮要发的消息计算 `visibleAt`（累计：第二条从第一条的 visibleAt 之后再延迟）。
     *
     * @return `null` = 不延迟（离线/关闭）——调用方按「立即落库 + 立即发事件」处理。
     */
    fun visibleAts(personaId: String, texts: List<String>, now: Long = clock.millis()): List<Long>? {
        if (texts.isEmpty()) return emptyList()
        if (!shouldDelay(now)) return null
        val mood = store.mood(personaId)
        var cursor = now
        return texts.map { text ->
            cursor += TypingDelay.millis(text, mood, random)
            cursor
        }
    }

    /**
     * 前台投递：入队 → `typing.started` → 逐条等到 `visibleAt` 发 `message.created` → `typing.stopped`。
     *
     * 调用方（`ChatService`）已经在库里落了消息（`visibleAt` 已写进行，事件被抑制）；这里只负责
     * 「什么时候让人看见」。写进 `events.jsonl` 与 [sink] 的是**同一份** `message` DTO。
     */
    fun present(
        sessionId: String,
        personaId: String,
        messages: List<Message>,
        sink: SseSink?,
        enqueuedAt: Long = clock.millis()
    ) {
        if (messages.isEmpty()) return
        val lock = sessionLocks.computeIfAbsent(sessionId) { ReentrantLock() }
        lock.lock()
        try {
            val entries = messages.map { message ->
                PendingDelivery(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    personaId = personaId,
                    messageId = message.id,
                    visibleAt = message.visibleAt ?: message.createdAt,
                    enqueuedAt = enqueuedAt
                )
            }
            // 先 claim 再入队：恢复线程在 250 ms 轮询里即使看到这批条目也不会重复发。
            claimed += entries.map { it.id }
            store.enqueueDeliveries(entries)
            store.appendTypingEvent(sessionId, personaId, EventType.TYPING_STARTED, enqueuedAt)
            sink?.emit(SseEvent.TypingStarted(sessionId, personaId, enqueuedAt))
            var lastDeliveredAt = enqueuedAt
            for ((index, message) in messages.withIndex()) {
                val entry = entries[index]
                sleeper.sleepUntil(entry.visibleAt)
                lastDeliveredAt = maxOf(clock.millis(), entry.visibleAt)
                store.appendMessageCreatedEvent(sessionId, personaId, message, lastDeliveredAt)
                store.completeDelivery(entry.id)
                claimed.remove(entry.id)
                sink?.emit(SseEvent.MessageCreated(message))
            }
            store.appendTypingEvent(sessionId, personaId, EventType.TYPING_STOPPED, lastDeliveredAt)
            sink?.emit(SseEvent.TypingStopped(sessionId, personaId, lastDeliveredAt))
        } finally {
            lock.unlock()
            if (!lock.hasQueuedThreads()) sessionLocks.remove(sessionId, lock)
        }
    }

    /**
     * 后台消息的「前台在线」投递（主动消息 / 日记提醒 / defer_reply 念头）。
     *
     * 与 [present] 的区别：**不阻塞调用线程**。心跳线程不该为了 4 秒打字动画停在那里，
     * 所以这里只做「入队 + typing.started」，到点的 `message.created` 由 [start] 的恢复线程发。
     * 客户端在线时它看起来与聊天消息完全一样（typing → message → stopped）。
     */
    fun enqueueAsync(
        sessionId: String,
        personaId: String,
        messages: List<Message>,
        enqueuedAt: Long = clock.millis()
    ) {
        if (messages.isEmpty()) return
        val entries = messages.map { message ->
            PendingDelivery(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                personaId = personaId,
                messageId = message.id,
                visibleAt = message.visibleAt ?: message.createdAt,
                enqueuedAt = enqueuedAt
            )
        }
        store.enqueueDeliveries(entries)
        store.appendTypingEvent(sessionId, personaId, EventType.TYPING_STARTED, enqueuedAt)
    }

    /**
     * 0.15：**离线也留队列记录**的立即投递。
     *
     * 后台消息（主动消息/日记提醒/延后回复）在客户端不在线时以前是「落库即发事件」，
     * 队列里什么都没有；现在统一走「先入队、再立即写 `message.created` 并完成条目」：
     * - 队列记录（`message_queue.json`）真实存在过，崩溃恢复线程/诊断能看到这一条；
     * - `message.created` 事件照常写进 `events.jsonl`，客户端下次启动同步即可见；
     * - 因为立即完成，队列不会堆积，行为与「落库即发事件」对客户端完全等价。
     *
     * 返回实际投递的条数（消息已被删除的条目会作废）。
     */
    fun deliverNow(
        sessionId: String,
        personaId: String,
        messages: List<Message>,
        now: Long = clock.millis()
    ): Int {
        if (messages.isEmpty()) return 0
        val entries = messages.map { message ->
            PendingDelivery(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                personaId = personaId,
                messageId = message.id,
                visibleAt = message.visibleAt ?: message.createdAt,
                enqueuedAt = now
            )
        }
        store.enqueueDeliveries(entries)
        var delivered = 0
        for ((index, message) in messages.withIndex()) {
            store.appendMessageCreatedEvent(sessionId, personaId, message, now)
            store.completeDelivery(entries[index].id)
            delivered += 1
        }
        return delivered
    }

    // ------------------------------------------------------------------ 崩溃恢复

    /** 启动后台恢复线程（`Main` 在服务起来后调用；重复调用幂等）。 */
    fun start() {
        synchronized(this) {
            if (running) return
            running = true
            val thread = Thread({ loop() }, "agentd-delivery")
            thread.isDaemon = true
            worker = thread
            thread.start()
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            worker?.interrupt()
            worker = null
        }
    }

    val isRunning: Boolean get() = running

    private fun loop() {
        while (running) {
            runCatching { recoverDue() }
                .onFailure { failure ->
                    System.err.println("[agentd] 投递恢复失败：${failure::class.simpleName}: ${failure.message}")
                }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * 补发到点但没投递的条目（重启恢复 / 前台异常退出）。
     *
     * 只处理**没被前台 claim** 的条目；前台正常路径已经 `completeDelivery`，不会重复。
     */
    fun recoverDue(now: Long = clock.millis()): Int {
        val cutoff = now - recoveryGraceMs
        var delivered = 0
        for (entry in store.dueDeliveries(cutoff)) {
            if (entry.id in claimed) continue
            claimed += entry.id
            try {
                val message = store.findMessage(entry.sessionId, entry.messageId)
                if (message == null) {
                    // 消息被删了/会话没了：条目作废，不发事件。
                    store.completeDelivery(entry.id)
                    continue
                }
                store.appendMessageCreatedEvent(entry.sessionId, entry.personaId, message, now)
                store.completeDelivery(entry.id)
                delivered += 1
                if (store.pendingDeliveries().none { it.sessionId == entry.sessionId }) {
                    store.appendTypingEvent(entry.sessionId, entry.personaId, EventType.TYPING_STOPPED, now)
                }
            } finally {
                claimed.remove(entry.id)
            }
        }
        return delivered
    }

    /** 是否还有排队等发的消息（诊断/测试用）。 */
    fun pendingCount(): Int = store.pendingDeliveries().size
}
