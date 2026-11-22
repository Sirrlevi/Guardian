package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.parent.ParentViewModel

/** What the parent may truthfully say about their last change: sent, or confirmed by the child's phone. */
enum class ApplyState { None, Waiting, Offline, Applied, AppliedLimited }

fun applyState(vm: ParentViewModel): ApplyState {
    val d = vm.device ?: return ApplyState.None
    val sent = vm.policy.updatedAt
    if (sent <= 0L) return ApplyState.None
    val ack = d.ack
    if (ack == null || ack.policyUpdatedAt < sent) return if (d.isOnline) ApplyState.Waiting else ApplyState.Offline
    return if (ack.enforcing) ApplyState.Applied else ApplyState.AppliedLimited
}

@Composable
fun ApplyBanner(vm: ParentViewModel, modifier: Modifier = Modifier) {
    val state = applyState(vm)
    if (state == ApplyState.None) return
    val d = vm.device
    val (color, icon, title, body) = when (state) {
        ApplyState.Applied -> Quad(gColors.success, GIcon.Check, "Applied on " + (d?.name ?: "the child's phone"),
            "Confirmed " + Formats.relative(d?.ack?.appliedAt ?: 0L) + ". The phone is enforcing your latest rules.")
        ApplyState.AppliedLimited -> Quad(gColors.warning, GIcon.Warning, "Received, but can't fully enforce",
            "Missing on the child's phone: " + (d?.ack?.missing?.joinToString { permissionLabel(it) }?.ifBlank { "a permission" } ?: "a permission") + ".")
        ApplyState.Offline -> Quad(gColors.warning, GIcon.Wifi, "Saved. The child's phone is offline",
            "Your rules apply by themselves as soon as it reconnects.")
        else -> Quad(gColors.info, GIcon.Refresh, "Sent. Waiting for the child's phone",
            "This turns green only after the phone confirms it is enforcing the new rules.")
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.13f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GIconView(icon, color, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Quad(val color: Color, val icon: GIcon, val title: String, val body: String)

fun permissionLabel(id: String): String = when (id) {
    "usage" -> "Usage access"
    "overlay" -> "Display over other apps"
    "battery" -> "Battery optimisation"
    "notifications" -> "Notifications"
    "admin" -> "Uninstall protection"
    "vpn" -> "Website filter"
    else -> id
}
