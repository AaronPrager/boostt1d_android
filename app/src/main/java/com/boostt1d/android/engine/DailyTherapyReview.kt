package com.boostt1d.android.engine

import kotlinx.serialization.Serializable

/** Where the daily seven-day therapy review came from. */
@Serializable
enum class DailyTherapyReviewSource(val displayName: String) {
    FORMULA_ONLY("Formula only"),
    FORMULA_AND_AI("Formula + daily AI review"),
}

/**
 * One dose-setting proposal whose numbers were produced and bounded by the local formula
 * engine. AI may prioritize and explain it, but it never supplies [currentValue] or
 * [proposedValue].
 */
@Serializable
data class DailyTherapyProposal(
    val id: String,
    val parameter: TherapyParameter,
    val timeWindow: String,
    val title: String,
    val summary: String,
    val currentValue: Double?,
    val proposedValue: Double?,
    /**
     * What the week actually measured, before the step and the cap.
     *
     * Kept separate from [proposedValue] because they are rarely the same number: a loop
     * running 48% above a 1.10 U/hr profile measures 1.63, and the suggestion is a bounded
     * step toward that, not a jump to it. Without this the two tiles on screen look like
     * arithmetic that does not work.
     */
    val observedValue: Double? = null,
    val priority: Priority,
    val evidenceStrength: TherapyEvidenceStrength,
    val sampleLabel: String,
    val explanation: String,
    val evidence: List<String>,
    val contributingFactors: List<String>,
    val whatToVerify: List<String>,
    val caveats: List<String>,
    val careTeamQuestion: String,
    val deliveryNote: String,
    val wasAIReviewed: Boolean,
)

@Serializable
data class DailyTherapyObservation(
    val id: String,
    val title: String,
    val observation: String,
    val supportingEvidence: List<String>,
    val whatToTrack: String,
)

/**
 * The presentation-ready result. It is rebuilt from the current formula findings every time
 * the report opens, even when the AI wording came from today's cache. This prevents a cached
 * model response from carrying old setting values onto the screen.
 */
@Serializable
data class DailyTherapyReview(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val generatedAtMillis: Long,
    val source: DailyTherapyReviewSource,
    val deliveryModeLabel: String,
    val overview: String,
    val proposals: List<DailyTherapyProposal>,
    val observations: List<DailyTherapyObservation>,
    val experiments: List<String>,
    val safetyNotes: List<String>,
    val statusNote: String?,
)

// MARK: - AI response (wording and prioritization only)

/**
 * Strict response returned by the once-daily AI review. There are deliberately no dose fields
 * here. A recommendation can only reference a formula finding by its stable key.
 */
@Serializable
data class AIDailyTherapyReview(
    val overview: String,
    val recommendations: List<AIDailyTherapyRecommendation>,
    val observations: List<AIDailyTherapyObservation>,
    val experiments: List<String>,
    val safetyNotes: List<String>,
)

@Serializable
data class AIDailyTherapyRecommendation(
    val findingKey: String,
    val explanation: String,
    val contributingFactors: List<String>,
    val whatToVerify: List<String>,
)

@Serializable
data class AIDailyTherapyObservation(
    val title: String,
    val observation: String,
    val supportingEvidence: List<String>,
    val whatToTrack: String,
)

/** Stable across rebuilds of the same finding, unlike the view-only UUID. */
val TherapyFinding.dailyReviewKey: String
    get() {
        val directionKey = when (direction) {
            TherapyDirection.INCREASE -> "increase"
            TherapyDirection.DECREASE -> "decrease"
            TherapyDirection.HOLD -> "hold"
        }
        return "${parameter.key}|$startHour|$endHour|$directionKey"
    }
