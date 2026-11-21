package com.sirrlevi.guardian.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GuardianSession(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAtMs: Long
)

class GuardianApi(private val apiKey: String, databaseUrl: String) {
    private val databaseBase = databaseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = apiKey.isNotBlank() && databaseBase.startsWith("https://")
    fun signUp(email: String, password: String): GuardianSession = auth("accounts:signUp", email, password)
    fun signIn(email: String, password: String): GuardianSession = auth("accounts:signInWithPassword", email, password)

    private fun auth(endpoint: String, email: String, password: String): GuardianSession {
        requireConfigured()
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
            addProperty("returnSecureToken", true)
        }
        val result = execute(
            Request.Builder()
                .url("https://identitytoolkit.googleapis.com/v1/$endpoint?key=${url(apiKey)}")
                .post(body.toString().toRequestBody(jsonType))
                .build()
        ).asJsonObject
        return sessionFrom(result)
    }

    fun refresh(session: GuardianSession): GuardianSession {
        requireConfigured()
        val form = "grant_type=refresh_token&refresh_token=${url(session.refreshToken)}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val result = execute(
            Request.Builder()
                .url("https://securetoken.googleapis.com/v1/token?key=${url(apiKey)}")
                .post(form)
                .build()
        ).asJsonObject
        return GuardianSession(
            uid = result.getString("user_id", session.uid),
            idToken = result.getString("id_token", session.idToken),
            refreshToken = result.getString("refresh_token", session.refreshToken),
            expiresAtMs = System.currentTimeMillis() + result.getString("expires_in", "3600").toLong() * 1000L
        )
    }

    fun get(path: String, session: GuardianSession): JsonElement? {
        val result = execute(
            Request.Builder().url("$databaseBase/${cleanPath(path)}.json?auth=${url(session.idToken)}").get().build()
        )
        return if (result.isJsonNull) null else result
    }

    fun put(path: String, value: JsonElement, session: GuardianSession) {
        execute(
            Request.Builder()
                .url("$databaseBase/${cleanPath(path)}.json?auth=${url(session.idToken)}")
                .put(value.toString().toRequestBody(jsonType))
                .build()
        )
    }

    /** Multi-path update: keys may be "a/b/c" paths and a JSON null value deletes that path. */
    fun patch(path: String, value: JsonObject, session: GuardianSession) {
        execute(
            Request.Builder()
                .url("$databaseBase/${cleanPath(path)}.json?auth=${url(session.idToken)}")
                .patch(value.toString().toRequestBody(jsonType))
                .build()
        )
    }

    fun delete(path: String, session: GuardianSession) {
        execute(
            Request.Builder().url("$databaseBase/${cleanPath(path)}.json?auth=${url(session.idToken)}").delete().build()
        )
    }

    private val streamHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(75, TimeUnit.SECONDS) // the server sends a keep-alive every ~30 s
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** A prepared Server-Sent-Events connection; call [cancel] from any thread to stop it. */
    class StreamHandle internal constructor(private val callRef: Any) {
        fun cancel() = (callRef as Call).cancel()
        internal fun call(): Call = callRef as Call
    }

    /** Prepares a Server-Sent-Events connection. Nothing is sent until [consumeStream] runs. */
    fun openStream(path: String, session: GuardianSession): StreamHandle =
        StreamHandle(
            streamHttp.newCall(
                Request.Builder()
                    .url("$databaseBase/${cleanPath(path)}.json?auth=${url(session.idToken)}")
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()
            )
        )

    /**
     * Blocks while the stream is open and calls [onEvent] with the event name ("put", "patch", …)
     * whenever the data under the path changes. Returns when the server closes the stream
     * (for example "auth_revoked" after the token expires) or when the call is cancelled;
     * the caller reconnects with a fresh token.
     */
    fun consumeStream(handle: StreamHandle, onEvent: (String) -> Unit) {
        val call = handle.call()
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Guardian stream error ${response.code}")
            val source = response.body?.source() ?: return
            val parser = SseParser { event, _ -> onEvent(event) }
            while (!call.isCanceled()) {
                val line = source.readUtf8Line() ?: break
                parser.feed(line)
            }
        }
    }

    fun post(path: String, value: JsonElement, session: GuardianSession): String? {
        val result = execute(
            Request.Builder()
                .url("$databaseBase/${cleanPath(path)}.json?auth=${url(session.idToken)}")
                .post(value.toString().toRequestBody(jsonType))
                .build()
        ).asJsonObject
        return result.getString("name", "")
    }

    private fun execute(request: Request): JsonElement {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JsonParser.parseString(raw).asJsonObject
                        .getAsJsonObject("error")?.get("message")?.asString
                } catch (_: Exception) { null }
                throw IOException("Guardian backend error ${response.code}: ${detail ?: raw.take(240)}")
            }
            if (raw.isBlank()) return JsonObject()
            return JsonParser.parseString(raw)
        }
    }

    private fun sessionFrom(result: JsonObject) = GuardianSession(
        uid = result.getString("localId", ""),
        idToken = result.getString("idToken", ""),
        refreshToken = result.getString("refreshToken", ""),
        expiresAtMs = System.currentTimeMillis() + result.getString("expiresIn", "3600").toLong() * 1000L
    )

    private fun JsonObject.getString(name: String, fallback: String): String =
        if (has(name) && !get(name).isJsonNull) get(name).asString else fallback

    private fun cleanPath(path: String): String =
        path.trim('/').split('/').joinToString("/") { it.replace(".", "%2E") }

    private fun url(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun requireConfigured() {
        check(isConfigured()) {
            "Firebase is not configured. Add FIREBASE_API_KEY and FIREBASE_DATABASE_URL to GitHub Actions secrets."
        }
    }
}
