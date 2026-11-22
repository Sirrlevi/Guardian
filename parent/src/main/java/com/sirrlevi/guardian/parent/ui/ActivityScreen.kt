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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.DomainRules
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.AppInfo
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.SessionRow
import com.sirrlevi.guardian.parent.WebVisit
import java.util.Calendar

/** "What did they do, and when?" – a day picker, then every app session or every site visited. */
@Composable
fun ActivityScreen(vm: ParentViewModel, padding: PaddingValues, onOpenApp: (AppInfo) -> Unit) {
    val device = vm.device
    var section by remember { mutableIntStateOf(0) }
    val todayKey = remember { DayClock.of(System.currentTimeMillis()).dayKey }
    val days = remember(todayKey) { Formats.lastDays(7).reversed() }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Activity", style = MaterialTheme.typography.headlineMedium)
                Text(Formats.longDay(vm.selectedDay), style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(days, key = { it }) { key ->
                    val on = key == vm.selectedDay
                    val total = device?.daily?.firstOrNull { it.dayKey == key }?.totalSec
                    Column(
                        Modifier.width(64.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary else gColors.card)
                            .clickable { vm.selectDay(key) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(Formats.weekdayShort(key), style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else gColors.muted)
                        Text(key.takeLast(2).trimStart('0'), style = MaterialTheme.typography.titleMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        Text(if (key == todayKey) "Today" else if (total != null && total > 0) Durations.compact(total) else "–",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (on) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else gColors.muted, maxLines = 1)
                    }
                }
            }
        }
        item { Segmented(listOf("Apps", "Websites"), section, { section = it }) }

        if (device == null) {
            item { GCard { EmptyState(GIcon.Phone, "No phone connected", "Pair your child's phone from Settings to see what they do.") } }
            return@LazyColumn
        }

        if (section == 0) appsSection(vm, device, todayKey, onOpenApp) else websitesSection(vm, device)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appsSection(
    vm: ParentViewModel, d: ChildDevice, todayKey: String, onOpenApp: (AppInfo) -> Unit
) {
    val day = vm.selectedDay
    val isToday = day == todayKey
    val sessions = (d.sessions[day] ?: emptyList()).filter { it.seconds >= 3 }.sortedByDescending { it.start }

    val liveNow = d.live?.takeIf { isToday && it.pkg.isNotBlank() && it.screenOn && d.isOnline }
    if (liveNow != null) {
        item {
            val live = liveNow
            GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconView(live.pkg, d.nameOf(live.pkg), vm.icons, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Using now", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
                        Text(d.nameOf(live.pkg), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    LiveDot(gColors.success)
                }
            }
        }
    }

    if (sessions.isEmpty()) {
        item {
            GCard {
                EmptyState(
                    GIcon.Activity,
                    if (d.hasMonitor) "No app use recorded" else "Waiting for live tracking",
                    if (d.hasMonitor) "Nothing was opened on this day, or the phone was offline." else "Update Guardian Child on the phone and grant Usage Access to record every app session."
                )
            }
        }
        return
    }

    val perApp = sessions.groupBy { it.pkg }.mapValues { (_, rows) -> rows.sumOf { it.seconds } }
    val total = perApp.values.sum()
    val topPkg = perApp.maxByOrNull { it.value }?.key
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Screen time", Durations.compact(total), Modifier.weight(1f))
            StatTile("Sessions", sessions.size.toString(), Modifier.weight(1f))
            StatTile("Most used", topPkg?.let { d.nameOf(it) } ?: "–", Modifier.weight(1.3f), sub = topPkg?.let { Durations.compact(perApp[it] ?: 0) })
        }
    }

    val byHour = sessions.groupBy { hourOf(it.start) }.toSortedMap(compareByDescending<Int> { it })
    byHour.forEach { (hour, rows) ->
        item(key = "h$day$hour") {
            Column {
                SectionTitle(Formats.hourLabel(hour) + " – " + Formats.hourLabel((hour + 1) % 24),
                    subtitle = Durations.compact(rows.sumOf { it.seconds }) + " in " + rows.size + " session" + (if (rows.size == 1) "" else "s"))
                GCard(padding = 4.dp) {
                    rows.forEachIndexed { i, s -> SessionLine(vm, d, s, i > 0, onOpenApp) }
                }
            }
        }
    }
}

private fun hourOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

@Composable
private fun SessionLine(vm: ParentViewModel, d: ChildDevice, s: SessionRow, divider: Boolean, onOpenApp: (AppInfo) -> Unit) {
    if (divider) HorizontalDivider(color = gColors.stroke)
    val app = d.app(s.pkg)
    Row(
        Modifier.fillMaxWidth().then(if (app != null) Modifier.clickable { onOpenApp(app) } else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconView(s.pkg, d.nameOf(s.pkg), vm.icons, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.nameOf(s.pkg), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(Formats.timeOfDay(s.start) + " – " + Formats.timeOfDay(s.end), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Text(Durations.compact(s.seconds), style = MaterialTheme.typography.labelLarge)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.websitesSection(vm: ParentViewModel, d: ChildDevice) {
    val day = vm.selectedDay
    val visits = d.visits.filter { dayKeyOf(it.startedAt) == day }.sortedByDescending { it.startedAt }
    val blockedDomains = vm.policy.blockedDomains

    item {
        GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
            Row(verticalAlignment = Alignment.Top) {
                GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Guardian can see sites opened in Guardian Browser on the child's phone. Android doesn't let any app read other browsers' history, so for full coverage keep other browsers disabled or blocked in the Apps tab.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (visits.isEmpty()) {
        item {
            GCard { EmptyState(GIcon.Globe, "No sites visited", "Pages opened in Guardian Browser on this day show up here with time and duration.") }
        }
        return
    }

    val totalMs = visits.sumOf { it.durationMs }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Pages", visits.size.toString(), Modifier.weight(1f))
            StatTile("Time browsing", Durations.compact((totalMs / 1000L).toInt()), Modifier.weight(1f))
        }
    }
    item {
        GCard(padding = 4.dp) {
            visits.take(80).forEachIndexed { i, v -> VisitLine(vm, v, i > 0, blockedDomains) }
        }
    }
}

private fun dayKeyOf(ms: Long): String = DayClock.of(ms).dayKey

@Composable
private fun VisitLine(vm: ParentViewModel, v: WebVisit, divider: Boolean, blockedDomains: List<String>) {
    if (divider) HorizontalDivider(color = gColors.stroke)
    val host = DomainRules.hostOf(v.url) ?: v.url
    val isBlocked = v.blocked || DomainRules.isBlockedHost(host, blockedDomains)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(gColors.info.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            GIconView(GIcon.Globe, gColors.info, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title.ifBlank { host }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(host, style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1)
            Text(Formats.timeOfDay(v.startedAt) + (if (v.durationMs >= 1000L) " · " + Durations.compact((v.durationMs / 1000L).toInt()) else ""),
                style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
        Spacer(Modifier.width(8.dp))
        if (isBlocked) Pill(if (v.blocked) "Blocked" else "Blocked site", gColors.danger)
        else SoftButton("Block", { vm.setWebRules(blockedDomains + host, vm.policy.blockedUrls) }, color = gColors.danger)
    }
}
