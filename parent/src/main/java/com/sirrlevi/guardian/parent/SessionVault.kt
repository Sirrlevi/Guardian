package com.sirrlevi.guardian.parent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.sirrlevi.guardian.core.GuardianSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("guardian_secure", Context.MODE_PRIVATE)
    private val alias = "guardian_parent_session_key"
    private val gson = Gson()

    fun save(session: GuardianSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(): GuardianSession? = try {
        val packed = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), GuardianSession::class.java)
    } catch (_: Exception) { null }

    fun clear() { prefs.edit().remove("session").apply() }

    private fun getKey(): SecretKey {
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
