package com.lirica.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LiricaColors.VioletCore,
    onPrimary = Color(0xFF0D0C13),
    primaryContainer = LiricaColors.CardSurface,
    onPrimaryContainer = LiricaColors.VioletLight,

    secondary = LiricaColors.VioletLight,
    onSecondary = Color(0xFF0D0C13),
    secondaryContainer = Color(0xFF262138),
    onSecondaryContainer = LiricaColors.TextMain,

    tertiary = LiricaColors.VioletDeep,
    onTertiary = Color.White,

    background = LiricaColors.BackgroundDeep,
    onBackground = LiricaColors.TextMain,

    surface = LiricaColors.BackgroundSurface,
    onSurface = LiricaColors.TextMain,
    surfaceVariant = LiricaColors.CardSurface,
    onSurfaceVariant = LiricaColors.TextSecondary,

    error = LiricaColors.Danger,
    onError = Color.White,
    errorContainer = Color(0x33FF6B6B),
    onErrorContainer = Color(0xFFFFB4AB),

    outline = LiricaColors.CardBorder,
    outlineVariant = Color(0x18B4A5F5)
)

@Composable
fun LiricaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
