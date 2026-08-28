package com.boostt1d.android.data

import java.util.Locale

/**
 * Turns a therapy profile's time-based schedules into something readable.
 *
 * Nightscout stores every schedule as start times only: a segment runs until the next
 * one begins, and the last wraps around midnight to the first. Printing those raw start
 * times ("00:00, 06:00, 22:00") leaves the reader to work out the spans and the day's
 * total in their head, which is the arithmetic this does instead.
 */
object InsulinDoseSchedule {

    const val MINUTES_PER_DAY = 24 * 60

    /** One span of the day at a single value. */
    data class Segment(
        val start: String,
        val end: String,
        val value: Double,
        /** How much of the day the segment covers. */
        val minutes: Int,
    ) {
        /** A schedule with one entry has no boundaries worth printing. */
        val rangeLabel: String
            get() = if (minutes >= MINUTES_PER_DAY) "All day" else "$start – $end"
    }

    /**
     * Segments in clock order, each carrying the span it covers.
     *
     * Entries with an unreadable time are dropped rather than defaulted to midnight,
     * which would silently invent a segment boundary.
     */
    fun segments(values: List<TimeValue>): List<Segment> {
        val parsed = values
            .mapNotNull { entry -> minutes(entry.time)?.let { it to entry.value } }
            .sortedBy { it.first }

        val first = parsed.firstOrNull() ?: return emptyList()

        return parsed.mapIndexed { index, entry ->
            // The last segment runs to where the first one starts tomorrow, so a schedule
            // always adds up to exactly one day.
            val nextStart = if (index + 1 < parsed.size) {
                parsed[index + 1].first
            } else {
                first.first + MINUTES_PER_DAY
            }
            val span = if (parsed.size == 1) MINUTES_PER_DAY else nextStart - entry.first

            Segment(
                start = timeLabel(entry.first),
                end = timeLabel(nextStart),
                value = entry.second,
                minutes = span,
            )
        }
    }

    /** Units of basal delivered across a full day, or null when there is no schedule. */
    fun totalDailyBasal(values: List<TimeValue>): Double? {
        val segments = segments(values)
        if (segments.isEmpty()) return null
        return segments.sumOf { it.value * it.minutes / 60.0 }
    }

    /** Minutes since midnight for an `HH:mm` (or `HH:mm:ss`) time, null if unreadable. */
    fun minutes(time: String): Int? {
        val parts = time.split(":")
        if (parts.size < 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..24 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    /**
     * Wraps past midnight so a schedule's last boundary reads as the next day's start
     * rather than "26:00" — except a full day, which reads as 24:00.
     */
    private fun timeLabel(minutes: Int): String {
        val normalized = if (minutes == MINUTES_PER_DAY) minutes else minutes % MINUTES_PER_DAY
        return String.format(Locale.US, "%02d:%02d", normalized / 60, normalized % 60)
    }
}
