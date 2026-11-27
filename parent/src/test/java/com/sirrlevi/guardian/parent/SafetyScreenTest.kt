package com.sirrlevi.guardian.parent

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.AlertKind
import com.sirrlevi.guardian.core.AlertLevel
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.SafetyAlert
import com.sirrlevi.guardian.core.SafetyIds
import com.sirrlevi.guardian.core.SafetyInput
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.SafetyScreen
import com.sirrlevi.guardian.parent.ui.SafetyTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.coroutines.EmptyCoroutineContext

/** The alerts screen must give every alert its reason, never present "nothing found" as "safe", and only change things the database confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class SafetyScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_800_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")

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

    private val alert = SafetyAlert(
        SafetyIds.id(AlertKind.MONITORING_OFF, "usage|x"), AlertKind.MONITORING_OFF, AlertLevel.REVIEW, "Usage access is off",
        "Guardian Child can no longer see which apps are used (as the phone last reported, 2 minutes ago).", now - 120_000L, "Guardian Child"
    )

    private val backend = FakeSafetyBackend(SafetyInput("Aarav's phone", now, utc, reportedAt = now - 60_000L, usageAccess = false, lastSeenMs = now - 60_000L))

    /** [check] runs the real model against the fake database first, so dismissing and settings behave as they do in the app. */
    private fun show(withDevice: Boolean = true, check: Boolean = true, prepare: (SafetyModel) -> Unit = {}): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.showForTest(if (withDevice) listOf(device()) else emptyList(), FamilyPolicy())
        // Everything stays on the test's own thread: no background threads can outlive a test or leave Compose's idle check waiting.
        vm.safety.io = EmptyCoroutineContext
        vm.safetyBackendForTest = backend
        if (check) runBlocking { vm.safety.tick() }
        prepare(vm.safety)
        rule.setContent { GuardianTheme(ThemeMode.Light) { SafetyScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
        return vm
    }

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun withoutAPhoneItSaysSo() {
        show(withDevice = false, check = false)
        assertTrue(has("No phone connected"))
        assertFalse(has("What to look for"))
    }

    @Test fun theScreenSaysTheParentDecidesAndNothingIsBlockedOrSent() {
        show()
        assertTrue(has(SafetyTexts.INTRO_TITLE))
        assertTrue(has("Nothing is blocked or sent to anyone", substring = true))
        assertTrue(has(SafetyTexts.LIMITS_TITLE))
    }

    @Test fun anAlertShowsItsNeutralLabelTitleReasonAndSource() {
        show()
        assertTrue(has("Review recommended"))
        assertTrue(has("Usage access is off"))
        assertTrue(has("as the phone last reported", substring = true))
        assertTrue(has("From: Guardian Child"))
        assertTrue(has("Dismiss"))
    }

    @Test fun whileLoadingItSaysItIsChecking() {
        show(check = false)
        assertTrue(has(SafetyTexts.LOADING))
        assertFalse(has(SafetyTexts.NOTHING))
    }

    @Test fun aFailedFirstCheckIsNeverShownAsNothingFound() {
        backend.failRead = java.io.IOException("No internet connection.")
        show()
        assertTrue(has(SafetyTexts.FAILED))
        assertTrue(has("No internet connection."))
        assertFalse(has(SafetyTexts.NOTHING))
        assertFalse(has("What to look for"))
    }

    @Test fun anEmptyListSaysThatItDoesNotMeanEverythingIsFine() {
        backend.inputNow = SafetyInput("Aarav's phone", now, utc, reportedAt = now - 60_000L, usageAccess = true, lastSeenMs = now - 60_000L)
        show()
        assertTrue(has(SafetyTexts.NOTHING))
        assertTrue(has("That doesn't mean everything is fine", substring = true))
        assertFalse(has("all clear", substring = true))
        assertFalse(has("is safe", substring = true))
    }

    @Test fun anOldResultIsShownAsOldWhenTheLastRefreshFailed() {
        val vm = show()
        runBlocking { backend.failRead = java.io.IOException("offline"); vm.safety.tick() }
        rule.waitForIdle()
        assertTrue(has(SafetyTexts.STALE))
        assertTrue(has("Usage access is off"))
    }

    @Test fun dismissingMovesTheAlertToTheDismissedListAndSavesIt() {
        show()
        rule.onNodeWithText("Dismiss").performClick()
        rule.waitForIdle()
        assertEquals(1, backend.dismissedNow.size)
        assertFalse(has("Dismiss"))
        assertFalse(has("Usage access is off"))
        assertTrue(has("Show dismissed (1)"))
        rule.onNodeWithText("Show dismissed (1)").performClick()
        rule.waitForIdle()
        assertTrue(has("Usage access is off"))
        assertTrue(has("Bring back"))
        rule.onNodeWithText("Bring back").performClick()
        rule.waitForIdle()
        assertTrue(backend.dismissedNow.isEmpty())
        assertTrue(has("Dismiss"))
    }

    @Test fun aFailedDismissalIsReportedAndTheAlertStays() {
        show()
        backend.failWrite = java.io.IOException("denied")
        rule.onNodeWithText("Dismiss").performClick()
        rule.waitForIdle()
        assertTrue(has(SafetyModel.DISMISS_FAILED, substring = true))
        assertTrue(has("Usage access is off"))
        assertTrue(backend.dismissedNow.isEmpty())
    }

    @Test fun everyKindCanBeSwitchedOffAndTheChoiceIsSaved() {
        show()
        AlertKind.values().forEach { assertTrue(it.setting, has(it.setting)) }
        rule.onNodeWithText("Guardian's access switched off").performClick()
        rule.waitForIdle()
        assertFalse(backend.savedSettings.last().on(AlertKind.MONITORING_OFF))
        assertFalse("its alert goes away with it", has("Usage access is off"))
    }

    @Test fun theLimitsCanBeRaisedAndAreKeptInRange() {
        show()
        assertTrue(has("5 h a day"))
        rule.onAllNodesWithText("+")[2].performClick()
        rule.waitForIdle()
        assertEquals(6, backend.savedSettings.last().usageHours)
        assertTrue(has("6 h a day"))
    }

    @Test fun theNightWindowIsShownAsClockTimes() {
        show()
        assertTrue(has("23:00"))
        assertTrue(has("05:00"))
    }

    @Test fun aPermissionAlertAsksNothingOfTheChildAndSaysItCannotKnowWhen() {
        backend.permsNow = mapOf("usage" to true)
        backend.inputNow = SafetyInput("Aarav's phone", now, utc, lastSeenMs = now - 60_000L, perms = mapOf("usage" to false))
        show()
        assertTrue(has("Usage access was switched off"))
        assertTrue(has("can't say exactly when or why", substring = true))
    }
}
