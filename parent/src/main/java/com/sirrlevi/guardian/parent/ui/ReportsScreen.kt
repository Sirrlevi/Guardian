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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.AppCategories
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.AppInfo
import com.sirrlevi.guardian.parent.ChildDevice
import com.sirrlevi.guardian.parent.ParentViewModel

private class RangeData(
    val keys: List<String>,
    val totals: List<Int>,
    val perApp: Map<String, Int>
)

/** Per-day app seconds, using the live "today" numbers for today so the report is never a few minutes behind. */
private fun appsOn(d: ChildDevice, key: String, todayKey: String): Map<String, Int> {
    val stored = d.daily.firstOrNull { it.dayKey == key }?.apps ?: emptyMap()
    if (key != todayKey) return stored
    return if (d.todaySec.values.sum() >= stored.values.sum()) d.todaySec else stored
}

private fun buildRange(d: ChildDevice, days: Int, todayKey: String): RangeData {
    val keys = Formats.lastDays(days)
    val perApp = HashMap<String, Int>()
    val totals = keys.map { key ->
        val apps = appsOn(d, key, todayKey)
        apps.forEach { (pkg, sec) -> perApp[pkg] = (perApp[pkg] ?: 0) + sec }
        val stored = d.daily.firstOrNull { it.dayKey == key }?.totalSec ?: 0
        maxOf(stored, apps.values.sum(), if (key == todayKey) todayTotal(d) else 0)
    }
    return RangeData(keys, totals, perApp)
}

@Composable
fun ReportsScreen(vm: ParentViewModel, padding: PaddingValues, onOpenApp: (AppInfo) -> Unit) {
    val device = vm.device
    var range by remember { mutableIntStateOf(1) }
    var picked by remember(range) { mutableStateOf<Int?>(null) }
    val todayKey = remember { DayClock.of(System.currentTimeMillis()).dayKey }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Reports", style = MaterialTheme.typography.headlineMedium)
                Text("Screen time, habits and trends", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        item { Segmented(listOf("Today", "7 days", "30 days"), range, { range = it }) }

        if (device == null) {
            item { GCard { EmptyState(GIcon.Chart, "Nothing to report yet", "Once a phone is paired and has been used, charts and trends appear here.") } }
            return@LazyColumn
        }

        val days = when (range) { 0 -> 1; 1 -> 7; else -> 30 }
        val data = buildRange(device, days, todayKey)
        val previous = if (range == 0) null else buildRange(device, days * 2, todayKey).totals.take(days).sum()
        val total = data.totals.sum()
        val activeDays = data.totals.count { it > 0 }

        item { Summary(range, total, activeDays, days, previous) }
        item { TrendCard(device, range, data, picked) { picked = it } }

        if (data.perApp.isNotEmpty()) {
            item { CategoryCard(device, data) }
            item { RankingCard(vm, device, data, onOpenApp) }
            val insights = insights(device, range, data)
            if (insights.isNotEmpty()) item { InsightsCard(insights) }
        } else {
            item { GCard { EmptyState(GIcon.Chart, "No app use in this period", "As soon as your child uses their phone, the breakdown shows up here.") } }
        }
    }
}

@Composable
private fun Summary(range: Int, total: Int, activeDays: Int, days: Int, previous: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(if (range == 0) "Today" else "Total", Durations.compact(total), Modifier.weight(1f))
        if (range != 0) {
            val avg = if (activeDays == 0) 0 else total / activeDays
            StatTile("Daily average", Durations.compact(avg), Modifier.weight(1f), sub = "$activeDays of $days days")
            if (previous != null && previous > 0) {
                val diff = total - previous
                val pct = Math.abs(diff) * 100 / previous
                StatTile("vs before", (if (diff >= 0) "▲ " else "▼ ") + pct + "%", Modifier.weight(1f),
                    valueColor = if (diff > 0) gColors.warning else gColors.success, sub = "previous $days days")
            }
        }
    }
}

@Composable
private fun TrendCard(d: ChildDevice, range: Int, data: RangeData, picked: Int?, onPick: (Int?) -> Unit) {
    GCard {
        when (range) {
            0 -> {
                Text("By the hour", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (picked != null) Formats.hourLabel(picked) + " – " + Formats.hourLabel((picked + 1) % 24) + " · " + Durations.compact(d.hourlyToday.getOrElse(picked) { 0 })
                    else "Tap a bar to see that hour", style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
                Spacer(Modifier.height(8.dp))
                BarChart(d.hourlyToday.map { it / 60f }, List(24) { h -> if (h % 6 == 0) Formats.hourLabel(h).replace(" ", "").lowercase() else "" },
                    selected = picked, onSelect = onPick)
            }
            1 -> {
                Text("Last 7 days", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (picked != null) Formats.longDay(data.keys[picked]) + " · " + Durations.compact(data.totals[picked])
                    else "Tap a bar to see that day", style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
                Spacer(Modifier.height(8.dp))
                BarChart(data.totals.map { it / 60f }, data.keys.map { Formats.weekdayShort(it) }, selected = picked, onSelect = onPick)
            }
            else -> {
                Text("Last 30 days", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (picked != null && picked in data.keys.indices) Formats.longDay(data.keys[picked]) + " · " + Durations.compact(data.totals[picked])
                    else "Drag across the chart to read any day", style = MaterialTheme.typography.bodySmall, color = gColors.muted
                )
                Spacer(Modifier.height(8.dp))
                AreaChart(data.totals.map { it / 60f }, selected = picked, onSelect = onPick)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Formats.dayLabel(data.keys.first(), data.keys.last()), style = MaterialTheme.typography.labelSmall, color = gColors.muted)
                    Text("Today", style = MaterialTheme.typography.labelSmall, color = gColors.muted)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(d: ChildDevice, data: RangeData) {
    val byCategory = HashMap<String, Int>()
    data.perApp.forEach { (pkg, sec) -> byCategory[categoryOf(d, pkg)] = (byCategory[categoryOf(d, pkg)] ?: 0) + sec }
    val sorted = byCategory.entries.sortedByDescending { it.value }
    val palette = gColors.chart
    val slices = sorted.take(6).mapIndexed { i, e -> Slice(AppCategories.label(e.key), e.value.toFloat(), palette[i % palette.size]) }
    val total = sorted.sumOf { it.value }.coerceAtLeast(1)
    GCard {
        Text("Where the time goes", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(slices, diameter = 150.dp, thickness = 20.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(slices.firstOrNull()?.let { (it.value * 100 / total).toInt().toString() + "%" } ?: "", style = MaterialTheme.typography.titleLarge)
                    Text(slices.firstOrNull()?.label ?: "", style = MaterialTheme.typography.bodySmall, color = gColors.muted, maxLines = 1)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

@Composable
private fun RankingCard(vm: ParentViewModel, d: ChildDevice, data: RangeData, onOpenApp: (AppInfo) -> Unit) {
    val ranked = data.perApp.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(10)
    if (ranked.isEmpty()) return
    val palette = gColors.chart
    GCard {
        Text("Top apps", style = MaterialTheme.typography.titleSmall)
        Text("Tap an app to set a limit or block it", style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        Spacer(Modifier.height(12.dp))
        ranked.forEachIndexed { i, e ->
            val app = d.app(e.key)
            RankRow(
                leading = { AppIconView(e.key, d.nameOf(e.key), vm.icons, size = 40.dp) },
                title = d.nameOf(e.key), value = Durations.compact(e.value),
                fraction = e.value.toFloat() / ranked[0].value, color = palette[i % palette.size],
                modifier = if (app != null) Modifier.clickable(onClick = { onOpenApp(app) }) else Modifier,
                sub = AppCategories.label(categoryOf(d, e.key))
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun insights(d: ChildDevice, range: Int, data: RangeData): List<String> {
    val out = ArrayList<String>()
    val total = data.totals.sum()
    if (total <= 0) return out
    val topApp = data.perApp.maxByOrNull { it.value }
    if (topApp != null) {
        val share = topApp.value * 100 / total
        out.add(d.nameOf(topApp.key) + " takes " + share + "% of all screen time" + (if (share >= 40) " — a big share." else "."))
    }
    if (range == 0) {
        val peak = d.hourlyToday.withIndex().maxByOrNull { it.value }
        if (peak != null && peak.value > 0) out.add("Busiest hour: " + Formats.hourLabel(peak.index) + " – " + Formats.hourLabel((peak.index + 1) % 24) + " (" + Durations.compact(peak.value) + ").")
        val late = d.hourlyToday.withIndex().filter { it.index >= 22 || it.index < 5 }.sumOf { it.value }
        if (late >= 300) out.add("About " + Durations.compact(late) + " of use after 10 PM or before 5 AM. A bedtime schedule can help.")
    } else {
        val peak = data.totals.withIndex().maxByOrNull { it.value }
        if (peak != null && peak.value > 0) out.add("Heaviest day: " + Formats.longDay(data.keys[peak.index]) + " (" + Durations.compact(peak.value) + ").")
        val half = data.totals.size / 2
        if (half >= 3) {
            val first = data.totals.take(half).average()
            val second = data.totals.drop(data.totals.size - half).average()
            if (first > 0 && second > first * 1.2) out.add("Screen time is trending up over this period.")
            else if (first > 0 && second < first * 0.8) out.add("Screen time is trending down — nice progress.")
        }
    }
    return out
}

@Composable
private fun InsightsCard(lines: List<String>) {
    GCard(container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("Insights", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}
