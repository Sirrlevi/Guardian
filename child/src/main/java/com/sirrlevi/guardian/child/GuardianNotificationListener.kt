package com.sirrlevi.guardian.child

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sirrlevi.guardian.core.CommDedupe
import java.util.concurrent.Executors

/**
 * Records the preview text of chat and social notifications and hands it to the parent.
 *
 * It only exists while the child (or the parent, with the child's phone in hand) has switched on
 * "Notification access" for Guardian in Android settings; Android shows that switch and can revoke it at any time.
 * It sees what a notification shows, never the full conversation, and nothing is recorded before pairing.
 * Removing a notification (swiping it away) does not delete anything that was already captured.
 */
class GuardianNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.i(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val app = applicationContext
        if (!isPaired(app)) return
        executor.execute {
            try {
                val records = NotificationExtractor.records(app, n).filter { dedupe.firstSeen(it.id) }
                if (records.isNotEmpty() && CommsQueue(app).add(records) > 0) CommsUploadWorker.enqueue(app)
            } catch (e: Exception) {
                Log.w(TAG, "could not record notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty: history already captured stays, and re-posted notifications are de-duplicated by id.
    }

    companion object {
        private const val TAG = "GuardianComms"
        private val executor = Executors.newSingleThreadExecutor()
        private val dedupe = CommDedupe(3000)

        @Volatile var connected: Boolean = false
            private set

        private fun isPaired(context: Context): Boolean =
            !context.getSharedPreferences("guardian_child", Context.MODE_PRIVATE).getString("family_id", "").isNullOrBlank()
    }
}
