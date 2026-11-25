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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.Tab

private class MoreItem(val tab: Tab, val icon: GIcon, val title: String, val body: String)
private class MoreSection(val title: String, val items: List<MoreItem>)

/**
 * Everything that is not in the bottom bar, grouped so the list stays readable as tools are added.
 * Only tools that really work get a row; nothing here is a placeholder.
 */
@Composable
fun MoreScreen(vm: ParentViewModel, padding: PaddingValues, open: (Tab) -> Unit) {
    val sections = listOf(
        MoreSection("Insights", listOf(
            MoreItem(Tab.Reports, GIcon.Chart, "Reports", "Weekly and monthly screen time, trends and top apps.")
        )),
        MoreSection("Optional tools", listOf(
            MoreItem(Tab.Media, GIcon.Photo, "Shared media", "Optional preview copies of new photos and videos. Off until you turn it on."),
            MoreItem(Tab.Preview, GIcon.Screen, "Screen preview", "Ask to see your child's screen. It only starts if they allow it on their phone, every time.")
        )),
        MoreSection("Guardian", listOf(
            MoreItem(Tab.Settings, GIcon.Settings, "Settings", "Pair a phone, app updates, theme and account.")
        ))
    )
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("More", style = MaterialTheme.typography.headlineMedium)
                Text(vm.device?.name ?: "Guardian", style = MaterialTheme.typography.bodyMedium, color = gColors.muted)
            }
        }
        sections.forEach { section ->
            item(key = "section-" + section.title) {
                Text(section.title, style = MaterialTheme.typography.labelLarge, color = gColors.muted, modifier = Modifier.padding(top = 4.dp))
            }
            section.items.forEach { entry ->
                item(key = entry.tab.name) {
                    GCard(onClick = { open(entry.tab) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIconView(entry.icon, MaterialTheme.colorScheme.primary, size = 26.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                Text(entry.body, style = MaterialTheme.typography.bodySmall, color = gColors.muted, modifier = Modifier.padding(top = 2.dp))
                            }
                            GIconView(GIcon.Chevron, gColors.muted, size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}
