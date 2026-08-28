package com.boostt1d.android.ui

import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Formatting shared by every screen, so a dose reads the same wherever it appears. */
object Fmt {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val dayTimeFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    private val dayShortTimeFormat = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    fun time(millis: Long): String = timeFormat.format(Date(millis))

    /**
     * A chart axis label that stays useful across a day boundary.
     *
     * A 24-hour window starts and ends at the same clock time, so labelling both ends
     * "17:40" reads as a zero-width window. The weekday is added only when the window
     * actually crosses midnight.
     */
    fun axisLabel(millis: Long, crossesDay: Boolean): String =
        if (crossesDay) dayShortTimeFormat.format(Date(millis)) else timeFormat.format(Date(millis))
    fun day(millis: Long): String = dayFormat.format(Date(millis))
    fun dayTime(millis: Long): String = dayTimeFormat.format(Date(millis))

    fun glucose(mgdl: Double, unit: BGUnit): String = GlucoseDisplay.format(mgdl, unit)

    /** Insulin to two decimals, trimmed — "2.5 U", not "2.50 U". */
    fun units(value: Double): String {
        val rounded = (value * 100).roundToInt() / 100.0
        return if (rounded == rounded.toLong().toDouble()) {
            "${rounded.toLong()}"
        } else {
            String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
        }
    }

    fun carbs(value: Double): String = "${value.roundToInt()}"

    fun percent(value: Double): String = "${value.roundToInt()}%"

    fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** A signed delta, so a comparison reads as a direction and not just a number. */
    fun signed(value: Double, unit: BGUnit): String {
        val shown = GlucoseDisplay.fromMgdL(abs(value), unit)
        val magnitude = GlucoseDisplay.formatInUserUnit(shown, unit)
        return if (value >= 0) "+$magnitude" else "−$magnitude"
    }

    /** How long ago, in the coarsest useful unit. */
    fun ago(millis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - millis) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 min ago"
            minutes < 60 -> "$minutes min ago"
            minutes < 120 -> "1 hr ago"
            minutes < 60 * 24 -> "${minutes / 60} hr ago"
            minutes < 60 * 48 -> "yesterday"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }
}

/**
 * The arrows Nightscout and Dexcom use for trend, spelled out.
 *
 * A manual reading has no trend — there is no previous sample from the same sensor to
 * derive one from — so this returns null rather than inventing "Flat".
 */
object TrendArrow {
    fun symbol(direction: String?): String? = when (direction) {
        "DoubleUp" -> "⇈"
        "SingleUp" -> "↑"
        "FortyFiveUp" -> "↗"
        "Flat" -> "→"
        "FortyFiveDown" -> "↘"
        "SingleDown" -> "↓"
        "DoubleDown" -> "⇊"
        else -> null
    }

    fun label(direction: String?): String? = when (direction) {
        "DoubleUp" -> "Rising fast"
        "SingleUp" -> "Rising"
        "FortyFiveUp" -> "Rising slowly"
        "Flat" -> "Steady"
        "FortyFiveDown" -> "Falling slowly"
        "SingleDown" -> "Falling"
        "DoubleDown" -> "Falling fast"
        else -> null
    }
}
