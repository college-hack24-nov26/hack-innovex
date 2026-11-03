package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DrishtiColorScheme = darkColorScheme(
    primary = BrightCyan,
    onPrimary = ObsidianBackground,
    primaryContainer = DarkCyan,
    onPrimaryContainer = TextPrimary,
    secondary = GoldenAmber,
    onSecondary = ObsidianBackground,
    secondaryContainer = DeepSurface,
    onSecondaryContainer = GoldenAmber,
    tertiary = SafeEmerald,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = DeepSurface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    error = DangerCrimson,
    onError = TextPrimary
)

@Composable
fun DrishtiTheme(
    content: @Composable () -> Unit
) {
    // Accessibility-first high contrast dark theme is enforced for visual clarity & OLED battery efficiency
    MaterialTheme(
        colorScheme = DrishtiColorScheme,
        typography = Typography,
        content = content
    )
}
