package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * Child safety alerts: simple, readable rules over data Guardian already has (message previews, app usage, the phone's own report).
 *
 * What these alerts are NOT: proof of anything. A rule can only notice that a pattern or a word appeared in a notification preview, and
 * previews are partial. So every alert carries a neutral label, a plain reason that says what was seen and why it matters, and a line
 * saying what the rule cannot know. The parent reviews and dismisses; nothing is blocked or reported to anyone automatically.
 */

object SafetyLabels {
    const val SUSPICIOUS = "Potentially suspicious content"
    const val REVIEW = "Review recommended"
    const val REPEATED = "Repeated contact pattern"
    const val UNKNOWN_SENDER = "Unknown sender"
}

enum class AlertKind(val code: String, val label: String, val setting: String) {
    UNKNOWN_SENDER("unknown_sender", SafetyLabels.REPEATED, "New people contacting your child"),
    SUSPICIOUS_LINK("link", SafetyLabels.SUSPICIOUS, "Links that hide where they go"),
    SUSPICIOUS_WORDS("words", SafetyLabels.SUSPICIOUS, "Worrying wording in message previews"),
    LATE_NIGHT("late_night", SafetyLabels.REVIEW, "Late-night phone use"),
    EXCESSIVE_USAGE("usage", SafetyLabels.REVIEW, "Very long screen time"),
    NEW_APP("new_app", SafetyLabels.REVIEW, "New apps installed"),
    MONITORING_OFF("monitoring_off", SafetyLabels.REVIEW, "Guardian's access switched off"),
    DISCONNECTED("disconnected", SafetyLabels.REVIEW, "Phone stopped reporting"),
    PERMISSION_CHANGE("permission", SafetyLabels.REVIEW, "Permissions switched off");

    companion object {
        fun fromCode(code: String?): AlertKind? = values().firstOrNull { it.code == code }
    }
}

enum class AlertLevel { NOTE, REVIEW }

/** One alert. [id] is the same every time the same thing is seen, so dismissing it sticks and it is never reported twice. */
data class SafetyAlert(
    val id: String,
    val kind: AlertKind,
    val level: AlertLevel,
    /** Short headline, e.g. "Unknown sender: Sam". */
    val title: String,
    /** What was seen and why it is shown. Never states or implies certainty. */
    val reason: String,
    /** When the underlying thing happened (the message, the night, the install...). */
    val ts: Long,
    /** The app or place it came from, when there is one. */
    val source: String? = null
) {
    val label: String get() = kind.label
}

object SafetyPolicy {
    const val UNKNOWN_MIN_MESSAGES = 3
    const val UNKNOWN_WINDOW_MS = 24L * 3_600_000L
    /** Someone counts as new only if the earlier history reaches back far enough to have shown them before. */
    const val UNKNOWN_MIN_HISTORY_MS = 3L * 24L * 3_600_000L
    const val NEW_APP_WINDOW_MS = 48L * 3_600_000L
    const val LATE_NIGHT_MIN_SEC = 10 * 60
    const val INFO_FRESH_MS = 12L * 3_600_000L
    const val COMMS_LOOKBACK_MS = 7L * 24L * 3_600_000L
    const val DAYS_LOOKBACK = 3
    const val MAX_ALERTS = 100
    const val SELF_NOTE = "A rule can only notice a pattern; it can't tell who someone is or what they meant."
}

/** What the parent chose. Everything is on by default; the numbers have sensible limits. */
data class SafetySettings(
    val off: Set<AlertKind> = emptySet(),
    val lateFromHour: Int = 23,
    val lateToHour: Int = 5,
    val usageHours: Int = 5,
    val disconnectedHours: Int = 6,
    val ts: Long = 0L
) {
    fun on(kind: AlertKind) = kind !in off
    fun normalized() = copy(
        lateFromHour = lateFromHour.coerceIn(18, 23), lateToHour = lateToHour.coerceIn(0, 9),
        usageHours = usageHours.coerceIn(1, 16), disconnectedHours = disconnectedHours.coerceIn(2, 48)
    )
    fun withKind(kind: AlertKind, enabled: Boolean) = copy(off = if (enabled) off - kind else off + kind)
}

object SafetySettingsCodec {
    fun toJson(s: SafetySettings): JsonObject = JsonObject().apply {
        val n = s.normalized()
        val kinds = JsonObject()
        AlertKind.values().forEach { kinds.addProperty(it.code, n.on(it)) }
        add("kinds", kinds)
        addProperty("lateFrom", n.lateFromHour); addProperty("lateTo", n.lateToHour)
        addProperty("usageH", n.usageHours); addProperty("offH", n.disconnectedHours); addProperty("ts", n.ts)
    }

    /** Missing or damaged data means the defaults (everything on), never "everything off". */
    fun fromJson(o: JsonObject?): SafetySettings {
        if (o == null) return SafetySettings()
        fun int(key: String, default: Int) = try { o.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default } catch (_: Exception) { default }
        val off = HashSet<AlertKind>()
        val kinds = o.get("kinds")?.takeIf { it.isJsonObject }?.asJsonObject
        if (kinds != null) for (k in AlertKind.values()) {
            val v = kinds.get(k.code)
            if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && !v.asBoolean) off.add(k)
        }
        val ts = try { o.get("ts")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L } catch (_: Exception) { 0L }
        return SafetySettings(off, int("lateFrom", 23), int("lateTo", 5), int("usageH", 5), int("offH", 6), ts).normalized()
    }
}

data class SafetySession(val pkg: String, val name: String, val start: Long, val end: Long)
data class SafetyApp(val pkg: String, val name: String, val system: Boolean, val installedAt: Long)
/** Screen time for one day: [totalSec] and the biggest apps as (name, seconds). */
data class SafetyDay(val dayKey: String, val totalSec: Int, val top: List<Pair<String, Int>>)

/** Everything the rules look at, about one phone, at one moment. */
data class SafetyInput(
    val deviceName: String,
    val now: Long,
    val zone: TimeZone = TimeZone.getDefault(),
    /** Message previews, newest or oldest first. */
    val comms: List<CommRecord> = emptyList(),
    val apps: List<SafetyApp> = emptyList(),
    /** Sessions by day key (yyyyMMdd). */
    val sessions: Map<String, List<SafetySession>> = emptyMap(),
    val days: List<SafetyDay> = emptyList(),
    /** When the phone last reported anything, 0 when never. */
    val lastSeenMs: Long = 0L,
    /** When the phone's own status report was made, 0 when there is none. */
    val reportedAt: Long = 0L,
    /** Null = the phone doesn't say (an old app), so nothing is claimed. */
    val usageAccess: Boolean? = null,
    val notificationAccess: Boolean? = null,
    val serviceRunning: Boolean? = null,
    /** Permission name to granted, as last reported. */
    val perms: Map<String, Boolean> = emptyMap()
)

object SafetyIds {
    /** Short stable id part for [seed]; hex only, so it is safe as a database key and reveals nothing about the seed. */
    fun hash(seed: String): String =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }

    fun id(kind: AlertKind, seed: String) = kind.code + "_" + hash(seed)
    fun isValid(id: String) = Regex("^[a-z_]{3,20}_[0-9a-f]{12}$").matches(id)
}

object SafetyDays {
    fun key(ms: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * How long [startMs]..[endMs] overlaps the night that starts at [fromHour] and ends at [toHour] the next morning, per night (keyed by
     * the date the night starts). Handles a night that was entered or left in the middle of a session.
     */
    fun nightOverlap(startMs: Long, endMs: Long, zone: TimeZone, fromHour: Int, toHour: Int): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        if (endMs <= startMs) return out
        val day = Calendar.getInstance(zone).apply { timeInMillis = startMs; add(Calendar.DAY_OF_MONTH, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var guard = 0
        while (day.timeInMillis <= endMs && guard++ < 40) {
            val from = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, fromHour) }.timeInMillis
            val to = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, toHour) }.timeInMillis
            val overlap = minOf(endMs, to) - maxOf(startMs, from)
            if (overlap > 0) out[key(day.timeInMillis, zone)] = (out[key(day.timeInMillis, zone)] ?: 0L) + overlap
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }
}

/** Looks for links that hide or disguise where they lead. It never opens a link and never says a link is dangerous. */
object LinkRules {
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+")
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc", "rebrand.ly", "t.ly", "s.id", "lnkd.in"
    )
    private val ODD_SUFFIXES = setOf("zip", "click", "top", "xyz", "tk", "gq", "ml", "ga", "cf", "icu", "buzz")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    data class Finding(val url: String, val reasons: List<String>)

    fun findings(text: String?): List<Finding> {
        if (text.isNullOrBlank()) return emptyList()
        return URL.findAll(text).mapNotNull { m -> check(m.value.trimEnd('.', ',', ')', '!', '?', ';', ':')) }.take(3).toList()
    }

    private fun check(url: String): Finding? {
        val noScheme = url.replace(Regex("(?i)^https?://"), "")
        val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hasUser = authority.contains('@')
        val host = authority.substringAfter('@').substringBefore(':').lowercase(Locale.US).removePrefix("www.")
        if (host.isBlank()) return null
        val reasons = ArrayList<String>()
        if (host in SHORTENERS) reasons.add("it uses a link shortener, so the real destination is hidden")
        if (IPV4.matches(host)) reasons.add("it points to a bare number instead of a site name")
        if (host.split('.').any { it.startsWith("xn--") }) reasons.add("its address uses look-alike characters that can imitate another site")
        if (hasUser) reasons.add("it has an @ before the address, a trick that makes one site look like another")
        val suffix = host.substringAfterLast('.')
        if (suffix in ODD_SUFFIXES) reasons.add("it ends in .$suffix, a suffix often used by throwaway sites")
        return if (reasons.isEmpty()) null else Finding(url.take(80), reasons)
    }
}

/** Looks for wording that is worth a calm look. Phrases rather than single words, to keep ordinary chat out. */
object WordRules {
    enum class Group(val title: String, val note: String) {
        SECRECY("asking to keep something secret from parents", "Secrecy requests can be harmless (a surprise party) or a reason to talk."),
        PICTURES("asking for pictures or whether the child is alone", "This wording is sometimes used to pressure children. It is also common between friends joking."),
        MEETING("asking to meet alone", "Worth checking who this is and whether the meeting is expected."),
        THREAT("threatening or hurtful wording", "This may be bullying or a joke that went wrong."),
        WELLBEING("wording about hurting oneself", "This does not mean something is wrong; it is a reason for a calm, caring check-in.")
    }

    private val PHRASES: Map<Group, List<String>> = mapOf(
        Group.SECRECY to listOf(
            "don't tell your parents", "dont tell your parents", "don't tell your mom", "don't tell your dad", "our little secret", "our secret",
            "delete this chat", "delete our chat", "delete these messages", "don't tell anyone", "dont tell anyone", "keep this between us"
        ),
        Group.PICTURES to listOf(
            "send nudes", "send me nudes", "nudes", "sexting", "send me pics", "send me a pic", "show me your body", "are you home alone",
            "are your parents home", "are you alone right now"
        ),
        Group.MEETING to listOf("meet me alone", "come alone", "don't bring anyone", "dont bring anyone"),
        Group.THREAT to listOf("kill yourself", "kys", "i will find you", "i'll hurt you", "ill hurt you", "you're worthless", "nobody likes you"),
        Group.WELLBEING to listOf("kill myself", "want to die", "end my life", "hurt myself", "self harm")
    )

    private val COMPILED: Map<Group, List<Pair<String, Regex>>> = PHRASES.mapValues { (_, list) ->
        list.map { it to Regex("(?<![a-z0-9])" + Regex.escape(it) + "(?![a-z0-9])") }
    }

    data class Match(val group: Group, val phrase: String)

    private fun clean(text: String) = text.lowercase(Locale.US).replace('’', '\'').replace('‘', '\'').replace(Regex("\\s+"), " ")

    /** One match per group at most. Wellbeing wording counts from either side of the chat; everything else only from other people. */
    fun matches(text: String?, incoming: Boolean): List<Match> {
        if (text.isNullOrBlank()) return emptyList()
        val t = clean(text)
        val out = ArrayList<Match>()
        for ((group, list) in COMPILED) {
            if (group != Group.WELLBEING && !incoming) continue
            val hit = list.firstOrNull { (_, re) -> re.containsMatchIn(t) } ?: continue
            out.add(Match(group, hit.first))
        }
        return out
    }
}

object SafetyEvaluator {
    private fun ago(now: Long, ts: Long): String {
        val m = ((now - ts) / 60_000L).coerceAtLeast(0)
        return when {
            m < 2 -> "a moment ago"
            m < 60 -> "$m minutes ago"
            m < 48 * 60 -> "${m / 60} hours ago"
            else -> "${m / (60 * 24)} days ago"
        }
    }

    fun evaluate(input: SafetyInput, settings: SafetySettings): List<SafetyAlert> {
        val s = settings.normalized()
        val out = ArrayList<SafetyAlert>()
        if (s.on(AlertKind.UNKNOWN_SENDER)) out += unknownSenders(input)
        if (s.on(AlertKind.SUSPICIOUS_LINK)) out += links(input)
        if (s.on(AlertKind.SUSPICIOUS_WORDS)) out += words(input)
        if (s.on(AlertKind.LATE_NIGHT)) out += lateNight(input, s)
        if (s.on(AlertKind.EXCESSIVE_USAGE)) out += usage(input, s)
        if (s.on(AlertKind.NEW_APP)) out += newApps(input)
        if (s.on(AlertKind.MONITORING_OFF)) out += monitoring(input)
        if (s.on(AlertKind.DISCONNECTED)) out += disconnected(input, s)
        return out.distinctBy { it.id }.sortedByDescending { it.ts }.take(SafetyPolicy.MAX_ALERTS)
    }

    // ---- message previews

    private fun recent(input: SafetyInput) = input.comms.filter { it.timeMs >= input.now - SafetyPolicy.COMMS_LOOKBACK_MS && it.timeMs <= input.now + 60_000L }

    private fun unknownSenders(input: SafetyInput): List<SafetyAlert> {
        val all = input.comms.filter { it.direction != CommDirection.OUTGOING && !it.sender.isNullOrBlank() }
        if (all.isEmpty()) return emptyList()
        // Only judge "new" when the history reaches back far enough to have shown this person earlier.
        val historyStart = input.comms.minOf { it.timeMs }
        if (input.now - historyStart < SafetyPolicy.UNKNOWN_MIN_HISTORY_MS) return emptyList()
        val out = ArrayList<SafetyAlert>()
        val windowStart = input.now - SafetyPolicy.UNKNOWN_WINDOW_MS
        for ((name, items) in all.groupBy { it.sender!!.trim().lowercase(Locale.US) }) {
            if (name.isBlank() || items.all { it.sender.equals(it.app, ignoreCase = true) }) continue
            val first = items.minOf { it.timeMs }
            val inWindow = items.filter { it.timeMs >= windowStart }
            if (first < windowStart || inWindow.size < SafetyPolicy.UNKNOWN_MIN_MESSAGES) continue
            val shown = items.first().sender!!.trim().take(40)
            val apps = inWindow.map { it.app }.distinct().joinToString(", ")
            out.add(
                SafetyAlert(
                    id = SafetyIds.id(AlertKind.UNKNOWN_SENDER, name + "|" + SafetyDays.key(first, input.zone)),
                    kind = AlertKind.UNKNOWN_SENDER, level = AlertLevel.REVIEW,
                    title = "${SafetyLabels.UNKNOWN_SENDER}: $shown",
                    reason = "\"$shown\" is not in the earlier message previews Guardian has (first seen ${ago(input.now, first)}) and has appeared ${inWindow.size} times in the last 24 hours on $apps. " +
                        "This only shows a pattern in notification previews. ${SafetyPolicy.SELF_NOTE}",
                    ts = inWindow.maxOf { it.timeMs }, source = apps
                )
            )
        }
        return out
    }

    private fun links(input: SafetyInput): List<SafetyAlert> = recent(input).mapNotNull { r ->
        if (r.direction == CommDirection.OUTGOING) return@mapNotNull null
        val f = LinkRules.findings(r.text).firstOrNull() ?: return@mapNotNull null
        val who = r.sender?.takeIf { it.isNotBlank() } ?: "someone"
        SafetyAlert(
            id = SafetyIds.id(AlertKind.SUSPICIOUS_LINK, r.id), kind = AlertKind.SUSPICIOUS_LINK, level = AlertLevel.NOTE,
            title = "Link in a message from $who",
            reason = "A preview from $who in ${r.app} contains a link (${f.url}) that may be worth a look: " + f.reasons.joinToString("; ") +
                ". Guardian did not open it and can't say whether it is harmful.",
            ts = r.timeMs, source = r.app
        )
    }

    private fun words(input: SafetyInput): List<SafetyAlert> = recent(input).mapNotNull { r ->
        val hits = WordRules.matches(r.text, incoming = r.direction != CommDirection.OUTGOING)
        if (hits.isEmpty()) return@mapNotNull null
        val who = if (r.direction == CommDirection.OUTGOING) "your child" else r.sender?.takeIf { it.isNotBlank() } ?: "someone"
        val level = if (hits.any { it.group == WordRules.Group.WELLBEING || it.group == WordRules.Group.MEETING || it.group == WordRules.Group.PICTURES }) AlertLevel.REVIEW else AlertLevel.NOTE
        SafetyAlert(
            id = SafetyIds.id(AlertKind.SUSPICIOUS_WORDS, r.id), kind = AlertKind.SUSPICIOUS_WORDS, level = level,
            title = "Potentially suspicious wording from $who",
            reason = "A preview in ${r.app} from $who contains wording that matches: " + hits.joinToString("; ") { "\"${it.phrase}\" (${it.group.title})" } +
                ". " + hits.first().group.note + " Previews are partial and words alone prove nothing; read it in context in Communication.",
            ts = r.timeMs, source = r.app
        )
    }

    // ---- usage

    private fun lateNight(input: SafetyInput, s: SafetySettings): List<SafetyAlert> {
        val perNight = HashMap<String, Long>()
        val apps = HashMap<String, HashMap<String, Long>>()
        val lowest = input.now - SafetyPolicy.DAYS_LOOKBACK * 24L * 3_600_000L
        for (list in input.sessions.values) for (x in list) {
            if (x.end < lowest) continue
            for ((night, ms) in SafetyDays.nightOverlap(x.start, minOf(x.end, input.now), input.zone, s.lateFromHour, s.lateToHour)) {
                perNight[night] = (perNight[night] ?: 0L) + ms
                val m = apps.getOrPut(night) { HashMap() }
                m[x.name] = (m[x.name] ?: 0L) + ms
            }
        }
        return perNight.mapNotNull { (night, ms) ->
            val sec = (ms / 1000L).toInt()
            if (sec < SafetyPolicy.LATE_NIGHT_MIN_SEC) return@mapNotNull null
            val top = apps.getValue(night).entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
            SafetyAlert(
                id = SafetyIds.id(AlertKind.LATE_NIGHT, night), kind = AlertKind.LATE_NIGHT, level = AlertLevel.NOTE,
                title = "Late-night use on ${niceDay(night)}",
                reason = "${input.deviceName} was used for about ${minutes(sec)} between ${hour(s.lateFromHour)} and ${hour(s.lateToHour)} (mostly $top). " +
                    "This is only about timing; it can be a late film or a restless night.",
                ts = nightEnd(night, s, input.zone, input.now)
            )
        }
    }

    private fun nightEnd(night: String, s: SafetySettings, zone: TimeZone, now: Long): Long {
        val y = night.substring(0, 4).toInt(); val m = night.substring(4, 6).toInt(); val d = night.substring(6, 8).toInt()
        val c = Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, s.lateToHour, 0, 0); add(Calendar.DAY_OF_MONTH, 1) }
        return minOf(c.timeInMillis, now)
    }

    private fun usage(input: SafetyInput, s: SafetySettings): List<SafetyAlert> = input.days.mapNotNull { d ->
        if (d.totalSec < s.usageHours * 3600) return@mapNotNull null
        val top = d.top.take(3).joinToString(", ") { "${it.first} ${minutes(it.second)}" }
        SafetyAlert(
            id = SafetyIds.id(AlertKind.EXCESSIVE_USAGE, d.dayKey), kind = AlertKind.EXCESSIVE_USAGE, level = AlertLevel.NOTE,
            title = "Long screen time on ${niceDay(d.dayKey)}",
            reason = "${input.deviceName} was in use for ${minutes(d.totalSec)} (your limit for this alert is ${s.usageHours} h)" + (if (top.isNotEmpty()) ": $top" else "") +
                ". Long days happen (holidays, travel); this is a prompt to look, not a verdict.",
            ts = dayEnd(d.dayKey, input.zone, input.now)
        )
    }

    private fun dayEnd(dayKey: String, zone: TimeZone, now: Long): Long {
        val c = Calendar.getInstance(zone).apply { clear(); set(dayKey.substring(0, 4).toInt(), dayKey.substring(4, 6).toInt() - 1, dayKey.substring(6, 8).toInt(), 23, 59, 0) }
        return minOf(c.timeInMillis, now)
    }

    private fun newApps(input: SafetyInput): List<SafetyAlert> = input.apps.mapNotNull { a ->
        if (a.system || a.installedAt <= 0L || a.pkg.startsWith("com.sirrlevi.guardian")) return@mapNotNull null
        if (input.now - a.installedAt !in 0..SafetyPolicy.NEW_APP_WINDOW_MS) return@mapNotNull null
        SafetyAlert(
            id = SafetyIds.id(AlertKind.NEW_APP, a.pkg + "|" + a.installedAt), kind = AlertKind.NEW_APP, level = AlertLevel.NOTE,
            title = "New app: ${a.name}",
            reason = "\"${a.name}\" was installed ${ago(input.now, a.installedAt)}. Guardian doesn't judge apps; it is shown so you can check it is one you expect.",
            ts = a.installedAt, source = a.name
        )
    }

    // ---- the phone itself

    private fun monitoring(input: SafetyInput): List<SafetyAlert> {
        if (input.reportedAt <= 0L || input.now - input.reportedAt > SafetyPolicy.INFO_FRESH_MS) return emptyList()
        val day = SafetyDays.key(input.now, input.zone)
        val when_ = "as the phone last reported, ${ago(input.now, input.reportedAt)}"
        val out = ArrayList<SafetyAlert>()
        fun add(code: String, title: String, reason: String) = out.add(
            SafetyAlert(
                id = SafetyIds.id(AlertKind.MONITORING_OFF, "$code|$day"), kind = AlertKind.MONITORING_OFF, level = AlertLevel.REVIEW,
                title = title, reason = "$reason ($when_). It can be switched off by accident or after an Android update; it can also be done on purpose.", ts = input.reportedAt
            )
        )
        if (input.usageAccess == false) add("usage", "Usage access is off", "Guardian Child can no longer see which apps are used, so time limits and activity are not working.")
        if (input.notificationAccess == false) add("notif", "Notification access is off", "Guardian Child can no longer read message previews, so Communication has no new messages.")
        if (input.serviceRunning == false) add("service", "Guardian's service isn't running", "The monitoring service on the phone has stopped, so reports may be late or missing.")
        return out
    }

    private fun disconnected(input: SafetyInput, s: SafetySettings): List<SafetyAlert> {
        if (input.lastSeenMs <= 0L || input.now - input.lastSeenMs < s.disconnectedHours * 3_600_000L) return emptyList()
        return listOf(
            SafetyAlert(
                id = SafetyIds.id(AlertKind.DISCONNECTED, (input.lastSeenMs / 60_000L).toString()), kind = AlertKind.DISCONNECTED, level = AlertLevel.REVIEW,
                title = "${input.deviceName} stopped reporting",
                reason = "${input.deviceName} last reported ${ago(input.now, input.lastSeenMs)}. It may be switched off, out of battery or offline; " +
                    "it can also mean Guardian Child was stopped or removed. Guardian can't tell which.",
                ts = input.lastSeenMs
            )
        )
    }

    // ---- helpers

    private fun minutes(sec: Int): String = if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else "${maxOf(1, sec / 60)} min"
    private fun hour(h: Int) = String.format(Locale.US, "%02d:00", h)
    private fun niceDay(key: String) = "${key.substring(6, 8).trimStart('0')}/${key.substring(4, 6).trimStart('0')}"
}

/**
 * Permission changes can only be seen by comparing with an earlier report. [diff] says which permissions were on in the last snapshot and
 * are off now; the first time there is nothing to compare with, so nothing is claimed.
 */
object PermissionWatch {
    val NAMES = mapOf(
        "usage" to "Usage access", "overlay" to "Display over other apps", "notifications" to "Notifications", "battery" to "Battery optimisation exemption",
        "admin" to "Device administrator", "vpn" to "Web filter (VPN)", "notifaccess" to "Notification access"
    )

    fun diff(previous: Map<String, Boolean>?, now: Map<String, Boolean>): List<String> {
        if (previous == null || previous.isEmpty()) return emptyList()
        return now.keys.filter { previous[it] == true && now[it] == false && it in NAMES }.sorted()
    }

    fun alerts(changed: List<String>, input: SafetyInput, detectedAt: Long): List<SafetyAlert> = changed.map { key ->
        val name = NAMES.getValue(key)
        SafetyAlert(
            id = SafetyIds.id(AlertKind.PERMISSION_CHANGE, key + "|" + SafetyDays.key(detectedAt, input.zone)), kind = AlertKind.PERMISSION_CHANGE, level = AlertLevel.REVIEW,
            title = "$name was switched off",
            reason = "$name was on in Guardian's earlier report and is off now. Android updates, battery savers or the child can switch it off. " +
                "Guardian only sees the change when it checks, so it can't say exactly when or why.",
            ts = detectedAt
        )
    }
}

object SafetyAlertCodec {
    fun toJson(a: SafetyAlert): JsonObject = JsonObject().apply {
        addProperty("k", a.kind.code); addProperty("lv", a.level.name); addProperty("t", a.title.take(120)); addProperty("r", a.reason.take(600))
        addProperty("ts", a.ts); a.source?.let { addProperty("src", it.take(60)) }
    }

    fun fromJson(id: String, o: JsonObject?): SafetyAlert? {
        if (o == null || !SafetyIds.isValid(id)) return null
        return try {
            val kind = AlertKind.fromCode(o.get("k")?.asString) ?: return null
            val level = AlertLevel.values().firstOrNull { it.name == o.get("lv")?.asString } ?: AlertLevel.NOTE
            SafetyAlert(id, kind, level, o.get("t").asString, o.get("r").asString, o.get("ts").asLong, o.get("src")?.takeIf { it.isJsonPrimitive }?.asString)
        } catch (_: Exception) { null }
    }
}
