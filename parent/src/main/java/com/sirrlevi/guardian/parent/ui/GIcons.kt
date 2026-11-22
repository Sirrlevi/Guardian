package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Hand-drawn 24×24 line icons, so the app needs no icon library. */
enum class GIcon {
    Home, Apps, Activity, Chart, Settings, Block, Timer, Globe, Battery, Wifi, Search, Close, Check,
    Chevron, Back, Plus, Bell, Download, Shield, Refresh, Lock, Phone, Trash, Pause, Warning, Person, Moon, Play, Link
}

@Composable
fun GIconView(icon: GIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, strokeWidth: Float = 2f) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = strokeWidth * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawGIcon(icon, tint, u, stroke)
    }
}

private fun DrawScope.drawGIcon(icon: GIcon, c: Color, u: Float, stroke: Stroke) {
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
    fun poly(vararg p: Float, close: Boolean = false) {
        val path = Path()
        path.moveTo(p[0] * u, p[1] * u)
        var i = 2
        while (i < p.size) { path.lineTo(p[i] * u, p[i + 1] * u); i += 2 }
        if (close) path.close()
        drawPath(path, c, style = stroke)
    }
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(c, r * u, o(cx, cy), style = stroke)
    fun dot(cx: Float, cy: Float, r: Float) = drawCircle(c, r * u, o(cx, cy))
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(c, o(x, y), Size(w * u, h * u), CornerRadius(r * u, r * u), style = stroke)

    when (icon) {
        GIcon.Home -> { poly(3f, 11f, 12f, 3.5f, 21f, 11f); poly(5.5f, 9.5f, 5.5f, 20f, 18.5f, 20f, 18.5f, 9.5f); poly(10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f) }
        GIcon.Apps -> { box(4f, 4f, 6.5f, 6.5f, 1.8f); box(13.5f, 4f, 6.5f, 6.5f, 1.8f); box(4f, 13.5f, 6.5f, 6.5f, 1.8f); box(13.5f, 13.5f, 6.5f, 6.5f, 1.8f) }
        GIcon.Activity -> { ring(12f, 12f, 9f); poly(12f, 7f, 12f, 12f, 15.5f, 14f) }
        GIcon.Chart -> { line(6f, 20f, 6f, 12f); line(12f, 20f, 12f, 5f); line(18f, 20f, 18f, 9f) }
        GIcon.Settings -> {
            line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
            drawCircle(c.copy(alpha = 1f), 2.4f * u, o(15f, 6f)); drawCircle(c, 2.4f * u, o(8f, 12f)); drawCircle(c, 2.4f * u, o(16f, 18f))
        }
        GIcon.Block -> { ring(12f, 12f, 9f); line(5.7f, 5.7f, 18.3f, 18.3f) }
        GIcon.Timer -> { ring(12f, 13.5f, 7.5f); line(12f, 13.5f, 12f, 9.5f); line(9.5f, 3f, 14.5f, 3f); line(12f, 3f, 12f, 6f) }
        GIcon.Globe -> { ring(12f, 12f, 9f); line(3f, 12f, 21f, 12f); drawOval(c, o(8f, 3f), Size(8f * u, 18f * u), style = stroke) }
        GIcon.Battery -> { box(3f, 7f, 16f, 10f, 2.5f); line(21.5f, 10.5f, 21.5f, 13.5f) }
        GIcon.Wifi -> {
            for (r in listOf(4f, 8f, 12f)) drawArc(c, 225f, 90f, false, o(12f - r, 19f - r), Size(2 * r * u, 2 * r * u), style = stroke)
            dot(12f, 19f, 1.3f)
        }
        GIcon.Search -> { ring(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20.5f, 20.5f) }
        GIcon.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        GIcon.Check -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
        GIcon.Chevron -> poly(9f, 5f, 16f, 12f, 9f, 19f)
        GIcon.Back -> poly(15f, 5f, 8f, 12f, 15f, 19f)
        GIcon.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        GIcon.Bell -> { poly(6f, 16f, 6f, 11f, 6.5f, 9f, 8f, 6.5f, 12f, 5.2f, 16f, 6.5f, 17.5f, 9f, 18f, 11f, 18f, 16f, 19.5f, 17.5f, 4.5f, 17.5f, close = true); line(10.5f, 20.5f, 13.5f, 20.5f) }
        GIcon.Download -> { line(12f, 4f, 12f, 15f); poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); line(5f, 20f, 19f, 20f) }
        GIcon.Shield -> {
            val p = Path()
            p.moveTo(12f * u, 3f * u); p.lineTo(19f * u, 6f * u); p.lineTo(19f * u, 12f * u)
            p.cubicTo(19f * u, 16.5f * u, 16f * u, 19.5f * u, 12f * u, 21f * u)
            p.cubicTo(8f * u, 19.5f * u, 5f * u, 16.5f * u, 5f * u, 12f * u); p.lineTo(5f * u, 6f * u); p.close()
            drawPath(p, c, style = stroke)
        }
        GIcon.Refresh -> { drawArc(c, -50f, 280f, false, o(5f, 5f), Size(14f * u, 14f * u), style = stroke); poly(19f, 3.5f, 19f, 8.5f, 14f, 8.5f) }
        GIcon.Lock -> { box(5f, 11f, 14f, 9.5f, 2.5f); poly(8f, 11f, 8f, 8f, 9f, 5.8f, 12f, 4.8f, 15f, 5.8f, 16f, 8f, 16f, 11f) }
        GIcon.Phone -> { box(7f, 2.5f, 10f, 19f, 2.5f); line(10.5f, 18.5f, 13.5f, 18.5f) }
        GIcon.Trash -> { line(4f, 6.5f, 20f, 6.5f); poly(9f, 6.5f, 9f, 4f, 15f, 4f, 15f, 6.5f); poly(6.5f, 6.5f, 7.5f, 20f, 16.5f, 20f, 17.5f, 6.5f) }
        GIcon.Pause -> { line(9f, 5f, 9f, 19f); line(15f, 5f, 15f, 19f) }
        GIcon.Warning -> { poly(12f, 4f, 21f, 19.5f, 3f, 19.5f, close = true); line(12f, 10f, 12f, 14.5f); dot(12f, 17f, 1.1f) }
        GIcon.Person -> { ring(12f, 8f, 4f); drawArc(c, 200f, 140f, false, o(4.5f, 13.5f), Size(15f * u, 14f * u), style = stroke) }
        GIcon.Moon -> drawArc(c, 70f, 240f, false, o(4f, 4f), Size(16f * u, 16f * u), style = stroke)
        GIcon.Play -> poly(8f, 5f, 19f, 12f, 8f, 19f, close = true)
        GIcon.Link -> { box(3f, 9f, 11f, 6f, 3f); box(10f, 9f, 11f, 6f, 3f) }
    }
}
