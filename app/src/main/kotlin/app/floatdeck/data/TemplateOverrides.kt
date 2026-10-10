package app.floatdeck.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 用户对某个模板的自定义覆盖（override）。
 *
 * 不改写用户导入的原始 template.json，而是以独立 JSON 文件保存在
 * `filesDir/template_overrides/<id>.json`，加载模板时叠加生效。
 * 当前包含背景显示调整；立绘位置覆盖也走同一文件。
 */
data class TemplateOverride(
    val background: BackgroundAdjustment = BackgroundAdjustment(),
    val portraits: Map<String, PortraitOverride> = emptyMap(),
)

/**
 * 背景显示调整。
 *
 * @param zoom 缩放倍率，≥ 1（1 为刚好铺满屏幕的 cover 尺寸）
 * @param offsetX 水平平移，[-1, 1] 表示“扣除视差余量后的可用余量”的方向比例
 * @param offsetY 垂直平移，同上
 */
data class BackgroundAdjustment(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * 单张立绘的覆盖：可选的锁屏/解锁显式位置，以及按屏幕比例保存的用户偏移。
 */
data class PortraitOverride(
    val lockedPosition: PortraitPosition? = null,
    val unlockedPosition: PortraitPosition? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * 模板 override 的加载与保存。
 *
 * JSON 序列化核心不依赖 [Context]，可直接单测。
 */
object TemplateOverrides {
    private const val DIR_NAME = "template_overrides"
    private const val PREFS_NAME = "settings_prefs"

    /**
     * SharedPreferences key：每次用户主动保存时递增，
     * 壁纸服务监听到变更后触发模板热重载。
     */
    const val PREF_KEY_REVISION = "template_override_revision"

    /** 加载某模板的 override；文件缺失或解析失败时返回默认值。 */
    fun load(
        context: Context,
        templateId: String,
    ): TemplateOverride {
        val file = fileFor(context, templateId) ?: return TemplateOverride()
        return try {
            fromJson(file.readText())
        } catch (_: Exception) {
            TemplateOverride()
        }
    }

    /**
     * 保存某模板的 override。
     *
     * @param bumpRevision 为 true 时递增版本号以通知运行中的壁纸热重载；
     *   立绘拖动结束这类“静默”保存传 false，避免整模板重新加载造成闪烁。
     */
    fun save(
        context: Context,
        templateId: String,
        override: TemplateOverride,
        bumpRevision: Boolean = true,
    ) {
        val file = fileFor(context, templateId) ?: return
        try {
            file.writeText(toJson(override).toString())
        } catch (_: Exception) {
            return
        }
        if (bumpRevision) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(PREF_KEY_REVISION, prefs.getInt(PREF_KEY_REVISION, 0) + 1).apply()
        }
    }

    /** 模板 id 复用导入校验规则，防止路径注入；非法 id 返回 null 即不落盘。 */
    private fun fileFor(
        context: Context,
        templateId: String,
    ): File? {
        if (!RemoteTemplateLoader.isValidTemplateId(templateId)) return null
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$templateId.json")
    }

    // ------------------------------------------------------------------
    // 纯序列化核心（可脱离 Context 单测）
    // ------------------------------------------------------------------

    internal fun toJson(override: TemplateOverride): JSONObject {
        val root = JSONObject()
        root.put(
            "background",
            JSONObject()
                .put("zoom", override.background.zoom.toDouble())
                .put("offsetX", override.background.offsetX.toDouble())
                .put("offsetY", override.background.offsetY.toDouble()),
        )
        if (override.portraits.isNotEmpty()) {
            val portraits = JSONObject()
            override.portraits.forEach { (id, po) ->
                val entry = JSONObject()
                po.lockedPosition?.let { entry.put("locked", positionToJson(it)) }
                po.unlockedPosition?.let { entry.put("unlocked", positionToJson(it)) }
                entry.put("offsetX", po.offsetX.toDouble())
                entry.put("offsetY", po.offsetY.toDouble())
                portraits.put(id, entry)
            }
            root.put("portraits", portraits)
        }
        return root
    }

    internal fun fromJson(text: String): TemplateOverride {
        val root = JSONObject(text)
        val bg = root.optJSONObject("background")
        val portraitsJson = root.optJSONObject("portraits")
        val portraits = mutableMapOf<String, PortraitOverride>()
        if (portraitsJson != null) {
            portraitsJson.keys().forEach { id ->
                val entry = portraitsJson.optJSONObject(id) ?: return@forEach
                portraits[id] =
                    PortraitOverride(
                        lockedPosition = positionFromJson(entry.optJSONObject("locked")),
                        unlockedPosition = positionFromJson(entry.optJSONObject("unlocked")),
                        offsetX = entry.optDouble("offsetX", 0.0).toFloat(),
                        offsetY = entry.optDouble("offsetY", 0.0).toFloat(),
                    )
            }
        }
        return TemplateOverride(
            background =
                BackgroundAdjustment(
                    zoom = bg?.optDouble("zoom", 1.0)?.toFloat() ?: 1f,
                    offsetX = bg?.optDouble("offsetX", 0.0)?.toFloat() ?: 0f,
                    offsetY = bg?.optDouble("offsetY", 0.0)?.toFloat() ?: 0f,
                ),
            portraits = portraits,
        )
    }

    internal fun positionToJson(position: PortraitPosition): JSONObject =
        JSONObject()
            .put("x", position.xRatio.toDouble())
            .put("y", position.yRatio.toDouble())
            .put("rotation", position.rotation.toDouble())
            .put("scale", position.scale.toDouble())
            .put("tiltOffset", position.tiltOffset.toDouble())

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
}
