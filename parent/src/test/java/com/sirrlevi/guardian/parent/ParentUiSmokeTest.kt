package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sirrlevi.guardian.core.AppRule
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.Schedule
import com.sirrlevi.guardian.parent.ui.BrandIntro
import com.sirrlevi.guardian.parent.ui.Formats
import com.sirrlevi.guardian.parent.ui.GuardianLogo
import com.sirrlevi.guardian.parent.ui.GuardianApp
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import com.sirrlevi.guardian.parent.ui.minusLabel
import com.sirrlevi.guardian.parent.ui.plusLabel
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders every Parent screen with realistic sample data and walks the tabs, so a crash while composing
 * (bad constraints, missing data, wrong casts) fails the build instead of the user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ParentUiSmokeTest {
    @get:Rule val rule = createComposeRule()

    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"

    private fun device(now: Long): ChildDevice {
        val apps = listOf(
            AppInfo(yt, "YouTube", "video", false, now - 900_000_000L, now - 1_000_000L, "19.1"),
            AppInfo(ig, "Instagram", "social", false, now - 800_000_000L, now - 2_000_000L, "300.0"),
            AppInfo("com.android.chrome", "Chrome", "browser", false, now - 700_000_000L, now, "120"),
            AppInfo("com.duolingo", "Duolingo", "education", false, now - 600_000_000L, now, "5"),
            AppInfo("com.mojang.minecraftpe", "Minecraft", "games", false, now - 500_000_000L, now, "1.20"),
            AppInfo("com.android.settings", "Settings", "other", true, 0L, 0L, "14")
        )
        val today = Formats.lastDays(1, now)[0]
        val days = Formats.lastDays(10, now)
        fun at(hoursAgo: Int, minutes: Int) = now - hoursAgo * 3_600_000L to now - hoursAgo * 3_600_000L + minutes * 60_000L
        val sessions = listOf(
            at(1, 20).let { SessionRow(yt, "YouTube", it.first, it.second) },
            at(2, 12).let { SessionRow(ig, "Instagram", it.first, it.second) },
            at(3, 30).let { SessionRow("com.duolingo", "Duolingo", it.first, it.second) },
            at(5, 45).let { SessionRow("com.mojang.minecraftpe", "Minecraft", it.first, it.second) }
        )
        return ChildDevice(
            id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now,
            apps = apps,
            info = DeviceInfo(now, "Galaxy A15", "Samsung", "14", 63, false, 128_000, 52_000, 6_000, 2_100, "wifi", true, 600, true, "1.0.36",
                "overlay", true, true, true, false, false, false, false),
            live = LiveState(yt, "YouTube", now - 300_000L, 1800, 5400, false, "", true, now),
            ack = AckState(1000L, now, "overlay", true, emptyList()),
            todaySec = mapOf(yt to 3000, ig to 1500, "com.duolingo" to 900, "com.mojang.minecraftpe" to 2700),
            hourlyToday = List(24) { if (it in 8..20) (it * 137) % 1800 else 0 },
            sessions = mapOf(today to sessions),
            daily = days.mapIndexed { i, key ->
                DayTotals(key, 4000 + i * 700, mapOf(yt to 1500 + i * 100, ig to 1000, "com.duolingo" to 900, "com.mojang.minecraftpe" to 600 + i * 50))
            },
            visits = listOf(
                WebVisit("https://www.wikipedia.org/wiki/Moon", "Moon – Wikipedia", now - 3_600_000L, 240_000L, false),
                WebVisit("https://bad.example.com/x", "Blocked page", now - 7_200_000L, 1_000L, true)
            ),
            requests = listOf(MoreTimeRequest("r1", yt, "YouTube", "DAILY_LIMIT", now - 60_000L, "pending")),
            hasMonitor = true
        )
    }

    private fun policy() = FamilyPolicy(
        blockedDomains = listOf("bad.example.com"),
        blockedUrls = emptyList(),
        appRules = mapOf(yt to AppRule(dailyLimitSec = 3600), ig to AppRule(blocked = true)),
        schedules = listOf(Schedule("s1", "School hours", listOf(2, 3, 4, 5, 6), 8 * 60, 15 * 60, listOf("com.duolingo"))),
        updatedAt = 1000L
    )

    private fun walk(mode: ThemeMode) {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(device(System.currentTimeMillis())), policy())
        var requested by mutableStateOf<Tab?>(null)
        rule.setContent { GuardianTheme(mode) { GuardianApp(vm, requested, { requested = null }, {}) } }
        rule.waitForIdle()
        for (tab in Tab.values()) {
            rule.runOnIdle { requested = tab }
            rule.waitForIdle()
        }
        // Apps tab -> open the per-app control sheet
        rule.runOnIdle { requested = Tab.Apps }
        rule.waitForIdle()
        rule.onAllNodesWithText("YouTube")[0].performScrollTo().performClick()
        rule.waitForIdle()
        try {
            rule.onNodeWithText("Block this app").assertExists()
        } catch (e: AssertionError) {
            val roots = rule.onAllNodes(isRoot())
            val dump = (0 until roots.fetchSemanticsNodes().size).joinToString("\n=====ROOT=====\n") { roots[it].printToString(maxDepth = 12) }
            throw AssertionError("Sheet did not open. " + e.message + "\n" + dump, e)
        }
        assertTrue(rule.onAllNodesWithText("Fine-tune (up to 12 hours)").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Adjust today's time").fetchSemanticsNodes().isNotEmpty())
        for (m in listOf(10, 30, 60)) {
            assertTrue(rule.onAllNodesWithText(minusLabel(m)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText(plusLabel(m)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun adjustButtonsAddAndTakeAwayTimeForTodayOnly() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(device(System.currentTimeMillis())), policy())
        var requested by mutableStateOf<Tab?>(null)
        rule.setContent { GuardianTheme(ThemeMode.Dark) { GuardianApp(vm, requested, { requested = null }, {}) } }
        rule.waitForIdle()
        rule.runOnIdle { requested = Tab.Apps }
        rule.waitForIdle()
        rule.onAllNodesWithText("YouTube")[0].performScrollTo().performClick()
        rule.waitForIdle()
        val clock = DayClock.of(System.currentTimeMillis())
        fun extra() = vm.ruleFor(yt).extraFor(clock.dayKey)
        fun limit() = vm.ruleFor(yt).limitFor(clock.dayOfWeek, clock.dayKey)
        assertEquals(0, extra())
        assertEquals(3600, limit())

        rule.onNodeWithText(plusLabel(30)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(30 * 60, extra())
        assertEquals(90 * 60, limit())
        assertTrue(rule.onAllNodesWithText("+30m").fetchSemanticsNodes().isNotEmpty())

        rule.onNodeWithText(plusLabel(10)).performScrollTo().performClick()
        rule.onNodeWithText(minusLabel(60)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(-20 * 60, extra())
        assertEquals(40 * 60, limit())
        assertEquals(3600, vm.ruleFor(yt).dailyLimitSec) // the normal limit never moves

        rule.onNodeWithText("Reset to normal limit (1h 00m)").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, extra())
        assertEquals(3600, limit())
        assertTrue(rule.onAllNodesWithText("Reset to normal limit (1h 00m)").fetchSemanticsNodes().isEmpty())
    }

    @Test fun savingANewLimitReplacesTheOldOneAndQuickTapsAllCount() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(listOf(device(System.currentTimeMillis())), policy())
        val clock = DayClock.of(System.currentTimeMillis())
        vm.saveRule(yt, AppRule(dailyLimitSec = 2 * 3600))
        vm.saveRule(yt, vm.ruleFor(yt).copy(dailyLimitSec = 3 * 3600))
        assertEquals(3 * 3600, vm.ruleFor(yt).limitFor(clock.dayOfWeek, clock.dayKey)) // 3h, not 2h + 3h
        repeat(3) { vm.adjustToday(yt, 10 * 60) }
        assertEquals(30 * 60, vm.ruleFor(yt).extraFor(clock.dayKey)) // three fast taps, three times +10
        assertEquals(3 * 3600 + 30 * 60, vm.ruleFor(yt).limitFor(clock.dayOfWeek, clock.dayKey))
        vm.adjustToday(yt, -10 * 3600) // cannot go below zero
        assertEquals(0, vm.ruleFor(yt).limitFor(clock.dayOfWeek, clock.dayKey))
        vm.resetToday(yt)
        assertEquals(3 * 3600, vm.ruleFor(yt).limitFor(clock.dayOfWeek, clock.dayKey))
    }

    @Test fun everyScreenRendersInDarkTheme() = walk(ThemeMode.Dark)

    @Test fun everyScreenRendersInLightTheme() = walk(ThemeMode.Light)

    @Test fun signedOutShowsSignInForm() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        rule.setContent { GuardianTheme(ThemeMode.Light) { GuardianApp(vm, null, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Guardian").assertExists()
    }

    @Test fun emptyFamilyShowsPairingPrompts() {
        val vm = ParentViewModel(ApplicationProvider.getApplicationContext())
        vm.showForTest(emptyList(), FamilyPolicy())
        var requested by mutableStateOf<Tab?>(null)
        rule.setContent { GuardianTheme(ThemeMode.Dark) { GuardianApp(vm, requested, { requested = null }, {}) } }
        rule.waitForIdle()
        for (tab in Tab.values()) {
            rule.runOnIdle { requested = tab }
            rule.waitForIdle()
        }
    }

    @Test
    fun brandIntroShowsNameAndSkipsOnTap() {
        var finished = false
        rule.setContent { GuardianTheme(ThemeMode.Dark) { BrandIntro(onFinished = { finished = true }, durationMillis = 600_000L) } }
        rule.onNodeWithText("Guardian").assertExists()
        rule.onNodeWithText("PARENT").assertExists()
        assertTrue("intro must not end by itself before its timer", !finished)
        rule.onNodeWithText("Guardian").performClick()
        rule.waitForIdle()
        assertTrue("tapping the intro skips it", finished)
    }

    @Test
    fun animatedLogoComposesInDarkAndLight() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                GuardianTheme(ThemeMode.Dark) { GuardianLogo(96.dp, Modifier.size(96.dp)) }
                GuardianTheme(ThemeMode.Light) { GuardianLogo(96.dp, Modifier.size(96.dp)) }
            }
        }
        rule.waitForIdle()
    }
}
