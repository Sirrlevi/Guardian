package com.sirrlevi.guardian.core

import java.util.Calendar
import java.util.TimeZone

enum class BlockReason { NONE, PARENT_BLOCK, DAILY_LIMIT, SCHEDULE, PAUSED }

data class Decision(
    val blocked: Boolean,
    val reason: BlockReason = BlockReason.NONE,
    val limitSec: Int? = null,
    val usedSec: Int = 0,
    val scheduleName: String = ""
) {
    val remainingSec: Int? get() = limitSec?.let { (it - usedSec).coerceAtLeast(0) }
}

/** The calendar facts the engine needs, kept separate so tests never depend on the real clock. */
data class DayClock(val dayOfWeek: Int, val dayKey: String, val minuteOfDay: Int, val dayStartMs: Long) {
    companion object {
        fun of(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): DayClock {
            val c = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
            val key = String.format(
                java.util.Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
            )
            val start = (c.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return DayClock(c.get(Calendar.DAY_OF_WEEK), key, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), start)
        }
    }
}

object Enforcement {
    /**
     * Decides whether [packageName] may be used right now.
     * [exempt] is the set of packages that must never be blocked (Guardian itself, the launcher,
     * the dialer, system UI) so a child can always reach home, call for help, or open settings.
     */
    fun decide(
        packageName: String,
        policy: FamilyPolicy,
        usedSec: Int,
        nowMs: Long,
        clock: DayClock,
        exempt: Set<String>
    ): Decision {
        if (packageName in exempt) return Decision(false, usedSec = usedSec)
        if (policy.pauseUntil > nowMs) return Decision(true, BlockReason.PAUSED, usedSec = usedSec)
        val rule = policy.appRules[packageName]
        if (rule?.blocked == true) return Decision(true, BlockReason.PARENT_BLOCK, usedSec = usedSec)
        val active = policy.schedules.firstOrNull { it.enabled && isActive(it, clock) && packageName !in it.allowedPackages }
        if (active != null) return Decision(true, BlockReason.SCHEDULE, usedSec = usedSec, scheduleName = active.name)
        val limit = rule?.limitFor(clock.dayOfWeek, clock.dayKey)
        if (limit != null) return Decision(usedSec >= limit, if (usedSec >= limit) BlockReason.DAILY_LIMIT else BlockReason.NONE, limit, usedSec)
        return Decision(false, usedSec = usedSec)
    }

    /** True when [schedule] covers the current moment. A window whose end is before its start runs past midnight. */
    fun isActive(schedule: Schedule, clock: DayClock): Boolean {
        val m = clock.minuteOfDay
        val today = clock.dayOfWeek
        val yesterday = if (today == 1) 7 else today - 1
        return if (schedule.startMin <= schedule.endMin) {
            today in schedule.days && m >= schedule.startMin && m < schedule.endMin
        } else {
            (today in schedule.days && m >= schedule.startMin) || (yesterday in schedule.days && m < schedule.endMin)
        }
    }
}
