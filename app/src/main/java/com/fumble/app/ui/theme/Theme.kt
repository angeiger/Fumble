package com.fumble.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

private val FumbleShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private fun FumbleColors.toMaterialScheme() = with(this) {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = accent,
        onPrimary = if (isDark) canvas else surface,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,

        secondary = keep,
        onSecondary = if (isDark) canvas else surface,
        secondaryContainer = keepSoft,
        onSecondaryContainer = keep,

        error = trash,
        onError = if (isDark) canvas else surface,
        errorContainer = trashSoft,
        onErrorContainer = trash,

        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = canvas,
        onSurfaceVariant = inkMuted,
        outline = hairline,
        outlineVariant = hairline,

        // Material's tonal elevation would tint every raised surface. The design keeps
        // flat surfaces separated by the canvas colour instead, so these are pinned.
        surfaceContainer = surface,
        surfaceContainerLow = surface,
        surfaceContainerLowest = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
    )
}

/**
 * Applies the chosen palette to the whole tree.
 *
 * The palette is a user setting rather than a follow-the-system switch. "Dark mode" is
 * one of the offered palettes, not a separate axis — the design is a set of complete
 * looks, and mixing a chosen accent with an inferred ground would only produce
 * combinations nobody picked.
 */
@Composable
fun FumbleTheme(
    palette: FumblePalette = FumblePalettes.first(),
    content: @Composable () -> Unit,
) {
    val colors = palette.colors
    val scheme = remember(colors) { colors.toMaterialScheme() }

    CompositionLocalProvider(LocalFumbleColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = FumbleTypography,
            shapes = FumbleShapes,
            content = content,
        )
    }
}
