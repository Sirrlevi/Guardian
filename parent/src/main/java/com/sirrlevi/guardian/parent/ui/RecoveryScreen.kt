package com.sirrlevi.guardian.parent.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.LocationRules
import com.sirrlevi.guardian.core.RecoveryPolicy
import com.sirrlevi.guardian.core.RecoveryTexts
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.RecoveryModel
import com.sirrlevi.guardian.parent.RecoveryRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Ask { ENABLE, RING, LOCK, DELETE }

/**
 * Find my phone for the selected child phone: where it was last (a real position, with its age and accuracy), "find now", "ring" and "lock".
 *
 * Location is shown only when the parent switched it on and the child allowed it on their phone; otherwise the screen says which of the two
 * is missing. Ring and lock are shown as done only when the phone confirms. Nothing here uses the camera or microphone.
 */
@Composable
fun RecoveryScreen(vm: ParentViewModel, padding: PaddingValues, watching: Boolean = true) {
    val device = vm.device
    val model = vm.recovery
    val controls = vm.controls
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var asking by remember { mutableStateOf<Ask?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(15) }

    LaunchedEffect(device?.id, watching) { if (device != null && watching) model.watch() }
    LaunchedEffect(device?.id, watching) { if (device != null && watching) controls.watch() }
    LaunchedEffect(watching) {
        if (watching) {
            while (true) { delay(1_000L); clock = System.currentTimeMillis() }
        }
    }
    // When a "find now" finishes there is a new position to show: fetch it now instead of at the next poll.
    val busy = controls.sending || controls.waiting != null
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy && watching) model.refreshNow()
        wasBusy = busy
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Find my phone", style = MaterialTheme.typography.headlineMedium)
                Text(if (device != null) "For ${device.name}" else "For your child's phone", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        if (device == null) {
            item { GCard { EmptyState(GIcon.Pin, "No phone connected", "Pair your child's phone from More → Settings to use Find my phone.") } }
            return@LazyColumn
        }

        item { RecoveryNote() }

        val now = maxOf(clock, model.now)

        // ------------------------------------------------------------ where the phone was
        item {
            GCard {
                RecoveryToggle(
                    "Share this phone's location",
                    when {
                        !model.loaded -> "Checking…"
                        model.sharing -> "On · about every ${model.effective.intervalMin} min, kept ${days(model.effective.retentionDays)}"
                        else -> "Off · nothing is read or sent"
                    },
                    model.sharing, enabled = model.loaded && !model.saving
                ) { on -> if (on) asking = Ask.ENABLE else scope.launch { model.setEnabled(false) } }

                if (model.sharing) {
                    val block = model.block
                    when {
                        model.state == null -> Warn(RecoveryTexts.NO_REPORT)
                        block != null -> Warn(LocationRules.text(block))
                        model.stateIsOld -> Warn("The phone hasn't reported its location settings for a while, so it may be off or offline.")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = gColors.stroke)
                val last = model.last
                if (last == null) {
                    Text("No position yet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (model.sharing) "As soon as the phone can read its position it appears here. You can also tap Find now."
                        else "Turn on location sharing to see where the phone is.",
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted
                    )
                } else {
                    LastPosition(last, now, device.name) { openInMaps(context, last, device.name, model) }
                }
                val error = model.error
                if (error != null) Warn("Couldn't reach the server: $error. What's shown may be out of date.")
                model.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (model.noticeIsProblem) gColors.warning else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        // ------------------------------------------------------------ things the parent can ask the phone to do
        item {
            val locate = controls.locateOffer
            val ring = controls.ringOffer
            val lock = controls.lockOffer
            GCard {
                Text("Ask the phone", style = MaterialTheme.typography.titleMedium)
                if (!device.isOnline || controls.statusIsOld) {
                    Text(
                        "The phone hasn't reported lately, so it may be off or offline. A request is cancelled after 2 minutes if the phone doesn't answer.",
                        style = MaterialTheme.typography.bodySmall, color = gColors.warning, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (controls.ringing) {
                    Pill("Ringing now" + (controls.status?.ringUntil?.takeIf { it > now }?.let { " · until " + Formats.timeOfDay(it) } ?: ""), gColors.success, Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Find now", { scope.launch { controls.locate() } }, Modifier.fillMaxWidth(), enabled = locate.available && !busy, icon = GIcon.Pin)
                if (!locate.available) Hint(locate.why)
                Spacer(Modifier.height(10.dp))
                SoftButton("Ring phone", { asking = Ask.RING }, Modifier.fillMaxWidth(), enabled = ring.available && !busy, icon = GIcon.Bell)
                if (!ring.available) Hint(ring.why) else Hint(RecoveryTexts.SILENT_NOTE)
                Spacer(Modifier.height(10.dp))
                SoftButton("Lock now", { asking = Ask.LOCK }, Modifier.fillMaxWidth(), enabled = lock.available && !busy, icon = GIcon.Lock)
                if (!lock.available) Hint(lock.why)

                controls.notice?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = if (controls.noticeIsProblem) gColors.warning else MaterialTheme.colorScheme.primary)
                }
                if (controls.waiting != null && !controls.restarting) {
                    Spacer(Modifier.height(6.dp))
                    Text("Waiting for the phone to answer…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
                }
                controls.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Couldn't reach the server: $it", style = MaterialTheme.typography.bodySmall, color = gColors.warning)
                }
            }
        }

        // ------------------------------------------------------------ settings
        if (model.sharing) {
            item {
                GCard {
                    Text("How location is kept", style = MaterialTheme.typography.titleMedium)
                    Text("How often the phone reads its position", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoveryPolicy.INTERVALS_MIN.forEach { m ->
                            RecoveryChip("$m min", model.effective.intervalMin == m, enabled = !model.saving) { scope.launch { model.setInterval(m) } }
                        }
                    }
                    Text("How long positions are kept", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoveryPolicy.RETENTION_DAYS.forEach { d ->
                            RecoveryChip(days(d), model.effective.retentionDays == d, enabled = !model.saving) { scope.launch { model.setRetention(d) } }
                        }
                    }
                    Text(
                        "The phone removes older positions itself, and keeps at most the newest ${RecoveryPolicy.MAX_POINTS}. A shorter interval uses more battery.",
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // ------------------------------------------------------------ history
        item {
            GCard {
                Row(Modifier.fillMaxWidth().clickable { showHistory = !showHistory }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Saved positions", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (model.history.isEmpty()) "None saved" else "${model.history.size} saved, newest first",
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted
                        )
                    }
                    Text(if (showHistory) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (showHistory) {
                    if (model.history.isEmpty()) {
                        Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted, modifier = Modifier.padding(top = 10.dp))
                    }
                    model.history.take(shown).forEach { p ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = gColors.stroke)
                        Row(Modifier.fillMaxWidth().clickable { openInMaps(context, p, device.name, model) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Formats.dateTime(p.ts), style = MaterialTheme.typography.bodyMedium)
                                Text(LocationRules.coordinates(p) + " · " + LocationRules.accuracyText(p.accuracyM), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                            }
                            if (p.provider == "mock") Pill("Mock", gColors.warning)
                            Spacer(Modifier.width(8.dp))
                            GIconView(GIcon.Chevron, gColors.muted, size = 18.dp)
                        }
                    }
                    if (model.history.size > shown) {
                        TextButton(onClick = { shown += 15 }) { Text("Show more") }
                    }
                }
                if (model.history.isNotEmpty() || model.last != null) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = gColors.stroke)
                    TextButton(onClick = { asking = Ask.DELETE }, enabled = !model.saving) { Text("Delete all saved positions", color = gColors.danger) }
                }
            }
        }

        // ------------------------------------------------------------ status
        item {
            GCard {
                Text("Recovery status", style = MaterialTheme.typography.titleMedium)
                Text("What the phone last reported, with its age.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                Spacer(Modifier.height(6.dp))
                RecoveryRows.of(device, controls.status, model.settings, model.state, model.last, now).forEach { row -> StatusLine(row) }
            }
        }

        item { SectionTitle("Not offered", subtitle = "Guardian doesn't do these, and doesn't pretend to.") }
        item {
            GCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera or microphone", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Pill("Unavailable", gColors.muted)
                }
                Text(RecoveryTexts.NO_CAMERA_MIC, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = gColors.stroke)
                Text("When the phone is off", style = MaterialTheme.typography.titleSmall)
                Text(RecoveryTexts.WHEN_OFF_NOTE, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    val ask = asking
    if (ask != null && device != null) {
        AlertDialog(
            onDismissRequest = { asking = null },
            title = {
                Text(
                    when (ask) {
                        Ask.ENABLE -> "Turn on location sharing?"
                        Ask.RING -> "Make ${device.name} ring?"
                        Ask.LOCK -> "Lock ${device.name} now?"
                        Ask.DELETE -> "Delete all saved positions?"
                    }
                )
            },
            text = {
                Text(
                    when (ask) {
                        Ask.ENABLE -> "Guardian will read the phone's position about every ${model.effective.intervalMin} minutes and keep it for ${days(model.effective.retentionDays)}. " +
                            "Your child sees this in Guardian Child, and nothing is shared until they allow location in Android's own permission dialog. You can turn it off any time."
                        Ask.RING -> "It plays the alarm sound for up to a minute. A notice on the phone says Guardian is ringing it, and whoever has the phone can stop it from there."
                        Ask.LOCK -> "The screen turns off and needs the PIN, pattern or fingerprint to open. If someone is using the phone right now, they will be interrupted."
                        Ask.DELETE -> "This removes every saved position, including the latest one. It can't be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch {
                        when (ask) {
                            Ask.ENABLE -> model.setEnabled(true)
                            Ask.RING -> controls.ring()
                            Ask.LOCK -> controls.lock()
                            Ask.DELETE -> model.clearHistory()
                        }
                    }
                }) {
                    Text(
                        when (ask) {
                            Ask.ENABLE -> "Turn on"
                            Ask.RING -> "Yes, ring it"
                            Ask.LOCK -> "Yes, lock it"
                            Ask.DELETE -> "Delete"
                        },
                        color = if (ask == Ask.DELETE) gColors.danger else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } }
        )
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

private fun openInMaps(context: Context, point: LocationPoint, name: String, model: RecoveryModel) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LocationRules.geoUri(point, name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { model.tell("There's no map app on this phone to open it in.", problem = true) }
}

@Composable
private fun LastPosition(p: LocationPoint, now: Long, name: String, onOpen: () -> Unit) {
    val old = now - p.ts > RecoveryPolicy.OLD_FIX_MS
    Text("Last position", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
    Text(Formats.relative(p.ts, now) + " · " + Formats.dateTime(p.ts), style = MaterialTheme.typography.titleMedium)
    Text(LocationRules.coordinates(p) + " · " + LocationRules.accuracyText(p.accuracyM), style = MaterialTheme.typography.bodyMedium)
    if (old) Warn("This position is old. $name may have moved since.")
    if (p.provider == "mock") Warn("Android says this position came from a \"mock location\" app, so it may not be where the phone really is.")
    Spacer(Modifier.height(10.dp))
    SoftButton("Open in Maps", onOpen, Modifier.fillMaxWidth(), icon = GIcon.Pin)
}

@Composable
private fun Warn(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = gColors.warning, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RecoveryNote() {
    GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.Top) {
            GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Real positions only", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Location is off until you turn it on and your child allows it on their phone, and they can see that it's on. " +
                        "You only ever see positions the phone really read, with their age and accuracy. Ring and lock count as done only when the phone confirms.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
        }
    }
}

@Composable
private fun RecoveryToggle(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RecoveryChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
