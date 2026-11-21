package com.sirrlevi.guardian.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the monitor after the phone reboots so limits keep working without anyone opening the app. */
class GuardianBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        GuardianMonitorService.startIfReady(context.applicationContext)
    }
}
