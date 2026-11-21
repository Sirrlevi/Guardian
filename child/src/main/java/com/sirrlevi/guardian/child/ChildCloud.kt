package com.sirrlevi.guardian.child

import android.content.Context
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianSession

/** Shared, thread-safe access to the child's Firebase session (refreshed automatically). */
internal class ChildCloud(context: Context) {
    private val app = context.applicationContext
    val api = GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL)
    private val vault = SessionVault(app)
    private val prefs = app.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
    @Volatile private var cached: GuardianSession? = null

    val familyId: String get() = prefs.getString("family_id", "").orEmpty()

    @Synchronized
    fun session(): GuardianSession? {
        var current = cached ?: vault.load() ?: return null
        if (current.expiresAtMs < System.currentTimeMillis() + 120_000L) {
            current = api.refresh(current)
            vault.save(current)
        }
        cached = current
        return current
    }

    fun isPaired(): Boolean = familyId.isNotBlank() && api.isConfigured() && vault.load() != null
}
