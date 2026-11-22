package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.AppCategories
import com.sirrlevi.guardian.core.AppRule
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.AppInfo
import com.sirrlevi.guardian.parent.ParentViewModel

private val DayLetters = listOf("S", "M", "T", "W", "T", "F", "S")
private val DayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/** Tap an app → block it or set a daily limit in hours / minutes / seconds, with a dial, a slider or typed numbers. */
@Composable
fun AppControlSheet(vm: ParentViewModel, app: AppInfo, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val device = vm.device
    val rule = vm.ruleFor(app.pkg)
    val clock = remember { DayClock.of(System.currentTimeMillis()) }

    var blocked by remember(app.pkg) { mutableStateOf(rule.blocked) }
    var mode by remember(app.pkg) { mutableIntStateOf(when { rule.daySec.isNotEmpty() -> 2; rule.dailyLimitSec > 0 -> 1; else -> 0 }) }
    var dailySec by remember(app.pkg) { mutableIntStateOf(if (rule.dailyLimitSec > 0) rule.dailyLimitSec else 3600) }
    var days by remember(app.pkg) {
        mutableStateOf((1..7).associateWith { rule.daySec[it] ?: (if (rule.dailyLimitSec > 0) rule.dailyLimitSec else 3600) })
    }
    var dow by remember(app.pkg) { mutableIntStateOf(clock.dayOfWeek) }

    val edited = rule.copy(
        blocked = blocked,
        dailyLimitSec = if (mode == 1) dailySec else 0,
        daySec = if (mode == 2) days else emptyMap()
    )
    val changed = edited != rule

    val liveExtra = device?.live?.takeIf { it.pkg == app.pkg && !it.blocked }?.let {
        ((System.currentTimeMillis() - it.ts) / 1000L).toInt().coerceIn(0, 60)
    } ?: 0
    val usedToday = (device?.todaySec?.get(app.pkg) ?: 0) + liveExtra
    val limitToday = edited.limitFor(clock.dayOfWeek, clock.dayKey)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp).width(44.dp).height(5.dp).clip(CircleShape).background(gColors.stroke))
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            // ---- header
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconView(app.pkg, app.name, vm.icons, size = 60.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(AppCategories.label(app.category), MaterialTheme.colorScheme.secondary)
                        if (app.installedAt > 0L) Pill("Since " + Formats.shortDate(app.installedAt), gColors.muted)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---- today
            GCard(container = MaterialTheme.colorScheme.surfaceVariant, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Used today", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
                        Text(if (usedToday == 0) "Not used yet" else Durations.compact(usedToday), style = MaterialTheme.typography.titleLarge)
                    }
                    if (limitToday != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Today's limit", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
                            Text(Durations.compact(limitToday), style = MaterialTheme.typography.titleLarge, color = gColors.warning)
                        }
                    }
                }
                if (limitToday != null && limitToday > 0) {
                    Spacer(Modifier.height(10.dp))
                    val over = usedToday >= limitToday
                    ProgressBar(usedToday.toFloat() / limitToday, if (over) gColors.danger else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (over) "Limit reached for today" else Durations.compact(limitToday - usedToday) + " left today",
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            ApplyBanner(vm)
            Spacer(Modifier.height(14.dp))

            // ---- block switch
            GCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(gColors.danger.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        GIconView(GIcon.Block, gColors.danger, size = 22.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Block this app", style = MaterialTheme.typography.titleSmall)
                        Text(if (blocked) "It can't be opened until you turn this off." else "Your child can open it normally.",
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                    Switch(
                        checked = blocked, onCheckedChange = { blocked = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = gColors.danger, checkedThumbColor = MaterialTheme.colorScheme.background)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---- daily limit
            GCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(gColors.warning.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        GIconView(GIcon.Timer, gColors.warning, size = 22.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Daily time limit", style = MaterialTheme.typography.titleSmall)
                        Text("Counts only the time the app is open on screen.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Segmented(listOf("No limit", "Every day", "Custom days"), mode, onSelect = { new ->
                    if (new == 2 && mode != 2) days = (1..7).associateWith { if (mode == 1) dailySec else 3600 }
                    mode = new
                })
                if (mode == 1) {
                    Spacer(Modifier.height(16.dp))
                    LimitEditor(dailySec, { dailySec = it })
                }
                if (mode == 2) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (d in 1..7) {
                            val on = d == dow
                            val unlimited = (days[d] ?: 0) >= AppRule.UNLIMITED_SEC
                            Column(
                                Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { dow = d }.padding(horizontal = 9.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(DayLetters[d - 1], fontWeight = FontWeight.Bold,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                Text(if (unlimited) "∞" else shortHm(days[d] ?: 0), style = MaterialTheme.typography.labelSmall,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary else gColors.muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(DayNames[dow - 1], style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("No limit on this day", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
                        Switch(
                            checked = (days[dow] ?: 0) >= AppRule.UNLIMITED_SEC,
                            onCheckedChange = { on -> days = days + (dow to if (on) AppRule.UNLIMITED_SEC else 3600) }
                        )
                    }
                    if ((days[dow] ?: 0) < AppRule.UNLIMITED_SEC) {
                        Spacer(Modifier.height(8.dp))
                        LimitEditor(days[dow] ?: 0, { v -> days = days + (dow to v) })
                        Spacer(Modifier.height(8.dp))
                        SoftButton("Use this for every day", { val v = days[dow] ?: 0; days = (1..7).associateWith { v } }, Modifier.fillMaxWidth())
                    }
                }
            }

            // ---- extra time
            if (rule.dailyLimitSec > 0 || rule.daySec.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                GCard(padding = 14.dp) {
                    Text("Give extra time today", style = MaterialTheme.typography.titleSmall)
                    Text("One-off. Tomorrow the normal limit applies again.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "+15 min", 30 to "+30 min", 60 to "+1 hour").forEach { (min, label) ->
                            SoftButton(label, { vm.grantBonus(app.pkg, min * 60) }, Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Cancel", onDismiss, Modifier.weight(1f), color = gColors.muted)
                PrimaryButton(if (changed) "Save changes" else "No changes", {
                    vm.saveRule(app.pkg, edited, if (edited.blocked && !rule.blocked) "${app.name} blocked. Waiting for the child's phone to confirm." else "Saved. Waiting for the child's phone to confirm.")
                    onDismiss()
                }, Modifier.weight(1.6f), enabled = changed)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun shortHm(sec: Int): String {
    val (h, m, _) = Durations.split(sec)
    return when { h > 0 && m > 0 -> "${h}h${m}"; h > 0 -> "${h}h"; m > 0 -> "${m}m"; else -> "0" }
}

/** Dial + quick chips + slider + typed hours / minutes / seconds, all editing the same value. */
@Composable
fun LimitEditor(valueSec: Int, onChange: (Int) -> Unit) {
    val (h, m, s) = Durations.split(valueSec)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TimeDial(valueSec, onChange, diameter = 216.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (valueSec >= AppRule.UNLIMITED_SEC) "24h" else Durations.compact(valueSec), style = MaterialTheme.typography.headlineMedium)
                Text("per day", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 * 60 to "15m", 30 * 60 to "30m", 60 * 60 to "1h", 90 * 60 to "1h 30m", 2 * 3600 to "2h", 3 * 3600 to "3h", 4 * 3600 to "4h").forEach { (sec, label) ->
                val on = valueSec == sec
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onChange(sec) }.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Fine-tune (up to 12 hours)", style = MaterialTheme.typography.labelMedium, color = gColors.muted, modifier = Modifier.fillMaxWidth())
        Slider(
            value = valueSec.coerceAtMost(12 * 3600).toFloat(),
            onValueChange = { onChange(Durations.snap(it.toInt(), 300)) },
            valueRange = 0f..(12 * 3600f),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.height(6.dp))
        Text("Or type the exact time", style = MaterialTheme.typography.labelMedium, color = gColors.muted, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberBox("hours", h, Modifier.weight(1f)) { onChange(Durations.join(it, m, s)) }
            NumberBox("min", m, Modifier.weight(1f)) { onChange(Durations.join(h, it, s)) }
            NumberBox("sec", s, Modifier.weight(1f)) { onChange(Durations.join(h, m, it)) }
        }
    }
}

@Composable
private fun NumberBox(label: String, value: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if ((text.toIntOrNull() ?: 0) != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            text = digits
            onValue(digits.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    )
}
