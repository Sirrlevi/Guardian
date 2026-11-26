package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.LocationPermission
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Recovery status rows: real values with their age, "Not reported" when the phone said nothing, never a guess. */
class RecoveryRowsTest {
    private val now = 1_800_000_000_000L

    private fun info(network: String = "wifi") = DeviceInfo(
        ts = now - 20_000L, model = "SM-A145F", manufacturer = "Samsung", androidVersion = "14", battery = 63, charging = false,
        storageTotalMb = 0L, storageFreeMb = 0L, ramTotalMb = 0L, ramFreeMb = 0L, network = network, screenOn = true, uptimeMin = 300L,
        autoTime = true, appVersion = "1.0", enforcement = "overlay", permUsage = true, permOverlay = true, permNotifications = true,
        permBattery = true, permAdmin = true, permVpn = false, permOwner = false, monitorRunning = true, monitorKnown = true
    )

    private fun device(info: DeviceInfo? = info()) = ChildDevice(
        id = "kid1", name = "Aarav's phone", manufacturer = "Samsung", lastSeen = now - 10_000L, apps = emptyList(), info = info,
        live = null, ack = null, todaySec = emptyMap(), hourlyToday = List(24) { 0 }, sessions = emptyMap(), daily = emptyList(),
        visits = emptyList(), requests = emptyList(), hasMonitor = true
    )

    private fun rows(
        device: ChildDevice = device(), control: ControlStatus? = null, settings: RecoverySettings? = null, state: RecoveryState? = null, last: LocationPoint? = null
    ) = RecoveryRows.of(device, control, settings, state, last, now).associateBy { it.label }

    private fun control(canRing: Boolean = true, admin: Boolean = true, ringUntil: Long = 0L) =
        ControlStatus(now - 5_000L, admin, false, admin, false, false, true, now - 3_600_000L, false, canRing = canRing, ringUntil = ringUntil)

    @Test fun showsTheRealIdentityBatteryAndNetworkOfThePhone() {
        val r = rows()
        assertTrue(r.getValue("Phone").value.contains("SM-A145F"))
        assertTrue(r.getValue("Phone").value.contains("Android 14"))
        assertEquals("63%", r.getValue("Battery").value)
        assertTrue(r.getValue("Network").value.startsWith("Wi-Fi"))
        assertEquals("Online", r.getValue("Connection").value)
    }

    @Test fun networkThatIsOfflineIsAWarningAndUnknownIsNotGuessed() {
        assertEquals(StatusTone.WARN, rows(device(info("offline"))).getValue("Network").tone)
        assertEquals("No connection", rows(device(info("offline"))).getValue("Network").value.substringBefore(" ("))
        assertEquals("Not reported", rows(device(info("something"))).getValue("Network").value)
        assertEquals("Not reported", rows(device(null)).getValue("Network").value)
    }

    @Test fun locationSharingFollowsTheParentsOwnChoice() {
        assertEquals("Off", rows().getValue("Location sharing").value)
        val on = rows(settings = RecoverySettings(true, 30, 1, now)).getValue("Location sharing")
        assertEquals("On · about every 30 min, kept 1 day", on.value)
        assertEquals(StatusTone.GOOD, on.tone)
    }

    @Test fun thePermissionRowSaysWhatIsMissingOrThatItIsWaiting() {
        val on = RecoverySettings(true, 15, 7, now)
        assertEquals("Waiting for the phone to report", rows(settings = on).getValue("Location on the phone").value)
        fun perm(p: LocationPermission, services: Boolean = true) = rows(settings = on, state = RecoveryState(now - 30_000L, true, p, services, 0L)).getValue("Location on the phone")
        assertTrue(perm(LocationPermission.NONE).value.startsWith("Not allowed"))
        assertEquals(StatusTone.WARN, perm(LocationPermission.NONE).tone)
        assertTrue(perm(LocationPermission.WHILE_OPEN).value.startsWith("Allowed only while the app is open"))
        assertTrue(perm(LocationPermission.ALWAYS, services = false).value.contains("switched off in the phone's settings"))
        assertEquals(StatusTone.GOOD, perm(LocationPermission.ALWAYS).tone)
    }

    @Test fun aMissingPermissionIsNotAWarningWhenTheParentNeverAskedForLocation() {
        val r = rows(state = RecoveryState(now, false, LocationPermission.NONE, true, 0L)).getValue("Location on the phone")
        assertEquals(StatusTone.PLAIN, r.tone)
    }

    @Test fun theLastPositionShowsItsAgeAndOldOnesAreFlagged() {
        assertEquals("None yet", rows().getValue("Last position").value)
        val fresh = rows(last = LocationPoint(now - 120_000L, 28.6, 77.2, 10f, "gps")).getValue("Last position")
        assertTrue(fresh.value.startsWith("2 min ago"))
        assertEquals(StatusTone.PLAIN, fresh.tone)
        val old = rows(last = LocationPoint(now - 3 * 3_600_000L, 28.6, 77.2, 10f, "gps")).getValue("Last position")
        assertEquals(StatusTone.WARN, old.tone)
    }

    @Test fun lockAndRingReadinessComeFromTheReportNotFromAssumption() {
        assertEquals("Not reported", rows().getValue("Remote lock").value)
        assertEquals("Not reported", rows().getValue("Ring").value)
        val ready = rows(control = control())
        assertTrue(ready.getValue("Remote lock").value.startsWith("Ready"))
        assertTrue(ready.getValue("Ring").value.startsWith("Ready"))
        val blocked = rows(control = control(canRing = false, admin = false))
        assertEquals(StatusTone.WARN, blocked.getValue("Remote lock").tone)
        assertEquals("Needs notifications allowed for Guardian Child", blocked.getValue("Ring").value)
        assertEquals("Ringing now", rows(control = control(ringUntil = now + 20_000L)).getValue("Ring").value)
    }
}
