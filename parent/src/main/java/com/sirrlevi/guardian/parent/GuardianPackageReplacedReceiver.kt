package com.sirrlevi.guardian.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs right after Android finished an in-place update of this app. The update cancels pending
 * background jobs, so this re-arms the periodic update check and tidies old downloads.
 */
class GuardianPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        try {
            GuardianUpdater(app).cleanupDownloads()
            GuardianUpdateScheduler.schedule(app)
        } catch (_: Exception) {
            // Best effort: everything is re-armed again the next time the app is opened.
        }
    }
}
