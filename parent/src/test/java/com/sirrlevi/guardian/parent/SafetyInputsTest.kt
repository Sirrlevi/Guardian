package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.SafetyEvaluator
import com.sirrlevi.guardian.core.SafetySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** What the Parent hands to the rules must say "unknown" when the phone didn't say, never "off". */
class SafetyInputsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_800_000_000_000L

    private fun report(known: Boolean = true, usage: Boolean = true, notifAccess: Boolean = true, monitorRunning: Boolean = true) = DeviceInfo(
        ts = now - 30_000L, model = "SM-A145F", manufacturer = "Samsung", androidVersion = "14", battery = 63, charging = false,
        storageTotalMb = 0L, storageFreeMb = 0L, ramTotalMb = 0L, ramFreeMb = 0L, network = "wifi", screenOn = true, uptimeMin = 300L,
        autoTime = true, appVersion = "1.0", enforcement = "overlay", permUsage = usage, permOverlay = true, permNotifications = true,
        permBattery = false, permAdmin = false, permVpn = false, permOwner = false,
        permNotifAccess = notifAccess, notifAccessKnown = known, monitorRunning = monitorRunning, monitorKnown = known
    )

    private fun device(info: DeviceInfo? = report()) = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now - 10_000L,
        apps = listOf(AppInfo("p.yt", "YouTube", "video", false, now - 90 * 86_400_000L, 0L, "1"), AppInfo("p.new", "Chatty", "social", false, now - 3_600_000L, 0L, "1")),
        info = info, live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 },
        sessions = emptyMap(),
        daily = listOf(
            DayTotals("20270110", 3600, mapOf("p.yt" to 3600)), DayTotals("20270113", 7200, mapOf("p.yt" to 5000, "p.new" to 2200)),
            DayTotals("20270114", 18_000, mapOf("p.yt" to 10_000, "p.new" to 5000, "p.x" to 3000)), DayTotals("20270112", 1000, emptyMap()), DayTotals("20270115", 600, emptyMap())
        ),
        visits = emptyList(), requests = emptyList(), hasMonitor = true
    )

    @Test fun permissionsAreTakenFromThePhonesOwnReport() {
        val p = SafetyInputs.perms(report(usage = false))
        assertEquals(false, p["usage"]); assertEquals(true, p["overlay"]); assertEquals(true, p["notifications"]); assertEquals(false, p["admin"]); assertEquals(true, p["notifaccess"])
        assertFalse("the web filter and the battery exemption are not watched", p.containsKey("vpn") || p.containsKey("battery"))
    }

    @Test fun anOldChildAppThatDoesNotReportNotificationAccessIsNotTreatedAsOff() {
        val input = SafetyInputs.build(device(report(known = false, notifAccess = false, monitorRunning = false)), emptyList(), emptyMap(), now, utc)
        assertNull(input.notificationAccess)
        assertNull(input.serviceRunning)
        assertFalse(input.perms.containsKey("notifaccess"))
        assertTrue(SafetyEvaluator.evaluate(input, SafetySettings()).none { it.title.contains("Notification access") || it.title.contains("service") })
    }

    @Test fun aKnownOffStateReachesTheRules() {
        val input = SafetyInputs.build(device(report(usage = false, notifAccess = false, monitorRunning = false)), emptyList(), emptyMap(), now, utc)
        assertEquals(false, input.usageAccess); assertEquals(false, input.notificationAccess); assertEquals(false, input.serviceRunning)
        assertEquals(now - 30_000L, input.reportedAt)
        assertEquals(3, SafetyEvaluator.evaluate(input, SafetySettings()).count { it.title.endsWith("is off") || it.title.contains("isn't running") })
    }

    @Test fun withoutAReportNothingIsClaimed() {
        val input = SafetyInputs.build(device(info = null), emptyList(), emptyMap(), now, utc)
        assertNull(input.usageAccess); assertNull(input.notificationAccess); assertNull(input.serviceRunning)
        assertEquals(0L, input.reportedAt)
        assertTrue(input.perms.isEmpty())
    }

    @Test fun onlyTheNewestDaysAreUsedAndTheBiggestAppsNamed() {
        val input = SafetyInputs.build(device(), emptyList(), emptyMap(), now, utc)
        assertEquals(listOf("20270115", "20270114", "20270113"), input.days.map { it.dayKey })
        val big = input.days.single { it.dayKey == "20270114" }
        assertEquals(listOf("YouTube" to 10_000, "Chatty" to 5000, "p.x" to 3000), big.top)
    }

    @Test fun appsAndSessionsAreCarriedOverWithTheirNames() {
        val sessions = mapOf("20270114" to listOf(SessionRow("p.yt", "", now - 1000L, now), SessionRow("p.new", "Chatty", now - 5000L, now - 3000L)))
        val input = SafetyInputs.build(device(), emptyList(), sessions, now, utc)
        assertEquals(listOf("YouTube", "Chatty"), input.apps.map { it.name })
        assertEquals(listOf("p.yt", "Chatty"), input.sessions.getValue("20270114").map { it.name })
        assertEquals(now - 10_000L, input.lastSeenMs)
        assertEquals("Aarav's phone", input.deviceName)
    }

    @Test fun aNewAppReachesTheListThroughTheRealInput() {
        val alerts = SafetyEvaluator.evaluate(SafetyInputs.build(device(), emptyList(), emptyMap(), now, utc), SafetySettings())
        assertEquals(listOf("New app: Chatty"), alerts.filter { it.title.startsWith("New app") }.map { it.title })
    }
}
