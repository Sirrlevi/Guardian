package com.sirrlevi.guardian.child

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
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
    val message: String
)

class GuardianUpdater(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("guardian_updates", Context.MODE_PRIVATE)
    private val keyAlias = "guardian_update_token_key"
    private val repository = "Sirrlevi/Guardian"
    private val apiBase = "https://api.github.com/repos/$repository"

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
        val token = readToken() ?: throw IllegalStateException("Add a GitHub fine-grained token in Settings first.")
        val current = currentVersionCode()
        val expectedAsset = when (app.packageName) {
            "com.sirrlevi.guardian.parent" -> "Guardian-Parent.apk"
            "com.sirrlevi.guardian.child" -> "Guardian-Child.apk"
            else -> throw IllegalStateException("This Guardian package is not supported by the updater.")
        }

        val response = requestJson("$apiBase/releases/latest", token)
        val tag = response.stringValue("tag_name")
        val version = Regex("""(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Latest release has an invalid version tag: $tag")
        val assets = response.getAsJsonArray("assets")
            ?: throw IOException("The latest release has no APK assets.")
        val asset = assets.firstOrNull {
            it.isJsonObject && it.asJsonObject.stringValue("name") == expectedAsset
        }?.asJsonObject ?: throw IOException(
            "Latest release does not contain $expectedAsset. A signed release build is required."
        )

        val release = GuardianRelease(
            versionCode = version,
            tagName = tag,
            title = response.stringValue("name").ifBlank { tag },
            notes = response.stringValue("body"),
            assetId = asset.get("id")?.asLong ?: throw IOException("Release APK asset ID is missing."),
            assetName = expectedAsset,
            assetSize = asset.get("size")?.asLong ?: 0L,
            pageUrl = response.stringValue("html_url")
        )
        val available = release.versionCode > current
        val message = when {
            available -> "Update available · " + release.title + " (version " + release.versionCode + ")"
            release.versionCode == current -> "You're up to date · version " + current
            else -> "Installed version " + current + " is newer than the latest release " + release.versionCode + "."
        }
        return UpdateCheckResult(current, release, available, message)
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

        val launch = Intent(app, ChildActivity::class.java).apply {
            action = "com.sirrlevi.guardian.child.OPEN_APP_UPDATES"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            app, 7301, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Guardian update available")
            .setContentText("Version " + release.versionCode + " is ready. Tap to open App Updates.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("A new " + release.title + " build is ready. Open Guardian → Settings → App Updates to download and install it."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(7301, notification)
        prefs.edit().putString("last_notified_tag", release.tagName).apply()
    }

    @Throws(IOException::class)
    fun download(release: GuardianRelease): File {
        val token = readToken() ?: throw IllegalStateException("GitHub token is not saved.")
        val apiUrl = "$apiBase/releases/assets/" + release.assetId
        val apiConnection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
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
                val code = responseCode
                apiConnection.disconnect()
                throw IOException("GitHub APK download failed (HTTP $code). Check token access and release assets.")
            }
        }

        val directory = File(app.filesDir, "updates").apply { mkdirs() }
        val output = File(directory, "guardian-update-" + release.versionCode + ".apk")
        try {
            stream.use { input ->
                FileOutputStream(output).use { fileOut ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        fileOut.write(buffer, 0, count)
                    }
                    fileOut.fd.sync()
                }
            }
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

    private fun currentVersionCode(): Int {
        @Suppress("DEPRECATION")
        return app.packageManager.getPackageInfo(app.packageName, 0).versionCode
    }

    private fun requestJson(url: String, token: String): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Guardian-Android-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = try { connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(250) } catch (_: Exception) { null }
                throw IOException("GitHub update check failed (HTTP $code). " + detail.orEmpty())
            }
            return JsonParser.parseString(connection.inputStream.bufferedReader().use { it.readText() }).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.stringValue(name: String): String =
        try { get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty() } catch (_: Exception) { "" }

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
