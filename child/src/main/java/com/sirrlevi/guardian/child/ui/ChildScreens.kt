package com.sirrlevi.guardian.child.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirrlevi.guardian.child.BuildConfig
import com.sirrlevi.guardian.child.ChildViewModel
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.core.Enforcement
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateTime(ms: Long): String = if (ms <= 0L) "not yet" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
private fun clockTime(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
private fun minuteLabel(min: Int): String {
    val h = (min / 60) % 24
    val h12 = if (h % 12 == 0) 12 else h % 12
    return h12.toString() + ":" + (min % 60).toString().padStart(2, '0') + if (h < 12) " AM" else " PM"
}

// ------------------------------------------------------------------ pairing

@Composable
fun PairScreen(vm: ChildViewModel) {
    var mode by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val canSubmit = !vm.busy && email.contains("@") && password.length >= 6 && code.length == 8

    Box(Modifier.fillMaxSize().background(kc.bg).systemBarsPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            KEmoji("🛡️", size = 84.dp)
            Spacer(Modifier.height(16.dp))
            Text("Link this phone", style = MaterialTheme.typography.headlineLarge, color = kc.text)
            Spacer(Modifier.height(4.dp))
            Text("Guardian helps your family balance screen time. Everything it does is visible to you.",
                style = MaterialTheme.typography.bodyMedium, color = kc.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))

            KCard {
                Text("1  Ask your parent for a code", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Text("They open Guardian Parent → Settings → Connect a child's phone.", style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(14.dp))
                Text("2  Make your own sign-in", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Spacer(Modifier.height(8.dp))
                KSegmented(listOf("New account", "I have one"), mode, { mode = it; vm.authError = null })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true, label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true, label = { Text("Password (6+ characters)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text("3  Type the 8-digit code", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) }, singleLine = true,
                    placeholder = { Text("00000000", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                )
                val error = vm.authError
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = kc.coral)
                }
                Spacer(Modifier.height(16.dp))
                if (vm.busy) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp) }
                } else {
                    KButton("Link this phone", { vm.pair(mode == 0, email, password, code) }, Modifier.fillMaxWidth(), enabled = canSubmit)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Guardian shows a notification while it runs, and Android always asks before it gets any permission. It never records the screen, microphone or camera.",
                style = MaterialTheme.typography.bodySmall, color = kc.muted, textAlign = TextAlign.Center
            )
        }
    }
}

// ------------------------------------------------------------------ today

@Composable
fun TodayScreen(vm: ChildViewModel, padding: PaddingValues, goTab: (ChildTab) -> Unit) {
    val clock = DayClock.of(System.currentTimeMillis())
    val now = System.currentTimeMillis()
    val used = vm.todayApps.toMap()
    val rules = vm.policy.appRules
    val blocked = rules.filter { it.value.blocked }.keys.sorted()
    val limited = rules.entries.filter { !it.value.blocked && it.value.limitFor(clock.dayOfWeek, clock.dayKey) != null }
        .sortedBy { it.key }
    val schedules = vm.policy.schedules.filter { it.enabled && Enforcement.isActive(it, clock) }
    val paused = vm.policy.pauseUntil > now

    ScreenColumn(padding) {
        item { Heading("Hi there 👋", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())) }

        if (paused) item {
            KCard(container = kc.purple.copy(alpha = 0.12f)) {
                Text("⏸  Apps are paused", style = MaterialTheme.typography.titleMedium, color = kc.purple)
                Text("Your parent paused apps until " + clockTime(vm.policy.pauseUntil) + ". Calls and Guardian still work.",
                    style = MaterialTheme.typography.bodyMedium, color = kc.text)
            }
        }
        if (schedules.isNotEmpty()) item {
            KCard(container = kc.sun.copy(alpha = 0.14f)) {
                val s = schedules.first()
                Text("🌙  " + s.name, style = MaterialTheme.typography.titleMedium, color = kc.text)
                Text("Quiet time until " + minuteLabel(s.endMin) + ". Only " +
                    (if (s.allowedPackages.isEmpty()) "essential apps" else s.allowedPackages.size.toString() + " allowed app" + (if (s.allowedPackages.size == 1) "" else "s")) +
                    " can be opened.", style = MaterialTheme.typography.bodyMedium, color = kc.text)
            }
        }

        item { ScreenTimeCard(vm) }

        if (blocked.isNotEmpty() || limited.isNotEmpty()) {
            item { KSection("Your rules", subtitle = "Set by your parent. They change here as soon as your parent updates them.") }
            if (limited.isNotEmpty()) item {
                KCard(padding = 16.dp) {
                    limited.forEachIndexed { i, (pkg, rule) ->
                        val limit = rule.limitFor(clock.dayOfWeek, clock.dayKey) ?: return@forEachIndexed
                        val u = used[pkg] ?: 0
                        val left = (limit - u).coerceAtLeast(0)
                        val frac = if (limit > 0) u.toFloat() / limit else 1f
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KAppIcon(pkg, 42.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(rememberAppLabel(pkg), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = kc.text, maxLines = 1)
                                    Text(if (left == 0) "Time's up" else Durations.compact(left) + " left",
                                        style = MaterialTheme.typography.labelLarge, color = if (left == 0) kc.coral else kc.muted)
                                }
                                Spacer(Modifier.height(6.dp))
                                KProgress(frac, if (frac < 0.7f) kc.primary else if (frac < 0.9f) kc.sun else kc.coral)
                                val adjust = rule.extraFor(clock.dayKey)
                                Text("Used " + Durations.compact(u) + " of " + Durations.compact(limit) + " today" +
                                    (if (adjust > 0) " (" + Durations.signed(adjust) + " extra from your parent)" else if (adjust < 0) " (" + Durations.signed(adjust) + " today)" else ""),
                                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                            }
                        }
                    }
                }
            }
            if (blocked.isNotEmpty()) item {
                KCard(padding = 16.dp) {
                    Text("Not available right now", style = MaterialTheme.typography.titleSmall, color = kc.text)
                    Spacer(Modifier.height(10.dp))
                    blocked.forEachIndexed { i, pkg ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KAppIcon(pkg, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(rememberAppLabel(pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = kc.text, maxLines = 1)
                            KPill("Blocked", kc.coral)
                        }
                    }
                }
            }
        }

        val top = vm.todayApps.filter { it.second >= 30 }.take(6)
        if (top.isNotEmpty()) {
            item { KSection("Most used today") }
            item {
                KCard(padding = 16.dp) {
                    top.forEachIndexed { i, (pkg, sec) ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KAppIcon(pkg, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(rememberAppLabel(pkg), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = kc.text, maxLines = 1)
                                    Text(Durations.compact(sec), style = MaterialTheme.typography.labelLarge, color = kc.muted)
                                }
                                Spacer(Modifier.height(5.dp))
                                KProgress(sec.toFloat() / top[0].second, kc.chart[i % kc.chart.size], height = 7.dp)
                            }
                        }
                    }
                }
            }
        }

        item {
            KCard(container = kc.primarySoft) {
                Text("🔍  What your parent can see", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Spacer(Modifier.height(4.dp))
                Text("Always: which apps you use and for how long, when the screen turns on or off or the phone is unlocked, the apps installed on this phone, and pages opened in Guardian Browser.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(6.dp))
                Text("Only if you turn on Message previews (Protection tab): the sender and preview text of chat notifications, as Android shows them. Not full chats.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(6.dp))
                Text("Only if your parent turns on photo backup and you allow photo access: smaller preview copies of new photos and one picture from each new video.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(6.dp))
                Text("Guardian never uses your camera or microphone. Every permission it asks for is listed in the Protection tab.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
            }
        }
    }
}

@Composable
private fun ScreenTimeCard(vm: ChildViewModel) {
    val top = vm.todayApps.filter { it.second > 0 }
    val palette = kc.chart
    val parts = ArrayList<Pair<Float, androidx.compose.ui.graphics.Color>>()
    top.take(5).forEachIndexed { i, (_, sec) -> parts.add(sec.toFloat() to palette[i % palette.size]) }
    val rest = top.drop(5).sumOf { it.second }
    if (rest > 0) parts.add(rest.toFloat() to kc.muted)

    KCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KRing(parts, diameter = 150.dp, thickness = 16.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (vm.todayTotalSec <= 0) "0m" else Durations.compact(vm.todayTotalSec), style = MaterialTheme.typography.headlineMedium, color = kc.text)
                    Text("today", style = MaterialTheme.typography.bodySmall, color = kc.muted)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Screen time", style = MaterialTheme.typography.titleMedium, color = kc.text)
                if (!vm.todayLoaded) Text("Loading…", style = MaterialTheme.typography.bodySmall, color = kc.muted)
                top.take(4).forEachIndexed { i, (pkg, sec) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(palette[i % palette.size], RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text(rememberAppLabel(pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = kc.text, maxLines = 1)
                        Text(Durations.compact(sec), style = MaterialTheme.typography.labelMedium, color = kc.muted)
                    }
                }
                if (vm.todayLoaded && top.isEmpty()) Text("Nothing used yet today.", style = MaterialTheme.typography.bodySmall, color = kc.muted)
            }
        }
        if (vm.todayLoaded) {
            Spacer(Modifier.height(10.dp))
            Text(
                (if (vm.todayFromPhone) "Counted on this phone (offline) · " else "Same numbers your parent sees · ") + "updated " + clockTime(vm.todayUpdatedAt),
                style = MaterialTheme.typography.labelSmall, color = kc.muted
            )
        }
    }
}

// ------------------------------------------------------------------ protection

private fun emojiFor(id: String): String = when (id) {
    "usage" -> "📊"; "overlay" -> "🪟"; "battery" -> "🔋"; "notifications" -> "🔔"; "admin" -> "🔒"; "vpn" -> "🌐"; "notifaccess" -> "💬"; "media" -> "🖼️"; else -> "✅"
}

@Composable
fun ProtectionScreen(vm: ChildViewModel, padding: PaddingValues, actions: ChildActions, capture: CaptureState = CaptureState()) {
    val items = vm.permissions
    val required = items.filter { it.required }
    val requiredDone = required.count { it.granted }
    val done = items.count { it.granted }
    val ready = requiredDone == required.size && vm.monitorRunning
    var showOwnerInfo by remember { mutableStateOf(false) }

    ScreenColumn(padding) {
        item { Heading("Protection", "What Guardian can do on this phone, and what still needs your OK.") }
        item {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KRing(listOf(done.toFloat() to (if (ready) kc.good else kc.primary)), diameter = 104.dp, thickness = 12.dp, maxValue = items.size.coerceAtLeast(1).toFloat()) {
                        Text("$done/${items.size}", style = MaterialTheme.typography.titleLarge, color = kc.text)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                ready -> "Guardian is on"
                                requiredDone == required.size -> "Starting up…"
                                else -> "A few steps left"
                            }, style = MaterialTheme.typography.titleLarge, color = kc.text
                        )
                        Text(
                            if (ready) "Your parent's rules apply within about a second."
                            else "Required steps done: $requiredDone of ${required.size}. Without them limits and blocking can't work.",
                            style = MaterialTheme.typography.bodySmall, color = kc.muted
                        )
                        Spacer(Modifier.height(8.dp))
                        KPill(if (vm.monitorRunning) "Live tracking running" else "Live tracking off", if (vm.monitorRunning) kc.good else kc.sun)
                    }
                }
            }
        }
        item { KSection("Steps", subtitle = "Android keeps these in its own screens, so each button opens the right one.") }
        items(items.size) { index ->
            val p = items[index]
            KCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KEmoji(emojiFor(p.id), bg = if (p.granted) kc.good.copy(alpha = 0.16f) else kc.sun.copy(alpha = 0.18f))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = kc.text)
                            Spacer(Modifier.width(8.dp))
                            if (p.granted) KPill("On", kc.good) else KPill(if (p.required) "Required" else if (p.id == "notifaccess" || p.id == "media") "Optional" else "Recommended", if (p.required) kc.coral else kc.sun)
                        }
                        Text(p.why, style = MaterialTheme.typography.bodySmall, color = kc.muted)
                    }
                }
                if (!p.granted) {
                    Spacer(Modifier.height(12.dp))
                    KButton("Turn on", { actions.grant(p.id) }, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KEmoji("🧱", bg = kc.purple.copy(alpha = 0.16f))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Hard blocking", style = MaterialTheme.typography.titleSmall, color = kc.text)
                        Text(if (vm.isDeviceOwner) "Device Owner mode is on: blocked apps are suspended by Android itself."
                        else "Optional. Blocked apps are covered by the \"time's up\" screen. Full suspension needs Device Owner setup.",
                            style = MaterialTheme.typography.bodySmall, color = kc.muted)
                    }
                }
                if (!vm.isDeviceOwner) {
                    Spacer(Modifier.height(12.dp))
                    KButton("How does this work?", { showOwnerInfo = true }, Modifier.fillMaxWidth(), soft = true, color = kc.purple)
                }
            }
        }
        item { CaptureStatusCard(capture, onAllow = actions.startCapture, onDecline = actions.declineCapture, onStop = actions.stopCapture) }
        vm.mediaStatus?.let { m ->
            item {
                KCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KEmoji("🖼️", bg = kc.sky.copy(alpha = 0.16f))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Photo & video backup", style = MaterialTheme.typography.titleSmall, color = kc.text)
                            Text(
                                when {
                                    !m.enabled -> "Your parent hasn't turned this on, so nothing is being sent."
                                    m.access == com.sirrlevi.guardian.core.MediaAccess.NONE -> "Your parent turned this on, but no photos are shared with Guardian yet."
                                    m.waiting == "wifi" -> "Waiting for Wi-Fi, as your parent chose."
                                    m.waiting == "offline" -> "No connection right now. Guardian will try again."
                                    else -> "${m.uploaded} sent, ${m.pending} waiting" + (if (m.failed > 0) ", ${m.failed} couldn't be backed up" else "") + "."
                                },
                                style = MaterialTheme.typography.bodySmall, color = kc.muted
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only new photos and videos that Android shares with Guardian are copied, as smaller previews. Originals never leave this phone.",
                        style = MaterialTheme.typography.bodySmall, color = kc.muted
                    )
                    if (m.enabled && m.access != com.sirrlevi.guardian.core.MediaAccess.NONE) {
                        Spacer(Modifier.height(12.dp))
                        KButton("Back up now", { vm.backUpMediaNow() }, Modifier.fillMaxWidth(), soft = true)
                    }
                }
            }
        }
        item {
            KCard {
                Text("Sync", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Text("Last sync with your parent: " + dateTime(vm.lastSync), style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(12.dp))
                KButton("Sync now", { vm.syncNow() }, Modifier.fillMaxWidth(), soft = true)
            }
        }
    }
    if (showOwnerInfo) {
        AlertDialog(
            onDismissRequest = { showOwnerInfo = false },
            title = { Text("About hard blocking") },
            text = {
                Text("Android only lets an app suspend other apps when it is the Device Owner. Installing the APK does not give Guardian that role; it is normally set up once on a fresh or reset phone, by a parent, with a computer (ADB). " +
                    "Without it Guardian still blocks apps with the \"time's up\" screen, as long as Usage Access and Display over other apps stay on.")
            },
            confirmButton = { TextButton(onClick = { showOwnerInfo = false }) { Text("Got it") } }
        )
    }
}

// ------------------------------------------------------------------ browser

@Composable
fun BrowserScreen(vm: ChildViewModel, padding: PaddingValues, actions: ChildActions) {
    val vpnReady = vm.permissions.firstOrNull { it.id == "vpn" }?.granted == true
    ScreenColumn(padding) {
        item { Heading("Browser", "A safe place to browse, with the family's website rules built in.") }
        item {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KEmoji("🧭", bg = kc.sky.copy(alpha = 0.16f), size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Guardian Browser", style = MaterialTheme.typography.titleMedium, color = kc.text)
                        Text("Pages you open here show up in your parent's Activity tab with the time spent.", style = MaterialTheme.typography.bodySmall, color = kc.muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                KButton("Open Guardian Browser", { actions.openBrowser() }, Modifier.fillMaxWidth())
            }
        }
        item {
            KCard {
                Text("Website rules", style = MaterialTheme.typography.titleMedium, color = kc.text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CountTile("Blocked sites", vm.blockedDomainCount, Modifier.weight(1f))
                    CountTile("Blocked pages", vm.blockedUrlCount, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Website filter", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = kc.text)
                    if (vpnReady) KPill("Allowed", kc.good) else KPill("Needs your OK", kc.sun)
                }
                Text("It checks website names on this phone and blocks the ones your parent chose, in every app. Android shows a key icon while it runs.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(12.dp))
                KButton(if (vpnReady) "Start website filter" else "Allow & start website filter", { actions.startWebsiteFilter() }, Modifier.fillMaxWidth(), soft = true)
            }
        }
        item {
            KCard(container = kc.primarySoft) {
                Text("Good to know", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Spacer(Modifier.height(4.dp))
                Text("Guardian can only record pages opened in Guardian Browser. Other browsers such as Chrome keep their history private, and the website filter can block a whole site but can't see which page inside it you opened.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
            }
        }
    }
}

@Composable
private fun CountTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier.clipRound(18).background(kc.primarySoft).padding(14.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = kc.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = kc.muted)
    }
}

private fun Modifier.clipRound(radius: Int): Modifier = this.then(Modifier.clip(RoundedCornerShape(radius.dp)))

// ------------------------------------------------------------------ settings

@Composable
fun ChildSettingsScreen(vm: ChildViewModel, padding: PaddingValues) {
    var showUnpair by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var changing by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val result = vm.updateResult
    val release = result?.release

    ScreenColumn(padding) {
        item { Heading("Settings", "Updates, account and what stays on this phone.") }

        item {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KEmoji("⬇️", bg = kc.sky.copy(alpha = 0.16f))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App updates", style = MaterialTheme.typography.titleMedium, color = kc.text)
                        Text("Guardian Child", style = MaterialTheme.typography.bodySmall, color = kc.muted)
                    }
                    when {
                        result?.updateAvailable == true -> if (result.needsReinstall) KPill("Reinstall once", kc.sun) else KPill("Update ready", kc.good)
                        result != null && release == null -> KPill("No release yet", kc.sun)
                        result != null -> KPill("Up to date", kc.good)
                    }
                }
                Spacer(Modifier.height(12.dp))
                InfoLine("Installed", "v" + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")")
                InfoLine("Latest", if (release != null) release.tagName.removePrefix("guardian-") + " (build " + release.versionCode + ")" else "Not checked yet")
                InfoLine("Last checked", dateTime(vm.lastUpdateCheck))
                Spacer(Modifier.height(8.dp))
                Text(vm.updateStatus, style = MaterialTheme.typography.bodyMedium, color = kc.text)
                if (vm.updateProgress >= 0) {
                    Spacer(Modifier.height(10.dp))
                    KProgress(vm.updateProgress / 100f, kc.primary)
                }
                if (result?.updateAvailable == true && release != null && release.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("What's new", style = MaterialTheme.typography.labelMedium, color = kc.muted)
                    Text(release.notes.trim().take(400), style = MaterialTheme.typography.bodySmall, color = kc.text)
                }
                Spacer(Modifier.height(14.dp))
                val reinstallUrl = vm.reinstallUrl
                if (reinstallUrl != null) {
                    KButton("Download APK in browser", { uriHandler.openUri(reinstallUrl) }, Modifier.fillMaxWidth(), enabled = !vm.updateBusy)
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KButton(if (vm.updateBusy && vm.updateProgress < 0) "Checking…" else "Check for updates", { vm.checkUpdates(true) },
                        Modifier.weight(1f), enabled = !vm.updateBusy, soft = true)
                    if (result?.updateAvailable == true && reinstallUrl == null) KButton("Download & install", { vm.downloadUpdate() }, Modifier.weight(1.2f), enabled = !vm.updateBusy)
                }
                Spacer(Modifier.height(8.dp))
                Text("Guardian checks in the background and shows a notification when a new version is ready. No login or token is needed. Android's installer always asks you to confirm, and your data stays.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                if (changing) {
                    Spacer(Modifier.height(10.dp))
                    Text("GitHub token (only for a private repository)", style = MaterialTheme.typography.titleSmall, color = kc.text)
                    Text("A fine-grained token for Sirrlevi/Guardian with read-only Contents access. It is encrypted with the Android Keystore and stays on this phone.",
                        style = MaterialTheme.typography.bodySmall, color = kc.muted)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = token, onValueChange = { token = it }, singleLine = true, placeholder = { Text("github_pat_…") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    KButton("Save token & check", { vm.saveToken(token); token = ""; changing = false }, Modifier.fillMaxWidth(), enabled = token.trim().length >= 20)
                } else {
                    TextButton(onClick = { changing = true }) { Text(if (vm.hasToken) "Replace token" else "Private repository? Add a token", color = kc.primary) }
                }
            }
        }

        item {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KEmoji("👨‍👩‍👧", bg = kc.primarySoft)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Paired with your family", style = MaterialTheme.typography.titleMedium, color = kc.text)
                        Text("Family " + vm.familyId.take(8), style = MaterialTheme.typography.bodySmall, color = kc.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Unpairing switches protection off, so it needs your parent: they sign in with their Guardian Parent account.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
                Spacer(Modifier.height(10.dp))
                KButton("Parent area: unpair this phone", { showUnpair = true }, Modifier.fillMaxWidth(), soft = true, color = kc.coral)
            }
        }

        item {
            KCard(container = kc.primarySoft) {
                Text("What stays on this phone", style = MaterialTheme.typography.titleSmall, color = kc.text)
                Spacer(Modifier.height(4.dp))
                Text("Your sign-in (encrypted), the family link, the latest rules and downloaded updates live in Guardian's private storage. Updating keeps all of it, so you never have to set things up again.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted)
            }
        }
    }

    if (showUnpair) UnpairDialog(vm) { showUnpair = false }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = kc.muted)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall, color = kc.text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UnpairDialog(vm: ChildViewModel, onClose: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Parent sign-in") },
        text = {
            Column {
                Text("Enter the Guardian Parent account of this family to unpair the phone.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, singleLine = true, label = { Text("Parent email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, label = { Text("Parent password") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = kc.coral)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.contains("@") && password.length >= 6, onClick = {
                busy = true
                error = null
                vm.unpair(email, password) { err ->
                    busy = false
                    if (err == null) onClose() else error = err
                }
            }) { Text(if (busy) "Checking…" else "Unpair", color = kc.coral) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } }
    )
}
