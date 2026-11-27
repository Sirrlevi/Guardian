package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.WallpaperMeta
import com.sirrlevi.guardian.core.WallpaperTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** The child's phone and the database in memory. */
class FakeAppearanceBackend : AppearanceBackend {
    var metaNow: WallpaperMeta? = null
    var imageNow: ByteArray? = null
    var failWith: Exception? = null
    var failImage: Exception? = null
    var failDelete: Exception? = null
    var metaCalls = 0
    var imageCalls = 0
    var deleted = 0

    override fun meta(): WallpaperMeta? { failWith?.let { throw it }; metaCalls++; return metaNow }
    override fun image(meta: WallpaperMeta): ByteArray? { failImage?.let { throw it }; imageCalls++; return imageNow }
    override fun delete() { failDelete?.let { throw it }; deleted++; metaNow = null; imageNow = null }
}

/** Plain JVM test on purpose (see ScreenPreviewModelTest): the model only needs Compose's state runtime. */
class AppearanceModelTest {
    private var time = 1_800_000_000_000L
    private val backend = FakeAppearanceBackend()
    private val model = AppearanceModel({ backend }, { time }, io = EmptyCoroutineContext)

    private fun jpeg(size: Int) = ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
    private fun share(ts: Long = time - 60_000L, size: Int = 1000) {
        backend.metaNow = WallpaperMeta(ts, 1080, 2400, size, 1)
        backend.imageNow = jpeg(size)
    }

    @Test fun beforeTheFirstLookNothingIsShown() {
        assertEquals(AppearanceModel.Phase.LOADING, model.phase)
        assertNull(model.meta)
        assertNull(model.jpeg)
    }

    @Test fun whenNothingWasSharedThereIsNothingToShow() = runBlocking {
        model.tick()
        assertEquals(AppearanceModel.Phase.NOT_SHARED, model.phase)
        assertNull(model.jpeg)
        assertNull(model.meta)
        assertEquals("no picture is asked for when none was shared", 0, backend.imageCalls)
        assertNull(model.error)
        assertEquals(time, model.checkedAt)
    }

    @Test fun aSharedPictureIsShownWithTheTimeTheChildSharedIt() = runBlocking {
        share(ts = time - 3_600_000L)
        model.tick()
        assertEquals(AppearanceModel.Phase.SHOWN, model.phase)
        assertEquals(time - 3_600_000L, model.meta!!.ts)
        assertEquals(1000, model.jpeg!!.size)
    }

    @Test fun thePictureIsDownloadedOnceNotEveryPoll() = runBlocking {
        share()
        repeat(4) { model.tick() }
        assertEquals(4, backend.metaCalls)
        assertEquals(1, backend.imageCalls)
        assertEquals(AppearanceModel.Phase.SHOWN, model.phase)
    }

    @Test fun aNewerPictureReplacesTheOldOne() = runBlocking {
        share(ts = time - 7_200_000L, size = 1000)
        model.tick()
        share(ts = time - 60_000L, size = 2000)
        model.tick()
        assertEquals(2, backend.imageCalls)
        assertEquals(2000, model.jpeg!!.size)
        assertEquals(time - 60_000L, model.meta!!.ts)
    }

    @Test fun aPictureThatIsCutShortIsReportedDamagedAndNeverShown() = runBlocking {
        backend.metaNow = WallpaperMeta(time - 60_000L, 1080, 2400, 1000, 1)
        backend.imageNow = jpeg(999)
        model.tick()
        assertEquals(AppearanceModel.Phase.DAMAGED, model.phase)
        assertNull(model.jpeg)
        assertNotNull("the time is still known", model.meta)
    }

    @Test fun aMissingPictureIsReportedDamaged() = runBlocking {
        backend.metaNow = WallpaperMeta(time - 60_000L, 1080, 2400, 1000, 1)
        backend.imageNow = null
        model.tick()
        assertEquals(AppearanceModel.Phase.DAMAGED, model.phase)
        assertNull(model.jpeg)
    }

    @Test fun aDamagedPictureIsNotDownloadedAgainUntilTheParentAsks() = runBlocking {
        backend.metaNow = WallpaperMeta(time - 60_000L, 1080, 2400, 1000, 1)
        backend.imageNow = null
        repeat(3) { model.tick() }
        assertEquals(1, backend.imageCalls)
        backend.imageNow = jpeg(1000)
        model.refreshNow()
        assertEquals(2, backend.imageCalls)
        assertEquals(AppearanceModel.Phase.SHOWN, model.phase)
    }

    @Test fun whenTheChildRemovesItTheParentStopsSeeingIt() = runBlocking {
        share()
        model.tick()
        backend.metaNow = null
        model.tick()
        assertEquals(AppearanceModel.Phase.NOT_SHARED, model.phase)
        assertNull(model.jpeg)
        assertNull(model.meta)
    }

    @Test fun withoutAnyConnectionNothingIsInventedAndTheScreenSaysSo() = runBlocking {
        backend.failWith = IOException("Unable to resolve host")
        val wait = model.tick()
        assertEquals(AppearanceModel.Phase.FAILED, model.phase)
        assertEquals("No internet connection.", model.error)
        assertEquals(AppearanceModel.RETRY_MS, wait)
        assertNull(model.jpeg)
    }

    @Test fun aFailedPictureDownloadIsAFailureNotAnEmptyAnswer() = runBlocking {
        share()
        backend.failImage = IOException("Guardian backend error 503")
        model.tick()
        assertEquals(AppearanceModel.Phase.FAILED, model.phase)
        assertTrue(model.error!!.contains("503"))
        backend.failImage = null
        model.tick()
        assertEquals("it tries again by itself", AppearanceModel.Phase.SHOWN, model.phase)
        assertNull(model.error)
    }

    @Test fun whenTheConnectionDropsTheLastRealPictureStaysWithAWarning() = runBlocking {
        share()
        model.tick()
        backend.failWith = IOException("timeout")
        model.tick()
        assertEquals(AppearanceModel.Phase.SHOWN, model.phase)
        assertNotNull(model.jpeg)
        assertEquals("No internet connection.", model.error)
    }

    @Test fun deletingRemovesThePictureFromTheServerAndTheScreen() = runBlocking {
        share()
        model.tick()
        assertTrue(model.delete())
        assertEquals(1, backend.deleted)
        assertEquals(AppearanceModel.Phase.NOT_SHARED, model.phase)
        assertNull(model.jpeg)
        assertEquals(WallpaperTexts.REMOVED, model.notice)
        assertFalse(model.noticeIsProblem)
        assertFalse(model.busy)
    }

    @Test fun aFailedDeleteKeepsThePictureAndSaysItFailed() = runBlocking {
        share()
        model.tick()
        backend.failDelete = IOException("Guardian backend error 401")
        assertFalse(model.delete())
        assertEquals(AppearanceModel.Phase.SHOWN, model.phase)
        assertNotNull(model.jpeg)
        assertTrue(model.notice!!.startsWith(WallpaperTexts.REMOVE_FAILED))
        assertTrue(model.noticeIsProblem)
        assertFalse(model.busy)
    }

    @Test fun savingTellsTheTruthEitherWay() {
        model.saved(true)
        assertEquals(WallpaperTexts.SAVED, model.notice)
        assertFalse(model.noticeIsProblem)
        model.saved(false)
        assertEquals(WallpaperTexts.SAVE_FAILED, model.notice)
        assertTrue(model.noticeIsProblem)
    }

    @Test fun selectingAnotherPhoneForgetsTheOldPicture() = runBlocking {
        share()
        model.tick()
        model.reset()
        assertEquals(AppearanceModel.Phase.LOADING, model.phase)
        assertNull(model.jpeg)
        assertNull(model.meta)
        model.tick()
        assertEquals("the picture is fetched again for the next phone", 2, backend.imageCalls)
    }
}
