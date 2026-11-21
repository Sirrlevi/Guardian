package com.sirrlevi.guardian.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChildSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val prefs = applicationContext.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
    private val vault = SessionVault(applicationContext)
    private val api = GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!api.isConfigured()) return@withContext Result.retry()
            var session = vault.load() ?: return@withContext Result.failure()
            if (session.expiresAtMs < System.currentTimeMillis() + 120_000L) {
                session = api.refresh(session)
                vault.save(session)
            }
            val familyId = prefs.getString("family_id", "").orEmpty()
            if (familyId.isBlank()) return@withContext Result.failure()

            val policy = api.get("families/" + familyId + "/policies", session)?.asJsonObject ?: JsonObject()
            val domains = policy.arrayStrings("blockedDomains")
            val urls = policy.arrayStrings("blockedUrls")
            val blockedPackages = policy.arrayStrings("blockedPackages")
            prefs.edit()
                .putString("blocked_domains", Gson().toJson(domains))
                .putString("blocked_urls", Gson().toJson(urls))
                .apply()

            val now = System.currentTimeMillis()
            val today = UsageMonitor.collect(applicationContext, UsageMonitor.dayStartMillis())
            val week = UsageMonitor.collect(applicationContext, now - 7L * 24 * 60 * 60 * 1000)
            val month = UsageMonitor.collect(applicationContext, now - 30L * 24 * 60 * 60 * 1000)
            val recentActivities = UsageMonitor.recentForegroundEvents(applicationContext, 40)
            val installedApps = InstalledAppsCollector.collect(applicationContext)
            applyAppPolicy(policy, blockedPackages, today)

            val manager = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val lastForeground = UsageMonitor.lastForegroundEvent(applicationContext)
            val status = JsonObject().apply {
                addProperty("lastSeen", now)
                addProperty("vpnSupported", true)
                addProperty("deviceOwner", manager.isDeviceOwnerApp(applicationContext.packageName))
                addProperty("usageAccessGranted", hasUsageAccess())
                addProperty("screenTimeTodayMs", totalForeground(today))
                addProperty("screenTimeWeeklyAverageMs", totalForeground(week) / 7L)
                addProperty("screenTimeMonthlyAverageMs", totalForeground(month) / 30L)
                if (lastForeground != null) {
                    addProperty("lastForegroundPackage", lastForeground.first)
                    addProperty("lastForegroundAt", lastForeground.second)
                }
                addProperty("androidVersion", android.os.Build.VERSION.RELEASE ?: "unknown")
            }
            val devicePath = "families/" + familyId + "/devices/" + session.uid
            api.put(devicePath + "/status", status, session)
            api.put(devicePath + "/usage/today", today, session)
            api.put(devicePath + "/usage/week", week, session)
            api.put(devicePath + "/usage/month", month, session)
            api.put(devicePath + "/recentActivities", recentActivities, session)
            api.put(devicePath + "/apps/installed", installedApps, session)
            prefs.edit().putLong("last_sync", now).apply()
            Result.success()
        } catch (e: Exception) {
            Log.w("GuardianSync", "Child sync failed", e)
            Result.retry()
        }
    }

    private fun totalForeground(report: JsonArray): Long =
        report.sumOf { it.asJsonObject.get("foregroundMs")?.asLong ?: 0L }

    private fun applyAppPolicy(policy: JsonObject, blocked: List<String>, usage: JsonArray) {
        val manager = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!manager.isDeviceOwnerApp(applicationContext.packageName)) return
        val desired = blocked.toMutableSet()
        val limits = policy.getAsJsonObject("appTimeLimitsMinutes")
        if (limits != null) {
            for ((packageName, limitElement) in limits.entrySet()) {
                val minutes = try { limitElement.asInt } catch (_: Exception) { 0 }
                if (minutes > 0 && UsageMonitor.foregroundMillis(packageName, usage) >= minutes * 60_000L) {
                    desired.add(packageName)
                }
            }
        }
        desired.remove(applicationContext.packageName)
        val admin = ComponentName(applicationContext, GuardianAdminReceiver::class.java)
        val previous = prefs.getStringSet("suspended_packages", emptySet()).orEmpty()
        val toRelease = previous - desired
        val toSuspend = desired - previous
        if (toRelease.isNotEmpty()) manager.setPackagesSuspended(admin, toRelease.toTypedArray(), false)
        if (toSuspend.isNotEmpty()) manager.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
        prefs.edit().putStringSet("suspended_packages", desired).apply()
    }

    private fun hasUsageAccess(): Boolean = try {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            applicationContext.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun JsonObject.arrayStrings(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { try { it.asString.trim().takeIf(String::isNotBlank) } catch (_: Exception) { null } }
    }
}
