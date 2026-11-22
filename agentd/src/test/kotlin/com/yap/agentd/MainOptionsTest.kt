package com.yap.agentd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动参数的解析（命令行 > 环境变量 > 默认值）。
 *
 * 不启动服务、也不碰真实环境变量：`parseOptions(args, env)` 把 env 当参数传进来，
 * 所以测试不会污染同进程的其它用例。无效参数现在抛 [IllegalArgumentException]（`main`
 * 把它转成退出码 2），因此也能测。
 */
class MainOptionsTest {

    private fun parse(vararg args: String, env: Map<String, String> = emptyMap()): Options =
        parseOptions(arrayOf(*args), env)

    @Test
    fun `defaults match the documented values`() {
        val options = parse()
        assertEquals("127.0.0.1", options.host)
        assertEquals(8788, options.port)
        assertNull(options.token)
        assertEquals("默认 60 请求/分钟", 60, options.rateLimitPerMinute)
        assertEquals("默认突发 30", 30, options.rateBurst)
        assertFalse(options.help)
    }

    @Test
    fun `data directory is configurable by flag and environment`() {
        assertEquals("默认相对工作目录", "./agentd-data", parse().dataDir)
        assertEquals("/tmp/agentd-data", parse("--data", "/tmp/agentd-data").dataDir)
        assertEquals("/tmp/agentd-other", parse("--data=/tmp/agentd-other").dataDir)
        assertEquals(
            "/tmp/agentd-env",
            parse(env = mapOf("YAP_AGENTD_DATA" to "/tmp/agentd-env")).dataDir
        )
        assertEquals(
            "命令行优先于环境变量",
            "/tmp/agentd-cli",
            parse("--data", "/tmp/agentd-cli", env = mapOf("YAP_AGENTD_DATA" to "/tmp/agentd-env")).dataDir
        )
        assertEquals(
            "空白环境变量回退默认值",
            "./agentd-data",
            parse(env = mapOf("YAP_AGENTD_DATA" to "   ")).dataDir
        )
    }

    @Test
    fun `ffmpeg flag and environment are parsed with command line precedence`() {
        assertNull("默认不钉死路径（按 PATH 找 ffmpeg）", parse().ffmpegPath)
        assertEquals("/opt/ffmpeg/bin/ffmpeg", parse("--ffmpeg", "/opt/ffmpeg/bin/ffmpeg").ffmpegPath)
        assertEquals("/usr/local/bin/ffmpeg", parse("--ffmpeg=/usr/local/bin/ffmpeg").ffmpegPath)
        assertEquals(
            "/env/ffmpeg",
            parse(env = mapOf("YAP_FFMPEG" to "/env/ffmpeg")).ffmpegPath
        )
        assertEquals(
            "命令行优先于环境变量",
            "/cli/ffmpeg",
            parse("--ffmpeg", "/cli/ffmpeg", env = mapOf("YAP_FFMPEG" to "/env/ffmpeg")).ffmpegPath
        )
    }

    @Test
    fun `llm flags and environment are parsed with command line precedence`() {
        assertNull("默认没有服务端 LLM 配置", parse().llmBaseUrl)
        assertNull(parse().llmKey)
        assertNull(parse().llmModel)

        val fromEnv = parse(
            env = mapOf(
                "YAP_LLM_BASE_URL" to "https://env.example.com/v1",
                "YAP_LLM_KEY" to "sk-env",
                "YAP_LLM_MODEL" to "env-model"
            )
        )
        assertEquals("https://env.example.com/v1", fromEnv.llmBaseUrl)
        assertEquals("sk-env", fromEnv.llmKey)
        assertEquals("env-model", fromEnv.llmModel)

        val fromFlags = parse(
            "--llm-base-url", "https://cli.example.com/v1",
            "--llm-key=sk-cli",
            "--llm-model", "cli-model",
            env = mapOf("YAP_LLM_BASE_URL" to "https://env.example.com/v1")
        )
        assertEquals("命令行优先", "https://cli.example.com/v1", fromFlags.llmBaseUrl)
        assertEquals("sk-cli", fromFlags.llmKey)
        assertEquals("cli-model", fromFlags.llmModel)
    }

    @Test
    fun `reasoning effort flag and environment are parsed with command line precedence`() {
        assertNull("默认由生成器用 low", parse().reasoningEffort)
        assertEquals(
            "high",
            parse(env = mapOf("YAP_LLM_REASONING_EFFORT" to "HIGH")).reasoningEffort
        )
        assertEquals(
            "命令行优先，并且大小写归一",
            "medium",
            parse("--reasoning-effort", "Medium", env = mapOf("YAP_LLM_REASONING_EFFORT" to "high")).reasoningEffort
        )
        assertEquals("medium", parse("--reasoning-effort=medium").reasoningEffort)

        for (args in listOf(arrayOf("--reasoning-effort", "  "), arrayOf("--reasoning-effort="))) {
            val failure = runCatching { parse(*args) }.exceptionOrNull()
            assertTrue("${args.toList()} 应该解析失败：$failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("--reasoning-effort") == true)
        }
        for (value in listOf("hyper", "0")) {
            val failure = runCatching { parse("--reasoning-effort", value) }.exceptionOrNull()
            assertTrue("$value 应该解析失败：$failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("low / medium / high") == true)
        }
        val fromEnv = runCatching { parse(env = mapOf("YAP_LLM_REASONING_EFFORT" to "hyper")) }.exceptionOrNull()
        assertTrue("环境变量里的坏值也要在启动时失败：$fromEnv", fromEnv is IllegalArgumentException)
    }

    @Test
    fun `blank llm flags are rejected instead of silently ignored`() {
        for (args in listOf(arrayOf("--llm-base-url", "  "), arrayOf("--llm-key="), arrayOf("--llm-model", ""))) {
            val failure = runCatching { parse(*args) }.exceptionOrNull()
            assertTrue("${args.toList()} 应该解析失败：$failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("--llm-") == true)
        }
    }

    @Test
    fun `blank data directory is rejected`() {
        for (args in listOf(arrayOf("--data", "   "), arrayOf("--data="))) {
            val failure = runCatching { parse(*args) }.exceptionOrNull()
            assertTrue("${args.toList()} 应该解析失败：$failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("--data") == true)
        }
    }

    @Test
    fun `rate limit flags are parsed in both spellings`() {
        assertEquals(15, parse("--rate-limit", "15").rateLimitPerMinute)
        assertEquals(15, parse("--rate-limit=15").rateLimitPerMinute)
        assertEquals(5, parse("--rate-burst", "5").rateBurst)
        assertEquals(5, parse("--rate-burst=5").rateBurst)
    }

    @Test
    fun `rate limit zero means disabled`() {
        assertEquals(0, parse("--rate-limit", "0").rateLimitPerMinute)
        assertEquals(0, parse("--rate-limit=0").rateLimitPerMinute)
    }

    @Test
    fun `environment variables are used when the flag is missing`() {
        val options = parse(
            env = mapOf(
                "YAP_AGENTD_TOKEN" to "env-token-0123456789",
                "YAP_AGENTD_RATE_LIMIT" to "7",
                "YAP_AGENTD_RATE_BURST" to "3"
            )
        )
        assertEquals("env-token-0123456789", options.token)
        assertEquals(7, options.rateLimitPerMinute)
        assertEquals(3, options.rateBurst)
    }

    @Test
    fun `command line wins over environment variables`() {
        val options = parse(
            "--rate-limit", "0",
            "--rate-burst", "9",
            "--token", "cli-token-0123456789",
            env = mapOf(
                "YAP_AGENTD_TOKEN" to "env-token-0123456789",
                "YAP_AGENTD_RATE_LIMIT" to "7",
                "YAP_AGENTD_RATE_BURST" to "3"
            )
        )
        assertEquals("cli-token-0123456789", options.token)
        assertEquals(0, options.rateLimitPerMinute)
        assertEquals(9, options.rateBurst)
    }

    @Test
    fun `blank environment values fall back to defaults`() {
        val options = parse(
            env = mapOf(
                "YAP_AGENTD_TOKEN" to "   ",
                "YAP_AGENTD_RATE_LIMIT" to " ",
                "YAP_AGENTD_RATE_BURST" to ""
            )
        )
        assertNull(options.token)
        assertEquals(60, options.rateLimitPerMinute)
        assertEquals(30, options.rateBurst)
    }

    @Test
    fun `help is recognised`() {
        assertTrue(parse("--help").help)
        assertTrue(parse("-h").help)
    }

    @Test
    fun `invalid values are rejected with a readable message`() {
        val cases = listOf(
            arrayOf("--port", "0") to "端口",
            arrayOf("--port", "70000") to "端口",
            arrayOf("--port", "abc") to "--port",
            arrayOf("--rate-limit", "-1") to "负数",
            arrayOf("--rate-burst", "0") to "至少是 1",
            arrayOf("--rate-burst", "x") to "--rate-burst",
            arrayOf("--nope") to "不认识的参数"
        )
        for ((args, expected) in cases) {
            val failure = runCatching { parse(*args) }.exceptionOrNull()
            assertTrue("${args.toList()} 应该解析失败", failure is IllegalArgumentException)
            assertTrue(
                "错误文案要提到 ${expected}，实际：${failure?.message}",
                failure?.message?.contains(expected) == true
            )
        }
    }

    @Test
    fun `invalid environment values are rejected`() {
        for ((name, value) in listOf(
            "YAP_AGENTD_RATE_LIMIT" to "abc",
            "YAP_AGENTD_RATE_BURST" to "0"
        )) {
            val failure = runCatching { parse(env = mapOf(name to value)) }.exceptionOrNull()
            assertTrue("$name=$value 应该解析失败：$failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains(name) == true)
        }
    }
}
