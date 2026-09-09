package com.fumble.app.ui.swipe.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fumble.app.R
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.ui.common.StackGlyph
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkFaint
import com.fumble.app.ui.theme.FumbleInkMuted

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = FumbleAccent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * Shown when there is nothing left to review.
 *
 * Names the album when the deck was narrowed to one, and offers the way back out.
 * Without that, finishing a folder looks identical to finishing the whole device.
 */
@Composable
fun EmptyState(
    scope: AlbumScope,
    onStartOver: () -> Unit,
    onShowAllPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val insideAlbum = scope is AlbumScope.Only
    val unnamed = stringResource(R.string.album_unnamed)
    val body = when (scope) {
        AlbumScope.All -> stringResource(R.string.empty_body)
        is AlbumScope.Only ->
            stringResource(R.string.empty_body_album, scope.name.ifBlank { unnamed })
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StackGlyph(
            color = FumbleInkFaint,
            modifier = Modifier.size(96.dp),
        )
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.displaySmall,
            color = FumbleInk,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = FumbleInkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (insideAlbum) {
            SoftButton(
                label = stringResource(R.string.empty_all_albums_cta),
                onClick = onShowAllPhotos,
                modifier = Modifier.padding(top = 28.dp),
            )
        } else {
            SoftButton(
                label = stringResource(R.string.empty_reset_cta),
                onClick = onStartOver,
                modifier = Modifier.padding(top = 28.dp),
            )
        }
    }
}

/** A borderless, tinted pill. The only button shape in the app. */
@Composable
fun SoftButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = FumbleAccent,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = contentColor,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
    )
}
