package com.fumble.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app draws with.
 *
 * Keep and trash stay green-ish and red-ish in every palette. They are not decoration:
 * they are the two words the interface says, and a palette that recolours them would
 * be changing the meaning rather than the mood.
 */
@Immutable
data class FumbleColors(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val hairline: Color,
    val accent: Color,
    val accentSoft: Color,
    val keep: Color,
    val keepSoft: Color,
    val trash: Color,
    val trashSoft: Color,

    /**
     * The one control that carries no meaning: undo.
     *
     * Its own pair rather than a reuse of `surface` and `inkMuted`, because those exist
     * to sit *quietly* against the ground and undo has to be found at a glance. Each
     * palette tunes its own grey, since how far a neutral has to travel from the canvas
     * to register differs completely between a near-white and a true black.
     */
    val neutral: Color,
    val neutralSoft: Color,

    /** Shadow tint under cards. Near-black on light grounds, and absent on dark ones. */
    val cardShadow: Color,
    /** True when the ground is dark, so system bar icons can be flipped to light. */
    val isDark: Boolean,
)

/** A named, user-selectable palette. */
@Immutable
data class FumblePalette(
    val id: String,
    val label: String,
    val colors: FumbleColors,
)

private val Daylight = FumbleColors(
    canvas = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF12141C),
    inkMuted = Color(0xFF7A7F8E),
    inkFaint = Color(0xFFB6BAC6),
    hairline = Color(0xFFE8EAF1),
    accent = Color(0xFF5B4BFF),
    accentSoft = Color(0xFFEDEBFF),
    keep = Color(0xFF00C48C),
    keepSoft = Color(0xFFE1F8F1),
    trash = Color(0xFFFF4D6A),
    trashSoft = Color(0xFFFFE8EC),
    // A grey with enough weight to read against a near-white ground, and anthracite
    // on top rather than the muted ink used for quiet text.
    neutral = Color(0xFF3C414F),
    neutralSoft = Color(0xFFDFE2EB),
    cardShadow = Color(0x1F161A2E),
    isDark = false,
)

private val Paper = FumbleColors(
    canvas = Color(0xFFF7F3EC),
    surface = Color(0xFFFFFCF7),
    ink = Color(0xFF221C15),
    inkMuted = Color(0xFF8A7E6E),
    inkFaint = Color(0xFFC4B8A6),
    hairline = Color(0xFFEBE3D6),
    accent = Color(0xFFC2643C),
    accentSoft = Color(0xFFF8E7DD),
    keep = Color(0xFF3E9E6B),
    keepSoft = Color(0xFFE4F2E9),
    trash = Color(0xFFD9534F),
    trashSoft = Color(0xFFFBE6E5),
    // Built like this palette's accent pair — a soft tint carrying a saturated mark —
    // but in warm taupe rather than terracotta, so it belongs without being mistaken
    // for the favourite button beside it.
    neutral = Color(0xFF6E6152),
    neutralSoft = Color(0xFFEBE0CE),
    cardShadow = Color(0x1F3A2E1E),
    isDark = false,
)

private val Midnight = FumbleColors(
    canvas = Color(0xFF12141C),
    surface = Color(0xFF1C1F2A),
    ink = Color(0xFFF2F3F7),
    inkMuted = Color(0xFF9AA0B0),
    inkFaint = Color(0xFF5C6273),
    hairline = Color(0xFF2A2E3C),
    accent = Color(0xFF8B7BFF),
    accentSoft = Color(0xFF262640),
    keep = Color(0xFF2BD9A4),
    keepSoft = Color(0xFF16342C),
    trash = Color(0xFFFF6B83),
    trashSoft = Color(0xFF3A2029),
    // Lifted clearly above the surface, not just the canvas: on a dark ground a
    // container has to out-rank the cards to be found, and a light mark on top of it
    // carries the rest.
    neutral = Color(0xFFC7CDDC),
    neutralSoft = Color(0xFF333949),
    // A shadow under a dark card on a dark ground is invisible; the surface lift does
    // the separating instead.
    cardShadow = Color(0x00000000),
    isDark = true,
)

private val Carbon = FumbleColors(
    canvas = Color(0xFF000000),
    surface = Color(0xFF121212),
    ink = Color(0xFFF5F5F5),
    inkMuted = Color(0xFF9E9E9E),
    inkFaint = Color(0xFF5A5A5A),
    hairline = Color(0xFF222222),
    accent = Color(0xFF3DDC97),
    accentSoft = Color(0xFF10281F),
    keep = Color(0xFF3DDC97),
    keepSoft = Color(0xFF10281F),
    trash = Color(0xFFFF5C7A),
    trashSoft = Color(0xFF2E1119),
    // The widest jump of the four: against true black even a mid grey reads as bright,
    // so the container sits well above the surface and the mark stays clearly lighter.
    neutral = Color(0xFFD2D2D2),
    neutralSoft = Color(0xFF2C2C2C),
    cardShadow = Color(0x00000000),
    isDark = true,
)

/**
 * The palettes offered in settings, in the order they are shown.
 *
 * Ids are persisted, so they must not be renamed.
 */
val FumblePalettes: List<FumblePalette> = listOf(
    FumblePalette(id = "daylight", label = "Daylight", colors = Daylight),
    FumblePalette(id = "paper", label = "Paper", colors = Paper),
    FumblePalette(id = "midnight", label = "Midnight", colors = Midnight),
    FumblePalette(id = "carbon", label = "Carbon", colors = Carbon),
)

fun paletteById(id: String): FumblePalette =
    FumblePalettes.firstOrNull { it.id == id } ?: FumblePalettes.first()
