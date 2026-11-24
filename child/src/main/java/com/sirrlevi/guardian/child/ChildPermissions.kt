package com.sirrlevi.guardian.child

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** One place that knows which Android permissions Guardian Child needs and how to ask for each. */
object ChildPermissions {
    data class Item(val id: String, val title: String, val why: String, val granted: Boolean, val required: Boolean)

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True when Android lists Guardian among the apps allowed to read notification previews. */
    fun hasNotificationAccess(context: Context): Boolean = try {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Exception) { false }

    /**
     * How much of the gallery Android lets Guardian see. Android 14+ can share just the photos the child picked
     * ("selected photos"), which shows up as partial access; Guardian never works around that.
     */
    fun mediaAccess(context: Context): com.sirrlevi.guardian.core.MediaAccess {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> com.sirrlevi.guardian.core.MediaAccess.FULL
                Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> com.sirrlevi.guardian.core.MediaAccess.PARTIAL
                else -> com.sirrlevi.guardian.core.MediaAccess.NONE
            }
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> com.sirrlevi.guardian.core.MediaAccess.FULL
            else -> com.sirrlevi.guardian.core.MediaAccess.NONE
        }
    }

    fun mediaPermissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun ignoresBatteryOptimization(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) { false }

    fun isDeviceOwner(context: Context): Boolean = try {
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName)
    } catch (_: Exception) { false }

    fun isAdminActive(context: Context): Boolean = try {
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(ComponentName(context, GuardianAdminReceiver::class.java))
    } catch (_: Exception) { false }

    fun isVpnReady(context: Context): Boolean = try { VpnService.prepare(context) == null } catch (_: Exception) { false }

    /** True when Guardian can actually stop a blocked app (either by overlay or as Device Owner). */
    fun canEnforce(context: Context): Boolean = hasUsageAccess(context) && (hasOverlay(context) || isDeviceOwner(context))

    fun items(context: Context): List<Item> = listOf(
        Item("usage", "Usage access", "Lets Guardian see which app is open and for how long. Without it nothing can be reported or limited.",
            hasUsageAccess(context), true),
        Item("overlay", "Display over other apps", "Shows the \"time's up\" screen on top of a blocked app.",
            hasOverlay(context), true),
        Item("battery", "Run without battery limits", "Stops Android from putting Guardian to sleep, so limits keep working all day.",
            ignoresBatteryOptimization(context), false),
        Item("notifications", "Notifications", "Shows the always-on \"protection active\" notice and update alerts.",
            hasNotifications(context), false),
        Item("admin", "Uninstall protection", "Makes Android ask for confirmation before Guardian can be removed.",
            isAdminActive(context), false),
        Item("vpn", "Website filter (VPN)", "Blocks the websites your parent chose. Android shows a visible key icon while it is on.",
            isVpnReady(context), false),
        Item("notifaccess", "Message previews (optional)",
            "Lets Guardian share the preview text that chat notifications (WhatsApp, Instagram, SMS…) already show on this phone with your parent. It never sees whole chats, and you can turn it off here any time.",
            hasNotificationAccess(context), false),
        mediaItem(context)
    )

    private fun mediaItem(context: Context): Item {
        val access = mediaAccess(context)
        val status = when (access) {
            com.sirrlevi.guardian.core.MediaAccess.FULL -> " Right now: all photos and videos are visible to Guardian."
            com.sirrlevi.guardian.core.MediaAccess.PARTIAL -> " Right now: only the photos you chose are visible to Guardian."
            com.sirrlevi.guardian.core.MediaAccess.NONE -> ""
        }
        return Item("media", "Photos & videos (optional)",
            "Only if your parent turns on photo backup: Guardian sends smaller preview copies of new photos and videos it is allowed to see to your parent. Originals stay on this phone. You decide what Android shares, and you can stop any time in Android settings.$status",
            access != com.sirrlevi.guardian.core.MediaAccess.NONE, false)
    }

    fun settingsIntent(context: Context, id: String): Intent? = when (id) {
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "notifaccess" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "media" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        "admin" -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, GuardianAdminReceiver::class.java))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Guardian asks for confirmation before it can be removed from this device.")
        else -> null
    }
}
