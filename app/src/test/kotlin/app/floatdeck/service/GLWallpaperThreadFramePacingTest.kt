package app.floatdeck.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 覆盖 [GLWallpaperThread.sleepMillisForFrame] 的帧率节流计算。
 * EGL/GL 线程强依赖 Android 框架难以整体单测，但帧间隔计算是纯逻辑，
 * 提取后可独立验证。
 */
class GLWallpaperThreadFramePacingTest {
    // FRAME_INTERVAL_NANOS = 1_000_000_000 / 60 = 16_666_666
    // PRESENT_MARGIN_NANOS = 3ms = 3_000_000

    @Test
    fun `frame that took zero time sleeps interval minus present margin`() {
        // (16_666_666 - 3_000_000) / 1_000_000 = 13
        assertEquals(13L, GLWallpaperThread.sleepMillisForFrame(0L, 16_666_666L))
    }

    @Test
    fun `frame that used half the interval sleeps the remainder minus margin`() {
        // (16_666_666 - 3_000_000 - 8_333_333) / 1_000_000 = 5
        assertEquals(5L, GLWallpaperThread.sleepMillisForFrame(8_333_333L, 16_666_666L))
    }

    @Test
    fun `frame that hit the interval does not sleep`() {
        assertEquals(0L, GLWallpaperThread.sleepMillisForFrame(16_666_666L, 16_666_666L))
    }

    @Test
    fun `frame that overshot is clamped to zero`() {
        // 慢帧耗时超过间隔，不应睡眠负数
        assertEquals(0L, GLWallpaperThread.sleepMillisForFrame(20_000_000L, 16_666_666L))
    }

    @Test
    fun `margin can be disabled to sleep the full interval`() {
        // presentMarginNanos = 0 恢复无余量行为，便于对比
        assertEquals(16L, GLWallpaperThread.sleepMillisForFrame(0L, 16_666_666L, 0L))
    }

    @Test
    fun `default frame interval is 60fps`() {
        // 不传 frameIntervalNanos 时使用默认 60fps 间隔
        assertEquals(13L, GLWallpaperThread.sleepMillisForFrame(0L))
        assertEquals(0L, GLWallpaperThread.sleepMillisForFrame(20_000_000L))
    }
}
