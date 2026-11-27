package com.wesal.messenger.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import com.wesal.messenger.core.util.NeonColorPreset
import com.wesal.messenger.core.util.VerificationSealShape
import com.wesal.messenger.core.util.VerificationSealType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WesalVerifiedMark(
    badgeSize: Dp,
    modifier: Modifier = Modifier,
    badgeColor: Color = Color(0xFF1D9BF0),
    verificationType: String = "BLUE",
    glowColor: Color? = null,
    verificationGlowColor: String = "",
    glowEnabled: Boolean = true,
    glowIntensity: Float = 0.7f
) {
    val sealType = VerificationSealType.fromKey(verificationType)
    val resolvedFill = if (sealType == VerificationSealType.BLUE && badgeColor != Color(0xFF1D9BF0)) {
        badgeColor
    } else {
        Color(sealType.primaryHex)
    }
    val borderStrokeColor = Color(sealType.secondaryHex)
    val customGlowPreset = NeonColorPreset.fromKey(verificationGlowColor).takeIf { it != NeonColorPreset.NONE }
    val effectiveGlowColor = glowColor ?: customGlowPreset?.glowColor
    val halo = effectiveGlowColor ?: Color(sealType.glowHex)
    val effectiveGlow = glowIntensity.coerceIn(0.15f, 1.0f)

    Canvas(modifier.size(badgeSize)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        // Round 10 (item 8): the badge read as "مقصوصة من تحت" (clipped at
        // the bottom) in list rows, chips and the settings card. Root cause:
        // the glow halo was drawn with radius 0.90×size — it reaches 1.4×
        // the canvas and Compose clips every draw at the canvas bounds, so
        // the halo was sliced on all four sides (most visible at the
        // bottom, against the next row), and the seal's own stroke sits
        // within ~1% of the edge for the starburst/shield shapes. Fix: draw
        // the whole mark scaled 0.92 about the centre (a real inset margin
        // for seal + stroke) and cap the glow gradient at 0.54×size so —
        // after the inset — it fades to transparent BEFORE the edge and can
        // never be clipped, in any parent (rows, FilterChips, avatar
        // corners) at any call site.
        withTransform({ scale(0.92f, 0.92f, c) }) {
        if (glowEnabled) {
            val innerAlpha = ((if (effectiveGlowColor != null) 0.62f else 0.42f) * effectiveGlow).coerceIn(0.1f, 0.92f)
            val midAlpha = ((if (effectiveGlowColor != null) 0.28f else 0.18f) * effectiveGlow).coerceIn(0.05f, 0.50f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        halo.copy(alpha = innerAlpha),
                        halo.copy(alpha = midAlpha),
                        Color.Transparent
                    ),
                    center = c,
                    radius = s * 0.54f
                ),
                radius = s * 0.54f,
                center = c
            )
        }
        // Badge SHAPE comes from the seal type the admin granted (spec §6.1): the same
        // shape renders in the profile, chat list, chat screen, search and contacts,
        // because every surface draws through this one composable.
        val badgePath = Path()
        fun polygon(sides: Int, radius: Float, rotation: Double = -PI / 2) {
            for (i in 0 until sides) {
                val ang = PI * 2 * i / sides + rotation
                val x = c.x + (radius * cos(ang)).toFloat()
                val y = c.y + (radius * sin(ang)).toFloat()
                if (i == 0) badgePath.moveTo(x, y) else badgePath.lineTo(x, y)
            }
            badgePath.close()
        }
        when (sealType.shape) {
            VerificationSealShape.STARBURST -> {
                val n = 24
                for (i in 0 until n) {
                    val ang = PI * 2 * i / n - PI / 2
                    val r = if (i % 2 == 0) s * 0.46f else s * 0.38f
                    val x = c.x + (r * cos(ang)).toFloat()
                    val y = c.y + (r * sin(ang)).toFloat()
                    if (i == 0) badgePath.moveTo(x, y) else badgePath.lineTo(x, y)
                }
                badgePath.close()
            }
            VerificationSealShape.CIRCLE -> {
                badgePath.addOval(androidx.compose.ui.geometry.Rect(center = c, radius = s * 0.45f))
            }
            VerificationSealShape.SHIELD -> {
                badgePath.moveTo(c.x, s * 0.06f)
                badgePath.lineTo(s * 0.82f, s * 0.16f)
                badgePath.lineTo(s * 0.82f, s * 0.48f)
                badgePath.quadraticBezierTo(s * 0.82f, s * 0.78f, c.x, s * 0.95f)
                badgePath.quadraticBezierTo(s * 0.18f, s * 0.78f, s * 0.18f, s * 0.48f)
                badgePath.lineTo(s * 0.18f, s * 0.16f)
                badgePath.close()
            }
            VerificationSealShape.DIAMOND -> polygon(4, s * 0.48f)
            VerificationSealShape.HEXAGON -> polygon(6, s * 0.47f)
            VerificationSealShape.OCTAGON -> polygon(8, s * 0.47f, -PI / 2 + PI / 8)
        }
        drawPath(badgePath, resolvedFill)
        drawPath(
            path = badgePath,
            color = borderStrokeColor,
            style = Stroke(width = s * 0.055f, join = StrokeJoin.Round)
        )
        val check = Path()
        check.moveTo(s * 0.29f, s * 0.52f)
        check.lineTo(s * 0.44f, s * 0.67f)
        check.lineTo(s * 0.72f, s * 0.36f)
        val checkColor = if (sealType == VerificationSealType.PLATINUM || sealType == VerificationSealType.PEARL || sealType == VerificationSealType.NEON_YELLOW) {
            Color(0xFF0A1116)
        } else {
            Color.White
        }
        drawPath(
            path = check,
            color = checkColor,
            style = Stroke(width = s * 0.105f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        } // withTransform (round-10 inset)
    }
}
