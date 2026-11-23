package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsAndSseTest {
    @Test fun formatsDurations() {
        assertEquals("1h 02m", Durations.compact(3725))
        assertEquals("2m 05s", Durations.compact(125))
        assertEquals("42s", Durations.compact(42))
        assertEquals("15m", Durations.compact(900))
        assertEquals("1:02:05", Durations.clock(3725))
    }

    @Test fun formatsSignedDurations() {
        assertEquals("+30m", Durations.signed(1800))
        assertEquals("+2h 00m", Durations.signed(7200))
        assertEquals("\u22121h 00m", Durations.signed(-3600))
        assertEquals("0m", Durations.signed(0))
    }

    @Test fun splitsAndJoinsHoursMinutesSeconds() {
        assertEquals(Triple(1, 2, 5), Durations.split(3725))
        assertEquals(3725, Durations.join(1, 2, 5))
        assertEquals(AppRule.UNLIMITED_SEC, Durations.join(30, 0, 0))
        assertEquals(1200, Durations.snap(1190, 300))
    }

    @Test fun parsesServerSentEvents() {
        val seen = mutableListOf<Pair<String, String>>()
        val parser = SseParser { e, d -> seen.add(e to d) }
        listOf("event: put", "data: {\"path\":\"/\"}", "", ": comment", "event: keep-alive", "data: null", "").forEach(parser::feed)
        assertEquals(listOf("put" to "{\"path\":\"/\"}", "keep-alive" to "null"), seen)
    }
}

class AppCategoriesTest {
    @Test fun recognisesPopularApps() {
        assertEquals("social", AppCategories.hintFor("com.instagram.android"))
        assertEquals("video", AppCategories.hintFor("com.google.android.youtube"))
        assertEquals("messaging", AppCategories.hintFor("com.whatsapp"))
        org.junit.Assert.assertNull(AppCategories.hintFor("com.example.unknown"))
        assertEquals("Video & streaming", AppCategories.label("video"))
        assertEquals("Other", AppCategories.label("nonsense"))
    }
}
