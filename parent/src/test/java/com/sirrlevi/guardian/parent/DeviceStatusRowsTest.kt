package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlResult
import com.sirrlevi.guardian.core.ControlStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status rows must only ever show what the phone reported, with its age, and say "not reported" instead of guessing. */
class DeviceStatusRowsTest {
    private val now = 1_800_000_000_000L

    private fun info(
        ts: Long = now - 30_000L, battery: Int = 63, charging: Boolean = true, screenOn: Boolean = true, uptimeMin: Long = 600L,
        usage: Boolean = true, overlay: Boolean = true, notifications: Boolean = true, admin: Boolean = true, batteryOpt: Boolean = true, owner: Boolean = false,
        monitorRunning: Boolean = true, monitorKnown: Boolean = true
    ) = DeviceInfo(
        ts = ts, model = "SM-A145F", manufacturer = "Samsung", androidVersion = "14", battery = battery, charging = charging,
        storageTotalMb = 0L, storageFreeMb = 0L, ramTotalMb = 0L, ramFreeMb = 0L, network = "wifi", screenOn = screenOn, uptimeMin = uptimeMin,
        autoTime = true, appVersion = "1.0", enforcement = "overlay", permUsage = usage, permOverlay = overlay, permNotifications = notifications,
        permBattery = batteryOpt, permAdmin = admin, permVpn = false, permOwner = owner, monitorRunning = monitorRunning, monitorKnown = monitorKnown
    )

    private fun device(info: DeviceInfo?, lastSeen: Long = now - 10_000L) = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = lastSeen, apps = emptyList(), info = info, live = null, ack = null,
        todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(), visits = emptyList(), requests = emptyList(), hasMonitor = info != null
    )

    private fun rows(d: ChildDevice, control: ControlStatus? = null) = DeviceStatusRows.of(d, control, now).associateBy { it.label }

    @Test fun valuesComeStraightFromTheReport() {
        val r = rows(device(info()))
        assertEquals("Online", r.getValue("Connection").value)
        assertEquals(StatusTone.GOOD, r.getValue("Connection").tone)
        assertEquals("63% · charging", r.getValue("Battery").value)
        assertEquals("Samsung SM-A145F · Android 14", r.getValue("Phone").value)
        assertTrue(r.getValue("Last sync").value.startsWith("just now"))
        assertEquals("On (reported just now)", r.getValue("Screen").value)
        assertEquals("Running", r.getValue("Guardian Child").value)
        assertEquals("All granted", r.getValue("Monitoring permissions").value)
        assertTrue(r.containsKey("Last restart"))
    }

    @Test fun anOfflinePhoneIsShownWithItsLastConnection() {
        val r = rows(device(info(ts = now - 3_600_000L), lastSeen = now - 10 * 60_000L))
        assertEquals("Offline · last connected 10 min ago", r.getValue("Connection").value)
        assertEquals(StatusTone.WARN, r.getValue("Connection").tone)
    }

    @Test fun aPhoneThatNeverReportedShowsNothingMadeUp() {
        val r = rows(device(null, lastSeen = 0L))
        assertEquals("Never connected", r.getValue("Connection").value)
        assertEquals("No report from the phone yet", r.getValue("Last sync").value)
        assertEquals("Not reported", r.getValue("Battery").value)
        assertEquals("Not reported", r.getValue("Screen").value)
        assertEquals("Not reported", r.getValue("Monitoring permissions").value)
        assertEquals("Not reported by this version of the child app", r.getValue("Guardian Child").value)
        assertFalse(r.containsKey("Last restart"))
    }

    @Test fun aLowBatteryThatIsNotChargingIsFlagged() {
        assertEquals(StatusTone.WARN, rows(device(info(battery = 9, charging = false))).getValue("Battery").tone)
        assertEquals(StatusTone.PLAIN, rows(device(info(battery = 9, charging = true))).getValue("Battery").tone)
        assertEquals("Not reported", rows(device(info(battery = -1))).getValue("Battery").value)
    }

    @Test fun theScreenRowUsesTheFreshControlReportElseTheDeviceReport() {
        fun control(screen: Boolean, locked: Boolean, age: Long = 20_000L) = ControlStatus(now - age, true, false, true, false, locked, screen, 0L, false)
        assertEquals("Off (locked)", rows(device(info()), control(screen = false, locked = true)).getValue("Screen").value)
        assertEquals("On, lock screen showing", rows(device(info()), control(screen = true, locked = true)).getValue("Screen").value)
        assertEquals("On, unlocked", rows(device(info()), control(screen = true, locked = false)).getValue("Screen").value)
        // An old control report is not trusted over the newer device report.
        assertEquals("Off (reported just now)", rows(device(info(screenOn = false)), control(screen = true, locked = false, age = 600_000L)).getValue("Screen").value)
    }

    @Test fun theServiceRowSaysWhenTheReportsStopped() {
        val stale = rows(device(info(ts = now - 10 * 60_000L)))
        assertEquals(StatusTone.WARN, stale.getValue("Guardian Child").tone)
        assertTrue(stale.getValue("Guardian Child").value.startsWith("No report for 10 min"))
        assertEquals("Not running", rows(device(info(monitorRunning = false))).getValue("Guardian Child").value)
    }

    @Test fun missingPermissionsAreNamedAndDeviceOwnerNeedsNoOverlay() {
        val r = rows(device(info(usage = false, admin = false)))
        assertEquals("Missing: Usage access, Uninstall protection", r.getValue("Monitoring permissions").value)
        assertEquals(StatusTone.WARN, r.getValue("Monitoring permissions").tone)
        assertEquals("All granted", rows(device(info(overlay = false, owner = true))).getValue("Monitoring permissions").value)
        assertEquals(listOf("Display over other apps"), DeviceStatusRows.missingPermissions(info(overlay = false)))
    }

    @Test fun historyLinesSayWhatActuallyHappened() {
        fun label(type: String, result: ControlResult) = ControlLabels.event(ControlEvent(now, type, result, "ctl-00000001"))
        assertEquals("Screen locked (confirmed by the phone)", label("lock", ControlResult.DONE))
        assertEquals("Lock refused by the phone", label("lock", ControlResult.REJECTED))
        assertEquals("Lock failed", label("lock", ControlResult.FAILED))
        assertEquals("Restart accepted, phone was restarting", label("reboot", ControlResult.RESTARTING))
        assertEquals("Phone restarted and came back online", label("reboot", ControlResult.DONE))
        assertEquals("Unsupported request (unlock) refused", label("unlock", ControlResult.REJECTED))
        assertEquals("Unsupported request (unknown) refused", label("", ControlResult.REJECTED))
    }
}
