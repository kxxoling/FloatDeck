package app.floatdeck.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for optional explicit portrait positions in template.json
 * ([Templates.loadTemplateFromDir]).
 */
class TemplatesPositionTest {
    @TempDir
    lateinit var tempDir: File

    private fun writeTemplate(json: String) {
        File(tempDir, "template.json").writeText(json)
    }

    @Test
    fun `locked and unlocked positions are parsed with per-field defaults`() {
        writeTemplate(
            """
            {
              "id": "t", "name": "T", "wallpaper": "w.webp",
              "portraits": {
                "left": [
                  {
                    "file": "a.webp", "label": "A",
                    "locked":   {"x": 0.3, "y": 0.4, "rotation": 5, "scale": 0.9, "tiltOffset": 1.5},
                    "unlocked": {"x": 0.05, "y": 0.5, "scale": 0.64}
                  }
                ],
                "right": []
              }
            }
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
        assertEquals(0.05f, unlocked!!.xRatio, 1e-6f)
        assertEquals(0f, unlocked.rotation, 1e-6f) // omitted → default
        assertEquals(1f, unlocked.tiltOffset, 1e-6f) // omitted → default
    }

    @Test
    fun `portraits without positions parse to null positions`() {
        writeTemplate(
            """
            {
              "id": "t", "name": "T", "wallpaper": "w.webp",
              "portraits": {
                "left": [{"file": "a.webp", "label": "A"}],
                "right": [{"file": "b.webp", "label": "B"}]
              }
            }
            """.trimIndent(),
        )
        val def = Templates.loadTemplateFromDir(tempDir)
        assertNotNull(def)
        (def!!.left + def.right).forEach { portrait ->
            assertNull(portrait.lockedPosition)
            assertNull(portrait.unlockedPosition)
        }
    }

    @Test
    fun `toTemplateConfigFromDir carries explicit positions`() {
        writeTemplate(
            """
            {
              "id": "t", "name": "T", "wallpaper": "w.webp",
              "portraits": {
                "left": [{"file": "a.webp", "label": "A", "locked": {"x": 0.1, "y": 0.2}}],
                "right": []
              }
            }
            """.trimIndent(),
        )
        val def = Templates.loadTemplateFromDir(tempDir)!!
        val config = Templates.toTemplateConfigFromDir(def, tempDir)
        val portrait = config.portraits.single()
        assertNotNull(portrait.lockedPosition)
        assertEquals(0.1f, portrait.lockedPosition!!.xRatio, 1e-6f)
        assertNull(portrait.unlockedPosition)
    }
}
