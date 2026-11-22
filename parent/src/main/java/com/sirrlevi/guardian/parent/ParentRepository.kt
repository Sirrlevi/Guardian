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
    fun fetchRealtime(session: GuardianSession, deviceId: String): Pair<LiveState?, AckState?> {
        val rt = api.get(GuardianPaths.realtime(session.uid, deviceId), session).obj() ?: return null to null
        return parseLive(rt.objectOrNull("live")) to parseAck(rt.objectOrNull("ack"))
    }

    fun fetchDevice(session: GuardianSession, meta: DeviceMeta, selectedDay: String, previous: ChildDevice?): ChildDevice {
        val fid = session.uid
        val base = GuardianPaths.device(fid, meta.id)
        val today = DayClock.of(System.currentTimeMillis()).dayKey

        val info = parseInfo(api.get("$base/info", session).obj())
        val rt = api.get("$base/rt", session).obj()
        val status = api.get("$base/status", session).obj()
        val catalogJson = api.get("$base/catalog", session).obj()
        val dailyJson = try {
            api.get("$base/daily", session, "orderBy=\"\$key\"&limitToLast=45").obj()
        } catch (_: Exception) { null }
        val hourlyJson = api.get("$base/hourly/$today", session)
        val visitsJson = try {
            api.get("$base/browserHistory", session, "orderBy=\"\$key\"&limitToLast=120").obj()
        } catch (_: Exception) { null }
        val requestsJson = try {
            api.get("$base/requests", session, "orderBy=\"\$key\"&limitToLast=30").obj()
        } catch (_: Exception) { null }

        val sessions = LinkedHashMap<String, List<SessionRow>>()
        val dayKeys = linkedSetOf(today, selectedDay)
        previous?.sessions?.forEach { (k, v) -> if (k !in dayKeys) sessions[k] = v }
        dayKeys.forEach { key -> sessions[key] = parseSessions(api.get(GuardianPaths.sessions(fid, meta.id, key), session).obj()) }

        val apps = parseCatalog(catalogJson).ifEmpty {
            parseLegacyApps(api.get("$base/apps/installed", session))
        }
        val daily = parseDaily(dailyJson)
        val todayApps = daily.firstOrNull { it.dayKey == today }?.apps ?: emptyMap()
        val todaySec = todayApps.ifEmpty { parseLegacyUsage(api.get("$base/usage/today", session)) }
        val hourly = (hourlyJson as? JsonArray)?.map { it.asIntSafe() }?.let { list ->
            List(24) { list.getOrNull(it) ?: 0 }
        } ?: List(24) { 0 }

        return ChildDevice(
            id = meta.id,
            name = meta.name,
            manufacturer = meta.manufacturer,
            lastSeen = status?.longOr("lastSeen", meta.lastSeen) ?: meta.lastSeen,
            apps = apps,
            info = info,
            live = parseLive(rt?.objectOrNull("live")),
            ack = parseAck(rt?.objectOrNull("ack")),
            todaySec = todaySec,
            hourlyToday = hourly,
            sessions = sessions,
            daily = daily,
            visits = parseVisits(visitsJson),
            requests = parseRequests(requestsJson),
            hasMonitor = info != null
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
            permBattery = perm("battery"), permAdmin = perm("admin"), permVpn = perm("vpn"), permOwner = perm("owner")
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
        const val SCHEMA = 2
    }
}
