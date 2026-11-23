package com.sirrlevi.guardian.parent.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.CommAppGroup
import com.sirrlevi.guardian.core.CommConversation
import com.sirrlevi.guardian.core.CommDirection
import com.sirrlevi.guardian.core.CommFilter
import com.sirrlevi.guardian.core.CommQuery
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.Connection
import com.sirrlevi.guardian.parent.ParentViewModel

private val GROUPS = listOf<CommAppGroup?>(null, CommAppGroup.WHATSAPP, CommAppGroup.INSTAGRAM, CommAppGroup.OTHER)
private val GROUP_LABELS = listOf("All", "WhatsApp", "Instagram", "Other")
private const val PAGE = 40

/**
 * Notification previews from the child's phone. It shows only what each notification displayed; it is
 * not a copy of the chats, and the screen says so instead of implying otherwise.
 */
@Composable
fun CommunicationScreen(vm: ParentViewModel, padding: PaddingValues) {
    val device = vm.device
    var group by rememberSaveable { mutableIntStateOf(0) }
    var dayFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sender by rememberSaveable { mutableStateOf<String?>(null) }
    var timeline by rememberSaveable { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(PAGE) }
    val expanded = remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(device?.id, device?.commsAt) { vm.refreshCommsIfStale() }

    val records = vm.comms
    val todayKey = remember { DayClock.of(System.currentTimeMillis()).dayKey }
    val range = dayFilter?.let { dayRange(it) } ?: (0L to Long.MAX_VALUE)
    val filter = CommFilter(group = GROUPS[group], fromMs = range.first, toMs = range.second, query = query, sender = sender)
    val shown = remember(records, filter) { CommQuery.apply(records, filter) }
    val senders = remember(records) { CommQuery.senders(records).take(8) }
    val daysWithData = remember(records) { records.map { DayClock.of(it.timeMs).dayKey }.distinct().sortedDescending() }
    val filtersOn = group != 0 || dayFilter != null || query.isNotBlank() || sender != null

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Messages", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (device != null) "Notification previews from ${device.name}" else "Notification previews",
                    style = MaterialTheme.typography.bodyMedium, color = gColors.muted
                )
            }
        }

        if (device == null) {
            item { GCard { EmptyState(GIcon.Phone, "No phone connected", "Pair your child's phone from More → Settings to see message previews.") } }
            return@LazyColumn
        }

        item { HonestyNote() }
        item { StatusCard(device, vm.connection == Connection.Offline) }

        item { Segmented(GROUP_LABELS, group, { group = it; limit = PAGE }) }

        if (daysWithData.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ChipLite("All days", dayFilter == null) { dayFilter = null; limit = PAGE } }
                    items(daysWithData, key = { it }) { key ->
                        ChipLite(Formats.dayLabel(key, todayKey), dayFilter == key) { dayFilter = key; limit = PAGE }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it; limit = PAGE },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search sender, chat or text") },
                leadingIcon = { GIconView(GIcon.Search, gColors.muted, size = 20.dp) },
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (senders.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ChipLite("Everyone", sender == null) { sender = null; limit = PAGE } }
                    items(senders, key = { it.first }) { (name, count) ->
                        ChipLite("$name · $count", sender.equals(name, ignoreCase = true)) { sender = name; limit = PAGE }
                    }
                }
            }
        }

        item { Segmented(listOf("Conversations", "Timeline"), timeline, { timeline = it }) }

        val error = vm.commsError
        if (error != null) {
            item {
                GCard(container = gColors.warning.copy(alpha = 0.12f)) {
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    SoftButton("Try again", { vm.loadComms(silent = false) }, color = gColors.warning, icon = GIcon.Refresh)
                }
            }
        }

        when {
            vm.commsLoading && !vm.commsLoaded -> items(4) { SkeletonRow() }

            records.isEmpty() -> item {
                GCard {
                    if (error != null && !vm.commsLoaded) {
                        EmptyState(GIcon.Warning, "Couldn't load previews", "Check your connection and try again.")
                    } else {
                        EmptyState(
                            GIcon.Chat, "No message previews yet",
                            if (device.info?.permNotifAccess == true)
                                "When ${device.name} gets a WhatsApp, Instagram or other chat notification, its preview shows up here."
                            else "Turn on message previews in Guardian Child on ${device.name}'s phone (Protection tab) and they will appear here."
                        )
                    }
                }
            }

            shown.isEmpty() -> item {
                GCard {
                    EmptyState(
                        GIcon.Search, "Nothing matches", "No previews fit these filters.",
                        action = { SoftButton("Clear filters", { group = 0; dayFilter = null; query = ""; sender = null }) }
                    )
                }
            }

            else -> {
                item {
                    SectionTitle(
                        "Notification preview",
                        subtitle = "${shown.size} item${if (shown.size == 1) "" else "s"}" + if (filtersOn) " match your filters" else " in the last ${vm.commsDays} days",
                        trailing = { if (vm.commsLoading) Pill("Updating…", gColors.info) }
                    )
                }
                if (timeline == 0) {
                    val conversations = CommQuery.conversations(shown)
                    items(conversations.take(limit), key = { it.key }) { c ->
                        ConversationCard(vm, c, c.key in expanded.value) {
                            expanded.value = if (c.key in expanded.value) expanded.value - c.key else expanded.value + c.key
                        }
                    }
                    if (conversations.size > limit) item { SoftButton("Show more chats", { limit += PAGE }, Modifier.fillMaxWidth()) }
                } else {
                    val newestFirst = shown.asReversed()
                    var lastDay = ""
                    val rows = newestFirst.take(limit).map { r ->
                        val day = DayClock.of(r.timeMs).dayKey
                        val header = day != lastDay
                        lastDay = day
                        r to header
                    }
                    items(rows, key = { it.first.id }) { (r, header) ->
                        Column {
                            if (header) Text(
                                Formats.dayLabel(DayClock.of(r.timeMs).dayKey, todayKey),
                                style = MaterialTheme.typography.labelLarge, color = gColors.muted, modifier = Modifier.padding(bottom = 6.dp, top = 4.dp)
                            )
                            GCard(padding = 12.dp) { MessageLine(vm, r, showChat = true) }
                        }
                    }
                    if (newestFirst.size > limit) item { SoftButton("Show more", { limit += PAGE }, Modifier.fillMaxWidth()) }
                }
                if (vm.commsDays < 28) {
                    item {
                        SoftButton(
                            if (vm.commsLoading) "Loading…" else "Load earlier days", { vm.loadEarlierComms() },
                            Modifier.fillMaxWidth(), enabled = !vm.commsLoading, icon = GIcon.Refresh
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HonestyNote() {
    GCard(container = gColors.info.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.Top) {
            GIconView(GIcon.Shield, gColors.info, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Previews, not full chats", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "You see only what each notification showed on the phone. Apps can shorten or hide message text, so some messages may be missing or say \"Message content unavailable\". Replies sent without a notification are not shown.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
        }
    }
}

@Composable
private fun StatusCard(d: ChildDevice, offline: Boolean) {
    val info = d.info
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Last synchronized: " + if (d.commsAt > 0L) Formats.relative(d.commsAt) else "never", style = MaterialTheme.typography.bodyMedium)
                if (d.commsAt > 0L) Text(Formats.dateTime(d.commsAt), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
            when {
                info == null -> Pill("Waiting for phone", gColors.muted)
                !info.notifAccessKnown -> Pill("Update needed", gColors.warning)
                info.permNotifAccess -> Pill("Access on", gColors.success)
                else -> Pill("Access off", gColors.warning)
            }
        }
        if (offline) {
            Spacer(Modifier.height(8.dp))
            Text("You are offline. Showing the last previews loaded.", style = MaterialTheme.typography.bodySmall, color = gColors.warning)
        }
        when {
            info == null -> Unit
            !info.notifAccessKnown -> Hint("Update Guardian Child on this phone to turn on message previews.")
            !info.permNotifAccess -> {
                Spacer(Modifier.height(10.dp))
                Text("Notification access disabled", style = MaterialTheme.typography.titleSmall, color = gColors.warning)
                Text(
                    "Message previews are switched off on ${d.name}'s phone, so nothing new is being collected. Open Guardian Child there, go to Protection and turn on \"Message previews\". Android always shows this setting and the child can turn it off.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
}

@Composable
private fun ConversationCard(vm: ParentViewModel, c: CommConversation, open: Boolean, toggle: () -> Unit) {
    GCard(onClick = toggle, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconView(c.pkg, c.app, vm.icons, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.isGroup) { Spacer(Modifier.width(6.dp)); Pill("Group", gColors.info) }
                }
                Text(
                    "${c.app} · ${c.items.size} preview${if (c.items.size == 1) "" else "s"} · ${Formats.relative(c.lastMs)}",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(if (open) "Hide" else "Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        val last = c.items.last()
        if (!open) {
            Spacer(Modifier.height(8.dp))
            Text(
                last.text ?: "Message content unavailable",
                style = MaterialTheme.typography.bodyMedium, color = if (last.text == null) gColors.muted else MaterialTheme.colorScheme.onSurface,
                fontStyle = if (last.text == null) FontStyle.Italic else FontStyle.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(Modifier.height(6.dp))
            val items = c.items.asReversed().take(30)
            items.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = gColors.stroke)
                Box(Modifier.padding(vertical = 8.dp)) { MessageLine(vm, r, showChat = false) }
            }
            if (c.items.size > items.size) Text("Showing the latest ${items.size} of ${c.items.size}.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
    }
}

@Composable
private fun MessageLine(vm: ParentViewModel, r: CommRecord, showChat: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showChat) {
                AppIconView(r.pkg, r.app, vm.icons, size = 32.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(r.sender ?: "Unknown sender", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showChat) {
                    val chat = r.conversation
                    Text(
                        if (chat != null && !chat.equals(r.sender, ignoreCase = true)) "${r.app} · $chat" else r.app,
                        style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(Formats.timeOfDay(r.timeMs), style = MaterialTheme.typography.labelMedium, color = gColors.muted)
        }
        Spacer(Modifier.height(6.dp))
        val text = r.text
        Text(
            text ?: "Message content unavailable",
            style = MaterialTheme.typography.bodyMedium,
            color = if (text == null) gColors.muted else MaterialTheme.colorScheme.onSurface,
            fontStyle = if (text == null) FontStyle.Italic else FontStyle.Normal
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (r.direction) {
                CommDirection.INCOMING -> Pill("Incoming", gColors.info)
                CommDirection.OUTGOING -> Pill("Outgoing", gColors.success)
                CommDirection.UNKNOWN -> Pill("Direction unknown", gColors.muted)
            }
            if (r.isCall) Pill("Call", gColors.warning)
            if (r.isGroup == true) Pill("Group", gColors.muted)
        }
    }
}

@Composable
private fun ChipLite(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SkeletonRow() {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val a = pulse.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    GCard(padding = 12.dp, modifier = Modifier.alpha(a.value)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(gColors.stroke))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(gColors.stroke))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(gColors.stroke))
            }
        }
    }
}

/** Start and end (inclusive) of a day key in the phone's time zone. */
private fun dayRange(key: String): Pair<Long, Long>? {
    val start = Formats.parseDayKey(key) ?: return null
    val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }
    return start.timeInMillis to end.timeInMillis - 1L
}
