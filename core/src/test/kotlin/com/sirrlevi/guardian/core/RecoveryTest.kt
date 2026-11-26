package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val day = 24L * 3_600_000L
    private val now = 1_800_000_000_000L

    // ---------------------------------------------------------------- settings

    @Test fun settingsSurviveTheDatabaseRoundTrip() {
        val s = RecoverySettings(true, 30, 7, now)
        assertEquals(s, RecoverySettingsCodec.fromJson(RecoverySettingsCodec.toJson(s)))
    }

    @Test fun onlyTheOfferedIntervalsAndRetentionsAreEverUsed() {
        val odd = RecoverySettings(true, 1, 9_999, now).normalized()
        assertEquals(RecoveryPolicy.DEFAULT_INTERVAL_MIN, odd.intervalMin)
        assertEquals(RecoveryPolicy.DEFAULT_RETENTION_DAYS, odd.retentionDays)
        for (m in RecoveryPolicy.INTERVALS_MIN) assertEquals(m, RecoverySettings(true, m, 7, 0).normalized().intervalMin)
        for (d in RecoveryPolicy.RETENTION_DAYS) assertEquals(d, RecoverySettings(true, 15, d, 0).normalized().retentionDays)
    }

    @Test fun settingsThatAreMissingOrDamagedMeanOffNotOn() {
        assertNull(RecoverySettingsCodec.fromJson(null))
        val damaged = RecoverySettingsCodec.fromJson(JsonObject().apply { addProperty("on", "maybe"); addProperty("every", "soon") })
        // A value that can't be read never switches sharing on.
        assertTrue(damaged == null || !damaged.enabled)
        assertFalse(RecoverySettings.OFF.enabled)
        assertFalse(RecoverySettingsCodec.fromJson(JsonObject())!!.enabled)
    }

    // ---------------------------------------------------------------- points

    @Test fun aRealPointRoundTripsAndKeepsItsTime() {
        val p = LocationPoint(now, 28.613939, 77.209021, 18.4f, "gps")
        val back = LocationPointCodec.fromJson(LocationPointCodec.toJson(p))!!
        assertEquals(p.ts, back.ts)
        assertEquals(p.lat, back.lat, 0.000001)
        assertEquals(p.lon, back.lon, 0.000001)
        assertEquals("gps", back.provider)
        assertEquals(18f, back.accuracyM, 0.5f)
    }

    @Test fun pointsThatAreNotRealPositionsAreDroppedNotShown() {
        fun point(lat: Any, lon: Any) = JsonObject().apply {
            addProperty("ts", now)
            if (lat is Number) addProperty("lat", lat) else addProperty("lat", lat.toString())
            if (lon is Number) addProperty("lon", lon) else addProperty("lon", lon.toString())
        }
        assertNull("the empty 0,0 reading", LocationPointCodec.fromJson(point(0.0, 0.0)))
        assertNull(LocationPointCodec.fromJson(point(91.0, 10.0)))
        assertNull(LocationPointCodec.fromJson(point(10.0, 181.0)))
        assertNull(LocationPointCodec.fromJson(point("north", "east")))
        assertNull(LocationPointCodec.fromJson(JsonObject()))
        assertNull(LocationPointCodec.fromJson(null))
        assertNull("no time means we can't say how old it is", LocationPointCodec.fromJson(JsonObject().apply { addProperty("lat", 10.0); addProperty("lon", 10.0) }))
        assertFalse(LocationRules.isValid(Double.NaN, 5.0))
        assertFalse(LocationRules.isValid(5.0, Double.POSITIVE_INFINITY))
        assertTrue(LocationRules.isValid(-33.86, 151.21))
        assertTrue("a real place on the equator or the prime meridian is fine", LocationRules.isValid(0.0, 32.5))
    }

    @Test fun historyKeysSortByTime() {
        val keys = listOf(5L, 1_000L, 20L, now).map { LocationPointCodec.key(it) }
        assertEquals(keys.sorted(), listOf(5L, 20L, 1_000L, now).map { LocationPointCodec.key(it) })
        assertEquals(13, LocationPointCodec.key(now).length)
    }

    // ---------------------------------------------------------------- state and reasons

    @Test fun stateSurvivesTheRoundTripAndUnknownPermissionMeansNone() {
        val s = RecoveryState(now, true, LocationPermission.ALWAYS, true, now - 5_000L)
        assertEquals(s, RecoveryStateCodec.fromJson(RecoveryStateCodec.toJson(s)))
        val odd = RecoveryStateCodec.fromJson(JsonObject().apply { addProperty("ts", 1L); addProperty("perm", "root") })!!
        assertEquals(LocationPermission.NONE, odd.permission)
        assertNull(RecoveryStateCodec.fromJson(JsonObject()))
    }

    @Test fun locationIsBlockedForTheRightReasonInTheRightOrder() {
        assertEquals(LocationBlock.OFF, LocationRules.blockFor(false, LocationPermission.ALWAYS, true))
        assertEquals("off beats everything, a parent who didn't switch it on never gets a permission nag", LocationBlock.OFF, LocationRules.blockFor(false, LocationPermission.NONE, false))
        assertEquals(LocationBlock.NO_PERMISSION, LocationRules.blockFor(true, LocationPermission.NONE, true))
        assertEquals(LocationBlock.SERVICES_OFF, LocationRules.blockFor(true, LocationPermission.ALWAYS, false))
        assertEquals(LocationBlock.ONLY_WHILE_OPEN, LocationRules.blockFor(true, LocationPermission.WHILE_OPEN, true))
        assertNull(LocationRules.blockFor(true, LocationPermission.ALWAYS, true))
    }

    @Test fun everyBlockHasAnExplanation() {
        for (b in LocationBlock.values()) assertTrue(LocationRules.text(b).length > 30)
    }

    // ---------------------------------------------------------------- schedule and retention

    @Test fun aPositionIsDueOnTheChosenInterval() {
        assertTrue("never read yet", LocationRules.due(0L, now, 15))
        assertFalse(LocationRules.due(now - 5 * 60_000L, now, 15))
        assertTrue(LocationRules.due(now - 15 * 60_000L, now, 15))
        assertTrue("a tick that comes a little early still counts", LocationRules.due(now - 14 * 60_000L - 30_000L, now, 15))
        assertTrue("a clock that jumped back must not stop reading forever", LocationRules.due(now + day, now, 15))
    }

    @Test fun historyOlderThanTheRetentionIsPruned() {
        val keys = listOf(now - 10 * day, now - 8 * day, now - 6 * day, now - day, now).map { LocationPointCodec.key(it) }
        val gone = LocationRules.expiredKeys(keys, now, 7)
        assertEquals(setOf(keys[0], keys[1]), gone.toSet())
        assertEquals(keys.toSet() - gone.toSet(), keys.drop(2).toSet())
        assertEquals("one day keeps exactly the last day", keys.take(3).toSet(), LocationRules.expiredKeys(keys, now, 1).toSet())
    }

    @Test fun historyIsAlsoCappedByCount() {
        val keys = (1..50).map { LocationPointCodec.key(now - it * 60_000L) }
        val gone = LocationRules.expiredKeys(keys, now, 30, maxPoints = 10)
        assertEquals(40, gone.size)
        val kept = keys.toSet() - gone.toSet()
        assertEquals("the newest ten stay", (1..10).map { LocationPointCodec.key(now - it * 60_000L) }.toSet(), kept)
    }

    @Test fun keysThatAreNotTimesAreCleanedUp() {
        val gone = LocationRules.expiredKeys(listOf("oops", LocationPointCodec.key(now)), now, 7)
        assertEquals(listOf("oops"), gone)
    }

    @Test fun aRecentPositionCanBeReusedAnOldOneCannot() {
        assertTrue(LocationRules.reusable(now - 30_000L, now))
        assertFalse(LocationRules.reusable(now - 5 * 60_000L, now))
        assertFalse(LocationRules.reusable(0L, now))
        assertFalse("a position from the future is not reused", LocationRules.reusable(now + 10_000L, now))
    }

    // ---------------------------------------------------------------- display helpers

    @Test fun mapLinksAreSafeAndOpenTheRightSpot() {
        val p = LocationPoint(now, 28.613939, 77.209021, 10f, "gps")
        assertEquals("geo:28.613939,77.209021?q=28.613939,77.209021(Aarav's+phone)", LocationRules.geoUri(p, "Aarav's phone"))
        val tricky = LocationRules.geoUri(p, "x)&q=evil(")
        assertFalse(tricky.contains("&"))
        assertEquals(1, tricky.count { it == '(' })
        assertEquals("geo:28.613939,77.209021?q=28.613939,77.209021", LocationRules.geoUri(p, "  "))
    }

    @Test fun accuracyIsDescribedPlainly() {
        assertEquals("within about 18 m", LocationRules.accuracyText(18.4f))
        assertEquals("within about 2.5 km", LocationRules.accuracyText(2_500f))
        assertEquals("accuracy unknown", LocationRules.accuracyText(0f))
        assertEquals("28.61394, 77.20902", LocationRules.coordinates(LocationPoint(now, 28.613939, 77.209021, 1f, "")))
    }

    @Test fun theChildIsToldPlainlyWhatTheParentCanDoAndWhatIsOn() {
        val on = RecoverySettings(true, 15, 7, now)
        fun view(s: RecoverySettings?, p: LocationPermission, services: Boolean = true) = RecoveryChildText.location(RecoveryLocalView(s, p, services))
        assertTrue(view(null, LocationPermission.NONE).contains("waiting"))
        assertTrue(view(RecoverySettings.OFF, LocationPermission.ALWAYS).contains("hasn't switched"))
        assertTrue(view(on, LocationPermission.NONE).contains("haven't allowed"))
        assertTrue(view(on, LocationPermission.WHILE_OPEN).contains("Allow all the time"))
        assertTrue(view(on, LocationPermission.ALWAYS, services = false).contains("switched off in this phone's settings"))
        val live = view(on.copy(retentionDays = 1), LocationPermission.ALWAYS)
        assertTrue(live, live.contains("about every 15 minutes, kept for 1 day."))
        assertTrue(view(on, LocationPermission.ALWAYS).contains("kept for 7 days"))
        assertTrue(RecoveryChildText.WHAT_PARENT_CAN_DO.contains("never turns on your camera or microphone"))
    }

    @Test fun noCameraOrMicrophoneFeatureExistsAndTheTextSaysSo() {
        assertTrue(RecoveryTexts.NO_CAMERA_MIC.contains("camera"))
        assertTrue(ControlType.values().none { it.code.contains("camera") || it.code.contains("mic") || it.code.contains("record") })
    }
}
