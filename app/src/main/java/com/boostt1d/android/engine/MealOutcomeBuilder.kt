package com.boostt1d.android.engine

import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.sync.NightscoutService
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * One logged meal joined to what glucose actually did afterwards.
 *
 * Carbs come from the event log first — a pump or Nightscout carb entry is the most commonly
 * present record and needs no extra effort from the user. The food log, when there is one,
 * adds what the event log can never carry: what the food *was*, and its fat and protein.
 * That is the difference between "60 g at 19:00" and "pizza".
 */
data class MealOutcome(
    val atMillis: Long,
    /** Normalised description when one is known, otherwise null — carbs alone still count. */
    val label: String?,
    val carbs: Double,
    val fat: Double?,
    val protein: Double?,
    val fiber: Double?,
    val insulin: Double,
    /** Minutes between the dose and the meal. Positive means the dose came first. */
    val preBolusMinutes: Int?,
    val baseline: Double,
    val peak: Double,
    val minutesToPeak: Int,
    /**
     * Glucose four hours later minus the starting value. Null when the window has no ending
     * reading — a missing endpoint must not read as zero.
     */
    val delta4h: Double?,
    val wentLow: Boolean,
    /**
     * True when more carbs or another dose landed inside the four hours. The row still
     * counts, but its ending value describes two decisions rather than one.
     */
    val interrupted: Boolean,
    /** 1 = Sunday … 7 = Saturday, as `Calendar.DAY_OF_WEEK`. */
    val weekday: Int,
    val mealPeriod: String,
) {
    val peakRise: Double get() = peak - baseline
    val hasComposition: Boolean get() = fat != null || protein != null
}

/** Aggregated behaviour of a food that shows up more than once. */
data class RepeatFoodOutcome(
    val label: String,
    val count: Int,
    val medianCarbs: Double,
    val medianPeakRise: Double,
    val medianMinutesToPeak: Int,
    val medianDelta4h: Double?,
    val lowCount: Int,
    val medianFat: Double?,
    val medianProtein: Double?,
)

/** One weekday, across every occurrence of it in the window. */
data class WeekdayOutcome(
    val weekday: Int,
    val name: String,
    val dayCount: Int,
    val averageGlucose: Double,
    val timeInRange: Double,
    val timeBelow: Double,
    val mealCount: Int,
    val totalCarbs: Double,
    val totalInsulin: Double,
)

/**
 * Joins meals, doses and glucose into the table the AI review reasons from.
 *
 * The model was previously handed a list of meal names and, separately, a downsampled glucose
 * series, and asked to find meal patterns — which meant doing the join itself, by timestamp,
 * across hundreds of points. That is the part it is worst at, and the part a formula is best
 * at. So the join happens here and the model gets facts.
 *
 * Nothing here judges or recommends. Every figure is measured, and rows whose four-hour
 * window was interrupted are marked rather than dropped, so the model can weigh them.
 *
 * Ported 1:1 from the iOS MealOutcomeBuilder; the tests are the spec.
 */
object MealOutcomeBuilder {

    /** Carb entries below this are corrections for a low or a rounding artefact, not meals. */
    private const val MINIMUM_CARBS = 10.0

    /** Rows sent to the model. Enough for a week of eating without swamping the prompt. */
    private const val MAX_ROWS_IN_PROMPT = 40

    /**
     * Occurrences a weekday needs before it can be compared against another weekday. One
     * Friday is a day, not a Friday pattern — and a seven-day window never has two of any.
     */
    const val MIN_DAYS_PER_WEEKDAY_FOR_COMPARISON = 2

    /** A food needs to recur at least this often before its median means anything. */
    private const val MINIMUM_REPEATS = 3

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    // MARK: - Meals

    fun mealOutcomes(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogEntries: List<FoodLogSnapshot>,
        lowGlucose: Double,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<MealOutcome> {
        val sorted = glucoseEntries.sortedBy { it.epochMilliseconds }
        if (sorted.isEmpty()) return emptyList()

        val carbTreatments = treatments
            .filter { (it.carbs ?: 0.0) >= MINIMUM_CARBS }
            .map { treatmentMillis(it) to (it.carbs ?: 0.0) }
            .filter { it.first > 0L }

        val insulinEvents = treatments
            .filter { (it.insulin ?: 0.0) > 0 }
            .map { treatmentMillis(it) to (it.insulin ?: 0.0) }

        val allCarbTimes = treatments
            .filter { (it.carbs ?: 0.0) > 0 }
            .map { treatmentMillis(it) } +
            foodLogEntries.filter { (it.carbsGrams ?: 0.0) > 0 }.map { it.recordedAtMillis }

        // Event-log carbs are the spine. A food log entry near one enriches it rather than
        // creating a second meal; one with no carb entry nearby becomes a meal of its own, so
        // users who only photograph food are not left out.
        val events = mutableListOf<Triple<Long, Double, FoodLogSnapshot?>>()
        for ((at, carbs) in carbTreatments) {
            val match = foodLogEntries
                .filter { abs(it.recordedAtMillis - at) <= 20 * MINUTE }
                .minByOrNull { abs(it.recordedAtMillis - at) }
            events.add(Triple(at, carbs, match))
        }
        for (entry in foodLogEntries) {
            val carbs = entry.carbsGrams ?: continue
            if (carbs < MINIMUM_CARBS) continue
            val covered = carbTreatments.any { abs(it.first - entry.recordedAtMillis) <= 20 * MINUTE }
            if (!covered) events.add(Triple(entry.recordedAtMillis, carbs, entry))
        }

        val calendar = Calendar.getInstance(timeZone)
        val outcomes = mutableListOf<MealOutcome>()

        for ((at, carbs, food) in events.sortedBy { it.first }) {
            val end = at + 4 * HOUR

            val baseline = nearestGlucose(sorted, at, 20 * MINUTE) ?: continue

            val window = sorted.filter { it.epochMilliseconds > at && it.epochMilliseconds <= end }
            if (window.size < 4) continue

            val peakEntry = window.maxByOrNull { it.sgv }
            val peak = peakEntry?.sgv?.toDouble() ?: baseline
            val minutesToPeak = peakEntry?.let { Math.round((it.epochMilliseconds - at) / 60_000.0).toInt() } ?: 0

            val doses = insulinEvents.filter { it.first >= at - 30 * MINUTE && it.first <= at + 45 * MINUTE }
            val insulin = doses.sumOf { it.second }
            // Truncation toward zero, as Swift's Int(Double) does, so "dose 90 s after" is 1 not 2.
            val preBolus = doses.minOfOrNull { it.first }?.let { ((at - it) / MINUTE).toInt() }

            val laterCarbs = allCarbTimes.any { it > at + 20 * MINUTE && it <= end }
            val laterDose = insulinEvents.any { it.first > at + 45 * MINUTE && it.first <= end }

            calendar.timeInMillis = at
            outcomes.add(
                MealOutcome(
                    atMillis = at,
                    label = food?.let { normalisedLabel(it.descriptionText) },
                    carbs = carbs,
                    fat = food?.fatGrams,
                    protein = food?.proteinGrams,
                    fiber = food?.fiberGrams,
                    insulin = insulin,
                    preBolusMinutes = preBolus,
                    baseline = baseline,
                    peak = peak,
                    minutesToPeak = minutesToPeak,
                    delta4h = nearestGlucose(sorted, end, 25 * MINUTE)?.let { it - baseline },
                    wentLow = window.any { it.sgv < lowGlucose },
                    interrupted = laterCarbs || laterDose,
                    weekday = calendar.get(Calendar.DAY_OF_WEEK),
                    mealPeriod = mealPeriod(calendar.get(Calendar.HOUR_OF_DAY)),
                )
            )
        }

        return outcomes
    }

    // MARK: - Repeat foods

    /**
     * Groups meals by the words in their descriptions, so "pizza", "2 slices of pizza" and
     * "pizza night" answer one question together.
     *
     * Deliberately generic rather than a food dictionary: a hardcoded list would work for
     * pizza and fail for whatever this user actually eats.
     */
    fun repeatFoods(outcomes: List<MealOutcome>): List<RepeatFoodOutcome> {
        val byToken = mutableMapOf<String, MutableList<MealOutcome>>()
        for (outcome in outcomes) {
            val label = outcome.label ?: continue
            for (token in label.split(" ").toSet()) {
                byToken.getOrPut(token) { mutableListOf() }.add(outcome)
            }
        }

        val used = mutableSetOf<MealOutcome>()
        val groups = mutableListOf<RepeatFoodOutcome>()

        // Biggest groups first, and a meal only joins one — otherwise "chicken pizza" would be
        // counted in both groups and the medians would double-dip. Ties broken by name so the
        // grouping is deterministic, which Swift's dictionary order was not.
        for ((token, meals) in byToken.entries.sortedWith(compareByDescending<Map.Entry<String, List<MealOutcome>>> { it.value.size }.thenBy { it.key })) {
            val fresh = meals.filter { it !in used }
            if (fresh.size < MINIMUM_REPEATS) continue
            used.addAll(fresh)

            groups.add(
                RepeatFoodOutcome(
                    label = token,
                    count = fresh.size,
                    medianCarbs = median(fresh.map { it.carbs }) ?: 0.0,
                    medianPeakRise = median(fresh.map { it.peakRise }) ?: 0.0,
                    medianMinutesToPeak = (median(fresh.map { it.minutesToPeak.toDouble() }) ?: 0.0).toInt(),
                    medianDelta4h = median(fresh.mapNotNull { it.delta4h }),
                    lowCount = fresh.count { it.wentLow },
                    medianFat = median(fresh.mapNotNull { it.fat }),
                    medianProtein = median(fresh.mapNotNull { it.protein }),
                )
            )
        }

        return groups
    }

    // MARK: - Weekdays

    fun weekdayOutcomes(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<WeekdayOutcome> {
        if (glucoseEntries.isEmpty()) return emptyList()

        val calendar = Calendar.getInstance(timeZone)
        val readings = mutableMapOf<Int, MutableList<Double>>()
        val days = mutableMapOf<Int, MutableSet<Long>>()
        for (entry in glucoseEntries) {
            calendar.timeInMillis = entry.epochMilliseconds
            val weekday = calendar.get(Calendar.DAY_OF_WEEK)
            readings.getOrPut(weekday) { mutableListOf() }.add(entry.sgv.toDouble())
            days.getOrPut(weekday) { mutableSetOf() }.add(TodaySoFarBuilder.startOfDay(entry.epochMilliseconds, timeZone))
        }

        val meals = mutableMapOf<Int, Int>()
        val carbs = mutableMapOf<Int, Double>()
        val insulin = mutableMapOf<Int, Double>()
        for (treatment in treatments) {
            val at = treatmentMillis(treatment)
            if (at <= 0L) continue
            calendar.timeInMillis = at
            val weekday = calendar.get(Calendar.DAY_OF_WEEK)
            treatment.carbs?.takeIf { it >= MINIMUM_CARBS }?.let {
                meals[weekday] = (meals[weekday] ?: 0) + 1
                carbs[weekday] = (carbs[weekday] ?: 0.0) + it
            }
            treatment.insulin?.takeIf { it > 0 }?.let {
                insulin[weekday] = (insulin[weekday] ?: 0.0) + it
            }
        }

        // Index 1..7 = Sunday..Saturday, matching Calendar.DAY_OF_WEEK; index 0 is empty.
        val names = DateFormatSymbols.getInstance(Locale.getDefault()).weekdays

        return (1..7).mapNotNull { weekday ->
            val values = readings[weekday]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val count = values.size.toDouble()
            WeekdayOutcome(
                weekday = weekday,
                name = names.getOrNull(weekday)?.takeIf { it.isNotEmpty() } ?: "Day $weekday",
                dayCount = days[weekday]?.size ?: 0,
                averageGlucose = values.sum() / count,
                timeInRange = values.count { it >= lowGlucose && it <= highGlucose } / count * 100,
                timeBelow = values.count { it < lowGlucose } / count * 100,
                mealCount = meals[weekday] ?: 0,
                totalCarbs = carbs[weekday] ?: 0.0,
                totalInsulin = insulin[weekday] ?: 0.0,
            )
        }
    }

    // MARK: - Prompt rendering

    /**
     * The meal, repeat-food and weekday sections of the AI prompt.
     *
     * Written as facts rather than raw series: the model's job is to interpret these, not to
     * recompute them. Every number here was measured locally.
     */
    fun promptContext(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogEntries: List<FoodLogSnapshot>,
        lowGlucose: Double,
        highGlucose: Double,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val outcomes = mealOutcomes(glucoseEntries, treatments, foodLogEntries, lowGlucose, timeZone)
        val weekdays = weekdayOutcomes(glucoseEntries, treatments, lowGlucose, highGlucose, timeZone)

        val sections = mutableListOf<String>()

        if (outcomes.isEmpty()) {
            sections.add(
                "MEAL OUTCOMES:\nNo carb entries of ${MINIMUM_CARBS.toInt()} g or more were found " +
                    "in the event log or the food log for this period."
            )
        } else {
            val described = outcomes.count { it.label != null }
            val formatter = SimpleDateFormat("EEE MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }

            val lines = mutableListOf(
                "MEAL OUTCOMES (${outcomes.size} meals, $described with a food description; " +
                    "carbs from the event log, composition from the food log where present). " +
                    "Columns: when | food | carbs | fat/protein | insulin | pre-bolus | start→peak (minutes) | 4h change | flags"
            )

            for (outcome in outcomes.takeLast(MAX_ROWS_IN_PROMPT)) {
                val parts = mutableListOf(formatter.format(Date(outcome.atMillis)))
                parts.add(outcome.label ?: "(carbs only, no description)")
                parts.add("${Math.round(outcome.carbs)}g")
                val fat = outcome.fat
                val protein = outcome.protein
                parts.add(
                    if (fat != null && protein != null) "fat ${Math.round(fat)}g / protein ${Math.round(protein)}g"
                    else "composition unknown"
                )
                parts.add(String.format(Locale.US, "%.1fU", outcome.insulin))
                parts.add(
                    outcome.preBolusMinutes?.let { if (it >= 0) "$it min before" else "${-it} min after" }
                        ?: "no dose logged"
                )
                parts.add("${Math.round(outcome.baseline)}→${Math.round(outcome.peak)} (${outcome.minutesToPeak} min)")
                parts.add(outcome.delta4h?.let { "${if (it >= 0) "+" else ""}${Math.round(it)}" } ?: "4h unknown")

                val flags = mutableListOf<String>()
                if (outcome.wentLow) flags.add("went low")
                if (outcome.interrupted) flags.add("interrupted")
                if (flags.isNotEmpty()) parts.add(flags.joinToString(", "))

                lines.add("- " + parts.joinToString(" | "))
            }

            if (outcomes.size > MAX_ROWS_IN_PROMPT) {
                lines.add("- (…${outcomes.size - MAX_ROWS_IN_PROMPT} earlier meals omitted)")
            }
            sections.add(lines.joinToString("\n"))

            val repeats = repeatFoods(outcomes)
            if (repeats.isNotEmpty()) {
                val repeatLines = mutableListOf("REPEAT FOODS (grouped by description; medians across each group):")
                for (group in repeats) {
                    var line = "- ${group.label}: ${group.count} meals, median ${Math.round(group.medianCarbs)}g carbs, " +
                        "peak +${Math.round(group.medianPeakRise)} mg/dL at ${group.medianMinutesToPeak} min"
                    group.medianDelta4h?.let { line += ", ${if (it >= 0) "+" else ""}${Math.round(it)} mg/dL at 4h" }
                    group.medianFat?.let { line += ", median fat ${Math.round(it)}g" }
                    if (group.lowCount > 0) line += ", ${group.lowCount} went low"
                    repeatLines.add(line)
                }
                sections.add(repeatLines.joinToString("\n"))
            }
        }

        // A weekday row built from a single occurrence is one day wearing a weekday's name. Sent
        // as a table it reliably produces "some days, like Fridays…" out of n=1 — the model is
        // not wrong to read a row as a group, so do not offer it one.
        val comparable = weekdays.filter { it.dayCount >= MIN_DAYS_PER_WEEKDAY_FOR_COMPARISON }

        if (comparable.size >= 2) {
            val lines = mutableListOf(
                "BY DAY OF WEEK (only weekdays occurring at least " +
                    "$MIN_DAYS_PER_WEEKDAY_FOR_COMPARISON times in this window are listed):"
            )
            for (day in comparable) {
                lines.add(
                    "- ${day.name} (${day.dayCount} day${if (day.dayCount == 1) "" else "s"}): " +
                        "avg ${Math.round(day.averageGlucose)} mg/dL, " +
                        "${Math.round(day.timeInRange)}% in range, " +
                        "${Math.round(day.timeBelow)}% low, " +
                        "${day.mealCount} meals, ${Math.round(day.totalCarbs)}g carbs, " +
                        String.format(Locale.US, "%.1fU insulin", day.totalInsulin)
                )
            }
            sections.add(lines.joinToString("\n"))
        } else if (weekdays.isNotEmpty()) {
            // Say it rather than omitting it. A missing section leaves the model free to
            // reconstruct weekdays from the dated meal rows above; an explicit prohibition does not.
            sections.add(
                "BY DAY OF WEEK:\nNot available. This window covers each weekday at most " +
                    "${weekdays.maxOfOrNull { it.dayCount } ?: 1} time(s), which cannot support any " +
                    "day-of-week comparison. Do not describe any finding as specific to a " +
                    "weekday, do not say things like \"on Fridays\" or \"weekends\", and do " +
                    "not pluralise a single day into a habit."
            )
        }

        return sections.joinToString("\n\n")
    }

    // MARK: - Helpers

    /**
     * When a treatment happened: `mills`, else `created_at`, else 0 — the same fallback iOS
     * uses, with 0 standing in for `distantPast` so callers filter it the same way.
     */
    internal fun treatmentMillis(treatment: NightscoutTreatment): Long {
        treatment.mills?.takeIf { it > 0 }?.let { return it }
        return NightscoutService.parseTimestamp(treatment.createdAt) ?: 0L
    }

    /** Strips quantities, units and filler so descriptions of the same food collide. */
    internal fun normalisedLabel(description: String): String? {
        val tokens = description
            .lowercase(Locale.getDefault())
            .split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
        if (tokens.isEmpty()) return null
        return tokens.take(4).joinToString(" ")
    }

    private val STOPWORDS = setOf(
        "with", "and", "the", "for", "some", "large", "small", "medium", "homemade",
        "slice", "slices", "cup", "cups", "bowl", "plate", "piece", "pieces", "serving",
        "servings", "grams", "gram", "half", "whole", "fresh", "little", "lunch",
        "dinner", "breakfast", "snack", "meal", "left", "over", "leftovers",
    )

    private fun mealPeriod(hour: Int): String = when (hour) {
        in 4..10 -> "Breakfast"
        in 11..15 -> "Lunch"
        in 16..21 -> "Dinner"
        else -> "Late night"
    }

    private fun nearestGlucose(sorted: List<NightscoutGlucoseEntry>, targetMillis: Long, windowMillis: Long): Double? {
        var bestDistance = Long.MAX_VALUE
        var bestValue: Double? = null
        for (entry in sorted) {
            val distance = abs(entry.epochMilliseconds - targetMillis)
            if (distance > windowMillis) continue
            if (distance < bestDistance) {
                bestDistance = distance
                bestValue = entry.sgv.toDouble()
            }
        }
        return bestValue
    }

    internal fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}
