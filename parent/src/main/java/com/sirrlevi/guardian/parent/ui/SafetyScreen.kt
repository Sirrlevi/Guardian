package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.AlertKind
import com.sirrlevi.guardian.core.AlertLevel
import com.sirrlevi.guardian.core.SafetyAlert
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.SafetyModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen says about itself, kept here so tests and the screen agree on the words. */
object SafetyTexts {
    const val INTRO_TITLE = "You review these, Guardian doesn't decide"
    const val INTRO = "These alerts come from simple rules over message previews, app use and the phone's own reports. " +
        "A rule can only notice a pattern or a word; it can't tell who someone is or what they meant. Nothing is blocked, and nothing leaves the app unless you turn on Telegram reports."
    const val NOTHING = "No alerts from the rules right now"
    const val NOTHING_BODY = "That doesn't mean everything is fine: previews are partial and many things can't be seen. It only means none of the rules below found anything."
    const val LOADING = "Checking…"
    const val FAILED = "Couldn't check just now."
    const val STALE = "Couldn't refresh. This is the last result."
    const val LIMITS_TITLE = "What these alerts can't tell you"
    const val LIMITS = "Message previews are short and sometimes missing. Alerts can be wrong either way: harmless messages can match, and real problems can go unnoticed. " +
        "If something worries you, talk to your child and read the messages in context."
}

private fun whenText(ts: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * Safety alerts for the selected phone. Each alert has a neutral label, the reason it is shown and when it happened; the parent can
 * dismiss it (and bring it back). Which kinds are on, and their limits, are set here too.
 */
@Composable
fun SafetyScreen(vm: ParentViewModel, padding: PaddingValues, watching: Boolean = true) {
    val device = vm.device
    val model = vm.safety
    val scope = rememberCoroutineScope()
    var showDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(device?.id, watching) { if (device != null && watching) model.watch() }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Safety alerts", style = MaterialTheme.typography.headlineMedium)
                Text(if (device != null) "For ${device.name}" else "For your child's phone", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        if (device == null) {
            item { GCard { EmptyState(GIcon.Shield, "No phone connected", "Pair your child's phone from More → Settings to see alerts.") } }
            return@LazyColumn
        }

        item {
            GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Row(verticalAlignment = Alignment.Top) {
                    GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(SafetyTexts.INTRO_TITLE, style = MaterialTheme.typography.titleSmall)
                        Text(SafetyTexts.INTRO, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                }
            }
        }

        item { StatusLine(model) }

        val notice = model.notice
        if (notice != null) {
            item {
                GCard(container = (if (model.noticeIsProblem) gColors.warning else gColors.success).copy(alpha = 0.12f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.clearNotice() }) { Text("OK") }
                    }
                }
            }
        }

        if (model.phase == SafetyModel.Phase.READY) {
            if (model.active.isEmpty()) {
                item { GCard { EmptyState(GIcon.Check, SafetyTexts.NOTHING, SafetyTexts.NOTHING_BODY) } }
            } else {
                items(model.active, key = { it.id }) { alert ->
                    AlertCard(alert, actionText = "Dismiss", busy = model.busy) { scope.launch { model.dismiss(alert.id) } }
                }
            }

            if (model.dismissed.isNotEmpty()) {
                item {
                    TextButton(onClick = { showDismissed = !showDismissed }) {
                        Text(if (showDismissed) "Hide dismissed (${model.dismissed.size})" else "Show dismissed (${model.dismissed.size})")
                    }
                }
                if (showDismissed) {
                    items(model.dismissed, key = { "d-" + it.id }) { alert ->
                        AlertCard(alert, actionText = "Bring back", busy = model.busy, muted = true) { scope.launch { model.restore(alert.id) } }
                    }
                }
            }

            item { SettingsCard(model) { block -> scope.launch { block() } } }
        }

        item {
            GCard {
                Text(SafetyTexts.LIMITS_TITLE, style = MaterialTheme.typography.titleSmall)
                Text(SafetyTexts.LIMITS, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(model: SafetyModel) {
    val error = model.error
    when {
        model.phase == SafetyModel.Phase.LOADING -> Text(SafetyTexts.LOADING, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        model.phase == SafetyModel.Phase.FAILED ->
            GCard(container = gColors.warning.copy(alpha = 0.12f)) {
                Text(SafetyTexts.FAILED, style = MaterialTheme.typography.titleSmall)
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        error != null ->
            GCard(container = gColors.warning.copy(alpha = 0.12f)) {
                Text(SafetyTexts.STALE, style = MaterialTheme.typography.titleSmall)
                Text("$error Last checked ${whenText(model.checkedAt)}.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        else -> Text("Last checked ${whenText(model.checkedAt)}", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
    }
}

@Composable
private fun AlertCard(alert: SafetyAlert, actionText: String, busy: Boolean, muted: Boolean = false, onAction: () -> Unit) {
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(alert.label, if (alert.level == AlertLevel.REVIEW && !muted) gColors.warning else gColors.muted)
            Spacer(Modifier.weight(1f))
            Text(whenText(alert.ts), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Text(alert.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(alert.reason, style = MaterialTheme.typography.bodyMedium, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
        val source = alert.source
        if (!source.isNullOrBlank()) Text("From: $source", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onAction, enabled = !busy) { Text(actionText) }
        }
    }
}

@Composable
private fun SettingsCard(model: SafetyModel, launch: (suspend () -> Unit) -> Unit) {
    val s = model.settings
    GCard {
        Text("What to look for", style = MaterialTheme.typography.titleMedium)
        Text("Turn off anything you don't want alerts about. Limits only change when an alert is shown.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        Spacer(Modifier.padding(top = 6.dp))
        AlertKind.values().forEach { kind ->
            KindToggle(kind.setting, s.on(kind), !model.busy) { on -> launch { model.setKind(kind, on) } }
        }
        Spacer(Modifier.padding(top = 8.dp))
        Stepper("Late-night use starts at", hour(s.lateFromHour), !model.busy, onLess = { launch { model.setLimits(s.lateFromHour - 1, s.lateToHour, s.usageHours, s.disconnectedHours) } }, onMore = { launch { model.setLimits(s.lateFromHour + 1, s.lateToHour, s.usageHours, s.disconnectedHours) } })
        Stepper("Late-night use ends at", hour(s.lateToHour), !model.busy, onLess = { launch { model.setLimits(s.lateFromHour, s.lateToHour - 1, s.usageHours, s.disconnectedHours) } }, onMore = { launch { model.setLimits(s.lateFromHour, s.lateToHour + 1, s.usageHours, s.disconnectedHours) } })
        Stepper("Long screen time from", "${s.usageHours} h a day", !model.busy, onLess = { launch { model.setLimits(s.lateFromHour, s.lateToHour, s.usageHours - 1, s.disconnectedHours) } }, onMore = { launch { model.setLimits(s.lateFromHour, s.lateToHour, s.usageHours + 1, s.disconnectedHours) } })
        Stepper("Phone silent for", "${s.disconnectedHours} h", !model.busy, onLess = { launch { model.setLimits(s.lateFromHour, s.lateToHour, s.usageHours, s.disconnectedHours - 1) } }, onMore = { launch { model.setLimits(s.lateFromHour, s.lateToHour, s.usageHours, s.disconnectedHours + 1) } })
    }
}

private fun hour(h: Int) = String.format(Locale.US, "%02d:00", h)

@Composable
private fun KindToggle(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Stepper(title: String, value: String, enabled: Boolean, onLess: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
        TextButton(onClick = onLess, enabled = enabled) { Text("−") }
        TextButton(onClick = onMore, enabled = enabled) { Text("+") }
    }
}
