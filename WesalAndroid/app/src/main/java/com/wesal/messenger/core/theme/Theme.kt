package com.wesal.messenger.core.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.wesalStringsForLanguage

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
    primary = WesalLightPalette.emerald,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = WesalLightPalette.turquoise,
    onSecondary = Color.White,
    secondaryContainer = WesalLightPalette.surfaceElevated,
    onSecondaryContainer = Color(0xFF0F172A),
    background = WesalLightPalette.background,
    onBackground = WesalLightPalette.textPrimary,
    surface = WesalLightPalette.surface,
    onSurface = WesalLightPalette.textPrimary,
    surfaceVariant = WesalLightPalette.surfaceElevated,
    onSurfaceVariant = WesalLightPalette.textSecondary,
    outline = WesalLightPalette.border,
    error = WesalLightPalette.error,
    onError = Color.White
)

@Composable
fun WesalTheme(
    darkTheme: Boolean = true,
    languageTag: String = "ar",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) WesalDarkPalette else WesalLightPalette
    val colorScheme = if (darkTheme) WesalDarkColorScheme else WesalLightColorScheme
    val strings = wesalStringsForLanguage(languageTag)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = palette.surface.toArgb()
                window.navigationBarColor = palette.surface.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        WesalColors provides palette,
        LocalWesalStrings provides strings
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = wesalTypography(fontScale),
            content = content
        )
    }
}
