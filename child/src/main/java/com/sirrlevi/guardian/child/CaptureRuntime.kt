package com.sirrlevi.guardian.child

import android.content.Context
import android.util.Log
import com.sirrlevi.guardian.core.CaptureController
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureReasons
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Glue between the phone and [CaptureController]: one controller per app process, one background thread that polls for the
 * parent's requests and produces live pictures, and a state flow the screen can watch.
 *
 * The thread runs while the monitor or a sharing session is alive. Anything that touches the controller from the main thread goes
 * through [worker], so the main thread never waits on a network call.
 */
internal object CaptureRuntime {
    private const val TAG = "GuardianCapture"
    private val flow = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> get() = flow

    @Volatile var engine: CaptureEngine? = null
    @Volatile private var controller: CaptureController? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "guardian-capture-runtime").apply { isDaemon = true } }
    private var ticker: ScheduledFuture<*>? = null
    private var holders = 0
    private var lastPollAt = 0L
    private var lastLiveAt = 0L

    fun stateNow(): CaptureState = flow.value

    private fun controller(context: Context): CaptureController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            val app = context.applicationContext
            val created = CaptureController(
                CloudCaptureStore(ChildCloud(app)), AndroidCaptureHost(app), PrefsProcessedIds(app),
                log = { message, error -> Log.w(TAG, message, error) }
            )
            created.onChange = { flow.value = it }
            controller = created
            return created
        }
    }

    /** Keeps the polling thread running while something (the monitor, a sharing session) needs it. */
    @Synchronized fun retain(context: Context) {
        holders++
        if (ticker == null) {
            val app = context.applicationContext
            ticker = worker.scheduleWithFixedDelay({ tick(app) }, 1_000L, 1_000L, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) { ticker?.cancel(false); ticker = null }
    }

    private fun tick(app: Context) {
        try {
            if (!ChildCloud(app).isPaired()) return
            val c = controller(app)
            val now = System.currentTimeMillis()
            val every = when (c.state.status) {
                CaptureStatus.ACTIVE -> CapturePolicy.ACTIVE_POLL_MS
                CaptureStatus.AWAITING -> CapturePolicy.AWAITING_POLL_MS
                else -> CapturePolicy.IDLE_POLL_MS
            }
            if (now - lastPollAt >= every) { lastPollAt = now; c.poll() }
            if (c.state.status == CaptureStatus.ACTIVE) liveStep(c, now)
        } catch (e: Exception) {
            Log.w(TAG, "tick failed", e)
        }
    }

    private fun liveStep(c: CaptureController, now: Long) {
        if (now - lastLiveAt < CapturePolicy.LIVE_INTERVAL_MS || !c.liveWanted(now)) return
        lastLiveAt = now
        val frame = engine?.live() ?: return
        val jpeg = frame.jpeg
        if (jpeg == null) { c.note(frame.note); return }
        c.sendLive(jpeg, frame.width, frame.height)
    }

    // ---- called by the screen and the service; each hops onto the worker thread

    fun approved(context: Context, width: Int, height: Int) {
        val app = context.applicationContext
        worker.execute {
            val ok = try { controller(app).onApproved(width, height) } catch (e: Exception) { Log.w(TAG, "approve failed", e); false }
            // The request was withdrawn at the last moment: stop capturing right away.
            if (!ok) app.stopService(android.content.Intent(app, CaptureService::class.java))
        }
    }

    fun declined(context: Context) { val app = context.applicationContext; worker.execute { controller(app).onDeclined() } }
    fun stopByChild(context: Context) { val app = context.applicationContext; worker.execute { controller(app).stop(CaptureReasons.CHILD_STOPPED) } }
    fun stoppedBySystem(context: Context) { val app = context.applicationContext; worker.execute { controller(app).stop(CaptureReasons.SYSTEM_STOPPED) } }
    fun failed(context: Context) { val app = context.applicationContext; worker.execute { controller(app).stop(CaptureReasons.ERROR) } }
    fun serviceGone(context: Context) { val app = context.applicationContext; worker.execute { controller(app).stop(CaptureReasons.SYSTEM_STOPPED) } }
    fun resized(context: Context, width: Int, height: Int) { val app = context.applicationContext; worker.execute { controller(app).resized(width, height) } }

    @androidx.annotation.VisibleForTesting
    fun showForTest(next: CaptureState) { flow.value = next }
}
