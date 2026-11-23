package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coil 图片内存缓存上限：`min(可用内存 × 8%, 24MB)`。
 *
 * 这条规则以前根本不存在：Coil 默认吃可用内存的 25% 当缓存上限，
 * 在大内存机器上等于几百 MB 的额度。单测把 8% 和 24MB 两道闸钉住。
 */
class AppMemoryBudgetTest {

    @Test
    fun `小内存设备按 8% 算`() {
        // 100MB 可用 × 8% = 8MB，低于 24MB 上限。
        assertEquals(
            8L * 1024 * 1024,
            AppMemoryBudget.imageMemoryCacheBytes(100L * 1024 * 1024)
        )
        // 512MB × 8% ≈ 41MB，会被 24MB 上限截住。
        assertEquals(
            AppMemoryBudget.IMAGE_MEMORY_CACHE_MAX_BYTES,
            AppMemoryBudget.imageMemoryCacheBytes(512L * 1024 * 1024)
        )
    }

    @Test
    fun `大内存设备封顶 24MB`() {
        assertEquals(
            24L * 1024 * 1024,
            AppMemoryBudget.imageMemoryCacheBytes(8L * 1024 * 1024 * 1024)
        )
        assertEquals(24L * 1024 * 1024, AppMemoryBudget.IMAGE_MEMORY_CACHE_MAX_BYTES)
    }

    @Test
    fun `8% 与 24MB 的边界`() {
        // 刚好 300MB × 8% = 24MB：不超上限。
        assertEquals(24L * 1024 * 1024, AppMemoryBudget.imageMemoryCacheBytes(300L * 1024 * 1024))
        // 比 300MB 多 1MB：仍封顶 24MB。
        assertEquals(24L * 1024 * 1024, AppMemoryBudget.imageMemoryCacheBytes(301L * 1024 * 1024))
    }

    @Test
    fun `拿不到可用内存时退回 0`() {
        assertEquals(0L, AppMemoryBudget.imageMemoryCacheBytes(0))
        assertEquals(0L, AppMemoryBudget.imageMemoryCacheBytes(-1))
    }

    @Test
    fun `磁盘缓存与比例常量符合规格`() {
        assertEquals(0.08, AppMemoryBudget.IMAGE_MEMORY_CACHE_FRACTION, 0.0)
        assertEquals(96L * 1024 * 1024, AppMemoryBudget.IMAGE_DISK_CACHE_BYTES)
    }

    @Test
    fun `本地执行预算与解码缓存`() {
        assertEquals(256L * 1024 * 1024, AppMemoryBudget.LOCAL_RUNTIME_BUDGET_BYTES)
        assertTrue(AppMemoryBudget.decodingCachesWithinBudget())
        assertEquals(24L * 1024 * 1024, AppMemoryBudget.STICKER_FRAME_CACHE_MAX_BYTES)
        assertEquals(12L * 1024 * 1024, AppMemoryBudget.VIDEO_FRAME_CACHE_MAX_BYTES)
    }
}
