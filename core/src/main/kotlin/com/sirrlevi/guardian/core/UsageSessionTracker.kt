package com.sirrlevi.guardian.core

/** One continuous stretch of foreground time for one app. */
data class Session(val packageName: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Turns the raw "app came to front / went to back / screen off" events Android reports into
 * per-app seconds, an hourly profile and a session list for one calendar day.
 * Events must be fed in time order. Pure logic, no Android types, so it is unit-tested.
 */
class UsageSessionTracker(private var dayStartMs: Long, private val ignored: Set<String> = emptySet()) {
    private val usedMs = HashMap<String, Long>()
    private val hourlyMs = LongArray(24)
    private val sessionList = ArrayList<Session>()

    var currentPackage: String? = null
        private set
    var currentSinceMs: Long = 0L
        private set

    /** Called whenever a finished session is recorded (used to push the timeline to the parent). */
    var onSessionClosed: ((Session) -> Unit)? = null

    val sessions: List<Session> get() = sessionList
    fun hourlySeconds(): IntArray = IntArray(24) { (hourlyMs[it] / 1000L).toInt() }

    fun reset(newDayStartMs: Long) {
        dayStartMs = newDayStartMs
        usedMs.clear(); hourlyMs.fill(0L); sessionList.clear()
        currentPackage = null; currentSinceMs = 0L
    }

    fun onResumed(packageName: String, ts: Long) {
        if (packageName in ignored) {
            // The home screen came to the front: whatever app was open is no longer on screen.
            if (currentPackage != null) close(ts)
            return
        }
        val cur = currentPackage
        if (cur == packageName) return
        if (cur != null) close(ts)
        currentPackage = packageName
        currentSinceMs = maxOf(ts, dayStartMs)
    }

    fun onPaused(packageName: String, ts: Long) {
        if (currentPackage == packageName) close(ts)
    }

    fun onScreenOff(ts: Long) {
        if (currentPackage != null) close(ts)
    }

    /** Stops counting the open session at [ts] (used while the blocked screen covers an app). */
    fun stopCounting(ts: Long) = onScreenOff(ts)

    /** Seconds used today by [packageName], including a session that is still open at [nowMs]. */
    fun usedSeconds(packageName: String, nowMs: Long): Int {
        var total = usedMs[packageName] ?: 0L
        if (currentPackage == packageName) total += (nowMs - currentSinceMs).coerceAtLeast(0L)
        return (total / 1000L).toInt()
    }

    fun totalSeconds(nowMs: Long): Int {
        var total = usedMs.values.sum()
        if (currentPackage != null) total += (nowMs - currentSinceMs).coerceAtLeast(0L)
        return (total / 1000L).toInt()
    }

    /** Per-app seconds for every app used today (open session included), largest first. */
    fun perAppSeconds(nowMs: Long): List<Pair<String, Int>> {
        val keys = HashSet(usedMs.keys).also { set -> currentPackage?.let { set.add(it) } }
        return keys.map { it to usedSeconds(it, nowMs) }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    private fun close(endTs: Long) {
        val pkg = currentPackage ?: return
        val start = currentSinceMs
        currentPackage = null
        currentSinceMs = 0L
        val end = maxOf(endTs, start)
        val duration = end - start
        if (duration <= 0L) return
        usedMs[pkg] = (usedMs[pkg] ?: 0L) + duration
        addToHours(start, end)
        val session = Session(pkg, start, end)
        sessionList.add(session)
        onSessionClosed?.invoke(session)
    }

    private fun addToHours(start: Long, end: Long) {
        var cursor = start
        while (cursor < end) {
            val hour = ((cursor - dayStartMs) / HOUR_MS).toInt().coerceIn(0, 23)
            val hourEnd = dayStartMs + (hour + 1) * HOUR_MS
            val slice = minOf(end, hourEnd) - cursor
            hourlyMs[hour] += slice
            cursor += if (slice <= 0L) end - cursor else slice
        }
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
