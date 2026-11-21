package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EnforcementTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
    }.timeInMillis
    private fun decide(pkg: String, policy: FamilyPolicy, used: Int, now: Long, exempt: Set<String> = emptySet()) =
        Enforcement.decide(pkg, policy, used, now, DayClock.of(now, utc), exempt)

    @Test fun parentBlockWins() {
        val p = FamilyPolicy(appRules = mapOf("a" to AppRule(blocked = true)))
        val d = decide("a", p, 0, at(21, 12, 0))
        assertTrue(d.blocked); assertEquals(BlockReason.PARENT_BLOCK, d.reason)
    }

    @Test fun dailyLimitBlocksOnlyAfterTheLimit() {
        val p = FamilyPolicy(appRules = mapOf("a" to AppRule(dailyLimitSec = 600)))
        assertFalse(decide("a", p, 599, at(21, 12, 0)).blocked)
        assertEquals(1, decide("a", p, 599, at(21, 12, 0)).remainingSec)
        val over = decide("a", p, 600, at(21, 12, 0))
        assertTrue(over.blocked); assertEquals(BlockReason.DAILY_LIMIT, over.reason)
    }

    @Test fun exemptAppsAreNeverBlocked() {
        val p = FamilyPolicy(appRules = mapOf("home" to AppRule(blocked = true)), pauseUntil = Long.MAX_VALUE)
        assertFalse(decide("home", p, 0, at(21, 12, 0), exempt = setOf("home")).blocked)
    }

    @Test fun pauseBlocksEverythingUntilItEnds() {
        val now = at(21, 12, 0)
        val p = FamilyPolicy(pauseUntil = now + 60_000)
        assertEquals(BlockReason.PAUSED, decide("x", p, 0, now).reason)
        assertFalse(decide("x", p, 0, now + 61_000).blocked)
    }

    @Test fun scheduleAllowsOnlyListedApps() {
        val sched = Schedule("s", "School", days = listOf(2, 3, 4, 5, 6), startMin = 8 * 60, endMin = 14 * 60, allowedPackages = listOf("calc"))
        val p = FamilyPolicy(schedules = listOf(sched))
        val monday10 = at(23, 10, 0) // 23 Nov 2026 is a Monday
        assertTrue(decide("game", p, 0, monday10).blocked)
        assertFalse(decide("calc", p, 0, monday10).blocked)
        assertFalse(decide("game", p, 0, at(23, 15, 0)).blocked)
        assertFalse(decide("game", p, 0, at(22, 10, 0)).blocked) // Sunday is not a school day
    }

    @Test fun overnightScheduleSpansMidnight() {
        val sched = Schedule("b", "Bedtime", days = listOf(1, 2, 3, 4, 5, 6, 7), startMin = 21 * 60, endMin = 7 * 60)
        val p = FamilyPolicy(schedules = listOf(sched))
        assertTrue(decide("x", p, 0, at(21, 22, 30)).blocked)
        assertTrue(decide("x", p, 0, at(22, 6, 59)).blocked)
        assertFalse(decide("x", p, 0, at(22, 7, 0)).blocked)
        assertFalse(decide("x", p, 0, at(21, 12, 0)).blocked)
    }
}
