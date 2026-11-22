package com.sirrlevi.guardian.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Locale

/** A release that can actually be installed by one of the two apps. */
data class ReleaseCandidate(
    val tag: String,
    val versionCode: Int,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val assetId: Long,
    val assetName: String,
    val assetSize: Long
)

/**
 * The downloaded update cannot be installed over the app that is on this phone
 * (different signing key). [downloadUrl] lets the user fetch the APK in a browser instead.
 */
class UpdateBlockedException(message: String, val downloadUrl: String) : IOException(message)

/**
 * Pure (Android-free) rules used by the in-app updaters of Guardian Parent and Guardian Child.
 * Kept in :core so they can be unit-tested on CI without an emulator.
 */
object UpdateRules {
    const val PARENT_PACKAGE = "com.sirrlevi.guardian.parent"
    const val CHILD_PACKAGE = "com.sirrlevi.guardian.child"
    const val REPOSITORY = "Sirrlevi/Guardian"

    const val NO_RELEASE_MESSAGE =
        "No installable Guardian release has been published yet. Updates show up here as soon as a signed build is released."

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

    /** Direct browser download link for a release asset (works without a token on a public repository). */
    fun directDownloadUrl(tag: String, assetName: String): String =
        "https://github.com/$REPOSITORY/releases/download/$tag/$assetName"

    /**
     * Chooses the newest release that really contains [assetName].
     *
     * GitHub's `releases/latest` only returns one release, which may be an old or test release without the APK
     * (that was the "has no Guardian-Parent.apk" error). Looking through the list instead skips drafts,
     * pre-releases (debug test builds) and releases that do not carry this app's APK.
     *
     * @throws IllegalArgumentException when GitHub's answer is not a release list.
     */
    fun pickRelease(releasesJson: String, assetName: String): ReleaseCandidate? {
        val root = try {
            JsonParser.parseString(releasesJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("GitHub sent an answer that is not valid JSON.")
        }
        if (!root.isJsonArray) throw IllegalArgumentException("GitHub did not return a list of releases.")
        return (root as JsonArray)
            .filter { it.isJsonObject }
            .map { it.asJsonObject }
            .mapNotNull { candidateOf(it, assetName) }
            .maxByOrNull { it.versionCode }
    }

    private fun candidateOf(release: JsonObject, assetName: String): ReleaseCandidate? {
        if (release.bool("draft") || release.bool("prerelease")) return null
        val tag = release.text("tag_name")
        val version = versionFromTag(tag) ?: return null
        val asset = release.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }
            ?.firstOrNull { it.text("name") == assetName }
            ?: return null
        val id = asset.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        return ReleaseCandidate(
            tag = tag,
            versionCode = version,
            title = release.text("name").ifBlank { tag },
            notes = release.text("body"),
            pageUrl = release.text("html_url"),
            assetId = id,
            assetName = assetName,
            assetSize = asset.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        )
    }

    private fun JsonObject.text(name: String): String =
        try { get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty() } catch (_: Exception) { "" }

    private fun JsonObject.bool(name: String): Boolean =
        try { get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false } catch (_: Exception) { false }

    /**
     * Android only installs an update over an app signed with the same certificate.
     * Both sets hold lowercase/uppercase hex SHA-256 digests of the signing certificates.
     * An empty set means "could not be read", which is treated as compatible so Android itself decides.
     */
    fun sameSigners(installed: Set<String>, candidate: Set<String>): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return true
        return installed.map { it.lowercase(Locale.US) }.toSet() == candidate.map { it.lowercase(Locale.US) }.toSet()
    }

    fun signatureMismatchMessage(): String =
        "This update is signed with a different key than the Guardian on this phone, so Android will not install it over the top. " +
            "Do this once: (1) download the APK in your browser, (2) uninstall this app, (3) install the downloaded APK. " +
            "After that, every update installs in place and keeps your data."

    fun debugBuildMessage(): String =
        "This phone runs a test (debug) build. Its signature changes with every build, so it can never be updated in place. " +
            "Install the signed release once (download it in your browser, uninstall this app, install the APK). " +
            "After that, updates install in place and keep your data."

    fun percent(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done * 100L) / total).coerceIn(0L, 100L).toInt()

    fun formatSize(bytes: Long): String = when {
        bytes < 0L -> "?"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Human-readable explanation for a failed GitHub API call. */
    fun explainHttpFailure(code: Int, hadToken: Boolean = false): String = when (code) {
        401 -> "The saved GitHub token is invalid or expired. Save a new fine-grained token, or leave it empty if the repository is public."
        403 -> "GitHub refused the request, usually because this network hit GitHub's anonymous rate limit. Wait a few minutes, or save a fine-grained token (Contents: Read-only)."
        404 -> if (hadToken) {
            "GitHub could not find the Guardian releases with the saved token. Check that the token can read Sirrlevi/Guardian."
        } else {
            "No release was found. If the Guardian repository is private, save a fine-grained token with Contents: Read-only."
        }
        else -> "GitHub returned HTTP $code."
    }
}
