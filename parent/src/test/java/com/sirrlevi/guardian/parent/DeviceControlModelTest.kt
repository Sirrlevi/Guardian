package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.ControlAck
import com.sirrlevi.guardian.core.ControlCommand
import com.sirrlevi.guardian.core.ControlCommandCodec
import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlPolicy
import com.sirrlevi.guardian.core.ControlResult
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.ControlTexts
import com.sirrlevi.guardian.core.ControlType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** A phone and a database in memory, so the parent's side of device controls can be exercised without a network. */
class FakeControlBackend : ControlBackend {
    var statusNow: ControlStatus? = null
    val sent = ArrayList<ControlCommand>()
    val acks = HashMap<String, ControlAck>()
    val deletedAcks = ArrayList<String>()
    val withdrawn = ArrayList<String>()
    var events = listOf<ControlEvent>()
    var logCalls = 0
    var failWith: Exception? = null
    var failSend: Exception? = null

    private fun check() { failWith?.let { throw it } }
    override fun status(): ControlStatus? { check(); return statusNow }
    override fun send(command: ControlCommand) { check(); failSend?.let { throw it }; sent.add(command) }
    override fun withdraw(id: String) { check(); withdrawn.add(id) }
    override fun ack(id: String): ControlAck? { check(); return acks[id] }
    override fun deleteAck(id: String) { check(); acks.remove(id); deletedAcks.add(id) }
    override fun log(): List<ControlEvent> { check(); logCalls++; return events }
}

/** Plain JVM test on purpose (see ScreenPreviewModelTest): the model only needs Compose's state runtime. */
class DeviceControlModelTest {
    private var time = 1_800_000_000_000L
    private val backend = FakeControlBackend()
    private var counter = 0
    private val model = DeviceControlModel({ backend }, { time }, { "ctl-" + (++counter).toString().padStart(8, '0') }, io = EmptyCoroutineContext)

    private fun capable(admin: Boolean = true, owner: Boolean = true, pending: Boolean = false) =
        ControlStatus(time, admin, owner, admin, owner, false, true, time - 3_600_000L, pending)

    private fun ack(id: String, type: String, result: ControlResult, reason: String) = ControlAck(id, type, result, reason, time)

    @Test fun firstRefreshLoadsTheStatusAndOffersWhatThePhoneCanDo() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        assertTrue(model.loaded)
        assertNull(model.error)
        assertTrue(model.lockOffer.available)
        assertTrue(model.rebootOffer.available)
    }

    @Test fun withoutAReportFromThePhoneNothingIsOffered() = runBlocking {
        model.tick()
        assertFalse(model.lockOffer.available)
        assertFalse(model.rebootOffer.available)
        assertFalse(model.lock())
        assertFalse(model.reboot())
        assertEquals(ControlTexts.NO_REPORT, model.notice)
        assertTrue(model.noticeIsProblem)
        assertTrue(backend.sent.isEmpty())
    }

    @Test fun lockSendsOneValidShortLivedRequestAndIsNotDoneYet() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        assertTrue(model.lock())
        val cmd = backend.sent.single()
        assertEquals(ControlType.LOCK, cmd.type)
        assertTrue(ControlCommandCodec.isValidId(cmd.id))
        assertEquals(time + ControlPolicy.TTL_MS, cmd.expiresAt)
        assertEquals(ControlType.LOCK, model.waiting)
        assertFalse(model.noticeIsProblem)
        assertFalse(model.notice!!.startsWith("Locked"))
    }

    @Test fun lockIsShownAsDoneOnlyWhenThePhoneAnswersDone() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.lock()
        val id = backend.sent.single().id
        backend.acks[id] = ack(id, "lock", ControlResult.DONE, ControlTexts.LOCKED)
        model.tick()
        assertNull(model.waiting)
        assertEquals("Locked. The phone checked and confirmed it.", model.notice)
        assertFalse(model.noticeIsProblem)
        assertEquals(listOf(id), backend.deletedAcks)
    }

    @Test fun aRefusalShowsThePhonesReasonAsAProblem() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.lock()
        val id = backend.sent.single().id
        backend.acks[id] = ack(id, "lock", ControlResult.REJECTED, ControlTexts.LOCK_NEEDS_ADMIN)
        model.tick()
        assertNull(model.waiting)
        assertEquals(ControlTexts.LOCK_NEEDS_ADMIN, model.notice)
        assertTrue(model.noticeIsProblem)
    }

    @Test fun aFailureAndAnExpiryAreProblemsNeverSuccesses() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.lock()
        var id = backend.sent.last().id
        backend.acks[id] = ack(id, "lock", ControlResult.FAILED, ControlTexts.LOCK_FAILED)
        model.tick()
        assertEquals(ControlTexts.LOCK_FAILED, model.notice)
        assertTrue(model.noticeIsProblem)
        model.lock()
        id = backend.sent.last().id
        backend.acks[id] = ack(id, "lock", ControlResult.EXPIRED, ControlTexts.EXPIRED)
        model.tick()
        assertEquals(ControlTexts.EXPIRED, model.notice)
        assertTrue(model.noticeIsProblem)
    }

    @Test fun anUnansweredRequestIsTakenBackAndReportedAsNotDone() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.lock()
        val id = backend.sent.single().id
        time += ControlPolicy.TTL_MS
        model.tick()
        // Still inside the allowed lateness: keep waiting.
        assertEquals(ControlType.LOCK, model.waiting)
        assertTrue(backend.withdrawn.isEmpty())
        time += ControlPolicy.CLOCK_SKEW_MS + ControlPolicy.ACK_GRACE_MS + 1_000L
        model.tick()
        assertNull(model.waiting)
        assertEquals(listOf(id), backend.withdrawn)
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.contains("nothing was done"))
        assertFalse(model.notice!!.contains("Locked"))
    }

    @Test fun anAnswerThatArrivesAtTheLastMomentStillWins() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.lock()
        val id = backend.sent.single().id
        time += ControlPolicy.TTL_MS + ControlPolicy.CLOCK_SKEW_MS + ControlPolicy.ACK_GRACE_MS + 5_000L
        backend.acks[id] = ack(id, "lock", ControlResult.DONE, ControlTexts.LOCKED)
        model.tick()
        assertTrue(backend.withdrawn.isEmpty())
        assertEquals("Locked. The phone checked and confirmed it.", model.notice)
    }

    @Test fun onlyOneRequestAtATimeAndNothingPilesUp() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        assertTrue(model.lock())
        assertFalse(model.lock())
        assertFalse(model.reboot())
        assertEquals(1, backend.sent.size)
        assertEquals("A request is already waiting for the phone.", model.notice)
    }

    @Test fun anActionThePhoneCannotDoIsNeverSentAndTheReasonIsShown() = runBlocking {
        backend.statusNow = capable(admin = false, owner = false)
        model.tick()
        assertFalse(model.lock())
        assertEquals(ControlTexts.LOCK_NEEDS_ADMIN, model.notice)
        assertFalse(model.reboot())
        assertEquals(ControlTexts.REBOOT_NEEDS_OWNER, model.notice)
        assertTrue(backend.sent.isEmpty())
    }

    @Test fun restartIsAcceptedFirstAndConfirmedOnlyWhenThePhoneIsBack() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        assertTrue(model.reboot())
        val id = backend.sent.single().id
        assertEquals(ControlType.REBOOT, backend.sent.single().type)

        backend.acks[id] = ack(id, "reboot", ControlResult.RESTARTING, ControlTexts.RESTARTING)
        time += 5_000L
        model.tick()
        assertTrue(model.restarting)
        assertEquals(ControlType.REBOOT, model.waiting)
        assertTrue(model.notice!!.contains("restarting"))
        assertTrue(model.notice!!.contains(ControlTexts.AFTER_RESTART_NOTE))
        // The "restarting" answer is kept: it is not the final word.
        assertTrue(backend.deletedAcks.isEmpty())

        time += 90_000L
        backend.acks[id] = ack(id, "reboot", ControlResult.DONE, ControlTexts.BACK)
        model.tick()
        assertNull(model.waiting)
        assertFalse(model.restarting)
        assertEquals(ControlTexts.BACK, model.notice)
        assertFalse(model.noticeIsProblem)
    }

    @Test fun aRestartThePhoneNeverReportedBackIsAProblemNotASuccess() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.reboot()
        val id = backend.sent.single().id
        backend.acks[id] = ack(id, "reboot", ControlResult.RESTARTING, ControlTexts.RESTARTING)
        model.tick()
        time += ControlPolicy.REBOOT_WAIT_MS + 1_000L
        model.tick()
        assertNull(model.waiting)
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.contains("hasn't reported back"))
        assertTrue(backend.withdrawn.isEmpty())
    }

    @Test fun aRestartAlreadyUnderWayOnThePhoneIsNotOfferedAgain() = runBlocking {
        backend.statusNow = capable(pending = true)
        model.tick()
        assertFalse(model.rebootOffer.available)
        assertEquals(ControlTexts.RESTART_ACTIVE, model.rebootOffer.why)
    }

    @Test fun lostConnectionKeepsTheLastKnownStatusAndSaysSo() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        backend.failWith = IOException("Unable to resolve host x")
        model.tick()
        assertEquals("No internet connection.", model.error)
        assertNotNull(model.status)
        backend.failWith = null
        model.tick()
        assertNull(model.error)
    }

    @Test fun failingToSendIsReportedNotHidden() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        backend.failSend = IOException("denied")
        assertFalse(model.lock())
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.startsWith("Couldn't send that"))
        assertNull(model.waiting)
    }

    @Test fun statusIsCalledOldWhenThePhoneStoppedReporting() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        assertFalse(model.statusIsOld)
        time += ControlPolicy.STATUS_STALE_MS + 1_000L
        backend.failWith = IOException("offline")
        model.tick()
        assertTrue(model.statusIsOld)
    }

    @Test fun theHistoryIsLoadedAndReloadedAfterAnAnswer() = runBlocking {
        backend.statusNow = capable()
        backend.events = listOf(ControlEvent(time - 1_000L, "lock", ControlResult.DONE, "ctl-00000001"))
        model.tick()
        assertEquals(1, model.log.size)
        assertEquals(1, backend.logCalls)
        model.tick()
        assertEquals(1, backend.logCalls)
        model.lock()
        val id = backend.sent.single().id
        backend.acks[id] = ack(id, "lock", ControlResult.DONE, ControlTexts.LOCKED)
        model.tick()
        assertEquals(2, backend.logCalls)
    }

    @Test fun resetForgetsEverythingWhenAnotherPhoneIsChosen() = runBlocking {
        backend.statusNow = capable()
        model.tick()
        model.lock()
        model.reset()
        assertNull(model.status)
        assertNull(model.waiting)
        assertNull(model.notice)
        assertFalse(model.loaded)
        // Nothing from the old phone's request is waited for any more.
        model.tick()
        assertNull(model.waiting)
    }
}

