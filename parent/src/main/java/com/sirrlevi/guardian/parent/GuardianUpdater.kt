package com.sirrlevi.guardian.parent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sirrlevi.guardian.core.UpdateBlockedException
import com.sirrlevi.guardian.core.UpdateRules
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class GuardianRelease(
    val versionCode: Int,
    val tagName: String,
    val title: String,
    val notes: String,
    val assetId: Long,
    val assetName: String,
    val assetSize: Long,
    val pageUrl: String
)

data class UpdateCheckResult(
    val currentVersion: Int,
    val release: GuardianRelease?,
    val updateAvailable: Boolean,
    val message: String,
    /** True when this phone runs a debug build: Android cannot update it in place, a one-time reinstall is needed. */
    val needsReinstall: Boolean = false,
    /** Direct browser link to the APK, used for the one-time reinstall. */
    val reinstallUrl: String = ""
)

/** A GitHub answer with a non-2xx status; turned into a readable message by [GuardianUpdater]. */
private class HttpFailure(val code: Int) : IOException("HTTP $code")

class GuardianUpdater(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("guardian_updates", Context.MODE_PRIVATE)
    private val keyAlias = "guardian_update_token_key"
    private val apiBase = "https://api.github.com/repos/" + UpdateRules.REPOSITORY

    /** The token is optional: the repository is public, so updates work without one. */
    fun hasToken(): Boolean = !readToken().isNullOrBlank()

    fun saveToken(raw: String) {
        val token = raw.trim()
        require(token.length >= 30) { "That does not look like a GitHub fine-grained token." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(
            "github_token",
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        ).apply()
    }

    fun checkForUpdate(): UpdateCheckResult {
        val current = currentVersionCode()
        val expectedAsset = UpdateRules.assetNameFor(app.packageName)
            ?: throw IllegalStateException("This Guardian package is not supported by the updater.")

        // Look through the release list instead of only `releases/latest`: that single release may be an
        // old or test release without this app's APK.
        val picked = try {
            UpdateRules.pickRelease(withToken { token -> requestText("$apiBase/releases?per_page=30", token) }, expectedAsset)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "GitHub sent an unexpected answer.")
        }
        prefs.edit().putLong("last_checked_at", System.currentTimeMillis()).apply()
        if (picked == null) {
            cleanupDownloads()
            return UpdateCheckResult(current, null, false, UpdateRules.NO_RELEASE_MESSAGE)
        }

        val release = GuardianRelease(
            versionCode = picked.versionCode,
            tagName = picked.tag,
            title = picked.title,
            notes = picked.notes,
            assetId = picked.assetId,
            assetName = picked.assetName,
            assetSize = picked.assetSize,
            pageUrl = picked.pageUrl
        )
        val available = UpdateRules.isNewer(release.versionCode, current)
        if (!available) cleanupDownloads()
        val reinstall = available && isDebugBuild()
        val headline = "Update available · " + release.title + " (version " + release.versionCode + ")"
        val message = when {
            reinstall -> headline + ". " + UpdateRules.debugBuildMessage()
            available -> headline
            release.versionCode == current -> "You're up to date · version " + current
            else -> "Installed version " + current + " is newer than the latest release " + release.versionCode + "."
        }
        return UpdateCheckResult(
            current, release, available, message,
            needsReinstall = reinstall,
            reinstallUrl = if (reinstall) UpdateRules.directDownloadUrl(release.tagName, release.assetName) else ""
        )
    }

    private fun isDebugBuild(): Boolean =
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun lastCheckedAt(): Long = prefs.getLong("last_checked_at", 0L)

    /** Deletes downloaded update APKs that are no longer newer than the installed build. */
    fun cleanupDownloads() {
        val current = currentVersionCode()
        File(app.filesDir, "updates").listFiles()?.forEach { file ->
            val version = Regex("""guardian-update-(\d+)\.apk""").matchEntire(file.name)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (version == null || version <= current) file.delete()
        }
    }

    fun notifyIfAvailable(result: UpdateCheckResult) {
        val release = result.release ?: return
        if (!result.updateAvailable) return
        if (prefs.getString("last_notified_tag", "") == release.tagName) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "guardian_app_updates"
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications when a new Guardian version is ready"
                }
            )
        }

        val launch = Intent(app, ParentActivity::class.java).apply {
            action = "com.sirrlevi.guardian.parent.OPEN_APP_UPDATES"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            app, 7301, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Guardian update available")
            .setContentText("Version " + release.versionCode + " is ready. Tap to download and install.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("A new " + release.title + " build is ready. Tap to open Guardian → Settings → Check for updates, then download and install it. Your data and settings are kept."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(7301, notification)
        prefs.edit().putString("last_notified_tag", release.tagName).apply()
    }

    @Throws(IOException::class)
    fun download(release: GuardianRelease, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val output = withToken { token -> downloadWith(token, release, onProgress) }
        ensureSameSigner(output, release)
        return output
    }

    private fun downloadWith(token: String?, release: GuardianRelease, onProgress: (Long, Long) -> Unit): File {
        val apiUrl = "$apiBase/releases/assets/" + release.assetId
        val apiConnection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Guardian-Android-Updater")
        }

        val responseCode = apiConnection.responseCode
        val stream = when {
            responseCode in 200..299 -> apiConnection.inputStream
            responseCode in 300..399 -> {
                val location = apiConnection.getHeaderField("Location")
                    ?: throw IOException("GitHub did not provide a download redirect.")
                apiConnection.disconnect()
                val target = URL(location)
                val host = target.host.lowercase()
                if (target.protocol != "https" || !(host == "release-assets.githubusercontent.com" || host.endsWith(".githubusercontent.com"))) {
                    throw IOException("GitHub returned an unexpected APK download host.")
                }
                val redirected = (target.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Guardian-Android-Updater")
                }
                if (redirected.responseCode !in 200..299) {
                    val code = redirected.responseCode
                    redirected.disconnect()
                    throw IOException("APK download failed (HTTP $code).")
                }
                redirected.inputStream
            }
            else -> {
                apiConnection.disconnect()
                throw HttpFailure(responseCode)
            }
        }

        val directory = File(app.filesDir, "updates").apply { mkdirs() }
        val output = File(directory, "guardian-update-" + release.versionCode + ".apk")
        try {
            stream.use { input ->
                FileOutputStream(output).use { fileOut ->
                    val buffer = ByteArray(16 * 1024)
                    var written = 0L
                    var lastReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        fileOut.write(buffer, 0, count)
                        written += count
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 150L) {
                            lastReport = now
                            onProgress(written, release.assetSize)
                        }
                    }
                    fileOut.fd.sync()
                    onProgress(written, release.assetSize)
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            apiConnection.disconnect()
        }

        if (!output.isFile || output.length() < 1024L) {
            output.delete()
            throw IOException("Downloaded APK is empty or incomplete.")
        }
        if (release.assetSize > 0 && output.length() != release.assetSize) {
            output.delete()
            throw IOException("Downloaded APK size does not match the GitHub release asset.")
        }
        output.inputStream().use {
            val header = ByteArray(2)
            if (it.read(header) != 2 || header[0] != 0x50.toByte() || header[1] != 0x4b.toByte()) {
                output.delete()
                throw IOException("Downloaded file is not a valid APK archive.")
            }
        }
        return output
    }

    /**
     * Android refuses to install an update signed with a different key than the installed app
     * ("App not installed as package conflicts with an existing package"). Detect that before opening the
     * installer so the person gets a clear explanation and a browser link instead.
     * If the certificates cannot be read the check is skipped and Android decides.
     */
    private fun ensureSameSigner(apk: File, release: GuardianRelease) {
        val installed: Set<String>
        val candidate: Set<String>
        try {
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            installed = signerDigests(pm.getPackageInfo(app.packageName, flags))
            candidate = signerDigests(pm.getPackageArchiveInfo(apk.path, flags))
        } catch (_: Exception) {
            return
        }
        if (!UpdateRules.sameSigners(installed, candidate)) {
            apk.delete()
            throw UpdateBlockedException(
                UpdateRules.signatureMismatchMessage(),
                UpdateRules.directDownloadUrl(release.tagName, release.assetName)
            )
        }
    }

    private fun signerDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun currentVersionCode(): Int {
        @Suppress("DEPRECATION")
        return app.packageManager.getPackageInfo(app.packageName, 0).versionCode
    }

    /**
     * Runs [block] with the saved token (if any). A stale or too-narrow token must not break updates for a
     * public repository, so on 401/403/404 the request is repeated once without it.
     */
    private fun <T> withToken(block: (String?) -> T): T {
        val token = readToken()
        try {
            return block(token)
        } catch (first: HttpFailure) {
            if (token != null && first.code in intArrayOf(401, 403, 404)) {
                try {
                    return block(null)
                } catch (_: HttpFailure) {
                    // fall through and report the first failure
                }
            }
            throw IOException(UpdateRules.explainHttpFailure(first.code, token != null) + " (HTTP " + first.code + ")")
        }
    }

    private fun requestText(url: String, token: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Guardian-Android-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpFailure(code)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun readToken(): String? {
        return try {
            val packed = prefs.getString("github_token", null) ?: return null
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12))
            )
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
