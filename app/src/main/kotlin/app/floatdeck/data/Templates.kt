package app.floatdeck.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 模板 JSON 文件中的单个肖像条目（位置字段可选，缺省时由渲染器使用内置布局算法）。 */
data class TemplateFile(
    val file: String,
    val label: String,
    val lockedPosition: PortraitPosition? = null,
    val unlockedPosition: PortraitPosition? = null,
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
 * 模板加载与布局参数解析。
 *
 * 从 assets/templates/<id>/template.json 或导入目录读取配置；
 * 肖像条目可携带可选的 locked/unlocked 显式位置（屏幕比例坐标），
 * 未提供时渲染器回退到内置布局算法。
 */
object Templates {
    private const val TEMPLATES_DIR = "templates"

    /** 从文件系统目录加载模板定义（用于远程导入的模板）。 */
    fun loadTemplateFromDir(dir: File): TemplateDef? {
        val jsonFile = File(dir, "template.json")
        if (!jsonFile.exists()) return null
        return try {
            parseDef(JSONObject(jsonFile.readText()))
        } catch (_: Exception) {
            null
        }
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
            parseDef(JSONObject(jsonStr))
        } catch (_: Exception) {
            null
        }

    /**
     * 按设置中的模板 id 解析定义：优先远程导入目录，其次内置 assets。
     *
     * @return 模板定义与其目录（内置模板时目录为 null）；找不到返回 null
     */
    fun resolveDef(
        context: Context,
        templateId: String,
    ): Pair<TemplateDef, File?>? {
        val remoteDir = File(context.filesDir, "${RemoteTemplateLoader.TEMPLATE_DIR}/$templateId")
        if (remoteDir.isDirectory) {
            loadTemplateFromDir(remoteDir)?.let { return it to remoteDir }
        }
        return loadTemplate(context, templateId)?.let { it to null }
    }

    /** 解析 template.json 根对象；id/name/wallpaper/portraits 为必填。 */
    internal fun parseDef(json: JSONObject): TemplateDef {
        val leftArr = json.getJSONObject("portraits").getJSONArray("left")
        val rightArr = json.getJSONObject("portraits").getJSONArray("right")

        val left = (0 until leftArr.length()).map { parseTemplateFile(leftArr.getJSONObject(it)) }
        val right = (0 until rightArr.length()).map { parseTemplateFile(rightArr.getJSONObject(it)) }

        return TemplateDef(
            id = json.getString("id"),
            name = json.getString("name"),
            wallpaper = json.getString("wallpaper"),
            left = left,
            right = right,
        )
    }

    private fun parseTemplateFile(obj: JSONObject): TemplateFile =
        TemplateFile(
            file = obj.getString("file"),
            label = obj.getString("label"),
            lockedPosition = TemplateOverrides.positionFromJson(obj.optJSONObject("locked")),
            unlockedPosition = TemplateOverrides.positionFromJson(obj.optJSONObject("unlocked")),
        )

    /** 将模板定义转换为渲染用的 TemplateConfig（assets 路径版本）。 */
    fun toTemplateConfig(
        context: Context,
        def: TemplateDef,
    ): TemplateConfig {
        val portraits =
            (def.left + def.right).map { tf ->
                PortraitConfig(
                    id = "${def.id}_${tf.file}",
                    assetPath = "$TEMPLATES_DIR/${def.id}/${tf.file}",
                    label = tf.label,
                    lockedPosition = tf.lockedPosition,
                    unlockedPosition = tf.unlockedPosition,
                )
            }

        return TemplateConfig(
            id = def.id,
            name = def.name,
            wallpaperAsset = "$TEMPLATES_DIR/${def.id}/${def.wallpaper}",
            portraits = portraits,
        )
    }

    /** 将远程模板定义转换为渲染用的 TemplateConfig（使用文件路径而非 assets 路径）。 */
    fun toTemplateConfigFromDir(
        def: TemplateDef,
        templateDir: File,
    ): TemplateConfig {
        val portraits =
            (def.left + def.right).map { tf ->
                PortraitConfig(
                    id = "${def.id}_${tf.file}",
                    assetPath = File(templateDir, tf.file).absolutePath,
                    label = tf.label,
                    lockedPosition = tf.lockedPosition,
                    unlockedPosition = tf.unlockedPosition,
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
}
