package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.ScreenEvent

/** One installed app as the child reported it. */
data class AppInfo(
    val pkg: String,
    val name: String,
    val category: String,
    val system: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
    val version: String
)

/** What the child is doing right now (streamed in real time). */
data class LiveState(
    val pkg: String,
    val name: String,
    val sinceMs: Long,
    val usedSec: Int,
    val totalSec: Int,
    val blocked: Boolean,
    val reason: String,
    val screenOn: Boolean,
    val ts: Long
)

/** The child's confirmation that it is enforcing a given version of the parent's rules. */
data class AckState(
    val policyUpdatedAt: Long,
    val appliedAt: Long,
    val mode: String,
    val enforcing: Boolean,
    val missing: List<String>
)

data class DeviceInfo(
    val ts: Long,
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val battery: Int,
    val charging: Boolean,
    val storageTotalMb: Long,
    val storageFreeMb: Long,
    val ramTotalMb: Long,
    val ramFreeMb: Long,
    val network: String,
    val screenOn: Boolean,
    val uptimeMin: Long,
    val autoTime: Boolean,
    val appVersion: String,
    val enforcement: String,
    val permUsage: Boolean,
    val permOverlay: Boolean,
    val permNotifications: Boolean,
    val permBattery: Boolean,
    val permAdmin: Boolean,
    val permVpn: Boolean,
    val permOwner: Boolean,
    /** Whether Android notification access (message previews) is switched on for Guardian Child. */
    val permNotifAccess: Boolean = false,
    /** False when the child app is too old to report notification access at all (so "off" would be a guess). */
    val notifAccessKnown: Boolean = false,
    /** What the child's Guardian service said about itself in its last report ("running"); false when the report doesn't say. */
    val monitorRunning: Boolean = false,
    val monitorKnown: Boolean = false,
    /** Android's API level, 0 when the report doesn't say. */
    val sdk: Int = 0
)

data class SessionRow(val pkg: String, val name: String, val start: Long, val end: Long) {
    val seconds: Int get() = ((end - start) / 1000L).toInt().coerceAtLeast(0)
}

data class DayTotals(val dayKey: String, val totalSec: Int, val apps: Map<String, Int>)

data class WebVisit(val url: String, val title: String, val startedAt: Long, val durationMs: Long, val blocked: Boolean)

data class MoreTimeRequest(val id: String, val pkg: String, val name: String, val reason: String, val ts: Long, val status: String)

data class ChildDevice(
    val id: String,
    val name: String,
    val manufacturer: String,
    val lastSeen: Long,
    val apps: List<AppInfo>,
    val info: DeviceInfo?,
    val live: LiveState?,
    val ack: AckState?,
    /** Seconds used today per package. */
    val todaySec: Map<String, Int>,
    val hourlyToday: List<Int>,
    /** Sessions per day key (yyyyMMdd). */
    val sessions: Map<String, List<SessionRow>>,
    val daily: List<DayTotals>,
    val visits: List<WebVisit>,
    val requests: List<MoreTimeRequest>,
    val hasMonitor: Boolean,
    /** When the child last uploaded message previews, on the child's clock; 0 when it never has. */
    val commsAt: Long = 0L,
    /** Screen on/off and lock/unlock events per day key (empty for days the phone never reported). */
    val screenEvents: Map<String, List<ScreenEvent>> = emptyMap()
) {
    val isOnline: Boolean get() = System.currentTimeMillis() - presenceTs() < ONLINE_WINDOW_MS
    fun presenceTs(): Long = maxOf(lastSeen, info?.ts ?: 0L, live?.ts ?: 0L)
    fun app(pkg: String): AppInfo? = apps.firstOrNull { it.pkg == pkg }
    fun nameOf(pkg: String): String = app(pkg)?.name ?: live?.takeIf { it.pkg == pkg }?.name?.takeIf { it.isNotBlank() } ?: pkg

    companion object {
        const val ONLINE_WINDOW_MS = 150_000L
    }
}

/**
 * Screens of the Parent app. The ones with [inBar] sit in the bottom bar; the others are reached from "More"
 * (or from a notification) and highlight "More" while open.
 */
enum class Tab(val label: String, val inBar: Boolean = true) {
    Home("Home"), Apps("Apps"), Activity("Activity"), Messages("Messages"), More("More"),
    Reports("Reports", false), Settings("Settings", false), Media("Shared media", false), Preview("Screen preview", false), Controls("Device controls", false)
}

enum class ThemeMode { System, Dark, Light }

enum class Connection { Connecting, Live, Syncing, Offline }
