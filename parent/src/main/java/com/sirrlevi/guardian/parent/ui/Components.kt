package com.sirrlevi.guardian.parent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    container: Color = gColors.card,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    var m = modifier.fillMaxWidth().clip(shape).background(container).border(1.dp, gColors.stroke, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        trailing?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) MaterialTheme.colorScheme.background else color, maxLines = 1)
    }
}

@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier, dot: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "live")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    androidx.compose.foundation.Canvas(modifier.size(dot * 2.6f)) {
        val radius = dot.toPx() / 2f
        drawCircle(color.copy(alpha = (1f - phase.value) * 0.45f), radius = radius * (1f + 1.4f * phase.value), center = center)
        drawCircle(color, radius = radius, center = center)
    }
}

private val AvatarColors = listOf(
    Color(0xFF3DA9A0), Color(0xFF5C8DF0), Color(0xFFE09A3E), Color(0xFF9C7BE8), Color(0xFFE0689A), Color(0xFF58B85A), Color(0xFFE0703F)
)

@Composable
fun AppIconView(pkg: String, name: String, icons: Map<String, ImageBitmap>, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.26f)
    val bmp = icons[pkg]
    if (bmp != null) {
        Image(bmp, contentDescription = name, modifier = modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
    } else {
        val tint = AvatarColors[(name.firstOrNull()?.code ?: 0) % AvatarColors.size]
        Box(modifier.size(size).clip(shape).background(tint.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull()?.uppercase() ?: "?", color = tint, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: GIcon? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    ) {
        if (icon != null) { GIconView(icon, MaterialTheme.colorScheme.onPrimary, size = 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary, icon: GIcon? = null) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        if (icon != null) { GIconView(icon, color, size = 18.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun EmptyState(icon: GIcon, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            GIconView(icon, MaterialTheme.colorScheme.primary, size = 30.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = gColors.muted, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color = MaterialTheme.colorScheme.surfaceVariant) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = gColors.muted, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1)
    }
}

@Composable
fun Dimmed(visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (visible) 1f else 0.45f)) { content() }
}
