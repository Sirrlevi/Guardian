package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsTest {
    private val wa = "com.whatsapp"
    private val ig = "com.instagram.android"
    private val t0 = 1_700_000_000_000L

    private fun raw(
        pkg: String = wa, title: String? = null, text: String? = null, bigText: String? = null,
        conversation: String? = null, group: Boolean? = null, messages: List<RawMessage> = emptyList(),
        style: Boolean = messages.isNotEmpty(), category: String? = "msg", whenMs: Long = t0, postTime: Long = t0 + 5,
        summary: Boolean = false, ongoing: Boolean = false
    ) = RawNotification(pkg, postTime, whenMs, category, title, text, bigText, conversation, group, messages, style, summary, ongoing)

    // ---- MessagingStyle

    @Test fun messagingStyleGivesOneRecordPerMessageWithDirection() {
        val records = NotificationParser.parse(
            raw(
                title = "Riya", conversation = null, group = false,
                messages = listOf(
                    RawMessage("Riya", "hi, are you home?", t0 - 60_000, isSelf = false),
                    RawMessage("You", "yes", t0 - 30_000, isSelf = true),
                    RawMessage("Riya", "ok see you", t0, isSelf = false)
                )
            ), "WhatsApp"
        )
        assertEquals(3, records.size)
        assertEquals(listOf(CommDirection.INCOMING, CommDirection.OUTGOING, CommDirection.INCOMING), records.map { it.direction })
        assertEquals("Riya", records[0].sender)
        assertEquals("Riya", records[0].conversation)
        assertEquals("yes", records[1].text)
        assertTrue(records.all { it.contentAvailable && it.app == "WhatsApp" })
    }

    @Test fun groupMessagingStyleKeepsGroupTitleAndSenderApart() {
        val records = NotificationParser.parse(
            raw(
                conversation = "Class 3B parents", group = true,
                messages = listOf(RawMessage("Mr. Sam", "send me a photo", t0, isSelf = false))
            ), "WhatsApp"
        )
        assertEquals(1, records.size)
        assertEquals("Class 3B parents", records[0].conversation)
        assertEquals("Mr. Sam", records[0].sender)
        assertEquals(true, records[0].isGroup)
    }

    @Test fun unknownWriterStaysUnknownInsteadOfBeingGuessed() {
        val records = NotificationParser.parse(
            raw(title = "Riya", messages = listOf(RawMessage(null, "hello", t0, isSelf = null))), "WhatsApp"
        )
        assertEquals(CommDirection.UNKNOWN, records.single().direction)
        assertEquals("Riya", records.single().sender) // the notification title is the only sender information there is
    }

    @Test fun messageWithoutAnyTextOrSenderIsSkipped() {
        val records = NotificationParser.parse(raw(messages = listOf(RawMessage(null, null, t0, null))), "WhatsApp")
        assertTrue(records.isEmpty())
    }

    @Test fun onlyTheLatestTenMessagesOfAStyleAreKept() {
        val many = (1..25).map { RawMessage("Riya", "m$it", t0 + it * 1000L, false) }
        val records = NotificationParser.parse(raw(title = "Riya", messages = many), "WhatsApp")
        assertEquals(10, records.size)
        assertEquals("m16", records.first().text)
        assertEquals("m25", records.last().text)
    }

    // ---- plain notifications

    @Test fun plainNotificationUsesTitleAsSenderAndKeepsDirectionUnknown() {
        val r = NotificationParser.parse(raw(pkg = ig, title = "alex_9", text = "want to play?", style = false, category = "social"), "Instagram").single()
        assertEquals("alex_9", r.sender)
        assertEquals("want to play?", r.text)
        assertEquals(CommDirection.UNKNOWN, r.direction)
        assertEquals(t0, r.timeMs)
    }

    @Test fun groupTextWithSenderPrefixIsSplit() {
        val r = NotificationParser.parse(raw(title = "Cousins", text = "Dev: movie tonight?", group = true, style = false), "WhatsApp").single()
        assertEquals("Dev", r.sender)
        assertEquals("movie tonight?", r.text)
        assertEquals("Cousins", r.conversation)
    }

    @Test fun colonInOneToOneMessageIsNotTreatedAsASender() {
        val r = NotificationParser.parse(raw(title = "Riya", text = "Time: 5pm", group = false, style = false), "WhatsApp").single()
        assertEquals("Riya", r.sender)
        assertEquals("Time: 5pm", r.text)
    }

    @Test fun bigTextWinsOverTruncatedText() {
        val r = NotificationParser.parse(raw(title = "Riya", text = "long mess…", bigText = "long message in full", style = false), "WhatsApp").single()
        assertEquals("long message in full", r.text)
    }

    @Test fun veryLongTextIsCapped() {
        val r = NotificationParser.parse(raw(title = "Riya", text = "x".repeat(2000), style = false), "WhatsApp").single()
        assertEquals(NotificationParser.MAX_TEXT, r.text!!.length)
    }

    // ---- missing / hidden content

    @Test fun hiddenContentKeepsTheSenderButMarksTextUnavailable() {
        for (placeholder in listOf("New message", "3 new messages", "You have a new message", "Sensitive notification content hidden", "2 messages from 2 chats")) {
            val r = NotificationParser.parse(raw(title = "Riya", text = placeholder, style = false), "WhatsApp").single()
            assertNull(placeholder, r.text)
            assertFalse(placeholder, r.contentAvailable)
            assertEquals("Riya", r.sender)
        }
    }

    @Test fun senderOnlyNotificationIsKeptAsAnEventWithoutText() {
        val r = NotificationParser.parse(raw(pkg = ig, title = "alex_9", text = null, style = false), "Instagram").single()
        assertNull(r.text)
        assertFalse(r.contentAvailable)
    }

    @Test fun nothingAtAllProducesNothing() {
        assertTrue(NotificationParser.parse(raw(title = null, text = null, style = false), "WhatsApp").isEmpty())
        assertTrue(NotificationParser.parse(raw(title = "  ", text = "‎", style = false), "WhatsApp").isEmpty())
    }

    @Test fun placeholderDetection() {
        assertTrue(NotificationParser.isPlaceholder(null))
        assertTrue(NotificationParser.isPlaceholder("   "))
        assertTrue(NotificationParser.isPlaceholder("New messages"))
        assertFalse(NotificationParser.isPlaceholder("I have a new message for you tomorrow"))
        assertFalse(NotificationParser.isPlaceholder("ok"))
    }

    // ---- what is and is not recorded

    @Test fun groupSummariesAndOngoingNotificationsAreIgnored() {
        assertTrue(NotificationParser.parse(raw(title = "WhatsApp", text = "5 new messages", summary = true), "WhatsApp").isEmpty())
        assertTrue(NotificationParser.parse(raw(title = "WhatsApp Web", text = "Active", ongoing = true), "WhatsApp").isEmpty())
    }

    @Test fun unrelatedAppsAreNotRecordedButMessagingAppsAre() {
        assertTrue(NotificationParser.parse(raw(pkg = "com.bank.app", title = "Debit", text = "Rs 500 spent", category = "email", style = false), "Bank").isEmpty())
        assertTrue(NotificationParser.parse(raw(pkg = "com.example.player", title = "Song", text = "Artist", category = "transport", style = false), "Player").isEmpty())
        assertEquals(1, NotificationParser.parse(raw(pkg = "com.unknown.chat", title = "Sam", text = "hey", category = "msg", style = false), "Chat").size)
        assertEquals(1, NotificationParser.parse(raw(pkg = "com.unknown.chat2", title = "Sam", text = "hey", category = null, style = true,
            messages = listOf(RawMessage("Sam", "hey", t0, false))), "Chat2").size)
    }

    @Test fun guardianAndSystemNotificationsAreNeverRecorded() {
        for (pkg in listOf("com.sirrlevi.guardian.child", "android", "com.android.systemui")) {
            assertTrue(pkg, NotificationParser.parse(raw(pkg = pkg, title = "x", text = "y", style = false), "x").isEmpty())
        }
    }

    // ---- ids and duplicates

    @Test fun sameMessageGetsTheSameIdEvenWhenTheNotificationIsReposted() {
        val a = NotificationParser.parse(raw(title = "Riya", text = "hello", style = false, postTime = t0 + 10), "WhatsApp").single()
        val b = NotificationParser.parse(raw(title = "Riya", text = "hello", style = false, postTime = t0 + 99_000), "WhatsApp").single()
        assertEquals(a.id, b.id)
    }

    @Test fun differentTextOrTimeGivesADifferentId() {
        val a = NotificationParser.parse(raw(title = "Riya", text = "ok", style = false), "WhatsApp").single()
        val sameTextLater = NotificationParser.parse(raw(title = "Riya", text = "ok", style = false, whenMs = t0 + 60_000), "WhatsApp").single()
        val otherText = NotificationParser.parse(raw(title = "Riya", text = "okay", style = false), "WhatsApp").single()
        assertTrue(a.id != sameTextLater.id)
        assertTrue(a.id != otherText.id)
    }

    @Test fun idsAreValidDatabaseKeys() {
        val id = NotificationParser.recordId(wa, "a.b/c", "d#e", t0, "x$[y]")
        assertEquals(24, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun dedupeAcceptsAnIdOnlyOnceAndForgets() {
        val d = CommDedupe(capacity = 3)
        assertTrue(d.firstSeen("a"))
        assertFalse(d.firstSeen("a"))
        assertTrue(d.firstSeen("b")); assertTrue(d.firstSeen("c")); assertTrue(d.firstSeen("d"))
        assertTrue("oldest entry fell out of the bounded memory", d.firstSeen("a"))
        d.forget("d")
        assertTrue(d.firstSeen("d"))
    }

    // ---- storage format

    @Test fun recordSurvivesTheWireFormat() {
        val full = CommRecord("id1", wa, "WhatsApp", "Riya", "Class chat", true, "hello", t0, "msg", CommDirection.INCOMING, true)
        assertEquals(full, CommCodec.fromJson("id1", CommCodec.toJson(full)))
        val bare = CommRecord("id2", ig, "Instagram", null, null, null, null, t0, null, CommDirection.UNKNOWN, false)
        assertEquals(bare, CommCodec.fromJson("id2", CommCodec.toJson(bare)))
    }

    @Test fun brokenRecordsAreRejectedNotInvented() {
        assertNull(CommCodec.fromJson("x", com.google.gson.JsonObject()))
        assertNull(CommCodec.fromJson("x", com.google.gson.JsonObject().apply { addProperty("p", wa) }))
    }

    // ---- filtering, search, grouping

    private fun rec(id: String, pkg: String, sender: String?, conv: String?, text: String?, at: Long, dir: CommDirection = CommDirection.INCOMING, group: Boolean? = null) =
        CommRecord(id, pkg, if (pkg == wa) "WhatsApp" else if (pkg == ig) "Instagram" else "Other", sender, conv, group, text, at, "msg", dir, text != null)

    private val sample = listOf(
        rec("1", wa, "Riya", "Riya", "hello", t0),
        rec("2", wa, "You", "Riya", "hi", t0 + 1000, CommDirection.OUTGOING),
        rec("3", ig, "alex_9", "alex_9", "send pics?", t0 + 2000),
        rec("4", "com.sms", "+91 98xxxx", "+91 98xxxx", "click http://bad.example", t0 + 3000),
        rec("5", wa, "Dev", "Cousins", "movie?", t0 + 4000, group = true),
        rec("6", wa, "Dev", "Cousins", null, t0 + 5000, group = true)
    )

    @Test fun filtersByAppGroupDateSenderAndSearch() {
        assertEquals(listOf("1", "2", "5", "6"), CommQuery.apply(sample, CommFilter(group = CommAppGroup.WHATSAPP)).map { it.id })
        assertEquals(listOf("3"), CommQuery.apply(sample, CommFilter(group = CommAppGroup.INSTAGRAM)).map { it.id })
        assertEquals(listOf("4"), CommQuery.apply(sample, CommFilter(group = CommAppGroup.OTHER)).map { it.id })
        assertEquals(listOf("3", "4"), CommQuery.apply(sample, CommFilter(fromMs = t0 + 2000, toMs = t0 + 3999)).map { it.id })
        assertEquals(listOf("5", "6"), CommQuery.apply(sample, CommFilter(sender = "dev")).map { it.id })
        assertEquals(listOf("4"), CommQuery.apply(sample, CommFilter(query = " BAD.example ")).map { it.id })
        assertEquals(listOf("5", "6"), CommQuery.apply(sample, CommFilter(query = "cousins")).map { it.id })
        assertTrue(CommQuery.apply(sample, CommFilter(query = "nothing like this")).isEmpty())
    }

    @Test fun resultsAreOldestFirst() {
        val shuffled = sample.reversed()
        assertEquals(sample.map { it.id }, CommQuery.apply(shuffled, CommFilter()).map { it.id })
    }

    @Test fun sendersExcludeOutgoingAndRankByActivity() {
        val senders = CommQuery.senders(sample)
        assertEquals("Dev" to 2, senders.first())
        assertTrue(senders.none { it.first == "You" })
    }

    @Test fun conversationsGroupPerChatNewestFirst() {
        val chats = CommQuery.conversations(sample)
        assertEquals(listOf("Cousins", "+91 98xxxx", "alex_9", "Riya"), chats.map { it.title })
        val riya = chats.last()
        assertEquals(2, riya.items.size)
        assertFalse(riya.isGroup)
        assertTrue(chats.first().isGroup)
        assertEquals(t0 + 5000, chats.first().lastMs)
    }
}
