package com.sirrlevi.guardian.child

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.CommDirection
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.NotificationParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Android notification objects go in; records come out. Covers what the phone's listener does before upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommsChildTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val me = Person.Builder().setName("Me").setKey("me").build()
    private val friend = Person.Builder().setName("Aman").setKey("aman").build()

    @Before fun clean() { context.filesDir.listFiles()?.filter { it.name.startsWith("comms_queue") }?.forEach { it.delete() } }
    @After fun cleanAfter() = clean()

    private fun builder() = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun parse(pkg: String, n: Notification, label: String = "App"): List<CommRecord> =
        NotificationParser.parse(NotificationExtractor.toRaw(pkg, 5_000L, n), label)

    @Test fun messagingStyleKeepsEachMessageWithItsDirection() {
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("hello there", 1_000L, friend)
            .addMessage("hi, coming", 2_000L, me)
        val n = builder().setStyle(style).setCategory(Notification.CATEGORY_MESSAGE).build()
        val records = parse("com.whatsapp", n, "WhatsApp")
        assertEquals(2, records.size)
        val incoming = records.first { it.text == "hello there" }
        val outgoing = records.first { it.text == "hi, coming" }
        assertEquals(CommDirection.INCOMING, incoming.direction)
        assertEquals("Aman", incoming.sender)
        assertEquals(CommDirection.OUTGOING, outgoing.direction)
        assertTrue(records.all { it.app == "WhatsApp" && it.contentAvailable })
    }

    @Test fun messageWithoutAKnownSenderIsNeverGivenADirection() {
        val style = NotificationCompat.MessagingStyle(me)
        style.addMessage(NotificationCompat.MessagingStyle.Message("who knows", 1_000L, null as Person?))
        val n = builder().setStyle(style).build()
        val raw = NotificationExtractor.toRaw("com.whatsapp", 5_000L, n)
        assertNull(raw.messages.single().isSelf)
        assertEquals(CommDirection.UNKNOWN, NotificationParser.parse(raw, "WhatsApp").single().direction)
    }

    @Test fun groupConversationIsReported() {
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Class 3B").setGroupConversation(true)
            .addMessage("homework?", 1_000L, friend)
        val records = parse("com.whatsapp", builder().setStyle(style).build())
        assertEquals("Class 3B", records.single().conversation)
        assertEquals(true, records.single().isGroup)
    }

    @Test fun plainNotificationFromKnownChatAppBecomesAnUnknownDirectionPreview() {
        val n = builder().setContentTitle("Riya").setContentText("see you at 5").build()
        val r = parse("com.instagram.android", n, "Instagram").single()
        assertEquals("Riya", r.sender)
        assertEquals("see you at 5", r.text)
        assertEquals(CommDirection.UNKNOWN, r.direction)
    }

    @Test fun hiddenContentIsKeptAsUnavailableNotInvented() {
        val n = builder().setContentTitle("Riya").setContentText("2 new messages").build()
        val r = parse("com.whatsapp", n).single()
        assertFalse(r.contentAvailable)
        assertNull(r.text)
    }

    @Test fun groupSummaryAndOngoingNotificationsAreIgnored() {
        val summary = builder().setContentTitle("WhatsApp").setContentText("5 messages").setGroupSummary(true).setGroup("g").build()
        assertTrue(parse("com.whatsapp", summary).isEmpty())
        val ongoing = builder().setContentTitle("Riya").setContentText("hello").setOngoing(true).build()
        assertTrue(parse("com.whatsapp", ongoing).isEmpty())
    }

    @Test fun unrelatedAppsAndGuardianItselfAreIgnored() {
        val n = builder().setContentTitle("Level up!").setContentText("You reached level 9").build()
        assertTrue(parse("com.example.game", n).isEmpty())
        val own = builder().setContentTitle("x").setContentText("y").setCategory(Notification.CATEGORY_MESSAGE).build()
        assertTrue(parse("com.sirrlevi.guardian.child", own).isEmpty())
    }

    @Test fun unknownAppIsRecordedOnlyWhenAndroidMarksItAsAMessage() {
        val n = builder().setContentTitle("Mia").setContentText("lunch?").setCategory(Notification.CATEGORY_MESSAGE).build()
        assertEquals(1, parse("com.example.newchat", n).size)
    }

    @Test fun isSelfComparesKeysThenNames() {
        assertEquals(true, NotificationExtractor.isSelf(me, me))
        assertEquals(false, NotificationExtractor.isSelf(me, friend))
        assertEquals(true, NotificationExtractor.isSelf(Person.Builder().setName("You").build(), Person.Builder().setName("You").build()))
        assertNull(NotificationExtractor.isSelf(null, friend))
        assertNull(NotificationExtractor.isSelf(me, null))
    }

    @Test fun queueStoresDeduplicatesAndRemoves() {
        val q = CommsQueue(context)
        val records = parse(
            "com.whatsapp",
            builder().setStyle(NotificationCompat.MessagingStyle(me).addMessage("a", 1_000L, friend).addMessage("b", 3_000L, friend)).build()
        )
        assertEquals(2, q.add(records))
        assertEquals("the same messages re-posted by the app are not stored twice", 0, q.add(records))
        assertEquals(2, q.size())
        val first = q.peek(1).single()
        assertEquals(8, first.day.length)
        q.remove(listOf(first.id))
        assertEquals(1, q.size())
        q.remove(q.peek(10).map { it.id })
        assertEquals(0, q.size())
    }

    @Test fun queueSurvivesBeingReopenedAndDropsOldestWhenFull() {
        val q = CommsQueue(context)
        val bulk = (1..(CommsQueue.MAX_PENDING + 25)).map { i ->
            CommRecord("id$i", "com.whatsapp", "WhatsApp", "A", null, null, "m$i", 1_000_000L + i, null, CommDirection.UNKNOWN, true)
        }
        q.add(bulk)
        val reopened = CommsQueue(context)
        assertEquals(CommsQueue.MAX_PENDING, reopened.size())
        assertEquals("id26", reopened.peek(1).single().id)
    }
}
