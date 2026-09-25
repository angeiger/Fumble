package com.fumble.app.ui.swipe.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fumble.app.R
import com.fumble.app.ui.common.rememberFormattedSize
import com.fumble.app.ui.theme.FumbleInkMuted
import com.fumble.app.ui.theme.FumbleTrash
import com.fumble.app.ui.theme.FumbleTrashSoft

/**
 * The queue of left swipes that have not reached the system trash yet.
 *
 * It exists because trashing photos this app does not own needs the user's approval,
 * and asking once per swipe would make the app unusable. This is the honest version of
 * that trade: the count is always visible, and tapping empties it immediately rather
 * than waiting for the batch to fill.
 */
@Composable
fun PendingTrashPill(
    count: Int,
    bytes: Long,
    favoriteCount: Int,
    onEmptyNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = count > 0 || favoriteCount > 0
    // By the time the pill fades out, the counts have already dropped to zero. Keep
    // showing the last real ones instead of "0 to favourite" for the length of the fade.
    var shown by remember { mutableStateOf(Pending(count, bytes, favoriteCount)) }
    if (visible) shown = Pending(count, bytes, favoriteCount)

    val size = rememberFormattedSize(shown.bytes)
    val label = when {
        shown.count > 0 && shown.favoriteCount > 0 ->
            stringResource(R.string.pending_both, shown.count, shown.favoriteCount)

        shown.count > 0 -> stringResource(R.string.pending_trash, shown.count, size)
        else -> stringResource(R.string.pending_favorites, shown.favoriteCount)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(CircleShape)
                .background(FumbleTrashSoft)
                .clickable(role = Role.Button, onClick = onEmptyNow)
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(FumbleTrash)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = FumbleInkMuted,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Text(
                text = stringResource(R.string.pending_trash_cta),
                style = MaterialTheme.typography.labelSmall,
                color = FumbleTrash,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private data class Pending(val count: Int, val bytes: Long, val favoriteCount: Int)
