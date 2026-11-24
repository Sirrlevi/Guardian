package com.sirrlevi.guardian.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Enforcement
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.PolicyCodec
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
            val parsedPolicy = PolicyCodec.parse(policy)
            prefs.edit()
                .putString("blocked_domains", Gson().toJson(domains))
                .putString("blocked_urls", Gson().toJson(urls))
                .putString("policy_json", policy.toString())
                .apply()

            val now = System.currentTimeMillis()
            val today = UsageMonitor.collect(applicationContext, UsageMonitor.dayStartMillis())
            val week = UsageMonitor.collect(applicationContext, now - 7L * 24 * 60 * 60 * 1000)
            val month = UsageMonitor.collect(applicationContext, now - 30L * 24 * 60 * 60 * 1000)
            val recentActivities = UsageMonitor.recentForegroundEvents(applicationContext, 40)
            val installedApps = InstalledAppsCollector.collect(applicationContext)
            applyAppPolicy(parsedPolicy, today)
            try { AppCatalog.sync(applicationContext, ChildCloud(applicationContext)) } catch (e: Exception) { Log.w("GuardianSync", "catalog sync failed", e) }
            GuardianMonitorService.startIfReady(applicationContext)

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
                addProperty("monitorRunning", GuardianMonitorService.running)
            }
            val devicePath = "families/" + familyId + "/devices/" + session.uid
            api.put(devicePath + "/status", status, session)
            api.put(devicePath + "/usage/today", today, session)
            api.put(devicePath + "/usage/week", week, session)
            api.put(devicePath + "/usage/month", month, session)
            api.put(devicePath + "/recentActivities", recentActivities, session)
            api.put(devicePath + "/apps/installed", installedApps, session)
            prefs.edit().putLong("last_sync", now).apply()
            MediaBackupScheduler.schedule(applicationContext)
            try { CommsUploader.flush(applicationContext) } catch (e: Exception) { Log.w("GuardianSync", "comms flush failed", e) }
            Result.success()
        } catch (e: Exception) {
            Log.w("GuardianSync", "Child sync failed", e)
            Result.retry()
        }
    }

    private fun totalForeground(report: JsonArray): Long =
        report.sumOf { it.asJsonObject.get("foregroundMs")?.asLong ?: 0L }

    /** Device Owner suspension, decided with the same engine the live monitor uses. */
    private fun applyAppPolicy(policy: com.sirrlevi.guardian.core.FamilyPolicy, usage: JsonArray) {
        if (!ChildPermissions.isDeviceOwner(applicationContext)) return
        val now = System.currentTimeMillis()
        val clock = DayClock.of(now)
        val exempt = setOf(applicationContext.packageName)
        val desired = HashSet<String>()
        for (packageName in policy.appRules.keys) {
            val used = (UsageMonitor.foregroundMillis(packageName, usage) / 1000L).toInt()
            if (Enforcement.decide(packageName, policy, used, now, clock, exempt).blocked) desired.add(packageName)
        }
        DeviceOwnerEnforcer.apply(applicationContext, desired)
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
