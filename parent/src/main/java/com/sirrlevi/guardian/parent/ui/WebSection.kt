package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.core.DomainRules
import com.sirrlevi.guardian.core.Durations
import com.sirrlevi.guardian.parent.ParentViewModel

private class WebPreset(val name: String, val note: String, val domains: List<String>)

private val Presets = listOf(
    WebPreset("Social media", "Facebook, Instagram, TikTok, Snapchat, X…", listOf(
        "facebook.com", "instagram.com", "tiktok.com", "snapchat.com", "twitter.com", "x.com", "reddit.com", "discord.com", "pinterest.com", "tumblr.com")),
    WebPreset("Video & streaming", "YouTube, Netflix, Twitch…", listOf(
        "youtube.com", "youtu.be", "netflix.com", "twitch.tv", "primevideo.com", "hotstar.com", "dailymotion.com")),
    WebPreset("Online games", "Roblox, Steam, Epic, browser games…", listOf(
        "roblox.com", "steampowered.com", "epicgames.com", "miniclip.com", "poki.com", "y8.com", "friv.com")),
    WebPreset("Adult content", "Well-known adult sites", listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com", "onlyfans.com")),
    WebPreset("Gambling & betting", "Casino and betting sites", listOf(
        "bet365.com", "stake.com", "1xbet.com", "pokerstars.com", "betway.com"))
)

/** Block websites without typing: one-tap lists, a paste box, and a "block this" button on sites the child visited. */
@Composable
fun WebSection(vm: ParentViewModel) {
    val domains = vm.policy.blockedDomains
    val device = vm.device
    var input by remember { mutableStateOf("") }
    val parsed = DomainRules.hostOf(input)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ApplyBanner(vm)
        GCard {
            Text("Block a website", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text("Paste a link or type example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.width(10.dp))
                PrimaryButton("Block", {
                    parsed?.let { host -> vm.setWebRules(domains + host, vm.policy.blockedUrls); input = "" }
                }, enabled = parsed != null, modifier = Modifier.width(96.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("A whole site and all its pages are blocked, including subdomains such as m.example.com.",
                style = MaterialTheme.typography.bodySmall, color = gColors.muted)
        }

        SectionTitle("Quick block lists", subtitle = "Switch a whole category on or off in one tap")
        Presets.forEach { preset ->
            val on = preset.domains.all { it in domains }
            GCard(onClick = {
                vm.setWebRules(if (on) domains - preset.domains.toSet() else domains + preset.domains, vm.policy.blockedUrls)
            }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        GIconView(GIcon.Globe, MaterialTheme.colorScheme.primary, size = 22.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        Text(preset.note, style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                    }
                    Switch(checked = on, onCheckedChange = null)
                }
            }
        }

        SectionTitle("Blocked sites", subtitle = "${domains.size} blocked")
        if (domains.isEmpty()) {
            GCard { Text("Nothing blocked yet. Add a site above or switch on a list.", style = MaterialTheme.typography.bodyMedium, color = gColors.muted) }
        } else {
            GCard(padding = 4.dp) {
                domains.sorted().forEachIndexed { i, domain ->
                    if (i > 0) HorizontalDivider(color = gColors.stroke)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GIconView(GIcon.Globe, gColors.muted, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Box(Modifier.clip(CircleShape).clickable { vm.setWebRules(domains - domain, vm.policy.blockedUrls) }.padding(8.dp)) {
                            GIconView(GIcon.Close, gColors.danger, size = 18.dp)
                        }
                    }
                }
            }
        }

        val visits = device?.visits.orEmpty()
        if (visits.isNotEmpty()) {
            SectionTitle("Recently visited", subtitle = "Pages opened in Guardian Browser. Tap Block to stop a site.")
            val byHost = visits.filter { !it.blocked }.groupBy { DomainRules.hostOf(it.url) ?: it.url }
                .map { (host, list) -> Triple(host, list.size, list.maxOf { it.startedAt }) to list.sumOf { it.durationMs } }
                .sortedByDescending { it.first.third }.take(8)
            GCard(padding = 4.dp) {
                byHost.forEachIndexed { i, (meta, totalMs) ->
                    val (host, count, last) = meta
                    if (i > 0) HorizontalDivider(color = gColors.stroke)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(host, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text("$count visit${if (count == 1) "" else "s"} · " + Durations.compact((totalMs / 1000L).toInt()) + " · " + Formats.relative(last),
                                style = MaterialTheme.typography.bodySmall, color = gColors.muted)
                        }
                        if (DomainRules.isBlockedHost(host, domains)) Pill("Blocked", gColors.danger)
                        else SoftButton("Block", { vm.setWebRules(domains + host, vm.policy.blockedUrls) }, color = gColors.danger)
                    }
                }
            }
        }

        GCard(container = MaterialTheme.colorScheme.surfaceVariant) {
            Text("How website blocking works", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Blocked sites cannot be opened in Guardian Browser on your child's phone. If your child also turns on the website-filter VPN " +
                    "(Guardian Child → Permission centre), the same domains are blocked in other apps and browsers. Android does not let any app " +
                    "read Chrome's full history, so only Guardian Browser visits are listed here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
