package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirrlevi.guardian.core.AlertKind
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.PermissionWatch
import com.sirrlevi.guardian.core.SafetyAlert
import com.sirrlevi.guardian.core.SafetyApp
import com.sirrlevi.guardian.core.SafetyDay
import com.sirrlevi.guardian.core.SafetyDays
import com.sirrlevi.guardian.core.SafetyEvaluator
import com.sirrlevi.guardian.core.SafetyInput
import com.sirrlevi.guardian.core.SafetyPolicy
import com.sirrlevi.guardian.core.SafetySession
import com.sirrlevi.guardian.core.SafetySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.TimeZone
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** What the Safety alerts screen needs. Every call blocks and may throw; the model runs them off the main thread. */
interface SafetyBackend {
    /** Everything the rules look at right now. Throws when it can't be read, so nothing is judged from half the data. */
    fun input(): SafetyInput
    fun settings(): SafetySettings
    fun saveSettings(settings: SafetySettings)
    fun dismissed(): Map<String, Long>
    fun dismiss(id: String, at: Long)
    fun restore(id: String)
    fun events(): List<SafetyAlert>
    fun saveEvent(alert: SafetyAlert)
    fun deleteEvent(id: String)
    fun perms(): Map<String, Boolean>?
    fun savePerms(perms: Map<String, Boolean>)
}

/** Turns what the Parent already knows about a phone into the plain input the rules read. Pure, so it can be tested without a network. */
object SafetyInputs {
    fun perms(info: DeviceInfo?): Map<String, Boolean> {
        if (info == null) return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        out["usage"] = info.permUsage; out["overlay"] = info.permOverlay; out["notifications"] = info.permNotifications; out["admin"] = info.permAdmin
        // An old child app doesn't report notification access at all; "unknown" must never turn into "off".
        if (info.notifAccessKnown) out["notifaccess"] = info.permNotifAccess
        return out
    }

    fun build(device: ChildDevice, comms: List<CommRecord>, sessions: Map<String, List<SessionRow>>, now: Long, zone: TimeZone = TimeZone.getDefault()): SafetyInput {
        val info = device.info
        return SafetyInput(
            deviceName = device.name, now = now, zone = zone, comms = comms,
            apps = device.apps.map { SafetyApp(it.pkg, it.name, it.system, it.installedAt) },
            sessions = sessions.mapValues { (_, rows) -> rows.map { SafetySession(it.pkg, it.name.ifBlank { it.pkg }, it.start, it.end) } },
            days = device.daily.sortedByDescending { it.dayKey }.take(SafetyPolicy.DAYS_LOOKBACK).map { d ->
                SafetyDay(d.dayKey, d.totalSec, d.apps.entries.sortedByDescending { it.value }.take(3).map { device.nameOf(it.key) to it.value })
            },
            lastSeenMs = device.presenceTs(),
            reportedAt = info?.ts ?: 0L,
            usageAccess = info?.permUsage,
            notificationAccess = info?.takeIf { it.notifAccessKnown }?.permNotifAccess,
            serviceRunning = info?.takeIf { it.monitorKnown }?.monitorRunning,
            perms = perms(info),
            deviceId = device.id
        )
    }
}

/** The real backend: the selected phone of the signed-in parent. */
class RepoSafetyBackend(
    private val repo: ParentRepository,
    private val sessionOf: () -> GuardianSession?,
    private val remember: (GuardianSession) -> Unit,
    private val deviceOf: () -> ChildDevice?,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SafetyBackend {
    private fun <T> call(block: (GuardianSession, ChildDevice) -> T): T {
        val s0 = sessionOf() ?: throw IOException("Not signed in.")
        val d = deviceOf() ?: throw IOException("No phone is connected.")
        val s = repo.fresh(s0)
        if (s !== s0) remember(s)
        return block(s, d)
    }

    override fun input(): SafetyInput = call { s, d ->
        val now = clock()
        val zone = TimeZone.getDefault()
        val day = 24L * 3_600_000L
        val commDays = (SafetyPolicy.COMMS_LOOKBACK_MS / day).toInt().let { n -> (0 until n).map { SafetyDays.key(now - it * day, zone) } }
        val fetched = repo.fetchComms(s, d.id, commDays)
        if (fetched.failedDays == commDays.size) throw fetched.lastError ?: IOException("network error")
        if (fetched.failedDays > 0) throw IOException("Some message days could not be read.")
        val sessionDays = (0..SafetyPolicy.DAYS_LOOKBACK).map { SafetyDays.key(now - it * day, zone) }
        val sessions = LinkedHashMap<String, List<SessionRow>>()
        for (key in sessionDays) sessions[key] = repo.fetchSessionsFor(s, d.id, key)
        SafetyInputs.build(d, fetched.records, sessions, now, zone)
    }

    override fun settings() = call { s, d -> repo.fetchSafetySettings(s, d.id) }
    override fun saveSettings(settings: SafetySettings) = call { s, d -> repo.saveSafetySettings(s, d.id, settings) }
    override fun dismissed() = call { s, d -> repo.fetchSafetyDismissed(s, d.id) }
    override fun dismiss(id: String, at: Long) = call { s, d -> repo.dismissSafetyAlert(s, d.id, id, at) }
    override fun restore(id: String) = call { s, d -> repo.restoreSafetyAlert(s, d.id, id) }
    override fun events() = call { s, d -> repo.fetchSafetyEvents(s, d.id) }
    override fun saveEvent(alert: SafetyAlert) = call { s, d -> repo.saveSafetyEvent(s, d.id, alert) }
    override fun deleteEvent(id: String) = call { s, d -> repo.deleteSafetyEvent(s, d.id, id) }
    override fun perms() = call { s, d -> repo.fetchSafetyPerms(s, d.id) }
    override fun savePerms(perms: Map<String, Boolean>) = call { s, d -> repo.saveSafetyPerms(s, d.id, perms) }
}

/**
 * State of the Safety alerts screen.
 *
 * Alerts come from plain rules over data Guardian already holds. They are labelled neutrally, say why they are shown and what the rule
 * can't know, and the parent can dismiss each one (and bring it back). Nothing here is sent to anyone or blocked. When the data can't be
 * read the screen says so and keeps showing the last result as "last checked", instead of showing "all clear".
 */
class SafetyModel(
    private val backend: () -> SafetyBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Where blocking database calls run. Tests pass an empty context so everything stays on the test's own thread. */
    @Volatile var io: CoroutineContext = Dispatchers.IO
) {
    enum class Phase { LOADING, READY, FAILED }

    var phase by mutableStateOf(Phase.LOADING); private set
    var settings by mutableStateOf(SafetySettings()); private set
    /** Alerts the parent has not dismissed, newest first. */
    var active by mutableStateOf<List<SafetyAlert>>(emptyList()); private set
    var dismissed by mutableStateOf<List<SafetyAlert>>(emptyList()); private set
    /** Set when the last check failed; what is on screen is then the last result, not current. */
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableLongStateOf(0L); private set
    var notice by mutableStateOf<String?>(null); private set
    var noticeIsProblem by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set

    @Volatile var foreground = true

    /** Called after each successful check with what was found, so other features (Telegram reports) can pass it on. Never blocks the check. */
    @Volatile var onChecked: ((Checked) -> Unit)? = null

    /** The result of a check: the alerts the parent has not dismissed, and the daily totals the check looked at. */
    data class Checked(val deviceId: String, val deviceName: String, val now: Long, val alerts: List<SafetyAlert>, val days: List<SafetyDay>)

    private val mutex = Mutex()
    private var lastInput: SafetyInput? = null
    private var lastEvents: List<SafetyAlert> = emptyList()
    private var lastDismissed: Map<String, Long> = emptyMap()
    @Volatile private var lastTryAt = 0L

    /** Polls until cancelled. Call it from a coroutine tied to the screen being visible. */
    suspend fun watch() {
        while (coroutineContext.isActive) {
            val wait = if (!foreground) 1_000L else withContext(io) { tick() }
            delay(wait)
        }
    }

    /** For the app's own refresh: a full check at most every [QUIET_MS], so the "More" badge stays roughly current without constant reads. */
    suspend fun checkIfDue() {
        if (!foreground || clock() - lastTryAt < QUIET_MS) return
        withContext(io) { tick() }
    }

    /** One check. Returns how long to wait before the next one. */
    suspend fun tick(): Long = mutex.withLock {
        lastTryAt = clock()
        try {
            val b = backend()
            val input = b.input()
            val cfg = b.settings()
            val dismissedMap = b.dismissed()
            var events = b.events()
            val before = b.perms()

            // Permission changes can only be seen by comparing with the last snapshot. Alerts are saved before the snapshot, so a failure
            // in between means the change is found again next time rather than lost.
            val changed = PermissionWatch.diff(before, input.perms)
            val fresh = PermissionWatch.alerts(changed, input, input.now).filter { e -> events.none { it.id == e.id } }
            fresh.forEach { b.saveEvent(it) }
            events = events + fresh
            val merged = (before ?: emptyMap()) + input.perms
            if (input.perms.isNotEmpty() && merged != before) b.savePerms(merged)

            // Housekeeping is best effort and never fails a check.
            events.filter { input.now - it.ts > EVENT_KEEP_MS }.forEach { try { b.deleteEvent(it.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
            dismissedMap.filter { input.now - it.value > DISMISS_KEEP_MS }.keys.forEach { try { b.restore(it) } catch (e: CancellationException) { throw e } catch (_: Exception) {} }

            lastInput = input; lastEvents = events; lastDismissed = dismissedMap
            settings = cfg
            recompute()
            phase = Phase.READY; error = null; checkedAt = clock()
            try {
                onChecked?.invoke(Checked(input.deviceId, input.deviceName, input.now, active, input.days))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A listener's problem is never the check's problem.
            }
            REFRESH_MS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e)
            if (phase == Phase.LOADING) phase = Phase.FAILED
            RETRY_MS
        }
    }

    /** The parent asked to check again. */
    suspend fun refreshNow() { withContext(io) { tick() } }

    private fun recompute() {
        val input = lastInput ?: return
        val derived = SafetyEvaluator.evaluate(input, settings)
        val kept = lastEvents.filter { settings.on(it.kind) && input.now - it.ts <= EVENT_KEEP_MS }
        val all = (derived + kept).distinctBy { it.id }.sortedByDescending { it.ts }.take(SafetyPolicy.MAX_ALERTS)
        active = all.filter { it.id !in lastDismissed }
        dismissed = all.filter { it.id in lastDismissed }
    }

    suspend fun dismiss(id: String): Boolean = change(DISMISS_FAILED) {
        backend().dismiss(id, clock())
        lastDismissed = lastDismissed + (id to clock())
        recompute()
        say("Dismissed. You can bring it back from the list below.", problem = false)
    }

    suspend fun restore(id: String): Boolean = change(RESTORE_FAILED) {
        backend().restore(id)
        lastDismissed = lastDismissed - id
        recompute()
        say("Brought back.", problem = false)
    }

    suspend fun setKind(kind: AlertKind, enabled: Boolean): Boolean = save { it.withKind(kind, enabled) }

    suspend fun setLimits(lateFromHour: Int, lateToHour: Int, usageHours: Int, disconnectedHours: Int): Boolean =
        save { it.copy(lateFromHour = lateFromHour, lateToHour = lateToHour, usageHours = usageHours, disconnectedHours = disconnectedHours) }

    private suspend fun save(edit: (SafetySettings) -> SafetySettings): Boolean = change(SETTINGS_FAILED) {
        val next = edit(settings).normalized().copy(ts = clock())
        backend().saveSettings(next)
        settings = next
        recompute()
        say("Saved.", problem = false)
    }

    private suspend fun change(failure: String, block: () -> Unit): Boolean = withContext(io) {
        mutex.withLock {
            busy = true
            try {
                block()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("$failure ${describe(e)}", problem = true)
                false
            } finally {
                busy = false
            }
        }
    }

    private fun say(text: String?, problem: Boolean) { notice = text; noticeIsProblem = problem }
    fun clearNotice() { notice = null; noticeIsProblem = false }

    /** Forget everything, e.g. when another phone is selected or the parent signs out. */
    fun reset() {
        phase = Phase.LOADING; settings = SafetySettings(); active = emptyList(); dismissed = emptyList(); error = null; checkedAt = 0L
        notice = null; noticeIsProblem = false; busy = false
        lastInput = null; lastEvents = emptyList(); lastDismissed = emptyMap(); lastTryAt = 0L
    }

    /** Lets UI tests show fixed data without a network. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(
        phase: Phase, active: List<SafetyAlert> = emptyList(), dismissed: List<SafetyAlert> = emptyList(), settings: SafetySettings = SafetySettings(),
        error: String? = null, notice: String? = null, noticeIsProblem: Boolean = false
    ) {
        this.phase = phase; this.active = active; this.dismissed = dismissed; this.settings = settings; this.error = error
        this.checkedAt = clock(); this.notice = notice; this.noticeIsProblem = noticeIsProblem
    }

    private fun describe(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }

    companion object {
        const val REFRESH_MS = 30_000L
        const val RETRY_MS = 15_000L
        const val QUIET_MS = 10L * 60_000L
        const val EVENT_KEEP_MS = 30L * 24L * 3_600_000L
        const val DISMISS_KEEP_MS = 60L * 24L * 3_600_000L
        const val DISMISS_FAILED = "Couldn't dismiss it."
        const val RESTORE_FAILED = "Couldn't bring it back."
        const val SETTINGS_FAILED = "Couldn't save the setting."
        const val LOAD_FAILED = "Couldn't check just now."
    }
}
