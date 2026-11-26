package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.ControlOffer
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.LocationPermission
import com.sirrlevi.guardian.core.LocationPoint
import com.sirrlevi.guardian.core.RecoverySettings
import com.sirrlevi.guardian.core.RecoveryState
import com.sirrlevi.guardian.parent.ui.Formats

/**
 * The "Recovery status" rows. Every value is what the child's phone really reported, with its age; what it didn't report says so
 * instead of showing a guess.
 */
object RecoveryRows {
    fun of(device: ChildDevice, control: ControlStatus?, settings: RecoverySettings?, state: RecoveryState?, last: LocationPoint?, now: Long): List<StatusRow> {
        val rows = ArrayList<StatusRow>()
        val base = DeviceStatusRows.of(device, control, now).associateBy { it.label }
        listOf("Phone", "Connection", "Last sync", "Battery").forEach { label -> base[label]?.let { rows.add(it) } }
        rows.add(network(device.info, now))
        rows.add(sharing(settings))
        rows.add(permission(settings, state, now))
        rows.add(lastPosition(last, now))
        rows.add(lock(control, now))
        rows.add(ring(control, now))
        return rows
    }

    private fun network(info: DeviceInfo?, now: Long): StatusRow {
        if (info == null || info.ts <= 0L) return StatusRow("Network", "Not reported")
        val text = when (info.network) {
            "wifi" -> "Wi-Fi"
            "cellular" -> "Mobile data"
            "ethernet" -> "Ethernet"
            "offline" -> "No connection"
            "other" -> "Connected"
            else -> "Not reported"
        }
        val tone = if (info.network == "offline") StatusTone.WARN else StatusTone.PLAIN
        return StatusRow("Network", text + if (text == "Not reported") "" else " (" + Formats.relative(info.ts, now) + ")", tone)
    }

    private fun sharing(settings: RecoverySettings?): StatusRow =
        if (settings?.enabled == true) StatusRow("Location sharing", "On · about every ${settings.intervalMin} min, kept ${settings.retentionDays} day" + (if (settings.retentionDays == 1) "" else "s"), StatusTone.GOOD)
        else StatusRow("Location sharing", "Off")

    private fun permission(settings: RecoverySettings?, state: RecoveryState?, now: Long): StatusRow {
        if (state == null) return StatusRow("Location on the phone", if (settings?.enabled == true) "Waiting for the phone to report" else "Not reported", if (settings?.enabled == true) StatusTone.WARN else StatusTone.PLAIN)
        val perm = when (state.permission) {
            LocationPermission.ALWAYS -> "Allowed all the time"
            LocationPermission.WHILE_OPEN -> "Allowed only while the app is open"
            LocationPermission.NONE -> "Not allowed"
        }
        val services = if (state.servicesOn) "" else " · switched off in the phone's settings"
        val wanted = settings?.enabled == true
        val tone = when {
            !wanted -> StatusTone.PLAIN
            state.permission == LocationPermission.ALWAYS && state.servicesOn -> StatusTone.GOOD
            else -> StatusTone.WARN
        }
        return StatusRow("Location on the phone", perm + services + " (" + Formats.relative(state.ts, now) + ")", tone)
    }

    private fun lastPosition(last: LocationPoint?, now: Long): StatusRow =
        if (last == null) StatusRow("Last position", "None yet")
        else StatusRow("Last position", Formats.relative(last.ts, now) + " (" + Formats.dateTime(last.ts) + ")", if (now - last.ts > com.sirrlevi.guardian.core.RecoveryPolicy.OLD_FIX_MS) StatusTone.WARN else StatusTone.PLAIN)

    private fun lock(control: ControlStatus?, now: Long): StatusRow {
        val offer = ControlOffer.lock(control)
        return when {
            control == null -> StatusRow("Remote lock", "Not reported")
            offer.available -> StatusRow("Remote lock", "Ready" + if (ControlOffer.isStale(control, now)) " (as of " + Formats.relative(control.ts, now) + ")" else "", StatusTone.GOOD)
            else -> StatusRow("Remote lock", "Needs uninstall protection on the phone", StatusTone.WARN)
        }
    }

    private fun ring(control: ControlStatus?, now: Long): StatusRow = when {
        control == null -> StatusRow("Ring", "Not reported")
        ControlOffer.isRinging(control, now) -> StatusRow("Ring", "Ringing now", StatusTone.GOOD)
        control.canRing -> StatusRow("Ring", "Ready" + if (ControlOffer.isStale(control, now)) " (as of " + Formats.relative(control.ts, now) + ")" else "", StatusTone.GOOD)
        else -> StatusRow("Ring", "Needs notifications allowed for Guardian Child", StatusTone.WARN)
    }
}
