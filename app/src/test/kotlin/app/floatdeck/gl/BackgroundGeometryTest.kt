package app.floatdeck.gl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FloatDeckRenderer.backgroundGeometry] — the pure function
 * that resolves the user background zoom/pan into final draw dimensions.
 *
 * Invariant under test: after applying the user pan, the background must
 * still cover the screen even at maximum parallax offset (no black edges).
 */
class BackgroundGeometryTest {
    @Test
    fun `zoom of 1 with no slack zeroes the pan`() {
        // draw == screen exactly: no room for user pan
        val geo =
            FloatDeckRenderer.backgroundGeometry(
                screenWidth = 1000f,
                screenHeight = 2000f,
                drawWidth = 1000f,
                drawHeight = 2000f,
                maxParallaxOffset = 0f,
                zoom = 1f,
                offsetXFrac = 1f,
                offsetYFrac = -1f,
            )
        assertEquals(1000f, geo[0], 0.001f)
        assertEquals(2000f, geo[1], 0.001f)
        assertEquals(0f, geo[2], 0.001f)
        assertEquals(0f, geo[3], 0.001f)
    }

    @Test
    fun `pan consumes slack left after parallax reserve`() {
        // drawWidth 1200 on a 1000-wide screen → slack (1200-1000)/2 = 100 per side,
        // minus 40 reserved for parallax → 60 available at fraction 1.0
        val geo =
            FloatDeckRenderer.backgroundGeometry(
                screenWidth = 1000f,
                screenHeight = 2000f,
                drawWidth = 1200f,
                drawHeight = 2000f,
                maxParallaxOffset = 40f,
                zoom = 1f,
                offsetXFrac = 1f,
                offsetYFrac = 0f,
            )
        assertEquals(60f, geo[2], 0.001f)
        assertEquals(0f, geo[3], 0.001f)
    }

    @Test
    fun `zoom multiplies dimensions and grows the pan range`() {
        val geo =
            FloatDeckRenderer.backgroundGeometry(
                screenWidth = 1000f,
                screenHeight = 1000f,
                drawWidth = 1000f,
                drawHeight = 1000f,
                maxParallaxOffset = 0f,
                zoom = 2f,
                offsetXFrac = 0.5f,
                offsetYFrac = -1f,
            )
        assertEquals(2000f, geo[0], 0.001f)
        assertEquals(2000f, geo[1], 0.001f)
        // slack = (2000-1000)/2 = 500; half → 250, full negative → -500
        assertEquals(250f, geo[2], 0.001f)
        assertEquals(-500f, geo[3], 0.001f)
    }

    @Test
    fun `zoom below 1 is clamped and pan fraction is clamped`() {
        val geo =
            FloatDeckRenderer.backgroundGeometry(
                screenWidth = 1000f,
                screenHeight = 1000f,
                drawWidth = 1000f,
                drawHeight = 1000f,
                maxParallaxOffset = 0f,
                zoom = 0.2f, // clamped to 1
                offsetXFrac = 7f, // clamped to 1
                offsetYFrac = 0f,
            )
        assertEquals(1000f, geo[0], 0.001f)
        assertEquals(0f, geo[2], 0.001f)
    }
}
