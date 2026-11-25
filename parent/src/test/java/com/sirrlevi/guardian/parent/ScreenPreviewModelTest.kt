package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.CaptureAck
import com.sirrlevi.guardian.core.CaptureCmdType
import com.sirrlevi.guardian.core.CaptureCommand
import com.sirrlevi.guardian.core.CaptureCommandCodec
import com.sirrlevi.guardian.core.CaptureEvent
import com.sirrlevi.guardian.core.CaptureHeadline
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStatus
import com.sirrlevi.guardian.core.LinkQuality
import com.sirrlevi.guardian.core.LiveMeta
import com.sirrlevi.guardian.core.ShotMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** A phone and a database in memory, so the parent's side of screen sharing can be exercised without a network. */
class FakePreviewBackend : PreviewBackend {
    var stateNow: CaptureState? = null
    val sent = ArrayList<CaptureCommand>()
    val acks = HashMap<String, CaptureAck>()
    val deletedAcks = ArrayList<String>()
    var shotList = listOf<ShotMeta>()
    val images = HashMap<String, ByteArray>()
    var shotImageCalls = 0
    val deletedShots = ArrayList<String>()
    var liveMetaNow: LiveMeta? = null
    var liveBytes: ByteArray? = null
    var liveImageCalls = 0
    var events = listOf<CaptureEvent>()
    val viewerWrites = ArrayList<Long>()
    var viewerCleared = 0
    var failWith: Exception? = null

    private fun check() { failWith?.let { throw it } }
    override fun state(): CaptureState? { check(); return stateNow }
    override fun send(command: CaptureCommand) { check(); sent.add(command) }
    override fun ack(id: String): CaptureAck? { check(); return acks[id] }
    override fun deleteAck(id: String) { check(); acks.remove(id); deletedAcks.add(id) }
    override fun shots(): List<ShotMeta> { check(); return shotList }
    override fun shotImage(meta: ShotMeta): ByteArray? { check(); shotImageCalls++; return images[meta.id] }
    override fun deleteShot(id: String) { check(); deletedShots.add(id); shotList = shotList.filter { it.id != id } }
    override fun liveMeta(): LiveMeta? { check(); return liveMetaNow }
    override fun liveImage(meta: LiveMeta): ByteArray? { check(); liveImageCalls++; return liveBytes }
    override fun log(): List<CaptureEvent> { check(); return events }
    override fun viewerHere(now: Long) { check(); viewerWrites.add(now) }
    override fun viewerGone() { viewerCleared++ }
}

/**
 * Plain JVM test on purpose: the model only needs Compose's state runtime, and running it under Robolectric wrote Compose state
 * outside any composition in the same sandbox the screen tests later run in, which left their Compose idle check spinning forever.
 */
class ScreenPreviewModelTest {
    private var time = 1_800_000_000_000L
    private val backend = FakePreviewBackend()
    private var counter = 0
    private val model = ScreenPreviewModel({ backend }, { time }, { "command-" + (++counter).toString().padStart(4, '0') }, io = EmptyCoroutineContext)

    private fun active(live: Boolean = false, heartbeat: Long = time, lastShot: Long = 0L, expires: Long = time + 600_000L) =
        CaptureState(status = CaptureStatus.ACTIVE, sessionId = "s1", since = time - 5_000L, expiresAt = expires, live = live, heartbeat = heartbeat, lastShotAt = lastShot, width = 540, height = 1200)

    private fun shot(n: Int) = ShotMeta(String.format("%013d_%02d", time + n, 0), time + n, 540, 1200, 1000, 1)

    @Test fun firstRefreshShowsNotSharingAndAllowsAsking() = runBlocking {
        model.tick()
        assertTrue(model.loaded)
        assertNull(model.error)
        assertEquals(CaptureHeadline.NONE, model.view.headline)
        assertTrue(model.view.canAsk)
        assertFalse(model.view.canShot)
    }

    @Test fun askSendsOneValidCommandThatLastsTenMinutes() = runBlocking {
        model.tick()
        assertTrue(model.ask())
        val cmd = backend.sent.single()
        assertEquals(CaptureCmdType.ASK, cmd.type)
        assertTrue(CaptureCommandCodec.isValidId(cmd.id))
        assertEquals(time + CapturePolicy.REQUEST_TTL_MS, cmd.expiresAt)
        assertEquals(CaptureCmdType.ASK, model.waiting)
        assertEquals("Request sent. Waiting for the phone.", model.notice)
        // Asking again while it is still waiting does not pile up a second request.
        assertFalse(model.ask())
        assertEquals(1, backend.sent.size)
    }

    @Test fun phoneAnswerIsCollectedAndItsAckRemoved() = runBlocking {
        model.tick()
        model.ask()
        val id = backend.sent.single().id
        backend.acks[id] = CaptureAck(id, CaptureAck.DONE, null, null, time)
        model.tick()
        assertNull(model.waiting)
        assertFalse(model.noticeIsProblem)
        assertTrue(model.notice!!.contains("only if the child allows"))
        assertEquals(listOf(id), backend.deletedAcks)
    }

    @Test fun rejectedRequestShowsThePhonesReason() = runBlocking {
        model.tick()
        model.ask()
        val id = backend.sent.single().id
        backend.acks[id] = CaptureAck(id, CaptureAck.REJECTED, "A session is already running.", null, time)
        model.tick()
        assertEquals("A session is already running.", model.notice)
        assertTrue(model.noticeIsProblem)
    }

    @Test fun unansweredRequestEndsAsNoAnswerNotAsSuccess() = runBlocking {
        backend.stateNow = active()
        model.tick()
        model.shot()
        assertEquals(CaptureCmdType.SHOT, model.waiting)
        time += CapturePolicy.COMMAND_TTL_MS + CapturePolicy.CLOCK_SKEW_MS + 1_000L
        backend.stateNow = active() // the phone is still reporting, it just never answered this request
        model.tick()
        assertNull(model.waiting)
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.contains("didn't answer"))
    }

    @Test fun screenshotsAreOnlyPossibleDuringAnApprovedSession() = runBlocking {
        model.tick()
        assertFalse(model.shot())
        assertTrue(backend.sent.isEmpty())
        assertTrue(model.noticeIsProblem)
    }

    @Test fun screenshotRequestsAreNotStackedAndFailuresAreExplained() = runBlocking {
        backend.stateNow = active()
        model.tick()
        assertTrue(model.shot())
        assertFalse(model.shot())
        assertEquals(1, backend.sent.size)
        val id = backend.sent.single().id
        backend.acks[id] = CaptureAck(id, CaptureAck.FAILED, "The phone's screen is off.", null, time)
        model.tick()
        assertEquals("The phone's screen is off.", model.notice)
        assertTrue(model.noticeIsProblem)
        // After the answer a new screenshot can be asked for.
        assertTrue(model.shot())
        assertEquals(2, backend.sent.size)
    }

    @Test fun aSilentPhoneIsShownAsNotRespondingAndNothingCanBeAsked() = runBlocking {
        backend.stateNow = active(heartbeat = time - CapturePolicy.STATE_STALE_MS - 5_000L)
        model.tick()
        assertEquals(CaptureHeadline.NOT_RESPONDING, model.view.headline)
        assertFalse(model.view.canShot)
        assertTrue(model.view.canStop)
        assertFalse(model.shot())
        assertTrue(backend.sent.isEmpty())
    }

    @Test fun liveFramesAreFetchedOnlyWhenNewAndTheViewerHeartbeatIsKept() = runBlocking {
        backend.stateNow = active(live = true)
        backend.liveMetaNow = LiveMeta(1, time, 540, 1200, 5000, 1, 400)
        backend.liveBytes = byteArrayOf(1, 2, 3)
        model.tick()
        assertEquals(1L, model.live!!.seq)
        assertArrayEquals(byteArrayOf(1, 2, 3), model.liveJpeg)
        assertEquals(1, backend.liveImageCalls)
        assertEquals(1, backend.viewerWrites.size)
        // Same picture again: nothing is downloaded a second time.
        time += 2_000L
        model.tick()
        assertEquals(1, backend.liveImageCalls)
        // A newer picture is downloaded.
        backend.liveMetaNow = LiveMeta(2, time, 540, 1200, 5000, 1, 400)
        backend.liveBytes = byteArrayOf(9)
        model.tick()
        assertEquals(2, backend.liveImageCalls)
        assertArrayEquals(byteArrayOf(9), model.liveJpeg)
        assertEquals(LinkQuality.GOOD, model.link)
        // The heartbeat is repeated every few seconds, not on every refresh.
        assertEquals(1, backend.viewerWrites.size)
        time += ScreenPreviewModel.VIEWER_EVERY_MS
        backend.stateNow = active(live = true)
        model.tick()
        assertEquals(2, backend.viewerWrites.size)
    }

    @Test fun aPictureThatCouldNotBeReadInFullIsSkippedNotShownHalfWay() = runBlocking {
        backend.stateNow = active(live = true)
        backend.liveMetaNow = LiveMeta(5, time, 540, 1200, 5000, 2, 400)
        backend.liveBytes = null
        model.tick()
        assertNull(model.liveJpeg)
        assertNull(model.live)
    }

    @Test fun liveIsClearedAndTheViewerReleasedWhenTheSessionEnds() = runBlocking {
        backend.stateNow = active(live = true)
        backend.liveMetaNow = LiveMeta(1, time, 540, 1200, 5000, 1, 400)
        backend.liveBytes = byteArrayOf(1)
        model.tick()
        assertNotNull(model.liveJpeg)
        backend.stateNow = CaptureState(status = CaptureStatus.ENDED, reason = "child_stopped", since = time)
        model.tick()
        assertNull(model.liveJpeg)
        assertNull(model.live)
        assertEquals(1, backend.viewerCleared)
        assertEquals(CaptureHeadline.ENDED, model.view.headline)
    }

    @Test fun startingLiveTellsThePhoneIAmWatchingBeforeAskingForPictures() = runBlocking {
        backend.stateNow = active(live = false)
        model.tick()
        assertTrue(model.setLive(true))
        assertEquals(1, backend.viewerWrites.size)
        val cmd = backend.sent.single()
        assertEquals(CaptureCmdType.LIVE, cmd.type)
        assertTrue(cmd.on)
    }

    @Test fun lostConnectionKeepsLastKnownDataAndSaysSo() = runBlocking {
        backend.stateNow = active()
        model.tick()
        backend.failWith = IOException("Unable to resolve host \"x\"")
        model.tick()
        assertEquals("No internet connection.", model.error)
        assertEquals(CaptureStatus.ACTIVE, model.state!!.status)
        backend.failWith = null
        model.tick()
        assertNull(model.error)
    }

    @Test fun failingToSendIsReportedNotHidden() = runBlocking {
        model.tick()
        backend.failWith = IOException("denied")
        assertFalse(model.ask())
        assertTrue(model.noticeIsProblem)
        assertTrue(model.notice!!.startsWith("Couldn't send that"))
        assertNull(model.waiting)
    }

    @Test fun screenshotsAndHistoryReloadWhenTheNewestChanges() = runBlocking {
        backend.stateNow = active(lastShot = 0L)
        model.tick()
        assertTrue(model.shots.isEmpty())
        backend.shotList = listOf(shot(2), shot(1))
        backend.events = listOf(CaptureEvent(time, "shot", null))
        backend.stateNow = active(lastShot = time + 2)
        model.tick()
        assertEquals(2, model.shots.size)
        assertEquals(1, model.log.size)
    }

    @Test fun screenshotImagesAreCachedAndDeletingRemovesThem() = runBlocking {
        val a = shot(2)
        backend.shotList = listOf(a)
        backend.images[a.id] = byteArrayOf(7, 7)
        model.tick()
        assertArrayEquals(byteArrayOf(7, 7), model.shotImage(a))
        assertArrayEquals(byteArrayOf(7, 7), model.shotImage(a))
        assertEquals(1, backend.shotImageCalls)
        assertTrue(model.deleteShot(a))
        assertEquals(listOf(a.id), backend.deletedShots)
        assertTrue(model.shots.isEmpty())
    }

    @Test fun resetForgetsEverythingWhenAnotherPhoneIsChosen() = runBlocking {
        backend.stateNow = active(live = true)
        backend.liveMetaNow = LiveMeta(1, time, 540, 1200, 5000, 1, 400)
        backend.liveBytes = byteArrayOf(1)
        backend.shotList = listOf(shot(1))
        model.tick()
        model.reset()
        assertNull(model.state)
        assertNull(model.liveJpeg)
        assertTrue(model.shots.isEmpty())
        assertFalse(model.loaded)
        assertNull(model.notice)
    }
}
