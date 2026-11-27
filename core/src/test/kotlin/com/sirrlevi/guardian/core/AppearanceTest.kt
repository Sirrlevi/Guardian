package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AppearanceTest {
    private val meta = WallpaperMeta(1_800_000_000_000L, 1080, 2400, 480_000, 4)
    private fun jpeg(size: Int) = ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }

    // ---------------------------------------------------------------- description of the shared picture

    @Test fun theDescriptionSurvivesTheDatabaseRoundTrip() {
        assertEquals(meta, WallpaperCodec.fromJson(WallpaperCodec.toJson(meta)))
    }

    @Test fun anIncompleteOrImplausibleDescriptionIsNeverShown() {
        assertNull(WallpaperCodec.fromJson(null))
        assertNull(WallpaperCodec.fromJson(JsonObject()))
        val missing = WallpaperCodec.toJson(meta).also { it.remove("n") }
        assertNull(WallpaperCodec.fromJson(missing))
        for (bad in listOf(
            meta.copy(ts = 0L), meta.copy(width = 0), meta.copy(height = -1), meta.copy(bytes = 0),
            meta.copy(chunks = 0), meta.copy(chunks = WallpaperPolicy.MAX_CHUNKS + 1)
        )) assertNull("$bad", WallpaperCodec.fromJson(WallpaperCodec.toJson(bad)))
        assertNotNull(WallpaperCodec.fromJson(WallpaperCodec.toJson(meta.copy(chunks = WallpaperPolicy.MAX_CHUNKS))))
    }

    @Test fun aGarbledFieldIsRejectedNotGuessed() {
        val o = WallpaperCodec.toJson(meta).also { it.addProperty("w", "wide") }
        assertNull(WallpaperCodec.fromJson(o))
    }

    // ---------------------------------------------------------------- how the child's picture is encoded

    @Test fun everyEncodingStepIsAllowedAndNeverBiggerThanTheStepBefore() {
        val steps = WallpaperPolicy.attempts()
        assertTrue(steps.isNotEmpty())
        assertEquals("the first try keeps a full phone-sized wallpaper", WallpaperPolicy.MAX_EDGE, steps.first().edge)
        assertTrue(steps.all { it.edge <= WallpaperPolicy.MAX_EDGE && it.quality in 1..100 })
        steps.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b.edge < a.edge || (b.edge == a.edge && b.quality < a.quality)) }
    }

    @Test fun aPictureThatNeverFitsIsNotSentHalfway() {
        assertTrue(WallpaperPolicy.fits(1))
        assertTrue(WallpaperPolicy.fits(WallpaperPolicy.MAX_BYTES))
        assertFalse(WallpaperPolicy.fits(WallpaperPolicy.MAX_BYTES + 1))
        assertFalse(WallpaperPolicy.fits(0))
    }

    @Test fun theLimitStaysWithinTheNumberOfPiecesTheDatabaseDescriptionAllows() {
        val pieces = MediaChunks.split("A".repeat(WallpaperPolicy.MAX_BYTES * 4 / 3 + 4)).size
        assertTrue("$pieces pieces", pieces <= WallpaperPolicy.MAX_CHUNKS)
    }

    // ---------------------------------------------------------------- what the parent is shown

    @Test fun aPictureIsOnlyCompleteWhenItIsAJpegOfTheSizeThePhoneReported() {
        assertTrue(WallpaperRules.isComplete(meta, jpeg(meta.bytes)))
        assertFalse("missing", WallpaperRules.isComplete(meta, null))
        assertFalse("empty", WallpaperRules.isComplete(meta, ByteArray(0)))
        assertFalse("cut short", WallpaperRules.isComplete(meta, jpeg(meta.bytes - 1)))
        assertFalse("too long", WallpaperRules.isComplete(meta, jpeg(meta.bytes + 1)))
        assertFalse("not a JPEG", WallpaperRules.isComplete(meta, ByteArray(meta.bytes)))
    }

    @Test fun theSavedFileIsNamedAfterWhenTheChildSharedIt() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("guardian-wallpaper-20270115-0800.jpg", WallpaperRules.fileName(1_800_000_000_000L, utc))
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        assertEquals("guardian-wallpaper-20270115-1330.jpg", WallpaperRules.fileName(1_800_000_000_000L, ist))
    }

    @Test fun sizesAreReadable() {
        assertEquals("1 KB", WallpaperRules.sizeText(10))
        assertEquals("480 KB", WallpaperRules.sizeText(480_000))
        assertEquals("1.2 MB", WallpaperRules.sizeText(1_200_000))
        assertEquals("1080×2400 · 480 KB", WallpaperRules.detail(meta))
    }

    @Test fun theTextsNeverPretendToBeALiveViewOrAnAutomaticRead() {
        val none = WallpaperTexts.notShared("Aarav's phone")
        assertTrue(none.contains("Android doesn't let apps read"))
        assertTrue(none.contains("Share my wallpaper"))
        assertTrue(WallpaperTexts.sharedNote("Aarav's phone").contains("not a live view"))
        assertTrue(WallpaperTexts.incomplete("Aarav's phone").contains("share it again"))
        assertTrue(WallpaperChildText.EXPLAIN.contains("Android doesn't let Guardian read your wallpaper"))
        assertTrue(WallpaperChildText.EXPLAIN.contains("remove it again"))
    }

    @Test fun theChildAlwaysSeesWhetherSomethingIsShared() {
        assertEquals("Nothing shared.", WallpaperChildText.status(null))
        assertTrue(WallpaperChildText.status("26 Nov, 2:20 PM").contains("26 Nov, 2:20 PM"))
    }

    @Test fun theWallpaperHasItsOwnPlaceInTheDatabase() {
        assertEquals("families/f/devices/d/appearance/wallpaper", GuardianPaths.wallpaper("f", "d"))
        assertTrue(GuardianPaths.wallpaperBlob("f", "d").endsWith("/appearance/wallpaperblob"))
        assertFalse("not mixed into screenshots", GuardianPaths.wallpaper("f", "d").contains("/capture"))
    }
}
