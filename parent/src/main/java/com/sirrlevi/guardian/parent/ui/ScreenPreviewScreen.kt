package com.sirrlevi.guardian.parent.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sirrlevi.guardian.core.CaptureEventCodec
import com.sirrlevi.guardian.core.CaptureHeadline
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureReasons
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.LinkQuality
import com.sirrlevi.guardian.core.ShotMeta
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.ScreenPreviewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun decodeJpeg(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } catch (_: Exception) { null }
}

private fun minutes(ms: Long): Long = ms / 60_000L

/**
 * Approved screen sharing: ask to see the child's screen, take screenshots, and watch a small live picture.
 *
 * Nothing starts without the child tapping Allow on their phone and confirming Android's own dialog, every time. The status shown
 * here is what the phone last reported (with its age); a request is shown as waiting until the phone answers it.
 */
@Composable
fun ScreenPreviewScreen(vm: ParentViewModel, padding: PaddingValues, watching: Boolean = true) {
    val device = vm.device
    val model = vm.preview
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<ShotMeta?>(null) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }

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
                Text("Screen preview", style = MaterialTheme.typography.headlineMedium)
                Text(if (device != null) "Approved sharing with ${device.name}" else "Approved sharing", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        if (device == null) {
            item { GCard { EmptyState(GIcon.Phone, "No phone connected", "Pair your child's phone from More → Settings to use screen preview.") } }
            return@LazyColumn
        }

        item { PreviewHonestyNote(device.name) }
        item { PreviewStatusCard(model, device, clock) }
        item { PreviewControls(model, device.name) { block -> scope.launch { block() } } }

        val state = model.state
        if (model.view.headline == CaptureHeadline.SHARING && state?.live == true) item { PreviewLiveCard(model, clock) }

        item { SectionTitle("Screenshots", subtitle = "The newest ${CapturePolicy.SHOT_KEEP} are kept on Guardian's server; older ones are removed automatically.") }
        val shots = model.shots
        if (shots.isEmpty()) {
            item {
                GCard {
                    EmptyState(
                        GIcon.Screen, "No screenshots yet",
                        if (model.view.canShot) "Tap Take screenshot to get one from ${device.name}'s phone." else "Once ${device.name} allows sharing, you can take screenshots here."
                    )
                }
            }
        } else {
            item { PreviewLatestShot(model, shots.first(), clock) { viewing = it } }
            if (shots.size > 1) {
                item {
                    GCard {
                        Text("Earlier screenshots", style = MaterialTheme.typography.titleSmall)
                        shots.drop(1).forEach { shot ->
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = gColors.stroke)
                            Row(Modifier.fillMaxWidth().clickable { viewing = shot }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Formats.dateTime(shot.ts), style = MaterialTheme.typography.bodyMedium)
                                    Text("${shot.width}×${shot.height}", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                                }
                                Text("View", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        item {
            GCard {
                Row(Modifier.fillMaxWidth().clickable { showLog = !showLog }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recent activity", style = MaterialTheme.typography.titleSmall)
                        Text("What happened in recent sharing sessions, as the phone recorded it.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                    Text(if (showLog) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (showLog) {
                    if (model.log.isEmpty()) {
                        Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted, modifier = Modifier.padding(top = 10.dp))
                    }
                    model.log.take(12).forEach { e ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = gColors.stroke)
                        Text(CaptureEventCodec.label(e.kind), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Formats.dateTime(e.ts) + (e.detail?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted
                        )
                    }
                }
            }
        }
    }

    val open = viewing
    if (open != null) PreviewShotViewer(model, open, device?.name ?: "the child", onClose = { viewing = null })
}

@Composable
private fun PreviewHonestyNote(name: String) {
    GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.Top) {
            GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Only with your child's OK, every time", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$name has to tap Allow on their phone and confirm Android's own dialog every time. A notification stays visible on the phone while the screen is shared, " +
                        "with a button to stop. Sessions end by themselves after ${minutes(CapturePolicy.SESSION_MAX_MS)} minutes. " +
                        "Screens that Android protects (some banking and video apps) can't be captured and show as black. Guardian never works around that.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
        }
    }
}

private fun headlineTitle(h: CaptureHeadline, name: String): String = when (h) {
    CaptureHeadline.NONE -> "Not sharing"
    CaptureHeadline.WAITING -> "Waiting for $name to allow"
    CaptureHeadline.SHARING -> "Sharing now"
    CaptureHeadline.NOT_RESPONDING -> "The phone stopped answering"
    CaptureHeadline.ENDED -> "Session ended"
}

@Composable
private fun headlineColor(h: CaptureHeadline): Color = when (h) {
    CaptureHeadline.SHARING -> gColors.success
    CaptureHeadline.WAITING, CaptureHeadline.NOT_RESPONDING -> gColors.warning
    else -> gColors.muted
}

private fun headlineDetail(h: CaptureHeadline, s: CaptureState?, reason: String?, name: String, now: Long): String = when (h) {
    CaptureHeadline.NONE -> "Nothing is being shared. Ask to see the screen and $name gets a request on the phone."
    CaptureHeadline.WAITING -> "The request is on the phone and lapses at ${Formats.timeOfDay(s?.expiresAt ?: 0L)}." +
        if (s != null && !s.notifOk) " Notifications are off on the phone, so $name may not see it. Ask them to open Guardian Child." else ""
    CaptureHeadline.SHARING -> "Since ${Formats.timeOfDay(s?.since ?: 0L)}. Ends by itself at ${Formats.timeOfDay(s?.expiresAt ?: 0L)}." +
        if (s != null && s.width > 0) " Picture size ${s.width}×${s.height}." else ""
    CaptureHeadline.NOT_RESPONDING -> "No report from the phone for ${Formats.relative(s?.heartbeat ?: 0L, now).removeSuffix(" ago")}. " +
        "It may be offline, or sharing may have been stopped there. Nothing is captured while it is silent."
    CaptureHeadline.ENDED -> CaptureReasons.text(reason, name)
}

@Composable
private fun PreviewStatusCard(model: ScreenPreviewModel, device: ChildDevice, clock: Long) {
    val view = model.view
    val state = model.state
    val headline = view.headline
    val now = if (model.now > 0L) maxOf(clock, model.now) else clock
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(headlineTitle(headline, device.name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Pill(
                when (headline) {
                    CaptureHeadline.NONE -> "Off"; CaptureHeadline.WAITING -> "Waiting"; CaptureHeadline.SHARING -> "Live session"
                    CaptureHeadline.NOT_RESPONDING -> "No answer"; CaptureHeadline.ENDED -> "Ended"
                },
                headlineColor(headline)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(headlineDetail(headline, state, view.reason, device.name, now), style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
        val phoneNote = state?.error
        if (headline == CaptureHeadline.SHARING && phoneNote != null) {
            Spacer(Modifier.height(6.dp))
            Text("The phone says: $phoneNote", style = MaterialTheme.typography.bodySmall, color = gColors.warning)
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = gColors.stroke)

        InfoRow(
            "Phone connection",
            when {
                model.error != null -> "Can't reach the server. Showing the last known state."
                device.isOnline -> "Online"
                else -> "Offline · last seen " + Formats.relative(device.presenceTs(), now)
            },
            if (model.error != null || !device.isOnline) gColors.warning else gColors.success
        )
        InfoRow(
            "Permission",
            when (headline) {
                CaptureHeadline.SHARING, CaptureHeadline.NOT_RESPONDING -> "Allowed on the phone for this session"
                CaptureHeadline.WAITING -> "Waiting for ${device.name}'s OK"
                CaptureHeadline.ENDED -> if (view.reason == CaptureReasons.DECLINED) "${device.name} declined" else "Not allowed (session ended)"
                CaptureHeadline.NONE -> "Not allowed. ${device.name} is asked each time."
            }
        )
        if (state != null && headline != CaptureHeadline.NONE) {
            InfoRow("Phone notifications", if (state.notifOk) "On" else "Off, so requests may go unseen", if (state.notifOk) null else gColors.warning)
        }
        InfoRow("Last screenshot", if ((state?.lastShotAt ?: 0L) > 0L) Formats.dateTime(state!!.lastShotAt) + " (" + Formats.relative(state.lastShotAt, now) + ")" else "None yet")
        InfoRow(
            "Transfer",
            when (state?.transfer) {
                "sending" -> "Sending a picture…"
                "ok" -> "Last picture delivered"
                "failed" -> "Last picture failed to send"
                else -> "Nothing sent"
            },
            if (state?.transfer == "failed") gColors.warning else null
        )
        if (model.checkedAt > 0L) {
            Text("Checked " + Formats.relative(model.checkedAt, now), style = MaterialTheme.typography.labelSmall, color = gColors.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, tint: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(132.dp), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PreviewControls(model: ScreenPreviewModel, name: String, run: (suspend () -> Unit) -> Unit) {
    val view = model.view
    val state = model.state
    val busy = model.sending
    GCard {
        when (view.headline) {
            CaptureHeadline.NONE, CaptureHeadline.ENDED ->
                PrimaryButton("Ask to see the screen", { run { model.ask() } }, Modifier.fillMaxWidth(), enabled = view.canAsk && !busy, icon = GIcon.Screen)
            CaptureHeadline.WAITING ->
                SoftButton("Cancel request", { run { model.stop() } }, Modifier.fillMaxWidth(), enabled = view.canStop && !busy, icon = GIcon.Close)
            CaptureHeadline.SHARING -> {
                PrimaryButton("Take screenshot", { run { model.shot() } }, Modifier.fillMaxWidth(), enabled = view.canShot && !busy, icon = GIcon.Photo)
                Spacer(Modifier.height(8.dp))
                val liveOn = state?.live == true
                SoftButton(
                    if (liveOn) "Stop live preview" else "Start live preview", { run { model.setLive(!liveOn) } },
                    Modifier.fillMaxWidth(), enabled = view.canLive && !busy, icon = if (liveOn) GIcon.Pause else GIcon.Play
                )
                Spacer(Modifier.height(8.dp))
                SoftButton("Stop sharing", { run { model.stop() } }, Modifier.fillMaxWidth(), enabled = view.canStop && !busy, color = gColors.danger, icon = GIcon.Close)
            }
            CaptureHeadline.NOT_RESPONDING -> {
                Text("$name's phone isn't answering, so screenshots and the preview are paused.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
                Spacer(Modifier.height(8.dp))
                SoftButton("End this session", { run { model.stop() } }, Modifier.fillMaxWidth(), enabled = view.canStop && !busy, color = gColors.danger, icon = GIcon.Close)
            }
        }
        val waiting = model.waiting
        val notice = model.notice
        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(notice, style = MaterialTheme.typography.bodyMedium, color = if (model.noticeIsProblem) gColors.warning else MaterialTheme.colorScheme.primary)
        } else if (waiting != null) {
            Spacer(Modifier.height(10.dp))
            Text("Waiting for the phone to answer…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
        }
        val error = model.error
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text("Couldn't reach the server: $error", style = MaterialTheme.typography.bodySmall, color = gColors.warning)
        }
    }
}

@Composable
private fun PreviewLiveCard(model: ScreenPreviewModel, clock: Long) {
    val live = model.live
    val bytes = model.liveJpeg
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(live?.seq) { val b = bytes; if (b != null) decodeJpeg(b)?.let { picture = it } }
    val now = if (model.now > 0L) maxOf(clock, model.now) else clock
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live preview", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Pill(
                when (model.link) {
                    LinkQuality.GOOD -> "Good connection"; LinkQuality.SLOW -> "Slow connection"
                    LinkQuality.STALLED -> "Stalled"; LinkQuality.UNKNOWN -> "Starting"
                },
                when (model.link) { LinkQuality.GOOD -> gColors.success; LinkQuality.UNKNOWN -> gColors.muted; else -> gColors.warning }
            )
        }
        Spacer(Modifier.height(8.dp))
        val shown = picture
        if (shown != null) {
            Image(
                shown, contentDescription = "Live preview of the child's screen", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
            )
        } else {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("Waiting for the first picture…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        val updated = if (model.liveSeenAt > 0L) "Updated " + Formats.relative(model.liveSeenAt, now) else "No picture yet"
        Text(
            when (model.link) {
                LinkQuality.STALLED -> "No new picture for a while. The phone may be offline or on a protected or switched-off screen."
                else -> updated
            },
            style = MaterialTheme.typography.bodySmall, color = gColors.muted
        )
        Text(
            "A small picture that refreshes every few seconds, not video, to save battery and data. It only updates while this screen is open.",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted
        )
    }
}

@Composable
private fun PreviewLatestShot(model: ScreenPreviewModel, shot: ShotMeta, clock: Long, onOpen: (ShotMeta) -> Unit) {
    var picture by remember(shot.id) { mutableStateOf<ImageBitmap?>(null) }
    var phase by remember(shot.id) { mutableIntStateOf(0) } // 0 loading, 1 ready, 2 unavailable
    LaunchedEffect(shot.id) {
        val data = model.shotImage(shot)
        val decoded = if (data == null) null else decodeJpeg(data)
        if (decoded != null) { picture = decoded; phase = 1 } else phase = 2
    }
    val now = if (model.now > 0L) maxOf(clock, model.now) else clock
    GCard {
        Text("Latest screenshot", style = MaterialTheme.typography.titleSmall)
        Text(Formats.dateTime(shot.ts) + " · " + Formats.relative(shot.ts, now) + " · ${shot.width}×${shot.height}", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        Spacer(Modifier.height(8.dp))
        when (phase) {
            1 -> Image(
                picture!!, contentDescription = "Latest screenshot, tap to enlarge", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clickable { onOpen(shot) }
            )
            0 -> Text("Loading screenshot…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            else -> Text("This screenshot couldn't be loaded. Check your connection and try again.", style = MaterialTheme.typography.bodyMedium, color = gColors.warning)
        }
        Spacer(Modifier.height(8.dp))
        SoftButton("Open, zoom and save", { onOpen(shot) }, Modifier.fillMaxWidth(), enabled = phase != 0)
    }
}

@Composable
private fun PreviewShotViewer(model: ScreenPreviewModel, shot: ShotMeta, deviceName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember(shot.id) { mutableIntStateOf(0) }
    var bytes by remember(shot.id) { mutableStateOf<ByteArray?>(null) }
    var picture by remember(shot.id) { mutableStateOf<ImageBitmap?>(null) }
    var note by remember(shot.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(shot.id) {
        val data = model.shotImage(shot)
        val decoded = if (data == null) null else decodeJpeg(data)
        if (data != null && decoded != null) { bytes = data; picture = decoded; phase = 1 } else phase = 2
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
        val data = bytes
        if (uri != null && data != null) {
            scope.launch(Dispatchers.IO) {
                val saved = try { context.contentResolver.openOutputStream(uri)?.use { it.write(data); true } ?: false } catch (_: Exception) { false }
                withContext(Dispatchers.Main) { note = if (saved) "Screenshot saved." else "Couldn't save the screenshot." }
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Formats.dateTime(shot.ts), style = MaterialTheme.typography.titleMedium)
                    Text("Screenshot of $deviceName's screen · ${shot.width}×${shot.height}", style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SoftButton("Close", onClose, icon = GIcon.Close)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                when (phase) {
                    0 -> Text("Loading screenshot…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
                    1 -> ZoomableImage(picture!!, "Screenshot")
                    else -> Text(
                        "The screenshot couldn't be loaded. Check your connection and open it again.",
                        style = MaterialTheme.typography.bodyMedium, color = gColors.warning, modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                val message = note
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Save", { saver.launch("guardian-screen-" + shot.ts + ".jpg") }, Modifier.weight(1f), enabled = phase == 1, icon = GIcon.Download)
                    SoftButton("Delete", { confirmDelete = true }, Modifier.weight(1f), enabled = !deleting, color = gColors.danger, icon = GIcon.Trash)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this screenshot?") },
            text = { Text("This removes it from Guardian's server. Nothing changes on $deviceName's phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        val done = model.deleteShot(shot)
                        deleting = false
                        if (done) onClose() else note = "Couldn't delete it. Check your connection and try again."
                    }
                }) { Text("Delete", color = gColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
