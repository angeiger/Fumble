package com.fumble.app.ui.swipe.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.fumble.app.R
import com.fumble.app.domain.model.Photo
import com.fumble.app.domain.model.SwipeDirection
import com.fumble.app.ui.swipe.SwipeGeometry
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleKeep
import com.fumble.app.ui.theme.FumbleSurface
import com.fumble.app.ui.theme.FumbleTrash
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Cards kept in the composition. The hindmost one exists mainly to warm its bitmap. */
private const val VISIBLE_CARDS = 3

/** How far each card behind the top one peeks out below it. */
private val CardStep = 16.dp

private const val SCALE_STEP = 0.045f
private const val ALPHA_STEP = 0.15f
private const val EXIT_DURATION_MS = 280

private val ReturnSpring = spring<Offset>(
    dampingRatio = 0.62f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * Lets the action buttons drive the same animation the finger does, so a tap and a
 * swipe are literally the same motion.
 */
@Stable
class SwipeStackState internal constructor() {

    internal var command by mutableStateOf<SwipeDirection?>(null)

    /** True while a card is flying off screen. The action bar disables itself on it. */
    var isSettling by mutableStateOf(false)
        internal set

    fun swipe(direction: SwipeDirection) {
        if (!isSettling) command = direction
    }
}

@Composable
fun rememberSwipeStackState(): SwipeStackState = remember { SwipeStackState() }

/**
 * The card stack.
 *
 * [photos] is expected to be top-card-first. Only the first [VISIBLE_CARDS] are drawn,
 * each one keyed by its MediaStore id so promoting a card keeps its already-decoded
 * bitmap instead of remounting it.
 */
@Composable
fun SwipeCardStack(
    photos: List<Photo>,
    state: SwipeStackState,
    onDecision: (Photo, SwipeDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val dismissDistance = widthPx * SwipeGeometry.DISMISS_FRACTION
        val upDistance = heightPx * SwipeGeometry.UP_FRACTION
        val exitDistance = widthPx * 1.15f
        val exitDistanceUp = heightPx * 1.15f

        // Written by the top card, read by the cards behind it so they rise into place
        // as it is dragged away. A plain float state read only inside graphicsLayer /
        // drawBehind lambdas, so dragging never triggers recomposition.
        val topProgress = remember { mutableFloatStateOf(0f) }

        // Cards that have finished flying out but whose removal has not come back down
        // from the ViewModel yet.
        //
        // Promoting the next card has to happen in the same frame that resets
        // `topProgress`, otherwise the second card renders one frame at its old depth
        // and visibly dips. A round trip through the state flow is a frame or two too
        // slow, so the stack drops the card locally and lets the authoritative list
        // catch up. Ids leave this set only once [photos] no longer contains them,
        // which is also what makes an undo re-deal work.
        var dismissedIds: Set<Long> by remember { mutableStateOf(emptySet()) }

        LaunchedEffect(photos) {
            if (dismissedIds.isEmpty()) return@LaunchedEffect
            val present = photos.mapTo(HashSet(photos.size)) { it.id }
            dismissedIds = dismissedIds.intersect(present)
        }

        val visible = remember(photos, dismissedIds) {
            photos.asSequence()
                .filter { it.id !in dismissedIds }
                .take(VISIBLE_CARDS)
                .toList()
        }

        // Reversed so the top card is composed last and therefore draws on top.
        visible.withIndex().toList().asReversed().forEach { (depth, photo) ->
            key(photo.id) {
                StackCard(
                    photo = photo,
                    depth = depth,
                    isTop = depth == 0,
                    state = state,
                    topProgress = topProgress,
                    dismissDistance = dismissDistance,
                    upDistance = upDistance,
                    exitDistance = exitDistance,
                    exitDistanceUp = exitDistanceUp,
                    onDecision = { decided, direction ->
                        dismissedIds = dismissedIds + decided.id
                        onDecision(decided, direction)
                    },
                )
            }
        }
    }
}

@Composable
private fun StackCard(
    photo: Photo,
    depth: Int,
    isTop: Boolean,
    state: SwipeStackState,
    topProgress: MutableFloatState,
    dismissDistance: Float,
    upDistance: Float,
    exitDistance: Float,
    exitDistanceUp: Float,
    onDecision: (Photo, SwipeDirection) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoom = rememberPhotoZoomState(photo.id)
    val stepPx = with(LocalDensity.current) { CardStep.toPx() }

    /** Which way this card is leaning, and how far, in each direction that matters. */
    fun lean(): SwipeGeometry.Lean = SwipeGeometry.lean(
        offsetX = offset.value.x,
        offsetY = offset.value.y,
        dismissDistance = dismissDistance,
        upDistance = upDistance,
    )

    suspend fun flyOut(direction: SwipeDirection) {
        state.isSettling = true
        try {
            // Send it away at rest scale. A card leaving mid-zoom reads as a glitch,
            // and the decision was about the photo, not about the crop being looked at.
            zoom.settle()
            offset.animateTo(
                targetValue = Offset(
                    x = direction.signX * exitDistance,
                    y = if (direction == SwipeDirection.UP) {
                        -exitDistanceUp
                    } else {
                        offset.value.y - stepPx * 2f
                    },
                ),
                animationSpec = tween(EXIT_DURATION_MS, easing = FastOutLinearInEasing),
            )
            // Reset before handing the decision up: the card behind is about to become
            // the top card and must not inherit this card's drag progress.
            topProgress.floatValue = 0f
            onDecision(photo, direction)
        } finally {
            state.isSettling = false
        }
    }

    LaunchedEffect(isTop, dismissDistance, upDistance) {
        if (!isTop) return@LaunchedEffect

        // Discard anything the buttons queued while this card was still buried.
        state.command = null

        launch {
            // Magnitude, not a signed value: the cards behind should rise for any
            // direction, including a swipe straight up.
            snapshotFlow { offset.value }.collect { current ->
                topProgress.floatValue = SwipeGeometry.lean(
                    offsetX = current.x,
                    offsetY = current.y,
                    dismissDistance = dismissDistance,
                    upDistance = upDistance,
                ).magnitude
            }
        }

        snapshotFlow { state.command }
            .filterNotNull()
            .collect { direction ->
                state.command = null
                flyOut(direction)
            }
    }

    val gesture = if (isTop) {
        Modifier.pointerInput(photo.id, dismissDistance) {
            detectCardGestures(
                zoom = zoom,
                onSwipeDelta = { delta ->
                    scope.launch { offset.snapTo(offset.value + delta) }
                },
                onSwipeEnd = { velocity ->
                    val committed = SwipeGeometry.shouldCommit(
                        offsetX = offset.value.x,
                        offsetY = offset.value.y,
                        dismissDistance = dismissDistance,
                        upDistance = upDistance,
                        velocityX = velocity.x,
                        velocityY = velocity.y,
                    )
                    scope.launch {
                        if (committed) {
                            flyOut(
                                SwipeGeometry.directionOf(
                                    offsetX = offset.value.x,
                                    offsetY = offset.value.y,
                                    dismissDistance = dismissDistance,
                                    upDistance = upDistance,
                                )
                            )
                        } else {
                            offset.animateTo(Offset.Zero, ReturnSpring)
                        }
                    }
                },
                onSwipeCancel = {
                    scope.launch { offset.animateTo(Offset.Zero, ReturnSpring) }
                },
                onDoubleTap = { point ->
                    scope.launch { zoom.toggleAt(point) }
                },
            )
        }
    } else {
        Modifier
    }

    val transform = if (isTop) {
        Modifier.graphicsLayer {
            translationX = offset.value.x
            translationY = offset.value.y
            rotationZ = lean().horizontal * SwipeGeometry.MAX_ROTATION_DEGREES
            // Pivot below the card so it swings rather than spins.
            transformOrigin = TransformOrigin(0.5f, 1.15f)
        }
    } else {
        Modifier.graphicsLayer {
            val p = abs(topProgress.floatValue)
            val scale = lerp(scaleAt(depth), scaleAt(depth - 1), p)
            scaleX = scale
            scaleY = scale
            translationY = lerp(depth * stepPx, (depth - 1) * stepPx, p)
            alpha = lerp(alphaAt(depth), alphaAt(depth - 1), p)
            // Anchored at the bottom edge so the peek below the top card is exactly
            // CardStep regardless of how tall the stack ends up being.
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }

    PhotoCard(
        photo = photo,
        elevation = if (isTop) 20.dp else 10.dp,
        zoom = if (isTop) zoom else null,
        // Gesture first, transform second: the pointer node then sits above the layer,
        // so drag deltas arrive in untransformed space and the card's rotation does not
        // bleed a sideways drag into vertical movement.
        modifier = Modifier
            .fillMaxSize()
            .then(gesture)
            .then(transform),
        overlay = {
            if (isTop) SwipeOverlay(lean = { lean() })
        },
    )
}

/** The colour wash and the three badges that tell the user what the release will do. */
@Composable
private fun BoxScope.SwipeOverlay(lean: () -> SwipeGeometry.Lean) {
    // Hoisted: these are composable getters reading the active palette, and a
    // drawBehind lambda is not composition.
    val keep = FumbleKeep
    val trash = FumbleTrash
    val favorite = FumbleAccent

    Box(
        Modifier
            .matchParentSize()
            .drawBehind {
                val l = lean()
                val strength = l.magnitude
                if (strength == 0f) return@drawBehind
                drawRect(
                    color = when {
                        l.isUpward -> favorite
                        l.horizontal > 0f -> keep
                        else -> trash
                    },
                    alpha = strength * 0.22f,
                )
            }
    )

    // Only the dominant direction is announced. Showing two badges during a diagonal
    // drag would leave the user guessing which one the release will pick.
    SwipeBadge(
        label = stringResource(R.string.overlay_keep),
        color = FumbleKeep,
        alignment = Alignment.TopStart,
        alpha = { lean().let { if (it.isUpward) 0f else it.horizontal.coerceAtLeast(0f) } },
    )
    SwipeBadge(
        label = stringResource(R.string.overlay_trash),
        color = FumbleTrash,
        alignment = Alignment.TopEnd,
        alpha = { lean().let { if (it.isUpward) 0f else (-it.horizontal).coerceAtLeast(0f) } },
    )
    SwipeBadge(
        label = stringResource(R.string.overlay_favorite),
        color = FumbleAccent,
        alignment = Alignment.BottomCenter,
        alpha = { lean().let { if (it.isUpward) it.up.coerceAtLeast(0f) else 0f } },
    )
}

@Composable
private fun BoxScope.SwipeBadge(
    label: String,
    color: Color,
    alignment: Alignment,
    alpha: () -> Float,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .align(alignment)
            .padding(20.dp)
            .graphicsLayer {
                val a = alpha().coerceIn(0f, 1f)
                this.alpha = a
                scaleX = 0.88f + 0.12f * a
                scaleY = 0.88f + 0.12f * a
            }
            .background(FumbleSurface.copy(alpha = 0.92f), CircleShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private fun scaleAt(depth: Int): Float = 1f - depth.coerceAtLeast(0) * SCALE_STEP

private fun alphaAt(depth: Int): Float = 1f - depth.coerceAtLeast(0) * ALPHA_STEP
