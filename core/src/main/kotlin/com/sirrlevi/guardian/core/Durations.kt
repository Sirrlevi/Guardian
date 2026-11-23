package com.sirrlevi.guardian.core

import java.util.Locale

object Durations {
    /** 3725 → "1h 02m", 125 → "2m 05s", 42 → "42s". */
    fun compact(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 && sec > 0 && m < 10 -> String.format(Locale.US, "%dm %02ds", m, sec)
            m > 0 -> String.format(Locale.US, "%dm", m)
            else -> String.format(Locale.US, "%ds", sec)
        }
    }

    /** 1800 → "+30m", -3600 → "−1h 00m", 0 → "0m". Uses a real minus sign so it lines up with a plus. */
    fun signed(sec: Int): String = when {
        sec > 0 -> "+" + compact(sec)
        sec < 0 -> "\u2212" + compact(-sec)
        else -> "0m"
    }

    /** 3725 → "1:02:05" (a stopwatch reading). */
    fun clock(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun split(totalSec: Int): Triple<Int, Int, Int> {
        val s = totalSec.coerceAtLeast(0)
        return Triple(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Joins typed hour / minute / second fields, clamped to one day. */
    fun join(hours: Int, minutes: Int, seconds: Int): Int =
        (hours.coerceAtLeast(0) * 3600L + minutes.coerceAtLeast(0) * 60L + seconds.coerceAtLeast(0))
            .coerceAtMost(AppRule.UNLIMITED_SEC.toLong()).toInt()

    /** Rounds to the nearest [stepSec] — used by the slider and the dial. */
    fun snap(totalSec: Int, stepSec: Int): Int =
        if (stepSec <= 0) totalSec else ((totalSec + stepSec / 2) / stepSec) * stepSec
}
