package com.sirrlevi.guardian.child

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import com.sirrlevi.guardian.core.MediaCandidate
import com.sirrlevi.guardian.core.MediaFailure
import com.sirrlevi.guardian.core.MediaGeometry
import com.sirrlevi.guardian.core.MediaKind
import java.io.ByteArrayOutputStream

/**
 * Makes the preview copy (a resized JPEG, or the poster frame of a video) and a tiny grid thumbnail.
 * The original file is only ever read, never moved or changed, and never uploaded.
 */
internal object MediaPreviewer {
    class Result(val preview: ByteArray?, val thumb: ByteArray?, val width: Int, val height: Int, val failure: String?)

    private const val SOFT_LIMIT = 700_000
    private const val HARD_LIMIT = 1_100_000

    fun make(context: Context, c: MediaCandidate): Result {
        val uri = ContentUris.withAppendedId(MediaScanner.uriFor(c.kind), c.mediaStoreId)
        val source = decode(context, uri, c.kind)
            ?: return fail(if (c.kind == MediaKind.IMAGE) MediaFailure.UNREADABLE else MediaFailure.NO_PREVIEW)
        var bitmap = source
        try {
            val (w, h) = MediaGeometry.fit(bitmap.width, bitmap.height, MediaGeometry.PREVIEW_EDGE)
            if (w != bitmap.width || h != bitmap.height) bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== source) source.recycle() }
            bitmap = flattenAlpha(bitmap)
            var jpeg = jpeg(bitmap, 80)
            if (jpeg.size > SOFT_LIMIT) jpeg = jpeg(bitmap, 62)
            if (jpeg.size > HARD_LIMIT) return fail(MediaFailure.TOO_LARGE)
            val (tw, th) = MediaGeometry.fit(bitmap.width, bitmap.height, MediaGeometry.THUMB_EDGE)
            val small = Bitmap.createScaledBitmap(bitmap, tw, th, true)
            val thumb = jpeg(small, 60)
            if (small !== bitmap) small.recycle()
            return Result(jpeg, thumb, bitmap.width, bitmap.height, null)
        } catch (_: Exception) {
            return fail(MediaFailure.UNREADABLE)
        } catch (_: OutOfMemoryError) {
            return fail(MediaFailure.TOO_LARGE)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: android.net.Uri, kind: MediaKind): Bitmap? = try {
        if (kind == MediaKind.IMAGE) decodeImage(context, uri) else decodeVideoFrame(context, uri)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun fail(reason: String) = Result(null, null, 0, 0, reason)

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Transparent pictures would turn black as JPEG; put them on white first. */
    private fun flattenAlpha(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
        return flat
    }

    private fun decodeImage(context: Context, uri: android.net.Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = MediaGeometry.fit(info.size.width, info.size.height, MediaGeometry.PREVIEW_EDGE)
                decoder.setTargetSize(w, h)
            }
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = MediaGeometry.sampleSize(bounds.outWidth, bounds.outHeight, MediaGeometry.PREVIEW_EDGE) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Exception) { null }
        val degrees = when (rotation) {
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

    private fun decodeVideoFrame(context: Context, uri: android.net.Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return context.contentResolver.loadThumbnail(uri, Size(MediaGeometry.PREVIEW_EDGE, MediaGeometry.PREVIEW_EDGE), null)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }
}
