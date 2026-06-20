package app.floatdeck.sensor

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * 覆盖 [SensorHandler.safeRotationValues]：部分厂商的旋转矢量传感器会返回长度为 5
 * （含 heading）的 values，直接传给 SensorManager.getRotationMatrixFromVector 会抛
 * IllegalArgumentException，需截断到前 4 个元素。
 */
class SensorHandlerTest {
    @Test
    fun `truncates length-5 values to first 4 elements`() {
        val values = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val result = SensorHandler.safeRotationValues(values)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), result)
    }

    @Test
    fun `keeps length-4 values as-is`() {
        val values = floatArrayOf(1f, 2f, 3f, 4f)
        // 长度正好为 4 时不复制，返回原数组
        assertSame(values, SensorHandler.safeRotationValues(values))
    }

    @Test
    fun `keeps length-3 values as-is`() {
        val values = floatArrayOf(1f, 2f, 3f)
        assertSame(values, SensorHandler.safeRotationValues(values))
    }

    @Test
    fun `handles longer arrays`() {
        val values = floatArrayOf(9f, 8f, 7f, 6f, 5f, 4f, 3f)
        assertArrayEquals(floatArrayOf(9f, 8f, 7f, 6f), SensorHandler.safeRotationValues(values))
    }

    @Test
    fun `empty array returns empty`() {
        val empty = FloatArray(0)
        assertSame(empty, SensorHandler.safeRotationValues(empty))
    }
}
