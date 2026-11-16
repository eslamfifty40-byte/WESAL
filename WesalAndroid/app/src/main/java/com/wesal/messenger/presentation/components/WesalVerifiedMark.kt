package com.wesal.messenger.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.wesal.messenger.core.util.VerificationSealType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WesalVerifiedMark(
    badgeSize: Dp,
    modifier: Modifier = Modifier,
    badgeColor: Color = Color(0xFF1D9BF0),
    verificationType: String = "BLUE"
) {
    val sealType = VerificationSealType.fromKey(verificationType)
    val resolvedFill = when (sealType) {
        VerificationSealType.BLUE -> if (badgeColor == Color(0xFF1D9BF0)) Color(0xFF1D9BF0) else badgeColor
        VerificationSealType.BLACK -> Color(0xFF0B0E11)
        VerificationSealType.GREEN -> Color(0xFF00D66B)
    }
    val borderStrokeColor = when (sealType) {
        VerificationSealType.BLACK -> Color(0xFFE2E8F0)
        else -> resolvedFill
    }

    Canvas(modifier.size(badgeSize)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        val star = Path()
        val n = 24
        for (i in 0 until n) {
            val ang = PI * 2 * i / n - PI / 2
            val r = if (i % 2 == 0) s * 0.46f else s * 0.38f
            val x = c.x + (r * cos(ang)).toFloat()
            val y = c.y + (r * sin(ang)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, resolvedFill)
        drawPath(
            path = star,
            color = borderStrokeColor,
            style = Stroke(width = if (sealType == VerificationSealType.BLACK) s * 0.065f else s * 0.05f, join = StrokeJoin.Round)
        )
        val check = Path()
        check.moveTo(s * 0.29f, s * 0.52f)
        check.lineTo(s * 0.44f, s * 0.67f)
        check.lineTo(s * 0.72f, s * 0.36f)
        drawPath(
            path = check,
            color = Color.White,
            style = Stroke(width = s * 0.105f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
