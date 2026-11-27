package com.sirrlevi.guardian.child

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.MediaChunks
import com.sirrlevi.guardian.core.MediaGeometry
import com.sirrlevi.guardian.core.WallpaperCodec
import com.sirrlevi.guardian.core.WallpaperMeta
import com.sirrlevi.guardian.core.WallpaperPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Sends the one picture the child picked as their wallpaper. Android does not let apps read the wallpaper, so this only ever runs
 * after the child chose a picture in the system picker; nothing here looks at the wallpaper itself.
 */
object WallpaperSharing {
    sealed class Outcome {
        data class Shared(val ts: Long) : Outcome()
        object CantOpen : Outcome()
        object TooBig : Outcome()
        object UploadFailed : Outcome()
        object NotSignedIn : Outcome()
    }

    private const val PREFS = "guardian_child"
    private const val KEY = "wallpaper_shared_at"

    /** When the child last shared a picture from this phone, or 0. Kept locally so the child always sees what they have shared. */
    fun sharedAt(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)

    private fun remember(context: Context, ts: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, ts).apply()
    }

    /** Blocking. Call off the main thread. */
    fun share(context: Context, uri: Uri, now: Long = System.currentTimeMillis()): Outcome {
        val app = context.applicationContext
        val cloud = ChildCloud(app)
        if (!cloud.isPaired()) return Outcome.NotSignedIn

        val decoded: Bitmap? = try { decode(app, uri) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        val bitmap = decoded ?: return Outcome.CantOpen
        try {
            val jpeg = encode(bitmap) ?: return Outcome.TooBig
            val signedIn = try { cloud.session() } catch (_: IOException) { null }
            val session = signedIn ?: return Outcome.UploadFailed
            val family = cloud.familyId
            val chunks = MediaChunks.split(Base64.encodeToString(jpeg.bytes, Base64.NO_WRAP))
            val blob = JsonObject().apply { chunks.forEach { (name, value) -> addProperty(name, value) } }
            val meta = WallpaperMeta(now, jpeg.width, jpeg.height, jpeg.bytes.size, chunks.size)
            try {
                // Old description first, the new one last: whatever the parent can see is always a complete picture.
                cloud.api.delete(GuardianPaths.wallpaper(family, session.uid), session)
                cloud.api.put(GuardianPaths.wallpaperBlob(family, session.uid), blob, session)
                cloud.api.put(GuardianPaths.wallpaper(family, session.uid), WallpaperCodec.toJson(meta), session)
            } catch (_: IOException) {
                remember(app, 0L)
                return Outcome.UploadFailed
            }
            remember(app, now)
            return Outcome.Shared(now)
        } finally {
            bitmap.recycle()
        }
    }

    /** Blocking. Takes the shared picture off Guardian's server. */
    fun remove(context: Context): Boolean {
        val app = context.applicationContext
        val cloud = ChildCloud(app)
        return try {
            val session = cloud.session() ?: return false
            val family = cloud.familyId
            cloud.api.delete(GuardianPaths.wallpaper(family, session.uid), session)
            cloud.api.delete(GuardianPaths.wallpaperBlob(family, session.uid), session)
            remember(app, 0L)
            true
        } catch (_: IOException) {
            false
        }
    }

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** The first way of encoding that fits the size limit, or null when even the smallest does not. */
    private fun encode(source: Bitmap): Encoded? {
        for (attempt in WallpaperPolicy.attempts()) {
            val (w, h) = MediaGeometry.fit(source.width, source.height, attempt.edge)
            val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
            try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, attempt.quality, out)) continue
                val bytes = out.toByteArray()
                if (WallpaperPolicy.fits(bytes.size)) return Encoded(bytes, w, h)
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }
        return null
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val raw: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = MediaGeometry.fit(info.size.width, info.size.height, WallpaperPolicy.MAX_EDGE)
                decoder.setTargetSize(w, h)
            }
        } else {
            decodeLegacy(context, uri)
        }
        return if (raw == null) null else flatten(raw)
    }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = MediaGeometry.sampleSize(bounds.outWidth, bounds.outHeight, WallpaperPolicy.MAX_EDGE) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Exception) { null }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** JPEG has no transparency, so a see-through picture is drawn on black, like a screen. */
    private fun flatten(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.BLACK); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
        return flat
    }
}
