package com.sirrlevi.guardian.parent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sirrlevi.guardian.core.Delivery
import com.sirrlevi.guardian.core.DeliveryCodec
import com.sirrlevi.guardian.core.HttpsTransport
import com.sirrlevi.guardian.core.TelegramSettings
import com.sirrlevi.guardian.core.TelegramSettingsCodec
import com.sirrlevi.guardian.core.TelegramTransport
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The bot token and chat. [toString] never shows the token, so it can't reach a log by accident. */
class TelegramCredentials(val token: String, val chatId: String) {
    override fun toString() = "TelegramCredentials(chat=$chatId, token=***)"
}

/** The result of the last connection test. Kept so the screen can say what happened and when. */
data class ConnectionStatus(val testedAt: Long, val ok: Boolean, val message: String, val botName: String? = null)

/** Everything the Telegram screen reads and writes, so tests can run without a phone, a keystore or a network. */
interface TelegramBackend {
    fun settings(): TelegramSettings
    fun saveSettings(settings: TelegramSettings)
    /** Oldest first. */
    fun outbox(): List<Delivery>
    fun saveOutbox(items: List<Delivery>)
    fun connection(): ConnectionStatus?
    fun saveConnection(status: ConnectionStatus?)
    fun credentials(): TelegramCredentials?
    fun saveCredentials(credentials: TelegramCredentials)
    fun clearCredentials()
    fun transport(): TelegramTransport
}

/**
 * Keeps the bot token encrypted with an AES-GCM key that lives in the Android Keystore and never leaves it.
 * What this protects against, and what it doesn't, is spelled out on the Telegram screen (TelegramTexts.LIMITS).
 */
internal class TelegramVault(context: Context) {
    private val prefs = context.getSharedPreferences("guardian_telegram_secure", Context.MODE_PRIVATE)
    private val alias = "guardian_parent_telegram_key"

    fun save(credentials: TelegramCredentials) {
        val plain = JsonObject().apply { addProperty("t", credentials.token); addProperty("c", credentials.chatId) }.toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("blob", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    /** Null when nothing is saved or it can't be decrypted (for example the key was lost): the parent then simply enters it again. */
    fun load(): TelegramCredentials? = try {
        val packed = prefs.getString("blob", null) ?: return null
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val o = JsonParser.parseString(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).asJsonObject
        TelegramCredentials(o.get("t").asString, o.get("c").asString)
    } catch (_: Exception) { null }

    fun clear() { prefs.edit().remove("blob").apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/** The real thing. Settings, reports and the test result are small files in the app's no-backup folder; the token is in the vault. */
internal class DeviceTelegramBackend(context: Context) : TelegramBackend {
    private val dir: File = context.noBackupFilesDir
    private val vault = TelegramVault(context)
    private val settingsFile = File(dir, "telegram_settings.json")
    private val outboxFile = File(dir, "telegram_outbox.json")

    private fun read(f: File): com.google.gson.JsonElement? = try { if (f.exists()) JsonParser.parseString(f.readText()) else null } catch (_: Exception) { null }

    private fun write(f: File, text: String) {
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("Could not save ${f.name}") }
    }

    override fun settings(): TelegramSettings = TelegramSettingsCodec.fromJson(read(settingsFile)?.takeIf { it.isJsonObject }?.asJsonObject?.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject)

    override fun saveSettings(settings: TelegramSettings) {
        val o = read(settingsFile)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        o.add("settings", TelegramSettingsCodec.toJson(settings))
        write(settingsFile, o.toString())
    }

    override fun connection(): ConnectionStatus? {
        val c = read(settingsFile)?.takeIf { it.isJsonObject }?.asJsonObject?.get("connection")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return try {
            ConnectionStatus(c.get("at").asLong, c.get("ok").asBoolean, c.get("msg").asString, c.get("bot")?.takeIf { it.isJsonPrimitive }?.asString)
        } catch (_: Exception) { null }
    }

    override fun saveConnection(status: ConnectionStatus?) {
        val o = read(settingsFile)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (status == null) o.remove("connection")
        else o.add("connection", JsonObject().apply { addProperty("at", status.testedAt); addProperty("ok", status.ok); addProperty("msg", status.message.take(300)); status.botName?.let { addProperty("bot", it) } })
        write(settingsFile, o.toString())
    }

    override fun outbox(): List<Delivery> = DeliveryCodec.listFromJson(read(outboxFile))
    override fun saveOutbox(items: List<Delivery>) = write(outboxFile, DeliveryCodec.listToJson(items).toString())

    override fun credentials() = vault.load()
    override fun saveCredentials(credentials: TelegramCredentials) = vault.save(credentials)
    override fun clearCredentials() = vault.clear()
    override fun transport(): TelegramTransport = HttpsTransport()
}
