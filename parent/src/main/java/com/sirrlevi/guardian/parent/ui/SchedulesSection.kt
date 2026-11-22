package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.Schedule
import com.sirrlevi.guardian.parent.ParentViewModel

private val DayShort = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private fun minuteLabel(min: Int): String {
    val h = min / 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return h12.toString() + ":" + (min % 60).toString().padStart(2, '0') + (if (h < 12) " AM" else " PM")
}

private fun daysLabel(days: List<Int>): String = when {
    days.toSet() == (1..7).toSet() -> "Every day"
    days.toSet() == setOf(2, 3, 4, 5, 6) -> "Weekdays"
    days.toSet() == setOf(1, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { DayShort[it - 1] }
}

/** Recurring quiet times: during a schedule only the apps you allow can be opened. */
@Composable
fun SchedulesSection(vm: ParentViewModel) {
    val schedules = vm.policy.schedules
    var editing by remember { mutableStateOf<Schedule?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ApplyBanner(vm)
        GCard {
            Text("Quiet times", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("During a schedule only the apps you allow can be opened. The phone app, home screen and emergency calls always work.",
                style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("School hours", { editing = Schedule("s" + System.currentTimeMillis(), "School hours", listOf(2, 3, 4, 5, 6), 8 * 60, 14 * 60) }, Modifier.weight(1f))
                SoftButton("Bedtime", { editing = Schedule("s" + System.currentTimeMillis(), "Bedtime", (1..7).toList(), 21 * 60, 7 * 60) }, Modifier.weight(1f))
                SoftButton("Custom", { editing = Schedule("s" + System.currentTimeMillis(), "", (1..7).toList(), 20 * 60, 22 * 60) }, Modifier.weight(1f))
            }
        }
        if (schedules.isEmpty()) {
            EmptyState(GIcon.Moon, "No schedules yet", "Pick School hours or Bedtime above to create one in a few taps.")
        }
        schedules.forEach { s ->
            GCard(onClick = { editing = s }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        GIconView(GIcon.Moon, MaterialTheme.colorScheme.secondary, size = 22.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name.ifBlank { "Schedule" }, style = MaterialTheme.typography.titleSmall)
                        Text(minuteLabel(s.startMin) + " – " + minuteLabel(s.endMin) + " · " + daysLabel(s.days),
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                        Text(if (s.allowedPackages.isEmpty()) "No apps allowed" else "${s.allowedPackages.size} apps allowed",
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                    Switch(checked = s.enabled, onCheckedChange = { on ->
                        vm.saveSchedules(schedules.map { if (it.id == s.id) it.copy(enabled = on) else it })
                    })
                }
            }
        }
    }

    editing?.let { current ->
        ScheduleEditor(vm, current, isNew = schedules.none { it.id == current.id }, onDismiss = { editing = null })
    }
}

@Composable
private fun ScheduleEditor(vm: ParentViewModel, initial: Schedule, isNew: Boolean, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial.name) }
    var days by remember { mutableStateOf(initial.days.toSet()) }
    var start by remember { mutableStateOf(initial.startMin) }
    var end by remember { mutableStateOf(initial.endMin) }
    var allowed by remember { mutableStateOf(initial.allowedPackages.toSet()) }
    var picking by remember { mutableStateOf(0) } // 0 none, 1 start, 2 end
    val apps = vm.device?.apps.orEmpty().filter { !it.system }.sortedBy { it.name.lowercase() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(if (isNew) "New schedule" else "Edit schedule", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(14.dp))
            Text("Days", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (d in 1..7) {
                    val on = d in days
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { days = if (on) days - d else days + d },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(DayShort[d - 1].take(1), fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeButton("From", minuteLabel(start), Modifier.weight(1f)) { picking = 1 }
                TimeButton("Until", minuteLabel(end), Modifier.weight(1f)) { picking = 2 }
            }
            if (end <= start) Text("Runs past midnight into the next morning.", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            Text("Apps that stay allowed", style = MaterialTheme.typography.titleSmall)
            Text("Everything else is blocked during this time.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            Spacer(Modifier.height(6.dp))
            if (apps.isEmpty()) Text("The child's app list isn't available yet.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            apps.forEach { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { allowed = if (app.pkg in allowed) allowed - app.pkg else allowed + app.pkg }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIconView(app.pkg, app.name, vm.icons, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(app.name, Modifier.weight(1f), maxLines = 1)
                    Checkbox(checked = app.pkg in allowed, onCheckedChange = null)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isNew) SoftButton("Delete", {
                    vm.saveSchedules(vm.policy.schedules.filter { it.id != initial.id }); onDismiss()
                }, Modifier.weight(1f), color = gColors.danger)
                PrimaryButton("Save", {
                    val saved = initial.copy(name = name.trim().ifBlank { "Schedule" }, days = days.sorted(), startMin = start, endMin = end, allowedPackages = allowed.toList())
                    val list = if (isNew) vm.policy.schedules + saved else vm.policy.schedules.map { if (it.id == initial.id) saved else it }
                    vm.saveSchedules(list)
                    onDismiss()
                }, Modifier.weight(1.4f), enabled = days.isNotEmpty())
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking != 0) {
        val current = if (picking == 1) start else end
        val state = rememberTimePickerState(initialHour = current / 60, initialMinute = current % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { picking = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = state.hour * 60 + state.minute
                    if (picking == 1) start = minutes else end = minutes
                    picking = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }
}

@Composable
private fun TimeButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = gColors.muted)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
