package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds what repeated over a window of complete days — meal-window highs, overnight rises,
 * afternoon lows, corrections that ended low — and ranks them.
 *
 * Everything here is measured: a pattern is a count of days it happened on over a count of
 * days it could have, and the wording carries both numbers. Ported 1:1 from the iOS
 * WhatHappenedPatternDetector; the claim-integrity tests are the spec for the denominators.
 */
object WhatHappenedPatternDetector {

    private class DayBucket(
        val dayStartMillis: Long,
        val label: String,
        val entries: List<NightscoutGlucoseEntry>,
    )

    /** The stretch of days a period covers. `end` is exclusive. */
    data class Window(val startMillis: Long, val endMillis: Long)

    private const val HOUR_MILLIS = 60L * 60 * 1000

    /**
     * Readings a calendar day needs before it counts as a day the pattern had a chance to
     * happen on. Roughly two hours at the ten-minute resolution the on-device cache stores.
     */
    const val MIN_READINGS_PER_DAY = 12

    /** Returns up to [limit] highest-scoring patterns for a rolling window of [periodDays]. */
    fun detectTopPatterns(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment> = emptyList(),
        lowMgdL: Double,
        highMgdL: Double,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        periodDays: Int = 7,
        limit: Int = 3,
    ): List<WhatHappenedPattern> {
        val daysBack = max(periodDays, 1)
        val window = analysisWindow(daysBack, nowMillis, timeZone)
        val weekEntries = entries
            .filter { it.epochMilliseconds >= window.startMillis && it.epochMilliseconds < window.endMillis }
            .sortedBy { it.epochMilliseconds }

        val minReadings = max(12, daysBack * 2)
        if (weekEntries.size < minReadings) return emptyList()

        val days = dayBuckets(weekEntries, window, timeZone)
        val minDays = min(3, max(2, daysBack / 3))
        if (days.size < minDays) return emptyList()

        val hourOf = hourExtractor(timeZone)
        val candidates = mutableListOf<WhatHappenedPattern>()

        candidates += mealWindowHighs(
            title = "Repeated breakfast highs", windowName = "breakfast", hourRange = 6 until 10,
            days = days, highMgdL = highMgdL, hourOf = hourOf,
            factors = listOf(
                "Breakfast composition and carb estimate",
                "Insulin timing relative to eating",
                "Morning insulin sensitivity / breakfast settings",
            ),
            discuss = listOf(
                "Does the morning meal strategy need review?",
                "Would logging breakfast carbs and bolus timing for a week help evaluate this?",
            ),
        )

        // Lunch was missing entirely once — only breakfast and dinner were candidates — so
        // large midday highs never appeared even when they dominated dinner. The window matches
        // the therapy ICR lunch block (11:00–16:00).
        candidates += mealWindowHighs(
            title = "Repeated lunch highs", windowName = "lunch", hourRange = 11 until 16,
            days = days, highMgdL = highMgdL, hourOf = hourOf,
            factors = listOf(
                "Lunch composition and carb estimate",
                "Insulin timing relative to lunch",
                "Midday insulin sensitivity / lunch settings",
            ),
            discuss = listOf(
                "Does the midday meal strategy need review?",
                "Would logging lunch carbs and bolus timing for a week help evaluate this?",
            ),
        )

        candidates += mealWindowHighs(
            title = "Repeated dinner highs", windowName = "dinner", hourRange = 17 until 22,
            days = days, highMgdL = highMgdL, hourOf = hourOf,
            factors = listOf(
                "Meal composition (including higher-fat dinners)",
                "Insulin timing relative to dinner",
                "Carbohydrate estimate or dinner insulin settings",
            ),
            discuss = listOf(
                "Does the dinner strategy need review?",
                "Could meal composition or bolus timing be contributing to these evening rises?",
            ),
        )

        overnightRisePattern(days, highMgdL, hourOf)?.let { candidates += it }
        afternoonLowPattern(days, lowMgdL, hourOf)?.let { candidates += it }
        weekdayVariabilityPattern(days, lowMgdL, highMgdL)?.let { candidates += it }

        // The same complete-day window the glucose uses, so a correction on a partial day
        // cannot be judged against readings the analysis has excluded.
        val weekTreatments = treatments.filter {
            val at = MealOutcomeBuilder.treatmentMillis(it)
            at >= window.startMillis && at < window.endMillis
        }
        correctionFollowedByLowPattern(weekEntries, weekTreatments, lowMgdL, days, timeZone)?.let { candidates += it }
        delayedPostMealHighPattern(weekEntries, weekTreatments, highMgdL, days, timeZone)?.let { candidates += it }

        return candidates
            .sortedWith(compareByDescending<WhatHappenedPattern> { it.score }.thenByDescending { it.occurrenceCount })
            .take(limit)
    }

    // MARK: - Pattern builders

    private fun mealWindowHighs(
        title: String,
        windowName: String,
        hourRange: IntRange,
        days: List<DayBucket>,
        highMgdL: Double,
        hourOf: (Long) -> Int,
        factors: List<String>,
        discuss: List<String>,
    ): List<WhatHappenedPattern> {
        var flaggedDays = 0
        var excessSum = 0.0
        val chart = mutableListOf<WhatHappenedPatternChartPoint>()

        for (day in days) {
            val windowEntries = day.entries.filter { hourOf(it.epochMilliseconds) in hourRange }
            val average = if (windowEntries.isEmpty()) 0.0 else windowEntries.sumOf { it.sgv.toDouble() } / windowEntries.size
            val flagged = windowEntries.isNotEmpty() && average > highMgdL
            if (flagged) {
                flaggedDays += 1
                excessSum += average - highMgdL
            }
            chart += WhatHappenedPatternChartPoint(label = day.label, value = average, highlighted = flagged)
        }

        val opportunities = days.count { day -> day.entries.any { hourOf(it.epochMilliseconds) in hourRange } }
        if (opportunities < 3 || flaggedDays < 3) return emptyList()

        val frequency = flaggedDays.toDouble() / opportunities
        if (frequency < 0.4) return emptyList()

        val priority = when {
            frequency >= 0.7 -> Priority.HIGH
            frequency >= 0.5 -> Priority.MEDIUM
            else -> Priority.LOW
        }
        val highLabel = Math.round(highMgdL)
        // How far above target on flagged days — so a larger lunch high outranks a milder
        // dinner high at the same frequency, instead of frequency alone deciding the top card.
        val meanExcess = excessSum / flaggedDays
        val score = frequency * 100 + flaggedDays * 4 + min(meanExcess, 100.0) * 0.4

        return listOf(
            WhatHappenedPattern(
                id = UUID.randomUUID().toString(),
                title = title,
                observation = "Average glucose during $windowName was above $highLabel mg/dL on $flaggedDays of $opportunities days.",
                frequencyLabel = "$flaggedDays of $opportunities days",
                occurrenceCount = flaggedDays,
                opportunityCount = opportunities,
                priority = priority,
                chartPoints = chart,
                chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE,
                contributingFactors = factors,
                discussQuestions = discuss,
                score = score,
                hours = hourRange.toSet(),
            )
        )
    }

    private fun overnightRisePattern(days: List<DayBucket>, highMgdL: Double, hourOf: (Long) -> Int): WhatHappenedPattern? {
        var riseDays = 0
        var validDays = 0
        val chart = mutableListOf<WhatHappenedPatternChartPoint>()

        for (day in days) {
            val early = day.entries.filter { hourOf(it.epochMilliseconds) in 0 until 3 }
            val late = day.entries.filter { hourOf(it.epochMilliseconds) in 4 until 7 }
            if (early.size < 2 || late.size < 2) {
                chart += WhatHappenedPatternChartPoint(label = day.label, value = 0.0, highlighted = false)
                continue
            }
            validDays += 1
            val earlyAvg = early.sumOf { it.sgv.toDouble() } / early.size
            val lateAvg = late.sumOf { it.sgv.toDouble() } / late.size
            val rise = lateAvg - earlyAvg
            val flagged = rise >= 25 || (lateAvg > highMgdL && rise >= 15)
            if (flagged) riseDays += 1
            chart += WhatHappenedPatternChartPoint(label = day.label, value = max(0.0, rise), highlighted = flagged)
        }

        if (validDays < 3 || riseDays < 3) return null
        val frequency = riseDays.toDouble() / validDays
        if (frequency < 0.4) return null

        return WhatHappenedPattern(
            id = UUID.randomUUID().toString(),
            title = "Overnight glucose rise",
            observation = "Glucose tended to rise overnight into the early morning on $riseDays of $validDays nights.",
            frequencyLabel = "$riseDays of $validDays nights",
            occurrenceCount = riseDays,
            opportunityCount = validDays,
            priority = if (frequency >= 0.7) Priority.HIGH else Priority.MEDIUM,
            chartPoints = chart,
            chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE,
            contributingFactors = listOf(
                "Overnight basal / background insulin coverage",
                "Bedtime snacks or delayed dinner absorption",
                "Dawn phenomenon or overnight growth hormone effects",
            ),
            discussQuestions = listOf(
                "Could overnight coverage be worth reviewing with the care team?",
                "What bedtime details should I log to understand these overnight rises?",
            ),
            score = frequency * 95 + riseDays * 3,
            hours = (0 until 7).toSet(),
        )
    }

    private fun afternoonLowPattern(days: List<DayBucket>, lowMgdL: Double, hourOf: (Long) -> Int): WhatHappenedPattern? {
        var lowDays = 0
        val chart = mutableListOf<WhatHappenedPatternChartPoint>()

        for (day in days) {
            val afternoon = day.entries.filter { hourOf(it.epochMilliseconds) in 14 until 17 }
            val hasLow = afternoon.any { it.sgv < lowMgdL }
            if (hasLow) lowDays += 1
            val avg = if (afternoon.isEmpty()) 0.0 else afternoon.sumOf { it.sgv.toDouble() } / afternoon.size
            chart += WhatHappenedPatternChartPoint(label = day.label, value = avg, highlighted = hasLow)
        }

        val opportunities = days.count { day -> day.entries.any { hourOf(it.epochMilliseconds) in 14 until 17 } }
        if (opportunities < 3 || lowDays < 2) return null
        val frequency = lowDays.toDouble() / opportunities
        if (frequency < 0.3) return null

        val lowLabel = Math.round(lowMgdL)
        return WhatHappenedPattern(
            id = UUID.randomUUID().toString(),
            title = "Afternoon lows",
            observation = "Glucose dipped below $lowLabel mg/dL in the afternoon on $lowDays of $opportunities days.",
            frequencyLabel = "$lowDays of $opportunities days",
            occurrenceCount = lowDays,
            opportunityCount = opportunities,
            priority = if (frequency >= 0.5) Priority.HIGH else Priority.MEDIUM,
            chartPoints = chart,
            chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE,
            contributingFactors = listOf(
                "Afternoon activity or exercise",
                "Insulin still active from lunch",
                "Missed or delayed afternoon snack",
            ),
            discussQuestions = listOf(
                "If afternoon lows keep appearing, could activity timing or lunch coverage be worth reviewing?",
                "What should I note on active afternoons to evaluate this safely?",
            ),
            score = frequency * 90 + lowDays * 5,
            hours = (14 until 17).toSet(),
        )
    }

    private fun weekdayVariabilityPattern(days: List<DayBucket>, lowMgdL: Double, highMgdL: Double): WhatHappenedPattern? {
        if (days.size < 5) return null

        class DayStat(val label: String, val cv: Double, val tir: Double)
        val dayStats = mutableListOf<DayStat>()
        for (day in days) {
            if (day.entries.size < 6) continue
            val values = day.entries.map { it.sgv.toDouble() }
            val avg = values.sum() / values.size
            if (avg <= 0) continue
            val variance = values.sumOf { (it - avg) * (it - avg) } / values.size
            val cv = sqrt(variance) / avg * 100
            val tir = values.count { it >= lowMgdL && it <= highMgdL }.toDouble() / values.size * 100
            dayStats += DayStat(day.label, cv, tir)
        }

        if (dayStats.size < 4) return null
        val cvs = dayStats.map { it.cv }
        val meanCV = cvs.sum() / cvs.size
        val maxCV = cvs.maxOrNull() ?: 0.0
        if (maxCV - meanCV < 8) return null

        val chart = dayStats.map {
            WhatHappenedPatternChartPoint(label = it.label, value = it.cv, highlighted = it.cv >= meanCV + 8)
        }
        val highDays = chart.count { it.highlighted }

        return WhatHappenedPattern(
            id = UUID.randomUUID().toString(),
            title = "Higher variability on some days",
            observation = "Glucose swung more on $highDays day${if (highDays == 1) "" else "s"} this week (CV notably above the weekly average).",
            frequencyLabel = "$highDays of ${dayStats.size} days",
            occurrenceCount = highDays,
            opportunityCount = dayStats.size,
            priority = if (highDays >= 3) Priority.MEDIUM else Priority.LOW,
            chartPoints = chart,
            chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE,
            contributingFactors = listOf(
                "Schedule differences (work, school, weekends)",
                "Activity, stress, or sleep changes",
                "Less consistent meal or bolus timing",
            ),
            discussQuestions = listOf(
                "Do certain days of the week need a different routine discussion?",
                "What lifestyle differences stand out on the more variable days?",
            ),
            score = highDays * 12 + (maxCV - meanCV),
        )
    }

    private fun correctionFollowedByLowPattern(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowMgdL: Double,
        days: List<DayBucket>,
        timeZone: TimeZone,
    ): WhatHappenedPattern? {
        val corrections = treatments.filter { (it.insulin ?: 0.0) > 0 && (it.carbs ?: 0.0) <= 0 }
        if (corrections.size < 3) return null

        fun followedByLow(correction: NightscoutTreatment): Boolean {
            val at = MealOutcomeBuilder.treatmentMillis(correction)
            val windowEnd = at + 4 * HOUR_MILLIS
            return entries.any { it.epochMilliseconds > at && it.epochMilliseconds <= windowEnd && it.sgv < lowMgdL }
        }

        val matched = corrections.count(::followedByLow)
        if (matched < 2) return null
        val frequency = matched.toDouble() / corrections.size
        if (frequency < 0.3) return null

        val chart = days.map { day ->
            val dayMatched = corrections
                .filter { sameDay(MealOutcomeBuilder.treatmentMillis(it), day.dayStartMillis, timeZone) }
                .any(::followedByLow)
            WhatHappenedPatternChartPoint(label = day.label, value = if (dayMatched) 1.0 else 0.0, highlighted = dayMatched)
        }

        return WhatHappenedPattern(
            id = UUID.randomUUID().toString(),
            title = "Corrections followed by lows",
            observation = "After $matched of ${corrections.size} correction doses, glucose later dipped below range within about 4 hours.",
            frequencyLabel = "$matched of ${corrections.size} corrections",
            occurrenceCount = matched,
            opportunityCount = corrections.size,
            priority = if (frequency >= 0.5) Priority.HIGH else Priority.MEDIUM,
            chartPoints = chart,
            chartKind = WhatHappenedPatternChartKind.OCCURRENCE_FLAGS,
            contributingFactors = listOf(
                "Correction factor may be stronger than needed at that time",
                "Stacking with insulin already on board",
                "Activity after the correction",
            ),
            discussQuestions = listOf(
                "If this keeps happening, could correction dosing or timing be worth reviewing?",
                "How should I account for active insulin before correcting?",
            ),
            score = frequency * 110 + matched * 6,
        )
    }

    private fun delayedPostMealHighPattern(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        highMgdL: Double,
        days: List<DayBucket>,
        timeZone: TimeZone,
    ): WhatHappenedPattern? {
        val meals = treatments.filter { (it.carbs ?: 0.0) >= 15 }
        if (meals.size < 3) return null

        var delayed = 0
        for (meal in meals) {
            val at = MealOutcomeBuilder.treatmentMillis(meal)
            // Early window 0–2h vs delayed 2–4h.
            val early = entries.filter { it.epochMilliseconds > at && it.epochMilliseconds <= at + 2 * HOUR_MILLIS }
            val late = entries.filter { it.epochMilliseconds > at + 2 * HOUR_MILLIS && it.epochMilliseconds <= at + 4 * HOUR_MILLIS }
            if (early.isEmpty() || late.isEmpty()) continue
            val earlyMax = early.maxOf { it.sgv }
            val lateMax = late.maxOf { it.sgv }
            if (lateMax > highMgdL && lateMax >= earlyMax) delayed += 1
        }

        if (delayed < 2) return null
        val frequency = delayed.toDouble() / meals.size
        if (frequency < 0.3) return null

        val chart = days.map { day ->
            val dayHit = meals
                .filter { sameDay(MealOutcomeBuilder.treatmentMillis(it), day.dayStartMillis, timeZone) }
                .any { meal ->
                    val at = MealOutcomeBuilder.treatmentMillis(meal)
                    entries.any {
                        it.epochMilliseconds > at + 2 * HOUR_MILLIS && it.epochMilliseconds <= at + 4 * HOUR_MILLIS && it.sgv > highMgdL
                    }
                }
            WhatHappenedPatternChartPoint(label = day.label, value = if (dayHit) 1.0 else 0.0, highlighted = dayHit)
        }

        return WhatHappenedPattern(
            id = UUID.randomUUID().toString(),
            title = "Delayed highs after meals",
            observation = "Glucose peaked later (about 2–4 hours after eating) above range for $delayed of ${meals.size} logged meals.",
            frequencyLabel = "$delayed of ${meals.size} meals",
            occurrenceCount = delayed,
            opportunityCount = meals.size,
            priority = if (frequency >= 0.5) Priority.MEDIUM else Priority.LOW,
            chartPoints = chart,
            chartKind = WhatHappenedPatternChartKind.OCCURRENCE_FLAGS,
            contributingFactors = listOf(
                "Higher-fat or higher-protein meals with slower absorption",
                "Bolus timing relative to the meal",
                "Extended carbohydrate absorption",
            ),
            discussQuestions = listOf(
                "Could higher-fat meals need a different dosing discussion with the care team?",
                "What meal details should I log when delayed rises show up?",
            ),
            score = frequency * 85 + delayed * 4,
        )
    }

    // MARK: - Helpers

    /**
     * The last [periodDays] **complete** calendar days, ending at midnight this morning.
     *
     * Today is deliberately excluded. It is always partial, and including it does two bad
     * things at once: it counts a half-day as a full chance for a pattern to occur, and it
     * makes the denominator wobble through the day — six days at breakfast, seven by evening,
     * from the same data. A window of complete days is stable whenever it is opened, and
     * "5 of 7 days" means five out of seven whole days.
     *
     * Days are stepped with the calendar, not by adding 86,400,000 ms, so a DST change inside
     * the window does not shift midnight by an hour and leave a day short.
     */
    fun analysisWindow(periodDays: Int, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Window {
        val end = TodaySoFarBuilder.startOfDay(nowMillis, timeZone)
        val start = Calendar.getInstance(timeZone).apply {
            timeInMillis = end
            add(Calendar.DAY_OF_MONTH, -max(periodDays, 1))
        }.timeInMillis
        return Window(start, end)
    }

    /**
     * One bucket per complete day in the window.
     *
     * Anchored to calendar days rather than a rolling 7×24h span: walking from
     * `startOfDay(now − 7 days)` to `startOfDay(now)` inclusive spans **eight** calendar days
     * whenever `now` is not midnight. That is where "8 of 8" came from in a seven-day report.
     *
     * Days too thin to represent a day are dropped rather than counted as a missed
     * opportunity, so a sensor gap cannot quietly deflate a frequency.
     */
    private fun dayBuckets(entries: List<NightscoutGlucoseEntry>, window: Window, timeZone: TimeZone): List<DayBucket> {
        val formatter = SimpleDateFormat("EEE", Locale.getDefault()).apply { this.timeZone = timeZone }
        val calendar = Calendar.getInstance(timeZone)
        val buckets = mutableListOf<DayBucket>()

        var cursor = window.startMillis
        while (cursor < window.endMillis) {
            calendar.timeInMillis = cursor
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            val next = calendar.timeInMillis
            val dayEntries = entries.filter { it.epochMilliseconds >= cursor && it.epochMilliseconds < next }
            if (dayEntries.size >= MIN_READINGS_PER_DAY) {
                buckets += DayBucket(cursor, formatter.format(Date(cursor)), dayEntries)
            }
            cursor = next
        }
        return buckets
    }

    private fun hourExtractor(timeZone: TimeZone): (Long) -> Int {
        val calendar = Calendar.getInstance(timeZone)
        return { millis -> calendar.timeInMillis = millis; calendar.get(Calendar.HOUR_OF_DAY) }
    }

    private fun sameDay(millis: Long, dayStartMillis: Long, timeZone: TimeZone): Boolean =
        TodaySoFarBuilder.startOfDay(millis, timeZone) == dayStartMillis
}
