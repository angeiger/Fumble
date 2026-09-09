package com.fumble.app.ui.swipe.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fumble.app.R
import com.fumble.app.domain.model.Photo
import com.fumble.app.ui.common.rememberFormattedSize
import com.fumble.app.ui.theme.FumbleCardShadow
import com.fumble.app.ui.theme.FumbleInkFaint
import com.fumble.app.ui.theme.FumbleInkMuted
import com.fumble.app.ui.theme.FumbleSurface

private val CardCorner = 32.dp

/**
 * One photograph on a card.
 *
 * The image is fitted rather than cropped: this app asks the user to make a keep-or-bin
 * call, and cropping would hide exactly the edges they might be judging it on. When
 * that is not enough, [zoom] magnifies it in place — the card clips, so a zoomed photo
 * never spills past its own edges.
 */
@Composable
fun PhotoCard(
    photo: Photo,
    modifier: Modifier = Modifier,
    elevation: Dp = 18.dp,
    zoom: PhotoZoomState? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var failed by remember(photo.id) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(CardCorner),
                clip = false,
                // Barely-there shadow: enough to separate the card from the canvas,
                // not enough to read as a raised material slab. Fully transparent on
                // dark palettes, where the surface lift does the separating instead.
                ambientColor = FumbleCardShadow,
                spotColor = FumbleCardShadow,
            )
            .clip(RoundedCornerShape(CardCorner))
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { zoom?.onSized(it.toSize()) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.uri)
                .crossfade(true)
                .build(),
            contentDescription = stringResource(R.string.cd_photo),
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            onSuccess = { failed = false },
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (zoom == null) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                    }
                ),
        )

        if (failed) {
            Text(
                text = stringResource(R.string.photo_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = FumbleInkFaint,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        } else {
            Text(
                text = rememberFormattedSize(photo.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = FumbleInkMuted,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(FumbleSurface.copy(alpha = 0.88f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        overlay()
    }
}
