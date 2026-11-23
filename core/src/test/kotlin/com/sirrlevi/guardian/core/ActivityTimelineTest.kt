package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTimelineTest {
    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"

    @Test fun usageEventTypesMapToScreenKinds() {
        assertEquals(ScreenKind.ON, ScreenKind.fromUsageEvent(15))
        assertEquals(ScreenKind.OFF, ScreenKind.fromUsageEvent(16))
        assertEquals(ScreenKind.LOCKED, ScreenKind.fromUsageEvent(17))
        assertEquals(ScreenKind.UNLOCKED, ScreenKind.fromUsageEvent(18))
        assertNull("app resume/pause are not screen events", ScreenKind.fromUsageEvent(1))
        assertNull(ScreenKind.fromUsageEvent(2))
        assertNull(ScreenKind.fromUsageEvent(0))
    }

    @Test fun codecRoundTripsAndRejectsGarbage() {
        val e = ScreenEvent(1_700_000_000_000L, ScreenKind.UNLOCKED)
        assertEquals(e, ScreenEventCodec.fromJson(ScreenEventCodec.toJson(e)))
        assertNull(ScreenEventCodec.fromJson(JsonObject()))
        assertNull(ScreenEventCodec.fromJson(JsonObject().apply { addProperty("k", "boom"); addProperty("t", 5L) }))
        assertNull(ScreenEventCodec.fromJson(JsonObject().apply { addProperty("k", "on"); addProperty("t", -1L) }))
        assertNull(ScreenEventCodec.fromJson(JsonObject().apply { addProperty("k", "on"); addProperty("t", "abc") }))
    }

    @Test fun eachSessionBecomesOpenedAndBackgroundWithItsDuration() {
        val t = listOf(Session(yt, 10_000L, 70_000L))
        val events = ActivityTimeline.build(t, emptyList())
        assertEquals(listOf(TimelineKind.APP_OPENED, TimelineKind.APP_BACKGROUND), events.map { it.kind })
        assertEquals(listOf(10_000L, 70_000L), events.map { it.timeMs })
        assertTrue(events.all { it.pkg == yt && it.durationSec == 60 })
    }

    @Test fun shortBlipsAreNotListed() {
        val events = ActivityTimeline.build(listOf(Session(yt, 0L, 1_000L), Session("", 0L, 60_000L)), emptyList())
        assertTrue(events.isEmpty())
    }

    @Test fun screenAndAppEventsInterleaveInTimeOrder() {
        val events = ActivityTimeline.build(
            listOf(Session(ig, 20_000L, 50_000L)),
            listOf(ScreenEvent(10_000L, ScreenKind.ON), ScreenEvent(15_000L, ScreenKind.UNLOCKED), ScreenEvent(60_000L, ScreenKind.OFF))
        )
        assertEquals(
            listOf(TimelineKind.SCREEN_ON, TimelineKind.UNLOCKED, TimelineKind.APP_OPENED, TimelineKind.APP_BACKGROUND, TimelineKind.SCREEN_OFF),
            events.map { it.kind }
        )
    }

    @Test fun simultaneousEventsKeepASensibleOrder() {
        val events = ActivityTimeline.build(
            listOf(Session(yt, 5_000L, 9_000L)),
            listOf(ScreenEvent(5_000L, ScreenKind.ON), ScreenEvent(9_000L, ScreenKind.OFF))
        )
        assertEquals(
            listOf(TimelineKind.SCREEN_ON, TimelineKind.APP_OPENED, TimelineKind.APP_BACKGROUND, TimelineKind.SCREEN_OFF),
            events.map { it.kind }
        )
    }

    @Test fun repeatedScreenEventsAndExactDuplicatesAreCollapsed() {
        val events = ActivityTimeline.build(
            emptyList(),
            listOf(ScreenEvent(1_000L, ScreenKind.ON), ScreenEvent(2_000L, ScreenKind.ON), ScreenEvent(2_000L, ScreenKind.ON), ScreenEvent(3_000L, ScreenKind.OFF))
        )
        assertEquals(listOf(TimelineKind.SCREEN_ON, TimelineKind.SCREEN_OFF), events.map { it.kind })
        assertEquals(1_000L, events.first().timeMs)
    }

    @Test fun summaryCountsWhatWasRecordedAndNothingElse() {
        val s = ActivityTimeline.summary(
            listOf(Session(yt, 1_000L, 61_000L), Session(ig, 100_000L, 130_000L), Session(yt, 200_000L, 201_000L)),
            listOf(ScreenEvent(500L, ScreenKind.ON), ScreenEvent(800L, ScreenKind.UNLOCKED), ScreenEvent(140_000L, ScreenKind.ON), ScreenEvent(150_000L, ScreenKind.OFF))
        )
        assertEquals(2, s.appSessions)
        assertEquals(90, s.totalSec)
        assertEquals(60, s.longestSec)
        assertEquals(yt, s.longestPkg)
        assertEquals(500L, s.firstMs)
        assertEquals(150_000L, s.lastMs)
        assertEquals(2, s.screenOns)
        assertEquals(1, s.unlocks)
        assertEquals(2, s.distinctApps)
    }

    @Test fun emptyDayHasAnEmptySummary() {
        val s = ActivityTimeline.summary(emptyList(), emptyList())
        assertEquals(0, s.appSessions)
        assertEquals(0L, s.firstMs)
        assertNull(s.longestPkg)
    }
}
