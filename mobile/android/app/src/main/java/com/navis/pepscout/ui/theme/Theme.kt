package com.navis.pepscout.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = PepScoutGreen,
    onPrimary = Color.White,
    secondary = PepScoutAccent,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFF1F5F9),
    onSurface = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = PepScoutGreen,
    onPrimary = Color.White,
    secondary = PepScoutAccent,
    background = PepScoutSlate,
    surface = PepScoutSurface,
    onSurface = Color.White
)

@Composable
fun PepScoutTheme(useDarkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}
