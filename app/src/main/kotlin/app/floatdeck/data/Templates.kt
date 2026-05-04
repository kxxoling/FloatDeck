package app.floatdeck.data

import android.content.Context
import app.floatdeck.R
import org.json.JSONObject
import java.io.File

/** 模板 JSON 文件中的单个肖像条目。 */
data class TemplateFile(
    val file: String,
    val label: String,
)

/**
 * 从 template.json 解析出的原始模板定义。
 *
 * @param left 左侧肖像列表（解锁状态下显示在屏幕左侧）
 * @param right 右侧肖像列表（解锁状态下显示在屏幕右侧）
 */
data class TemplateDef(
    val id: String,
    val name: String,
    val wallpaper: String,
    val left: List<TemplateFile>,
    val right: List<TemplateFile>,
)

/**
 * 模板加载与布局计算。
 *
 * 从 assets/templates/<id>/template.json 读取配置，
 * 根据屏幕尺寸计算出每张肖像在锁屏/解锁两种状态下的位置参数。
 */
object Templates {
    private const val TEMPLATES_DIR = "templates"

    /** 从文件系统目录加载模板定义（用于远程导入的模板）。 */
    fun loadTemplateFromDir(dir: File): TemplateDef? =
        try {
            val jsonFile = File(dir, "template.json")
            val jsonStr = jsonFile.readText()
            val json = JSONObject(jsonStr)

            val leftArr = json.getJSONObject("portraits").getJSONArray("left")
            val rightArr = json.getJSONObject("portraits").getJSONArray("right")

            val left =
                (0 until leftArr.length()).map { i ->
                    val obj = leftArr.getJSONObject(i)
                    TemplateFile(obj.getString("file"), obj.getString("label"))
                }
            val right =
                (0 until rightArr.length()).map { i ->
                    val obj = rightArr.getJSONObject(i)
                    TemplateFile(obj.getString("file"), obj.getString("label"))
                }

            TemplateDef(
                id = json.getString("id"),
                name = json.getString("name"),
                wallpaper = json.getString("wallpaper"),
                left = left,
                right = right,
            )
        } catch (_: Exception) {
            null
        }

    /** 从 assets 加载模板定义，解析失败返回 null。 */
    fun loadTemplate(
        context: Context,
        templateId: String,
    ): TemplateDef? =
        try {
            val jsonStr =
                context.assets
                    .open("$TEMPLATES_DIR/$templateId/template.json")
                    .bufferedReader()
                    .use { it.readText() }
            val json = JSONObject(jsonStr)

            // 解析左右两侧的肖像列表
            val leftArr = json.getJSONObject("portraits").getJSONArray("left")
            val rightArr = json.getJSONObject("portraits").getJSONArray("right")

            val left =
                (0 until leftArr.length()).map { i ->
                    val obj = leftArr.getJSONObject(i)
                    TemplateFile(obj.getString("file"), obj.getString("label"))
                }
            val right =
                (0 until rightArr.length()).map { i ->
                    val obj = rightArr.getJSONObject(i)
                    TemplateFile(obj.getString("file"), obj.getString("label"))
                }

            TemplateDef(
                id = json.getString("id"),
                name = json.getString("name"),
                wallpaper = json.getString("wallpaper"),
                left = left,
                right = right,
            )
        } catch (_: Exception) {
            null
        }

    /**
     * 将模板定义转换为渲染用的 TemplateConfig，计算每张肖像的布局位置。
     *
     * @param screenWidth Surface 宽度（像素）
     * @param screenHeight Surface 高度（像素）
     */
    fun toTemplateConfig(
        context: Context,
        def: TemplateDef,
        screenWidth: Float,
        screenHeight: Float,
    ): TemplateConfig {
        val allFiles = def.left + def.right
        val total = allFiles.size

        val portraits =
            allFiles.mapIndexed { i, tf ->
                val isLeft = i < def.left.size
                val sideIndex = if (isLeft) i else i - def.left.size
                val sideCount = if (isLeft) def.left.size else def.right.size

                PortraitConfig(
                    id = "${def.id}_${tf.file}",
                    assetPath = "$TEMPLATES_DIR/${def.id}/${tf.file}",
                    label = tf.label,
                    lockedPosition = lockedPosition(i, total),
                    unlockedPosition = unlockedPosition(isLeft, sideIndex, sideCount),
                )
            }

        return TemplateConfig(
            id = def.id,
            name = def.name,
            wallpaperAsset = "$TEMPLATES_DIR/${def.id}/${def.wallpaper}",
            portraits = portraits,
        )
    }

    /**
     * 将远程模板定义转换为渲染用的 TemplateConfig（使用文件路径而非 assets 路径）。
     */
    fun toTemplateConfigFromDir(
        def: TemplateDef,
        templateDir: File,
        screenWidth: Float,
        screenHeight: Float,
    ): TemplateConfig {
        val allFiles = def.left + def.right
        val total = allFiles.size

        val portraits =
            allFiles.mapIndexed { i, tf ->
                val isLeft = i < def.left.size
                val sideIndex = if (isLeft) i else i - def.left.size
                val sideCount = if (isLeft) def.left.size else def.right.size

                PortraitConfig(
                    id = "${def.id}_${tf.file}",
                    assetPath = File(templateDir, tf.file).absolutePath,
                    label = tf.label,
                    lockedPosition = lockedPosition(i, total),
                    unlockedPosition = unlockedPosition(isLeft, sideIndex, sideCount),
                    isRemote = true,
                )
            }

        return TemplateConfig(
            id = def.id,
            name = def.name,
            wallpaperAsset = File(templateDir, def.wallpaper).absolutePath,
            portraits = portraits,
            isRemote = true,
        )
    }

    /**
     * 计算锁屏状态下的肖像位置：居中网格排列，带随机旋转。
     *
     * 列数自动调整（≤8 张用 4 列，>8 张用 5 列），每行居中对齐。
     */
    private fun lockedPosition(
        i: Int,
        total: Int,
    ): PortraitPosition {
        val cols = if (total <= 8) 4 else 5
        val row = i / cols
        val col = i % cols
        val rows = (total + cols - 1) / cols
        // 最后一行可能不满，计算实际列数以居中
        val colsInRow = (total - row * cols).coerceAtMost(cols)
        val rowWidth = (colsInRow - 1) * 0.1f
        val startX = 0.5f - rowWidth / 2f
        val centerY = 0.2f + row * (0.6f / (rows - 1).coerceAtLeast(1))

        return PortraitPosition(
            xRatio = (startX + col * 0.1f).coerceIn(0.05f, 0.95f),
            yRatio = centerY.coerceIn(0.1f, 0.9f),
            rotation = (i - total / 2) * 2.5f, // 以中心为对称的旋转角度
            scale = 0.85f,
            tiltOffset = 0.6f + (i % 5) * 0.12f, // 交错视差灵敏度
        )
    }

    /**
     * 计算解锁状态下的肖像位置：分散到屏幕左右两侧，竖向均匀排列。
     *
     * @param isLeft 是否属于左侧组
     * @param sideIndex 在该侧的索引
     * @param sideCount 该侧总数
     */
    private fun unlockedPosition(
        isLeft: Boolean,
        sideIndex: Int,
        sideCount: Int,
    ): PortraitPosition {
        val x = if (isLeft) 0.05f else 0.95f
        val yStart = 0.12f
        val yEnd = 0.88f
        val yStep = (yEnd - yStart) / (sideCount - 1).coerceAtLeast(1)
        val y = yStart + sideIndex * yStep

        return PortraitPosition(
            xRatio = x,
            yRatio = y.coerceIn(0.1f, 0.9f),
            // 左侧微左倾，右侧微右倾
            rotation = if (isLeft) -6f + sideIndex * 1.5f else 6f - sideIndex * 1.5f,
            scale = 0.4f,
            tiltOffset = 0.2f,
        )
    }
}
