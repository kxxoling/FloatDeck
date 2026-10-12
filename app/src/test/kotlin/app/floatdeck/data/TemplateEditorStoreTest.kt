package app.floatdeck.data

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for the layout editor's JSON writes ([TemplateEditorStore.withPortraitPositions]).
 */
class TemplateEditorStoreTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writes by file name and removes on null`() {
        val json =
            JSONObject(
                """
                {"id": "t", "name": "T", "wallpaper": "w.webp",
                 "portraits": {"left": [
                    {"file": "a.webp", "label": "A",
                     "locked": {"x": 0.1, "y": 0.2}, "offset": {"x": 0.01, "y": 0.02}}
                 ], "right": []}}
                """.trimIndent(),
            )
        val locked = PortraitPosition(0.3f, 0.4f, rotation = 5f, scale = 0.9f)
        val updated =
            TemplateEditorStore.withPortraitPositions(
                json,
                mapOf("a.webp" to Pair(locked, null), "missing.webp" to Pair(locked, locked)),
            )

        val entry = updated.getJSONObject("portraits").getJSONArray("left").getJSONObject(0)
        assertEquals(0.3, entry.getJSONObject("locked").getDouble("x"), 1e-6)
        assertEquals(5.0, entry.getJSONObject("locked").getDouble("rotation"), 1e-6)
        assertFalse(entry.has("unlocked")) // null removes the field
        assertEquals(0.01, entry.getJSONObject("offset").getDouble("x"), 1e-6) // untouched
        assertEquals("A", entry.getString("label"))

        // Round trip through the template parser keeps the written position
        File(tempDir, "template.json").writeText(updated.toString())
        val def = Templates.loadTemplateFromDir(tempDir)!!
        assertEquals(0.3f, def.left.single().lockedPosition!!.xRatio, 1e-6f)
        assertNull(def.left.single().unlockedPosition)
    }
}
