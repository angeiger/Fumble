package com.fumble.app.ui.common

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Localised, short byte size ("1.2 GB", "840 kB") using the platform formatter. */
@Composable
fun rememberFormattedSize(bytes: Long): String {
    val context: Context = LocalContext.current
    return remember(context, bytes) { Formatter.formatShortFileSize(context, bytes) }
}
