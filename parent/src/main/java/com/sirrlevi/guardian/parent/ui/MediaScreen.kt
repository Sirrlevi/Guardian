package com.sirrlevi.guardian.parent.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.MediaAccess
import com.sirrlevi.guardian.core.MediaItem
import com.sirrlevi.guardian.core.MediaKind
import com.sirrlevi.guardian.core.MediaPlanner
import com.sirrlevi.guardian.core.MediaSettings
import com.sirrlevi.guardian.core.MediaSettingsCodec
import com.sirrlevi.guardian.core.MediaStatus
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.Connection
import com.sirrlevi.guardian.parent.ParentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COLUMNS = 3
private val RETENTION_LABELS = listOf("7 days", "30 days", "90 days", "Until deleted")
private val FILTER_LABELS = listOf("All", "Photos", "Videos")

private sealed interface GalleryRow {
    val id: String
    class Header(val day: String) : GalleryRow { override val id: String get() = "h$day" }
    class Tiles(val items: List<MediaItem>) : GalleryRow { override val id: String get() = "t" + items.first().key }
}

/**
 * Optional preview copies of the child's new photos and videos.
 *
 * What is stored is a resized picture (and one still frame per video); originals never leave the phone, and
 * this screen says so. Backup is off until the parent turns it on, and whatever the phone cannot do right now
 * (no permission, waiting for Wi-Fi, a failed file) is reported with its reason instead of being hidden.
 */
@Composable
fun MediaScreen(vm: ParentViewModel, padding: PaddingValues) {
    val device = vm.device
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    var confirmEnable by remember { mutableStateOf(false) }
    var showFailed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(device?.id) { if (device != null) vm.loadMedia() }

    val settings = vm.mediaSettings
    val all = vm.mediaItems
    val ok = remember(all) { all.filter { it.status == MediaStatus.OK } }
    val failed = remember(all) { all.filter { it.status == MediaStatus.FAILED } }
    val shown = remember(ok, filter) {
        when (filter) {
            1 -> ok.filter { it.kind == MediaKind.IMAGE }
            2 -> ok.filter { it.kind == MediaKind.VIDEO }
            else -> ok
        }
    }
    val todayKey = remember { DayClock.of(System.currentTimeMillis()).dayKey }
    val rows = remember(shown) {
        buildList<GalleryRow> {
            MediaPlanner.groupByDay(shown).forEach { (day, items) ->
                add(GalleryRow.Header(day))
                items.chunked(COLUMNS).forEach { add(GalleryRow.Tiles(it)) }
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Shared media", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (device != null) "Photos and videos from ${device.name}" else "Photos and videos",
                    style = MaterialTheme.typography.bodyMedium, color = gColors.muted
                )
            }
        }

        if (device == null) {
            item { GCard { EmptyState(GIcon.Phone, "No phone connected", "Pair your child's phone from More → Settings to use shared media.") } }
            return@LazyColumn
        }

        item { HonestyNote(device.name) }
        item { SettingsCard(vm, settings, onEnable = { confirmEnable = true }) }
        item { PhoneStatusCard(device, vm.mediaStatus, settings, offline = vm.connection == Connection.Offline, loading = vm.mediaLoading, onRefresh = { vm.loadMedia() }) }

        val error = vm.mediaError
        if (error != null) {
            item {
                GCard(container = gColors.warning.copy(alpha = 0.12f)) {
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    SoftButton("Try again", { vm.loadMedia() }, color = gColors.warning, icon = GIcon.Refresh)
                }
            }
        }

        if (ok.isNotEmpty() || (vm.mediaLoaded && failed.isEmpty())) {
            item { Segmented(FILTER_LABELS, filter, { filter = it }) }
        }

        when {
            vm.mediaLoading && !vm.mediaLoaded -> item { SkeletonTiles() }

            ok.isEmpty() -> item {
                GCard {
                    if (error != null && !vm.mediaLoaded) {
                        EmptyState(GIcon.Warning, "Couldn't load photos", "Check your connection and try again.")
                    } else if (!settings.enabled) {
                        EmptyState(
                            GIcon.Photo, "Photo backup is off",
                            "Turn it on above to keep preview copies of new photos and videos from ${device.name}'s phone. Nothing is copied until you do."
                        )
                    } else {
                        EmptyState(
                            GIcon.Photo, "No previews yet",
                            if (vm.mediaStatus?.access == MediaAccess.NONE)
                                "Photo access is off on ${device.name}'s phone, so nothing can be backed up. Open Guardian Child there and allow photo access."
                            else "New photos and videos added after backup was switched on show up here once the phone has synced."
                        )
                    }
                }
            }

            shown.isEmpty() -> item {
                GCard {
                    EmptyState(
                        GIcon.Search, "Nothing here", "No ${if (filter == 1) "photos" else "videos"} among the previews loaded so far.",
                        action = { SoftButton("Show all", { filter = 0 }) }
                    )
                }
            }

            else -> {
                items(rows, key = { it.id }) { row ->
                    when (row) {
                        is GalleryRow.Header -> Text(
                            Formats.dayLabel(row.day, todayKey), style = MaterialTheme.typography.labelLarge,
                            color = gColors.muted, modifier = Modifier.padding(top = 4.dp)
                        )
                        is GalleryRow.Tiles -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.items.forEach { item -> Tile(item, Modifier.weight(1f)) { viewing = item } }
                            repeat(COLUMNS - row.items.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                item {
                    Text(
                        "Previews loaded: ${ok.size} · ${MediaPlanner.humanBytes(MediaPlanner.storageBytes(ok))}" +
                            if (vm.mediaHasMore) " · more available" else "",
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted
                    )
                }
                if (vm.mediaHasMore) {
                    item {
                        SoftButton(
                            if (vm.mediaLoading) "Loading…" else "Load older previews", { vm.loadMoreMedia() },
                            Modifier.fillMaxWidth(), enabled = !vm.mediaLoading, icon = GIcon.Refresh
                        )
                    }
                }
            }
        }

        if (failed.isNotEmpty()) {
            item {
                GCard {
                    Row(Modifier.fillMaxWidth().clickable { showFailed = !showFailed }, verticalAlignment = Alignment.CenterVertically) {
                        GIconView(GIcon.Warning, gColors.warning, size = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Couldn't be backed up (${failed.size})", style = MaterialTheme.typography.titleSmall)
                            Text("These files stay on the phone. The reason is shown for each.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                        }
                        Text(if (showFailed) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    if (showFailed) {
                        failed.take(15).forEach { f ->
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = gColors.stroke)
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (f.kind == MediaKind.VIDEO) "Video" else "Photo") + " · " + Formats.dateTime(f.createdMs) + " · " + (f.reason ?: "Unknown reason"),
                                style = MaterialTheme.typography.bodySmall, color = gColors.muted
                            )
                        }
                        if (failed.size > 15) Text("Showing 15 of ${failed.size}.", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (confirmEnable && device != null) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Turn on photo backup?") },
            text = {
                Text(
                    "Guardian will keep a resized preview copy of new photos, and one still frame of new videos, from ${device.name}'s phone. " +
                        "Originals stay on the phone and older media is not copied. ${device.name} can see that backup is on and can turn off photo access in Android settings at any time."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmEnable = false; vm.updateMediaSettings { it.copy(enabled = true) } }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("Cancel") } }
        )
    }

    val open = viewing
    if (open != null && device != null) {
        MediaViewer(vm, open, device.name, onClose = { viewing = null })
    }
}

@Composable
private fun HonestyNote(name: String) {
    GCard(container = gColors.info.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.Top) {
            GIconView(GIcon.Shield, gColors.info, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Preview copies only", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "When this is on, Guardian copies a resized version of new photos (and one still frame of each new video) from $name's phone. " +
                        "Original photos and videos are never uploaded and stay on the phone; original-quality backup needs separate file storage that this app does not have yet. " +
                        "Only media added after you switch this on is copied, and $name can see that backup is on.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsCard(vm: ParentViewModel, s: MediaSettings, onEnable: () -> Unit) {
    GCard {
        ToggleRow(
            "Back up new photos & videos",
            if (s.enabled) (if (s.enabledAt > 0L) "On · copying media added after ${Formats.dateTime(s.enabledAt)}" else "On") else "Off · nothing is copied",
            s.enabled
        ) { on -> if (on) onEnable() else vm.updateMediaSettings { it.copy(enabled = false) } }
        if (s.enabled) {
            HorizontalDivider(color = gColors.stroke)
            ToggleRow("Photos", null, s.images) { on -> vm.updateMediaSettings { it.copy(images = on) } }
            ToggleRow("Videos", "A still frame only; the video file is not copied", s.videos) { on -> vm.updateMediaSettings { it.copy(videos = on) } }
            ToggleRow("Wi-Fi only", "Don't use mobile data for backup", s.wifiOnly) { on -> vm.updateMediaSettings { it.copy(wifiOnly = on) } }
            Spacer(Modifier.height(6.dp))
            Text("Keep previews for", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val selected = MediaSettingsCodec.RETENTION_CHOICES.indexOf(MediaSettingsCodec.normalizeRetention(s.retentionDays)).coerceAtLeast(0)
            Segmented(RETENTION_LABELS, selected, { i -> vm.updateMediaSettings { it.copy(retentionDays = MediaSettingsCodec.RETENTION_CHOICES[i]) } })
            Spacer(Modifier.height(6.dp))
            Text(
                "Turning backup off stops new copies. Previews already kept stay until they expire or you delete them.",
                style = MaterialTheme.typography.bodySmall, color = gColors.muted
            )
        }
        if (vm.mediaSaving) {
            Spacer(Modifier.height(6.dp))
            Text("Saving…", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
    }
}

@Composable
private fun PhoneStatusCard(d: ChildDevice, status: MediaStatusSnapshot?, settings: MediaSettings, offline: Boolean, loading: Boolean, onRefresh: () -> Unit) {
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On the phone", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (status != null && status.lastRunMs > 0L) "Last checked ${Formats.relative(status.lastRunMs)}" else "Last checked: never",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
            when {
                status == null -> Pill("No report yet", gColors.muted)
                status.access == MediaAccess.FULL -> Pill("Full photo access", gColors.success)
                status.access == MediaAccess.PARTIAL -> Pill("Selected photos only", gColors.warning)
                else -> Pill("No photo access", gColors.warning)
            }
        }
        if (status != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Waiting", status.pending.toString(), Modifier.weight(1f))
                StatTile("Backed up", status.uploaded.toString(), Modifier.weight(1f))
                StatTile("Failed", status.failed.toString(), Modifier.weight(1f), valueColor = if (status.failed > 0) gColors.warning else MaterialTheme.colorScheme.onSurface)
            }
            val why = waitingText(status, settings, d.name)
            if (why != null) Note(why, gColors.warning)
            if (status.access == MediaAccess.PARTIAL) {
                Note("Android lets ${d.name} share only selected photos with apps, so only those can be backed up.", gColors.muted)
            }
            val problem = status.lastError
            if (problem != null) Note("Last problem: $problem", gColors.muted)
        } else if (d.info != null) {
            Note("The phone has not reported media status yet. If this stays empty, update Guardian Child on ${d.name}'s phone.", gColors.muted)
        }
        if (offline) Note("You are offline. Showing what was loaded last.", gColors.warning)
        Spacer(Modifier.height(10.dp))
        SoftButton(if (loading) "Refreshing…" else "Refresh", onRefresh, enabled = !loading, icon = GIcon.Refresh)
    }
}

private fun waitingText(s: MediaStatusSnapshot, settings: MediaSettings, name: String): String? = when (s.waiting) {
    "permission" -> "Photo access is off on $name's phone. Open Guardian Child there and allow it; Android always lets the child change this."
    "disabled" -> if (settings.enabled) "$name's phone has not picked up the new setting yet. It starts when Guardian Child next syncs." else null
    "wifi" -> "Waiting for Wi-Fi. Backup is set not to use mobile data."
    "offline" -> "The phone lost its connection and will retry."
    else -> null
}

@Composable
private fun Note(text: String, color: Color) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun Tile(item: MediaItem, modifier: Modifier, onClick: () -> Unit) {
    val bitmap = remember(item.key) { decodeThumb(item.thumb) }
    val label = (if (item.kind == MediaKind.VIDEO) "Video" else "Photo") + " from " + Formats.timeOfDay(item.createdMs)
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = label }.clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GIconView(GIcon.Photo, gColors.muted, Modifier.align(Alignment.Center), size = 28.dp)
        }
        if (item.kind == MediaKind.VIDEO) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIconView(GIcon.Play, Color.White, size = 12.dp)
                if (item.durationMs > 0L) {
                    Spacer(Modifier.width(4.dp))
                    Text(duration(item.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SkeletonTiles() {
    val pulse = rememberInfiniteTransition(label = "media-skeleton")
    val a = pulse.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(Modifier.alpha(a.value), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(COLUMNS) { Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(gColors.stroke)) }
            }
        }
    }
}

/** Full-screen look at one stored preview, with zoom, save-to-phone and delete. */
@Composable
private fun MediaViewer(vm: ParentViewModel, item: MediaItem, deviceName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember(item.key) { mutableIntStateOf(0) } // 0 loading, 1 ready, 2 unavailable
    var bytes by remember(item.key) { mutableStateOf<ByteArray?>(null) }
    var picture by remember(item.key) { mutableStateOf<ImageBitmap?>(null) }
    var note by remember(item.key) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val thumb = remember(item.key) { decodeThumb(item.thumb) }

    LaunchedEffect(item.key) {
        val data = vm.loadMediaPreview(item)
        val decoded = if (data == null) null else withContext(Dispatchers.Default) {
            try { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() } catch (_: Exception) { null }
        }
        if (data != null && decoded != null) { bytes = data; picture = decoded; phase = 1 } else phase = 2
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
        val data = bytes
        if (uri != null && data != null) {
            scope.launch(Dispatchers.IO) {
                val saved = try { context.contentResolver.openOutputStream(uri)?.use { it.write(data); true } ?: false } catch (_: Exception) { false }
                withContext(Dispatchers.Main) { note = if (saved) "Preview saved." else "Couldn't save the preview." }
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Formats.dateTime(item.createdMs), style = MaterialTheme.typography.titleMedium)
                    Text(item.name, style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SoftButton("Close", onClose, icon = GIcon.Close)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                when (phase) {
                    0 -> Text("Loading preview…", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
                    1 -> ZoomableImage(picture!!, if (item.kind == MediaKind.VIDEO) "Still frame from a video" else "Photo preview")
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                        if (thumb != null) {
                            Image(thumb, contentDescription = "Small thumbnail", contentScale = ContentScale.Fit, modifier = Modifier.weight(1f, fill = false).fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            "The full preview couldn't be loaded. Check your connection and open it again" + if (thumb != null) "; the small thumbnail is shown meanwhile." else ".",
                            style = MaterialTheme.typography.bodyMedium, color = gColors.warning
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                val what = if (item.kind == MediaKind.VIDEO) "video" else "photo"
                val size = if (item.width > 0 && item.height > 0) " · ${item.width}×${item.height}" else ""
                Text(
                    "Preview copy (${MediaPlanner.humanBytes(item.previewBytes.toLong())}). The original $what (${MediaPlanner.humanBytes(item.sizeBytes)}$size) stays on $deviceName's phone.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
                if (item.kind == MediaKind.VIDEO) {
                    Text(
                        "This is one still frame" + (if (item.durationMs > 0L) " from a ${duration(item.durationMs)} video" else "") + ". The video itself is not backed up.",
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted
                    )
                }
                val message = note
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton(
                        "Save preview", { saver.launch("guardian-" + item.key.substringBefore('_') + ".jpg") },
                        Modifier.weight(1f), enabled = phase == 1, icon = GIcon.Download
                    )
                    SoftButton("Delete", { confirmDelete = true }, Modifier.weight(1f), enabled = !deleting, color = gColors.danger, icon = GIcon.Trash)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this preview?") },
            text = { Text("This removes the backed-up preview copy from Guardian. The original stays on $deviceName's phone and will not be backed up again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    vm.deleteMedia(item) { done ->
                        deleting = false
                        if (done) onClose() else note = "Couldn't delete it. Check your connection and try again."
                    }
                }) { Text("Delete", color = gColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun ZoomableImage(bitmap: ImageBitmap, description: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    Image(
        bitmap, contentDescription = description, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = box.width * (next - 1f) / 2f
                    val maxY = box.height * (next - 1f) / 2f
                    offset = if (next == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    scale = next
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
    )
}

private fun decodeThumb(thumb: String?): ImageBitmap? {
    if (thumb.isNullOrEmpty()) return null
    return try {
        val data = Base64.decode(thumb, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
    } catch (_: Exception) { null }
}

private fun duration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return if (minutes >= 60L) String.format(java.util.Locale.US, "%d:%02d:%02d", minutes / 60L, minutes % 60L, seconds)
    else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}
