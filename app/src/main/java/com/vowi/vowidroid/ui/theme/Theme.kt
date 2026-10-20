package com.vowi.vowidroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Call-control colors shared by the dialer and the in-call screen. */
val CallGreen = Color(0xFF1E8E3E)
val CallRed = Color(0xFFD93025)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    secondary = Color(0xFF00639B),
    tertiary = Color(0xFF146C2E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    secondary = Color(0xFF7FCFFF),
    tertiary = Color(0xFF6DD58C),
)

/** Material 3 with the wallpaper's dynamic colors, as the stock Phone and Messages apps use. */
@Composable
fun VowiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
