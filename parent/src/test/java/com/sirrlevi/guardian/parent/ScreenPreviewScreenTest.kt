package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.CaptureEvent
import com.sirrlevi.guardian.core.CapturePolicy
import com.sirrlevi.guardian.core.CaptureReasons
import com.sirrlevi.guardian.core.CaptureState
import com.sirrlevi.guardian.core.CaptureStatus
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.ShotMeta
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.ScreenPreviewScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** The Screen preview screen must show the phone's real state, offer only what is possible, and never claim sharing before the child allows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class ScreenPreviewScreenTest {
    @get:Rule val rule = createComposeRule()

    @org.junit.Before fun shortIdleTimeout() { // TEMP diag
        try {
            Class.forName("androidx.test.espresso.IdlingPolicies")
                .getMethod("setMasterPolicyTimeout", java.lang.Long.TYPE, java.util.concurrent.TimeUnit::class.java)
                .invoke(null, 8L, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Throwable) { System.err.println("DIAG-DUMP no idle policy: $e") }
    }

    private val now = System.currentTimeMillis()

    private fun device() = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now, apps = emptyList(), info = null,
        live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(),
        visits = emptyList(), requests = emptyList(), hasMonitor = true
    )

    private fun show(
        state: CaptureState?, shots: List<ShotMeta> = emptyList(), log: List<CaptureEvent> = emptyList(), error: String? = null,
        notice: String? = null, problem: Boolean = false, withDevice: Boolean = true, backend: FakePreviewBackend? = null
    ): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(if (withDevice) listOf(device()) else emptyList(), FamilyPolicy())
        vm.previewBackendForTest = backend ?: FakePreviewBackend()
        // Everything stays on the test's own thread: no background threads can outlive a test or leave Compose's idle check waiting.
        vm.preview.io = EmptyCoroutineContext
        vm.preview.showForTest(state, shots, log, error = error, notice = notice, noticeIsProblem = problem, now = now)
        val testThread = Thread.currentThread()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            Thread.sleep(5_000L)
            if (!done.get()) {
                System.err.println("DIAG-DUMP test thread stack:")
                testThread.stackTrace.take(60).forEach { System.err.println("DIAG-DUMP   at $it") }
                Thread.getAllStackTraces().forEach { (t, st) ->
                    if (t !== testThread) { System.err.println("DIAG-DUMP thread ${t.name} state=${t.state}"); st.take(14).forEach { System.err.println("DIAG-DUMP     at $it") } }
                }
            }
        }.apply { isDaemon = true; start() }
        rule.setContent { GuardianTheme(ThemeMode.Light) { ScreenPreviewScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
        done.set(true)
        return vm
    }

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun active(live: Boolean = false, heartbeat: Long = now) =
        CaptureState(status = CaptureStatus.ACTIVE, sessionId = "s", since = now - 60_000L, expiresAt = now + 600_000L, live = live, heartbeat = heartbeat, width = 540, height = 1200, lastShotAt = now - 30_000L, transfer = "ok")

    @Test fun explainsThatTheChildMustAllowItEveryTime() {
        show(null)
        assertTrue(has("Screen preview"))
        assertTrue(has("Only with your child's OK, every time"))
        assertTrue(has("every time", substring = true))
        assertTrue(has("show as black", substring = true))
    }

    @Test fun whenNothingIsSharedOnlyAskingIsOffered() {
        show(CaptureState())
        assertTrue(has("Not sharing"))
        assertTrue(has("Ask to see the screen"))
        assertFalse(has("Take screenshot"))
        assertFalse(has("Stop sharing"))
        assertTrue(has("None yet"))
        assertTrue(has("No screenshots yet"))
    }

    @Test fun askingSendsARequestButDoesNotClaimSharing() {
        val fake = FakePreviewBackend()
        show(CaptureState(), backend = fake)
        rule.onNodeWithText("Ask to see the screen").performClick()
        rule.waitUntil(5_000) { fake.sent.isNotEmpty() }
        assertEquals("ask", fake.sent.single().type.code)
        rule.waitForIdle()
        assertFalse(has("Sharing now"))
    }

    @Test fun waitingShowsThatTheChildHasNotAllowedYet() {
        show(CaptureState(status = CaptureStatus.AWAITING, since = now, expiresAt = now + 300_000L, notifOk = false))
        assertTrue(has("Waiting for Aarav's phone to allow"))
        assertTrue(has("Cancel request"))
        assertFalse(has("Take screenshot"))
        assertTrue(has("Notifications are off on the phone", substring = true))
        assertTrue(has("Off, so requests may go unseen"))
    }

    @Test fun anApprovedSessionOffersScreenshotLiveAndStop() {
        show(active())
        assertTrue(has("Sharing now"))
        assertTrue(has("Take screenshot"))
        assertTrue(has("Start live preview"))
        assertTrue(has("Stop sharing"))
        assertTrue(has("Allowed on the phone for this session"))
        assertTrue(has("Last picture delivered"))
        assertFalse(has("Ask to see the screen"))
    }

    @Test fun liveShowsItsStateAndTheStopControl() {
        show(active(live = true))
        assertTrue(has("Live preview"))
        assertTrue(has("Stop live preview"))
        assertTrue(has("Waiting for the first picture…"))
        assertTrue(has("not video", substring = true))
    }

    @Test fun aSilentPhoneOffersNoScreenshotsOnlyEndingTheSession() {
        show(active(heartbeat = now - CapturePolicy.STATE_STALE_MS - 10_000L))
        assertTrue(has("The phone stopped answering"))
        assertFalse(has("Take screenshot"))
        assertTrue(has("End this session"))
    }

    @Test fun aDeclinedRequestIsShownAsDeclined() {
        show(CaptureState(status = CaptureStatus.ENDED, reason = CaptureReasons.DECLINED, since = now))
        assertTrue(has("Session ended"))
        assertTrue(has("Aarav's phone chose not to share."))
        assertTrue(has("Aarav's phone declined"))
        assertTrue(has("Ask to see the screen"))
    }

    @Test fun blankScreenReportedByThePhoneIsShownWithItsReason() {
        show(active().copy(error = "The screen looked completely black."))
        assertTrue(has("The phone says: The screen looked completely black.", substring = true))
    }

    @Test fun problemsAndConnectionErrorsAreVisible() {
        show(active(), notice = "The phone's screen is off.", problem = true, error = "No internet connection.")
        assertTrue(has("The phone's screen is off."))
        assertTrue(has("Couldn't reach the server: No internet connection.", substring = true))
        assertTrue(has("Can't reach the server. Showing the last known state."))
    }

    @Test fun screenshotsAreListedWithAViewerAndTheRetentionNote() {
        val shots = listOf(ShotMeta("0000000000002_00", now - 1_000L, 540, 1200, 1000, 1), ShotMeta("0000000000001_00", now - 90_000L, 540, 1200, 1000, 1))
        show(active(), shots = shots)
        assertTrue(has("Latest screenshot"))
        assertTrue(has("Earlier screenshots"))
        assertTrue(has("Open, zoom and save"))
        assertTrue(has("The newest ${CapturePolicy.SHOT_KEEP} are kept", substring = true))
    }

    @Test fun recentActivityIsShownOnRequest() {
        show(CaptureState(), log = listOf(CaptureEvent(now - 5_000L, "approved", null), CaptureEvent(now - 9_000L, "asked", null)))
        assertFalse(has("Sharing approved on the phone"))
        rule.onNodeWithText("Show").performClick()
        rule.waitForIdle()
        assertTrue(has("Sharing approved on the phone"))
        assertTrue(has("Parent asked to share the screen"))
    }

    @Test fun withoutAPhoneItSaysSo() {
        show(null, withDevice = false)
        assertTrue(has("No phone connected"))
    }
}
