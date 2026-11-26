package com.sirrlevi.guardian.child

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.ControlOutcome
import com.sirrlevi.guardian.core.ControlResult
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.LocationPermission
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.LocationPointCodec
import com.sirrlevi.guardian.core.LocationRules
import com.sirrlevi.guardian.core.LocationSource
import com.sirrlevi.guardian.core.RecoveryPolicy
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoverySettingsCodec
import com.sirrlevi.guardian.core.RecoveryState
import com.sirrlevi.guardian.core.RecoveryStateCodec
import com.sirrlevi.guardian.core.RecoveryStore
import com.sirrlevi.guardian.core.RecoveryTexts
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The Realtime Database side of location sharing. */
internal class CloudRecoveryStore(private val cloud: ChildCloud) : RecoveryStore {
    private fun session(): GuardianSession = cloud.session() ?: throw IOException("This phone is not signed in.")
    private val family: String get() = cloud.familyId
    private fun obj(e: JsonElement?): JsonObject? = if (e != null && e.isJsonObject) e.asJsonObject else null

    override fun readSettings(): RecoverySettings? {
        val s = session()
        return RecoverySettingsCodec.fromJson(obj(cloud.api.get(GuardianPaths.recoverySettings(family, s.uid), s)))
    }

    override fun saveFix(point: LocationPoint) {
        val s = session()
        val json = LocationPointCodec.toJson(point)
        cloud.api.put(GuardianPaths.recoveryPoint(family, s.uid, LocationPointCodec.key(point.ts)), json, s)
        cloud.api.put(GuardianPaths.recoveryLast(family, s.uid), json, s)
    }

    override fun historyKeys(): List<String> {
        val s = session()
        return obj(cloud.api.get(GuardianPaths.recoveryHistory(family, s.uid), s, "shallow=true"))?.keySet()?.toList().orEmpty()
    }

    override fun deletePoints(keys: List<String>) {
        val s = session()
        keys.chunked(100).forEach { chunk ->
            val gone = JsonObject()
            chunk.forEach { gone.add(it, JsonNull.INSTANCE) }
            cloud.api.patch(GuardianPaths.recoveryHistory(family, s.uid), gone, s)
        }
    }

    override fun writeState(state: RecoveryState) {
        val s = session()
        cloud.api.put(GuardianPaths.recoveryState(family, s.uid), RecoveryStateCodec.toJson(state), s)
    }
}

/**
 * Reads a position with Android's own location service. It never guesses: it returns a position only if Android gave one just now
 * (or a very recent one it already had), and it reports what permission it really has.
 */
internal class AndroidLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext
    private val manager get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val direct = Executor { it.run() }

    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun permission(): LocationPermission = when {
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.NONE
        Build.VERSION.SDK_INT < 29 -> LocationPermission.ALWAYS
        granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> LocationPermission.ALWAYS
        else -> LocationPermission.WHILE_OPEN
    }

    override fun servicesOn(): Boolean = try { LocationManagerCompat.isLocationEnabled(manager) } catch (_: Exception) { false }

    @SuppressLint("MissingPermission")
    override fun read(timeoutMs: Long): LocationPoint? {
        val lm = manager
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val enabled = lm.getProviders(true)
        val order = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in enabled) order.add(LocationManager.FUSED_PROVIDER)
        if (fine && LocationManager.GPS_PROVIDER in enabled) order.add(LocationManager.GPS_PROVIDER)
        if (LocationManager.NETWORK_PROVIDER in enabled) order.add(LocationManager.NETWORK_PROVIDER)
        if (order.isEmpty()) return null

        // A position Android already has and that is only seconds old saves the battery. Anything older is not reused.
        val now = System.currentTimeMillis()
        order.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time in 0..RecoveryPolicy.REUSE_FIX_MS }
            .minByOrNull { it.accuracy }
            ?.let { toPoint(it)?.let { point -> return point } }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        for ((index, provider) in order.withIndex()) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left < 1_500L) break
            // The first choice gets most of the time, but the others still get a chance.
            val share = if (index == order.lastIndex) left else (left * 6 / 10).coerceAtLeast(1_500L)
            current(lm, provider, share)?.let { found -> toPoint(found)?.let { return it } }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun current(lm: LocationManager, provider: String, timeoutMs: Long): Location? {
        val result = AtomicReference<Location?>(null)
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        try {
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, direct, Consumer { location -> result.set(location); done.countDown() })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
        } catch (e: InterruptedException) {
            cancel.cancel()
            Thread.currentThread().interrupt()
        }
        return result.get()
    }

    private fun toPoint(l: Location): LocationPoint? {
        if (!LocationRules.isValid(l.latitude, l.longitude)) return null
        val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider
        // A position that Android says came from a "mock location" app is passed on as such, so the parent isn't told it is real.
        return LocationPoint(
            ts = if (l.time > 0L) l.time else System.currentTimeMillis(),
            lat = l.latitude, lon = l.longitude, accuracyM = if (l.hasAccuracy()) l.accuracy else 0f,
            provider = if (mock) "mock" else (l.provider ?: "")
        )
    }
}

/**
 * The "find my phone" sound. It always comes with a visible notice that says it was Guardian and offers a way to stop it, it uses the
 * alarm sound for at most a minute, and it puts the alarm volume back afterwards. It never rings without the notice.
 */
internal object Ringer {
    private const val TAG = "GuardianRing"
    const val ACTION_STOP = "com.sirrlevi.guardian.child.RING_STOP"
    private const val CHANNEL = "guardian_ring"
    private const val NOTIFICATION_ID = 4110

    private var player: MediaPlayer? = null
    private var savedVolume = -1
    @Volatile private var until = 0L

    fun ringUntil(): Long = until

    private fun notifications(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Find my phone", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Shown while Guardian makes this phone ring because your parent used Find my phone." }
        )
        return nm
    }

    /** True when the notice can really be shown: notifications are allowed for the app and this channel isn't switched off. */
    fun canShowNotice(context: Context): Boolean = try {
        val app = context.applicationContext
        val nm = notifications(app)
        NotificationManagerCompat.from(app).areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    } catch (_: Exception) { false }

    @Synchronized fun start(context: Context, now: Long): ControlOutcome {
        val app = context.applicationContext
        if (!canShowNotice(app)) return ControlOutcome(ControlResult.REJECTED, RecoveryTexts.RING_UNAVAILABLE)
        if (player != null && until > now) {
            until = now + RecoveryPolicy.RING_MS
            notice(app)
            return ControlOutcome(ControlResult.DONE, RecoveryTexts.RING_STARTED)
        }
        stopLocked(app)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            notice(app)
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            try { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (e: Exception) { Log.w(TAG, "volume not raised", e) }
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: throw IOException("no alarm sound on this phone")
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            if (!mp.isPlaying) { mp.release(); throw IOException("the sound did not start") }
            player = mp
            until = now + RecoveryPolicy.RING_MS
            ControlOutcome(ControlResult.DONE, RecoveryTexts.RING_STARTED)
        } catch (e: Exception) {
            Log.w(TAG, "ring failed", e)
            stopLocked(app)
            ControlOutcome(ControlResult.FAILED, RecoveryTexts.RING_FAILED)
        }
    }

    @Synchronized fun stop(context: Context) = stopLocked(context.applicationContext)

    /** Called by the runtime's timer. */
    @Synchronized fun stopIfOver(context: Context, now: Long) {
        if (until > 0L && now >= until) stopLocked(context.applicationContext)
    }

    /** A leftover notice from a process that died while ringing would otherwise stay forever. */
    @Synchronized fun clearStale(context: Context) {
        if (player == null && until == 0L) {
            try { (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
        }
    }

    private fun stopLocked(app: Context) {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        until = 0L
        if (savedVolume >= 0) {
            try { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) { }
            savedVolume = -1
        }
        try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
    }

    private fun notice(app: Context) {
        val stop = PendingIntent.getBroadcast(
            app, 7, Intent(app, RingStopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(app, CHANNEL)
            .setContentTitle("Guardian is making this phone ring")
            .setContentText("Your parent used Find my phone. Tap here to stop the sound.")
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_stat_guardian), "Stop", stop).build())
            .build()
        notifications(app).notify(NOTIFICATION_ID, notification)
    }
}

/** The "Stop" on the ring notice. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Ringer.ACTION_STOP) return
        Ringer.stop(context)
        ControlRuntime.statusChanged()
    }
}
