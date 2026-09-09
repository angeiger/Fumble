package com.fumble.app.ui.swipe.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp

/** How far in a double tap goes, and the ceiling for a pinch. */
private const val DOUBLE_TAP_SCALE = 2.6f
private const val MAX_SCALE = 6f

/**
 * Zoom and pan of the photograph *inside* a card.
 *
 * Deliberately separate from the card's own position: a zoomed image being dragged
 * around must not look like a card being swiped away, and the two transforms apply at
 * different levels of the layout.
 *
 * [isZoomed] is the switch the gesture handler reads. While it is true the card refuses
 * to swipe, because a finger dragging across a magnified photo means "show me the rest
 * of it", not "throw this away".
 */
@Stable
class PhotoZoomState {

    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** A hair above 1 so floating-point dust after a pinch-out cannot lock swiping. */
    val isZoomed: Boolean get() = scale > 1.01f

    private var containerSize: Size = Size.Zero

    fun onSized(size: Size) {
        containerSize = size
    }

    /**
     * Applies one frame of a pinch.
     *
     * [centroid] is kept pinned to the same part of the photo, which is what makes
     * zooming feel like moving paper rather than moving a camera.
     */
    fun transform(centroid: Offset, panChange: Offset, zoomChange: Float) {
        val target = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)

        // Where the centroid sits on the image right now, in unscaled image space.
        val focus = (centroid - center - offset) / scale

        scale = target
        offset = clamp(centroid - center - focus * target + panChange, target)
    }

    fun pan(delta: Offset) {
        offset = clamp(offset + delta, scale)
    }

    /** Toggles between fully out and [DOUBLE_TAP_SCALE], centred on the tapped point. */
    suspend fun toggleAt(point: Offset) {
        if (isZoomed) {
            animate(targetScale = 1f, targetOffset = Offset.Zero)
        } else {
            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
            animate(
                targetScale = DOUBLE_TAP_SCALE,
                targetOffset = clamp(
                    (center - point) * (DOUBLE_TAP_SCALE - 1f),
                    DOUBLE_TAP_SCALE,
                ),
            )
        }
    }

    /** Eases back to fully zoomed out. Used before a card leaves the stack. */
    suspend fun settle() {
        if (!isZoomed && offset == Offset.Zero) return
        animate(targetScale = 1f, targetOffset = Offset.Zero)
    }

    /**
     * One 0..1 driver interpolating both values, so scale and offset cannot drift out
     * of step and leave the image clamped against an edge it should not be at.
     */
    private suspend fun animate(targetScale: Float, targetOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        ) {
            scale = lerp(fromScale, targetScale, value)
            offset = lerp(fromOffset, targetOffset, value)
        }
        scale = targetScale
        offset = targetOffset
    }

    /**
     * Keeps the photo from being dragged off its own card. At scale 1 there is nowhere
     * to go, so the allowance is zero and the image stays centred.
     */
    private fun clamp(candidate: Offset, forScale: Float): Offset {
        if (containerSize == Size.Zero) return Offset.Zero
        val maxX = (containerSize.width * (forScale - 1f)) / 2f
        val maxY = (containerSize.height * (forScale - 1f)) / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }
}

/** One zoom state per photo, reset automatically when the card is replaced. */
@Composable
fun rememberPhotoZoomState(key: Any): PhotoZoomState = remember(key) { PhotoZoomState() }
