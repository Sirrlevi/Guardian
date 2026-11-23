package com.sirrlevi.guardian.child

import android.content.Context
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.GuardianPaths

/** Sends queued notification previews to the family's database. Re-sending is harmless: records are keyed by id. */
internal object CommsUploader {
    private const val BATCH = 200
    const val RETENTION_DAYS = 30

    /** Uploads everything queued. Returns true when the queue is empty afterwards; throws on a network failure. */
    fun flush(context: Context): Boolean {
        val app = context.applicationContext
        val queue = CommsQueue(app)
        if (queue.size() == 0) return true
        val cloud = ChildCloud(app)
        if (!cloud.isPaired()) return false
        val session = cloud.session() ?: return false
        val familyId = cloud.familyId
        var uploaded = 0

        while (true) {
            val batch = queue.peek(BATCH)
            if (batch.isEmpty()) break
            for ((day, entries) in batch.groupBy { it.day }) {
                val body = JsonObject()
                for (e in entries) body.add(e.id, e.record)
                cloud.api.patch(GuardianPaths.commsDay(familyId, session.uid, day), body, session)
            }
            queue.remove(batch.map { it.id })
            uploaded += batch.size
        }

        val now = System.currentTimeMillis()
        val signal = JsonObject().apply { addProperty("commsAt", now) }
        cloud.api.patch(GuardianPaths.realtime(familyId, session.uid), signal, session)
        val prefs = app.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
        prefs.edit().putLong("comms_last_sync", now).putInt("comms_last_count", uploaded).apply()
        pruneOnce(cloud, session.uid, familyId, session, prefs)
        return queue.size() == 0
    }

    /** Removes preview days older than [RETENTION_DAYS], at most once per day. */
    private fun pruneOnce(
        cloud: ChildCloud, uid: String, familyId: String,
        session: com.sirrlevi.guardian.core.GuardianSession, prefs: android.content.SharedPreferences
    ) {
        val today = DayClock.of(System.currentTimeMillis()).dayKey
        if (prefs.getString("comms_pruned_day", "") == today) return
        try {
            val cutoff = DayClock.of(System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000).dayKey
            val days = cloud.api.get(GuardianPaths.comms(familyId, uid), session, "shallow=true")
            val obj = if (days != null && days.isJsonObject) days.asJsonObject else null
            if (obj != null) {
                val stale = JsonObject()
                for (k in obj.keySet()) if (k.length == 8 && k < cutoff) stale.add(k, com.google.gson.JsonNull.INSTANCE)
                if (stale.size() > 0) cloud.api.patch(GuardianPaths.comms(familyId, uid), stale, session)
            }
            prefs.edit().putString("comms_pruned_day", today).apply()
        } catch (_: Exception) { /* retried tomorrow; never blocks an upload */ }
    }
}
