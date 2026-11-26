package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirrlevi.guardian.core.ControlOffer
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.LocationBlock
import com.sirrlevi.guardian.core.LocationPermission
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.LocationRules
import com.sirrlevi.guardian.core.RecoveryPolicy
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoveryState
import com.sirrlevi.guardian.core.RecoveryTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Everything the Find my phone screen needs from the database. Every call blocks and may throw; the model runs them off the main thread. */
interface RecoveryBackend {
    fun settings(): RecoverySettings?
    fun saveSettings(settings: RecoverySettings)
    fun state(): RecoveryState?
    fun last(): LocationPoint?
    fun history(limit: Int): List<LocationPoint>
    fun clearHistory()
}

/** The real backend: the selected phone of the signed-in parent. */
class RepoRecoveryBackend(
    private val repo: ParentRepository,
    private val sessionOf: () -> GuardianSession?,
    private val remember: (GuardianSession) -> Unit,
    private val deviceOf: () -> String?
) : RecoveryBackend {
    private fun <T> call(block: (GuardianSession, String) -> T): T {
        val s0 = sessionOf() ?: throw IOException("Not signed in.")
        val id = deviceOf() ?: throw IOException("No phone is connected.")
        val s = repo.fresh(s0)
        if (s !== s0) remember(s)
        return block(s, id)
    }

    override fun settings() = call { s, d -> repo.fetchRecoverySettings(s, d) }
    override fun saveSettings(settings: RecoverySettings) = call { s, d -> repo.saveRecoverySettings(s, d, settings) }
    override fun state() = call { s, d -> repo.fetchRecoveryState(s, d) }
    override fun last() = call { s, d -> repo.fetchRecoveryLast(s, d) }
    override fun history(limit: Int) = call { s, d -> repo.fetchRecoveryHistory(s, d, limit) }
    override fun clearHistory() = call { s, d -> repo.clearRecoveryHistory(s, d) }
}

/**
 * State and actions of the Find my phone screen: the location settings the parent chose, and the real positions the child's phone
 * has saved, with their age. Nothing here is estimated; with no position it says why there is none.
 */
class RecoveryModel(
    private val backend: () -> RecoveryBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Where blocking database calls run. Tests pass an empty context so everything stays on the test's own thread. */
    @Volatile var io: CoroutineContext = Dispatchers.IO
) {
    var settings by mutableStateOf<RecoverySettings?>(null); private set
    var state by mutableStateOf<RecoveryState?>(null); private set
    var last by mutableStateOf<LocationPoint?>(null); private set
    var history by mutableStateOf<List<LocationPoint>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    /** Set when the last refresh could not reach the server; the data on screen is then the last known, not current. */
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableLongStateOf(0L); private set
    var now by mutableLongStateOf(0L); private set
    var notice by mutableStateOf<String?>(null); private set
    var noticeIsProblem by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set

    @Volatile var foreground = true

    /** What the parent has chosen; "off" until they have chosen anything. */
    val effective: RecoverySettings get() = settings ?: RecoverySettings.OFF
    val sharing: Boolean get() = effective.enabled
    val stateIsOld: Boolean get() = state.let { it == null || (now.takeIf { n -> n > 0L } ?: clock()) - it.ts > RecoveryPolicy.STATE_STALE_MS }

    /** Why no position is being shared right now, or null when everything needed is in place. */
    val block: LocationBlock? get() = state.let { st ->
        LocationRules.blockFor(sharing, st?.permission ?: LocationPermission.ALWAYS, st?.servicesOn ?: true)
    }

    /** Whether "find now" can be sent, with the reason when it can't. */
    val locateOffer: ControlOffer.Option get() {
        val st = state
        return when {
            !loaded -> ControlOffer.Option(false, "Checking the phone…")
            !sharing -> ControlOffer.Option(false, RecoveryTexts.OFF)
            st == null -> ControlOffer.Option(false, RecoveryTexts.NO_REPORT)
            else -> LocationRules.blockFor(true, st.permission, st.servicesOn)?.let { ControlOffer.Option(false, LocationRules.text(it)) }
                ?: ControlOffer.Option(true, "")
        }
    }

    private val mutex = Mutex()
    private var historyAt = 0L
    private var historyDirty = true

    /** Polls until cancelled. Call it from a coroutine tied to the screen being visible. */
    suspend fun watch() {
        while (coroutineContext.isActive) {
            val wait = if (!foreground) 1_000L else withContext(io) { tick() }
            delay(wait)
        }
    }

    /** One refresh. Returns how long to wait before the next one. */
    suspend fun tick(): Long = mutex.withLock {
        val t = clock()
        now = t
        try {
            val b = backend()
            settings = b.settings()
            state = b.state()
            last = b.last()
            if (historyDirty || t - historyAt >= HISTORY_EVERY_MS) { history = b.history(HISTORY_SHOWN); historyAt = t; historyDirty = false }
            loaded = true; error = null; checkedAt = t
            REFRESH_MS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e)
            RETRY_MS
        }
    }

    /** A new answer is likely (for example "find now" just finished): refresh straight away, history included. */
    suspend fun refreshNow() {
        historyDirty = true
        withContext(io) { tick() }
    }

    // ------------------------------------------------------------ actions

    suspend fun setEnabled(on: Boolean): Boolean = save(effective.copy(enabled = on)) {
        if (on) "Location sharing is on. Your child sees this in Guardian Child and has to allow location on their phone before anything is shared. It can take a minute to start."
        else "Location sharing is off. The phone stops reading its position within a minute. Saved positions stay until they expire or you delete them."
    }

    suspend fun setInterval(minutes: Int): Boolean {
        if (minutes !in RecoveryPolicy.INTERVALS_MIN) return false
        return save(effective.copy(intervalMin = minutes)) { "The phone will read its position about every $minutes minutes." }
    }

    suspend fun setRetention(days: Int): Boolean {
        if (days !in RecoveryPolicy.RETENTION_DAYS) return false
        return save(effective.copy(retentionDays = days)) {
            "Positions are kept for " + (if (days == 1) "1 day" else "$days days") + ". The phone removes older ones itself."
        }
    }

    private suspend fun save(next: RecoverySettings, message: () -> String): Boolean = withContext(io) {
        mutex.withLock {
            val saved = next.copy(ts = clock())
            saving = true
            try {
                backend().saveSettings(saved)
                settings = saved
                say(message(), problem = false)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't save that: " + describe(e), problem = true)
                false
            } finally {
                saving = false
            }
        }
    }

    /** Deletes every saved position (also the newest). */
    suspend fun clearHistory(): Boolean = withContext(io) {
        mutex.withLock {
            saving = true
            try {
                backend().clearHistory()
                history = emptyList(); last = null; historyDirty = true
                say("All saved positions were deleted.", problem = false)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't delete them: " + describe(e), problem = true)
                false
            } finally {
                saving = false
            }
        }
    }

    private fun say(text: String?, problem: Boolean) { notice = text; noticeIsProblem = problem }

    /** Shows a message from the screen itself, e.g. when no map app is installed. */
    fun tell(text: String, problem: Boolean = false) = say(text, problem)

    /** Forget everything, e.g. when another phone is selected or the parent signs out. */
    fun reset() {
        settings = null; state = null; last = null; history = emptyList(); loaded = false; error = null
        checkedAt = 0L; now = 0L; notice = null; noticeIsProblem = false; saving = false
        historyAt = 0L; historyDirty = true
    }

    /** Lets UI tests show fixed data without a network. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(
        settings: RecoverySettings? = null, state: RecoveryState? = null, last: LocationPoint? = null, history: List<LocationPoint> = emptyList(),
        now: Long = clock(), error: String? = null, notice: String? = null, noticeIsProblem: Boolean = false
    ) {
        this.settings = settings; this.state = state; this.last = last; this.history = history; this.loaded = true
        this.now = now; this.checkedAt = now; this.error = error; this.notice = notice; this.noticeIsProblem = noticeIsProblem
    }

    private fun describe(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }

    companion object {
        const val REFRESH_MS = 10_000L
        const val RETRY_MS = 5_000L
        const val HISTORY_EVERY_MS = 30_000L
        const val HISTORY_SHOWN = 60
    }
}
