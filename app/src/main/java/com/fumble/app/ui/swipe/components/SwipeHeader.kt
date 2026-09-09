package com.fumble.app.ui.swipe.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fumble.app.R
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.ui.common.Wordmark
import com.fumble.app.ui.common.rememberFormattedSize
import com.fumble.app.ui.theme.FumbleInkFaint
import com.fumble.app.ui.theme.FumbleInkMuted

/**
 * Wordmark and progress readout, plus the one control the app has: what you are
 * swiping through.
 *
 * The scope is always on screen rather than hidden behind a menu. Once the deck can be
 * narrowed to a single album, "all caught up" means nothing unless the user can see
 * what it was caught up *with*.
 */
@Composable
fun SwipeHeader(
    remaining: Int,
    freedBytes: Long,
    scope: AlbumScope,
    onPickAlbum: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingLabel = stringResource(R.string.remaining_count, remaining)
    val freedLabel = stringResource(R.string.freed_label, rememberFormattedSize(freedBytes))

    // Neither half is worth a zero. While the first batch loads there is nothing
    // truthful to say, and "0 left" next to an empty state would just be noise.
    val readout = buildList {
        if (remaining > 0) add(remainingLabel)
        if (freedBytes > 0) add(freedLabel)
    }.joinToString("  ·  ")

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Wordmark()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = readout,
                    style = MaterialTheme.typography.bodySmall,
                    color = FumbleInkMuted,
                )
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint = FumbleInkFaint,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onOpenSettings)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
        }

        ScopeChip(
            scope = scope,
            onClick = onPickAlbum,
            modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun ScopeChip(
    scope: AlbumScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unnamed = stringResource(R.string.album_unnamed)
    val label = when (scope) {
        AlbumScope.All -> stringResource(R.string.album_all)
        is AlbumScope.Only -> scope.name.ifBlank { unnamed }
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.cd_pick_album),
                onClick = onClick,
            )
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FumbleInkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = FumbleInkFaint,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp),
        )
    }
}
