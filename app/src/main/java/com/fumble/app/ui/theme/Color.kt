package com.fumble.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The active palette.
 *
 * Static rather than dynamic on purpose: the palette changes only when the user picks a
 * different one in settings, and a whole-tree recomposition at that moment is exactly
 * what should happen.
 */
val LocalFumbleColors = staticCompositionLocalOf { paletteById("daylight").colors }

/*
 * These read the active palette instead of naming fixed colours, so every existing
 * `color = FumbleInk` call site kept working when themes arrived. They are composable
 * getters, so they can only be read inside composition — hoist the value into a local
 * before using one inside a `drawBehind` or `graphicsLayer` lambda.
 */

val FumbleCanvas: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.canvas

val FumbleSurface: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.surface

val FumbleInk: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.ink

val FumbleInkMuted: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.inkMuted

val FumbleInkFaint: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.inkFaint

val FumbleHairline: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.hairline

val FumbleAccent: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.accent

val FumbleAccentSoft: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.accentSoft

val FumbleKeep: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.keep

val FumbleKeepSoft: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.keepSoft

val FumbleTrash: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.trash

val FumbleTrashSoft: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.trashSoft

val FumbleCardShadow: Color
    @Composable @ReadOnlyComposable get() = LocalFumbleColors.current.cardShadow
