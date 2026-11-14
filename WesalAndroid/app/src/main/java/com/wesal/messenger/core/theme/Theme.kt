package com.wesal.messenger.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WesalDarkColorScheme = darkColorScheme(
    primary = WesalEmerald,
    onPrimary = WesalDeepBlack,
    primaryContainer = WesalEmeraldDark,
    onPrimaryContainer = WesalTextPrimary,
    secondary = WesalTurquoise,
    onSecondary = WesalDeepBlack,
    secondaryContainer = WesalSurfaceElevated,
    onSecondaryContainer = WesalTurquoiseSoft,
    background = WesalDeepBlack,
    onBackground = WesalTextPrimary,
    surface = WesalSurfaceDark,
    onSurface = WesalTextPrimary,
    surfaceVariant = WesalSurfaceElevated,
    onSurfaceVariant = WesalTextSecondary,
    outline = WesalSurfaceBorder,
    error = WesalError,
    onError = Color.White
)

private val WesalLightColorScheme = lightColorScheme(
    primary = WesalEmeraldDark,
    onPrimary = Color.White,
    secondary = Color(0xFF008B99),
    onSecondary = Color.White,
    background = Color(0xFFF3F6F8),
    onBackground = Color(0xFF0A1116),
    surface = Color.White,
    onSurface = Color(0xFF0A1116),
    surfaceVariant = Color(0xFFE6EDF2),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = WesalError,
    onError = Color.White
)

@Composable
fun WesalTheme(
    darkTheme: Boolean = true,
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) WesalDarkColorScheme else WesalLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = wesalTypography(fontScale),
        content = content
    )
}
