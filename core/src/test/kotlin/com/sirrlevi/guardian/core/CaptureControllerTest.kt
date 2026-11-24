package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeStore : CaptureStore {
    var commands = ArrayList<CaptureCommand>()
    var viewerAt = 0L
    var dbState: CaptureState? = null
    val states = ArrayList<CaptureState>()
    val acks = ArrayList<CaptureAck>()
    val deleted = ArrayList<String>()
    val events = ArrayList<CaptureEvent>()
    val shots = ArrayList<Triple<String, Int, Int>>()
    val lives = ArrayList<Long>()
    var clearedLive = 0
    var pruned = 0
    var failInbox = false
    var failShotWrite = false
    var failLiveWrite = false

    override fun readInbox(): CaptureInbox {
        if (failInbox) throw java.io.IOException("offline")
        return CaptureInbox(commands.toList(), viewerAt)
    }
    override fun readState(): CaptureState? = dbState
    override fun writeState(state: CaptureState) { states.add(state); dbState = state }
    override fun writeAck(ack: CaptureAck) { acks.add(ack) }
    override fun deleteCommand(id: String) { deleted.add(id); commands.removeAll { it.id == id } }
    override fun appendLog(event: CaptureEvent) { events.add(event) }
    override fun writeShot(id: String, ts: Long, width: Int, height: Int, jpeg: ByteArray) {
        if (failShotWrite) throw java.io.IOException("offline")
        shots.add(Triple(id, width, height))
    }
    override fun writeLive(seq: Long, ts: Long, width: Int, height: Int, jpeg: ByteArray, lastUploadMs: Long) {
        if (failLiveWrite) throw java.io.IOException("offline")
        lives.add(seq)
    }
    override fun clearLive() { clearedLive++ }
    override fun pruneShots(keep: Int) { pruned++ }
}

private class FakeHost : CaptureHost {
    var prompts = 0
    var promptHidden = 0
    var stops = 0
    var shot: ShotResult = ShotResult.ok(ByteArray(10), 720, 1600)
    var notifications = true
    var shotCalls = 0
    override fun showPrompt(expiresAt: Long) { prompts++ }
    override fun hidePrompt() { promptHidden++ }
    override fun stopSession() { stops++ }
    override fun takeShot(): ShotResult { shotCalls++; return shot }
    override fun notificationsEnabled() = notifications
}

private class FakeProcessed : ProcessedIds {
    var ids = emptyList<String>()
    override fun load() = ids
    override fun save(ids: List<String>) { this.ids = ids }
}

class CaptureControllerTest {
    private var now = 1_800_000_000_000L
    private lateinit var store: FakeStore
    private lateinit var host: FakeHost
    private lateinit var processed: FakeProcessed
    private lateinit var c: CaptureController
    private var idCounter = 0
    private var cmdCounter = 0

    @Before fun setUp() {
        store = FakeStore(); host = FakeHost(); processed = FakeProcessed()
        c = controller()
    }

    private fun controller() = CaptureController(store, host, processed, { now }, { "session-${++idCounter}" })

    private fun send(type: CaptureCmdType, id: String = "cmd-%08d".format(++cmdCounter), on: Boolean = false): String {
        store.commands.add(CaptureCommand.create(id, type, now, on))
        return id
    }

    private fun approve() {
        send(CaptureCmdType.ASK); c.poll()
        assertTrue(c.onApproved(720, 1600))
    }

    private fun ack(id: String) = store.acks.last { it.id == id }

    @Test fun idleWithNothingAskedWritesNothing() {
        assertTrue(c.poll())
        assertTrue(store.states.isEmpty())
        assertTrue(store.acks.isEmpty())
        assertEquals(CaptureStatus.IDLE, c.state.status)
    }

    @Test fun askShowsAPromptAndWaitsForTheChild() {
        val id = send(CaptureCmdType.ASK)
        c.poll()
        assertEquals(CaptureStatus.AWAITING, c.state.status)
        assertEquals(1, host.prompts)
        assertEquals(CaptureAck.DONE, ack(id).result)
        assertTrue("command is cleared once handled", id in store.deleted)
        assertEquals("asked", store.events.single().kind)
        assertEquals(0, host.shotCalls)
        assertEquals("no capture before the child approves", CaptureStatus.AWAITING, store.dbState!!.status)
    }

    @Test fun noticeWhenNotificationsAreOffSoTheParentKnowsThePromptMayBeMissed() {
        host.notifications = false
        send(CaptureCmdType.ASK); c.poll()
        assertFalse(store.dbState!!.notifOk)
    }

    @Test fun theSameRequestIsNeverRunTwice() {
        val id = send(CaptureCmdType.ASK)
        c.poll()
        store.commands.add(CaptureCommand.create(id, CaptureCmdType.ASK, now))
        c.poll()
        assertEquals(1, host.prompts)
        assertEquals(1, store.events.count { it.kind == "asked" })
    }

    @Test fun replayStaysBlockedAfterARestart() {
        val id = send(CaptureCmdType.ASK)
        c.poll()
        c.onDeclined()
        val restarted = controller()
        store.commands.add(CaptureCommand.create(id, CaptureCmdType.ASK, now))
        restarted.poll()
        assertEquals(1, host.prompts)
        assertEquals(CaptureStatus.IDLE, restarted.state.status)
    }

    @Test fun aSecondAskWhileWaitingIsRejected() {
        send(CaptureCmdType.ASK); c.poll()
        val second = send(CaptureCmdType.ASK); c.poll()
        assertEquals(CaptureAck.REJECTED, ack(second).result)
        assertEquals(1, host.prompts)
    }

    @Test fun screenshotWithoutAnApprovedSessionIsRefused() {
        val id = send(CaptureCmdType.SHOT)
        c.poll()
        assertEquals(CaptureAck.REJECTED, ack(id).result)
        assertEquals(CaptureFailure.NO_SESSION, ack(id).reason)
        assertEquals(0, host.shotCalls)
        assertTrue(store.shots.isEmpty())
    }

    @Test fun screenshotWhileOnlyWaitingForApprovalIsRefused() {
        send(CaptureCmdType.ASK); c.poll()
        val id = send(CaptureCmdType.SHOT); c.poll()
        assertEquals(CaptureAck.REJECTED, ack(id).result)
        assertEquals(0, host.shotCalls)
    }

    @Test fun approvalStartsASessionThatEndsByItself() {
        approve()
        assertEquals(CaptureStatus.ACTIVE, c.state.status)
        assertEquals(now + CapturePolicy.SESSION_MAX_MS, c.state.expiresAt)
        assertEquals(720, c.state.width)
        assertEquals("approved", store.events.last().kind)
    }

    @Test fun approvalAfterTheParentCancelledDoesNotStartCapture() {
        send(CaptureCmdType.ASK); c.poll()
        send(CaptureCmdType.STOP); c.poll()
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.CANCELLED, c.state.reason)
        assertFalse("child tapped Allow too late", c.onApproved(720, 1600))
        assertEquals(CaptureStatus.ENDED, c.state.status)
    }

    @Test fun approvalAfterTheRequestLapsedDoesNotStartCapture() {
        send(CaptureCmdType.ASK); c.poll()
        now += CapturePolicy.REQUEST_TTL_MS + 1_000L
        assertFalse(c.onApproved(720, 1600))
    }

    @Test fun anUnansweredRequestExpiresAndTheChildPromptIsRemoved() {
        send(CaptureCmdType.ASK); c.poll()
        now += CapturePolicy.REQUEST_TTL_MS + 1_000L
        c.poll()
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.EXPIRED, c.state.reason)
        assertEquals(1, host.promptHidden)
        assertEquals("expired", store.events.last().kind)
    }

    @Test fun decliningEndsTheRequestWithoutSharing() {
        send(CaptureCmdType.ASK); c.poll()
        c.onDeclined()
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.DECLINED, c.state.reason)
        assertEquals("declined", store.events.last().kind)
        assertEquals(0, host.shotCalls)
    }

    @Test fun screenshotIsTakenSentAndAcknowledgedWithItsId() {
        approve()
        val id = send(CaptureCmdType.SHOT)
        c.poll()
        assertEquals(1, host.shotCalls)
        val sent = store.shots.single()
        assertEquals(720, sent.second); assertEquals(1600, sent.third)
        assertEquals(CaptureAck.DONE, ack(id).result)
        assertEquals(sent.first, ack(id).shotId)
        assertEquals("ok", c.state.transfer)
        assertEquals(now, c.state.lastShotAt)
        assertNull(c.state.error)
        assertEquals(1, store.pruned)
        assertEquals("shot", store.events.last().kind)
    }

    @Test fun severalScreenshotsInOneSessionAreAllowed() {
        approve()
        repeat(3) { send(CaptureCmdType.SHOT); now += 1_000L; c.poll() }
        assertEquals(3, store.shots.size)
        assertEquals(3, store.shots.map { it.first }.toSet().size)
    }

    @Test fun aBlackScreenIsReportedNotSentAsAPicture() {
        approve()
        host.shot = ShotResult.fail(CaptureFailure.BLANK)
        val id = send(CaptureCmdType.SHOT)
        c.poll()
        assertEquals(CaptureAck.FAILED, ack(id).result)
        assertEquals(CaptureFailure.BLANK, ack(id).reason)
        assertTrue(store.shots.isEmpty())
        assertEquals("failed", c.state.transfer)
        assertEquals(CaptureFailure.BLANK, c.state.error)
        assertEquals("the session itself carries on", CaptureStatus.ACTIVE, c.state.status)
    }

    @Test fun aFailedUploadIsReportedAsFailed() {
        approve()
        store.failShotWrite = true
        val id = send(CaptureCmdType.SHOT)
        c.poll()
        assertEquals(CaptureAck.FAILED, ack(id).result)
        assertEquals(CaptureFailure.UPLOAD, ack(id).reason)
        assertEquals("failed", c.state.transfer)
        assertNull(ack(id).shotId)
    }

    @Test fun theNextSuccessfulScreenshotClearsTheOldProblem() {
        approve()
        host.shot = ShotResult.fail(CaptureFailure.SCREEN_OFF)
        send(CaptureCmdType.SHOT); c.poll()
        assertEquals(CaptureFailure.SCREEN_OFF, c.state.error)
        host.shot = ShotResult.ok(ByteArray(5), 720, 1600)
        send(CaptureCmdType.SHOT); c.poll()
        assertNull(c.state.error)
        assertEquals("ok", c.state.transfer)
    }

    @Test fun anExceptionWhileCapturingBecomesAFailureNotACrash() {
        val throwing = object : CaptureHost by host { override fun takeShot(): ShotResult { throw IllegalStateException("boom") } }
        val c2 = CaptureController(store, throwing, processed, { now }, { "x" })
        store.commands.add(CaptureCommand.create("ask-00000001", CaptureCmdType.ASK, now)); c2.poll()
        assertTrue(c2.onApproved(720, 1600))
        store.commands.add(CaptureCommand.create("shot-0000001", CaptureCmdType.SHOT, now)); c2.poll()
        assertEquals(CaptureAck.FAILED, ack("shot-0000001").result)
        assertEquals(CaptureFailure.ENCODE, ack("shot-0000001").reason)
    }

    @Test fun anOldScreenshotRequestIsNotRunLater() {
        approve()
        val id = "old-shot-0001"
        store.commands.add(CaptureCommand.create(id, CaptureCmdType.SHOT, now - 10 * 60_000L))
        c.poll()
        assertEquals(CaptureAck.EXPIRED, ack(id).result)
        assertEquals(0, host.shotCalls)
        assertTrue(id in store.deleted)
    }

    @Test fun livePreviewNeedsTheParentToKeepLooking() {
        approve()
        send(CaptureCmdType.LIVE, on = true); c.poll()
        assertTrue(c.state.live)
        assertFalse("no heartbeat yet", c.liveWanted(now))
        store.viewerAt = now - 5_000L; c.poll()
        assertTrue(c.liveWanted(now))
        now += CapturePolicy.VIEWER_FRESH_MS + 1_000L
        assertFalse("parent left the screen", c.liveWanted(now))
    }

    @Test fun livePreviewOffStopsIt() {
        approve()
        store.viewerAt = now
        send(CaptureCmdType.LIVE, on = true); c.poll()
        assertTrue(c.liveWanted(now))
        send(CaptureCmdType.LIVE, on = false); c.poll()
        assertFalse(c.liveWanted(now))
        assertEquals(listOf("approved", "live_on", "live_off"), store.events.map { it.kind }.filter { it.startsWith("live") || it == "approved" })
    }

    @Test fun liveFramesGetIncreasingNumbers() {
        approve()
        assertTrue(c.sendLive(ByteArray(3), 360, 800))
        assertTrue(c.sendLive(ByteArray(3), 360, 800))
        assertEquals(listOf(1L, 2L), store.lives)
        assertEquals("ok", c.state.transfer)
    }

    @Test fun liveIsRefusedOutsideASession() {
        assertFalse(c.sendLive(ByteArray(3), 360, 800))
        assertTrue(store.lives.isEmpty())
    }

    @Test fun aBriefLiveFailureIsShownButTheSessionContinues() {
        approve()
        store.failLiveWrite = true
        assertFalse(c.sendLive(ByteArray(3), 360, 800))
        assertEquals("failed", c.state.transfer)
        assertEquals(CaptureFailure.UPLOAD, c.state.error)
        assertEquals(CaptureStatus.ACTIVE, c.state.status)
        store.failLiveWrite = false
        assertTrue(c.sendLive(ByteArray(3), 360, 800))
        assertNull(c.state.error)
    }

    @Test fun losingTheNetworkForTooLongEndsTheSession() {
        approve()
        store.failLiveWrite = true
        now += CapturePolicy.NETWORK_LOSS_END_MS + 1_000L
        assertFalse(c.sendLive(ByteArray(3), 360, 800))
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.NETWORK, c.state.reason)
        assertEquals(1, host.stops)
        assertEquals(1, store.clearedLive)
    }

    @Test fun anInboxThatCannotBeReadEventuallyEndsTheSessionToo() {
        approve()
        store.failInbox = true
        now += 60_000L; c.poll()
        assertEquals("still within the grace period", CaptureStatus.ACTIVE, c.state.status)
        now += CapturePolicy.NETWORK_LOSS_END_MS; c.poll()
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.NETWORK, c.state.reason)
    }

    @Test fun theSessionEndsAtItsTimeLimit() {
        approve()
        now += CapturePolicy.SESSION_MAX_MS
        c.poll()
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.TIMEOUT, c.state.reason)
        assertEquals(1, host.stops)
    }

    @Test fun parentStopEndsTheSessionAndRemovesTheLiveFrame() {
        approve()
        val id = send(CaptureCmdType.STOP)
        c.poll()
        assertEquals(CaptureAck.DONE, ack(id).result)
        assertEquals(CaptureStatus.ENDED, c.state.status)
        assertEquals(CaptureReasons.PARENT_STOPPED, c.state.reason)
        assertEquals(1, host.stops)
        assertEquals(1, store.clearedLive)
        assertFalse(c.liveWanted(now))
    }

    @Test fun stoppingTwiceChangesNothingTheSecondTime() {
        approve()
        c.stop(CaptureReasons.CHILD_STOPPED)
        c.stop(CaptureReasons.SYSTEM_STOPPED)
        assertEquals(CaptureReasons.CHILD_STOPPED, c.state.reason)
        assertEquals(1, host.stops)
        assertEquals(1, store.events.count { it.kind == "ended" })
    }

    @Test fun androidEndingCaptureIsRecordedAsSuch() {
        approve()
        c.stop(CaptureReasons.SYSTEM_STOPPED)
        assertEquals(CaptureReasons.SYSTEM_STOPPED, store.dbState!!.reason)
        assertEquals(CaptureStatus.ENDED, store.dbState!!.status)
    }

    @Test fun stopWithNothingOpenIsRejectedNotPretendedDone() {
        val id = send(CaptureCmdType.STOP)
        c.poll()
        assertEquals(CaptureAck.REJECTED, ack(id).result)
    }

    @Test fun aRestartCorrectsAStaleSharingStateInTheDatabase() {
        store.dbState = CaptureState(CaptureStatus.ACTIVE, "old", now - 60_000L, now + 60_000L, true, heartbeat = now - 60_000L)
        c.poll()
        assertEquals(CaptureStatus.ENDED, store.dbState!!.status)
        assertEquals(CaptureReasons.SYSTEM_STOPPED, store.dbState!!.reason)
        assertFalse(store.dbState!!.live)
        assertEquals(1, store.clearedLive)
        assertEquals(CaptureStatus.IDLE, c.state.status)
    }

    @Test fun aRestartLeavesAnEndedStateAlone() {
        store.dbState = CaptureState(CaptureStatus.ENDED, "old", now - 60_000L, 0L, reason = CaptureReasons.PARENT_STOPPED)
        c.poll()
        assertTrue(store.states.isEmpty())
    }

    @Test fun heartbeatIsRefreshedWhileSharing() {
        approve()
        val first = store.dbState!!.heartbeat
        now += 2_000L
        c.poll()
        assertEquals(first + 2_000L, store.dbState!!.heartbeat)
    }

    @Test fun rotationUpdatesTheReportedSizeOnlyWhenItChanged() {
        approve()
        val writes = store.states.size
        c.resized(720, 1600)
        assertEquals("same size, nothing to write", writes, store.states.size)
        c.resized(1600, 720)
        assertEquals(1600, store.dbState!!.width)
        assertEquals(720, store.dbState!!.height)
    }

    @Test fun noteIsWrittenOnlyWhenItChanges() {
        approve()
        c.note(CaptureFailure.BLANK)
        val writes = store.states.size
        c.note(CaptureFailure.BLANK)
        assertEquals(writes, store.states.size)
        c.note(null)
        assertNull(store.dbState!!.error)
    }

    @Test fun aFailingDatabaseDoesNotCrashTheController() {
        val broken = object : CaptureStore by store {
            override fun writeState(state: CaptureState) { throw java.io.IOException("offline") }
            override fun writeAck(ack: CaptureAck) { throw java.io.IOException("offline") }
            override fun appendLog(event: CaptureEvent) { throw java.io.IOException("offline") }
        }
        val c2 = CaptureController(broken, host, processed, { now }, { "x" })
        store.commands.add(CaptureCommand.create("ask-00000001", CaptureCmdType.ASK, now))
        assertTrue(c2.poll())
        assertEquals(CaptureStatus.AWAITING, c2.state.status)
        assertEquals(1, host.prompts)
    }

    @Test fun onChangeFollowsTheState() {
        val seen = ArrayList<CaptureStatus>()
        c.onChange = { seen.add(it.status) }
        approve()
        c.stop(CaptureReasons.CHILD_STOPPED)
        assertEquals(CaptureStatus.AWAITING, seen.first())
        assertEquals(CaptureStatus.ENDED, seen.last())
        assertTrue(CaptureStatus.ACTIVE in seen)
    }
}
