package com.sirrlevi.guardian.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.text.DateFormat
import java.util.Date

/** The two notifications of screen sharing: the request waiting for the child's decision, and the permanent "sharing" notice. */
internal object CaptureNotifications {
    const val ACTION_PROMPT = "com.sirrlevi.guardian.child.CAPTURE_PROMPT"
    const val ACTION_STOP = "com.sirrlevi.guardian.child.CAPTURE_STOP"
    const val PROMPT_ID = 4108
    const val ACTIVE_ID = 4109
    private const val CHANNEL_PROMPT = "guardian_capture_prompt"
    private const val CHANNEL_ACTIVE = "guardian_capture_active"

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, "Screen sharing requests", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Tells you when a parent asks to see your screen. Nothing is shared unless you allow it." }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, "Screen is being shared", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Stays visible for as long as your screen is shared, with a button to stop." }
        )
        return nm
    }

    fun showPrompt(context: Context, expiresAt: Long) {
        val app = context.applicationContext
        val open = PendingIntent.getActivity(
            app, 1,
            Intent(app, ChildActivity::class.java).setAction(ACTION_PROMPT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val timeout = (expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val notification = Notification.Builder(app, CHANNEL_PROMPT)
            .setContentTitle("Your parent asked to see your screen")
            .setContentText("Tap to choose. Nothing is shared unless you allow it.")
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(timeout)
            .build()
        manager(app).notify(PROMPT_ID, notification)
    }

    fun hidePrompt(context: Context) {
        (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PROMPT_ID)
    }

    /** Shown for the whole time the screen is shared, and always offers a way to stop. (Android 14+ lets people swipe such notices away; the sharing itself keeps its own status-bar indicator.) */
    fun active(context: Context, since: Long): Notification {
        val app = context.applicationContext
        val open = PendingIntent.getActivity(app, 2, Intent(app, ChildActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            app, 3, Intent(app, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager(app)
        return Notification.Builder(app, CHANNEL_ACTIVE)
            .setContentTitle("Your screen is being shared")
            .setContentText("Your parent can see your screen since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(since)) + ". Tap Stop to end it.")
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_stat_guardian), "Stop sharing", stop).build())
            .build()
    }
}
