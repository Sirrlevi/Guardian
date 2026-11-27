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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sirrlevi.guardian.core.WallpaperRules
import com.sirrlevi.guardian.core.WallpaperTexts
import com.sirrlevi.guardian.parent.AppearanceModel
import com.sirrlevi.guardian.parent.ParentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun decodeWallpaper(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } catch (_: Exception) { null }
}

/**
 * Device appearance: the wallpaper picture the child chose to share, with the time they shared it.
 *
 * Android doesn't let apps read a wallpaper, so Guardian can't fetch it. This screen shows a picture only after the child picked and
 * sent one from their own phone; until then it says so and how to share. It is never a live view and nothing is drawn in its place.
 */
@Composable
fun AppearanceScreen(vm: ParentViewModel, padding: PaddingValues, watching: Boolean = true) {
    val device = vm.device
    val model = vm.appearance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val name = device?.name ?: "Your child"
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var undecodable by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(device?.id, watching) { if (device != null && watching) model.watch() }
    LaunchedEffect(model.jpeg) {
        val bytes = model.jpeg
        picture = null; undecodable = false
        if (bytes != null) {
            val decoded = decodeWallpaper(bytes)
            picture = decoded; undecodable = decoded == null
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
        val data = model.jpeg
        if (uri != null && data != null) {
            scope.launch(Dispatchers.IO) {
                val ok = try { context.contentResolver.openOutputStream(uri)?.use { it.write(data); true } ?: false } catch (_: Exception) { false }
                withContext(Dispatchers.Main) { model.saved(ok) }
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
                Text("Device appearance", style = MaterialTheme.typography.headlineMedium)
                Text(if (device != null) "For ${device.name}" else "For your child's phone", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        if (device == null) {
            item { GCard { EmptyState(GIcon.Photo, "No phone connected", "Pair your child's phone from More → Settings to see what it shares.") } }
            return@LazyColumn
        }

        item {
            GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Row(verticalAlignment = Alignment.Top) {
                    GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Shared by your child, never taken", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Android doesn't let apps read a phone's wallpaper. Guardian only shows a wallpaper if your child chose a picture and sent it from their own phone, " +
                                "and they can remove it again.",
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted
                        )
                    }
                }
            }
        }

        item {
            GCard {
                Text("Wallpaper", style = MaterialTheme.typography.titleMedium)
                val meta = model.meta
                when (model.phase) {
                    AppearanceModel.Phase.LOADING -> WallNote(WallpaperTexts.LOADING, gColors.muted)
                    AppearanceModel.Phase.FAILED -> WallNote(WallpaperTexts.LOAD_FAILED, gColors.warning)
                    AppearanceModel.Phase.NOT_SHARED -> WallNote(WallpaperTexts.notShared(name), gColors.muted)
                    AppearanceModel.Phase.DAMAGED -> WallNote(WallpaperTexts.incomplete(name), gColors.warning)
                    AppearanceModel.Phase.SHOWN -> {
                        val shown = picture
                        when {
                            undecodable -> WallNote(WallpaperTexts.incomplete(name), gColors.warning)
                            shown == null -> WallNote(WallpaperTexts.LOADING, gColors.muted)
                            else -> {
                                Spacer(Modifier.height(10.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Image(
                                        shown, contentDescription = "Wallpaper shared by $name", contentScale = ContentScale.Fit,
                                        modifier = Modifier.heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)).clickable { open = true }
                                    )
                                }
                            }
                        }
                    }
                }
                if (meta != null && model.phase != AppearanceModel.Phase.NOT_SHARED) {
                    Spacer(Modifier.height(10.dp))
                    Text("Last updated " + Formats.dateTime(meta.ts) + " · " + Formats.relative(meta.ts), style = MaterialTheme.typography.titleSmall)
                    Text(WallpaperRules.detail(meta), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    if (model.phase == AppearanceModel.Phase.SHOWN) WallNote(WallpaperTexts.sharedNote(name), gColors.muted)
                }
                val error = model.error
                if (error != null) WallNote("Couldn't reach the server: $error. What's shown may be out of date.", gColors.warning)
                model.notice?.let { WallNote(it, if (model.noticeIsProblem) gColors.warning else MaterialTheme.colorScheme.primary) }

                val showing = model.phase == AppearanceModel.Phase.SHOWN && picture != null
                Spacer(Modifier.height(12.dp))
                if (showing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Open and zoom", { open = true }, Modifier.weight(1f), icon = GIcon.Photo)
                        SoftButton("Save", { model.meta?.let { saver.launch(WallpaperRules.fileName(it.ts)) } }, Modifier.weight(1f), icon = GIcon.Download)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Check again", { scope.launch { model.refreshNow() } }, Modifier.weight(1f), enabled = !model.busy, icon = GIcon.Refresh)
                    if (meta != null && model.phase != AppearanceModel.Phase.NOT_SHARED && model.phase != AppearanceModel.Phase.LOADING) {
                        SoftButton("Delete picture", { confirmDelete = true }, Modifier.weight(1f), enabled = !model.busy, color = gColors.danger, icon = GIcon.Trash)
                    }
                }
            }
        }
    }

    val shown = picture
    val meta = model.meta
    if (open && shown != null && meta != null) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 12.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Formats.dateTime(meta.ts), style = MaterialTheme.typography.titleMedium)
                        Text("Wallpaper shared by $name · " + WallpaperRules.detail(meta), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                    SoftButton("Close", { open = false }, icon = GIcon.Close)
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    ZoomableImage(shown, "Wallpaper shared by $name")
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Pinch to zoom, double-tap to reset.", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(bottom = 6.dp))
                    SoftButton("Save", { saver.launch(WallpaperRules.fileName(meta.ts)) }, Modifier.fillMaxWidth(), icon = GIcon.Download)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the shared wallpaper?") },
            text = { Text("This removes the picture from Guardian's server. Nothing changes on $name's phone, and they can share it again.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { model.delete() } }) { Text("Delete", color = gColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WallNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 6.dp))
}
