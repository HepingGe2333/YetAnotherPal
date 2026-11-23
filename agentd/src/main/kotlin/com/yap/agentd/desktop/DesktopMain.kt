package com.yap.agentd.desktop

import com.yap.agentd.AgentdServices
import com.yap.agentd.SendMessageRequest
import com.yap.agentd.SseEvent
import com.yap.agentd.SseSink
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.ServerLlmConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Persona
import com.yap.agentd.store.Session
import java.nio.file.Path
import java.util.UUID

/**
 * 桌面端真人测试工具（无原版 UI）：直接复用 agentd 的存储、提示词、工具回路、记忆、心情、
 * 日记、人格进化与消息队列，只在终端里对话。
 *
 * 用法：
 *   java -cp agentd/build/install/agentd/lib com.yap.agentd.desktop.DesktopMainKt \
 *     --data /tmp/yap-desktop --llm-base-url http://127.0.0.1:11435/v1 \
 *     --llm-key "$KEY" --llm-model campus-chat
 *
 * 命令：/personas /use <n> /new [persona] /state /tools /system /help /quit
 */
fun main(args: Array<String>) {
    val options = parse(args)
    val store = FileStore(Path.of(options.data), seedIfEmpty = true)
    val serverLlm = ServerLlmConfig(
        baseUrl = options.baseUrl,
        apiKey = options.apiKey,
        model = options.model
    )
    val resolver = LlmConfigResolver(store = store, loopback = true, server = serverLlm)
    val access = LlmAccess(LlmClient(), resolver)
    val services = AgentdServices(store = store, llm = access)
    val chat = services.chatService ?: error("没有 chatService（store/llm 未启用）")
    val console = Console(store)
    console.start(chat)
}

private data class Options(
    val data: String = System.getProperty("java.io.tmpdir") + "/yap-desktop",
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null
)

private fun parse(args: Array<String>): Options {
    var data = System.getProperty("java.io.tmpdir") + "/yap-desktop"
    var base: String? = null
    var key: String? = null
    var model: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--data" -> data = args.getOrNull(++i) ?: error("--data 需要目录")
            "--llm-base-url" -> base = args.getOrNull(++i)
            "--llm-key" -> key = args.getOrNull(++i)
            "--llm-model" -> model = args.getOrNull(++i)
            "--help", "-h" -> {
                println("用法: --data <dir> [--llm-base-url URL --llm-key KEY --llm-model MODEL]")
                kotlin.system.exitProcess(0)
            }
            else -> error("未知参数：${args[i]}")
        }
        i++
    }
    return Options(data, base, key, model)
}

private class Console(private val store: FileStore) {
    private var persona: Persona? = null
    private var session: Session? = null

    fun start(chat: com.yap.agentd.ChatService) {
        choose(store.personas().firstOrNull(), createSession = true)
        println("Yap desktop · 输入 /help 查看命令；直接输入消息开始聊天。")
        while (true) {
            print("\n你> ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isEmpty()) continue
            if (line.startsWith("/")) {
                if (handleCommand(line, chat)) break
                continue
            }
            send(chat, line)
        }
    }

    private fun handleCommand(line: String, chat: com.yap.agentd.ChatService): Boolean {
        val parts = line.split(Regex("\\s+"), limit = 2)
        when (parts[0]) {
            "/quit", "/exit" -> return true
            "/help" -> println("""
                /personas      列出人格
                /use <n|id>    切换人格（新建会话）
                /new [persona] 新建会话
                /state         当前人格/会话/心情/活动
                /tools         当前可用工具
                /system        打印最近一次系统提示词（如果服务端已生成）
                /quit          退出
            """.trimIndent())
            "/personas" -> store.personas().forEachIndexed { index, p ->
                println("${index + 1}. ${p.id}  ${p.name}")
            }
            "/use" -> {
                val key = parts.getOrNull(1) ?: return false
                val target = key.toIntOrNull()?.let { store.personas().getOrNull(it - 1) }
                    ?: store.personas().firstOrNull { it.id == key }
                choose(target, createSession = true)
            }
            "/new" -> {
                val target = parts.getOrNull(1)?.let { key -> store.personas().firstOrNull { it.id == key || it.name == key } }
                    ?: persona
                choose(target, createSession = true)
            }
            "/state" -> {
                val p = persona
                val s = session
                if (p == null || s == null) println("还没有选中人格/会话") else {
                    println("人格: ${p.name} (${p.id})")
                    println("会话: ${s.title} (${s.id})  消息数: ${s.messageCount}")
                    val mood = store.mood(p.id)
                    println("心情: ${mood?.label ?: "无"}  ${mood?.valence ?: ""}/${mood?.arousal ?: ""}/${mood?.dominance ?: ""}")
                    println("活动: ${p.activityJson ?: "无"}")
                }
            }
            "/tools" -> println("工具由服务端 ServerToolCatalog 生成；聊天时模型会按需调用。")
            "/system" -> println("系统提示词在每轮请求内部生成（含状态/人设/工具/记忆/节奏），不落库。")
            else -> println("未知命令：${parts[0]}")
        }
        return false
    }

    private fun choose(target: Persona?, createSession: Boolean) {
        val p = target ?: run { println("没有可用人格"); return }
        persona = p
        val existing = if (createSession) null else store.sessions().firstOrNull { it.personaId == p.id }
        session = existing ?: store.ensureSessionForPersona(p.id, "桌面测试").second
        println("使用人格 ${p.name} · 会话 ${session?.title}")
    }

    private fun send(chat: com.yap.agentd.ChatService, text: String) {
        val s = session ?: return
        val started = System.currentTimeMillis()
        var printed = false
        chat.send(
            s.id,
            SendMessageRequest(clientMessageId = UUID.randomUUID().toString(), content = text),
            SseSink { event ->
                when (event) {
                    is SseEvent.Delta -> {
                        if (!printed) { print("他> "); printed = true }
                        print(event.text)
                        System.out.flush()
                    }
                    is SseEvent.MessageCreated -> {
                        val m = event.message
                        if (m.role == "ASSISTANT" && m.content.isNotBlank() && !printed) {
                            println("他> ${m.content}")
                            printed = true
                        }
                    }
                    is SseEvent.Tool -> println("\n[工具] ${event.toolName} ${event.status} ${event.summary ?: ""}")
                    is SseEvent.Error -> println("\n[错误] ${event.code}: ${event.message}")
                    is SseEvent.Done -> {
                        if (printed) println()
                        if (event.silent) println("（已读不回）")
                        println("  · ${System.currentTimeMillis() - started}ms, tokens=${event.usage.promptTokens + event.usage.completionTokens}")
                    }
                    else -> Unit
                }
            }
        )
        // 重新读一次，保证 /state 看到的是最新心情/活动/消息数。
        session = store.session(s.id)
        persona = persona?.let { store.personas().firstOrNull { p -> p.id == it.id } }
    }
}
