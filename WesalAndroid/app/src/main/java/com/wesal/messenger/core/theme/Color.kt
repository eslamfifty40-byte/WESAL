package com.wesal.messenger.core.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// WESAL Official Brand Reference Palette (Sec 2 & Sec 8)
val WesalDeepBlack = Color(0xFF05090B)
val WesalSurfaceDark = Color(0xFF0B1115)
val WesalSurfaceElevated = Color(0xFF131C22)
val WesalSurfaceBorder = Color(0xFF1E2B33)

val WesalEmerald = Color(0xFF00D66B)
val WesalEmeraldDark = Color(0xFF008744)
val WesalTurquoise = Color(0xFF00D5E8)
val WesalTurquoiseSoft = Color(0xFF00D9E8)

val WesalTextPrimary = Color(0xFFFFFFFF)
val WesalTextSecondary = Color(0xFFB6C2CF)
val WesalTextMuted = Color(0xFF7D8B99)

val WesalBubbleOutgoing = Color(0xFF064E3B)
val WesalBubbleOutgoingAccent = Color(0xFF0A684D)
val WesalBubbleIncoming = Color(0xFF141E24)

val WesalError = Color(0xFFEF4444)
val WesalWarning = Color(0xFFF59E0B)
val WesalReadTick = Color(0xFF00D9E8)
val WesalVerifiedBlue = Color(0xFF1D9BF0)

data class WesalPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val emerald: Color,
    val emeraldDark: Color,
    val turquoise: Color,
    val turquoiseSoft: Color,
    val onAccent: Color,
    val onPrimary: Color = Color(0xFF06090B),
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingAccent: Color,
    val bubbleIncoming: Color,
    val error: Color,
    val warning: Color,
    val readTick: Color,
    val verifiedBlue: Color
)

val WesalDarkPalette = WesalPalette(
    isDark = true,
    background = WesalDeepBlack,
    surface = WesalSurfaceDark,
    surfaceElevated = WesalSurfaceElevated,
    border = WesalSurfaceBorder,
    emerald = WesalEmerald,
    emeraldDark = WesalEmeraldDark,
    turquoise = WesalTurquoise,
    turquoiseSoft = WesalTurquoiseSoft,
    onAccent = WesalDeepBlack,
    onPrimary = WesalDeepBlack,
    textPrimary = WesalTextPrimary,
    textSecondary = WesalTextSecondary,
    textMuted = WesalTextMuted,
    bubbleOutgoing = WesalBubbleOutgoing,
    bubbleOutgoingAccent = WesalBubbleOutgoingAccent,
    bubbleIncoming = WesalBubbleIncoming,
    error = WesalError,
    warning = WesalWarning,
    readTick = WesalReadTick,
    verifiedBlue = WesalVerifiedBlue
)

val WesalLightPalette = WesalPalette(
    isDark = false,
    background = Color(0xFFF3F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE6EDF2),
    border = Color(0xFFCBD5E1),
    emerald = Color(0xFF009944),
    emeraldDark = Color(0xFF007A33),
    turquoise = Color(0xFF008B99),
    turquoiseSoft = Color(0xFF0FA3B1),
    onAccent = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0A1116),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    bubbleOutgoing = Color(0xFFD1FAE5),
    bubbleOutgoingAccent = Color(0xFFA7F3D0),
    bubbleIncoming = Color(0xFFFFFFFF),
    error = Color(0xFFDC2626),
    warning = Color(0xFFD97706),
    readTick = Color(0xFF0284C7),
    verifiedBlue = WesalVerifiedBlue
)

val WesalColors = staticCompositionLocalOf { WesalDarkPalette }
