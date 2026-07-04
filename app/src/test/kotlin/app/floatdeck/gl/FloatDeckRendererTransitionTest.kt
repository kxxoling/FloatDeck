package app.floatdeck.gl

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the lock/unlock transition state machine in [FloatDeckRenderer].
 *
 * These tests exercise [FloatDeckRenderer.updateTransition],
 * [FloatDeckRenderer.triggerLock], and [FloatDeckRenderer.triggerUnlock] —
 * pure-CPU logic that must behave correctly even when the GL thread is not
 * rendering (e.g. while the screen is off).
 *
 * A relaxed-mock Context is used because the constructor requires one, but
 * the transition logic never touches it.
 *
 * Background — the bugs these tests guard against:
 *  - triggerLock snapping transitionProgress to 1f caused an abrupt visual
 *    jump when the screen turned on (the lerp was bypassed).
 *  - updateTransition was only called from onDrawFrame, so when the GL
 *    thread was paused the transition froze; by the time rendering resumed
 *    the state was stale.
 */
class FloatDeckRendererTransitionTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var renderer: FloatDeckRenderer

    @BeforeEach
    fun setUp() {
        renderer = FloatDeckRenderer(context)
    }

    /**
     * Consume the one-time isFirstFrame snap so subsequent calls exercise
     * the normal lerp path (8 % per call).
     */
    private fun consumeFirstFrame() {
        renderer.updateTransition()
    }

    // ==================================================================
    // triggerLock — must set target, must NOT snap progress
    // ==================================================================

    @Test
    fun `triggerLock sets target to locked without snapping progress`() {
        // Fresh renderer: progress 0, target 0 (unlocked / edges).
        assertEquals(0f, renderer.transitionProgress)

        renderer.triggerLock()

        assertEquals(1f, renderer.targetTransition)
        assertTrue(
            renderer.transitionProgress < 0.1f,
            "transitionProgress must not snap — it should lerp over many frames, " +
                "got ${renderer.transitionProgress}",
        )
    }

    @Test
    fun `progress lerps gradually after triggerLock (regression for instant snap)`() {
        consumeFirstFrame()
        renderer.transitionProgress = 0f

        renderer.triggerLock()
        renderer.updateTransition()

        assertTrue(
            renderer.transitionProgress in 0.001f..0.2f,
            "Progress should have moved ~8 % toward target, not snapped. " +
                "Got ${renderer.transitionProgress}",
        )
    }

    // ==================================================================
    // updateTransition — lerp factor and convergence
    // ==================================================================

    @Test
    fun `updateTransition moves 8 percent toward target per call`() {
        consumeFirstFrame()
        renderer.transitionProgress = 0f
        renderer.targetTransition = 1f

        renderer.updateTransition()

        assertEquals(0.08f, renderer.transitionProgress, 0.001f)
    }

    @Test
    fun `updateTransition converges to locked target within 60 calls`() {
        consumeFirstFrame()
        renderer.transitionProgress = 0f
        renderer.targetTransition = 1f

        repeat(60) { renderer.updateTransition() }

        assertEquals(1f, renderer.transitionProgress, 0.001f)
    }

    @Test
    fun `updateTransition never overshoots target`() {
        consumeFirstFrame()
        renderer.transitionProgress = 0f
        renderer.targetTransition = 1f

        repeat(100) {
            renderer.updateTransition()
            assertTrue(
                renderer.transitionProgress <= 1.0f,
                "Progress must never overshoot: got ${renderer.transitionProgress}",
            )
        }
    }

    // ==================================================================
    // triggerUnlock — delay then transition to unlocked
    // ==================================================================

    @Test
    fun `triggerUnlock leaves target unchanged during delay window`() {
        // Start from locked state.
        renderer.targetTransition = 1f
        renderer.transitionProgress = 1f

        renderer.triggerUnlock()

        // unlockDelaySeconds = 0.1f; each call adds 0.016f.
        // 6 calls × 0.016 = 0.096 < 0.1 → still waiting.
        repeat(6) { renderer.updateTransition() }
        assertEquals(
            1f,
            renderer.targetTransition,
            "Target must remain locked during the unlock delay",
        )
    }

    @Test
    fun `triggerUnlock flips target to unlocked after delay`() {
        renderer.targetTransition = 1f
        renderer.transitionProgress = 1f

        renderer.triggerUnlock()

        // 7 calls × 0.016 = 0.112 ≥ 0.1 → delay elapses, target flips.
        repeat(7) { renderer.updateTransition() }
        assertEquals(0f, renderer.targetTransition)
    }

    @Test
    fun `triggerUnlock eventually returns progress to edges`() {
        renderer.targetTransition = 1f
        renderer.transitionProgress = 1f

        renderer.triggerUnlock()

        // 7 calls for delay + ~60 calls for lerp = 67; use 70 for margin.
        repeat(70) { renderer.updateTransition() }

        assertEquals(0f, renderer.transitionProgress, 0.001f)
    }

    // ==================================================================
    // triggerLock cancels pending unlock
    // ==================================================================

    @Test
    fun `triggerLock cancels a pending unlock delay`() {
        renderer.targetTransition = 1f
        renderer.transitionProgress = 1f

        renderer.triggerUnlock()
        repeat(3) { renderer.updateTransition() } // part-way through delay

        renderer.triggerLock()

        assertEquals(1f, renderer.targetTransition)

        // Even after many more calls, target must stay at 1 (no delayed flip to 0).
        repeat(20) { renderer.updateTransition() }
        assertEquals(1f, renderer.targetTransition)
    }

    // ==================================================================
    // Full lock → unlock cycle
    // ==================================================================

    @Test
    fun `lock then unlock full cycle returns progress to edges`() {
        // --- Lock phase ---
        renderer.triggerLock()
        assertEquals(1f, renderer.targetTransition)
        repeat(60) { renderer.updateTransition() }
        assertEquals(1f, renderer.transitionProgress, 0.001f)

        // --- Unlock phase ---
        renderer.triggerUnlock()
        repeat(70) { renderer.updateTransition() }
        assertEquals(0f, renderer.transitionProgress, 0.001f)
    }

    @Test
    fun `repeated lock unlock cycles are stable`() {
        repeat(5) {
            renderer.triggerLock()
            repeat(60) { renderer.updateTransition() }
            assertEquals(1f, renderer.transitionProgress, 0.01f)

            renderer.triggerUnlock()
            repeat(70) { renderer.updateTransition() }
            assertEquals(0f, renderer.transitionProgress, 0.01f)
        }
    }

    // ==================================================================
    // isFirstFrame snap
    // ==================================================================

    @Test
    fun `first updateTransition snaps progress to current target`() {
        renderer.targetTransition = 1f
        assertEquals(0f, renderer.transitionProgress)

        renderer.updateTransition()

        assertEquals(1f, renderer.transitionProgress)
    }

    @Test
    fun `subsequent calls after first frame use lerp not snap`() {
        // First call snaps to initial target (0).
        renderer.updateTransition()

        // Now change target — lerp should apply, not snap.
        renderer.targetTransition = 1f
        renderer.updateTransition()

        assertEquals(0.08f, renderer.transitionProgress, 0.001f)
    }
}
