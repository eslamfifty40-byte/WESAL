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

@Composable
fun WesalVerifiedMark(badgeSize: Dp) {
    Canvas(Modifier.size(badgeSize)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        val star = Path()
        val n = 24
        for (i in 0 until n) {
            val ang = Math.PI * 2 * i / n - Math.PI / 2
            val r = if (i % 2 == 0) s * 0.47f else s * 0.39f
            val x = c.x + (r * Math.cos(ang)).toFloat()
            val y = c.y + (r * Math.sin(ang)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        val blue = Color(0xFF1D9BF0)
        drawPath(star, blue)
        drawPath(star, blue, style = Stroke(width = s * 0.06f, join = StrokeJoin.Round))
        val check = Path()
        check.moveTo(s * 0.28f, s * 0.52f)
        check.lineTo(s * 0.44f, s * 0.67f)
        check.lineTo(s * 0.73f, s * 0.36f)
        drawPath(check, Color.White, style = Stroke(width = s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
