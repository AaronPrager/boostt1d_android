package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.TodaySoFarBuilder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The ambulatory glucose profile: readings collapsed onto a 24-hour clock in 10-minute
 * buckets, one sample per calendar day per bucket, summarised as median and 25th–75th
 * percentile. Shared by the on-screen chart and the PDF so the printed curve matches what
 * the patient saw. Ported from MultiDayOverlayChartView's maths and DoctorVisitPDFExporter.agpPoints.
 */
object AgpProfile {

    const val BUCKET_MINUTES = 10

    data class Point(val minuteOfDay: Int, val median: Double, val p25: Double, val p75: Double)

    /** Minutes from local midnight, floored to the bucket. */
    fun minuteBucket(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return (minutes / BUCKET_MINUTES) * BUCKET_MINUTES
    }

    /** Linear-interpolated percentile of an already sorted list, as the iOS chart computes it. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val clamped = p.coerceIn(0.0, 1.0)
        val rank = clamped * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        val weight = rank - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    /**
     * One point per bucket with data. When [allowedDayStarts] is given only readings on those
     * calendar days count, which is how the chart limits itself to the last N days.
     */
    fun points(entries: List<NightscoutGlucoseEntry>, timeZone: TimeZone = TimeZone.getDefault(), allowedDayStarts: Set<Long>? = null): List<Point> {
        // bucket → (day → value), one sample per day per bucket; a later reading in the same
        // bucket replaces the earlier one, as the dictionary assignment does on iOS.
        val grouped = sortedMapOf<Int, MutableMap<Long, Double>>()
        for (entry in entries) {
            val day = TodaySoFarBuilder.startOfDay(entry.epochMilliseconds, timeZone)
            if (allowedDayStarts != null && day !in allowedDayStarts) continue
            grouped.getOrPut(minuteBucket(entry.epochMilliseconds, timeZone)) { mutableMapOf() }[day] = entry.sgv.toDouble()
        }
        return grouped.mapNotNull { (bucket, byDay) ->
            val sorted = byDay.values.sorted()
            if (sorted.isEmpty()) null else Point(bucket, percentile(sorted, 0.50), percentile(sorted, 0.25), percentile(sorted, 0.75))
        }
    }

    /** The last [dayCount] calendar days ending today. */
    fun dayStarts(dayCount: Int, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): List<Long> {
        val capped = dayCount.coerceAtLeast(1)
        val today = TodaySoFarBuilder.startOfDay(nowMillis, timeZone)
        val calendar = Calendar.getInstance(timeZone)
        return (0 until capped).map { offset ->
            calendar.timeInMillis = today
            calendar.add(Calendar.DAY_OF_MONTH, -(capped - 1) + offset)
            calendar.timeInMillis
        }
    }

    fun distinctCalendarDays(entries: List<NightscoutGlucoseEntry>, timeZone: TimeZone = TimeZone.getDefault()): Int =
        entries.map { TodaySoFarBuilder.startOfDay(it.epochMilliseconds, timeZone) }.toSet().size
}
