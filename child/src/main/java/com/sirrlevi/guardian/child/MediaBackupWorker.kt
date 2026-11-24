package com.sirrlevi.guardian.child

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.MediaAccess
import com.sirrlevi.guardian.core.MediaChunks
import com.sirrlevi.guardian.core.MediaCodec
import com.sirrlevi.guardian.core.MediaFailure
import com.sirrlevi.guardian.core.MediaIds
import com.sirrlevi.guardian.core.MediaItem
import com.sirrlevi.guardian.core.MediaPlanner
import com.sirrlevi.guardian.core.MediaSettingsCodec
import com.sirrlevi.guardian.core.MediaStatus
import com.sirrlevi.guardian.core.MediaStatusCodec
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Sends preview copies of new, allowed photos and videos to the parent. Runs only while backup is switched on. */
class MediaBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (MediaBackupRunner.run(applicationContext) == MediaBackupRunner.Outcome.MORE) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w("GuardianMedia", "media backup run failed", e)
            Result.retry()
        }
    }
}

object MediaBackupScheduler {
    private const val PERIODIC = "guardian_media_periodic"
    private const val NOW = "guardian_media_now"

    /** Checks for new media every half hour while online and not on a nearly empty battery. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val request = PeriodicWorkRequestBuilder<MediaBackupWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One extra run soon (after permission is granted, from "Back up now", or when the gallery changes). */
    fun runNow(context: Context, delaySec: Long = 0L) {
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<MediaBackupWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}

/** The actual backup pass. Kept separate from the worker so it can be called from anywhere and reasoned about alone. */
internal object MediaBackupRunner {
    enum class Outcome { DONE, MORE }

    private const val TAG = "GuardianMedia"
    private const val PUBLISH_EVERY = 3
    private const val MAX_EXPIRE_PER_RUN = 40

    fun run(context: Context): Outcome {
        val app = context.applicationContext
        val cloud = ChildCloud(app)
        if (!cloud.isPaired()) return Outcome.DONE
        val session = cloud.session() ?: return Outcome.MORE
        val api = cloud.api
        val familyId = cloud.familyId
        val uid = session.uid
        val state = MediaBackupState(app)

        val settings = MediaSettingsCodec.fromJson(api.get(GuardianPaths.mediaSettings(familyId, uid), session).asObjectOrNull())
        val access = ChildPermissions.mediaAccess(app)

        fun publish(waiting: String?, pending: Int, error: String? = null) {
            val snapshot = MediaStatusSnapshot(
                access, settings.enabled, pending, state.stored().size, state.failed().size, System.currentTimeMillis(), waiting, error
            )
            state.save(snapshot)
            try { api.put(GuardianPaths.mediaStatus(familyId, uid), MediaStatusCodec.toJson(snapshot), session) } catch (e: Exception) { Log.w(TAG, "status upload failed", e) }
        }

        if (!settings.enabled) { publish("disabled", 0); return Outcome.DONE }
        if (access == MediaAccess.NONE) { publish("permission", 0); return Outcome.DONE }
        if (settings.wifiOnly && isMetered(app)) { publish("wifi", state.status()?.pending ?: 0); return Outcome.DONE }

        val found = MediaScanner.scan(app, settings)
        val deleted = shallowKeys(api.get(GuardianPaths.mediaDeleted(familyId, uid), session, "shallow=true"))
        val attempts = state.attempts()
        val plan = MediaPlanner.plan(found, settings, state.stored(), deleted, attempts)
        var pending = MediaPlanner.pendingCount(found, settings, state.stored(), deleted, attempts)
        publish(null, pending)

        var sinceLastPublish = 0
        for (candidate in plan) {
            val key = MediaIds.key(candidate)
            val result = MediaPreviewer.make(app, candidate)
            if (result.failure != null || result.preview == null || result.thumb == null) {
                val reason = result.failure ?: MediaFailure.UNREADABLE
                if (state.bumpAttempt(key) >= MediaPlanner.MAX_ATTEMPTS) {
                    // Give up and say so: the parent sees the file name and the reason instead of silence.
                    val marker = item(candidate, key, MediaStatus.FAILED, reason, 0, 0, null, 0, 0)
                    try { api.put(GuardianPaths.mediaItem(familyId, uid, key), MediaCodec.toJson(marker), session) } catch (e: Exception) { Log.w(TAG, "failure marker upload failed", e) }
                    state.markFailed(key)
                    pending--
                }
                continue
            }
            try {
                val base64 = Base64.encodeToString(result.preview, Base64.NO_WRAP)
                val chunks = MediaChunks.split(base64)
                val blob = JsonObject().apply { chunks.forEach { (name, value) -> addProperty(name, value) } }
                // Pieces first, description last: an item that exists is always complete.
                api.put(GuardianPaths.mediaBlob(familyId, uid, key), blob, session)
                val thumb = Base64.encodeToString(result.thumb, Base64.NO_WRAP)
                val stored = item(candidate, key, MediaStatus.OK, null, result.preview.size, chunks.size, thumb, result.width, result.height)
                api.put(GuardianPaths.mediaItem(familyId, uid, key), MediaCodec.toJson(stored), session)
                state.markStored(key)
                pending--
            } catch (e: Exception) {
                Log.w(TAG, "upload failed, will retry", e)
                publish("offline", pending, "Upload interrupted; it will retry")
                return Outcome.MORE
            }
            if (++sinceLastPublish >= PUBLISH_EVERY) { sinceLastPublish = 0; publish(null, pending) }
        }

        expireOld(api, session, familyId, uid, settings, state)

        val remaining = MediaPlanner.pendingCount(found, settings, state.stored(), deleted, state.attempts())
        publish(null, remaining)
        return if (remaining > 0) Outcome.MORE else Outcome.DONE
    }

    private fun item(
        c: com.sirrlevi.guardian.core.MediaCandidate, key: String, status: MediaStatus, reason: String?,
        previewBytes: Int, chunks: Int, thumb: String?, width: Int, height: Int
    ) = MediaItem(
        key = key, kind = c.kind, name = c.name, mime = c.mime, sizeBytes = c.sizeBytes, createdMs = c.createdMs,
        width = if (width > 0) width else c.width, height = if (height > 0) height else c.height, durationMs = c.durationMs,
        uploadedMs = System.currentTimeMillis(), status = status, reason = reason, previewBytes = previewBytes, chunks = chunks, thumb = thumb
    )

    /** Removes preview copies older than the parent's retention choice. The original photos are untouched. */
    private fun expireOld(
        api: com.sirrlevi.guardian.core.GuardianApi, session: com.sirrlevi.guardian.core.GuardianSession,
        familyId: String, uid: String, settings: com.sirrlevi.guardian.core.MediaSettings, state: MediaBackupState
    ) {
        if (settings.retentionDays <= 0) return
        try {
            val keys = shallowKeys(api.get(GuardianPaths.mediaItems(familyId, uid), session, "shallow=true"))
            val old = MediaPlanner.expired(keys, settings, System.currentTimeMillis()).take(MAX_EXPIRE_PER_RUN)
            for (key in old) {
                api.delete(GuardianPaths.mediaItem(familyId, uid, key), session)
                api.delete(GuardianPaths.mediaBlob(familyId, uid, key), session)
            }
            state.forget(old)
        } catch (e: Exception) {
            Log.w(TAG, "retention clean-up failed, will retry next run", e)
        }
    }

    private fun isMetered(context: Context): Boolean = try {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    private fun shallowKeys(element: JsonElement?): Set<String> =
        if (element != null && element.isJsonObject) element.asJsonObject.keySet().toSet() else emptySet()

    private fun JsonElement?.asObjectOrNull(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null
}
