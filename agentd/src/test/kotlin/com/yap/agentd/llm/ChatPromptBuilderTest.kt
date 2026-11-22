package com.yap.agentd.llm

import com.yap.agentd.TestSupport
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Mood
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * 服务端提示词拼装（`spec/persona-guidance` §1 + `spec/mood` §3）。
 *
 * 这些断言直接钉「提示词里到底写了什么、顺序对不对」：规范要求段顺序不可乱、
 * 固定中文来自共享脚手架（`:agentcore` `PersonaPromptScaffold`），心情必须带三个维度
 * 且不能出现「我的情绪是…」式自述。
 */
class ChatPromptBuilderTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-15T11:40:00Z").toEpochMilli() // 19:40 本地

    private val builder = ChatPromptBuilder(zone)

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-prompt-test")
        store = FileStore.open(root, { now })
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun persona() = store.persona(FileStore.SEED_PERSONA_1_ID)!!

    private fun seedRichPersona() {
        store.setPersonaExtras(
            extras = mapOf(
                "worldJson" to JsonValue.Str(
                    """{"worldName":"临港新城·云筑建筑事务所","era":"当代，中国沿海新一线城市，2026 年",""" +
                        """"description":"在事务所画图、审图。","notes":"周三晚上要给母亲打电话。"}"""
                ),
                "speechStyleJson" to JsonValue.Str(
                    """{"tone":"简短直接，偏冷淡务实","phrases":["在忙，晚点说","别想太多"],""" +
                        """"habits":["句子很短"],"avoid":["长篇大论"]}"""
                ),
                "traits" to listText("慢热", "嘴硬心软"),
                "likes" to listText("夜里散步"),
                "behaviors" to listText("先听完再给建议"),
                "quirks" to JsonValue.Str("写字前会先转笔。"),
                "personaCardJson" to JsonValue.Str(
                    """{"identity":{"age":"二十六","role":"建筑师","relation":"恋人"},""" +
                        """"voice":{"address":"你"},"life":{"schedule":"十一点半睡"},"bond":"你们在一起三年了。"}"""
                )
            )
        )
    }

    @Test
    fun `sections follow the normative order`() {
        seedRichPersona()
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        // 0.15 上下文 profile（state_first）的固定顺序：
        // 状态（此刻 + 心情 + 引导）→ 人设 → 工具 → 反 AI → （记忆为空则省略）→ 边界。
        val titles = listOf(
            "# 你此刻的状态",
            "# 你的心情",
            "# 引导",
            "# 你是谁",
            "# 你所在的世界",
            "# 你怎么说话",
            "# 你们的关系",
            "# 你能做什么",
            "# 别像 AI",
            "# 回复节奏",
            "# 边界"
        )
        val positions = titles.map { prompt.indexOf(it) }
        assertTrue("这些段都必须出现：$positions", positions.all { it >= 0 })
        assertEquals("段顺序不可乱（state_first）", positions.sorted(), positions)
        assertTrue("状态段排在最前", prompt.startsWith("# 你此刻的状态"))
        assertTrue("边界是最后一段", prompt.trimEnd().endsWith("同一个口头禅开头。"))
    }

    @Test
    fun `identity world voice relationship and profile data all make it into the prompt`() {
        seedRichPersona()
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        assertTrue(prompt.contains("林深"))
        assertTrue("人设卡 meta 进身份段", prompt.contains("二十六，建筑师"))
        assertTrue("世界名", prompt.contains("临港新城"))
        assertTrue("世界描述", prompt.contains("在事务所画图、审图"))
        assertTrue("其他背景", prompt.contains("周三晚上要给母亲打电话"))
        assertTrue("话风 tone", prompt.contains("简短直接，偏冷淡务实"))
        assertTrue("常用语", prompt.contains("在忙，晚点说"))
        assertTrue("话风习惯数据行", prompt.contains("- 习惯：句子很短"))
        assertTrue("关系段", prompt.contains("你是对方的恋人"))
        assertTrue("profile 性格", prompt.contains("- 性格：慢热、嘴硬心软"))
        assertTrue("profile 设定", prompt.contains("写字前会先转笔"))
        assertTrue("时间（日期 + 星期 + 时区）", prompt.contains("2026-11-15 周日 19:40（UTC+08:00）"))
    }

    @Test
    fun `no world and no mood still produce a complete prompt without a mood section`() {
        val prompt = builder.systemPrompt(persona(), mood = null, activity = null, now = now)
        assertTrue(prompt.contains("你没有特别的世界设定"))
        assertFalse("没有心情就不写心情段", prompt.contains("# 你的心情"))
        assertTrue(prompt.contains("# 边界"))
        assertTrue("没有心情也必须有引导（g17 兜底）", prompt.contains("# 引导"))
    }

    @Test
    fun `a negative or low arousal mood picks g12 and puts it between mood and voice`() {
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        assertTrue(prompt.contains("# 引导"))
        assertTrue("valence -1 / arousal 2 → g12", prompt.contains("这一轮：有点防备，不轻易示弱"))
        assertTrue(prompt.contains("- 用短句挡一下"))
        assertTrue(prompt.contains("- 被问急了才多露半句"))
        assertTrue(prompt.contains("- 不要掏心掏肺。"))
        assertFalse("不该同时出现 g17", prompt.contains("只聊此刻"))

        val stateIndex = prompt.indexOf("# 你此刻的状态")
        val moodIndex = prompt.indexOf("# 你的心情")
        val guidanceIndex = prompt.indexOf("# 引导")
        val voiceIndex = prompt.indexOf("# 你怎么说话")
        assertTrue("引导在心情之后、话风之前", stateIndex < moodIndex && moodIndex < guidanceIndex)
        assertTrue("引导是人设之前的最后一段", guidanceIndex < voiceIndex)
    }

    @Test
    fun `a positive mood picks g17 and missing mood falls back to g17`() {
        val positive = mood().copy(label = "挺开心", valence = 1, arousal = 4)
        val fromPositive = builder.systemPrompt(persona(), positive, activity = null, now = now)
        assertTrue("正向高唤醒 → g17", fromPositive.contains("这一轮：只聊此刻，不翻旧账"))
        assertTrue(fromPositive.contains("- 回应现在这句话"))
        assertTrue(fromPositive.contains("- 不延伸过去"))
        assertFalse("不该同时出现 g12", fromPositive.contains("有点防备"))

        val fromMissing = builder.systemPrompt(persona(), mood = null, activity = null, now = now)
        assertTrue("心情缺失 → g17", fromMissing.contains("这一轮：只聊此刻，不翻旧账"))

        // 边界：valence 0 但 arousal 1（低唤醒）算偏负档；arousal 2（平静）不算。
        val lowArousal = builder.systemPrompt(persona(), mood().copy(valence = 0, arousal = 1), activity = null, now = now)
        assertTrue("arousal < 2 → g12", lowArousal.contains("这一轮：有点防备，不轻易示弱"))
        val calm = builder.systemPrompt(persona(), mood().copy(valence = 0, arousal = 2), activity = null, now = now)
        assertTrue("arousal = 2（平静）→ g17", calm.contains("这一轮：只聊此刻，不翻旧账"))

        // 边界：valence 0 且 arousal 3 → 正常档。
        val neutral = builder.systemPrompt(persona(), mood().copy(valence = 0, arousal = 3), activity = null, now = now)
        assertTrue("neutral → g17", neutral.contains("这一轮：只聊此刻，不翻旧账"))
    }

    @Test
    fun `the anti ai section is exactly the five core rules`() {
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)
        val section = prompt.substringAfter("# 别像 AI").substringBefore("\n\n# ")
        val rules = section.lines().map { it.trim() }.filter { it.startsWith("- ") }

        assertEquals("core 只留五条：$rules", 5, rules.size)
        assertTrue("不反问", section.contains("不要每句话都反问"))
        assertTrue("不总结", section.contains("不要总结对方说的话"))
        assertTrue("不列点", section.contains("不要列点"))
        assertTrue("不总安慰", section.contains("不要每次都认同或安慰"))
        assertTrue("不旁白", section.contains("旁白") && section.contains("沉默了一会儿"))
        assertFalse("节奏那条删了", section.contains("句子可以短、可以不完整"))
        assertFalse("建议那条删了", section.contains("不主动给建议"))
        assertFalse("stay_silent 那条删了（工具说明里有）", section.contains("不想说话时调用"))
        assertTrue("工具段仍然带着 stay_silent 的用法", prompt.contains("stay_silent"))
    }

    @Test
    fun `the reply rhythm section pins one short emotional reply per turn`() {
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)
        val section = prompt.substringAfter("# 回复节奏").substringBefore("\n\n# ")
        val rules = section.lines().map { it.trim() }.filter { it.startsWith("- ") }

        assertEquals("六条节奏规则：$rules", 6, rules.size)
        assertTrue("默认只发 1 条", section.contains("默认只发 1 条"))
        assertTrue("只有新信息才第 2 条", section.contains("只有真的带来新信息，才补第 2 条"))
        assertTrue("每条 ≤18 字", section.contains("不超过 18 字"))
        assertTrue("先接情绪再说自己", section.contains("先接住对方的情绪"))
        assertTrue("不为连发拆句", section.contains("不要为了「连发」把一句话拆成几条"))
        assertTrue("深夜/累更短但不只 4 字", section.contains("深夜或很累的时候更短") && section.contains("不要只剩四个字"))
        assertEquals(ReplyRhythm.section(), "# 回复节奏\n" + rules.joinToString("\n"))
    }

    @Test
    fun `busy activity makes the prompt say he is not available`() {
        val activity = ActivityState(
            kind = "WORK",
            activity = "在事务所赶第三版方案",
            until = now + 90 * 60_000L,
            availability = ActivityAvailability.BUSY
        )
        val prompt = builder.systemPrompt(persona(), mood(), activity, now)

        assertTrue(prompt.contains("你不太有空"))
        assertTrue(prompt.contains("21:10 结束"))
        assertFalse("不能在忙的时候说「没在忙什么」", prompt.contains("你没在忙什么。"))
    }

    @Test
    fun `multi dimensional mood is injected and never self reported`() {
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        assertTrue(prompt.contains("心情：有点累（强度 3/5）"))
        assertTrue("三个维度都要有", prompt.contains("愉快度 -1"))
        assertTrue(prompt.contains("唤醒度 2/5"))
        assertTrue(prompt.contains("掌控感 3/5"))
        assertTrue(prompt.contains("不要直接报数值"))
        assertEquals("「我的情绪是」只允许出现在禁止自述的那一句里", 1, Regex("我的情绪是").findAll(prompt).count())
        assertFalse("心情原因不直接进提示词", prompt.contains("连着加班"))
    }

    @Test
    fun `chat messages map history roles and keep the system prompt first`() {
        seedRichPersona()
        val messages = mutableListOf(
            message("m1", MessageRole.USER, "在干嘛", createdAt = now - 5 * 60_000L),
            message("m2", MessageRole.ASSISTANT, "在改图。", createdAt = now - 4 * 60_000L),
            message(
                "m3", MessageRole.USER, "几点完", createdAt = now - 60_000L,
                attachments = listOf("/v1/media/img-1")
            )
        )
        val built = builder.chatMessages(persona(), mood(), null, messages, now)

        assertEquals(listOf("system", "user", "assistant", "user"), built.map { it.role })
        assertEquals("profile 的 timestamped 历史：`[HH:mm] 用户：…`", "[19:35] 用户：在干嘛", built[1].content)
        assertEquals("[19:36] 他：在改图。", built[2].content)
        assertTrue("用户消息用「用户：」前缀", built[3].content.startsWith("[19:39] 用户：几点完"))
        assertTrue("附件要告诉模型「你看不到内容」", built[3].content.contains("看不到内容"))
    }

    @Test
    fun `history timestamps switch to month-day across days and skip tool messages`() {
        val messages = listOf(
            // 前一天晚上 21:05（本地）→ 跨天，要带月日
            message("d1", MessageRole.USER, "昨天那句", createdAt = Instant.parse("2026-11-14T13:05:00Z").toEpochMilli()),
            message("d2", MessageRole.ASSISTANT, "嗯。", createdAt = Instant.parse("2026-11-14T13:06:00Z").toEpochMilli()),
            // 今天 → 只写时:分
            message("d3", MessageRole.USER, "今天继续", createdAt = Instant.parse("2026-11-15T11:35:00Z").toEpochMilli()),
            // 工具/系统消息不加时间戳
            message("d4", MessageRole.SYSTEM, "导入的一条系统消息", createdAt = Instant.parse("2026-11-15T11:36:00Z").toEpochMilli()),
            // 时间缺失（导入老数据）也不加
            message("d5", MessageRole.USER, "没有时间的那条", createdAt = 0L)
        )
        val built = builder.chatMessages(persona(), null, null, messages, now, historyLimit = 10)

        assertEquals("[11-14 21:05] 用户：昨天那句", built[1].content)
        assertEquals("[11-14 21:06] 他：嗯。", built[2].content)
        assertEquals("[19:35] 用户：今天继续", built[3].content)
        assertEquals("系统消息原样透传", "导入的一条系统消息", built[4].content)
        assertEquals("没有 createdAt 的历史不加假时间", "没有时间的那条", built[5].content)
    }

    @Test
    fun `the remaining budget decides history length instead of a fixed forty cap`() {
        val messages = (1..60).map { index ->
            message(
                id = "m$index",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "第 $index 句",
                createdAt = now - (60 - index) * 60_000L
            )
        }
        val built = builder.chatMessages(persona(), null, null, messages, now)
        assertEquals("60 条短消息全都在预算内（不再被 40 条截断）", 61, built.size)
        assertEquals("顺序仍然是升序", "第 60 句", built.last().content.substringAfter("："))
    }

    @Test
    fun `proactive messages add the one line instruction and the situation`() {
        val built = builder.proactiveMessages(persona(), mood(), null, emptyList(), now)
        val system = built.first().content
        assertTrue(system.contains("# 现在的情况"))
        assertTrue(system.contains("只输出这一句话本身"))
        assertTrue(built.any { it.role == "user" && it.content.contains("主动找他") })
    }

    private fun mood() = Mood(
        personaId = FileStore.SEED_PERSONA_1_ID,
        label = "有点累",
        intensity = 3,
        updatedAt = now - 60_000L,
        valence = -1,
        arousal = 2,
        dominance = 3,
        reason = "连着加班"
    )

    /** 一条存储层消息（默认 CHAT 来源、无附件；测试只关心角色/内容/时间）。 */
    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        attachments: List<String> = emptyList()
    ) = com.yap.agentd.store.Message(
        id = id, sessionId = FileStore.SEED_SESSION_ID, blockIndex = 0,
        role = role, content = content, createdAt = createdAt, readAt = null,
        attachments = attachments, source = "CHAT", clientMessageId = null,
        replyTo = null, promptTokens = null, completionTokens = null
    )
}
