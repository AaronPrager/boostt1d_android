package com.boostt1d.android.insights

import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.engine.DailyTherapyReviewService
import com.boostt1d.android.engine.GlucoseWeeklyReportBuilder
import com.boostt1d.android.engine.MealOutcomeBuilder
import com.boostt1d.android.engine.PatternService
import com.boostt1d.android.engine.TherapyChangeDetector
import com.boostt1d.android.engine.TherapyChangeOutcomeBuilder
import com.boostt1d.android.engine.TherapyGlucoseFormatter
import com.boostt1d.android.engine.TherapySettingsReviewBuilder
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.engine.WhatHappenedDailyOverviewBuilder
import com.boostt1d.android.engine.WhatHappenedPatternDetector
import java.util.TimeZone

/**
 * One load, one seven-day window, one source of truth.
 *
 * This is the computation half of the iOS report's `refreshReport`: everything after the
 * network has answered. It takes the fourteen days the app holds and produces the snapshot
 * every page of the report paints from, going through the same caches iOS does so a reopen
 * costs nothing and a settled week is never rebuilt.
 *
 * Pure: no Android, no I/O beyond the stores the caches were given. Runs on a background
 * dispatcher; the view model owns that.
 */
class WhatHappenedReportLoader(
    private val patternService: PatternService,
    private val detector: TherapyChangeDetector,
    private val analysisCache: WhatHappenedAnalysisCache,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    fun build(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        profile: NightscoutProfileDocument?,
        lowGlucose: Double,
        highGlucose: Double,
        therapyType: InsulinTherapyType,
        formatter: TherapyGlucoseFormatter,
        nowMillis: Long,
        foodLogEntries: List<FoodLogSnapshot> = emptyList(),
    ): WhatHappenedAnalysisCache.Snapshot {
        // The summary page's own rolling window drives its metrics, but everything
        // pattern-related uses the detector's window of complete days. Feeding the model
        // today's meals while the patterns exclude today described two different weeks.
        val window = WhatHappenedPatternDetector.analysisWindow(7, nowMillis, timeZone)
        val weekStart = window.startMillis
        val weekEnd = window.endMillis

        val report = GlucoseWeeklyReportBuilder.build(entries, lowGlucose, highGlucose, nowMillis = nowMillis, timeZone = timeZone)
        val weekEntries = entries.filter { it.epochMilliseconds >= weekStart && it.epochMilliseconds < weekEnd }
        val weekTreatments = treatments.filter { val at = MealOutcomeBuilder.treatmentMillis(it); at >= weekStart && at < weekEnd }
        val weekFood = foodLogEntries.filter { it.recordedAtMillis >= weekStart && it.recordedAtMillis < weekEnd }

        // Meals, repeat foods and weekday behaviour are what a model could add over the
        // detector, so they are built here even while the AI path is absent.
        val mealContext = MealOutcomeBuilder.promptContext(weekEntries, weekTreatments, weekFood, lowGlucose, highGlucose, timeZone)
        val patternResult = patternService.patterns(
            entries = weekEntries, treatments = weekTreatments,
            lowGlucose = lowGlucose, highGlucose = highGlucose,
            periodDays = 7, limit = 3,
            mealContext = mealContext, hasTherapyProfile = profile != null,
            nowMillis = nowMillis,
        )

        val daily = WhatHappenedDailyOverviewBuilder.build(entries, treatments, lowGlucose, highGlucose, weekEndMillis = weekEnd, timeZone = timeZone)

        // Identical settings append nothing, so this is safe on every load; a hand-entered
        // profile that changed since the last save is what it catches.
        val changes = detector.record(profile, emptyList(), nowMillis)
        val watchingSince = detector.watchingSinceMillis

        // Everything below analyses complete days only, so it cannot change until midnight.
        // Restricting the inputs to settled data is what makes that true of the outcomes as
        // well — otherwise today's readings would keep nudging the "after" side of a recent
        // change, and the verdict would drift through the day.
        val settledEntries = entries.filter { it.epochMilliseconds < weekEnd }
        val settledTreatments = treatments.filter { MealOutcomeBuilder.treatmentMillis(it) < weekEnd }

        val signature = WhatHappenedAnalysisCache.Signature(
            windowStartMillis = weekStart,
            settledEntryCount = settledEntries.size,
            latestSettledReadingMillis = settledEntries.maxOfOrNull { it.epochMilliseconds } ?: 0L,
            settledTreatmentCount = settledTreatments.size,
            lowGlucose = lowGlucose,
            highGlucose = highGlucose,
            profileDigest = WhatHappenedAnalysisCache.profileDigest(profile),
        )

        val bundle = analysisCache.cached(signature) ?: run {
            // The settings review is scoped to the days since any change it would otherwise
            // average across, so it never describes a therapy the user no longer has.
            val review = TherapySettingsReviewBuilder.build(
                glucoseEntries = weekEntries, treatments = weekTreatments, foodLogEntries = weekFood,
                profile = profile, lowGlucose = lowGlucose, highGlucose = highGlucose,
                periodDays = 7, changes = changes, nowMillis = nowMillis,
                therapyType = therapyType, formatter = formatter, timeZone = timeZone,
            )
            // Judged over a longer horizon than the week the rest of the page covers: a change
            // made ten days ago is still the most recent thing the user did.
            val outcomes = TherapyChangeOutcomeBuilder.build(
                changes = changes, glucoseEntries = settledEntries, treatments = settledTreatments,
                lowGlucose = lowGlucose, highGlucose = highGlucose,
                nowMillis = nowMillis, formatter = formatter, timeZone = timeZone,
            )
            WhatHappenedAnalysisCache.Bundle(review, outcomes).also { analysisCache.store(it, signature) }
        }

        // The window ends at midnight; the review is dated to the last moment inside it.
        val displayPeriodEnd = weekEnd - 1
        // No AI in this build, so the opening review is the complete review. When phase 4
        // brings the reviewer, today's cached wording is painted here first.
        val dailyReview = DailyTherapyReviewService.assemble(
            formulaReview = bundle.review, treatments = weekTreatments,
            periodStartMillis = weekStart, periodEndMillis = displayPeriodEnd,
            ai = null, generatedAtMillis = nowMillis, statusNote = null, therapyType = therapyType,
        )

        val snapshot = WhatHappenedAnalysisCache.Snapshot(
            report = report,
            patterns = patternResult.patterns,
            coverageLabel = patternResult.coverageLabel,
            dailyDays = daily,
            review = bundle.review,
            outcomes = bundle.outcomes,
            watchingSinceMillis = watchingSince,
            dailyTherapyReview = dailyReview,
        )
        analysisCache.store(snapshot)
        return snapshot
    }
}
