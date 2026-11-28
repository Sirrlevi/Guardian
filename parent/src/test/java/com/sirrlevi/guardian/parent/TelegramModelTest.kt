package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.AlertKind
import com.sirrlevi.guardian.core.AlertLevel
import com.sirrlevi.guardian.core.Delivery
import com.sirrlevi.guardian.core.DeliveryCodec
import com.sirrlevi.guardian.core.DeliveryState
import com.sirrlevi.guardian.core.ReportCategory
import com.sirrlevi.guardian.core.SafetyAlert
import com.sirrlevi.guardian.core.SafetyDay
import com.sirrlevi.guardian.core.SafetyDays
import com.sirrlevi.guardian.core.TelegramKeys
import com.sirrlevi.guardian.core.TelegramSettings
import com.sirrlevi.guardian.core.TelegramTransport
import com.sirrlevi.guardian.core.TelegramTransportException
import com.sirrlevi.guardian.core.TransportResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone
import kotlin.coroutines.EmptyCoroutineContext

/** A scripted Telegram: records every request and answers from a list; once the list is empty it confirms with a fresh message id. */
class ScriptedTransport : TelegramTransport {
    val urls = ArrayList<String>()
    val forms = ArrayList<String>()
    val answers = ArrayList<() -> TransportResponse>()
    var nextId = 100L
    var onPost: (String) -> Unit = {}

    override fun post(url: String, form: String): TransportResponse {
        urls.add(url); forms.add(form)
        onPost(url)
        if (answers.isNotEmpty()) return answers.removeAt(0).invoke()
        return if (url.endsWith("/getMe")) me().invoke() else ok(nextId++).invoke()
    }

    val sends get() = urls.count { it.endsWith("/sendMessage") }

    companion object {
        fun ok(id: Long): () -> TransportResponse = { TransportResponse(200, """{"ok":true,"result":{"message_id":$id}}""") }
        fun me(): () -> TransportResponse = { TransportResponse(200, """{"ok":true,"result":{"id":1,"is_bot":true,"username":"guardian_test_bot"}}""") }
        fun fail(code: Int, description: String): () -> TransportResponse = { TransportResponse(code, """{"ok":false,"error_code":$code,"description":"$description"}""") }
        fun noConnection(): () -> TransportResponse = { throw TelegramTransportException(false, "Unable to resolve host") }
        fun brokeAfterSending(): () -> TransportResponse = { throw TelegramTransportException(true, "connection reset") }
    }
}

/** The phone's own storage and the network, in memory. */
class FakeTelegramBackend : TelegramBackend {
    var settingsNow = TelegramSettings()
    var outboxNow: List<Delivery> = emptyList()
    var connectionNow: ConnectionStatus? = null
    var creds: TelegramCredentials? = null
    val scripted = ScriptedTransport()
    var failSaveSettings: Exception? = null
    var failSaveOutbox: Exception? = null
    var saves = 0

    override fun settings() = settingsNow
    override fun saveSettings(settings: TelegramSettings) { failSaveSettings?.let { throw it }; settingsNow = settings }
    override fun outbox() = outboxNow
    override fun saveOutbox(items: List<Delivery>) { failSaveOutbox?.let { throw it }; saves++; outboxNow = items }
    override fun connection() = connectionNow
    override fun saveConnection(status: ConnectionStatus?) { connectionNow = status }
    override fun credentials() = creds
    override fun saveCredentials(credentials: TelegramCredentials) { creds = credentials }
    override fun clearCredentials() { creds = null }
    override fun transport(): TelegramTransport = scripted
}

/** Plain JVM test on purpose (see SafetyModelTest). */
class TelegramModelTest {
    private val token = "123456789" + ":" + "AAEhBOweik6ad9r_QXMENQjcrOaazbPvyYo" // assembled in code: a made-up token, kept out of the source as one literal
    private val chat = "555000111"
    private val utc = TimeZone.getTimeZone("UTC")
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    /** 08:00 UTC, after the morning summary hour. */
    private var time = 1_800_000_000_000L
    private val paused = ArrayList<Long>()

    private val backend = FakeTelegramBackend()
    private val model = TelegramModel({ backend }, { time }, EmptyCoroutineContext, { paused.add(it) }, { utc })

    private fun alert(id: String, kind: AlertKind = AlertKind.SUSPICIOUS_LINK, ts: Long = time - 10 * minute) =
        SafetyAlert(id = id, kind = kind, level = AlertLevel.REVIEW, title = "Title $id", reason = "Reason $id", ts = ts)

    private fun checked(vararg alerts: SafetyAlert, days: List<SafetyDay> = emptyList()) = SafetyModel.Checked("kid1", "Aarav's phone", time, alerts.toList(), days)

    /** Credentials saved, tested and reports on since an hour ago. */
    private fun ready(categories: Set<ReportCategory> = setOf(ReportCategory.SAFETY, ReportCategory.DEVICE_STATUS)) = runBlocking {
        backend.creds = TelegramCredentials(token, chat)
        backend.connectionNow = ConnectionStatus(time - minute, true, "Connected")
        backend.settingsNow = TelegramSettings(enabled = true, categories = categories, reportFrom = time - hour)
        model.load()
    }

    private fun item(key: String, state: DeliveryState, category: ReportCategory = ReportCategory.SAFETY, createdAt: Long = time - minute, messageId: Long = 0L) =
        Delivery(key, category, "Guardian · Aarav's phone\nReview recommended\n\nTitle $key", createdAt, state = state, messageId = messageId, sentAt = if (state == DeliveryState.SENT) createdAt else 0L)

    private fun everythingTheModelShows() = listOf(model.notice, model.chatId, model.connection?.message, model.passNote) + model.items.flatMap { listOf(it.lastError, it.text) }

    // ---------------------------------------------------------------- credentials

    @Test fun beforeAnythingIsSetUpNothingIsOnAndNothingIsSent() = runBlocking {
        model.load()
        assertTrue(model.loaded)
        assertFalse(model.settings.enabled)
        assertFalse(model.hasCredentials)
        model.report(checked(alert("a1")))
        model.deliverDue()
        assertTrue(backend.scripted.urls.isEmpty())
        assertTrue(model.items.isEmpty())
    }

    @Test fun aTokenOrChatThatDoesNotLookRightIsRefusedAndNothingIsStored() = runBlocking {
        assertFalse(model.saveCredentials("hello", chat))
        assertEquals(TelegramScreenTexts.BAD_TOKEN, model.notice)
        assertFalse(model.saveCredentials(token, "my chat"))
        assertEquals(TelegramScreenTexts.BAD_CHAT, model.notice)
        assertNull(backend.creds)
        assertFalse(model.hasCredentials)
        assertTrue(model.noticeIsProblem)
    }

    @Test fun savingCredentialsKeepsThemInTheVaultOnlyAndNeverInWhatTheScreenShows() = runBlocking {
        assertTrue(model.saveCredentials("  $token ", " $chat "))
        assertEquals(token, backend.creds?.token)
        assertEquals(chat, backend.creds?.chatId)
        assertTrue(model.hasCredentials)
        assertEquals(chat, model.chatId)
        assertTrue(everythingTheModelShows().none { it?.contains(token) == true })
        assertFalse(backend.creds.toString().contains(token))
    }

    @Test fun newCredentialsMustBeTestedAgainAndTurnReportsOff() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("w", DeliveryState.WAITING), item("s", DeliveryState.SENT, messageId = 7))
        model.load()
        assertTrue(model.saveCredentials(token.replace("AAE", "AAF"), chat))
        assertNull(model.connection)
        assertNull(backend.connectionNow)
        assertFalse(model.settings.enabled)
        assertEquals(listOf("s"), model.items.map { it.key })
    }

    @Test fun removingTheTokenTurnsReportsOffAndForgetsTheConnection() = runBlocking {
        ready()
        assertTrue(model.removeCredentials())
        assertNull(backend.creds)
        assertFalse(model.hasCredentials)
        assertFalse(model.settings.enabled)
        assertNull(backend.connectionNow)
    }

    // ---------------------------------------------------------------- the connection test

    @Test fun theConnectionCountsOnlyWhenTelegramConfirmsARealTestMessage() = runBlocking {
        model.saveCredentials(token, chat)
        assertTrue(model.testConnection())
        val c = model.connection!!
        assertTrue(c.ok)
        assertTrue(c.message.contains("@guardian_test_bot"))
        assertTrue(c.message.contains("message 100"))
        assertEquals(listOf("/getMe", "/sendMessage"), backend.scripted.urls.map { it.substringAfterLast("bot$token") })
        assertEquals(c, backend.connectionNow)
    }

    @Test fun aRejectedTokenIsReportedAndNoTestMessageIsSent() = runBlocking {
        model.saveCredentials(token, chat)
        backend.scripted.answers.add(ScriptedTransport.fail(401, "Unauthorized"))
        assertFalse(model.testConnection())
        assertFalse(model.connection!!.ok)
        assertEquals(1, backend.scripted.urls.size)
        assertEquals(0, backend.scripted.sends)
    }

    @Test fun aChatThatCannotBeWrittenToMeansNotConnectedEvenIfTheTokenIsFine() = runBlocking {
        model.saveCredentials(token, chat)
        backend.scripted.answers.add(ScriptedTransport.me())
        backend.scripted.answers.add(ScriptedTransport.fail(400, "Bad Request: chat not found"))
        assertFalse(model.testConnection())
        val c = model.connection!!
        assertFalse(c.ok)
        assertEquals("guardian_test_bot", c.botName)
        assertFalse(model.settings.enabled)
    }

    @Test fun noConnectionAtAllIsReportedPlainly() = runBlocking {
        model.saveCredentials(token, chat)
        backend.scripted.answers.add(ScriptedTransport.noConnection())
        assertFalse(model.testConnection())
        assertFalse(model.connection!!.ok)
        assertTrue(model.connection!!.message.isNotBlank())
    }

    @Test fun anUnexpectedFailureCarryingTheTokenNeverShowsIt() = runBlocking {
        model.saveCredentials(token, chat)
        backend.scripted.onPost = { throw IllegalStateException("boom while calling https://api.telegram.org/bot$token/getMe") }
        assertFalse(model.testConnection())
        val shown = everythingTheModelShows() + backend.connectionNow?.message
        assertTrue(shown.none { it?.contains(token) == true })
        assertTrue(shown.none { it?.contains("AAEhBOweik6ad9r") == true })
    }

    @Test fun aConnectionTestWithoutCredentialsSaysWhatIsMissing() = runBlocking {
        assertFalse(model.testConnection())
        assertEquals(TelegramScreenTexts.NEED_CREDENTIALS, model.notice)
        assertTrue(backend.scripted.urls.isEmpty())
    }

    // ---------------------------------------------------------------- turning reports on and off

    @Test fun reportsCannotBeTurnedOnWithoutCredentialsOrASuccessfulTest() = runBlocking {
        assertFalse(model.setEnabled(true))
        assertEquals(TelegramScreenTexts.NEED_CREDENTIALS, model.notice)
        model.saveCredentials(token, chat)
        assertFalse(model.setEnabled(true))
        assertEquals(TelegramScreenTexts.TEST_FIRST, model.notice)
        backend.scripted.answers.add(ScriptedTransport.fail(401, "Unauthorized"))
        model.testConnection()
        assertFalse(model.setEnabled(true))
        assertFalse(model.settings.enabled)
        assertFalse(backend.settingsNow.enabled)
    }

    @Test fun turningReportsOnStartsFromNowSoOlderAlertsAreNeverSent() = runBlocking {
        model.saveCredentials(token, chat)
        model.testConnection()
        assertTrue(model.setEnabled(true))
        assertTrue(model.settings.enabled)
        assertEquals(time, model.settings.reportFrom)
        val before = backend.scripted.sends
        model.report(checked(alert("old", ts = time - hour), alert("new", ts = time + minute)))
        assertEquals(listOf(TelegramKeys.alert("kid1", "new")), model.items.map { it.key })
        assertEquals(before + 1, backend.scripted.sends)
    }

    @Test fun turningReportsOffDiscardsWhatWasWaitingButKeepsTheRecord() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("w", DeliveryState.WAITING), item("r", DeliveryState.RETRYING), item("s", DeliveryState.SENT, messageId = 9), item("f", DeliveryState.FAILED))
        model.load()
        assertTrue(model.setEnabled(false))
        assertFalse(backend.settingsNow.enabled)
        assertEquals(setOf("s", "f"), backend.outboxNow.map { it.key }.toSet())
        model.report(checked(alert("late")))
        model.deliverDue()
        assertEquals(0, backend.scripted.sends)
    }

    // ---------------------------------------------------------------- what gets reported

    @Test fun aSafetyAlertIsSentAsPlainTextWithItsReasonAndSucceedsOnlyWithAMessageId() = runBlocking {
        ready()
        model.report(checked(alert("a1")))
        val d = model.items.single()
        assertEquals(DeliveryState.SENT, d.state)
        assertEquals(100L, d.messageId)
        assertEquals(TelegramKeys.alert("kid1", "a1"), d.key)
        val form = backend.scripted.forms.single()
        assertFalse(form.contains("parse_mode"))
        assertTrue(java.net.URLDecoder.decode(form, "UTF-8").contains("Reason a1"))
        assertEquals(1, model.sentCount)
        assertEquals(0, model.waitingCount)
    }

    @Test fun theSameAlertIsNeverReportedTwice() = runBlocking {
        ready()
        model.report(checked(alert("a1")))
        model.report(checked(alert("a1")))
        model.report(checked(alert("a1")))
        assertEquals(1, backend.scripted.sends)
        assertEquals(1, model.items.size)
    }

    @Test fun onlyTheChosenCategoriesAreReported() = runBlocking {
        ready(categories = setOf(ReportCategory.DEVICE_STATUS))
        model.report(checked(alert("link", AlertKind.SUSPICIOUS_LINK), alert("off", AlertKind.MONITORING_OFF)))
        assertEquals(listOf(TelegramKeys.alert("kid1", "off")), model.items.map { it.key })
        assertEquals(ReportCategory.DEVICE_STATUS, model.items.single().category)
    }

    @Test fun switchingACategoryOffDiscardsWhatWasWaitingInIt() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("w1", DeliveryState.WAITING, ReportCategory.SAFETY), item("w2", DeliveryState.WAITING, ReportCategory.DEVICE_STATUS), item("s", DeliveryState.SENT, ReportCategory.SAFETY, messageId = 3))
        model.load()
        assertTrue(model.setCategory(ReportCategory.SAFETY, false))
        assertEquals(setOf("w2", "s"), model.items.map { it.key }.toSet())
        assertFalse(ReportCategory.SAFETY in backend.settingsNow.categories)
    }

    @Test fun aSummaryOfYesterdayIsSentOnceAndOnlyWhenTheCategoryIsOn() = runBlocking {
        ready(categories = setOf(ReportCategory.SAFETY, ReportCategory.DAILY_SUMMARY))
        backend.settingsNow = backend.settingsNow.copy(reportFrom = time - 3 * day)
        model.load()
        val yesterday = SafetyDay(SafetyDays.key(time - day, utc), 7_500, listOf("YouTube" to 3_600, "Chrome" to 1_800))
        model.report(checked(days = listOf(yesterday)))
        model.report(checked(days = listOf(yesterday)))
        val d = model.items.single()
        assertEquals(TelegramKeys.daily("kid1", yesterday.dayKey), d.key)
        assertEquals(DeliveryState.SENT, d.state)
        assertTrue(d.text.contains("2 h 5 min"))
        assertEquals(1, backend.scripted.sends)
    }

    @Test fun noSummaryWhenTheCategoryIsOff() = runBlocking {
        ready()
        backend.settingsNow = backend.settingsNow.copy(reportFrom = time - 3 * day)
        model.load()
        model.report(checked(days = listOf(SafetyDay(SafetyDays.key(time - day, utc), 7_500, emptyList()))))
        assertEquals(0, backend.scripted.sends)
    }

    // ---------------------------------------------------------------- delivery that goes wrong

    @Test fun aReportIsSavedAsSendingBeforeTheRequestSoAStopCannotCauseADuplicate() = runBlocking {
        ready()
        val seen = ArrayList<DeliveryState>()
        backend.scripted.onPost = { seen.add(backend.outboxNow.single().state) }
        model.report(checked(alert("a1")))
        assertEquals(listOf(DeliveryState.SENDING), seen)
        assertEquals(DeliveryState.SENT, backend.outboxNow.single().state)
    }

    @Test fun noConnectionMeansTryingAgainLaterNeverSent() = runBlocking {
        ready()
        backend.scripted.answers.add(ScriptedTransport.noConnection())
        model.report(checked(alert("a1")))
        val d = model.items.single()
        assertEquals(DeliveryState.RETRYING, d.state)
        assertEquals(0L, d.messageId)
        assertTrue(d.nextAttemptAt > time)
        assertEquals(0, model.sentCount)
        model.deliverDue()
        assertEquals("not yet due", 1, backend.scripted.sends)
        time += 2 * minute
        model.deliverDue()
        assertEquals(DeliveryState.SENT, model.items.single().state)
        assertEquals(2, backend.scripted.sends)
    }

    @Test fun aWrongTokenFailsTheReportAndStopsTheRest() = runBlocking {
        ready()
        backend.scripted.answers.add(ScriptedTransport.fail(401, "Unauthorized"))
        model.report(checked(alert("a1"), alert("a2")))
        assertEquals(1, backend.scripted.sends)
        assertEquals(1, model.problemCount)
        assertEquals(1, model.waitingCount)
        assertNotNull(model.passNote)
        assertTrue(model.items.none { it.state == DeliveryState.SENT })
    }

    @Test fun aConnectionThatBrokeAfterSendingIsNotConfirmedAndIsNeverResentOnItsOwn() = runBlocking {
        ready()
        backend.scripted.answers.add(ScriptedTransport.brokeAfterSending())
        model.report(checked(alert("a1")))
        val key = model.items.single().key
        assertEquals(DeliveryState.UNCONFIRMED, model.items.single().state)
        time += 2 * day
        model.deliverDue(); model.deliverDue()
        assertEquals(1, backend.scripted.sends)
        assertEquals(DeliveryState.UNCONFIRMED, model.items.single().state)
        assertTrue(model.retry(key))
        assertEquals(DeliveryState.SENT, model.items.single().state)
        assertEquals(2, backend.scripted.sends)
    }

    @Test fun aReportLeftSendingByAStopComesBackAsNotConfirmed() = runBlocking {
        ready()
        // Through the same JSON the phone's storage uses: a report saved as "sending" is read back as "not confirmed".
        backend.outboxNow = DeliveryCodec.listFromJson(DeliveryCodec.listToJson(listOf(item("sending1", DeliveryState.SENDING))))
        model.load()
        assertEquals(DeliveryState.UNCONFIRMED, model.items.single().state)
        model.deliverDue()
        assertEquals(0, backend.scripted.sends)
    }

    @Test fun retryAllOnlyRetriesWhatFailedAndNeverResendsAnUnconfirmedOrSentReport() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("f1", DeliveryState.FAILED), item("f2", DeliveryState.FAILED), item("u", DeliveryState.UNCONFIRMED), item("s", DeliveryState.SENT, messageId = 4))
        model.load()
        assertTrue(model.retryAllNotSent())
        assertEquals(2, backend.scripted.sends)
        assertEquals(3, model.sentCount)
        assertEquals(DeliveryState.UNCONFIRMED, model.items.first { it.key == "u" }.state)
        assertEquals(4L, model.items.first { it.key == "s" }.messageId)
    }

    @Test fun retryAndSendNowNeedReportsToBeOn() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("f1", DeliveryState.FAILED))
        model.load()
        model.setEnabled(false)
        assertFalse(model.retry("f1"))
        assertEquals(TelegramScreenTexts.TURN_ON_FIRST, model.notice)
        assertFalse(model.retryAllNotSent())
        assertFalse(model.sendNow())
        assertEquals(0, backend.scripted.sends)
    }

    @Test fun nothingIsSentWithoutCredentialsEvenIfReportsAreOn() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("w", DeliveryState.WAITING))
        model.load()
        backend.creds = null
        model.deliverDue()
        assertEquals(0, backend.scripted.sends)
        assertEquals(TelegramScreenTexts.NEED_CREDENTIALS, model.passNote)
        assertEquals(DeliveryState.WAITING, model.items.single().state)
    }

    @Test fun aBurstIsSentInSmallBatchesWithPausesBetweenReports() = runBlocking {
        ready()
        backend.outboxNow = (1..14).map { item("w$it", DeliveryState.WAITING, createdAt = time - it * minute) }
        model.load()
        model.deliverDue()
        assertEquals(10, backend.scripted.sends)
        assertEquals(9, paused.size)
        assertTrue(paused.all { it >= 1_500L })
        assertEquals(4, model.waitingCount)
        assertNotNull(model.passNote)
    }

    @Test fun aStorageFailureIsReportedWithoutCrashingOrClaimingSuccess() = runBlocking {
        ready()
        backend.failSaveOutbox = IOException("disk full, token $token")
        model.report(checked(alert("a1")))
        assertTrue(model.noticeIsProblem)
        assertTrue(everythingTheModelShows().none { it?.contains(token) == true })
        assertEquals(0, model.sentCount)
    }

    // ---------------------------------------------------------------- history

    @Test fun shorterRetentionForgetsOlderFinishedReportsAtOnce() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("old", DeliveryState.SENT, createdAt = time - 3 * day, messageId = 1), item("recent", DeliveryState.SENT, createdAt = time - hour, messageId = 2))
        model.load()
        assertTrue(model.setRetention(1))
        assertEquals(1, backend.settingsNow.retentionDays)
        assertEquals(listOf("recent"), model.items.map { it.key })
        assertTrue(model.setRetention(5))
        assertEquals("only offered choices are kept", 30, model.settings.retentionDays)
    }

    @Test fun clearingHistoryKeepsWhatIsWaitingAndNothingClearedIsReportedAgain() = runBlocking {
        ready()
        val k = TelegramKeys.alert("kid1", "a1")
        backend.outboxNow = listOf(item(k, DeliveryState.SENT, messageId = 5), item("w", DeliveryState.WAITING))
        model.load()
        assertTrue(model.clearHistory())
        assertEquals(listOf("w"), model.items.map { it.key })
        assertEquals(time, backend.settingsNow.reportFrom)
        model.report(checked(alert("a1", ts = time - minute)))
        assertEquals("an alert from before the clearing doesn't come back", 0, model.items.count { it.key == k })
    }

    @Test fun signingOutRemovesEverythingAboutTelegramFromThePhone() = runBlocking {
        ready()
        backend.outboxNow = listOf(item("s", DeliveryState.SENT, messageId = 1))
        model.load()
        model.wipe()
        assertNull(backend.creds)
        assertNull(backend.connectionNow)
        assertEquals(TelegramSettings(), backend.settingsNow)
        assertTrue(backend.outboxNow.isEmpty())
        assertFalse(model.hasCredentials)
        assertTrue(model.items.isEmpty())
    }

    @Test fun theCredentialsObjectNeverPrintsTheToken() {
        assertFalse(TelegramCredentials(token, chat).toString().contains(token))
        assertFalse(TelegramCredentials(token, chat).toString().contains("AAEhBOweik6ad9r"))
    }
}
