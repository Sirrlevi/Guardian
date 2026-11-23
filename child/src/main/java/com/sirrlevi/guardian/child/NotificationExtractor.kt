package com.sirrlevi.guardian.child

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.NotificationParser
import com.sirrlevi.guardian.core.RawMessage
import com.sirrlevi.guardian.core.RawNotification

/**
 * Copies what a notification itself exposes into a [RawNotification]. Only fields Android hands to an
 * enabled notification listener are read; nothing is inferred and nothing is fetched from the other app.
 */
internal object NotificationExtractor {
    fun toRaw(sbn: StatusBarNotification): RawNotification = toRaw(sbn.packageName.orEmpty(), sbn.postTime, sbn.notification)

    fun toRaw(pkg: String, postTimeMs: Long, n: Notification): RawNotification {
        val extras: Bundle? = n.extras
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (_: Exception) { null }

        val messages = ArrayList<RawMessage>()
        if (style != null) {
            for (m in style.messages) {
                val person: Person? = m.person
                messages.add(RawMessage(person?.name?.toString(), m.text?.toString(), m.timestamp, isSelf(style.user, person)))
            }
        }
        return RawNotification(
            pkg = pkg,
            postTimeMs = postTimeMs,
            whenMs = if (n.`when` > 0L) n.`when` else postTimeMs,
            category = n.category,
            title = text(extras, Notification.EXTRA_TITLE),
            text = text(extras, Notification.EXTRA_TEXT),
            bigText = text(extras, Notification.EXTRA_BIG_TEXT),
            conversationTitle = style?.conversationTitle?.toString(),
            isGroup = if (style != null) (try { style.isGroupConversation } catch (_: Exception) { null }) else null,
            messages = messages,
            hasMessagingStyle = style != null,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        )
    }

    fun records(context: Context, sbn: StatusBarNotification): List<CommRecord> = try {
        NotificationParser.parse(toRaw(sbn), appLabel(context, sbn.packageName.orEmpty()))
    } catch (_: Exception) { emptyList() }

    /**
     * True/false only when Android lets us compare the sender with the device user; null otherwise, so a
     * message of unknown origin is never labelled incoming or outgoing.
     */
    internal fun isSelf(user: Person?, sender: Person?): Boolean? {
        if (user == null || sender == null) return null
        val userKey = user.key
        val senderKey = sender.key
        if (userKey != null && senderKey != null) return userKey == senderKey
        val userName = user.name?.toString()
        val senderName = sender.name?.toString()
        if (userName != null && senderName != null) return userName == senderName
        return null
    }

    private fun text(extras: Bundle?, key: String): String? = try {
        extras?.getCharSequence(key)?.toString()
    } catch (_: Exception) { null }

    private fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
