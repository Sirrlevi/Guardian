package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import java.util.Locale

/*
 * Approved screen sharing (screenshots and a small live preview): everything that needs no Android types.
 *
 * Nothing starts on its own. The parent asks, the child approves on the phone through Android's own screen-capture
 * dialog (once per session), and the child can stop at any moment. While a session is active the parent can ask for
 * screenshots and switch on a low-rate preview. "Live" here means a still picture that refreshes every few seconds,
 * not video, and the screens say so.
 */

object CapturePolicy {
    const val SESSION_MAX_MS = 30 * 60_000L
    const val REQUEST_TTL_MS = 10 * 60_000L
    const val COMMAND_TTL_MS = 2 * 60_000L
    /** Parent and child clocks may differ a little; a command is only called expired after this much extra. */
    const val CLOCK_SKEW_MS = 60_000L
    /** The preview only runs while the parent keeps the Screen preview screen open (it sends a heartbeat). */
    const val VIEWER_FRESH_MS = 30_000L
    const val LIVE_INTERVAL_MS = 2_500L
    const val STATE_STALE_MS = 25_000L
    const val NETWORK_LOSS_END_MS = 3 * 60_000L
    const val SHOT_KEEP = 10
    const val LOG_KEEP = 30
    const val PROCESSED_KEEP = 80
    const val SHOT_EDGE = 1280
    const val SHOT_QUALITY = 75
    const val LIVE_EDGE = 540
    const val LIVE_QUALITY = 45
    const val IDLE_POLL_MS = 15_000L
    const val AWAITING_POLL_MS = 3_000L
    const val ACTIVE_POLL_MS = 2_000L
}

enum class CaptureStatus(val code: String) {
    IDLE("idle"), AWAITING("await"), ACTIVE("active"), ENDED("ended");

    companion object {
        fun fromCode(code: String?): CaptureStatus = values().firstOrNull { it.code == code } ?: IDLE
    }
}

/** Why a session or request ended. */
object CaptureReasons {
    const val PARENT_STOPPED = "parent_stopped"
    const val CHILD_STOPPED = "child_stopped"
    const val SYSTEM_STOPPED = "system_stopped"
    const val TIMEOUT = "timeout"
    const val NETWORK = "network"
    const val ERROR = "error"
    const val DECLINED = "declined"
    const val EXPIRED = "expired"
    const val CANCELLED = "cancelled"

    fun text(code: String?, childName: String): String = when (code) {
        PARENT_STOPPED -> "You stopped the session."
        CHILD_STOPPED -> "$childName stopped sharing."
        SYSTEM_STOPPED -> "Android ended the session, for example from its own screen-sharing control or after a restart."
        TIMEOUT -> "The session reached its ${CapturePolicy.SESSION_MAX_MS / 60_000L}-minute limit and ended by itself."
        NETWORK -> "The phone was offline for too long, so sharing was ended."
        DECLINED -> "$childName chose not to share."
        EXPIRED -> "The request wasn't answered in time."
        CANCELLED -> "You cancelled the request."
        ERROR -> "Sharing stopped because of an error on the phone."
        else -> "The session ended."
    }
}

/** Plain explanations for pictures that could not be taken. Shown as written, never replaced by a fake picture. */
object CaptureFailure {
    const val BLANK = "The screen looked completely black. It may be switched off, or it is showing a protected screen that Android hides from screen capture."
    const val SCREEN_OFF = "The phone's screen is off."
    const val NO_FRAME = "No picture was available yet. Try again in a moment."
    const val NO_SESSION = "No screen-sharing session is active."
    const val ENCODE = "The picture could not be prepared."
    const val UPLOAD = "The picture could not be sent. Check the phone's connection."
    const val TOO_LARGE = "The picture was too large to send."
}

data class CaptureState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val sessionId: String? = null,
    /** When the current status began (request sent, sharing started, or session ended). */
    val since: Long = 0L,
    /** Awaiting: when the request lapses. Active: when the session ends by itself. */
    val expiresAt: Long = 0L,
    val live: Boolean = false,
    val reason: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val lastShotAt: Long = 0L,
    /** Last time the phone reported in. Old while "active" means the phone stopped answering. */
    val heartbeat: Long = 0L,
    /** False when notifications are off on the phone, so it may not have shown the request. */
    val notifOk: Boolean = true,
    val error: String? = null,
    /** "sending", "ok" or "failed" for the latest picture. */
    val transfer: String? = null
)

object CaptureStateCodec {
    fun toJson(s: CaptureState): JsonObject = JsonObject().apply {
        addProperty("st", s.status.code)
        if (s.sessionId != null) addProperty("sid", s.sessionId)
        addProperty("since", s.since)
        addProperty("exp", s.expiresAt)
        addProperty("live", s.live)
        if (s.reason != null) addProperty("why", s.reason)
        addProperty("w", s.width)
        addProperty("h", s.height)
        addProperty("shot", s.lastShotAt)
        addProperty("hb", s.heartbeat)
        addProperty("notif", s.notifOk)
        if (s.error != null) addProperty("err", s.error)
        if (s.transfer != null) addProperty("xfer", s.transfer)
    }

    fun fromJson(o: JsonObject?): CaptureState? {
        if (o == null) return null
        return try {
            CaptureState(
                status = CaptureStatus.fromCode(o.get("st")?.asString),
                sessionId = o.get("sid")?.asString,
                since = o.get("since")?.asLong ?: 0L,
                expiresAt = o.get("exp")?.asLong ?: 0L,
                live = o.get("live")?.asBoolean ?: false,
                reason = o.get("why")?.asString,
                width = o.get("w")?.asInt ?: 0,
                height = o.get("h")?.asInt ?: 0,
                lastShotAt = o.get("shot")?.asLong ?: 0L,
                heartbeat = o.get("hb")?.asLong ?: 0L,
                notifOk = o.get("notif")?.asBoolean ?: true,
                error = o.get("err")?.asString,
                transfer = o.get("xfer")?.asString
            )
        } catch (_: Exception) { null }
    }
}

enum class CaptureCmdType(val code: String) {
    /** Ask the child to start sharing; the child still has to approve. */
    ASK("ask"),
    SHOT("shot"),
    /** Switch the small preview on or off ([CaptureCommand.on]). */
    LIVE("live"),
    /** Ends an active session, or withdraws a request nobody has answered yet. */
    STOP("stop");

    companion object {
        fun fromCode(code: String?): CaptureCmdType? = values().firstOrNull { it.code == code }
    }
}

data class CaptureCommand(val id: String, val type: CaptureCmdType, val ts: Long, val expiresAt: Long, val on: Boolean = false) {
    companion object {
        fun ttlFor(type: CaptureCmdType): Long = if (type == CaptureCmdType.ASK) CapturePolicy.REQUEST_TTL_MS else CapturePolicy.COMMAND_TTL_MS
        fun create(id: String, type: CaptureCmdType, now: Long, on: Boolean = false) = CaptureCommand(id, type, now, now + ttlFor(type), on)
    }
}

object CaptureCommandCodec {
    fun toJson(c: CaptureCommand): JsonObject = JsonObject().apply {
        addProperty("t", c.type.code)
        addProperty("ts", c.ts)
        addProperty("exp", c.expiresAt)
        if (c.type == CaptureCmdType.LIVE) addProperty("on", c.on)
    }

    fun fromJson(id: String, o: JsonObject): CaptureCommand? = try {
        val type = CaptureCmdType.fromCode(o.get("t")?.asString)
        if (type == null || !isValidId(id)) null
        else CaptureCommand(id, type, o.get("ts")?.asLong ?: 0L, o.get("exp")?.asLong ?: 0L, o.get("on")?.asBoolean ?: false)
    } catch (_: Exception) { null }

    /** Ids become database keys and are used as replay-protection keys, so they stay short and plain. */
    fun isValidId(id: String): Boolean = id.length in 8..40 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

/** What the parent left for the child: the requests, and when the parent last had the Screen preview screen open. */
data class CaptureInbox(val commands: List<CaptureCommand>, val viewerAt: Long)

object CaptureInboxCodec {
    fun fromJson(o: JsonObject?): CaptureInbox {
        if (o == null) return CaptureInbox(emptyList(), 0L)
        val commands = ArrayList<CaptureCommand>()
        val cmd = o.get("cmd")
        if (cmd != null && cmd.isJsonObject) {
            cmd.asJsonObject.entrySet().forEach { (id, value) ->
                if (value.isJsonObject) CaptureCommandCodec.fromJson(id, value.asJsonObject)?.let { commands.add(it) }
            }
        }
        val viewer = try { o.get("viewer")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L } catch (_: Exception) { 0L }
        return CaptureInbox(commands, viewer)
    }
}

/** Sorting of what arrived into "do now", "too old" and "already handled". */
object CaptureCommands {
    class Sorted(val due: List<CaptureCommand>, val expired: List<CaptureCommand>, val seen: List<CaptureCommand>)

    fun isExpired(c: CaptureCommand, now: Long): Boolean = now > c.expiresAt + CapturePolicy.CLOCK_SKEW_MS

    fun sort(all: List<CaptureCommand>, processed: Set<String>, now: Long): Sorted {
        val due = ArrayList<CaptureCommand>()
        val expired = ArrayList<CaptureCommand>()
        val seen = ArrayList<CaptureCommand>()
        for (c in all.sortedBy { it.ts }) {
            when {
                c.id in processed -> seen.add(c)
                isExpired(c, now) -> expired.add(c)
                else -> due.add(c)
            }
        }
        return Sorted(due, expired, seen)
    }

    /** Keeps the most recent [keep] ids so the "already handled" list cannot grow forever. */
    fun remember(processed: List<String>, id: String, keep: Int = CapturePolicy.PROCESSED_KEEP): List<String> =
        (processed.filter { it != id } + id).takeLast(keep)
}

enum class CaptureAction { START_PROMPT, TAKE_SHOT, LIVE_ON, LIVE_OFF, END_SESSION, CANCEL_REQUEST, REJECT }

data class CaptureDecision(val action: CaptureAction, val reason: String? = null)

object CaptureLogic {
    /** What the child does with one command, given what its session is doing. Anything that cannot be done is rejected with a reason. */
    fun decide(cmd: CaptureCommand, status: CaptureStatus): CaptureDecision = when (cmd.type) {
        CaptureCmdType.ASK -> when (status) {
            CaptureStatus.ACTIVE -> CaptureDecision(CaptureAction.REJECT, "A session is already running.")
            CaptureStatus.AWAITING -> CaptureDecision(CaptureAction.REJECT, "A request is already waiting for approval.")
            else -> CaptureDecision(CaptureAction.START_PROMPT)
        }
        CaptureCmdType.SHOT ->
            if (status == CaptureStatus.ACTIVE) CaptureDecision(CaptureAction.TAKE_SHOT) else CaptureDecision(CaptureAction.REJECT, CaptureFailure.NO_SESSION)
        CaptureCmdType.LIVE ->
            if (status == CaptureStatus.ACTIVE) CaptureDecision(if (cmd.on) CaptureAction.LIVE_ON else CaptureAction.LIVE_OFF)
            else CaptureDecision(CaptureAction.REJECT, CaptureFailure.NO_SESSION)
        CaptureCmdType.STOP -> when (status) {
            CaptureStatus.ACTIVE -> CaptureDecision(CaptureAction.END_SESSION)
            CaptureStatus.AWAITING -> CaptureDecision(CaptureAction.CANCEL_REQUEST)
            else -> CaptureDecision(CaptureAction.REJECT, "Nothing to stop.")
        }
    }

    /** The preview only produces pictures while the parent is actually looking at it. */
    fun viewerPresent(viewerAt: Long, now: Long): Boolean = viewerAt > 0L && now - viewerAt <= CapturePolicy.VIEWER_FRESH_MS
}

data class CaptureAck(val id: String, val result: String, val reason: String?, val shotId: String?, val ts: Long) {
    companion object {
        const val DONE = "done"
        const val FAILED = "failed"
        const val REJECTED = "rejected"
        const val EXPIRED = "expired"
    }
}

object CaptureAckCodec {
    fun toJson(a: CaptureAck): JsonObject = JsonObject().apply {
        addProperty("r", a.result)
        if (a.reason != null) addProperty("why", a.reason)
        if (a.shotId != null) addProperty("shot", a.shotId)
        addProperty("ts", a.ts)
    }

    fun fromJson(id: String, o: JsonObject?): CaptureAck? {
        if (o == null) return null
        return try { CaptureAck(id, o.get("r")?.asString ?: return null, o.get("why")?.asString, o.get("shot")?.asString, o.get("ts")?.asLong ?: 0L) } catch (_: Exception) { null }
    }
}

data class ShotMeta(val id: String, val ts: Long, val width: Int, val height: Int, val bytes: Int, val chunks: Int)

object ShotCodec {
    fun newId(ts: Long, n: Int): String = String.format(Locale.US, "%013d_%02d", ts.coerceAtLeast(0L), n.coerceIn(0, 99))

    fun toJson(m: ShotMeta): JsonObject = JsonObject().apply {
        addProperty("ts", m.ts); addProperty("w", m.width); addProperty("h", m.height); addProperty("b", m.bytes); addProperty("n", m.chunks)
    }

    fun fromJson(id: String, o: JsonObject): ShotMeta? = try {
        ShotMeta(id, o.get("ts").asLong, o.get("w").asInt, o.get("h").asInt, o.get("b").asInt, o.get("n").asInt)
    } catch (_: Exception) { null }

    /** Oldest ids beyond the newest [keep]; ids sort by time because they start with the timestamp. */
    fun toPrune(ids: Collection<String>, keep: Int): List<String> = ids.sorted().dropLast(keep.coerceAtLeast(0))
}

/** The latest live frame: [seq] grows with every picture so the parent can tell a new one from the one it already has. */
data class LiveMeta(val seq: Long, val ts: Long, val width: Int, val height: Int, val bytes: Int, val chunks: Int, val uploadMs: Long)

object LiveCodec {
    fun toJson(m: LiveMeta): JsonObject = JsonObject().apply {
        addProperty("seq", m.seq); addProperty("ts", m.ts); addProperty("w", m.width); addProperty("h", m.height)
        addProperty("b", m.bytes); addProperty("n", m.chunks); addProperty("up", m.uploadMs)
    }

    fun fromJson(o: JsonObject?): LiveMeta? {
        if (o == null) return null
        return try { LiveMeta(o.get("seq").asLong, o.get("ts").asLong, o.get("w").asInt, o.get("h").asInt, o.get("b").asInt, o.get("n").asInt, o.get("up")?.asLong ?: 0L) } catch (_: Exception) { null }
    }
}

data class CaptureEvent(val ts: Long, val kind: String, val detail: String?)

object CaptureEventCodec {
    fun toJson(e: CaptureEvent): JsonObject = JsonObject().apply {
        addProperty("ts", e.ts); addProperty("k", e.kind); if (e.detail != null) addProperty("d", e.detail)
    }

    fun fromJson(o: JsonObject): CaptureEvent? = try { CaptureEvent(o.get("ts").asLong, o.get("k").asString, o.get("d")?.asString) } catch (_: Exception) { null }

    fun key(ts: Long, n: Int): String = String.format(Locale.US, "%013d_%02d", ts.coerceAtLeast(0L), n.coerceIn(0, 99))

    fun label(kind: String): String = when (kind) {
        "asked" -> "Parent asked to share the screen"
        "approved" -> "Sharing approved on the phone"
        "declined" -> "Not shared (declined)"
        "shot" -> "Screenshot taken"
        "live_on" -> "Live preview on"
        "live_off" -> "Live preview off"
        "ended" -> "Sharing ended"
        "expired" -> "Request expired"
        "rejected" -> "Request not possible"
        else -> kind
    }
}

enum class LinkQuality { UNKNOWN, GOOD, SLOW, STALLED }

object CaptureLink {
    fun of(now: Long, lastFrameAt: Long, uploadMs: Long): LinkQuality {
        if (lastFrameAt <= 0L) return LinkQuality.UNKNOWN
        val age = now - lastFrameAt
        return when {
            age > 15_000L -> LinkQuality.STALLED
            age > 6_000L || uploadMs > 2_500L -> LinkQuality.SLOW
            else -> LinkQuality.GOOD
        }
    }
}

/** A completely black frame means the phone is not showing anything capturable (off, or a protected screen). */
object BlankFrame {
    fun isBlank(samples: IntArray): Boolean =
        samples.isNotEmpty() && samples.all { ((it shr 16) and 0xFF) <= 3 && ((it shr 8) and 0xFF) <= 3 && (it and 0xFF) <= 3 }

    /** A grid of sample positions across the frame, kept off the very edge. */
    fun points(width: Int, height: Int, grid: Int = 16): List<Pair<Int, Int>> {
        if (width <= 0 || height <= 0 || grid <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (gy in 0 until grid) for (gx in 0 until grid) {
            out.add(((gx + 0.5) * width / grid).toInt().coerceIn(0, width - 1) to ((gy + 0.5) * height / grid).toInt().coerceIn(0, height - 1))
        }
        return out
    }
}

/** How the parent should present the session, from what the child last reported and how old that report is. */
enum class CaptureHeadline { NONE, WAITING, SHARING, NOT_RESPONDING, ENDED }

data class CaptureView(
    val headline: CaptureHeadline,
    val reason: String?,
    val canAsk: Boolean,
    val canShot: Boolean,
    val canLive: Boolean,
    val canStop: Boolean
) {
    companion object {
        fun of(state: CaptureState?, now: Long): CaptureView {
            val s = state ?: CaptureState()
            return when (s.status) {
                CaptureStatus.IDLE -> CaptureView(CaptureHeadline.NONE, null, true, false, false, false)
                CaptureStatus.ENDED -> CaptureView(CaptureHeadline.ENDED, s.reason, true, false, false, false)
                CaptureStatus.AWAITING ->
                    if (s.expiresAt > 0L && now > s.expiresAt + CapturePolicy.CLOCK_SKEW_MS) CaptureView(CaptureHeadline.ENDED, CaptureReasons.EXPIRED, true, false, false, false)
                    else CaptureView(CaptureHeadline.WAITING, null, false, false, false, true)
                CaptureStatus.ACTIVE -> when {
                    s.expiresAt > 0L && now > s.expiresAt + CapturePolicy.CLOCK_SKEW_MS -> CaptureView(CaptureHeadline.ENDED, CaptureReasons.TIMEOUT, true, false, false, false)
                    s.heartbeat > 0L && now - s.heartbeat > CapturePolicy.STATE_STALE_MS -> CaptureView(CaptureHeadline.NOT_RESPONDING, null, false, false, false, true)
                    else -> CaptureView(CaptureHeadline.SHARING, null, false, true, true, true)
                }
            }
        }
    }
}
