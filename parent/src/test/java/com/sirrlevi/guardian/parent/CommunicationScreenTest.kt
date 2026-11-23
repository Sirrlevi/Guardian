package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.CommDirection
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.parent.ui.CommunicationScreen
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Messages screen must be honest: previews only, unknown stays unknown, missing text is said to be missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class CommunicationScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun device(accessOn: Boolean = true, known: Boolean = true, commsAt: Long = now - 120_000L) = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now, apps = emptyList(),
        info = DeviceInfo(now, "Galaxy A15", "Samsung", "14", 63, false, 128_000, 52_000, 6_000, 2_100, "wifi", true, 600, true, "1.0.52",
            "overlay", true, true, true, false, false, false, false, permNotifAccess = accessOn, notifAccessKnown = known),
        live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(),
        visits = emptyList(), requests = emptyList(), hasMonitor = true, commsAt = commsAt
    )

    private fun rec(
        id: String, pkg: String, app: String, sender: String?, text: String?, dir: CommDirection,
        ageMin: Long, conversation: String? = null, group: Boolean? = null
    ) = CommRecord(id, pkg, app, sender, conversation, group, text, now - ageMin * 60_000L, "msg", dir, text != null)

    private val sample = listOf(
        rec("a1", "com.whatsapp", "WhatsApp", "Riya", "are you coming to the park?", CommDirection.INCOMING, 5),
        rec("a2", "com.whatsapp", "WhatsApp", "Me", "yes, in 10 minutes", CommDirection.OUTGOING, 4),
        rec("a3", "com.instagram.android", "Instagram", "kabir_05", null, CommDirection.UNKNOWN, 3),
        rec("a4", "com.whatsapp", "WhatsApp", "Aman", "homework page 12", CommDirection.UNKNOWN, 2, "Class 3B", true)
    )

    private fun show(d: ChildDevice, records: List<CommRecord>, error: String? = null, loaded: Boolean = true) {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(d), FamilyPolicy())
        vm.showCommsForTest(records, error, loaded)
        rule.setContent { GuardianTheme(ThemeMode.Light) { CommunicationScreen(vm, PaddingValues(0.dp)) } }
        rule.waitForIdle()
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun saysItShowsPreviewsAndWhenItLastSynchronized() {
        show(device(), sample)
        assertTrue(has("Previews, not full chats"))
        assertTrue(has("Notification preview"))
        assertTrue(has("Last synchronized:", substring = true))
        assertTrue("Access on pill", has("Access on"))
    }

    @Test fun neverSynchronizedIsStatedPlainly() {
        show(device(commsAt = 0L), sample)
        assertTrue(has("Last synchronized: never"))
    }

    @Test fun unknownDirectionAndMissingTextAreLabelled() {
        show(device(), sample)
        rule.onNodeWithText("Timeline").performClick()
        rule.waitForIdle()
        assertTrue(has("Incoming"))
        assertTrue(has("Outgoing"))
        assertTrue(has("Direction unknown"))
        assertTrue(has("Message content unavailable"))
        assertTrue("sender of the hidden preview is still shown", has("kabir_05"))
    }

    @Test fun conversationsGroupByChatAndMarkGroups() {
        show(device(), sample)
        assertTrue(has("Class 3B"))
        assertTrue(has("Group"))
    }

    @Test fun appTabFiltersTheList() {
        show(device(), sample)
        rule.onNodeWithText("Timeline").performClick()
        rule.onAllNodesWithText("Instagram")[0].performClick() // the tab: first in reading order, above the rows
        rule.waitForIdle()
        assertTrue(has("kabir_05"))
        assertTrue("WhatsApp rows are filtered out", !has("are you coming to the park?"))
    }

    @Test fun searchWithNoHitShowsAClearableEmptyState() {
        show(device(), sample)
        rule.onNode(hasSetTextAction()).performTextInput("zzzz")
        rule.waitForIdle()
        assertTrue(has("Nothing matches"))
        rule.onNodeWithText("Clear filters").performClick()
        rule.waitForIdle()
        assertTrue(!has("Nothing matches"))
    }

    @Test fun disabledNotificationAccessIsExplained() {
        show(device(accessOn = false), emptyList())
        assertTrue(has("Notification access disabled"))
        assertTrue(has("No message previews yet"))
        assertTrue(has("Access off"))
    }

    @Test fun oldChildAppIsToldToUpdateInsteadOfBeingReportedAsOff() {
        show(device(accessOn = false, known = false), emptyList())
        assertTrue(has("Update needed"))
        assertTrue(!has("Notification access disabled"))
    }

    @Test fun loadErrorOffersRetry() {
        show(device(), emptyList(), error = "Couldn't load messages: No internet connection.")
        assertTrue(has("Couldn't load messages", substring = true))
        assertTrue(has("Try again"))
    }

    @Test fun noPhoneShowsPairingPrompt() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(emptyList(), FamilyPolicy())
        rule.setContent { GuardianTheme(ThemeMode.Dark) { CommunicationScreen(vm, PaddingValues(0.dp)) } }
        rule.waitForIdle()
        assertTrue(has("No phone connected"))
    }
}
