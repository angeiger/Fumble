package com.fumble.app.ui.swipe.components

import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.fumble.app.R
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleCanvas
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkMuted
import com.fumble.app.ui.theme.FumbleKeep
import kotlin.math.pow
import kotlin.random.Random

private const val DURATION_MS = 2400
private const val CARD_COUNT = 22

/** A falling photo-card glyph. Positions are fractions of the canvas, so they scale. */
private class FallingCard(
    val x: Float,
    val y: Float,
    val width: Float,
    val aspect: Float,
    val tilt: Float,
    val spin: Float,
    val birth: Float,
    val fall: Float,
)

/**
 * The moment after photos actually reach the trash.
 *
 * This is the only screen in the app that exists purely to feel good — everything else
 * earns its pixels by informing a decision. It is kept honest by showing the two real
 * numbers and naming the 30-day window, and it carries no congratulatory slogan: the
 * user cleared out some photos, which is a fact, not an achievement to be told about.
 *
 * The motion is built in three overlapping passes rather than one burst, which is what
 * separates a celebration from a puff of confetti:
 *
 * 1. Two thick ribbons *draw themselves* along curves. A stroke that grows reads as
 *    something happening; the same shape faded in reads as a decoration appearing.
 * 2. Card glyphs pop in one after another, then fall away under gravity.
 * 3. The reclaimed size counts up in the middle of it.
 *
 * Colours come from the active palette, so the celebration belongs to whichever look
 * the user chose rather than always being the same branded orange.
 */
@Composable
fun TrashCelebration(
    photoCount: Int,
    freedBytes: Long,
    onDone: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val pop = remember { Animatable(0.72f) }

    val cards = remember(photoCount) {
        val random = Random(freedBytes.toInt() xor photoCount)
        List(CARD_COUNT) {
            FallingCard(
                x = 0.06f + random.nextFloat() * 0.88f,
                y = 0.08f + random.nextFloat() * 0.66f,
                width = 0.055f + random.nextFloat() * 0.055f,
                aspect = 0.72f + random.nextFloat() * 0.6f,
                tilt = (random.nextFloat() - 0.5f) * 50f,
                spin = (random.nextFloat() - 0.5f) * 220f,
                birth = random.nextFloat() * 0.34f,
                fall = 0.75f + random.nextFloat() * 0.75f,
            )
        }
    }

    val ground = FumbleCanvas
    val ribbonPrimary = FumbleAccent
    val ribbonSecondary = FumbleKeep
    val glyphColor = FumbleInk

    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ground.copy(alpha = 0.97f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value

            // Ribbons first and underneath, so the numbers always stay readable.
            drawRibbon(
                path = sweepingRibbon(size.width, size.height),
                color = ribbonPrimary,
                width = size.width * 0.115f,
                reveal = easeOut(phase(t, start = 0f, end = 0.24f)),
            )
            drawRibbon(
                path = bottomArc(size.width, size.height),
                color = ribbonSecondary,
                width = size.width * 0.085f,
                reveal = easeOut(phase(t, start = 0.09f, end = 0.32f)),
            )

            cards.forEach { card -> drawFallingCard(card, t, glyphColor) }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(40.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    alpha = pop.value.coerceIn(0f, 1f)
                },
        ) {
            CountingSize(
                targetBytes = freedBytes,
                progressProvider = { progress.value },
            )
            Text(
                text = stringResource(R.string.celebration_headline),
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                color = FumbleInkMuted,
            )
            Text(
                text = stringResource(R.string.celebration_subtitle, photoCount),
                fontSize = 14.sp,
                color = FumbleInkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

/** Enters past the top-right corner and sweeps down across to the left. */
private fun sweepingRibbon(w: Float, h: Float) = Path().apply {
    moveTo(w * 1.25f, -h * 0.02f)
    cubicTo(
        w * 0.70f, h * 0.08f,
        w * 0.52f, h * 0.34f,
        -w * 0.25f, h * 0.60f,
    )
}

/** A shallow arc bulging off the bottom edge, framing the numbers from below. */
private fun bottomArc(w: Float, h: Float) = Path().apply {
    moveTo(-w * 0.25f, h * 0.86f)
    cubicTo(
        w * 0.22f, h * 1.06f,
        w * 0.74f, h * 1.02f,
        w * 1.25f, h * 0.80f,
    )
}

/**
 * Draws the first [reveal] of a path as a thick round-capped stroke.
 *
 * Trimming the path is what makes the ribbon appear to be *drawn*. Animating alpha or
 * scale on the finished shape would land somewhere much flatter.
 */
private fun DrawScope.drawRibbon(path: Path, color: Color, width: Float, reveal: Float) {
    if (reveal <= 0f) return

    val measure = PathMeasure().apply { setPath(path, false) }
    val visible = Path()
    measure.getSegment(0f, measure.length * reveal, visible, true)

    drawPath(
        path = visible,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawFallingCard(card: FallingCard, t: Float, color: Color) {
    val age = t - card.birth
    if (age <= 0f) return

    // Pops to full size, holds, then drops away — one after another, never in unison.
    val appear = (age / 0.09f).coerceIn(0f, 1f)
    val falling = ((age - 0.16f) / 0.62f).coerceAtLeast(0f)
    val alpha = (1f - falling).coerceIn(0f, 1f)
    if (alpha <= 0f) return

    val cardWidth = size.width * card.width * easeOut(appear)
    val cardHeight = cardWidth * card.aspect
    val position = Offset(
        x = size.width * card.x,
        y = size.height * card.y + size.height * card.fall * falling.pow(2f),
    )

    rotate(degrees = card.tilt + card.spin * falling, pivot = position) {
        drawRoundRect(
            color = color,
            topLeft = position - Offset(cardWidth / 2f, cardHeight / 2f),
            size = Size(cardWidth, cardHeight),
            cornerRadius = CornerRadius(cardWidth * 0.2f),
            style = Stroke(width = size.width * 0.005f),
            alpha = alpha * 0.55f,
        )
    }
}

/** Maps overall progress onto a sub-phase's own 0..1. */
private fun phase(t: Float, start: Float, end: Float): Float =
    ((t - start) / (end - start)).coerceIn(0f, 1f)

private fun easeOut(t: Float): Float = 1f - (1f - t).pow(3f)

/**
 * The freed size, counting up.
 *
 * Isolated so only this line recomposes per frame; the ribbons and glyphs read the same
 * progress from inside a draw lambda and never recompose at all.
 */
@Composable
private fun CountingSize(targetBytes: Long, progressProvider: () -> Float) {
    val context = LocalContext.current
    // Front-loaded so the number lands while the ribbons are still moving.
    val eased = easeOut((progressProvider() / 0.38f).coerceIn(0f, 1f))
    val shown = lerp(0f, targetBytes.toFloat(), eased).toLong()

    Text(
        text = Formatter.formatShortFileSize(context, shown),
        fontSize = 60.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2).sp,
        color = FumbleInk,
    )
}
