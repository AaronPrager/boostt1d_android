package com.boostt1d.android.engine

import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Hour-by-hour therapy settings review — the pass a clinician makes over a download before
 * changing a pump.
 *
 * This is deliberately *not* the pattern engine. [PatternService] answers "what happened", in
 * windows chosen by how interesting they are. This answers "which setting, in which hours of
 * the day, is not doing its job", in windows chosen by the clock and by the therapy profile's
 * own segments.
 *
 * Three separate readings, each from the evidence that can actually support it:
 *
 * - **Basal** — from fasting drift only: hours with no carbs and no bolus insulin still
 *   acting. Glucose that climbs while nothing else is happening is the one clean signal about
 *   background insulin.
 * - **ISF** — from corrections that stood alone: no food, no other bolus, measured over three
 *   hours so most of the dose has acted.
 * - **Carb ratio** — from meals that were not touched again for four hours, comparing glucose
 *   at the meal with glucose once the dose has finished.
 *
 * Everything is measured, never assumed, and every finding carries the sample it rests on.
 * Windows without enough clean data produce a note saying so rather than a weak verdict — a
 * confident-sounding number from four readings is worse than silence here.
 *
 * Ported 1:1 from the iOS TherapySettingsReviewBuilder; its 26 tests are the spec.
 */
object TherapySettingsReviewBuilder {

    // MARK: Tunables

    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 86_400_000L
    private const val EPOCH_GUARD = 0L

    /**
     * How long a bolus keeps affecting glucose. Windows overlapping one say nothing about
     * basal. Overridden by the profile's DIA when it has one.
     */
    private const val DEFAULT_INSULIN_DURATION_HOURS = 4.0
    private const val CARB_SHADOW_HOURS = 4.0
    /** Overnight loop-delivery comparison only. A late dinner should not erase 01:00–06:00. */
    private const val LOOP_OVERNIGHT_CARB_SHADOW_HOURS = 2.0
    private const val EXERCISE_SHADOW_HOURS = 6.0
    /** The same numbers, readable from the UI so the screen can name the rule it applies. */
    val mealExclusionHours: Double get() = CARB_SHADOW_HOURS
    val overnightMealExclusionHours: Double get() = LOOP_OVERNIGHT_CARB_SHADOW_HOURS
    val activityExclusionHours: Double get() = EXERCISE_SHADOW_HOURS

    /**
     * Sample minimums scale with the window. Over three days an hour can supply at most three
     * fasting windows, so demanding three means one late dinner on one night erases the hour —
     * and the whole screen goes quiet on a user with plenty of data.
     */
    private fun minFastingWindows(periodDays: Int): Int = if (periodDays >= 7) 3 else 2

    /** mg/dL per hour. Below this a fasting trend is noise, not a basal problem. */
    private const val DRIFT_THRESHOLD = 8.0
    /** Fasting readings below target, in percent, that make a window a safety finding regardless of drift. */
    private const val FASTING_HYPO_PERCENT = 5.0

    /** Hours where an unrecorded meal is unlikely enough that one logged event in the day is enough to trust the silence. */
    private const val OVERNIGHT_FASTING_START_HOUR = 23
    private const val OVERNIGHT_FASTING_END_HOUR = 6
    /** Records a day needs before *daytime* silence means "did not eat" rather than "did not log". */
    private const val MIN_DAYTIME_LOG_RECORDS = 2

    private const val MIN_CORRECTIONS_PER_WINDOW = 3
    private const val MIN_MEALS_PER_WINDOW = 3
    private const val ISF_MISMATCH_PERCENT = 15.0
    private const val CARB_RATIO_MISMATCH_PERCENT = 12.0

    /** No single review may move a setting more than this. Clinicians step; they do not jump. */
    private const val MAX_CHANGE_PERCENT = 20.0
    /** Half the distance to what was measured — the rest waits for the next review to confirm. */
    private const val STEP_FRACTION = 0.5

    /** Days that must pass on a new setting before it can be reviewed at all — the same bar [TherapyChangeOutcomeBuilder] uses. */
    private const val MIN_DAYS_ON_NEW_SETTING = 3

    // Loop delivery
    private const val LOOP_DEVIATION_THRESHOLD = 0.20
    private const val LOOP_OVERNIGHT_DEVIATION_THRESHOLD = 0.15
    private const val LOOP_STEADY_BAND = 0.10
    private const val MIN_LOOP_DAYS_WITH_TEMP_BASALS = 2
    private const val LOOP_OVERNIGHT_START_HOUR = 23
    private const val LOOP_OVERNIGHT_END_HOUR = 6

    // MARK: Entry point

    /**
     * @param changes therapy edits the user has made, from [TherapyChangeDetector]. A setting
     *   that changed inside the period is reviewed on the days *since* the change only, because
     *   averaging both sides of an edit describes a therapy that never existed.
     * @param therapyType stated by the caller from the profile, so a review is reproducible
     *   rather than inheriting whatever a device last stored.
     */
    fun build(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogEntries: List<FoodLogSnapshot>,
        profile: NightscoutProfileDocument?,
        lowGlucose: Double,
        highGlucose: Double,
        periodDays: Int,
        changes: List<TherapyChange> = emptyList(),
        nowMillis: Long,
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
        formatter: TherapyGlucoseFormatter = TherapyGlucoseFormatter.mgdl,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): TherapySettingsReview {
        if (glucoseEntries.size < 30) return TherapySettingsReview.empty

        val clock = Clock(timeZone)
        val settings = TherapyProfileSettings(profile)
        val delivery = InsulinDeliveryContext(treatments, therapyType)
        val context = Context(
            treatments = treatments,
            foodLogEntries = foodLogEntries,
            isClosedLoop = delivery.isClosedLoop,
            insulinDuration = settings.insulinDuration ?: DEFAULT_INSULIN_DURATION_HOURS,
            clock = clock,
        )

        val samplingMinutes = samplingIntervalMinutes(glucoseEntries)
        val recentChanges = latestChangePerParameter(changes, periodDays, nowMillis)

        var fastingDiagnostics = FastingDiagnostics()
        // The hourly strip always covers the whole period: it is a picture of the days, not a
        // verdict on a setting, and blanking half of it would confuse more than it protects.
        var hours = hourlyStats(glucoseEntries, context, lowGlucose, highGlucose, periodDays, clock, fastingDiagnostics)

        val findings = mutableListOf<TherapyFinding>()
        val steadyNotes = mutableListOf<String>()
        val dataNotes = mutableListOf<String>()
        var basalMethod = TherapyBasalMethod.NONE
        var loopComparedHours = 0
        var loopComparedDays = 0
        var usedDeliveryFallback = false

        // On a closed loop, read the loop rather than the glucose. Fasting drift measures how
        // well the algorithm compensated, not whether the baseline under it is right — and with
        // SMBs arriving every few minutes there is no fasting window left to measure in anyway.
        // The temp basals the system set are a direct, food-independent statement about the
        // profile, and they need no dose attribution to interpret.
        //
        // Recent basal edits still gate this path the same way open-loop does.
        val basalBlocked = changeDataNote(TherapyParameter.BASAL, recentChanges, nowMillis, formatter)
        if (delivery.isClosedLoop) {
            if (basalBlocked != null) {
                dataNotes += basalBlocked
            } else {
                val basalChange = usableChange(TherapyParameter.BASAL, recentChanges, nowMillis)
                val loopEntries = basalChange?.let { entriesSince(glucoseEntries, it.changedAtMillis) } ?: glucoseEntries
                val loopPeriod = basalChange?.let { daysSince(it, nowMillis) } ?: periodDays
                val loopResult = loopBasalFindings(loopEntries, settings, context, delivery, loopPeriod, clock)
                findings += loopResult.findings
                steadyNotes += loopResult.steadyNotes
                dataNotes += loopResult.dataNotes
                loopComparedHours = loopResult.comparedHours
                loopComparedDays = loopResult.comparedDays
                basalMethod = if (loopComparedHours > 0) TherapyBasalMethod.LOOP_DELIVERY else TherapyBasalMethod.NONE
                hours = merged(hours, loopResult, loopPeriod)
            }
        } else if (basalBlocked != null) {
            dataNotes += basalBlocked
        } else {
            val basalChange = usableChange(TherapyParameter.BASAL, recentChanges, nowMillis)
            // Re-measure the hours from post-change data only when basal moved, so a fasting
            // drift computed under the old rate cannot argue about the new one.
            val basalHours: List<TherapyHourStat>
            if (basalChange != null) {
                val scoped = FastingDiagnostics()
                basalHours = hourlyStats(
                    entriesSince(glucoseEntries, basalChange.changedAtMillis), context, lowGlucose, highGlucose,
                    daysSince(basalChange, nowMillis), clock, scoped,
                )
                fastingDiagnostics = scoped
            } else {
                basalHours = hours
            }

            val basalPeriod = basalChange?.let { daysSince(it, nowMillis) } ?: periodDays
            val basalResult = basalFindings(basalHours, settings, context, basalPeriod, fastingDiagnostics, formatter)
            // Fasting drift is the better instrument when it works, but it needs uninterrupted
            // stretches — and a long insulin duration, frequent corrections or an unflagged
            // automated system can leave almost none. Rather than report "1 clean hour" and
            // stop, fall back to what was actually delivered against what the profile asked
            // for. That read needs no fasting window and no dose attribution.
            if (basalResult.findings.isEmpty() && basalResult.steadyNotes.isEmpty()) {
                val loopEntries = basalChange?.let { entriesSince(glucoseEntries, it.changedAtMillis) } ?: glucoseEntries
                val fallback = loopBasalFindings(loopEntries, settings, context, delivery, basalPeriod, clock)
                if (fallback.findings.isNotEmpty() || fallback.steadyNotes.isNotEmpty()) {
                    usedDeliveryFallback = true
                    findings += fallback.findings
                    steadyNotes += fallback.steadyNotes
                    dataNotes += fallback.dataNotes
                    dataNotes += fastingUnreadableNote(fastingDiagnostics, delivery)
                    loopComparedHours = fallback.comparedHours
                    loopComparedDays = fallback.comparedDays
                    basalMethod = TherapyBasalMethod.LOOP_DELIVERY
                    hours = merged(hours, fallback, basalPeriod)
                }
            }

            if (!usedDeliveryFallback) {
                basalMethod = if (fastingDiagnostics.accepted > 0) TherapyBasalMethod.FASTING_DRIFT else TherapyBasalMethod.NONE
                findings += basalResult.findings
                steadyNotes += basalResult.steadyNotes
                dataNotes += basalResult.dataNotes
            }
        }

        var corrections: List<CorrectionSample> = emptyList()
        val isfBlocked = changeDataNote(TherapyParameter.ISF, recentChanges, nowMillis, formatter)
        if (isfBlocked != null) {
            dataNotes += isfBlocked
        } else {
            val isfChange = usableChange(TherapyParameter.ISF, recentChanges, nowMillis)
            val correctionDiagnostics = CorrectionDiagnostics()
            corrections = cleanCorrections(entriesSince(glucoseEntries, isfChange?.changedAtMillis), context, correctionDiagnostics, clock)
            val isfResult = isfFindings(corrections, settings, context, correctionDiagnostics, formatter)
            findings += isfResult.findings
            steadyNotes += isfResult.steadyNotes
            dataNotes += isfResult.dataNotes
        }

        var meals: List<MealSample> = emptyList()
        val carbBlocked = changeDataNote(TherapyParameter.CARB_RATIO, recentChanges, nowMillis, formatter)
        if (carbBlocked != null) {
            dataNotes += carbBlocked
        } else {
            val carbChange = usableChange(TherapyParameter.CARB_RATIO, recentChanges, nowMillis)
            val mealDiagnostics = MealDiagnostics()
            meals = cleanMeals(entriesSince(glucoseEntries, carbChange?.changedAtMillis), context, lowGlucose, samplingMinutes, mealDiagnostics, clock)
            val carbResult = carbRatioFindings(meals, settings, context, mealDiagnostics, formatter)
            findings += carbResult.findings
            steadyNotes += carbResult.steadyNotes
            dataNotes += carbResult.dataNotes
        }

        val annotatedFindings = annotated(findings, recentChanges, nowMillis, formatter).toMutableList()

        if (!settings.hasAny) {
            dataNotes.add(
                0,
                "No therapy profile is available, so the review reports what your glucose did without " +
                    "naming your current settings. Connect Nightscout, or enter your basal, ISF and carb " +
                    "ratio in Settings, to see each finding against the setting it applies to.",
            )
        }

        annotatedFindings.sortWith(
            compareBy<TherapyFinding> { it.parameter.reviewRank }
                .thenByDescending { priorityRank(it.priority) }
                .thenBy { it.startHour },
        )

        return TherapySettingsReview(
            periodDays = periodDays,
            hours = hours,
            findings = annotatedFindings,
            steadyNotes = steadyNotes,
            dataNotes = dataNotes,
            isClosedLoop = context.isClosedLoop,
            hasTherapySettings = settings.hasAny,
            // Zero on a loop: those hours exist in the hourly strip but no finding was drawn
            // from them, and claiming them as the measurement would be false.
            cleanFastingHours = if (basalMethod == TherapyBasalMethod.FASTING_DRIFT) hours.sumOf { it.fastingWindowCount } else 0,
            cleanCorrections = corrections.size,
            cleanMeals = meals.size,
            basalMethod = basalMethod,
            loopComparedHours = loopComparedHours,
            loopComparedDays = loopComparedDays,
        )
    }

    // MARK: - Clock

    /** Calendar arithmetic in one place, so every hour and day boundary agrees. */
    private class Clock(val timeZone: TimeZone) {
        private val calendar = Calendar.getInstance(timeZone)
        fun hour(millis: Long): Int { calendar.timeInMillis = millis; return calendar.get(Calendar.HOUR_OF_DAY) }
        fun startOfDay(millis: Long): Long = TodaySoFarBuilder.startOfDay(millis, timeZone)
        fun addHours(millis: Long, hours: Int): Long { calendar.timeInMillis = millis; calendar.add(Calendar.HOUR_OF_DAY, hours); return calendar.timeInMillis }
    }

    // MARK: - Change awareness
    //
    // A settings review that averages across an edit is describing a therapy the user never
    // had. Everything below exists to keep the two sides of an edit apart.

    /** The newest edit to each parameter inside the analysis window. */
    private fun latestChangePerParameter(changes: List<TherapyChange>, periodDays: Int, nowMillis: Long): Map<TherapyParameter, TherapyChange> {
        val windowStart = nowMillis - periodDays * DAY_MILLIS
        val result = mutableMapOf<TherapyParameter, TherapyChange>()
        for (change in changes) {
            if (change.changedAtMillis <= windowStart || change.changedAtMillis > nowMillis) continue
            val existing = result[change.parameter]
            if (existing != null && existing.changedAtMillis >= change.changedAtMillis) continue
            result[change.parameter] = change
        }
        return result
    }

    private fun daysSince(change: TherapyChange, nowMillis: Long): Int =
        max(1, ceil((nowMillis - change.changedAtMillis) / DAY_MILLIS.toDouble()).toInt())

    /** The change to scope this parameter's analysis to, or null when nothing is recent enough to matter. */
    private fun usableChange(parameter: TherapyParameter, changes: Map<TherapyParameter, TherapyChange>, nowMillis: Long): TherapyChange? {
        val change = changes[parameter] ?: return null
        return if (daysSince(change, nowMillis) >= MIN_DAYS_ON_NEW_SETTING) change else null
    }

    /**
     * Set when a parameter changed too recently to review. Saying so is the whole point — a
     * silent section reads as "nothing found", which is a different and wrong message.
     */
    private fun changeDataNote(parameter: TherapyParameter, changes: Map<TherapyParameter, TherapyChange>, nowMillis: Long, formatter: TherapyGlucoseFormatter): String? {
        val change = changes[parameter] ?: return null
        val days = daysSince(change, nowMillis)
        if (days >= MIN_DAYS_ON_NEW_SETTING) return null
        val remaining = MIN_DAYS_ON_NEW_SETTING - days
        return "${parameter.displayName} changed $days day${if (days == 1) "" else "s"} ago " +
            "(${change.windowLabel}, ${changeValueLabel(change, formatter)}). Not reviewed yet — " +
            "$remaining more day${if (remaining == 1) "" else "s"} on the new setting first."
    }

    /** "0.80 → 0.95 U/hr", in the caller's glucose unit for ISF. */
    private fun changeValueLabel(change: TherapyChange, formatter: TherapyGlucoseFormatter): String = when (change.parameter) {
        TherapyParameter.BASAL -> "${two(change.previousValue)} → ${two(change.newValue)} U/hr"
        TherapyParameter.ISF -> "${formatter.format(change.previousValue)} → ${formatter.format(change.newValue)} ${formatter.unitLabel}/U"
        TherapyParameter.CARB_RATIO -> "${one(change.previousValue)} → ${one(change.newValue)} g/U"
    }

    /** Names the edit on any finding whose window it overlaps, so the reader knows the review already accounts for it. */
    private fun annotated(findings: List<TherapyFinding>, changes: Map<TherapyParameter, TherapyChange>, nowMillis: Long, formatter: TherapyGlucoseFormatter): List<TherapyFinding> {
        if (changes.isEmpty()) return findings
        return findings.map { finding ->
            val change = changes[finding.parameter] ?: return@map finding
            if (!overlaps(finding, change)) return@map finding
            val days = daysSince(change, nowMillis)
            finding.copy(
                caveats = listOf(
                    "Changed $days day${if (days == 1) "" else "s"} ago (${changeValueLabel(change, formatter)}). Only the days since are in this finding."
                ) + finding.caveats
            )
        }
    }

    private fun overlaps(finding: TherapyFinding, change: TherapyChange): Boolean =
        hourRange(finding.startHour, finding.endHour).any { change.contains(it) }

    private fun hourRange(start: Int, end: Int): List<Int> {
        val result = mutableListOf<Int>()
        var hour = start
        do {
            result += hour
            hour = (hour + 1) % 24
        } while (hour != end && result.size < 24)
        return result
    }

    /** Writes per-hour delivery onto the hourly strip, so its readout matches the method the basal finding came from. */
    private fun merged(hours: List<TherapyHourStat>, loop: LoopResult, periodDays: Int): List<TherapyHourStat> {
        if (loop.ratiosByHour.isEmpty() && loop.shadowedDaysByHour.isEmpty() && loop.activityShadowedDaysByHour.isEmpty()) return hours
        return hours.map { hour ->
            val ratios = loop.ratiosByHour[hour.hour]
            val minDays = minLoopDays(hour.hour, periodDays)
            hour.copy(
                mealShadowedDays = loop.shadowedDaysByHour[hour.hour] ?: 0,
                activityShadowedDays = loop.activityShadowedDaysByHour[hour.hour] ?: 0,
                // Always record how many days there were, so the strip can say "only 1 day here"
                // instead of going silent — but only state a percentage once it clears the same
                // bar a finding must clear. One day is an anecdote.
                deliveredDayCount = ratios?.size ?: 0,
                deliveredRatio = if (ratios != null && ratios.size >= minDays) median(ratios) else null,
            )
        }
    }

    /** Entries at or after [sinceMillis], or all of them when there is no cutoff. */
    private fun entriesSince(entries: List<NightscoutGlucoseEntry>, sinceMillis: Long?): List<NightscoutGlucoseEntry> {
        if (sinceMillis == null) return entries
        return entries.filter { it.epochMilliseconds >= sinceMillis }
    }

    // MARK: - Diagnostics
    //
    // "Not enough data" is a dead end for someone who knows they have months of it. Each
    // analysis counts what it threw away and why, so the screen can name the reason instead
    // of the outcome.

    private class FastingDiagnostics {
        var bucketsExamined = 0
        var rejectedSparse = 0
        var rejectedCarbs = 0
        var rejectedInsulin = 0
        var rejectedExercise = 0
        /** Hours on days with nothing recorded, where absence of food could not be confirmed. */
        var rejectedUnlogged = 0
        var accepted = 0
    }

    private class CorrectionDiagnostics {
        var candidates = 0
        var rejectedAlgorithm = 0
        var rejectedCarbsNearby = 0
        var rejectedOverlappingDose = 0
        var rejectedExercise = 0
        var rejectedMissingGlucose = 0
        var rejectedNotACorrection = 0
        var rejectedImplausible = 0
        var accepted = 0
    }

    private class MealDiagnostics {
        var candidates = 0
        var rejectedNoBolus = 0
        var rejectedLaterCarbs = 0
        var rejectedLaterDose = 0
        var rejectedExercise = 0
        var rejectedMissingGlucose = 0
        var rejectedSparseGlucose = 0
        var accepted = 0
    }

    private fun unattributedLoopBasalNote(delivery: InsulinDeliveryContext): String =
        "Basal — not enough delivery data uploaded to compare against your profile " +
            "(${delivery.bolusCount} doses, ${delivery.tempBasalCount} temp basals this period). " +
            "Everything else here is unaffected."

    private fun fastingUnreadableNote(diagnostics: FastingDiagnostics, delivery: InsulinDeliveryContext): String =
        "Basal was read from insulin delivered rather than from fasting glucose: only " +
            "${diagnostics.accepted} of ${diagnostics.bucketsExamined} hours were free of food " +
            "insulin, and logged activity (${diagnostics.rejectedCarbs} within " +
            "${CARB_SHADOW_HOURS.toInt()}h of food, ${diagnostics.rejectedInsulin} with insulin " +
            "still acting, ${diagnostics.rejectedExercise} within " +
            "${EXERCISE_SHADOW_HOURS.toInt()}h of exercise). " +
            "${delivery.bolusCount} doses and ${delivery.tempBasalCount} temp basals were " +
            "uploaded, ${delivery.automaticBolusCount} flagged automatic."

    private fun unloggedBasalNote(diagnostics: FastingDiagnostics): String =
        "Basal — needs to know when you ate. ${diagnostics.rejectedUnlogged} of " +
            "${diagnostics.bucketsExamined} hours fell on days with nothing logged, and an " +
            "empty log is not the same as not eating, so they are left out. Log meals in the " +
            "Event Log and this fills in. Everything else here is unaffected."

    /** Renders the counters as a sentence, dropping the reasons that never fired. */
    private fun breakdown(parts: List<Pair<Int, String>>): String {
        val active = parts.filter { it.first > 0 }.map { "${it.first} ${it.second}" }
        if (active.isEmpty()) return ""
        if (active.size == 1) return active[0]
        return active.dropLast(1).joinToString(", ") + " and " + active.last()
    }

    /** Typical minutes between readings. Anything that counts readings has to ask rather than assume a sensor cadence. */
    private fun samplingIntervalMinutes(entries: List<NightscoutGlucoseEntry>): Double {
        val sorted = entries.map { it.epochMilliseconds }.sorted()
        if (sorted.size < 10) return 5.0
        val gaps = mutableListOf<Double>()
        for (index in 1 until sorted.size) {
            val gap = (sorted[index] - sorted[index - 1]) / 60_000.0
            // Ignore sync gaps; they say nothing about the resolution of the data itself.
            if (gap > 0 && gap <= 30) gaps += gap
        }
        if (gaps.isEmpty()) return 5.0
        return max(1.0, median(gaps))
    }

    // MARK: - Shared context

    /** Event timelines the three analyses all filter against. */
    private class Context(
        val treatments: List<NightscoutTreatment>,
        val foodLogEntries: List<FoodLogSnapshot>,
        val isClosedLoop: Boolean,
        val insulinDuration: Double,
        val clock: Clock,
    ) {
        val carbTimes: List<Long>
        /** Every dose, whoever delivered it. */
        val bolusTimes: List<Long>
        /** Doses the user chose. On a closed loop this excludes the algorithm's own micro-boluses. */
        val manualBolusTimes: List<Long>
        /** Explicitly logged exercise/activity events. */
        val exerciseTimes: List<Long>
        /**
         * Carb entries and user-chosen doses per day. Zero means the day says nothing about
         * food — which is a different fact from there having been none.
         */
        val userRecordsByDay: Map<Long, Int>

        init {
            // Carbs come from the event log first and the food log second, so a user who logs
            // in either place is covered — and a meal recorded in both counts once.
            val carbs = treatments.filter { (it.carbs ?: 0.0) > 0 }.map { MealOutcomeBuilder.treatmentMillis(it) }.toMutableList()
            for (entry in foodLogEntries) {
                if ((entry.carbsGrams ?: 0.0) <= 0) continue
                val covered = carbs.any { abs(it - entry.recordedAtMillis) < 15 * 60_000L }
                if (!covered) carbs += entry.recordedAtMillis
            }
            carbTimes = carbs.sorted()

            val boluses = treatments.filter { (it.insulin ?: 0.0) > 0 }
            bolusTimes = boluses.map { MealOutcomeBuilder.treatmentMillis(it) }.sorted()
            manualBolusTimes = boluses.filter { !it.isAlgorithmDelivered }.map { MealOutcomeBuilder.treatmentMillis(it) }.sorted()
            exerciseTimes = treatments.mapNotNull { treatment ->
                val event = (treatment.eventType ?: "").lowercase(Locale.US)
                val notes = (treatment.notes ?: "").lowercase(Locale.US)
                val isExercise = event.contains("exercise") || event.contains("activity") ||
                    event.contains("workout") || notes.contains("exercise")
                if (isExercise) MealOutcomeBuilder.treatmentMillis(treatment) else null
            }.filter { it > EPOCH_GUARD }.sorted()

            // Count logged *events*, not fields. A meal entered with its carbs and its bolus is
            // one thing the user recorded, and counting it twice would let a single logged
            // lunch vouch for a whole unlogged afternoon.
            val counts = mutableMapOf<Long, Int>()
            var lastCounted: Long? = null
            for (time in (carbTimes + manualBolusTimes).sorted()) {
                val last = lastCounted
                if (last != null && time - last < 15 * 60_000L) continue
                lastCounted = time
                val day = clock.startOfDay(time)
                counts[day] = (counts[day] ?: 0) + 1
            }
            userRecordsByDay = counts
        }

        enum class FastingRejection { CARBS, INSULIN, EXERCISE, UNLOGGED, NONE }

        /** Why a window can't speak about basal, or NONE when the user was recording that day and recorded nothing that would move glucose across it. */
        fun fastingRejection(start: Long, end: Long): FastingRejection {
            val carbStart = start - (CARB_SHADOW_HOURS * HOUR_MILLIS).toLong()
            if (carbTimes.any { it > carbStart && it < end }) return FastingRejection.CARBS
            val bolusStart = start - (insulinDuration * HOUR_MILLIS).toLong()
            if (manualBolusTimes.any { it > bolusStart && it < end }) return FastingRejection.INSULIN
            val exerciseStart = start - (EXERCISE_SHADOW_HOURS * HOUR_MILLIS).toLong()
            if (exerciseTimes.any { it > exerciseStart && it < end }) return FastingRejection.EXERCISE
            if (!silenceIsEvidence(start)) return FastingRejection.UNLOGGED
            return FastingRejection.NONE
        }

        /**
         * Whether an absent record here means "did not eat" rather than "did not log".
         *
         * Two bars, because the hours are not equally trustworthy. Overnight an unrecorded meal
         * is unlikely, so one record in the day is enough to show the user logs at all. During
         * the day it is not: a logged breakfast says nothing about whether lunch would have
         * been logged too.
         */
        private fun silenceIsEvidence(moment: Long): Boolean {
            val records = userRecordsByDay[clock.startOfDay(moment)] ?: 0
            if (records <= 0) return false
            val hour = clock.hour(moment)
            val isOvernight = hour >= OVERNIGHT_FASTING_START_HOUR || hour < OVERNIGHT_FASTING_END_HOUR
            return isOvernight || records >= MIN_DAYTIME_LOG_RECORDS
        }

        fun hasCarbs(start: Long, end: Long): Boolean = carbTimes.any { it in start..end }
        fun hasManualBolus(start: Long, end: Long): Boolean = manualBolusTimes.any { it in start..end }
        fun hasExercise(start: Long, end: Long): Boolean = exerciseTimes.any { it in start..end }
    }

    // MARK: - Hourly profile

    private data class DayHour(val day: Long, val hour: Int)

    private fun hourlyStats(
        entries: List<NightscoutGlucoseEntry>,
        context: Context,
        lowGlucose: Double,
        highGlucose: Double,
        periodDays: Int,
        clock: Clock,
        diagnostics: FastingDiagnostics,
    ): List<TherapyHourStat> {
        val byHour = mutableMapOf<Int, MutableList<Double>>()
        val daysByHour = mutableMapOf<Int, MutableSet<Long>>()
        val buckets = mutableMapOf<DayHour, MutableList<NightscoutGlucoseEntry>>()

        for (entry in entries) {
            val at = entry.epochMilliseconds
            val hour = clock.hour(at)
            val day = clock.startOfDay(at)
            byHour.getOrPut(hour) { mutableListOf() } += entry.sgv.toDouble()
            daysByHour.getOrPut(hour) { mutableSetOf() } += day
            buckets.getOrPut(DayHour(day, hour)) { mutableListOf() } += entry
        }

        val driftsByHour = mutableMapOf<Int, MutableList<Double>>()
        val fastingBelow = mutableMapOf<Int, Int>()
        val fastingTotal = mutableMapOf<Int, Int>()

        for ((key, readings) in buckets) {
            diagnostics.bucketsExamined += 1
            val sorted = readings.sortedBy { it.epochMilliseconds }
            if (sorted.size < 3) { diagnostics.rejectedSparse += 1; continue }
            val first = sorted.first()
            val last = sorted.last()
            val span = last.epochMilliseconds - first.epochMilliseconds
            // A window that only covers a few minutes turns sensor noise into a slope. Half an
            // hour is the floor: an hour with one missed sync still has to be readable.
            if (span < 30 * 60_000L) { diagnostics.rejectedSparse += 1; continue }
            when (context.fastingRejection(first.epochMilliseconds, last.epochMilliseconds)) {
                Context.FastingRejection.CARBS -> { diagnostics.rejectedCarbs += 1; continue }
                Context.FastingRejection.INSULIN -> { diagnostics.rejectedInsulin += 1; continue }
                Context.FastingRejection.EXERCISE -> { diagnostics.rejectedExercise += 1; continue }
                Context.FastingRejection.UNLOGGED -> { diagnostics.rejectedUnlogged += 1; continue }
                Context.FastingRejection.NONE -> diagnostics.accepted += 1
            }

            val drift = (last.sgv.toDouble() - first.sgv.toDouble()) / (span / HOUR_MILLIS.toDouble())
            driftsByHour.getOrPut(key.hour) { mutableListOf() } += drift

            val below = sorted.count { it.sgv.toDouble() < lowGlucose }
            fastingBelow[key.hour] = (fastingBelow[key.hour] ?: 0) + below
            fastingTotal[key.hour] = (fastingTotal[key.hour] ?: 0) + sorted.size
        }

        return (0 until 24).map { hour ->
            val values = byHour[hour] ?: emptyList()
            val count = values.size
            val average = if (values.isEmpty()) null else values.sum() / count
            val inRange = values.count { it >= lowGlucose && it <= highGlucose }
            val above = values.count { it > highGlucose }
            val below = values.count { it < lowGlucose }
            val drifts = driftsByHour[hour] ?: emptyList()
            val total = fastingTotal[hour] ?: 0

            TherapyHourStat(
                hour = hour,
                averageGlucose = average,
                timeInRange = if (count == 0) 0.0 else inRange.toDouble() / count * 100,
                timeAbove = if (count == 0) 0.0 else above.toDouble() / count * 100,
                timeBelow = if (count == 0) 0.0 else below.toDouble() / count * 100,
                readingCount = count,
                dayCount = daysByHour[hour]?.size ?: 0,
                fastingDrift = if (drifts.size >= minFastingWindows(periodDays)) median(drifts) else null,
                fastingWindowCount = drifts.size,
                fastingBelowPercent = if (total == 0) 0.0 else (fastingBelow[hour] ?: 0).toDouble() / total * 100,
            )
        }
    }

    // MARK: - Basal

    private enum class HourVerdict { RISING, FALLING, HYPO_RISK, STEADY, UNKNOWN }

    private fun verdict(hour: TherapyHourStat, periodDays: Int): HourVerdict {
        val drift = hour.fastingDrift ?: return HourVerdict.UNKNOWN
        if (hour.fastingWindowCount < minFastingWindows(periodDays)) return HourVerdict.UNKNOWN
        if (hour.fastingBelowPercent > FASTING_HYPO_PERCENT) return HourVerdict.HYPO_RISK
        if (drift >= DRIFT_THRESHOLD) return HourVerdict.RISING
        if (drift <= -DRIFT_THRESHOLD) return HourVerdict.FALLING
        return HourVerdict.STEADY
    }

    // MARK: - Basal on a closed loop
    //
    // Fasting drift is the wrong instrument here, and not only because unflagged SMBs erase
    // every clean window. On a loop, glucose that holds flat overnight says the *algorithm*
    // did its job — it says nothing about whether the profile underneath it is right.
    //
    // The loop already publishes the answer. Every temp basal it sets is a statement about how
    // far your profile is from what the hour actually needed. Overnight is the only window most
    // looping users still have after meals, so it uses a shorter meal shadow, a lower deviation
    // bar, and one fewer required day so that signal can surface.

    /** A stretch during which the loop delivered at a rate other than the profile's. */
    private class TempBasalInterval(val start: Long, val end: Long, /** Absolute U/hr. */ val rate: Double)

    private fun isLoopOvernightHour(hour: Int): Boolean = hour >= LOOP_OVERNIGHT_START_HOUR || hour < LOOP_OVERNIGHT_END_HOUR
    private fun loopCarbShadowHours(hour: Int): Double = if (isLoopOvernightHour(hour)) LOOP_OVERNIGHT_CARB_SHADOW_HOURS else CARB_SHADOW_HOURS
    private fun loopDeviationThreshold(hour: Int): Double = if (isLoopOvernightHour(hour)) LOOP_OVERNIGHT_DEVIATION_THRESHOLD else LOOP_DEVIATION_THRESHOLD
    /** Overnight can speak from two clean nights; daytime still wants the fuller sample. */
    private fun minLoopDays(hour: Int, periodDays: Int): Int = if (isLoopOvernightHour(hour)) 2 else minFastingWindows(periodDays)

    private class LoopResult(
        val findings: List<TherapyFinding>,
        val steadyNotes: List<String>,
        val dataNotes: List<String>,
        val comparedHours: Int,
        val comparedDays: Int,
        val ratiosByHour: Map<Int, List<Double>>,
        val shadowedDaysByHour: Map<Int, Int>,
        val activityShadowedDaysByHour: Map<Int, Int>,
    ) {
        companion object {
            fun notes(vararg notes: String) = LoopResult(emptyList(), emptyList(), notes.toList(), 0, 0, emptyMap(), emptyMap(), emptyMap())
        }
    }

    /** What the loop actually delivered, hour by hour, against what the profile asked for. */
    private fun loopBasalFindings(
        entries: List<NightscoutGlucoseEntry>,
        settings: TherapyProfileSettings,
        context: Context,
        delivery: InsulinDeliveryContext,
        periodDays: Int,
        clock: Clock,
    ): LoopResult {
        if (settings.basal.isEmpty()) {
            return LoopResult.notes(
                "Basal — your loop's delivery can be compared against your profile only once " +
                    "BoostT1D knows what that profile is. Connect Nightscout, or enter your " +
                    "basal rates in Settings, and this section fills in."
            )
        }

        val intervals = tempBasalIntervals(context.treatments, settings, clock)
        // Systems that dose entirely by SMB upload no temp basals at all. Their insulin is
        // just as measurable — it arrives as boluses — so delivery is read from both, and a
        // day counts once either appears.
        val boluses = insulinDoses(context.treatments)
        val daysWithDelivery = (intervals.map { clock.startOfDay(it.start) } + boluses.map { clock.startOfDay(it.at) }).toSet()

        if (daysWithDelivery.size < MIN_LOOP_DAYS_WITH_TEMP_BASALS) {
            return LoopResult.notes(unattributedLoopBasalNote(delivery))
        }

        // Ratio of delivered to profile, per hour, per day.
        val ratiosByHour = mutableMapOf<Int, MutableList<Double>>()
        val deliveredByHour = mutableMapOf<Int, MutableList<Double>>()
        val shadowedDaysByHour = mutableMapOf<Int, Int>()
        val activityShadowedDaysByHour = mutableMapOf<Int, Int>()
        var carbShadowedHours = 0
        var activityShadowedHours = 0

        val hourBuckets = entries.map { DayHour(clock.startOfDay(it.epochMilliseconds), clock.hour(it.epochMilliseconds)) }.toSet()

        for (bucket in hourBuckets) {
            if (bucket.day !in daysWithDelivery) continue
            val start = clock.addHours(bucket.day, bucket.hour)
            val end = clock.addHours(start, 1)

            // Meals are logged reliably even when doses are not, so the carb shadow is still
            // worth applying: an hour spent covering dinner says nothing about the baseline.
            val mealShadow = loopCarbShadowHours(bucket.hour)
            if (context.hasCarbs(start - (mealShadow * HOUR_MILLIS).toLong(), end)) {
                carbShadowedHours += 1
                shadowedDaysByHour[bucket.hour] = (shadowedDaysByHour[bucket.hour] ?: 0) + 1
                continue
            }
            if (context.hasExercise(start - (EXERCISE_SHADOW_HOURS * HOUR_MILLIS).toLong(), end)) {
                activityShadowedHours += 1
                activityShadowedDaysByHour[bucket.hour] = (activityShadowedDaysByHour[bucket.hour] ?: 0) + 1
                continue
            }
            val profileRate = settings.basalAt(bucket.hour)?.takeIf { it > 0 } ?: continue

            val delivered = deliveredRate(start, end, intervals, boluses, profileRate)
            ratiosByHour.getOrPut(bucket.hour) { mutableListOf() } += delivered / profileRate
            deliveredByHour.getOrPut(bucket.hour) { mutableListOf() } += delivered
        }

        val verdicts = arrayOfNulls<TherapyDirection>(24)
        for (hour in 0 until 24) {
            val minDays = minLoopDays(hour, periodDays)
            val ratios = ratiosByHour[hour] ?: continue
            if (ratios.size < minDays) continue
            val ratio = median(ratios)
            val deviation = loopDeviationThreshold(hour)
            verdicts[hour] = when {
                ratio >= 1 + deviation -> TherapyDirection.INCREASE
                ratio <= 1 - deviation -> TherapyDirection.DECREASE
                abs(ratio - 1) <= LOOP_STEADY_BAND -> TherapyDirection.HOLD
                else -> null
            }
        }

        val findings = mutableListOf<TherapyFinding>()
        for (direction in listOf(TherapyDirection.INCREASE, TherapyDirection.DECREASE)) {
            for (run in circularRuns { verdicts[it] == direction }) {
                if (run.size < 2) continue
                findings += loopBasalFinding(run, direction, ratiosByHour, deliveredByHour, settings, delivery.isClosedLoop)
            }
        }

        val steadyNotes = mutableListOf<String>()
        for (run in circularRuns { verdicts[it] == TherapyDirection.HOLD }) {
            if (run.size < 3) continue
            val days = run.mapNotNull { ratiosByHour[it]?.size }.maxOrNull() ?: 0
            steadyNotes += "Basal ${windowLabel(run)} — delivery stayed within ${(LOOP_STEADY_BAND * 100).toInt()}% " +
                "of your profile across $days days. The baseline looks right here."
        }

        val dataNotes = mutableListOf<String>()
        val unread = (0 until 24).filter { verdicts[it] == null }
        if (unread.size >= 6) {
            var note = "Basal — ${unread.size} hours could not be compared against your loop's " +
                "delivery (too few clean days outside meal and exercise shadows). " +
                "Overnight uses a ${LOOP_OVERNIGHT_CARB_SHADOW_HOURS.toInt()}h meal shadow; daytime " +
                "uses ${CARB_SHADOW_HOURS.toInt()}h, and logged exercise shadows " +
                "${EXERCISE_SHADOW_HOURS.toInt()}h."
            if (carbShadowedHours > 0) note += " $carbShadowedHours hour-days were skipped as meal coverage rather than baseline."
            if (activityShadowedHours > 0) note += " $activityShadowedHours hour-days were skipped because logged exercise could still be affecting delivery."
            dataNotes += note
        }

        val comparedHours = ratiosByHour.values.sumOf { it.size }
        val comparedDays = hourBuckets.filter { it.day in daysWithDelivery }.map { it.day }.toSet().size

        return LoopResult(findings, steadyNotes, dataNotes, comparedHours, comparedDays, ratiosByHour, shadowedDaysByHour, activityShadowedDaysByHour)
    }

    private fun loopBasalFinding(
        run: List<Int>,
        direction: TherapyDirection,
        ratiosByHour: Map<Int, List<Double>>,
        deliveredByHour: Map<Int, List<Double>>,
        settings: TherapyProfileSettings,
        isLoop: Boolean,
    ): TherapyFinding {
        val ratios = run.flatMap { ratiosByHour[it] ?: emptyList() }
        val ratio = median(ratios)
        val days = run.mapNotNull { ratiosByHour[it]?.size }.maxOrNull() ?: 0
        val label = windowLabel(run)
        val current = settings.basalAt(run[0])
        // Hours join a window by *ratio*, so a window can span profile segments with very
        // different absolute rates. `current` is the rate at the window's first hour, so
        // delivery has to be expressed at that rate too: pooling raw delivered U/hr across a
        // window whose profile steps inside it compares two baselines, and can hand a
        // "withholds insulin" finding a suggestion to raise the rate.
        val observed = current?.let { it * ratio } ?: median(run.flatMap { deliveredByHour[it] ?: emptyList() })
        val deviation = schoolbookRound(abs(ratio - 1) * 100).toInt()

        // The loop's own delivery is the measurement, so the step is toward it — halved and
        // capped exactly as the fasting path does. Clinicians step; they do not jump.
        var suggested: Double? = null
        var percent: Double? = null
        if (current != null && current > 0) {
            val rawDelta = observed - current
            val cap = max(current * MAX_CHANGE_PERCENT / 100, 0.05)
            val bounded = min(max(rawDelta * STEP_FRACTION, -cap), cap)
            val rounded = schoolbookRound(bounded / 0.05) * 0.05
            if (abs(rounded) >= 0.05) {
                suggested = max(current + rounded, 0.0)
                percent = rounded / current * 100
            }
        }

        val strength = when {
            days >= 5 && ratios.size >= run.size * 4 -> TherapyEvidenceStrength.STRONG
            days >= 3 -> TherapyEvidenceStrength.MODERATE
            else -> TherapyEvidenceStrength.LIMITED
        }

        val priority = when {
            // A profile running too strong is the one the loop cannot always save you from:
            // it has a floor at zero basal, and below that it can only feed you.
            direction == TherapyDirection.DECREASE && deviation >= 25 -> Priority.HIGH
            deviation >= 30 -> Priority.HIGH
            deviation >= 20 -> Priority.MEDIUM
            else -> Priority.LOW
        }

        val mealShadowHours = if (run.all(::isLoopOvernightHour)) {
            LOOP_OVERNIGHT_CARB_SHADOW_HOURS
        } else if (run.any(::isLoopOvernightHour)) {
            LOOP_OVERNIGHT_CARB_SHADOW_HOURS
        } else {
            CARB_SHADOW_HOURS
        }

        val evidence = mutableListOf(
            "Median $deviation% ${if (direction == TherapyDirection.INCREASE) "above" else "below"} profile across $label, on $days days.",
            "From what your system actually delivered — temp basals and doses, not inferred from glucose.",
            "Hours within ${mealShadowHours.toInt()}h of a meal or ${EXERCISE_SHADOW_HOURS.toInt()}h of logged exercise are excluded, " +
                "so this is baseline delivery rather than meal or activity coverage.",
        )
        if (current != null) {
            val stepped = settings.varies(settings.basal, run)
            evidence += "Profile basal at ${hourLabel(run[0])}: ${two(current)} U/hr; " +
                (if (stepped) "delivery at that rate: " else "median delivered: ") +
                "${two(observed)} U/hr" +
                (if (stepped) " (the rate changes inside this window, so delivery is stated as the median gap applied to this hour's rate)." else ".")
        }

        val caveats = mutableListOf<String>()
        if (isLoop) {
            caveats += "The algorithm disagreed with your profile — that does not mean the profile caused a problem. " +
                "A loop covering a known gap may be keeping you exactly where you want to be."
            caveats += "Closing the gap usually means acting sooner and swinging less, but removes headroom the algorithm was using. One for your care team."
        } else {
            caveats += "This counts every dose in these hours, including corrections. Correcting the same window night after night " +
                "is itself evidence about the baseline, but it is not the same as measuring basal directly."
            caveats += "Meal hours are excluded, so this is not meal insulin — but a late or unrecorded meal would still land here."
        }
        if (strength == TherapyEvidenceStrength.LIMITED) {
            caveats += "Based on $days days — thin enough that another week could change it."
        }
        caveats += "Basal changes usually start 1–2h early, so $label is set from ${hourLabel((run[0] + 22) % 24)}."

        val actor = if (isLoop) "Your loop ran" else "You took"
        val headline = if (direction == TherapyDirection.INCREASE) {
            "$actor $deviation% above profile here — topping up a baseline that reads light."
        } else {
            "$actor $deviation% below profile here — less than your profile asked for."
        }

        return TherapyFinding(
            parameter = TherapyParameter.BASAL,
            startHour = run[0],
            endHour = (run[run.size - 1] + 1) % 24,
            windowLabel = label,
            title = if (isLoop) {
                if (direction == TherapyDirection.INCREASE) "Loop adds insulin $label" else "Loop withholds insulin $label"
            } else {
                if (direction == TherapyDirection.INCREASE) "More insulin than profile $label" else "Less insulin than profile $label"
            },
            headline = headline,
            direction = direction,
            priority = priority,
            strength = strength,
            currentValue = current,
            observedValue = observed,
            suggestedValue = suggested,
            percentChange = percent,
            sampleLabel = "${ratios.size} hours across $days days",
            evidence = evidence,
            rationale = if (isLoop) {
                "Every temp basal is the algorithm stating how far your baseline was from what the hour needed. " +
                    "A consistent gap, day after day, is the clearest read available — fasting glucose on a loop " +
                    "mostly measures how well the algorithm compensated."
            } else {
                "Total insulin delivered in hours with no food acting, against what the profile asked for. " +
                    "Used here because there were too few uninterrupted fasting stretches to read basal the usual way."
            },
            caveats = caveats,
            doctorQuestion = if (direction == TherapyDirection.INCREASE) {
                "My loop runs about $deviation% above my profile basal $label most days — should the profile rate be raised so it starts from the right place?"
            } else {
                "My loop runs about $deviation% below my profile basal $label most days — is the profile rate higher than I need in that window?"
            },
        )
    }

    /**
     * Temp basals as absolute-rate intervals.
     *
     * Uploaders disagree about the fields: `absolute` is the U/hr rate when present, `rate` is
     * the same thing for some and a percentage of profile for others. A value above 10 cannot
     * be a basal rate in U/hr, so it is read as a percentage.
     */
    private fun tempBasalIntervals(treatments: List<NightscoutTreatment>, settings: TherapyProfileSettings, clock: Clock): List<TempBasalInterval> {
        val result = mutableListOf<TempBasalInterval>()
        // Uploaders end a temp basal early with a zero-duration "Temp Basal" record. Dropping
        // those leaves the cancelled interval billed for its full nominal length — insulin that
        // was never delivered, read as the loop running above profile.
        val cancels = mutableListOf<Long>()

        for (treatment in treatments) {
            if (treatment.eventType != "Temp Basal") continue
            val minutes = treatment.duration ?: continue
            val start = MealOutcomeBuilder.treatmentMillis(treatment)
            if (minutes <= 0) { cancels += start; continue }
            val end = start + minutes * 60_000L

            val absolute = treatment.absolute
            val raw = treatment.rate
            val rate: Double? = when {
                absolute != null -> absolute
                raw != null -> if (raw > 10) settings.basalAt(clock.hour(start))?.let { it * raw / 100 } else raw
                else -> null
            }
            if (rate == null || rate < 0) continue
            result += TempBasalInterval(start, end, rate)
        }

        // A temp basal ends at whichever comes first: the next one being set, a cancel record,
        // or its own stated duration. Without this, overlapping records double-count the loop's
        // activity and cancelled ones are counted long after they stopped.
        cancels.sort()
        val sorted = result.sortedBy { it.start }
        return sorted.mapIndexedNotNull { index, interval ->
            var end = interval.end
            if (index + 1 < sorted.size) end = min(end, sorted[index + 1].start)
            cancels.firstOrNull { it > interval.start }?.let { end = min(end, it) }
            if (end <= interval.start) return@mapIndexedNotNull null
            if (end < interval.end) TempBasalInterval(interval.start, end, interval.rate) else interval
        }
    }

    /** One dose of insulin, whoever decided on it. */
    private class InsulinDose(val at: Long, val units: Double)

    /** Every dose in the window, deliberately not filtered by attribution. */
    private fun insulinDoses(treatments: List<NightscoutTreatment>): List<InsulinDose> =
        treatments.mapNotNull { t -> t.insulin?.takeIf { it > 0 }?.let { InsulinDose(MealOutcomeBuilder.treatmentMillis(t), it) } }

    /**
     * Total insulin delivered across one hour, expressed as a rate: temp basals time-weighted,
     * the profile rate filling any uncovered time, plus every bolus that landed inside the hour.
     */
    private fun deliveredRate(start: Long, end: Long, intervals: List<TempBasalInterval>, boluses: List<InsulinDose>, profileRate: Double): Double {
        val span = end - start
        if (span <= 0) return profileRate

        var units = 0.0
        var covered = 0L
        for (interval in intervals) {
            if (interval.end <= start || interval.start >= end) continue
            val overlap = min(interval.end, end) - max(interval.start, start)
            if (overlap <= 0) continue
            units += interval.rate * overlap / HOUR_MILLIS
            covered += overlap
        }
        units += profileRate * max(span - covered, 0L) / HOUR_MILLIS

        for (bolus in boluses) {
            if (bolus.at >= start && bolus.at < end) units += bolus.units
        }
        return units / (span / HOUR_MILLIS.toDouble())
    }

    private class NotesResult(val findings: List<TherapyFinding>, val steadyNotes: List<String>, val dataNotes: List<String>)

    private fun basalFindings(
        hours: List<TherapyHourStat>,
        settings: TherapyProfileSettings,
        context: Context,
        periodDays: Int,
        diagnostics: FastingDiagnostics,
        formatter: TherapyGlucoseFormatter,
    ): NotesResult {
        val verdicts = hours.map { verdict(it, periodDays) }
        val evaluated = verdicts.count { it != HourVerdict.UNKNOWN }

        if (evaluated < 2) {
            // When the log is the thing that is missing, say that and nothing else.
            if (diagnostics.rejectedUnlogged > diagnostics.accepted) {
                return NotesResult(emptyList(), emptyList(), listOf(unloggedBasalNote(diagnostics)))
            }

            val reasons = breakdown(listOf(
                diagnostics.rejectedCarbs to "were within ${CARB_SHADOW_HOURS.toInt()}h of food",
                diagnostics.rejectedInsulin to "had bolus insulin still acting",
                diagnostics.rejectedExercise to "were within ${EXERCISE_SHADOW_HOURS.toInt()}h of logged exercise",
                diagnostics.rejectedUnlogged to "were on days with nothing logged",
                diagnostics.rejectedSparse to "had gaps in the glucose data",
            ))
            var note = "Basal — of ${diagnostics.bucketsExamined} clock-hours in this period, " +
                "${diagnostics.accepted} were free of food, bolus insulin, and logged exercise"
            note += if (reasons.isEmpty()) "." else "; $reasons."
            note += " Basal can only be read from hours where nothing else is moving glucose, " +
                "and each hour of the day needs ${minFastingWindows(periodDays)} such windows before it counts."
            if (periodDays < 7) note += " Switching to the 7-day period usually clears this."
            return NotesResult(emptyList(), emptyList(), listOf(note))
        }

        val findings = mutableListOf<TherapyFinding>()

        for (run in circularRuns { verdicts[it] == HourVerdict.RISING }) {
            if (run.size < 2) continue
            findings += basalFinding(run, hours, settings, context, formatter, TherapyDirection.INCREASE, hypoRisk = false)
        }

        for (run in circularRuns { verdicts[it] == HourVerdict.FALLING || verdicts[it] == HourVerdict.HYPO_RISK }) {
            if (run.size < 2) continue
            val hypoRisk = run.any { verdicts[it] == HourVerdict.HYPO_RISK }
            findings += basalFinding(run, hours, settings, context, formatter, TherapyDirection.DECREASE, hypoRisk)
        }

        val steadyNotes = mutableListOf<String>()
        for (run in circularRuns { verdicts[it] == HourVerdict.STEADY }) {
            if (run.size < 3) continue
            val windows = run.sumOf { hours[it].fastingWindowCount }
            steadyNotes += "Basal ${windowLabel(run)} — fasting glucose held flat across $windows clean hours. Nothing to change here."
        }

        val dataNotes = mutableListOf<String>()
        val unknown = (0 until 24).filter { verdicts[it] == HourVerdict.UNKNOWN }
        if (unknown.size >= 6) {
            // Name what actually consumed the hours. Food, insulin, unlogged days and sensor
            // gaps have different fixes.
            val reasons = breakdown(listOf(
                diagnostics.rejectedCarbs to "were within ${CARB_SHADOW_HOURS.toInt()}h of food",
                diagnostics.rejectedInsulin to "had bolus insulin still acting",
                diagnostics.rejectedExercise to "were within ${EXERCISE_SHADOW_HOURS.toInt()}h of logged exercise",
                diagnostics.rejectedUnlogged to "were on days with nothing logged",
                diagnostics.rejectedSparse to "had gaps in the glucose data",
            ))
            var note = "Basal — ${unknown.size} hours of the day had too little fasting time " +
                "to read (fewer than ${minFastingWindows(periodDays)} clean windows each). " +
                "Of ${diagnostics.bucketsExamined} clock-hours in this period, ${diagnostics.accepted} came up clean"
            note += if (reasons.isEmpty()) "." else "; $reasons."
            note += " Those hours appear in the profile above but were not judged."
            if (periodDays < 7) note += " The 7-day period gives each hour more chances to come up clean."
            dataNotes += note
        }

        return NotesResult(findings, steadyNotes, dataNotes)
    }

    private fun basalFinding(
        run: List<Int>,
        hours: List<TherapyHourStat>,
        settings: TherapyProfileSettings,
        context: Context,
        formatter: TherapyGlucoseFormatter,
        direction: TherapyDirection,
        hypoRisk: Boolean,
    ): TherapyFinding {
        val drifts = run.mapNotNull { hours[it].fastingDrift }
        val meanDrift = if (drifts.isEmpty()) 0.0 else drifts.sum() / drifts.size
        val totalChange = drifts.sum()
        val windows = run.sumOf { hours[it].fastingWindowCount }
        val days = run.maxOfOrNull { hours[it].dayCount } ?: 0
        val label = windowLabel(run)

        val current = settings.basalAt(run[0])
        val isf = settings.isfAt(run[0])

        // Units work out directly: (mg/dL per hour) ÷ (mg/dL per unit) = units per hour.
        var suggested: Double? = null
        var percent: Double? = null
        if (current != null && isf != null && isf > 0) {
            val rawDelta = meanDrift / isf
            val cap = max(current * MAX_CHANGE_PERCENT / 100, 0.05)
            val stepped = rawDelta * STEP_FRACTION
            val bounded = min(max(stepped, -cap), cap)
            val rounded = schoolbookRound(bounded / 0.05) * 0.05
            if (abs(rounded) >= 0.05) {
                val target = max(current + rounded, 0.0)
                suggested = target
                percent = (target - current) / current * 100
            }
        }

        val strength = when {
            windows >= run.size * 4 && days >= 5 -> TherapyEvidenceStrength.STRONG
            windows >= run.size * 2 && days >= 3 -> TherapyEvidenceStrength.MODERATE
            else -> TherapyEvidenceStrength.LIMITED
        }

        val priority = when {
            hypoRisk -> Priority.HIGH
            abs(totalChange) >= 40 -> Priority.HIGH
            abs(totalChange) >= 25 -> Priority.MEDIUM
            else -> Priority.LOW
        }

        val evidence = mutableListOf(
            "Fasting glucose ${if (direction == TherapyDirection.INCREASE) "rose" else "fell"} " +
                "${formatter.format(abs(totalChange))} ${formatter.unitLabel} across this window " +
                "(${formatter.format(abs(meanDrift))} ${formatter.unitLabel} per hour).",
            "Measured only in hours with no carbs for ${CARB_SHADOW_HOURS.toInt()}h and no bolus " +
                "insulin for ${context.insulinDuration.toInt()}h, and no logged exercise for " +
                "${EXERCISE_SHADOW_HOURS.toInt()}h — nothing else recorded was moving glucose.",
        )
        if (hypoRisk) {
            val worst = run.maxOfOrNull { hours[it].fastingBelowPercent } ?: 0.0
            evidence += "Up to ${schoolbookRound(worst).toInt()}% of fasting readings in this window were below your low target."
        }
        if (current != null) {
            evidence += "Current basal at ${hourLabel(run[0])}: ${two(current)} U/hr" +
                (if (settings.varies(settings.basal, run)) " (the rate changes inside this window)." else ".")
        }

        val caveats = mutableListOf<String>()
        if (context.isClosedLoop) {
            caveats += if (direction == TherapyDirection.INCREASE) {
                "Your loop is already adding insulin against this rise, so the underlying profile gap is likely at least this large."
            } else {
                "Your loop is already cutting insulin here. A baseline profile that is too strong makes it work against itself all night."
            }
        }
        caveats += "Basal changes are normally started 1–2 hours before the window they are meant to " +
            "cover, because insulin takes that long to act — so a change for $label is " +
            "usually made from ${hourLabel((run[0] + 22) % 24)}."
        if (strength == TherapyEvidenceStrength.LIMITED) {
            caveats += "Based on $windows fasting hours — thin enough that another week of data could change it."
        }

        val headline = when {
            hypoRisk -> "Glucose drops into your low range while fasting here — the most likely single cause is too much background insulin."
            direction == TherapyDirection.INCREASE ->
                "Glucose climbs ${formatter.format(abs(totalChange))} ${formatter.unitLabel} with no food and no bolus on board — background insulin is not holding this window."
            else ->
                "Glucose falls ${formatter.format(abs(totalChange))} ${formatter.unitLabel} with nothing acting on it — background insulin is running ahead of what this window needs."
        }

        return TherapyFinding(
            parameter = TherapyParameter.BASAL,
            startHour = run[0],
            endHour = (run[run.size - 1] + 1) % 24,
            windowLabel = label,
            title = "${periodName(run[0])} basal — $label",
            headline = headline,
            direction = direction,
            priority = priority,
            strength = strength,
            currentValue = current,
            observedValue = null,
            suggestedValue = suggested,
            percentChange = percent,
            sampleLabel = "$windows fasting hours across $days days",
            evidence = evidence,
            rationale = if (direction == TherapyDirection.INCREASE) {
                "Basal insulin's whole job is to hold glucose level between meals. A consistent climb in a window " +
                    "where nothing else is acting is the textbook signal that the rate covering it is set too low."
            } else {
                "A consistent fall with no food and no bolus on board means the rate covering this window is " +
                    "delivering more than the body needs at that hour."
            },
            caveats = caveats,
            doctorQuestion = if (direction == TherapyDirection.INCREASE) {
                "My glucose rises about ${formatter.format(abs(totalChange))} ${formatter.unitLabel} between $label while fasting — should we look at the basal rate covering that window?"
            } else {
                "My glucose drifts down about ${formatter.format(abs(totalChange))} ${formatter.unitLabel} between $label while fasting — should the basal rate covering that window come down?"
            },
        )
    }

    // MARK: - ISF

    private class CorrectionSample(val at: Long, val hour: Int, val units: Double, val startGlucose: Double, val endGlucose: Double) {
        /** mg/dL dropped per unit. */
        val measuredISF: Double get() = (startGlucose - endGlucose) / units
    }

    /**
     * Corrections that stood alone: no food, no other dose, and glucose high enough that the
     * dose was genuinely a correction. Anything else measures the meal, not the ISF.
     */
    private fun cleanCorrections(entries: List<NightscoutGlucoseEntry>, context: Context, diagnostics: CorrectionDiagnostics, clock: Clock): List<CorrectionSample> {
        val sorted = entries.sortedBy { it.epochMilliseconds }
        val observation = 3 * HOUR_MILLIS
        val samples = mutableListOf<CorrectionSample>()

        for (treatment in context.treatments) {
            val units = treatment.insulin ?: continue
            if (units < 0.3) continue
            if ((treatment.carbs ?: 0.0) != 0.0) continue
            diagnostics.candidates += 1
            if (treatment.isAlgorithmDelivered) { diagnostics.rejectedAlgorithm += 1; continue }

            val at = MealOutcomeBuilder.treatmentMillis(treatment)
            if (at <= EPOCH_GUARD) continue
            val end = at + observation

            // Nothing else may touch the window: no carbs from two hours before, and no
            // second dose of the user's own inside it.
            if (context.hasCarbs(at - 2 * HOUR_MILLIS, end)) { diagnostics.rejectedCarbsNearby += 1; continue }
            val insulinWindowStart = at - (context.insulinDuration * HOUR_MILLIS).toLong()
            if (context.manualBolusTimes.any { it != at && it > insulinWindowStart && it <= end }) {
                diagnostics.rejectedOverlappingDose += 1; continue
            }
            if (context.hasExercise(at - (EXERCISE_SHADOW_HOURS * HOUR_MILLIS).toLong(), end)) { diagnostics.rejectedExercise += 1; continue }

            val start = nearestGlucose(sorted, at, 15 * 60_000L)
            val finish = nearestGlucose(sorted, end, 20 * 60_000L)
            if (start == null || finish == null) { diagnostics.rejectedMissingGlucose += 1; continue }
            // Below this a dose is not correcting anything, and the arithmetic is dominated by
            // whatever else was going on.
            if (start < 150) { diagnostics.rejectedNotACorrection += 1; continue }

            val isf = (start - finish) / units
            // Values outside this range are not a sensitivity measurement, they are a missing
            // carb entry or a sensor artefact.
            if (isf <= 5 || isf >= 400) { diagnostics.rejectedImplausible += 1; continue }
            diagnostics.accepted += 1

            samples += CorrectionSample(at, clock.hour(at), units, start, finish)
        }

        return samples
    }

    private fun isfFindings(
        corrections: List<CorrectionSample>,
        settings: TherapyProfileSettings,
        context: Context,
        diagnostics: CorrectionDiagnostics,
        formatter: TherapyGlucoseFormatter,
    ): NotesResult {
        if (corrections.isEmpty()) {
            if (diagnostics.candidates == 0) {
                return NotesResult(emptyList(), emptyList(), listOf(
                    "Correction factor — no doses without carbs were found in this period, so there was nothing to measure sensitivity from."
                ))
            }
            val reasons = breakdown(listOf(
                diagnostics.rejectedCarbsNearby to "had food inside the window",
                diagnostics.rejectedOverlappingDose to "overlapped another dose",
                diagnostics.rejectedExercise to "were near logged exercise",
                diagnostics.rejectedNotACorrection to "started below 150 mg/dL, so they were not correcting a high",
                diagnostics.rejectedMissingGlucose to "had no reading three hours later",
                diagnostics.rejectedAlgorithm to "were delivered by your loop rather than chosen by you",
                diagnostics.rejectedImplausible to "produced a drop too far outside the plausible range to use",
            ))
            var note = "Correction factor — of ${diagnostics.candidates} carb-free doses, none stood alone long enough to measure"
            note += if (reasons.isEmpty()) "." else ": $reasons."
            note += " Sensitivity needs a correction with no food for two hours before and three hours after."
            return NotesResult(emptyList(), emptyList(), listOf(note))
        }

        val findings = mutableListOf<TherapyFinding>()
        val steadyNotes = mutableListOf<String>()
        val dataNotes = mutableListOf<String>()

        for (window in dayWindows) {
            val inWindow = corrections.filter { window.contains(it.hour) }
            if (inWindow.size < MIN_CORRECTIONS_PER_WINDOW) {
                if (inWindow.isNotEmpty()) {
                    dataNotes += "Correction factor ${window.label} — only ${inWindow.size} clean " +
                        "correction${if (inWindow.size == 1) "" else "s"} here, $MIN_CORRECTIONS_PER_WINDOW needed before it means anything."
                }
                continue
            }

            val measured = median(inWindow.map { it.measuredISF })
            val hours = window.hours
            val current = settings.isfAt(window.start)
            if (current == null) {
                steadyNotes += "Correction factor ${window.label} — 1 unit lowered you about " +
                    "${formatter.format(measured)} ${formatter.unitLabel} across ${inWindow.size} corrections. No configured ISF to compare it against."
                continue
            }

            val diffPercent = (measured - current) / current * 100
            if (abs(diffPercent) < ISF_MISMATCH_PERCENT) {
                steadyNotes += "Correction factor ${window.label} — measured " +
                    "${formatter.format(measured)} ${formatter.unitLabel} per unit against a " +
                    "setting of ${formatter.format(current)}; within ${schoolbookRound(abs(diffPercent)).toInt()}%. " +
                    "Corrections are landing where they should."
                continue
            }

            // Higher measured ISF means each unit went further than the setting assumes, so the
            // setting should rise and future corrections get smaller.
            val direction = if (measured > current) TherapyDirection.INCREASE else TherapyDirection.DECREASE
            val cap = current * MAX_CHANGE_PERCENT / 100
            val stepped = (measured - current) * STEP_FRACTION
            val bounded = min(max(stepped, -cap), cap)
            val suggested = schoolbookRound(current + bounded)
            val percent = (suggested - current) / current * 100

            val strength = when {
                inWindow.size >= 8 -> TherapyEvidenceStrength.STRONG
                inWindow.size >= 5 -> TherapyEvidenceStrength.MODERATE
                else -> TherapyEvidenceStrength.LIMITED
            }

            val priority = when {
                direction == TherapyDirection.INCREASE && abs(diffPercent) >= 30 -> Priority.HIGH
                abs(diffPercent) >= 30 -> Priority.MEDIUM
                else -> Priority.LOW
            }

            val caveats = mutableListOf(
                "Measured three hours after each dose, by which point most of a rapid-acting unit has acted. Earlier readings would understate it."
            )
            if (context.isClosedLoop) {
                caveats += "Only your own corrections were counted — the loop's automatic micro-boluses were excluded, since they are not doses you chose."
            }
            if (settings.varies(settings.isf, hours)) {
                caveats += "Your ISF already changes inside this window; the comparison uses the value at ${hourLabel(window.start)}."
            }
            if (strength == TherapyEvidenceStrength.LIMITED) {
                caveats += "Three to four corrections is the minimum this can be read from — treat it as a lead, not a conclusion."
            }

            findings += TherapyFinding(
                parameter = TherapyParameter.ISF,
                startHour = window.start,
                endHour = window.end,
                windowLabel = window.label,
                title = "${window.name} corrections — ${window.label}",
                headline = if (direction == TherapyDirection.INCREASE) {
                    "1 unit lowered you about ${formatter.format(measured)} ${formatter.unitLabel} here, more than the ${formatter.format(current)} your setting assumes — corrections in this window are landing harder than intended."
                } else {
                    "1 unit lowered you only about ${formatter.format(measured)} ${formatter.unitLabel} here against the ${formatter.format(current)} your setting assumes — corrections in this window are falling short."
                },
                direction = direction,
                priority = priority,
                strength = strength,
                currentValue = current,
                observedValue = measured,
                suggestedValue = suggested,
                percentChange = percent,
                sampleLabel = "${inWindow.size} standalone corrections",
                evidence = listOf(
                    "Median measured drop: ${formatter.format(measured)} ${formatter.unitLabel} per unit.",
                    "Configured ISF at ${hourLabel(window.start)}: ${formatter.format(current)} ${formatter.unitLabel} per unit.",
                    "Difference: ${if (diffPercent > 0) "+" else ""}${schoolbookRound(diffPercent).toInt()}%.",
                    "Each dose started above ${formatter.format(150.0)} ${formatter.unitLabel} with no food for two hours either side.",
                ),
                rationale = if (direction == TherapyDirection.INCREASE) {
                    "Insulin sensitivity moves through the day — usually higher at night and lower around dawn. " +
                        "When measured drops consistently beat the setting, every correction in that window is a little " +
                        "larger than it needs to be, and the overshoot shows up as a low a few hours later."
                } else {
                    "When the measured drop consistently falls short of the setting, corrections in this window " +
                        "under-deliver, and the high they were meant to fix is still there hours later — often followed " +
                        "by a second correction and stacked insulin."
                },
                caveats = caveats,
                doctorQuestion = "Across ${inWindow.size} corrections between ${window.label}, 1 unit moved me about ${formatter.format(measured)} ${formatter.unitLabel} rather than the ${formatter.format(current)} my pump assumes — is my correction factor for that window worth revisiting?",
            )
        }

        return NotesResult(findings, steadyNotes, dataNotes)
    }

    // MARK: - Carb ratio

    private class MealSample(
        val at: Long, val hour: Int, val carbs: Double, val units: Double,
        val baseline: Double, val fourHour: Double, val peak: Double, val wentLow: Boolean,
    ) {
        val excursion: Double get() = fourHour - baseline
        val peakRise: Double get() = peak - baseline
    }

    /**
     * Meals big enough to read, bolused once, and left alone for four hours. Anything re-dosed
     * or re-fed in between measures the second decision, not the ratio.
     */
    private fun cleanMeals(
        entries: List<NightscoutGlucoseEntry>,
        context: Context,
        lowGlucose: Double,
        samplingMinutes: Double,
        diagnostics: MealDiagnostics,
        clock: Clock,
    ): List<MealSample> {
        val sorted = entries.sortedBy { it.epochMilliseconds }
        val samples = mutableListOf<MealSample>()

        val mealEvents = context.treatments
            .mapNotNull { t -> t.carbs?.takeIf { it >= 15 }?.let { MealOutcomeBuilder.treatmentMillis(t) to it } }
            .toMutableList()
        for (entry in context.foodLogEntries) {
            val carbs = entry.carbsGrams?.takeIf { it >= 15 } ?: continue
            val covered = mealEvents.any { abs(it.first - entry.recordedAtMillis) < 15 * 60_000L }
            if (!covered) mealEvents += entry.recordedAtMillis to carbs
        }

        // Four hours of readings at whatever resolution this data actually has, allowing for
        // the odd missed sync. Hard-coding a count assumes a five-minute sensor feed and
        // silently discards every meal in a ten-minute cache.
        val expectedReadings = max(6, ((240 / max(samplingMinutes, 1.0)) * 0.55).toInt())

        for ((at, carbs) in mealEvents.sortedBy { it.first }) {
            if (at <= EPOCH_GUARD) continue
            diagnostics.candidates += 1
            val end = at + 4 * HOUR_MILLIS

            // The dose that covered this meal: anything from half an hour before to
            // three-quarters of an hour after, whoever delivered it.
            val units = context.treatments
                .filter { (it.insulin ?: 0.0) > 0 }
                .filter { val time = MealOutcomeBuilder.treatmentMillis(it); time >= at - 30 * 60_000L && time <= at + 45 * 60_000L }
                .sumOf { it.insulin ?: 0.0 }
            if (units <= 0) { diagnostics.rejectedNoBolus += 1; continue }

            if (context.hasCarbs(at + 15 * 60_000L, end)) { diagnostics.rejectedLaterCarbs += 1; continue }
            if (context.hasManualBolus(at + 45 * 60_000L, end)) { diagnostics.rejectedLaterDose += 1; continue }
            if (context.hasExercise(at - (EXERCISE_SHADOW_HOURS * HOUR_MILLIS).toLong(), end)) { diagnostics.rejectedExercise += 1; continue }

            val baseline = nearestGlucose(sorted, at, 20 * 60_000L)
            val fourHour = nearestGlucose(sorted, end, 20 * 60_000L)
            if (baseline == null || fourHour == null) { diagnostics.rejectedMissingGlucose += 1; continue }

            val during = sorted.filter { it.epochMilliseconds >= at && it.epochMilliseconds <= end }
            if (during.size < expectedReadings) { diagnostics.rejectedSparseGlucose += 1; continue }
            diagnostics.accepted += 1
            val peak = during.maxOfOrNull { it.sgv.toDouble() } ?: baseline
            val wentLow = during.any { it.sgv.toDouble() < lowGlucose }

            samples += MealSample(at, clock.hour(at), carbs, units, baseline, fourHour, peak, wentLow)
        }

        return samples
    }

    private fun carbRatioFindings(
        meals: List<MealSample>,
        settings: TherapyProfileSettings,
        context: Context,
        diagnostics: MealDiagnostics,
        formatter: TherapyGlucoseFormatter,
    ): NotesResult {
        if (meals.isEmpty()) {
            if (diagnostics.candidates == 0) {
                return NotesResult(emptyList(), emptyList(), listOf(
                    "Carb ratio — no meals of 15 g or more were logged in this period, so there was nothing to measure the ratio against."
                ))
            }
            val reasons = breakdown(listOf(
                diagnostics.rejectedLaterCarbs to "had more carbs within four hours",
                diagnostics.rejectedLaterDose to "had another dose within four hours",
                diagnostics.rejectedExercise to "were near logged exercise",
                diagnostics.rejectedNoBolus to "had no insulin recorded near the meal",
                diagnostics.rejectedSparseGlucose to "had gaps in the four hours after",
                diagnostics.rejectedMissingGlucose to "had no reading at the meal or four hours later",
            ))
            var note = "Carb ratio — of ${diagnostics.candidates} meals, none were left alone long enough to read"
            note += if (reasons.isEmpty()) "." else ": $reasons."
            note += " A ratio can only be measured from a meal bolused once and not eaten over for four hours."
            return NotesResult(emptyList(), emptyList(), listOf(note))
        }

        val findings = mutableListOf<TherapyFinding>()
        val steadyNotes = mutableListOf<String>()
        val dataNotes = mutableListOf<String>()

        for (window in mealWindows) {
            val inWindow = meals.filter { window.contains(it.hour) }
            if (inWindow.size < MIN_MEALS_PER_WINDOW) {
                if (inWindow.isNotEmpty()) {
                    dataNotes += "Carb ratio ${window.name.lowercase(Locale.US)} — only ${inWindow.size} clean " +
                        "meal${if (inWindow.size == 1) "" else "s"} to read, $MIN_MEALS_PER_WINDOW needed."
                }
                continue
            }

            val lows = inWindow.filter { it.wentLow }
            // A meal that had to be rescued has an endpoint written by the rescue carbs, not by
            // the ratio — count it as a signal, but keep it out of the arithmetic.
            val usable = inWindow.filter { !it.wentLow }
            val medianExcursion = median(inWindow.map { it.excursion })
            val medianPeakRise = median(inWindow.map { it.peakRise })
            val isf = settings.isfAt(window.start)
            val current = settings.carbRatioAt(window.start)

            // Insulin the meal actually needed = what was given, plus what it would have taken
            // to erase the leftover excursion at the four-hour mark.
            val impliedRatios = mutableListOf<Double>()
            if (isf != null && isf > 0) {
                for (meal in usable) {
                    val extra = meal.excursion / isf
                    val total = meal.units + extra
                    if (total <= 0.2) continue
                    val implied = meal.carbs / total
                    if (implied <= 1 || implied >= 60) continue
                    impliedRatios += implied
                }
            }

            val lowPressure = lows.size >= 2
            val observed = if (impliedRatios.isEmpty()) null else median(impliedRatios)

            if (current == null || observed == null) {
                val summary = if (medianExcursion >= 0) {
                    "still ${formatter.format(medianExcursion)} ${formatter.unitLabel} above where it started"
                } else {
                    "${formatter.format(abs(medianExcursion))} ${formatter.unitLabel} below where it started"
                }
                steadyNotes += "${window.name} meals — four hours after ${inWindow.size} meals, glucose was $summary." +
                    (if (current == null) " No configured carb ratio to compare against." else " Not enough of an ISF to convert that into a ratio.")
                continue
            }

            val diffPercent = (observed - current) / current * 100
            if (abs(diffPercent) < CARB_RATIO_MISMATCH_PERCENT && !lowPressure) {
                steadyNotes += "Carb ratio ${window.name.lowercase(Locale.US)} — meals landed within " +
                    "${formatter.format(abs(medianExcursion))} ${formatter.unitLabel} of where " +
                    "they started after four hours across ${inWindow.size} meals. The ratio is doing its job."
                continue
            }

            // A lower ratio number means more insulin per gram. Repeated lows say the ratio is
            // too strong whatever the arithmetic says, and they override it.
            val direction = if (lowPressure) TherapyDirection.INCREASE else if (observed > current) TherapyDirection.INCREASE else TherapyDirection.DECREASE
            val contradicted = lowPressure && observed <= current

            var suggested: Double? = null
            var percent: Double? = null
            if (!contradicted) {
                val cap = current * MAX_CHANGE_PERCENT / 100
                val stepped = (observed - current) * STEP_FRACTION
                val bounded = min(max(stepped, -cap), cap)
                val target = schoolbookRound((current + bounded) * 2) / 2
                if (abs(target - current) >= 0.5) {
                    suggested = target
                    percent = (target - current) / current * 100
                }
            }

            val strength = when {
                inWindow.size >= 8 -> TherapyEvidenceStrength.STRONG
                inWindow.size >= 5 -> TherapyEvidenceStrength.MODERATE
                else -> TherapyEvidenceStrength.LIMITED
            }

            val priority = when {
                lowPressure -> Priority.HIGH
                abs(medianExcursion) >= 60 -> Priority.MEDIUM
                else -> Priority.LOW
            }

            val evidence = mutableListOf(
                "Four hours after the meal, glucose was a median of " +
                    "${if (medianExcursion >= 0) "+" else ""}${formatter.format(medianExcursion)} " +
                    "${formatter.unitLabel} from where it started.",
                "Implied ratio from the insulin actually needed: 1 unit per ${one(observed)} g.",
                "Configured ratio at ${hourLabel(window.start)}: 1 unit per ${one(current)} g.",
            )
            if (lows.isNotEmpty()) {
                evidence += "${lows.size} of ${inWindow.size} meals dropped below your low target within four hours; those were left out of the ratio arithmetic."
            }
            if (medianPeakRise >= 60 && abs(medianExcursion) < 30) {
                evidence += "Glucose still peaked ${formatter.format(medianPeakRise)} ${formatter.unitLabel} " +
                    "above the starting value before coming back — that shape is bolus timing rather than ratio strength."
            }

            val caveats = mutableListOf(
                "Measured at four hours, once the dose has finished. The peak in between is mostly a timing question, not a ratio one."
            )
            if (medianPeakRise >= 60 && abs(medianExcursion) < 40) {
                caveats += "The large peak with a near-normal four-hour landing usually answers to pre-bolusing 15–20 minutes earlier before it answers to a ratio change."
            }
            if (context.isClosedLoop) {
                caveats += "Your loop corrects after meals too, so part of the excursion was already cleaned up by it — a ratio gap it hides is likely wider than this shows."
            }
            if (settings.varies(settings.carbRatio, window.hours)) {
                caveats += "Your ratio already changes inside this window; the comparison uses the value at ${hourLabel(window.start)}."
            }
            if (contradicted) {
                caveats += "The lows here and the arithmetic from the meals that did not go low point in opposite directions — " +
                    "usually unlogged rescue carbs. No target ratio is offered for that reason; the lows are the part worth raising."
            }

            val headline = when {
                lowPressure -> "${lows.size} of ${inWindow.size} ${window.name.lowercase(Locale.US)} meals ended below your low target — meal insulin here is running stronger than the food needs."
                direction == TherapyDirection.DECREASE ->
                    "${window.name} meals finish a median of ${formatter.format(medianExcursion)} ${formatter.unitLabel} above where they started — the ratio is not covering the carbs."
                else ->
                    "${window.name} meals finish ${formatter.format(abs(medianExcursion))} ${formatter.unitLabel} below where they started — the ratio is delivering more than the carbs call for."
            }

            findings += TherapyFinding(
                parameter = TherapyParameter.CARB_RATIO,
                startHour = window.start,
                endHour = window.end,
                windowLabel = window.label,
                title = "${window.name} carb ratio — ${window.label}",
                headline = headline,
                direction = direction,
                priority = priority,
                strength = strength,
                currentValue = current,
                observedValue = observed,
                suggestedValue = suggested,
                percentChange = percent,
                sampleLabel = "${inWindow.size} single-bolus meals",
                evidence = evidence,
                rationale = if (direction == TherapyDirection.DECREASE) {
                    "Where glucose is still up four hours later, the dose that covered the meal was short. " +
                        "A ratio number that comes down means more insulin per gram, which is what closes that gap."
                } else {
                    "Landing below the starting point four hours out means the meal dose outran the food. " +
                        "A ratio number that goes up means less insulin per gram — the safer direction to move first."
                },
                caveats = caveats,
                doctorQuestion = "Across ${inWindow.size} ${window.name.lowercase(Locale.US)} meals my glucose was a median of ${if (medianExcursion >= 0) "+" else ""}${formatter.format(medianExcursion)} ${formatter.unitLabel} four hours after eating — should we look at my carb ratio for that window?",
            )
        }

        return NotesResult(findings, steadyNotes, dataNotes)
    }

    // MARK: - Windows

    private class DayWindow(val name: String, val start: Int, val end: Int) {
        val hours: List<Int>
            get() {
                val result = mutableListOf<Int>()
                var hour = start
                while (hour != end) { result += hour; hour = (hour + 1) % 24 }
                return result
            }

        val label: String get() = "${String.format(Locale.US, "%02d", start)}:00–${String.format(Locale.US, "%02d", end)}:00"

        fun contains(hour: Int): Boolean = if (start < end) hour >= start && hour < end else hour >= start || hour < end
    }

    /** Sensitivity is read in the four blocks it actually varies across. */
    private val dayWindows = listOf(
        DayWindow("Overnight", 0, 6),
        DayWindow("Morning", 6, 12),
        DayWindow("Afternoon", 12, 18),
        DayWindow("Evening", 18, 24),
    )

    /** Meals group by which meal they are, not by clock block. */
    private val mealWindows = listOf(
        DayWindow("Breakfast", 4, 11),
        DayWindow("Lunch", 11, 16),
        DayWindow("Dinner", 16, 22),
    )

    // MARK: - Helpers

    /** Contiguous runs of hours satisfying [predicate], wrapping across midnight — an overnight rise is one window, not two. */
    private fun circularRuns(predicate: (Int) -> Boolean): List<List<Int>> {
        val flags = (0 until 24).map(predicate)
        if (!flags.contains(true)) return emptyList()
        if (flags.all { it }) return listOf((0 until 24).toList())

        var start = 0
        for (hour in 0 until 24) {
            if (flags[hour] && !flags[(hour + 23) % 24]) { start = hour; break }
        }

        val runs = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (offset in 0 until 24) {
            val hour = (start + offset) % 24
            if (flags[hour]) {
                current += hour
            } else if (current.isNotEmpty()) {
                runs += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) runs += current
        return runs
    }

    private fun windowLabel(run: List<Int>): String {
        val first = run.firstOrNull() ?: return ""
        val last = run.last()
        if (run.size >= 24) return "00:00–24:00"
        // A window ending at midnight reads as 24:00, not as 00:00 — the latter looks like a
        // window that ends before it starts.
        return "${String.format(Locale.US, "%02d", first)}:00–${String.format(Locale.US, "%02d", last + 1)}:00"
    }

    private fun hourLabel(hour: Int): String = "${String.format(Locale.US, "%02d", hour)}:00"

    private fun periodName(hour: Int): String = when (hour) {
        in 0 until 6 -> "Overnight"
        in 6 until 12 -> "Morning"
        in 12 until 17 -> "Afternoon"
        in 17 until 22 -> "Evening"
        else -> "Late night"
    }

    private fun priorityRank(priority: Priority): Int = when (priority) {
        Priority.HIGH -> 3
        Priority.MEDIUM -> 2
        Priority.LOW -> 1
    }

    /** Glucose nearest [targetMillis], provided something was recorded within [windowMillis]. */
    private fun nearestGlucose(sorted: List<NightscoutGlucoseEntry>, targetMillis: Long, windowMillis: Long): Double? {
        var bestDistance = Long.MAX_VALUE
        var bestValue: Double? = null
        for (entry in sorted) {
            val distance = abs(entry.epochMilliseconds - targetMillis)
            if (distance > windowMillis) continue
            if (bestValue == null || distance < bestDistance) {
                bestDistance = distance
                bestValue = entry.sgv.toDouble()
            }
        }
        return bestValue
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    /** Swift's `.rounded()`: half away from zero. Kotlin's `round` is half-even and `Math.round` half-up. */
    internal fun schoolbookRound(value: Double): Double = if (value >= 0) floor(value + 0.5) else -floor(-value + 0.5)

    private fun two(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun one(value: Double): String = String.format(Locale.US, "%.1f", value)
}
