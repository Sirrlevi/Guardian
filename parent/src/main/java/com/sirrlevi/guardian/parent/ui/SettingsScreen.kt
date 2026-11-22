package com.sirrlevi.guardian.parent.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirrlevi.guardian.parent.BuildConfig
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.ThemeMode

@Composable
fun SettingsScreen(vm: ParentViewModel, padding: PaddingValues) {
    val device = vm.device
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text("Phones, permissions, look and updates", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        item { PairingCard(vm) }
        item { DevicesCard(vm) }
        if (device != null) item { PhoneHealthCard(device) }
        item { AppearanceCard(vm) }
        item { UpdatesCard(vm) }
        item { AccountCard(vm) }
        item { AboutCard() }
    }
}

// ------------------------------------------------------------------ pairing

@Composable
private fun PairingCard(vm: ParentViewModel) {
    val now = rememberNow()
    val clipboard = LocalClipboardManager.current
    val remaining = vm.pairingExpiresAt - now
    val active = vm.pairingCode.isNotBlank() && remaining > 0
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(GIcon.Link, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Connect a child's phone", style = MaterialTheme.typography.titleSmall)
                Text("Install Guardian Child on their phone, create its account, then type this code there.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (active) {
            val code = vm.pairingCode
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("Expires in " + Formats.countdown(remaining), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Copy code", { clipboard.setText(AnnotatedString(code)) }, Modifier.weight(1f))
                SoftButton("New code", { vm.generatePairingCode() }, Modifier.weight(1f))
            }
        } else {
            PrimaryButton(if (vm.pairingCode.isBlank()) "Get a pairing code" else "Get a new code", { vm.generatePairingCode() }, Modifier.fillMaxWidth())
            if (vm.pairingCode.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("The last code expired. Codes work once and last a few minutes.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        }
    }
}

@Composable
private fun IconBubble(icon: GIcon, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        GIconView(icon, tint, size = 22.dp)
    }
}

// ------------------------------------------------------------------ devices

@Composable
private fun DevicesCard(vm: ParentViewModel) {
    val now = rememberNow()
    GCard(padding = 4.dp) {
        Text("Paired phones", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp))
        if (vm.devices.isEmpty()) {
            Text("No phone paired yet.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp))
        }
        vm.devices.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = gColors.stroke)
            val selected = d.id == vm.device?.id
            Row(
                Modifier.fillMaxWidth().clickable { vm.selectDevice(d.id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBubble(GIcon.Phone, if (d.isOnline) gColors.success else gColors.muted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text((if (d.isOnline) "Online" else "Last seen " + Formats.relative(d.presenceTs(), now)) +
                        (d.info?.let { " · Android " + it.androidVersion } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                }
                if (selected) Pill("Selected", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ------------------------------------------------------------------ phone health

@Composable
private fun PhoneHealthCard(d: ChildDevice) {
    val info = d.info
    GCard {
        Text("Phone health · " + d.name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (info == null) {
            Text("This phone hasn't reported its status yet. Update Guardian Child on it (Settings → App Updates), open the app once and keep it connected to the internet.",
                style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            return@GCard
        }
        Text("Reported " + Formats.relative(info.ts), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        Spacer(Modifier.height(10.dp))
        HealthRow("Usage access", "Needed to see and limit apps", info.permUsage, required = true)
        HealthRow("Display over other apps", "Shows the \"time's up\" screen", info.permOverlay || info.permOwner, required = true)
        HealthRow("Notifications", "Keeps tracking alive and shows status", info.permNotifications, required = true)
        HealthRow("Unrestricted battery", "Stops Android from pausing Guardian", info.permBattery, required = true)
        HealthRow("Automatic date & time", "Prevents changing the clock to cheat limits", info.autoTime, required = true)
        HealthRow("Uninstall protection", "Optional – makes removal harder", info.permAdmin, required = false)
        HealthRow("Website filter", "Optional – filters browsing on the phone", info.permVpn, required = false)
        HealthRow("Hard blocking (Device Owner)", "Optional – needs a one-time ADB setup", info.permOwner, required = false)
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = gColors.stroke)
        Spacer(Modifier.height(10.dp))
        Fact("Device", listOf(info.manufacturer, info.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { d.name })
        if (info.battery >= 0) Fact("Battery", info.battery.toString() + "%" + if (info.charging) " · charging" else "")
        Fact("Network", when (info.network) { "wifi" -> "Wi-Fi"; "cellular" -> "Mobile data"; "offline" -> "Offline"; else -> info.network })
        if (info.storageTotalMb > 0) Fact("Storage", Formats.mb(info.storageFreeMb) + " free of " + Formats.mb(info.storageTotalMb))
        if (info.ramTotalMb > 0) Fact("Memory", Formats.mb(info.ramFreeMb) + " free of " + Formats.mb(info.ramTotalMb))
        Fact("Enforcement", when (info.enforcement) { "device-owner" -> "Device Owner (hard block)"; "overlay" -> "Blocked-screen overlay"; "limited" -> "Limited – a permission is missing"; else -> info.enforcement })
        Fact("Guardian Child", "v" + info.appVersion)
        Spacer(Modifier.height(6.dp))
        Text("Permissions can only be turned on from the child's own phone — Android doesn't allow granting them remotely.",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted)
    }
}

@Composable
private fun HealthRow(title: String, note: String, ok: Boolean, required: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        when {
            ok -> Pill("On", gColors.success)
            required -> Pill("Missing", gColors.danger)
            else -> Pill("Off", gColors.muted)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------ appearance

@Composable
private fun AppearanceCard(vm: ParentViewModel) {
    val modes = ThemeMode.values().toList()
    GCard {
        Text("Appearance", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Segmented(listOf("Auto", "Dark", "Light"), modes.indexOf(vm.themeMode), { vm.setTheme(modes[it]) })
    }
}

// ------------------------------------------------------------------ updates

@Composable
private fun UpdatesCard(vm: ParentViewModel) {
    val result = vm.updateResult
    val release = result?.release
    var token by remember { mutableStateOf("") }
    var changing by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(GIcon.Download, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("App updates", style = MaterialTheme.typography.titleSmall)
                Text("Guardian Parent", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
            when {
                result?.updateAvailable == true -> Pill(if (result.needsReinstall) "Reinstall once" else "Update ready", if (result.needsReinstall) gColors.warning else gColors.success, filled = false)
                result != null && release == null -> Pill("No release yet", gColors.warning)
                result != null -> Pill("Up to date", gColors.success)
            }
        }
        Spacer(Modifier.height(12.dp))
        Fact("Installed", "v" + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")")
        Fact("Latest", if (release != null) release.tagName.removePrefix("guardian-") + " (build " + release.versionCode + ")" else "Not checked yet")
        Fact("Last checked", Formats.dateTime(vm.lastUpdateCheck))
        Spacer(Modifier.height(8.dp))
        Text(vm.updateStatus, style = MaterialTheme.typography.bodyMedium)
        if (vm.updateProgress >= 0) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(vm.updateProgress / 100f, MaterialTheme.colorScheme.primary)
        }
        if (result?.updateAvailable == true && release != null && release.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("What's new", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
            Text(release.notes.trim().take(400), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        val reinstallUrl = vm.reinstallUrl
        if (reinstallUrl != null) {
            PrimaryButton("Download APK in browser", { uriHandler.openUri(reinstallUrl) }, Modifier.fillMaxWidth(), enabled = !vm.updateBusy)
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(if (vm.updateBusy && vm.updateProgress < 0) "Checking…" else "Check for updates", { vm.checkUpdates(true) },
                Modifier.weight(1f), enabled = !vm.updateBusy)
            if (result?.updateAvailable == true && reinstallUrl == null) {
                PrimaryButton("Download & install", { vm.downloadUpdate() }, Modifier.weight(1.2f), enabled = !vm.updateBusy)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Guardian checks in the background and shows a notification when a new version is ready. No login or token is needed. Android's installer always asks you to confirm, and your data stays.",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        if (changing) {
            Spacer(Modifier.height(12.dp))
            Text("GitHub token (only for a private repository)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Use a fine-grained token for Sirrlevi/Guardian with read-only access to Contents. It is encrypted with the Android Keystore and stored only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true,
                placeholder = { Text("github_pat_…") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Save token & check", {
                vm.saveToken(token); token = ""; changing = false
            }, Modifier.fillMaxWidth(), enabled = token.trim().length >= 20)
        } else {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { changing = true }) { Text(if (vm.hasToken) "Replace token" else "Private repository? Add a token") }
        }
    }
}

// ------------------------------------------------------------------ account

@Composable
private fun AccountCard(vm: ParentViewModel) {
    var confirm by remember { mutableStateOf(false) }
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(GIcon.Person, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Account", style = MaterialTheme.typography.titleSmall)
                Text(vm.email.ifBlank { "Signed in" }, style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        SoftButton("Sign out", { confirm = true }, Modifier.fillMaxWidth(), color = gColors.danger)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out?") },
            text = { Text("Your child's phone keeps enforcing the rules you already set. You'll need to sign in again to change them.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.signOut() }) { Text("Sign out", color = gColors.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AboutCard() {
    GCard {
        Text("About Guardian", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Guardian is open about what it does. It tracks app usage, enforces the limits you set and shows a visible notification on the child's phone. " +
                "It never records the screen, microphone or camera, and it can't read other browsers' private history.",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted
        )
    }
}
