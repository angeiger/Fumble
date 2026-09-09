package com.fumble.app.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fumble.app.R
import com.fumble.app.ui.common.StackGlyph
import com.fumble.app.ui.common.Wordmark
import com.fumble.app.ui.swipe.components.SoftButton
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkMuted

/** Shown until the app can read at least some of the gallery. */
@Composable
fun PermissionGate(
    state: MediaAccessState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StackGlyph(
            color = FumbleAccent,
            modifier = Modifier.size(96.dp),
        )

        // The first screen anyone sees, so it is where the name gets explained.
        Wordmark(
            fontSize = 30.sp,
            showTagline = true,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp),
        )

        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleLarge,
            color = FumbleInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )

        Text(
            text = stringResource(
                if (state.permanentlyDenied) {
                    R.string.permission_denied_body
                } else {
                    R.string.permission_body
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = FumbleInkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )

        if (state.permanentlyDenied) {
            SoftButton(
                label = stringResource(R.string.permission_settings_cta),
                onClick = state::openSettings,
                modifier = Modifier.padding(top = 32.dp),
            )
        } else {
            SoftButton(
                label = stringResource(R.string.permission_cta),
                onClick = state::request,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }
}
