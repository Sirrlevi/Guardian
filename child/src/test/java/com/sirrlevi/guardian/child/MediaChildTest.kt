package com.sirrlevi.guardian.child

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.MediaAccess
import com.sirrlevi.guardian.core.MediaKind
import com.sirrlevi.guardian.core.MediaSettings
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A stand-in for Android's media database, so the scanner's column handling is exercised for real. */
class FakeMediaProvider : ContentProvider() {
    override fun onCreate() = true

    private fun rows(uri: Uri, projection: Array<String>?): Cursor {
        val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID)
        val cursor = MatrixCursor(cols)
        if (uri.toString().contains("/images/")) {
            fun row(id: Long, name: String, size: Long, added: Long, taken: Long?, w: Int, h: Int) {
                cursor.addRow(cols.map { col ->
                    when (col) {
                        MediaStore.MediaColumns._ID -> id
                        MediaStore.MediaColumns.DISPLAY_NAME -> name
                        MediaStore.MediaColumns.MIME_TYPE -> "image/jpeg"
                        MediaStore.MediaColumns.SIZE -> size
                        MediaStore.MediaColumns.DATE_ADDED -> added
                        MediaStore.MediaColumns.WIDTH -> w
                        MediaStore.MediaColumns.HEIGHT -> h
                        MediaStore.Images.ImageColumns.DATE_TAKEN -> taken
                        else -> null
                    }
                })
            }
            row(11, "IMG_0001.jpg", 2_500_000, 1_700_000_500L, 1_700_000_000_000L, 4000, 3000)
            row(12, "IMG_0002.jpg", 900_000, 1_700_000_900L, null, 1600, 1200)
        } else if (uri.toString().contains("/video/")) {
            cursor.addRow(cols.map { col ->
                when (col) {
                    MediaStore.MediaColumns._ID -> 21L
                    MediaStore.MediaColumns.DISPLAY_NAME -> "VID_0001.mp4"
                    MediaStore.MediaColumns.MIME_TYPE -> "video/mp4"
                    MediaStore.MediaColumns.SIZE -> 8_000_000L
                    MediaStore.MediaColumns.DATE_ADDED -> 1_700_001_000L
                    MediaStore.MediaColumns.WIDTH -> 1920
                    MediaStore.MediaColumns.HEIGHT -> 1080
                    MediaStore.Video.VideoColumns.DATE_TAKEN -> 1_700_001_000_000L
                    MediaStore.Video.VideoColumns.DURATION -> 42_000L
                    else -> null
                }
            })
        }
        return cursor
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
        rows(uri, projection)

    override fun query(uri: Uri, projection: Array<String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor =
        rows(uri, projection)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaChildTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() { context.getSharedPreferences("guardian_media", Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun cleanAfter() = clean()

    // ---- permission levels

    @Test fun noPermissionMeansNoAccess() {
        assertEquals(MediaAccess.NONE, ChildPermissions.mediaAccess(context))
    }

    @Test fun selectedPhotosOnlyIsReportedAsPartial() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(MediaAccess.PARTIAL, ChildPermissions.mediaAccess(context))
    }

    @Test fun granularImageOrVideoPermissionMeansFullAccess() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(MediaAccess.FULL, ChildPermissions.mediaAccess(context))
    }

    @Test fun permissionRequestListMatchesTheAndroidVersion() {
        assertTrue(ChildPermissions.mediaPermissionsToRequest().isNotEmpty())
        val item = ChildPermissions.items(context).first { it.id == "media" }
        assertFalse(item.granted)
        assertFalse("optional, never required", item.required)
    }

    // ---- local state

    @Test fun storedFailedAndAttemptsAreRememberedAndForgotten() {
        val s = MediaBackupState(context)
        s.markStored("a"); s.markStored("b"); s.markFailed("c")
        assertEquals(setOf("a", "b"), s.stored())
        assertEquals(setOf("c"), s.failed())
        assertEquals(1, s.bumpAttempt("c"))
        assertEquals(2, s.bumpAttempt("c"))
        assertEquals(2, s.attempts()["c"])
        s.markStored("c")
        assertTrue("a success clears the failed mark", "c" !in s.failed())
        s.forget(listOf("a"))
        assertEquals(setOf("b", "c"), s.stored())
        assertEquals(setOf("b", "c"), MediaBackupState(context).stored())
    }

    @Test fun statusSurvivesRestartAndPermissionIsReadLive() {
        val s = MediaBackupState(context)
        assertFalse(s.lastKnownEnabled())
        assertNull(s.status())
        s.save(MediaStatusSnapshot(MediaAccess.FULL, true, 3, 10, 1, 123L, "wifi", null))
        assertTrue(s.lastKnownEnabled())
        val shown = s.localStatus(context)
        assertEquals(3, shown.pending)
        assertEquals("permission is not taken from the saved copy", MediaAccess.NONE, shown.access)
    }

    // ---- scanner

    @Test fun scannerReadsPhotosAndVideosFromTheMediaDatabase() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        val found = MediaScanner.scan(context, MediaSettings(enabled = true, enabledAt = 1_000L))
        val images = found.filter { it.kind == MediaKind.IMAGE }
        val videos = found.filter { it.kind == MediaKind.VIDEO }
        assertEquals(2, images.size)
        assertEquals(1, videos.size)
        val first = images.first { it.mediaStoreId == 11L }
        assertEquals("IMG_0001.jpg", first.name)
        assertEquals(1_700_000_000_000L, first.createdMs)
        assertEquals(1_700_000_500_000L, first.addedMs)
        assertEquals(4000, first.width)
        val second = images.first { it.mediaStoreId == 12L }
        assertEquals("no capture time: fall back to when it was added", 1_700_000_900_000L, second.createdMs)
        assertEquals(42_000L, videos.single().durationMs)
    }

    @Test fun scannerHonoursWhichKindsTheParentAllowed() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        val onlyImages = MediaScanner.scan(context, MediaSettings(enabled = true, enabledAt = 1_000L, videos = false))
        assertTrue(onlyImages.all { it.kind == MediaKind.IMAGE })
        val onlyVideos = MediaScanner.scan(context, MediaSettings(enabled = true, enabledAt = 1_000L, images = false))
        assertTrue(onlyVideos.all { it.kind == MediaKind.VIDEO })
    }
}
