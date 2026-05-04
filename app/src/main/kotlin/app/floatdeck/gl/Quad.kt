package app.floatdeck.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 单位四边形几何数据。
 *
 * 顶点范围 [-1, 1]，以原点为中心。
 * UV 坐标翻转了 V 轴以确保图片正立：
 * - OpenGL 正交投影中 Y 轴向下（屏幕坐标），Y=-1 在顶部，Y=+1 在底部。
 * - 图片 UV 原点在左上角 (0,0)，右下角为 (1,1)。
 * - 因此顶部顶点 (-1,-1) 对应 UV (0,0)，底部顶点 (-1,1) 对应 UV (0,1)。
 */
object Quad {
    @Suppress("MagicNumber")
    private val vertexData =
        floatArrayOf(
            // 位置 x, 位置 y, UV s, UV t
            -1f,
            -1f,
            0f,
            0f,
            1f,
            -1f,
            1f,
            0f,
            -1f,
            1f,
            0f,
            1f,
            1f,
            1f,
            1f,
            1f,
        )

    /** 每个顶点占 4 个 float × 4 字节 = 16 字节步长。 */
    private const val STRIDE = 4 * 4

    /** 将顶点数据写入 native 字节缓冲区供 GL 使用。 */
    fun createBuffer(): FloatBuffer {
        val buffer =
            ByteBuffer
                .allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        buffer.put(vertexData).position(0)
        return buffer
    }

    fun getStride(): Int = STRIDE

    /** 位置属性在顶点数据中的字节偏移量（紧跟在位置之后）。 */
    fun getPositionOffset(): Int = 0

    /** UV 属性在顶点数据中的字节偏移量（跳过 2 个 float = 8 字节）。 */
    fun getUvOffset(): Int = 2 * 4
}
