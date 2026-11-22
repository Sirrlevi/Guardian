package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.AppInfo
import com.sirrlevi.guardian.parent.ParentViewModel

private val AppFilters = listOf("All apps", "Blocked", "Has a limit", "System")
private val AppSorts = listOf("Most used", "A–Z", "Newest")

@Composable
fun AppsScreen(vm: ParentViewModel, padding: PaddingValues, onOpenApp: (AppInfo) -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val device = vm.device
    val clock = DayClock.of(System.currentTimeMillis())

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Segmented(listOf("Apps", "Websites", "Schedules"), section, { section = it }) }
        if (section == 1) { item { WebSection(vm) }; return@LazyColumn }
        if (section == 2) { item { SchedulesSection(vm) }; return@LazyColumn }

        item { ApplyBanner(vm) }
        if (device == null || device.apps.isEmpty()) {
            item {
                EmptyState(
                    GIcon.Apps, "No apps to show yet",
                    if (device == null) "Pair your child's phone from Settings. Its apps appear here with their real icons."
                    else "Waiting for the child's phone to send its app list. Make sure Guardian Child is open once and has Usage Access."
                )
            }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search apps") },
                leadingIcon = { GIconView(GIcon.Search, gColors.muted, size = 20.dp) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(AppFilters.size) { i ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(AppFilters[i]) })
                }
                item {
                    Spacer(Modifier.width(4.dp))
                    SoftButton("Sort: " + AppSorts[sort], { sort = (sort + 1) % AppSorts.size }, color = gColors.muted)
                }
            }
        }

        val rules = vm.policy.appRules
        val q = query.trim().lowercase()
        val list = device.apps.filter { app ->
            val rule = rules[app.pkg]
            val used = device.todaySec[app.pkg] ?: 0
            val matches = q.isEmpty() || app.name.lowercase().contains(q) || app.pkg.lowercase().contains(q)
            matches && when (filter) {
                0 -> !app.system || used > 0
                1 -> rule?.blocked == true
                2 -> rule != null && (rule.dailyLimitSec > 0 || rule.daySec.isNotEmpty())
                else -> app.system
            }
        }.let { apps ->
            when (sort) {
                0 -> apps.sortedByDescending { device.todaySec[it.pkg] ?: 0 }
                1 -> apps.sortedBy { it.name.lowercase() }
                else -> apps.sortedByDescending { it.installedAt }
            }
        }
        val maxUsed = (device.todaySec.values.maxOrNull() ?: 1).coerceAtLeast(1)

        if (list.isEmpty()) {
            item { EmptyState(GIcon.Search, "Nothing matches", "Try a different search or filter.") }
        }
        items(list, key = { it.pkg }) { app ->
            val rule = rules[app.pkg]
            val used = device.todaySec[app.pkg] ?: 0
            val limit = rule?.limitFor(clock.dayOfWeek, clock.dayKey)
            val over = limit != null && used >= limit
            GCard(onClick = { onOpenApp(app) }, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconView(app.pkg, app.name, vm.icons, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            if (used > 0) Durations.compact(used) + " today" else "Not used today",
                            style = MaterialTheme.typography.bodySmall, color = gColors.muted
                        )
                        if (used > 0) {
                            Spacer(Modifier.height(6.dp))
                            ProgressBar(used.toFloat() / maxUsed, if (over) gColors.danger else MaterialTheme.colorScheme.primary, height = 4.dp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (rule?.blocked == true) Pill("Blocked", gColors.danger)
                        else if (over) Pill("Limit reached", gColors.danger)
                        else if (limit != null) Pill("Limit " + Durations.compact(limit), gColors.warning)
                    }
                    Spacer(Modifier.width(4.dp))
                    GIconView(GIcon.Chevron, gColors.muted, size = 18.dp)
                }
            }
        }
    }
}
