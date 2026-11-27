package com.sirrlevi.guardian.parent.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.ControlTexts
import com.sirrlevi.guardian.core.ControlType
import com.sirrlevi.guardian.parent.ControlLabels
import com.sirrlevi.guardian.parent.DeviceControlModel
import com.sirrlevi.guardian.parent.DeviceStatusRows
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.StatusRow
import com.sirrlevi.guardian.parent.StatusTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Device controls for the selected child phone: its real status, "Lock now", and "Restart" where Android allows it.
 *
 * A request is shown as waiting until the phone answers, and as done only when the phone says it checked the result. Unlocking and
 * shutting down are shown as unavailable, with the reason, because Android offers no way to do them from another phone.
 */
@Composable
fun DeviceControlsScreen(vm: ParentViewModel, padding: PaddingValues, watching: Boolean = true) {
    val device = vm.device
    val model = vm.controls
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf<ControlType?>(null) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    // A restart also needs this phone's own screen lock (PIN, pattern, fingerprint), so a child holding the parent's unlocked phone can't use it.
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) scope.launch { model.reboot() }
        else model.tell("Restart cancelled. Your screen lock wasn't confirmed.", problem = false)
    }

    // Erase is asked for in two steps: the word ERASE typed here, then this phone's screen lock. The typed word travels with the request.
    var eraseDialog by remember { mutableStateOf(false) }
    var eraseTyped by remember { mutableStateOf("") }
    val eraseCredential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val typed = eraseTyped
        eraseTyped = ""
        if (result.resultCode == Activity.RESULT_OK) scope.launch { model.erase(typed) }
        else model.tell("Erase cancelled. Your screen lock wasn't confirmed, so nothing was sent.", problem = false)
    }

    LaunchedEffect(device?.id, watching) { if (device != null && watching) model.watch() }
    LaunchedEffect(watching) {
        if (watching) {
            while (true) { delay(1_000L); clock = System.currentTimeMillis() }
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Device controls", style = MaterialTheme.typography.headlineMedium)
                Text(if (device != null) "For ${device.name}" else "For your child's phone", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        if (device == null) {
            item { GCard { EmptyState(GIcon.Phone, "No phone connected", "Pair your child's phone from More → Settings to use device controls.") } }
            return@LazyColumn
        }

        item { ControlsHonestyNote() }

        val now = if (model.now > 0L) maxOf(clock, model.now) else clock
        item {
            GCard {
                Text("Phone status", style = MaterialTheme.typography.titleMedium)
                Text("What the phone last reported, with its age.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                Spacer(Modifier.height(6.dp))
                DeviceStatusRows.of(device, model.status, now).forEach { row -> StatusLine(row) }
                if (model.checkedAt > 0L) {
                    Text("Checked " + Formats.relative(model.checkedAt, now), style = MaterialTheme.typography.labelSmall, color = gColors.muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item { ControlActions(model, device.isOnline, onLock = { confirm = ControlType.LOCK }, onReboot = { confirm = ControlType.REBOOT }) }

        item { EraseCard(model, onErase = { eraseTyped = ""; eraseDialog = true }) }

        item { SectionTitle("Not possible on Android", subtitle = "Guardian doesn't offer these, and doesn't pretend to.") }
        item {
            GCard {
                UnavailableLine("Unlock the phone remotely", ControlTexts.UNLOCK_NOTE)
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = gColors.stroke)
                UnavailableLine("Shut the phone down", ControlTexts.SHUTDOWN_NOTE)
            }
        }

        item {
            GCard {
                Row(Modifier.fillMaxWidth().clickable { showLog = !showLog }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recent activity", style = MaterialTheme.typography.titleSmall)
                        Text("Requests the phone handled, as it recorded them.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                    Text(if (showLog) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (showLog) {
                    if (model.log.isEmpty()) {
                        Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted, modifier = Modifier.padding(top = 10.dp))
                    }
                    model.log.take(12).forEach { e ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = gColors.stroke)
                        Text(ControlLabels.event(e), style = MaterialTheme.typography.bodyMedium)
                        Text(Formats.dateTime(e.ts), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                }
            }
        }
    }

    val asking = confirm
    if (asking != null && device != null) {
        val secure = asking == ControlType.REBOOT && credentialIntent(context, "Restart ${device.name}") != null
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (asking == ControlType.LOCK) "Lock ${device.name} now?" else "Restart ${device.name}?") },
            text = {
                Column {
                    Text(
                        if (asking == ControlType.LOCK) "The screen turns off and needs the PIN, pattern or fingerprint to open. If someone is using the phone right now, they will be interrupted."
                        else "The phone shuts down and starts again. Anything unsaved there is lost, and an ongoing call or download stops.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (asking == ControlType.REBOOT) {
                        Spacer(Modifier.height(8.dp))
                        Text(ControlTexts.AFTER_RESTART_NOTE, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (secure) "You'll be asked for this phone's screen lock to confirm it's you."
                            else "This phone has no screen lock, so Guardian can't ask for your PIN. Setting one protects restarts.",
                            style = MaterialTheme.typography.bodySmall, color = if (secure) gColors.muted else gColors.warning
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (asking == ControlType.LOCK) scope.launch { model.lock() }
                    else {
                        val intent = credentialIntent(context, "Restart ${device.name}")
                        if (intent != null) credential.launch(intent) else scope.launch { model.reboot() }
                    }
                }) { Text(if (asking == ControlType.LOCK) "Yes, lock it" else "Yes, restart", color = if (asking == ControlType.REBOOT) gColors.danger else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }

    if (eraseDialog && device != null) {
        val intent = credentialIntent(context, "Erase ${device.name}")
        val typedRight = eraseTyped == ControlTexts.ERASE_WORD
        AlertDialog(
            onDismissRequest = { eraseDialog = false; eraseTyped = "" },
            title = { Text("Erase ${device.name}?") },
            text = {
                Column {
                    Text(ControlTexts.ERASE_WARNING, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(ControlTexts.ERASE_AFTER, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = eraseTyped, onValueChange = { eraseTyped = it.take(12) }, singleLine = true,
                        label = { Text("Type ${ControlTexts.ERASE_WORD} to confirm") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (intent != null) "Next you'll be asked for this phone's screen lock to confirm it's you."
                        else "This phone has no screen lock, so Guardian can't confirm it's you. Set a PIN, pattern or password on this phone first: erasing needs it.",
                        style = MaterialTheme.typography.bodySmall, color = if (intent != null) gColors.muted else gColors.warning
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typedRight && intent != null,
                    onClick = {
                        eraseDialog = false
                        if (intent != null) eraseCredential.launch(intent)
                    }
                ) { Text("Continue", color = if (typedRight && intent != null) gColors.danger else gColors.muted) }
            },
            dismissButton = { TextButton(onClick = { eraseDialog = false; eraseTyped = "" }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EraseCard(model: DeviceControlModel, onErase: () -> Unit) {
    val offer = model.eraseOffer
    val busy = model.sending || model.waiting != null
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Erase phone", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!offer.available) Pill("Unavailable", gColors.muted)
        }
        Text(
            "Factory reset. For a lost or stolen phone only. It can't be undone, and the phone can't confirm when it's finished.",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp)
        )
        if (!offer.available) {
            Text(offer.why, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 6.dp))
        } else {
            Spacer(Modifier.height(10.dp))
            SoftButton("Erase phone…", onErase, Modifier.fillMaxWidth(), enabled = !busy, color = gColors.danger, icon = GIcon.Trash)
            Text(
                "You'll type ${ControlTexts.ERASE_WORD} and confirm with this phone's screen lock. The request lasts one minute.",
                style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun credentialIntent(context: Context, title: String): Intent? = try {
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (keyguard.isDeviceSecure) keyguard.createConfirmDeviceCredentialIntent(title, "Confirm it's you with your screen lock.") else null
} catch (_: Exception) { null }

@Composable
private fun ControlsHonestyNote() {
    GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.Top) {
            GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Done only when the phone confirms", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A request is sent to your child's phone, which checks what really happened and answers. Until then it shows as waiting. " +
                        "Requests last 2 minutes; if the phone is off or offline they are cancelled, never queued for later. Your child's phone keeps a record of every request.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
        }
    }
}

@Composable
internal fun StatusLine(row: StatusRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(row.label, Modifier.width(132.dp), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        val tint: Color = when (row.tone) {
            StatusTone.GOOD -> gColors.success
            StatusTone.WARN -> gColors.warning
            StatusTone.PLAIN -> MaterialTheme.colorScheme.onSurface
        }
        Text(row.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun UnavailableLine(title: String, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Pill("Unavailable", gColors.muted)
    }
    Text(note, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun ControlActions(model: DeviceControlModel, online: Boolean, onLock: () -> Unit, onReboot: () -> Unit) {
    val busy = model.sending || model.waiting != null
    val lock = model.lockOffer
    val reboot = model.rebootOffer
    GCard {
        Text("Actions", style = MaterialTheme.typography.titleMedium)
        if (!online || model.statusIsOld) {
            Text(
                "The phone hasn't reported lately, so it may be off or offline. A request is cancelled after 2 minutes if the phone doesn't answer.",
                style = MaterialTheme.typography.bodySmall, color = gColors.warning, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Lock now", onLock, Modifier.fillMaxWidth(), enabled = lock.available && !busy, icon = GIcon.Lock)
        if (!lock.available) Text(lock.why, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        SoftButton("Restart phone", onReboot, Modifier.fillMaxWidth(), enabled = reboot.available && !busy, color = gColors.danger, icon = GIcon.Refresh)
        if (!reboot.available) Text(reboot.why, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))

        val notice = model.notice
        val waiting = model.waiting
        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(notice, style = MaterialTheme.typography.bodyMedium, color = if (model.noticeIsProblem) gColors.warning else MaterialTheme.colorScheme.primary)
        }
        if (waiting != null && !model.restarting) {
            Spacer(Modifier.height(6.dp))
            Text("Waiting for the phone to answer…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
        }
        val error = model.error
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text("Couldn't reach the server: $error", style = MaterialTheme.typography.bodySmall, color = gColors.warning)
        }
    }
}
