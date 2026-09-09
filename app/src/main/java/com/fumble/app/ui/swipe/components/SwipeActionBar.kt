package com.fumble.app.ui.swipe.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fumble.app.R
import com.fumble.app.domain.model.SwipeDirection
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleAccentSoft
import com.fumble.app.ui.theme.FumbleInkFaint
import com.fumble.app.ui.theme.FumbleKeep
import com.fumble.app.ui.theme.FumbleKeepSoft
import com.fumble.app.ui.theme.FumbleTrash
import com.fumble.app.ui.theme.FumbleTrashSoft

/**
 * Trash, keep, and a small undo. Tapping routes through the same [SwipeDirection]
 * pipeline the gesture uses, so a button press produces an identical card animation.
 *
 * @param canUndo undo stays available with an empty deck — that is exactly when a
 *   misfired last swipe most needs taking back.
 * @param undoDepth how many swipes are still reversible. Not drawn, but announced to
 *   screen readers, since the button itself cannot show how far back it reaches.
 * @param enabled whether there is a card to act on at all.
 */
@Composable
fun SwipeActionBar(
    onSwipe: (SwipeDirection) -> Unit,
    onUndo: () -> Unit,
    canUndo: Boolean,
    undoDepth: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleAction(
            icon = Icons.Rounded.Refresh,
            contentDescription = if (undoDepth > 1) {
                stringResource(R.string.cd_undo_count, undoDepth)
            } else {
                stringResource(R.string.cd_undo)
            },
            tint = FumbleInkFaint,
            container = Color.Transparent,
            size = 46.dp,
            iconSize = 20.dp,
            enabled = canUndo,
            mirrored = true,
            onClick = onUndo,
        )

        CircleAction(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_trash),
            tint = FumbleTrash,
            container = FumbleTrashSoft,
            size = 66.dp,
            iconSize = 30.dp,
            enabled = enabled,
            onClick = { onSwipe(SwipeDirection.LEFT) },
        )

        // Smaller than its neighbours on purpose: favouriting is the rare, deliberate
        // action, and it should not compete with the two the user takes all day.
        CircleAction(
            icon = Icons.Rounded.Star,
            contentDescription = stringResource(R.string.cd_favorite),
            tint = FumbleAccent,
            container = FumbleAccentSoft,
            size = 52.dp,
            iconSize = 24.dp,
            enabled = enabled,
            onClick = { onSwipe(SwipeDirection.UP) },
        )

        CircleAction(
            icon = Icons.Rounded.Favorite,
            contentDescription = stringResource(R.string.cd_keep),
            tint = FumbleKeep,
            container = FumbleKeepSoft,
            size = 66.dp,
            iconSize = 28.dp,
            enabled = enabled,
            onClick = { onSwipe(SwipeDirection.RIGHT) },
        )
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    container: Color,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    mirrored: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "actionPress",
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .clip(CircleShape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = tint),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { if (mirrored) scaleX = -1f },
        )
    }
}
