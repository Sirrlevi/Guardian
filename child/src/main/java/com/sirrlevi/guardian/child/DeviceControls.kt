package com.sirrlevi.guardian.child

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.ControlAck
import com.sirrlevi.guardian.core.ControlAckCodec
import com.sirrlevi.guardian.core.ControlCommand
import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlEventCodec
import com.sirrlevi.guardian.core.ControlHost
import com.sirrlevi.guardian.core.ControlInboxCodec
import com.sirrlevi.guardian.core.ControlOutcome
import com.sirrlevi.guardian.core.ControlPolicy
import com.sirrlevi.guardian.core.ControlProcessor
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.ControlStatusCodec
import com.sirrlevi.guardian.core.ControlStore
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.PendingReboot
import com.sirrlevi.guardian.core.ProcessedIds
import com.sirrlevi.guardian.core.RecoveryCoordinator
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The Realtime Database side of the device controls. */
internal class CloudControlStore(private val cloud: ChildCloud) : ControlStore {
    private var logCount = 0
    private var ackCount = 0

    private fun session(): GuardianSession = cloud.session() ?: throw IOException("This phone is not signed in.")
    private val family: String get() = cloud.familyId
    private fun obj(e: JsonElement?): JsonObject? = if (e != null && e.isJsonObject) e.asJsonObject else null

    override fun readInbox(): List<ControlCommand> {
        val s = session()
        return ControlInboxCodec.fromJson(obj(cloud.api.get(GuardianPaths.controlInbox(family, s.uid), s)))
    }

    override fun readAck(id: String): ControlAck? {
        val s = session()
        return ControlAckCodec.fromJson(id, obj(cloud.api.get(GuardianPaths.controlAck(family, s.uid, id), s)))
    }

    override fun writeAck(ack: ControlAck) {
        val s = session()
        cloud.api.put(GuardianPaths.controlAck(family, s.uid, ack.id), ControlAckCodec.toJson(ack), s)
        if (++ackCount % 20 == 0) pruneAcks(s)
    }

    override fun deleteCommand(id: String) {
        val s = session()
        cloud.api.delete(GuardianPaths.controlCommand(family, s.uid, id), s)
    }

    override fun writeStatus(status: ControlStatus) {
        val s = session()
        cloud.api.put(GuardianPaths.controlStatus(family, s.uid), ControlStatusCodec.toJson(status), s)
    }

    override fun appendLog(event: ControlEvent) {
        val s = session()
        val base = GuardianPaths.controlLog(family, s.uid)
        cloud.api.put(base + "/" + ControlEventCodec.key(event.ts, logCount++ % 100), ControlEventCodec.toJson(event), s)
        if (logCount % 10 == 0) {
            val keys = obj(cloud.api.get(base, s, "shallow=true"))?.keySet()?.toList().orEmpty()
            keys.sorted().dropLast(ControlPolicy.LOG_KEEP).forEach { cloud.api.delete("$base/$it", s) }
        }
    }

    private fun pruneAcks(s: GuardianSession) {
        val node = obj(cloud.api.get(GuardianPaths.controlAcks(family, s.uid), s)) ?: return
        val cutoff = System.currentTimeMillis() - ControlPolicy.ACK_KEEP_MS
        node.entrySet().forEach { (id, value) ->
            val ts = try { if (value.isJsonObject) value.asJsonObject.get("ts")?.asLong ?: 0L else 0L } catch (_: Exception) { 0L }
            if (ts < cutoff) cloud.api.delete(GuardianPaths.controlAck(family, s.uid, id), s)
        }
    }
}

/**
 * Remembers handled request ids across app restarts. It writes synchronously because a restart can follow right after:
 * an id that was not saved would be run again.
 */
internal class ControlProcessedIds(context: Context) : ProcessedIds {
    private val prefs = context.applicationContext.getSharedPreferences("guardian_control", Context.MODE_PRIVATE)
    override fun load(): List<String> = prefs.getString("handled", "").orEmpty().split(',').filter { it.isNotBlank() }
    override fun save(ids: List<String>) { prefs.edit().putString("handled", ids.joinToString(",")).commit() }
}

/** What this phone really can do and see. Lock and restart go through Android's own device-policy service. */
internal class AndroidControlHost(context: Context) : ControlHost {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("guardian_control", Context.MODE_PRIVATE)
    private val admin = ComponentName(app, GuardianAdminReceiver::class.java)
    private val policy get() = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val power get() = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard get() = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    override fun now() = System.currentTimeMillis()
    override fun sdk() = Build.VERSION.SDK_INT
    override fun adminActive() = ChildPermissions.isAdminActive(app)
    override fun deviceOwner() = ChildPermissions.isDeviceOwner(app)
    override fun screenOn(): Boolean = try { power.isInteractive } catch (_: Exception) { true }
    override fun isLocked(): Boolean = try { keyguard.isKeyguardLocked || !power.isInteractive } catch (_: Exception) { false }
    override fun lockNow() = policy.lockNow()
    override fun reboot() = policy.reboot(admin)

    /** Unknown counts as blocked: an erase is never attempted on a guess. */
    override fun factoryResetBlocked(): Boolean = try {
        (app.getSystemService(Context.USER_SERVICE) as UserManager).hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)
    } catch (_: Exception) { true }

    /** Factory reset without removing Android's factory-reset protection. Only reached for a Device Owner, after the parent's confirmation was checked. */
    override fun erase() = policy.wipeData(0)
    override fun bootAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Location sharing and "find now"; ticked by [ControlRuntime] and asked by the parent's locate request. */
    val recovery: RecoveryCoordinator by lazy {
        RecoveryCoordinator(CloudRecoveryStore(ChildCloud(app)), AndroidLocationSource(app), log = { message, error -> Log.w("GuardianRecovery", message, error) })
    }

    override fun canRing(): Boolean = Ringer.canShowNotice(app)
    override fun ring(): ControlOutcome = Ringer.start(app, now())
    override fun ringUntil(): Long = Ringer.ringUntil()
    override fun locate(): ControlOutcome = recovery.locate()

    override fun pendingReboot(): PendingReboot? {
        val id = prefs.getString("reboot_id", null) ?: return null
        return PendingReboot(id, prefs.getLong("reboot_at", 0L))
    }

    /** Written synchronously: the phone restarts right after this, and the note must survive it. */
    override fun setPendingReboot(pending: PendingReboot?) {
        val edit = prefs.edit()
        if (pending == null) edit.remove("reboot_id").remove("reboot_at")
        else edit.putString("reboot_id", pending.id).putLong("reboot_at", pending.requestedAt)
        edit.commit()
    }

    override fun sleep(ms: Long) { Thread.sleep(ms) }
}

/**
 * Glue between the phone and [ControlProcessor]: one background thread that, while the monitor runs, looks for the parent's lock and
 * restart requests, answers them, and keeps the parent's view of what this phone can do up to date.
 */
internal object ControlRuntime {
    private const val TAG = "GuardianControl"
    private const val STATUS_EVERY_MS = 60_000L
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "guardian-control-runtime").apply { isDaemon = true } }
    private var ticker: ScheduledFuture<*>? = null
    private var holders = 0
    @Volatile private var processor: ControlProcessor? = null
    @Volatile private var host: AndroidControlHost? = null
    private var lastPollAt = 0L
    @Volatile private var lastStatusAt = 0L

    /** The parent's location settings as this phone last read them, or null before the first read. */
    fun recoverySettings(): com.sirrlevi.guardian.core.RecoverySettings? = host?.recovery?.snapshot()

    /** Something the parent can see changed (for example the ring was stopped on the phone): report it at the next tick. */
    fun statusChanged() { lastStatusAt = 0L }

    private fun processor(app: Context): ControlProcessor {
        processor?.let { return it }
        synchronized(this) {
            processor?.let { return it }
            val h = AndroidControlHost(app)
            host = h
            val created = ControlProcessor(CloudControlStore(ChildCloud(app)), h, ControlProcessedIds(app), log = { message, error -> Log.w(TAG, message, error) })
            processor = created
            return created
        }
    }

    @Synchronized fun retain(context: Context) {
        holders++
        if (ticker == null) {
            val app = context.applicationContext
            lastPollAt = 0L; lastStatusAt = 0L
            Ringer.clearStale(app)
            ticker = worker.scheduleWithFixedDelay({ tick(app) }, 2_000L, 2_000L, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) { ticker?.cancel(false); ticker = null }
    }

    private fun tick(app: Context) {
        try {
            if (!ChildCloud(app).isPaired()) return
            val p = processor(app)
            val now = System.currentTimeMillis()
            val restarting = host?.pendingReboot() != null
            val every = if (restarting) ControlPolicy.PENDING_POLL_MS else ControlPolicy.IDLE_POLL_MS
            if (now - lastPollAt >= every) {
                lastPollAt = now
                try {
                    if (p.poll() > 0) lastStatusAt = 0L
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed", e)
                }
            }
            if (Ringer.ringUntil() in 1..now) { Ringer.stopIfOver(app, now); lastStatusAt = 0L }
            try { host?.recovery?.tick() } catch (e: Exception) { Log.w(TAG, "location tick failed", e) }
            if (now - lastStatusAt >= STATUS_EVERY_MS) {
                lastStatusAt = now
                try { p.reportStatus() } catch (e: Exception) { Log.w(TAG, "status not sent", e); lastStatusAt = now - STATUS_EVERY_MS + 10_000L }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tick failed", e)
        }
    }
}
