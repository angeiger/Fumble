package com.fumble.app.ui.swipe.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fumble.app.R
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleHairline
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkFaint
import com.fumble.app.ui.theme.FumbleInkMuted
import com.fumble.app.ui.theme.FumblePalettes
import com.fumble.app.ui.theme.FumbleSurface

/** Thresholds offered. Free choice would only invite a number nobody wants to live with. */
private val ThresholdOptions = listOf(10, 25, 50, 100, 200)

/**
 * The app's only settings screen, and deliberately two decisions long.
 *
 * Everything else Fumble does is either automatic or one tap away on the main screen.
 * A settings menu that grows past what fits on one sheet would be a sign the defaults
 * are wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    trashThreshold: Int,
    paletteId: String,
    versionName: String,
    onThresholdChange: (Int) -> Unit,
    onPaletteChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FumbleSurface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.labelSmall,
                color = FumbleInkFaint,
            )

            // Trash threshold ------------------------------------------------
            Text(
                text = stringResource(R.string.settings_threshold_title),
                style = MaterialTheme.typography.titleMedium,
                color = FumbleInk,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.settings_threshold_body),
                style = MaterialTheme.typography.bodySmall,
                color = FumbleInkMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThresholdOptions.forEach { value ->
                    ChoiceChip(
                        label = value.toString(),
                        selected = value == trashThreshold,
                        onClick = { onThresholdChange(value) },
                    )
                }
            }

            // Palette --------------------------------------------------------
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleMedium,
                color = FumbleInk,
                modifier = Modifier.padding(top = 32.dp),
            )
            Column(modifier = Modifier.padding(top = 12.dp)) {
                FumblePalettes.forEach { palette ->
                    PaletteRow(
                        label = palette.label,
                        selected = palette.id == paletteId,
                        swatchCanvas = palette.colors.canvas,
                        swatchSurface = palette.colors.surface,
                        swatchAccent = palette.colors.accent,
                        onClick = { onPaletteChange(palette.id) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.labelSmall,
                color = FumbleInkFaint,
                modifier = Modifier.padding(top = 28.dp),
            )
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else FumbleInkMuted,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) FumbleAccent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun PaletteRow(
    label: String,
    selected: Boolean,
    swatchCanvas: Color,
    swatchSurface: Color,
    swatchAccent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A miniature of the app: ground, card, accent.
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(swatchCanvas)
                .border(1.dp, FumbleHairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(swatchSurface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(swatchAccent)
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) FumbleAccent else FumbleInk,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )

        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(FumbleAccent)
            )
        }
    }
}
