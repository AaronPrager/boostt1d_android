package com.boostt1d.android.data

import java.util.Calendar
import java.util.TimeZone

/** Today, up to this moment, measured against the same hours on the days behind it. */
data class TodaySoFar(
    val dayStartMillis: Long,
    /** Newest reading today. */
    val dataThroughMillis: Long,
    val readingCount: Int,
    /** Hours of today the readings actually span. */
    val hoursCovered: Double,
    val averageGlucose: Double?,
    val inRange: Double,
    val above: Double,
    val below: Double,
    /** Separate excursions below range, not readings — three dips is three events. */
    val lowEpisodes: Int,
    /** The same clock hours across the completed days behind today. */
    val baselineAverage: Double?,
    val baselineInRange: Double?,
    val baselineDays: Int,
) {
    val hasEnoughData: Boolean get() = readingCount >= MIN_READINGS && averageGlucose != null
    val hasBaseline: Boolean get() = baselineAverage != null && baselineDays >= 2

    val averageDelta: Double?
        get() {
            val today = averageGlucose ?: return null
            val base = baselineAverage ?: return null
            return today - base
        }

    val inRangeDelta: Double?
        get() = baselineInRange?.let { inRange - it }

    companion object {
        /** Below this there is not enough of today to say anything about it. */
        const val MIN_READINGS = 6

        val empty = TodaySoFar(
            dayStartMillis = 0, dataThroughMillis = 0, readingCount = 0, hoursCovered = 0.0,
            averageGlucose = null, inRange = 0.0, above = 0.0, below = 0.0, lowEpisodes = 0,
            baselineAverage = null, baselineInRange = null, baselineDays = 0,
        )
    }
}

/**
 * Shows today without letting it distort the week.
 *
 * Today is excluded from pattern frequencies on purpose — a partial day cannot be a fair
 * denominator. But excluding it silently means a low you had this morning is nowhere on
 * the page, which is its own kind of wrong. So today gets its own strip, kept apart from
 * the counts below it.
 *
 * The comparison is against **the same clock hours** on the completed days, never against
 * whole days. At 09:00 today is nearly all overnight; measuring that against full days
 * would make every morning look calm and every evening look wild, purely from the shape of
 * the day rather than from anything that happened.
 */
object TodaySoFarBuilder {

    private const val HOUR_MILLIS = 60L * 60 * 1000
    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    /** A single dip lasting forty minutes is one low, not the eight readings it spans. */
    private const val LOW_EPISODE_SEPARATION_MILLIS = 15L * 60 * 1000

    fun build(
        entries: List<NightscoutGlucoseEntry>,
        lowGlucose: Double,
        highGlucose: Double,
        periodDays: Int = 7,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): TodaySoFar {
        val dayStart = startOfDay(nowMillis, timeZone)
        val today = entries
            .filter { it.recordedAtMillis in dayStart..nowMillis }
            .sortedBy { it.recordedAtMillis }

        val first = today.firstOrNull() ?: return TodaySoFar.empty
        val last = today.last()

        val stats = share(today.map { it.sgv.toDouble() }, lowGlucose, highGlucose)

        // The same slice of the clock on each completed day, so morning meets morning.
        val elapsed = last.recordedAtMillis - dayStart
        val windowEnd = dayStart
        val windowStart = windowEnd - maxOf(periodDays, 1) * DAY_MILLIS

        val baselineValues = mutableListOf<Double>()
        var baselineDays = 0
        var cursor = windowStart
        while (cursor < windowEnd) {
            val sliceEnd = cursor + elapsed
            val slice = entries.filter { it.recordedAtMillis in cursor..sliceEnd }
            if (slice.size >= TodaySoFar.MIN_READINGS) {
                baselineValues += slice.map { it.sgv.toDouble() }
                baselineDays += 1
            }
            cursor += DAY_MILLIS
        }

        val baseline =
            if (baselineValues.isEmpty()) null else share(baselineValues, lowGlucose, highGlucose)

        return TodaySoFar(
            dayStartMillis = dayStart,
            dataThroughMillis = last.recordedAtMillis,
            readingCount = today.size,
            hoursCovered = (last.recordedAtMillis - first.recordedAtMillis) / 3_600_000.0,
            averageGlucose = stats.average,
            inRange = stats.inRange,
            above = stats.above,
            below = stats.below,
            lowEpisodes = lowEpisodes(today, lowGlucose),
            baselineAverage = baseline?.average,
            baselineInRange = baseline?.inRange,
            baselineDays = baselineDays,
        )
    }

    private data class Share(
        val average: Double,
        val inRange: Double,
        val above: Double,
        val below: Double,
    )

    private fun share(values: List<Double>, low: Double, high: Double): Share {
        val total = values.size.toDouble()
        if (total == 0.0) return Share(0.0, 0.0, 0.0, 0.0)
        val below = values.count { it < low }
        val above = values.count { it > high }
        return Share(
            average = values.sum() / total,
            inRange = (values.size - below - above) / total * 100,
            above = above / total * 100,
            below = below / total * 100,
        )
    }

    /**
     * Excursions, not readings. Counting a forty-minute dip's eight readings as eight lows
     * would make a quiet day look alarming.
     */
    private fun lowEpisodes(entries: List<NightscoutGlucoseEntry>, lowGlucose: Double): Int {
        var episodes = 0
        var lastLow: Long? = null
        for (entry in entries) {
            if (entry.sgv >= lowGlucose) continue
            val continuesPrevious =
                lastLow?.let { entry.recordedAtMillis - it <= LOW_EPISODE_SEPARATION_MILLIS } ?: false
            if (!continuesPrevious) episodes += 1
            lastLow = entry.recordedAtMillis
        }
        return episodes
    }

    fun startOfDay(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
