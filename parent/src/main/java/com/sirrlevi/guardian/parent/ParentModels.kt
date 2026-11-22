package com.sirrlevi.guardian.parent

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
    val permOwner: Boolean
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
    val hasMonitor: Boolean
) {
    val isOnline: Boolean get() = System.currentTimeMillis() - presenceTs() < ONLINE_WINDOW_MS
    fun presenceTs(): Long = maxOf(lastSeen, info?.ts ?: 0L, live?.ts ?: 0L)
    fun app(pkg: String): AppInfo? = apps.firstOrNull { it.pkg == pkg }
    fun nameOf(pkg: String): String = app(pkg)?.name ?: live?.takeIf { it.pkg == pkg }?.name?.takeIf { it.isNotBlank() } ?: pkg

    companion object {
        const val ONLINE_WINDOW_MS = 150_000L
    }
}

enum class Tab(val label: String) { Home("Home"), Apps("Apps"), Activity("Activity"), Reports("Reports"), Settings("Settings") }

enum class ThemeMode { System, Dark, Light }

enum class Connection { Connecting, Live, Syncing, Offline }
