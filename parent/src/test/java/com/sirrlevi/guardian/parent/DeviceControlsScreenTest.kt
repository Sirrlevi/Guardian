package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlResult
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.ControlTexts
import com.sirrlevi.guardian.core.ControlType
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.parent.ui.DeviceControlsScreen
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** The Device controls screen must show the phone's real state, offer only what is possible, and never claim success before the phone confirms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class DeviceControlsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun info() = DeviceInfo(
        ts = now - 20_000L, model = "SM-A145F", manufacturer = "Samsung", androidVersion = "14", battery = 63, charging = false,
        storageTotalMb = 0L, storageFreeMb = 0L, ramTotalMb = 0L, ramFreeMb = 0L, network = "wifi", screenOn = true, uptimeMin = 300L,
        autoTime = true, appVersion = "1.0", enforcement = "overlay", permUsage = true, permOverlay = true, permNotifications = true,
        permBattery = true, permAdmin = true, permVpn = false, permOwner = false, monitorRunning = true, monitorKnown = true
    )

    private fun device() = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now, apps = emptyList(), info = info(),
        live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(),
        visits = emptyList(), requests = emptyList(), hasMonitor = true
    )

    private fun status(admin: Boolean = true, owner: Boolean = false) = ControlStatus(now - 10_000L, admin, owner, admin, owner, false, true, now - 3_600_000L, false)

    private fun show(
        status: ControlStatus?, log: List<ControlEvent> = emptyList(), error: String? = null, notice: String? = null, problem: Boolean = false,
        waiting: ControlType? = null, withDevice: Boolean = true, backend: FakeControlBackend? = null
    ): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(if (withDevice) listOf(device()) else emptyList(), FamilyPolicy())
        val fake = backend ?: FakeControlBackend()
        fake.statusNow = status
        vm.controlBackendForTest = fake
        // Everything stays on the test's own thread: no background threads can outlive a test or leave Compose's idle check waiting.
        vm.controls.io = EmptyCoroutineContext
        vm.controls.showForTest(status, log, now = now, error = error, notice = notice, noticeIsProblem = problem, waiting = waiting)
        rule.setContent { GuardianTheme(ThemeMode.Light) { DeviceControlsScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
        return vm
    }

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun withoutAPhoneItSaysSo() {
        show(null, withDevice = false)
        assertTrue(has("No phone connected"))
        assertFalse(has("Lock now"))
    }

    @Test fun showsTheRealStatusOfThePhone() {
        show(status())
        assertTrue(has("Phone status"))
        assertTrue(has("63%"))
        assertTrue(has("Samsung SM-A145F · Android 14"))
        assertTrue(has("Online"))
        assertTrue(has("Running"))
        assertTrue(has("All granted"))
    }

    @Test fun offersLockWhenAllowedAndExplainsRestartWhenItIsNot() {
        show(status(admin = true, owner = false))
        assertTrue(has("Lock now"))
        assertTrue(has("Restart phone"))
        assertTrue(has(ControlTexts.REBOOT_NEEDS_OWNER))
        assertFalse(has(ControlTexts.LOCK_NEEDS_ADMIN))
    }

    @Test fun explainsWhyLockIsUnavailableWithoutAdmin() {
        show(status(admin = false, owner = false))
        assertTrue(has(ControlTexts.LOCK_NEEDS_ADMIN))
        assertTrue(has(ControlTexts.REBOOT_NEEDS_OWNER))
    }

    @Test fun waitsForTheFirstReportBeforeOfferingAnything() {
        show(null)
        assertTrue(has(ControlTexts.NO_REPORT))
    }

    @Test fun unlockAndShutdownAreShownAsUnavailableWithTheReason() {
        show(status(owner = true))
        assertTrue(has("Unlock the phone remotely"))
        assertTrue(has("Shut the phone down"))
        assertTrue(has(ControlTexts.UNLOCK_NOTE))
        assertTrue(has(ControlTexts.SHUTDOWN_NOTE))
        assertEquals(2, rule.onAllNodesWithText("Unavailable").fetchSemanticsNodes().size)
    }

    @Test fun lockAsksForConfirmationThenSendsButDoesNotClaimItLocked() {
        val fake = FakeControlBackend()
        show(status(), backend = fake)
        rule.onNodeWithText("Lock now").performClick()
        assertTrue(has("Lock Aarav's phone now?"))
        assertTrue(fake.sent.isEmpty())
        rule.onNodeWithText("Yes, lock it").performClick()
        rule.waitUntil(5_000) { fake.sent.isNotEmpty() }
        assertEquals("lock", fake.sent.single().typeCode)
        rule.waitForIdle()
        assertFalse(has("Locked.", substring = true))
        assertTrue(has("Waiting for the phone to answer…"))
        assertTrue(has("Request sent.", substring = true))
    }

    @Test fun cancellingTheConfirmationSendsNothing() {
        val fake = FakeControlBackend()
        show(status(), backend = fake)
        rule.onNodeWithText("Lock now").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun whileARequestIsWaitingNothingElseCanBeSent() {
        show(status(owner = true), waiting = ControlType.LOCK)
        assertTrue(has("Waiting for the phone to answer…"))
        rule.onNodeWithText("Lock now").performClick()
        rule.waitForIdle()
        // The button is disabled, so no confirmation opens.
        assertFalse(has("Lock Aarav's phone now?"))
    }

    @Test fun aRestartNeedsADeliberateConfirmationAndExplainsTheAfterEffects() {
        val fake = FakeControlBackend()
        show(status(owner = true), backend = fake)
        rule.onNodeWithText("Restart phone").performClick()
        assertTrue(has("Restart Aarav's phone?"))
        assertTrue(has(ControlTexts.AFTER_RESTART_NOTE))
        assertTrue(fake.sent.isEmpty())
        // This test phone has no screen lock, so the dialog says it can't ask for one; the request goes out after the explicit confirmation.
        assertTrue(has("This phone has no screen lock", substring = true))
        rule.onNodeWithText("Yes, restart").performClick()
        rule.waitUntil(5_000) { fake.sent.isNotEmpty() }
        assertEquals("reboot", fake.sent.single().typeCode)
    }

    @Test fun aRefusalFromThePhoneIsShownWithItsReason() {
        show(status(), notice = ControlTexts.LOCK_NEEDS_ADMIN, problem = true)
        assertTrue(has(ControlTexts.LOCK_NEEDS_ADMIN, substring = true))
    }

    @Test fun anOldReportWarnsThatARequestMayNotBeAnswered() {
        val stale = ControlStatus(now - 3_600_000L, true, false, true, false, false, true, 0L, false)
        show(stale)
        assertTrue(has("hasn't reported lately", substring = true))
    }

    @Test fun serverErrorsAreShownAndTheLastKnownStatusStays() {
        show(status(), error = "No internet connection.")
        assertTrue(has("Couldn't reach the server: No internet connection."))
        assertTrue(has("63%"))
    }

    @Test fun recentActivityListsWhatThePhoneRecorded() {
        show(status(), log = listOf(ControlEvent(now - 60_000L, "lock", ControlResult.DONE, "ctl-00000001")))
        assertFalse(has("Screen locked (confirmed by the phone)"))
        rule.onNodeWithText("Show").performClick()
        assertTrue(has("Screen locked (confirmed by the phone)"))
    }
}
