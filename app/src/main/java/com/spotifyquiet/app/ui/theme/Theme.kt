package com.spotifyquiet.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val WarningColor = Color(0xFFF2C14E)
val InfoColor = Color(0xFF8AB4F8)
val ErrorColor = Color(0xFFF07C70)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BE0B2),
    onPrimary = Color(0xFF05241A),
    primaryContainer = Color(0xFF1B3A30),
    onPrimaryContainer = Color(0xFFB5F0D6),
    secondary = Color(0xFF9AA6B4),
    onSecondaryContainer = Color(0xFFC7D2DD),
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE9EDF2),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFE9EDF2),
    surfaceVariant = Color(0xFF1C212A),
    onSurfaceVariant = Color(0xFFA7B2BF),
    outline = Color(0xFF3A424D),
    error = Color(0xFFF07C70),
    onError = Color(0xFF2A0A07),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun SpotifyQuietTheme(content: @Composable () -> Unit) {
    // Dark-only by design: SpotifyQuiet is a quiet background companion.
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
