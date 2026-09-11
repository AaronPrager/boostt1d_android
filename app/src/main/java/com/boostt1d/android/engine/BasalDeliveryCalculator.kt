package com.boostt1d.android.engine

import com.boostt1d.android.data.EventType
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutTreatment
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Basal insulin actually delivered over a stretch of time.
 *
 * Nightscout records what the pump was told to do, not a running total of units, so basal
 * has to be reconstructed: temp basals time-weighted over whatever part of an hour they
 * cover, the profile rate filling the rest. Both the day cards in What Happened? and the
 * doctor visit report need the same number, and a TDD that disagrees between two screens is
 * worse than no TDD at all, so the arithmetic lives here once.
 *
 * Ported from the iOS BasalDeliveryCalculator.
 */
object BasalDeliveryCalculator {

    private const val HOUR_MILLIS = 3_600_000L

    /**
     * Temp basals as absolute-rate intervals, parsed once for a whole window.
     *
     * Thin wrapper so callers do not have to know the review builder owns the field-by-field
     * handling of `absolute` versus `rate` versus percent-of-profile.
     */
    fun intervals(
        treatments: List<NightscoutTreatment>,
        settings: TherapyProfileSettings,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<TherapySettingsReviewBuilder.TempBasalInterval> =
        TherapySettingsReviewBuilder.tempBasalIntervals(treatments, settings, timeZone)

    /**
     * Whether the profile's basal schedule describes insulin the patient is really getting.
     *
     * On injections the long-acting dose is logged as a treatment and is already inside the
     * bolus total, so laying a schedule on top would count the same insulin twice. When the
     * user has not said how they deliver, a temp basal record anywhere in the window is the
     * evidence of a pump.
     */
    fun countsScheduledBasal(
        treatments: List<NightscoutTreatment>,
        therapyType: InsulinTherapyType,
        settings: TherapyProfileSettings,
    ): Boolean {
        if (settings.basal.isEmpty()) return false
        return when (therapyType) {
            InsulinTherapyType.CLOSED_LOOP, InsulinTherapyType.PUMP -> true
            InsulinTherapyType.INJECTIONS -> false
            InsulinTherapyType.UNSPECIFIED -> treatments.any { it.eventType == EventType.TEMP_BASAL }
        }
    }

    /**
     * Basal units delivered between two instants.
     *
     * Walked hour by hour because the profile rate changes on the clock. A pump suspended by
     * a zero-rate temp basal contributes nothing for as long as it runs, which is the point
     * of counting delivery rather than assuming the schedule was followed.
     *
     * Returns null when the profile has no rate for an hour, since a partly counted day is
     * worse than an honest blank.
     */
    fun units(
        fromMillis: Long,
        toMillis: Long,
        intervals: List<TherapySettingsReviewBuilder.TempBasalInterval>,
        settings: TherapyProfileSettings,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Double? {
        val calendar = Calendar.getInstance(timeZone)
        var units = 0.0
        var hourStart = fromMillis

        while (hourStart < toMillis) {
            calendar.timeInMillis = hourStart
            val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            val hourEnd = min(calendar.timeInMillis, toMillis)
            val span = hourEnd - hourStart
            if (span <= 0) break

            val profileRate = settings.basalAt(hourOfDay) ?: return null

            var covered = 0L
            for (interval in intervals) {
                if (interval.end <= hourStart || interval.start >= hourEnd) continue
                val overlap = min(interval.end, hourEnd) - max(interval.start, hourStart)
                if (overlap <= 0) continue
                units += interval.rate * overlap / HOUR_MILLIS
                covered += overlap
            }
            units += profileRate * max(span - covered, 0L) / HOUR_MILLIS

            hourStart = hourEnd
        }

        return units
    }
}
