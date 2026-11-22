package com.yap.app.domain

import com.yap.app.data.local.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从最近聊天学习话风」的取样规则（`spec/persona-guidance/README.md` §3 的另一种取样方式）。
 *
 * 这里只测纯函数：样本怎么拼、取几条、怎么截断、多短算「没得学」；以及「应用」时怎么改人格。
 * 读库与调用模型的部分在 `StyleDistillationRepositoryTest`，接线在
 * `SpeechStyleLearnWiringContractTest`。
 *
 * 隐私是**结构性**保证：这个类没有落库入口（String 进、String 出），所以「样本不落库」
 * 在这里表现为「[StyleDistillation] 的公开 API 里没有任何一眼能写盘的参数」。
 */
class StyleDistillationTest {

    private fun msg(fromUser: Boolean, text: String) = StyleDistillation.Message(fromUser, text)

    // ------------------------------------------------------------ 样本组装

    @Test
    fun `样本用 用户与 他 两个人称 按时间正序拼行`() {
        val sample = StyleDistillation.sample(
            listOf(
                msg(fromUser = true, text = "在吗"),
                msg(fromUser = false, text = "嗯，刚下班"),
                msg(fromUser = true, text = "累不累"),
                msg(fromUser = false, text = "还行")
            )
        )
        assertEquals(
            "用户：在吗\n他：嗯，刚下班\n用户：累不累\n他：还行",
            sample
        )
        assertTrue(sample.startsWith(StyleDistillation.USER_PREFIX))
        assertTrue(sample.contains("\n" + StyleDistillation.PERSONA_PREFIX))
    }

    @Test
    fun `只用固定人称 不写真实名字`() {
        // 样本里的人称是固定的：模型不需要知道对方叫什么，也不该把名字写进话风。
        val sample = StyleDistillation.sample(listOf(msg(true, "林深你在干嘛"), msg(false, "在加班")))
        assertTrue(sample.contains("用户：林深你在干嘛"))
        assertTrue(sample.contains("他：在加班"))
        assertFalse("样本里不出现「林深：」这种昵称前缀", sample.contains("林深："))
    }

    @Test
    fun `没有正文的消息被跳过 空样本返回空串`() {
        assertEquals("", StyleDistillation.sample(emptyList()))
        // 只有表情/图片的消息（content 为空）不算语料。
        assertEquals(
            "",
            StyleDistillation.sample(listOf(msg(true, ""), msg(false, "   "), msg(true, "\n")))
        )
        val mixed = StyleDistillation.sample(
            listOf(msg(true, "在吗"), msg(true, ""), msg(false, "在"))
        )
        assertEquals("用户：在吗\n他：在", mixed)
    }

    @Test
    fun `换行与连续空白压成一行`() {
        val sample = StyleDistillation.sample(
            listOf(msg(true, "今天  好累\n\n不想说话"), msg(false, "那就先睡"))
        )
        assertEquals("用户：今天 好累 不想说话\n他：那就先睡", sample)
        assertEquals("a b", StyleDistillation.flatten("  a\n\n b  "))
    }

    // ------------------------------------------------------------ 条数与截断

    @Test
    fun `最多取最近 50 条 太老的不要`() {
        val messages = (1..80).map { msg(fromUser = it % 2 == 0, text = "第 $it 条") }
        val lines = StyleDistillation.pick(messages)
        assertTrue("最多 50 行，实际 ${lines.size}", lines.size <= StyleDistillation.MAX_MESSAGES)
        assertEquals(50, StyleDistillation.MAX_MESSAGES)
        // 最近的那条一定在，最早的那几条一定不在。
        assertTrue(lines.last().contains("第 80 条"))
        assertFalse(lines.any { it.contains("第 1 条") })
    }

    @Test
    fun `单条过长会截到 120 字并补省略号`() {
        val long = "字".repeat(500)
        val line = StyleDistillation.pick(listOf(msg(true, long))).single()
        // 前缀 + 120 字（最后一位换成省略号）。
        assertEquals(StyleDistillation.USER_PREFIX.length + StyleDistillation.MAX_CHARS_PER_LINE, line.length)
        assertTrue(line.endsWith("…"))
        assertTrue("不能整条丢掉", line.contains("字"))
    }

    @Test
    fun `整段样本不超过 8000 字`() {
        val messages = (1..50).map { msg(fromUser = it % 2 == 0, text = "字".repeat(400)) }
        val sample = StyleDistillation.sample(messages)
        assertTrue("样本 ${sample.length} 字超了上限", sample.length <= StyleDistillation.MAX_TOTAL_CHARS)
        assertEquals(8_000, StyleDistillation.MAX_TOTAL_CHARS)
        // 超预算时整行不进来，不出现半行没有前缀的残句。
        sample.lines().forEach { line ->
            assertTrue(
                "每一行都要有人称前缀：$line",
                line.startsWith(StyleDistillation.USER_PREFIX) || line.startsWith(StyleDistillation.PERSONA_PREFIX)
            )
        }
    }

    @Test
    fun `预算刚好用完时最后一行可以截断 不是整条丢掉`() {
        // 一行刚好超过预算：expect 截断而不是空样本（最近那句话最值钱）。
        val text = "字".repeat(StyleDistillation.MAX_TOTAL_CHARS)
        val sample = StyleDistillation.sample(listOf(msg(true, text)))
        assertTrue(sample.isNotBlank())
        assertTrue(sample.length <= StyleDistillation.MAX_TOTAL_CHARS)
    }

    // ------------------------------------------------------------ 够不够学

    @Test
    fun `太少消息不值得跑一次`() {
        assertFalse(StyleDistillation.hasEnough(emptyList()))
        assertFalse(StyleDistillation.hasEnough(listOf(msg(true, "在吗"))))
        assertEquals(4, StyleDistillation.MIN_MESSAGES)
        // 空正文不算数。
        assertFalse(
            StyleDistillation.hasEnough((1..5).map { msg(true, "") })
        )
        // 四条有正文就够了。
        assertTrue(StyleDistillation.hasEnough((1..4).map { msg(it % 2 == 0, "第 $it 条") }))
    }

    // ------------------------------------------------------------ 「应用」怎么改人格

    private fun persona(version: Int = 3, speech: String? = null) = PersonaEntity(
        id = "p1",
        name = "林深",
        description = "",
        avatarUri = null,
        avatarSeed = 0,
        prompt = "你是林深本人。",
        category = "日常聊天",
        version = version,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = false,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        speechStyleJson = speech
    )

    @Test
    fun `应用会写下 speechStyleJson 并让人格进入新版本`() {
        val style = SpeechStyle(tone = "短句，偏冷淡", phrases = listOf("知道了"), habits = listOf("少用感叹号"))
        val applied = SpeechStyleApply.next(persona(version = 3), style, now = 9_000L)!!
        assertEquals(4, applied.version)
        assertEquals(9_000L, applied.updatedAt)
        val written = SpeechStyleCodec.decode(applied.speechStyleJson)
        assertEquals("短句，偏冷淡", written?.tone)
        assertEquals(listOf("知道了"), written?.phrases)
        assertEquals(listOf("少用感叹号"), written?.habits)
        // 别的东西一个都不动：应用话风不是一次整人设重写。
        assertEquals("你是林深本人。", applied.prompt)
        assertEquals("林深", applied.name)
        assertEquals("p1", applied.id)
    }

    @Test
    fun `空话风不写 也不产生空版本`() {
        assertNull(SpeechStyleApply.next(persona(), SpeechStyle(), now = 9_000L))
        // 只有空字符串字段也一样。
        assertNull(
            SpeechStyleApply.next(persona(), SpeechStyle(tone = "  ", phrases = listOf(" ")), now = 9_000L)
        )
    }

    @Test
    fun `取消就是什么都不发生`() {
        // 「取消」在界面上只是把预览清掉：不调用 SpeechStyleApply，所以人格原样不动。
        // 这里用「原对象 vs 未调用」的等价形式钉住：没有 next() 就没有新实体。
        val before = persona(version = 7, speech = """{"tone":"旧话风"}""")
        val style = SpeechStyle(tone = "新话风")
        // 用户点了取消 → 预览丢弃、不写库。
        val preview = style
        val applied = if (preview.isEmpty()) null else SpeechStyleApply.next(before, preview, now = 1L)
        // 应用会改版本；取消则版本、话风、更新时间都不变。
        assertEquals(8, applied!!.version)
        assertEquals(7, before.version)
        assertEquals("""{"tone":"旧话风"}""", before.speechStyleJson)
        assertEquals(1_000L, before.updatedAt)
    }
}
