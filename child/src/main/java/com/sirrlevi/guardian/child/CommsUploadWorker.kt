package com.sirrlevi.guardian.child

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Uploads queued notification previews once the phone is online, retrying with back-off while it is not. */
class CommsUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (CommsUploader.flush(applicationContext)) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("GuardianComms", "upload failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "guardian_comms_upload"

        /** A short delay lets a burst of messages go out as one request. */
        fun enqueue(context: Context, delaySec: Long = 4) {
            val request = OneTimeWorkRequestBuilder<CommsUploadWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
