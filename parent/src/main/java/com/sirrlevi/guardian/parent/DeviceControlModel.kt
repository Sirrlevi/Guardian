package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirrlevi.guardian.core.ControlAck
import com.sirrlevi.guardian.core.ControlCommand
import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlOffer
import com.sirrlevi.guardian.core.ControlPolicy
import com.sirrlevi.guardian.core.ControlResult
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.ControlTexts
import com.sirrlevi.guardian.core.ControlType
import com.sirrlevi.guardian.core.RecoveryTexts
import com.sirrlevi.guardian.core.GuardianSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Everything the Device controls screen needs from the database. Every call blocks and may throw; the model runs them off the main thread. */
interface ControlBackend {
    fun status(): ControlStatus?
    fun send(command: ControlCommand)
    /** Takes a request back before the phone has picked it up. */
    fun withdraw(id: String)
    fun ack(id: String): ControlAck?
    fun deleteAck(id: String)
    fun log(): List<ControlEvent>
}

/** The real backend: the selected phone of the signed-in parent. */
class RepoControlBackend(
    private val repo: ParentRepository,
    private val sessionOf: () -> GuardianSession?,
    private val remember: (GuardianSession) -> Unit,
    private val deviceOf: () -> String?
) : ControlBackend {
    private fun <T> call(block: (GuardianSession, String) -> T): T {
        val s0 = sessionOf() ?: throw IOException("Not signed in.")
        val id = deviceOf() ?: throw IOException("No phone is connected.")
        val s = repo.fresh(s0)
        if (s !== s0) remember(s)
        return block(s, id)
    }

    override fun status() = call { s, d -> repo.fetchControlStatus(s, d) }
    override fun send(command: ControlCommand) = call { s, d -> repo.sendControlCommand(s, d, command) }
    override fun withdraw(id: String) = call { s, d -> repo.withdrawControlCommand(s, d, id) }
    override fun ack(id: String) = call { s, d -> repo.fetchControlAck(s, d, id) }
    override fun deleteAck(id: String) = call { s, d -> repo.deleteControlAck(s, d, id) }
    override fun log() = call { s, d -> repo.fetchControlLog(s, d) }
}

/**
 * State and actions of the Device controls screen. A request is shown as "waiting" until the child's phone answers it, and as done
 * only when the phone says it checked the result. A request nobody answers is taken back and reported as unanswered, never as done.
 */
class DeviceControlModel(
    private val backend: () -> ControlBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { "ctl-" + UUID.randomUUID().toString().replace("-", "").take(28) },
    /** Whether "find now" may be sent, from what the phone said about its location settings (the Find my phone screen supplies it). */
    private val locateGate: () -> ControlOffer.Option = { ControlOffer.Option(true, "") },
    /** Where blocking database calls run. Tests pass an empty context so everything stays on the test's own thread. */
    @Volatile var io: CoroutineContext = Dispatchers.IO
) {
    var status by mutableStateOf<ControlStatus?>(null); private set
    var loaded by mutableStateOf(false); private set
    /** Set when the last refresh could not reach the server; the data on screen is then the last known, not current. */
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableLongStateOf(0L); private set
    var now by mutableLongStateOf(0L); private set
    var log by mutableStateOf<List<ControlEvent>>(emptyList()); private set
    var notice by mutableStateOf<String?>(null); private set
    var noticeIsProblem by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    /** The kind of request still waiting for the phone's answer, if any. */
    var waiting by mutableStateOf<ControlType?>(null); private set
    /** True once the phone said it is restarting and we are waiting for it to come back. */
    var restarting by mutableStateOf(false); private set

    @Volatile var foreground = true

    val lockOffer: ControlOffer.Option get() = ControlOffer.lock(status)
    val rebootOffer: ControlOffer.Option get() = ControlOffer.reboot(status)
    val eraseOffer: ControlOffer.Option get() = ControlOffer.erase(status)
    val ringOffer: ControlOffer.Option get() = ControlOffer.ring(status)
    val locateOffer: ControlOffer.Option get() = locateGate()
    /** True while the phone's own fresh report says it is ringing. */
    val ringing: Boolean get() = ControlOffer.isRinging(status, now.takeIf { it > 0L } ?: clock())
    val statusIsOld: Boolean get() = ControlOffer.isStale(status, now.takeIf { it > 0L } ?: clock())

    private class Pending(val id: String, val type: ControlType, val sentAt: Long, val expiresAt: Long, var restarting: Boolean = false)

    private var pending: Pending? = null
    private val mutex = Mutex()
    private var logAt = 0L
    private var logDirty = true

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
            status = b.status()
            loaded = true; error = null; checkedAt = t
            settle(b, t)
            if (logDirty || t - logAt >= LOG_EVERY_MS) { log = b.log(); logAt = t; logDirty = false }
            if (pending != null) ControlPolicy.PENDING_POLL_MS else ControlPolicy.IDLE_POLL_MS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e)
            ControlPolicy.PENDING_POLL_MS
        }
    }

    /** Collects the phone's answer to our request, and gives up on a request it never answered. */
    private fun settle(b: ControlBackend, t: Long) {
        val p = pending ?: return
        val ack = try { b.ack(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (ack?.result == ControlResult.RESTARTING) p.restarting = true
        val stillRestarting = p.restarting && (ack == null || ack.result == ControlResult.RESTARTING)
        when {
            stillRestarting && t > p.sentAt + ControlPolicy.REBOOT_WAIT_MS ->
                finish("The phone said it was restarting but hasn't reported back yet. " + ControlTexts.AFTER_RESTART_NOTE, problem = true)
            ack == null && !p.restarting && t > p.expiresAt + ControlPolicy.CLOCK_SKEW_MS + ControlPolicy.ACK_GRACE_MS -> {
                // Taken back so a phone that comes online later cannot act on an old request.
                try { b.withdraw(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                finish("The phone didn't answer, so nothing was done. It may be off, offline or Guardian Child may be stopped there. The request has been cancelled.", problem = true)
            }
            ack == null -> Unit
            ack.result == ControlResult.ERASING -> {
                // The phone cannot say it finished: it is resetting. This is the last thing it will ever tell us about this request.
                finish(ControlTexts.ERASING + " " + ControlTexts.ERASE_AFTER, problem = false)
                try { b.deleteAck(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            ack.result == ControlResult.RESTARTING -> {
                restarting = true
                say("The phone is restarting. " + ControlTexts.AFTER_RESTART_NOTE, problem = false)
            }
            ack.result == ControlResult.DONE -> {
                finish(
                    when (p.type) {
                        ControlType.LOCK -> "Locked. The phone checked and confirmed it."
                        ControlType.REBOOT -> ControlTexts.BACK
                        ControlType.RING -> ack.reason.ifBlank { RecoveryTexts.RING_STARTED }
                        ControlType.LOCATE -> ack.reason.ifBlank { RecoveryTexts.LOCATED }
                        ControlType.ERASE -> ack.reason.ifBlank { ControlTexts.ERASING }
                    },
                    problem = false
                )
                try { b.deleteAck(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            else -> {
                finish(ack.reason.ifBlank { "The phone couldn't do that." }, problem = true)
                try { b.deleteAck(p.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    private fun finish(text: String, problem: Boolean) {
        pending = null; waiting = null; restarting = false; logDirty = true
        say(text, problem)
    }

    private fun say(text: String?, problem: Boolean) { notice = text; noticeIsProblem = problem }

    // ------------------------------------------------------------ actions

    suspend fun lock(): Boolean = send(ControlType.LOCK)
    suspend fun reboot(): Boolean = send(ControlType.REBOOT)
    suspend fun ring(): Boolean = send(ControlType.RING)
    suspend fun locate(): Boolean = send(ControlType.LOCATE)

    /** Erase needs the word typed exactly; the screen also asks for this phone's screen lock before calling it. Nothing is sent otherwise. */
    suspend fun erase(typed: String): Boolean {
        if (typed != ControlTexts.ERASE_WORD) {
            say("Nothing was sent: the confirmation word wasn't typed exactly as ${ControlTexts.ERASE_WORD}.", problem = true)
            return false
        }
        return send(ControlType.ERASE)
    }

    private suspend fun send(type: ControlType): Boolean {
        val ok = withContext(io) {
            mutex.withLock {
                val t = clock()
                val offer = when (type) {
                    ControlType.LOCK -> ControlOffer.lock(status)
                    ControlType.REBOOT -> ControlOffer.reboot(status)
                    ControlType.RING -> ControlOffer.ring(status)
                    ControlType.LOCATE -> locateGate()
                    ControlType.ERASE -> ControlOffer.erase(status)
                }
                if (!offer.available) {
                    say(offer.why, problem = true)
                    return@withLock false
                }
                if (pending != null) {
                    say("A request is already waiting for the phone.", problem = false)
                    return@withLock false
                }
                val command = ControlCommand.create(newId(), type, t)
                sending = true
                try {
                    backend().send(command)
                    pending = Pending(command.id, type, t, command.expiresAt)
                    waiting = type
                    say(
                        when (type) {
                            ControlType.LOCK -> "Request sent. This only counts as done once the phone confirms it locked."
                            ControlType.REBOOT -> "Request sent. Waiting for the phone to accept it."
                            ControlType.RING -> "Request sent. This only counts as done once the phone confirms the sound started."
                            ControlType.LOCATE -> "Looking for the phone. This can take up to half a minute."
                            ControlType.ERASE -> "Erase request sent. The phone has one minute to accept it; if it doesn't answer, nothing is erased and the request is cancelled."
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
        if (ok) withContext(io) { tick() }
        return ok
    }

    /** Shows a message from the screen itself, e.g. when the parent backs out of the screen-lock check. */
    fun tell(text: String, problem: Boolean = false) = say(text, problem)

    /** Forget everything, e.g. when another phone is selected or the parent signs out. */
    fun reset() {
        status = null; loaded = false; error = null; checkedAt = 0L; now = 0L; log = emptyList()
        notice = null; noticeIsProblem = false; sending = false; waiting = null; restarting = false
        pending = null; logAt = 0L; logDirty = true
    }

    /** Lets UI tests show fixed data without a network. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(
        status: ControlStatus?, log: List<ControlEvent> = emptyList(), now: Long = clock(), error: String? = null,
        notice: String? = null, noticeIsProblem: Boolean = false, waiting: ControlType? = null, restarting: Boolean = false
    ) {
        this.status = status; this.loaded = true; this.log = log; this.now = now; this.checkedAt = now; this.error = error
        this.notice = notice; this.noticeIsProblem = noticeIsProblem; this.waiting = waiting; this.restarting = restarting
    }

    private fun describe(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }

    companion object {
        const val LOG_EVERY_MS = 30_000L
    }
}
