package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirrlevi.guardian.core.CaptureAck
import com.sirrlevi.guardian.core.CaptureCmdType
import com.sirrlevi.guardian.core.CaptureCommand
import com.sirrlevi.guardian.core.CaptureEvent
import com.sirrlevi.guardian.core.CaptureLink
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStatus
import com.sirrlevi.guardian.core.CaptureView
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.LinkQuality
import com.sirrlevi.guardian.core.LiveMeta
import com.sirrlevi.guardian.core.ShotMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Everything the Screen preview screen needs from the database. Every call blocks and may throw; the model runs them off the main thread. */
interface PreviewBackend {
    fun state(): CaptureState?
    fun send(command: CaptureCommand)
    fun ack(id: String): CaptureAck?
    fun deleteAck(id: String)
    fun shots(): List<ShotMeta>
    fun shotImage(meta: ShotMeta): ByteArray?
    fun deleteShot(id: String)
    fun liveMeta(): LiveMeta?
    fun liveImage(meta: LiveMeta): ByteArray?
    fun log(): List<CaptureEvent>
    fun viewerHere(now: Long)
    fun viewerGone()
}

/** The real backend: the selected phone of the signed-in parent. */
class RepoPreviewBackend(
    private val repo: ParentRepository,
    private val sessionOf: () -> GuardianSession?,
    private val remember: (GuardianSession) -> Unit,
    private val deviceOf: () -> String?
) : PreviewBackend {
    private fun <T> call(block: (GuardianSession, String) -> T): T {
        val s0 = sessionOf() ?: throw IOException("Not signed in.")
        val id = deviceOf() ?: throw IOException("No phone is connected.")
        val s = repo.fresh(s0)
        if (s !== s0) remember(s)
        return block(s, id)
    }

    override fun state() = call { s, d -> repo.fetchCaptureState(s, d) }
    override fun send(command: CaptureCommand) = call { s, d -> repo.sendCaptureCommand(s, d, command) }
    override fun ack(id: String) = call { s, d -> repo.fetchCaptureAck(s, d, id) }
    override fun deleteAck(id: String) = call { s, d -> repo.deleteCaptureAck(s, d, id) }
    override fun shots() = call { s, d -> repo.fetchShots(s, d) }
    override fun shotImage(meta: ShotMeta) = call { s, d -> repo.fetchShotImage(s, d, meta) }
    override fun deleteShot(id: String) = call { s, d -> repo.deleteShot(s, d, id) }
    override fun liveMeta() = call { s, d -> repo.fetchLiveMeta(s, d) }
    override fun liveImage(meta: LiveMeta) = call { s, d -> repo.fetchLiveImage(s, d, meta) }
    override fun log() = call { s, d -> repo.fetchCaptureLog(s, d) }
    override fun viewerHere(now: Long) = call { s, d -> repo.touchCaptureViewer(s, d, now) }
    override fun viewerGone() = call { s, d -> repo.clearCaptureViewer(s, d) }
}

/**
 * State and actions of the Screen preview screen: what the phone says about the sharing session, the screenshots it sent, and the
 * small live picture. Nothing here is guessed: the status comes from the phone's own report (with its age), and every command is
 * shown as waiting until the phone answers it or it times out.
 *
 * While the screen is open [watch] polls gently (a small status read every few seconds; pictures only when something new exists).
 * It stops by itself when the screen closes or the app goes to the background.
 */
class ScreenPreviewModel(
    private val backend: () -> PreviewBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    var state by mutableStateOf<CaptureState?>(null); private set
    var loaded by mutableStateOf(false); private set
    /** Set when the last refresh could not reach the server; the data on screen is then the last known, not current. */
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableLongStateOf(0L); private set
    var now by mutableLongStateOf(0L); private set
    var shots by mutableStateOf<List<ShotMeta>>(emptyList()); private set
    var log by mutableStateOf<List<CaptureEvent>>(emptyList()); private set
    var live by mutableStateOf<LiveMeta?>(null); private set
    var liveJpeg by mutableStateOf<ByteArray?>(null); private set
    /** Our own clock at the moment a new live picture was first seen, so the link quality never depends on the two phones' clocks agreeing. */
    var liveSeenAt by mutableLongStateOf(0L); private set
    var notice by mutableStateOf<String?>(null); private set
    var noticeIsProblem by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    /** The kind of request still waiting for the phone's answer, if any. */
    var waiting by mutableStateOf<CaptureCmdType?>(null); private set

    @Volatile var foreground = true

    val view: CaptureView get() = CaptureView.of(state, now.takeIf { it > 0L } ?: clock())
    val link: LinkQuality get() = CaptureLink.of(now, liveSeenAt, live?.uploadMs ?: 0L)

    private class Pending(val id: String, val type: CaptureCmdType, val expiresAt: Long)

    private val pending = ArrayList<Pending>()
    private val mutex = Mutex()
    private var viewerSentAt = 0L
    private var marker = ""
    private var shotsLoaded = false
    private val imageCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > CACHE_SHOTS
    }

    /** Polls until cancelled. Call it from a coroutine tied to the screen being visible. */
    suspend fun watch() {
        try {
            while (coroutineContext.isActive) {
                val wait = if (!foreground) 1_000L else withContext(Dispatchers.IO) { tick() }
                delay(wait)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { goneQuietly() }
        }
    }

    /** One refresh. Returns how long to wait before the next one. */
    suspend fun tick(): Long = mutex.withLock {
        val t = clock()
        now = t
        try {
            val b = backend()
            val s = b.state()
            state = s
            loaded = true; error = null; checkedAt = t
            val status = s?.status ?: CaptureStatus.IDLE
            val active = status == CaptureStatus.ACTIVE

            // The phone only sends live pictures while we say we are looking, and says so again every few seconds.
            if (active && (s?.live == true || pending.any { it.type == CaptureCmdType.LIVE }) && t - viewerSentAt >= VIEWER_EVERY_MS) {
                b.viewerHere(t); viewerSentAt = t
            } else if (!active && viewerSentAt != 0L) {
                try { b.viewerGone() } catch (_: Exception) { }
                viewerSentAt = 0L
            }

            settle(b, t)

            val next = "${s?.status?.code}|${s?.sessionId}|${s?.lastShotAt}|${s?.since}|${s?.transfer}|${s?.reason}"
            if (!shotsLoaded || next != marker) {
                shots = b.shots(); shotsLoaded = true
                log = b.log()
                marker = next
            }

            if (active && s?.live == true) refreshLive(b, t)
            else if (live != null || liveJpeg != null) { live = null; liveJpeg = null }

            if (active && s?.live == true) LIVE_POLL_MS else if (status == CaptureStatus.IDLE || status == CaptureStatus.ENDED) IDLE_POLL_MS else ACTIVE_POLL_MS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e)
            ACTIVE_POLL_MS
        }
    }

    private fun refreshLive(b: PreviewBackend, t: Long) {
        val meta = b.liveMeta() ?: return
        if (meta.seq == live?.seq) return
        // The phone writes the picture before its description, but a newer picture can land between our two reads. Then we skip and retry.
        val image = b.liveImage(meta) ?: return
        live = meta; liveJpeg = image; liveSeenAt = t
    }

    /** Collects the phone's answers to our requests, and gives up on requests it never answered. */
    private fun settle(b: PreviewBackend, t: Long) {
        val done = ArrayList<Pending>()
        for (p in pending) {
            val ack = try { b.ack(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (ack != null) {
                report(p.type, ack)
                try { b.deleteAck(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                done.add(p)
            } else if (t > p.expiresAt + CapturePolicy.CLOCK_SKEW_MS) {
                say("The phone didn't answer. It may be offline or Guardian Child may be stopped there.", problem = true)
                done.add(p)
            }
        }
        if (done.isNotEmpty()) { pending.removeAll(done.toSet()); waiting = pending.lastOrNull()?.type }
    }

    private fun report(type: CaptureCmdType, ack: CaptureAck) {
        val reason = ack.reason
        when (ack.result) {
            CaptureAck.DONE -> when (type) {
                CaptureCmdType.ASK -> say("The request is on the phone. Sharing starts only if the child allows it.", problem = false)
                CaptureCmdType.SHOT -> say("Screenshot received.", problem = false)
                CaptureCmdType.STOP -> say("Stopped.", problem = false)
                CaptureCmdType.LIVE -> say(null, problem = false)
            }
            CaptureAck.EXPIRED -> say("The phone got that request too late, so it was ignored.", problem = true)
            else -> say(reason ?: "The phone couldn't do that.", problem = true)
        }
    }

    private fun say(text: String?, problem: Boolean) { notice = text; noticeIsProblem = problem }

    // ------------------------------------------------------------ actions

    suspend fun ask() = send(CaptureCmdType.ASK) { it.canAsk }
    suspend fun shot() = send(CaptureCmdType.SHOT) { it.canShot }
    suspend fun setLive(on: Boolean) = send(CaptureCmdType.LIVE, on) { it.canLive }
    suspend fun stop() = send(CaptureCmdType.STOP) { it.canStop }

    private suspend fun send(type: CaptureCmdType, on: Boolean = false, allowed: (CaptureView) -> Boolean): Boolean {
        val ok = withContext(Dispatchers.IO) {
            mutex.withLock {
                val t = clock()
                if (!allowed(CaptureView.of(state, t))) {
                    say("That isn't possible right now.", problem = true)
                    return@withLock false
                }
                // Asking twice, or taking screenshots faster than the phone answers, would only pile up requests.
                if (type != CaptureCmdType.LIVE && type != CaptureCmdType.STOP && pending.any { it.type == type && t <= it.expiresAt }) {
                    say(if (type == CaptureCmdType.SHOT) "A screenshot is already on its way." else "A request is already waiting.", problem = false)
                    return@withLock false
                }
                val command = CaptureCommand.create(newId(), type, t, on)
                sending = true
                try {
                    val b = backend()
                    if (type == CaptureCmdType.LIVE && on) { b.viewerHere(t); viewerSentAt = t }
                    b.send(command)
                    pending.add(Pending(command.id, type, command.expiresAt))
                    waiting = type
                    say(
                        when (type) {
                            CaptureCmdType.ASK -> "Request sent. Waiting for the phone."
                            CaptureCmdType.SHOT -> "Asking the phone for a screenshot…"
                            CaptureCmdType.LIVE -> if (on) "Starting the live preview…" else "Stopping the live preview…"
                            CaptureCmdType.STOP -> "Stopping…"
                        },
                        problem = false
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("Couldn't send that: " + describe(e), problem = true)
                    false
                } finally {
                    sending = false
                }
            }
        }
        if (ok) withContext(Dispatchers.IO) { tick() }
        return ok
    }

    /** The screenshot's JPEG bytes (the last few are kept so reopening is instant), or null if it can't be loaded. */
    suspend fun shotImage(meta: ShotMeta): ByteArray? {
        synchronized(imageCache) { imageCache[meta.id] }?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                backend().shotImage(meta)?.also { synchronized(imageCache) { imageCache[meta.id] = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    suspend fun deleteShot(meta: ShotMeta): Boolean = withContext(Dispatchers.IO) {
        try {
            backend().deleteShot(meta.id)
            synchronized(imageCache) { imageCache.remove(meta.id) }
            shots = shots.filter { it.id != meta.id }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("Couldn't delete it: " + describe(e), problem = true)
            false
        }
    }

    /** Forget everything, e.g. when another phone is selected or the parent signs out. */
    fun reset() {
        state = null; loaded = false; error = null; checkedAt = 0L; now = 0L
        shots = emptyList(); log = emptyList(); live = null; liveJpeg = null; liveSeenAt = 0L
        notice = null; noticeIsProblem = false; sending = false; waiting = null
        pending.clear(); viewerSentAt = 0L; marker = ""; shotsLoaded = false
        synchronized(imageCache) { imageCache.clear() }
    }

    /** Lets UI tests show fixed data without a network. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(
        state: CaptureState?, shots: List<ShotMeta> = emptyList(), log: List<CaptureEvent> = emptyList(), live: LiveMeta? = null, liveJpeg: ByteArray? = null,
        now: Long = clock(), error: String? = null, notice: String? = null, noticeIsProblem: Boolean = false
    ) {
        this.state = state; this.loaded = true; this.shots = shots; this.log = log; this.live = live; this.liveJpeg = liveJpeg
        this.liveSeenAt = if (live != null) now else 0L
        this.now = now; this.checkedAt = now; this.error = error; this.notice = notice; this.noticeIsProblem = noticeIsProblem
    }

    private fun goneQuietly() {
        if (viewerSentAt == 0L) return
        viewerSentAt = 0L
        try { backend().viewerGone() } catch (_: Exception) { }
    }

    private fun describe(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }

    companion object {
        const val VIEWER_EVERY_MS = 10_000L
        const val LIVE_POLL_MS = 2_000L
        const val ACTIVE_POLL_MS = 3_000L
        const val IDLE_POLL_MS = 6_000L
        const val CACHE_SHOTS = 5
    }
}
