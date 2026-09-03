package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - Vocabulary

/** What the data says about a therapy change, once there has been time to say anything. */
enum class TherapyChangeVerdict(val displayName: String, /** Verdicts that carry a result sort above the ones still waiting. */ val rank: Int) {
    IMPROVED("Looks better", 1),
    WORSE("Looks worse", 0),
    UNCHANGED("No clear change", 2),
    /** The change is real but too recent to judge. */
    TOO_EARLY("Too early to tell", 3),
    /** Not enough clean readings on one side of the change to compare. */
    NOT_ENOUGH_DATA("Not enough data", 4),
}

/** The glucose picture inside one hour window, over one stretch of days. */
data class TherapyWindowStats(
    val averageGlucose: Double?,
    /** Percent of readings in range. */
    val inRange: Double,
    val above: Double,
    val below: Double,
    val readingCount: Int,
    val dayCount: Int,
) {
    companion object {
        val empty = TherapyWindowStats(null, 0.0, 0.0, 0.0, 0, 0)
    }
}

/** One therapy change, and what happened after it. */
data class TherapyChangeOutcome(
    val change: TherapyChange,
    val verdict: TherapyChangeVerdict,
    /** One line stating the result. */
    val headline: String,
    /** What moved, in numbers. */
    val detail: String,
    val before: TherapyWindowStats,
    val after: TherapyWindowStats,
    /** "6 days before · 5 days after" — what the comparison rests on. */
    val sampleLabel: String,
    val caveats: List<String>,
) {
    val id: String get() = change.id

    /** Percentage-point change in time in range. Positive is better. */
    val inRangeDelta: Double get() = after.inRange - before.inRange
    /** Percentage-point change in time below range. Negative is better. */
    val belowDelta: Double get() = after.below - before.below
    val averageDelta: Double?
        get() {
            val b = before.averageGlucose ?: return null
            val a = after.averageGlucose ?: return null
            return a - b
        }

    val hasComparison: Boolean
        get() = verdict != TherapyChangeVerdict.TOO_EARLY && verdict != TherapyChangeVerdict.NOT_ENOUGH_DATA
}

/**
 * How glucose values are written in the outcome's wording. The engine cannot know the user's
 * unit, so the app supplies one built from its display settings.
 */
class TherapyGlucoseFormatter(
    val format: (Double) -> String,
    val unitLabel: String,
) {
    companion object {
        val mgdl = TherapyGlucoseFormatter({ String.format(Locale.US, "%.0f", it) }, "mg/dL")
    }
}

// MARK: - Builder

/**
 * Answers "did it work?" for therapy changes the user actually made.
 *
 * A pattern engine that never checks its own advice is a diagnosis with no follow-up. This
 * is the follow-up: for each detected change, the same hour window is measured on both sides
 * of the edit and the two are compared.
 *
 * Three rules keep the answer honest:
 *
 * - **Only the hours that changed.** A basal edit at 00:00–06:00 is judged on 00:00–06:00.
 *   Whole-day numbers would drown a real overnight effect in fourteen unaffected hours.
 * - **Lows outrank averages.** A change that lowered average glucose while adding
 *   hypoglycemia did not work, however good the time-in-range looks.
 * - **Silence over a weak verdict.** Below the day and reading minimums the answer is
 *   "not enough data yet", never a number.
 *
 * Everything here is local and deterministic. AI never decides whether a change worked.
 * Ported 1:1 from the iOS TherapyChangeOutcomeBuilder.
 */
object TherapyChangeOutcomeBuilder {

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Days of settled data needed after a change before it is judged. Below this the CGM is
     * still showing the tail of the old settings on the first night.
     */
    const val MIN_DAYS_AFTER = 3
    /** Days before the change to compare against. Capped so an old baseline does not compete with a recent one. */
    const val MAX_DAYS_BEFORE = 7
    private const val MIN_DAYS_BEFORE = 2
    /**
     * Readings needed on each side. An hour window over three days yields ~36 at 5-minute
     * sampling, so this is a floor against gaps, not a real bar.
     */
    private const val MIN_READINGS_PER_SIDE = 20

    /** Percentage points of time-in-range that count as a real move rather than week-to-week noise. */
    private const val IN_RANGE_THRESHOLD = 5.0
    /** Percentage points of added time-below that make a change harmful regardless of the rest. */
    private const val BELOW_THRESHOLD = 2.0
    /** Time below range that is acceptable in absolute terms. */
    private const val BELOW_SAFETY_CEILING = 4.0

    /**
     * Outcomes for every change recent enough to still matter, worst news first.
     *
     * @param changes from [TherapyChangeDetector], newest first.
     * @param horizonDays how far back to report changes at all. Beyond this a change is simply
     *   how things are, not a recent experiment.
     */
    fun build(
        changes: List<TherapyChange>,
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        horizonDays: Int = 21,
        nowMillis: Long,
        formatter: TherapyGlucoseFormatter = TherapyGlucoseFormatter.mgdl,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<TherapyChangeOutcome> {
        if (changes.isEmpty() || glucoseEntries.isEmpty()) return emptyList()

        val horizon = nowMillis - horizonDays * DAY_MILLIS
        val sorted = glucoseEntries.sortedBy { it.epochMilliseconds }

        // One outcome per parameter+window: when a setting was tuned twice in a fortnight,
        // only the latest edit is a live question. Reporting both would put two contradictory
        // verdicts about the same hours on one screen.
        val seen = mutableSetOf<String>()
        val outcomes = mutableListOf<TherapyChangeOutcome>()

        for (change in changes.sortedByDescending { it.changedAtMillis }) {
            if (change.changedAtMillis < horizon || change.changedAtMillis > nowMillis) continue
            val key = "${change.parameter.name}-${change.startHour}-${change.endHour}"
            if (!seen.add(key)) continue

            outcomes += outcome(change, sorted, treatments, lowGlucose, highGlucose, nowMillis, formatter, timeZone)
        }

        return outcomes.sortedWith(
            compareBy<TherapyChangeOutcome> { it.verdict.rank }.thenByDescending { it.change.changedAtMillis }
        )
    }

    // MARK: One change

    private fun outcome(
        change: TherapyChange,
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        nowMillis: Long,
        formatter: TherapyGlucoseFormatter,
        timeZone: TimeZone,
    ): TherapyChangeOutcome {
        val daysSince = (nowMillis - change.changedAtMillis) / DAY_MILLIS.toDouble()

        val afterEntries = entries.filter { it.epochMilliseconds > change.changedAtMillis && it.epochMilliseconds <= nowMillis }
        val beforeStart = change.changedAtMillis - MAX_DAYS_BEFORE * DAY_MILLIS
        val beforeEntries = entries.filter { it.epochMilliseconds >= beforeStart && it.epochMilliseconds <= change.changedAtMillis }

        val after = stats(afterEntries, change, lowGlucose, highGlucose, timeZone)
        val before = stats(beforeEntries, change, lowGlucose, highGlucose, timeZone)

        if (daysSince < MIN_DAYS_AFTER || after.dayCount < MIN_DAYS_AFTER) {
            val remaining = max(1, MIN_DAYS_AFTER - after.dayCount)
            return TherapyChangeOutcome(
                change = change,
                verdict = TherapyChangeVerdict.TOO_EARLY,
                headline = "Checking back in $remaining day${if (remaining == 1) "" else "s"}",
                detail = "Needs $MIN_DAYS_AFTER days on the new setting to compare fairly. Updates itself.",
                before = before,
                after = after,
                sampleLabel = sampleLabel(before, after),
                caveats = emptyList(),
            )
        }

        val beforeAverage = before.averageGlucose
        val afterAverage = after.averageGlucose
        if (before.dayCount < MIN_DAYS_BEFORE ||
            before.readingCount < MIN_READINGS_PER_SIDE ||
            after.readingCount < MIN_READINGS_PER_SIDE ||
            beforeAverage == null || afterAverage == null
        ) {
            return TherapyChangeOutcome(
                change = change,
                verdict = TherapyChangeVerdict.NOT_ENOUGH_DATA,
                headline = "Not enough readings either side to compare",
                detail = "Too few readings from before the change to say what it did.",
                before = before,
                after = after,
                sampleLabel = sampleLabel(before, after),
                caveats = emptyList(),
            )
        }

        val inRangeDelta = after.inRange - before.inRange
        val belowDelta = after.below - before.below
        val averageDelta = afterAverage - beforeAverage

        // Safety first: added hypoglycemia overrides an improved average or time in range.
        // This is the one verdict that must not be outvoted by the numbers that look good.
        val addedLows = belowDelta >= BELOW_THRESHOLD && after.below >= BELOW_SAFETY_CEILING

        val verdict = when {
            addedLows -> TherapyChangeVerdict.WORSE
            inRangeDelta >= IN_RANGE_THRESHOLD -> TherapyChangeVerdict.IMPROVED
            inRangeDelta <= -IN_RANGE_THRESHOLD -> TherapyChangeVerdict.WORSE
            else -> TherapyChangeVerdict.UNCHANGED
        }

        return TherapyChangeOutcome(
            change = change,
            verdict = verdict,
            headline = headline(verdict, change, addedLows, inRangeDelta, averageDelta),
            detail = detail(change, before, after, averageDelta, formatter),
            before = before,
            after = after,
            sampleLabel = sampleLabel(before, after),
            caveats = caveats(change, before, after, treatments, nowMillis, timeZone),
        )
    }

    // MARK: Measurement

    /** Glucose inside the changed hours only, aggregated across whichever days are supplied. */
    private fun stats(
        entries: List<NightscoutGlucoseEntry>,
        change: TherapyChange,
        lowGlucose: Double,
        highGlucose: Double,
        timeZone: TimeZone,
    ): TherapyWindowStats {
        val calendar = Calendar.getInstance(timeZone)
        fun hourOf(millis: Long): Int { calendar.timeInMillis = millis; return calendar.get(Calendar.HOUR_OF_DAY) }

        val inWindow = entries.filter { change.contains(hourOf(it.epochMilliseconds)) }
        if (inWindow.isEmpty()) return TherapyWindowStats.empty

        val values = inWindow.map { it.sgv.toDouble() }
        val below = values.count { it < lowGlucose }
        val above = values.count { it > highGlucose }
        val total = values.size.toDouble()

        // An overnight window spans two calendar dates; count the day it started on so
        // "5 nights" means five nights.
        val days = inWindow.map { entry ->
            val hour = hourOf(entry.epochMilliseconds)
            val anchor = if (change.startHour > change.endHour && hour < change.endHour) {
                entry.epochMilliseconds - DAY_MILLIS
            } else {
                entry.epochMilliseconds
            }
            TodaySoFarBuilder.startOfDay(anchor, timeZone)
        }.toSet()

        return TherapyWindowStats(
            averageGlucose = values.sum() / total,
            inRange = (values.size - below - above) / total * 100,
            above = above / total * 100,
            below = below / total * 100,
            readingCount = values.size,
            dayCount = days.size,
        )
    }

    // MARK: Wording

    private fun headline(
        verdict: TherapyChangeVerdict,
        change: TherapyChange,
        addedLows: Boolean,
        inRangeDelta: Double,
        averageDelta: Double,
    ): String = when {
        verdict == TherapyChangeVerdict.IMPROVED ->
            "Time in range up ${points(inRangeDelta)} in ${change.windowLabel} since the change"
        verdict == TherapyChangeVerdict.WORSE && addedLows ->
            "More time below range in ${change.windowLabel} since the change"
        verdict == TherapyChangeVerdict.WORSE ->
            "Time in range down ${points(-inRangeDelta)} in ${change.windowLabel} since the change"
        verdict == TherapyChangeVerdict.UNCHANGED -> {
            val direction = if (averageDelta < 0) "lower" else "higher"
            if (abs(averageDelta) < 5) {
                "${change.windowLabel} looks much the same since the change"
            } else {
                "${change.windowLabel} is slightly $direction, but not enough to call it"
            }
        }
        else -> verdict.displayName
    }

    private fun detail(
        change: TherapyChange,
        before: TherapyWindowStats,
        after: TherapyWindowStats,
        averageDelta: Double,
        formatter: TherapyGlucoseFormatter,
    ): String {
        val parts = mutableListOf<String>()

        val beforeAverage = before.averageGlucose
        val afterAverage = after.averageGlucose
        if (beforeAverage != null && afterAverage != null) {
            parts += "Average ${formatter.format(beforeAverage)} → ${formatter.format(afterAverage)} ${formatter.unitLabel}"
        }
        parts += "in range ${percent(before.inRange)} → ${percent(after.inRange)}"
        if (before.below >= 1 || after.below >= 1) {
            parts += "below range ${percent(before.below)} → ${percent(after.below)}"
        }

        // Stating the intent next to the result is what makes the card readable: "aimed
        // lower, went lower" and "aimed lower, went higher" are different stories from the
        // same two numbers.
        val moved = if (averageDelta < 0) "moved down" else "moved up"
        val matchedIntent = change.expectsLowerGlucose == (averageDelta < 0)
        val expectation = if (abs(averageDelta) < 3) {
            "The change aimed for ${change.intentLabel}; glucose here barely moved"
        } else {
            "The change aimed for ${change.intentLabel}, and glucose here $moved" +
                (if (matchedIntent) " as expected" else " the other way")
        }

        return parts.joinToString(", ") + ". " + expectation + "."
    }

    /**
     * Anything that could explain the difference other than the change itself. Naming these
     * is what separates a comparison from a claim.
     */
    private fun caveats(
        change: TherapyChange,
        before: TherapyWindowStats,
        after: TherapyWindowStats,
        treatments: List<NightscoutTreatment>,
        nowMillis: Long,
        timeZone: TimeZone,
    ): List<String> {
        val result = mutableListOf<String>()

        if (after.dayCount < 5) {
            result += "Only ${after.dayCount} day${if (after.dayCount == 1) "" else "s"} since the change — this will firm up."
        }

        // Exercise inside the window on either side moves glucose more than most setting
        // edits do, so it has to be named rather than silently averaged in.
        val calendar = Calendar.getInstance(timeZone)
        val windowStart = change.changedAtMillis - MAX_DAYS_BEFORE * DAY_MILLIS
        val exerciseDays = treatments
            .filter { it.eventType == "Exercise" }
            .map { MealOutcomeBuilder.treatmentMillis(it) }
            .filter { it >= windowStart && it <= nowMillis }
            .filter { calendar.timeInMillis = it; change.contains(calendar.get(Calendar.HOUR_OF_DAY)) }
            .map { TodaySoFarBuilder.startOfDay(it, timeZone) }
            .toSet()
        if (exerciseDays.isNotEmpty()) {
            result += "${exerciseDays.size} day${if (exerciseDays.size == 1) "" else "s"} had logged exercise during ${change.windowLabel}."
        }

        if (before.dayCount < after.dayCount - 2 || after.dayCount < before.dayCount - 2) {
            result += "Uneven days (${before.dayCount} before, ${after.dayCount} after) — treat as directional."
        }

        result += "Food, activity, sleep and site changes affect this window too — this compares the days, not the setting alone."
        return result
    }

    private fun sampleLabel(before: TherapyWindowStats, after: TherapyWindowStats): String =
        "${before.dayCount} day${if (before.dayCount == 1) "" else "s"} before · " +
            "${after.dayCount} day${if (after.dayCount == 1) "" else "s"} after"

    private fun percent(value: Double): String = "${value.roundToInt()}%"

    private fun points(value: Double): String = "${value.roundToInt()} points"
}
