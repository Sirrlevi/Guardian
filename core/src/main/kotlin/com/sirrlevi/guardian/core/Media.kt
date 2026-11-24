package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.Locale

/*
 * Shared-media backup: the parts that need no Android types (so they are unit-tested).
 *
 * What is backed up is a resized preview copy (and, for videos, one poster frame), kept in the family's own
 * database. The original file never leaves the child's phone: the database has no room for it, and the app
 * says so wherever a backed-up item is shown.
 */

enum class MediaKind(val code: String) {
    IMAGE("i"), VIDEO("v");

    companion object {
        fun fromCode(code: String?): MediaKind? = values().firstOrNull { it.code == code }
    }
}

/** How much of the gallery Android lets Guardian see, as granted by the child on the phone. */
enum class MediaAccess(val code: String) {
    NONE("none"), PARTIAL("partial"), FULL("full");

    companion object {
        fun fromCode(code: String?): MediaAccess = values().firstOrNull { it.code == code } ?: NONE
    }
}

/** Parent-controlled backup settings. Backup is off until the parent switches it on. */
data class MediaSettings(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    /** Days a backed-up item is kept; 0 means keep until the parent deletes it. */
    val retentionDays: Int = 30,
    val images: Boolean = true,
    val videos: Boolean = true,
    /** Only media added to the phone after this moment is considered. Set when backup is switched on. */
    val enabledAt: Long = 0L,
    val updatedAt: Long = 0L
)

object MediaSettingsCodec {
    val RETENTION_CHOICES = listOf(7, 30, 90, 0)

    fun toJson(s: MediaSettings): JsonObject = JsonObject().apply {
        addProperty("on", s.enabled)
        addProperty("wifi", s.wifiOnly)
        addProperty("keep", normalizeRetention(s.retentionDays))
        addProperty("img", s.images)
        addProperty("vid", s.videos)
        addProperty("since", s.enabledAt)
        addProperty("ts", s.updatedAt)
    }

    /** Missing or broken settings mean "off"; nothing is ever backed up on a guess. */
    fun fromJson(o: JsonObject?): MediaSettings {
        if (o == null) return MediaSettings()
        return MediaSettings(
            enabled = bool(o, "on", false),
            wifiOnly = bool(o, "wifi", true),
            retentionDays = normalizeRetention(int(o, "keep", 30)),
            images = bool(o, "img", true),
            videos = bool(o, "vid", true),
            enabledAt = long(o, "since", 0L),
            updatedAt = long(o, "ts", 0L)
        )
    }

    fun normalizeRetention(days: Int): Int = if (days in RETENTION_CHOICES) days else 30

    /** Only a real JSON boolean counts; text like "maybe" is not silently read as false. */
    private fun bool(o: JsonObject, k: String, d: Boolean): Boolean {
        val p = o.get(k)
        return if (p != null && p.isJsonPrimitive && p.asJsonPrimitive.isBoolean) p.asBoolean else d
    }
    private fun int(o: JsonObject, k: String, d: Int) = try { o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: d } catch (_: Exception) { d }
    private fun long(o: JsonObject, k: String, d: Long) = try { o.get(k)?.takeIf { it.isJsonPrimitive }?.asLong ?: d } catch (_: Exception) { d }
}

/** One photo or video found in the phone's shared media storage. */
data class MediaCandidate(
    val mediaStoreId: Long,
    val kind: MediaKind,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val createdMs: Long,
    val addedMs: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long
)

object MediaIds {
    /** Same file seen twice (re-scan, copy in another folder) gives the same fingerprint. */
    fun fingerprint(c: MediaCandidate): String {
        val raw = listOf(c.kind.code, c.name, c.sizeBytes, c.createdMs / 1000L, c.width, c.height).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** `<creation seconds, 10 digits>_<fingerprint>`: sorts oldest to newest as plain text, which makes paging by key work. */
    fun key(c: MediaCandidate): String = String.format(Locale.US, "%010d_%s", (c.createdMs.coerceAtLeast(0L) / 1000L), fingerprint(c))

    fun createdMsOf(key: String): Long = key.substringBefore('_').toLongOrNull()?.times(1000L) ?: 0L

    fun isValidKey(key: String): Boolean = Regex("^\\d{10}_[0-9a-f]{20}$").matches(key)
}

enum class MediaStatus(val code: String) {
    OK("ok"), FAILED("fail");

    companion object {
        fun fromCode(code: String?): MediaStatus = values().firstOrNull { it.code == code } ?: OK
    }
}

/** Why a file could not be backed up; shown to the parent as written. */
object MediaFailure {
    const val UNREADABLE = "The file could not be opened"
    const val UNSUPPORTED = "This file type can't be previewed"
    const val TOO_LARGE = "The preview is too large to store"
    const val NO_PREVIEW = "No preview frame could be made"
    const val UPLOAD = "Upload kept failing"
}

/** What the parent sees for one backed-up (or failed) item. */
data class MediaItem(
    val key: String,
    val kind: MediaKind,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val createdMs: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val uploadedMs: Long,
    val status: MediaStatus,
    val reason: String?,
    /** Size of the stored preview copy in bytes (what counts against the database). */
    val previewBytes: Int,
    val chunks: Int,
    /** Tiny base64 JPEG for the grid. */
    val thumb: String?
)

object MediaCodec {
    fun toJson(i: MediaItem): JsonObject = JsonObject().apply {
        addProperty("k", i.kind.code)
        addProperty("n", i.name)
        addProperty("m", i.mime)
        addProperty("sz", i.sizeBytes)
        addProperty("ct", i.createdMs)
        addProperty("w", i.width)
        addProperty("h", i.height)
        if (i.durationMs > 0L) addProperty("d", i.durationMs)
        addProperty("up", i.uploadedMs)
        addProperty("st", i.status.code)
        if (i.reason != null) addProperty("why", i.reason)
        addProperty("pb", i.previewBytes)
        addProperty("cn", i.chunks)
        if (i.thumb != null) addProperty("th", i.thumb)
    }

    fun fromJson(key: String, o: JsonObject): MediaItem? = try {
        val kind = MediaKind.fromCode(o.get("k")?.asString)
        if (kind == null || !MediaIds.isValidKey(key)) null else MediaItem(
            key = key, kind = kind,
            name = o.get("n")?.asString.orEmpty(),
            mime = o.get("m")?.asString.orEmpty(),
            sizeBytes = o.get("sz")?.asLong ?: 0L,
            createdMs = o.get("ct")?.asLong ?: MediaIds.createdMsOf(key),
            width = o.get("w")?.asInt ?: 0,
            height = o.get("h")?.asInt ?: 0,
            durationMs = o.get("d")?.asLong ?: 0L,
            uploadedMs = o.get("up")?.asLong ?: 0L,
            status = MediaStatus.fromCode(o.get("st")?.asString),
            reason = o.get("why")?.asString,
            previewBytes = o.get("pb")?.asInt ?: 0,
            chunks = o.get("cn")?.asInt ?: 0,
            thumb = o.get("th")?.asString
        )
    } catch (_: Exception) { null }
}

/** A stored preview is split into database-sized pieces named c000, c001 … (numeric keys would turn into an array). */
object MediaChunks {
    const val SIZE = 150_000

    fun name(i: Int): String = String.format(Locale.US, "c%03d", i)

    fun split(base64: String, size: Int = SIZE): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        var at = 0
        while (at < base64.length) {
            val end = minOf(base64.length, at + size)
            out[name(i++)] = base64.substring(at, end)
            at = end
        }
        return out
    }

    /** Joins pieces back in order. Null when any piece the item promised is missing. */
    fun join(pieces: Map<String, String>, expected: Int): String? {
        if (expected <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until expected) sb.append(pieces[name(i)] ?: return null)
        return sb.toString()
    }
}

/** Progress and state the child reports so the parent can show honest sync status. */
data class MediaStatusSnapshot(
    val access: MediaAccess,
    val enabled: Boolean,
    val pending: Int,
    val uploaded: Int,
    val failed: Int,
    val lastRunMs: Long,
    /** Why nothing is moving right now: "permission", "disabled", "wifi", "offline", or null. */
    val waiting: String?,
    val lastError: String?
)

object MediaStatusCodec {
    fun toJson(s: MediaStatusSnapshot): JsonObject = JsonObject().apply {
        addProperty("acc", s.access.code)
        addProperty("on", s.enabled)
        addProperty("pend", s.pending)
        addProperty("done", s.uploaded)
        addProperty("fail", s.failed)
        addProperty("run", s.lastRunMs)
        if (s.waiting != null) addProperty("wait", s.waiting)
        if (s.lastError != null) addProperty("err", s.lastError)
    }

    fun fromJson(o: JsonObject?): MediaStatusSnapshot? {
        if (o == null) return null
        return try {
            MediaStatusSnapshot(
                access = MediaAccess.fromCode(o.get("acc")?.asString),
                enabled = o.get("on")?.asBoolean ?: false,
                pending = o.get("pend")?.asInt ?: 0,
                uploaded = o.get("done")?.asInt ?: 0,
                failed = o.get("fail")?.asInt ?: 0,
                lastRunMs = o.get("run")?.asLong ?: 0L,
                waiting = o.get("wait")?.asString,
                lastError = o.get("err")?.asString
            )
        } catch (_: Exception) { null }
    }
}

object MediaPlanner {
    const val MAX_ATTEMPTS = 3
    const val MAX_PER_RUN = 20

    /**
     * Which of the [found] files to back up next. Nothing older than the moment backup was switched on,
     * nothing already stored, nothing the parent deleted, nothing that failed too many times, only kinds the
     * parent allowed, newest first, and never more than [limit] per run so a big gallery cannot hog the network.
     */
    fun plan(
        found: List<MediaCandidate>,
        settings: MediaSettings,
        stored: Set<String>,
        deleted: Set<String>,
        attempts: Map<String, Int>,
        limit: Int = MAX_PER_RUN,
        now: Long = System.currentTimeMillis()
    ): List<MediaCandidate> {
        if (!settings.enabled || settings.enabledAt <= 0L) return emptyList()
        return found.asSequence()
            .filter { (it.kind == MediaKind.IMAGE && settings.images) || (it.kind == MediaKind.VIDEO && settings.videos) }
            .filter { it.addedMs >= settings.enabledAt }
            .map { it to MediaIds.key(it) }
            .distinctBy { it.second }
            .filter { it.second !in stored && it.second !in deleted && (attempts[it.second] ?: 0) < MAX_ATTEMPTS }
            .filter { expired(listOf(it.second), settings, now).isEmpty() }   // already past the retention window: not worth storing
            .sortedByDescending { it.first.addedMs }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** How many eligible files are still waiting (same rules as [plan], no limit). */
    fun pendingCount(
        found: List<MediaCandidate>, settings: MediaSettings, stored: Set<String>, deleted: Set<String>, attempts: Map<String, Int>,
        now: Long = System.currentTimeMillis()
    ): Int = plan(found, settings, stored, deleted, attempts, Int.MAX_VALUE, now).size

    /** Keys older than the retention window; empty when retention is "keep until deleted". */
    fun expired(keys: Collection<String>, settings: MediaSettings, now: Long): List<String> {
        if (settings.retentionDays <= 0) return emptyList()
        val cutoff = now - settings.retentionDays * 24L * 60 * 60 * 1000
        return keys.filter { val t = MediaIds.createdMsOf(it); t in 1 until cutoff }
    }

    fun storageBytes(items: Collection<MediaItem>): Long = items.sumOf { it.previewBytes.toLong() }

    fun groupByDay(items: List<MediaItem>): List<Pair<String, List<MediaItem>>> =
        items.sortedByDescending { it.createdMs }
            .groupBy { DayClock.of(it.createdMs).dayKey }
            .toList()
            .sortedByDescending { it.first }

    fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}

/** Picture sizes for the preview copy and the grid thumbnail. */
object MediaGeometry {
    const val PREVIEW_EDGE = 1280
    const val THUMB_EDGE = 200

    /** Width and height scaled down (never up) so the longer side is at most [maxEdge], keeping the aspect ratio. */
    fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxEdge to maxEdge
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** Largest power-of-two sample size that still leaves the decoded picture at least [maxEdge] on its long side. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) { longest /= 2; sample *= 2 }
        return sample
    }
}

