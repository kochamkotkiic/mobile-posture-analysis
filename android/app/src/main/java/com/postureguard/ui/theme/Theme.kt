package com.postureguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/*
 * Minimal monochrome look: black on white (light) / white on black (dark).
 * No dynamic (wallpaper) colours on purpose, so the app looks the same on every phone and the
 * only colours on screen are the status colours (green good / red bad).
 */

private val LightColors = lightColorScheme(
    primary = Black,
    onPrimary = White,
    primaryContainer = Black,
    onPrimaryContainer = White,
    secondary = Grey500,
    onSecondary = White,
    secondaryContainer = Grey100,
    onSecondaryContainer = Black,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = Grey50,
    onSurfaceVariant = Grey500,
    surfaceContainerHighest = Grey100,
    surfaceContainerHigh = Grey50,
    surfaceContainer = Grey50,
    surfaceContainerLow = Grey50,
    surfaceContainerLowest = White,
    outline = Grey300,
    outlineVariant = Grey100,
    error = StatusColors.Bad,
    onError = White,
)

private val DarkColors = darkColorScheme(
    primary = White,
    onPrimary = Black,
    primaryContainer = White,
    onPrimaryContainer = Black,
    secondary = Grey500,
    onSecondary = Black,
    secondaryContainer = Grey700,
    onSecondaryContainer = White,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = Grey800,
    onSurfaceVariant = Grey500,
    surfaceContainerHighest = Grey700,
    surfaceContainerHigh = Grey800,
    surfaceContainer = Grey800,
    surfaceContainerLow = Grey900,
    surfaceContainerLowest = Black,
    outline = Grey700,
    outlineVariant = Grey800,
    error = StatusColors.Bad,
    onError = White,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PostureGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
