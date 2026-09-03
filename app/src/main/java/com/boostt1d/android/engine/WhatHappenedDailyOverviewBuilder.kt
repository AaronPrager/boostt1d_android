package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.min

/** One day in the What Happened? daily overview timeline. */
@Serializable
data class WhatHappenedDayOverview(
    val id: String,
    val dayStartMillis: Long,
    val weekdayLabel: String,
    val dateLabel: String,

    val readingCount: Int,
    val averageGlucoseMgdL: Double?,
    val minGlucoseMgdL: Double?,
    val maxGlucoseMgdL: Double?,
    val timeInRangePercent: Double?,
    val lowReadingCount: Int,

    val meals: List<WhatHappenedDayEvent>,
    val boluses: List<WhatHappenedDayEvent>,
    /**
     * Nightscout events typed "Exercise".
     *
     * Not only exercise: that event type is what most uploaders and users reach for to record
     * anything that is neither food nor insulin, so the notes on it are as likely to read
     * "Dance" or "Sick" as "Run". The bucket is honest — the old name was not.
     */
    val activity: List<WhatHappenedDayEvent>,
    val notes: List<WhatHappenedDayEvent>,
    val lowMoments: List<WhatHappenedDayEvent>,
) {
    val totalCarbs: Double get() = meals.mapNotNull { it.carbs }.sum()
    val totalInsulin: Double get() = meals.mapNotNull { it.insulin }.sum() + boluses.mapNotNull { it.insulin }.sum()
    val hasAnyActivity: Boolean
        get() = readingCount > 0 || meals.isNotEmpty() || boluses.isNotEmpty() ||
            activity.isNotEmpty() || notes.isNotEmpty() || lowMoments.isNotEmpty()
}

@Serializable
data class WhatHappenedDayEvent(
    val id: String,
    val timeMillis: Long,
    val title: String,
    val detail: String?,
    val carbs: Double?,
    val insulin: Double?,
)

/**
 * The Days tab: a day-by-day timeline of readings, doses, meals and events.
 *
 * Ported 1:1 from the iOS WhatHappenedDailyOverviewBuilder.
 */
object WhatHappenedDailyOverviewBuilder {

    /** Exactly seven calendar days ending on the report's end day, newest first. */
    fun build(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowMgdL: Double,
        highMgdL: Double,
        weekEndMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<WhatHappenedDayOverview> {
        val locale = Locale.getDefault()
        val weekdayFormatter = SimpleDateFormat("EEE", locale).apply { this.timeZone = timeZone }
        val dateFormatter = SimpleDateFormat("MMM d", locale).apply { this.timeZone = timeZone }
        val timeFormatter = SimpleDateFormat("h:mm a", locale).apply { this.timeZone = timeZone }

        val calendar = Calendar.getInstance(timeZone)
        val endDay = TodaySoFarBuilder.startOfDay(weekEndMillis, timeZone)
        val startDay = calendar.run { timeInMillis = endDay; add(Calendar.DAY_OF_MONTH, -6); timeInMillis }

        val days = mutableListOf<WhatHappenedDayOverview>()
        var cursor = startDay

        while (cursor <= endDay) {
            val next = calendar.run { timeInMillis = cursor; add(Calendar.DAY_OF_MONTH, 1); timeInMillis }
            val dayEntries = entries
                .filter { it.epochMilliseconds >= cursor && it.epochMilliseconds < next }
                .sortedBy { it.epochMilliseconds }
            val dayTreatments = treatments
                .filter { val at = MealOutcomeBuilder.treatmentMillis(it); at >= cursor && at < next }
                .sortedBy { MealOutcomeBuilder.treatmentMillis(it) }

            val values = dayEntries.map { it.sgv.toDouble() }
            val average = if (values.isEmpty()) null else values.sum() / values.size
            val minG = values.minOrNull()
            val maxG = values.maxOrNull()
            val inRange = if (values.isEmpty()) null else values.count { it >= lowMgdL && it <= highMgdL }.toDouble() / values.size * 100
            val lowCount = values.count { it < lowMgdL }

            val meals = dayTreatments.mapNotNull { treatment ->
                val carbs = treatment.carbs?.takeIf { it > 0 } ?: return@mapNotNull null
                val at = MealOutcomeBuilder.treatmentMillis(treatment)
                val detailParts = mutableListOf("${formatOneDecimal(carbs)}g carbs")
                treatment.insulin?.takeIf { it > 0 }?.let { detailParts += "${formatOneDecimal(it)}u insulin" }
                treatment.notes?.takeIf { it.isNotEmpty() }?.let { detailParts += it }
                WhatHappenedDayEvent(
                    id = UUID.randomUUID().toString(),
                    timeMillis = at,
                    title = timeFormatter.format(Date(at)),
                    detail = detailParts.joinToString(" · "),
                    carbs = carbs,
                    insulin = treatment.insulin,
                )
            }

            val boluses = dayTreatments.mapNotNull { treatment ->
                val insulin = treatment.insulin?.takeIf { it > 0 } ?: return@mapNotNull null
                // Skip meal boluses already listed with carbs to reduce duplication, but
                // still count pure corrections / insulin-only.
                if ((treatment.carbs ?: 0.0) > 0) return@mapNotNull null
                val at = MealOutcomeBuilder.treatmentMillis(treatment)
                val note = treatment.notes?.trim()?.takeIf { it.isNotEmpty() }
                WhatHappenedDayEvent(
                    id = UUID.randomUUID().toString(),
                    timeMillis = at,
                    title = timeFormatter.format(Date(at)),
                    detail = listOfNotNull("${formatOneDecimal(insulin)}u", note).joinToString(" · "),
                    carbs = null,
                    insulin = insulin,
                )
            }

            val activity = dayTreatments.mapNotNull { treatment ->
                if (treatment.eventType != "Exercise") return@mapNotNull null
                val at = MealOutcomeBuilder.treatmentMillis(treatment)
                val note = treatment.notes?.trim()?.takeIf { it.isNotEmpty() }
                val duration = treatment.duration?.let { durationLabel(it) }
                WhatHappenedDayEvent(
                    id = UUID.randomUUID().toString(),
                    timeMillis = at,
                    title = timeFormatter.format(Date(at)),
                    detail = listOfNotNull(duration, note ?: "Activity").joinToString(" · "),
                    carbs = null,
                    insulin = null,
                )
            }

            val notes = dayTreatments.mapNotNull { treatment ->
                val note = treatment.notes?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // Avoid duplicating notes already shown in the activity lane.
                if (treatment.eventType == "Exercise") return@mapNotNull null
                // Meal/bolus notes already appear in those rows; keep standalone note-ish events.
                val hasMealOrBolus = (treatment.carbs ?: 0.0) > 0 || (treatment.insulin ?: 0.0) > 0
                if (hasMealOrBolus) return@mapNotNull null
                val at = MealOutcomeBuilder.treatmentMillis(treatment)
                WhatHappenedDayEvent(
                    id = UUID.randomUUID().toString(),
                    timeMillis = at,
                    title = timeFormatter.format(Date(at)),
                    detail = note,
                    carbs = null,
                    insulin = null,
                )
            }

            // Compact low markers: one per contiguous stretch, labeled with start time + min.
            val lowMoments = compactLowMoments(dayEntries, lowMgdL, timeFormatter)

            days += WhatHappenedDayOverview(
                id = UUID.randomUUID().toString(),
                dayStartMillis = cursor,
                weekdayLabel = weekdayFormatter.format(Date(cursor)),
                dateLabel = dateFormatter.format(Date(cursor)),
                readingCount = dayEntries.size,
                averageGlucoseMgdL = average,
                minGlucoseMgdL = minG,
                maxGlucoseMgdL = maxG,
                timeInRangePercent = inRange,
                lowReadingCount = lowCount,
                meals = meals,
                boluses = boluses,
                activity = activity,
                notes = notes,
                lowMoments = lowMoments,
            )

            cursor = next
        }

        // Newest day first for a familiar feed-style timeline.
        return days.reversed()
    }

    /**
     * Durations as a person would say them.
     *
     * Nightscout carries these in minutes, and an override left running until it is
     * cancelled arrives as 43200 — thirty days. Printed raw, that is a number the reader has
     * to do arithmetic on before it means anything.
     */
    fun durationLabel(minutes: Int): String {
        if (minutes < 60) return "$minutes min"

        if (minutes < 1440) {
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest > 0) "${hours}h ${rest}m" else "${hours}h"
        }

        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val dayPart = if (days == 1) "1 day" else "$days days"
        return if (hours > 0) "$dayPart ${hours}h" else dayPart
    }

    private fun compactLowMoments(
        entries: List<NightscoutGlucoseEntry>,
        lowMgdL: Double,
        timeFormatter: SimpleDateFormat,
    ): List<WhatHappenedDayEvent> {
        val moments = mutableListOf<WhatHappenedDayEvent>()
        var episodeStart: NightscoutGlucoseEntry? = null
        var episodeMin: Int? = null

        fun closeEpisode() {
            val start = episodeStart ?: return
            val minValue = episodeMin ?: start.sgv
            moments += WhatHappenedDayEvent(
                id = UUID.randomUUID().toString(),
                timeMillis = start.epochMilliseconds,
                title = timeFormatter.format(Date(start.epochMilliseconds)),
                detail = "Low · $minValue mg/dL",
                carbs = null,
                insulin = null,
            )
            episodeStart = null
            episodeMin = null
        }

        for (entry in entries) {
            if (entry.sgv.toDouble() < lowMgdL) {
                if (episodeStart == null) {
                    episodeStart = entry
                    episodeMin = entry.sgv
                } else {
                    episodeMin = min(episodeMin ?: entry.sgv, entry.sgv)
                }
            } else {
                closeEpisode()
            }
        }
        closeEpisode()
        return moments
    }

    private fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
}
