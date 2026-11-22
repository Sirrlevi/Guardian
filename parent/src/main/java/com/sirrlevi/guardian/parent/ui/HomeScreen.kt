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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.AppCategories
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.AppInfo
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.Tab
import kotlinx.coroutines.delay
import java.util.Calendar

/** A clock that ticks once a second, so "using YouTube for 12:04" counts up live. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

fun todayTotal(d: ChildDevice): Int = maxOf(d.todaySec.values.sum(), d.live?.totalSec ?: 0)

@Composable
fun HomeScreen(vm: ParentViewModel, padding: PaddingValues, onOpenApp: (AppInfo) -> Unit, goTab: (Tab) -> Unit) {
    val device = vm.device
    val now = rememberNow()
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                Text("Here is how " + (device?.name ?: "your child's phone") + " is doing.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        if (vm.devices.size > 1) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.devices, key = { it.id }) { d ->
                        FilterChip(selected = d.id == device?.id, onClick = { vm.selectDevice(d.id) }, label = { Text(d.name) })
                    }
                }
            }
        }
        if (device == null) {
            item {
                if (!vm.loadedOnce) {
                    GCard { Text("Loading your family data…", color = gColors.muted) }
                } else {
                    GCard {
                        EmptyState(GIcon.Phone, "Connect your child's phone",
                            "Install Guardian Child on their phone, then pair it with a one-time code. It takes about a minute.",
                            action = { PrimaryButton("Get a pairing code", { goTab(Tab.Settings) }) })
                    }
                }
            }
            return@LazyColumn
        }

        item { DeviceHero(vm, device, now) }
        item { ApplyBanner(vm) }

        val pending = device.requests.filter { it.status == "pending" && now - it.ts < 24L * 3600_000L }
        items(pending, key = { it.id }) { r -> RequestCard(vm, r) }

        item { PauseCard(vm, now) }
        item { TodayCard(vm, device) }
        item { TopAppsCard(vm, device, onOpenApp) }

        val issues = attentionItems(device)
        if (issues.isNotEmpty()) {
            item {
                GCard(container = gColors.warning.copy(alpha = 0.10f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIconView(GIcon.Warning, gColors.warning, size = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Needs attention", style = MaterialTheme.typography.titleSmall, color = gColors.warning)
                    }
                    Spacer(Modifier.height(8.dp))
                    issues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                    Spacer(Modifier.height(10.dp))
                    SoftButton("See phone health", { goTab(Tab.Settings) }, color = gColors.warning)
                }
            }
        }
    }
}

private fun attentionItems(d: ChildDevice): List<String> {
    val info = d.info
    val out = ArrayList<String>()
    if (info == null) {
        out.add("This phone is running an older Guardian Child. Update it to get live app tracking, limits and the blocked screen.")
        return out
    }
    if (!info.permUsage) out.add("Usage access is off, so nothing can be tracked or limited.")
    if (!info.permOverlay && !info.permOwner) out.add("\"Display over other apps\" is off, so blocked apps can't be covered.")
    if (!info.autoTime) out.add("Automatic date & time is off. A child could change the clock to dodge limits.")
    if (!info.permBattery) out.add("Battery optimisation may stop Guardian in the background.")
    return out
}

@Composable
private fun DeviceHero(vm: ParentViewModel, d: ChildDevice, now: Long) {
    GCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                GIconView(GIcon.Phone, MaterialTheme.colorScheme.primary, size = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(if (d.isOnline) "Online now" else "Last seen " + Formats.relative(d.presenceTs(), now),
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
            if (d.isOnline) {
                Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(gColors.success); Text("Live", color = gColors.success, style = MaterialTheme.typography.labelLarge) }
            } else Pill("Offline", gColors.warning)
        }
        val info = d.info
        if (info != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.battery >= 0) Pill((if (info.charging) "⚡ " else "") + info.battery + "%", if (info.battery <= 20 && !info.charging) gColors.danger else gColors.success)
                Pill(when (info.network) { "wifi" -> "Wi-Fi"; "cellular" -> "Mobile data"; "offline" -> "No network"; else -> info.network.replaceFirstChar { it.uppercase() } }, gColors.info)
                Pill(if (info.screenOn) "Screen on" else "Screen off", gColors.muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        LivePanel(vm, d, now)
    }
}

@Composable
private fun LivePanel(vm: ParentViewModel, d: ChildDevice, now: Long) {
    val live = d.live
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
    ) {
        if (live == null || (!d.hasMonitor && live.pkg.isBlank())) {
            Text("Live view", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
            Text(if (d.hasMonitor) "Waiting for the first update…" else "Live tracking starts once Guardian Child is updated and has Usage Access.",
                style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val fresh = d.isOnline
        val name = live.name.ifBlank { d.nameOf(live.pkg) }
        Text(if (fresh) "Right now" else "Last seen doing", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
        Spacer(Modifier.height(8.dp))
        when {
            !live.screenOn -> Row(verticalAlignment = Alignment.CenterVertically) {
                GIconView(GIcon.Moon, gColors.muted, size = 24.dp); Spacer(Modifier.width(10.dp))
                Text("Screen is off", style = MaterialTheme.typography.titleMedium)
            }
            live.pkg.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                GIconView(GIcon.Home, MaterialTheme.colorScheme.primary, size = 24.dp); Spacer(Modifier.width(10.dp))
                Text("On the home screen", style = MaterialTheme.typography.titleMedium)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconView(live.pkg, name, vm.icons, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (live.blocked) "$name · blocked" else name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val elapsed = if (live.sinceMs > 0L) ((now - live.sinceMs) / 1000L).toInt().coerceAtLeast(0) else 0
                        if (live.blocked) {
                            Text("The \"time's up\" screen is showing", style = MaterialTheme.typography.bodySmall, color = gColors.danger)
                        } else if (fresh) {
                            Text("Open for " + Durations.clock(elapsed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(Formats.timeOfDay(live.ts), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                        }
                    }
                    if (live.blocked) GIconView(GIcon.Block, gColors.danger, size = 24.dp)
                }
                val usedToday = d.todaySec[live.pkg] ?: live.usedSec
                if (usedToday > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Today on $name: " + Durations.compact(maxOf(usedToday, live.usedSec)), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                }
            }
        }
    }
}

@Composable
private fun RequestCard(vm: ParentViewModel, r: com.sirrlevi.guardian.parent.MoreTimeRequest) {
    GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconView(r.pkg, r.name, vm.icons, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(when (r.reason) {
                    "DAILY_LIMIT" -> "Asked for more time on ${r.name}"
                    "PARENT_BLOCK" -> "Asked to unblock ${r.name}"
                    else -> "Asked to use ${r.name}"
                }, style = MaterialTheme.typography.titleSmall)
                Text(Formats.relative(r.ts), style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Not now", { vm.respond(r, false) }, Modifier.weight(1f), color = gColors.muted)
            when (r.reason) {
                "DAILY_LIMIT" -> PrimaryButton("Give 15 minutes", { vm.respond(r, true) }, Modifier.weight(1.4f))
                "PARENT_BLOCK" -> PrimaryButton("Unblock", { vm.respond(r, true) }, Modifier.weight(1.4f))
                else -> PrimaryButton("Dismiss", { vm.respond(r, false) }, Modifier.weight(1.4f))
            }
        }
    }
}

@Composable
private fun PauseCard(vm: ParentViewModel, now: Long) {
    val until = vm.policy.pauseUntil
    val paused = until > now
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(gColors.info.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                GIconView(GIcon.Pause, gColors.info, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Apps are paused" else "Pause all apps", style = MaterialTheme.typography.titleSmall)
                Text(if (paused) "Resumes in " + Durations.compact(((until - now) / 1000L).toInt()) else "Dinner, homework, bedtime — phone calls still work.",
                    style = MaterialTheme.typography.bodySmall, color = gColors.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (paused) {
            PrimaryButton("Resume now", { vm.pauseApps(0) }, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (m, label) ->
                    SoftButton(label, { vm.pauseApps(m) }, Modifier.weight(1f), color = gColors.info)
                }
            }
        }
    }
}

@Composable
private fun TodayCard(vm: ParentViewModel, d: ChildDevice) {
    val total = todayTotal(d)
    val yesterdayKey = Formats.lastDays(2)[0]
    val yesterday = d.daily.firstOrNull { it.dayKey == yesterdayKey }?.totalSec
    var selected by remember { mutableStateOf<Int?>(null) }
    GCard {
        Text("Screen time today", style = MaterialTheme.typography.labelMedium, color = gColors.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (total == 0) "0m" else Durations.compact(total), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.width(10.dp))
            if (yesterday != null && yesterday > 0) {
                val diff = total - yesterday
                val pct = Math.abs(diff) * 100 / yesterday
                Text((if (diff >= 0) "▲ " else "▼ ") + pct + "% vs yesterday", style = MaterialTheme.typography.labelMedium,
                    color = if (diff > 0) gColors.warning else gColors.success, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        val sel = selected
        Text(
            if (sel != null) Formats.hourLabel(sel) + " – " + Formats.hourLabel((sel + 1) % 24) + " · " + Durations.compact(d.hourlyToday.getOrElse(sel) { 0 })
            else "Tap a bar to see that hour",
            style = MaterialTheme.typography.bodySmall, color = gColors.muted
        )
        Spacer(Modifier.height(6.dp))
        BarChart(
            values = d.hourlyToday.map { it / 60f },
            labels = List(24) { h -> if (h % 6 == 0) Formats.hourLabel(h).replace(" ", "").lowercase() else "" },
            selected = sel, onSelect = { selected = it }
        )
        if (!d.hasMonitor || d.hourlyToday.all { it == 0 }) {
            Spacer(Modifier.height(8.dp))
            Text("The hourly chart fills in as your child uses the phone.", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }
    }
}

@Composable
private fun TopAppsCard(vm: ParentViewModel, d: ChildDevice, onOpenApp: (AppInfo) -> Unit) {
    val ranked = d.todaySec.filter { it.value > 0 }.toList().sortedByDescending { it.second }
    GCard {
        Text("Most used today", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        if (ranked.isEmpty()) {
            Text("No app use recorded yet today.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            return@GCard
        }
        val palette = gColors.chart
        val top = ranked.take(5)
        val rest = ranked.drop(5).sumOf { it.second }
        val slices = top.mapIndexed { i, (pkg, sec) -> Slice(d.nameOf(pkg), sec.toFloat(), palette[i % palette.size]) } +
            (if (rest > 0) listOf(Slice("Other", rest.toFloat(), gColors.muted)) else emptyList())
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(slices, diameter = 150.dp, thickness = 20.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Durations.compact(ranked.sumOf { it.second }), style = MaterialTheme.typography.titleMedium)
                    Text("total", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                slices.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(8.dp))
                        Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(Durations.compact(s.value.toInt()), style = MaterialTheme.typography.labelMedium, color = gColors.muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        top.forEachIndexed { i, (pkg, sec) ->
            val app = d.app(pkg)
            RankRow(
                leading = { AppIconView(pkg, d.nameOf(pkg), vm.icons, size = 38.dp) },
                title = d.nameOf(pkg), value = Durations.compact(sec), fraction = sec.toFloat() / top[0].second,
                color = palette[i % palette.size],
                modifier = if (app != null) Modifier.clickable { onOpenApp(app) } else Modifier
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

fun categoryOf(d: ChildDevice, pkg: String): String =
    d.app(pkg)?.category?.takeIf { it != "other" } ?: AppCategories.hintFor(pkg) ?: d.app(pkg)?.category ?: "other"
