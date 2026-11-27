package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.AlertKind
import com.sirrlevi.guardian.core.AlertLevel
import com.sirrlevi.guardian.core.CommDirection
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.SafetyAlert
import com.sirrlevi.guardian.core.SafetyIds
import com.sirrlevi.guardian.core.SafetyInput
import com.sirrlevi.guardian.core.SafetySettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone
import kotlin.coroutines.EmptyCoroutineContext

/** The child's phone and the database in memory. */
class FakeSafetyBackend(var inputNow: SafetyInput) : SafetyBackend {
    var settingsNow = SafetySettings()
    val dismissedNow = LinkedHashMap<String, Long>()
    val eventsNow = LinkedHashMap<String, SafetyAlert>()
    var permsNow: Map<String, Boolean>? = null
    var failRead: Exception? = null
    var failWrite: Exception? = null
    var failEventWrite: Exception? = null
    var failHousekeeping: Exception? = null
    var inputCalls = 0
    val savedSettings = ArrayList<SafetySettings>()
    var savedPerms = 0
    var savedEvents = 0
    val deletedEvents = ArrayList<String>()
    val restored = ArrayList<String>()

    private fun read() { failRead?.let { throw it } }
    private fun write() { failWrite?.let { throw it } }

    override fun input(): SafetyInput { read(); inputCalls++; return inputNow }
    override fun settings(): SafetySettings { read(); return settingsNow }
    override fun saveSettings(settings: SafetySettings) { write(); savedSettings.add(settings); settingsNow = settings }
    override fun dismissed(): Map<String, Long> { read(); return dismissedNow.toMap() }
    override fun dismiss(id: String, at: Long) { write(); dismissedNow[id] = at }
    override fun restore(id: String) { failHousekeeping?.let { throw it }; write(); restored.add(id); dismissedNow.remove(id) }
    override fun events(): List<SafetyAlert> { read(); return eventsNow.values.toList() }
    override fun saveEvent(alert: SafetyAlert) { failEventWrite?.let { throw it }; write(); savedEvents++; eventsNow[alert.id] = alert }
    override fun deleteEvent(id: String) { failHousekeeping?.let { throw it }; write(); deletedEvents.add(id); eventsNow.remove(id) }
    override fun perms(): Map<String, Boolean>? { read(); return permsNow }
    override fun savePerms(perms: Map<String, Boolean>) { write(); savedPerms++; permsNow = perms }
}

/** Plain JVM test on purpose (see ScreenPreviewModelTest): the model only needs Compose's state runtime. */
class SafetyModelTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour
    private var time = 1_800_000_000_000L
    private fun input(usage: Boolean? = null, perms: Map<String, Boolean> = emptyMap(), now: Long = time, comms: List<CommRecord> = emptyList()) =
        SafetyInput("Aarav's phone", now, utc, comms = comms, reportedAt = now - 60_000L, usageAccess = usage, perms = perms, lastSeenMs = now - 60_000L)

    private val backend = FakeSafetyBackend(input(usage = false))
    private val model = SafetyModel({ backend }, { time }, io = EmptyCoroutineContext)

    private fun message(id: String, text: String) = CommRecord(id, "com.whatsapp", "WhatsApp", "Sam", null, false, text, time - hour, "msg", CommDirection.INCOMING, true)

    @Test fun beforeTheFirstCheckNothingIsShown() {
        assertEquals(SafetyModel.Phase.LOADING, model.phase)
        assertTrue(model.active.isEmpty())
        assertTrue(model.dismissed.isEmpty())
        assertEquals(0L, model.checkedAt)
    }

    @Test fun aCheckListsWhatTheRulesFoundWithTheirReasons() = runBlocking {
        model.tick()
        assertEquals(SafetyModel.Phase.READY, model.phase)
        val a = model.active.single()
        assertEquals("Usage access is off", a.title)
        assertTrue(a.reason.contains("as the phone last reported"))
        assertNull(model.error)
        assertEquals(time, model.checkedAt)
    }

    @Test fun whenNothingIsFoundTheListIsEmptyButTheCheckStillCountsAsDone() = runBlocking {
        backend.inputNow = input(usage = true)
        model.tick()
        assertEquals(SafetyModel.Phase.READY, model.phase)
        assertTrue(model.active.isEmpty())
        assertEquals(time, model.checkedAt)
    }

    @Test fun aFirstCheckThatCannotReadAnythingSaysSoAndNeverLooksLikeAllClear() = runBlocking {
        backend.failRead = IOException("Unable to resolve host \"x\"")
        val wait = model.tick()
        assertEquals(SafetyModel.Phase.FAILED, model.phase)
        assertEquals("No internet connection.", model.error)
        assertEquals(SafetyModel.RETRY_MS, wait)
        assertTrue(model.active.isEmpty())
        assertEquals("a failed check is not a check", 0L, model.checkedAt)
    }

    @Test fun aLaterFailureKeepsTheLastResultAndSaysItIsOld() = runBlocking {
        model.tick()
        val before = model.checkedAt
        time += 5 * 60_000L
        backend.failRead = IOException("boom")
        model.tick()
        assertEquals(SafetyModel.Phase.READY, model.phase)
        assertEquals("boom", model.error)
        assertEquals("Usage access is off", model.active.single().title)
        assertEquals(before, model.checkedAt)
        backend.failRead = null
        model.tick()
        assertNull(model.error)
        assertEquals(time, model.checkedAt)
    }

    // ---------------------------------------------------------------- dismiss and bring back

    @Test fun dismissingMovesTheAlertAndRemembersItInTheDatabase() = runBlocking {
        model.tick()
        val id = model.active.single().id
        assertTrue(model.dismiss(id))
        assertTrue(model.active.isEmpty())
        assertEquals(id, model.dismissed.single().id)
        assertEquals(time, backend.dismissedNow[id])
        assertFalse(model.noticeIsProblem)
    }

    @Test fun aDismissedAlertStaysDismissedOnTheNextCheckAndOnAnotherDevice() = runBlocking {
        model.tick()
        model.dismiss(model.active.single().id)
        time += 60_000L
        backend.inputNow = input(usage = false)
        model.tick()
        assertTrue(model.active.isEmpty())
        assertEquals(1, model.dismissed.size)
        // A new parent session starts from the database, not from memory.
        val other = SafetyModel({ backend }, { time }, io = EmptyCoroutineContext)
        other.tick()
        assertTrue(other.active.isEmpty())
        assertEquals(1, other.dismissed.size)
    }

    @Test fun aDismissedAlertCanBeBroughtBack() = runBlocking {
        model.tick()
        val id = model.active.single().id
        model.dismiss(id)
        assertTrue(model.restore(id))
        assertEquals(id, model.active.single().id)
        assertTrue(model.dismissed.isEmpty())
        assertTrue(backend.dismissedNow.isEmpty())
    }

    @Test fun aFailedDismissalChangesNothingAndSaysWhy() = runBlocking {
        model.tick()
        val id = model.active.single().id
        backend.failWrite = IOException("denied")
        assertFalse(model.dismiss(id))
        assertEquals(id, model.active.single().id)
        assertTrue(model.dismissed.isEmpty())
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.startsWith(SafetyModel.DISMISS_FAILED))
        assertTrue(model.notice!!.contains("denied"))
        assertFalse(model.busy)
    }

    // ---------------------------------------------------------------- settings

    @Test fun turningAKindOffRemovesItsAlertsAndIsSaved() = runBlocking {
        model.tick()
        assertTrue(model.setKind(AlertKind.MONITORING_OFF, false))
        assertTrue(model.active.isEmpty())
        val saved = backend.savedSettings.single()
        assertFalse(saved.on(AlertKind.MONITORING_OFF))
        assertEquals(time, saved.ts)
        assertTrue(model.setKind(AlertKind.MONITORING_OFF, true))
        assertEquals(1, model.active.size)
    }

    @Test fun limitsAreKeptSensibleAndSaved() = runBlocking {
        model.tick()
        assertTrue(model.setLimits(lateFromHour = 3, lateToHour = 20, usageHours = 0, disconnectedHours = 500))
        val s = model.settings
        assertEquals(18, s.lateFromHour); assertEquals(9, s.lateToHour); assertEquals(1, s.usageHours); assertEquals(48, s.disconnectedHours)
        assertEquals(s, backend.savedSettings.single())
    }

    @Test fun aFailedSaveKeepsTheOldSettingsAndSaysWhy() = runBlocking {
        model.tick()
        backend.failWrite = IOException("offline")
        assertFalse(model.setKind(AlertKind.MONITORING_OFF, false))
        assertTrue(model.settings.on(AlertKind.MONITORING_OFF))
        assertEquals(1, model.active.size)
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.startsWith(SafetyModel.SETTINGS_FAILED))
    }

    @Test fun settingsComeFromTheDatabaseAtEachCheck() = runBlocking {
        backend.settingsNow = SafetySettings(off = setOf(AlertKind.MONITORING_OFF))
        model.tick()
        assertTrue(model.active.isEmpty())
        assertFalse(model.settings.on(AlertKind.MONITORING_OFF))
    }

    // ---------------------------------------------------------------- permission changes

    @Test fun theFirstSnapshotClaimsNothingAndIsSaved() = runBlocking {
        backend.inputNow = input(perms = mapOf("usage" to false, "overlay" to true))
        model.tick()
        assertTrue(model.active.none { it.kind == AlertKind.PERMISSION_CHANGE })
        assertEquals(mapOf("usage" to false, "overlay" to true), backend.permsNow)
    }

    @Test fun aPermissionSwitchedOffSinceTheLastSnapshotIsReportedOnceAndKept() = runBlocking {
        backend.permsNow = mapOf("usage" to true, "overlay" to true)
        backend.inputNow = input(perms = mapOf("usage" to false, "overlay" to true))
        model.tick()
        val a = model.active.single { it.kind == AlertKind.PERMISSION_CHANGE }
        assertEquals("Usage access was switched off", a.title)
        assertEquals(1, backend.savedEvents)
        assertEquals(false, backend.permsNow!!["usage"])
        // The next checks do not report it again, but it is still there to review.
        time += 60_000L
        backend.inputNow = input(perms = mapOf("usage" to false, "overlay" to true))
        model.tick(); model.tick()
        assertEquals(1, backend.savedEvents)
        assertEquals(a.id, model.active.single { it.kind == AlertKind.PERMISSION_CHANGE }.id)
    }

    @Test fun anAlertThatCouldNotBeSavedIsFoundAgainNextTime() = runBlocking {
        backend.permsNow = mapOf("usage" to true)
        backend.inputNow = input(perms = mapOf("usage" to false))
        backend.failEventWrite = IOException("full")
        model.tick()
        assertEquals("full", model.error)
        assertEquals("the snapshot is only updated after the alert was saved", true, backend.permsNow!!["usage"])
        backend.failEventWrite = null
        model.tick()
        assertEquals(1, model.active.count { it.kind == AlertKind.PERMISSION_CHANGE })
        assertEquals(false, backend.permsNow!!["usage"])
    }

    @Test fun aPhoneThatReportsNoPermissionsLeavesTheSnapshotAlone() = runBlocking {
        backend.permsNow = mapOf("usage" to true)
        backend.inputNow = input(perms = emptyMap())
        model.tick()
        assertEquals(0, backend.savedPerms)
        assertEquals(mapOf("usage" to true), backend.permsNow)
    }

    @Test fun aPermissionTheOldPhoneAppDoesNotReportIsKeptNotForgotten() = runBlocking {
        backend.permsNow = mapOf("usage" to true, "notifaccess" to true)
        backend.inputNow = input(perms = mapOf("usage" to true, "overlay" to true))
        model.tick()
        assertEquals(mapOf("usage" to true, "notifaccess" to true, "overlay" to true), backend.permsNow)
    }

    @Test fun permissionAlertsFollowTheirSwitch() = runBlocking {
        backend.permsNow = mapOf("usage" to true)
        backend.inputNow = input(perms = mapOf("usage" to false))
        model.tick()
        model.setKind(AlertKind.PERMISSION_CHANGE, false)
        assertTrue(model.active.none { it.kind == AlertKind.PERMISSION_CHANGE })
        model.setKind(AlertKind.PERMISSION_CHANGE, true)
        assertTrue(model.active.any { it.kind == AlertKind.PERMISSION_CHANGE })
    }

    // ---------------------------------------------------------------- housekeeping

    private fun oldEvent(ageDays: Int) = SafetyAlert(
        SafetyIds.id(AlertKind.PERMISSION_CHANGE, "old$ageDays"), AlertKind.PERMISSION_CHANGE, AlertLevel.REVIEW, "Old", "Seen long ago, with a reason.", time - ageDays * day
    )

    @Test fun oldSavedAlertsAndOldDismissalsAreCleanedUpAndNeverShown() = runBlocking {
        val old = oldEvent(40); val recent = oldEvent(3)
        backend.eventsNow[old.id] = old; backend.eventsNow[recent.id] = recent
        backend.dismissedNow["words_aaaaaaaaaaaa"] = time - 70 * day
        backend.dismissedNow["words_bbbbbbbbbbbb"] = time - 2 * day
        model.tick()
        assertEquals(listOf(old.id), backend.deletedEvents)
        assertEquals(listOf("words_aaaaaaaaaaaa"), backend.restored)
        assertTrue(model.active.any { it.id == recent.id })
        assertTrue(model.active.none { it.id == old.id })
    }

    @Test fun cleanupFailuresNeverFailACheck() = runBlocking {
        backend.eventsNow[oldEvent(40).id] = oldEvent(40)
        backend.failHousekeeping = IOException("nope")
        model.tick()
        assertEquals(SafetyModel.Phase.READY, model.phase)
        assertNull(model.error)
    }

    @Test fun damagedStoredAlertsAreSkippedByTheRepositoryNotShown() {
        // The codec drops them before they reach the model; this documents that the model never sees a half alert.
        assertNull(com.sirrlevi.guardian.core.SafetyAlertCodec.fromJson("bad id", com.google.gson.JsonObject()))
    }

    // ---------------------------------------------------------------- scheduling

    @Test fun theQuietCheckRunsOnceThenWaits() = runBlocking {
        model.checkIfDue()
        assertEquals(1, backend.inputCalls)
        time += 5 * 60_000L
        model.checkIfDue()
        assertEquals("too soon", 1, backend.inputCalls)
        time += 6 * 60_000L
        model.checkIfDue()
        assertEquals(2, backend.inputCalls)
    }

    @Test fun nothingIsCheckedWhileTheAppIsInTheBackground() = runBlocking {
        model.foreground = false
        model.checkIfDue()
        assertEquals(0, backend.inputCalls)
    }

    @Test fun aFailedQuietCheckIsRetriedAtTheNextDueTime() = runBlocking {
        backend.failRead = IOException("x")
        model.checkIfDue()
        assertEquals(SafetyModel.Phase.FAILED, model.phase)
        time += 11 * 60_000L
        backend.failRead = null
        model.checkIfDue()
        assertEquals(SafetyModel.Phase.READY, model.phase)
    }

    @Test fun resetForgetsEverythingAboutThePreviousPhone() = runBlocking {
        model.tick()
        model.dismiss(model.active.single().id)
        model.reset()
        assertEquals(SafetyModel.Phase.LOADING, model.phase)
        assertTrue(model.active.isEmpty())
        assertTrue(model.dismissed.isEmpty())
        assertEquals(0L, model.checkedAt)
        assertNull(model.notice)
        assertNotNull(model.settings)
    }

    @Test fun messageRulesReachTheList() = runBlocking {
        backend.inputNow = input(usage = true, comms = listOf(message("m1", "this is our little secret")))
        model.tick()
        val a = model.active.single()
        assertEquals(AlertKind.SUSPICIOUS_WORDS, a.kind)
        assertEquals("Potentially suspicious content", a.label)
    }
}
