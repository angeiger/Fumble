package com.fumble.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * The app's own mark: three fanned cards. Drawn rather than shipped as an asset so it
 * inherits whatever colour the surrounding state needs.
 */
@Composable
fun StackGlyph(
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier) {
        val cardWidth = size.width * 0.62f
        val cardHeight = size.height * 0.78f
        val topLeft = Offset(
            x = (size.width - cardWidth) / 2f,
            y = (size.height - cardHeight) / 2f,
        )
        val cardSize = Size(cardWidth, cardHeight)
        val radius = CornerRadius(cardWidth * 0.16f)

        listOf(-16f to 0.18f, -8f to 0.34f, 0f to 1f).forEach { (angle, alpha) ->
            rotate(degrees = angle, pivot = center) {
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = cardSize,
                    cornerRadius = radius,
                    alpha = alpha,
                )
            }
        }
    }
}
