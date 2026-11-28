package com.sirrlevi.guardian.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection

/*
 * Optional Telegram reports for the parent.
 *
 * Guardian has no server of its own, so the bot token has to live on the Parent phone (encrypted with the Android Keystore, see the
 * Parent app's TelegramVault) and reports are sent by the Parent app itself. Nothing here ever logs or stores the token in plain text,
 * every request goes to https://api.telegram.org only, and a report counts as sent only when Telegram answers ok:true with a message id.
 */

object TelegramPolicy {
    const val HOST = "api.telegram.org"
    /** Telegram's limit is 4096; the rest is headroom. */
    const val MAX_TEXT = 3800
    /** Telegram allows about one message a second to a chat and 20 a minute to a group; stay clearly below. */
    const val MIN_GAP_MS = 1_500L
    const val MAX_PER_MINUTE = 15
    const val MAX_PER_PASS = 10
    const val MAX_ATTEMPTS = 6
    /** Wait after the first, second... failed attempt. The last value is used for every later one. */
    val BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L, 6 * 3_600_000L)
    const val RETRY_AFTER_CAP_MS = 3_600_000L
    const val MAX_ITEMS = 300
    val RETENTION_DAYS = listOf(1, 7, 30, 90)
    const val DEFAULT_RETENTION_DAYS = 30
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 20_000
    const val MAX_RESPONSE_BYTES = 64 * 1024
    /** Yesterday's screen-time summary is sent after this hour in the morning. */
    const val SUMMARY_HOUR = 7
}

/** What can be reported. Message text from the child's phone is never one of them. */
enum class ReportCategory(val code: String, val title: String, val about: String) {
    SAFETY("safety", "Safety alerts", "Alerts from Safety alerts: the label, title and reason shown there, which can name an app, a sender, a link or a flagged word. Never the full text of a message."),
    DEVICE_STATUS("status", "Phone status", "When the phone stops reporting, Guardian's access is switched off or a permission is turned off."),
    DAILY_SUMMARY("daily", "Daily screen-time summary", "Yesterday's total screen time and the biggest apps, once a day in the morning.");

    companion object {
        fun fromCode(code: String?): ReportCategory? = values().firstOrNull { it.code == code }
        fun of(kind: AlertKind): ReportCategory = when (kind) {
            AlertKind.MONITORING_OFF, AlertKind.DISCONNECTED, AlertKind.PERMISSION_CHANGE -> DEVICE_STATUS
            else -> SAFETY
        }
    }
}

/** What the parent chose. Reports are off until switched on. [reportFrom] is when reporting began (or history was cleared): nothing older is ever sent. */
data class TelegramSettings(
    val enabled: Boolean = false,
    val categories: Set<ReportCategory> = setOf(ReportCategory.SAFETY, ReportCategory.DEVICE_STATUS),
    val retentionDays: Int = TelegramPolicy.DEFAULT_RETENTION_DAYS,
    val reportFrom: Long = 0L
) {
    fun on(category: ReportCategory) = enabled && category in categories
    fun normalized() = copy(retentionDays = if (retentionDays in TelegramPolicy.RETENTION_DAYS) retentionDays else TelegramPolicy.DEFAULT_RETENTION_DAYS)
}

object TelegramSettingsCodec {
    fun toJson(s: TelegramSettings): JsonObject = JsonObject().apply {
        val n = s.normalized()
        addProperty("on", n.enabled)
        add("cats", JsonArray().also { a -> n.categories.sortedBy { it.code }.forEach { a.add(it.code) } })
        addProperty("keep", n.retentionDays); addProperty("from", n.reportFrom)
    }

    /** Damaged or missing data means everything off, never "send everything". */
    fun fromJson(o: JsonObject?): TelegramSettings {
        if (o == null) return TelegramSettings()
        val enabled = try { o.get("on")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false } catch (_: Exception) { false }
        val cats = try {
            o.get("cats")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> if (e.isJsonPrimitive) ReportCategory.fromCode(e.asString) else null }?.toSet()
        } catch (_: Exception) { null }
        val keep = try { o.get("keep")?.takeIf { it.isJsonPrimitive }?.asInt ?: TelegramPolicy.DEFAULT_RETENTION_DAYS } catch (_: Exception) { TelegramPolicy.DEFAULT_RETENTION_DAYS }
        val from = try { o.get("from")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L } catch (_: Exception) { 0L }
        return TelegramSettings(enabled, cats ?: TelegramSettings().categories, keep, from).normalized()
    }
}

object TelegramScrub {
    private val TOKEN = Regex("\\d{6,}:[A-Za-z0-9_-]{20,}")
    private val BOT_PATH = Regex("/bot[^/\\s\"']+")
    private val TOKEN_FORMAT = Regex("^\\d{6,12}:[A-Za-z0-9_-]{30,60}$")
    private val CHAT_NUMBER = Regex("^-?\\d{1,20}$")
    private val CHAT_NAME = Regex("^@[A-Za-z][A-Za-z0-9_]{4,31}$")

    /** Removes anything that looks like a bot token (and the exact [token], if given) from text that may be shown or kept. */
    fun scrub(text: String?, token: String? = null): String {
        var t = text ?: return ""
        if (!token.isNullOrEmpty()) t = t.replace(token, "***")
        return t.replace(BOT_PATH, "/bot***").replace(TOKEN, "***")
    }

    fun looksLikeToken(token: String) = TOKEN_FORMAT.matches(token)
    fun looksLikeChatId(chat: String) = CHAT_NUMBER.matches(chat) || CHAT_NAME.matches(chat)
}

enum class FailureKind { NETWORK, RATE_LIMITED, TOKEN, CHAT, BLOCKED, REJECTED, SERVER, UNCONFIRMED }

/** Why a send did not succeed, in words that tell the parent what to do. [retryable] says whether trying again on its own can help. */
data class TelegramFailure(val kind: FailureKind, val message: String, val retryable: Boolean, val retryAfterMs: Long = 0L)

sealed class SendResult {
    /** Telegram confirmed the message: [messageId] is the id it gave it. */
    data class Sent(val messageId: Long, val botName: String? = null) : SendResult()
    data class Failed(val failure: TelegramFailure) : SendResult()
}

/** Raised by a transport. [sent] is false when the request certainly never left the phone (no connection), true when it may have arrived. */
class TelegramTransportException(val sent: Boolean, message: String) : IOException(message)

data class TransportResponse(val code: Int, val body: String?)

interface TelegramTransport {
    fun post(url: String, form: String): TransportResponse
}

/** The only transport: HTTPS to api.telegram.org, no redirects, short timeouts. */
class HttpsTransport : TelegramTransport {
    override fun post(url: String, form: String): TransportResponse {
        val u = URL(url)
        if (u.protocol != "https" || u.host != TelegramPolicy.HOST || u.port != -1) throw TelegramTransportException(false, "Refused: only https://${TelegramPolicy.HOST} is allowed.")
        var written = false
        var conn: HttpURLConnection? = null
        try {
            conn = u.openConnection() as? HttpsURLConnection ?: throw TelegramTransportException(false, "Refused: not an HTTPS connection.")
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TelegramPolicy.CONNECT_TIMEOUT_MS
            conn.readTimeout = TelegramPolicy.READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            val bytes = form.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes); it.flush() }
            written = true
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return TransportResponse(code, stream?.use { readLimited(it) })
        } catch (e: TelegramTransportException) {
            throw e
        } catch (e: IOException) {
            // The class name only: an exception's own text can contain the address, which contains the token.
            throw TelegramTransportException(written, e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < TelegramPolicy.MAX_RESPONSE_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** Builds the requests and turns Telegram's answers into results. Holds the token only for the length of a call. */
class TelegramClient(private val transport: TelegramTransport, private val token: String, private val chatId: String) {
    private val methods = setOf("getMe", "sendMessage")

    fun url(method: String): String {
        require(method in methods) { "Unknown Telegram method." }
        require(TelegramScrub.looksLikeToken(token)) { "Bot token has the wrong format." }
        return "https://${TelegramPolicy.HOST}/bot$token/$method"
    }

    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    /** Checks the token. Success carries the bot's username. */
    fun getMe(): SendResult = call("getMe", "") { result -> SendResult.Sent(0L, result.get("username")?.takeIf { it.isJsonPrimitive }?.asString) }

    /** Plain text only (no parse mode), so nothing in a message can be read as formatting. Success only when Telegram returns a message id. */
    fun sendMessage(text: String): SendResult =
        call("sendMessage", form("chat_id" to chatId, "text" to text.take(TelegramPolicy.MAX_TEXT), "disable_web_page_preview" to "true")) { result ->
            val id = result.get("message_id")?.takeIf { it.isJsonPrimitive }?.let { try { it.asLong } catch (_: Exception) { 0L } } ?: 0L
            if (id > 0L) SendResult.Sent(id) else null
        }

    private fun call(method: String, form: String, onOk: (JsonObject) -> SendResult.Sent?): SendResult {
        val response = try {
            transport.post(url(method), form)
        } catch (e: TelegramTransportException) {
            return SendResult.Failed(
                if (e.sent) TelegramFailure(FailureKind.UNCONFIRMED, "The connection broke after sending, so it isn't known whether Telegram got it.", false)
                else TelegramFailure(FailureKind.NETWORK, "No connection to Telegram. It will be tried again.", true)
            )
        }
        return TelegramAnswers.parse(response, onOk)
    }
}

object TelegramAnswers {
    fun parse(r: TransportResponse, onOk: (JsonObject) -> SendResult.Sent?): SendResult {
        val json = try { r.body?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject } catch (_: Exception) { null }
        val ok = json?.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (r.code == 200 && ok == true) {
            val result = json?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            val sent = result?.let(onOk)
            if (sent != null) return sent
            return SendResult.Failed(TelegramFailure(FailureKind.UNCONFIRMED, "Telegram answered without confirming the message.", false))
        }
        if (r.code == 200) return SendResult.Failed(TelegramFailure(FailureKind.UNCONFIRMED, "Telegram's answer could not be read, so it isn't known whether the message arrived.", false))
        val description = TelegramScrub.scrub(json?.get("description")?.takeIf { it.isJsonPrimitive }?.asString).take(120)
        val code = json?.get("error_code")?.takeIf { it.isJsonPrimitive }?.let { try { it.asInt } catch (_: Exception) { r.code } } ?: r.code
        val retryAfter = json?.get("parameters")?.takeIf { it.isJsonObject }?.asJsonObject?.get("retry_after")?.takeIf { it.isJsonPrimitive }?.let { try { it.asLong } catch (_: Exception) { 0L } } ?: 0L
        val failure = when {
            code == 401 || code == 404 -> TelegramFailure(FailureKind.TOKEN, "Telegram doesn't accept this bot token. Check it and save it again.", false)
            code == 403 -> TelegramFailure(FailureKind.BLOCKED, "The bot can't write to this chat (it was blocked, removed or never started). Open the chat and press Start with your bot.", false)
            code == 400 && description.contains("chat not found", ignoreCase = true) ->
                TelegramFailure(FailureKind.CHAT, "Telegram can't find that chat. Check the chat ID, and press Start with your bot in Telegram first.", false)
            code == 429 -> TelegramFailure(FailureKind.RATE_LIMITED, "Telegram asked to slow down.", true, (retryAfter.coerceIn(1L, 3_600L)) * 1000L)
            code >= 500 -> TelegramFailure(FailureKind.SERVER, "Telegram had a problem (error $code). It will be tried again.", true)
            else -> TelegramFailure(FailureKind.REJECTED, "Telegram refused it" + (if (description.isNotBlank()) ": $description" else " (error $code)") + ".", false)
        }
        return SendResult.Failed(failure)
    }
}

enum class DeliveryState(val code: String, val label: String) {
    WAITING("waiting", "Waiting to send"),
    SENDING("sending", "Sending"),
    RETRYING("retrying", "Will try again"),
    SENT("sent", "Sent"),
    FAILED("failed", "Not sent"),
    /** The phone can't know whether Telegram got it (the app stopped or the connection broke mid-send). Never retried on its own, to avoid a duplicate. */
    UNCONFIRMED("unconfirmed", "Not confirmed");

    val pending: Boolean get() = this == WAITING || this == RETRYING || this == SENDING

    companion object { fun fromCode(code: String?): DeliveryState? = values().firstOrNull { it.code == code } }
}

/** One report. [key] says what it is about, so the same thing is never sent twice. */
data class Delivery(
    val key: String,
    val category: ReportCategory,
    val text: String,
    val createdAt: Long,
    val state: DeliveryState = DeliveryState.WAITING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val sentAt: Long = 0L,
    /** The id Telegram gave the message: the proof it was delivered. Set only with [DeliveryState.SENT]. */
    val messageId: Long = 0L
)

object DeliveryCodec {
    fun toJson(d: Delivery): JsonObject = JsonObject().apply {
        addProperty("k", d.key); addProperty("c", d.category.code); addProperty("t", d.text); addProperty("at", d.createdAt)
        addProperty("s", d.state.code); addProperty("n", d.attempts); addProperty("next", d.nextAttemptAt)
        d.lastError?.let { addProperty("e", TelegramScrub.scrub(it).take(200)) }
        addProperty("sent", d.sentAt); addProperty("mid", d.messageId)
    }

    fun fromJson(o: JsonElement?): Delivery? {
        if (o == null || !o.isJsonObject) return null
        val j = o.asJsonObject
        return try {
            val key = j.get("k").asString
            if (!TelegramKeys.isValid(key)) return null
            val state = DeliveryState.fromCode(j.get("s")?.asString) ?: return null
            // A report that was mid-send when the app stopped may or may not have arrived.
            val safeState = if (state == DeliveryState.SENDING) DeliveryState.UNCONFIRMED else state
            val messageId = j.get("mid")?.asLong ?: 0L
            Delivery(
                key = key, category = ReportCategory.fromCode(j.get("c")?.asString) ?: return null, text = j.get("t").asString.take(TelegramPolicy.MAX_TEXT),
                createdAt = j.get("at").asLong, state = if (safeState == DeliveryState.SENT && messageId <= 0L) DeliveryState.UNCONFIRMED else safeState,
                attempts = j.get("n")?.asInt ?: 0, nextAttemptAt = j.get("next")?.asLong ?: 0L,
                lastError = j.get("e")?.takeIf { it.isJsonPrimitive }?.asString, sentAt = j.get("sent")?.asLong ?: 0L, messageId = messageId
            )
        } catch (_: Exception) { null }
    }

    fun listToJson(items: List<Delivery>): JsonArray = JsonArray().also { a -> items.forEach { a.add(toJson(it)) } }

    /** Damaged entries are dropped; they never turn into sends. */
    fun listFromJson(e: JsonElement?): List<Delivery> =
        e?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { fromJson(it) } ?: emptyList()
}

object TelegramKeys {
    private val VALID = Regex("^[A-Za-z0-9:_-]{3,120}$")
    fun isValid(key: String) = VALID.matches(key)
    fun alert(deviceId: String, alertId: String) = "a:${deviceId.take(40)}:$alertId"
    fun daily(deviceId: String, dayKey: String) = "d:${deviceId.take(40)}:$dayKey"
}

/** What a pass did, for the screen. */
data class PassResult(val items: List<Delivery>, val sent: Int, val failed: Int, val waiting: Int, val stoppedBecause: String? = null)

/** The queue rules. Pure: time, sending and saving are passed in, so every rule can be tested. */
object TelegramQueue {
    /** Adds a report unless one with the same key already exists in any state (that is the duplicate protection). */
    fun enqueue(items: List<Delivery>, key: String, category: ReportCategory, text: String, now: Long): List<Delivery> {
        if (!TelegramKeys.isValid(key) || items.any { it.key == key }) return items
        val d = Delivery(key, category, text.take(TelegramPolicy.MAX_TEXT), now)
        var list = items + d
        if (list.size > TelegramPolicy.MAX_ITEMS) {
            // Make room by forgetting the oldest finished reports first; never by dropping something still waiting.
            val finished = list.filter { !it.state.pending }.sortedBy { it.createdAt }
            val drop = finished.take(list.size - TelegramPolicy.MAX_ITEMS).map { it.key }.toSet()
            list = list.filter { it.key !in drop }
        }
        return list
    }

    /** Forgets reports older than the retention period. Anything older than that is also too old to be worth sending. */
    fun prune(items: List<Delivery>, now: Long, retentionDays: Int): List<Delivery> {
        val limit = now - retentionDays.coerceAtLeast(1) * 24L * 3_600_000L
        return items.filter { it.createdAt >= limit }
    }

    /** Turning reports off throws away what was still waiting; finished reports stay as history until their retention ends. */
    fun discardPending(items: List<Delivery>): List<Delivery> = items.filter { !it.state.pending }

    fun retry(items: List<Delivery>, key: String): List<Delivery> = items.map {
        if (it.key == key && (it.state == DeliveryState.FAILED || it.state == DeliveryState.UNCONFIRMED)) it.copy(state = DeliveryState.WAITING, attempts = 0, nextAttemptAt = 0L, lastError = null) else it
    }

    fun retryAllFailed(items: List<Delivery>): List<Delivery> = items.map {
        if (it.state == DeliveryState.FAILED) it.copy(state = DeliveryState.WAITING, attempts = 0, nextAttemptAt = 0L, lastError = null) else it
    }

    fun clearHistory(items: List<Delivery>): List<Delivery> = items.filter { it.state.pending }

    fun backoffAfter(attempts: Int): Long = TelegramPolicy.BACKOFF_MS[(attempts - 1).coerceIn(0, TelegramPolicy.BACKOFF_MS.size - 1)]

    /**
     * Sends what is due, oldest first, within the rate limits. Each report is saved as "sending" before the request and as its outcome after,
     * so a stop in between leaves it "not confirmed" instead of risking a second copy. Only an answer with a message id makes a report "sent".
     */
    fun runPass(
        start: List<Delivery>, now: () -> Long, recentSends: MutableList<Long>, send: (String) -> SendResult, save: (List<Delivery>) -> Unit, pause: (Long) -> Unit = {}
    ): PassResult {
        var list = start
        var sent = 0; var failed = 0
        var stopped: String? = null
        var tried = 0
        val due = list.filter { (it.state == DeliveryState.WAITING || it.state == DeliveryState.RETRYING) && it.nextAttemptAt <= now() }.sortedBy { it.createdAt }
        for (item in due) {
            if (tried >= TelegramPolicy.MAX_PER_PASS) { stopped = "Sent ${TelegramPolicy.MAX_PER_PASS} reports; the rest follow shortly."; break }
            recentSends.removeAll { now() - it >= 60_000L }
            if (recentSends.size >= TelegramPolicy.MAX_PER_MINUTE) { stopped = "Waiting to stay within Telegram's limits."; break }
            if (tried > 0) pause(TelegramPolicy.MIN_GAP_MS)
            tried++
            val attempts = item.attempts + 1
            list = replace(list, item.copy(state = DeliveryState.SENDING, attempts = attempts)); save(list)
            val result = try { send(item.text) } catch (e: Exception) {
                SendResult.Failed(TelegramFailure(FailureKind.UNCONFIRMED, "Sending stopped unexpectedly, so it isn't known whether Telegram got it.", false))
            }
            recentSends.add(now())
            when (result) {
                is SendResult.Sent -> {
                    sent++
                    list = replace(list, item.copy(state = DeliveryState.SENT, attempts = attempts, sentAt = now(), messageId = result.messageId, lastError = null, nextAttemptAt = 0L))
                }
                is SendResult.Failed -> {
                    val f = result.failure
                    val next = when {
                        f.kind == FailureKind.UNCONFIRMED -> item.copy(state = DeliveryState.UNCONFIRMED, attempts = attempts, lastError = f.message)
                        f.retryable && attempts < TelegramPolicy.MAX_ATTEMPTS ->
                            item.copy(
                                state = DeliveryState.RETRYING, attempts = attempts, lastError = f.message,
                                nextAttemptAt = now() + maxOf(backoffAfter(attempts), minOf(f.retryAfterMs, TelegramPolicy.RETRY_AFTER_CAP_MS))
                            )
                        f.retryable -> item.copy(state = DeliveryState.FAILED, attempts = attempts, lastError = "Gave up after $attempts tries. ${f.message}")
                        else -> item.copy(state = DeliveryState.FAILED, attempts = attempts, lastError = f.message)
                    }
                    if (next.state == DeliveryState.FAILED || next.state == DeliveryState.UNCONFIRMED) failed++
                    list = replace(list, next)
                    // Telegram asked to slow down, or the credentials/chat are wrong: the rest would only fail the same way.
                    stopped = when (f.kind) {
                        FailureKind.RATE_LIMITED -> "Telegram asked to slow down."
                        FailureKind.TOKEN, FailureKind.CHAT, FailureKind.BLOCKED -> f.message
                        FailureKind.NETWORK -> "No connection to Telegram."
                        else -> null
                    }
                }
            }
            save(list)
            if (stopped != null) break
        }
        return PassResult(list, sent, failed, list.count { it.state == DeliveryState.WAITING || it.state == DeliveryState.RETRYING }, stopped)
    }

    private fun replace(list: List<Delivery>, d: Delivery) = list.map { if (it.key == d.key) d else it }
}

/** The words of each report. Plain text, no formatting characters from the child's phone are ever interpreted. */
object TelegramTexts {
    private fun time(ts: Long, zone: TimeZone) = SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).apply { timeZone = zone }.format(Date(ts))

    fun alert(deviceName: String, a: SafetyAlert, zone: TimeZone): String = (
        "Guardian · $deviceName\n${a.label}\n\n${a.title}\n${a.reason}\n\nTime: ${time(a.ts, zone)}\n" +
            "Review it in Guardian. Alerts come from simple rules and can be wrong."
        ).take(TelegramPolicy.MAX_TEXT)

    fun daily(deviceName: String, d: SafetyDay): String {
        val top = d.top.take(3).joinToString(", ") { "${it.first} ${duration(it.second)}" }
        return ("Guardian · $deviceName\nScreen time on ${dayText(d.dayKey)}: ${duration(d.totalSec)}" + (if (top.isNotEmpty()) "\nBiggest apps: $top" else "")).take(TelegramPolicy.MAX_TEXT)
    }

    fun test(enabled: Boolean) = "Guardian test: this chat is connected. " +
        (if (enabled) "Reports you turned on will arrive here." else "Reports are still off; turn them on in Guardian to receive any.")

    fun duration(sec: Int) = if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else "${maxOf(1, sec / 60)} min"
    fun dayText(key: String) = "${key.substring(6, 8).trimStart('0')}/${key.substring(4, 6).trimStart('0')}"
}

object TelegramDaily {
    /** Yesterday's summary, once the morning has come, only for a day after reporting began, and only when the phone reported that day. */
    fun pick(now: Long, zone: TimeZone, reportFrom: Long, days: List<SafetyDay>): SafetyDay? {
        val c = Calendar.getInstance(zone).apply { timeInMillis = now }
        if (c.get(Calendar.HOUR_OF_DAY) < TelegramPolicy.SUMMARY_HOUR) return null
        c.add(Calendar.DAY_OF_MONTH, -1)
        val yesterday = SafetyDays.key(c.timeInMillis, zone)
        if (yesterday < SafetyDays.key(reportFrom, zone)) return null
        return days.firstOrNull { it.dayKey == yesterday && it.totalSec > 0 }
    }
}
