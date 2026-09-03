package com.boostt1d.android.engine

import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

/**
 * The three tunable schedules. Shared by the change detector and the settings review, which
 * is why it lives on its own rather than inside either.
 */
@Serializable
enum class TherapyParameter(val displayName: String, val shortName: String) {
    BASAL("Basal rate", "Basal"),
    ISF("Correction factor (ISF)", "ISF"),
    CARB_RATIO("Carb ratio (ICR)", "Carb ratio"),
}

@Serializable
enum class TherapyDirection {
    /** The setting's number should go up — more basal, weaker corrections, weaker meal ratio. */
    INCREASE,
    /** The setting's number should go down. */
    DECREASE,
    HOLD,
}

/** One value from a therapy schedule, applying from [startHour] until the next segment. */
@Serializable
data class TherapySegmentValue(
    /** Fractional: 06:30 is 6.5. */
    val startHour: Double,
    val value: Double,
)

/**
 * The three tunable schedules as they stood at one moment.
 *
 * Stored rather than derived, because the whole point is to know what the settings *used to
 * be* — information that no longer exists anywhere once the profile is edited.
 */
@Serializable
data class TherapySettingsSnapshot(
    /**
     * When this schedule began applying. Taken from the profile document when it declares a
     * start, otherwise the moment the app first saw these values.
     */
    val effectiveAtMillis: Long,
    val basal: List<TherapySegmentValue>,
    val isf: List<TherapySegmentValue>,
    val carbRatio: List<TherapySegmentValue>,
) {
    val isEmpty: Boolean get() = basal.isEmpty() && isf.isEmpty() && carbRatio.isEmpty()

    fun segments(parameter: TherapyParameter): List<TherapySegmentValue> = when (parameter) {
        TherapyParameter.BASAL -> basal
        TherapyParameter.ISF -> isf
        TherapyParameter.CARB_RATIO -> carbRatio
    }

    /** Value in effect at a clock hour, wrapping past midnight the way a pump schedule does. */
    fun value(parameter: TherapyParameter, hour: Int): Double? =
        TherapyProfileSettings.valueIn(segments(parameter), hour)

    /** True when the two snapshots describe the same therapy, ignoring when they were seen. */
    fun hasSameSettings(other: TherapySettingsSnapshot): Boolean =
        basal == other.basal && isf == other.isf && carbRatio == other.carbRatio
}

/**
 * One edit to one setting, over one run of hours.
 *
 * Adjacent hours that moved the same way collapse into a single change, because that is how
 * the edit was made — nobody changes 03:00 and 04:00 as two separate decisions.
 */
@Serializable
data class TherapyChange(
    /** Stable across rebuilds so the UI does not churn between refreshes. */
    val id: String,
    val parameter: TherapyParameter,
    /** When the new value started applying. */
    val changedAtMillis: Long,
    val startHour: Int,
    /** Exclusive. Wraps past midnight when `endHour <= startHour`. */
    val endHour: Int,
    val windowLabel: String,
    val previousValue: Double,
    val newValue: Double,
) {
    val direction: TherapyDirection
        get() = when {
            newValue > previousValue -> TherapyDirection.INCREASE
            newValue < previousValue -> TherapyDirection.DECREASE
            else -> TherapyDirection.HOLD
        }

    /** Signed, relative to the previous value. */
    val percentChange: Double
        get() = if (previousValue == 0.0) 0.0 else (newValue - previousValue) / previousValue * 100

    fun contains(hour: Int): Boolean =
        if (startHour < endHour) hour >= startHour && hour < endHour
        else hour >= startHour || hour < endHour

    /** "0.85 → 0.95 U/hr". Takes the unit rather than reading a singleton, so the engine stays pure. */
    fun valueLabel(unit: BGUnit): String =
        "${format(previousValue, parameter, unit)} → ${format(newValue, parameter, unit)} ${unitLabel(unit)}"

    fun unitLabel(unit: BGUnit): String = when (parameter) {
        TherapyParameter.BASAL -> "U/hr"
        TherapyParameter.ISF -> "${unit.displayName}/U"
        TherapyParameter.CARB_RATIO -> "g/U"
    }

    /**
     * What the edit was trying to do, in plain language. ISF and carb ratio move the opposite
     * way to the insulin they produce — a bigger number means less insulin — so "increase"
     * alone would read backwards to most people.
     */
    val intentLabel: String
        get() = when (parameter to direction) {
            TherapyParameter.BASAL to TherapyDirection.INCREASE -> "more background insulin"
            TherapyParameter.BASAL to TherapyDirection.DECREASE -> "less background insulin"
            TherapyParameter.ISF to TherapyDirection.INCREASE -> "smaller corrections"
            TherapyParameter.ISF to TherapyDirection.DECREASE -> "larger corrections"
            TherapyParameter.CARB_RATIO to TherapyDirection.INCREASE -> "less insulin per carb"
            TherapyParameter.CARB_RATIO to TherapyDirection.DECREASE -> "more insulin per carb"
            else -> "no change"
        }

    /** True when the edit was expected to pull glucose down in this window. */
    val expectsLowerGlucose: Boolean
        get() = when (parameter to direction) {
            TherapyParameter.BASAL to TherapyDirection.INCREASE,
            TherapyParameter.ISF to TherapyDirection.DECREASE,
            TherapyParameter.CARB_RATIO to TherapyDirection.DECREASE -> true
            else -> false
        }

    private fun format(value: Double, parameter: TherapyParameter, unit: BGUnit): String = when (parameter) {
        TherapyParameter.BASAL -> String.format(Locale.US, "%.2f", value)
        TherapyParameter.ISF -> GlucoseDisplay.format(value, unit)
        TherapyParameter.CARB_RATIO ->
            if (abs(value - Math.rint(value)) < 1e-9) String.format(Locale.US, "%.0f", value)
            else String.format(Locale.US, "%.1f", value)
    }
}
