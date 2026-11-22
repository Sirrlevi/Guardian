package com.sirrlevi.guardian.parent.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Formats {
    fun timeOfDay(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    fun dateTime(ms: Long): String = if (ms <= 0L) "never" else SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))
    fun shortDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0L) return "never"
        val s = ((now - ms) / 1000L).coerceAtLeast(0L)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s / 60).coerceAtLeast(1)} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    fun countdown(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun parseDayKey(key: String): Calendar? = try {
        Calendar.getInstance().apply {
            clear()
            set(key.substring(0, 4).toInt(), key.substring(4, 6).toInt() - 1, key.substring(6, 8).toInt())
        }
    } catch (_: Exception) { null }

    fun dayKeyOf(c: Calendar): String =
        String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    /** Last [count] day keys, oldest first, ending today. */
    fun lastDays(count: Int, now: Long = System.currentTimeMillis()): List<String> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val out = ArrayList<String>()
        repeat(count) {
            out.add(0, dayKeyOf(c))
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    fun weekdayShort(key: String): String =
        parseDayKey(key)?.let { SimpleDateFormat("EEE", Locale.getDefault()).format(it.time) } ?: key

    fun dayLabel(key: String, todayKey: String): String {
        if (key == todayKey) return "Today"
        val c = parseDayKey(key) ?: return key
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (dayKeyOf(y) == key) return "Yesterday"
        return SimpleDateFormat("EEE d", Locale.getDefault()).format(c.time)
    }

    fun longDay(key: String): String =
        parseDayKey(key)?.let { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(it.time) } ?: key

    fun hourLabel(hour: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return h12.toString() + if (hour < 12) " AM" else " PM"
    }

    fun mb(value: Long): String = if (value >= 1024) String.format(Locale.US, "%.1f GB", value / 1024.0) else "$value MB"
}
