package app.floatdeck.gl

import app.floatdeck.data.PortraitPosition
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure placement-resolution functions
 * ([FloatDeckRenderer.lockedPlacementValues] / [interpolatePlacement]).
 *
 * The invariant: an explicit position (template.json or user override)
 * replaces the legacy layout values entirely, while editor extras apply
 * on top of either source.
 */
class PortraitPlacementTest {
    private val legacy =
        floatArrayOf(
            0.5f, // x
            0.4f, // y
            FloatDeckRenderer.LEGACY_LOCKED_SCALE,
            7.5f, // rotation
        )

    @Test
    fun `explicit position overrides legacy layout`() {
        val position = PortraitPosition(0.25f, 0.75f, rotation = 10f, scale = 1.1f)
        val out =
            FloatDeckRenderer.lockedPlacementValues(
                position,
                legacy,
                extraScale = 1f,
                extraRotation = 0f,
            )
        assertArrayEquals(floatArrayOf(0.25f, 0.75f, 1.1f, 10f), out, 1e-6f)
    }

    @Test
    fun `null position falls back to legacy values`() {
        val out =
            FloatDeckRenderer.unlockedPlacementValues(
                null,
                legacy,
                extraScale = 1f,
                extraRotation = 0f,
            )
        assertArrayEquals(legacy, out, 1e-6f)
    }

    @Test
    fun `editor extras apply on top of both sources`() {
        val position = PortraitPosition(0.25f, 0.75f, rotation = 10f, scale = 1.1f)
        val explicit =
            FloatDeckRenderer.lockedPlacementValues(position, legacy, extraScale = 2f, extraRotation = 5f)
        assertArrayEquals(floatArrayOf(0.25f, 0.75f, 2.2f, 15f), explicit, 1e-6f)

        val fallback =
            FloatDeckRenderer.lockedPlacementValues(null, legacy, extraScale = 2f, extraRotation = 5f)
        assertArrayEquals(
            floatArrayOf(0.5f, 0.4f, FloatDeckRenderer.LEGACY_LOCKED_SCALE * 2f, 12.5f),
            fallback,
            1e-6f,
        )
    }

    @Test
    fun `interpolation hits endpoints at t=0 and t=1`() {
        val locked = floatArrayOf(0.2f, 0.3f, 0.9f, 5f)
        val unlocked = floatArrayOf(0.05f, 0.6f, 0.64f, -7f)

        assertArrayEquals(unlocked, FloatDeckRenderer.interpolatePlacement(locked, unlocked, 0f), 1e-6f)
        assertArrayEquals(locked, FloatDeckRenderer.interpolatePlacement(locked, unlocked, 1f), 1e-6f)
    }

    @Test
    fun `interpolation is clamped and linear at midpoint`() {
        val locked = floatArrayOf(0.2f, 0.3f, 0.9f, 5f)
        val unlocked = floatArrayOf(0.05f, 0.6f, 0.64f, -7f)

        val mid = FloatDeckRenderer.interpolatePlacement(locked, unlocked, 0.5f)
        assertArrayEquals(
            floatArrayOf(0.125f, 0.45f, 0.77f, -1f),
            mid,
            1e-6f,
        )
        // out-of-range t clamps to the nearest endpoint
        assertArrayEquals(
            locked,
            FloatDeckRenderer.interpolatePlacement(locked, unlocked, 3f),
            1e-6f,
        )
    }
}
