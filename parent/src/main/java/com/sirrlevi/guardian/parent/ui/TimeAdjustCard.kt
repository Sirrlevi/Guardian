package com.sirrlevi.guardian.parent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.AppRule
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.ParentViewModel
import kotlinx.coroutines.delay

/** A real minus sign, so "−30 min" lines up with "+30 min". */
const val MINUS_SIGN = "−"

/** Minutes offered on both sides of the card. */
val AdjustSteps = listOf(10, 30, 60)

fun minusLabel(minutes: Int) = "$MINUS_SIGN$minutes min"
fun plusLabel(minutes: Int) = "+$minutes min"

/**
 * Today-only time for one app: three "take away" buttons on the left, three "add" buttons on the right.
 * Every tap changes the numbers at once (they roll up or down), buzzes lightly, and says what happened,
 * so there is never any doubt that a tap counted. Tomorrow the normal limit applies again.
 */
@Composable
fun TimeAdjustCard(vm: ParentViewModel, pkg: String, rule: AppRule, clock: DayClock) {
    val base = rule.baseLimitFor(clock.dayOfWeek) ?: return
    val extra = rule.extraFor(clock.dayKey)
    val total = rule.limitFor(clock.dayOfWeek, clock.dayKey) ?: base

    var rollsUp by remember { mutableStateOf(true) }
    var flashText by remember { mutableStateOf("") }
    var flashColor by remember { mutableStateOf(Color.Unspecified) }
    var flashOn by remember { mutableStateOf(false) }
    var flashTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            delay(2000)
            flashOn = false
        }
    }
    val up = gColors.success
    val down = gColors.danger

    fun show(text: String, color: Color) {
        flashText = text
        flashColor = color
        flashOn = true
        flashTick++
    }

    fun step(minutes: Int) {
        val before = vm.ruleFor(pkg).extraFor(clock.dayKey)
        rollsUp = minutes > 0
        val after = vm.adjustToday(pkg, minutes * 60)
        val applied = after.extraFor(clock.dayKey) - before
        val nowTotal = after.limitFor(clock.dayOfWeek, clock.dayKey) ?: base
        when {
            applied == 0 && minutes < 0 -> show("Already at 0. Nothing left to take away.", down)
            applied == 0 -> show("Can't add more than 12 hours.", down)
            else -> show(
                Durations.signed(applied) + "  ·  limit today " + Durations.compact(nowTotal),
                if (applied > 0) up else down
            )
        }
    }

    GCard(padding = 14.dp) {
        Text("Adjust today's time", style = MaterialTheme.typography.titleSmall)
        Text(
            "One-off, for today only. Your normal limit is not changed, and tomorrow it applies again.",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted
        )
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryCell("Normal limit", Durations.compact(base), rollsUp, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            SummaryCell(
                "Today's change", Durations.signed(extra), rollsUp,
                if (extra > 0) up else if (extra < 0) down else gColors.muted, Modifier.weight(1f)
            )
            SummaryCell("Limit today", Durations.compact(total), rollsUp, MaterialTheme.colorScheme.primary, Modifier.weight(1f), bold = true)
        }

        AnimatedVisibility(
            visible = flashOn,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
                    .background(flashColor.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(flashText, style = MaterialTheme.typography.labelLarge, color = flashColor, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdjustSteps.forEach { m -> StepButton(minusLabel(m), down, total > 0, Modifier.fillMaxWidth()) { step(-m) } }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdjustSteps.forEach { m -> StepButton(plusLabel(m), up, extra < AppRule.MAX_EXTRA_SEC, Modifier.fillMaxWidth()) { step(m) } }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (extra != 0) {
            SoftButton(
                "Reset to normal limit (" + Durations.compact(base) + ")",
                {
                    rollsUp = extra < 0
                    vm.resetToday(pkg)
                    show("Back to the normal limit of " + Durations.compact(base), gColors.muted)
                },
                Modifier.fillMaxWidth(), color = gColors.muted
            )
        } else {
            Text(
                "No change today. The normal limit applies.",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = gColors.muted, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, rollsUp: Boolean, color: Color, modifier: Modifier, bold: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = gColors.muted, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically { h -> if (rollsUp) h else -h } + fadeIn()) togetherWith
                    (slideOutVertically { h -> if (rollsUp) -h else h } + fadeOut())
            },
            label = "roll"
        ) { text ->
            Text(
                text, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

/** A button that visibly squeezes when pressed, ripples, buzzes lightly and then springs back. */
@Composable
private fun StepButton(text: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    val fill = if (!enabled) 0.05f else if (pressed) 0.30f else 0.14f
    Box(
        modifier.height(52.dp).scale(scale).clip(shape)
            .background(color.copy(alpha = fill))
            .border(1.dp, color.copy(alpha = if (enabled) 0.55f else 0.2f), shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) color else gColors.muted, maxLines = 1)
    }
}
