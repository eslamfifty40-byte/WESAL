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

enum class WesalPersonalAccentPreset(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val emeraldHex: Long,
    val turquoiseHex: Long
) {
    DEFAULT("DEFAULT", "وصال الأصلي", "WESAL Classic", 0xFF00D66BL, 0xFF00D5E8L),
    ROYAL_GOLD("ROYAL_GOLD", "ذهبي ملكي", "Royal Gold", 0xFFD97706L, 0xFFFFD54FL),
    ELECTRIC_BLUE("ELECTRIC_BLUE", "أزرق نيون", "Electric Blue", 0xFF1D9BF0L, 0xFF38BDF8L),
    VIOLET_COSMOS("VIOLET_COSMOS", "بنفسجي كوني", "Violet Cosmos", 0xFF8B5CF6L, 0xFFD946EFL),
    CRIMSON_ROYAL("CRIMSON_ROYAL", "قرمزي ملكي", "Royal Crimson", 0xFFE11D48L, 0xFFFF6B6BL),
    EMERALD_MINT("EMERALD_MINT", "زمردي منعش", "Fresh Mint", 0xFF10B981L, 0xFF14B8A6L),
    AMBER_SUNSET("AMBER_SUNSET", "عنبر الغروب", "Amber Sunset", 0xFFF59E0BL, 0xFFFB923CL),
    SAPPHIRE_ICE("SAPPHIRE_ICE", "ياقوتي ثلجي", "Sapphire Ice", 0xFF2563EBL, 0xFF67E8F9L),
    RUBY_FLAME("RUBY_FLAME", "ياقوت أحمر", "Ruby Flame", 0xFFDC2626L, 0xFFFFD54FL),
    ROSE_QUARTZ("ROSE_QUARTZ", "وردي ملكي", "Rose Quartz", 0xFFEC4899L, 0xFFF472B6L),
    CYAN_AURORA("CYAN_AURORA", "أورورا سماوي", "Cyan Aurora", 0xFF06B6D4L, 0xFF69F0AEL),
    INDIGO_NEBULA("INDIGO_NEBULA", "نيلي فضائي", "Indigo Nebula", 0xFF6366F1L, 0xFF38BDF8L),
    COPPER_BRONZE("COPPER_BRONZE", "برونزي فاخر", "Luxury Bronze", 0xFFB45309L, 0xFFFBBF24L),
    PLATINUM_STEEL("PLATINUM_STEEL", "بلاتيني عصري", "Platinum", 0xFF64748BL, 0xFF94A3B8L);

    companion object {
        fun fromKey(key: String?): WesalPersonalAccentPreset =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

enum class WesalPersonalSurfacePreset(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val darkBgHex: Long,
    val darkSurfaceHex: Long,
    val darkElevatedHex: Long,
    val lightBgHex: Long,
    val lightSurfaceHex: Long,
    val lightElevatedHex: Long
) {
    DEFAULT("DEFAULT", "افتراضي", "Default", 0xFF05090BL, 0xFF0B1115L, 0xFF131C22L, 0xFFF3F6F8L, 0xFFFFFFFFL, 0xFFE6EDF2L),
    MIDNIGHT_NAVY("MIDNIGHT_NAVY", "أزرق ليلي", "Midnight", 0xFF060B17L, 0xFF0D1526L, 0xFF162138L, 0xFFF0F7FFL, 0xFFFFFFFFL, 0xFFE0ECFFL),
    OBSIDIAN_EMERALD("OBSIDIAN_EMERALD", "أوبسيديان أخضر", "Forest Obsidian", 0xFF040D0AL, 0xFF091813L, 0xFF10261EL, 0xFFF0FDF4L, 0xFFFFFFFFL, 0xFFDCFCE7L),
    VELVET_CHARCOAL("VELVET_CHARCOAL", "رمادي مخملي", "Velvet", 0xFF09090BL, 0xFF121216L, 0xFF1C1C22L, 0xFFF8FAFCL, 0xFFFFFFFFL, 0xFFE2E8F0L),
    ROYAL_BORDEAUX("ROYAL_BORDEAUX", "عنابي ملكي", "Royal Bordeaux", 0xFF120609L, 0xFF1D0B10L, 0xFF2B1219L, 0xFFFFF1F2L, 0xFFFFFFFFL, 0xFFFFE4E6L),
    COSMIC_PLUM("COSMIC_PLUM", "بنفسجي ليلي", "Cosmic Plum", 0xFF0B0714L, 0xFF140E24L, 0xFF1E1636L, 0xFFF5F3FFL, 0xFFFFFFFFL, 0xFFEDE9FEL),
    DEEP_OCEAN("DEEP_OCEAN", "محيط عميق", "Deep Ocean", 0xFF041014L, 0xFF091B22L, 0xFF102A33L, 0xFFECFEFFL, 0xFFFFFFFFL, 0xFFCFFAFEL),
    WARM_ONYX("WARM_ONYX", "عقيق ذهبي", "Warm Onyx", 0xFF0D0A05L, 0xFF18130AL, 0xFF261E10L, 0xFFFFFBEBL, 0xFFFFFFFFL, 0xFFFEF3C7L);

    companion object {
        fun fromKey(key: String?): WesalPersonalSurfacePreset =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

@Composable
fun WesalTheme(
    darkTheme: Boolean = true,
    languageTag: String = "ar",
    fontScale: Float = 1.0f,
    globalAppearance: GlobalAppearanceConfig = GlobalAppearanceConfig(),
    personalAccentPreset: String = "DEFAULT",
    personalSurfacePreset: String = "DEFAULT",
    content: @Composable () -> Unit
) {
    val basePalette = if (darkTheme) WesalDarkPalette else WesalLightPalette
    val accentPreset = WesalPersonalAccentPreset.fromKey(personalAccentPreset)
    val surfacePreset = WesalPersonalSurfacePreset.fromKey(personalSurfacePreset)
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
    } else if (accentPreset != WesalPersonalAccentPreset.DEFAULT || surfacePreset != WesalPersonalSurfacePreset.DEFAULT) {
        val nextEmerald = if (accentPreset == WesalPersonalAccentPreset.DEFAULT) basePalette.emerald else Color(accentPreset.emeraldHex)
        val nextTurquoise = if (accentPreset == WesalPersonalAccentPreset.DEFAULT) basePalette.turquoise else Color(accentPreset.turquoiseHex)
        val nextBg = if (surfacePreset == WesalPersonalSurfacePreset.DEFAULT) basePalette.background else Color(if (darkTheme) surfacePreset.darkBgHex else surfacePreset.lightBgHex)
        val nextSurface = if (surfacePreset == WesalPersonalSurfacePreset.DEFAULT) basePalette.surface else Color(if (darkTheme) surfacePreset.darkSurfaceHex else surfacePreset.lightSurfaceHex)
        val nextElevated = if (surfacePreset == WesalPersonalSurfacePreset.DEFAULT) basePalette.surfaceElevated else Color(if (darkTheme) surfacePreset.darkElevatedHex else surfacePreset.lightElevatedHex)
        basePalette.copy(
            emerald = nextEmerald,
            turquoise = nextTurquoise,
            turquoiseSoft = nextTurquoise,
            iconTint = nextTurquoise,
            background = nextBg,
            surface = nextSurface,
            surfaceElevated = nextElevated
        )
    } else {
        basePalette
    }
    val baseColorScheme = if (darkTheme) WesalDarkColorScheme else WesalLightColorScheme
    val colorScheme = if (globalAppearance.enabled || accentPreset != WesalPersonalAccentPreset.DEFAULT || surfacePreset != WesalPersonalSurfacePreset.DEFAULT) {
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
