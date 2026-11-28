package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TelegramReportsTest {
    private val token = "123456789" + ":" + "AAEhBOweik6ad9r_QXMENQjcrOaazbPvyYo" // assembled in code: a made-up token, kept out of the source as one literal
    private val utc = TimeZone.getTimeZone("UTC")
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    private var time = 1_800_000_000_000L

    /** A fake Telegram: records what it was asked and answers from a script. */
    private class FakeTransport(val answers: ArrayList<() -> TransportResponse> = ArrayList()) : TelegramTransport {
        val urls = ArrayList<String>(); val forms = ArrayList<String>()
        override fun post(url: String, form: String): TransportResponse {
            urls.add(url); forms.add(form)
            return (if (answers.isEmpty()) ok(1) else answers.removeAt(0)).invoke()
        }
        companion object {
            fun ok(id: Long) = { TransportResponse(200, """{"ok":true,"result":{"message_id":$id}}""") }
            fun me() = { TransportResponse(200, """{"ok":true,"result":{"id":1,"is_bot":true,"username":"guardian_test_bot"}}""") }
            fun fail(code: Int, description: String, retryAfter: Int? = null) = {
                TransportResponse(code, """{"ok":false,"error_code":$code,"description":"$description"""" + (if (retryAfter != null) ""","parameters":{"retry_after":$retryAfter}""" else "") + "}")
            }
        }
    }

    private fun client(t: TelegramTransport, chat: String = "555000111") = TelegramClient(t, token, chat)

    // ---------------------------------------------------------------- the client

    @Test fun requestsGoOnlyToTelegramOverHttps() {
        val t = FakeTransport()
        client(t).sendMessage("hello"); client(t).getMe()
        assertEquals("https://api.telegram.org/bot$token/sendMessage", t.urls[0])
        assertEquals("https://api.telegram.org/bot$token/getMe", t.urls[1])
        assertTrue(t.urls.all { it.startsWith("https://api.telegram.org/bot") })
    }

    @Test fun aWrongLookingTokenOrAnUnknownMethodIsRefusedBeforeAnythingIsSent() {
        val t = FakeTransport()
        var refused = 0
        try { TelegramClient(t, "not a token/../x", "1").sendMessage("x") } catch (_: IllegalArgumentException) { refused++ }
        try { client(t).url("deleteMessage") } catch (_: IllegalArgumentException) { refused++ }
        assertEquals(2, refused)
        assertTrue(t.urls.isEmpty())
    }

    @Test fun theRealTransportRefusesAnythingButTelegramOverHttps() {
        val transport = HttpsTransport()
        for (url in listOf("http://api.telegram.org/bot1/x", "https://example.com/bot1/x", "https://api.telegram.org@evil.example/x", "https://api.telegram.org:8443/bot1/x", "file:///etc/passwd")) {
            val e = try { transport.post(url, ""); null } catch (e: TelegramTransportException) { e } catch (e: java.net.MalformedURLException) { null }
            if (!url.startsWith("file")) assertNotNull(url, e)
            if (e != null) assertFalse("a refusal means nothing was sent: $url", e.sent)
        }
    }

    @Test fun messagesAreSentAsPlainTextWithNoFormattingMode() {
        val t = FakeTransport()
        client(t).sendMessage("a *bold* _text_ <b>x</b> & more")
        val form = t.forms.single()
        assertFalse(form.contains("parse_mode"))
        assertTrue(form.contains("chat_id=555000111"))
        assertTrue(form.contains("text=a+*bold*+_text_+%3Cb%3Ex%3C%2Fb%3E+%26+more"))
    }

    @Test fun aMessageCountsAsSentOnlyWhenTelegramReturnsAMessageId() {
        assertEquals(SendResult.Sent(42L), client(FakeTransport(arrayListOf(FakeTransport.ok(42)))).sendMessage("x"))
        for (body in listOf("""{"ok":true}""", """{"ok":true,"result":{}}""", """{"ok":true,"result":{"message_id":0}}""", """{"ok":true,"result":{"message_id":"abc"}}""")) {
            val r = client(FakeTransport(arrayListOf({ TransportResponse(200, body) }))).sendMessage("x")
            assertEquals(body, FailureKind.UNCONFIRMED, (r as SendResult.Failed).failure.kind)
        }
        for (body in listOf("""{"ok":false}""", "not json", "", null)) {
            val r = client(FakeTransport(arrayListOf({ TransportResponse(200, body) }))).sendMessage("x")
            assertTrue("$body", r is SendResult.Failed)
        }
        // A success status with ok:false, or an HTTP error that claims ok:true, is never a success.
        assertTrue(client(FakeTransport(arrayListOf({ TransportResponse(500, """{"ok":true,"result":{"message_id":9}}""") }))).sendMessage("x") is SendResult.Failed)
    }

    @Test fun telegramsRefusalsAreExplainedInPlainWords() {
        fun failure(code: Int, text: String, retry: Int? = null) = (client(FakeTransport(arrayListOf(FakeTransport.fail(code, text, retry)))).sendMessage("x") as SendResult.Failed).failure
        val bad = failure(401, "Unauthorized")
        assertEquals(FailureKind.TOKEN, bad.kind); assertFalse(bad.retryable)
        assertEquals(FailureKind.TOKEN, failure(404, "Not Found").kind)
        assertEquals(FailureKind.BLOCKED, failure(403, "Forbidden: bot was blocked by the user").kind)
        val chat = failure(400, "Bad Request: chat not found")
        assertEquals(FailureKind.CHAT, chat.kind); assertTrue(chat.message.contains("press Start"))
        val limited = failure(429, "Too Many Requests: retry after 17", 17)
        assertEquals(FailureKind.RATE_LIMITED, limited.kind); assertTrue(limited.retryable); assertEquals(17_000L, limited.retryAfterMs)
        assertEquals(3_600_000L, failure(429, "x", 999_999).retryAfterMs)
        assertTrue(failure(502, "Bad Gateway").retryable)
        val other = failure(400, "Bad Request: message is too long")
        assertEquals(FailureKind.REJECTED, other.kind); assertFalse(other.retryable); assertTrue(other.message.contains("message is too long"))
    }

    @Test fun noConnectionIsRetryableButABrokenConnectionAfterSendingIsNotConfirmed() {
        val before = TelegramTransport { _, _ -> throw TelegramTransportException(false, "UnknownHostException") }
        val f1 = (client(before).sendMessage("x") as SendResult.Failed).failure
        assertEquals(FailureKind.NETWORK, f1.kind); assertTrue(f1.retryable)
        val after = TelegramTransport { _, _ -> throw TelegramTransportException(true, "SocketTimeoutException") }
        val f2 = (client(after).sendMessage("x") as SendResult.Failed).failure
        assertEquals(FailureKind.UNCONFIRMED, f2.kind); assertFalse("never retried on its own: that could send it twice", f2.retryable)
    }

    @Test fun theConnectionTestReadsTheBotName() {
        val r = client(FakeTransport(arrayListOf(FakeTransport.me()))).getMe()
        assertEquals("guardian_test_bot", (r as SendResult.Sent).botName)
    }

    @Test fun tooLongTextIsCutBeforeSending() {
        val t = FakeTransport()
        client(t).sendMessage("x".repeat(10_000))
        assertTrue(t.forms.single().length < 4_200)
    }

    // ---------------------------------------------------------------- secrets

    @Test fun theTokenNeverSurvivesScrubbing() {
        val messy = "FileNotFoundException: https://api.telegram.org/bot$token/sendMessage and $token again; other 987654321" + ":" + "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZ_x"
        val clean = TelegramScrub.scrub(messy, token)
        assertFalse(clean.contains(token)); assertFalse(clean.contains("AAEhBOweik6ad9r")); assertFalse(clean.contains("ZZZZZZZZZZ"))
        assertTrue(clean.contains("/bot***/sendMessage"))
        assertEquals("", TelegramScrub.scrub(null))
    }

    @Test fun errorTextFromTelegramIsScrubbedToo() {
        val r = client(FakeTransport(arrayListOf(FakeTransport.fail(400, "Bad Request: bad token $token")))).sendMessage("x") as SendResult.Failed
        assertFalse(r.failure.message.contains(token)); assertFalse(r.failure.message.contains("AAEhBOweik6"))
    }

    @Test fun tokenAndChatFormatsAreChecked() {
        assertTrue(TelegramScrub.looksLikeToken(token))
        assertFalse(TelegramScrub.looksLikeToken("123:abc")); assertFalse(TelegramScrub.looksLikeToken("$token ")); assertFalse(TelegramScrub.looksLikeToken("bot$token"))
        for (ok in listOf("123456", "-1001234567890", "@guardian_family")) assertTrue(ok, TelegramScrub.looksLikeChatId(ok))
        for (bad in listOf("", "12 34", "family", "@a", "1.5", "--5", "@bad name")) assertFalse(bad, TelegramScrub.looksLikeChatId(bad))
    }

    @Test fun savedReportsNeverContainTheToken() {
        val d = Delivery("a:k1:alert1", ReportCategory.SAFETY, "text", time, DeliveryState.FAILED, 2, 0L, "boom $token /bot$token/x")
        val json = DeliveryCodec.toJson(d).toString()
        assertFalse(json.contains(token)); assertFalse(json.contains("AAEhBOweik6"))
    }

    // ---------------------------------------------------------------- settings

    @Test fun reportsAreOffByDefaultAndDamagedSettingsNeverTurnThemOn() {
        assertFalse(TelegramSettings().enabled)
        assertFalse(TelegramSettingsCodec.fromJson(null).enabled)
        assertFalse(TelegramSettingsCodec.fromJson(JsonObject()).enabled)
        val weird = JsonObject().also { it.addProperty("on", "yes"); it.addProperty("keep", "lots"); it.addProperty("cats", "all") }
        val s = TelegramSettingsCodec.fromJson(weird)
        assertFalse(s.enabled); assertEquals(TelegramPolicy.DEFAULT_RETENTION_DAYS, s.retentionDays)
    }

    @Test fun settingsSurviveStorage() {
        val s = TelegramSettings(true, setOf(ReportCategory.DAILY_SUMMARY), 7, time)
        assertEquals(s, TelegramSettingsCodec.fromJson(TelegramSettingsCodec.toJson(s)))
        assertEquals(TelegramPolicy.DEFAULT_RETENTION_DAYS, TelegramSettings(retentionDays = 5).normalized().retentionDays)
    }

    @Test fun aCategoryOnlyReportsWhenReportsAreOnAndItIsChosen() {
        val s = TelegramSettings(true, setOf(ReportCategory.SAFETY))
        assertTrue(s.on(ReportCategory.SAFETY)); assertFalse(s.on(ReportCategory.DAILY_SUMMARY))
        assertFalse(TelegramSettings(false, setOf(ReportCategory.SAFETY)).on(ReportCategory.SAFETY))
    }

    @Test fun everyAlertKindMapsToACategory() {
        assertEquals(ReportCategory.DEVICE_STATUS, ReportCategory.of(AlertKind.DISCONNECTED))
        assertEquals(ReportCategory.DEVICE_STATUS, ReportCategory.of(AlertKind.MONITORING_OFF))
        assertEquals(ReportCategory.SAFETY, ReportCategory.of(AlertKind.SUSPICIOUS_WORDS))
        AlertKind.values().forEach { assertNotNull(ReportCategory.of(it)) }
    }

    // ---------------------------------------------------------------- queue rules

    private fun enqueue(list: List<Delivery>, key: String, at: Long = time) = TelegramQueue.enqueue(list, key, ReportCategory.SAFETY, "report $key", at)

    @Test fun theSameThingIsNeverQueuedTwiceInAnyState() {
        var list = enqueue(emptyList(), "a:k1:one")
        list = enqueue(list, "a:k1:one")
        assertEquals(1, list.size)
        list = list.map { it.copy(state = DeliveryState.SENT, messageId = 5L) }
        assertEquals("already sent, so not again", 1, enqueue(list, "a:k1:one").size)
        list = list.map { it.copy(state = DeliveryState.FAILED) }
        assertEquals("failed ones are retried by hand, not re-queued", 1, enqueue(list, "a:k1:one").size)
    }

    @Test fun badKeysAreIgnored() {
        assertTrue(enqueue(emptyList(), "bad key!").isEmpty())
        assertTrue(enqueue(emptyList(), "x").isEmpty())
    }

    @Test fun whenTheQueueIsFullOldFinishedReportsGoFirstAndWaitingOnesStay() {
        var list = (1..TelegramPolicy.MAX_ITEMS).map { Delivery("a:k:$it", ReportCategory.SAFETY, "t", time - it * minute, DeliveryState.SENT, messageId = 1L) }
        list = list + Delivery("a:k:waiting", ReportCategory.SAFETY, "t", time - 999 * day, DeliveryState.WAITING)
        list = TelegramQueue.enqueue(list, "a:k:new", ReportCategory.SAFETY, "t", time)
        assertEquals(TelegramPolicy.MAX_ITEMS, list.size)
        assertTrue(list.any { it.key == "a:k:waiting" }); assertTrue(list.any { it.key == "a:k:new" })
        assertFalse("the oldest finished one made room", list.any { it.key == "a:k:${TelegramPolicy.MAX_ITEMS}" })
    }

    @Test fun retentionForgetsOldReports() {
        val list = listOf(Delivery("a:k:old", ReportCategory.SAFETY, "t", time - 8 * day, DeliveryState.SENT, messageId = 1), Delivery("a:k:new", ReportCategory.SAFETY, "t", time - 2 * day, DeliveryState.SENT, messageId = 1))
        assertEquals(listOf("a:k:new"), TelegramQueue.prune(list, time, 7).map { it.key })
        assertEquals(2, TelegramQueue.prune(list, time, 30).size)
        assertTrue(TelegramQueue.prune(list, time, 1).isEmpty())
    }

    @Test fun turningReportsOffDiscardsWhatWasWaitingButKeepsHistory() {
        val list = listOf(
            Delivery("a:k:w", ReportCategory.SAFETY, "t", time), Delivery("a:k:r", ReportCategory.SAFETY, "t", time, DeliveryState.RETRYING),
            Delivery("a:k:s", ReportCategory.SAFETY, "t", time, DeliveryState.SENT, messageId = 3), Delivery("a:k:f", ReportCategory.SAFETY, "t", time, DeliveryState.FAILED)
        )
        assertEquals(listOf("a:k:s", "a:k:f"), TelegramQueue.discardPending(list).map { it.key })
        assertEquals(listOf("a:k:w", "a:k:r"), TelegramQueue.clearHistory(list).map { it.key })
    }

    @Test fun retryMakesAFailedReportWaitAgainButNeverTouchesASentOne() {
        val list = listOf(
            Delivery("a:k:f", ReportCategory.SAFETY, "t", time, DeliveryState.FAILED, 6, 0, "bad token"), Delivery("a:k:u", ReportCategory.SAFETY, "t", time, DeliveryState.UNCONFIRMED, 1),
            Delivery("a:k:s", ReportCategory.SAFETY, "t", time, DeliveryState.SENT, 1, messageId = 3)
        )
        val one = TelegramQueue.retry(list, "a:k:f")
        assertEquals(DeliveryState.WAITING, one.first { it.key == "a:k:f" }.state)
        assertEquals(0, one.first { it.key == "a:k:f" }.attempts); assertNull(one.first { it.key == "a:k:f" }.lastError)
        assertEquals(DeliveryState.UNCONFIRMED, one.first { it.key == "a:k:u" }.state)
        val all = TelegramQueue.retryAllFailed(list)
        assertEquals(DeliveryState.WAITING, all.first { it.key == "a:k:f" }.state)
        assertEquals("an unconfirmed report needs a deliberate retry of its own", DeliveryState.UNCONFIRMED, all.first { it.key == "a:k:u" }.state)
        assertEquals(DeliveryState.SENT, TelegramQueue.retry(list, "a:k:s").first { it.key == "a:k:s" }.state)
        assertEquals(DeliveryState.WAITING, TelegramQueue.retry(list, "a:k:u").first { it.key == "a:k:u" }.state)
    }

    // ---------------------------------------------------------------- sending passes

    private class Run(val outbox: List<Delivery>, val sends: ArrayList<String> = ArrayList(), val saves: ArrayList<List<Delivery>> = ArrayList(), val pauses: ArrayList<Long> = ArrayList())

    private fun pass(list: List<Delivery>, recent: MutableList<Long> = ArrayList(), script: (String) -> SendResult): Pair<PassResult, Run> {
        val run = Run(list)
        val r = TelegramQueue.runPass(list, { time }, recent, { text -> run.sends.add(text); script(text) }, { run.saves.add(it) }, { run.pauses.add(it) })
        return r to run
    }

    private fun waiting(vararg keys: String) = keys.mapIndexed { i, k -> Delivery(k, ReportCategory.SAFETY, "report $k", time - (keys.size - i) * minute) }

    @Test fun aSentReportRecordsTelegramsMessageIdAndNothingElseIsSentTwice() {
        var id = 100L
        val (r, run) = pass(waiting("a:k:1", "a:k:2")) { SendResult.Sent(++id) }
        assertEquals(2, r.sent)
        assertEquals(listOf("report a:k:1", "report a:k:2"), run.sends)
        val d = r.items.first { it.key == "a:k:1" }
        assertEquals(DeliveryState.SENT, d.state); assertEquals(101L, d.messageId); assertEquals(time, d.sentAt)
        val (again, run2) = pass(r.items) { SendResult.Sent(1) }
        assertEquals(0, again.sent); assertTrue(run2.sends.isEmpty())
    }

    @Test fun aReportIsSavedAsSendingBeforeTheRequestSoACrashCannotSendItTwice() {
        val seen = ArrayList<DeliveryState>()
        val list = waiting("a:k:1")
        var saved: List<Delivery> = list
        TelegramQueue.runPass(list, { time }, ArrayList(), { _ -> seen.add(saved.single().state); SendResult.Sent(7) }, { saved = it }, {})
        assertEquals("while the request is made the saved state says sending", listOf(DeliveryState.SENDING), seen)
        // If the app had died right then, the next start reads the saved list: it must not be sent again on its own.
        val crashed = DeliveryCodec.listFromJson(DeliveryCodec.listToJson(listOf(list.single().copy(state = DeliveryState.SENDING, attempts = 1))))
        assertEquals(DeliveryState.UNCONFIRMED, crashed.single().state)
        assertTrue(pass(crashed) { SendResult.Sent(1) }.second.sends.isEmpty())
    }

    @Test fun aRetryableFailureWaitsWithGrowingDelaysThenGivesUp() {
        var list = waiting("a:k:1")
        val fail = SendResult.Failed(TelegramFailure(FailureKind.SERVER, "Telegram had a problem (error 502).", true))
        val gaps = ArrayList<Long>()
        repeat(TelegramPolicy.MAX_ATTEMPTS) {
            val before = time
            val (r, _) = pass(list) { fail }
            list = r.items
            val d = list.single()
            if (d.state == DeliveryState.RETRYING) gaps.add(d.nextAttemptAt - before)
            time = maxOf(time, d.nextAttemptAt)
        }
        assertEquals(listOf(minute, 5 * minute, 15 * minute, hour, 6 * hour), gaps)
        val d = list.single()
        assertEquals(DeliveryState.FAILED, d.state); assertEquals(TelegramPolicy.MAX_ATTEMPTS, d.attempts)
        assertTrue(d.lastError!!.startsWith("Gave up after"))
    }

    @Test fun aReportThatIsNotDueYetIsLeftAlone() {
        val list = listOf(Delivery("a:k:1", ReportCategory.SAFETY, "t", time - hour, DeliveryState.RETRYING, 1, nextAttemptAt = time + 5 * minute, lastError = "x"))
        val (r, run) = pass(list) { SendResult.Sent(1) }
        assertTrue(run.sends.isEmpty()); assertEquals(1, r.waiting)
    }

    @Test fun badCredentialsStopThePassAndLeaveTheRestWaiting() {
        val bad = SendResult.Failed(TelegramFailure(FailureKind.TOKEN, "Telegram doesn't accept this bot token. Check it and save it again.", false))
        val (r, run) = pass(waiting("a:k:1", "a:k:2", "a:k:3")) { bad }
        assertEquals(1, run.sends.size)
        assertEquals(DeliveryState.FAILED, r.items.first { it.key == "a:k:1" }.state)
        assertEquals(2, r.items.count { it.state == DeliveryState.WAITING })
        assertTrue(r.stoppedBecause!!.contains("bot token"))
    }

    @Test fun noConnectionKeepsEverythingForLater() {
        val down = SendResult.Failed(TelegramFailure(FailureKind.NETWORK, "No connection to Telegram. It will be tried again.", true))
        val (r, run) = pass(waiting("a:k:1", "a:k:2")) { down }
        assertEquals(1, run.sends.size)
        assertEquals(DeliveryState.RETRYING, r.items.first { it.key == "a:k:1" }.state)
        assertEquals(DeliveryState.WAITING, r.items.first { it.key == "a:k:2" }.state)
        assertEquals(0, r.failed)
    }

    @Test fun whenTelegramAsksToSlowDownItsWaitIsObeyedAndThePassStops() {
        val slow = SendResult.Failed(TelegramFailure(FailureKind.RATE_LIMITED, "Telegram asked to slow down.", true, 90 * 60_000L))
        val (r, run) = pass(waiting("a:k:1", "a:k:2")) { slow }
        assertEquals(1, run.sends.size)
        val d = r.items.first { it.key == "a:k:1" }
        assertEquals("never longer than an hour", time + TelegramPolicy.RETRY_AFTER_CAP_MS, d.nextAttemptAt)
    }

    @Test fun anUnconfirmedReportIsNotCountedAsSentAndNotRetriedOnItsOwn() {
        val lost = SendResult.Failed(TelegramFailure(FailureKind.UNCONFIRMED, "The connection broke after sending.", false))
        val (r, _) = pass(waiting("a:k:1")) { lost }
        assertEquals(0, r.sent); assertEquals(1, r.failed)
        assertEquals(DeliveryState.UNCONFIRMED, r.items.single().state)
        assertEquals(0L, r.items.single().messageId)
        assertTrue(pass(r.items) { SendResult.Sent(1) }.second.sends.isEmpty())
    }

    @Test fun anUnexpectedExceptionLeavesTheReportNotConfirmedNotSent() {
        val (r, _) = pass(waiting("a:k:1")) { throw IllegalStateException("boom $token") }
        assertEquals(DeliveryState.UNCONFIRMED, r.items.single().state)
        assertFalse(r.items.single().lastError!!.contains(token))
    }

    @Test fun aPassSendsAtMostTenAndPausesBetweenMessages() {
        val keys = (1..14).map { "a:k:$it" }.toTypedArray()
        val (r, run) = pass(waiting(*keys)) { SendResult.Sent(5) }
        assertEquals(TelegramPolicy.MAX_PER_PASS, run.sends.size)
        assertEquals(TelegramPolicy.MAX_PER_PASS - 1, run.pauses.size)
        assertTrue(run.pauses.all { it == TelegramPolicy.MIN_GAP_MS })
        assertEquals(4, r.waiting)
        assertEquals("oldest first", "report a:k:1", run.sends.first())
    }

    @Test fun theRateLimitPerMinuteIsKept() {
        val recent = ArrayList<Long>().also { l -> repeat(TelegramPolicy.MAX_PER_MINUTE) { l.add(time - 10_000L) } }
        val (r, run) = pass(waiting("a:k:1"), recent) { SendResult.Sent(1) }
        assertTrue(run.sends.isEmpty()); assertEquals(1, r.waiting)
        val old = ArrayList<Long>().also { l -> repeat(TelegramPolicy.MAX_PER_MINUTE) { l.add(time - 61_000L) } }
        assertEquals(1, pass(waiting("a:k:1"), old) { SendResult.Sent(1) }.second.sends.size)
    }

    // ---------------------------------------------------------------- storage

    @Test fun savedReportsSurviveAndDamagedOnesAreDropped() {
        val d = Delivery("a:k:1", ReportCategory.DAILY_SUMMARY, "text", time, DeliveryState.SENT, 1, 0, null, time, 77)
        assertEquals(listOf(d), DeliveryCodec.listFromJson(DeliveryCodec.listToJson(listOf(d))))
        assertTrue(DeliveryCodec.listFromJson(null).isEmpty())
        assertNull(DeliveryCodec.fromJson(JsonObject()))
        assertNull(DeliveryCodec.fromJson(DeliveryCodec.toJson(d).also { it.addProperty("k", "bad key!") }))
        assertNull(DeliveryCodec.fromJson(DeliveryCodec.toJson(d).also { it.addProperty("s", "delivered") }))
    }

    @Test fun aSentReportWithoutAMessageIdIsNeverTrustedAsSent() {
        val json = DeliveryCodec.toJson(Delivery("a:k:1", ReportCategory.SAFETY, "t", time, DeliveryState.SENT, 1, 0, null, time, 0))
        assertEquals(DeliveryState.UNCONFIRMED, DeliveryCodec.fromJson(json)!!.state)
    }

    // ---------------------------------------------------------------- the words of the reports

    @Test fun anAlertReportCarriesTheNeutralLabelTheReasonAndACautionAndNoRawMessage() {
        val a = SafetyAlert(SafetyIds.id(AlertKind.SUSPICIOUS_WORDS, "m1"), AlertKind.SUSPICIOUS_WORDS, AlertLevel.NOTE, "Potentially suspicious wording from Sam", "A preview in WhatsApp from Sam matches \"our little secret\".", time - hour, "WhatsApp")
        val text = TelegramTexts.alert("Aarav's phone", a, utc)
        assertTrue(text.contains("Guardian · Aarav's phone")); assertTrue(text.contains("Potentially suspicious content"))
        assertTrue(text.contains(a.reason)); assertTrue(text.contains("can be wrong"))
        assertTrue(text.length <= TelegramPolicy.MAX_TEXT)
    }

    @Test fun aDailySummaryNamesTheDayTheTotalAndTheBiggestApps() {
        val text = TelegramTexts.daily("Aarav's phone", SafetyDay("20270114", 3 * 3600 + 20 * 60, listOf("YouTube" to 3900, "Roblox" to 1800)))
        assertTrue(text.contains("14/1")); assertTrue(text.contains("3 h 20 min")); assertTrue(text.contains("YouTube 1 h 5 min"))
    }

    @Test fun theTestMessageSaysWhetherReportsAreOn() {
        assertTrue(TelegramTexts.test(false).contains("still off")); assertTrue(TelegramTexts.test(true).contains("will arrive"))
    }

    // ---------------------------------------------------------------- the daily summary choice

    private fun at(y: Int, m: Int, d: Int, h: Int): Long = java.util.Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis
    private val days = listOf(SafetyDay("20270114", 7200, emptyList()), SafetyDay("20270113", 3000, emptyList()), SafetyDay("20270115", 100, emptyList()))

    @Test fun theSummaryIsForYesterdayAfterSevenAndNotBefore() {
        assertEquals("20270114", TelegramDaily.pick(at(2027, 1, 15, 8), utc, at(2027, 1, 1, 0), days)!!.dayKey)
        assertNull(TelegramDaily.pick(at(2027, 1, 15, 6), utc, at(2027, 1, 1, 0), days))
    }

    @Test fun nothingOlderThanWhenReportingBeganIsSummarised() {
        assertNull(TelegramDaily.pick(at(2027, 1, 15, 8), utc, at(2027, 1, 15, 3), days))
        assertNotNull(TelegramDaily.pick(at(2027, 1, 15, 8), utc, at(2027, 1, 14, 20), days))
    }

    @Test fun aDayWithNoDataIsNotSummarisedAsZero() {
        assertNull(TelegramDaily.pick(at(2027, 1, 20, 9), utc, at(2027, 1, 1, 0), days))
        assertNull(TelegramDaily.pick(at(2027, 1, 15, 9), utc, 0L, listOf(SafetyDay("20270114", 0, emptyList()))))
    }
}
