package com.sirrlevi.guardian.child

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.os.StatFs
import com.google.gson.JsonObject
import java.util.Locale
import java.util.TimeZone

/** Everyday device facts the parent sees on the dashboard. Nothing here is personal content. */
internal object DeviceInfoCollector {
    fun collect(context: Context, screenOn: Boolean, monitorRunning: Boolean): JsonObject {
        val info = JsonObject()
        info.addProperty("ts", System.currentTimeMillis())
        info.addProperty("model", Build.MODEL ?: "Android")
        info.addProperty("manufacturer", Build.MANUFACTURER ?: "")
        info.addProperty("androidVersion", Build.VERSION.RELEASE ?: "")
        info.addProperty("sdk", Build.VERSION.SDK_INT)
        info.addProperty("patch", Build.VERSION.SECURITY_PATCH ?: "")
        info.addProperty("appVersion", BuildConfig.VERSION_NAME)
        info.addProperty("appCode", BuildConfig.VERSION_CODE)
        info.addProperty("screenOn", screenOn)
        info.addProperty("monitor", monitorRunning)
        info.addProperty("uptimeMin", SystemClock.elapsedRealtime() / 60_000L)
        info.addProperty("timezone", TimeZone.getDefault().id)
        info.addProperty("locale", Locale.getDefault().toLanguageTag())
        info.addProperty("autoTime", try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
        } catch (_: Exception) { true })

        try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) info.addProperty("battery", level * 100 / scale)
                val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                info.addProperty("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
        } catch (_: Exception) { }

        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            info.addProperty("storageTotalMb", stat.totalBytes / 1_048_576L)
            info.addProperty("storageFreeMb", stat.availableBytes / 1_048_576L)
        } catch (_: Exception) { }

        try {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            info.addProperty("ramTotalMb", memory.totalMem / 1_048_576L)
            info.addProperty("ramFreeMb", memory.availMem / 1_048_576L)
        } catch (_: Exception) { }

        info.addProperty("network", networkType(context))

        info.add("perms", JsonObject().apply {
            addProperty("usage", ChildPermissions.hasUsageAccess(context))
            addProperty("overlay", ChildPermissions.hasOverlay(context))
            addProperty("notifications", ChildPermissions.hasNotifications(context))
            addProperty("battery", ChildPermissions.ignoresBatteryOptimization(context))
            addProperty("admin", ChildPermissions.isAdminActive(context))
            addProperty("vpn", ChildPermissions.isVpnReady(context))
            addProperty("owner", ChildPermissions.isDeviceOwner(context))
        })
        info.addProperty(
            "enforcement",
            when {
                ChildPermissions.isDeviceOwner(context) -> "device-owner"
                ChildPermissions.canEnforce(context) -> "overlay"
                else -> "limited"
            }
        )
        return info
    }

    private fun networkType(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    } catch (_: Exception) { "unknown" }
}
