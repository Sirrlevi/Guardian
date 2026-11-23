package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.Locale

/*
 * Communication Activity: what the Child phone's *notifications* show about chat apps.
 *
 * This is deliberately not a chat mirror. Android only lets a notification listener see what an app puts in
 * a notification, so every field here can be missing and nothing is invented: if a sender or direction is not
 * in the notification it stays null / UNKNOWN, and if the app hid the text the record says so.
 */

/** Whether the notification shows a received message, a sent one (an inline reply), or cannot tell. */
enum class CommDirection(val code: String) {
    INCOMING("in"), OUTGOING("out"), UNKNOWN("unk");

    companion object {
        fun fromCode(code: String?): CommDirection = values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class CommAppGroup(val label: String) { WHATSAPP("WhatsApp"), INSTAGRAM("Instagram"), OTHER("Other") }

object CommApps {
    private val whatsapp = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val instagram = setOf("com.instagram.android")
    private val otherMessaging = setOf(
        "com.facebook.orca", "com.facebook.mlite", "org.telegram.messenger", "org.telegram.messenger.web", "com.snapchat.android",
        "com.discord", "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.google.android.apps.dynamite", "com.viber.voip", "com.skype.raider", "com.zhiliaoapp.musically",
        "com.kakao.talk", "jp.naver.line.android", "com.tencent.mm"
    )

    /** System and Guardian packages whose notifications are never recorded. */
    private val never = setOf(
        "android", "com.android.systemui", "com.android.providers.downloads", "com.google.android.gms", "com.android.vending",
        "com.sirrlevi.guardian.child", "com.sirrlevi.guardian.parent"
    )

    const val CATEGORY_MESSAGE = "msg"
    const val CATEGORY_SOCIAL = "social"
    const val CATEGORY_CALL = "call"

    fun group(pkg: String): CommAppGroup = when (pkg) {
        in whatsapp -> CommAppGroup.WHATSAPP
        in instagram -> CommAppGroup.INSTAGRAM
        else -> CommAppGroup.OTHER
    }

    /**
     * Known chat apps are always recorded. Any other app is recorded only when Android itself marks the
     * notification as a message or social post, so downloads, banking alerts, media players, etc. never are.
     */
    fun isCandidate(pkg: String, category: String?, hasMessagingStyle: Boolean): Boolean {
        if (pkg in never) return false
        if (pkg in whatsapp || pkg in instagram || pkg in otherMessaging) return true
        return hasMessagingStyle || category == CATEGORY_MESSAGE || category == CATEGORY_SOCIAL
    }
}

/** One message of a MessagingStyle notification. [isSelf] is null when Android does not say who wrote it. */
data class RawMessage(val sender: String?, val text: String?, val timeMs: Long, val isSelf: Boolean?)

/** Everything one notification exposed, copied out of the Android objects so the parsing can be unit-tested. */
data class RawNotification(
    val pkg: String,
    val postTimeMs: Long,
    /** `Notification.when`: the time the message was sent, stable when the notification is updated. */
    val whenMs: Long,
    val category: String?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val conversationTitle: String?,
    val isGroup: Boolean?,
    val messages: List<RawMessage> = emptyList(),
    val hasMessagingStyle: Boolean = false,
    val groupSummary: Boolean = false,
    val ongoing: Boolean = false
)

data class CommRecord(
    val id: String,
    val pkg: String,
    val app: String,
    val sender: String?,
    val conversation: String?,
    val isGroup: Boolean?,
    /** The preview text, or null when the notification did not contain readable content. */
    val text: String?,
    val timeMs: Long,
    val category: String?,
    val direction: CommDirection,
    val contentAvailable: Boolean
) {
    val isCall: Boolean get() = category == CommApps.CATEGORY_CALL
}

object NotificationParser {
    const val MAX_TEXT = 500
    private const val MAX_MESSAGES = 10

    private val placeholders = listOf(
        Regex("""^(\d+\s+)?new\s+messages?$""", RegexOption.IGNORE_CASE),
        Regex("""^you have (a |\d+ )?new messages?$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+ messages? from \d+ chats?$""", RegexOption.IGNORE_CASE),
        Regex("""^(messages?|new notification|checking for new messages)$""", RegexOption.IGNORE_CASE),
        Regex("""^(sensitive )?(notification )?content (is )?hidden$""", RegexOption.IGNORE_CASE),
        Regex("""^sensitive notification content hidden$""", RegexOption.IGNORE_CASE)
    )

    /** True when [text] is empty or one of the generic strings apps show instead of the real message. */
    fun isPlaceholder(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isEmpty() || placeholders.any { it.matches(t) }
    }

    /** Turns one notification into zero or more records. Never throws and never fills in missing facts. */
    fun parse(raw: RawNotification, appName: String): List<CommRecord> {
        if (!CommApps.isCandidate(raw.pkg, raw.category, raw.hasMessagingStyle)) return emptyList()
        if (raw.groupSummary || raw.ongoing) return emptyList()
        val title = clean(raw.title)
        val conversationTitle = clean(raw.conversationTitle)
        val out = ArrayList<CommRecord>()

        if (raw.messages.isNotEmpty()) {
            for (m in raw.messages.takeLast(MAX_MESSAGES)) {
                val text = clean(m.text)
                val sender = clean(m.sender) ?: if (m.isSelf != true) title else null
                if (text == null && sender == null) continue
                val direction = when (m.isSelf) {
                    true -> CommDirection.OUTGOING
                    false -> CommDirection.INCOMING
                    null -> CommDirection.UNKNOWN
                }
                val at = if (m.timeMs > 0L) m.timeMs else timeOf(raw)
                out.add(build(raw, appName, sender, conversationTitle ?: if (raw.isGroup == true) null else sender, raw.isGroup, text, at, direction))
            }
            return out
        }

        val body = clean(raw.bigText) ?: clean(raw.text)
        if (title == null && body == null) return emptyList()
        var sender = title
        var conversation = conversationTitle ?: title
        var text = body
        // Group chats usually post the group name as the title and "Sender: message" as the text.
        if (text != null && (raw.isGroup == true || conversationTitle != null)) {
            val cut = text.indexOf(": ")
            if (cut in 1..40) {
                sender = text.substring(0, cut).trim()
                text = text.substring(cut + 2).trim().ifEmpty { null }
                conversation = conversationTitle ?: title
            }
        }
        out.add(build(raw, appName, sender, conversation, raw.isGroup, text, timeOf(raw), CommDirection.UNKNOWN))
        return out
    }

    private fun timeOf(raw: RawNotification): Long = if (raw.whenMs > 0L) raw.whenMs else raw.postTimeMs

    private fun clean(value: String?): String? {
        val t = value?.replace('‎', ' ')?.replace('‏', ' ')?.trim()
        if (t.isNullOrEmpty()) return null
        return if (t.length > MAX_TEXT) t.substring(0, MAX_TEXT) else t
    }

    private fun build(
        raw: RawNotification, appName: String, sender: String?, conversation: String?, isGroup: Boolean?,
        text: String?, timeMs: Long, direction: CommDirection
    ): CommRecord {
        val hidden = isPlaceholder(text)
        val shown = if (hidden) null else text
        return CommRecord(
            id = recordId(raw.pkg, conversation, sender, timeMs, shown),
            pkg = raw.pkg, app = appName, sender = sender, conversation = conversation, isGroup = isGroup,
            text = shown, timeMs = timeMs, category = raw.category, direction = direction, contentAvailable = shown != null
        )
    }

    /**
     * Stable id for one message: the same message seen again (a notification update, a re-post) gets the same id,
     * a different message never does. 24 hex characters, safe as a Realtime Database key.
     */
    fun recordId(pkg: String, conversation: String?, sender: String?, timeMs: Long, text: String?): String {
        val seed = listOf(pkg, conversation.orEmpty(), sender.orEmpty(), (timeMs / 1000L).toString(), text.orEmpty()).joinToString("\u0001")
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }
}

/** Remembers recently seen record ids so the same message is not stored or uploaded twice. */
class CommDedupe(private val capacity: Int = 2000) {
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    /** True the first time [id] is offered, false for every repeat while it is still remembered. */
    @Synchronized fun firstSeen(id: String): Boolean = seen.put(id, true) == null
    @Synchronized fun forget(id: String) { seen.remove(id) }
}

object CommCodec {
    fun toJson(r: CommRecord): JsonObject = JsonObject().apply {
        addProperty("p", r.pkg)
        addProperty("a", r.app)
        r.sender?.let { addProperty("s", it) }
        r.conversation?.let { addProperty("c", it) }
        r.isGroup?.let { addProperty("g", it) }
        r.text?.let { addProperty("t", it) }
        addProperty("ts", r.timeMs)
        r.category?.let { addProperty("k", it) }
        addProperty("d", r.direction.code)
        addProperty("ok", r.contentAvailable)
    }

    fun fromJson(id: String, o: JsonObject): CommRecord? {
        val pkg = o.stringOr("p")
        val ts = o.longOr("ts", 0L)
        if (pkg.isBlank() || ts <= 0L) return null
        val text = if (o.has("t")) o.stringOr("t").takeIf { it.isNotBlank() } else null
        return CommRecord(
            id = id, pkg = pkg, app = o.stringOr("a", pkg),
            sender = o.stringOr("s").takeIf { it.isNotBlank() },
            conversation = o.stringOr("c").takeIf { it.isNotBlank() },
            isGroup = if (o.has("g")) o.boolOrFalse("g") else null,
            text = text, timeMs = ts,
            category = o.stringOr("k").takeIf { it.isNotBlank() },
            direction = CommDirection.fromCode(o.stringOr("d")),
            contentAvailable = if (o.has("ok")) o.boolOrFalse("ok") else text != null
        )
    }
}

data class CommFilter(
    val group: CommAppGroup? = null,
    val fromMs: Long = 0L,
    val toMs: Long = Long.MAX_VALUE,
    val query: String = "",
    val sender: String? = null
)

data class CommConversation(val key: String, val title: String, val pkg: String, val app: String, val isGroup: Boolean, val items: List<CommRecord>) {
    val lastMs: Long get() = items.maxOfOrNull { it.timeMs } ?: 0L
}

object CommQuery {
    /** Records that pass [f], oldest first. */
    fun apply(records: List<CommRecord>, f: CommFilter): List<CommRecord> {
        val q = f.query.trim().lowercase(Locale.getDefault())
        return records.filter { r ->
            (f.group == null || CommApps.group(r.pkg) == f.group) &&
                r.timeMs in f.fromMs..f.toMs &&
                (f.sender == null || r.sender.equals(f.sender, ignoreCase = true)) &&
                (q.isEmpty() || listOfNotNull(r.sender, r.conversation, r.text, r.app).any { it.lowercase(Locale.getDefault()).contains(q) })
        }.sortedBy { it.timeMs }
    }

    /** People who appear in [records], most active first. */
    fun senders(records: List<CommRecord>): List<Pair<String, Int>> =
        records.filter { it.direction != CommDirection.OUTGOING }
            .mapNotNull { it.sender?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .groupingBy { it }.eachCount()
            .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase(Locale.getDefault()) })

    /** Records grouped per chat, most recently active chat first. */
    fun conversations(records: List<CommRecord>): List<CommConversation> =
        records.groupBy { it.pkg + "|" + (it.conversation ?: it.sender ?: "?").lowercase(Locale.getDefault()) }
            .map { (key, items) ->
                val sorted = items.sortedBy { it.timeMs }
                val first = sorted.first()
                CommConversation(
                    key = key,
                    title = first.conversation ?: first.sender ?: "Unknown conversation",
                    pkg = first.pkg, app = first.app,
                    isGroup = sorted.any { it.isGroup == true },
                    items = sorted
                )
            }.sortedByDescending { it.lastMs }
}
