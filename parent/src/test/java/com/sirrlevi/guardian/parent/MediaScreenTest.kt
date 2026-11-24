package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.MediaAccess
import com.sirrlevi.guardian.core.MediaFailure
import com.sirrlevi.guardian.core.MediaItem
import com.sirrlevi.guardian.core.MediaKind
import com.sirrlevi.guardian.core.MediaSettings
import com.sirrlevi.guardian.core.MediaStatus
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import com.sirrlevi.guardian.parent.ui.Formats
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.MediaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Shared media screen must say what it really stores (preview copies), ask before switching backup on,
 * and report what the phone cannot do instead of hiding it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class MediaScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun device(withInfo: Boolean = true) = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now, apps = emptyList(),
        info = if (withInfo) DeviceInfo(now, "Galaxy A15", "Samsung", "14", 63, false, 128_000, 52_000, 6_000, 2_100, "wifi", true, 600, true, "1.0.52",
            "overlay", true, true, true, false, false, false, false) else null,
        live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(),
        visits = emptyList(), requests = emptyList(), hasMonitor = true
    )

    private fun item(
        n: Int, kind: MediaKind = MediaKind.IMAGE, ageMs: Long = 10_000L, status: MediaStatus = MediaStatus.OK,
        reason: String? = null, durationMs: Long = 0L
    ) = MediaItem(
        key = String.format("%010d_%020x", (now - ageMs - n * 1_000L) / 1000L, n), kind = kind,
        name = if (kind == MediaKind.VIDEO) "VID_$n.mp4" else "IMG_$n.jpg", mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        sizeBytes = 3_400_000L, createdMs = now - ageMs - n * 1_000L, width = 4000, height = 3000, durationMs = durationMs,
        uploadedMs = now, status = status, reason = reason, previewBytes = 180_000, chunks = 1, thumb = null
    )

    private fun status(
        access: MediaAccess = MediaAccess.FULL, enabled: Boolean = true, pending: Int = 0, uploaded: Int = 0, failed: Int = 0,
        waiting: String? = null, error: String? = null, lastRun: Long = now - 60_000L
    ) = MediaStatusSnapshot(access, enabled, pending, uploaded, failed, lastRun, waiting, error)

    private fun show(
        items: List<MediaItem> = emptyList(), settings: MediaSettings = MediaSettings(), status: MediaStatusSnapshot? = null,
        hasMore: Boolean = false, error: String? = null, withInfo: Boolean = true
    ): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(device(withInfo)), FamilyPolicy())
        vm.showMediaForTest(items, settings, status, hasMore, error)
        rule.setContent { GuardianTheme(ThemeMode.Light) { MediaScreen(vm, PaddingValues(0.dp)) } }
        rule.waitForIdle()
        return vm
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun hasDescription(text: String, substring: Boolean = false) =
        rule.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun saysPreviewCopiesOnlyAndThatOriginalsStayOnThePhone() {
        show()
        assertTrue(has("Preview copies only"))
        assertTrue(has("never uploaded", substring = true))
        assertTrue(has("original-quality backup needs separate file storage", substring = true))
    }

    @Test fun backupIsOffByDefaultAndSaysNothingIsCopied() {
        show()
        assertTrue(has("Back up new photos & videos"))
        assertTrue(has("Off · nothing is copied"))
        assertTrue(has("Photo backup is off"))
        assertFalse("sub-settings are hidden while off", has("Keep previews for"))
    }

    @Test fun switchingOnAsksFirstAndCancelChangesNothing() {
        val vm = show()
        rule.onNodeWithText("Back up new photos & videos").performClick()
        rule.waitForIdle()
        assertTrue(has("Turn on photo backup?"))
        assertTrue("explains originals stay and the child can see it", has("Originals stay on the phone", substring = true))
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertFalse(has("Turn on photo backup?"))
        assertFalse(vm.mediaSettings.enabled)
    }

    @Test fun confirmClosesTheDialog() {
        show()
        rule.onNodeWithText("Back up new photos & videos").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Turn on").performClick()
        rule.waitForIdle()
        assertFalse(has("Turn on photo backup?"))
    }

    @Test fun enabledSettingsShowEveryChoice() {
        show(settings = MediaSettings(enabled = true, enabledAt = now - 3_600_000L, wifiOnly = true, retentionDays = 30))
        assertTrue(has("On · copying media added after", substring = true))
        assertTrue(has("Wi-Fi only"))
        assertTrue(has("A still frame only; the video file is not copied"))
        assertTrue(has("Keep previews for"))
        assertTrue(has("7 days"))
        assertTrue(has("30 days"))
        assertTrue(has("90 days"))
        assertTrue(has("Until deleted"))
        assertTrue(has("Previews already kept stay", substring = true))
    }

    @Test fun phoneStatusShowsCountsPartialAccessAndWhyItIsWaiting() {
        show(
            settings = MediaSettings(enabled = true, enabledAt = now - 1000L),
            status = status(access = MediaAccess.PARTIAL, pending = 2, uploaded = 5, failed = 1, waiting = "wifi", error = "Upload interrupted; it will retry")
        )
        assertTrue(has("Selected photos only"))
        assertTrue(has("Waiting for Wi-Fi", substring = true))
        assertTrue(has("share only selected photos", substring = true))
        assertTrue(has("Last problem: Upload interrupted; it will retry"))
        assertTrue(has("Backed up"))
        assertTrue(has("5"))
    }

    @Test fun missingPermissionIsExplainedNotHidden() {
        show(settings = MediaSettings(enabled = true), status = status(access = MediaAccess.NONE, enabled = true, waiting = "permission"))
        assertTrue(has("No photo access"))
        assertTrue(has("Photo access is off on Aarav's phone", substring = true))
        assertTrue("empty state points at the permission", has("allow photo access", substring = true))
    }

    @Test fun settingNotYetPickedUpByThePhoneIsSaidSo() {
        show(settings = MediaSettings(enabled = true), status = status(enabled = false, waiting = "disabled"))
        assertTrue(has("has not picked up the new setting yet", substring = true))
    }

    @Test fun noReportYetFromAnOldChildAppIsStated() {
        show(settings = MediaSettings(enabled = true), status = null)
        assertTrue(has("No report yet"))
        assertTrue(has("has not reported media status yet", substring = true))
        assertTrue(has("Last checked: never"))
    }

    @Test fun galleryShowsTilesVideoBadgeAndTheHonestCount() {
        show(
            items = listOf(item(1), item(2), item(3, MediaKind.VIDEO, durationMs = 42_000L), item(4)),
            status = status(uploaded = 4)
        )
        assertTrue(hasDescription("Photo from", substring = true))
        assertTrue(hasDescription("Video from", substring = true))
        assertTrue("video length badge", has("0:42"))
        assertTrue(has("Previews loaded: 4", substring = true))
        assertFalse(has("Photo backup is off"))
    }

    @Test fun filterNarrowsTheGrid() {
        show(items = listOf(item(1), item(2, MediaKind.VIDEO, durationMs = 5_000L)))
        rule.onAllNodesWithText("Videos")[0].performClick()
        rule.waitForIdle()
        assertTrue(hasDescription("Video from", substring = true))
        assertFalse(hasDescription("Photo from", substring = true))
        rule.onAllNodesWithText("Photos")[0].performClick()
        rule.waitForIdle()
        assertTrue(hasDescription("Photo from", substring = true))
        assertFalse(hasDescription("Video from", substring = true))
    }

    @Test fun filterWithNoMatchHasAWayBack() {
        show(items = listOf(item(1)))
        rule.onAllNodesWithText("Videos")[0].performClick()
        rule.waitForIdle()
        assertTrue(has("Nothing here"))
        rule.onNodeWithText("Show all").performClick()
        rule.waitForIdle()
        assertTrue(hasDescription("Photo from", substring = true))
    }

    @Test fun moreAvailableIsSaidAndOffered() {
        show(items = listOf(item(1), item(2)), hasMore = true)
        assertTrue(has("more available", substring = true))
        assertTrue(has("Load older previews"))
    }

    @Test fun failedFilesAreListedWithTheirReason() {
        show(items = listOf(item(1), item(2, status = MediaStatus.FAILED, reason = MediaFailure.TOO_LARGE)))
        assertTrue(has("Couldn't be backed up (1)"))
        assertFalse("reason is tucked away until asked for", has(MediaFailure.TOO_LARGE, substring = true))
        rule.onNodeWithText("Show").performClick()
        rule.waitForIdle()
        assertTrue(has(MediaFailure.TOO_LARGE, substring = true))
        assertTrue(has("IMG_2.jpg"))
        assertFalse("a failed file is not shown as a backed-up tile", has("Previews loaded: 2", substring = true))
    }

    @Test fun openingATileShowsItsDetailsAndOffersSaveAndDelete() {
        show(items = listOf(item(1)))
        rule.onAllNodesWithContentDescription("Photo from", substring = true)[0].performClick()
        rule.waitForIdle()
        assertTrue(has("Preview copy (", substring = true))
        assertTrue(has("stays on Aarav's phone", substring = true))
        assertTrue(has("4000×3000", substring = true))
        assertTrue("no session in the test, so the full preview is honestly unavailable", has("The full preview couldn't be loaded", substring = true))
        assertTrue(has("Save preview"))
        assertTrue(has("Delete"))
        assertTrue(has("Close"))
    }

    @Test fun videoViewerSaysItIsOnlyAStillFrame() {
        show(items = listOf(item(1, MediaKind.VIDEO, durationMs = 65_000L)))
        rule.onAllNodesWithContentDescription("Video from", substring = true)[0].performClick()
        rule.waitForIdle()
        assertTrue(has("This is one still frame from a 1:05 video. The video itself is not backed up.", substring = true))
    }

    @Test fun deleteAsksForConfirmationAndCancelKeepsTheItem() {
        show(items = listOf(item(1)))
        rule.onAllNodesWithContentDescription("Photo from", substring = true)[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertTrue(has("Delete this preview?"))
        assertTrue(has("The original stays on Aarav's phone", substring = true))
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertFalse(has("Delete this preview?"))
        assertTrue("item still there", has("Save preview"))
    }

    @Test fun loadErrorOffersRetry() {
        show(error = "Couldn't load photos: No internet connection.")
        assertTrue(has("Couldn't load photos", substring = true))
        assertTrue(has("Try again"))
    }

    @Test fun noPhoneShowsPairingPrompt() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(emptyList(), FamilyPolicy())
        rule.setContent { GuardianTheme(ThemeMode.Dark) { MediaScreen(vm, PaddingValues(0.dp)) } }
        rule.waitForIdle()
        assertTrue(has("No phone connected"))
    }

    @Test fun tileLabelUsesTheTimeTheFileWasTaken() {
        val photo = item(1)
        show(items = listOf(photo))
        assertTrue(hasDescription("Photo from " + Formats.timeOfDay(photo.createdMs)))
        assertEquals(1, rule.onAllNodesWithContentDescription("Photo from", substring = true).fetchSemanticsNodes().size)
    }
}
