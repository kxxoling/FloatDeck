package app.floatdeck.data

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for optional portrait positions/offsets stored directly in
 * template.json ([Templates] parsing and [Templates.withPortraitOffsets]).
 */
class TemplatesPositionTest {
    @TempDir
    lateinit var tempDir: File

    private fun writeTemplate(portraitEntry: String) {
        File(tempDir, "template.json").writeText(
            """
            {
              "id": "t", "name": "T", "wallpaper": "w.webp",
              "portraits": {
                "left": [$portraitEntry],
                "right": []
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `locked and unlocked positions are parsed with per-field defaults`() {
        writeTemplate(
            """
            {"file": "a.webp", "label": "A",
             "locked":   {"x": 0.3, "y": 0.4, "rotation": 5, "scale": 0.9, "tiltOffset": 1.5},
             "unlocked": {"x": 0.05, "y": 0.5, "scale": 0.64}}
            """.trimIndent(),
        )
        val def = Templates.loadTemplateFromDir(tempDir)
        assertNotNull(def)
        val portrait = def!!.left.single()
        val locked = portrait.lockedPosition
        assertNotNull(locked)
        assertEquals(0.3f, locked!!.xRatio, 1e-6f)
        assertEquals(0.4f, locked.yRatio, 1e-6f)
        assertEquals(5f, locked.rotation, 1e-6f)
        assertEquals(0.9f, locked.scale, 1e-6f)
        assertEquals(1.5f, locked.tiltOffset, 1e-6f)
        val unlocked = portrait.unlockedPosition
        assertNotNull(unlocked)
        assertEquals(0f, unlocked!!.rotation, 1e-6f) // omitted → default
        assertEquals(1f, unlocked.tiltOffset, 1e-6f) // omitted → default
        assertEquals(0f, portrait.offsetX, 1e-6f) // no offset object → default
    }

    @Test
    fun `portraits without positions or offsets parse to defaults`() {
        writeTemplate("""{"file": "a.webp", "label": "A"}""")
        val def = Templates.loadTemplateFromDir(tempDir)
        assertNotNull(def)
        val portrait = def!!.left.single()
        assertNull(portrait.lockedPosition)
        assertNull(portrait.unlockedPosition)
        assertEquals(0f, portrait.offsetX, 1e-6f)
        assertEquals(0f, portrait.offsetY, 1e-6f)
    }

    @Test
    fun `offsets are parsed and carried into the portrait config`() {
        writeTemplate("""{"file": "a.webp", "label": "A", "offset": {"x": 0.02, "y": -0.01}}""")
        val config = Templates.toTemplateConfigFromDir(Templates.loadTemplateFromDir(tempDir)!!, tempDir)
        val portrait = config.portraits.single()
        assertEquals("a.webp", portrait.file)
        assertEquals(0.02f, portrait.offsetX, 1e-6f)
        assertEquals(-0.01f, portrait.offsetY, 1e-6f)
    }

    @Test
    fun `withPortraitOffsets writes by file name and preserves other fields`() {
        writeTemplate(
            """
            {"file": "a.webp", "label": "A",
             "locked": {"x": 0.1, "y": 0.2}}
            """.trimIndent(),
        )
        val json = JSONObject(File(tempDir, "template.json").readText())
        val updated =
            Templates.withPortraitOffsets(
                json,
                mapOf("a.webp" to Pair(0.03f, -0.02f), "missing.webp" to Pair(1f, 1f)),
            )

        val entry = updated.getJSONObject("portraits").getJSONArray("left").getJSONObject(0)
        assertEquals(0.03, entry.getJSONObject("offset").getDouble("x"), 1e-6)
        assertEquals(-0.02, entry.getJSONObject("offset").getDouble("y"), 1e-6)
        assertEquals(0.1, entry.getJSONObject("locked").getDouble("x"), 1e-6)
        assertEquals("A", entry.getString("label"))

        // Round trip through the parser keeps the written offsets
        File(tempDir, "template.json").writeText(updated.toString())
        val def = Templates.loadTemplateFromDir(tempDir)!!
        assertEquals(0.03f, def.left.single().offsetX, 1e-6f)
        assertEquals(-0.02f, def.left.single().offsetY, 1e-6f)
    }
}
