package com.sirrlevi.guardian.child

import android.content.Context
import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.CaptureAck
import com.sirrlevi.guardian.core.CaptureAckCodec
import com.sirrlevi.guardian.core.CaptureEvent
import com.sirrlevi.guardian.core.CaptureEventCodec
import com.sirrlevi.guardian.core.CaptureHost
import com.sirrlevi.guardian.core.CaptureInbox
import com.sirrlevi.guardian.core.CaptureInboxCodec
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStateCodec
import com.sirrlevi.guardian.core.CaptureStore
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.LiveCodec
import com.sirrlevi.guardian.core.LiveMeta
import com.sirrlevi.guardian.core.MediaChunks
import com.sirrlevi.guardian.core.ProcessedIds
import com.sirrlevi.guardian.core.ShotCodec
import com.sirrlevi.guardian.core.ShotMeta
import com.sirrlevi.guardian.core.ShotResult
import java.io.IOException

/** The Realtime Database side of screen sharing. Pictures are stored as text pieces, the description last, so a picture the parent can see is always complete. */
internal class CloudCaptureStore(private val cloud: ChildCloud) : CaptureStore {
    private var logCount = 0
    private var ackCount = 0

    private fun session(): GuardianSession = cloud.session() ?: throw IOException("This phone is not signed in.")
    private val family: String get() = cloud.familyId
    private fun obj(e: JsonElement?): JsonObject? = if (e != null && e.isJsonObject) e.asJsonObject else null

    override fun readInbox(): CaptureInbox {
        val s = session()
        return CaptureInboxCodec.fromJson(obj(cloud.api.get(GuardianPaths.captureInbox(family, s.uid), s)))
    }

    override fun readState(): CaptureState? {
        val s = session()
        return CaptureStateCodec.fromJson(obj(cloud.api.get(GuardianPaths.captureState(family, s.uid), s)))
    }

    override fun writeState(state: CaptureState) {
        val s = session()
        cloud.api.put(GuardianPaths.captureState(family, s.uid), CaptureStateCodec.toJson(state), s)
    }

    override fun writeAck(ack: CaptureAck) {
        val s = session()
        cloud.api.put(GuardianPaths.captureAck(family, s.uid, ack.id), CaptureAckCodec.toJson(ack), s)
        if (++ackCount % 20 == 0) pruneAcks(s)
    }

    override fun deleteCommand(id: String) {
        val s = session()
        cloud.api.delete(GuardianPaths.captureCommand(family, s.uid, id), s)
    }

    override fun appendLog(event: CaptureEvent) {
        val s = session()
        cloud.api.put(GuardianPaths.captureLog(family, s.uid) + "/" + CaptureEventCodec.key(event.ts, logCount++ % 100), CaptureEventCodec.toJson(event), s)
        if (logCount % 10 == 0) {
            val keys = keysOf(cloud.api.get(GuardianPaths.captureLog(family, s.uid), s, "shallow=true"))
            keys.sorted().dropLast(CapturePolicy.LOG_KEEP).forEach { cloud.api.delete(GuardianPaths.captureLog(family, s.uid) + "/" + it, s) }
        }
    }

    override fun writeShot(id: String, ts: Long, width: Int, height: Int, jpeg: ByteArray) {
        val s = session()
        val chunks = MediaChunks.split(Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val blob = JsonObject().apply { chunks.forEach { (name, value) -> addProperty(name, value) } }
        cloud.api.put(GuardianPaths.captureShotBlob(family, s.uid, id), blob, s)
        cloud.api.put(GuardianPaths.captureShot(family, s.uid, id), ShotCodec.toJson(ShotMeta(id, ts, width, height, jpeg.size, chunks.size)), s)
    }

    override fun writeLive(seq: Long, ts: Long, width: Int, height: Int, jpeg: ByteArray, lastUploadMs: Long) {
        val s = session()
        val chunks = MediaChunks.split(Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val blob = JsonObject().apply { chunks.forEach { (name, value) -> addProperty(name, value) } }
        cloud.api.put(GuardianPaths.captureLiveBlob(family, s.uid), blob, s)
        cloud.api.put(GuardianPaths.captureLive(family, s.uid), LiveCodec.toJson(LiveMeta(seq, ts, width, height, jpeg.size, chunks.size, lastUploadMs)), s)
    }

    /** The last live picture shows the child's screen, so it is removed as soon as sharing ends. */
    override fun clearLive() {
        val s = session()
        cloud.api.delete(GuardianPaths.captureLive(family, s.uid), s)
        cloud.api.delete(GuardianPaths.captureLiveBlob(family, s.uid), s)
    }

    override fun pruneShots(keep: Int) {
        val s = session()
        val ids = keysOf(cloud.api.get(GuardianPaths.captureShots(family, s.uid), s, "shallow=true"))
        for (id in ShotCodec.toPrune(ids, keep)) {
            cloud.api.delete(GuardianPaths.captureShot(family, s.uid, id), s)
            cloud.api.delete(GuardianPaths.captureShotBlob(family, s.uid, id), s)
        }
    }

    private fun pruneAcks(s: GuardianSession) {
        val node = obj(cloud.api.get(GuardianPaths.captureAcks(family, s.uid), s)) ?: return
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        node.entrySet().forEach { (id, value) ->
            val ts = try { if (value.isJsonObject) value.asJsonObject.get("ts")?.asLong ?: 0L else 0L } catch (_: Exception) { 0L }
            if (ts < cutoff) cloud.api.delete(GuardianPaths.captureAck(family, s.uid, id), s)
        }
    }

    private fun keysOf(e: JsonElement?): List<String> = obj(e)?.keySet()?.toList() ?: emptyList()
}

/** Remembers which request ids were already handled, across app restarts. */
internal class PrefsProcessedIds(context: Context) : ProcessedIds {
    private val prefs = context.applicationContext.getSharedPreferences("guardian_capture", Context.MODE_PRIVATE)
    override fun load(): List<String> = prefs.getString("handled", "").orEmpty().split(',').filter { it.isNotBlank() }
    override fun save(ids: List<String>) { prefs.edit().putString("handled", ids.joinToString(",")).apply() }
}

/** What the phone itself does for the controller. */
internal class AndroidCaptureHost(context: Context) : CaptureHost {
    private val app = context.applicationContext

    override fun showPrompt(expiresAt: Long) = CaptureNotifications.showPrompt(app, expiresAt)
    override fun hidePrompt() = CaptureNotifications.hidePrompt(app)
    override fun stopSession() { app.stopService(android.content.Intent(app, CaptureService::class.java)) }
    override fun takeShot(): ShotResult = CaptureRuntime.engine?.shot() ?: ShotResult.fail(com.sirrlevi.guardian.core.CaptureFailure.NO_SESSION)
    override fun notificationsEnabled(): Boolean =
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
}
