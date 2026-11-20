package com.sirrlevi.guardian.child

import android.app.usage.UsageStatsManager
import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object UsageMonitor {
    fun collect(context: Context, fromMillis: Long): JsonArray {
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, fromMillis, now).orEmpty()
        val packages = context.packageManager
        val output = JsonArray()
        stats.groupBy { it.packageName }.map { (packageName, entries) ->
            packageName to Pair(entries.sumOf { it.totalTimeInForeground }, entries.maxOfOrNull { it.lastTimeUsed } ?: 0L)
        }.filter { it.second.first > 0L }.sortedByDescending { it.second.first }.forEach { (packageName, totals) ->
            val label = try {
                @Suppress("DEPRECATION")
                val info = packages.getApplicationInfo(packageName, 0)
                packages.getApplicationLabel(info).toString()
            } catch (_: Exception) { packageName }
            output.add(JsonObject().apply {
                addProperty("packageName", packageName)
                addProperty("appName", label)
                addProperty("foregroundMs", totals.first)
                addProperty("lastTimeUsed", totals.second)
            })
        }
        return output
    }

    fun lastForegroundEvent(context: Context): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(now - 24L * 60 * 60 * 1000, now) ?: return null
        val event = android.app.usage.UsageEvents.Event()
        var last: Pair<String, Long>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName to event.timeStamp
            }
        }
        return last
    }

    fun foregroundMillis(packageName: String, report: JsonArray): Long =
        report.firstOrNull { it.asJsonObject.get("packageName")?.asString == packageName }
            ?.asJsonObject?.get("foregroundMs")?.asLong ?: 0L

    fun dayStartMillis(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
