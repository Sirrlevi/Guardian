package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.LocationPermission
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.RecoveryPolicy
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoveryState
import com.sirrlevi.guardian.core.RecoveryTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** The phone and the database in memory. */
class FakeRecoveryBackend : RecoveryBackend {
    var settingsNow: RecoverySettings? = null
    var stateNow: RecoveryState? = null
    var lastNow: LocationPoint? = null
    var historyNow: List<LocationPoint> = emptyList()
    val saved = ArrayList<RecoverySettings>()
    var cleared = 0
    var historyCalls = 0
    var failWith: Exception? = null
    var failSave: Exception? = null

    private fun check() { failWith?.let { throw it } }
    override fun settings(): RecoverySettings? { check(); return settingsNow }
    override fun saveSettings(settings: RecoverySettings) { check(); failSave?.let { throw it }; saved.add(settings); settingsNow = settings }
    override fun state(): RecoveryState? { check(); return stateNow }
    override fun last(): LocationPoint? { check(); return lastNow }
    override fun history(limit: Int): List<LocationPoint> { check(); historyCalls++; return historyNow.take(limit) }
    override fun clearHistory() { check(); cleared++; historyNow = emptyList(); lastNow = null }
}

/** Plain JVM test on purpose (see ScreenPreviewModelTest): the model only needs Compose's state runtime. */
class RecoveryModelTest {
    private var time = 1_800_000_000_000L
    private val backend = FakeRecoveryBackend()
    private val model = RecoveryModel({ backend }, { time }, io = EmptyCoroutineContext)

    private fun state(perm: LocationPermission = LocationPermission.ALWAYS, services: Boolean = true, sharing: Boolean = true) =
        RecoveryState(time, sharing, perm, services, time - 60_000L)

    private fun point(ts: Long = time - 60_000L) = LocationPoint(ts, 28.6139, 77.2090, 15f, "gps")

    @Test fun nothingIsSharedUntilTheParentTurnsItOn() = runBlocking {
        model.tick()
        assertTrue(model.loaded)
        assertFalse(model.sharing)
        assertEquals(RecoverySettings.OFF, model.effective)
        assertEquals(RecoveryTexts.OFF, model.locateOffer.why)
        assertFalse(model.locateOffer.available)
    }

    @Test fun beforeTheFirstRefreshNothingIsOffered() {
        assertFalse(model.loaded)
        assertFalse(model.locateOffer.available)
    }

    @Test fun turningItOnSavesTheChoiceAndTellsTheParentWhatHappensNext() = runBlocking {
        model.tick()
        assertTrue(model.setEnabled(true))
        val saved = backend.saved.single()
        assertTrue(saved.enabled)
        assertEquals(RecoveryPolicy.DEFAULT_INTERVAL_MIN, saved.intervalMin)
        assertEquals(time, saved.ts)
        assertTrue(model.sharing)
        assertTrue(model.notice!!.contains("allow location on their phone"))
        assertFalse(model.noticeIsProblem)
    }

    @Test fun turningItOffSavesAndSaysSavedPositionsStay() = runBlocking {
        backend.settingsNow = RecoverySettings(true, 15, 7, time)
        model.tick()
        assertTrue(model.setEnabled(false))
        assertFalse(backend.saved.single().enabled)
        assertFalse(model.sharing)
        assertTrue(model.notice!!.contains("stay until they expire"))
    }

    @Test fun aSaveThatFailsIsReportedAndTheOldChoiceStaysOnScreen() = runBlocking {
        backend.settingsNow = RecoverySettings(false, 15, 7, time)
        model.tick()
        backend.failSave = IOException("Unable to resolve host")
        assertFalse(model.setEnabled(true))
        assertFalse("the screen must not claim sharing is on", model.sharing)
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.contains("No internet connection"))
    }

    @Test fun onlyTheOfferedIntervalsAndRetentionsCanBeChosen() = runBlocking {
        backend.settingsNow = RecoverySettings(true, 15, 7, time)
        model.tick()
        assertFalse(model.setInterval(1))
        assertFalse(model.setRetention(365))
        assertTrue(backend.saved.isEmpty())
        assertTrue(model.setInterval(30))
        assertTrue(model.setRetention(30))
        assertEquals(30, backend.saved.last().intervalMin)
        assertEquals(30, backend.saved.last().retentionDays)
        assertTrue("changing one setting keeps the others", backend.saved.last().enabled)
    }

    @Test fun findNowIsOfferedOnlyWhenEverythingNeededIsInPlace() = runBlocking {
        backend.settingsNow = RecoverySettings(true, 15, 7, time)
        model.tick()
        assertEquals("on, but the phone hasn't reported", RecoveryTexts.NO_REPORT, model.locateOffer.why)
        backend.stateNow = state(LocationPermission.NONE); model.tick()
        assertEquals(RecoveryTexts.NO_PERMISSION, model.locateOffer.why)
        backend.stateNow = state(LocationPermission.WHILE_OPEN); model.tick()
        assertEquals(RecoveryTexts.ONLY_WHILE_OPEN, model.locateOffer.why)
        backend.stateNow = state(services = false); model.tick()
        assertEquals(RecoveryTexts.SERVICES_OFF, model.locateOffer.why)
        backend.stateNow = state(); model.tick()
        assertTrue(model.locateOffer.available)
        assertNull(model.block)
    }

    @Test fun theParentsOwnSwitchDecidesNotTheChildsOldReport() = runBlocking {
        // The child still says "sharing" for up to a minute after the parent turned it off.
        backend.settingsNow = RecoverySettings(false, 15, 7, time)
        backend.stateNow = state(sharing = true)
        model.tick()
        assertFalse(model.locateOffer.available)
        assertEquals(RecoveryTexts.OFF, model.locateOffer.why)
    }

    @Test fun theLastPositionIsShownWithItsRealTimeAndNeverInvented() = runBlocking {
        model.tick()
        assertNull(model.last)
        backend.lastNow = point(time - 5 * 60_000L)
        model.tick()
        assertEquals(time - 5 * 60_000L, model.last!!.ts)
    }

    @Test fun anOldReportFromThePhoneIsRecognised() = runBlocking {
        backend.stateNow = state()
        model.tick()
        assertFalse(model.stateIsOld)
        time += RecoveryPolicy.STATE_STALE_MS + 1_000L
        model.tick()
        assertTrue(model.stateIsOld)
    }

    @Test fun historyIsFetchedAtFirstThenOnlyNowAndThen() = runBlocking {
        backend.historyNow = listOf(point(time - 1_000L), point(time - 2_000L))
        model.tick()
        assertEquals(2, model.history.size)
        assertEquals(1, backend.historyCalls)
        time += 10_000L; model.tick()
        assertEquals("not again after 10 s", 1, backend.historyCalls)
        time += RecoveryModel.HISTORY_EVERY_MS; model.tick()
        assertEquals(2, backend.historyCalls)
        model.refreshNow()
        assertEquals("refreshNow always fetches it", 3, backend.historyCalls)
    }

    @Test fun whenTheServerCannotBeReachedTheOldDataIsKeptAndTheErrorIsShown() = runBlocking {
        backend.lastNow = point()
        model.tick()
        backend.failWith = IOException("timeout")
        val wait = model.tick()
        assertEquals(RecoveryModel.RETRY_MS, wait)
        assertNotNull(model.error)
        assertNotNull("the last known position stays visible, with its real age", model.last)
        backend.failWith = null
        model.tick()
        assertNull(model.error)
    }

    @Test fun deletingTheHistoryClearsEverythingShownIncludingTheNewest() = runBlocking {
        backend.lastNow = point()
        backend.historyNow = listOf(point(), point(time - 9_000L))
        model.tick()
        assertTrue(model.clearHistory())
        assertEquals(1, backend.cleared)
        assertTrue(model.history.isEmpty())
        assertNull(model.last)
        assertTrue(model.notice!!.contains("deleted"))
    }

    @Test fun aFailedDeleteDoesNotPretendItWorked() = runBlocking {
        backend.lastNow = point()
        model.tick()
        backend.failWith = IOException("offline")
        assertFalse(model.clearHistory())
        assertNotNull("the position is still there", model.last)
        assertTrue(model.noticeIsProblem)
    }

    @Test fun resetForgetsEverythingWhenAnotherPhoneIsChosen() = runBlocking {
        backend.settingsNow = RecoverySettings(true, 15, 7, time)
        backend.lastNow = point()
        model.tick()
        model.reset()
        assertFalse(model.loaded)
        assertNull(model.last)
        assertNull(model.settings)
        assertFalse(model.sharing)
        assertTrue(model.history.isEmpty())
    }
}
