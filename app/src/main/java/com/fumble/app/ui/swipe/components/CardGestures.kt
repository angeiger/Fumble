package com.fumble.app.ui.swipe.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity

/** Two taps closer together than this toggle the zoom. */
private const val DOUBLE_TAP_WINDOW_MS = 280L

/** A press held longer than this is not a tap, however little it moved. */
private const val TAP_TIMEOUT_MS = 400L

/**
 * Everything one card does with fingers, in a single detector.
 *
 * Pinch and swipe compete for the same input, so they cannot be two independent gesture
 * detectors racing to consume events. One handler decides, per gesture, which it is:
 *
 * - Two fingers down at any point → a transform. Zoom and pan the photo.
 * - One finger while already zoomed → pan the photo. A drag across a magnified image
 *   means "show me the rest of it", not "throw this away".
 * - One finger at rest scale → swipe the card, exactly as it behaved before zoom
 *   existed, touch slop and release velocity included.
 *
 * A press that never leaves the touch slop and ends quickly is a tap; two in quick
 * succession toggle the zoom.
 */
suspend fun PointerInputScope.detectCardGestures(
    zoom: PhotoZoomState,
    onSwipeDelta: (Offset) -> Unit,
    onSwipeEnd: (velocity: Velocity) -> Unit,
    onSwipeCancel: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
) {
    var lastTapUptime = 0L

    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()

        var isTransform = false
        var pastSlop = false
        var slopAccumulator = Offset.Zero
        var lastUptime = first.uptimeMillis

        while (true) {
            val event = awaitPointerEvent()
            event.changes.firstOrNull()?.let { lastUptime = it.uptimeMillis }
            if (event.changes.none { it.pressed }) break

            // Two fingers commit the gesture to transforming for good. Lifting back to
            // one keeps it there, so a pinch that ends one finger at a time never turns
            // into an accidental swipe.
            if (event.changes.count { it.pressed } >= 2) isTransform = true

            val panChange = event.calculatePan()

            when {
                isTransform -> {
                    zoom.transform(
                        centroid = event.calculateCentroid(useCurrent = false),
                        panChange = panChange,
                        zoomChange = event.calculateZoom(),
                    )
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }

                zoom.isZoomed -> {
                    zoom.pan(panChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }

                else -> {
                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                    if (!change.positionChanged()) continue

                    if (pastSlop) {
                        tracker.addPointerInputChange(change)
                        onSwipeDelta(panChange)
                        change.consume()
                    } else {
                        // Hold the card still until the finger has clearly committed,
                        // the same threshold the stock drag detector applies.
                        slopAccumulator += panChange
                        if (slopAccumulator.getDistance() > viewConfiguration.touchSlop) {
                            pastSlop = true
                            tracker.addPointerInputChange(change)
                            change.consume()
                        }
                    }
                }
            }
        }

        val wasTap = !isTransform &&
            !pastSlop &&
            slopAccumulator.getDistance() <= viewConfiguration.touchSlop &&
            (lastUptime - first.uptimeMillis) <= TAP_TIMEOUT_MS

        when {
            wasTap -> {
                if (lastUptime - lastTapUptime <= DOUBLE_TAP_WINDOW_MS) {
                    lastTapUptime = 0L
                    onDoubleTap(first.position)
                } else {
                    lastTapUptime = lastUptime
                }
            }

            pastSlop -> onSwipeEnd(tracker.calculateVelocity())

            // A transform simply ends where the user left it; nothing to settle.
            isTransform || zoom.isZoomed -> Unit

            else -> onSwipeCancel()
        }
    }
}
