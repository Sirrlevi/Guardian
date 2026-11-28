package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirrlevi.guardian.core.Delivery
import com.sirrlevi.guardian.core.DeliveryState
import com.sirrlevi.guardian.core.ReportCategory
import com.sirrlevi.guardian.core.SendResult
import com.sirrlevi.guardian.core.TelegramClient
import com.sirrlevi.guardian.core.TelegramDaily
import com.sirrlevi.guardian.core.TelegramKeys
import com.sirrlevi.guardian.core.TelegramQueue
import com.sirrlevi.guardian.core.TelegramScrub
import com.sirrlevi.guardian.core.TelegramSettings
import com.sirrlevi.guardian.core.TelegramTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.coroutines.CoroutineContext

/** The words the Telegram screen and model use, kept together so tests and the screen agree. */
object TelegramScreenTexts {
    const val INTRO_TITLE = "Optional: reports to your own Telegram chat"
    const val INTRO = "Guardian has no server of its own, so the Parent app sends these reports itself, only while it is open. " +
        "Reports that couldn't be sent wait and go out the next time you open it."
    const val LIMITS_TITLE = "How your bot token is kept, and what that can't protect"
    const val LIMITS = "The token is encrypted with a key kept in this phone's Android Keystore. It is never in Guardian's code, in logs or in plain files, " +
        "and other apps can't read it. That doesn't help if someone unlocks this phone and uses Guardian, if the phone is rooted, or against Telegram itself: " +
        "bot chats are not end-to-end encrypted. Use a bot only you control and a chat only you can read."
    const val NOT_OFFERED_TITLE = "What is never sent"
    const val NOT_OFFERED = "The full text of messages, photos, videos, screenshots and Shared media are never sent to Telegram, and automatic media backup to Telegram is not offered: " +
        "it would copy your child's private pictures to a third party. A Safety alert report is the alert as you see it in Safety alerts, and that can name an app, " +
        "a sender, a link or a flagged word. Leave that category off if you don't want those details in Telegram."
    const val TURN_ON_FIRST = "Turn reports on first."
    const val NO_TOKEN = "No bot token saved on this phone."
    const val HAS_TOKEN = "A bot token is saved on this phone (encrypted). It is never shown again."
    const val NOT_TESTED = "Not tested yet."
    const val TEST_FIRST = "Test the connection first: reports can only be turned on once Telegram has delivered a test message."
    const val NEED_CREDENTIALS = "Save a bot token and chat ID first."
    const val BAD_TOKEN = "That doesn't look like a bot token. It looks like 123456789:AA… and comes from @BotFather."
    const val BAD_CHAT = "That doesn't look like a chat ID. Use the number of your chat (for example -1001234567890) or @channelname."
    const val SAVE_FAILED = "Couldn't save it on this phone."
}

/**
 * Telegram reports for the parent. The token is held only inside a call, never in state that is shown or logged. A report is "sent" only
 * after Telegram confirmed it with a message id; everything else is shown as waiting, will try again, not sent or not confirmed.
 */
class TelegramModel(
    private val backend: () -> TelegramBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    @Volatile var io: CoroutineContext = Dispatchers.IO,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val zone: () -> TimeZone = { TimeZone.getDefault() }
) {
    var loaded by mutableStateOf(false); private set
    var settings by mutableStateOf(TelegramSettings()); private set
    var hasCredentials by mutableStateOf(false); private set
    var chatId by mutableStateOf(""); private set
    var connection by mutableStateOf<ConnectionStatus?>(null); private set
    /** Newest first. */
    var items by mutableStateOf<List<Delivery>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var noticeIsProblem by mutableStateOf(false); private set
    /** Why the last sending pass stopped early, if it did. */
    var passNote by mutableStateOf<String?>(null); private set

    private val mutex = Mutex()
    private var queue: List<Delivery> = emptyList()
    private val recentSends = ArrayList<Long>()

    val waitingCount get() = items.count { it.state.pending }
    val sentCount get() = items.count { it.state == DeliveryState.SENT }
    val problemCount get() = items.count { it.state == DeliveryState.FAILED || it.state == DeliveryState.UNCONFIRMED }

    private fun say(text: String?, problem: Boolean) { notice = text; noticeIsProblem = problem }
    fun clearNotice() { notice = null; noticeIsProblem = false }

    private fun show(list: List<Delivery>) { queue = list; items = list.sortedByDescending { it.createdAt } }

    // ------------------------------------------------------------ loading

    suspend fun load() = withContext(io) { mutex.withLock { loadLocked() } }

    private fun loadLocked() {
        val b = backend()
        val s = b.settings()
        settings = s
        val creds = b.credentials()
        hasCredentials = creds != null
        chatId = creds?.chatId ?: ""
        connection = if (creds != null) b.connection() else null
        val pruned = TelegramQueue.prune(b.outbox(), clock(), s.retentionDays)
        show(pruned)
        loaded = true
    }

    private fun ensureLoaded() { if (!loaded) loadLocked() }

    // ------------------------------------------------------------ credentials and the test

    suspend fun saveCredentials(token: String, chat: String): Boolean = act(TelegramScreenTexts.SAVE_FAILED) {
        val t = token.trim(); val c = chat.trim()
        when {
            !TelegramScrub.looksLikeToken(t) -> { say(TelegramScreenTexts.BAD_TOKEN, true); false }
            !TelegramScrub.looksLikeChatId(c) -> { say(TelegramScreenTexts.BAD_CHAT, true); false }
            else -> {
                val b = backend()
                b.saveCredentials(TelegramCredentials(t, c))
                hasCredentials = true; chatId = c; connection = null
                // A different bot or chat has to be tested again before anything is sent to it.
                b.saveConnection(null)
                turnOff(b)
                say("Saved. Now test the connection.", false)
                true
            }
        }
    }

    suspend fun removeCredentials(): Boolean = act(TelegramScreenTexts.SAVE_FAILED) {
        val b = backend()
        b.clearCredentials()
        hasCredentials = false; chatId = ""; connection = null
        b.saveConnection(null)
        turnOff(b)
        say("Removed from this phone. Reports are off.", false)
        true
    }

    /** Sends one test message. The connection counts as working only when Telegram confirms that message. */
    suspend fun testConnection(): Boolean = act("Couldn't test the connection.") {
        val b = backend()
        val creds = b.credentials()
        if (creds == null) { say(TelegramScreenTexts.NEED_CREDENTIALS, true); return@act false }
        val status = try {
            val client = TelegramClient(b.transport(), creds.token, creds.chatId)
            when (val me = client.getMe()) {
                is SendResult.Failed -> ConnectionStatus(clock(), false, TelegramScrub.scrub(me.failure.message, creds.token))
                is SendResult.Sent -> when (val sent = client.sendMessage(TelegramTexts.test(settings.enabled))) {
                    is SendResult.Failed -> ConnectionStatus(clock(), false, TelegramScrub.scrub(sent.failure.message, creds.token), me.botName)
                    is SendResult.Sent -> ConnectionStatus(
                        clock(), true, "Connected" + (me.botName?.let { " to @$it" } ?: "") + ". Telegram delivered a test message (message ${sent.messageId}).", me.botName
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionStatus(clock(), false, TelegramScrub.scrub(e.message ?: "Unexpected problem.", creds.token).take(160))
        }
        b.saveConnection(status)
        connection = status
        if (!status.ok && settings.enabled) turnOff(b)
        status.ok
    }

    // ------------------------------------------------------------ settings

    suspend fun setEnabled(on: Boolean): Boolean = act(TelegramScreenTexts.SAVE_FAILED) {
        val b = backend()
        if (on) {
            when {
                !hasCredentials -> { say(TelegramScreenTexts.NEED_CREDENTIALS, true); return@act false }
                connection?.ok != true -> { say(TelegramScreenTexts.TEST_FIRST, true); return@act false }
            }
            if (!settings.enabled) {
                // Only what happens from now on is reported, never the older alerts already on the screen.
                val next = settings.copy(enabled = true, reportFrom = clock())
                b.saveSettings(next); settings = next
            }
            say("Reports are on. Only things from now on are sent.", false)
        } else {
            turnOff(b)
            say("Reports are off. Anything that was waiting was discarded.", false)
        }
        true
    }

    suspend fun setCategory(category: ReportCategory, on: Boolean): Boolean = act(TelegramScreenTexts.SAVE_FAILED) {
        val b = backend()
        val next = settings.copy(categories = if (on) settings.categories + category else settings.categories - category)
        b.saveSettings(next); settings = next
        if (!on) { val kept = queue.filter { !(it.state.pending && it.category == category) }; b.saveOutbox(kept); show(kept) }
        true
    }

    suspend fun setRetention(days: Int): Boolean = act(TelegramScreenTexts.SAVE_FAILED) {
        val b = backend()
        val next = settings.copy(retentionDays = days).normalized()
        b.saveSettings(next); settings = next
        val kept = TelegramQueue.prune(queue, clock(), next.retentionDays)
        b.saveOutbox(kept); show(kept)
        true
    }

    private fun turnOff(b: TelegramBackend) {
        val next = settings.copy(enabled = false)
        if (settings.enabled || queue.any { it.state.pending }) {
            b.saveSettings(next); settings = next
            val kept = TelegramQueue.discardPending(queue)
            b.saveOutbox(kept); show(kept)
        }
    }

    // ------------------------------------------------------------ reporting and delivery

    /** Called after each Safety alerts check. Queues what is new and, if reports are on, sends what is due. */
    suspend fun report(checked: SafetyModel.Checked) {
        withContext(io) {
            mutex.withLock {
                try {
                    ensureLoaded()
                    if (!settings.enabled) return@withLock
                    val now = clock()
                    var list = queue
                    for (a in checked.alerts) {
                        val category = ReportCategory.of(a.kind)
                        if (!settings.on(category) || a.ts < settings.reportFrom) continue
                        list = TelegramQueue.enqueue(list, TelegramKeys.alert(checked.deviceId, a.id), category, TelegramTexts.alert(checked.deviceName, a, zone()), now)
                    }
                    if (settings.on(ReportCategory.DAILY_SUMMARY)) {
                        TelegramDaily.pick(now, zone(), settings.reportFrom, checked.days)?.let { day ->
                            list = TelegramQueue.enqueue(list, TelegramKeys.daily(checked.deviceId, day.dayKey), ReportCategory.DAILY_SUMMARY, TelegramTexts.daily(checked.deviceName, day), now)
                        }
                    }
                    list = TelegramQueue.prune(list, now, settings.retentionDays)
                    if (list != queue) { backend().saveOutbox(list); show(list) }
                    deliverLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("Telegram reports: ${TelegramScrub.scrub(e.message ?: "unexpected problem").take(120)}", true)
                }
            }
        }
    }

    /** Sends what is due now. Safe to call often. */
    suspend fun deliverDue() {
        withContext(io) {
            mutex.withLock {
                try { ensureLoaded(); deliverLocked() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    say("Telegram reports: ${TelegramScrub.scrub(e.message ?: "unexpected problem").take(120)}", true)
                }
            }
        }
    }

    private fun deliverLocked() {
        if (!settings.enabled) return
        val b = backend()
        val creds = b.credentials()
        if (creds == null) { passNote = TelegramScreenTexts.NEED_CREDENTIALS; return }
        if (queue.none { (it.state == DeliveryState.WAITING || it.state == DeliveryState.RETRYING) && it.nextAttemptAt <= clock() }) return
        val client = TelegramClient(b.transport(), creds.token, creds.chatId)
        val result = TelegramQueue.runPass(queue, clock, recentSends, { text -> client.sendMessage(text) }, { list -> b.saveOutbox(list); show(list) }, pause)
        show(result.items)
        passNote = result.stoppedBecause?.let { TelegramScrub.scrub(it, creds.token) }
    }

    /** The parent asked to send now. */
    suspend fun sendNow(): Boolean = act("Couldn't send.") {
        if (!settings.enabled) { say(TelegramScreenTexts.TURN_ON_FIRST, true); return@act false }
        deliverLocked(); true
    }

    suspend fun retry(key: String): Boolean = act("Couldn't retry.") {
        if (!settings.enabled) { say(TelegramScreenTexts.TURN_ON_FIRST, true); return@act false }
        val b = backend()
        val list = TelegramQueue.retry(queue, key)
        b.saveOutbox(list); show(list)
        deliverLocked()
        true
    }

    suspend fun retryAllNotSent(): Boolean = act("Couldn't retry.") {
        if (!settings.enabled) { say(TelegramScreenTexts.TURN_ON_FIRST, true); return@act false }
        val b = backend()
        val list = TelegramQueue.retryAllFailed(queue)
        b.saveOutbox(list); show(list)
        deliverLocked()
        true
    }

    /** Forgets finished reports. Nothing from before now is reported afterwards, so cleared alerts do not come back. */
    suspend fun clearHistory(): Boolean = act(TelegramScreenTexts.SAVE_FAILED) {
        val b = backend()
        val list = TelegramQueue.clearHistory(queue)
        val next = settings.copy(reportFrom = clock())
        b.saveSettings(next); settings = next
        b.saveOutbox(list); show(list)
        say("History cleared.", false)
        true
    }

    /** Signing out of Guardian removes everything about Telegram from this phone. */
    suspend fun wipe() {
        withContext(io) {
            mutex.withLock {
                try {
                    val b = backend()
                    b.clearCredentials(); b.saveConnection(null); b.saveSettings(TelegramSettings()); b.saveOutbox(emptyList())
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                settings = TelegramSettings(); hasCredentials = false; chatId = ""; connection = null; show(emptyList()); passNote = null; loaded = true
                notice = null; noticeIsProblem = false
            }
        }
    }

    private suspend fun act(failure: String, block: () -> Boolean): Boolean = withContext(io) {
        mutex.withLock {
            busy = true
            try {
                ensureLoaded()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("$failure ${TelegramScrub.scrub(e.message ?: "unexpected problem").take(120)}", true)
                false
            } finally {
                busy = false
            }
        }
    }

    /** UI tests only: shows fixed state without a backend. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(
        settings: TelegramSettings = TelegramSettings(), hasCredentials: Boolean = false, chatId: String = "", connection: ConnectionStatus? = null,
        items: List<Delivery> = emptyList(), notice: String? = null, noticeIsProblem: Boolean = false, passNote: String? = null
    ) {
        this.settings = settings; this.hasCredentials = hasCredentials; this.chatId = chatId; this.connection = connection
        show(items); this.notice = notice; this.noticeIsProblem = noticeIsProblem; this.passNote = passNote; loaded = true
    }
}
