package app.floatdeck.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.sqrt

/**
 * 设备姿态传感器监听器。
 *
 * 优先使用旋转矢量传感器（TYPE_ROTATION_VECTOR），回退到游戏旋转矢量，
 * 最后回退到加速度计。输出 rollX（左右倾斜）和 pitchY（前后倾斜）归一化值。
 */
class SensorHandler(
    private val context: Context,
) : SensorEventListener {
    companion object {
        /**
         * 把旋转矢量传感器返回的 values 截断到前 4 个元素。
         * 部分厂商返回长度为 5（含 heading），会导致
         * [SensorManager.getRotationMatrixFromVector] 抛 IllegalArgumentException。
         */
        internal fun safeRotationValues(values: FloatArray): FloatArray =
            if (values.size > 4) values.copyOf(4) else values

        /**
         * The (axisX, axisY) pair used with [SensorManager.remapCoordinateSystem]
         * to rotate device-frame data into screen frame, per Android docs.
         */
        internal fun remapAxesForRotation(rotation: Int): IntArray =
            when (rotation) {
                Surface.ROTATION_90 -> intArrayOf(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
                Surface.ROTATION_180 -> intArrayOf(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
                Surface.ROTATION_270 -> intArrayOf(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
                else -> intArrayOf(SensorManager.AXIS_X, SensorManager.AXIS_Y)
            }

        /**
         * Projects device-frame gravity components (gx, gy) onto the screen
         * axes for the given rotation, returning (screenRollX, screenPitchY).
         * Pure function so the axis table can be unit tested.
         */
        internal fun remapAccelForRotation(
            gx: Float,
            gy: Float,
            rotation: Int,
        ): FloatArray =
            when (rotation) {
                Surface.ROTATION_90 -> floatArrayOf(gy, -gx)
                Surface.ROTATION_180 -> floatArrayOf(-gx, -gy)
                Surface.ROTATION_270 -> floatArrayOf(-gy, gx)
                else -> floatArrayOf(gx, gy)
            }
    }

    /** 左右倾斜值（roll），约 -1 ~ 1 */
    var rollX = 0f
        private set

    /** 前后倾斜值（pitch），约 -1 ~ 1 */
    var pitchY = 0f
        private set

    /** Current display rotation (Surface.ROTATION_*); written by the render thread. */
    @Volatile
    var screenRotation = Surface.ROTATION_0

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private var registered = false

    /** 注册传感器监听，按优先级依次尝试旋转矢量 → 游戏旋转矢量 → 加速度计。 */
    fun register() {
        if (registered) return

        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector != null) {
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI)
            registered = true
            return
        }

        val gameRotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gameRotation != null) {
            sensorManager.registerListener(this, gameRotation, SensorManager.SENSOR_DELAY_UI)
            registered = true
            return
        }

        // 最后回退：仅用加速度计估算倾斜（无陀螺仪的设备）
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            registered = true
        }
    }

    /** 取消传感器监听。 */
    fun unregister() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            // 旋转矢量 / 游戏旋转矢量：旋转矩阵重映射到屏幕坐标系后，直接取世界"上"方向
            // 在屏幕坐标的分量作为倾斜量。平板/手机直立握持时两个输出都接近 0（无偏置），
            // 不像 getOrientation 的欧拉角在直立姿态下会饱和在 ±60°~90°。
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(
                    rotationMatrix,
                    safeRotationValues(event.values),
                )
                val axes = remapAxesForRotation(screenRotation)
                val screenMatrix = FloatArray(9)
                if (!SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        axes[0],
                        axes[1],
                        screenMatrix,
                    )
                ) {
                    return
                }
                // screenMatrix maps screen→world; its third row (elements 6/7/8)
                // holds the world-up vector in screen coordinates:
                //   R'[6] = up·screenX, R'[7] = up·screenY, R'[8] = up·screenZ
                pitchY = -screenMatrix[6] // screen bank: right edge dipping → positive
                rollX = -screenMatrix[8] // front/back tilt: leaning back → negative
            }
            // 加速度计回退：用重力方向归一化估算倾斜
            Sensor.TYPE_ACCELEROMETER -> {
                val g = event.values
                val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (norm > 0.1f) {
                    val remapped = remapAccelForRotation(g[0] / norm, g[1] / norm, screenRotation)
                    // Screen-Z gravity equals device-Z gravity for all four rotations.
                    pitchY = remapped[0]
                    rollX = g[2] / norm
                }
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {}
}
