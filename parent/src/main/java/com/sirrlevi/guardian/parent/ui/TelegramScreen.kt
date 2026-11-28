package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.Delivery
import com.sirrlevi.guardian.core.DeliveryState
import com.sirrlevi.guardian.core.ReportCategory
import com.sirrlevi.guardian.core.TelegramPolicy
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.TelegramModel
import com.sirrlevi.guardian.parent.TelegramScreenTexts
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The words on the Telegram screen that aren't about the model's own messages. Tests use them too. */
object TelegramUiTexts {
    const val SAVE = "Save"
    const val REPLACE = "Replace"
    const val REMOVE = "Remove token"
    const val TEST = "Test connection"
    const val TEST_NOTE = "Sends one real test message to your chat. It counts as connected only if Telegram confirms it."
    const val ENABLE = "Send reports to Telegram"
    const val SEND_NOW = "Send now"
    const val RETRY_ALL = "Retry failed"
    const val CLEAR = "Clear history"
    const val RETRY = "Retry"
    const val SEND_AGAIN = "Send again"
    const val UNCONFIRMED_NOTE = "The phone can't know whether Telegram got this one. It may already be in your chat, and sending again could put a second copy there."
    const val NOTHING = "Nothing has been reported yet. Reports only cover what happens after you turn them on."
    const val TOKEN_HINT = "Bot token from @BotFather"
    const val CHAT_HINT = "Chat ID or @channelname"
    const val CHANGE_NOTE = "To change the chat, enter the token again: it is never shown or read back."
    const val HISTORY_NOTE = "Clearing history doesn't send anything, and what was cleared is not reported again."
}

private fun whenText(ts: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ts))

/** Label and title of a report, from its own text, for the delivery list. */
internal fun deliveryPreview(text: String): String =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1).take(2).joinToString(" · ").take(140)

/**
 * Optional reports to the parent's own Telegram chat. Nothing is sent until a bot token and chat are saved, tested and reports are switched on,
 * and the screen shows plainly what is waiting, what Telegram confirmed and what failed.
 */
@Composable
fun TelegramScreen(vm: ParentViewModel, padding: PaddingValues) {
    val model = vm.telegram
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!model.loaded) model.load() }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Telegram reports", style = MaterialTheme.typography.headlineMedium)
                Text("Optional, off until you set it up", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        item {
            GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Row(verticalAlignment = Alignment.Top) {
                    GIconView(GIcon.Bell, MaterialTheme.colorScheme.primary, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(TelegramScreenTexts.INTRO_TITLE, style = MaterialTheme.typography.titleSmall)
                        Text(TelegramScreenTexts.INTRO, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                }
            }
        }

        val notice = model.notice
        if (notice != null) {
            item {
                GCard(container = (if (model.noticeIsProblem) gColors.warning else gColors.success).copy(alpha = 0.12f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.clearNotice() }) { Text("OK") }
                    }
                }
            }
        }

        item { CredentialsCard(model) { block -> scope.launch { block() } } }
        item { ConnectionCard(model) { block -> scope.launch { block() } } }
        item { ReportsCard(model) { block -> scope.launch { block() } } }
        item { DeliveryCard(model) { block -> scope.launch { block() } } }

        item {
            GCard {
                Text(TelegramScreenTexts.NOT_OFFERED_TITLE, style = MaterialTheme.typography.titleSmall)
                Text(TelegramScreenTexts.NOT_OFFERED, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item {
            GCard {
                Text(TelegramScreenTexts.LIMITS_TITLE, style = MaterialTheme.typography.titleSmall)
                Text(TelegramScreenTexts.LIMITS, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun CredentialsCard(model: TelegramModel, launch: (suspend () -> Unit) -> Unit) {
    var token by remember { mutableStateOf("") }
    var chat by remember(model.chatId) { mutableStateOf(model.chatId) }
    GCard {
        Text("Bot token and chat", style = MaterialTheme.typography.titleMedium)
        Text(
            if (model.hasCredentials) TelegramScreenTexts.HAS_TOKEN else TelegramScreenTexts.NO_TOKEN,
            style = MaterialTheme.typography.bodySmall, color = gColors.muted
        )
        Spacer(Modifier.padding(top = 6.dp))
        OutlinedTextField(
            value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !model.busy,
            placeholder = { Text(TelegramUiTexts.TOKEN_HINT) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        )
        Spacer(Modifier.padding(top = 6.dp))
        OutlinedTextField(
            value = chat, onValueChange = { chat = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !model.busy,
            placeholder = { Text(TelegramUiTexts.CHAT_HINT) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)
        )
        if (model.hasCredentials) Text(TelegramUiTexts.CHANGE_NOTE, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (model.hasCredentials) TextButton(onClick = { launch { model.removeCredentials() } }, enabled = !model.busy) { Text(TelegramUiTexts.REMOVE) }
            TextButton(
                onClick = {
                    val t = token; val c = chat
                    launch { if (model.saveCredentials(t, c)) token = "" }
                },
                enabled = !model.busy && token.isNotBlank() && chat.isNotBlank()
            ) { Text(if (model.hasCredentials) TelegramUiTexts.REPLACE else TelegramUiTexts.SAVE) }
        }
    }
}

@Composable
private fun ConnectionCard(model: TelegramModel, launch: (suspend () -> Unit) -> Unit) {
    val c = model.connection
    GCard {
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        when {
            !model.hasCredentials -> Text(TelegramScreenTexts.NEED_CREDENTIALS, style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            c == null -> Text(TelegramScreenTexts.NOT_TESTED, style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (c.ok) "Connected" else "Not connected", if (c.ok) gColors.success else gColors.warning)
                    Spacer(Modifier.weight(1f))
                    Text("Tested ${whenText(c.testedAt)}", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                }
                Text(c.message, style = MaterialTheme.typography.bodyMedium, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Text(TelegramUiTexts.TEST_NOTE, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { launch { model.testConnection() } }, enabled = model.hasCredentials && !model.busy) { Text(TelegramUiTexts.TEST) }
        }
    }
}

@Composable
private fun ReportsCard(model: TelegramModel, launch: (suspend () -> Unit) -> Unit) {
    val s = model.settings
    GCard {
        Text("What to send", style = MaterialTheme.typography.titleMedium)
        TelegramToggle(TelegramUiTexts.ENABLE, null, s.enabled, !model.busy) { on -> launch { model.setEnabled(on) } }
        ReportCategory.values().forEach { category ->
            TelegramToggle(category.title, category.about, category in s.categories, !model.busy) { on -> launch { model.setCategory(category, on) } }
        }
        Text("Keep delivery history for", style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TelegramPolicy.RETENTION_DAYS.forEach { days ->
                FilterChip(
                    selected = s.retentionDays == days, enabled = !model.busy,
                    onClick = { launch { model.setRetention(days) } },
                    label = { Text(if (days == 1) "1 day" else "$days days") }
                )
            }
        }
    }
}

@Composable
private fun TelegramToggle(title: String, about: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (about != null) Text(about, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun DeliveryCard(model: TelegramModel, launch: (suspend () -> Unit) -> Unit) {
    val on = model.settings.enabled
    GCard {
        Text("Deliveries", style = MaterialTheme.typography.titleMedium)
        if (model.items.isEmpty()) {
            Text(TelegramUiTexts.NOTHING, style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
        } else {
            Text(
                "${model.sentCount} sent by Telegram · ${model.waitingCount} waiting · ${model.problemCount} need a look",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val note = model.passNote
        if (note != null && on) Text(note, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
        if (model.items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (on && model.waitingCount > 0) TextButton(onClick = { launch { model.sendNow() } }, enabled = !model.busy) { Text(TelegramUiTexts.SEND_NOW) }
                if (on && model.items.any { it.state == DeliveryState.FAILED }) TextButton(onClick = { launch { model.retryAllNotSent() } }, enabled = !model.busy) { Text(TelegramUiTexts.RETRY_ALL) }
                TextButton(onClick = { launch { model.clearHistory() } }, enabled = !model.busy) { Text(TelegramUiTexts.CLEAR) }
            }
            Text(TelegramUiTexts.HISTORY_NOTE, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            model.items.take(15).forEach { d -> DeliveryRow(d, on, !model.busy) { launch { model.retry(d.key) } } }
        }
    }
}

@Composable
private fun DeliveryRow(d: Delivery, canRetry: Boolean, enabled: Boolean, onRetry: () -> Unit) {
    val color = when (d.state) {
        DeliveryState.SENT -> gColors.success
        DeliveryState.FAILED, DeliveryState.UNCONFIRMED -> gColors.warning
        else -> gColors.muted
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(d.state.label, color)
            Text("  " + d.category.title, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            Spacer(Modifier.weight(1f))
            Text(whenText(if (d.state == DeliveryState.SENT && d.sentAt > 0) d.sentAt else d.createdAt), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Text(deliveryPreview(d.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        if (d.state == DeliveryState.SENT && d.messageId > 0) Text("Telegram message ${d.messageId}", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        val err = d.lastError
        if (err != null && d.state != DeliveryState.SENT) Text(err, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        if (d.state == DeliveryState.RETRYING) Text("Next try ${whenText(d.nextAttemptAt)}", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        if (d.state == DeliveryState.UNCONFIRMED) Text(TelegramUiTexts.UNCONFIRMED_NOTE, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        if (canRetry && (d.state == DeliveryState.FAILED || d.state == DeliveryState.UNCONFIRMED)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, enabled = enabled) { Text(if (d.state == DeliveryState.UNCONFIRMED) TelegramUiTexts.SEND_AGAIN else TelegramUiTexts.RETRY) }
            }
        }
    }
}
