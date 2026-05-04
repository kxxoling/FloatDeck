package app.floatdeck.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.InputStream

/** 纹理加载工具：从 assets、占位符、渐变色等多种来源创建 GL 纹理。 */
object TextureLoader {
    /** 从 assets 目录加载图片并上传为 GL 纹理，失败返回 null。 */
    fun loadTextureFromAsset(
        context: Context,
        assetPath: String,
    ): Int? {
        return try {
            val inputStream: InputStream? = context.assets.open(assetPath)
            if (inputStream == null) return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return null
            val texId = loadBitmap(bitmap)
            bitmap.recycle()
            texId
        } catch (_: Exception) {
            null
        }
    }

    /** 从文件路径加载图片并上传为 GL 纹理，失败返回 null。 */
    fun loadTextureFromFile(path: String): Int? {
        return try {
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap == null) return null
            val texId = loadBitmap(bitmap)
            bitmap.recycle()
            texId
        } catch (_: Exception) {
            null
        }
    }

    /** 创建带圆角背景和文字标签的占位纹理（用于缺少资源时显示）。 */
    fun createPlaceholderTexture(
        label: String,
        width: Int = 128,
        height: Int = 256,
        bgColor: Int = Color.argb(255, 60, 60, 90),
    ): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 绘制圆角矩形背景
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawColor(Color.TRANSPARENT)
        val r = 16f
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            r,
            r,
            bgPaint,
        )

        // 居中绘制文字标签
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20f
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText(label, width / 2f, height / 2f, textPaint)

        val texId = loadBitmap(bitmap)
        bitmap.recycle()
        return texId
    }

    /** 创建渐变背景纹理（顶部深蓝 → 底部深青）。 */
    fun createGradientBackground(
        width: Int = 512,
        height: Int = 1024,
    ): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val topColor = Color.parseColor("#1a1a2e")
        val bottomColor = Color.parseColor("#16213e")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 逐行插值绘制渐变
        for (y in 0 until height) {
            val ratio = y.toFloat() / height
            val red = lerp(Color.red(topColor), Color.red(bottomColor), ratio)
            val green = lerp(Color.green(topColor), Color.green(bottomColor), ratio)
            val blue = lerp(Color.blue(topColor), Color.blue(bottomColor), ratio)
            paint.color = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
        }

        val texId = loadBitmap(bitmap)
        bitmap.recycle()
        return texId
    }

    /** 将 Bitmap 上传为 GL 纹理，配置双线性过滤和边缘钳位。 */
    fun loadBitmap(bitmap: Bitmap): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)

        // 双线性过滤
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR,
        )
        // 边缘钳位（不重复）
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        return texId
    }

    /** 删除指定纹理。 */
    fun deleteTexture(texId: Int) {
        GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
    }

    /** 线性插值。 */
    private fun lerp(
        a: Int,
        b: Int,
        t: Float,
    ): Float = a + (b - a) * t
}
