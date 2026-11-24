package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun cand(
        id: Long = 1, name: String = "IMG_1.jpg", kind: MediaKind = MediaKind.IMAGE, size: Long = 2_000_000,
        created: Long = now - day, added: Long = now - day, w: Int = 4000, h: Int = 3000, dur: Long = 0
    ) = MediaCandidate(id, kind, name, if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4", size, created, added, w, h, dur)

    private val on = MediaSettings(enabled = true, enabledAt = now - 2 * day)

    // ---- settings

    @Test fun missingSettingsMeanBackupIsOff() {
        assertFalse(MediaSettingsCodec.fromJson(null).enabled)
        assertFalse(MediaSettingsCodec.fromJson(JsonObject()).enabled)
    }

    @Test fun settingsRoundTripAndRetentionIsClampedToTheOffered() {
        val s = MediaSettings(true, false, 90, images = true, videos = false, enabledAt = 5L, updatedAt = 6L)
        assertEquals(s, MediaSettingsCodec.fromJson(MediaSettingsCodec.toJson(s)))
        assertEquals(30, MediaSettingsCodec.fromJson(JsonObject().apply { addProperty("keep", 3) }).retentionDays)
        assertEquals(0, MediaSettingsCodec.fromJson(JsonObject().apply { addProperty("keep", 0) }).retentionDays)
    }

    @Test fun brokenSettingValuesFallBackToSafeDefaults() {
        val o = JsonObject().apply { addProperty("on", "maybe"); addProperty("wifi", "x"); addProperty("keep", "many") }
        val s = MediaSettingsCodec.fromJson(o)
        assertFalse(s.enabled)
        assertTrue(s.wifiOnly)
        assertEquals(30, s.retentionDays)
    }

    // ---- ids

    @Test fun fingerprintIsStableAndSeparatesDifferentFiles() {
        assertEquals(MediaIds.fingerprint(cand()), MediaIds.fingerprint(cand(id = 99)))
        assertNotEquals(MediaIds.fingerprint(cand()), MediaIds.fingerprint(cand(name = "IMG_2.jpg")))
        assertNotEquals(MediaIds.fingerprint(cand()), MediaIds.fingerprint(cand(size = 5)))
        assertEquals(20, MediaIds.fingerprint(cand()).length)
    }

    @Test fun keysSortByCreationTimeAndRoundTrip() {
        val older = MediaIds.key(cand(created = 1_700_000_000_000L))
        val newer = MediaIds.key(cand(created = 1_700_000_500_000L))
        assertTrue(older < newer)
        assertTrue(MediaIds.isValidKey(older))
        assertEquals(1_700_000_000_000L, MediaIds.createdMsOf(older))
        assertFalse(MediaIds.isValidKey("../etc"))
        assertFalse(MediaIds.isValidKey("123_abc"))
    }

    // ---- item codec

    @Test fun itemRoundTripsIncludingFailures() {
        val c = cand()
        val item = MediaItem(MediaIds.key(c), MediaKind.IMAGE, c.name, c.mime, c.sizeBytes, c.createdMs, 4000, 3000, 0, now, MediaStatus.OK, null, 210_000, 2, "dGh1bWI=")
        assertEquals(item, MediaCodec.fromJson(item.key, MediaCodec.toJson(item)))
        val failed = item.copy(status = MediaStatus.FAILED, reason = MediaFailure.UNREADABLE, previewBytes = 0, chunks = 0, thumb = null)
        assertEquals(failed, MediaCodec.fromJson(failed.key, MediaCodec.toJson(failed)))
    }

    @Test fun brokenOrForeignItemsAreSkippedNotCrashed() {
        assertNull(MediaCodec.fromJson("nope", JsonObject()))
        assertNull(MediaCodec.fromJson(MediaIds.key(cand()), JsonObject()))
        assertNull(MediaCodec.fromJson(MediaIds.key(cand()), JsonObject().apply { addProperty("k", "i"); addProperty("sz", "big") }))
    }

    // ---- chunks

    @Test fun chunksSplitAndJoinBackExactly() {
        val text = "x".repeat(MediaChunks.SIZE * 2 + 17)
        val pieces = MediaChunks.split(text)
        assertEquals(listOf("c000", "c001", "c002"), pieces.keys.toList())
        assertEquals(text, MediaChunks.join(pieces, 3))
    }

    @Test fun missingChunkMeansNoImageRatherThanACorruptOne() {
        val pieces = MediaChunks.split("y".repeat(MediaChunks.SIZE + 5))
        assertNull(MediaChunks.join(pieces - "c001", 2))
        assertNull(MediaChunks.join(emptyMap(), 0))
    }

    // ---- status

    @Test fun statusRoundTrips() {
        val s = MediaStatusSnapshot(MediaAccess.PARTIAL, true, 4, 10, 1, now, "wifi", "x")
        assertEquals(s, MediaStatusCodec.fromJson(MediaStatusCodec.toJson(s)))
        assertNull(MediaStatusCodec.fromJson(null))
        assertEquals(MediaAccess.NONE, MediaAccess.fromCode("garbage"))
    }

    // ---- planner

    @Test fun nothingIsPlannedWhileBackupIsOff() {
        assertTrue(MediaPlanner.plan(listOf(cand()), MediaSettings(), emptySet(), emptySet(), emptyMap()).isEmpty())
        assertTrue(MediaPlanner.plan(listOf(cand()), MediaSettings(enabled = true, enabledAt = 0L), emptySet(), emptySet(), emptyMap()).isEmpty())
    }

    @Test fun mediaOlderThanTheSwitchOnMomentIsNeverTouched() {
        val old = cand(id = 1, name = "old.jpg", added = now - 5 * day)
        val fresh = cand(id = 2, name = "new.jpg", added = now - day)
        assertEquals(listOf("new.jpg"), MediaPlanner.plan(listOf(old, fresh), on, emptySet(), emptySet(), emptyMap()).map { it.name })
    }

    @Test fun storedDeletedAndExhaustedItemsAreSkipped() {
        val a = cand(name = "a.jpg"); val b = cand(name = "b.jpg", created = now - 3_000); val c = cand(name = "c.jpg", created = now - 2_000); val d = cand(name = "d.jpg", created = now - 1_000)
        val plan = MediaPlanner.plan(
            listOf(a, b, c, d), on,
            stored = setOf(MediaIds.key(a)), deleted = setOf(MediaIds.key(b)), attempts = mapOf(MediaIds.key(c) to MediaPlanner.MAX_ATTEMPTS)
        )
        assertEquals(listOf("d.jpg"), plan.map { it.name })
    }

    @Test fun kindsAndPerRunLimitAreRespected() {
        val img = cand(name = "i.jpg")
        val vid = cand(name = "v.mp4", kind = MediaKind.VIDEO, dur = 5_000)
        assertEquals(listOf("i.jpg"), MediaPlanner.plan(listOf(img, vid), on.copy(videos = false), emptySet(), emptySet(), emptyMap()).map { it.name })
        assertEquals(listOf("v.mp4"), MediaPlanner.plan(listOf(img, vid), on.copy(images = false), emptySet(), emptySet(), emptyMap()).map { it.name })
        val many = (1..50).map { cand(id = it.toLong(), name = "p$it.jpg", added = now - it * 1000L, created = now - it * 1000L) }
        assertEquals(MediaPlanner.MAX_PER_RUN, MediaPlanner.plan(many, on, emptySet(), emptySet(), emptyMap()).size)
        assertEquals(50, MediaPlanner.pendingCount(many, on, emptySet(), emptySet(), emptyMap()))
    }

    @Test fun theSameFileFoundTwiceIsPlannedOnce() {
        val a = cand(id = 1); val copy = cand(id = 2)
        assertEquals(1, MediaPlanner.plan(listOf(a, copy), on, emptySet(), emptySet(), emptyMap()).size)
    }

    @Test fun newestAdditionsGoFirst() {
        val a = cand(name = "a.jpg", added = now - 3 * 60_000L, created = now - 3 * 60_000L)
        val b = cand(name = "b.jpg", added = now - 60_000L, created = now - 60_000L)
        assertEquals(listOf("b.jpg", "a.jpg"), MediaPlanner.plan(listOf(a, b), on, emptySet(), emptySet(), emptyMap()).map { it.name })
    }

    // ---- retention, grouping, sizes

    @Test fun retentionExpiresOldKeysOnlyAndForeverKeepsEverything() {
        val old = MediaIds.key(cand(created = now - 40 * day, name = "o.jpg"))
        val recent = MediaIds.key(cand(created = now - 2 * day, name = "r.jpg"))
        assertEquals(listOf(old), MediaPlanner.expired(listOf(old, recent), MediaSettings(retentionDays = 30), now))
        assertTrue(MediaPlanner.expired(listOf(old, recent), MediaSettings(retentionDays = 0), now).isEmpty())
    }

    @Test fun itemsGroupByDayNewestFirstAndStorageAddsUp() {
        fun item(created: Long, bytes: Int, name: String) = MediaCandidate(1, MediaKind.IMAGE, name, "image/jpeg", 1, created, created, 1, 1, 0).let {
            MediaItem(MediaIds.key(it), MediaKind.IMAGE, name, "image/jpeg", 1, created, 1, 1, 0, now, MediaStatus.OK, null, bytes, 1, null)
        }
        val items = listOf(item(now - 3 * day, 100, "a"), item(now - day, 200, "b"), item(now - day + 1_000, 300, "c"))
        val groups = MediaPlanner.groupByDay(items)
        assertEquals(2, groups.size)
        assertTrue(groups[0].first > groups[1].first)
        assertEquals(listOf("c", "b"), groups[0].second.map { it.name })
        assertEquals(600L, MediaPlanner.storageBytes(items))
    }

    @Test fun humanBytesReadsNaturally() {
        assertEquals("512 B", MediaPlanner.humanBytes(512))
        assertEquals("2 KB", MediaPlanner.humanBytes(2048))
        assertEquals("1.5 MB", MediaPlanner.humanBytes(1_572_864))
    }

    @Test fun mediaAlreadyPastRetentionIsNotBackedUpJustToBeDeleted() {
        val settings = on.copy(retentionDays = 7, enabledAt = now - 60 * day)
        val ancient = cand(name = "a.jpg", created = now - 30 * day, added = now - day)
        val recent = cand(name = "r.jpg", created = now - 2 * day, added = now - day)
        val plan = MediaPlanner.plan(listOf(ancient, recent), settings, emptySet(), emptySet(), emptyMap(), now = now)
        assertEquals(listOf("r.jpg"), plan.map { it.name })
        val forever = MediaPlanner.plan(listOf(ancient, recent), settings.copy(retentionDays = 0), emptySet(), emptySet(), emptyMap(), now = now)
        assertEquals(2, forever.size)
    }

    // ---- geometry

    @Test fun fitScalesDownKeepingTheAspectRatioAndNeverUp() {
        assertEquals(1280 to 960, MediaGeometry.fit(4000, 3000, 1280))
        assertEquals(960 to 1280, MediaGeometry.fit(3000, 4000, 1280))
        assertEquals(800 to 600, MediaGeometry.fit(800, 600, 1280))
        assertEquals(200 to 1, MediaGeometry.fit(4000, 1, 200))
        assertEquals(1280 to 1280, MediaGeometry.fit(0, 0, 1280))
    }

    @Test fun sampleSizeStaysAtLeastTheTargetEdge() {
        assertEquals(2, MediaGeometry.sampleSize(4000, 3000, 1280))
        assertEquals(1, MediaGeometry.sampleSize(2000, 1500, 1280))
        assertEquals(4, MediaGeometry.sampleSize(5120, 3840, 1280))
        assertEquals(1, MediaGeometry.sampleSize(100, 100, 1280))
    }
}
