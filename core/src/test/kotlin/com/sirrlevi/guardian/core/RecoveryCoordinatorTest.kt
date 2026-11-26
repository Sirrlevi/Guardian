package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class RecoveryFakeStore : RecoveryStore {
    var settings: RecoverySettings? = null
    var settingsReadFails = false
    var saveFails = false
    var historyFails = false
    val saved = ArrayList<LocationPoint>()
    var keys = ArrayList<String>()
    val deleted = ArrayList<String>()
    val states = ArrayList<RecoveryState>()
    var settingsReads = 0

    override fun readSettings(): RecoverySettings? {
        settingsReads++
        if (settingsReadFails) throw java.io.IOException("offline")
        return settings
    }
    override fun saveFix(point: LocationPoint) {
        if (saveFails) throw java.io.IOException("offline")
        saved.add(point)
        keys.add(LocationPointCodec.key(point.ts))
    }
    override fun historyKeys(): List<String> {
        if (historyFails) throw java.io.IOException("offline")
        return keys.toList()
    }
    override fun deletePoints(keys: List<String>) { deleted.addAll(keys); this.keys.removeAll(keys.toSet()) }
    override fun writeState(state: RecoveryState) { states.add(state) }
}

private class RecoveryFakeSource(private val clock: () -> Long) : LocationSource {
    var perm = LocationPermission.ALWAYS
    var services = true
    var reads = 0
    var found = true
    var throwOnRead: Exception? = null
    var lastTimeout = 0L
    override fun permission() = perm
    override fun servicesOn() = services
    override fun read(timeoutMs: Long): LocationPoint? {
        reads++
        lastTimeout = timeoutMs
        throwOnRead?.let { throw it }
        return if (found) LocationPoint(clock(), 28.6139, 77.2090, 12f, "gps") else null
    }
}

class RecoveryCoordinatorTest {
    private var time = 1_800_000_000_000L
    private lateinit var store: RecoveryFakeStore
    private lateinit var source: RecoveryFakeSource
    private lateinit var coordinator: RecoveryCoordinator

    @Before fun setUp() {
        store = RecoveryFakeStore(); source = RecoveryFakeSource { time }
        coordinator = RecoveryCoordinator(store, source, { time })
    }

    private fun on(every: Int = 15, keep: Int = 7) { store.settings = RecoverySettings(true, every, keep, time) }
    private fun advance(ms: Long) { time += ms }

    // ---------------------------------------------------------------- nothing is read without the parent's switch

    @Test fun nothingIsReadWhileTheParentHasNotSwitchedItOn() {
        coordinator.tick()
        assertEquals(0, source.reads)
        assertTrue(store.saved.isEmpty())
        assertFalse(store.states.last().sharing)
    }

    @Test fun nothingIsReadBeforeTheSettingsWereReadOnce() {
        store.settingsReadFails = true
        coordinator.tick()
        assertEquals("settings unknown means nothing is shared", 0, source.reads)
    }

    @Test fun switchedOffAgainStopsReading() {
        on()
        coordinator.tick()
        assertEquals(1, source.reads)
        store.settings = RecoverySettings(false, 15, 7, time)
        advance(RecoveryPolicy.SETTINGS_POLL_MS + 1_000L)
        coordinator.tick()
        advance(20 * 60_000L)
        coordinator.tick()
        assertEquals(1, source.reads)
        assertFalse(store.states.last().sharing)
    }

    @Test fun noPermissionNoReadAndTheStateSaysWhy() {
        on()
        source.perm = LocationPermission.NONE
        coordinator.tick()
        assertEquals(0, source.reads)
        val state = store.states.last()
        assertEquals(LocationPermission.NONE, state.permission)
        assertEquals(LocationBlock.NO_PERMISSION, LocationRules.blockFor(state.sharing, state.permission, state.servicesOn))
    }

    @Test fun locationSwitchedOffInAndroidIsReportedNotWorkedAround() {
        on()
        source.services = false
        coordinator.tick()
        assertEquals(0, source.reads)
        assertFalse(store.states.last().servicesOn)
    }

    @Test fun onlyWhileOpenPermissionDoesNotReadInTheBackground() {
        on()
        source.perm = LocationPermission.WHILE_OPEN
        coordinator.tick()
        assertEquals(0, source.reads)
        assertEquals(LocationPermission.WHILE_OPEN, store.states.last().permission)
    }

    // ---------------------------------------------------------------- the schedule

    @Test fun positionsAreReadOnTheChosenInterval() {
        on(every = 15)
        coordinator.tick()
        assertEquals(1, store.saved.size)
        advance(5 * 60_000L); coordinator.tick()
        assertEquals("too soon", 1, store.saved.size)
        advance(10 * 60_000L); coordinator.tick()
        assertEquals(2, store.saved.size)
    }

    @Test fun aScheduledReadDoesNotKeepThePhoneWaitingLong() {
        on()
        coordinator.tick()
        assertEquals(RecoveryPolicy.SCHEDULED_TIMEOUT_MS, source.lastTimeout)
    }

    @Test fun aFailedReadIsRetriedLaterNotInATightLoop() {
        on()
        source.found = false
        coordinator.tick()
        assertEquals(1, source.reads)
        advance(2_000L); coordinator.tick()
        advance(2_000L); coordinator.tick()
        assertEquals("no hammering the GPS while indoors", 1, source.reads)
        advance(RecoveryPolicy.RETRY_AFTER_FAILURE_MS); source.found = true; coordinator.tick()
        assertEquals(2, source.reads)
        assertEquals(1, store.saved.size)
    }

    @Test fun aCrashWhileReadingIsContainedAndRetriedLater() {
        on()
        source.throwOnRead = SecurityException("revoked")
        coordinator.tick()
        assertEquals(1, source.reads)
        advance(2_000L); coordinator.tick()
        assertEquals(1, source.reads)
        source.throwOnRead = null
        advance(RecoveryPolicy.RETRY_AFTER_FAILURE_MS); coordinator.tick()
        assertEquals(1, store.saved.size)
    }

    @Test fun aReadingThatIsNotARealPositionIsNeverSaved() {
        on()
        val bogus = object : LocationSource {
            override fun permission() = LocationPermission.ALWAYS
            override fun servicesOn() = true
            override fun read(timeoutMs: Long) = LocationPoint(time, 0.0, 0.0, 5f, "gps")
        }
        RecoveryCoordinator(store, bogus, { time }).tick()
        assertTrue(store.saved.isEmpty())
    }

    @Test fun anOfflinePhoneKeepsTryingWithoutCrashing() {
        on()
        store.saveFails = true
        coordinator.tick()
        assertTrue(store.saved.isEmpty())
        store.saveFails = false
        advance(RecoveryPolicy.RETRY_AFTER_FAILURE_MS); coordinator.tick()
        assertEquals(1, store.saved.size)
    }

    @Test fun settingsAreCheckedAboutOnceAMinuteNotEveryTick() {
        on()
        repeat(20) { coordinator.tick(); advance(2_000L) }
        assertTrue("reads=${store.settingsReads}", store.settingsReads <= 2)
    }

    // ---------------------------------------------------------------- find now

    @Test fun findNowReadsAFreshPositionWhenSharingIsOn() {
        on()
        val outcome = coordinator.locate()
        assertEquals(ControlResult.DONE, outcome.result)
        assertEquals(RecoveryTexts.LOCATED, outcome.reason)
        assertEquals(1, store.saved.size)
        assertEquals(RecoveryPolicy.LOCATE_TIMEOUT_MS, source.lastTimeout)
    }

    @Test fun findNowIsRefusedWhenSharingIsOffAndNothingIsRead() {
        store.settings = RecoverySettings(false, 15, 7, time)
        val outcome = coordinator.locate()
        assertEquals(ControlResult.REJECTED, outcome.result)
        assertEquals(RecoveryTexts.OFF, outcome.reason)
        assertEquals(0, source.reads)
    }

    @Test fun findNowNeverAssumesTheOldSwitchStillHolds() {
        on()
        coordinator.tick()
        store.settings = RecoverySettings(false, 15, 7, time)
        advance(1_000L)
        assertEquals("the parent switched it off a second ago", ControlResult.REJECTED, coordinator.locate().result)
    }

    @Test fun findNowExplainsAMissingPermission() {
        on()
        source.perm = LocationPermission.NONE
        val outcome = coordinator.locate()
        assertEquals(ControlResult.REJECTED, outcome.result)
        assertEquals(RecoveryTexts.NO_PERMISSION, outcome.reason)
        source.perm = LocationPermission.WHILE_OPEN
        assertEquals(RecoveryTexts.ONLY_WHILE_OPEN, coordinator.locate().reason)
        source.perm = LocationPermission.ALWAYS; source.services = false
        assertEquals(RecoveryTexts.SERVICES_OFF, coordinator.locate().reason)
    }

    @Test fun findNowThatGetsNoPositionSaysFailedNotDone() {
        on()
        source.found = false
        val outcome = coordinator.locate()
        assertEquals(ControlResult.FAILED, outcome.result)
        assertEquals(RecoveryTexts.NO_FIX, outcome.reason)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun findNowReusesAVeryRecentPositionButNotAnOldOne() {
        on()
        coordinator.locate()
        advance(20_000L)
        assertEquals(ControlResult.DONE, coordinator.locate().result)
        assertEquals("a 20 s old position is reused", 1, source.reads)
        advance(5 * 60_000L)
        coordinator.locate()
        assertEquals("a 5 min old one is not", 2, source.reads)
    }

    @Test fun aMissingPermissionDuringFindNowSurfacesAsAnException() {
        on()
        source.throwOnRead = SecurityException("revoked")
        var thrown = false
        try { coordinator.locate() } catch (e: SecurityException) { thrown = true }
        assertTrue("the control processor turns this into a refusal with a reason", thrown)
    }

    @Test fun findNowThatCannotReadTheSettingsFailsInsteadOfGuessing() {
        on()
        coordinator.tick()
        store.settingsReadFails = true
        var thrown = false
        try { coordinator.locate() } catch (e: java.io.IOException) { thrown = true }
        assertTrue(thrown)
    }

    // ---------------------------------------------------------------- history retention

    @Test fun oldHistoryIsCleanedUpToTheChosenRetention() {
        on(keep = 7)
        val day = 24L * 3_600_000L
        store.keys.addAll(listOf(time - 20 * day, time - 8 * day, time - 2 * day).map { LocationPointCodec.key(it) })
        coordinator.tick()
        assertEquals(2, store.deleted.size)
        assertTrue(store.keys.any { it == LocationPointCodec.key(time - 2 * day) })
    }

    @Test fun historyIsCleanedEvenWhenSharingIsOff() {
        store.settings = RecoverySettings(false, 15, 1, time)
        val day = 24L * 3_600_000L
        store.keys.addAll(listOf(time - 3 * day, time - 1000L).map { LocationPointCodec.key(it) })
        coordinator.tick()
        assertEquals(listOf(LocationPointCodec.key(time - 3 * day)), store.deleted)
    }

    @Test fun cleaningIsNotRepeatedEveryTick() {
        on()
        var calls = 0
        val counting = object : RecoveryStore by store {
            override fun historyKeys(): List<String> { calls++; return store.historyKeys() }
        }
        val c = RecoveryCoordinator(counting, source, { time })
        repeat(10) { c.tick(); advance(2_000L) }
        assertEquals(1, calls)
    }

    @Test fun aFailingCleanupDoesNotStopLocationSharing() {
        on()
        store.historyFails = true
        coordinator.tick()
        assertEquals(1, store.saved.size)
    }

    // ---------------------------------------------------------------- state reports

    @Test fun theStateIsSentWhenSomethingChangesAndOtherwiseOnlyNowAndThen() {
        on()
        coordinator.tick()
        val first = store.states.size
        advance(2_000L); coordinator.tick()
        assertEquals("nothing changed", first, store.states.size)
        source.perm = LocationPermission.NONE
        advance(2_000L); coordinator.tick()
        assertEquals("the permission changed", first + 1, store.states.size)
        advance(RecoveryPolicy.STATE_EVERY_MS + 1_000L); coordinator.tick()
        assertEquals("a regular heartbeat", first + 2, store.states.size)
    }

    @Test fun aStateThatCouldNotBeSentIsSentAgainSoon() {
        on()
        val flaky = object : RecoveryStore by store {
            var fail = true
            override fun writeState(state: RecoveryState) { if (fail) throw java.io.IOException("offline"); store.writeState(state) }
        }
        val c = RecoveryCoordinator(flaky, source, { time })
        c.tick()
        assertTrue(store.states.isEmpty())
        flaky.fail = false
        advance(11_000L); c.tick()
        assertEquals(1, store.states.size)
    }

    @Test fun theSavedPositionKeepsItsOwnTimeAndAccuracy() {
        on()
        coordinator.tick()
        val p = store.saved.single()
        assertEquals(time, p.ts)
        assertEquals(12f, p.accuracyM, 0.01f)
        assertNull(LocationPointCodec.fromJson(LocationPointCodec.toJson(p.copy(lat = 0.0, lon = 0.0))))
    }
}
