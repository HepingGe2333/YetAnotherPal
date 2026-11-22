package com.yap.agentd

import com.yap.agentd.delivery.ClientPresence
import com.yap.agentd.delivery.MessageDelivery
import com.yap.agentd.delivery.Sleeper
import com.yap.agentd.delivery.TypingDelay
import com.yap.agentd.store.EventType
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Mood
import com.yap.agentd.store.PendingDelivery
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.UUID

/**
 * 0.14.1 服务端消息队列与 typing（`spec/experience/README.md` §8）的时序测试。
 *
 * 所有用例都用**可注入的 clock 与 sleep**：假 sleeper 只把假时钟拨到目标时刻，
 * 所以「第几条在什么时刻发」可以逐条断言，而不必真的等 4 秒。
 *
 * 钉住的行为：
 * - 延迟公式与心情修正（§5）；
 * - 前台在线：`typing.started` → 到点 `message.created` → 全部发完 `typing.stopped`；
 * - 离线：不发 typing，消息落库即发事件（同步直接显示）；
 * - `GET /v1/sessions/{id}/messages` 直接返回、不延迟；
 * - 队列持久化（重启不丢）与崩溃恢复补发。
 */
class DeliveryTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000_000L)
    private val sleeps = mutableListOf<Long>()

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-delivery")
        clock.now = 1_000_000L
        sleeps.clear()
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun presence(mark: Boolean = true): ClientPresence = ClientPresence(clock.asClock()).also {
        if (mark) it.markSeen(clock.now)
    }

    /** 假 sleeper：记录目标时刻，并把假时钟拨到那里（时间「瞬间过去」）。 */
    private fun sleeper(): Sleeper = Sleeper { target ->
        sleeps += target
        if (target > clock.now) clock.now = target
    }

    private fun delivery(presence: ClientPresence): MessageDelivery = MessageDelivery(
        store = store,
        clock = clock.asClock(),
        presence = presence,
        sleeper = sleeper(),
        // 0.15：延迟均值 × (1 ± 35%)；测试固定为 0.5，只看均值与累计时序。
        random = { 0.5 }
    )

    private fun twoMessageGenerator(): ReplyGenerator = ReplyGenerator { _, _ ->
        ReplyOutcome(usage = Usage(10, 20), messages = listOf("第一条", "第二条"))
    }

    private fun services(delivery: MessageDelivery?, presence: ClientPresence = presence()): AgentdServices =
        AgentdServices(
            store = store,
            replyGenerator = twoMessageGenerator(),
            presence = presence,
            delivery = delivery
        )

    /** 一次事件是不是一条 assistant 消息（用户消息也会发 `message.created`）。 */
    private fun com.yap.agentd.store.StoredEvent.isAssistantMessage(): Boolean {
        val message = payload.fields["message"] as? JsonValue.Obj ?: return false
        return (message.fields["role"] as? JsonValue.Str)?.value == "ASSISTANT"
    }

    private fun send(services: AgentdServices, sink: SseSink, clientMessageId: String = "c-1") {
        AuthorityRoutes.stream(
            services,
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"$clientMessageId","content":"在吗"}""",
            sink
        )
    }

    // ------------------------------------------------------------------ 延迟公式

    @Test
    fun `delay formula has a 06 to 09 second mean and a 35 percent jitter`() {
        // 均值：0 字 0.6s、15 字 0.75s、30 字 0.9s（0.6 + 字数×0.01，夹在 0.6..0.9）。
        assertEquals(600L, TypingDelay.millis("", null) { 0.5 })
        assertEquals(620L, TypingDelay.millis("你好", null) { 0.5 })
        assertEquals(750L, TypingDelay.millis("字".repeat(15), null) { 0.5 })
        assertEquals(900L, TypingDelay.millis("字".repeat(30), null) { 0.5 })
        assertEquals(900L, TypingDelay.millis("字".repeat(500), null) { 0.5 })
        // 低唤醒（很困）更慢；高唤醒更快；不愉快再慢一点（修正后仍夹在均值区间内）。
        val sleepy = Mood(personaId = personaId, label = "困", intensity = 2, updatedAt = 1L, arousal = 0)
        val excited = Mood(personaId = personaId, label = "兴奋", intensity = 4, updatedAt = 1L, valence = 2, arousal = 5)
        val unhappy = Mood(personaId = personaId, label = "低落", intensity = 3, updatedAt = 1L, valence = -2, arousal = 2)
        assertEquals(900L, TypingDelay.millis("你好", sleepy) { 0.5 })
        assertEquals(600L, TypingDelay.millis("你好", excited) { 0.5 })
        assertEquals(820L, TypingDelay.millis("你好", unhappy) { 0.5 })
        assertEquals(0.5, TypingDelay.moodAdjustment(sleepy), 0.0001)
        assertEquals(-0.4, TypingDelay.moodAdjustment(excited), 0.0001)

        // ±35%：0.0 是下界、1.0 是上界、0.5 正好是均值。
        val mean = TypingDelay.meanSeconds("字".repeat(13))
        assertEquals(mean * 0.65, TypingDelay.seconds("字".repeat(13), null) { 0.0 }, 0.0001)
        assertEquals(mean, TypingDelay.seconds("字".repeat(13), null) { 0.5 }, 0.0001)
        assertEquals(mean * 1.35, TypingDelay.seconds("字".repeat(13), null) { 1.0 }, 0.0001)
        assertEquals("坏随机源不抖动", mean, TypingDelay.seconds("字".repeat(13), null) { Double.NaN }, 0.0001)
        assertTrue("所有长度都落在 0.6..0.9 秒的均值区间", (0..500 step 7).all {
            TypingDelay.meanSeconds("字".repeat(it)) in TypingDelay.MEAN_MIN_SECONDS..TypingDelay.MEAN_MAX_SECONDS
        })
    }

    @Test
    fun `visibleAt is cumulative and only exists while the client is online`() {
        val presence = presence()
        val delivery = delivery(presence)
        val online = delivery.visibleAts(personaId, listOf("你好", "第二条消息"))
        assertNotNull(online)
        assertEquals(2, online!!.size)
        assertTrue("第二条要排在第一条的 visibleAt 之后", online[1] > online[0])
        assertEquals(1_000_000L + 620L, online[0])
        assertEquals(online[0] + TypingDelay.millis("第二条消息", null) { 0.5 }, online[1])

        presence.reset()
        assertNull("离线不延迟", delivery.visibleAts(personaId, listOf("你好")))
        assertFalse(delivery.shouldDelay())
    }

    // ------------------------------------------------------------------ 前台在线：typing → message.created → typing.stopped

    @Test
    fun `foreground turn emits typing started then message created then typing stopped`() {
        val presence = presence()
        val delivery = delivery(presence)
        val sink = RecordingSink()
        send(services(delivery), sink)

        assertEquals(
            listOf("accepted", "typing.started", "message.created", "message.created", "typing.stopped", "done"),
            sink.types()
        )
        val started = sink.firstOf<SseEvent.TypingStarted>()
        assertEquals(sessionId, started.sessionId)
        assertEquals(personaId, started.personaId)
        assertEquals(1_000_000L, started.at)

        val created = sink.events.filterIsInstance<SseEvent.MessageCreated>().map { it.message }
        assertEquals(2, created.size)
        assertEquals(listOf("第一条", "第二条"), created.map { it.content })
        assertEquals(created[0].visibleAt, sleeps[0])
        assertEquals(created[1].visibleAt, sleeps[1])
        assertTrue("visibleAt 必须晚于 createdAt", created[0].visibleAt!! > created[0].createdAt)
        assertTrue(created[1].visibleAt!! > created[0].visibleAt!!)

        val stopped = sink.firstOf<SseEvent.TypingStopped>()
        assertEquals(created.last().visibleAt, stopped.at)
        assertTrue("投递完队列要清空", store.pendingDeliveries().isEmpty())
    }

    @Test
    fun `typing and message events are persisted for the events long poll`() {
        val delivery = delivery(presence())
        send(services(delivery), RecordingSink())

        val events = store.eventsSince(0, limit = 100)
        val typingStarted = events.filter { it.type == EventType.TYPING_STARTED }
        // 用户消息自己也会发 message.created（落库即发）；这里只数 assistant 的。
        val created = events.filter { it.type == EventType.MESSAGE_CREATED && it.isAssistantMessage() }
        val typingStopped = events.filter { it.type == EventType.TYPING_STOPPED }
        assertEquals(1, typingStarted.size)
        assertEquals("一轮只发一次 typing.started", 1, typingStopped.size)
        assertEquals("落库时不发事件，到点才发", 2, created.size)
        assertEquals(sessionId, typingStarted.first().sessionId)
        assertEquals(personaId, typingStarted.first().personaId)
        // 事件顺序：typing.started 在所有 message.created 之前，typing.stopped 在最后。
        val order = events.filter {
            it.type == EventType.TYPING_STARTED || it.type == EventType.TYPING_STOPPED ||
                (it.type == EventType.MESSAGE_CREATED && it.isAssistantMessage())
        }.map { it.type }
        assertEquals(
            listOf(
                EventType.TYPING_STARTED,
                EventType.MESSAGE_CREATED,
                EventType.MESSAGE_CREATED,
                EventType.TYPING_STOPPED
            ),
            order
        )
        // message.created 的 payload 里有完整消息 DTO（含 visibleAt），与 SSE 同一份形状。
        val message = (created.first().payload.fields["message"] as JsonValue.Obj)
        assertTrue(message.fields.containsKey("visibleAt"))
    }

    @Test
    fun `messages endpoint returns immediately without applying the delay`() {
        val delivery = delivery(presence())
        send(services(delivery), RecordingSink())
        val nowAfterTurn = clock.now

        val response = AuthorityRoutes.handle(
            services(delivery),
            "GET",
            "/v1/sessions/$sessionId/messages",
            null
        )
        assertEquals(200, response.status)
        val page = TestSupport.parse(response.body)
        val contents = page.objects("messages").map { it.str("content") }
        assertEquals(listOf("在吗", "第一条", "第二条"), contents)
        assertEquals("直接返回不能改动时钟（没有延迟）", nowAfterTurn, clock.now)
        // 同步路径也能看到 visibleAt（客户端据此知道「服务端已经替它等过了」）。
        assertTrue(page.objects("messages").all { it.fields.containsKey("visibleAt") })
    }

    // ------------------------------------------------------------------ 离线：不发 typing、立即上屏

    @Test
    fun `offline client gets no typing events and messages are available immediately`() {
        val presence = presence(mark = false)
        val delivery = delivery(presence)
        val sink = RecordingSink()
        // 直接调 ChatService（路由入口会 markSeen，模拟离线时不应经过它）。
        services(delivery).chatService!!.send(
            sessionId,
            SendMessageRequest(clientMessageId = "offline-1", content = "在吗"),
            sink
        )

        assertFalse(sink.types().contains("typing.started"))
        assertFalse(sink.types().contains("typing.stopped"))
        assertEquals(listOf("accepted", "message.created", "message.created", "done"), sink.types())
        assertTrue("离线不排队", store.pendingDeliveries().isEmpty())
        val messages = store.blockMessages(sessionId, 0).filter { it.role == "ASSISTANT" }
        assertEquals(2, messages.size)
        assertEquals("离线消息的 visibleAt 就是 createdAt", messages[0].createdAt, messages[0].visibleAt)
        val events = store.eventsSince(0, limit = 100)
        assertEquals(2, events.count { it.type == EventType.MESSAGE_CREATED && it.isAssistantMessage() })
        assertEquals(0, events.count { it.type == EventType.TYPING_STARTED })
    }

    @Test
    fun `without a delivery service the old immediate path is unchanged`() {
        val sink = RecordingSink()
        send(services(delivery = null), sink)
        // 连发（2 条）仍然逐条 message.created，最后只有一个 done；没有 typing（0.14 行为）。
        assertEquals(listOf("accepted", "message.created", "message.created", "done"), sink.types())
        assertEquals(
            2,
            store.eventsSince(0, limit = 100)
                .count { it.type == EventType.MESSAGE_CREATED && it.isAssistantMessage() }
        )
    }

    // ------------------------------------------------------------------ 队列持久化与崩溃恢复

    @Test
    fun `pending deliveries survive a restart and are re-sent by the recovery worker`() {
        val message = store.appendMessage(
            sessionId = sessionId,
            role = "ASSISTANT",
            content = "排队中的一条",
            visibleAt = clock.now + 2_000L,
            emitEvent = false
        )
        store.enqueueDeliveries(
            listOf(
                PendingDelivery(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    personaId = personaId,
                    messageId = message.id,
                    visibleAt = message.visibleAt!!,
                    enqueuedAt = clock.now
                )
            )
        )
        assertTrue(store.eventsSince(0, limit = 100).none { it.type == EventType.MESSAGE_CREATED })

        // 重启：同一个数据目录重新打开，队列还在。
        val reopened = FileStore.open(root, clock.reader)
        assertEquals(1, reopened.pendingDeliveries().size)
        val delivery = MessageDelivery(
            store = reopened,
            clock = clock.asClock(),
            presence = presence(mark = false),
            sleeper = sleeper(),
            random = { 0.5 }
        )
        clock.now += 2_500L
        assertEquals("补发 1 条", 1, delivery.recoverDue())
        assertEquals(0, reopened.pendingDeliveries().size)
        val types = reopened.eventsSince(0, limit = 100).map { it.type }
        assertEquals(1, types.count { it == EventType.MESSAGE_CREATED })
        assertEquals(1, types.count { it == EventType.TYPING_STOPPED })

        // 幂等：再补发一次没有东西可发，也不会重复写事件。
        assertEquals(0, delivery.recoverDue())
        assertEquals(1, reopened.eventsSince(0, limit = 100).count { it.type == EventType.MESSAGE_CREATED })
    }

    @Test
    fun `online diary reminders are queued and delivered at visibleAt`() {
        store.upsertDiaryNote(
            personaId = personaId,
            noteId = null,
            content = "记得带伞",
            remindAt = clock.now
        )
        val presence = presence()
        val delivery = delivery(presence)
        val services = AgentdServices(
            store = store,
            replyGenerator = twoMessageGenerator(),
            presence = presence,
            delivery = delivery
        )
        val fired = services.proactiveTicker!!.tick(clock.now)
            .filter { it.reason == "diary_reminder" }
        assertEquals(1, fired.size)
        val message = store.findMessage(sessionId, fired.single().messageId)!!
        assertTrue("提醒要按 visibleAt 延后", message.visibleAt!! > message.createdAt)

        // 入队即发 typing.started；到点之前还没有 message.created 事件（等恢复线程发）。
        val before = store.eventsSince(0, limit = 100)
        assertEquals(1, before.count { it.type == EventType.TYPING_STARTED })
        assertTrue(before.none { it.type == EventType.MESSAGE_CREATED && it.isAssistantMessage() })
        assertEquals(1, store.pendingDeliveries().size)

        clock.now += 10_000L
        assertEquals(1, delivery.recoverDue())
        val after = store.eventsSince(0, limit = 100)
        assertEquals(1, after.count { it.type == EventType.MESSAGE_CREATED && it.isAssistantMessage() })
        assertEquals(1, after.count { it.type == EventType.TYPING_STOPPED })
        assertTrue(store.pendingDeliveries().isEmpty())
    }

    @Test
    fun `starting the worker thread does not throw and stop is clean`() {
        val delivery = delivery(presence())
        delivery.start()
        assertTrue(delivery.isRunning)
        delivery.stop()
        assertFalse(delivery.isRunning)
    }
}
