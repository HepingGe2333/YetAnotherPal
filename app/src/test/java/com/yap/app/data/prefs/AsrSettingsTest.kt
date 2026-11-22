package com.yap.app.data.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import com.yap.app.data.secure.StringCipher
import com.yap.app.domain.ApiKeyState
import com.yap.app.domain.AsrAvailability
import com.yap.app.domain.AsrDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASR 配置的纯 JVM 单测。
 *
 * DataStore 本体要 Android Context，单测跑不了；所以「默认值 / 脏值归一 / 加密后读写 /
 * 脱敏 / 解不开时怎么回退」这些规则全部放在 [AsrPreferenceCodec] 与 [AsrKeyCodec] 里，
 * 这里直接拿 `preferencesOf` 拼一份 Preferences 来钉住。
 */
class AsrSettingsTest {

    private val cipher = FakeCipher()

    // ---------------------------------------------------------------- 默认值与归一

    @Test
    fun `缺省时默认关闭、DeepSeek 地址、whisper-1`() {
        val stored = AsrPreferenceCodec.read(preferencesOf())

        assertFalse(stored.enabled)
        assertEquals(AsrDefaults.BASE_URL, stored.baseUrl)
        assertEquals("https://api.deepseek.com", stored.baseUrl)
        assertEquals(AsrDefaults.MODEL, stored.model)
        assertEquals("whisper-1", stored.model)
        assertNull(stored.apiKeyCipher)
    }

    @Test
    fun `脏值归一：地址剥多余后缀、模型去空白、密文去空白`() {
        val stored = AsrPreferenceCodec.read(
            preferencesOf(
                AsrPreferenceKeys.enabled to true,
                AsrPreferenceKeys.baseUrl to "  https://api.openai.com/v1/chat/completions///  ",
                AsrPreferenceKeys.model to "   ",
                AsrPreferenceKeys.apiKeyCipher to "  enc:sk-live  "
            )
        )

        assertTrue(stored.enabled)
        assertEquals("https://api.openai.com/v1", stored.baseUrl)
        assertEquals(AsrDefaults.MODEL, stored.model)
        assertEquals("enc:sk-live", stored.apiKeyCipher)
    }

    @Test
    fun `写了再读回完全一致，清空 Key 后不会留下空串密文`() {
        val prefs = mutablePreferencesOf()
        AsrPreferenceCodec.write(
            prefs,
            StoredAsrSettings(
                enabled = true,
                baseUrl = "https://asr.example.com",
                model = "whisper-large-v3",
                apiKeyCipher = "enc:sk-asr"
            )
        )
        val first = AsrPreferenceCodec.read(prefs)
        assertEquals(true, first.enabled)
        assertEquals("https://asr.example.com", first.baseUrl)
        assertEquals("whisper-large-v3", first.model)
        assertEquals("enc:sk-asr", first.apiKeyCipher)

        AsrPreferenceCodec.write(
            prefs,
            StoredAsrSettings(
                enabled = false,
                baseUrl = AsrDefaults.BASE_URL,
                model = AsrDefaults.MODEL,
                apiKeyCipher = null
            )
        )
        val second = AsrPreferenceCodec.read(prefs)
        assertFalse(second.enabled)
        assertNull("清空时应该 remove 而不是写空串", second.apiKeyCipher)
    }

    // ---------------------------------------------------------------- Key：加密 / 掩码 / 三态

    @Test
    fun `空白 Key 不加密，非空 Key 一定会过 cipher`() {
        assertNull(AsrKeyCodec.encrypt(cipher, null))
        assertNull(AsrKeyCodec.encrypt(cipher, "   "))
        assertEquals("enc:sk-live", AsrKeyCodec.encrypt(cipher, "  sk-live  "))
    }

    @Test
    fun `掩码只保留末四位，短 Key 全掩`() {
        assertEquals("••••ghij", AsrKeyCodec.mask("sk-abcdefghij"))
        assertEquals("••••efgh", AsrKeyCodec.mask("abcdefgh"))
        assertEquals("••••••••", AsrKeyCodec.mask("abc"))
        assertEquals("", AsrKeyCodec.mask(""))
        assertEquals("••••efgh", AsrKeyCodec.masked(cipher, "enc:sk-abcdefgh"))
        assertEquals("", AsrKeyCodec.masked(cipher, "broken"))
    }

    @Test
    fun `Key 三态：没配 可用 解不开`() {
        assertEquals(ApiKeyState.MISSING, AsrKeyCodec.state(cipher, null))
        assertEquals(ApiKeyState.USABLE, AsrKeyCodec.state(cipher, "enc:sk-live"))
        assertEquals(
            "密文在但解不开是 UNRECOVERABLE，不能当成没配",
            ApiKeyState.UNRECOVERABLE,
            AsrKeyCodec.state(cipher, "garbage")
        )
    }

    @Test
    fun `设置页状态不含明文 Key`() {
        val state = StoredAsrSettings(
            enabled = true,
            baseUrl = AsrDefaults.BASE_URL,
            model = AsrDefaults.MODEL,
            apiKeyCipher = "enc:sk-abcdefgh"
        ).toUiState(cipher)

        assertTrue(state.hasKey)
        assertEquals("••••efgh", state.maskedKey)
        assertFalse(state.keyUnrecoverable)
        assertFalse("UI 状态里不允许出现明文", state.toString().contains("sk-abcdefgh"))

        val broken = StoredAsrSettings(true, AsrDefaults.BASE_URL, AsrDefaults.MODEL, "garbage").toUiState(cipher)
        assertFalse(broken.hasKey)
        assertTrue(broken.keyUnrecoverable)
        assertEquals("", broken.maskedKey)
    }

    // ---------------------------------------------------------------- 可用性

    @Test
    fun `关闭时不可用，没 Key 时缺 Key，坏密文时不可恢复`() {
        assertEquals(
            AsrAvailability.Disabled,
            StoredAsrSettings(false, AsrDefaults.BASE_URL, AsrDefaults.MODEL, "enc:sk").toAvailability(cipher)
        )
        assertEquals(
            AsrAvailability.KeyMissing,
            StoredAsrSettings(true, AsrDefaults.BASE_URL, AsrDefaults.MODEL, null).toAvailability(cipher)
        )
        assertEquals(
            AsrAvailability.KeyUnrecoverable,
            StoredAsrSettings(true, AsrDefaults.BASE_URL, AsrDefaults.MODEL, "garbage").toAvailability(cipher)
        )
    }

    @Test
    fun `就绪时给出解密后的配置，且 toString 不带 Key`() {
        val availability = StoredAsrSettings(
            enabled = true,
            baseUrl = "https://api.deepseek.com",
            model = "whisper-1",
            apiKeyCipher = "enc:sk-asr-live"
        ).toAvailability(cipher)

        val ready = availability as AsrAvailability.Ready
        assertEquals("https://api.deepseek.com", ready.config.baseUrl)
        assertEquals("whisper-1", ready.config.model)
        assertEquals("sk-asr-live", ready.config.apiKey)
        assertFalse(ready.config.toString().contains("sk-asr-live"))
        assertTrue(ready.config.toString().contains("hasKey=true"))
    }

    /** 模拟 Keystore：前缀标记「能解」，否则解不开。 */
    private class FakeCipher : StringCipher {
        override fun encrypt(plainText: String): String =
            if (plainText.isEmpty()) "" else "enc:$plainText"

        override fun decrypt(encoded: String?): String? =
            encoded?.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")
    }
}
