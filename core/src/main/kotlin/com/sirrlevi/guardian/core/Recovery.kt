package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.util.Locale
import kotlin.math.abs

/*
 * Device recovery ("Find my phone") for a supervised child's phone, everything that needs no Android types.
 *
 * Rules that shape the code:
 *  - Location is shared only when the parent switched it on AND the child allowed it in Android's own permission dialog. Either one
 *    missing means no location is read, and both apps say which one is missing.
 *  - The parent sees real fixes only, with their age and accuracy. There is no guessed or "estimated" position.
 *  - History is kept for a retention the parent chose (1, 7 or 30 days) and is pruned by the child's phone itself.
 *  - There is no camera or microphone feature of any kind.
 */

object RecoveryPolicy {
    val INTERVALS_MIN = listOf(5, 15, 30, 60)
    const val DEFAULT_INTERVAL_MIN = 15
    val RETENTION_DAYS = listOf(1, 7, 30)
    const val DEFAULT_RETENTION_DAYS = 7
    /** The most history points kept, whatever the retention says, so the database cannot grow without end. */
    const val MAX_POINTS = 1000
    /** How often the phone checks whether the parent changed the location settings. */
    const val SETTINGS_POLL_MS = 60_000L
    /** How often the phone tells the parent which location permission it has, even when nothing else changed. */
    const val STATE_EVERY_MS = 5 * 60_000L
    /** How long a scheduled position may take before the phone gives up until the next try. */
    const val SCHEDULED_TIMEOUT_MS = 15_000L
    /** After a position couldn't be read, the phone waits this long before trying again (so a bad signal doesn't drain the battery). */
    const val RETRY_AFTER_FAILURE_MS = 90_000L
    /** Old history is cleaned up at most this often. */
    const val PRUNE_EVERY_MS = 60 * 60_000L
    /** A "find now" request waits this long for the phone to get a position. */
    const val LOCATE_TIMEOUT_MS = 25_000L
    /** A saved position the phone may reuse instead of asking again, when it is younger than this. */
    const val REUSE_FIX_MS = 60_000L
    /** How long a ring lasts at most. */
    const val RING_MS = 60_000L
    /** A fix older than this is shown with a warning that it may no longer be where the phone is. */
    const val OLD_FIX_MS = 60 * 60_000L
    /** The phone's own report is called old after this long. */
    const val STATE_STALE_MS = 15 * 60_000L
}

/** Which of Android's location permissions the phone has. */
enum class LocationPermission(val code: String) {
    NONE("none"), WHILE_OPEN("while_open"), ALWAYS("always");

    companion object {
        fun fromCode(code: String?): LocationPermission = values().firstOrNull { it.code == code } ?: NONE
    }
}

/** Why a position is not being shared right now. */
enum class LocationBlock { OFF, NO_PERMISSION, ONLY_WHILE_OPEN, SERVICES_OFF }

/** Parent-written settings for one child phone. */
data class RecoverySettings(val enabled: Boolean, val intervalMin: Int, val retentionDays: Int, val ts: Long) {
    /** Only the allowed values are ever used; anything else (damaged or hand-edited data) falls back to the default. */
    fun normalized(): RecoverySettings = copy(
        intervalMin = if (intervalMin in RecoveryPolicy.INTERVALS_MIN) intervalMin else RecoveryPolicy.DEFAULT_INTERVAL_MIN,
        retentionDays = if (retentionDays in RecoveryPolicy.RETENTION_DAYS) retentionDays else RecoveryPolicy.DEFAULT_RETENTION_DAYS
    )

    companion object {
        /** What the phone assumes while the parent has never switched anything on. */
        val OFF = RecoverySettings(false, RecoveryPolicy.DEFAULT_INTERVAL_MIN, RecoveryPolicy.DEFAULT_RETENTION_DAYS, 0L)
    }
}

object RecoverySettingsCodec {
    fun toJson(s: RecoverySettings): JsonObject = JsonObject().apply {
        val n = s.normalized()
        addProperty("on", n.enabled)
        addProperty("every", n.intervalMin)
        addProperty("keep", n.retentionDays)
        addProperty("ts", n.ts)
    }

    fun fromJson(o: JsonObject?): RecoverySettings? {
        if (o == null) return null
        return try {
            RecoverySettings(
                enabled = o.get("on")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                intervalMin = o.get("every")?.asInt ?: RecoveryPolicy.DEFAULT_INTERVAL_MIN,
                retentionDays = o.get("keep")?.asInt ?: RecoveryPolicy.DEFAULT_RETENTION_DAYS,
                ts = o.get("ts")?.asLong ?: 0L
            ).normalized()
        } catch (_: Exception) { null }
    }
}

/** One real position the phone read. */
data class LocationPoint(val ts: Long, val lat: Double, val lon: Double, val accuracyM: Float, val provider: String)

object LocationPointCodec {
    /** Keys sort by time, so the newest are last. */
    fun key(ts: Long): String = String.format(Locale.US, "%013d", ts)

    fun toJson(p: LocationPoint): JsonObject = JsonObject().apply {
        addProperty("ts", p.ts)
        addProperty("lat", Math.round(p.lat * 1_000_000.0) / 1_000_000.0)
        addProperty("lon", Math.round(p.lon * 1_000_000.0) / 1_000_000.0)
        addProperty("acc", Math.round(p.accuracyM))
        addProperty("src", p.provider)
    }

    /** A point that is not a real position (out of range, not a number, the empty 0,0 reading) is dropped, never shown. */
    fun fromJson(o: JsonObject?): LocationPoint? {
        if (o == null) return null
        return try {
            val lat = o.get("lat")?.asDouble ?: return null
            val lon = o.get("lon")?.asDouble ?: return null
            if (!LocationRules.isValid(lat, lon)) return null
            LocationPoint(o.get("ts")?.asLong ?: return null, lat, lon, o.get("acc")?.asFloat ?: 0f, o.get("src")?.asString.orEmpty())
        } catch (_: Exception) { null }
    }
}

/** What the child's phone says about location right now. */
data class RecoveryState(
    val ts: Long,
    /** What the phone currently believes the parent's switch is. */
    val sharing: Boolean,
    val permission: LocationPermission,
    val servicesOn: Boolean,
    /** When the phone last read a position (0 when never). */
    val lastFixAt: Long
)

object RecoveryStateCodec {
    fun toJson(s: RecoveryState): JsonObject = JsonObject().apply {
        addProperty("ts", s.ts)
        addProperty("on", s.sharing)
        addProperty("perm", s.permission.code)
        addProperty("services", s.servicesOn)
        addProperty("fix", s.lastFixAt)
    }

    fun fromJson(o: JsonObject?): RecoveryState? {
        if (o == null) return null
        return try {
            fun flag(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            RecoveryState(o.get("ts")?.asLong ?: return null, flag("on"), LocationPermission.fromCode(o.get("perm")?.asString), flag("services"), o.get("fix")?.asLong ?: 0L)
        } catch (_: Exception) { null }
    }
}

/** Every sentence about location, ringing and finding the phone, shared by both apps so they always say the same thing. */
object RecoveryTexts {
    const val OFF = "Location sharing is off, so the phone isn't reading or sending its position."
    const val NO_PERMISSION = "Your child hasn't allowed location in Guardian Child yet. It can only be switched on by them, in Android's own permission dialog (Guardian Child → Protection)."
    const val ONLY_WHILE_OPEN = "Location is only allowed while Guardian Child is open on that phone. For it to work in the background, your child chooses \"Allow all the time\" in Guardian Child → Protection."
    const val SERVICES_OFF = "Location is switched off in that phone's own settings, so Android gives Guardian no position."
    const val NO_REPORT = "Waiting for the phone to report its location settings. This needs the latest Guardian Child on that phone."

    const val LOCATED = "The phone read a fresh position."
    const val NO_FIX = "The phone couldn't get a position in time (it may be indoors, in a basement or have no signal). Try again in a minute."
    const val LOCATE_FAILED = "The phone couldn't read its position."

    const val RING_STARTED = "The phone is ringing for up to a minute. A notice on the phone says it was Guardian, and your child can stop it from there."
    const val RING_UNAVAILABLE = "The phone can't ring right now. Guardian only rings together with a visible notice, so notifications must be allowed for Guardian Child on that phone."
    const val RING_FAILED = "The phone couldn't start the sound."
    const val SILENT_NOTE = "Ringing uses the alarm sound, which plays even when the phone is on silent. It stays quiet if the phone is in \"Total silence\" mode or its alarm volume is blocked."

    const val NO_CAMERA_MIC =
        "Not offered. Android doesn't let an app secretly switch on a phone's camera or microphone, and Guardian never would. Nothing here records or listens."
    const val WHEN_OFF_NOTE =
        "A phone that is switched off, offline or out of battery can't answer. Its last position stays here, with its age."
}

/** What the child's own screen needs to say about Find my phone. */
data class RecoveryLocalView(val settings: RecoverySettings?, val permission: LocationPermission, val servicesOn: Boolean)

/** What the child is told, in plain words. The child always sees what the parent can do and what is on right now. */
object RecoveryChildText {
    const val WHAT_PARENT_CAN_DO =
        "Your parent can make this phone ring if it is lost (with a notice and a Stop button on your screen), and can lock the screen. " +
            "If they switch it on and you allow it, they can also see where this phone is. Guardian never turns on your camera or microphone."

    fun location(v: RecoveryLocalView): String {
        val s = v.settings
            ?: return "Location sharing: waiting to hear what your parent chose. Nothing is read until your parent switches it on and you allow it."
        if (!s.enabled) return "Your parent hasn't switched location sharing on, so Guardian isn't reading where this phone is."
        val every = "about every ${s.intervalMin} minutes, kept for ${s.retentionDays} day" + (if (s.retentionDays == 1) "" else "s")
        return when (LocationRules.blockFor(true, v.permission, v.servicesOn)) {
            null -> "Location sharing is on: your parent can see where this phone is, $every."
            LocationBlock.NO_PERMISSION -> "Your parent switched location sharing on, but nothing is shared because you haven't allowed location. You decide: turn it on below only if you agree."
            LocationBlock.ONLY_WHILE_OPEN -> "Your parent switched location sharing on. You allowed location only while Guardian Child is open, so it only works then. Choose \"Allow all the time\" below if you agree to it working in the background."
            LocationBlock.SERVICES_OFF -> "Your parent switched location sharing on, but Location is switched off in this phone's settings, so nothing is shared."
            LocationBlock.OFF -> "Your parent hasn't switched location sharing on."
        }
    }
}

object LocationRules {
    fun isValid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && abs(lat) <= 90.0 && abs(lon) <= 180.0 && !(lat == 0.0 && lon == 0.0)

    /** Why a position can't be shared right now, or null when it can. */
    fun blockFor(sharing: Boolean, permission: LocationPermission, servicesOn: Boolean): LocationBlock? = when {
        !sharing -> LocationBlock.OFF
        permission == LocationPermission.NONE -> LocationBlock.NO_PERMISSION
        !servicesOn -> LocationBlock.SERVICES_OFF
        permission == LocationPermission.WHILE_OPEN -> LocationBlock.ONLY_WHILE_OPEN
        else -> null
    }

    fun text(block: LocationBlock): String = when (block) {
        LocationBlock.OFF -> RecoveryTexts.OFF
        LocationBlock.NO_PERMISSION -> RecoveryTexts.NO_PERMISSION
        LocationBlock.ONLY_WHILE_OPEN -> RecoveryTexts.ONLY_WHILE_OPEN
        LocationBlock.SERVICES_OFF -> RecoveryTexts.SERVICES_OFF
    }

    /** Time for the next scheduled position. A little early is fine (checks happen on a timer), a missed one is not. */
    fun due(lastFixAt: Long, now: Long, intervalMin: Int): Boolean {
        if (lastFixAt <= 0L || lastFixAt > now) return true
        val every = intervalMin.coerceIn(1, 24 * 60) * 60_000L
        return now - lastFixAt >= every - every / 20
    }

    /** History keys to delete: older than the retention, or beyond the newest [maxPoints]. Keys that aren't times are cleaned up too. */
    fun expiredKeys(keys: Collection<String>, now: Long, retentionDays: Int, maxPoints: Int = RecoveryPolicy.MAX_POINTS): List<String> {
        val cutoff = now - retentionDays.coerceAtLeast(1) * 24L * 3_600_000L
        val timed = keys.mapNotNull { k -> k.toLongOrNull()?.let { k to it } }.sortedBy { it.second }
        val garbage = keys.filter { it.toLongOrNull() == null }
        val old = timed.filter { it.second < cutoff }.map { it.first }
        val rest = timed.filter { it.second >= cutoff }
        val surplus = rest.dropLast(maxPoints.coerceAtLeast(1)).map { it.first }
        return garbage + old + surplus
    }

    /** True when the phone may reuse a position it already has instead of asking the sensors again. */
    fun reusable(lastFixAt: Long, now: Long): Boolean = lastFixAt > 0L && now - lastFixAt in 0..RecoveryPolicy.REUSE_FIX_MS

    /** A `geo:` link any map app can open. The label is kept plain so it can't break the link. */
    fun geoUri(p: LocationPoint, label: String): String {
        val safe = label.filter { it.isLetterOrDigit() || it == ' ' || it == '\'' || it == '-' }.trim().replace(' ', '+')
        val coords = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)
        return "geo:$coords?q=$coords" + if (safe.isNotEmpty()) "($safe)" else ""
    }

    fun coordinates(p: LocationPoint): String = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)

    fun accuracyText(accuracyM: Float): String = when {
        accuracyM <= 0f -> "accuracy unknown"
        accuracyM < 1_000f -> "within about ${Math.round(accuracyM)} m"
        else -> String.format(Locale.US, "within about %.1f km", accuracyM / 1000f)
    }
}

// ------------------------------------------------------------------------------------------------ the phone's side

/** The Realtime Database side of location sharing. Every call blocks and may throw. */
interface RecoveryStore {
    fun readSettings(): RecoverySettings?
    /** Saves the position as the newest one and as one more line of history. */
    fun saveFix(point: LocationPoint)
    fun historyKeys(): List<String>
    fun deletePoints(keys: List<String>)
    fun writeState(state: RecoveryState)
}

/** What the phone itself can read. [read] may throw (for example a SecurityException when the permission was just taken away). */
interface LocationSource {
    fun permission(): LocationPermission
    fun servicesOn(): Boolean
    /** A fresh position, or null when none could be read within the time. Never guesses. */
    fun read(timeoutMs: Long): LocationPoint?
}

/**
 * Decides when the child's phone reads its position, and answers the parent's "find now".
 *  - Nothing is read unless the parent's switch is on and Android's permission and location setting allow it.
 *  - A failed or slow read is reported as such; an old position is never passed off as new.
 *  - History is cleaned up by the phone itself, to the retention the parent chose.
 * Everything runs on one thread, one call at a time.
 */
class RecoveryCoordinator(
    private val store: RecoveryStore,
    private val source: LocationSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {
    private var settings = RecoverySettings.OFF
    private var settingsTriedAt = 0L
    private var settingsLoaded = false
    private var lastFixAt = 0L
    private var nextAttemptAt = 0L
    private var lastState: RecoveryState? = null
    private var stateAt = 0L
    private var prunedAt = 0L

    /** What the phone currently believes the parent chose, or null until it has been read once. */
    @Synchronized fun snapshot(): RecoverySettings? = if (settingsLoaded) settings else null

    /** One pass, called every few seconds. Cheap when nothing is due. */
    @Synchronized fun tick() {
        val now = clock()
        refreshSettings(now, force = false)
        val perm = source.permission()
        val services = source.servicesOn()
        if (LocationRules.blockFor(settings.enabled, perm, services) == null && now >= nextAttemptAt && LocationRules.due(lastFixAt, now, settings.intervalMin)) {
            try {
                if (readAndSave(RecoveryPolicy.SCHEDULED_TIMEOUT_MS) == null) nextAttemptAt = now + RecoveryPolicy.RETRY_AFTER_FAILURE_MS
            } catch (e: Exception) {
                log("scheduled position failed", e)
                nextAttemptAt = now + RecoveryPolicy.RETRY_AFTER_FAILURE_MS
            }
        }
        prune(now)
        reportState(now, perm, services)
    }

    /** The parent's "find now". Reads the parent's current switch first, so a request after switching off never reads anything. */
    @Synchronized fun locate(): ControlOutcome {
        val now = clock()
        refreshSettings(now, force = true)
        if (!settings.enabled) return ControlOutcome(ControlResult.REJECTED, RecoveryTexts.OFF)
        val perm = source.permission()
        val services = source.servicesOn()
        LocationRules.blockFor(true, perm, services)?.let { return ControlOutcome(ControlResult.REJECTED, LocationRules.text(it)) }
        if (LocationRules.reusable(lastFixAt, now)) return ControlOutcome(ControlResult.DONE, RecoveryTexts.LOCATED)
        readAndSave(RecoveryPolicy.LOCATE_TIMEOUT_MS) ?: return ControlOutcome(ControlResult.FAILED, RecoveryTexts.NO_FIX)
        reportState(clock(), perm, services)
        return ControlOutcome(ControlResult.DONE, RecoveryTexts.LOCATED)
    }

    private fun readAndSave(timeoutMs: Long): LocationPoint? {
        val point = source.read(timeoutMs) ?: return null
        if (!LocationRules.isValid(point.lat, point.lon)) return null
        store.saveFix(point)
        lastFixAt = minOf(point.ts, clock()).coerceAtLeast(1L)
        return point
    }

    /** [force] reads the settings from the server right now and lets a failure through; otherwise they are read about once a minute. */
    private fun refreshSettings(now: Long, force: Boolean) {
        val every = if (settingsLoaded) RecoveryPolicy.SETTINGS_POLL_MS else 10_000L
        if (!force && now - settingsTriedAt < every) return
        settingsTriedAt = now
        try {
            settings = (store.readSettings() ?: RecoverySettings.OFF).normalized()
            settingsLoaded = true
        } catch (e: Exception) {
            if (force) throw e
            // Keep what we had. Until the settings have been read once, nothing is shared.
            log("settings not read", e)
            settingsTriedAt = now - RecoveryPolicy.SETTINGS_POLL_MS + 10_000L
        }
    }

    private fun prune(now: Long) {
        if (now - prunedAt < RecoveryPolicy.PRUNE_EVERY_MS) return
        prunedAt = now
        try {
            val gone = LocationRules.expiredKeys(store.historyKeys(), now, settings.retentionDays)
            if (gone.isNotEmpty()) store.deletePoints(gone)
        } catch (e: Exception) {
            log("history not cleaned", e)
            prunedAt = now - RecoveryPolicy.PRUNE_EVERY_MS + 5 * 60_000L
        }
    }

    private fun reportState(now: Long, perm: LocationPermission, services: Boolean) {
        val s = RecoveryState(now, settings.enabled, perm, services, lastFixAt)
        val before = lastState
        val changed = before == null || before.sharing != s.sharing || before.permission != s.permission || before.servicesOn != s.servicesOn
        if (!changed && now - stateAt < RecoveryPolicy.STATE_EVERY_MS) return
        try {
            store.writeState(s)
            lastState = s
            stateAt = now
        } catch (e: Exception) {
            log("location state not sent", e)
            stateAt = now - RecoveryPolicy.STATE_EVERY_MS + 10_000L
            lastState = null
        }
    }
}
