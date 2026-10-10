package app.floatdeck.gl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FloatDeckRenderer.parallaxOffsets] — the pure function
 * shared by the background and portrait parallax computations.
 *
 * Guards against regressions when the sensitivity multiplier is introduced:
 *  - the sensor input stays clamped to ±0.5 so extreme tilts cannot produce
 *    huge offsets;
 *  - the sensitivity multiplier scales the offset linearly;
 *  - the default (1.0) reproduces the historical hardcoded behavior.
 */
class ParallaxOffsetsTest {
    @Test
    fun `default sensitivity reproduces historical offsets`() {
        // Old code: pitch 0.3 → 0.3 * 1080 * 0.1 = 32.4 px
        val offsets =
            FloatDeckRenderer.parallaxOffsets(
                pitch = 0.3f,
                roll = -0.2f,
                screenWidth = 1080f,
                screenHeight = 2400f,
                xMultiplier = FloatDeckRenderer.BACKGROUND_PARALLAX_X,
                yMultiplier = FloatDeckRenderer.BACKGROUND_PARALLAX_Y,
                sensitivity = 1f,
            )
        assertEquals(32.4f, offsets[0], 0.001f)
        assertEquals(-0.2f * 2400f * 0.075f, offsets[1], 0.001f)
    }

    @Test
    fun `sensitivity scales offsets linearly`() {
        val base =
            FloatDeckRenderer.parallaxOffsets(0.4f, 0f, 1000f, 2000f, 0.1f, 0.1f, 1f)
        val doubled =
            FloatDeckRenderer.parallaxOffsets(0.4f, 0f, 1000f, 2000f, 0.1f, 0.1f, 2f)
        assertEquals(base[0] * 2f, doubled[0], 0.001f)
        assertEquals(0f, base[1], 0.001f)
    }

    @Test
    fun `sensor input is clamped before sensitivity is applied`() {
        // Tilt of 5.0 must clamp to 0.5 first, so a 3x sensitivity cannot
        // produce more than 3 * 0.5 * screen * multiplier.
        val offsets =
            FloatDeckRenderer.parallaxOffsets(5f, -5f, 1000f, 1000f, 0.25f, 0.2f, 3f)
        assertEquals(0.5f * 3f * 1000f * 0.25f, offsets[0], 0.001f)
        assertEquals(-0.5f * 3f * 1000f * 0.2f, offsets[1], 0.001f)
    }
}
