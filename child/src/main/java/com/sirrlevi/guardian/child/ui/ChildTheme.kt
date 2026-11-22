package com.sirrlevi.guardian.child.ui

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** The child's palette: warm, friendly and clearly different from the Parent app. */
@Immutable
class KColors(
    val bg: Color,
    val card: Color,
    val stroke: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val sun: Color,
    val coral: Color,
    val purple: Color,
    val sky: Color,
    val good: Color,
    val chart: List<Color>
)

private val LightK = KColors(
    bg = Color(0xFFF3FAF7), card = Color(0xFFFFFFFF), stroke = Color(0xFFDCEBE4), text = Color(0xFF12302B), muted = Color(0xFF5B7770),
    primary = Color(0xFF12A594), onPrimary = Color(0xFFFFFFFF), primarySoft = Color(0xFFDDF4EE),
    sun = Color(0xFFF5A623), coral = Color(0xFFE85D52), purple = Color(0xFF7458F0), sky = Color(0xFF2E8FE8), good = Color(0xFF1E9E5A),
    chart = listOf(Color(0xFF12A594), Color(0xFFF5A623), Color(0xFF7458F0), Color(0xFFE85D52), Color(0xFF2E8FE8), Color(0xFF8BC34A))
)

private val DarkK = KColors(
    bg = Color(0xFF0D1A18), card = Color(0xFF152825), stroke = Color(0xFF244039), text = Color(0xFFE8F5F1), muted = Color(0xFF8FB2AA),
    primary = Color(0xFF3FD6C2), onPrimary = Color(0xFF06211D), primarySoft = Color(0xFF1B3833),
    sun = Color(0xFFFFC152), coral = Color(0xFFFF7F73), purple = Color(0xFF9B86FF), sky = Color(0xFF5BB0FF), good = Color(0xFF55D58E),
    chart = listOf(Color(0xFF3FD6C2), Color(0xFFFFC152), Color(0xFF9B86FF), Color(0xFFFF7F73), Color(0xFF5BB0FF), Color(0xFFA7D86B))
)

private val LocalKColors = staticCompositionLocalOf { LightK }

val kc: KColors
    @Composable @ReadOnlyComposable get() = LocalKColors.current

@Composable
fun ChildTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkK else LightK
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
            surfaceVariant = c.primarySoft, onSurfaceVariant = c.muted, outline = c.stroke, error = c.coral
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
            surfaceVariant = c.primarySoft, onSurfaceVariant = c.muted, outline = c.stroke, error = c.coral
        )
    }
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 32.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    )
    CompositionLocalProvider(LocalKColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)
            ),
            content = content
        )
    }
}

// ---------------------------------------------------------------- components

@Composable
fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = kc.card,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(container).border(1.dp, kc.stroke, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun KPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 5.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun KButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, soft: Boolean = false, color: Color = kc.primary) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (soft) color.copy(alpha = 0.14f) else color
    val fg = if (soft) color else kc.onPrimary
    Box(
        modifier.clip(shape).background(if (enabled) bg else kc.stroke).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) fg else kc.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun KSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(kc.primarySoft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) kc.primary else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) kc.onPrimary else kc.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}

@Composable
fun KProgress(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(kc.stroke.copy(alpha = 0.7f))) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(50)).background(color))
    }
}

/** A donut made of coloured parts. [maxValue] turns it into a progress ring. */
@Composable
fun KRing(
    parts: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    diameter: Dp = 170.dp,
    thickness: Dp = 18.dp,
    maxValue: Float? = null,
    center: @Composable () -> Unit = {}
) {
    val track = kc.stroke.copy(alpha = 0.8f)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val d = this.size.minDimension - stroke
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
            val total = maxValue ?: parts.sumOf { it.first.toDouble() }.toFloat()
            if (total > 0f) {
                var start = -90f
                parts.forEach { (value, color) ->
                    val sweep = 360f * (value / total).coerceIn(0f, 1f)
                    if (sweep > 0.5f) {
                        drawArc(color, start, (sweep - if (parts.size > 1) 3f else 0f).coerceAtLeast(0.5f), false, topLeft, Size(d, d),
                            style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    start += sweep
                }
            }
        }
        center()
    }
}

@Composable
fun KSection(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = kc.text)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = kc.muted)
    }
}

/** The real icon of an installed app, read from Android – no network needed. */
@Composable
fun KAppIcon(pkg: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    val bmp: ImageBitmap? = remember(pkg) {
        try {
            val d = context.packageManager.getApplicationIcon(pkg)
            if (d is BitmapDrawable && d.bitmap != null) d.bitmap.asImageBitmap() else d.toBitmap(96, 96).asImageBitmap()
        } catch (_: Exception) { null }
    }
    val shape = RoundedCornerShape(size * 0.28f)
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
    } else {
        Box(Modifier.size(size).clip(shape).background(kc.primarySoft), contentAlignment = Alignment.Center) {
            Text(pkg.substringAfterLast('.').firstOrNull()?.uppercase() ?: "?", color = kc.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun rememberAppLabel(pkg: String): String {
    val context = LocalContext.current
    return remember(pkg) {
        try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
    }
}

@Composable
fun KEmoji(emoji: String, bg: Color = kc.primarySoft, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.34f)).background(bg), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
fun KGap(height: Dp) { Spacer(Modifier.height(height)) }
