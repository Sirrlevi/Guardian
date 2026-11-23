package com.sirrlevi.guardian.child

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.child.ui.ChildActions
import com.sirrlevi.guardian.child.ui.ChildApp
import com.sirrlevi.guardian.child.ui.ChildTab
import com.sirrlevi.guardian.child.ui.ChildTheme
import com.sirrlevi.guardian.core.AppRule
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.Schedule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders every Child screen with sample data so a crash while composing fails the build, not the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ChildUiSmokeTest {
    @get:Rule val rule = createComposeRule()

    private val actions = ChildActions(grant = {}, openBrowser = {}, startWebsiteFilter = {}, install = {})

    @Test fun pairingScreenRendersWhenUnpaired() {
        val vm = ChildViewModel(ApplicationProvider.getApplicationContext())
        rule.setContent { ChildTheme { ChildApp(vm, null, {}, actions) } }
        rule.waitForIdle()
        assert(rule.onAllNodesWithText("Link this phone").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun everyPairedScreenRenders() {
        val vm = ChildViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(
            listOf(
                ChildPermissions.Item("usage", "Usage access", "See which app is open", true, true),
                ChildPermissions.Item("overlay", "Display over other apps", "Show the time's up screen", false, true),
                ChildPermissions.Item("notifications", "Notifications", "Keep tracking alive", true, true),
                ChildPermissions.Item("battery", "Battery", "Run in the background", false, false),
                ChildPermissions.Item("notifaccess", "Message previews (optional)", "Share chat notification previews", false, false)
            ),
            FamilyPolicy(
                appRules = mapOf(
                    "com.google.android.youtube" to AppRule(dailyLimitSec = 3600),
                    "com.instagram.android" to AppRule(blocked = true)
                ),
                schedules = listOf(Schedule("s1", "School hours", listOf(1, 2, 3, 4, 5, 6, 7), 0, 24 * 60 - 1, listOf("com.duolingo"))),
                pauseUntil = System.currentTimeMillis() + 20 * 60_000L,
                updatedAt = 1L
            ),
            listOf("com.google.android.youtube" to 2400, "com.duolingo" to 900, "com.android.chrome" to 600)
        )
        var requested by mutableStateOf<ChildTab?>(null)
        rule.setContent { ChildTheme { ChildApp(vm, requested, { requested = null }, actions) } }
        rule.waitForIdle()
        for (tab in ChildTab.values()) {
            rule.runOnIdle { requested = tab }
            rule.waitForIdle()
        }
    }
}
