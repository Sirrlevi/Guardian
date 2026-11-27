package com.sirrlevi.guardian.parent

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.WallpaperMeta
import com.sirrlevi.guardian.core.WallpaperTexts
import com.sirrlevi.guardian.parent.ui.AppearanceScreen
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

/** The wallpaper screen must explain why there is no wallpaper, never draw a stand-in, and only offer actions that can really work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class AppearanceScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val meta = WallpaperMeta(now - 3_600_000L, 1080, 2400, 480_000, 4)

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

    private fun show(phase: AppearanceModel.Phase, meta: WallpaperMeta? = null, error: String? = null, notice: String? = null, withDevice: Boolean = true) {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(if (withDevice) listOf(device()) else emptyList(), FamilyPolicy())
        // Everything stays on the test's own thread: no background threads can outlive a test or leave Compose's idle check waiting.
        vm.appearance.io = EmptyCoroutineContext
        vm.appearanceBackendForTest = FakeAppearanceBackend()
        vm.appearance.showForTest(phase, meta, null, error, notice)
        rule.setContent { GuardianTheme(ThemeMode.Light) { AppearanceScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
    }

    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun withoutAPhoneItSaysSo() {
        show(AppearanceModel.Phase.LOADING, withDevice = false)
        assertTrue(has("No phone connected"))
        assertFalse(has("Wallpaper"))
    }

    @Test fun theScreenAlwaysSaysTheChildSharesItNotGuardianTakingIt() {
        show(AppearanceModel.Phase.NOT_SHARED)
        assertTrue(has("Shared by your child, never taken"))
        assertTrue(has("Android doesn't let apps read a phone's wallpaper", substring = true))
    }

    @Test fun withNothingSharedItExplainsWhyAndHowAndOffersNoPicture() {
        show(AppearanceModel.Phase.NOT_SHARED)
        assertTrue(has(WallpaperTexts.notShared("Aarav's phone")))
        assertFalse("no stand-in picture or actions", has("Open and zoom"))
        assertFalse(has("Save"))
        assertFalse(has("Delete picture"))
        assertFalse(has("Last updated", substring = true))
        assertTrue("the parent can still look again", has("Check again"))
    }

    @Test fun whileLookingItSaysSoAndOffersNothingElse() {
        show(AppearanceModel.Phase.LOADING)
        assertTrue(has(WallpaperTexts.LOADING))
        assertFalse(has("Open and zoom"))
        assertFalse(has("Delete picture"))
    }

    @Test fun whenTheServerCantBeReachedItSaysSoInsteadOfClaimingNothingWasShared() {
        show(AppearanceModel.Phase.FAILED, error = "No internet connection.")
        assertTrue(has(WallpaperTexts.LOAD_FAILED))
        assertTrue(has("Couldn't reach the server: No internet connection.", substring = true))
        assertFalse(has(WallpaperTexts.notShared("Aarav's phone")))
    }

    @Test fun aDamagedPictureIsExplainedWithItsTimeAndCanBeDeletedButNotOpened() {
        show(AppearanceModel.Phase.DAMAGED, meta)
        assertTrue(has(WallpaperTexts.incomplete("Aarav's phone")))
        assertTrue(has("Last updated", substring = true))
        assertTrue(has("1080×2400 · 480 KB"))
        assertFalse(has("Open and zoom"))
        assertFalse(has("Save"))
        assertTrue(has("Delete picture"))
    }

    @Test fun deletingNeedsAConfirmationThatSaysNothingChangesOnThePhone() {
        show(AppearanceModel.Phase.DAMAGED, meta)
        rule.onNodeWithText("Delete picture").performClick()
        assertTrue(has("Delete the shared wallpaper?"))
        assertTrue(has("Nothing changes on Aarav's phone", substring = true))
        rule.onNodeWithText("Cancel").performClick()
        assertFalse(has("Delete the shared wallpaper?"))
    }

    @Test fun confirmingTheDeleteRemovesItFromTheServer() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(device()), FamilyPolicy())
        val fake = FakeAppearanceBackend().apply { metaNow = meta; imageNow = null }
        vm.appearance.io = EmptyCoroutineContext
        vm.appearanceBackendForTest = fake
        vm.appearance.showForTest(AppearanceModel.Phase.DAMAGED, meta, null)
        rule.setContent { GuardianTheme(ThemeMode.Light) { AppearanceScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.onNodeWithText("Delete picture").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5_000) { fake.deleted > 0 }
        assertEquals(1, fake.deleted)
        rule.waitForIdle()
        assertTrue(has(WallpaperTexts.REMOVED))
    }
}
