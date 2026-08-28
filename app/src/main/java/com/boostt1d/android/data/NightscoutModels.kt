package com.boostt1d.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The wire vocabulary, ported from the iOS NightscoutModels.
 *
 * Nothing syncs yet, but these are the types every later phase speaks. Manual entries
 * are built as the same shapes a Nightscout download would produce, so when the sync
 * services land there is one representation of a reading, not two.
 */

/** One glucose reading. `sgv` is always mg/dL, whatever the user reads. */
@Serializable
data class NightscoutGlucoseEntry(
    @Serializable(with = FlexibleInt::class)
    val sgv: Int,
    val direction: String? = null,
    @Serializable(with = FlexibleLongOrNull::class)
    val date: Long? = null,
    /** Some Nightscout responses carry the timestamp here instead of in `date`. */
    @Serializable(with = FlexibleLongOrNull::class)
    val mills: Long? = null,
    val device: String? = null,
    @Serializable(with = FlexibleIntOrNull::class)
    val noise: Int? = null,
) {
    /**
     * Normalizes the timestamp whether the source stored seconds or milliseconds.
     *
     * The cutoff is the same one iOS uses: any value below 10^12 is too small to be
     * milliseconds since 1970 and is therefore seconds.
     */
    val epochMilliseconds: Long
        get() {
            val raw = date ?: mills ?: 0L
            return if (raw < 1_000_000_000_000L) raw * 1000 else raw
        }

    val recordedAtMillis: Long get() = epochMilliseconds
}

/** Insulin, carbs, and everything else that is an event rather than a reading. */
@Serializable
data class NightscoutTreatment(
    @SerialName("_id") val mongoId: String? = null,
    val id: String? = null,
    val eventType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val timestamp: String? = null,
    @Serializable(with = FlexibleLongOrNull::class)
    val mills: Long? = null,
    val enteredBy: String? = null,
    @Serializable(with = FlexibleDoubleOrNull::class)
    val insulin: Double? = null,
    @Serializable(with = FlexibleDoubleOrNull::class)
    val carbs: Double? = null,
    val notes: String? = null,
    @Serializable(with = FlexibleStringOrNull::class)
    val glucose: String? = null,
    val glucoseType: String? = null,
    val units: String? = null,
    @Serializable(with = FlexibleIntOrNull::class)
    val duration: Int? = null,
    @Serializable(with = FlexibleDoubleOrNull::class)
    val rate: Double? = null,
    @Serializable(with = FlexibleDoubleOrNull::class)
    val absolute: Double? = null,
    @Serializable(with = FlexibleIntOrNull::class)
    val utcOffset: Int? = null,
    /** Set by oref-lineage loops (Trio, AAPS, iAPS, OpenAPS) on a Super Micro Bolus. */
    val isSMB: Boolean? = null,
    /**
     * Set by Loop and AndroidAPS on insulin the algorithm delivered without user action.
     * With [isSMB], this is what separates an algorithm's decision from a person's.
     */
    val automatic: Boolean? = null,
) {
    /**
     * True only when the uploading app explicitly said so.
     *
     * Deliberately conservative: an unflagged bolus is never assumed automatic, because
     * mislabelling someone's own dose as the algorithm's is the worse error.
     */
    val isAlgorithmDelivered: Boolean get() = isSMB == true || automatic == true

    /**
     * Stable key for merging on-device treatments. Prefers Nightscout's `_id`, then `id`,
     * then a content fingerprint so rows carrying neither still dedupe.
     */
    val cacheKey: String
        get() {
            mongoId?.trim()?.takeIf { it.isNotEmpty() }?.let { return "ns:$it" }
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { return "id:$it" }
            val insulinPart = insulin?.let { String.format(Locale.US, "%.4f", it) } ?: ""
            val carbsPart = carbs?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            val ratePart = absolute?.let { String.format(Locale.US, "%.4f", it) }
                ?: rate?.let { String.format(Locale.US, "%.4f", it) }
                ?: ""
            return "f:${mills ?: 0}|${eventType ?: ""}|$insulinPart|$carbsPart|$ratePart|${duration ?: 0}"
        }

    /** Carbs that belong in the Food Log rather than the Event Log. */
    val recordsCarbsForFoodLog: Boolean get() = (carbs ?: 0.0) > 0

    /** Carb-only rows leave the Event Log once imported; insulin, temp basal and exercise stay. */
    val isCarbOnlyEventLogRow: Boolean
        get() = recordsCarbsForFoodLog &&
            (insulin ?: 0.0) <= 0 &&
            eventType != EventType.TEMP_BASAL &&
            eventType != EventType.EXERCISE

    val recordedAtMillis: Long get() = mills ?: 0L
}

/** The `eventType` strings Nightscout uses, so they are spelled once. */
object EventType {
    const val BOLUS = "Bolus"
    const val CORRECTION_BOLUS = "Correction Bolus"
    const val MEAL_BOLUS = "Meal Bolus"
    const val CARB_CORRECTION = "Carb Correction"
    const val TEMP_BASAL = "Temp Basal"
    const val EXERCISE = "Exercise"
    const val NOTE = "Note"
    const val BG_CHECK = "BG Check"
    const val SITE_CHANGE = "Site Change"
    const val SENSOR_START = "Sensor Start"

    /** Offered when logging by hand, in the order a person is likely to want them. */
    val manualOptions = listOf(
        MEAL_BOLUS, CORRECTION_BOLUS, BOLUS, CARB_CORRECTION,
        EXERCISE, SITE_CHANGE, SENSOR_START, NOTE,
    )
}

/**
 * One entry in a time-based therapy schedule.
 *
 * Nightscout stores schedules as start times only — a segment runs until the next one
 * begins, and the last wraps past midnight. See [InsulinDoseSchedule] for the arithmetic
 * that turns these into readable spans.
 */
@Serializable
data class TimeValue(
    val time: String,
    val value: Double,
) {
    val timeFormatted: String
        get() {
            val parts = time.split(":")
            if (parts.size < 2) return time
            val hour = parts[0].toIntOrNull() ?: 0
            val minute = parts[1].toIntOrNull() ?: 0
            return String.format(Locale.US, "%02d:%02d", hour, minute)
        }
}

/**
 * The therapy settings the app reasons about: basal rates, carb ratios, correction
 * factors and targets.
 *
 * On iOS this arrives inside a Nightscout `profile.json` document. In a manual build the
 * user types it in, so this is the same shape with the Nightscout envelope removed —
 * when sync lands, the downloaded profile maps onto this rather than replacing it.
 */
@Serializable
data class TherapyProfile(
    val basal: List<TimeValue> = emptyList(),
    val carbRatio: List<TimeValue> = emptyList(),
    val sensitivity: List<TimeValue> = emptyList(),
    val targetLow: List<TimeValue> = emptyList(),
    val targetHigh: List<TimeValue> = emptyList(),
    /** Duration of insulin action, hours. Nightscout's `dia`. */
    val dia: Double? = null,
    /** Where these values came from, so the UI can say whether they were typed or synced. */
    val source: Source = Source.MANUAL,
    val updatedAtMillis: Long = 0L,
) {
    @Serializable
    enum class Source { MANUAL, NIGHTSCOUT }

    val isEmpty: Boolean
        get() = basal.isEmpty() && carbRatio.isEmpty() && sensitivity.isEmpty()

    /** The scheduled value covering [minutesSinceMidnight], or null when unscheduled. */
    fun valueAt(schedule: List<TimeValue>, minutesSinceMidnight: Int): Double? {
        val parsed = schedule
            .mapNotNull { entry ->
                InsulinDoseSchedule.minutes(entry.time)?.let { it to entry.value }
            }
            .sortedBy { it.first }
        if (parsed.isEmpty()) return null
        // Before the first boundary belongs to the last segment, which wrapped midnight.
        return parsed.lastOrNull { it.first <= minutesSinceMidnight }?.second
            ?: parsed.last().second
    }
}
