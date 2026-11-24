package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.MediaAccess
import com.sirrlevi.guardian.core.MediaSettings
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.HomeScreen
import com.sirrlevi.guardian.parent.ui.MoreScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home shows one honest line per optional tool and opens its own screen; More groups the tools. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class HomeToolsTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun device(accessOn: Boolean = false, known: Boolean = true, commsAt: Long = 0L) = ChildDevice(
        id = "kid1", name = "Aarav", manufacturer = "Samsung", lastSeen = now, apps = emptyList(),
        info = DeviceInfo(now, "Galaxy A15", "Samsung", "14", 63, false, 128_000, 52_000, 6_000, 2_100, "wifi", true, 600, true, "1.0.52",
            "overlay", true, true, true, false, false, false, false, permNotifAccess = accessOn, notifAccessKnown = known),
        live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(),
        visits = emptyList(), requests = emptyList(), hasMonitor = true, commsAt = commsAt
    )

    private var opened: Tab? = null

    private fun showHome(d: ChildDevice, configure: (ParentViewModel) -> Unit = {}): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(d), FamilyPolicy())
        configure(vm)
        opened = null
        rule.setContent { GuardianTheme(ThemeMode.Light) { HomeScreen(vm, PaddingValues(0.dp), onOpenApp = {}, goTab = { opened = it }) } }
        rule.waitForIdle()
        return vm
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun status(access: MediaAccess = MediaAccess.FULL, enabled: Boolean = true, pending: Int = 0, uploaded: Int = 0) =
        MediaStatusSnapshot(access, enabled, pending, uploaded, 0, now, null, null)

    @Test fun messagesLineSaysOptionalAndOffWhenThePhoneHasNotTurnedItOn() {
        showHome(device(accessOn = false))
        assertTrue(has("Messages & media"))
        assertTrue(has("Not turned on on the phone (optional)"))
    }

    @Test fun messagesLineAsksForAnUpdateFromAnOldChildApp() {
        showHome(device(known = false))
        assertTrue(has("Update Guardian Child on this phone to use this"))
    }

    @Test fun messagesLineShowsLastSyncWhenOn() {
        showHome(device(accessOn = true, commsAt = now - 120_000L))
        assertTrue(has("Previews on · synced", substring = true))
    }

    @Test fun messagesLineAdmitsNothingHasSyncedYet() {
        showHome(device(accessOn = true, commsAt = 0L))
        assertTrue(has("Previews on · nothing synced yet"))
    }

    @Test fun mediaLineIsNeutralUntilTheBackupStateIsKnown() {
        showHome(device())
        assertTrue(has("Photo and video previews"))
        assertFalse("must not claim on/off before it is known", has("Off · nothing is copied"))
    }

    @Test fun mediaLineSaysOffWhenTheParentHasNotTurnedItOn() {
        showHome(device()) { it.showMediaForTest(emptyList(), MediaSettings(enabled = false), null) }
        assertTrue(has("Off · nothing is copied"))
    }

    @Test fun mediaLineWaitsForThePhoneToReport() {
        showHome(device()) { it.showMediaForTest(emptyList(), MediaSettings(enabled = true), null) }
        assertTrue(has("On · waiting for the phone to report"))
    }

    @Test fun mediaLineFlagsMissingPhonePermission() {
        showHome(device()) { it.showMediaForTest(emptyList(), MediaSettings(enabled = true), status(access = MediaAccess.NONE)) }
        assertTrue(has("On · photo access is off on the phone"))
        assertTrue(has("Needs permission"))
    }

    @Test fun mediaLineShowsRealCounts() {
        showHome(device()) { it.showMediaForTest(emptyList(), MediaSettings(enabled = true), status(pending = 2, uploaded = 7)) }
        assertTrue(has("On · 7 backed up, 2 waiting"))
        assertFalse(has("Needs permission"))
    }

    @Test fun rowsOpenTheirOwnScreens() {
        showHome(device())
        rule.onNodeWithText("Messages").performClick()
        rule.waitForIdle()
        assertEquals(Tab.Messages, opened)
        rule.onNodeWithText("Shared media").performClick()
        rule.waitForIdle()
        assertEquals(Tab.Media, opened)
    }

    @Test fun moreGroupsToolsAndOpensThem() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(device()), FamilyPolicy())
        var open: Tab? = null
        rule.setContent { GuardianTheme(ThemeMode.Dark) { MoreScreen(vm, PaddingValues(0.dp)) { open = it } } }
        rule.waitForIdle()
        listOf("Insights", "Optional tools", "Guardian", "Reports", "Shared media", "Settings").forEach { assertTrue(it, has(it)) }
        rule.onNodeWithText("Shared media").performClick()
        rule.waitForIdle()
        assertEquals(Tab.Media, open)
    }
}
