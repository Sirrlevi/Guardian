package com.sirrlevi.guardian.parent

import com.sirrlevi.guardian.core.ControlEvent
import com.sirrlevi.guardian.core.ControlOffer
import com.sirrlevi.guardian.core.ControlResult
import com.sirrlevi.guardian.core.ControlStatus
import com.sirrlevi.guardian.core.ControlType
import com.sirrlevi.guardian.parent.ui.Formats

enum class StatusTone { GOOD, WARN, PLAIN }

data class StatusRow(val label: String, val value: String, val tone: StatusTone = StatusTone.PLAIN)

/**
 * The "Phone status" rows. Every value comes from what the child's phone really reported (with its age); when the phone has not
 * reported something, the row says so instead of showing a guess.
 */
object DeviceStatusRows {
    /** The Guardian service on the phone writes a report about once a minute; this long without one means it isn't running or can't reach the internet. */
    const val REPORT_FRESH_MS = 150_000L

    fun of(device: ChildDevice, control: ControlStatus?, now: Long): List<StatusRow> {
        val info = device.info
        val rows = ArrayList<StatusRow>()

        val seen = device.presenceTs()
        rows.add(
            when {
                seen <= 0L -> StatusRow("Connection", "Never connected", StatusTone.WARN)
                now - seen < ChildDevice.ONLINE_WINDOW_MS -> StatusRow("Connection", "Online", StatusTone.GOOD)
                else -> StatusRow("Connection", "Offline · last connected " + Formats.relative(seen, now), StatusTone.WARN)
            }
        )

        rows.add(
            if (info == null || info.ts <= 0L) StatusRow("Last sync", "No report from the phone yet", StatusTone.WARN)
            else StatusRow("Last sync", Formats.relative(info.ts, now) + " (" + Formats.dateTime(info.ts) + ")")
        )

        rows.add(
            when {
                info == null || info.battery < 0 -> StatusRow("Battery", "Not reported")
                else -> StatusRow(
                    "Battery", info.battery.toString() + "%" + if (info.charging) " · charging" else "",
                    if (info.battery <= 15 && !info.charging) StatusTone.WARN else StatusTone.PLAIN
                )
            }
        )

        rows.add(
            if (info == null) StatusRow("Phone", listOf(device.manufacturer, device.name).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Not reported" })
            else StatusRow("Phone", (info.manufacturer + " " + info.model).trim().ifBlank { device.name } + if (info.androidVersion.isNotBlank()) " · Android " + info.androidVersion else "")
        )

        rows.add(screen(info, control, now))

        if (info != null && info.uptimeMin > 0L) {
            rows.add(StatusRow("Last restart", Formats.relative(info.ts - info.uptimeMin * 60_000L, now)))
        }

        rows.add(
            when {
                info == null || !info.monitorKnown -> StatusRow("Guardian Child", "Not reported by this version of the child app")
                now - info.ts > REPORT_FRESH_MS -> StatusRow("Guardian Child", "No report for " + Formats.relative(info.ts, now).removeSuffix(" ago") + ". It may be stopped or offline.", StatusTone.WARN)
                info.monitorRunning -> StatusRow("Guardian Child", "Running", StatusTone.GOOD)
                else -> StatusRow("Guardian Child", "Not running", StatusTone.WARN)
            }
        )

        rows.add(permissions(info))
        return rows
    }

    private fun screen(info: DeviceInfo?, control: ControlStatus?, now: Long): StatusRow {
        if (control != null && !ControlOffer.isStale(control, now)) {
            val text = when {
                !control.screenOn -> "Off (locked)"
                control.locked -> "On, lock screen showing"
                else -> "On, unlocked"
            }
            return StatusRow("Screen", text)
        }
        if (info != null && info.ts > 0L) {
            return StatusRow("Screen", (if (info.screenOn) "On" else "Off") + " (reported " + Formats.relative(info.ts, now) + ")")
        }
        return StatusRow("Screen", "Not reported")
    }

    /** Which of the permissions Guardian needs on the child's phone are missing. */
    fun missingPermissions(info: DeviceInfo): List<String> {
        val missing = ArrayList<String>()
        if (!info.permUsage) missing.add("Usage access")
        if (!info.permOverlay && !info.permOwner) missing.add("Display over other apps")
        if (!info.permNotifications) missing.add("Notifications")
        if (!info.permAdmin) missing.add("Uninstall protection")
        if (!info.permBattery) missing.add("Battery optimisation")
        return missing
    }

    private fun permissions(info: DeviceInfo?): StatusRow {
        if (info == null) return StatusRow("Monitoring permissions", "Not reported")
        val missing = missingPermissions(info)
        return if (missing.isEmpty()) StatusRow("Monitoring permissions", "All granted", StatusTone.GOOD)
        else StatusRow("Monitoring permissions", "Missing: " + missing.joinToString(", "), StatusTone.WARN)
    }
}

/** Plain-words labels for the phone's short history of requests. */
object ControlLabels {
    fun event(e: ControlEvent): String {
        val type = ControlType.fromCode(e.typeCode)
        return when (type) {
            ControlType.LOCK -> when (e.result) {
                ControlResult.DONE -> "Screen locked (confirmed by the phone)"
                ControlResult.REJECTED -> "Lock refused by the phone"
                ControlResult.FAILED -> "Lock failed"
                ControlResult.EXPIRED -> "Lock request expired before the phone saw it"
                ControlResult.RESTARTING -> "Lock request"
            }
            ControlType.REBOOT -> when (e.result) {
                ControlResult.RESTARTING -> "Restart accepted, phone was restarting"
                ControlResult.DONE -> "Phone restarted and came back online"
                ControlResult.REJECTED -> "Restart refused by the phone"
                ControlResult.FAILED -> "Restart failed"
                ControlResult.EXPIRED -> "Restart request expired before the phone saw it"
            }
            ControlType.RING -> when (e.result) {
                ControlResult.DONE -> "Phone rang (confirmed by the phone)"
                ControlResult.REJECTED -> "Ring refused by the phone"
                ControlResult.FAILED -> "Ring failed"
                ControlResult.EXPIRED -> "Ring request expired before the phone saw it"
                ControlResult.RESTARTING -> "Ring request"
            }
            ControlType.LOCATE -> when (e.result) {
                ControlResult.DONE -> "Position read (confirmed by the phone)"
                ControlResult.REJECTED -> "Find now refused by the phone"
                ControlResult.FAILED -> "Find now: no position could be read"
                ControlResult.EXPIRED -> "Find now expired before the phone saw it"
                ControlResult.RESTARTING -> "Find now request"
            }
            null -> "Unsupported request (" + e.typeCode.ifBlank { "unknown" } + ") refused"
        }
    }
}
