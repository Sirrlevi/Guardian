package com.sirrlevi.guardian.child.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

/**
 * Shown while the parent is asking to see the screen. Nothing is shared until Allow is tapped here and then confirmed in
 * Android's own screen-capture dialog. It cannot be dismissed by accident: the child answers Allow or Not now.
 */
@Composable
fun CaptureRequestDialog(state: CaptureState, onAllow: () -> Unit, onDecline: () -> Unit) {
    if (state.status != CaptureStatus.AWAITING) return
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Share your screen?") },
        text = {
            Column {
                Text("Your parent asked to see your phone's screen.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "If you allow it, Android asks you to confirm, and Guardian shows a notification for as long as your screen is shared. " +
                        "Your parent can take screenshots or watch a small live preview, for up to ${CapturePolicy.SESSION_MAX_MS / 60_000L} minutes. You can stop at any time.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Screens that Android marks as protected stay hidden. Nothing is shared if you choose Not now.",
                    style = MaterialTheme.typography.bodySmall, color = kc.muted
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Not now") } }
    )
}

/** Always visible on the Protection screen so the child can see whether anything is being shared, and stop it. */
@Composable
fun CaptureStatusCard(state: CaptureState, onAllow: () -> Unit, onDecline: () -> Unit, onStop: () -> Unit) {
    KCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KEmoji(if (state.status == CaptureStatus.ACTIVE) "📺" else "🖥️", bg = (if (state.status == CaptureStatus.ACTIVE) kc.coral else kc.sky).copy(alpha = 0.16f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Screen sharing", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = kc.text)
                    Spacer(Modifier.width(8.dp))
                    when (state.status) {
                        CaptureStatus.ACTIVE -> KPill("Sharing now", kc.coral)
                        CaptureStatus.AWAITING -> KPill("Waiting for you", kc.sun)
                        else -> KPill("Off", kc.good)
                    }
                }
                Text(
                    when (state.status) {
                        CaptureStatus.ACTIVE ->
                            "Your parent can take screenshots" + (if (state.live) " and is watching a small live preview" else "") +
                                ". Sharing ends by itself at ${clock(state.expiresAt)}."
                        CaptureStatus.AWAITING -> "Your parent asked to see your screen. Nothing is shared until you allow it."
                        else -> "Nothing is being shared. Your parent can ask to see your screen, but it only starts if you allow it here and confirm in Android's own dialog."
                    },
                    style = MaterialTheme.typography.bodySmall, color = kc.muted
                )
            }
        }
        when (state.status) {
            CaptureStatus.ACTIVE -> {
                Spacer(Modifier.height(12.dp))
                KButton("Stop sharing", onStop, Modifier.fillMaxWidth(), soft = true, color = kc.coral)
            }
            CaptureStatus.AWAITING -> {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KButton("Not now", onDecline, Modifier.weight(1f), soft = true, color = kc.muted)
                    KButton("Allow", onAllow, Modifier.weight(1f))
                }
            }
            else -> Unit
        }
    }
}
