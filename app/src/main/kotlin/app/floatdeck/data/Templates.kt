package app.floatdeck.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** A single portrait entry in template.json (position and offset fields optional; when absent the renderer applies its built-in layout). */
data class TemplateFile(
    val file: String,
    val label: String,
    val lockedPosition: PortraitPosition? = null,
    val unlockedPosition: PortraitPosition? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * 从 template.json 解析出的原始模板定义。
 *
 * @param left 左侧肖像列表（解锁状态下显示在屏幕左侧）
 * @param right 右侧肖像列表（解锁状态下显示在屏幕右侧）
 * @param backgroundZoom 用户背景缩放（缺省 1 = 100%），直接保存在模板自己的 template.json 里
 */
data class TemplateDef(
    val id: String,
    val name: String,
    val wallpaper: String,
    val left: List<TemplateFile>,
    val right: List<TemplateFile>,
    val backgroundZoom: Float = 1f,
)

/**
 * Template loading and saving.
 *
 * All user customizations (background zoom, portrait positions and offsets)
 * are written straight back into the template's own template.json; missing
 * fields fall back to defaults when read.
 */
object Templates {
    private const val TEMPLATES_DIR = "templates"
    private const val PREFS_NAME = "settings_prefs"

    /** SharedPreferences key bumped on every template.json write; the wallpaper service hot-reloads on change. */
    const val KEY_TEMPLATE_REVISION = "template_revision"

    /** Loads a template definition from a file-system directory (used for imported templates). */
    fun loadTemplateFromDir(dir: File): TemplateDef? {
        val jsonFile = File(dir, "template.json")
        if (!jsonFile.exists()) return null
        return try {
            parseDef(JSONObject(jsonFile.readText()))
        } catch (_: Exception) {
            null
        }
    }

    /** Loads a template definition from assets; returns null when parsing fails. */
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
     * 读取某模板当前的背景缩放；模板不存在时返回默认值 1。
     */
    fun backgroundZoom(
        context: Context,
        templateId: String,
    ): Float {
        val dir = File(context.filesDir, "${RemoteTemplateLoader.TEMPLATE_DIR}/$templateId")
        return loadTemplateFromDir(dir)?.backgroundZoom ?: 1f
    }

    /**
     * Persists the user background zoom into the imported template's own
     * template.json. Bumps the template revision so a running wallpaper
     * hot-reloads. Returns false when the template is missing.
     */
    fun saveBackgroundZoom(
        context: Context,
        templateId: String,
        zoom: Float,
    ): Boolean {
        val jsonFile = templateJsonFile(context, templateId) ?: return false
        return rewriteTemplateJson(context, jsonFile) { json -> withBackgroundZoom(json, zoom) }
    }

    /**
     * Persists per-portrait user drag offsets (screen-size fractions, keyed by
     * image file name) into the template's own template.json.
     *
     * @param bumpRevision false for silent drag-end saves (the renderer folds
     *   the offsets in memory; no hot-reload flicker)
     */
    fun savePortraitOffsets(
        context: Context,
        templateId: String,
        offsets: Map<String, Pair<Float, Float>>,
        bumpRevision: Boolean = true,
    ): Boolean {
        val jsonFile = templateJsonFile(context, templateId) ?: return false
        return rewriteTemplateJson(context, jsonFile, bumpRevision) { json -> withPortraitOffsets(json, offsets) }
    }

    /** Parses the template.json root object; id/name/wallpaper/portraits are required. */
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
            backgroundZoom = parseBackgroundZoom(json),
        )
    }

    private fun parseTemplateFile(obj: JSONObject): TemplateFile =
        TemplateFile(
            file = obj.getString("file"),
            label = obj.getString("label"),
            lockedPosition = positionFromJson(obj.optJSONObject("locked")),
            unlockedPosition = positionFromJson(obj.optJSONObject("unlocked")),
            offsetX = obj.optJSONObject("offset")?.optDouble("x", 0.0)?.toFloat() ?: 0f,
            offsetY = obj.optJSONObject("offset")?.optDouble("y", 0.0)?.toFloat() ?: 0f,
        )

    /** Parses the optional background.zoom field; missing values default to 1 (100%). */
    internal fun parseBackgroundZoom(json: JSONObject): Float =
        json.optJSONObject("background")?.optDouble("zoom", 1.0)?.toFloat() ?: 1f

    /** Parses an explicit position object; every field has a default. */
    internal fun positionFromJson(obj: JSONObject?): PortraitPosition? =
        if (obj == null) {
            null
        } else {
            PortraitPosition(
                xRatio = obj.optDouble("x", 0.5).toFloat(),
                yRatio = obj.optDouble("y", 0.5).toFloat(),
                rotation = obj.optDouble("rotation", 0.0).toFloat(),
                scale = obj.optDouble("scale", 1.0).toFloat(),
                tiltOffset = obj.optDouble("tiltOffset", 1.0).toFloat(),
            )
        }

    /** Returns [json] with background.zoom set (pure, unit-testable). */
    internal fun withBackgroundZoom(
        json: JSONObject,
        zoom: Float,
    ): JSONObject {
        val background = json.optJSONObject("background") ?: JSONObject()
        background.put("zoom", zoom.toDouble())
        json.put("background", background)
        return json
    }

    /**
     * Writes per-portrait offsets into the portraits entries, matched by file
     * name; entries without an offset are left untouched (pure, unit-testable).
     */
    internal fun withPortraitOffsets(
        json: JSONObject,
        offsets: Map<String, Pair<Float, Float>>,
    ): JSONObject {
        val portraits = json.optJSONObject("portraits") ?: return json
        for (side in listOf("left", "right")) {
            val arr = portraits.optJSONArray(side) ?: continue
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val offset = offsets[entry.getString("file")] ?: continue
                entry.put(
                    "offset",
                    JSONObject()
                        .put("x", offset.first.toDouble())
                        .put("y", offset.second.toDouble()),
                )
            }
        }
        return json
    }

    /** Converts a template definition into a renderable TemplateConfig (asset-path variant). */
    fun toTemplateConfig(
        context: Context,
        def: TemplateDef,
    ): TemplateConfig {
        val portraits =
            (def.left + def.right).map { tf -> tf.toPortraitConfig("$TEMPLATES_DIR/${def.id}", def.id, isRemote = false) }

        return TemplateConfig(
            id = def.id,
            name = def.name,
            wallpaperAsset = "$TEMPLATES_DIR/${def.id}/${def.wallpaper}",
            portraits = portraits,
        )
    }

    /** Converts an imported template definition into a TemplateConfig (file paths instead of asset paths). */
    fun toTemplateConfigFromDir(
        def: TemplateDef,
        templateDir: File,
    ): TemplateConfig {
        val portraits =
            (def.left + def.right).map { tf -> tf.toPortraitConfig(templateDir.absolutePath, def.id, isRemote = true) }

        return TemplateConfig(
            id = def.id,
            name = def.name,
            wallpaperAsset = File(templateDir, def.wallpaper).absolutePath,
            portraits = portraits,
            isRemote = true,
        )
    }

    private fun TemplateFile.toPortraitConfig(
        assetDir: String,
        templateId: String,
        isRemote: Boolean,
    ): PortraitConfig =
        PortraitConfig(
            id = "${templateId}_$file",
            file = file,
            assetPath = "$assetDir/$file",
            label = label,
            lockedPosition = lockedPosition,
            unlockedPosition = unlockedPosition,
            offsetX = offsetX,
            offsetY = offsetY,
            isRemote = isRemote,
        )

    private fun templateJsonFile(
        context: Context,
        templateId: String,
    ): File? {
        val jsonFile =
            File(
                File(context.filesDir, RemoteTemplateLoader.TEMPLATE_DIR),
                "$templateId/template.json",
            )
        return jsonFile.takeIf { it.exists() }
    }

    /** Read-modify-write of the template's template.json; bumps the revision by default. */
    private inline fun rewriteTemplateJson(
        context: Context,
        jsonFile: File,
        bumpRevision: Boolean = true,
        transform: (JSONObject) -> JSONObject,
    ): Boolean =
        try {
            jsonFile.writeText(transform(JSONObject(jsonFile.readText())).toString())
            if (bumpRevision) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt(KEY_TEMPLATE_REVISION, prefs.getInt(KEY_TEMPLATE_REVISION, 0) + 1).apply()
            }
            true
        } catch (_: Exception) {
            false
        }
}
