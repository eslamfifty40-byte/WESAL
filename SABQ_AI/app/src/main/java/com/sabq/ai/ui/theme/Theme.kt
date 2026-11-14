package com.sabq.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SabqColors {
    val Black = Color(0xFF06070D)
    val Surface = Color(0xFF0E101C)
    val SurfaceElevated = Color(0xFF141729)
    val Purple = Color(0xFF8B5CF6)
    val PurpleBright = Color(0xFFA855F7)
    val Cyan = Color(0xFF00D4FF)
    val Emerald = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextMuted = Color(0xFF94A3B8)
    val Border = Color(0x388B5CF6)
}

private val SabqDarkScheme = darkColorScheme(
    primary = SabqColors.Purple,
    secondary = SabqColors.Cyan,
    background = SabqColors.Black,
    surface = SabqColors.Surface,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = SabqColors.TextPrimary,
    onSurface = SabqColors.TextPrimary
)

@Composable
fun SabqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SabqDarkScheme,
        content = content
    )
}
