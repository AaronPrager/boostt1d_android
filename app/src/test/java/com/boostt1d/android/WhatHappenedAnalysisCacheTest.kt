package com.boostt1d.android

import com.boostt1d.android.engine.DailyTherapyProposal
import com.boostt1d.android.engine.DailyTherapyReview
import com.boostt1d.android.engine.DailyTherapyReviewSource
import com.boostt1d.android.engine.GlucosePeriodMetrics
import com.boostt1d.android.engine.InMemoryAnalysisCacheStore
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.TherapyBasalMethod
import com.boostt1d.android.engine.TherapyDirection
import com.boostt1d.android.engine.TherapyEvidenceStrength
import com.boostt1d.android.engine.TherapyFinding
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.engine.WhatHappenedDayEvent
import com.boostt1d.android.engine.WhatHappenedDayOverview
import com.boostt1d.android.engine.WhatHappenedWeeklyReport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cache is only worth persisting if it survives the trip intact. These encode the exact
 * payload the app writes and decode it back, so a field added to any of the nested types
 * without a schema bump fails here rather than silently on a user's cold launch.
 *
 * Ported from the iOS WhatHappenedAnalysisCachePersistenceTests.
 */
class WhatHappenedAnalysisCacheTest {

    @Test
    fun `a persisted snapshot round-trips through JSON with its findings intact`() {
        val snapshot = makeSnapshot()

        val data = Json.encodeToString(WhatHappenedAnalysisCache.Snapshot.serializer(), snapshot)
        val decoded = Json.decodeFromString(WhatHappenedAnalysisCache.Snapshot.serializer(), data)

        assertEquals(1_800, decoded.report.current.readingCount)
        assertEquals("6 complete days", decoded.coverageLabel)
        assertEquals(1, decoded.dailyDays.size)
        assertEquals(45.0, decoded.dailyDays.first().meals.first().carbs!!, 0.0)
        assertEquals(1, decoded.review.findings.size)
        assertEquals(TherapyParameter.BASAL, decoded.review.findings.first().parameter)
        assertEquals(TherapyEvidenceStrength.MODERATE, decoded.review.findings.first().strength)
        assertEquals(TherapyParameter.ISF, decoded.dailyTherapyReview.proposals.first().parameter)
        assertEquals(52.0, decoded.dailyTherapyReview.proposals.first().proposedValue!!, 0.0)
        assertNotNull(decoded.watchingSinceMillis)
    }

    /** The signature decides whether a restored bundle may be reused, so it has to come back byte-identical. */
    @Test
    fun `a signature round-trips and still compares equal`() {
        val signature = WhatHappenedAnalysisCache.Signature(
            windowStartMillis = 1_754_000_000_000, settledEntryCount = 1_720, latestSettledReadingMillis = 1_754_400_000_000,
            settledTreatmentCount = 63, lowGlucose = 70.0, highGlucose = 180.0, profileDigest = 918_273,
        )

        val data = Json.encodeToString(WhatHappenedAnalysisCache.Signature.serializer(), signature)
        assertEquals(signature, Json.decodeFromString(WhatHappenedAnalysisCache.Signature.serializer(), data))
    }

    @Test
    fun `a cold launch restores both the snapshot and the memoised bundle`() {
        val store = InMemoryAnalysisCacheStore()
        val signature = WhatHappenedAnalysisCache.Signature(1, 2, 3, 4, 70.0, 180.0, 5)

        val first = WhatHappenedAnalysisCache(store)
        val snapshot = makeSnapshot()
        first.store(WhatHappenedAnalysisCache.Bundle(snapshot.review, snapshot.outcomes), signature)
        // The bundle alone does not persist; the snapshot write is what makes the pair coherent.
        assertNull(store.contents)
        first.store(snapshot)
        assertNotNull(store.contents)

        val second = WhatHappenedAnalysisCache(store)
        assertEquals("6 complete days", second.snapshot?.coverageLabel)
        assertEquals(1, second.cached(signature)?.review?.findings?.size)
        assertNull(second.cached(signature.copy(settledEntryCount = 3)))

        second.invalidate()
        assertNull(store.contents)
        assertNull(WhatHappenedAnalysisCache(store).snapshot)
    }

    // MARK: - Fixture

    private fun makeSnapshot(): WhatHappenedAnalysisCache.Snapshot {
        val now = 1_754_400_000_000L
        val DAY = 86_400_000L

        val report = WhatHappenedWeeklyReport(
            current = GlucosePeriodMetrics(readingCount = 1_800, averageGlucoseMgdL = 154.0, timeInRangePercent = 68.0),
            currentPeriodStartMillis = now - 7 * DAY,
            currentPeriodEndMillis = now,
            plainLanguageSummary = "A steadier week than the last.",
        )

        val day = WhatHappenedDayOverview(
            id = "day-1", dayStartMillis = now, weekdayLabel = "Tuesday", dateLabel = "Aug 5",
            readingCount = 270, averageGlucoseMgdL = 149.0, minGlucoseMgdL = 68.0, maxGlucoseMgdL = 244.0,
            timeInRangePercent = 71.0, lowReadingCount = 3,
            meals = listOf(WhatHappenedDayEvent(id = "m1", timeMillis = now, title = "08:10", detail = "Porridge", carbs = 45.0, insulin = 4.5)),
            boluses = emptyList(), activity = emptyList(), notes = emptyList(), lowMoments = emptyList(),
        )

        val finding = TherapyFinding(
            parameter = TherapyParameter.BASAL, startHour = 0, endHour = 6, windowLabel = "12am–6am",
            title = "Overnight basal may be running high", headline = "Glucose drifts down through the night.",
            direction = TherapyDirection.DECREASE, priority = Priority.MEDIUM, strength = TherapyEvidenceStrength.MODERATE,
            currentValue = 0.85, observedValue = 0.72, suggestedValue = 0.78, percentChange = -8.0,
            sampleLabel = "14 fasting hours across 6 days", evidence = listOf("Median drift −22 mg/dL"),
            rationale = "A bounded half-step toward the observed response.", caveats = listOf("Two nights followed late exercise."),
            doctorQuestion = "Should my overnight basal come down?",
        )

        val review = TherapySettingsReview(
            periodDays = 7, hours = emptyList(), findings = listOf(finding), steadyNotes = listOf("Afternoon corrections looked right."),
            dataNotes = emptyList(), isClosedLoop = true, hasTherapySettings = true, cleanFastingHours = 14, cleanCorrections = 6,
            cleanMeals = 9, basalMethod = TherapyBasalMethod.LOOP_DELIVERY, loopComparedHours = 40, loopComparedDays = 6,
        )

        val proposal = DailyTherapyProposal(
            id = "isf-morning", parameter = TherapyParameter.ISF, timeWindow = "6am–12pm",
            title = "Morning corrections may be a little strong", summary = "Corrections before noon land below target.",
            currentValue = 45.0, proposedValue = 52.0, priority = Priority.MEDIUM, evidenceStrength = TherapyEvidenceStrength.MODERATE,
            sampleLabel = "6 clean corrections across 5 days", explanation = "Half-step toward the measured response.",
            evidence = listOf("Median overshoot −18 mg/dL"), contributingFactors = emptyList(),
            whatToVerify = listOf("Whether breakfast is pre-bolused"), caveats = emptyList(),
            careTeamQuestion = "Should my morning correction factor be weaker?",
            deliveryNote = "Automated delivery: only standalone corrections were measured.", wasAIReviewed = false,
        )

        val dailyReview = DailyTherapyReview(
            periodStartMillis = now - 7 * DAY, periodEndMillis = now, generatedAtMillis = now,
            source = DailyTherapyReviewSource.FORMULA_ONLY, deliveryModeLabel = "Automated delivery",
            overview = "1 setting window worth discussing this week.", proposals = listOf(proposal),
            observations = emptyList(), experiments = emptyList(), safetyNotes = emptyList(), statusNote = null,
        )

        return WhatHappenedAnalysisCache.Snapshot(
            report = report, patterns = emptyList(), coverageLabel = "6 complete days", dailyDays = listOf(day),
            review = review, outcomes = emptyList(), watchingSinceMillis = now - 20 * DAY, dailyTherapyReview = dailyReview,
        )
    }
}
