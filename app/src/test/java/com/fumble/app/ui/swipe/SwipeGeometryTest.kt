package com.fumble.app.ui.swipe

import com.fumble.app.domain.model.Decision
import com.fumble.app.domain.model.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGeometryTest {

    private val dismiss = 300f
    private val up = 200f

    private fun commit(
        x: Float,
        y: Float = 0f,
        vx: Float = 0f,
        vy: Float = 0f,
    ) = SwipeGeometry.shouldCommit(x, y, dismiss, up, vx, vy)

    private fun direction(x: Float, y: Float = 0f) =
        SwipeGeometry.directionOf(x, y, dismiss, up)

    // --- Sideways ----------------------------------------------------------

    @Test
    fun `a small slow drag springs back`() {
        assertFalse(commit(x = 120f, vx = 50f))
    }

    @Test
    fun `a slow drag past the threshold commits`() {
        assertTrue(commit(x = 320f, vx = 20f))
    }

    @Test
    fun `a short fast flick commits`() {
        assertTrue(commit(x = 60f, vx = 2400f))
    }

    /** The case that would otherwise trash a photo the user was rescuing. */
    @Test
    fun `flicking back towards centre cancels even from beyond the threshold`() {
        assertFalse(commit(x = 340f, vx = -2400f))
    }

    @Test
    fun `a resting card does not commit`() {
        assertFalse(commit(x = 0f))
    }

    @Test
    fun `direction follows the card position`() {
        assertEquals(SwipeDirection.RIGHT, direction(x = 320f))
        assertEquals(SwipeDirection.LEFT, direction(x = -320f))
    }

    // --- Upward ------------------------------------------------------------

    @Test
    fun `a drag straight up past the threshold favourites`() {
        assertTrue(commit(x = 0f, y = -220f))
        assertEquals(SwipeDirection.UP, direction(x = 0f, y = -220f))
    }

    @Test
    fun `a short upward drag springs back`() {
        assertFalse(commit(x = 0f, y = -80f))
    }

    @Test
    fun `a fast upward flick commits without the distance`() {
        assertTrue(commit(x = 0f, y = -40f, vy = -2000f))
    }

    /** Downward is not a gesture; it must never be mistaken for a favourite. */
    @Test
    fun `dragging downwards never commits`() {
        assertFalse(commit(x = 0f, y = 260f))
        assertFalse(commit(x = 0f, y = 40f, vy = 2000f))
    }

    /**
     * A thumb pushing up nearly always drifts sideways. Up has to win those, or
     * favouriting would be almost impossible to hit.
     */
    @Test
    fun `an upward drag that drifts sideways still favourites`() {
        assertEquals(SwipeDirection.UP, direction(x = 70f, y = -220f))
        assertTrue(commit(x = 70f, y = -220f))
    }

    @Test
    fun `a sideways drag that drifts upward still swipes sideways`() {
        assertEquals(SwipeDirection.RIGHT, direction(x = 320f, y = -20f))
    }

    // --- Mapping -----------------------------------------------------------

    @Test
    fun `each direction maps to its decision`() {
        assertEquals(Decision.TRASH, SwipeDirection.LEFT.decision)
        assertEquals(Decision.KEEP, SwipeDirection.RIGHT.decision)
        assertEquals(Decision.FAVORITE, SwipeDirection.UP.decision)
    }
}
