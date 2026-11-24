package com.sirrlevi.guardian.child

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.sirrlevi.guardian.core.MediaCandidate
import com.sirrlevi.guardian.core.MediaKind
import com.sirrlevi.guardian.core.MediaSettings

/**
 * Lists photos and videos in Android's shared media storage that Guardian is allowed to see. Android filters
 * the list itself: with "selected photos" access only those photos appear, and nothing app-private is ever listed.
 */
internal object MediaScanner {
    private const val MAX_ROWS = 400

    fun scan(context: Context, settings: MediaSettings): List<MediaCandidate> {
        val sinceSec = (settings.enabledAt / 1000L) - 1L
        val out = ArrayList<MediaCandidate>()
        if (settings.images) out.addAll(query(context, MediaKind.IMAGE, sinceSec))
        if (settings.videos) out.addAll(query(context, MediaKind.VIDEO, sinceSec))
        return out
    }

    fun uriFor(kind: MediaKind) =
        if (kind == MediaKind.IMAGE) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun query(context: Context, kind: MediaKind, sinceSec: Long): List<MediaCandidate> {
        val image = kind == MediaKind.IMAGE
        val takenCol = if (image) MediaStore.Images.ImageColumns.DATE_TAKEN else MediaStore.Video.VideoColumns.DATE_TAKEN
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(takenCol)
            if (!image) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(sinceSec.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS)
        }
        val out = ArrayList<MediaCandidate>()
        try {
            context.contentResolver.query(uriFor(kind), projection, args, null)?.use { c ->
                fun idx(name: String) = c.getColumnIndex(name)
                val iId = idx(MediaStore.MediaColumns._ID)
                val iName = idx(MediaStore.MediaColumns.DISPLAY_NAME)
                val iMime = idx(MediaStore.MediaColumns.MIME_TYPE)
                val iSize = idx(MediaStore.MediaColumns.SIZE)
                val iAdded = idx(MediaStore.MediaColumns.DATE_ADDED)
                val iW = idx(MediaStore.MediaColumns.WIDTH)
                val iH = idx(MediaStore.MediaColumns.HEIGHT)
                val iTaken = idx(takenCol)
                val iDur = if (image) -1 else idx(MediaStore.Video.VideoColumns.DURATION)
                while (c.moveToNext()) {
                    if (iId < 0) break
                    val added = if (iAdded >= 0) c.getLong(iAdded) * 1000L else 0L
                    val taken = if (iTaken >= 0 && !c.isNull(iTaken)) c.getLong(iTaken) else 0L
                    out.add(
                        MediaCandidate(
                            mediaStoreId = c.getLong(iId),
                            kind = kind,
                            name = if (iName >= 0) c.getString(iName).orEmpty() else "",
                            mime = if (iMime >= 0) c.getString(iMime).orEmpty() else if (image) "image/*" else "video/*",
                            sizeBytes = if (iSize >= 0) c.getLong(iSize) else 0L,
                            createdMs = if (taken > 0L) taken else added,
                            addedMs = added,
                            width = if (iW >= 0) c.getInt(iW) else 0,
                            height = if (iH >= 0) c.getInt(iH) else 0,
                            durationMs = if (iDur >= 0) c.getLong(iDur) else 0L
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // The permission for this kind was not granted (or was revoked mid-scan): nothing is visible, nothing is read.
        } catch (_: Exception) { }
        return out
    }
}
