package com.yap.app.domain

import com.yap.app.domain.ExperienceContract.ContextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「# 回复节奏」段的契约测试。
 *
 * 这一段的来历：0.16 起每一句给用户看的话都必须由 `send_message` 发出去，于是「一轮发几条」
 * 从工程决定变成了**模型的决定**。模型很容易为了显得热络而连发、或者把一句话拆成两条，
 * 所以把「像人」的默认值写进提示词并在这里逐条钉住：
 *
 * - 默认只发 1 条；只有确实有新信息才发第 2 条；
 * - 每条 ≤18 字；
 * - 先接住对方情绪再说自己；
 * - 不要为了连发而拆句；
 * - 深夜/累可以更短，但不能只回 4 个字。
 *
 * 位置固定：紧跟「# 引导」之后、人设之前（两段都回答「这一轮怎么说」）。
 * 这一段不在 `spec/persona-guidance/scaffold.json` 里（所以不进 [PromptBuilder.Scaffold]），
 * 也不改 [ExperienceRhythm] 的硬限制（`send_message` 仍然最多 120 字）。
 */
class RhythmPromptContractTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, zone).toInstant().toEpochMilli()

    private fun inputs(language: PromptLanguage = PromptLanguage.ZH) = PromptBuilder.Inputs(
        name = "林深",
        now = now,
        zone = zone,
        language = language
    )

    // ------------------------------------------------------------ 中文人格

    @Test
    fun `中文人格的提示词里有回复节奏段 六条规则逐条出现`() {
        val text = PromptBuilder.system(inputs())
        assertTrue("必须有 # 回复节奏 段：\n$text", text.contains(PromptBuilder.SECTION_RHYTHM))
        PromptBuilder.RHYTHM_ZH.forEach { line ->
            assertTrue("缺少节奏规则：「$line」\n$text", text.contains(line))
        }
        // 五条都带 `- ` 前缀（和别的段的列表格式一致）。
        val block = PromptBuilder.rhythmBlock(inputs())
        assertEquals(
            PromptBuilder.SECTION_RHYTHM + "\n" + PromptBuilder.RHYTHM_ZH.joinToString("\n") { "- $it" },
            block
        )
    }

    @Test
    fun `六条规则覆盖需求里的每一句`() {
        assertEquals(6, PromptBuilder.RHYTHM_ZH.size)
        // 1. 默认只发 1 条；只有确实有新信息才发第 2 条
        assertTrue(PromptBuilder.RHYTHM_ZH[0].contains("默认只发 1 条"))
        assertTrue(PromptBuilder.RHYTHM_ZH[1].contains("第 2 条"))
        // 3. 每条 ≤18 字
        assertTrue(PromptBuilder.RHYTHM_ZH[2].contains("18 个字"))
        // 4. 先接住对方情绪再说自己
        assertTrue(PromptBuilder.RHYTHM_ZH[3].contains("情绪"))
        assertTrue(PromptBuilder.RHYTHM_ZH[3].contains("你自己的事"))
        // 5. 不要为了连发而拆句
        assertTrue(PromptBuilder.RHYTHM_ZH[4].contains("拆"))
        // 6. 深夜/累更短，但不能只回 4 个字
        assertTrue(PromptBuilder.RHYTHM_ZH[5].contains("深夜"))
        assertTrue(PromptBuilder.RHYTHM_ZH[5].contains("四个字"))
    }

    @Test
    fun `节奏段紧跟在引导之后 人设之前`() {
        val text = PromptBuilder.system(inputs())
        val state = text.indexOf(PromptBuilder.Scaffold.SECTION_STATE)
        val guidance = text.indexOf(PromptBuilder.SECTION_GUIDANCE)
        val rhythm = text.indexOf(PromptBuilder.SECTION_RHYTHM)
        val identity = text.indexOf(PromptBuilder.Scaffold.SECTION_IDENTITY)
        assertTrue("状态 → 引导 → 节奏 → 人设：$state/$guidance/$rhythm/$identity", state < guidance)
        assertTrue(guidance < rhythm)
        assertTrue(rhythm < identity)
        // 只出现一次，且不影响边界永远是最后一段。
        assertEquals(1, Regex(Regex.escape(PromptBuilder.SECTION_RHYTHM)).findAll(text).count())
        assertTrue(text.contains(PromptBuilder.Scaffold.SECTION_BOUNDARIES))
        assertEquals(PromptBuilder.Scaffold.SECTION_BOUNDARIES, text.lines().last { it.startsWith("# ") })
    }

    @Test
    fun `18 字是提示词层面的目标 不推翻 send_message 的硬上限`() {
        // 提示词里说 18 字，工具层仍然允许写到 120 字（说不完不该被丢掉）。
        assertTrue(PromptBuilder.RHYTHM_ZH[2].contains("18"))
        assertEquals(120, ExperienceRhythm.MAX_SEND_MESSAGE_CHARS)
        assertEquals("字".repeat(120), ExperienceRhythm.clampSendText("字".repeat(200)))
        // 一轮仍然最多 4 条；节奏段只是建议默认 1 条，不新增第 5 条的通道。
        assertEquals(4, ExperienceRhythm.MAX_MESSAGES_PER_ROUND)
        assertEquals(4, AgentReplyProtocol.MAX_PER_ROUND)
    }

    // ------------------------------------------------------------ 英文人格

    @Test
    fun `英文人格用英文节奏段 不会出现中文规则`() {
        val text = PromptBuilder.system(inputs(language = PromptLanguage.EN))
        assertTrue("英文人格要有英文节奏段：\n$text", text.contains(PromptBuilder.SECTION_RHYTHM_EN))
        PromptBuilder.RHYTHM_EN.forEach { line ->
            assertTrue("缺少英文节奏规则：「$line」\n$text", text.contains(line))
        }
        assertFalse("英文人格不该看到中文节奏段标题", text.contains(PromptBuilder.SECTION_RHYTHM))
        // 段落标题本地化之后仍然是「这一轮怎么说」的位置（引导之后、人设之前）。
        val rhythm = text.indexOf(PromptBuilder.SECTION_RHYTHM_EN)
        assertTrue(text.indexOf("# Guidance") < rhythm)
        // `system()` 不跑 `localizeSectionTitles`（那是聊天主路径做的），所以人设标题仍是脚手架那一份。
        assertTrue(rhythm < text.indexOf(PromptBuilder.Scaffold.SECTION_IDENTITY))
    }

    @Test
    fun `中英文节奏规则一一对应 条数相同`() {
        assertEquals(PromptBuilder.RHYTHM_ZH.size, PromptBuilder.RHYTHM_EN.size)
        PromptBuilder.RHYTHM_EN.forEach { assertFalse("英文条目里不该夹中文：$it", it.any { c -> c.code > 0x4E00 }) }
    }

    // ------------------------------------------------------------ 装配与预算

    @Test
    fun `ContextAssembly 把节奏段放在引导之后 人设之前`() {
        val result = ContextAssembly.assemble(
            ContextAssembly.Inputs(
                state = "现在是周五 20:10。",
                guidance = "# 引导\n这一轮：只聊此刻，不翻旧账",
                rhythm = PromptBuilder.rhythmBlock(inputs()),
                persona = "你是阿澈本人。",
                boundaries = "# 边界\n- 不解释 AI 身份。"
            )
        )
        val system = result.system
        val guidance = system.indexOf("# 引导")
        val rhythm = system.indexOf(PromptBuilder.SECTION_RHYTHM)
        val persona = system.indexOf("你是阿澈本人。")
        assertTrue("引导在节奏之前", guidance in 0..<rhythm)
        assertTrue("节奏在人设之前", rhythm in 0..<persona)
    }

    @Test
    fun `不传节奏段时装配结果与以前完全一致`() {
        val base = ContextAssembly.Inputs(
            state = "现在是周五 20:10。",
            guidance = "# 引导\n这一轮：只聊此刻，不翻旧账",
            persona = "你是阿澈本人。"
        )
        val withoutRhythm = ContextAssembly.assemble(base)
        assertFalse(withoutRhythm.system.contains(PromptBuilder.SECTION_RHYTHM))
        // 状态 / 引导 / 人设：没有节奏段时就是这三段，顺序不变。
        assertEquals(3, withoutRhythm.blocks.size)
    }

    @Test
    fun `节奏段与引导共用状态预算 被压到零预算时也不丢`() {
        // 预算给得很小：状态正文被压到 0，节拍照样保留（它是「这一轮怎么说」，不能因为预算没了就允许连发）。
        val result = ContextAssembly.assemble(
            ContextAssembly.Inputs(
                state = "现在是周五 20:10。",
                guidance = "# 引导\n这一轮：只聊此刻，不翻旧账",
                rhythm = PromptBuilder.rhythmBlock(inputs()),
                persona = "你是阿澈本人。",
                practicalChars = 200
            )
        )
        assertTrue(result.system.contains(PromptBuilder.SECTION_RHYTHM))
        assertTrue(result.system.contains("# 引导"))
        assertTrue("人设永远保留", result.system.contains("你是阿澈本人。"))
        // 预算被占满时历史让位（节奏段算固定块）。
        assertTrue(result.historyCharLimit <= 200)
    }

    @Test
    fun `段标题来自 PromptBuilder 不重复写第二份`() {
        assertEquals("# 回复节奏", PromptBuilder.SECTION_RHYTHM)
        assertEquals("# Reply pacing", PromptBuilder.SECTION_RHYTHM_EN)
        // 只出现在节奏段里：别的段（引导/人设/工具/边界）不会自带这个标题。
        val text = PromptBuilder.system(inputs())
        assertEquals(1, text.lines().count { it == PromptBuilder.SECTION_RHYTHM })
    }

    @Test
    fun `主动思考的基座提示词不带节奏段`() {
        // base() 是给「他自己想事」用的：没有对话搭档，不需要「这一轮发几条」。
        val base = PromptBuilder.base(inputs())
        assertFalse(base.contains(PromptBuilder.SECTION_RHYTHM))
    }

    @Test
    fun `节奏段不改变共享契约的段枚举与预算`() {
        // 这一段是 App 侧的提示词规则，不新增 ContextSegment（agentcore 不归本任务）。
        assertEquals(6, ContextSegment.entries.size)
        assertTrue(
            ContextAssembly.assemble(
                ContextAssembly.Inputs(rhythm = PromptBuilder.rhythmBlock(inputs()))
            ).system.isNotBlank()
        )
    }
}
