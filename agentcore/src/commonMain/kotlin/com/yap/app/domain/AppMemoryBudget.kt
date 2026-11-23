package com.yap.app.domain

/**
 * 图片加载器的内存预算。
 *
 * 0.14.2 起按「本地执行 ≤256MB」重新收紧：图片缓存最多 24MB（可用内存的 8%），
 * 同时给表情帧、视频帧各留出硬预算，避免三类解码缓存叠加把进程顶穿。
 *
 * 纯计算，单测直接钉住「12% 与 48MB 取小」这条规则，避免以后有人顺手改成 25% 又没人发现。
 */
object AppMemoryBudget {

    /** 图片内存缓存占「可用内存」的比例。 */
    const val IMAGE_MEMORY_CACHE_FRACTION = 0.08

    /** 图片内存缓存硬上限：48MB。 */
    const val IMAGE_MEMORY_CACHE_MAX_BYTES = 24L * 1024 * 1024

    /** 图片磁盘缓存上限：128MB（聊天图片缩略图 + 头像原始文件之外的第二层缓存）。 */
    const val IMAGE_DISK_CACHE_BYTES = 96L * 1024 * 1024

    /** 图片磁盘缓存目录名（App 私有 cacheDir 下）。 */
    const val IMAGE_DISK_CACHE_DIR = "image_cache"

    /** 本地执行总内存目标：256MB（图片/表情/视频解码缓存都从这份预算里出）。 */
    const val LOCAL_RUNTIME_BUDGET_BYTES = 256L * 1024 * 1024

    /** 表情动画帧总预算：24MB；超过后只保留首帧/最近一帧。 */
    const val STICKER_FRAME_CACHE_MAX_BYTES = 24L * 1024 * 1024

    /** 视频关键帧总预算：12MB（≤8 帧 JPEG，解码后不再整段视频进内存）。 */
    const val VIDEO_FRAME_CACHE_MAX_BYTES = 12L * 1024 * 1024

    /**
     * 按可用内存算图片内存缓存上限：`min(available × 12%, 48MB)`。
     *
     * 传入 0 / 负数时返回 0，让 Coil 退化成「不缓存」而不是抛异常。
     */
    fun imageMemoryCacheBytes(availableMemoryBytes: Long): Long {
        if (availableMemoryBytes <= 0L) return 0L
        val proportional = (availableMemoryBytes * IMAGE_MEMORY_CACHE_FRACTION).toLong()
        return minOf(proportional, IMAGE_MEMORY_CACHE_MAX_BYTES)
    }

    /** 三类解码缓存之和必须小于本地执行预算的 40%（给 Room/Compose/网络留余量）。 */
    fun decodingCachesWithinBudget(): Boolean =
        IMAGE_MEMORY_CACHE_MAX_BYTES + STICKER_FRAME_CACHE_MAX_BYTES + VIDEO_FRAME_CACHE_MAX_BYTES <=
            LOCAL_RUNTIME_BUDGET_BYTES * 40 / 100
}
