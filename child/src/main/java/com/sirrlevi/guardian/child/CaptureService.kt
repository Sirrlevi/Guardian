package com.sirrlevi.guardian.child

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.sirrlevi.guardian.core.CaptureReasons
import com.sirrlevi.guardian.core.CaptureStatus

/**
 * Runs only while the child has approved sharing. It owns the MediaProjection, shows the permanent "your screen is being
 * shared" notification with a Stop button, and ends with the session. It never restarts by itself.
 */
class CaptureService : Service() {
    private var engine: CaptureEngine? = null
    private var retained = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CaptureNotifications.ACTION_STOP -> CaptureRuntime.stopByChild(this)
            ACTION_START -> begin(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        // Android only hands out the projection to a foreground service of the right type, so this comes first.
        try {
            ServiceCompat.startForeground(
                this, CaptureNotifications.ACTIVE_ID, CaptureNotifications.active(this, System.currentTimeMillis()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not go foreground", e)
            CaptureRuntime.failed(this)
            stopSelf()
            return
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val open = CaptureRuntime.stateNow()
        if (data == null || open.status != CaptureStatus.AWAITING || System.currentTimeMillis() > open.expiresAt) {
            // The request was withdrawn or ran out while the child was deciding: nothing is captured.
            stopSelf()
            return
        }
        val projection = try {
            (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.w(TAG, "projection refused", e)
            null
        }
        if (projection == null) {
            CaptureRuntime.failed(this)
            stopSelf()
            return
        }
        val created = CaptureEngine(
            applicationContext, projection,
            onStopped = { CaptureRuntime.stoppedBySystem(this) },
            onResized = { w, h -> CaptureRuntime.resized(this, w, h) }
        )
        if (!created.start()) {
            CaptureRuntime.failed(this)
            stopSelf()
            return
        }
        engine = created
        CaptureRuntime.engine = created
        CaptureRuntime.retain(this)
        retained = true
        CaptureRuntime.approved(this, created.width, created.height)
    }

    override fun onDestroy() {
        if (CaptureRuntime.engine === engine) CaptureRuntime.engine = null
        engine?.release()
        engine = null
        if (retained) CaptureRuntime.release()
        retained = false
        // If Android removed the service without anyone stopping the session, say so.
        CaptureRuntime.serviceGone(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardianCapture"
        private const val ACTION_START = "com.sirrlevi.guardian.child.CAPTURE_START"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        /** Called with the result of Android's screen-capture dialog, right after the child tapped "Start now". */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            )
        }
    }
}
