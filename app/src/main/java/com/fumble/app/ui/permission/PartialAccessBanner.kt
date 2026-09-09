package com.fumble.app.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fumble.app.R
import com.fumble.app.ui.theme.FumbleAccent
import com.fumble.app.ui.theme.FumbleAccentSoft
import com.fumble.app.ui.theme.FumbleInkMuted

/**
 * Android 14+ only. When the user grants access to a hand-picked subset, the app must
 * say so — otherwise "all caught up" would be a lie about photos it simply cannot see.
 */
@Composable
fun PartialAccessBanner(
    onSelectMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(CircleShape)
            .background(FumbleAccentSoft)
            .clickable(role = Role.Button, onClick = onSelectMore)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.partial_access_label),
            style = MaterialTheme.typography.bodySmall,
            color = FumbleInkMuted,
        )
        Text(
            text = stringResource(R.string.partial_access_cta),
            style = MaterialTheme.typography.labelSmall,
            color = FumbleAccent,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
