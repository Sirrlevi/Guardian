package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTest {
    private val now = 1_800_000_000_000L

    private fun cmd(id: String, type: CaptureCmdType, ts: Long = now, on: Boolean = false) = CaptureCommand.create(id, type, ts, on)

    @Test fun stateRoundTripsEveryField() {
        val s = CaptureState(
            CaptureStatus.ACTIVE, "sess-0001", now, now + CapturePolicy.SESSION_MAX_MS, true, null, 1080, 2400, now - 5, now, false, "x", "ok"
        )
        assertEquals(s, CaptureStateCodec.fromJson(CaptureStateCodec.toJson(s)))
    }

    @Test fun stateWithMissingFieldsStillReads() {
        val s = CaptureStateCodec.fromJson(JsonParser.parseString("""{"st":"await"}""").asJsonObject)
        assertNotNull(s)
        assertEquals(CaptureStatus.AWAITING, s!!.status)
        assertTrue("notifications default to fine", s.notifOk)
        assertNull(CaptureStateCodec.fromJson(null))
    }

    @Test fun unknownStatusMeansIdleNotActive() {
        assertEquals(CaptureStatus.IDLE, CaptureStatus.fromCode("recording-forever"))
        assertEquals(CaptureStatus.IDLE, CaptureStatus.fromCode(null))
    }

    @Test fun commandRoundTripsAndKeepsTheOnFlagOnlyForLive() {
        val live = cmd("abcdef123456", CaptureCmdType.LIVE, on = true)
        val back = CaptureCommandCodec.fromJson(live.id, CaptureCommandCodec.toJson(live))
        assertEquals(live, back)
        assertFalse(CaptureCommandCodec.toJson(cmd("abcdef123456", CaptureCmdType.SHOT)).has("on"))
    }

    @Test fun badCommandsAreDropped() {
        val ok = CaptureCommandCodec.toJson(cmd("abcdef123456", CaptureCmdType.SHOT))
        assertNull("unknown type", CaptureCommandCodec.fromJson("abcdef123456", JsonParser.parseString("""{"t":"wipe","ts":1,"exp":2}""").asJsonObject))
        assertNull("short id", CaptureCommandCodec.fromJson("abc", ok))
        assertNull("path-like id", CaptureCommandCodec.fromJson("../../secret12", ok))
        assertNull("id too long", CaptureCommandCodec.fromJson("a".repeat(41), ok))
        assertNull("wrong value types", CaptureCommandCodec.fromJson("abcdef123456", JsonParser.parseString("""{"t":"shot","ts":"soon","exp":{}}""").asJsonObject))
    }

    @Test fun askLivesLongerThanOtherCommands() {
        assertEquals(CapturePolicy.REQUEST_TTL_MS, cmd("abcdef123456", CaptureCmdType.ASK).expiresAt - now)
        assertEquals(CapturePolicy.COMMAND_TTL_MS, cmd("abcdef123456", CaptureCmdType.SHOT).expiresAt - now)
    }

    @Test fun inboxReadsCommandsAndViewerHeartbeat() {
        val json = JsonObject().apply {
            add("cmd", JsonObject().apply {
                add("abcdef123456", CaptureCommandCodec.toJson(cmd("abcdef123456", CaptureCmdType.SHOT)))
                add("zzzzzzzz9999", CaptureCommandCodec.toJson(cmd("zzzzzzzz9999", CaptureCmdType.ASK)))
                addProperty("junk-entry", "not an object")
            })
            addProperty("viewer", now - 3_000L)
        }
        val inbox = CaptureInboxCodec.fromJson(json)
        assertEquals(setOf("abcdef123456", "zzzzzzzz9999"), inbox.commands.map { it.id }.toSet())
        assertEquals(now - 3_000L, inbox.viewerAt)
        assertEquals(CaptureInbox(emptyList(), 0L), CaptureInboxCodec.fromJson(null))
        assertEquals(0L, CaptureInboxCodec.fromJson(JsonParser.parseString("""{"viewer":{"x":1}}""").asJsonObject).viewerAt)
    }

    @Test fun sortingSeparatesDueExpiredAndAlreadyHandled() {
        val fresh = cmd("fresh-000001", CaptureCmdType.SHOT, now - 1_000L)
        val older = cmd("older-000001", CaptureCmdType.SHOT, now - 5_000L)
        val stale = cmd("stale-000001", CaptureCmdType.SHOT, now - 10 * 60_000L)
        val done = cmd("done-0000001", CaptureCmdType.SHOT, now - 2_000L)
        val sorted = CaptureCommands.sort(listOf(fresh, done, stale, older), setOf(done.id), now)
        assertEquals("oldest first", listOf(older.id, fresh.id), sorted.due.map { it.id })
        assertEquals(listOf(stale.id), sorted.expired.map { it.id })
        assertEquals(listOf(done.id), sorted.seen.map { it.id })
    }

    @Test fun smallClockDifferenceDoesNotExpireACommand() {
        val c = CaptureCommand("abcdef123456", CaptureCmdType.SHOT, now, now + 1_000L)
        assertFalse(CaptureCommands.isExpired(c, now + 1_000L + CapturePolicy.CLOCK_SKEW_MS))
        assertTrue(CaptureCommands.isExpired(c, now + 1_001L + CapturePolicy.CLOCK_SKEW_MS))
    }

    @Test fun replayedCommandIsNeverDoneTwice() {
        val c = cmd("abcdef123456", CaptureCmdType.SHOT)
        val first = CaptureCommands.sort(listOf(c), emptySet(), now)
        assertEquals(1, first.due.size)
        val remembered = CaptureCommands.remember(emptyList(), c.id).toSet()
        val again = CaptureCommands.sort(listOf(c), remembered, now)
        assertTrue(again.due.isEmpty())
        assertEquals(1, again.seen.size)
    }

    @Test fun rememberedIdsAreCapped() {
        var list = emptyList<String>()
        repeat(CapturePolicy.PROCESSED_KEEP + 25) { list = CaptureCommands.remember(list, "id-%06d".format(it)) }
        assertEquals(CapturePolicy.PROCESSED_KEEP, list.size)
        assertEquals("id-%06d".format(CapturePolicy.PROCESSED_KEEP + 24), list.last())
        assertEquals(list.size, CaptureCommands.remember(list, list.last()).size)
    }

    @Test fun askStartsAPromptOnlyWhenNothingIsRunning() {
        val ask = cmd("abcdef123456", CaptureCmdType.ASK)
        assertEquals(CaptureAction.START_PROMPT, CaptureLogic.decide(ask, CaptureStatus.IDLE).action)
        assertEquals(CaptureAction.START_PROMPT, CaptureLogic.decide(ask, CaptureStatus.ENDED).action)
        assertEquals(CaptureAction.REJECT, CaptureLogic.decide(ask, CaptureStatus.ACTIVE).action)
        assertEquals(CaptureAction.REJECT, CaptureLogic.decide(ask, CaptureStatus.AWAITING).action)
    }

    @Test fun shotsAndLiveNeedAnApprovedSession() {
        for (status in listOf(CaptureStatus.IDLE, CaptureStatus.AWAITING, CaptureStatus.ENDED)) {
            assertEquals(status.name, CaptureAction.REJECT, CaptureLogic.decide(cmd("abcdef123456", CaptureCmdType.SHOT), status).action)
            assertEquals(status.name, CaptureAction.REJECT, CaptureLogic.decide(cmd("abcdef123456", CaptureCmdType.LIVE, on = true), status).action)
        }
        assertEquals(CaptureFailure.NO_SESSION, CaptureLogic.decide(cmd("abcdef123456", CaptureCmdType.SHOT), CaptureStatus.IDLE).reason)
        assertEquals(CaptureAction.TAKE_SHOT, CaptureLogic.decide(cmd("abcdef123456", CaptureCmdType.SHOT), CaptureStatus.ACTIVE).action)
        assertEquals(CaptureAction.LIVE_ON, CaptureLogic.decide(cmd("abcdef123456", CaptureCmdType.LIVE, on = true), CaptureStatus.ACTIVE).action)
        assertEquals(CaptureAction.LIVE_OFF, CaptureLogic.decide(cmd("abcdef123456", CaptureCmdType.LIVE, on = false), CaptureStatus.ACTIVE).action)
    }

    @Test fun stopEndsASessionOrWithdrawsARequest() {
        val stop = cmd("abcdef123456", CaptureCmdType.STOP)
        assertEquals(CaptureAction.END_SESSION, CaptureLogic.decide(stop, CaptureStatus.ACTIVE).action)
        assertEquals(CaptureAction.CANCEL_REQUEST, CaptureLogic.decide(stop, CaptureStatus.AWAITING).action)
        assertEquals(CaptureAction.REJECT, CaptureLogic.decide(stop, CaptureStatus.IDLE).action)
    }

    @Test fun previewRunsOnlyWhileTheParentIsLooking() {
        assertTrue(CaptureLogic.viewerPresent(now - 10_000L, now))
        assertTrue(CaptureLogic.viewerPresent(now - CapturePolicy.VIEWER_FRESH_MS, now))
        assertFalse(CaptureLogic.viewerPresent(now - CapturePolicy.VIEWER_FRESH_MS - 1L, now))
        assertFalse("never opened", CaptureLogic.viewerPresent(0L, now))
    }

    @Test fun ackShotAndLiveRoundTrip() {
        val ack = CaptureAck("abcdef123456", CaptureAck.FAILED, CaptureFailure.BLANK, null, now)
        assertEquals(ack, CaptureAckCodec.fromJson(ack.id, CaptureAckCodec.toJson(ack)))
        assertNull(CaptureAckCodec.fromJson("x", JsonObject()))
        val meta = ShotMeta(ShotCodec.newId(now, 3), now, 1080, 2400, 210_000, 2)
        assertEquals(meta, ShotCodec.fromJson(meta.id, ShotCodec.toJson(meta)))
        val live = LiveMeta(41, now, 540, 1200, 22_000, 1, 640)
        assertEquals(live, LiveCodec.fromJson(LiveCodec.toJson(live)))
        assertNull(LiveCodec.fromJson(JsonParser.parseString("""{"seq":1}""").asJsonObject))
    }

    @Test fun shotIdsSortByTimeAndOldOnesArePruned() {
        val ids = (1..14).map { ShotCodec.newId(now + it * 1000L, 0) }.shuffled()
        val prune = ShotCodec.toPrune(ids, CapturePolicy.SHOT_KEEP)
        assertEquals(4, prune.size)
        assertEquals((1..4).map { ShotCodec.newId(now + it * 1000L, 0) }, prune)
        assertTrue(ShotCodec.toPrune(ids.take(3), CapturePolicy.SHOT_KEEP).isEmpty())
    }

    @Test fun eventsRoundTripAndHaveReadableLabels() {
        val e = CaptureEvent(now, "shot", "1080x2400")
        assertEquals(e, CaptureEventCodec.fromJson(CaptureEventCodec.toJson(e)))
        assertEquals("Screenshot taken", CaptureEventCodec.label("shot"))
        assertEquals("something_new", CaptureEventCodec.label("something_new"))
        assertTrue(CaptureEventCodec.key(now, 1) < CaptureEventCodec.key(now + 1, 0))
    }

    @Test fun linkQualityFollowsFrameAgeAndUploadTime() {
        assertEquals(LinkQuality.UNKNOWN, CaptureLink.of(now, 0L, 0L))
        assertEquals(LinkQuality.GOOD, CaptureLink.of(now, now - 2_000L, 700L))
        assertEquals(LinkQuality.SLOW, CaptureLink.of(now, now - 8_000L, 700L))
        assertEquals(LinkQuality.SLOW, CaptureLink.of(now, now - 2_000L, 4_000L))
        assertEquals(LinkQuality.STALLED, CaptureLink.of(now, now - 30_000L, 700L))
    }

    @Test fun blackFramesAreDetectedButRealContentIsNot() {
        assertTrue(BlankFrame.isBlank(IntArray(256) { 0xFF000000.toInt() }))
        assertTrue(BlankFrame.isBlank(IntArray(256) { 0xFF020202.toInt() }))
        val oneBrightPixel = IntArray(256) { 0xFF000000.toInt() }.also { it[100] = 0xFF336699.toInt() }
        assertFalse(BlankFrame.isBlank(oneBrightPixel))
        assertFalse("white", BlankFrame.isBlank(IntArray(256) { 0xFFFFFFFF.toInt() }))
        assertFalse("nothing sampled is not 'blank'", BlankFrame.isBlank(IntArray(0)))
    }

    @Test fun samplePointsStayInsideTheFrame() {
        val pts = BlankFrame.points(1080, 2400, 16)
        assertEquals(256, pts.size)
        assertTrue(pts.all { it.first in 0 until 1080 && it.second in 0 until 2400 })
        assertEquals(256, pts.toSet().size)
        assertTrue(BlankFrame.points(0, 10).isEmpty())
        assertTrue(BlankFrame.points(1, 1, 4).all { it == 0 to 0 })
    }

    @Test fun headlineNoneWhenNothingEverHappened() {
        val v = CaptureView.of(null, now)
        assertEquals(CaptureHeadline.NONE, v.headline)
        assertTrue(v.canAsk); assertFalse(v.canShot); assertFalse(v.canLive); assertFalse(v.canStop)
    }

    @Test fun headlineWaitingOffersOnlyStop() {
        val s = CaptureState(CaptureStatus.AWAITING, "s", now, now + CapturePolicy.REQUEST_TTL_MS, heartbeat = now)
        val v = CaptureView.of(s, now + 5_000L)
        assertEquals(CaptureHeadline.WAITING, v.headline)
        assertFalse(v.canAsk); assertTrue(v.canStop); assertFalse(v.canShot)
    }

    @Test fun anUnansweredRequestLapsesOnTheParentToo() {
        val s = CaptureState(CaptureStatus.AWAITING, "s", now, now + CapturePolicy.REQUEST_TTL_MS)
        val v = CaptureView.of(s, now + CapturePolicy.REQUEST_TTL_MS + CapturePolicy.CLOCK_SKEW_MS + 1L)
        assertEquals(CaptureHeadline.ENDED, v.headline)
        assertEquals(CaptureReasons.EXPIRED, v.reason)
        assertTrue(v.canAsk)
    }

    @Test fun headlineSharingAllowsShotsLiveAndStop() {
        val s = CaptureState(CaptureStatus.ACTIVE, "s", now, now + CapturePolicy.SESSION_MAX_MS, heartbeat = now)
        val v = CaptureView.of(s, now + 4_000L)
        assertEquals(CaptureHeadline.SHARING, v.headline)
        assertTrue(v.canShot); assertTrue(v.canLive); assertTrue(v.canStop); assertFalse(v.canAsk)
    }

    @Test fun aSilentPhoneIsNotReportedAsSharing() {
        val s = CaptureState(CaptureStatus.ACTIVE, "s", now, now + CapturePolicy.SESSION_MAX_MS, heartbeat = now)
        val v = CaptureView.of(s, now + CapturePolicy.STATE_STALE_MS + 1L)
        assertEquals(CaptureHeadline.NOT_RESPONDING, v.headline)
        assertFalse("no screenshot button for a phone that is not answering", v.canShot)
        assertTrue(v.canStop)
    }

    @Test fun aSessionPastItsLimitCountsAsEndedEvenIfTheStateIsOld() {
        val s = CaptureState(CaptureStatus.ACTIVE, "s", now, now + CapturePolicy.SESSION_MAX_MS, heartbeat = now)
        val v = CaptureView.of(s, now + CapturePolicy.SESSION_MAX_MS + CapturePolicy.CLOCK_SKEW_MS + 1L)
        assertEquals(CaptureHeadline.ENDED, v.headline)
        assertEquals(CaptureReasons.TIMEOUT, v.reason)
        assertFalse(v.canShot)
    }

    @Test fun endedShowsItsReasonAndAllowsANewRequest() {
        val v = CaptureView.of(CaptureState(CaptureStatus.ENDED, "s", now, 0L, reason = CaptureReasons.CHILD_STOPPED), now)
        assertEquals(CaptureHeadline.ENDED, v.headline)
        assertEquals(CaptureReasons.CHILD_STOPPED, v.reason)
        assertTrue(v.canAsk)
        assertEquals("Aarav stopped sharing.", CaptureReasons.text(v.reason, "Aarav"))
    }

    @Test fun everyReasonHasAnExplanation() {
        val codes = listOf(
            CaptureReasons.PARENT_STOPPED, CaptureReasons.CHILD_STOPPED, CaptureReasons.SYSTEM_STOPPED, CaptureReasons.TIMEOUT,
            CaptureReasons.NETWORK, CaptureReasons.ERROR, CaptureReasons.DECLINED, CaptureReasons.EXPIRED, CaptureReasons.CANCELLED
        )
        val texts = codes.map { CaptureReasons.text(it, "Kid") }
        assertEquals(codes.size, texts.toSet().size)
        assertTrue(texts.none { it == CaptureReasons.text("unknown", "Kid") })
    }

    @Test fun sharingIsBoundedAndPreviewIsSmall() {
        assertTrue(CapturePolicy.SESSION_MAX_MS <= 60 * 60_000L)
        assertTrue(CapturePolicy.LIVE_EDGE < CapturePolicy.SHOT_EDGE)
        assertTrue(CapturePolicy.LIVE_QUALITY < CapturePolicy.SHOT_QUALITY)
        assertTrue("preview never faster than every two seconds", CapturePolicy.LIVE_INTERVAL_MS >= 2_000L)
    }

    @Test fun pathsStayUnderTheDeviceAndNeverCollideWithOtherData() {
        val base = GuardianPaths.device("fam", "kid")
        listOf(
            GuardianPaths.captureState("fam", "kid"), GuardianPaths.captureInbox("fam", "kid"), GuardianPaths.captureCommand("fam", "kid", "c1"),
            GuardianPaths.captureViewer("fam", "kid"), GuardianPaths.captureAck("fam", "kid", "c1"), GuardianPaths.captureShot("fam", "kid", "s1"),
            GuardianPaths.captureShotBlob("fam", "kid", "s1"), GuardianPaths.captureLive("fam", "kid"), GuardianPaths.captureLiveBlob("fam", "kid"),
            GuardianPaths.captureLog("fam", "kid")
        ).forEach { assertTrue(it, it.startsWith("$base/capture/")) }
        assertEquals("$base/capture/inbox/cmd/c1", GuardianPaths.captureCommand("fam", "kid", "c1"))
    }
}
