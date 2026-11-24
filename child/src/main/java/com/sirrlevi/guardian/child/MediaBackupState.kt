package com.sirrlevi.guardian.child

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sirrlevi.guardian.core.MediaAccess
import com.sirrlevi.guardian.core.MediaStatusCodec
import com.sirrlevi.guardian.core.MediaStatusSnapshot

/** What the phone remembers about its photo backup between runs (never the pictures themselves). */
internal class MediaBackupState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("guardian_media", Context.MODE_PRIVATE)

    @Synchronized fun stored(): Set<String> = prefs.getStringSet("stored", emptySet())?.toHashSet() ?: HashSet()
    @Synchronized fun failed(): Set<String> = prefs.getStringSet("failed", emptySet())?.toHashSet() ?: HashSet()

    @Synchronized fun markStored(key: String) {
        prefs.edit().putStringSet("stored", stored() + key).putStringSet("failed", failed() - key).apply()
    }

    @Synchronized fun markFailed(key: String) {
        prefs.edit().putStringSet("failed", failed() + key).apply()
    }

    /** Forgets keys whose copies were removed (retention or parent deletion), so the sets cannot grow forever. */
    @Synchronized fun forget(keys: Collection<String>) {
        if (keys.isEmpty()) return
        prefs.edit().putStringSet("stored", stored() - keys.toSet()).putStringSet("failed", failed() - keys.toSet()).apply()
    }

    @Synchronized fun attempts(): Map<String, Int> {
        val o = try { JsonParser.parseString(prefs.getString("attempts", "{}")).asJsonObject } catch (_: Exception) { JsonObject() }
        return o.entrySet().associate { (k, v) -> k to (try { v.asInt } catch (_: Exception) { 0 }) }
    }

    /** Counts one failed try for [key] and returns how many there have been. */
    @Synchronized fun bumpAttempt(key: String): Int {
        val map = attempts().toMutableMap()
        val n = (map[key] ?: 0) + 1
        map[key] = n
        val o = JsonObject()
        // Keep the newest few hundred so this cannot grow without bound.
        map.entries.toList().takeLast(500).forEach { o.addProperty(it.key, it.value) }
        prefs.edit().putString("attempts", o.toString()).apply()
        return n
    }

    @Synchronized fun save(s: MediaStatusSnapshot) {
        prefs.edit().putString("status", MediaStatusCodec.toJson(s).toString()).putBoolean("enabled", s.enabled).apply()
    }

    /** Last settings the parent chose, as seen on the last run: used to decide whether a gallery change is worth a run. */
    fun lastKnownEnabled(): Boolean = prefs.getBoolean("enabled", false)

    fun status(): MediaStatusSnapshot? {
        val raw = prefs.getString("status", null) ?: return null
        return try { MediaStatusCodec.fromJson(JsonParser.parseString(raw).asJsonObject) } catch (_: Exception) { null }
    }

    /** What the Child app shows about the backup; permission is read live, the rest from the last run. */
    fun localStatus(context: Context): MediaStatusSnapshot {
        val live = ChildPermissions.mediaAccess(context)
        val saved = status()
        return saved?.copy(access = live) ?: MediaStatusSnapshot(live, false, 0, stored().size, failed().size, 0L, null, null)
    }
}
