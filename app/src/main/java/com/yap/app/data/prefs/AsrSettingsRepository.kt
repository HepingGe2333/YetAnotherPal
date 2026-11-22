package com.yap.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yap.app.data.secure.StringCipher
import com.yap.app.data.repo.AsrConfigSource
import com.yap.app.domain.ApiKeyPolicy
import com.yap.app.domain.ApiKeyState
import com.yap.app.domain.AsrAvailability
import com.yap.app.domain.AsrModels
import com.yap.app.domain.AsrSettingsState
import com.yap.app.domain.AsrUrls
import com.yap.app.domain.ResolvedAsrConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 自己的 DataStore（`files/datastore/yap_asr.preferences_pb`）。
 *
 * 为什么**不**塞进 `yap_settings`：
 * - 加密 Key 与「主题/字体」这种可镜像设置不是一类东西，混在一个文件里以后很难审计
 *   「什么离开了设备」；
 * - 备份（[com.yap.app.domain.BackupSettings]）与 `POST /v1/settings` 都是**白名单**序列化，
 *   它们不认识这个文件里的任何键，所以 ASR 的地址/模型/Key 天然不进备份、不上云；
 * - 服务端旧版本不会因为多了一个不认识的 DataStore 而崩——它根本读不到。
 */
private val Context.asrDataStore: DataStore<Preferences> by preferencesDataStore(name = "yap_asr")

/** DataStore 里的键。`internal` 只给同模块的单测用（纯 JVM 拼 Preferences 验证读写）。 */
internal object AsrPreferenceKeys {
    val enabled = booleanPreferencesKey("asr_enabled")
    val baseUrl = stringPreferencesKey("asr_base_url")
    val model = stringPreferencesKey("asr_model")

    /**
     * ASR 专用 Key 的密文（Android Keystore AES-GCM，与 API 配置同一套 [StringCipher]）。
     *
     * 注意：这里**永远不写明文**。备份/服务端白名单都没有这个键，日志也不打它。
     */
    val apiKeyCipher = stringPreferencesKey("asr_api_key_cipher")
}

/** 从 DataStore 读出来的一份 ASR 配置（Key 是密文）。 */
internal data class StoredAsrSettings(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKeyCipher: String?
)

/**
 * Preferences ↔ [StoredAsrSettings] 的纯映射。
 *
 * 单独拆出来的唯一目的：DataStore 本体要 Android Context，纯 JVM 单测跑不了；
 * 但「缺键时默认关 / 默认 DeepSeek 地址 / 默认 whisper-1 / 脏值怎么归一」这些规则
 * 必须能被测试钉住。仓库的读写只做 `edit {}` 与 `data.first()` 两件事。
 */
internal object AsrPreferenceCodec {

    fun read(prefs: Preferences): StoredAsrSettings = StoredAsrSettings(
        enabled = prefs[AsrPreferenceKeys.enabled] ?: false,
        baseUrl = AsrUrls.normalizeBaseUrl(prefs[AsrPreferenceKeys.baseUrl]),
        model = AsrModels.normalize(prefs[AsrPreferenceKeys.model]),
        apiKeyCipher = prefs[AsrPreferenceKeys.apiKeyCipher]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    )

    /** 一次写全（测试往返、以及「重置」时用）。 */
    fun write(prefs: MutablePreferences, next: StoredAsrSettings) {
        prefs[AsrPreferenceKeys.enabled] = next.enabled
        prefs[AsrPreferenceKeys.baseUrl] = next.baseUrl
        prefs[AsrPreferenceKeys.model] = next.model
        if (next.apiKeyCipher.isNullOrBlank()) {
            prefs.remove(AsrPreferenceKeys.apiKeyCipher)
        } else {
            prefs[AsrPreferenceKeys.apiKeyCipher] = next.apiKeyCipher
        }
    }
}

/**
 * Key 的加密/脱敏/可用性判定（纯函数，便于单测用 Fake StringCipher 覆盖）。
 *
 * 三种状态沿用主链路的 [ApiKeyState]：没配 / 可用 / 密文在但解不开。
 * 「解不开」不能当成「没配」：界面要说「重新填一次」，而不是让用户以为 Key 丢了。
 */
internal object AsrKeyCodec {

    /** 明文 → 密文；空白输入返回 null（表示「清掉」）。 */
    fun encrypt(cipher: StringCipher, plain: String?): String? {
        val value = plain?.trim().orEmpty()
        if (value.isEmpty()) return null
        return cipher.encrypt(value)
    }

    fun state(cipher: StringCipher, cipherText: String?): ApiKeyState =
        ApiKeyPolicy.evaluate(cipherText, cipher.decrypt(cipherText))

    /** 界面用它：末 4 位 + 圆点；≤4 位全掩（长度本身也不该泄露）。 */
    fun masked(cipher: StringCipher, cipherText: String?): String {
        val plain = cipher.decrypt(cipherText)?.trim().orEmpty()
        return mask(plain)
    }

    fun mask(plain: String): String = when {
        plain.isEmpty() -> ""
        plain.length <= MASK_VISIBLE_TAIL -> "•".repeat(8)
        else -> "••••" + plain.takeLast(MASK_VISIBLE_TAIL)
    }

    const val MASK_VISIBLE_TAIL = 4
}

/** [StoredAsrSettings] → 设置页状态（含脱敏 Key；**不含明文**）。 */
internal fun StoredAsrSettings.toUiState(cipher: StringCipher): AsrSettingsState {
    val keyState = AsrKeyCodec.state(cipher, apiKeyCipher)
    return AsrSettingsState(
        enabled = enabled,
        baseUrl = baseUrl,
        model = model,
        hasKey = keyState == ApiKeyState.USABLE,
        maskedKey = AsrKeyCodec.masked(cipher, apiKeyCipher),
        keyUnrecoverable = keyState == ApiKeyState.UNRECOVERABLE
    )
}

/** [StoredAsrSettings] → 这一次能不能调用；能调用时带着解密后的 Key。 */
internal fun StoredAsrSettings.toAvailability(cipher: StringCipher): AsrAvailability {
    if (!enabled) return AsrAvailability.Disabled
    return when (val keyState = AsrKeyCodec.state(cipher, apiKeyCipher)) {
        ApiKeyState.MISSING -> AsrAvailability.KeyMissing
        ApiKeyState.UNRECOVERABLE -> AsrAvailability.KeyUnrecoverable
        ApiKeyState.USABLE -> {
            val key = cipher.decrypt(apiKeyCipher).orEmpty().trim()
            if (key.isEmpty()) {
                AsrAvailability.KeyUnrecoverable
            } else {
                AsrAvailability.Ready(ResolvedAsrConfig(baseUrl = baseUrl, model = model, apiKey = key))
            }
        }
    }
}

/**
 * ASR 配置的唯一读写入口（DataStore，没有 Room、没有数据库迁移）。
 *
 * 与 `SettingsRepository` 的关系：互不依赖。ASR 的开关/地址/模型/Key 都留在这台设备上，
 * 不参与「设置镜像到服务端」也不参与备份；「删除全部数据」由设置页显式调用 [resetAll]。
 */
@Singleton
class AsrSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: StringCipher
) : AsrConfigSource {

    /**
     * 设置页展示用状态。
     *
     * 解密是 Keystore 调用（毫秒级），放到 IO 线程做；DataStore 只在新值到来时发射，
     * 所以重组/旋转屏幕不会反复敲 Keystore。
     */
    val state: Flow<AsrSettingsState> = context.asrDataStore.data
        .map { prefs -> AsrPreferenceCodec.read(prefs).toUiState(cipher) }
        .flowOn(Dispatchers.IO)

    /** 当前存储的配置（内部用；Key 仍是密文）。 */
    internal suspend fun current(): StoredAsrSettings =
        AsrPreferenceCodec.read(context.asrDataStore.data.first())

    /** 转写链路用：现在能不能调用（关着 / 没 Key / 解不开 / 就绪）。 */
    override suspend fun availability(): AsrAvailability = current().toAvailability(cipher)

    /**
     * 设置页「测试」按钮用：已经保存的 Key 明文（没有则为 null）。
     *
     * 只在这个场景下解密：用户点了按钮、要拿它去发一次探测请求；按钮之外的路径都走
     * [availability] 得到 [ResolvedAsrConfig]。
     */
    suspend fun storedKey(): String? = cipher.decrypt(current().apiKeyCipher)?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun setEnabled(enabled: Boolean) = edit { prefs ->
        prefs[AsrPreferenceKeys.enabled] = enabled
    }

    suspend fun setBaseUrl(raw: String) = edit { prefs ->
        prefs[AsrPreferenceKeys.baseUrl] = AsrUrls.normalizeBaseUrl(raw)
    }

    suspend fun setModel(raw: String) = edit { prefs ->
        prefs[AsrPreferenceKeys.model] = AsrModels.normalize(raw)
    }

    /** 写新 Key；空白 = 清掉（与 API 配置编辑器的语义一致）。 */
    suspend fun setApiKey(plain: String) = edit { prefs ->
        val encrypted = AsrKeyCodec.encrypt(cipher, plain)
        if (encrypted == null) {
            prefs.remove(AsrPreferenceKeys.apiKeyCipher)
        } else {
            prefs[AsrPreferenceKeys.apiKeyCipher] = encrypted
        }
    }

    suspend fun clearApiKey() = edit { prefs ->
        prefs.remove(AsrPreferenceKeys.apiKeyCipher)
    }

    /** 「删除全部数据」时一起清掉：Key 不应该在用户以为清空了之后还留在设备上。 */
    suspend fun resetAll() = edit { prefs -> prefs.clear() }

    /**
     * 一次写全（设置页「保存」）：
     *
     * - [apiKey] 非空 → 加密写入；
     * - [apiKey] 为空 → **保留**已存的 Key（与 API 编辑器一致：输入框从不回填明文，
     *   留空保存 ≠ 清空 Key，清除要显式点按钮）。
     */
    suspend fun save(baseUrl: String, model: String, apiKey: String) = edit { prefs ->
        prefs[AsrPreferenceKeys.baseUrl] = AsrUrls.normalizeBaseUrl(baseUrl)
        prefs[AsrPreferenceKeys.model] = AsrModels.normalize(model)
        if (apiKey.isNotBlank()) {
            val encrypted = AsrKeyCodec.encrypt(cipher, apiKey)
            if (encrypted == null) {
                prefs.remove(AsrPreferenceKeys.apiKeyCipher)
            } else {
                prefs[AsrPreferenceKeys.apiKeyCipher] = encrypted
            }
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.asrDataStore.edit { prefs ->
            block(prefs)
        }
    }
}
