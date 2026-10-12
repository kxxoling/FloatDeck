package app.floatdeck.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Layout editor persistence: writes explicit locked/unlocked positions into
 * the template's own template.json. Kept in its own object (instead of
 * growing [Templates]) so the editor's storage footprint stays cohesive and
 * easy to move or rework.
 */
object TemplateEditorStore {
    private const val PREFS_NAME = "settings_prefs"

    /**
     * Writes positions (keyed by image file name; null removes the field) and
     * zeroes the drag offsets, which the editor has already folded into the
     * positions. Bumps the template revision so a running wallpaper
     * hot-reloads. Returns false when the template is missing.
     */
    fun saveLayout(
        context: Context,
        templateId: String,
        positions: Map<String, Pair<PortraitPosition?, PortraitPosition?>>,
    ): Boolean {
        val jsonFile = templateJsonFile(context, templateId) ?: return false
        return try {
            val json = JSONObject(jsonFile.readText())
            val zeroOffsets = positions.mapValues { Pair(0f, 0f) }
            jsonFile.writeText(
                withPortraitPositions(Templates.withPortraitOffsets(json, zeroOffsets), positions).toString(),
            )
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(Templates.KEY_TEMPLATE_REVISION, prefs.getInt(Templates.KEY_TEMPLATE_REVISION, 0) + 1)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun templateJsonFile(
        context: Context,
        templateId: String,
    ): File? =
        File(File(context.filesDir, RemoteTemplateLoader.TEMPLATE_DIR), "$templateId/template.json")
            .takeIf { it.exists() }

    /** Serializes an explicit position object (pure, unit-testable). */
    internal fun positionToJson(position: PortraitPosition): JSONObject =
        JSONObject()
            .put("x", position.xRatio.toDouble())
            .put("y", position.yRatio.toDouble())
            .put("rotation", position.rotation.toDouble())
            .put("scale", position.scale.toDouble())
            .put("tiltOffset", position.tiltOffset.toDouble())

    /**
     * Writes per-portrait explicit locked/unlocked positions, matched by file
     * name; a null position removes the field. Entries not in the map are left
     * untouched (pure, unit-testable).
     */
    internal fun withPortraitPositions(
        json: JSONObject,
        positions: Map<String, Pair<PortraitPosition?, PortraitPosition?>>,
    ): JSONObject {
        val portraits = json.optJSONObject("portraits") ?: return json
        for (side in listOf("left", "right")) {
            val arr = portraits.optJSONArray(side) ?: continue
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val pair = positions[entry.getString("file")] ?: continue
                if (pair.first != null) {
                    entry.put("locked", positionToJson(pair.first!!))
                } else {
                    entry.remove("locked")
                }
                if (pair.second != null) {
                    entry.put("unlocked", positionToJson(pair.second!!))
                } else {
                    entry.remove("unlocked")
                }
            }
        }
        return json
    }
}
