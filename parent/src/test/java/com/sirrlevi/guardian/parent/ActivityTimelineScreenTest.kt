package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.ScreenEvent
import com.sirrlevi.guardian.core.ScreenKind
import com.sirrlevi.guardian.parent.ui.ActivityScreen
import com.sirrlevi.guardian.parent.ui.Formats
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class ActivityTimelineScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val today = Formats.lastDays(1, now)[0]
    private val yt = "com.google.android.youtube"

    private fun device(screen: List<ScreenEvent>, withSessions: Boolean = true) = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now,
        apps = listOf(AppInfo(yt, "YouTube", "video", false, 0L, 0L, "19.1")),
        info = null, live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 },
        sessions = if (withSessions) mapOf(today to listOf(SessionRow(yt, "YouTube", now - 3_600_000L, now - 3_000_000L))) else emptyMap(),
        daily = emptyList(), visits = emptyList(), requests = emptyList(), hasMonitor = true,
        screenEvents = mapOf(today to screen)
    )

    private fun show(d: ChildDevice) {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(d), FamilyPolicy())
        rule.setContent { GuardianTheme(ThemeMode.Light) { ActivityScreen(vm, PaddingValues(0.dp), onOpenApp = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Timeline").performClick()
        rule.waitForIdle()
    }

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun timelineListsAppAndScreenEventsAndSaysWhatItIs() {
        show(device(listOf(
            ScreenEvent(now - 3_700_000L, ScreenKind.ON), ScreenEvent(now - 3_650_000L, ScreenKind.UNLOCKED),
            ScreenEvent(now - 2_900_000L, ScreenKind.OFF)
        )))
        assertTrue(has("App usage events"))
        assertTrue(has("Opened YouTube"))
        assertTrue(has("YouTube moved to background"))
        assertTrue(has("Screen turned on"))
        assertTrue(has("Phone unlocked"))
        assertTrue(has("Screen turned off"))
    }

    @Test fun missingScreenEventsAreNotInventedAndUnlocksShowAsNotReported() {
        show(device(emptyList()))
        assertTrue(has("Opened YouTube"))
        assertTrue(!has("Screen turned on"))
        assertTrue(has("not reported"))
    }

    @Test fun emptyDayExplainsItself() {
        show(device(emptyList(), withSessions = false))
        assertTrue(has("No activity recorded"))
    }
}
