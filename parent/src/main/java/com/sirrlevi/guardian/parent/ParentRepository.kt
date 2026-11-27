package com.sirrlevi.guardian.parent

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.CommCodec
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.CaptureAck
import com.sirrlevi.guardian.core.CaptureAckCodec
import com.sirrlevi.guardian.core.CaptureCommand
import com.sirrlevi.guardian.core.CaptureCommandCodec
import com.sirrlevi.guardian.core.CaptureEvent
import com.sirrlevi.guardian.core.CaptureEventCodec
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.ControlAck
import com.sirrlevi.guardian.core.ControlAckCodec
import com.sirrlevi.guardian.core.ControlCommand
import com.sirrlevi.guardian.core.ControlCommandCodec
import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlEventCodec
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.LocationPointCodec
import com.sirrlevi.guardian.core.RecoveryPolicy
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoverySettingsCodec
import com.sirrlevi.guardian.core.RecoveryState
import com.sirrlevi.guardian.core.RecoveryStateCodec
import com.sirrlevi.guardian.core.WallpaperCodec
import com.sirrlevi.guardian.core.WallpaperMeta
import com.sirrlevi.guardian.core.ControlStatusCodec
import com.sirrlevi.guardian.core.CaptureStateCodec
import com.sirrlevi.guardian.core.LiveCodec
import com.sirrlevi.guardian.core.LiveMeta
import com.sirrlevi.guardian.core.MediaChunks
import com.sirrlevi.guardian.core.ShotCodec
import com.sirrlevi.guardian.core.ShotMeta
import com.sirrlevi.guardian.core.MediaCodec
import com.sirrlevi.guardian.core.MediaItem
import com.sirrlevi.guardian.core.MediaSettings
import com.sirrlevi.guardian.core.MediaSettingsCodec
import com.sirrlevi.guardian.core.MediaStatusCodec
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import com.sirrlevi.guardian.core.ScreenEvent
import com.sirrlevi.guardian.core.ScreenEventCodec
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.PkgKey
import com.sirrlevi.guardian.core.PolicyCodec
import com.sirrlevi.guardian.core.arrayOrNull
import com.sirrlevi.guardian.core.boolOrFalse
import com.sirrlevi.guardian.core.intOr
import com.sirrlevi.guardian.core.longOr
import com.sirrlevi.guardian.core.objectOrNull
import com.sirrlevi.guardian.core.stringOr
import java.io.File

/** All talking to Firebase, parsing and on-disk caching for the Parent app. Every call here is blocking: run it off the main thread. */
class ParentRepository(context: Context) {
    private val app = context.applicationContext
    val api = GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL)
    private val vault = SessionVault(app)
    private val gson = Gson()
    private val cacheFile get() = File(app.filesDir, "parent_cache_v2.json")
    private val iconFile get() = File(app.filesDir, "parent_icons_v2.json")

    fun isConfigured() = api.isConfigured()
    fun loadSession(): GuardianSession? = vault.load()
    fun saveSession(session: GuardianSession) = vault.save(session)
    fun clearSession() { vault.clear(); cacheFile.delete(); iconFile.delete() }

    fun signIn(email: String, password: String) = api.signIn(email, password).also { vault.save(it) }
    fun signUp(email: String, password: String) = api.signUp(email, password).also { vault.save(it) }

    /** Returns a session whose token is valid for at least two more minutes. */
    fun fresh(session: GuardianSession): GuardianSession {
        if (session.expiresAtMs > System.currentTimeMillis() + 120_000L) return session
        return api.refresh(session).also { vault.save(it) }
    }

    // ------------------------------------------------------------ reading

    fun fetchPolicyRaw(session: GuardianSession): JsonObject? = api.get(GuardianPaths.policy(session.uid), session).obj()

    fun fetchSessionsFor(session: GuardianSession, deviceId: String, dayKey: String): List<SessionRow> =
        parseSessions(api.get(GuardianPaths.sessions(session.uid, deviceId, dayKey), session).obj())

    fun fetchScreenFor(session: GuardianSession, deviceId: String, dayKey: String): List<ScreenEvent> =
        parseScreen(api.get(GuardianPaths.screen(session.uid, deviceId, dayKey), session).obj())

    // ------------------------------------------------------------ shared media

    fun fetchMediaSettings(session: GuardianSession, deviceId: String): MediaSettings =
        MediaSettingsCodec.fromJson(api.get(GuardianPaths.mediaSettings(session.uid, deviceId), session).obj())

    fun saveMediaSettings(session: GuardianSession, deviceId: String, settings: MediaSettings) =
        api.put(GuardianPaths.mediaSettings(session.uid, deviceId), MediaSettingsCodec.toJson(settings), session)

    fun fetchMediaStatus(session: GuardianSession, deviceId: String): MediaStatusSnapshot? =
        MediaStatusCodec.fromJson(api.get(GuardianPaths.mediaStatus(session.uid, deviceId), session).obj())

    class MediaPage(val items: List<MediaItem>, val hasMore: Boolean, val oldestKey: String?)

    /** Up to [limit] items older than [beforeKey] (or the newest ones when it is null), newest first. */
    fun fetchMediaPage(session: GuardianSession, deviceId: String, beforeKey: String?, limit: Int = 60): MediaPage {
        val end = if (beforeKey != null) "&endAt=\"$beforeKey\"" else ""
        val ask = if (beforeKey != null) limit + 2 else limit + 1
        val node = api.get(GuardianPaths.mediaItems(session.uid, deviceId), session, "orderBy=\"\$key\"&limitToLast=$ask$end").obj()
            ?: return MediaPage(emptyList(), false, null)
        val keys = node.keySet().filter { beforeKey == null || it < beforeKey }.sorted()
        val wanted = keys.takeLast(limit).toSet()
        val items = node.entrySet().mapNotNull { (key, value) ->
            if (key in wanted) value.takeIf { it.isJsonObject }?.let { MediaCodec.fromJson(key, it.asJsonObject) } else null
        }.sortedByDescending { it.key }
        return MediaPage(items, hasMore = keys.size > limit, oldestKey = keys.takeLast(limit).firstOrNull())
    }

    /** The stored preview copy as JPEG bytes, or null if any piece is missing (never a half picture). */
    fun fetchMediaPreview(session: GuardianSession, deviceId: String, item: MediaItem): ByteArray? {
        val node = api.get(GuardianPaths.mediaBlob(session.uid, deviceId, item.key), session).obj() ?: return null
        val pieces = node.entrySet().mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.let { k to it.asString } }.toMap()
        val base64 = MediaChunks.join(pieces, item.chunks) ?: return null
        return try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) } catch (_: IllegalArgumentException) { null }
    }

    /** Deletes the preview copy and tells the child never to back that file up again. The original stays on the phone. */
    fun deleteMedia(session: GuardianSession, deviceId: String, key: String) {
        // Tombstone first: if it fails nothing is removed and the parent sees the error; if a later step fails the
        // phone still knows never to back this file up again.
        api.put(GuardianPaths.mediaDeleted(session.uid, deviceId) + "/" + key, com.google.gson.JsonPrimitive(System.currentTimeMillis()), session)
        api.delete(GuardianPaths.mediaBlob(session.uid, deviceId, key), session)
        api.delete(GuardianPaths.mediaItem(session.uid, deviceId, key), session)
    }

    // ------------------------------------------------------------ screen sharing

    fun fetchCaptureState(session: GuardianSession, deviceId: String): CaptureState? =
        CaptureStateCodec.fromJson(api.get(GuardianPaths.captureState(session.uid, deviceId), session).obj())

    fun sendCaptureCommand(session: GuardianSession, deviceId: String, command: CaptureCommand) =
        api.put(GuardianPaths.captureCommand(session.uid, deviceId, command.id), CaptureCommandCodec.toJson(command), session)

    fun fetchCaptureAck(session: GuardianSession, deviceId: String, id: String): CaptureAck? =
        CaptureAckCodec.fromJson(id, api.get(GuardianPaths.captureAck(session.uid, deviceId, id), session).obj())

    fun deleteCaptureAck(session: GuardianSession, deviceId: String, id: String) =
        api.delete(GuardianPaths.captureAck(session.uid, deviceId, id), session)

    /** Stored screenshots, newest first. A screenshot only appears here once all its pieces are stored. */
    fun fetchShots(session: GuardianSession, deviceId: String): List<ShotMeta> {
        val node = api.get(GuardianPaths.captureShots(session.uid, deviceId), session).obj() ?: return emptyList()
        return node.entrySet().mapNotNull { (id, v) -> v.takeIf { it.isJsonObject }?.let { ShotCodec.fromJson(id, it.asJsonObject) } }.sortedByDescending { it.id }
    }

    fun fetchShotImage(session: GuardianSession, deviceId: String, meta: ShotMeta): ByteArray? =
        decodePieces(api.get(GuardianPaths.captureShotBlob(session.uid, deviceId, meta.id), session).obj(), meta.chunks)

    fun deleteShot(session: GuardianSession, deviceId: String, id: String) {
        api.delete(GuardianPaths.captureShotBlob(session.uid, deviceId, id), session)
        api.delete(GuardianPaths.captureShot(session.uid, deviceId, id), session)
    }

    fun fetchLiveMeta(session: GuardianSession, deviceId: String): LiveMeta? =
        LiveCodec.fromJson(api.get(GuardianPaths.captureLive(session.uid, deviceId), session).obj())

    fun fetchLiveImage(session: GuardianSession, deviceId: String, meta: LiveMeta): ByteArray? =
        decodePieces(api.get(GuardianPaths.captureLiveBlob(session.uid, deviceId), session).obj(), meta.chunks)

    /** What happened in recent sessions on the phone, newest first. */
    fun fetchCaptureLog(session: GuardianSession, deviceId: String): List<CaptureEvent> {
        val node = api.get(GuardianPaths.captureLog(session.uid, deviceId), session).obj() ?: return emptyList()
        return node.entrySet().sortedByDescending { it.key }.mapNotNull { (_, v) -> v.takeIf { it.isJsonObject }?.let { CaptureEventCodec.fromJson(it.asJsonObject) } }
    }

    fun touchCaptureViewer(session: GuardianSession, deviceId: String, now: Long) =
        api.put(GuardianPaths.captureViewer(session.uid, deviceId), com.google.gson.JsonPrimitive(now), session)

    fun clearCaptureViewer(session: GuardianSession, deviceId: String) =
        api.delete(GuardianPaths.captureViewer(session.uid, deviceId), session)

    /** Joins the stored text pieces back into JPEG bytes, or null if any piece is missing or damaged (never a half picture). */
    private fun decodePieces(node: JsonObject?, expectedChunks: Int): ByteArray? {
        if (node == null) return null
        val pieces = node.entrySet().mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.let { k to it.asString } }.toMap()
        val base64 = MediaChunks.join(pieces, expectedChunks) ?: return null
        return try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) } catch (_: IllegalArgumentException) { null }
    }

    // ------------------------------------------------------------ device controls (lock now, restart)

    fun fetchControlStatus(session: GuardianSession, deviceId: String): ControlStatus? =
        ControlStatusCodec.fromJson(api.get(GuardianPaths.controlStatus(session.uid, deviceId), session).obj())

    fun sendControlCommand(session: GuardianSession, deviceId: String, command: ControlCommand) =
        api.put(GuardianPaths.controlCommand(session.uid, deviceId, command.id), ControlCommandCodec.toJson(command), session)

    fun withdrawControlCommand(session: GuardianSession, deviceId: String, id: String) =
        api.delete(GuardianPaths.controlCommand(session.uid, deviceId, id), session)

    fun fetchControlAck(session: GuardianSession, deviceId: String, id: String): ControlAck? =
        ControlAckCodec.fromJson(id, api.get(GuardianPaths.controlAck(session.uid, deviceId, id), session).obj())

    fun deleteControlAck(session: GuardianSession, deviceId: String, id: String) =
        api.delete(GuardianPaths.controlAck(session.uid, deviceId, id), session)

    /** What the phone did about recent requests, newest first. */
    fun fetchControlLog(session: GuardianSession, deviceId: String): List<ControlEvent> {
        val node = api.get(GuardianPaths.controlLog(session.uid, deviceId), session).obj() ?: return emptyList()
        return node.entrySet().sortedByDescending { it.key }.mapNotNull { (_, v) -> v.takeIf { it.isJsonObject }?.let { ControlEventCodec.fromJson(it.asJsonObject) } }
    }

    // ------------------------------------------------------------ device recovery (location sharing)

    fun fetchRecoverySettings(session: GuardianSession, deviceId: String): RecoverySettings? =
        RecoverySettingsCodec.fromJson(api.get(GuardianPaths.recoverySettings(session.uid, deviceId), session).obj())

    fun saveRecoverySettings(session: GuardianSession, deviceId: String, settings: RecoverySettings) =
        api.put(GuardianPaths.recoverySettings(session.uid, deviceId), RecoverySettingsCodec.toJson(settings), session)

    fun fetchRecoveryState(session: GuardianSession, deviceId: String): RecoveryState? =
        RecoveryStateCodec.fromJson(api.get(GuardianPaths.recoveryState(session.uid, deviceId), session).obj())

    fun fetchRecoveryLast(session: GuardianSession, deviceId: String): LocationPoint? =
        LocationPointCodec.fromJson(api.get(GuardianPaths.recoveryLast(session.uid, deviceId), session).obj())

    /** The newest [limit] positions, newest first. Damaged entries are skipped, never shown. */
    fun fetchRecoveryHistory(session: GuardianSession, deviceId: String, limit: Int): List<LocationPoint> {
        val node = api.get(GuardianPaths.recoveryHistory(session.uid, deviceId), session, "orderBy=\"\$key\"&limitToLast=" + limit.coerceIn(1, RecoveryPolicy.MAX_POINTS)).obj()
            ?: return emptyList()
        return node.entrySet().mapNotNull { (_, v) -> LocationPointCodec.fromJson(v.obj()) }.sortedByDescending { it.ts }
    }

    /** Deletes every saved position, including the newest one. */
    fun clearRecoveryHistory(session: GuardianSession, deviceId: String) {
        api.delete(GuardianPaths.recoveryHistory(session.uid, deviceId), session)
        api.delete(GuardianPaths.recoveryLast(session.uid, deviceId), session)
    }

    // ------------------------------------------------------------ device appearance (the wallpaper the child shared)

    fun fetchWallpaperMeta(session: GuardianSession, deviceId: String): WallpaperMeta? =
        WallpaperCodec.fromJson(api.get(GuardianPaths.wallpaper(session.uid, deviceId), session).obj())

    /** The shared picture as JPEG bytes, or null when any piece is missing or damaged (never a half picture). */
    fun fetchWallpaperImage(session: GuardianSession, deviceId: String, meta: WallpaperMeta): ByteArray? =
        decodePieces(api.get(GuardianPaths.wallpaperBlob(session.uid, deviceId), session).obj(), meta.chunks)

    /** Description first, so nobody sees a description whose picture is already gone. */
    fun deleteWallpaper(session: GuardianSession, deviceId: String) {
        api.delete(GuardianPaths.wallpaper(session.uid, deviceId), session)
        api.delete(GuardianPaths.wallpaperBlob(session.uid, deviceId), session)
    }

    class DeviceMeta(val id: String, val name: String, val manufacturer: String, val lastSeen: Long)

    fun fetchDeviceList(session: GuardianSession): List<DeviceMeta> {
        val keys = api.get(GuardianPaths.devices(session.uid), session, "shallow=true").obj() ?: return emptyList()
        return keys.keySet().map { id ->
            val base = GuardianPaths.device(session.uid, id)
            fun text(field: String, fallback: String) = try {
                api.get("$base/$field", session)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
            } catch (_: Exception) { fallback }
            DeviceMeta(id, text("deviceName", "Child phone"), text("manufacturer", ""), 0L)
        }
    }

    /** Light-weight read of the real-time node (current app + the child's confirmation). */
    fun fetchRealtime(session: GuardianSession, deviceId: String): RealtimeSnapshot? {
        val rt = api.get(GuardianPaths.realtime(session.uid, deviceId), session).obj() ?: return null
        return RealtimeSnapshot(parseLive(rt.objectOrNull("live")), parseAck(rt.objectOrNull("ack")), rt.longOr("commsAt", 0L))
    }

    class RealtimeSnapshot(val live: LiveState?, val ack: AckState?, val commsAt: Long)

    class CommsFetch(val records: List<CommRecord>, val failedDays: Int, val lastError: Exception?)

    /** Message previews for the given day keys. One failing day does not hide the others. Newest first. */
    fun fetchComms(session: GuardianSession, deviceId: String, dayKeys: List<String>): CommsFetch {
        val out = ArrayList<CommRecord>()
        var failed = 0
        var last: Exception? = null
        for (day in dayKeys) {
            try {
                val node = api.get(GuardianPaths.commsDay(session.uid, deviceId, day), session).obj() ?: continue
                for ((id, value) in node.entrySet()) {
                    val o = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    CommCodec.fromJson(id, o)?.let { out.add(it) }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                last = e
            }
        }
        return CommsFetch(out.sortedByDescending { it.timeMs }, failed, last)
    }

    /** Counts failed reads so a single slow or missing node does not blank the whole screen. */
    private class Reads {
        var failures = 0
        var last: Exception? = null
        inline fun <T> tolerate(fallback: T, block: () -> T): T = try {
            block()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
            last = e
            fallback
        }
    }

    fun fetchDevice(session: GuardianSession, meta: DeviceMeta, selectedDay: String, previous: ChildDevice?): ChildDevice {
        val fid = session.uid
        val base = GuardianPaths.device(fid, meta.id)
        val today = DayClock.of(System.currentTimeMillis()).dayKey
        val reads = Reads()

        // Anything that fails keeps what the screen showed last time instead of failing the whole refresh.
        val info = reads.tolerate(previous?.info) { parseInfo(api.get("$base/info", session).obj()) }
        val rt = reads.tolerate(null as JsonObject?) { api.get("$base/rt", session).obj() }
        val status = reads.tolerate(null as JsonObject?) { api.get("$base/status", session).obj() }
        val failuresBeforeCatalog = reads.failures
        val catalogJson = reads.tolerate(null as JsonObject?) { api.get("$base/catalog", session).obj() }
        val catalogFailed = reads.failures > failuresBeforeCatalog
        val dailyJson = try {
            api.get("$base/daily", session, "orderBy=\"\$key\"&limitToLast=45").obj()
        } catch (_: Exception) { null }
        val hourlyJson = reads.tolerate(null as JsonElement?) { api.get("$base/hourly/$today", session) }
        val visitsJson = try {
            api.get("$base/browserHistory", session, "orderBy=\"\$key\"&limitToLast=120").obj()
        } catch (_: Exception) { null }
        val requestsJson = try {
            api.get("$base/requests", session, "orderBy=\"\$key\"&limitToLast=30").obj()
        } catch (_: Exception) { null }

        val sessions = LinkedHashMap<String, List<SessionRow>>()
        val dayKeys = linkedSetOf(today, selectedDay)
        previous?.sessions?.forEach { (k, v) -> if (k !in dayKeys) sessions[k] = v }
        dayKeys.forEach { key ->
            sessions[key] = reads.tolerate(previous?.sessions?.get(key) ?: emptyList()) {
                parseSessions(api.get(GuardianPaths.sessions(fid, meta.id, key), session).obj())
            }
        }

        val screenEvents = LinkedHashMap<String, List<ScreenEvent>>()
        previous?.screenEvents?.forEach { (k, v) -> if (k !in dayKeys) screenEvents[k] = v }
        dayKeys.forEach { key ->
            screenEvents[key] = try {
                parseScreen(api.get(GuardianPaths.screen(fid, meta.id, key), session).obj())
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (_: Exception) {
                previous?.screenEvents?.get(key) ?: emptyList()   // optional data: never counts as a failed refresh
            }
        }

        val previousApps = previous?.apps ?: emptyList()
        val apps = if (catalogFailed && previousApps.isNotEmpty()) previousApps else parseCatalog(catalogJson).ifEmpty {
            reads.tolerate(previousApps) { parseLegacyApps(api.get("$base/apps/installed", session)) }
        }
        val daily = if (dailyJson != null) parseDaily(dailyJson) else previous?.daily ?: emptyList()
        val todayApps = daily.firstOrNull { it.dayKey == today }?.apps ?: emptyMap()
        val todaySec = todayApps.ifEmpty {
            reads.tolerate(previous?.todaySec ?: emptyMap()) { parseLegacyUsage(api.get("$base/usage/today", session)) }
        }
        val hourly = (hourlyJson as? JsonArray)?.map { it.asIntSafe() }?.let { list ->
            List(24) { list.getOrNull(it) ?: 0 }
        } ?: previous?.hourlyToday ?: List(24) { 0 }

        // Many reads failing at once means the connection (or sign-in) is really gone: report it.
        if (reads.failures >= 4) throw reads.last ?: IllegalStateException("Could not load the child's data")

        return ChildDevice(
            id = meta.id,
            name = meta.name,
            manufacturer = meta.manufacturer,
            lastSeen = status?.longOr("lastSeen", meta.lastSeen) ?: previous?.lastSeen ?: meta.lastSeen,
            apps = apps,
            info = info,
            live = if (rt != null) parseLive(rt.objectOrNull("live")) else previous?.live,
            ack = if (rt != null) parseAck(rt.objectOrNull("ack")) else previous?.ack,
            todaySec = todaySec,
            hourlyToday = hourly,
            sessions = sessions,
            daily = daily,
            visits = if (visitsJson != null) parseVisits(visitsJson) else previous?.visits ?: emptyList(),
            requests = if (requestsJson != null) parseRequests(requestsJson) else previous?.requests ?: emptyList(),
            hasMonitor = info != null,
            commsAt = rt?.longOr("commsAt", 0L) ?: previous?.commsAt ?: 0L,
            screenEvents = screenEvents
        )
    }

    fun fetchIcons(session: GuardianSession, deviceId: String): Map<String, String> {
        val json = api.get(GuardianPaths.icons(session.uid, deviceId), session).obj() ?: return emptyMap()
        return json.entrySet().mapNotNull { (key, value) ->
            val text = try { value.takeIf { it.isJsonPrimitive }?.asString } catch (_: Exception) { null }
            if (text.isNullOrBlank()) null else PkgKey.decode(key) to text
        }.toMap()
    }

    // ------------------------------------------------------------ writing

    fun patchPolicy(session: GuardianSession, update: JsonObject) =
        api.patch(GuardianPaths.policy(session.uid), update, session)

    fun setRequestStatus(session: GuardianSession, deviceId: String, requestId: String, status: String) =
        api.put(GuardianPaths.device(session.uid, deviceId) + "/requests/$requestId/status", com.google.gson.JsonPrimitive(status), session)

    fun createPairingCode(session: GuardianSession): Pair<String, Long> {
        val code = (10000000 + java.util.Random().nextInt(90000000)).toString()
        val expires = System.currentTimeMillis() + 15L * 60L * 1000L
        api.put("pairingCodes/$code", JsonObject().apply {
            addProperty("familyId", session.uid)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("expiresAt", expires)
        }, session)
        return code to expires
    }

    // ------------------------------------------------------------ cache

    private class CacheFile(val schema: Int = 0, val savedAt: Long = 0L, val devices: List<ChildDevice> = emptyList(), val policy: String = "{}")

    fun saveCache(devices: List<ChildDevice>, policy: JsonObject?) {
        try {
            cacheFile.writeText(gson.toJson(CacheFile(SCHEMA, System.currentTimeMillis(), devices, policy?.toString() ?: "{}")))
        } catch (_: Exception) { }
    }

    fun loadCache(): Pair<List<ChildDevice>, FamilyPolicy>? = try {
        if (!cacheFile.isFile) null else {
            val cache = gson.fromJson(cacheFile.readText(), CacheFile::class.java)
            if (cache == null || cache.schema != SCHEMA) null
            else cache.devices to PolicyCodec.parse(com.google.gson.JsonParser.parseString(cache.policy).asJsonObject)
        }
    } catch (_: Throwable) { null }

    fun saveIcons(icons: Map<String, String>) {
        try { iconFile.writeText(gson.toJson(icons)) } catch (_: Exception) { }
    }

    fun loadIcons(): Map<String, String> = try {
        if (!iconFile.isFile) emptyMap() else {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(iconFile.readText(), Map::class.java) as? Map<String, String>) ?: emptyMap()
        }
    } catch (_: Throwable) { emptyMap() }

    fun decodeIcon(base64: String): ImageBitmap? = try {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Throwable) { null }

    // ------------------------------------------------------------ parsing

    private fun JsonElement?.obj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonElement.asIntSafe(): Int = try { asInt } catch (_: Exception) { 0 }

    private fun parseLive(o: JsonObject?): LiveState? = o?.let {
        LiveState(
            pkg = it.stringOr("p"), name = it.stringOr("n"), sinceMs = it.longOr("since", 0L),
            usedSec = it.intOr("used", 0), totalSec = it.intOr("total", 0), blocked = it.boolOrFalse("blocked"),
            reason = it.stringOr("reason", "NONE"), screenOn = if (it.has("screen")) it.boolOrFalse("screen") else true,
            ts = it.longOr("ts", 0L)
        )
    }

    private fun parseAck(o: JsonObject?): AckState? = o?.let {
        AckState(
            policyUpdatedAt = it.longOr("policyUpdatedAt", 0L), appliedAt = it.longOr("appliedAt", 0L),
            mode = it.stringOr("mode", "none"), enforcing = it.boolOrFalse("enforcing"),
            missing = it.arrayOrNull("missing")?.mapNotNull { m -> try { m.asString } catch (_: Exception) { null } } ?: emptyList()
        )
    }

    private fun parseInfo(o: JsonObject?): DeviceInfo? {
        if (o == null) return null
        val perms = o.objectOrNull("perms")
        fun perm(name: String) = perms?.boolOrFalse(name) ?: false
        return DeviceInfo(
            ts = o.longOr("ts", 0L), model = o.stringOr("model"), manufacturer = o.stringOr("manufacturer"),
            androidVersion = o.stringOr("androidVersion"), battery = o.intOr("battery", -1),
            charging = o.boolOrFalse("charging"), storageTotalMb = o.longOr("storageTotalMb", 0L),
            storageFreeMb = o.longOr("storageFreeMb", 0L), ramTotalMb = o.longOr("ramTotalMb", 0L),
            ramFreeMb = o.longOr("ramFreeMb", 0L), network = o.stringOr("network", "unknown"),
            screenOn = o.boolOrFalse("screenOn"), uptimeMin = o.longOr("uptimeMin", 0L),
            autoTime = if (o.has("autoTime")) o.boolOrFalse("autoTime") else true,
            appVersion = o.stringOr("appVersion"), enforcement = o.stringOr("enforcement", "limited"),
            permUsage = perm("usage"), permOverlay = perm("overlay"), permNotifications = perm("notifications"),
            permBattery = perm("battery"), permAdmin = perm("admin"), permVpn = perm("vpn"), permOwner = perm("owner"),
            permNotifAccess = perm("notifAccess"), notifAccessKnown = perms?.has("notifAccess") == true,
            monitorRunning = o.boolOrFalse("monitor"), monitorKnown = o.has("monitor"), sdk = o.intOr("sdk", 0)
        )
    }

    private fun parseCatalog(o: JsonObject?): List<AppInfo> = o?.entrySet()?.mapNotNull { (key, value) ->
        val a = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val pkg = PkgKey.decode(key)
        AppInfo(pkg, a.stringOr("n", pkg), a.stringOr("c", "other"), a.boolOrFalse("s"), a.longOr("it", 0L), a.longOr("ut", 0L), a.stringOr("v"))
    }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun parseLegacyApps(e: JsonElement?): List<AppInfo> =
        (e as? JsonArray)?.mapNotNull { item ->
            val a = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val pkg = a.stringOr("packageName")
            if (pkg.isBlank()) null else AppInfo(pkg, a.stringOr("appName", pkg), "other", a.boolOrFalse("systemApp"), 0L, 0L, "")
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun parseLegacyUsage(e: JsonElement?): Map<String, Int> =
        (e as? JsonArray)?.mapNotNull { item ->
            val a = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val pkg = a.stringOr("packageName")
            if (pkg.isBlank()) null else pkg to (a.longOr("foregroundMs", 0L) / 1000L).toInt()
        }?.toMap() ?: emptyMap()

    private fun parseSessions(o: JsonObject?): List<SessionRow> = o?.entrySet()?.mapNotNull { (_, value) ->
        val s = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val start = s.longOr("s", 0L)
        val end = s.longOr("e", 0L)
        if (start <= 0L || end <= start) null else SessionRow(s.stringOr("p"), s.stringOr("n"), start, end)
    }?.sortedBy { it.start } ?: emptyList()

    private fun parseScreen(o: JsonObject?): List<ScreenEvent> = o?.entrySet()?.mapNotNull { (_, value) ->
        value.takeIf { it.isJsonObject }?.asJsonObject?.let { ScreenEventCodec.fromJson(it) }
    }?.sortedBy { it.timeMs } ?: emptyList()

    private fun parseDaily(o: JsonObject?): List<DayTotals> = o?.entrySet()?.mapNotNull { (day, value) ->
        val d = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val apps = d.objectOrNull("apps")?.entrySet()?.mapNotNull { (key, seconds) ->
            val sec = try { seconds.asInt } catch (_: Exception) { null }
            if (sec == null) null else PkgKey.decode(key) to sec
        }?.toMap() ?: emptyMap()
        DayTotals(day, d.intOr("total", apps.values.sum()), apps)
    }?.sortedBy { it.dayKey } ?: emptyList()

    private fun parseVisits(o: JsonObject?): List<WebVisit> = o?.entrySet()?.mapNotNull { (_, value) ->
        val v = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        WebVisit(v.stringOr("url"), v.stringOr("title"), v.longOr("startedAt", 0L), v.longOr("durationMs", 0L), v.stringOr("event") == "blocked_attempt")
    }?.sortedByDescending { it.startedAt } ?: emptyList()

    private fun parseRequests(o: JsonObject?): List<MoreTimeRequest> = o?.entrySet()?.mapNotNull { (id, value) ->
        val r = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        MoreTimeRequest(id, r.stringOr("p"), r.stringOr("n"), r.stringOr("reason"), r.longOr("ts", 0L), r.stringOr("status", "pending"))
    }?.sortedByDescending { it.ts } ?: emptyList()

    private companion object {
        const val SCHEMA = 3
    }
}
