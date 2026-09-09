package com.fumble.app.ui.swipe

import com.fumble.app.domain.model.SwipeDirection
import kotlin.math.abs

/**
 * The decision rules behind a card gesture, kept free of Compose so they can be
 * unit tested without a device.
 */
object SwipeGeometry {

    /** Fraction of the card's width a sideways drag must cover to count. */
    const val DISMISS_FRACTION = 0.30f

    /**
     * Fraction of the card's *height* an upward drag must cover.
     *
     * Smaller than the sideways fraction relative to the travel available, because a
     * thumb pushing up has far less room than one sweeping across.
     */
    const val UP_FRACTION = 0.18f

    /** A flick faster than this (px/s) commits regardless of how far it travelled. */
    const val FLING_VELOCITY = 900f

    /** Cards rotate at most this much at full sideways drag. */
    const val MAX_ROTATION_DEGREES = 11f

    /**
     * Which way the card is leaning, as a fraction of the distance needed to commit.
     *
     * Returning both axes lets the caller draw all three overlays from one number pair
     * without recomputing thresholds, and lets the cards behind rise in response to any
     * direction rather than only sideways ones.
     */
    fun lean(
        offsetX: Float,
        offsetY: Float,
        dismissDistance: Float,
        upDistance: Float,
    ): Lean = Lean(
        horizontal = (offsetX / dismissDistance).coerceIn(-1f, 1f),
        up = (-offsetY / upDistance).coerceIn(-1f, 1f),
    )

    data class Lean(val horizontal: Float, val up: Float) {
        /**
         * Up wins ties on purpose: a deliberate upward flick usually drifts sideways a
         * little, while a sideways swipe rarely climbs.
         */
        val isUpward: Boolean get() = up > abs(horizontal)

        /** How far this gesture is towards committing, in any direction. */
        val magnitude: Float get() = maxOf(abs(horizontal), up.coerceAtLeast(0f))
    }

    /**
     * Whether releasing here should dismiss the card.
     *
     * Distance alone is not enough: a short, fast flick is a deliberate decision and
     * users expect it to land. But a fast release that pushes *back* towards centre is
     * the opposite — someone changing their mind — and must never commit, least of all
     * to a trash decision the user was trying to take back.
     */
    fun shouldCommit(
        offsetX: Float,
        offsetY: Float,
        dismissDistance: Float,
        upDistance: Float,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        val lean = lean(offsetX, offsetY, dismissDistance, upDistance)

        if (lean.isUpward) {
            // Only a genuine upward flick counts; velocity downwards cancels.
            if (abs(velocityY) >= FLING_VELOCITY) return velocityY < 0f
            return lean.up >= 1f
        }

        if (abs(velocityX) >= FLING_VELOCITY) return offsetX * velocityX > 0f
        return abs(lean.horizontal) >= 1f
    }

    /**
     * Which way a committed card leaves. Whenever [shouldCommit] says yes, the card's
     * position and the release velocity agree, so position alone is enough.
     */
    fun directionOf(
        offsetX: Float,
        offsetY: Float,
        dismissDistance: Float,
        upDistance: Float,
    ): SwipeDirection {
        val lean = lean(offsetX, offsetY, dismissDistance, upDistance)
        return when {
            lean.isUpward -> SwipeDirection.UP
            offsetX >= 0f -> SwipeDirection.RIGHT
            else -> SwipeDirection.LEFT
        }
    }
}
