package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.util.Locale

/*
 * Device controls (lock now, restart) for a supervised child's phone, everything that needs no Android types.
 *
 * Rules that shape the code:
 *  - A request is only ever shown as done after the child's phone has answered it. The phone answers only after it has looked at
 *    the real result (is the screen locked now?), and a refusal comes with the reason.
 *  - Only two actions exist: lock and restart. There is deliberately no "unlock" (Android does not let an app get around a PIN,
 *    pattern, password or fingerprint) and no "shut down" (Android has no supported way to do it); the screens say so.
 *  - Restart works only when Guardian Child is the phone's Device Owner. Without that Android would refuse, and so does Guardian.
 *  - Every request has an id, a short life and is handled at most once, so an old or repeated request can never run again.
 */

object ControlPolicy {
    /** How long a request stays valid. */
    const val TTL_MS = 2 * 60_000L
    /** Longest life any request may claim; anything claiming more is treated as forged or damaged. */
    const val MAX_TTL_MS = 5 * 60_000L
    /** Parent and child clocks may differ a little; a request is only called expired after this much extra. */
    const val CLOCK_SKEW_MS = 60_000L
    const val PROCESSED_KEEP = 80
    const val LOG_KEEP = 40
    const val IDLE_POLL_MS = 10_000L
    const val PENDING_POLL_MS = 3_000L
    /** How long the phone waits to see that it really locked. */
    const val LOCK_CHECK_MS = 3_000L
    const val LOCK_CHECK_STEP_MS = 250L
    /** The phone gives up waiting for its own restart after this long; the parent stops waiting for it to come back after the same time. */
    const val REBOOT_WAIT_MS = 15 * 60_000L
    /** Extra time the parent waits for an answer after a request has expired, before it calls the request unanswered. */
    const val ACK_GRACE_MS = 30_000L
    /** The phone's own report is called old after this long. */
    const val STATUS_STALE_MS = 3 * 60_000L
    const val ACK_KEEP_MS = 24 * 3_600_000L
    const val MIN_SDK_REBOOT = 24
}

enum class ControlType(val code: String) {
    LOCK("lock"), REBOOT("reboot");

    companion object {
        fun fromCode(code: String?): ControlType? = values().firstOrNull { it.code == code }
    }
}

enum class ControlResult(val code: String) {
    /** The phone checked and it is true. */
    DONE("done"),
    /** Restart only: the phone has accepted and is about to restart. The final answer comes after it is back. */
    RESTARTING("restarting"),
    FAILED("failed"),
    /** The phone refused on purpose (missing permission, not allowed, unknown request). */
    REJECTED("rejected"),
    EXPIRED("expired");

    companion object {
        fun fromCode(code: String?): ControlResult? = values().firstOrNull { it.code == code }
    }
}

/** Every sentence that explains an outcome or a limit, shared by both apps so they always say the same thing. */
object ControlTexts {
    const val LOCK_NEEDS_ADMIN =
        "Uninstall protection isn't switched on in Guardian Child on that phone, and Android only lets an app lock the screen once it is. Ask your child to turn it on in Guardian Child → Protection."
    const val REBOOT_NEEDS_OWNER =
        "Restarting a phone from another phone is only allowed for an app that is the phone's Device Owner. Guardian Child isn't set up that way on this phone, so Android would refuse it."
    const val UNSUPPORTED = "That kind of request isn't supported by the version of Guardian Child on this phone."
    const val EXPIRED = "The phone received this too late and ignored it. If this keeps happening, check that both phones show the right time."
    const val INVALID = "The phone ignored this request because it was not valid."
    const val LOCKED = "The phone locked."
    const val ALREADY_LOCKED = "The phone's screen was already off or locked."
    const val LOCK_FAILED = "Android accepted the request, but the phone did not lock."
    const val RESTARTING = "The phone is restarting now."
    const val RESTART_ACTIVE = "A restart is already in progress on that phone."
    const val BACK = "The phone restarted and is back online."
    const val NO_RESTART = "The phone did not restart."
    const val UNCONFIRMED = "The phone handled this request but could not report the result, so it can't be confirmed."

    const val UNLOCK_NOTE =
        "Not possible. Android doesn't let any app unlock a phone or get around its PIN, pattern, password or fingerprint. Your child unlocks the phone themselves."
    const val SHUTDOWN_NOTE =
        "Not possible. Android has no supported way for an app to switch a phone off, and Guardian doesn't pretend to. Restart is offered instead when Android allows it."
    const val AFTER_RESTART_NOTE =
        "After a restart Android keeps the phone locked until your child enters their PIN once, so the phone reports back only after that."
    const val NO_REPORT = "Waiting for the phone to report what it can do. This needs the latest Guardian Child on that phone."
}

/** What the phone can really do right now. */
object ControlCapabilities {
    fun canLock(adminActive: Boolean) = adminActive
    fun canReboot(deviceOwner: Boolean, sdk: Int) = deviceOwner && sdk >= ControlPolicy.MIN_SDK_REBOOT
}

data class ControlCommand(val id: String, val typeCode: String, val ts: Long, val expiresAt: Long) {
    val type: ControlType? get() = ControlType.fromCode(typeCode)

    companion object {
        fun create(id: String, type: ControlType, now: Long) = ControlCommand(id, type.code, now, now + ControlPolicy.TTL_MS)
    }
}

object ControlCommandCodec {
    fun toJson(c: ControlCommand): JsonObject = JsonObject().apply {
        addProperty("t", c.typeCode)
        addProperty("ts", c.ts)
        addProperty("exp", c.expiresAt)
    }

    /** An unknown type is kept (and refused later with a reason) instead of being silently dropped. */
    fun fromJson(id: String, o: JsonObject): ControlCommand? = try {
        if (!isValidId(id)) null
        else ControlCommand(id, o.get("t")?.asString.orEmpty(), o.get("ts")?.asLong ?: 0L, o.get("exp")?.asLong ?: 0L)
    } catch (_: Exception) { null }

    /** Ids become database keys and replay-protection keys, so they stay short and plain. */
    fun isValidId(id: String): Boolean = id.length in 8..40 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

object ControlInboxCodec {
    fun fromJson(o: JsonObject?): List<ControlCommand> {
        if (o == null) return emptyList()
        val out = ArrayList<ControlCommand>()
        o.entrySet().forEach { (id, value) ->
            if (value.isJsonObject) ControlCommandCodec.fromJson(id, value.asJsonObject)?.let { out.add(it) }
        }
        return out
    }
}

/** Sorting of what arrived into "do now", "too old", "not valid" and "already handled". */
object ControlCommands {
    enum class Validity { OK, EXPIRED, INVALID }

    fun validity(c: ControlCommand, now: Long): Validity = when {
        c.expiresAt <= c.ts || c.expiresAt - c.ts > ControlPolicy.MAX_TTL_MS || c.ts > now + ControlPolicy.CLOCK_SKEW_MS -> Validity.INVALID
        now > c.expiresAt + ControlPolicy.CLOCK_SKEW_MS -> Validity.EXPIRED
        else -> Validity.OK
    }

    class Sorted(val due: List<ControlCommand>, val expired: List<ControlCommand>, val invalid: List<ControlCommand>, val seen: List<ControlCommand>)

    fun sort(all: List<ControlCommand>, processed: Set<String>, now: Long): Sorted {
        val due = ArrayList<ControlCommand>()
        val expired = ArrayList<ControlCommand>()
        val invalid = ArrayList<ControlCommand>()
        val seen = ArrayList<ControlCommand>()
        for (c in all.sortedBy { it.ts }) {
            if (c.id in processed) { seen.add(c); continue }
            when (validity(c, now)) {
                Validity.OK -> due.add(c)
                Validity.EXPIRED -> expired.add(c)
                Validity.INVALID -> invalid.add(c)
            }
        }
        return Sorted(due, expired, invalid, seen)
    }

    /** Keeps the most recent ids so the "already handled" list cannot grow forever. */
    fun remember(processed: List<String>, id: String, keep: Int = ControlPolicy.PROCESSED_KEEP): List<String> =
        (processed.filter { it != id } + id).takeLast(keep)
}

data class ControlAck(val id: String, val typeCode: String, val result: ControlResult, val reason: String, val ts: Long, val bootAt: Long = 0L)

object ControlAckCodec {
    fun toJson(a: ControlAck): JsonObject = JsonObject().apply {
        addProperty("t", a.typeCode)
        addProperty("r", a.result.code)
        addProperty("why", a.reason)
        addProperty("ts", a.ts)
        if (a.bootAt > 0L) addProperty("boot", a.bootAt)
    }

    fun fromJson(id: String, o: JsonObject?): ControlAck? {
        if (o == null) return null
        return try {
            val result = ControlResult.fromCode(o.get("r")?.asString) ?: return null
            ControlAck(id, o.get("t")?.asString.orEmpty(), result, o.get("why")?.asString.orEmpty(), o.get("ts")?.asLong ?: 0L, o.get("boot")?.asLong ?: 0L)
        } catch (_: Exception) { null }
    }
}

/** What the phone reports about its own controls. Battery, Android version and the like already travel in the phone's regular device info. */
data class ControlStatus(
    val ts: Long,
    val adminActive: Boolean,
    val deviceOwner: Boolean,
    val canLock: Boolean,
    val canReboot: Boolean,
    /** True when the screen is off or the lock screen is showing. */
    val locked: Boolean,
    val screenOn: Boolean,
    /** When the phone last started, on its own clock (0 when unknown). */
    val bootAt: Long,
    val rebootPending: Boolean
)

object ControlStatusCodec {
    fun toJson(s: ControlStatus): JsonObject = JsonObject().apply {
        addProperty("ts", s.ts)
        addProperty("admin", s.adminActive)
        addProperty("owner", s.deviceOwner)
        addProperty("lock", s.canLock)
        addProperty("reboot", s.canReboot)
        addProperty("locked", s.locked)
        addProperty("screen", s.screenOn)
        addProperty("boot", s.bootAt)
        addProperty("pending", s.rebootPending)
    }

    fun fromJson(o: JsonObject?): ControlStatus? {
        if (o == null) return null
        return try {
            fun flag(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            ControlStatus(
                ts = o.get("ts")?.asLong ?: return null,
                adminActive = flag("admin"), deviceOwner = flag("owner"), canLock = flag("lock"), canReboot = flag("reboot"),
                locked = flag("locked"), screenOn = flag("screen"), bootAt = o.get("boot")?.asLong ?: 0L, rebootPending = flag("pending")
            )
        } catch (_: Exception) { null }
    }
}

/** One line of the phone's short history of requests it handled (the audit trail the parent can read). */
data class ControlEvent(val ts: Long, val typeCode: String, val result: ControlResult, val id: String)

object ControlEventCodec {
    fun key(ts: Long, n: Int): String = String.format(Locale.US, "%013d_%02d", ts, n)

    fun toJson(e: ControlEvent): JsonObject = JsonObject().apply {
        addProperty("ts", e.ts)
        addProperty("t", e.typeCode)
        addProperty("r", e.result.code)
        addProperty("id", e.id)
    }

    fun fromJson(o: JsonObject): ControlEvent? = try {
        val result = ControlResult.fromCode(o.get("r")?.asString)
        if (result == null) null else ControlEvent(o.get("ts")?.asLong ?: 0L, o.get("t")?.asString.orEmpty(), result, o.get("id")?.asString.orEmpty())
    } catch (_: Exception) { null }
}

/** What the parent may offer, from the phone's own report. */
object ControlOffer {
    class Option(val available: Boolean, val why: String)

    fun lock(status: ControlStatus?): Option = when {
        status == null -> Option(false, ControlTexts.NO_REPORT)
        !status.canLock -> Option(false, ControlTexts.LOCK_NEEDS_ADMIN)
        else -> Option(true, "")
    }

    fun reboot(status: ControlStatus?): Option = when {
        status == null -> Option(false, ControlTexts.NO_REPORT)
        !status.canReboot -> Option(false, ControlTexts.REBOOT_NEEDS_OWNER)
        status.rebootPending -> Option(false, ControlTexts.RESTART_ACTIVE)
        else -> Option(true, "")
    }

    fun isStale(status: ControlStatus?, now: Long): Boolean = status == null || now - status.ts > ControlPolicy.STATUS_STALE_MS
}

// ------------------------------------------------------------------------------------------------ the phone's side

data class PendingReboot(val id: String, val requestedAt: Long)

/** The Realtime Database side of the controls. Every call blocks and may throw. */
interface ControlStore {
    fun readInbox(): List<ControlCommand>
    fun readAck(id: String): ControlAck?
    fun writeAck(ack: ControlAck)
    fun deleteCommand(id: String)
    fun writeStatus(status: ControlStatus)
    fun appendLog(event: ControlEvent)
}

/** What the phone itself can do and see. Calls may throw (for example a SecurityException); the processor turns that into an honest answer. */
interface ControlHost {
    fun now(): Long
    fun sdk(): Int
    fun adminActive(): Boolean
    fun deviceOwner(): Boolean
    /** True when the screen is off or the lock screen is showing. */
    fun isLocked(): Boolean
    fun screenOn(): Boolean
    fun lockNow()
    fun reboot()
    /** When the phone last started, on its own clock. */
    fun bootAt(): Long
    fun pendingReboot(): PendingReboot?
    fun setPendingReboot(pending: PendingReboot?)
    fun sleep(ms: Long)
}

/**
 * Takes the parent's requests from the inbox and carries them out, one at a time.
 *  - A request is remembered as handled before it runs, so a crash can never run it twice.
 *  - A request that is too old, not valid, or of an unknown kind is refused with a reason, never ignored silently.
 *  - The answer is written only after looking at the real result.
 *  - Restart is accepted only for a Device Owner, and its first answer ("restarting") is written before the phone goes down.
 */
class ControlProcessor(
    private val store: ControlStore,
    private val host: ControlHost,
    private val processed: ProcessedIds,
    private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {
    private var handled: List<String> = processed.load()
    private val unsent = LinkedHashMap<String, ControlAck>()

    /** One pass over the inbox. Returns how many requests were answered. */
    fun poll(): Int {
        resendUnsent()
        afterBoot()
        val now = host.now()
        val sorted = ControlCommands.sort(store.readInbox(), handled.toSet(), now)
        var answered = 0
        for (c in sorted.seen) settleSeen(c)
        for (c in sorted.invalid) { conclude(c, ControlResult.REJECTED, ControlTexts.INVALID); answered++ }
        for (c in sorted.expired) { conclude(c, ControlResult.EXPIRED, ControlTexts.EXPIRED); answered++ }
        for (c in sorted.due) {
            when (c.type) {
                ControlType.LOCK -> { lock(c); answered++ }
                ControlType.REBOOT -> { if (reboot(c)) answered++ }
                null -> { conclude(c, ControlResult.REJECTED, ControlTexts.UNSUPPORTED); answered++ }
            }
        }
        return answered
    }

    /** Writes the phone's report of what it can do and its current lock state. */
    fun reportStatus() {
        val admin = host.adminActive()
        val owner = host.deviceOwner()
        store.writeStatus(
            ControlStatus(
                ts = host.now(), adminActive = admin, deviceOwner = owner,
                canLock = ControlCapabilities.canLock(admin), canReboot = ControlCapabilities.canReboot(owner, host.sdk()),
                locked = host.isLocked(), screenOn = host.screenOn(), bootAt = host.bootAt(), rebootPending = host.pendingReboot() != null
            )
        )
    }

    /** Finishes a restart the phone started: reports that it is back, or that it never restarted. */
    fun afterBoot() {
        val p = host.pendingReboot() ?: return
        val now = host.now()
        val boot = host.bootAt()
        when {
            boot >= p.requestedAt -> {
                host.setPendingReboot(null)
                record(ControlEvent(now, ControlType.REBOOT.code, ControlResult.DONE, p.id))
                send(ControlAck(p.id, ControlType.REBOOT.code, ControlResult.DONE, ControlTexts.BACK, now, boot), deleteCommand = false)
            }
            now - p.requestedAt > ControlPolicy.REBOOT_WAIT_MS -> {
                host.setPendingReboot(null)
                record(ControlEvent(now, ControlType.REBOOT.code, ControlResult.FAILED, p.id))
                send(ControlAck(p.id, ControlType.REBOOT.code, ControlResult.FAILED, ControlTexts.NO_RESTART, now), deleteCommand = false)
            }
        }
    }

    private fun lock(c: ControlCommand) {
        if (!host.adminActive()) { conclude(c, ControlResult.REJECTED, ControlTexts.LOCK_NEEDS_ADMIN); return }
        if (host.isLocked()) { conclude(c, ControlResult.DONE, ControlTexts.ALREADY_LOCKED); return }
        try {
            host.lockNow()
        } catch (e: SecurityException) {
            conclude(c, ControlResult.FAILED, "Android refused to lock the phone."); return
        } catch (e: Exception) {
            log("lock failed", e)
            conclude(c, ControlResult.FAILED, "The phone could not be locked."); return
        }
        var waited = 0L
        while (waited < ControlPolicy.LOCK_CHECK_MS) {
            if (host.isLocked()) { conclude(c, ControlResult.DONE, ControlTexts.LOCKED); return }
            host.sleep(ControlPolicy.LOCK_CHECK_STEP_MS)
            waited += ControlPolicy.LOCK_CHECK_STEP_MS
        }
        if (host.isLocked()) conclude(c, ControlResult.DONE, ControlTexts.LOCKED)
        else conclude(c, ControlResult.FAILED, ControlTexts.LOCK_FAILED)
    }

    /** Returns true when the request was answered (accepted or refused). */
    private fun reboot(c: ControlCommand): Boolean {
        if (!host.deviceOwner() || host.sdk() < ControlPolicy.MIN_SDK_REBOOT) {
            conclude(c, ControlResult.REJECTED, ControlTexts.REBOOT_NEEDS_OWNER)
            return true
        }
        val running = host.pendingReboot()
        if (running != null && host.now() - running.requestedAt < ControlPolicy.REBOOT_WAIT_MS) {
            conclude(c, ControlResult.REJECTED, ControlTexts.RESTART_ACTIVE)
            return true
        }
        val now = host.now()
        // The parent must hear "restarting" before the phone goes down. If it can't be written, nothing is restarted and the request is retried while it is still valid.
        try {
            store.writeAck(ControlAck(c.id, c.typeCode, ControlResult.RESTARTING, ControlTexts.RESTARTING, now))
        } catch (e: Exception) {
            log("restart ack not written, not restarting", e)
            return false
        }
        remember(c.id)
        host.setPendingReboot(PendingReboot(c.id, now))
        record(ControlEvent(now, c.typeCode, ControlResult.RESTARTING, c.id))
        try { store.deleteCommand(c.id) } catch (e: Exception) { log("command not removed", e) }
        try {
            host.reboot()
        } catch (e: Exception) {
            log("restart refused", e)
            host.setPendingReboot(null)
            send(ControlAck(c.id, c.typeCode, ControlResult.FAILED, "Android refused to restart the phone.", host.now()), deleteCommand = false)
        }
        return true
    }

    /** A request we already handled but which is still in the inbox: make sure the parent has an answer, then clear it. */
    private fun settleSeen(c: ControlCommand) {
        if (unsent.containsKey(c.id)) return
        try {
            if (store.readAck(c.id) == null) {
                send(ControlAck(c.id, c.typeCode, ControlResult.FAILED, ControlTexts.UNCONFIRMED, host.now()), deleteCommand = false)
            }
            store.deleteCommand(c.id)
        } catch (e: Exception) {
            log("could not settle ${c.id}", e)
        }
    }

    private fun conclude(c: ControlCommand, result: ControlResult, reason: String) {
        remember(c.id)
        record(ControlEvent(host.now(), c.typeCode, result, c.id))
        send(ControlAck(c.id, c.typeCode, result, reason, host.now()), deleteCommand = true)
    }

    private fun send(ack: ControlAck, deleteCommand: Boolean) {
        try {
            store.writeAck(ack)
            unsent.remove(ack.id)
            if (deleteCommand) try { store.deleteCommand(ack.id) } catch (e: Exception) { log("command not removed", e) }
        } catch (e: Exception) {
            // Offline right now: the answer is kept and sent as soon as the phone is back.
            unsent[ack.id] = ack
            log("answer not sent yet", e)
        }
    }

    private fun resendUnsent() {
        for (ack in unsent.values.toList()) {
            try {
                store.writeAck(ack)
                unsent.remove(ack.id)
                try { store.deleteCommand(ack.id) } catch (_: Exception) { }
            } catch (e: Exception) {
                log("answer still not sent", e)
                return
            }
        }
    }

    private fun record(event: ControlEvent) {
        try { store.appendLog(event) } catch (e: Exception) { log("history not written", e) }
    }

    private fun remember(id: String) {
        handled = ControlCommands.remember(handled, id)
        try { processed.save(handled) } catch (e: Exception) { log("handled ids not saved", e) }
    }
}
