package com.example.carmusicmeter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04120B),
    primaryContainer = AccentContainer,
    onPrimaryContainer = Accent,
    secondary = Sky,
    onSecondary = Color(0xFF04101A),
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = StatusBad,
    onError = Color(0xFF1A0505),
)

@Composable
fun CarMusicMeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = CarMusicMeterTypography,
        content = content,
    )
}
