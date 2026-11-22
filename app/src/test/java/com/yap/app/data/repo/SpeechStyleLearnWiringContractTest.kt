package com.yap.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「从最近聊天学习话风」的接线契约。
 *
 * 规则在 `StyleDistillationTest` / `StyleDistillationRepositoryTest` 里逐条测；这里证明
 * 编辑页真的把三步接上了，而且**隐私**是结构性成立的：
 *
 * 1. 按钮 → `StyleDistillationRepository.distill`（读最近聊天、一次请求），不直接写库；
 * 2. 预览 → 只有点「应用」才调 `PersonaRepository.applySpeechStyle`（写 speechStyleJson + 版本）；
 * 3. 「取消」只清预览，不写库；
 * 4. 样本不落库、不写日志（读库/写库/日志只出现在各自该出现的地方）。
 */
class SpeechStyleLearnWiringContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    private val viewModel by lazy {
        source("src/main/java/com/yap/app/ui/screens/persona/PersonaEditorViewModel.kt")
    }
    private val screen by lazy {
        source("src/main/java/com/yap/app/ui/screens/persona/PersonaEditorScreen.kt")
    }
    private val repository by lazy {
        source("src/main/java/com/yap/app/data/repo/StyleDistillationRepository.kt")
    }
    private val personaRepository by lazy {
        source("src/main/java/com/yap/app/data/repo/PersonaRepository.kt")
    }

    // ------------------------------------------------------------ 取样与预览

    @Test
    fun `按钮调用取样仓库 而不是自己拼样本`() {
        val learn = viewModel.indexOf("fun learnSpeechFromChat()")
        assertTrue("缺少 learnSpeechFromChat", learn >= 0)
        val block = viewModel.substring(learn, minOf(viewModel.length, learn + 2_400))
        assertTrue("要点一次取样", block.contains("styleDistillationRepository.distill("))
        assertTrue("预览放进状态，不写库", block.contains("learnedSpeech = preview"))
        assertFalse("取样这一步不能写人格", block.contains("applySpeechStyle("))
        assertFalse("取样这一步不能写库", block.contains("personaRepository.save("))
    }

    @Test
    fun `没有消息与失败各有各的提示 不伪装成功`() {
        val learn = viewModel.indexOf("fun learnSpeechFromChat()")
        val block = viewModel.substring(learn, minOf(viewModel.length, learn + 2_400))
        assertTrue(block.contains("Distillation.NoMessages"))
        assertTrue(block.contains("Distillation.Failed"))
        assertTrue(block.contains("R.string.guidance_speech_learn_empty"))
        assertTrue(block.contains("R.string.guidance_speech_learn_failed"))
        assertTrue("新建人格还没保存时要说清楚", block.contains("R.string.guidance_speech_learn_need_save"))
    }

    @Test
    fun `预览标明来源 模型总结与本地兜底不一样`() {
        val learn = viewModel.indexOf("fun learnSpeechFromChat()")
        val block = viewModel.substring(learn, minOf(viewModel.length, learn + 2_400))
        assertTrue("来源来自 summarizer 的结果", block.contains("source = distilled.source"))
        // 界面按 source 选文案。
        assertTrue(screen.contains("learned.isHeuristic"))
        assertTrue(screen.contains("R.string.guidance_speech_source_heuristic"))
        assertTrue(screen.contains("R.string.guidance_speech_source_llm"))
    }

    // ------------------------------------------------------------ 应用 / 取消

    @Test
    fun `应用写 speechStyleJson 并记版本`() {
        val apply = viewModel.indexOf("fun applyLearnedSpeech()")
        assertTrue("缺少 applyLearnedSpeech", apply >= 0)
        val block = viewModel.substring(apply, minOf(viewModel.length, apply + 2_600))
        assertTrue("应用要真的写库", block.contains("personaRepository.applySpeechStyle("))
        assertTrue("空话风不写", block.contains("preview.isEmpty"))
        // 写库用的是仓库里的那条路径：bump 版本 + 记一条 revision。
        val method = personaRepository.indexOf("suspend fun applySpeechStyle(")
        assertTrue(method >= 0)
        val repoBlock = personaRepository.substring(method, minOf(personaRepository.length, method + 1_600))
        assertTrue(repoBlock.contains("SpeechStyleApply.next("))
        assertTrue(repoBlock.contains("insertRevision(persona, PersonaRevisionReason.BEFORE_EDIT"))
        assertTrue(repoBlock.contains("insertRevision(persisted, PersonaRevisionReason.EDITED"))
    }

    @Test
    fun `取消只清预览 不写库`() {
        val dismiss = viewModel.indexOf("fun dismissLearnedSpeech()")
        assertTrue(dismiss >= 0)
        val block = viewModel.substring(dismiss, minOf(viewModel.length, dismiss + 400))
        assertTrue(block.contains("learnedSpeech = null"))
        assertFalse("取消不能写库", block.contains("applySpeechStyle("))
        assertFalse("取消不能保存", block.contains("personaRepository.save("))
        assertFalse("取消不能改表单", block.contains("speechTone ="))
    }

    @Test
    fun `编辑页有按钮 预览 应用与取消`() {
        assertTrue(screen.contains("R.string.guidance_speech_learn_from_chat"))
        assertTrue(screen.contains("R.string.guidance_speech_learning"))
        assertTrue(screen.contains("R.string.guidance_speech_learn_preview_title"))
        assertTrue(screen.contains("R.string.guidance_speech_learn_apply"))
        assertTrue(screen.contains("R.string.guidance_speech_learn_cancel"))
        assertTrue(screen.contains("onLearnSpeech"))
        assertTrue(screen.contains("onApplyLearnedSpeech"))
        assertTrue(screen.contains("onDismissLearnedSpeech"))
        // 预览里要能看到四样东西（tone / phrases / habits / avoid）。
        assertTrue(screen.contains("learned.tone"))
        assertTrue(screen.contains("learned.phrases"))
        assertTrue(screen.contains("learned.habits"))
        assertTrue(screen.contains("learned.avoid"))
    }

    // ------------------------------------------------------------ 隐私

    @Test
    fun `样本不落库 不写日志 只交给总结器一次`() {
        // 数据层仓库里没有任何日志调用（样本是聊天原文，写日志等于泄露）。
        assertFalse("仓库里不能打日志", repository.contains("android.util.Log"))
        assertFalse(repository.contains("println("))
        // 取样路径里没有写操作：不 insert / update / 不改记忆与人设。
        assertFalse(repository.contains("insert("))
        assertFalse(repository.contains("update("))
        // 样本只交给 summarizer 一次（`summarize(sample)` 只出现一次）。
        assertEquals(1, Regex(Regex.escape("summarizer.summarize(sample)")).findAll(repository).count())
        // 也没有把 sample 存进字段/缓存。
        assertFalse("样本不能挂到字段上", repository.contains("private var sample"))
        assertFalse(repository.contains("lastSample"))
    }

    @Test
    fun `文案在两种语言里都有`() {
        val zh = File(appRoot(), "src/main/res/values/strings.xml").readText()
        val en = File(appRoot(), "src/main/res/values-en/strings.xml").readText()
        listOf(
            "guidance_speech_learn_from_chat",
            "guidance_speech_learning",
            "guidance_speech_learn_hint",
            "guidance_speech_learn_preview_title",
            "guidance_speech_learn_apply",
            "guidance_speech_learn_cancel",
            "guidance_speech_learn_applied",
            "guidance_speech_learn_empty",
            "guidance_speech_learn_failed",
            "guidance_speech_learn_need_save"
        ).forEach { name ->
            assertTrue("values 缺少 $name", zh.contains("name=\"$name\""))
            assertTrue("values-en 缺少 $name", en.contains("name=\"$name\""))
        }
    }

    @Test
    fun `隐私文案说清楚不会留下记录`() {
        val zh = File(appRoot(), "src/main/res/values/strings.xml").readText()
        val hint = zh.substringAfter("name=\"guidance_speech_learn_hint\">").substringBefore("</string>")
        assertTrue("要告诉用户样本不会留下记录：$hint", hint.contains("不会留下记录") || hint.contains("不保存"))
        assertTrue("要先看再应用：$hint", hint.contains("应用"))
    }
}
