package com.boostt1d.android

import com.boostt1d.android.engine.AIDailyTherapyObservation
import com.boostt1d.android.engine.AIDailyTherapyRecommendation
import com.boostt1d.android.engine.AIDailyTherapyReview
import com.boostt1d.android.engine.DailyTherapyReviewService
import com.boostt1d.android.engine.DailyTherapyReviewSource
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.TherapyBasalMethod
import com.boostt1d.android.engine.TherapyDirection
import com.boostt1d.android.engine.TherapyEvidenceStrength
import com.boostt1d.android.engine.TherapyFinding
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.dailyReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the iOS DailyTherapyReviewServiceTests. */
class DailyTherapyReviewServiceTest {

    private val DAY = 86_400_000L

    private fun review(finding: TherapyFinding, cleanCorrections: Int = 0, cleanMeals: Int = 0) = TherapySettingsReview(
        periodDays = 7, hours = emptyList(), findings = listOf(finding), steadyNotes = emptyList(), dataNotes = emptyList(),
        isClosedLoop = false, hasTherapySettings = true, cleanFastingHours = 0, cleanCorrections = cleanCorrections,
        cleanMeals = cleanMeals, basalMethod = TherapyBasalMethod.NONE, loopComparedHours = 0, loopComparedDays = 0,
    )

    @Test
    fun `AI can explain and prioritize formula findings but cannot create a proposal`() {
        val finding = TherapyFinding(
            parameter = TherapyParameter.ISF, startHour = 6, endHour = 10, windowLabel = "06:00–10:00",
            title = "Morning corrections ran stronger than the profile predicts",
            headline = "The verified correction response differs from the programmed ISF.",
            direction = TherapyDirection.DECREASE, priority = Priority.HIGH, strength = TherapyEvidenceStrength.STRONG,
            currentValue = 50.0, observedValue = 40.0, suggestedValue = 45.0, percentChange = -10.0,
            sampleLabel = "9 standalone corrections across 6 days",
            evidence = listOf("Median correction response: 40 mg/dL/U"),
            rationale = "The formula takes a bounded half-step toward the observed response.",
            caveats = listOf("Exercise can strengthen a correction."),
            doctorQuestion = "Should the morning correction factor be reviewed?",
        )
        assertEquals("isf|6|10|decrease", finding.dailyReviewKey)

        val ai = AIDailyTherapyReview(
            overview = "Morning corrections are the clearest setting question this week.",
            recommendations = listOf(
                AIDailyTherapyRecommendation("invented|0|24|increase", "Invented recommendation", emptyList(), emptyList()),
                AIDailyTherapyRecommendation(finding.dailyReviewKey, "Set the factor to 35 mg/dL/U.", emptyList(), emptyList()),
                AIDailyTherapyRecommendation(
                    finding.dailyReviewKey,
                    "The correction examples repeat across six separate days.",
                    listOf("Repeated morning timing"),
                    listOf("Check whether exercise preceded these corrections"),
                ),
            ),
            observations = listOf(
                AIDailyTherapyObservation("Unsafe numeric instruction", "Increase basal to 0.8 U/hr.", emptyList(), "Glucose"),
            ),
            experiments = listOf("Test a basal change of 10%."),
            safetyNotes = emptyList(),
        )

        val result = DailyTherapyReviewService.assemble(
            formulaReview = review(finding, cleanCorrections = 9),
            treatments = emptyList(),
            periodStartMillis = 0L,
            periodEndMillis = 7 * DAY,
            ai = ai,
            generatedAtMillis = 7 * DAY,
            statusNote = null,
        )

        assertEquals(DailyTherapyReviewSource.FORMULA_AND_AI, result.source)
        assertEquals(1, result.proposals.size)
        val proposal = result.proposals.first()
        assertEquals(finding.dailyReviewKey, proposal.id)
        assertEquals(50.0, proposal.currentValue!!, 0.0)
        assertEquals(45.0, proposal.proposedValue!!, 0.0)
        assertTrue(proposal.explanation.contains("six separate days"))
        assertTrue(proposal.wasAIReviewed)
        assertTrue(result.observations.isEmpty())
        assertTrue(result.experiments.isEmpty())
    }

    @Test
    fun `formula-only fallback keeps every numeric finding`() {
        val finding = TherapyFinding(
            parameter = TherapyParameter.CARB_RATIO, startHour = 12, endHour = 16, windowLabel = "12:00–16:00",
            title = "Lunch meal response", headline = "Lunches rose above the target window.",
            direction = TherapyDirection.DECREASE, priority = Priority.MEDIUM, strength = TherapyEvidenceStrength.MODERATE,
            currentValue = 10.0, observedValue = 8.0, suggestedValue = 9.0, percentChange = -10.0,
            sampleLabel = "8 meals across 5 days", evidence = listOf("Median meal rise: 75 mg/dL"),
            rationale = "The formula uses a conservative bounded step.", caveats = emptyList(),
            doctorQuestion = "Should the lunch ratio be reviewed?",
        )

        val result = DailyTherapyReviewService.formulaReview(
            review = review(finding, cleanMeals = 8),
            treatments = emptyList(),
            periodStartMillis = 0L,
            periodEndMillis = 7 * DAY,
            nowMillis = 7 * DAY,
            statusNote = "AI unavailable",
        )

        assertEquals(DailyTherapyReviewSource.FORMULA_ONLY, result.source)
        assertEquals(listOf(9.0), result.proposals.map { it.proposedValue })
        assertEquals("AI unavailable", result.statusNote)
    }

    @Test
    fun `the narrative guard catches doses, ratios, percentages and setting verbs`() {
        val safe = DailyTherapyReviewService::isSafeAINarrative
        assertFalse(safe(listOf("Take 2 units before breakfast.")))
        assertFalse(safe(listOf("Run 0.8 U/hr overnight.")))
        assertFalse(safe(listOf("A ratio of 1:12 would suit lunch.")))
        assertFalse(safe(listOf("Lower the basal a touch.")))
        assertFalse(safe(listOf("Basal should be lower overnight.")))
        // The verb list is matched on whole words, so an inflected "lowered" is not caught — the
        // same gap iOS has. The response schema carries no dose fields; this guard is defence in
        // depth against numbers smuggled into prose, not a grammar of every possible instruction.
        assertTrue(safe(listOf("Your basal could be lowered.")))
        assertFalse(safe(listOf("Time in range fell 5%.")))
        assertTrue(safe(listOf("Mornings repeat across six days; check whether exercise preceded them.")))
        assertTrue(safe(listOf("The correction examples cluster before lunch.")))
    }
}
