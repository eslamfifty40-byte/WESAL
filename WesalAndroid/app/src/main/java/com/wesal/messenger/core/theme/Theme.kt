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
    PLATINUM_STEEL("PLATINUM_STEEL", "بلاتيني عصري", "Platinum", 0xFF64748BL, 0xFF94A3B8L),
    NEON_LIME("NEON_LIME", "ليموني مشع", "Neon Lime", 0xFF65A30DL, 0xFFA3E635L),
    CORAL_REEF("CORAL_REEF", "مرجاني ساطع", "Coral Reef", 0xFFF97316L, 0xFFFF7A7AL),
    DEEP_TEAL("DEEP_TEAL", "فيروزي عميق", "Deep Teal", 0xFF0D9488L, 0xFF2DD4BFL),
    ORCHID_GLOW("ORCHID_GLOW", "أوركيد مضيء", "Orchid Glow", 0xFFC026D3L, 0xFFE879F9L),
    COBALT_STORM("COBALT_STORM", "كوبالت ملكي", "Cobalt Storm", 0xFF1D4ED8L, 0xFF60A5FAL),
    GARNET_WINE("GARNET_WINE", "نبيتي داكن", "Garnet Wine", 0xFFBE123CL, 0xFFFDA4AFL),
    JADE_FOREST("JADE_FOREST", "يشم طبيعي", "Jade Forest", 0xFF059669L, 0xFF34D399L),
    SOLAR_FLARE("SOLAR_FLARE", "شمسي ذهبي", "Solar Flare", 0xFFEA580CL, 0xFFFACC15L),
    AQUA_MARINE("AQUA_MARINE", "أكوا بحري", "Aquamarine", 0xFF0891B2L, 0xFF22D3EEL),
    MAGENTA_PULSE("MAGENTA_PULSE", "ماجنتا نابض", "Magenta Pulse", 0xFFDB2777L, 0xFFF9A8D4L),
    AMETHYST_ROYAL("AMETHYST_ROYAL", "جمشت ملكي", "Royal Amethyst", 0xFF7C3AEDL, 0xFFC4B5FDL),
    TURQUOISE_GOLD("TURQUOISE_GOLD", "تركواز وذهب", "Turquoise Gold", 0xFF00B8D4L, 0xFFFFD54FL);

    companion object {
        fun fromKey(key: String?): WesalPersonalAccentPreset =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

enum class WesalNameplateColorPreset(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val edgeHex: Long,
    val midHex: Long,
    val centerHex: Long,
    val borderAccentHex: Long
) {
    CRIMSON("CRIMSON", "أحمر ملكي", "Royal Crimson", 0xFF4A040EL, 0xFFD50026L, 0xFF7B0719L, 0xFFFFD54FL),
    ROYAL_BLUE("ROYAL_BLUE", "أزرق ملكي", "Royal Blue", 0xFF071A40L, 0xFF1D4ED8L, 0xFF0F2D6BL, 0xFF60A5FAL),
    EMERALD("EMERALD", "زمردي وصال", "WESAL Emerald", 0xFF03281AL, 0xFF00A854L, 0xFF064E3BL, 0xFF69F0AEL),
    GOLD_ONYX("GOLD_ONYX", "ذهبي وأونيكس", "Gold Onyx", 0xFF261A03L, 0xFFB45309L, 0xFF452C05L, 0xFFFFE082L),
    PURPLE_MAJESTY("PURPLE_MAJESTY", "بنفسجي ملكي", "Purple Majesty", 0xFF24063DL, 0xFF7E22CEL, 0xFF3E0B69L, 0xFFE879F9L),
    CYAN_NEON("CYAN_NEON", "سماوي نيون", "Cyan Neon", 0xFF042430L, 0xFF0891B2L, 0xFF0B3F52L, 0xFF00E5FFL),
    ROSE_VELVET("ROSE_VELVET", "وردي مخملي", "Rose Velvet", 0xFF420624L, 0xFFDB2777L, 0xFF6D0B3CL, 0xFFF9A8D4L),
    ORANGE_FLAME("ORANGE_FLAME", "ناري برتقالي", "Orange Flame", 0xFF431407L, 0xFFEA580CL, 0xFF7C2D12L, 0xFFFDBA74L),
    TEAL_ABYSS("TEAL_ABYSS", "فيروزي غامق", "Teal Abyss", 0xFF042624L, 0xFF0D9488L, 0xFF0E4742L, 0xFF5EEAD4L),
    INDIGO_NIGHT("INDIGO_NIGHT", "نيلي ليلي", "Indigo Night", 0xFF121042L, 0xFF4338CAL, 0xFF1E1B70L, 0xFFA5B4FCL),
    RUBY_DARK("RUBY_DARK", "ياقوت داكن", "Dark Ruby", 0xFF3D0318L, 0xFFE11D48L, 0xFF69072BL, 0xFFFDA4AFL),
    MINT_DARK("MINT_DARK", "نعناع زمردي", "Emerald Mint", 0xFF032B21L, 0xFF10B981L, 0xFF064E3EL, 0xFFA7F3D0L),
    MAGENTA_ROYAL("MAGENTA_ROYAL", "أرجواني فاخر", "Royal Magenta", 0xFF3D0539L, 0xFFC026D3L, 0xFF670A60L, 0xFFF0ABACL),
    SAPPHIRE("SAPPHIRE", "سافاير بحري", "Deep Sapphire", 0xFF081B3DL, 0xFF0284C7L, 0xFF0C346EL, 0xFF7DD3FCL),
    AMBER_BRONZE("AMBER_BRONZE", "عنبر برونزي", "Amber Bronze", 0xFF331B04L, 0xFFD97706L, 0xFF5C3108L, 0xFFFDE68AL),
    SILVER_SLATE("SILVER_SLATE", "فضي أردوازي", "Silver Slate", 0xFF0F172AL, 0xFF475569L, 0xFF1E293BL, 0xFFE2E8F0L),
    TURQUOISE("TURQUOISE", "تركواز ساطع", "Bright Turquoise", 0xFF042A36L, 0xFF00ACC1L, 0xFF094C61L, 0xFF84FFFFL),
    LIME_FOREST("LIME_FOREST", "ليموني غابي", "Forest Lime", 0xFF182E04L, 0xFF65A30DL, 0xFF2C5208L, 0xFFBEF264L),
    CORAL_SUNSET("CORAL_SUNSET", "غروب مرجاني", "Coral Sunset", 0xFF47120CL, 0xFFDC2626L, 0xFF7F1D1DL, 0xFFFCA5A5L),
    VIOLET_PLUM("VIOLET_PLUM", "برقوقي داكن", "Violet Plum", 0xFF210738L, 0xFF6D28D9L, 0xFF3B0E63L, 0xFFC4B5FDL),
    OBSIDIAN("OBSIDIAN", "أوبسيديان أسود", "Black Obsidian", 0xFF05070AL, 0xFF1F2937L, 0xFF111827L, 0xFF00E5FFL),
    PEARL_BLUE("PEARL_BLUE", "أزرق لؤلؤي", "Pearl Blue", 0xFF0A2442L, 0xFF2563EBL, 0xFF143C6EL, 0xFFBFDBFEL),
    COPPER("COPPER", "نحاسي لامع", "Polished Copper", 0xFF361206L, 0xFFC2410CL, 0xFF5C210BL, 0xFFFED7AAL),
    JADE("JADE", "يشم ملكي", "Royal Jade", 0xFF042619L, 0xFF059669L, 0xFF09452EL, 0xFF6EE7B7L),
    MIDNIGHT_SKY("MIDNIGHT_SKY", "سماء منتصف الليل", "Midnight Sky", 0xFF091229L, 0xFF3B82F6L, 0xFF12234DL, 0xFF93C5FDL),
    CHARCOAL_GOLD("CHARCOAL_GOLD", "فحمي مذهب", "Charcoal Gold", 0xFF141418L, 0xFF27272FL, 0xFF1D1D24L, 0xFFFFD54FL);

    companion object {
        fun fromKey(key: String?): WesalNameplateColorPreset =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: CRIMSON
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
    val rawPalette = if (globalAppearance.enabled) {
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
    // Round 13 (Items H + M2): pairing invariant. Above, textPrimary and
    // surfaceElevated are overridden INDEPENDENTLY (the global-appearance
    // branch validates custom text against `surface` only and never
    // touches surfaceElevated; surface presets replace surfaceElevated
    // without touching text), and field sites historically paired
    // palette text with scheme containers — in light mode the owner's
    // typed text rendered near-white on light fields (device-proven:
    // assistant composer, contacts search digits «68742»). Guarantee the
    // pair every input relies on: when textPrimary is illegible on
    // surfaceElevated, snap surfaceElevated back to the base elevated of
    // the text's own luminance side (the designed pairing).
    val palette = run {
        val elevLum = rawPalette.surfaceElevated.relativeLuminance()
        val textLum = rawPalette.textPrimary.relativeLuminance()
        if (kotlin.math.abs(textLum - elevLum) >= 0.35f) {
            rawPalette
        } else {
            rawPalette.copy(
                surfaceElevated = if (textLum > 0.5f) {
                    WesalDarkPalette.surfaceElevated
                } else {
                    WesalLightPalette.surfaceElevated
                }
            )
        }
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
            onSurfaceVariant = palette.textSecondary,
            // Round 13 (Item H): the container slots are what Material 3
            // actually paints text-field and card containers from — they
            // used to stay at the BASE scheme's values while surface /
            // onSurface moved to the palette, so a remapped appearance
            // could pair a light container with light text (the owner's
            // white-ghost typing in light mode). They now follow the
            // palette like every other slot.
            surfaceVariant = palette.surfaceElevated,
            surfaceContainer = palette.surface,
            surfaceContainerLow = palette.background,
            surfaceContainerHigh = palette.surfaceElevated,
            surfaceContainerHighest = palette.surfaceElevated,
            surfaceContainerLowest = palette.background
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
