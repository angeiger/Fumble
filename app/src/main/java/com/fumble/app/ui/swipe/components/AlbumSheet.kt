package com.fumble.app.ui.swipe.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fumble.app.R
import com.fumble.app.domain.model.Album
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkFaint
import com.fumble.app.ui.theme.FumbleInkMuted
import com.fumble.app.ui.theme.FumbleSurface

/**
 * Album picker.
 *
 * Counts are of photos still awaiting a decision, not of everything in the folder, so
 * the list doubles as a progress readout. Albums with nothing left in them are already
 * filtered out upstream — offering a folder that would immediately say "all caught up"
 * is only a way to waste a tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSheet(
    albums: List<Album>,
    scope: AlbumScope,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FumbleSurface,
        dragHandle = null,
    ) {
        Text(
            text = stringResource(R.string.album_sheet_title),
            style = MaterialTheme.typography.labelSmall,
            color = FumbleInkFaint,
            modifier = Modifier.padding(start = 28.dp, top = 28.dp, bottom = 8.dp),
        )

        val allLabel = stringResource(R.string.album_all)
        val unnamed = stringResource(R.string.album_unnamed)

        if (albums.isEmpty()) {
            // The list is fetched when the sheet opens, so this shows for a frame or
            // two on a large library and not at all on a small one.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = FumbleAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    AlbumRow(
                        label = allLabel,
                        count = albums.sumOf { it.photoCount },
                        selected = scope is AlbumScope.All,
                        onClick = { onSelect(null) },
                    )
                }

                items(albums, key = { it.id }) { album ->
                    AlbumRow(
                        label = album.name.ifBlank { unnamed },
                        count = album.photoCount,
                        selected = scope.albumId == album.id,
                        onClick = { onSelect(album.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) FumbleAccent else FumbleInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = FumbleInkMuted,
            )
            // Fixed-width slot so the counts stay aligned whether ticked or not.
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = FumbleAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
