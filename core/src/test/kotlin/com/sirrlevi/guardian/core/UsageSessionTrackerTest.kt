package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSessionTrackerTest {
    private val day = 1_000_000_000_000L
    private val h = 3_600_000L
    private fun tracker() = UsageSessionTracker(day)

    @Test fun addsUpForegroundTimePerApp() {
        val t = tracker()
        t.onResumed("a", day + 1000); t.onPaused("a", day + 11_000)
        t.onResumed("a", day + 20_000); t.onPaused("a", day + 25_000)
        assertEquals(15, t.usedSeconds("a", day + 30_000))
        assertEquals(2, t.sessions.size)
    }

    @Test fun openSessionCountsLive() {
        val t = tracker()
        t.onResumed("a", day + 1000)
        assertEquals("a", t.currentPackage)
        assertEquals(9, t.usedSeconds("a", day + 10_000))
    }

    @Test fun switchingAppsClosesThePreviousOne() {
        val t = tracker()
        t.onResumed("a", day + 0); t.onResumed("b", day + 5000); t.onPaused("b", day + 9000)
        assertEquals(5, t.usedSeconds("a", day + 10_000))
        assertEquals(4, t.usedSeconds("b", day + 10_000))
        assertNull(t.currentPackage)
    }

    @Test fun screenOffClosesTheSession() {
        val t = tracker()
        t.onResumed("a", day); t.onScreenOff(day + 7000)
        assertEquals(7, t.usedSeconds("a", day + 100_000))
    }

    @Test fun repeatedResumeOfTheSameAppDoesNotRestartIt() {
        val t = tracker()
        t.onResumed("a", day); t.onResumed("a", day + 4000); t.onPaused("a", day + 6000)
        assertEquals(6, t.usedSeconds("a", day + 10_000))
        assertEquals(1, t.sessions.size)
    }

    @Test fun sessionSpanningTwoHoursIsSplitInTheHourlyProfile() {
        val t = tracker()
        t.onResumed("a", day + h - 600_000); t.onPaused("a", day + h + 300_000)
        val hours = t.hourlySeconds()
        assertEquals(600, hours[0]); assertEquals(300, hours[1])
    }

    @Test fun ignoredPackagesAreNotCounted() {
        val t = UsageSessionTracker(day, setOf("launcher"))
        t.onResumed("launcher", day); t.onPaused("launcher", day + 5000)
        assertEquals(0, t.totalSeconds(day + 6000))
    }

    @Test fun perAppRankingIsLargestFirst() {
        val t = tracker()
        t.onResumed("a", day); t.onPaused("a", day + 3000)
        t.onResumed("b", day + 4000); t.onPaused("b", day + 14_000)
        assertEquals(listOf("b", "a"), t.perAppSeconds(day + 20_000).map { it.first })
    }

    @Test fun closedSessionsAreAnnounced() {
        val t = tracker(); val seen = mutableListOf<Session>()
        t.onSessionClosed = { seen.add(it) }
        t.onResumed("a", day); t.onPaused("a", day + 2000)
        assertEquals(1, seen.size); assertEquals(2000L, seen[0].durationMs)
    }
}

class UsageSessionTrackerHomeTest {
    @Test fun launcherResumeClosesTheOpenApp() {
        val day = 1_000_000_000_000L
        val t = UsageSessionTracker(day, setOf("launcher"))
        t.onResumed("a", day); t.onResumed("launcher", day + 4000)
        org.junit.Assert.assertNull(t.currentPackage)
        assertEquals(4, t.usedSeconds("a", day + 60_000))
    }
}
