package com.sirrlevi.guardian.core

import com.google.gson.JsonObject

/** Screen-level events Android's usage log reports. They say nothing about what happened on screen. */
enum class ScreenKind(val code: String) {
    ON("on"), OFF("off"), UNLOCKED("unlock"), LOCKED("lock");

    companion object {
        fun fromCode(code: String?): ScreenKind? = values().firstOrNull { it.code == code }

        /** Maps `UsageEvents.Event` types 15–18; every other type is not a screen event. */
        fun fromUsageEvent(type: Int): ScreenKind? = when (type) {
            15 -> ON          // SCREEN_INTERACTIVE
            16 -> OFF         // SCREEN_NON_INTERACTIVE
            17 -> LOCKED      // KEYGUARD_SHOWN
            18 -> UNLOCKED    // KEYGUARD_HIDDEN
            else -> null
        }
    }
}

data class ScreenEvent(val timeMs: Long, val kind: ScreenKind)

object ScreenEventCodec {
    fun toJson(e: ScreenEvent): JsonObject = JsonObject().apply {
        addProperty("k", e.kind.code)
        addProperty("t", e.timeMs)
    }

    /** Null for anything malformed, so one bad record never hides the rest. */
    fun fromJson(o: JsonObject): ScreenEvent? = try {
        val kind = ScreenKind.fromCode(o.get("k")?.asString)
        val time = o.get("t")?.asLong ?: 0L
        if (kind == null || time <= 0L) null else ScreenEvent(time, kind)
    } catch (_: Exception) { null }
}

enum class TimelineKind { SCREEN_ON, UNLOCKED, APP_OPENED, APP_BACKGROUND, LOCKED, SCREEN_OFF }

/** One line of the day's timeline. [durationSec] is set on app events: how long that stretch in the app lasted. */
data class TimelineEvent(val timeMs: Long, val kind: TimelineKind, val pkg: String? = null, val durationSec: Int? = null)

data class TimelineSummary(
    val appSessions: Int,
    val totalSec: Int,
    val longestSec: Int,
    val longestPkg: String?,
    val firstMs: Long,
    val lastMs: Long,
    val screenOns: Int,
    val unlocks: Int,
    val distinctApps: Int
)

/**
 * Builds the "app activity" timeline from what the phone recorded: app sessions (opened → background) and
 * screen events. It describes which app was in front and when, never what was done inside it.
 */
object ActivityTimeline {
    const val MIN_SESSION_MS = 3_000L

    fun build(sessions: List<Session>, screen: List<ScreenEvent>, minSessionMs: Long = MIN_SESSION_MS): List<TimelineEvent> {
        val out = ArrayList<TimelineEvent>()
        for (s in sessions) {
            if (s.packageName.isBlank() || s.durationMs < minSessionMs) continue
            val sec = (s.durationMs / 1000L).toInt()
            out.add(TimelineEvent(s.startMs, TimelineKind.APP_OPENED, s.packageName, sec))
            out.add(TimelineEvent(s.endMs, TimelineKind.APP_BACKGROUND, s.packageName, sec))
        }
        for (e in screen) {
            val kind = when (e.kind) {
                ScreenKind.ON -> TimelineKind.SCREEN_ON
                ScreenKind.OFF -> TimelineKind.SCREEN_OFF
                ScreenKind.UNLOCKED -> TimelineKind.UNLOCKED
                ScreenKind.LOCKED -> TimelineKind.LOCKED
            }
            out.add(TimelineEvent(e.timeMs, kind))
        }
        // Same moment: screen on, unlock, app opens, app closes, lock, screen off.
        val sorted = out.sortedWith(compareBy<TimelineEvent> { it.timeMs }.thenBy { it.kind.ordinal })
        val clean = ArrayList<TimelineEvent>(sorted.size)
        for (ev in sorted) {
            val prev = clean.lastOrNull()
            val repeatedScreen = prev != null && prev.kind == ev.kind && ev.pkg == null && prev.pkg == null
            val exactDuplicate = prev != null && prev.timeMs == ev.timeMs && prev.kind == ev.kind && prev.pkg == ev.pkg
            if (!repeatedScreen && !exactDuplicate) clean.add(ev)
        }
        return clean
    }

    fun summary(sessions: List<Session>, screen: List<ScreenEvent>, minSessionMs: Long = MIN_SESSION_MS): TimelineSummary {
        val real = sessions.filter { it.packageName.isNotBlank() && it.durationMs >= minSessionMs }
        val longest = real.maxByOrNull { it.durationMs }
        val times = real.flatMap { listOf(it.startMs, it.endMs) } + screen.map { it.timeMs }
        return TimelineSummary(
            appSessions = real.size,
            totalSec = (real.sumOf { it.durationMs } / 1000L).toInt(),
            longestSec = ((longest?.durationMs ?: 0L) / 1000L).toInt(),
            longestPkg = longest?.packageName,
            firstMs = times.minOrNull() ?: 0L,
            lastMs = times.maxOrNull() ?: 0L,
            screenOns = screen.count { it.kind == ScreenKind.ON },
            unlocks = screen.count { it.kind == ScreenKind.UNLOCKED },
            distinctApps = real.map { it.packageName }.toSet().size
        )
    }
}
