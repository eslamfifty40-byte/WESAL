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
import com.wesal.messenger.domain.model.GlobalAppearanceConfig

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

private fun safeArgbColor(hex: Long): Color? {
    if (hex == 0L) return null
    val normalized = if ((hex ushr 24) == 0L) (hex or 0xFF000000L) else hex
    return Color(normalized)
}

private fun Color.relativeLuminance(): Float {
    return 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

@Composable
fun WesalTheme(
    darkTheme: Boolean = true,
    languageTag: String = "ar",
    fontScale: Float = 1.0f,
    globalAppearance: GlobalAppearanceConfig = GlobalAppearanceConfig(),
    content: @Composable () -> Unit
) {
    val basePalette = if (darkTheme) WesalDarkPalette else WesalLightPalette
    val palette = if (globalAppearance.enabled) {
        val customEmerald = safeArgbColor(globalAppearance.emeraldHex) ?: basePalette.emerald
        val customTurquoise = safeArgbColor(globalAppearance.turquoiseHex) ?: basePalette.turquoise
        val rawBg = safeArgbColor(globalAppearance.backgroundHex)
        val customBg = if (rawBg != null) {
            val lum = rawBg.relativeLuminance()
            if ((darkTheme && lum < 0.45f) || (!darkTheme && lum > 0.55f)) rawBg else basePalette.background
        } else {
            basePalette.background
        }
        val rawSurface = safeArgbColor(globalAppearance.surfaceHex)
        val customSurface = if (rawSurface != null) {
            val lum = rawSurface.relativeLuminance()
            if ((darkTheme && lum < 0.45f) || (!darkTheme && lum > 0.55f)) rawSurface else basePalette.surface
        } else {
            basePalette.surface
        }
        val surfaceLum = customSurface.relativeLuminance()
        val rawText = safeArgbColor(globalAppearance.textPrimaryHex)
        val customText = if (rawText != null) {
            val textLum = rawText.relativeLuminance()
            val validContrast = kotlin.math.abs(textLum - surfaceLum) >= 0.35f
            if (validContrast) rawText else if (surfaceLum < 0.5f) Color.White else Color(0xFF0A1116)
        } else {
            if (surfaceLum < 0.5f) Color.White else Color(0xFF0A1116)
        }
        val textSecondary = if (surfaceLum < 0.5f) Color(0xFFB6C2CF) else Color(0xFF334155)
        val customIconTint = safeArgbColor(globalAppearance.iconTintHex) ?: customTurquoise
        basePalette.copy(
            emerald = customEmerald,
            turquoise = customTurquoise,
            turquoiseSoft = customTurquoise,
            background = customBg,
            surface = customSurface,
            textPrimary = customText,
            textSecondary = textSecondary,
            iconTint = customIconTint
        )
    } else {
        basePalette
    }
    val baseColorScheme = if (darkTheme) WesalDarkColorScheme else WesalLightColorScheme
    val colorScheme = if (globalAppearance.enabled) {
        baseColorScheme.copy(
            primary = palette.emerald,
            secondary = palette.turquoise,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary
        )
    } else {
        baseColorScheme
    }
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
