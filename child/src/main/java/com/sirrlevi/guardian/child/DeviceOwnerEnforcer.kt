package com.sirrlevi.guardian.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/** Hard enforcement through Android's own app suspension. Only possible when Guardian Child is the Device Owner. */
internal object DeviceOwnerEnforcer {
    fun apply(context: Context, wanted: Set<String>) {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!manager.isDeviceOwnerApp(context.packageName)) return
        val prefs = context.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
        val desired = wanted - context.packageName
        val admin = ComponentName(context, GuardianAdminReceiver::class.java)
        val previous = prefs.getStringSet("suspended_packages", emptySet()).orEmpty()
        val toRelease = previous - desired
        val toSuspend = desired - previous
        if (toRelease.isNotEmpty()) manager.setPackagesSuspended(admin, toRelease.toTypedArray(), false)
        if (toSuspend.isNotEmpty()) manager.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
        prefs.edit().putStringSet("suspended_packages", desired).apply()
    }
}
