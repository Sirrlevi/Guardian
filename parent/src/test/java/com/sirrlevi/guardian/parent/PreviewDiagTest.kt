package com.sirrlevi.guardian.parent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.ScreenPreviewScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** TEMPORARY: finds out why the Screen preview tests never reach idle. Removed once the cause is known. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class PreviewDiagTest {
    @get:Rule val rule = createComposeRule()

    private fun vm(): ParentViewModel {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(emptyList(), FamilyPolicy())
        vm.preview.io = EmptyCoroutineContext
        return vm
    }

    /** A: the screen with no phone, after the model was given fixed data (what the real tests do). */
    @Test fun a_screenAfterModelWrites() {
        val vm = vm()
        vm.preview.showForTest(null, now = 1_800_000_000_000L)
        rule.setContent { GuardianTheme(ThemeMode.Light) { ScreenPreviewScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
    }

    /** B: the same screen, model untouched. */
    @Test fun b_screenWithoutModelWrites() {
        val vm = vm()
        rule.setContent { GuardianTheme(ThemeMode.Light) { ScreenPreviewScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.waitForIdle()
    }

    /** C: model written, screen NOT composed. */
    @Test fun c_modelWritesOnly() {
        val vm = vm()
        vm.preview.showForTest(null, now = 1_800_000_000_000L)
        rule.setContent { GuardianTheme(ThemeMode.Light) { Text("hello") } }
        rule.waitForIdle()
    }

    /** D: model written, then screen composed with the clock stopped. */
    @Test fun d_screenWithManualClock() {
        val vm = vm()
        vm.preview.showForTest(null, now = 1_800_000_000_000L)
        rule.mainClock.autoAdvance = false
        rule.setContent { GuardianTheme(ThemeMode.Light) { ScreenPreviewScreen(vm, PaddingValues(0.dp), watching = false) } }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
    }

    /** E: nothing but the theme and a Text, no model at all. */
    @Test fun e_plainText() {
        rule.setContent { GuardianTheme(ThemeMode.Light) { Text("hello") } }
        rule.waitForIdle()
    }
}
