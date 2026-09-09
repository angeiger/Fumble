package com.fumble.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fumble.app.data.local.AppPreferences
import com.fumble.app.ui.swipe.SwipeRoute
import com.fumble.app.ui.theme.FumbleTheme
import com.fumble.app.ui.theme.paletteById
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val paletteId by preferences.paletteId.collectAsStateWithLifecycle()
            val palette = remember(paletteId) { paletteById(paletteId) }

            // Bar icons follow the chosen palette rather than the system setting: the
            // ground behind them is whatever the user picked, and dark icons on a
            // Carbon background would be unreadable.
            LaunchedEffect(palette.colors.isDark) {
                val style = if (palette.colors.isDark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            FumbleTheme(palette = palette) {
                SwipeRoute()
            }
        }
    }
}
