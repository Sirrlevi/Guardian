package com.sirrlevi.guardian.child

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class GuardianUpdateCheckWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val outcome: Result = try {
            val updater = GuardianUpdater(applicationContext)
            if (updater.hasToken()) {
                val update = updater.checkForUpdate()
                updater.notifyIfAvailable(update)
            }
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        return outcome
    }
}

object GuardianUpdateScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<GuardianUpdateCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "guardian-app-update-check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
