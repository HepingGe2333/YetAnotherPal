package com.yap.app.security

import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.remote.ServerSettingsWrite
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ASR 配置的隐私守卫（0.15）。
 *
 * 需求只有一句「Key 加密 / 至少不进日志、不进备份」，但这句话对应四条互相独立的出逃路径，
 * 任何一条漏掉都不是「少一点隐私」，而是「用户的语音转写 Key 被别人拿走」：
 *
 * 1. **备份**：`BackupSettings` / `BackupApiConfig` 是白名单序列化，必须完全不认识 ASR；
 * 2. **服务端设置镜像**：`ServerSettingsWrite.WHITELIST` 不能出现 ASR 字段；
 * 3. **日志**：`AsrClient` 的失败日志不能出现请求头（Bearer Key 在里面）；
 * 4. **落盘**：DataStore 里只能是密文键，且用独立文件（不跟 `yap_settings` 混）。
 *
 * 这里用源码扫描把 1–4 钉住；行为层面的「日志里没有 Key」由
 * `AsrClientTest.401 归类为 Key 被拒绝，且日志与 detail 都不含 Key` 覆盖。
 */
class AsrPrivacyGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("找不到主源码目录（工作目录=${File("").absolutePath}）：${candidates.joinToString(" / ")}")

    private val mainSourceDir: File by lazy {
        locate("src/main/java", "app/src/main/java", "../app/src/main/java")
    }

    private fun source(relative: String): String =
        File(mainSourceDir, "com/yap/app/$relative").readText()

    @Test
    fun `备份格式完全不认识 ASR 字段`() {
        val backup = source("domain/BackupCodec.kt").lowercase()
        assertFalse(
            "BackupCodec 里出现了 asr：备份一旦认识它，Key 或地址就可能被一起带走",
            backup.contains("asr")
        )
        val settings = source("data/prefs/AppSettings.kt").lowercase()
        assertFalse("AppSettings 不该混进 ASR（它会被备份与镜像）", settings.contains("asr"))
    }

    @Test
    fun `服务端设置白名单没有 ASR`() {
        assertTrue(
            "服务端设置白名单不能有 ASR 键：${ServerSettingsWrite.WHITELIST}",
            ServerSettingsWrite.WHITELIST.none { it.contains("asr", ignoreCase = true) }
        )
        // payload 里同样不能出现（白名单与 payload 是两份要同步的清单）。
        val payload = ServerSettingsWrite.payload(AppSettings())
        assertFalse(payload.toString().contains("asr", ignoreCase = true))
    }

    @Test
    fun `ASR 的 DataStore 是独立文件且只存密文键`() {
        val repository = source("data/prefs/AsrSettingsRepository.kt")

        assertTrue(
            "ASR 必须用独立 DataStore，不能写进 yap_settings",
            repository.contains("preferencesDataStore(name = \"yap_asr\")")
        )
        assertFalse(repository.contains("preferencesDataStore(name = \"yap_settings\")"))
        assertTrue("Key 只能以密文键落盘", repository.contains("stringPreferencesKey(\"asr_api_key_cipher\")"))
        assertFalse(
            "不允许出现明文 Key 的偏好键",
            repository.contains("stringPreferencesKey(\"asr_api_key\")")
        )
        assertTrue(
            "写 Key 必须经过 cipher.encrypt",
            repository.contains("AsrKeyCodec.encrypt(cipher")
        )
    }

    @Test
    fun `ASR 客户端日志不打印请求头或 Key`() {
        val client = source("network/AsrClient.kt")
        val logLines = client.lines().filter { it.contains("println(\"[asr]") }
        assertTrue("找不到 ASR 日志行，守卫会失效", logLines.isNotEmpty())
        logLines.forEach { line ->
            assertFalse("日志不能带 Bearer/Authorization：$line", line.contains("Bearer") || line.contains("Authorization"))
            assertFalse("日志不能打印 apiKey：$line", line.contains("apiKey"))
        }
        assertTrue(
            "失败日志必须用 redactUrl 脱敏端点（query 里可能藏着 api_key）",
            logLines.all { it.contains("redactUrl") }
        )
        assertTrue(
            "错误体摘要必须先过 scrub，再截断到 200 字符",
            client.contains("scrub(body.take(AsrDefaults.MAX_LOGGED_BODY_CHARS), apiKey)")
        )
    }

    @Test
    fun `系统备份关闭，ASR 的 DataStore 不会被自动备份`() {
        val manifest = File(mainSourceDir, "../AndroidManifest.xml").readText()
        assertTrue("allowBackup 必须是 false", manifest.contains("android:allowBackup=\"false\""))

        val rules = File(mainSourceDir, "../res/xml/data_extraction_rules.xml").readText()
        assertTrue(
            "data_extraction_rules 必须排除 file/（DataStore 落在 files/datastore）：$rules",
            rules.contains("domain=\"file\"")
        )
        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
    }

    @Test
    fun `附件里的转写文本不会包含 Key 字段`() {
        val attachments = source("domain/Attachments.kt")
        assertFalse(
            "MessageAttachment 只该存转写文本，不该存凭证",
            attachments.contains("apiKey") || attachments.contains("api_key")
        )
    }
}
