package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Device Appearance: the wallpaper the child chose to share.
 *
 * Android does not let a normal app read the home-screen or lock-screen wallpaper (it needs a storage permission that Google Play
 * and Android 13+ do not grant for this, and many wallpapers are live or system-owned). So Guardian never tries. The child opens
 * the system picture picker, chooses the picture that is their wallpaper, and Guardian sends exactly that picture. Nothing is
 * shared until they do, and they can remove it again. The parent therefore only ever sees a picture the child handed over,
 * with the time they did so; it is never presented as a live view of the screen.
 */

object WallpaperPolicy {
    /** Long side of the stored picture. A 1080x2400 phone wallpaper keeps its full size. */
    const val MAX_EDGE = 2400
    /** The picture is stored as text pieces in the database, so it has to stay modest. */
    const val MAX_BYTES = 900_000
    const val MAX_CHUNKS = 12

    /** One way to encode the picture: scale the long side down to [edge], then JPEG at [quality]. */
    data class Attempt(val edge: Int, val quality: Int)

    /** Tried in order until the JPEG fits [MAX_BYTES]: first a lower quality, then a smaller picture. Every step is smaller than the last. */
    fun attempts(): List<Attempt> {
        val out = ArrayList<Attempt>()
        for (edge in intArrayOf(MAX_EDGE, 1920, 1600, 1280)) for (quality in intArrayOf(85, 72, 60, 48)) out.add(Attempt(edge, quality))
        return out
    }

    fun fits(bytes: Int): Boolean = bytes in 1..MAX_BYTES
}

/** What is stored about the shared picture. The picture itself sits next to it in text pieces ([MediaChunks]). */
data class WallpaperMeta(val ts: Long, val width: Int, val height: Int, val bytes: Int, val chunks: Int)

object WallpaperCodec {
    fun toJson(m: WallpaperMeta): JsonObject = JsonObject().apply {
        addProperty("ts", m.ts); addProperty("w", m.width); addProperty("h", m.height); addProperty("b", m.bytes); addProperty("n", m.chunks)
    }

    /** Null for anything that is not a complete, plausible description, so the parent never shows a half-described picture. */
    fun fromJson(o: JsonObject?): WallpaperMeta? {
        if (o == null) return null
        return try {
            val m = WallpaperMeta(o.get("ts").asLong, o.get("w").asInt, o.get("h").asInt, o.get("b").asInt, o.get("n").asInt)
            m.takeIf { it.ts > 0L && it.width > 0 && it.height > 0 && it.bytes > 0 && it.chunks in 1..WallpaperPolicy.MAX_CHUNKS }
        } catch (_: Exception) { null }
    }
}

object WallpaperRules {
    /** File name for a saved copy, e.g. guardian-wallpaper-20261126-1420.jpg. The time is when the child shared it. */
    fun fileName(ts: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply { timeZone = zone }
        return "guardian-wallpaper-" + f.format(Date(ts)) + ".jpg"
    }

    fun sizeText(bytes: Int): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> maxOf(1, Math.round(bytes / 1000.0).toInt()).toString() + " KB"
    }

    fun detail(m: WallpaperMeta): String = "${m.width}×${m.height} · ${sizeText(m.bytes)}"

    /** Whether the downloaded bytes are the picture that was described: a JPEG, and exactly as long as the child's phone said. */
    fun isComplete(meta: WallpaperMeta, jpeg: ByteArray?): Boolean =
        jpeg != null && jpeg.size == meta.bytes && jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()
}

object WallpaperTexts {
    fun notShared(childName: String): String =
        "$childName hasn't shared a wallpaper. Android doesn't let apps read a phone's wallpaper, so Guardian can't fetch it by itself. " +
            "To share it, $childName opens Guardian on their phone, taps \"Share my wallpaper\" and picks the picture."

    fun sharedNote(childName: String): String =
        "This is the picture $childName chose to share. It is not a live view: if they change their wallpaper, they need to share it again."

    const val LOADING = "Looking for a shared wallpaper…"
    const val LOAD_FAILED = "The wallpaper couldn't be loaded. Check your connection and try again."

    fun incomplete(childName: String): String =
        "$childName shared a wallpaper, but its picture is missing or damaged, so nothing is shown. Ask them to share it again."

    const val SAVED = "Wallpaper saved."
    const val SAVE_FAILED = "Couldn't save the wallpaper."
    const val REMOVED = "The shared wallpaper was deleted from Guardian's server. Nothing changes on the phone."
    const val REMOVE_FAILED = "Couldn't delete the shared wallpaper."
}

/** What the child sees and is told on their own phone. */
object WallpaperChildText {
    const val EXPLAIN =
        "Android doesn't let Guardian read your wallpaper, so nothing is shared unless you choose a picture here. " +
            "Only the picture you pick is sent to your parent, and you can remove it again any time."

    fun status(sharedAtText: String?): String =
        if (sharedAtText == null) "Nothing shared." else "Shared with your parent on $sharedAtText."

    const val SHARED = "Your wallpaper picture was sent to your parent."
    const val CANT_OPEN = "That picture couldn't be opened. Pick a different one."
    const val TOO_BIG = "That picture is too big to send. Pick a smaller one."
    const val UPLOAD_FAILED = "The picture couldn't be sent. Check your connection and try again."
    const val REMOVED = "Your shared wallpaper was removed."
    const val REMOVE_FAILED = "Couldn't remove it. Check your connection and try again."
    const val NOT_SIGNED_IN = "This phone isn't connected to a parent yet."
}
