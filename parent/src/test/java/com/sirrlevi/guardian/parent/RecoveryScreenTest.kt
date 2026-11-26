package com.sirrlevi.guardian.parent

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.LocationPermission
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.LocationRules
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoveryState
import com.sirrlevi.guardian.core.RecoveryTexts
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.RecoveryScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** The Find my phone screen must show only real positions with their age, explain what is missing, and never claim success before the phone confirms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class RecoveryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val on = RecoverySettings(true, 15, 7, now)

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

    private fun control(canRing: Boolean = true, admin: Boolean = true, ringUntil: Long = 0L) =
        ControlStatus(now - 5_000L, admin, false, admin, false, false, true, now - 3_600_000L, false, canRing = canRing, ringUntil = ringUntil)

    private fun point(ageMs: Long = 120_000L, provider: String = "gps") = LocationPoint(now - ageMs, 28.6139, 77.2090, 15f, provider)

    private fun state(perm: LocationPermission = LocationPermission.ALWAYS, services: Boolean = true) = RecoveryState(now - 30_000L, true, perm, services, now - 120_000L)

    private class Backends(val recovery: FakeRecoveryBackend = FakeRecoveryBackend(), val controls: FakeControlBackend = FakeControlBackend())

    private fun show(
        settings: RecoverySettings? = null, state: RecoveryState? = null, last: LocationPoint? = null, history: List<LocationPoint> = emptyList(),
        control: ControlStatus? = control(), error: String? = null, withDevice: Boolean = true, backends: Backends = Backends()
    ): Backends {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(if (withDevice) listOf(device()) else emptyList(), FamilyPolicy())
        backends.recovery.settingsNow = settings; backends.recovery.stateNow = state; backends.recovery.lastNow = last; backends.recovery.historyNow = history
        backends.controls.statusNow = control
        vm.recoveryBackendForTest = backends.recovery
        vm.controlBackendForTest = backends.controls
        // Everything stays on the test's own thread: no background threads can outlive a test or leave Compose's idle check waiting.
        vm.recovery.io = EmptyCoroutineContext
        vm.controls.io = EmptyCoroutineContext
        vm.recovery.showForTest(settings, state, last, history, now = now, error = error)
        vm.controls.showForTest(control, now = now)
        rule.setContent { GuardianTheme(ThemeMode.Light) { RecoveryScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
        return backends
    }

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun withoutAPhoneItSaysSo() {
        show(withDevice = false)
        assertTrue(has("No phone connected"))
        assertFalse(has("Find now"))
    }

    @Test fun withLocationOffNothingIsShownOrClaimed() {
        show()
        assertTrue(has("Off · nothing is read or sent"))
        assertTrue(has("No position yet"))
        assertTrue(has("Turn on location sharing to see where the phone is."))
        assertTrue("the reason find-now is unavailable", has(RecoveryTexts.OFF))
        assertFalse(has("Open in Maps"))
        assertFalse(has("How location is kept"))
    }

    @Test fun cameraAndMicrophoneAreShownAsNotOffered() {
        show()
        assertTrue(has("Camera or microphone"))
        assertTrue(has(RecoveryTexts.NO_CAMERA_MIC))
        assertTrue(has(RecoveryTexts.WHEN_OFF_NOTE))
        assertTrue(has("Unavailable"))
    }

    @Test fun turningLocationOnNeedsAConfirmationThatExplainsTheChildSeesIt() {
        val b = show()
        rule.onNodeWithText("Share this phone's location").performClick()
        assertTrue(has("Turn on location sharing?"))
        assertTrue(has("Your child sees this in Guardian Child", substring = true))
        assertTrue("nothing is saved before the parent agrees", b.recovery.saved.isEmpty())
        rule.onNodeWithText("Turn on").performClick()
        rule.waitUntil(5_000) { b.recovery.saved.isNotEmpty() }
        assertTrue(b.recovery.saved.single().enabled)
    }

    @Test fun turningLocationOffNeedsNoConfirmation() {
        val b = show(settings = on, state = state())
        rule.onNodeWithText("Share this phone's location").performClick()
        rule.waitUntil(5_000) { b.recovery.saved.isNotEmpty() }
        assertFalse(b.recovery.saved.single().enabled)
    }

    @Test fun showsTheLastRealPositionWithItsAccuracyAndAge() {
        show(settings = on, state = state(), last = point())
        assertTrue(has("Last position"))
        assertTrue(has("28.61390, 77.20900 · within about 15 m"))
        assertTrue(has("Open in Maps"))
        assertFalse(has("This position is old", substring = true))
    }

    @Test fun anOldPositionIsFlaggedAsOld() {
        show(settings = on, state = state(), last = point(ageMs = 3 * 3_600_000L))
        assertTrue(has("This position is old. Aarav's phone may have moved since."))
    }

    @Test fun aPositionFromAMockLocationAppIsFlaggedAsPossiblyFake() {
        show(settings = on, state = state(), last = point(provider = "mock"))
        assertTrue(has("mock location", substring = true))
    }

    @Test fun missingPermissionIsExplainedAndFindNowCannotBeSent() {
        val b = show(settings = on, state = state(LocationPermission.NONE))
        assertTrue(has(RecoveryTexts.NO_PERMISSION))
        rule.onNodeWithText("Find now").performClick()
        rule.waitForIdle()
        assertTrue(b.controls.sent.isEmpty())
    }

    @Test fun waitingForTheFirstReportIsSaidPlainly() {
        show(settings = on, state = null)
        assertTrue(has(RecoveryTexts.NO_REPORT))
    }

    @Test fun findNowSendsALocateRequestAndDoesNotClaimAPosition() {
        val b = show(settings = on, state = state())
        rule.onNodeWithText("Find now").performClick()
        rule.waitUntil(5_000) { b.controls.sent.isNotEmpty() }
        assertEquals("locate", b.controls.sent.single().typeCode)
        rule.waitForIdle()
        assertTrue(has("Waiting for the phone to answer…"))
        assertFalse(has(RecoveryTexts.LOCATED))
    }

    @Test fun ringNeedsAConfirmationAndOnlySendsAfterYes() {
        val b = show(settings = on, state = state())
        rule.onNodeWithText("Ring phone").performClick()
        assertTrue(has("Make Aarav's phone ring?"))
        assertTrue(has("A notice on the phone says Guardian is ringing it", substring = true))
        assertTrue(b.controls.sent.isEmpty())
        rule.onNodeWithText("Yes, ring it").performClick()
        rule.waitUntil(5_000) { b.controls.sent.isNotEmpty() }
        assertEquals("ring", b.controls.sent.single().typeCode)
    }

    @Test fun cancellingTheRingSendsNothing() {
        val b = show(settings = on, state = state())
        rule.onNodeWithText("Ring phone").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(b.controls.sent.isEmpty())
    }

    @Test fun ringIsExplainedWhenThePhoneCannotShowItsNotice() {
        val b = show(settings = on, state = state(), control = control(canRing = false))
        assertTrue(has(RecoveryTexts.RING_UNAVAILABLE))
        rule.onNodeWithText("Ring phone").performClick()
        rule.waitForIdle()
        assertFalse(has("Make Aarav's phone ring?"))
        assertTrue(b.controls.sent.isEmpty())
    }

    @Test fun ringingIsShownWhileThePhoneReportsIt() {
        show(settings = on, state = state(), control = control(ringUntil = now + 30_000L))
        assertTrue(has("Ringing now", substring = true))
    }

    @Test fun lockAsksForConfirmationLikeEverywhereElse() {
        val b = show(settings = on, state = state())
        rule.onNodeWithText("Lock now").performClick()
        assertTrue(has("Lock Aarav's phone now?"))
        assertTrue(b.controls.sent.isEmpty())
        rule.onNodeWithText("Yes, lock it").performClick()
        rule.waitUntil(5_000) { b.controls.sent.isNotEmpty() }
        assertEquals("lock", b.controls.sent.single().typeCode)
    }

    @Test fun openInMapsHandsTheRealCoordinatesToAMapApp() {
        val p = point()
        show(settings = on, state = state(), last = p)
        rule.onNodeWithText("Open in Maps").performClick()
        rule.waitForIdle()
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertNotNull(started)
        assertEquals(LocationRules.geoUri(p, "Aarav's phone"), started.data.toString())
    }

    @Test fun theIntervalCanBeChangedWhileSharingIsOn() {
        val b = show(settings = on, state = state())
        assertTrue(has("How location is kept"))
        rule.onNodeWithText("30 min").performClick()
        rule.waitUntil(5_000) { b.recovery.saved.isNotEmpty() }
        assertEquals(30, b.recovery.saved.last().intervalMin)
        assertTrue("the other choices are kept", b.recovery.saved.last().enabled)
    }

    @Test fun theRetentionCanBeChangedWhileSharingIsOn() {
        val b = show(settings = on, state = state())
        rule.onNodeWithText("30 days").performClick()
        rule.waitUntil(5_000) { b.recovery.saved.isNotEmpty() }
        assertEquals(30, b.recovery.saved.last().retentionDays)
    }

    @Test fun deletingSavedPositionsAsksFirst() {
        val b = show(settings = on, state = state(), last = point(), history = listOf(point(), point(600_000L)))
        rule.onNodeWithText("Delete all saved positions").performClick()
        assertTrue(has("Delete all saved positions?"))
        assertEquals(0, b.recovery.cleared)
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5_000) { b.recovery.cleared == 1 }
    }

    @Test fun savedPositionsAreListedNewestFirstWhenOpened() {
        show(settings = on, state = state(), last = point(), history = listOf(point(120_000L), point(900_000L)))
        assertTrue(has("2 saved, newest first"))
        val before = count("28.61390, 77.20900 · within about 15 m")
        rule.onNodeWithText("Show").performClick()
        rule.waitForIdle()
        assertEquals(before + 2, count("28.61390, 77.20900 · within about 15 m"))
    }

    @Test fun theStatusRowsShowRealValues() {
        show(settings = on, state = state(LocationPermission.WHILE_OPEN))
        assertTrue(has("Recovery status"))
        assertTrue(has("Allowed only while the app is open", substring = true))
        assertTrue(has("Samsung SM-A145F · Android 14"))
        assertTrue(has("Wi-Fi", substring = true))
    }

    @Test fun aServerErrorIsShownAndTheOldDataStays() {
        show(settings = on, state = state(), last = point(), error = "No internet connection.")
        assertTrue(has("Couldn't reach the server: No internet connection.", substring = true))
        assertTrue(has("Last position"))
    }
}
