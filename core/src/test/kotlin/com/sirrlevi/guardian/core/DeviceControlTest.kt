package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ControlFakeStore : ControlStore {
    var inbox = ArrayList<ControlCommand>()
    val acks = LinkedHashMap<String, ControlAck>()
    val written = ArrayList<ControlAck>()
    val deleted = ArrayList<String>()
    val statuses = ArrayList<ControlStatus>()
    val events = ArrayList<ControlEvent>()
    var offline = false
    var failAckWrites = 0
    var failDeletes = false

    override fun readInbox(): List<ControlCommand> {
        if (offline) throw java.io.IOException("offline")
        return inbox.toList()
    }
    override fun readAck(id: String): ControlAck? {
        if (offline) throw java.io.IOException("offline")
        return acks[id]
    }
    override fun writeAck(ack: ControlAck) {
        if (offline) throw java.io.IOException("offline")
        if (failAckWrites > 0) { failAckWrites--; throw java.io.IOException("write failed") }
        acks[ack.id] = ack
        written.add(ack)
    }
    override fun deleteCommand(id: String) {
        if (failDeletes) throw java.io.IOException("delete failed")
        deleted.add(id)
        inbox.removeAll { it.id == id }
    }
    override fun writeStatus(status: ControlStatus) { statuses.add(status) }
    override fun appendLog(event: ControlEvent) { events.add(event) }
}

private class ControlFakeHost : ControlHost {
    var time = 1_800_000_000_000L
    var sdkLevel = 34
    var admin = true
    var owner = false
    var locked = false
    var screen = true
    var lockWorks = true
    var lockThrows: Exception? = null
    var rebootThrows: Exception? = null
    var lockCalls = 0
    var rebootCalls = 0
    var resetBlocked = false
    var eraseThrows: Exception? = null
    var eraseCalls = 0
    var beforeErase: (() -> Unit)? = null
    var boot = time - 3_600_000L
    var pending: PendingReboot? = null
    var lockAfterSleeps = 0
    private var sleeps = 0

    override fun now() = time
    override fun sdk() = sdkLevel
    override fun adminActive() = admin
    override fun deviceOwner() = owner
    override fun isLocked() = locked
    override fun screenOn() = screen
    override fun lockNow() {
        lockCalls++
        lockThrows?.let { throw it }
        if (lockWorks && lockAfterSleeps == 0) { locked = true; screen = false }
    }
    override fun reboot() { rebootCalls++; rebootThrows?.let { throw it } }
    override fun factoryResetBlocked() = resetBlocked
    override fun erase() { eraseCalls++; beforeErase?.invoke(); eraseThrows?.let { throw it } }
    override fun bootAt() = boot
    var ringable = true
    var ringOutcome = ControlOutcome(ControlResult.DONE, RecoveryTexts.RING_STARTED)
    var ringThrows: Exception? = null
    var ringCalls = 0
    var ringEnds = 0L
    var locateOutcome = ControlOutcome(ControlResult.DONE, RecoveryTexts.LOCATED)
    var locateThrows: Exception? = null
    var locateCalls = 0
    override fun canRing() = ringable
    override fun ring(): ControlOutcome { ringCalls++; ringThrows?.let { throw it }; return ringOutcome }
    override fun ringUntil() = ringEnds
    override fun locate(): ControlOutcome { locateCalls++; locateThrows?.let { throw it }; return locateOutcome }
    override fun pendingReboot() = pending
    override fun setPendingReboot(pending: PendingReboot?) { this.pending = pending }
    override fun sleep(ms: Long) {
        time += ms
        sleeps++
        if (lockWorks && lockAfterSleeps > 0 && sleeps >= lockAfterSleeps) { locked = true; screen = false }
    }
}

private class ControlFakeProcessed : ProcessedIds {
    var ids = emptyList<String>()
    override fun load() = ids
    override fun save(ids: List<String>) { this.ids = ids }
}

class DeviceControlTest {
    private lateinit var store: ControlFakeStore
    private lateinit var host: ControlFakeHost
    private lateinit var seen: ControlFakeProcessed
    private lateinit var processor: ControlProcessor

    @Before fun setUp() {
        store = ControlFakeStore(); host = ControlFakeHost(); seen = ControlFakeProcessed()
        processor = ControlProcessor(store, host, seen)
    }

    private fun request(type: ControlType, id: String = "cmd-0001-abcdef") =
        ControlCommand.create(id, type, host.time).also { store.inbox.add(it) }

    // ---------------------------------------------------------------- lock

    @Test fun lockIsDoneOnlyAfterThePhoneReallyLocked() {
        request(ControlType.LOCK)
        assertEquals(1, processor.poll())
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.DONE, ack.result)
        assertEquals(ControlTexts.LOCKED, ack.reason)
        assertEquals(1, host.lockCalls)
        assertTrue(store.deleted.contains("cmd-0001-abcdef"))
    }

    @Test fun lockWaitsALittleForSlowPhonesBeforeJudging() {
        host.lockAfterSleeps = 3
        request(ControlType.LOCK)
        processor.poll()
        assertEquals(ControlResult.DONE, store.acks.getValue("cmd-0001-abcdef").result)
    }

    @Test fun lockThatAndroidAcceptedButDidNotHappenIsAFailureNotASuccess() {
        host.lockWorks = false
        request(ControlType.LOCK)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.FAILED, ack.result)
        assertEquals(ControlTexts.LOCK_FAILED, ack.reason)
    }

    @Test fun lockWithoutAdminIsRefusedWithTheReasonAndNeverTried() {
        host.admin = false
        request(ControlType.LOCK)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.REJECTED, ack.result)
        assertEquals(ControlTexts.LOCK_NEEDS_ADMIN, ack.reason)
        assertEquals(0, host.lockCalls)
    }

    @Test fun androidRefusingTheLockIsReported() {
        host.lockThrows = SecurityException("no admin")
        request(ControlType.LOCK)
        processor.poll()
        assertEquals(ControlResult.FAILED, store.acks.getValue("cmd-0001-abcdef").result)
    }

    @Test fun aPhoneThatIsAlreadyLockedSaysSoWithoutLockingAgain() {
        host.locked = true
        request(ControlType.LOCK)
        processor.poll()
        assertEquals(ControlTexts.ALREADY_LOCKED, store.acks.getValue("cmd-0001-abcdef").reason)
        assertEquals(0, host.lockCalls)
    }

    // ---------------------------------------------------------------- restart

    @Test fun restartIsRefusedUnlessTheAppIsDeviceOwner() {
        host.owner = false
        request(ControlType.REBOOT)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.REJECTED, ack.result)
        assertEquals(ControlTexts.REBOOT_NEEDS_OWNER, ack.reason)
        assertEquals(0, host.rebootCalls)
        assertNull(host.pending)
    }

    @Test fun restartIsRefusedOnAndroidTooOldToAllowIt() {
        host.owner = true; host.sdkLevel = 23
        request(ControlType.REBOOT)
        processor.poll()
        assertEquals(ControlResult.REJECTED, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(0, host.rebootCalls)
    }

    @Test fun restartTellsTheParentBeforeThePhoneGoesDown() {
        host.owner = true
        request(ControlType.REBOOT)
        processor.poll()
        assertEquals(1, host.rebootCalls)
        assertEquals(ControlResult.RESTARTING, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals("cmd-0001-abcdef", host.pending!!.id)
        assertTrue(seen.ids.contains("cmd-0001-abcdef"))
        assertTrue(store.deleted.contains("cmd-0001-abcdef"))
    }

    @Test fun restartDoesNotHappenWhenTheParentCannotBeToldFirst() {
        host.owner = true
        store.failAckWrites = 1
        request(ControlType.REBOOT)
        assertEquals(0, processor.poll())
        assertEquals(0, host.rebootCalls)
        assertNull(host.pending)
        assertFalse(seen.ids.contains("cmd-0001-abcdef"))
        // Next pass, the network works and the request is still valid.
        assertEquals(1, processor.poll())
        assertEquals(1, host.rebootCalls)
    }

    @Test fun androidRefusingTheRestartIsReportedAndClearsThePending() {
        host.owner = true
        host.rebootThrows = SecurityException("not owner")
        request(ControlType.REBOOT)
        processor.poll()
        assertEquals(ControlResult.FAILED, store.acks.getValue("cmd-0001-abcdef").result)
        assertNull(host.pending)
    }

    @Test fun afterTheRestartThePhoneReportsItIsBackWithItsBootTime() {
        host.owner = true
        request(ControlType.REBOOT)
        processor.poll()
        // The phone goes down and comes back: a new process, the boot time is now after the request.
        host.time += 90_000L
        host.boot = host.time - 20_000L
        val reborn = ControlProcessor(store, host, seen)
        reborn.afterBoot()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.DONE, ack.result)
        assertEquals(ControlTexts.BACK, ack.reason)
        assertEquals(host.boot, ack.bootAt)
        assertNull(host.pending)
    }

    @Test fun aRestartThatNeverHappenedIsReportedAsFailedAfterTheWait() {
        host.owner = true
        request(ControlType.REBOOT)
        processor.poll()
        host.time += 60_000L
        processor.afterBoot()
        // Still waiting: the phone has not started again yet and the wait is not over.
        assertEquals(ControlResult.RESTARTING, store.acks.getValue("cmd-0001-abcdef").result)
        host.time += ControlPolicy.REBOOT_WAIT_MS
        processor.afterBoot()
        assertEquals(ControlResult.FAILED, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(ControlTexts.NO_RESTART, store.acks.getValue("cmd-0001-abcdef").reason)
        assertNull(host.pending)
    }

    @Test fun aSecondRestartWhileOneIsInProgressIsRefused() {
        host.owner = true
        request(ControlType.REBOOT, "cmd-0001-abcdef")
        processor.poll()
        request(ControlType.REBOOT, "cmd-0002-abcdef")
        processor.poll()
        assertEquals(1, host.rebootCalls)
        assertEquals(ControlTexts.RESTART_ACTIVE, store.acks.getValue("cmd-0002-abcdef").reason)
    }

    // ---------------------------------------------------------------- authorisation, expiry, replay

    @Test fun anUnknownKindOfRequestIsRefusedNeverRun() {
        store.inbox.add(ControlCommand("cmd-0009-abcdef", "unlock", host.time, host.time + ControlPolicy.TTL_MS))
        store.inbox.add(ControlCommand("cmd-0010-abcdef", "shutdown", host.time, host.time + ControlPolicy.TTL_MS))
        store.inbox.add(ControlCommand("cmd-0011-abcdef", "wipe", host.time, host.time + ControlPolicy.TTL_MS))
        processor.poll()
        listOf("cmd-0009-abcdef", "cmd-0010-abcdef", "cmd-0011-abcdef").forEach {
            assertEquals(ControlResult.REJECTED, store.acks.getValue(it).result)
            assertEquals(ControlTexts.UNSUPPORTED, store.acks.getValue(it).reason)
        }
        assertEquals(0, host.lockCalls + host.rebootCalls)
    }

    @Test fun thereIsNoUnlockOrShutdownType() {
        assertEquals("only these requests exist; nothing can unlock or shut down", listOf("lock", "reboot", "ring", "locate", "erase"), ControlType.values().map { it.code })
        assertNull(ControlType.fromCode("unlock"))
        assertNull(ControlType.fromCode("shutdown"))
    }


    // ---------------------------------------------------------------- erase (factory reset)

    private val eraseId = "cmd-0001-abcdef"

    @Test fun eraseIsRefusedUnlessTheAppIsDeviceOwner() {
        host.owner = false
        request(ControlType.ERASE)
        processor.poll()
        val ack = store.acks.getValue(eraseId)
        assertEquals(ControlResult.REJECTED, ack.result)
        assertEquals(ControlTexts.ERASE_NEEDS_OWNER, ack.reason)
        assertEquals(0, host.eraseCalls)
    }

    @Test fun eraseIsRefusedOnAndroidTooOldToAllowIt() {
        host.owner = true; host.sdkLevel = 23
        request(ControlType.ERASE)
        processor.poll()
        assertEquals(ControlResult.REJECTED, store.acks.getValue(eraseId).result)
        assertEquals(0, host.eraseCalls)
    }

    @Test fun eraseIsRefusedWhenAndroidForbidsAFactoryReset() {
        host.owner = true; host.resetBlocked = true
        request(ControlType.ERASE)
        processor.poll()
        assertEquals(ControlTexts.ERASE_BLOCKED, store.acks.getValue(eraseId).reason)
        assertEquals(0, host.eraseCalls)
    }

    @Test fun eraseWithoutTheParentsTypedConfirmationIsNeverRun() {
        host.owner = true
        for ((i, word) in listOf("", "erase", "Erase", "ERASE ", "YES").withIndex()) {
            val id = "cmd-000${i + 1}-abcdef"
            store.inbox.add(ControlCommand(id, "erase", host.time, host.time + ControlPolicy.ERASE_TTL_MS, word))
        }
        processor.poll()
        assertEquals(0, host.eraseCalls)
        assertEquals(5, store.acks.size)
        assertTrue(store.acks.values.all { it.result == ControlResult.REJECTED && it.reason == ControlTexts.ERASE_NOT_CONFIRMED })
    }

    @Test fun eraseTellsTheParentBeforeThePhoneIsWiped() {
        host.owner = true
        var answeredFirst = false
        host.beforeErase = { answeredFirst = store.acks[eraseId]?.result == ControlResult.ERASING && seen.ids.contains(eraseId) }
        request(ControlType.ERASE)
        assertEquals(1, processor.poll())
        assertEquals(1, host.eraseCalls)
        assertTrue("the answer and the replay protection exist before the wipe starts", answeredFirst)
        assertEquals(ControlResult.ERASING, store.acks.getValue(eraseId).result)
        assertTrue(store.deleted.contains(eraseId))
        assertEquals(ControlResult.ERASING, store.events.single().result)
    }

    @Test fun eraseDoesNotHappenWhenTheParentCannotBeToldFirst() {
        host.owner = true
        store.failAckWrites = 1
        request(ControlType.ERASE)
        assertEquals(0, processor.poll())
        assertEquals(0, host.eraseCalls)
        assertFalse(seen.ids.contains(eraseId))
        assertEquals("the next pass works and the request is still valid", 1, processor.poll())
        assertEquals(1, host.eraseCalls)
    }

    @Test fun aRepeatedEraseRequestIsNeverRunTwice() {
        host.owner = true
        host.eraseThrows = IllegalStateException("did not start")
        request(ControlType.ERASE)
        processor.poll()
        // The same request shows up again (replayed, or the delete failed): it is only settled, never run again.
        store.inbox.add(ControlCommand.create(eraseId, ControlType.ERASE, host.time))
        processor.poll()
        assertEquals(1, host.eraseCalls)
    }

    @Test fun androidRefusingTheEraseIsReported() {
        host.owner = true
        host.eraseThrows = SecurityException("not allowed")
        request(ControlType.ERASE)
        processor.poll()
        val ack = store.acks.getValue(eraseId)
        assertEquals(ControlResult.REJECTED, ack.result)
        assertTrue(ack.reason.contains("Android refused to erase the phone"))
    }

    @Test fun anEraseThatFailedToStartIsReportedAsFailed() {
        host.owner = true
        host.eraseThrows = IllegalStateException("boom")
        request(ControlType.ERASE)
        processor.poll()
        assertEquals(ControlResult.FAILED, store.acks.getValue(eraseId).result)
    }

    @Test fun eraseLivesOnlyOneMinute() {
        val c = ControlCommand.create(eraseId, ControlType.ERASE, host.time)
        assertEquals(host.time + ControlPolicy.ERASE_TTL_MS, c.expiresAt)
        assertEquals(ControlTexts.ERASE_WORD, c.confirm)
        assertEquals(ControlCommands.Validity.OK, ControlCommands.validity(c, host.time))
        // Older than a minute (plus the allowed clock difference): too late, and it is not run.
        host.owner = true
        val late = ControlCommand(eraseId, "erase", host.time - 3 * 60_000L, host.time - 2 * 60_000L, ControlTexts.ERASE_WORD)
        store.inbox.add(late)
        processor.poll()
        assertEquals(ControlResult.EXPIRED, store.acks.getValue(eraseId).result)
        assertEquals(0, host.eraseCalls)
    }

    @Test fun anEraseRequestClaimingALongerLifeIsTreatedAsForged() {
        host.owner = true
        store.inbox.add(ControlCommand(eraseId, "erase", host.time, host.time + 4 * 60_000L, ControlTexts.ERASE_WORD))
        processor.poll()
        assertEquals(ControlResult.REJECTED, store.acks.getValue(eraseId).result)
        assertEquals(ControlTexts.INVALID, store.acks.getValue(eraseId).reason)
        assertEquals(0, host.eraseCalls)
    }

    @Test fun onlyEraseCarriesAConfirmationAndItSurvivesTheDatabase() {
        val erase = ControlCommand.create(eraseId, ControlType.ERASE, 5_000L)
        assertEquals(erase, ControlCommandCodec.fromJson(eraseId, ControlCommandCodec.toJson(erase)))
        assertEquals(ControlTexts.ERASE_WORD, ControlCommandCodec.toJson(erase).get("ok").asString)
        val lock = ControlCommand.create(eraseId, ControlType.LOCK, 5_000L)
        assertEquals("", lock.confirm)
        assertFalse(ControlCommandCodec.toJson(lock).has("ok"))
    }

    @Test fun statusSaysThePhoneCanEraseOnlyWhenItReallyCan() {
        host.owner = false
        processor.reportStatus()
        assertFalse(store.statuses.last().canErase)
        host.owner = true
        processor.reportStatus()
        assertTrue(store.statuses.last().canErase)
        host.resetBlocked = true
        processor.reportStatus()
        assertFalse(store.statuses.last().canErase)
        host.resetBlocked = false; host.sdkLevel = 23
        processor.reportStatus()
        assertFalse(store.statuses.last().canErase)
    }

    @Test fun parentOffersEraseOnlyForAnEligiblePhoneAndSaysWhyNot() {
        assertEquals(ControlTexts.NO_REPORT, ControlOffer.erase(null).why)
        val capable = ControlStatus(host.time, true, true, true, true, false, true, host.boot, false, canErase = true)
        assertTrue(ControlOffer.erase(capable).available)
        assertEquals(ControlTexts.ERASE_NEEDS_OWNER, ControlOffer.erase(capable.copy(deviceOwner = false, canErase = false)).why)
        assertEquals(ControlTexts.ERASE_BLOCKED, ControlOffer.erase(capable.copy(canErase = false)).why)
    }

    @Test fun anOldStatusWithoutTheEraseFlagMeansNoErase() {
        val old = ControlStatusCodec.toJson(ControlStatus(1L, true, true, true, true, false, true, 0L, false, canErase = true)).also { it.remove("erase") }
        assertFalse(ControlStatusCodec.fromJson(old)!!.canErase)
    }

    @Test fun theEraseTextsSayItCannotBeUndoneAndCannotBeConfirmed() {
        assertTrue(ControlTexts.ERASE_WARNING.contains("can't be undone"))
        assertTrue(ControlTexts.ERASE_AFTER.contains("can't see the end result"))
        assertTrue(ControlTexts.ERASE_DISCLOSURE.contains("can't be undone"))
    }

    @Test fun anOldRequestIsAnsweredAsExpiredAndNotRun() {
        val old = ControlCommand("cmd-0001-abcdef", "lock", host.time - 10 * 60_000L, host.time - 8 * 60_000L)
        store.inbox.add(old)
        processor.poll()
        assertEquals(ControlResult.EXPIRED, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(0, host.lockCalls)
    }

    @Test fun aRequestWithinTheClockToleranceStillRuns() {
        store.inbox.add(ControlCommand("cmd-0001-abcdef", "lock", host.time - 150_000L, host.time - 30_000L))
        processor.poll()
        assertEquals(ControlResult.DONE, store.acks.getValue("cmd-0001-abcdef").result)
    }

    @Test fun aRequestClaimingAVeryLongLifeOrAFutureTimeIsNotValid() {
        store.inbox.add(ControlCommand("cmd-0001-abcdef", "lock", host.time, host.time + 24 * 3_600_000L))
        store.inbox.add(ControlCommand("cmd-0002-abcdef", "lock", host.time + 3_600_000L, host.time + 3_700_000L))
        store.inbox.add(ControlCommand("cmd-0003-abcdef", "lock", host.time, host.time - 1))
        processor.poll()
        listOf("cmd-0001-abcdef", "cmd-0002-abcdef", "cmd-0003-abcdef").forEach {
            assertEquals(ControlResult.REJECTED, store.acks.getValue(it).result)
            assertEquals(ControlTexts.INVALID, store.acks.getValue(it).reason)
        }
        assertEquals(0, host.lockCalls)
    }

    @Test fun aRepeatedRequestIsNeverRunTwice() {
        request(ControlType.LOCK)
        processor.poll()
        // The same request shows up again (e.g. the delete failed, or someone replays it).
        host.locked = false
        store.inbox.add(ControlCommand("cmd-0001-abcdef", "lock", host.time, host.time + ControlPolicy.TTL_MS))
        processor.poll()
        assertEquals(1, host.lockCalls)
        assertEquals(ControlResult.DONE, store.acks.getValue("cmd-0001-abcdef").result)
    }

    @Test fun handledIdsSurviveAnAppRestart() {
        request(ControlType.LOCK)
        processor.poll()
        host.locked = false
        val restarted = ControlProcessor(store, host, seen)
        store.inbox.add(ControlCommand("cmd-0001-abcdef", "lock", host.time, host.time + ControlPolicy.TTL_MS))
        restarted.poll()
        assertEquals(1, host.lockCalls)
    }

    @Test fun aHandledRequestWithoutAnyAnswerIsAnsweredAsUnconfirmedNotAsSuccess() {
        // The phone ran it, then lost its answer along with the process. It must not claim success.
        seen.ids = listOf("cmd-0001-abcdef")
        processor = ControlProcessor(store, host, seen)
        store.inbox.add(ControlCommand("cmd-0001-abcdef", "lock", host.time, host.time + ControlPolicy.TTL_MS))
        processor.poll()
        assertEquals(ControlResult.FAILED, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(ControlTexts.UNCONFIRMED, store.acks.getValue("cmd-0001-abcdef").reason)
        assertEquals(0, host.lockCalls)
    }

    @Test fun aGoodAnswerIsNotOverwrittenWhenOnlyTheCleanUpFailed() {
        store.failDeletes = true
        request(ControlType.LOCK)
        processor.poll()
        store.failDeletes = false
        processor.poll()
        assertEquals(ControlResult.DONE, store.acks.getValue("cmd-0001-abcdef").result)
        assertTrue(store.deleted.contains("cmd-0001-abcdef"))
    }

    @Test fun anAnswerThatCouldNotBeWrittenIsSentLaterNotLost() {
        request(ControlType.LOCK)
        store.failAckWrites = 1
        processor.poll()
        assertTrue(store.acks.isEmpty())
        processor.poll()
        assertEquals(ControlResult.DONE, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(1, host.lockCalls)
    }

    @Test fun offlineMeansNothingHappensAndNothingBreaks() {
        request(ControlType.LOCK)
        store.offline = true
        try { processor.poll(); throw AssertionError("expected an IOException") } catch (_: java.io.IOException) { }
        assertEquals(0, host.lockCalls)
        store.offline = false
        processor.poll()
        assertEquals(1, host.lockCalls)
    }

    @Test fun everyHandledRequestLeavesAnAuditLine() {
        request(ControlType.LOCK, "cmd-0001-abcdef")
        host.admin = false
        processor.poll()
        assertEquals(listOf(ControlResult.REJECTED), store.events.map { it.result })
        assertEquals("cmd-0001-abcdef", store.events.single().id)
    }

    // ---------------------------------------------------------------- ring and locate

    @Test fun ringIsDoneOnlyAfterThePhoneReallyStartedTheSoundAndTheNotice() {
        request(ControlType.RING)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.DONE, ack.result)
        assertEquals(RecoveryTexts.RING_STARTED, ack.reason)
        assertEquals(1, host.ringCalls)
    }

    @Test fun ringIsRefusedWithAReasonWhenThePhoneCannotShowItsNotice() {
        host.ringable = false
        request(ControlType.RING)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.REJECTED, ack.result)
        assertEquals(RecoveryTexts.RING_UNAVAILABLE, ack.reason)
        assertEquals("the phone must never ring without its notice", 0, host.ringCalls)
    }

    @Test fun aRingThatCouldNotStartIsReportedAsFailedNotAsDone() {
        host.ringOutcome = ControlOutcome(ControlResult.FAILED, RecoveryTexts.RING_FAILED)
        request(ControlType.RING)
        processor.poll()
        assertEquals(ControlResult.FAILED, store.acks.getValue("cmd-0001-abcdef").result)
    }

    @Test fun aCrashWhileRingingBecomesAnHonestFailure() {
        host.ringThrows = IllegalStateException("audio service died")
        request(ControlType.RING)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.FAILED, ack.result)
        assertEquals(RecoveryTexts.RING_FAILED, ack.reason)
    }

    @Test fun anExpiredRingIsNeverPlayed() {
        host.time += ControlPolicy.TTL_MS + ControlPolicy.CLOCK_SKEW_MS + 1_000L
        store.inbox.add(ControlCommand("cmd-0001-abcdef", ControlType.RING.code, host.time - ControlPolicy.TTL_MS - ControlPolicy.CLOCK_SKEW_MS - 5_000L, host.time - ControlPolicy.CLOCK_SKEW_MS - 5_000L))
        processor.poll()
        assertEquals(ControlResult.EXPIRED, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(0, host.ringCalls)
    }

    @Test fun aRingIsHandledAtMostOnce() {
        val c = request(ControlType.RING)
        processor.poll()
        store.inbox.add(c)
        processor.poll()
        assertEquals(1, host.ringCalls)
    }

    @Test fun locateAnswersWithWhatThePhoneReallyFound() {
        request(ControlType.LOCATE)
        processor.poll()
        assertEquals(ControlResult.DONE, store.acks.getValue("cmd-0001-abcdef").result)
        assertEquals(1, host.locateCalls)

        store.acks.clear()
        host.locateOutcome = ControlOutcome(ControlResult.FAILED, RecoveryTexts.NO_FIX)
        request(ControlType.LOCATE, "cmd-0002-abcdef")
        processor.poll()
        val failed = store.acks.getValue("cmd-0002-abcdef")
        assertEquals(ControlResult.FAILED, failed.result)
        assertEquals(RecoveryTexts.NO_FIX, failed.reason)
    }

    @Test fun locateRefusedBecauseSharingIsOffKeepsTheReason() {
        host.locateOutcome = ControlOutcome(ControlResult.REJECTED, RecoveryTexts.OFF)
        request(ControlType.LOCATE)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.REJECTED, ack.result)
        assertEquals(RecoveryTexts.OFF, ack.reason)
    }

    @Test fun aMissingPermissionDuringLocateIsARefusalWithTheAndroidReason() {
        host.locateThrows = SecurityException("no location permission")
        request(ControlType.LOCATE)
        processor.poll()
        val ack = store.acks.getValue("cmd-0001-abcdef")
        assertEquals(ControlResult.REJECTED, ack.result)
        assertTrue(ack.reason.contains("no location permission"))
    }

    @Test fun ringAndLocateLeaveAnAuditLine() {
        request(ControlType.RING)
        request(ControlType.LOCATE, "cmd-0002-abcdef")
        processor.poll()
        assertEquals(listOf("ring", "locate"), store.events.map { it.typeCode })
    }

    @Test fun statusReportsRingingOnlyWhileItReallyIs() {
        host.ringEnds = host.time + 30_000L
        processor.reportStatus()
        val status = store.statuses.last()
        assertTrue(status.canRing)
        assertEquals(host.ringEnds, status.ringUntil)
        assertTrue(ControlOffer.isRinging(status, host.time))
        assertFalse("a ring that has ended is not shown as ringing", ControlOffer.isRinging(status, host.time + 31_000L))
        host.ringEnds = host.time + 10 * 60_000L
        processor.reportStatus()
        val longRing = store.statuses.last()
        assertTrue(ControlOffer.isRinging(longRing, host.time + 1_000L))
        assertFalse("a report that has gone stale is not trusted", ControlOffer.isRinging(longRing, host.time + ControlPolicy.STATUS_STALE_MS + 1_000L))
    }

    @Test fun ringIsOfferedOnlyWhenThePhoneCanDoIt() {
        assertFalse(ControlOffer.ring(null).available)
        host.ringable = false
        processor.reportStatus()
        assertEquals(RecoveryTexts.RING_UNAVAILABLE, ControlOffer.ring(store.statuses.last()).why)
        host.ringable = true
        processor.reportStatus()
        assertTrue(ControlOffer.ring(store.statuses.last()).available)
    }

    @Test fun anOlderPhoneReportWithoutRingFieldsStillReadsAndMeansCannotRing() {
        val old = JsonObject().apply { addProperty("ts", 5L); addProperty("admin", true); addProperty("lock", true) }
        val status = ControlStatusCodec.fromJson(old)!!
        assertFalse(status.canRing)
        assertEquals(0L, status.ringUntil)
    }

    // ---------------------------------------------------------------- status and offers

    @Test fun statusIsBuiltFromWhatThePhoneReallyReports() {
        host.admin = true; host.owner = false; host.locked = true; host.screen = false
        processor.reportStatus()
        val s = store.statuses.single()
        assertTrue(s.canLock); assertFalse(s.canReboot); assertTrue(s.locked); assertFalse(s.screenOn)
        host.owner = true
        processor.reportStatus()
        assertTrue(store.statuses.last().canReboot)
        host.sdkLevel = 23
        processor.reportStatus()
        assertFalse(store.statuses.last().canReboot)
    }

    @Test fun parentOffersOnlyWhatThePhoneCanDoAndSaysWhyNot() {
        assertFalse(ControlOffer.lock(null).available)
        assertEquals(ControlTexts.NO_REPORT, ControlOffer.lock(null).why)
        val capable = ControlStatus(host.time, true, true, true, true, false, true, host.boot, false)
        assertTrue(ControlOffer.lock(capable).available)
        assertTrue(ControlOffer.reboot(capable).available)
        val limited = capable.copy(adminActive = false, deviceOwner = false, canLock = false, canReboot = false)
        assertEquals(ControlTexts.LOCK_NEEDS_ADMIN, ControlOffer.lock(limited).why)
        assertEquals(ControlTexts.REBOOT_NEEDS_OWNER, ControlOffer.reboot(limited).why)
        assertFalse(ControlOffer.reboot(capable.copy(rebootPending = true)).available)
    }

    @Test fun aStatusIsCalledOldAfterAFewMinutes() {
        val s = ControlStatus(1_000L, true, false, true, false, false, true, 0L, false)
        assertFalse(ControlOffer.isStale(s, 1_000L + 60_000L))
        assertTrue(ControlOffer.isStale(s, 1_000L + ControlPolicy.STATUS_STALE_MS + 1))
        assertTrue(ControlOffer.isStale(null, 0L))
    }

    // ---------------------------------------------------------------- codecs

    @Test fun commandsAckAndStatusSurviveTheDatabaseRoundTrip() {
        val c = ControlCommand.create("cmd-0001-abcdef", ControlType.REBOOT, 5_000L)
        val back = ControlCommandCodec.fromJson(c.id, ControlCommandCodec.toJson(c))!!
        assertEquals(c, back)
        assertEquals(5_000L + ControlPolicy.TTL_MS, back.expiresAt)

        val ack = ControlAck(c.id, "reboot", ControlResult.DONE, ControlTexts.BACK, 9_000L, 8_000L)
        assertEquals(ack, ControlAckCodec.fromJson(c.id, ControlAckCodec.toJson(ack)))

        val status = ControlStatus(1L, true, true, true, true, false, true, 77L, true)
        assertEquals(status, ControlStatusCodec.fromJson(ControlStatusCodec.toJson(status)))

        val event = ControlEvent(5L, "lock", ControlResult.DONE, c.id)
        assertEquals(event, ControlEventCodec.fromJson(ControlEventCodec.toJson(event)))
    }

    @Test fun damagedOrForeignDataIsIgnoredNotTrusted() {
        assertNull(ControlCommandCodec.fromJson("short", JsonObject()))
        assertNull(ControlCommandCodec.fromJson("has space in id ok1", JsonObject()))
        assertNull(ControlAckCodec.fromJson("cmd-0001-abcdef", JsonObject().apply { addProperty("r", "success!") }))
        assertNull(ControlAckCodec.fromJson("cmd-0001-abcdef", null))
        assertNull(ControlStatusCodec.fromJson(JsonObject()))
        assertNull(ControlEventCodec.fromJson(JsonObject().apply { addProperty("r", "?") }))
        assertTrue(ControlInboxCodec.fromJson(JsonObject().apply { addProperty("x", 1) }).isEmpty())
    }

    @Test fun idsAreShortPlainAndSafeAsDatabaseKeys() {
        assertTrue(ControlCommandCodec.isValidId("ctl-1234567890abcdef"))
        assertFalse(ControlCommandCodec.isValidId("a/b/../../c-1234"))
        assertFalse(ControlCommandCodec.isValidId("x".repeat(41)))
    }

    @Test fun theProcessedListIsCappedSoItCannotGrowForever() {
        var ids = emptyList<String>()
        for (i in 1..200) ids = ControlCommands.remember(ids, "cmd-%06d".format(i))
        assertEquals(ControlPolicy.PROCESSED_KEEP, ids.size)
        assertEquals("cmd-000200", ids.last())
    }
}
