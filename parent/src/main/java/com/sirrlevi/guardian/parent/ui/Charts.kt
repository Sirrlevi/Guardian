package com.sirrlevi.guardian.parent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.Durations
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class Slice(val label: String, val value: Float, val color: Color)

/** A ring chart that sweeps in when it first appears. The text in the middle is supplied by the caller. */
@Composable
fun DonutChart(slices: List<Slice>, modifier: Modifier = Modifier, diameter: Dp = 170.dp, thickness: Dp = 22.dp, center: @Composable () -> Unit = {}) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val total = slices.sumOf { it.value.toDouble() }.toFloat()
            if (total > 0f) {
                val gap = if (slices.size > 1) 3f else 0f
                var start = -90f
                slices.forEach { slice ->
                    val sweep = slice.value / total * 360f * progress.value
                    if (sweep > 0.5f) {
                        drawArc(slice.color, start + gap / 2f, max(sweep - gap, 0.5f), false, Offset(inset, inset), arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Butt))
                    }
                    start += sweep
                }
            }
        }
        center()
    }
}

/** Vertical bars with tap-to-inspect. Labels are drawn by the caller-supplied list so they stay crisp. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 150.dp,
    highlightLast: Boolean = false,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {}
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val grid = gColors.stroke
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(values.size) {
                detectTapGestures { offset ->
                    if (values.isEmpty()) return@detectTapGestures
                    val index = (offset.x / (this.size.width.toFloat() / values.size)).toInt().coerceIn(0, values.size - 1)
                    onSelect(if (selected == index) null else index)
                }
            }
        ) {
            val n = values.size
            if (n == 0) return@Canvas
            val maxV = max(values.maxOrNull() ?: 0f, 1f)
            val slot = this.size.width / n
            val barW = slot * 0.58f
            for (i in 1..3) {
                val y = this.size.height * (1f - i / 4f)
                drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(this.size.width, y), strokeWidth = 1f)
            }
            values.forEachIndexed { i, v ->
                val h = (v / maxV) * (this.size.height - 4f) * progress.value
                val x = i * slot + (slot - barW) / 2f
                drawRoundRect(track, Offset(x, 0f), Size(barW, this.size.height), CornerRadius(barW / 2f), alpha = 0.35f)
                val c = if (selected != null && selected != i) color.copy(alpha = 0.35f) else color
                if (h > 0.5f) drawRoundRect(c, Offset(x, this.size.height - h), Size(barW, h), CornerRadius(barW / 2f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gColors.muted, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** A smooth line with a soft fill. Drag a finger across it to read any day. */
@Composable
fun AreaChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 150.dp,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {}
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    val grid = gColors.stroke
    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    if (values.size < 2) return@detectTapGestures
                    val i = (offset.x / this.size.width * (values.size - 1) + 0.5f).toInt().coerceIn(0, values.size - 1)
                    onSelect(if (selected == i) null else i)
                }
            }
            .pointerInput(values.size) {
                detectHorizontalDragGestures(onHorizontalDrag = { change, _ ->
                    if (values.size >= 2) {
                        val i = (change.position.x / this.size.width * (values.size - 1) + 0.5f).toInt().coerceIn(0, values.size - 1)
                        onSelect(i)
                        change.consume()
                    }
                })
            }
    ) {
        if (values.size < 2) return@Canvas
        val maxV = max(values.maxOrNull() ?: 0f, 1f)
        val w = this.size.width
        val h = this.size.height - 8f
        for (i in 1..3) {
            val y = h * (1f - i / 4f) + 4f
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        fun px(i: Int) = i * w / (values.size - 1)
        fun py(v: Float) = 4f + h - (v / maxV) * h * progress.value
        val line = Path()
        line.moveTo(px(0), py(values[0]))
        for (i in 1 until values.size) {
            val x0 = px(i - 1); val y0 = py(values[i - 1]); val x1 = px(i); val y1 = py(values[i])
            val mid = (x0 + x1) / 2f
            line.cubicTo(mid, y0, mid, y1, x1, y1)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, this@Canvas.size.height); lineTo(0f, this@Canvas.size.height); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(3.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val s = selected
        if (s != null && s in values.indices) {
            val x = px(s); val y = py(values[s])
            drawLine(color.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, this.size.height), strokeWidth = 2f)
            drawCircle(color, 6f * density, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.9f), 2.6f * density, Offset(x, y))
        }
    }
}

/**
 * A round dial for picking a time: drag the handle around the ring. One full turn is [maxSec].
 * The value snaps to [stepSec]. Everything else (centre text) is supplied by the caller.
 */
@Composable
fun TimeDial(
    valueSec: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxSec: Int = 6 * 3600,
    stepSec: Int = 5 * 60,
    diameter: Dp = 220.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    center: @Composable () -> Unit
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = gColors.muted
    var lastValue by remember { mutableStateOf(valueSec) }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(maxSec, stepSec) {
                fun valueAt(p: Offset): Int {
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat() + 90f
                    if (deg < 0f) deg += 360f
                    var v = Durations.snap((deg / 360f * maxSec).toInt(), stepSec)
                    // Crossing the 12 o'clock mark must not jump from "full" back to zero (or the reverse).
                    if (lastValue >= maxSec * 0.75f && v <= maxSec * 0.25f) v = maxSec
                    if (lastValue <= maxSec * 0.25f && v >= maxSec * 0.75f) v = 0
                    return v.coerceIn(0, maxSec)
                }
                detectDragGestures(
                    onDragStart = { p -> val v = valueAt(p); lastValue = v; onChange(v) },
                    onDrag = { change, _ -> val v = valueAt(change.position); lastValue = v; onChange(v); change.consume() }
                )
            }.pointerInput(maxSec, stepSec) {
                detectTapGestures { p ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat() + 90f
                    if (deg < 0f) deg += 360f
                    val v = Durations.snap((deg / 360f * maxSec).toInt(), stepSec).coerceIn(0, maxSec)
                    lastValue = v
                    onChange(v)
                }
            }
        ) {
            val stroke = 20.dp.toPx()
            val inset = stroke / 2f + 6.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            // hour ticks
            val radius = arcSize.width / 2f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val hours = maxSec / 3600
            for (h in 0 until hours) {
                val a = (h.toFloat() / hours) * 2f * PI.toFloat() - (PI / 2).toFloat()
                val r1 = radius - stroke / 2f - 4.dp.toPx()
                val r2 = r1 - 5.dp.toPx()
                drawLine(tick.copy(alpha = 0.6f), Offset(cx + cos(a) * r1, cy + sin(a) * r1), Offset(cx + cos(a) * r2, cy + sin(a) * r2), strokeWidth = 2f)
            }
            val sweep = (valueSec.toFloat() / maxSec).coerceIn(0f, 1f) * 360f
            if (sweep > 0f) {
                drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))), -90f, sweep, false,
                    Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val ha = (-90f + sweep) * PI.toFloat() / 180f
            val hx = cx + cos(ha) * radius
            val hy = cy + sin(ha) * radius
            drawCircle(Color.White, stroke * 0.62f, Offset(hx, hy))
            drawCircle(color, stroke * 0.34f, Offset(hx, hy))
        }
        center()
    }
}

/** One row of a ranking: icon, name, bar and value. */
@Composable
fun RankRow(
    leading: @Composable () -> Unit,
    title: String,
    value: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Box(Modifier.height(6.dp))
            ProgressBar(fraction, color, height = 6.dp)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
    }
}

