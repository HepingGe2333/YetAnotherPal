package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.16 思考强度（`spec/tool-call-agents/README.md` §2）。
 *
 * 三条规则：
 * 1. 主聊天会话默认 `low`；
 * 2. 调用过 `think` 之后、**这一轮后续请求**升到 `high`；
 * 3. 下一轮重新从 `low` 开始（变量在每轮生成里重新初始化），辅助调用不带这个字段。
 *
 * 请求体形状（OpenAI 兼容的 `reasoning_effort`）由 `ReasoningEffortPayloadTest` 断言；
 * 这里钉纯规则与「谁在用」。
 */
class ReasoningEffortTest {

    @Test
    fun `默认是 low 而不是空`() {
        assertEquals("low", ReasoningEffort.DEFAULT)
        assertEquals("low", ReasoningEffort.LOW)
        assertEquals("high", ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.forRequest(thinkCalledThisTurn = false))
    }

    @Test
    fun `这一轮调用过 think 之后升到 high 下一轮回到 low`() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.forRequest(thinkCalledThisTurn = true))
        // 下一轮没有 think：重新问一次就是 low（等价于「结束后恢复」）。
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.forRequest(thinkCalledThisTurn = false))
    }

    @Test
    fun `坏档位不发送`() {
        assertTrue(ReasoningEffort.isValid("low"))
        assertTrue(ReasoningEffort.isValid(" HIGH "))
        assertFalse(ReasoningEffort.isValid("max"))
        assertFalse(ReasoningEffort.isValid(null))
        assertEquals("high", ReasoningEffort.sanitize("HIGH"))
        assertNull(ReasoningEffort.sanitize("off"))
        assertNull(ReasoningEffort.sanitize(null))
    }

    /** 「仅主 LLM 会话使用」：聊天仓库显式传，辅助调用点不许出现这个参数。 */
    @Test
    fun `只有聊天主路径设置思考强度`() {
        val source = appSource("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue("聊天请求要带 reasoningEffort", source.contains("config.inferenceParams(reasoningEffort = reasoningEffort)"))
        assertTrue("默认值来自 ReasoningEffort", source.contains("ReasoningEffort.DEFAULT"))
        assertTrue("think 之后升级", source.contains("ReasoningEffort.forRequest(thinkCalledThisTurn)"))
        assertTrue("只有 think 成功才升级", source.contains("equals(ToolCatalog.THINK, ignoreCase = true) && execution.result.ok"))
        // 变量在每轮 generateAssistant 内初始化：下一轮自然从 low 开始。
        assertTrue(source.contains("var reasoningEffort = ReasoningEffort.DEFAULT"))

        val auxRoots = listOf(
            "src/main/java/com/yap/app/data/repo/ProactiveRepository.kt",
            "src/main/java/com/yap/app/data/repo/HistoryCompressor.kt",
            "src/main/java/com/yap/app/data/repo/EvolutionRepository.kt",
            "src/main/java/com/yap/app/data/repo/MemoryRepository.kt",
            "src/main/java/com/yap/app/data/ai/GuidanceLlm.kt",
            "src/main/java/com/yap/app/data/ai/DefaultCrisisModel.kt",
            "src/main/java/com/yap/app/speech"
        )
        auxRoots.forEach { relative ->
            val file = File(appRoot(), relative)
            if (!file.isFile) return@forEach
            assertFalse(
                "$relative 是辅助调用，不应该跟着聊天带 reasoning_effort",
                file.readText().contains("reasoningEffort")
            )
        }
    }

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun appSource(relative: String): String = File(appRoot(), relative).readText()
}
