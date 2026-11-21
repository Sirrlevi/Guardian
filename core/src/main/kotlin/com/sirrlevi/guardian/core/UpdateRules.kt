package com.sirrlevi.guardian.core

import java.util.Locale

/**
 * Pure (Android-free) rules used by the in-app updaters of Guardian Parent and Guardian Child.
 * Kept in :core so they can be unit-tested on CI without an emulator.
 */
object UpdateRules {
    const val PARENT_PACKAGE = "com.sirrlevi.guardian.parent"
    const val CHILD_PACKAGE = "com.sirrlevi.guardian.child"

    /** Name of the Release asset that belongs to the given installed package, or null if unsupported. */
    fun assetNameFor(packageName: String): String? = when (packageName) {
        PARENT_PACKAGE -> "Guardian-Parent.apk"
        CHILD_PACKAGE -> "Guardian-Child.apk"
        else -> null
    }

    /** Release tags look like `guardian-v1.0.42`; the trailing number is the Android versionCode. */
    fun versionFromTag(tag: String): Int? =
        Regex("""(\d+)\s*$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(latest: Int, installed: Int): Boolean = latest > installed

    fun percent(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done * 100L) / total).coerceIn(0L, 100L).toInt()

    fun formatSize(bytes: Long): String = when {
        bytes < 0L -> "?"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Human-readable explanation for a failed GitHub API call. */
    fun explainHttpFailure(code: Int): String = when (code) {
        401 -> "The GitHub token is invalid or expired. Save a new fine-grained token."
        403 -> "GitHub refused the request. Check that the token has Contents: Read-only access, or wait a few minutes if GitHub is rate-limiting."
        404 -> "No release was found, or the token cannot see the Guardian repository."
        else -> "GitHub returned HTTP $code."
    }
}
