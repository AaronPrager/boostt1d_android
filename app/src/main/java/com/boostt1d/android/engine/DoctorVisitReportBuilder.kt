package com.boostt1d.android.engine

import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DoctorVisitPeriod(val days: Int, val title: String) {
    DAYS_7(7, "7 days"),
    DAYS_14(14, "14 days");

    val shortTitle: String get() = "${days}d"
}

data class DoctorVisitHourBlock(val hourStart: Int, val hourEnd: Int, val percent: Double, val label: String) {
    companion object {
        fun of(hourStart: Int, hourEnd: Int, percent: Double, kind: String) =
            DoctorVisitHourBlock(hourStart, hourEnd, percent, "$kind ${hourLabel(hourStart)}–${hourLabel(hourEnd)}")

        private fun hourLabel(hour: Int): String {
            val h = ((hour % 24) + 24) % 24
            val suffix = if (h < 12) "am" else "pm"
            val display = if (h % 12 == 0) 12 else h % 12
            return "$display$suffix"
        }
    }
}

data class DoctorVisitDailyProfile(
    val dayStartMillis: Long,
    val weekdayLabel: String,
    val averageGlucoseMgdL: Double?,
    val timeInRangePercent: Double?,
    val readingCount: Int,
    val insulinUnits: Double,
    val carbsGrams: Double,
    val mealCount: Int,
    val bolusCount: Int,
) {
    val hasInsulinOrCarbs: Boolean get() = insulinUnits > 0 || carbsGrams > 0 || mealCount > 0 || bolusCount > 0
}

data class DoctorVisitReport(
    val period: DoctorVisitPeriod = DoctorVisitPeriod.DAYS_7,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val dataDaysAvailable: Double = 0.0,
    val current: GlucosePeriodMetrics = GlucosePeriodMetrics(),
    val previous: GlucosePeriodMetrics? = null,
    /** Metrics used for "what changed" (the second half when a prior period is unavailable). */
    val changeCurrent: GlucosePeriodMetrics = GlucosePeriodMetrics(),
    val usesHalfPeriodComparison: Boolean = false,
    val weekday: GlucosePeriodMetrics = GlucosePeriodMetrics(),
    val weekend: GlucosePeriodMetrics = GlucosePeriodMetrics(),
    val totalInsulinUnits: Double = 0.0,
    val totalCarbsGrams: Double = 0.0,
    val mealCount: Int = 0,
    val bolusCount: Int = 0,
    val exerciseCount: Int = 0,
    /** Avg glucose change in the 2 hours after exercise vs the same-day baseline (mg/dL). */
    val exerciseAssociatedDeltaMgdL: Double? = null,
    val recurrentHighBlocks: List<DoctorVisitHourBlock> = emptyList(),
    val recurrentLowBlocks: List<DoctorVisitHourBlock> = emptyList(),
    val dailyProfiles: List<DoctorVisitDailyProfile> = emptyList(),
    val patterns: List<WhatHappenedPattern> = emptyList(),
    /** Formula dose suggestions. Populated only when doses may be shown at all; empty in this build everywhere. */
    val doseSuggestions: List<AdjustmentSuggestion> = emptyList(),
    /** How insulin reaches the patient — framing a clinician needs before reading a bolus log. */
    val deliverySummary: String = "",
    /** The same narrative the What Happened? screen opens with, against this report's own window. */
    val plainLanguageSummary: String = "",
    val therapy: DoctorVisitTherapySnapshot = DoctorVisitTherapySnapshot(),
) {
    val hasEnoughData: Boolean get() = current.readingCount >= 24

    val changeSummaryLines: List<String>
        get() {
            val previous = previous ?: return listOf("Not enough prior-period data yet to compare what changed.")
            return listOf(
                deltaLine("Time in range", changeCurrent.timeInRangePercent, previous.timeInRangePercent, "%", higherIsBetter = true),
                deltaLine("Time low", changeCurrent.timeLowPercent, previous.timeLowPercent, "%", higherIsBetter = false),
                deltaLine("Average glucose", changeCurrent.averageGlucoseMgdL, previous.averageGlucoseMgdL, " mg/dL", higherIsBetter = null),
                deltaLine("Variability (CV)", changeCurrent.coefficientOfVariation, previous.coefficientOfVariation, "%", higherIsBetter = false),
                deltaLine("Low episodes", changeCurrent.lowEpisodeCount.toDouble(), previous.lowEpisodeCount.toDouble(), "", higherIsBetter = false, asInteger = true),
            )
        }

    private fun deltaLine(title: String, current: Double, previous: Double, suffix: String, higherIsBetter: Boolean?, asInteger: Boolean = false): String {
        val change = current - previous
        val formatted: String
        if (asInteger) {
            val intChange = change.roundToInt()
            if (intChange == 0) return "$title: similar to prior period"
            formatted = "${if (intChange > 0) "+" else ""}$intChange"
        } else if (abs(change) < 0.5) {
            return "$title: similar to prior period"
        } else {
            formatted = String.format(Locale.US, "%+.1f%s", change, suffix)
        }
        val hint = when (higherIsBetter) {
            null -> ""
            true -> if (change > 0) " (improved)" else " (worsened)"
            false -> if (change < 0) " (improved)" else " (worsened)"
        }
        return "$title: $formatted$hint"
    }
}

/**
 * The clinical report a patient brings to an appointment. Ported 1:1 from the iOS
 * DoctorVisitReportBuilder; there is no iOS test file, so the Android tests pin the behaviour
 * the port was read from.
 */
object DoctorVisitReportBuilder {

    fun build(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        period: DoctorVisitPeriod,
        lowMgdL: Double,
        highMgdL: Double,
        therapyProfile: NightscoutProfileDocument?,
        veryHighMgdL: Double = GlucoseCacheRules.VERY_HIGH_MGDL,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        /** Patterns from [PatternService], so the PDF matches what the app shows. Null detects locally. */
        patterns: List<WhatHappenedPattern>? = null,
        /** From [DoseSuggestionService]; the caller decides whether to supply them. Never computed here. */
        doseSuggestions: List<AdjustmentSuggestion> = emptyList(),
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
    ): DoctorVisitReport {
        val calendar = Calendar.getInstance(timeZone)
        fun minusDays(millis: Long, days: Int): Long = calendar.run { timeInMillis = millis; add(Calendar.DAY_OF_MONTH, -days); timeInMillis }

        val periodEnd = nowMillis
        val periodStart = minusDays(periodEnd, period.days)
        val previousEnd = periodStart
        val previousStart = minusDays(previousEnd, period.days)

        val periodEntries = entries.filter { it.epochMilliseconds in periodStart..periodEnd }.sortedBy { it.epochMilliseconds }
        val previousEntries = entries.filter { it.epochMilliseconds >= previousStart && it.epochMilliseconds < previousEnd }

        val current = GlucoseWeeklyReportBuilder.metrics(periodEntries, lowMgdL, highMgdL, veryHighMgdL, timeZone)

        // Prefer a true prior period. If unavailable, compare first vs second half.
        val priorPeriod = if (previousEntries.size >= 24) GlucoseWeeklyReportBuilder.metrics(previousEntries, lowMgdL, highMgdL, veryHighMgdL, timeZone) else null
        val halfSplit: Pair<GlucosePeriodMetrics, GlucosePeriodMetrics>? = if (priorPeriod == null && periodEntries.size >= 48) {
            val midpoint = periodStart + (periodEnd - periodStart) / 2
            val first = periodEntries.filter { it.epochMilliseconds < midpoint }
            val second = periodEntries.filter { it.epochMilliseconds >= midpoint }
            if (first.size >= 24 && second.size >= 24) {
                GlucoseWeeklyReportBuilder.metrics(first, lowMgdL, highMgdL, veryHighMgdL, timeZone) to
                    GlucoseWeeklyReportBuilder.metrics(second, lowMgdL, highMgdL, veryHighMgdL, timeZone)
            } else null
        } else null

        val changeCurrent = halfSplit?.second ?: current
        val changePrevious = priorPeriod ?: halfSplit?.first

        val weekdayEntries = periodEntries.filter { !isWeekend(it.epochMilliseconds, calendar) }
        val weekendEntries = periodEntries.filter { isWeekend(it.epochMilliseconds, calendar) }

        val periodTreatments = treatments.filter { val at = MealOutcomeBuilder.treatmentMillis(it); at in periodStart..periodEnd }

        var totalInsulin = 0.0; var totalCarbs = 0.0; var mealCount = 0; var bolusCount = 0; var exerciseCount = 0
        for (treatment in periodTreatments) {
            treatment.insulin?.takeIf { it > 0 }?.let { totalInsulin += it; bolusCount += 1 }
            treatment.carbs?.takeIf { it > 0 }?.let { totalCarbs += it; mealCount += 1 }
            if (treatment.eventType == "Exercise") exerciseCount += 1
        }

        val detected = patterns ?: WhatHappenedPatternDetector.detectTopPatterns(
            periodEntries, periodTreatments, lowMgdL, highMgdL, nowMillis, timeZone, periodDays = period.days, limit = 5,
        )

        val weekly = WhatHappenedWeeklyReport(
            current = current, previous = changePrevious, lowThresholdMgdL = lowMgdL, highThresholdMgdL = highMgdL,
            veryHighThresholdMgdL = veryHighMgdL, currentPeriodStartMillis = periodStart, currentPeriodEndMillis = periodEnd,
        )

        return DoctorVisitReport(
            period = period,
            periodStartMillis = periodStart,
            periodEndMillis = periodEnd,
            dataDaysAvailable = dataSpanDays(periodEntries),
            current = current,
            previous = changePrevious,
            changeCurrent = changeCurrent,
            usesHalfPeriodComparison = halfSplit != null,
            weekday = GlucoseWeeklyReportBuilder.metrics(weekdayEntries, lowMgdL, highMgdL, veryHighMgdL, timeZone),
            weekend = GlucoseWeeklyReportBuilder.metrics(weekendEntries, lowMgdL, highMgdL, veryHighMgdL, timeZone),
            totalInsulinUnits = totalInsulin,
            totalCarbsGrams = totalCarbs,
            mealCount = mealCount,
            bolusCount = bolusCount,
            exerciseCount = exerciseCount,
            exerciseAssociatedDeltaMgdL = exerciseAssociatedDelta(periodEntries, periodTreatments, timeZone),
            recurrentHighBlocks = recurrentBlocks(periodEntries, highMgdL, above = true, kind = "High", timeZone = timeZone),
            recurrentLowBlocks = recurrentBlocks(periodEntries, lowMgdL, above = false, kind = "Low", timeZone = timeZone),
            dailyProfiles = dailyProfiles(periodEntries, periodTreatments, lowMgdL, highMgdL, periodStart, periodEnd, timeZone),
            patterns = detected,
            doseSuggestions = doseSuggestions,
            deliverySummary = InsulinDeliveryContext(periodTreatments, therapyType).clinicalSummary,
            plainLanguageSummary = GlucoseWeeklyReportBuilder.plainLanguageSummary(weekly, timeZone),
            therapy = DoctorVisitTherapySnapshot.from(therapyProfile),
        )
    }

    // MARK: - Helpers

    private fun isWeekend(millis: Long, calendar: Calendar): Boolean {
        calendar.timeInMillis = millis
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    private fun dataSpanDays(entries: List<NightscoutGlucoseEntry>): Double {
        val first = entries.firstOrNull()?.epochMilliseconds ?: return 0.0
        val last = entries.last().epochMilliseconds
        return ((last - first) / 86_400_000.0).coerceAtLeast(0.0)
    }

    private fun dailyProfiles(
        entries: List<NightscoutGlucoseEntry>, treatments: List<NightscoutTreatment>,
        lowMgdL: Double, highMgdL: Double, startMillis: Long, endMillis: Long, timeZone: TimeZone,
    ): List<DoctorVisitDailyProfile> {
        val formatter = SimpleDateFormat("EEE M/d", Locale.getDefault()).apply { this.timeZone = timeZone }
        val calendar = Calendar.getInstance(timeZone)
        val profiles = mutableListOf<DoctorVisitDailyProfile>()
        var cursor = TodaySoFarBuilder.startOfDay(startMillis, timeZone)
        val endDay = TodaySoFarBuilder.startOfDay(endMillis, timeZone)

        while (cursor <= endDay) {
            val next = calendar.run { timeInMillis = cursor; add(Calendar.DAY_OF_MONTH, 1); timeInMillis }
            val dayEntries = entries.filter { it.epochMilliseconds >= cursor && it.epochMilliseconds < next }
            val dayTreatments = treatments.filter { val at = MealOutcomeBuilder.treatmentMillis(it); at >= cursor && at < next }

            val values = dayEntries.map { it.sgv.toDouble() }
            val avg = if (values.isEmpty()) null else values.sum() / values.size
            val tir = if (values.isEmpty()) null else values.count { it >= lowMgdL && it <= highMgdL }.toDouble() / values.size * 100

            var dayInsulin = 0.0; var dayCarbs = 0.0; var dayMeals = 0; var dayBoluses = 0
            for (t in dayTreatments) {
                t.insulin?.takeIf { it > 0 }?.let { dayInsulin += it; dayBoluses += 1 }
                t.carbs?.takeIf { it > 0 }?.let { dayCarbs += it; dayMeals += 1 }
            }
            profiles += DoctorVisitDailyProfile(cursor, formatter.format(Date(cursor)), avg, tir, dayEntries.size, dayInsulin, dayCarbs, dayMeals, dayBoluses)
            cursor = next
        }
        return profiles.reversed()
    }

    /** Top clock-hour blocks where highs or lows recur most often — distinct times of day, abutting windows merged. */
    internal fun recurrentBlocks(entries: List<NightscoutGlucoseEntry>, thresholdMgdL: Double, above: Boolean, kind: String, timeZone: TimeZone): List<DoctorVisitHourBlock> {
        if (entries.isEmpty()) return emptyList()
        val hitByHour = IntArray(24)
        val totalByHour = IntArray(24)
        val calendar = Calendar.getInstance(timeZone)
        for (entry in entries) {
            calendar.timeInMillis = entry.epochMilliseconds
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            totalByHour[hour] += 1
            val value = entry.sgv.toDouble()
            if (if (above) value > thresholdMgdL else value < thresholdMgdL) hitByHour[hour] += 1
        }

        // Score 3-hour windows by average hit rate.
        val windows = mutableListOf<Pair<Int, Double>>()
        for (start in 0 until 24) {
            var hits = 0; var total = 0
            for (offset in 0 until 3) { val h = (start + offset) % 24; hits += hitByHour[h]; total += totalByHour[h] }
            if (total < 8) continue
            val percent = hits.toDouble() / total * 100
            if (percent >= 15) windows += start to percent
        }

        // The windows slide by one hour, so the raw top 3 were almost always the same event
        // three times over. Pick greedily by strength, skipping any window sharing an hour with
        // one already chosen, so the reported periods describe genuinely distinct times of day.
        val covered = mutableSetOf<Int>()
        for ((start, _) in windows.sortedByDescending { it.second }) {
            val hours = (0 until 3).map { (start + it) % 24 }.toSet()
            if (hours.any { it in covered }) continue
            covered += hours
            if (covered.size >= 9) break
        }

        return contiguousHourSpans(covered).map { (start, length) ->
            var hits = 0; var total = 0
            for (offset in 0 until length) { val h = (start + offset) % 24; hits += hitByHour[h]; total += totalByHour[h] }
            DoctorVisitHourBlock.of(start, (start + length) % 24, if (total > 0) hits.toDouble() / total * 100 else 0.0, kind)
        }.sortedByDescending { it.percent }
    }

    /** Maximal runs of consecutive covered hours on the 24-hour clock, wrapping midnight. */
    internal fun contiguousHourSpans(hours: Set<Int>): List<Pair<Int, Int>> {
        if (hours.isEmpty()) return emptyList()
        if (hours.size >= 24) return listOf(0 to 24)
        return hours.sorted().filter { ((it + 23) % 24) !in hours }.map { start ->
            var length = 0; var hour = start
            while (hour in hours && length < 24) { length += 1; hour = (hour + 1) % 24 }
            start to length
        }
    }

    /** Mean glucose 0–2h after exercise minus mean glucose earlier the same day. */
    private fun exerciseAssociatedDelta(entries: List<NightscoutGlucoseEntry>, treatments: List<NightscoutTreatment>, timeZone: TimeZone): Double? {
        val exercises = treatments.filter { it.eventType == "Exercise" }
        if (exercises.isEmpty()) return null
        val deltas = mutableListOf<Double>()
        for (exercise in exercises) {
            val at = MealOutcomeBuilder.treatmentMillis(exercise)
            val dayStart = TodaySoFarBuilder.startOfDay(at, timeZone)
            val baseline = entries.filter { it.epochMilliseconds >= dayStart && it.epochMilliseconds < at }.map { it.sgv.toDouble() }
            val after = entries.filter { it.epochMilliseconds >= at && it.epochMilliseconds <= at + 2 * 3_600_000L }.map { it.sgv.toDouble() }
            if (baseline.size < 3 || after.size < 2) continue
            deltas += after.sum() / after.size - baseline.sum() / baseline.size
        }
        if (deltas.size < 2) return null
        return deltas.sum() / deltas.size
    }
}
