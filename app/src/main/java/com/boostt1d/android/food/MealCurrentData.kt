package com.boostt1d.android.food

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.data.TimeValue
import java.util.Calendar
import java.util.TimeZone

/**
 * What the Snap a Meal screen shows next to an estimate: the inputs a dose would be built
 * from, read-only. The dose itself is withheld under HIDE_DOSE_RECOMMENDATIONS, so what iOS
 * calls "Current Data" is all of this screen's arithmetic that survives.
 *
 * Ported from the inputs half of FoodAnalysisView.calculateInsulin.
 */
data class MealCurrentData(
    val carbsGrams: Double,
    val currentGlucoseMgdl: Int?,
    val targetGlucoseMgdl: Int?,
    val carbRatio: Double?,
    val carbRatioTime: String?,
    val insulinSensitivity: Double?,
    val iob: Double,
    val cob: Double,
    /**
     * True when a therapy feed is configured but the last CGM reading is older than fifteen
     * minutes (or missing) — its IOB/COB cannot be trusted, so both read as unknown.
     */
    val iobDataStale: Boolean,
    /** Set when the profile is missing or has no carb ratio; the screen offers to enter one. */
    val setupMessage: String?,
) {
    val needsProfile: Boolean get() = setupMessage != null

    companion object {
        const val CONNECTION_STALE_MILLIS = 15 * 60_000L

        fun build(
            carbsGrams: Double,
            latest: NightscoutGlucoseEntry?,
            onBoard: OnBoard,
            therapy: TherapyProfile,
            hasTherapyFeed: Boolean,
            nowMillis: Long,
            timeZone: TimeZone = TimeZone.getDefault(),
        ): MealCurrentData {
            val glucoseIsStale = latest == null || nowMillis - latest.epochMilliseconds > CONNECTION_STALE_MILLIS
            val stale = hasTherapyFeed && glucoseIsStale
            val iob = if (stale) 0.0 else onBoard.insulinUnits ?: 0.0
            val cob = if (stale) 0.0 else onBoard.carbsGrams ?: 0.0

            val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
            val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            if (therapy.isEmpty) {
                return MealCurrentData(carbsGrams, latest?.sgv, null, null, null, null, iob, cob, stale, "No insulin doses found on this device")
            }
            val ratio = current(therapy.carbRatio, minutes)
                ?: return MealCurrentData(carbsGrams, latest?.sgv, null, null, null, null, iob, cob, stale, "Your insulin doses are missing a carb ratio")

            return MealCurrentData(
                carbsGrams = carbsGrams,
                currentGlucoseMgdl = latest?.sgv,
                targetGlucoseMgdl = therapy.targetHigh.firstOrNull()?.value?.toInt(),
                carbRatio = ratio.value,
                carbRatioTime = ratio.timeFormatted,
                insulinSensitivity = therapy.sensitivity.firstOrNull()?.value,
                iob = iob,
                cob = cob,
                iobDataStale = stale,
                setupMessage = null,
            )
        }

        /** The scheduled value covering the current minute of the day; before the first boundary the last segment applies. */
        private fun current(schedule: List<TimeValue>, minutesSinceMidnight: Int): TimeValue? {
            val sorted = schedule.mapNotNull { tv -> minutesOf(tv.time)?.let { it to tv } }.sortedBy { it.first }
            if (sorted.isEmpty()) return null
            return sorted.lastOrNull { it.first <= minutesSinceMidnight }?.second ?: sorted.first().second
        }

        private fun minutesOf(time: String): Int? {
            val parts = time.split(":").mapNotNull { it.trim().toIntOrNull() }
            return if (parts.size >= 2) parts[0] * 60 + parts[1] else null
        }
    }
}
