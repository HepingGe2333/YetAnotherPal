package com.yap.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.repo.PersonaCardRepository
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PersonaRepositoryInitializer
import com.yap.app.data.repo.ProactiveScheduler
import com.yap.app.service.AgentLoopController
import com.yap.app.util.AppForegroundTracker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.yap.app.data.repo.ApiUsageRepository
import com.yap.app.data.repo.MemoryConsolidationWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YapApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var database: YapDatabase
    @Inject lateinit var initializer: PersonaRepositoryInitializer
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var proactiveScheduler: ProactiveScheduler
    @Inject lateinit var agentLoopController: AgentLoopController
    @Inject lateinit var foregroundTracker: AppForegroundTracker
    @Inject lateinit var personaCardRepository: PersonaCardRepository
    @Inject lateinit var personaRepository: PersonaRepository
    @Inject lateinit var memoryRepository: com.yap.app.data.repo.MemoryRepository
    @Inject lateinit var historyCompressor: com.yap.app.data.repo.HistoryCompressor

    /** 服务端模式的前台同步/事件订阅（默认关闭时不发任何请求）。 */
    @Inject lateinit var serverModeRuntime: com.yap.app.data.remote.ServerModeRuntime

    /** 流水仓库在这里被 Hilt 构造出来，启动时把 90 天前的旧流水清掉。 */
    @Inject lateinit var usageRepository: ApiUsageRepository

    /**
     * Coil 的全局入口：所有 `AsyncImage` / `imageLoader` 调用都会落到 [AppModule.provideImageLoader]
     * 返回的那一个实例上（内存缓存 min(可用内存×12%, 48MB)、磁盘 128MB）。
     *
     * 用 [dagger.Lazy] 而不是直接注入：没有图片的页面（联系人首页、设置首页）
     * 完全不该为了「以后可能加载头像」就在冷启动时把 Coil 的缓存组件建起来。
     * 第一次真正出现 AsyncImage 时才 get()。
     */
    @Inject lateinit var imageLoader: dagger.Lazy<ImageLoader>

    override fun newImageLoader(): ImageLoader = imageLoader.get()

    /**
     * 0.14.2：系统内存紧张时主动把 Coil 的内存缓存吐回去。
     *
     * 图片缓存的上限已经压到 24MB，但「上限」不等于「系统压力下会自动归还」；
     * 收到 RUNNING_LOW / COMPLETE 时清一次，避免聊天页 + 表情 + 视频帧叠加。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { imageLoader.get().memoryCache?.clear() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { imageLoader.get().memoryCache?.clear() }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 前后台判定：退到桌面也算「不在前台」，回复该弹的通知才弹得出来。
        foregroundTracker.install(this)
        appScope.launch {
            // 90 天前的明细对「这个月花了多少」已经没有用了，启动时顺手清一次。
            runCatching { usageRepository.pruneOlderThan(90) }
        }
        appScope.launch {
            initializer.seedIfNeeded()
            // 一人一段：种子人格 / 老库 / 导入的人格如果还没有会话，启动时补齐。
            runCatching { personaRepository.ensureSessionsForAll() }
            // 老版本留下的「等确认」补丁：直接生效，别让它们变成死按钮。
            runCatching { personaCardRepository.applyAllPendingOnce() }
            proactiveScheduler.ensureScheduled()
            // 每天给自己整理一次记忆：留下印象深的，把不重要的移出上下文。
            // 1.9 起这是内置行为，没有开关可关：记忆是他的记性，不是用户可调的能力。
            runCatching { MemoryConsolidationWorker.schedule(this@YapApplication) }
            // 中文倒排索引：从老库升上来时补建一次，之后由写入路径自己维护。
            runCatching { memoryRepository.ensureTermIndex() }
            // 本地向量索引：老库升上来时补建；之后由记忆的写入/整理/漂移路径自己维护。
            runCatching { memoryRepository.ensureVectorIndex() }
            // 1.7 早期版本把工具调用写成了「回忆」存进摘要，这里把这类脏摘要清掉；
            // 清掉只是不再把它塞进提示词，下次聊到该压的时候会重新压一遍。
            runCatching { historyCompressor.repairStaleSummaries() }
        }
        // 设置里「常驻后台」一开，就把他的循环拉起来；关了就走 WorkManager 粗粒度。
        agentLoopController.observe()
        // 服务端模式：启动/回前台同步一次，前台时事件长轮询；关闭时什么都不做。
        serverModeRuntime.start(appScope)
    }
}
