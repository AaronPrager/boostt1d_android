package com.boostt1d.android.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/** How much the numbers behind a finding can carry. */
@Serializable
enum class TherapyEvidenceStrength(val label: String) {
    STRONG("Strong"),
    MODERATE("Moderate"),
    LIMITED("Limited");

    /**
     * "Limited" sounds like a verdict on the user. It is a statement about how many days the
     * app got to look at, so it says that instead.
     */
    val plainLabel: String
        get() = when (this) {
            STRONG -> "Seen on most days"
            MODERATE -> "Seen on several days"
            LIMITED -> "Seen on only a few days"
        }
}

/** One clock hour, aggregated across every day in the window. */
@Serializable
data class TherapyHourStat(
    val hour: Int,
    val averageGlucose: Double?,
    val timeInRange: Double,
    val timeAbove: Double,
    val timeBelow: Double,
    val readingCount: Int,
    val dayCount: Int,
    /**
     * Median mg/dL change per hour measured only in windows with no food and no bolus insulin
     * acting. Null when there were too few such windows to say anything.
     */
    val fastingDrift: Double?,
    val fastingWindowCount: Int,
    /** Share of fasting readings below the low target, in percent. */
    val fastingBelowPercent: Double,
    /**
     * Median delivered-to-profile ratio for this hour, set only when basal was read from
     * delivery. Null on the fasting path.
     *
     * Without this the hourly strip had one vocabulary — fasting windows — and used it whatever
     * the review had actually measured, so a loop user tapping 03:00 was told "only 1 fasting
     * hour here, too few to read basal from" underneath a basal finding drawn from seven days
     * of delivery data.
     */
    val deliveredRatio: Double? = null,
    val deliveredDayCount: Int = 0,
    /**
     * Days excluded from the delivery comparison at this hour because a meal was acting.
     *
     * Counted so a thin hour can say *why* it is thin. "Only 1 day to compare" reads as missing
     * data; "the other 6 were within 4h of a meal" is a design decision the user can see, and
     * one they can act on by shifting when they eat or log.
     */
    val mealShadowedDays: Int = 0,
    /** Days excluded because logged activity could still be changing glucose or sensitivity. */
    val activityShadowedDays: Int = 0,
) {
    val id: Int get() = hour
}

/** A single settings observation for one window of the day. */
@Serializable
data class TherapyFinding(
    val parameter: TherapyParameter,
    val startHour: Int,
    /** Exclusive. Wraps past midnight when `endHour <= startHour`. */
    val endHour: Int,
    val windowLabel: String,
    val title: String,
    /** One-line clinical read of the window. */
    val headline: String,
    val direction: TherapyDirection,
    val priority: Priority,
    val strength: TherapyEvidenceStrength,
    /**
     * The value configured in the therapy profile, in engine units (U/hr, mg/dL per unit,
     * g per unit). Null when no profile is available.
     */
    val currentValue: Double?,
    /** What the data measured for the same quantity. */
    val observedValue: Double?,
    /**
     * Conservative half-step toward the observed value, clamped. Null when there is no
     * configured value to anchor to.
     */
    val suggestedValue: Double?,
    val percentChange: Double?,
    /** "14 fasting hours across 6 days" — how much this rests on. */
    val sampleLabel: String,
    val evidence: List<String>,
    val rationale: String,
    val caveats: List<String>,
    val doctorQuestion: String,
) {
    /** Fresh per instance and meaningless outside a single render pass; never persisted. */
    @Transient
    val id: String = UUID.randomUUID().toString()
}

/**
 * How the basal read was produced.
 *
 * The two paths measure different quantities from different evidence, and the screen must
 * never describe one in the other's vocabulary — "measured from 24 fasting hours" under a
 * finding that came from temp basals is simply false.
 */
@Serializable
enum class TherapyBasalMethod {
    /** Fasting drift: hours with no food and no bolus acting. Open-loop therapy. */
    FASTING_DRIFT,
    /** The loop's own delivery against the profile it was given. */
    LOOP_DELIVERY,
    /** Nothing could be read. */
    NONE,
}

/** The full hour-by-hour therapy settings review for one analysis period. */
@Serializable
data class TherapySettingsReview(
    val periodDays: Int,
    /** 24 entries, hour 0 through 23. Hours with no data still appear, with zero readings. */
    val hours: List<TherapyHourStat>,
    val findings: List<TherapyFinding>,
    /** Windows that were evaluated and looked right — worth saying so explicitly. */
    val steadyNotes: List<String>,
    /** Why something could not be evaluated. */
    val dataNotes: List<String>,
    val isClosedLoop: Boolean,
    val hasTherapySettings: Boolean,
    val cleanFastingHours: Int,
    val cleanCorrections: Int,
    val cleanMeals: Int,
    val basalMethod: TherapyBasalMethod,
    /** Hour-days of loop delivery compared against the profile. Zero unless [basalMethod] is LOOP_DELIVERY. */
    val loopComparedHours: Int,
    val loopComparedDays: Int,
) {
    /** True when there is an hourly glucose profile worth drawing. */
    val hasHourlyProfile: Boolean get() = hours.any { it.readingCount >= 3 }

    /** True when the review says anything at all beyond the hourly strip. */
    val hasReviewContent: Boolean get() = findings.isNotEmpty() || steadyNotes.isNotEmpty() || dataNotes.isNotEmpty()

    companion object {
        val empty = TherapySettingsReview(
            periodDays = 0, hours = emptyList(), findings = emptyList(), steadyNotes = emptyList(),
            dataNotes = emptyList(), isClosedLoop = false, hasTherapySettings = false,
            cleanFastingHours = 0, cleanCorrections = 0, cleanMeals = 0,
            basalMethod = TherapyBasalMethod.NONE, loopComparedHours = 0, loopComparedDays = 0,
        )
    }
}
