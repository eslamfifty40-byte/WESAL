package com.wesal.messenger.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun wesalTypography(fontScale: Float = 1.0f): Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = (28 * fontScale).sp,
        lineHeight = (36 * fontScale).sp,
        color = WesalTextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = (22 * fontScale).sp,
        lineHeight = (28 * fontScale).sp,
        color = WesalTextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = (19 * fontScale).sp,
        lineHeight = (26 * fontScale).sp,
        color = WesalTextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = (16 * fontScale).sp,
        lineHeight = (22 * fontScale).sp,
        color = WesalTextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = (15.5f * fontScale).sp,
        lineHeight = (22 * fontScale).sp,
        color = WesalTextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = (14 * fontScale).sp,
        lineHeight = (20 * fontScale).sp,
        color = WesalTextSecondary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = (12 * fontScale).sp,
        lineHeight = (16 * fontScale).sp,
        color = WesalTextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = (11 * fontScale).sp,
        lineHeight = (14 * fontScale).sp,
        color = WesalTextMuted
    )
)
