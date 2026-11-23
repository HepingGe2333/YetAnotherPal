package com.yap.app.data.prefs

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class BubbleStyle { SOFT, COMPACT, OUTLINED }

/**
 * 本机偏好。
 *
 * 1.9 把「调机器人」的那些旋钮从产品里拿掉了：
 * 记忆四开关、读消息节奏、月度额度 / 单价、每天主动几次，全都不再存在。
 * 记忆是内置行为；节奏与主动程度由人格（`proactiveLevel` 等）和模型自己的
 * `defer_reply` 决定；用量页只统计 token，不拦截、不显示钱。
 *
 * 这里剩下的每一项用户都能在设置页看到（[heartbeatLastResetAt] 除外，那是内部状态）。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: Long = 0xFFC76F4E,
    val fontScale: Float = 1f,
    val bubbleStyle: BubbleStyle = BubbleStyle.SOFT,
    val animationsEnabled: Boolean = true,
    val chatBackgroundColor: Long? = null,
    val chatBackgroundImageUri: String? = null,
    /**
     * 「我的头像」：App 私有目录里的 `file://` 路径，null = 默认（一枚安静的人形图标）。
     *
     * 它**不进数据库**（不需要为新功能加迁移），也**不进备份**：图片文件本来就不跟着
     * 备份走，换手机之后重新选一张就好。
     */
    val userAvatarUri: String? = null,
    /**
     * 「我的头像」的**本机缓存副本**（服务端模式才有值）。
     *
     * 服务端模式下 [userAvatarUri] 存 `/v1/media/<id>`，渲染走带鉴权的媒体组件；
     * 但服务端不可达（离线/关掉服务端模式）时那张图取不到，所以上传成功后再留一份本地文件，
     * 渲染层用它兜底。本地模式下它恒为 null（此时 [userAvatarUri] 本身就是本地文件）。
     */
    val userAvatarLocalUri: String? = null,
    val activePersonaId: String? = null,
    val hapticsEnabled: Boolean = true,
    /** 让他常驻后台继续「活着」（前台服务 + 自主循环）。 */
    val agentLoopEnabled: Boolean = true,
    /**
     * 打开聊天 / 生成主动消息前，按需把人格提示词翻译成软件当前语言
     * （`spec/persona-translation/README.md` §6，默认开）。
     *
     * 关掉之后一律用原文：对「我就是要用中文设定配英文界面」的人，翻译只是多余的一次请求。
     */
    val autoTranslatePersona: Boolean = true,
    /** 是否还显示「把他留在后台」的引导卡片。 */
    val backgroundTipDismissed: Boolean = false,
    /**
     * 他的冲动曲线走到哪儿了：概率时钟上一次被重置的时刻。
     *
     * 这是**内部状态**，不是设置项：界面里没有它，也不该有。它存在的唯一理由，
     * 是让 App 重启之后曲线接着走 —— 不然每次冷启动他都从头「很想找你」，像换了个人。
     */
    val heartbeatLastResetAt: Long = 0L,
    /** 上一次他真的因为这股冲动想了一回的时刻；null = 还没发生过。同样是内部状态。 */
    val heartbeatLastFiredAt: Long? = null,

    /**
     * 人格进化评估的游标（`docs/design/persona-evolution.md` §2）。
     *
     * 三件都是**内部状态**，界面上没有它们：只为了让「每 20 条用户消息或 24 小时评估一次」
     * 在 App 重启之后接着算，而不是每次冷启动都算作「已经过了 24 小时」。
     * 换人格时游标重置（[evolutionPersonaId] 对不上就当 0）。
     */
    val evolutionPersonaId: String? = null,
    /** 上次评估时这个会话已经有几条用户消息。 */
    val evolutionUserMessageCount: Int = 0,
    /** 上次评估的时刻；0 = 还没评估过。 */
    val evolutionLastRunAt: Long = 0L,

    /**
     * 自动情绪判定的游标（`spec/mood/README.md` §2）。
     *
     * 与进化游标一样是**内部状态**：只为了让「每 4 条用户消息或 24 小时判定一次」
     * 在 App 重启之后接着算。换人格时游标重置（[moodJudgePersonaId] 对不上就当 0）。
     */
    val moodJudgePersonaId: String? = null,
    val moodJudgeUserMessageCount: Int = 0,
    val moodJudgeLastRunAt: Long = 0L,

    /**
     * 连续「已读不回」（`stay_silent`）的轮数，以及它属于哪个人格。
     *
     * 规范 §4 要求连续不超过 2 轮，第 3 次必须要么回一句、要么忽略这次调用。
     * 计数器同样是内部状态：回一句（产生 assistant 消息）时清零；
     * 换人格时按 [staySilentPersonaId] 判断，不会把 A 的沉默算到 B 头上。
     */
    val staySilentPersonaId: String? = null,
    val staySilentStreak: Int = 0,

    /**
     * 自动检查更新（默认开）。
     *
     * 只在设置页打开 / App 回到前台时**静默**查一次，24 小时最多一次
     * （[com.yap.app.domain.UpdateThrottle]）；失败与限流都不弹错误。
     */
    val updateCheckEnabled: Boolean = true,
    /**
     * 检查更新时把预发布版本（tag 带 `-beta` 之类）也算进来。
     *
     * 默认关：`/releases/latest` 本来就只给正式版，这里多一道「用户明确想看才提示」的闸。
     */
    val updateIncludePrereleases: Boolean = false,
    /** 上次检查更新的时刻（内部状态：只有 24h 节流读它，设置页不显示）。 */
    val updateLastCheckedAt: Long = 0L,
    /** 上一次已经弹过「有新版本」的版本号（内部状态：同一个版本不反复打扰）。 */
    val updateLastNotifiedVersion: String? = null
)
