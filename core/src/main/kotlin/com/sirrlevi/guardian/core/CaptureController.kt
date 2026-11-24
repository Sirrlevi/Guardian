package com.sirrlevi.guardian.core

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/*
 * The child's side of approved screen sharing, with no Android types so every rule can be unit-tested:
 * what happens to each request, when a session must end, and what is reported back.
 *
 * Android-specific work (the consent dialog, MediaProjection, notifications, the network) sits behind the three small
 * interfaces below.
 */

/** Where state, answers and pictures go. Implemented over the Realtime Database; faked in tests. */
interface CaptureStore {
    fun readInbox(): CaptureInbox
    fun readState(): CaptureState?
    fun writeState(state: CaptureState)
    fun writeAck(ack: CaptureAck)
    fun deleteCommand(id: String)
    fun appendLog(event: CaptureEvent)
    fun writeShot(id: String, ts: Long, width: Int, height: Int, jpeg: ByteArray)
    fun writeLive(seq: Long, ts: Long, width: Int, height: Int, jpeg: ByteArray, lastUploadMs: Long)
    fun clearLive()
    fun pruneShots(keep: Int)
}

class ShotResult private constructor(val jpeg: ByteArray?, val width: Int, val height: Int, val failure: String?) {
    companion object {
        fun ok(jpeg: ByteArray, width: Int, height: Int) = ShotResult(jpeg, width, height, null)
        fun fail(reason: String) = ShotResult(null, 0, 0, reason)
    }
}

/** What the phone itself has to do. */
interface CaptureHost {
    /** Tell the child (notification) that a request is waiting for their decision. */
    fun showPrompt(expiresAt: Long)
    fun hidePrompt()
    /** Stop capturing and remove the "sharing" notification. Must be safe to call more than once. */
    fun stopSession()
    /** Take one picture of the screen right now, or say plainly why it cannot be done. */
    fun takeShot(): ShotResult
    fun notificationsEnabled(): Boolean
}

/** Request ids already handled, kept across restarts so an old request can never run a second time. */
interface ProcessedIds {
    fun load(): List<String>
    fun save(ids: List<String>)
}

class CaptureController(
    private val store: CaptureStore,
    private val host: CaptureHost,
    private val processed: ProcessedIds,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {
    @Volatile var state: CaptureState = CaptureState(); private set
    /** Called (from whichever thread changed the state) so the screen can follow along. */
    @Volatile var onChange: ((CaptureState) -> Unit)? = null

    private val lock = ReentrantLock()
    private var handled: List<String> = try { processed.load() } catch (_: Exception) { emptyList() }
    private var viewerAt = 0L
    private var seq = 0L
    private var lastUploadMs = 0L
    private var lastNetworkOkAt = 0L
    private var shotCounter = 0
    private var reconciled = false

    /** One round: time-based endings, then whatever the parent asked. Returns false when another round was already running. */
    fun poll(): Boolean {
        if (!lock.tryLock()) return false
        try {
            val now = clock()
            if (!reconciled) { reconciled = true; reconcileAfterRestart(now) }
            when (state.status) {
                CaptureStatus.ACTIVE -> if (now >= state.expiresAt) end(CaptureReasons.TIMEOUT, now)
                CaptureStatus.AWAITING -> if (now > state.expiresAt) end(CaptureReasons.EXPIRED, now)
                else -> Unit
            }
            val inbox = try {
                store.readInbox().also { lastNetworkOkAt = now }
            } catch (e: Exception) {
                log("inbox read failed", e)
                if (state.status == CaptureStatus.ACTIVE && now - lastNetworkOkAt > CapturePolicy.NETWORK_LOSS_END_MS) end(CaptureReasons.NETWORK, now)
                null
            }
            if (inbox != null) {
                viewerAt = inbox.viewerAt
                val sorted = CaptureCommands.sort(inbox.commands, handled.toSet(), now)
                sorted.seen.forEach { quietly { store.deleteCommand(it.id) } }
                for (c in sorted.expired) {
                    remember(c.id)
                    ack(c.id, CaptureAck.EXPIRED, "It was not done in time.", null, now)
                    event("expired", c.type.code, now)
                    quietly { store.deleteCommand(c.id) }
                }
                for (c in sorted.due) handle(c, now)
            }
            if (state.status == CaptureStatus.ACTIVE || state.status == CaptureStatus.AWAITING) publish(clock())
            return true
        } finally {
            lock.unlock()
        }
    }

    /** The child allowed sharing and Android granted capture. False means "do not capture": the request is no longer open. */
    fun onApproved(width: Int, height: Int): Boolean {
        lock.lock()
        try {
            val now = clock()
            if (state.status != CaptureStatus.AWAITING || now > state.expiresAt) return false
            lastNetworkOkAt = now
            setState(
                CaptureState(
                    CaptureStatus.ACTIVE, state.sessionId, now, now + CapturePolicy.SESSION_MAX_MS, false, null, width, height,
                    0L, now, state.notifOk, null, null
                ), now
            )
            event("approved", "${width}x$height", now)
            return true
        } finally {
            lock.unlock()
        }
    }

    /** The child said no, or closed Android's dialog without allowing. */
    fun onDeclined() {
        lock.lock()
        try { if (state.status == CaptureStatus.AWAITING) end(CaptureReasons.DECLINED, clock()) } finally { lock.unlock() }
    }

    /** Ends the session or request for [reason]; does nothing when nothing is open. Safe to call twice. */
    fun stop(reason: String) {
        lock.lock()
        try { end(reason, clock()) } finally { lock.unlock() }
    }

    /** The screen was rotated or its size changed. */
    fun resized(width: Int, height: Int) {
        lock.lock()
        try {
            if (state.status == CaptureStatus.ACTIVE && (state.width != width || state.height != height)) setState(state.copy(width = width, height = height), clock())
        } finally { lock.unlock() }
    }

    /** True while a preview picture should be produced: the parent switched it on and is still looking. */
    fun liveWanted(now: Long = clock()): Boolean = state.status == CaptureStatus.ACTIVE && state.live && CaptureLogic.viewerPresent(viewerAt, now)

    /** Sends one preview picture. False when it could not be sent; too many failures in a row end the session. */
    fun sendLive(jpeg: ByteArray, width: Int, height: Int): Boolean {
        lock.lock()
        try {
            if (state.status != CaptureStatus.ACTIVE) return false
            val started = clock()
            return try {
                store.writeLive(++seq, started, width, height, jpeg, lastUploadMs)
                val done = clock()
                lastUploadMs = done - started
                lastNetworkOkAt = done
                if (state.transfer != "ok" || state.error != null) setState(state.copy(transfer = "ok", error = null), done)
                true
            } catch (e: Exception) {
                log("live upload failed", e)
                val now = clock()
                if (now - lastNetworkOkAt > CapturePolicy.NETWORK_LOSS_END_MS) {
                    end(CaptureReasons.NETWORK, now)
                } else if (state.transfer != "failed") {
                    setState(state.copy(transfer = "failed", error = CaptureFailure.UPLOAD), now)
                }
                false
            }
        } finally { lock.unlock() }
    }

    /** Records a short problem ("screen is black") for the parent, or clears it with null. Only writes when it changed. */
    fun note(message: String?) {
        lock.lock()
        try {
            if (state.status == CaptureStatus.ACTIVE && state.error != message) setState(state.copy(error = message), clock())
        } finally { lock.unlock() }
    }

    // ------------------------------------------------------------------ internals

    private fun handle(cmd: CaptureCommand, now: Long) {
        // Remembered before anything happens: a request is carried out at most once, even if the app dies halfway.
        remember(cmd.id)
        val decision = CaptureLogic.decide(cmd, state.status)
        when (decision.action) {
            CaptureAction.START_PROMPT -> {
                val expires = minOf(cmd.expiresAt, now + CapturePolicy.REQUEST_TTL_MS)
                setState(CaptureState(CaptureStatus.AWAITING, newId(), now, expires, notifOk = safeBool { host.notificationsEnabled() }, heartbeat = now), now)
                quietly { host.showPrompt(expires) }
                event("asked", null, now)
                ack(cmd.id, CaptureAck.DONE, "Waiting for approval on the phone.", null, now)
            }
            CaptureAction.TAKE_SHOT -> shoot(cmd, now)
            CaptureAction.LIVE_ON, CaptureAction.LIVE_OFF -> {
                val on = decision.action == CaptureAction.LIVE_ON
                setState(state.copy(live = on, error = null), now)
                event(if (on) "live_on" else "live_off", null, now)
                ack(cmd.id, CaptureAck.DONE, null, null, now)
            }
            CaptureAction.END_SESSION -> { ack(cmd.id, CaptureAck.DONE, null, null, now); end(CaptureReasons.PARENT_STOPPED, now) }
            CaptureAction.CANCEL_REQUEST -> { ack(cmd.id, CaptureAck.DONE, null, null, now); end(CaptureReasons.CANCELLED, now) }
            CaptureAction.REJECT -> {
                ack(cmd.id, CaptureAck.REJECTED, decision.reason, null, now)
                event("rejected", decision.reason, now)
            }
        }
        quietly { store.deleteCommand(cmd.id) }
    }

    private fun shoot(cmd: CaptureCommand, now: Long) {
        val result = try { host.takeShot() } catch (e: Exception) { log("shot failed", e); ShotResult.fail(CaptureFailure.ENCODE) }
        val jpeg = result.jpeg
        if (jpeg == null) {
            val why = result.failure ?: CaptureFailure.NO_FRAME
            setState(state.copy(transfer = "failed", error = why), now)
            ack(cmd.id, CaptureAck.FAILED, why, null, now)
            event("rejected", why, now)
            return
        }
        setState(state.copy(transfer = "sending"), now)
        val id = ShotCodec.newId(now, shotCounter++ % 100)
        try {
            store.writeShot(id, now, result.width, result.height, jpeg)
        } catch (e: Exception) {
            log("shot upload failed", e)
            setState(state.copy(transfer = "failed", error = CaptureFailure.UPLOAD), clock())
            ack(cmd.id, CaptureAck.FAILED, CaptureFailure.UPLOAD, null, clock())
            return
        }
        lastNetworkOkAt = clock()
        quietly { store.pruneShots(CapturePolicy.SHOT_KEEP) }
        setState(state.copy(lastShotAt = now, transfer = "ok", error = null), clock())
        event("shot", "${result.width}x${result.height}", now)
        ack(cmd.id, CaptureAck.DONE, null, id, clock())
    }

    private fun end(reason: String, now: Long) {
        val before = state
        if (before.status != CaptureStatus.ACTIVE && before.status != CaptureStatus.AWAITING) return
        setState(
            CaptureState(CaptureStatus.ENDED, before.sessionId, now, 0L, false, reason, before.width, before.height, before.lastShotAt, now, before.notifOk, null, null),
            now
        )
        quietly { host.hidePrompt() }
        quietly { host.stopSession() }
        if (before.status == CaptureStatus.ACTIVE) quietly { store.clearLive() }
        event(
            when (reason) {
                CaptureReasons.DECLINED -> "declined"
                CaptureReasons.EXPIRED, CaptureReasons.CANCELLED -> "expired"
                else -> "ended"
            },
            reason, now
        )
    }

    /** After the app was killed the database may still say "sharing"; that is no longer true, so say so. */
    private fun reconcileAfterRestart(now: Long) {
        if (state.status != CaptureStatus.IDLE) return
        val stored: CaptureState? = try { store.readState() } catch (e: Exception) { log("state read failed", e); null }
        val old = stored ?: return
        if (old.status != CaptureStatus.ACTIVE && old.status != CaptureStatus.AWAITING) return
        val reason = if (old.status == CaptureStatus.ACTIVE) CaptureReasons.SYSTEM_STOPPED else CaptureReasons.EXPIRED
        quietly { store.writeState(old.copy(status = CaptureStatus.ENDED, reason = reason, live = false, since = now, expiresAt = 0L, heartbeat = now, error = null, transfer = null)) }
        if (old.status == CaptureStatus.ACTIVE) quietly { store.clearLive() }
        event("ended", reason, now)
    }

    private fun setState(next: CaptureState, now: Long) {
        state = next
        onChange?.let { quietly { it(next) } }
        publish(now)
    }

    private fun publish(now: Long) {
        quietly { store.writeState(state.copy(heartbeat = now)) }
    }

    private fun ack(id: String, result: String, reason: String?, shotId: String?, now: Long) {
        quietly { store.writeAck(CaptureAck(id, result, reason, shotId, now)) }
    }

    private fun event(kind: String, detail: String?, now: Long) {
        quietly { store.appendLog(CaptureEvent(now, kind, detail)) }
    }

    private fun remember(id: String) {
        handled = CaptureCommands.remember(handled, id)
        quietly { processed.save(handled) }
    }

    private inline fun quietly(block: () -> Unit) {
        try { block() } catch (e: Exception) { log("capture step failed", e) }
    }

    private inline fun safeBool(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { true }
}
