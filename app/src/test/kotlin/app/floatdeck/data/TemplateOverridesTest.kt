package app.floatdeck.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for the template override JSON serialization
 * ([TemplateOverrides.toJson] / [TemplateOverrides.fromJson]).
 */
class TemplateOverridesTest {
    @Test
    fun `round trip keeps background adjustment`() {
        val original =
            TemplateOverride(
                background = BackgroundAdjustment(zoom = 1.5f, offsetX = -0.3f, offsetY = 0.8f),
            )
        val restored = TemplateOverrides.fromJson(TemplateOverrides.toJson(original).toString())
        assertEquals(original, restored)
    }

    @Test
    fun `round trip keeps portrait overrides`() {
        val original =
            TemplateOverride(
                portraits =
                    mapOf(
                        "tmpl_a.webp" to
                            PortraitOverride(
                                lockedPosition = PortraitPosition(0.4f, 0.3f, rotation = 5f, scale = 0.9f, tiltOffset = 1.2f),
                                unlockedPosition = PortraitPosition(0.05f, 0.5f, rotation = -6f, scale = 0.6f),
                                offsetX = 0.02f,
                                offsetY = -0.01f,
                            ),
                        "tmpl_b.webp" to PortraitOverride(offsetX = -0.05f),
                    ),
            )
        val restored = TemplateOverrides.fromJson(TemplateOverrides.toJson(original).toString())
        assertEquals(original, restored)
    }

    @Test
    fun `portrait override with only offsets keeps positions null`() {
        val restored =
            TemplateOverrides.fromJson(
                """{"portraits": {"a": {"offsetX": 0.1, "offsetY": 0.2}}}""",
            )
        val portrait = restored.portraits.getValue("a")
        assertNull(portrait.lockedPosition)
        assertNull(portrait.unlockedPosition)
        assertEquals(0.1f, portrait.offsetX, 1e-6f)
        assertEquals(0.2f, portrait.offsetY, 1e-6f)
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val restored = TemplateOverrides.fromJson("""{}""")
        assertEquals(TemplateOverride(), restored)
    }

    @Test
    fun `position json applies per-field defaults`() {
        val position = TemplateOverrides.positionFromJson(TemplateOverrides.positionToJson(PortraitPosition(0.7f, 0.2f)))
        assertEquals(PortraitPosition(0.7f, 0.2f), position)
        assertEquals(null, TemplateOverrides.positionFromJson(null))
    }
}
