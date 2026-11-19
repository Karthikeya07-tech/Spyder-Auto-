package com.example.spyder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SpyderColors = darkColorScheme(
    primary = SpyderRed,
    onPrimary = SpyderWhite,
    secondary = SpyderMaroon,
    onSecondary = SpyderWhite,
    tertiary = SpyderRed,
    onTertiary = SpyderWhite,
    background = SpyderBackground,
    onBackground = SpyderWhite,
    surface = SpyderBlack,
    onSurface = SpyderWhite,
    surfaceVariant = SpyderMaroonDeep,
    onSurfaceVariant = SpyderWhite,
    outline = SpyderMaroon,
    error = SpyderRed,
    onError = SpyderWhite
)

@Composable
fun SpyderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SpyderColors,
        typography = Typography,
        content = content
    )
}
