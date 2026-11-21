package com.sirrlevi.guardian.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Runs right after Android finished an in-place update of this app. An update stops running
 * services and cancels pending background jobs, so this re-arms update checks, usage sync and,
 * if it was running before, the family-approved DNS filter (only when the VPN consent still exists).
 */
class GuardianPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        try {
            GuardianUpdater(app).cleanupDownloads()
            GuardianUpdateScheduler.schedule(app)

            val prefs = app.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
            if (prefs.getString("family_id", "").orEmpty().isNotBlank()) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    "guardian-child-sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ChildSyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()
                )
                WorkManager.getInstance(app).enqueue(
                    OneTimeWorkRequestBuilder<ChildSyncWorker>().setConstraints(constraints).build()
                )
            }
            GuardianMonitorService.startIfReady(app)
            if (prefs.getBoolean("vpn_wanted", false) && VpnService.prepare(app) == null) {
                ContextCompat.startForegroundService(app, Intent(app, DomainFilterVpnService::class.java))
            }
        } catch (_: Exception) {
            // Best effort: everything is re-armed again the next time the app is opened.
        }
    }
}
