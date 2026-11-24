package com.sirrlevi.guardian.child

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.child.ui.CaptureRequestDialog
import com.sirrlevi.guardian.child.ui.CaptureStatusCard
import com.sirrlevi.guardian.child.ui.ChildTheme
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The phone-side pieces of screen sharing that can be checked without a real screen: stored ids, notifications and the child's dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class CaptureChildTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test fun handledIdsSurviveARestart() {
        PrefsProcessedIds(context).save(listOf("cmd-aaaaaaaa", "cmd-bbbbbbbb"))
        assertEquals(listOf("cmd-aaaaaaaa", "cmd-bbbbbbbb"), PrefsProcessedIds(context).load())
    }

    @Test fun handledIdsStartEmpty() {
        PrefsProcessedIds(context).save(emptyList())
        assertTrue(PrefsProcessedIds(context).load().isEmpty())
    }

    @Test fun requestNotificationAppearsAndDisappears() {
        CaptureNotifications.showPrompt(context, System.currentTimeMillis() + 60_000L)
        val shown = shadowOf(manager).getNotification(CaptureNotifications.PROMPT_ID)
        assertNotNull(shown)
        assertEquals("Your parent asked to see your screen", shown.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(shown.contentIntent)
        CaptureNotifications.hidePrompt(context)
        assertNull(shadowOf(manager).getNotification(CaptureNotifications.PROMPT_ID))
    }

    @Test fun sharingNotificationIsOngoingAndHasAStopButton() {
        val n = CaptureNotifications.active(context, System.currentTimeMillis())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, n.actions.size)
        assertEquals("Stop sharing", n.actions[0].title.toString())
        assertEquals("Your screen is being shared", n.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun dialogAsksAndReportsTheAnswer() {
        var allowed = 0
        var declined = 0
        rule.setContent { ChildTheme { CaptureRequestDialog(CaptureState(status = CaptureStatus.AWAITING, expiresAt = 1L), { allowed++ }, { declined++ }) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Share your screen?").fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("Not now")[0].performClick()
        rule.waitForIdle()
        assertEquals(0, allowed)
        assertEquals(1, declined)
        rule.onAllNodesWithText("Allow")[0].performClick()
        rule.waitForIdle()
        assertEquals(1, allowed)
    }

    @Test fun dialogIsAbsentUnlessARequestIsWaiting() {
        for (status in listOf(CaptureStatus.IDLE, CaptureStatus.ACTIVE, CaptureStatus.ENDED)) {
            rule.setContent { ChildTheme { CaptureRequestDialog(CaptureState(status = status), {}, {}) } }
            rule.waitForIdle()
            assertTrue("dialog shown for $status", rule.onAllNodesWithText("Share your screen?").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun cardSaysNothingIsSharedWhenIdle() {
        rule.setContent { ChildTheme { CaptureStatusCard(CaptureState(), {}, {}, {}) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Off").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Stop sharing").fetchSemanticsNodes().isEmpty())
    }

    @Test fun cardOffersStopWhileSharing() {
        var stopped = 0
        rule.setContent {
            ChildTheme { CaptureStatusCard(CaptureState(status = CaptureStatus.ACTIVE, live = true, expiresAt = System.currentTimeMillis() + 600_000L), {}, {}, { stopped++ }) }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Sharing now").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Stop sharing").performClick()
        rule.waitForIdle()
        assertEquals(1, stopped)
    }

    @Test fun cardLetsTheChildAnswerAWaitingRequest() {
        var allowed = 0
        var declined = 0
        rule.setContent { ChildTheme { CaptureStatusCard(CaptureState(status = CaptureStatus.AWAITING), { allowed++ }, { declined++ }, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertEquals(1, declined)
        assertEquals(1, allowed)
    }
}
