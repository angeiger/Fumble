package com.fumble.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.fumble.app.R
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkFaint

/**
 * The app's name, with the leading letter carrying the accent colour.
 *
 * One component rather than a literal in each screen, so the name and its styling only
 * ever have to change in one place.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    showTagline: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = FumbleAccent)) { append("F") }
                append("umble")
            },
            color = FumbleInk,
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            letterSpacing = fontSize.value.times(0.13f).sp,
        )

        if (showTagline) {
            // "Foto Bumble" is the whole explanation of what this app is. It earns its
            // place wherever someone might be meeting the name for the first time.
            Text(
                text = stringResource(R.string.app_tagline),
                color = FumbleInkFaint,
                fontSize = fontSize.value.times(0.42f).sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = fontSize.value.times(0.09f).sp,
            )
        }
    }
}
