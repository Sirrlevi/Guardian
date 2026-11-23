package com.sirrlevi.guardian.child

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sirrlevi.guardian.core.CommCodec
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.DayClock
import java.io.File

/**
 * Notification previews waiting to be uploaded. They live in the app's private storage only until the
 * upload succeeds (or the cap is reached), so a phone that is offline for a while loses nothing recent.
 */
internal class CommsQueue(context: Context) {
    private val file = File(context.applicationContext.filesDir, "comms_queue.jsonl")

    data class Entry(val id: String, val day: String, val record: JsonObject)

    @Synchronized
    fun add(records: List<CommRecord>): Int {
        if (records.isEmpty()) return 0
        val existing = readAll()
        val have = existing.mapTo(HashSet()) { it.id }
        val fresh = records.filter { have.add(it.id) }
        if (fresh.isEmpty()) return 0
        val all = existing + fresh.map { Entry(it.id, DayClock.of(it.timeMs).dayKey, CommCodec.toJson(it)) }
        writeAll(all.takeLast(MAX_PENDING))
        return fresh.size
    }

    @Synchronized
    fun peek(limit: Int): List<Entry> = readAll().take(limit)

    @Synchronized
    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val drop = ids.toHashSet()
        writeAll(readAll().filter { it.id !in drop })
    }

    @Synchronized
    fun size(): Int = readAll().size

    private fun readAll(): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Entry>()
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val o = JsonParser.parseString(line).asJsonObject
                    out.add(Entry(o.get("id").asString, o.get("day").asString, o.getAsJsonObject("rec")))
                } catch (_: Exception) { /* a torn line is dropped, the rest survives */ }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun writeAll(entries: List<Entry>) {
        try {
            if (entries.isEmpty()) { file.delete(); return }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for (e in entries) {
                    val o = JsonObject()
                    o.addProperty("id", e.id)
                    o.addProperty("day", e.day)
                    o.add("rec", e.record)
                    w.write(o.toString()); w.newLine()
                }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { }
    }

    companion object {
        /** Oldest previews are dropped first if the phone stays offline long enough to reach this. */
        const val MAX_PENDING = 1000
    }
}
