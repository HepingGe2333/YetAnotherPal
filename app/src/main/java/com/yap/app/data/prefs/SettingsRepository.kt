package com.yap.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "yap_settings")

/**
 * 本机偏好的唯一入口。
 *
 * 1.9 删掉了记忆开关、读消息节奏、额度/单价、每日主动次数这几组键。
 * 这里**刻意不保留**它们的旧 key：即使老用户的 DataStore 里还躺着这些值，
 * 也不会再被读出来、更不会被界面写回去。数据迁移不需要清它们——它们已经没人认识。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    /** M5：白名单设置镜像到服务端（失败会抛给界面；本地已落盘，不回滚）。 */
    private val serverWrites: com.yap.app.data.remote.ServerWriteGateway
) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val accentColor = longPreferencesKey("accent_color")
        val fontScale = floatPreferencesKey("font_scale")
        val bubbleStyle = stringPreferencesKey("bubble_style")
        val animations = booleanPreferencesKey("animations")
        val chatBackgroundColor = longPreferencesKey("chat_background_color")
        val chatBackgroundImageUri = stringPreferencesKey("chat_background_image")
        val userAvatarUri = stringPreferencesKey("user_avatar_uri")
        val userAvatarLocalUri = stringPreferencesKey("user_avatar_local_uri")
        val activePersonaId = stringPreferencesKey("active_persona_id")
        val haptics = booleanPreferencesKey("haptics")
        val seeded = booleanPreferencesKey("initial_personas_seeded")
        /**
         * 一次性迁移标记（0.12 续 7）：把「从没配置过睡眠」的旧默认关掉，只跑一次。
         *
         * 它和 [seeded] 一样是**内部状态**，设置页里没有它；「删除全部数据」会把它一起清掉，
         * 下次启动再跑一遍也无害 —— 那时库里只有刚种下的新人格（本来就不睡）。
         */
        val sleepDefaultsMigratedV1 = booleanPreferencesKey("sleep_defaults_migrated_v1")
        val agentLoop = booleanPreferencesKey("agent_loop")
        val autoTranslatePersona = booleanPreferencesKey("auto_translate_persona")
        val backgroundTipDismissed = booleanPreferencesKey("background_tip_dismissed")
        // 心跳的内部状态：只用来让冲动曲线跨重启接着走，不对外暴露（设置页里没有它）。
        val heartbeatLastResetAt = longPreferencesKey("heartbeat_last_reset_at")
        val heartbeatLastFiredAt = longPreferencesKey("heartbeat_last_fired_at")

        // 人格进化评估的游标（内部状态，界面里看不到）。
        val evolutionPersonaId = stringPreferencesKey("evolution_persona_id")
        val evolutionUserMessageCount = intPreferencesKey("evolution_user_message_count")
        val evolutionLastRunAt = longPreferencesKey("evolution_last_run_at")

        // 自动情绪判定的游标（内部状态，界面里看不到）：每 4 条用户消息 / 24 小时一次。
        val moodJudgePersonaId = stringPreferencesKey("mood_judge_persona_id")
        val moodJudgeUserMessageCount = intPreferencesKey("mood_judge_user_message_count")
        val moodJudgeLastRunAt = longPreferencesKey("mood_judge_last_run_at")

        // 「已读不回」连续轮数（内部状态）：连续 ≤2 轮，回一句就清零。
        val staySilentPersonaId = stringPreferencesKey("stay_silent_persona_id")
        val staySilentStreak = intPreferencesKey("stay_silent_streak")

        // 更新检查（0.14.2）：开关是本机偏好；后两个是节流用的内部状态。
        val updateCheckEnabled = booleanPreferencesKey("update_check_enabled")
        val updateIncludePrereleases = booleanPreferencesKey("update_include_prereleases")
        val updateLastCheckedAt = longPreferencesKey("update_last_checked_at")
        val updateLastNotifiedVersion = stringPreferencesKey("update_last_notified_version")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accentColor = prefs[Keys.accentColor] ?: 0xFFC76F4E,
            fontScale = (prefs[Keys.fontScale] ?: 1f).coerceIn(0.85f, 1.3f),
            bubbleStyle = prefs[Keys.bubbleStyle]?.let { runCatching { BubbleStyle.valueOf(it) }.getOrNull() } ?: BubbleStyle.SOFT,
            animationsEnabled = prefs[Keys.animations] ?: true,
            chatBackgroundColor = prefs[Keys.chatBackgroundColor],
            chatBackgroundImageUri = prefs[Keys.chatBackgroundImageUri],
            userAvatarUri = prefs[Keys.userAvatarUri],
            userAvatarLocalUri = prefs[Keys.userAvatarLocalUri],
            activePersonaId = prefs[Keys.activePersonaId],
            hapticsEnabled = prefs[Keys.haptics] ?: true,
            agentLoopEnabled = prefs[Keys.agentLoop] ?: true,
            autoTranslatePersona = prefs[Keys.autoTranslatePersona] ?: true,
            backgroundTipDismissed = prefs[Keys.backgroundTipDismissed] ?: false,
            heartbeatLastResetAt = prefs[Keys.heartbeatLastResetAt]?.takeIf { it > 0L } ?: 0L,
            heartbeatLastFiredAt = prefs[Keys.heartbeatLastFiredAt]?.takeIf { it > 0L },
            evolutionPersonaId = prefs[Keys.evolutionPersonaId],
            evolutionUserMessageCount = prefs[Keys.evolutionUserMessageCount] ?: 0,
            evolutionLastRunAt = prefs[Keys.evolutionLastRunAt] ?: 0L,
            moodJudgePersonaId = prefs[Keys.moodJudgePersonaId],
            moodJudgeUserMessageCount = prefs[Keys.moodJudgeUserMessageCount] ?: 0,
            moodJudgeLastRunAt = prefs[Keys.moodJudgeLastRunAt] ?: 0L,
            staySilentPersonaId = prefs[Keys.staySilentPersonaId],
            staySilentStreak = (prefs[Keys.staySilentStreak] ?: 0).coerceIn(0, STAY_SILENT_MAX_STREAK),
            updateCheckEnabled = prefs[Keys.updateCheckEnabled] ?: true,
            updateIncludePrereleases = prefs[Keys.updateIncludePrereleases] ?: false,
            updateLastCheckedAt = prefs[Keys.updateLastCheckedAt]?.takeIf { it > 0L } ?: 0L,
            updateLastNotifiedVersion = prefs[Keys.updateLastNotifiedVersion]
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = editAndSync { it[Keys.themeMode] = mode.name }
    suspend fun setAccentColor(argb: Long) = editAndSync { it[Keys.accentColor] = argb }
    suspend fun setFontScale(scale: Float) = editAndSync { it[Keys.fontScale] = scale.coerceIn(0.85f, 1.3f) }
    suspend fun setBubbleStyle(style: BubbleStyle) = editAndSync { it[Keys.bubbleStyle] = style.name }
    suspend fun setAnimationsEnabled(enabled: Boolean) = editAndSync { it[Keys.animations] = enabled }
    suspend fun setChatBackgroundColor(argb: Long?) = editAndSync {
        if (argb == null) it.remove(Keys.chatBackgroundColor) else it[Keys.chatBackgroundColor] = argb
    }
    suspend fun setChatBackgroundImageUri(uri: String?) = edit {
        if (uri == null) it.remove(Keys.chatBackgroundImageUri) else it[Keys.chatBackgroundImageUri] = uri
    }

    /**
     * 「我的头像」只存一个 App 私有目录里的文件路径。
     *
     * 不加数据库迁移：头像不是人格/聊天/记忆的一部分，也没有历史版本要保留；
     * 清空数据（[resetAll]）时它跟着清掉，符合「这台手机上的你重新开始」的语义。
     */
    suspend fun setUserAvatarUri(uri: String?) = edit {
        if (uri == null) it.remove(Keys.userAvatarUri) else it[Keys.userAvatarUri] = uri
    }

    /**
     * 一次写两个引用（服务端引用 + 本机缓存副本），只写本地。
     *
     * 「我的头像」上传链路（[com.yap.app.data.remote.UserAvatarCoordinator]）用它在
     * 「上传成功之后、推送之前」原子地落盘；推送失败时调用方会再写回旧值。
     */
    suspend fun setUserAvatar(serverRef: String?, localRef: String?) = edit {
        if (serverRef == null) it.remove(Keys.userAvatarUri) else it[Keys.userAvatarUri] = serverRef
        if (localRef == null) it.remove(Keys.userAvatarLocalUri) else it[Keys.userAvatarLocalUri] = localRef
    }

    /** 把当前白名单设置镜像到服务端（头像链路在写完引用之后显式调用；失败抛异常）。 */
    suspend fun pushServerSettings() {
        if (!serverWrites.isEnabled()) return
        serverWrites.pushSettings(settings.first()).getOrThrow()
    }

    /**
     * 把服务端 `GET /v1/settings` 的白名单值写回本地（bootstrap 全量拉取 / 新客户端首连）。
     *
     * 只覆盖**响应里确实出现的键**：服务端版本较老时缺 `userAvatarUri`，不能把本机头像清掉。
     * 不触发 `POST /v1/settings`（避免「拉下来又立刻推回去」的回路），也不改开关本身的
     * 服务端连接配置（那不属于白名单）。
     */
    suspend fun applyServerSettings(values: JsonObject) {
        if (values.isEmpty()) return
        val parsed = com.yap.app.data.remote.ServerSettingsWrite.read(values)
        context.settingsDataStore.edit { prefs ->
            parsed.themeMode?.let { mode ->
                runCatching { ThemeMode.valueOf(mode) }.getOrNull()?.let { prefs[Keys.themeMode] = it.name }
            }
            parsed.accentColor?.let { prefs[Keys.accentColor] = it }
            parsed.fontScale?.let { prefs[Keys.fontScale] = it.coerceIn(0.85f, 1.3f) }
            parsed.bubbleStyle?.let { style ->
                runCatching { BubbleStyle.valueOf(style) }.getOrNull()?.let { prefs[Keys.bubbleStyle] = it.name }
            }
            parsed.animationsEnabled?.let { prefs[Keys.animations] = it }
            parsed.hapticsEnabled?.let { prefs[Keys.haptics] = it }
            if (parsed.clearChatBackground) {
                prefs.remove(Keys.chatBackgroundColor)
            } else {
                parsed.chatBackgroundColor?.let { prefs[Keys.chatBackgroundColor] = it }
            }
            parsed.agentLoopEnabled?.let { prefs[Keys.agentLoop] = it }
            if (parsed.clearActivePersona) {
                prefs.remove(Keys.activePersonaId)
            } else {
                parsed.activePersonaId?.takeIf { it.isNotBlank() }?.let { prefs[Keys.activePersonaId] = it }
            }
            parsed.autoTranslatePersona?.let { prefs[Keys.autoTranslatePersona] = it }
            when {
                parsed.clearUserAvatar -> {
                    // 服务端明确清空：本机缓存副本也一起清，否则老文件会被当成「还在引用」永不回收。
                    prefs.remove(Keys.userAvatarUri)
                    prefs.remove(Keys.userAvatarLocalUri)
                }
                !parsed.userAvatarUri.isNullOrBlank() -> {
                    // 服务端引用统一存规范形态；本机缓存副本（userAvatarLocalUri）不动：
                    // 离线时仍能用旧图兜底，直到用户下次换头像。
                    val ref = com.yap.app.data.remote.ServerBootstrapMapping.avatarRef(parsed.userAvatarUri)
                    if (ref != null) prefs[Keys.userAvatarUri] = ref
                }
            }
        }
    }
    suspend fun setActivePersonaId(id: String?) = editAndSync {
        if (id == null) it.remove(Keys.activePersonaId) else it[Keys.activePersonaId] = id
    }
    suspend fun setHapticsEnabled(enabled: Boolean) = editAndSync { it[Keys.haptics] = enabled }

    /** 关掉「自动翻译人设」：之后一律用原文，缓存保留（重新打开时还能直接命中）。 */
    suspend fun setAutoTranslatePersona(enabled: Boolean) = editAndSync { it[Keys.autoTranslatePersona] = enabled }

    /**
     * 清空所有设置。
     *
     * [preserveSeedMarker] 默认 `true`：普通「重置设置」不应该把示例人格再播回来。
     * 「删除全部数据」传 `false` —— 那是「这台手机上的你重新开始」，与 iOS 对齐：
     * 清完之后按当前语言重新播种三个人格，而不是得到一个空白 App。
     */
    suspend fun resetAll(preserveSeedMarker: Boolean = true) {
        val seeded = context.settingsDataStore.data.first()[Keys.seeded] ?: false
        context.settingsDataStore.edit {
            it.clear()
            if (preserveSeedMarker && seeded) it[Keys.seeded] = true
        }
    }

    suspend fun hasSeededInitialPersonas(): Boolean =
        context.settingsDataStore.data.first()[Keys.seeded] ?: false

    suspend fun markInitialPersonasSeeded() {
        context.settingsDataStore.edit { it[Keys.seeded] = true }
    }

    /** 0.12 续 7 的一次性迁移标记：跑过一次之后不再动任何人的睡眠设置。 */
    suspend fun hasMigratedSleepDefaults(): Boolean =
        context.settingsDataStore.data.first()[Keys.sleepDefaultsMigratedV1] ?: false

    suspend fun markSleepDefaultsMigrated() {
        context.settingsDataStore.edit { it[Keys.sleepDefaultsMigratedV1] = true }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    /**
     * 本地先落盘，再把**白名单**设置镜像到服务端（M5）。
     *
     * 顺序刻意是「本地先写」：设置是设备偏好，服务端不可达时用户仍要能改主题；
     * 但服务端失败会作为异常抛给设置页（用户应该知道「这台手机以外没同步上」），
     * 不会静默吞掉。非白名单项（聊天背景图、我的头像、引导状态）不触发网络请求。
     */
    private suspend fun editAndSync(
        block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        edit(block)
        if (!serverWrites.isEnabled()) return
        serverWrites.pushSettings(settings.first()).getOrThrow()
    }

    suspend fun setAgentLoopEnabled(enabled: Boolean) = editAndSync { it[Keys.agentLoop] = enabled }

    suspend fun setBackgroundTipDismissed(dismissed: Boolean) =
        edit { it[Keys.backgroundTipDismissed] = dismissed }

    /**
     * 记下他的冲动曲线走到哪儿了（内部状态，没有对应的界面开关）。
     *
     * 只由心跳那一层调用：一次互动或一次真的冲动之后重置概率时钟。
     * 传 0 / null 表示「当没有过」，比如清空数据之后。
     */
    suspend fun setHeartbeatState(lastResetAt: Long, lastFiredAt: Long?) = edit {
        if (lastResetAt > 0L) it[Keys.heartbeatLastResetAt] = lastResetAt else it.remove(Keys.heartbeatLastResetAt)
        if (lastFiredAt != null && lastFiredAt > 0L) {
            it[Keys.heartbeatLastFiredAt] = lastFiredAt
        } else {
            it.remove(Keys.heartbeatLastFiredAt)
        }
    }

    /**
     * 推进人格进化评估的游标（内部状态，没有对应的界面）。
     *
     * 存「哪个会话已经是第几条用户消息、上次评估是什么时候」，重启后接着算，
     * 避免每次冷启动都以为「已经过了 24 小时」而立刻打一次模型。
     */
    suspend fun setEvolutionCursor(personaId: String, userMessageCount: Int, at: Long) = edit {
        it[Keys.evolutionPersonaId] = personaId
        it[Keys.evolutionUserMessageCount] = userMessageCount.coerceAtLeast(0)
        it[Keys.evolutionLastRunAt] = at.coerceAtLeast(0L)
    }

    /**
     * 推进自动情绪判定的游标（内部状态，没有对应的界面）。
     *
     * 与进化游标同样的道理：存「第几条用户消息、上次判定是什么时候」，重启后接着算，
     * 避免每次冷启动都以为「已经过了 24 小时」而立刻打一次模型。
     */
    suspend fun setMoodJudgeCursor(personaId: String, userMessageCount: Int, at: Long) = edit {
        it[Keys.moodJudgePersonaId] = personaId
        it[Keys.moodJudgeUserMessageCount] = userMessageCount.coerceAtLeast(0)
        it[Keys.moodJudgeLastRunAt] = at.coerceAtLeast(0L)
    }

    /**
     * 记下「已读不回」走到第几轮（内部状态）。
     *
     * `personaId` 与人格绑定：换人格时如果对不上就从 0 开始算，
     * 免得 A 的沉默轮到 B 头上被强制打断。
     */
    suspend fun setStaySilentStreak(personaId: String, streak: Int) = edit {
        it[Keys.staySilentPersonaId] = personaId
        it[Keys.staySilentStreak] = streak.coerceIn(0, STAY_SILENT_MAX_STREAK)
    }

    /** 他回了一句（落了一条 assistant 消息）：连续沉默的计数清零。 */
    suspend fun resetStaySilentStreak() = edit {
        it.remove(Keys.staySilentStreak)
        it.remove(Keys.staySilentPersonaId)
    }

    /**
     * 自动检查更新的开关。
     *
     * 只写本地、不镜像到服务端：这是「这台设备要不要联网查新版本」的偏好，
     * 不是跟「他」一起走的设置。
     */
    suspend fun setUpdateCheckEnabled(enabled: Boolean) = edit { it[Keys.updateCheckEnabled] = enabled }

    /** 是否把预发布版本也算作「有新版本」。 */
    suspend fun setUpdateIncludePrereleases(enabled: Boolean) =
        edit { it[Keys.updateIncludePrereleases] = enabled }

    /**
     * 记下这次检查的时间与「已经提示过的版本」（内部状态，24h 节流与去重用）。
     *
     * [lastCheckedAt] <= 0 表示清掉（例如「删除全部数据」）。
     */
    suspend fun setUpdateCheckState(lastCheckedAt: Long, lastNotifiedVersion: String?) = edit {
        if (lastCheckedAt > 0L) it[Keys.updateLastCheckedAt] = lastCheckedAt else it.remove(Keys.updateLastCheckedAt)
        if (lastNotifiedVersion.isNullOrBlank()) {
            it.remove(Keys.updateLastNotifiedVersion)
        } else {
            it[Keys.updateLastNotifiedVersion] = lastNotifiedVersion
        }
    }

    companion object {
        /** 连续「已读不回」的上限（`spec/mood/README.md` §4：不要连续使用超过 2 轮）。 */
        const val STAY_SILENT_MAX_STREAK = 2
    }
}
