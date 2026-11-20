package com.sirrlevi.guardian.child

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object InstalledAppsCollector {
    fun collect(context: Context): JsonArray {
        val manager = context.packageManager
        return JsonArray().apply {
            manager.getInstalledApplications(0)
                .sortedBy { it.packageName.lowercase() }
                .forEach { info ->
                    val label = try { manager.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
                    add(JsonObject().apply {
                        addProperty("packageName", info.packageName)
                        addProperty("appName", label)
                        addProperty("systemApp", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        addProperty("enabled", info.enabled)
                    })
                }
        }
    }
}
