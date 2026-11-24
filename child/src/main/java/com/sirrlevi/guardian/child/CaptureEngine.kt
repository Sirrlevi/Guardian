package com.sirrlevi.guardian.child

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.sirrlevi.guardian.core.BlankFrame
import com.sirrlevi.guardian.core.CaptureFailure
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.MediaGeometry
import com.sirrlevi.guardian.core.ShotResult
import java.io.ByteArrayOutputStream

/** One picture for the live preview: a JPEG, nothing to send yet, or a plain reason there is nothing to show. */
internal class LiveFrame(val jpeg: ByteArray?, val width: Int, val height: Int, val note: String?)

/**
 * Holds one approved MediaProjection session. It mirrors the screen into a small ImageReader and keeps only the newest
 * frame, so a still screen can be captured on request and nothing is converted until a picture is actually wanted.
 * Protected screens (windows marked secure) come out black; that is reported, never worked around.
 *
 * Needs a real phone to verify: Robolectric has no screen to mirror.
 */
internal class CaptureEngine(
    private val app: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
    private val onResized: (Int, Int) -> Unit
) {
    private val thread = HandlerThread("guardian-capture").also { it.start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    @Volatile private var released = false
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    private var dpi = 0
    private var lastSizeCheck = 0L

    private val callback = object : MediaProjection.Callback() {
        // Android (or the user, from the system's own control) ended the capture. It is never restarted without a new approval.
        override fun onStop() { if (!released) onStopped() }
    }

    /** Registers for stop events and starts mirroring. False when Android refused. */
    fun start(): Boolean = try {
        // Android 14+ requires the callback to exist before the display is created.
        projection.registerCallback(callback, handler)
        val (w, h, d) = screen()
        build(w, h, d)
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not start capture", e)
        release()
        false
    }

    fun shot(): ShotResult {
        if (released) return ShotResult.fail(CaptureFailure.NO_SESSION)
        if (!interactive()) return ShotResult.fail(CaptureFailure.SCREEN_OFF)
        checkSize(force = true)
        val bitmap = newestBitmap(waitMs = 1_500L) ?: return ShotResult.fail(CaptureFailure.NO_FRAME)
        try {
            if (isBlank(bitmap)) return ShotResult.fail(CaptureFailure.BLANK)
            var jpeg = encode(bitmap, CapturePolicy.SHOT_QUALITY) ?: return ShotResult.fail(CaptureFailure.ENCODE)
            if (jpeg.size > MAX_SHOT_BYTES) jpeg = encode(bitmap, 55) ?: return ShotResult.fail(CaptureFailure.ENCODE)
            if (jpeg.size > MAX_SHOT_BYTES) return ShotResult.fail(CaptureFailure.TOO_LARGE)
            return ShotResult.ok(jpeg, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** A small, low-quality picture for the live preview. Nothing is encoded when the screen is off or black. */
    fun live(): LiveFrame {
        if (released) return LiveFrame(null, 0, 0, null)
        if (!interactive()) return LiveFrame(null, 0, 0, CaptureFailure.SCREEN_OFF)
        checkSize(force = false)
        val bitmap = newestBitmap(waitMs = 0L) ?: return LiveFrame(null, 0, 0, null)
        try {
            if (isBlank(bitmap)) return LiveFrame(null, 0, 0, CaptureFailure.BLANK)
            val (w, h) = MediaGeometry.fit(bitmap.width, bitmap.height, CapturePolicy.LIVE_EDGE)
            val small = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
            try {
                val jpeg = encode(small, CapturePolicy.LIVE_QUALITY) ?: return LiveFrame(null, 0, 0, CaptureFailure.ENCODE)
                return LiveFrame(jpeg, small.width, small.height, null)
            } finally {
                if (small !== bitmap) small.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Stops mirroring and gives everything back. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        try { projection.unregisterCallback(callback) } catch (_: Exception) { }
        try { display?.release() } catch (_: Exception) { }
        synchronized(lock) {
            try { held?.close() } catch (_: Exception) { }
            held = null
            try { reader?.close() } catch (_: Exception) { }
            reader = null
        }
        try { projection.stop() } catch (_: Exception) { }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ internals

    private fun build(screenW: Int, screenH: Int, screenDpi: Int) {
        val (w, h) = MediaGeometry.fit(screenW, screenH, CapturePolicy.SHOT_EDGE)
        val fresh = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        fresh.setOnImageAvailableListener({ drain(it) }, handler)
        val current = display
        if (current == null) {
            display = projection.createVirtualDisplay(
                "guardian-screen-share", w, h, screenDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, fresh.surface, null, handler
            )
        } else {
            // Android 14+ allows only one display per approval, so a rotation resizes the same one.
            current.surface = fresh.surface
            current.resize(w, h, screenDpi)
        }
        val old = synchronized(lock) {
            held?.close(); held = null
            val previous = reader
            reader = fresh
            previous
        }
        try { old?.close() } catch (_: Exception) { }
        width = w; height = h; dpi = screenDpi
    }

    /** Takes every new frame out of the reader at once, keeping only the newest, so the reader never stalls. */
    private fun drain(source: ImageReader) {
        val image: Image? = try { source.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) return
        synchronized(lock) {
            if (released || source !== reader) { image.close(); return }
            held?.close()
            held = image
        }
    }

    private fun newestBitmap(waitMs: Long): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (true) {
            val bitmap = synchronized(lock) { held?.let { toBitmap(it) } }
            if (bitmap != null) return bitmap
            if (released || SystemClock.elapsedRealtime() >= deadline) return null
            try { Thread.sleep(100L) } catch (_: InterruptedException) { return null }
        }
    }

    private fun toBitmap(image: Image): Bitmap? = try {
        val plane = image.planes[0]
        val padded = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        if (padded == image.width) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { full.recycle() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read frame", e)
        null
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val points = BlankFrame.points(bitmap.width, bitmap.height)
        return BlankFrame.isBlank(IntArray(points.size) { bitmap.getPixel(points[it].first, points[it].second) })
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
    }

    private fun interactive(): Boolean = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** Follows screen rotation by resizing the one display. */
    private fun checkSize(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSizeCheck < 1_000L) return
        lastSizeCheck = now
        try {
            val (w, h, d) = screen()
            val (fw, fh) = MediaGeometry.fit(w, h, CapturePolicy.SHOT_EDGE)
            if (fw != width || fh != height) {
                build(w, h, d)
                onResized(width, height)
            }
        } catch (e: Exception) {
            Log.w(TAG, "resize failed", e)
        }
    }

    private fun screen(): Triple<Int, Int, Int> {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), app.resources.configuration.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private companion object {
        const val TAG = "GuardianCapture"
        const val MAX_SHOT_BYTES = 800_000
    }
}
