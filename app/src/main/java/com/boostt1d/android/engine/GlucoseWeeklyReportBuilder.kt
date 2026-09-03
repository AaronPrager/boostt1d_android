package com.boostt1d.android.engine

import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.TodaySoFarBuilder
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt

/** Period metrics for the "What Happened?" weekly report. */
@Serializable
data class GlucosePeriodMetrics(
    val readingCount: Int = 0,
    val averageGlucoseMgdL: Double = 0.0,
    val standardDeviationMgdL: Double = 0.0,
    val coefficientOfVariation: Double = 0.0,
    val estimatedA1C: Double = 0.0,
    val gmi: Double = 0.0,
    val timeInRangePercent: Double = 0.0,
    val timeLowPercent: Double = 0.0,
    val timeHighPercent: Double = 0.0,
    val timeVeryHighPercent: Double = 0.0,
    val lowEpisodeCount: Int = 0,
    val lowEpisodeTotalMinutes: Int = 0,
    /** Hour-of-day (0–23) with the highest share of above-range readings, if any. */
    val hardestHourStart: Int? = null,
    val hardestHourEnd: Int? = null,
    val hardestWindowAboveRangePercent: Double = 0.0,
)

@Serializable
data class WhatHappenedWeeklyReport(
    val current: GlucosePeriodMetrics = GlucosePeriodMetrics(),
    val previous: GlucosePeriodMetrics? = null,
    val lowThresholdMgdL: Double = 70.0,
    val highThresholdMgdL: Double = 180.0,
    val veryHighThresholdMgdL: Double = GlucoseCacheRules.VERY_HIGH_MGDL,
    val currentPeriodStartMillis: Long,
    val currentPeriodEndMillis: Long,
    val previousPeriodStartMillis: Long? = null,
    val previousPeriodEndMillis: Long? = null,
    val plainLanguageSummary: String = "",
) {
    val hasEnoughCurrentData: Boolean get() = current.readingCount >= 12
}

/**
 * This-week vs prior-week metrics from a pool of readings (ideally up to 14 days).
 *
 * Ported 1:1 from the iOS GlucoseWeeklyReportBuilder.
 */
object GlucoseWeeklyReportBuilder {

    /**
     * The width of one reading, added to a low episode so its last reading counts for its own
     * duration rather than zero. The typical CGM cadence, and the same value iOS uses
     * (`CGMGlucoseCache.storageBucketMinutes`), so both apps time an episode identically.
     */
    private const val STORAGE_BUCKET_MINUTES = 5

    /**
     * Days of the seven each window must actually contain readings on before the two are
     * compared. Five leaves room for ordinary sensor gaps without letting a part-week pose
     * as a week.
     */
    const val MINIMUM_COMPARISON_DAYS = 5

    fun build(
        entries: List<NightscoutGlucoseEntry>,
        lowMgdL: Double,
        highMgdL: Double,
        veryHighMgdL: Double = GlucoseCacheRules.VERY_HIGH_MGDL,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): WhatHappenedWeeklyReport {
        val calendar = Calendar.getInstance(timeZone)
        fun minusDays(millis: Long, days: Int): Long = calendar.run { timeInMillis = millis; add(Calendar.DAY_OF_MONTH, -days); timeInMillis }

        val currentEnd = nowMillis
        val currentStart = minusDays(currentEnd, 7)
        val previousEnd = currentStart
        val previousStart = minusDays(previousEnd, 7)

        val currentEntries = entries.filter { it.epochMilliseconds >= currentStart && it.epochMilliseconds <= currentEnd }
        val previousEntries = entries.filter { it.epochMilliseconds >= previousStart && it.epochMilliseconds < previousEnd }

        val current = metrics(currentEntries, lowMgdL, highMgdL, veryHighMgdL, timeZone)

        // A week-over-week comparison is only honest when *both* windows hold something
        // close to a week. The floor used to be 12 readings in the prior window — about an
        // hour of CGM — so a sliver of history eight days back was enough to render a full
        // "compared with previous week" panel beside three real days of data, and every stat
        // card picked up a "vs last week" subtitle it had no basis for.
        val hasComparableWindows = previousEntries.size >= 12 &&
            distinctDayCount(previousEntries, timeZone) >= MINIMUM_COMPARISON_DAYS &&
            distinctDayCount(currentEntries, timeZone) >= MINIMUM_COMPARISON_DAYS

        val previous = if (hasComparableWindows) metrics(previousEntries, lowMgdL, highMgdL, veryHighMgdL, timeZone) else null

        val report = WhatHappenedWeeklyReport(
            current = current,
            previous = previous,
            lowThresholdMgdL = lowMgdL,
            highThresholdMgdL = highMgdL,
            veryHighThresholdMgdL = veryHighMgdL,
            currentPeriodStartMillis = currentStart,
            currentPeriodEndMillis = currentEnd,
            previousPeriodStartMillis = if (previous == null) null else previousStart,
            previousPeriodEndMillis = if (previous == null) null else previousEnd,
        )
        return report.copy(plainLanguageSummary = plainLanguageSummary(report, timeZone))
    }

    /**
     * Calendar days the readings land on — the density-independent way to ask "is this a
     * week of data?", so a manual-entry user with a handful of readings a day is judged the
     * same as a CGM user with 288.
     */
    private fun distinctDayCount(entries: List<NightscoutGlucoseEntry>, timeZone: TimeZone): Int =
        entries.map { TodaySoFarBuilder.startOfDay(it.epochMilliseconds, timeZone) }.toSet().size

    fun metrics(
        entries: List<NightscoutGlucoseEntry>,
        lowMgdL: Double,
        highMgdL: Double,
        veryHighMgdL: Double,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): GlucosePeriodMetrics {
        if (entries.isEmpty()) return GlucosePeriodMetrics()

        val values = entries.map { it.sgv.toDouble() }
        val count = values.size.toDouble()
        val average = values.sum() / count
        val variance = values.sumOf { (it - average) * (it - average) } / count
        val stdDev = sqrt(variance)
        val cv = if (average > 0) (stdDev / average) * 100 else 0.0
        val estimatedA1C = (average + 46.7) / 28.7
        val gmi = 3.31 + (0.02392 * average)

        val inRange = values.count { it >= lowMgdL && it <= highMgdL }
        val low = values.count { it < lowMgdL }
        val veryHigh = values.count { it > veryHighMgdL }
        val high = values.count { it > highMgdL && it <= veryHighMgdL }

        val episodes = lowEpisodes(entries, lowMgdL)
        val hardest = hardestWindow(entries, highMgdL, timeZone)

        return GlucosePeriodMetrics(
            readingCount = values.size,
            averageGlucoseMgdL = average,
            standardDeviationMgdL = stdDev,
            coefficientOfVariation = cv,
            estimatedA1C = estimatedA1C,
            gmi = gmi,
            timeInRangePercent = inRange / count * 100,
            timeLowPercent = low / count * 100,
            timeHighPercent = high / count * 100,
            timeVeryHighPercent = veryHigh / count * 100,
            lowEpisodeCount = episodes.count,
            lowEpisodeTotalMinutes = episodes.totalMinutes,
            hardestHourStart = hardest?.startHour,
            hardestHourEnd = hardest?.endHour,
            hardestWindowAboveRangePercent = hardest?.abovePercent ?: 0.0,
        )
    }

    // MARK: - Low episodes

    private class LowEpisodeStats(var count: Int, var totalMinutes: Int)

    /** Contiguous below-threshold stretches lasting at least ~15 minutes. */
    private fun lowEpisodes(entries: List<NightscoutGlucoseEntry>, lowMgdL: Double): LowEpisodeStats {
        val sorted = entries.sortedBy { it.epochMilliseconds }
        val stats = LowEpisodeStats(0, 0)
        if (sorted.isEmpty()) return stats

        var episodeStart: Long? = null
        var lastLow: Long? = null

        val minEpisodeMinutes = 15
        val gapBreakMinutes = 25

        fun close(start: Long, last: Long) {
            val duration = ((last - start) / 60_000L).toInt() + STORAGE_BUCKET_MINUTES
            if (duration >= minEpisodeMinutes) {
                stats.count += 1
                stats.totalMinutes += duration
            }
        }

        for (entry in sorted) {
            val at = entry.epochMilliseconds
            val isLow = entry.sgv.toDouble() < lowMgdL

            if (isLow) {
                val start = episodeStart
                val last = lastLow
                if (start != null && last != null) {
                    val gapMinutes = (at - last) / 60_000.0
                    if (gapMinutes > gapBreakMinutes) {
                        close(start, last)
                        episodeStart = at
                    }
                } else if (episodeStart == null) {
                    episodeStart = at
                }
                lastLow = at
            } else {
                val start = episodeStart
                val last = lastLow
                if (start != null && last != null) {
                    close(start, last)
                    episodeStart = null
                    lastLow = null
                }
            }
        }

        val start = episodeStart
        val last = lastLow
        if (start != null && last != null) close(start, last)

        return stats
    }

    // MARK: - Hardest window

    private class HardWindow(val startHour: Int, val endHour: Int, val abovePercent: Double)

    /** Finds the 3-hour clock window with the highest % of readings above range. */
    private fun hardestWindow(entries: List<NightscoutGlucoseEntry>, highMgdL: Double, timeZone: TimeZone): HardWindow? {
        if (entries.size < 12) return null
        val calendar = Calendar.getInstance(timeZone)
        val hours = entries.map { calendar.timeInMillis = it.epochMilliseconds; calendar.get(Calendar.HOUR_OF_DAY) }

        var best: HardWindow? = null
        for (startHour in 0 until 24) {
            val window = setOf(startHour, (startHour + 1) % 24, (startHour + 2) % 24)
            val indices = hours.indices.filter { hours[it] in window }
            if (indices.size < 4) continue
            val above = indices.count { entries[it].sgv.toDouble() > highMgdL }
            val percent = above.toDouble() / indices.size * 100
            if (percent >= 20 && percent > (best?.abovePercent ?: -1.0)) {
                best = HardWindow(startHour, (startHour + 3) % 24, percent)
            }
        }
        return best
    }

    // MARK: - Plain language

    fun plainLanguageSummary(report: WhatHappenedWeeklyReport, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (!report.hasEnoughCurrentData) {
            return "Not enough glucose readings yet for a weekly summary. Keep syncing for a few days and check back."
        }

        val current = report.current
        val sentences = mutableListOf<String>()
        val previous = report.previous

        if (previous != null) {
            val tirDelta = current.timeInRangePercent - previous.timeInRangePercent
            val cvDelta = current.coefficientOfVariation - previous.coefficientOfVariation

            sentences += when {
                abs(tirDelta) < 2 && abs(cvDelta) < 2 -> "Glucose patterns were similar to last week."
                tirDelta >= 2 && cvDelta <= 0 -> "Glucose was more stable this week."
                tirDelta <= -2 || cvDelta >= 3 -> "Glucose was more variable this week."
                tirDelta >= 2 -> "Time in range improved this week."
                else -> "Time in range was a bit lower than last week."
            }

            sentences += String.format(
                Locale.US,
                "Time in range %s from %.0f%% to %.0f%%.",
                if (tirDelta >= 0) "increased" else "decreased",
                previous.timeInRangePercent,
                current.timeInRangePercent,
            )
        } else {
            sentences += String.format(Locale.US, "Time in range was %.0f%% this week.", current.timeInRangePercent)
            sentences += if (current.coefficientOfVariation < 36) "Glucose was relatively stable." else "Glucose showed more day-to-day swings."
        }

        if (current.lowEpisodeCount > 0) {
            val hours = current.lowEpisodeTotalMinutes / 60
            val minutes = current.lowEpisodeTotalMinutes % 60
            val durationText = if (hours > 0) {
                if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            } else {
                "$minutes min"
            }
            val n = current.lowEpisodeCount
            sentences += "There ${if (n == 1) "was" else "were"} $n low${if (n == 1) "" else "s"} totaling about $durationText."
        }

        val start = current.hardestHourStart
        val end = current.hardestHourEnd
        if (start != null && end != null && current.hardestWindowAboveRangePercent >= 25) {
            sentences += "The most consistent difficulty was a rise between ${formatHour(start, timeZone)} and ${formatHour(end, timeZone)}."
        }

        return sentences.joinToString(" ")
    }

    private fun formatHour(hour: Int, timeZone: TimeZone): String {
        val calendar = Calendar.getInstance(timeZone).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).apply { this.timeZone = timeZone }.format(calendar.time)
    }
}
