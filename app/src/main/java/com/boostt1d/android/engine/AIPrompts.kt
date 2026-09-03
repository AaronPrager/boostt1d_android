package com.boostt1d.android.engine

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The text the two live AI calls send, built exactly as iOS builds it. The prompts are the
 * contract with the backend and the model; wording differences between platforms would be
 * differences in what the model is asked, so they are reproduced verbatim.
 *
 * Ported from the prompt-building half of the iOS APIService.
 */
object AIPrompts {

    /**
     * Largest number of CGM points to send. A full 5-minute grid over 7 days is ~2,000 points,
     * so the bucket widens to fit this budget instead. Shape and time-of-day structure survive;
     * only resolution drops — 500 points over 7 days is ~20-minute detail.
     */
    const val MAX_GLUCOSE_SERIES_POINTS = 500
    private const val STORAGE_BUCKET_MINUTES = 5

    private fun stamp(timeZone: TimeZone) = SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }

    /** CGM as a downsampled time series. Bucket size starts at the CGM cadence and widens until the series fits the budget. */
    fun glucoseSeries(entries: List<NightscoutGlucoseEntry>, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (entries.isEmpty()) return "No CGM readings."
        val formatter = stamp(timeZone)
        val earliest = entries.minOf { it.epochMilliseconds }
        val latest = entries.maxOf { it.epochMilliseconds }

        val spanMinutes = max((latest - earliest) / 60_000.0, 1.0)
        val neededMultiple = ceil(spanMinutes / STORAGE_BUCKET_MINUTES / MAX_GLUCOSE_SERIES_POINTS).toInt()
        val bucketMinutes = STORAGE_BUCKET_MINUTES * max(neededMultiple, 1)
        val bucketMillis = bucketMinutes * 60_000L

        // One reading per bucket, earliest first.
        val byBucket = sortedMapOf<Long, Int>()
        for (entry in entries) {
            val slot = (entry.epochMilliseconds / bucketMillis) * bucketMillis
            byBucket[slot] = entry.sgv
        }
        val lines = byBucket.map { (slot, sgv) -> "${formatter.format(Date(slot))} $sgv" }
        return "${lines.size} readings at $bucketMinutes-minute resolution (time, mg/dL):\n" + lines.joinToString("\n")
    }

    /** Every logged event with its timestamp. Temp basals are counted, not listed. */
    fun eventLog(treatments: List<NightscoutTreatment>, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (treatments.isEmpty()) return "No logged events."
        val formatter = stamp(timeZone)

        // Closed-loop systems emit temp basals every few minutes, which would dominate the
        // payload without adding insight. Keep the clinically meaningful events in full.
        val meaningful = treatments.filter { it.eventType != "Temp Basal" }
        val tempBasalCount = treatments.size - meaningful.size
        val tempBasalNote = if (tempBasalCount > 0) "\n($tempBasalCount algorithm-driven Temp Basal events omitted for brevity.)" else ""

        val lines = meaningful
            .sortedBy { MealOutcomeBuilder.treatmentMillis(it) }
            .takeLast(400)
            .map { treatment ->
                val parts = mutableListOf(formatter.format(Date(MealOutcomeBuilder.treatmentMillis(treatment))))
                parts += treatment.eventType ?: "Unknown"
                treatment.insulin?.takeIf { it > 0 }?.let {
                    parts += String.format(Locale.US, "%.2fu", it)
                    // Marked per-line as well as in the summary: an unlabelled 0.15u looks like a user decision.
                    if (treatment.isAlgorithmDelivered) parts += "auto/SMB"
                }
                treatment.carbs?.takeIf { it > 0 }?.let { parts += "${it.roundToInt()}g" }
                treatment.duration?.takeIf { it > 0 }?.let { parts += "${it}min" }
                treatment.rate?.let { parts += String.format(Locale.US, "rate %.2f", it) }
                treatment.glucose?.takeIf { it.isNotEmpty() }?.let { parts += "bg $it" }
                treatment.notes?.takeIf { it.isNotEmpty() }?.let { parts += "($it)" }
                parts.joinToString(" · ")
            }
        return "${lines.size} events (time, type, details):\n" + lines.joinToString("\n") + tempBasalNote
    }

    /** Delivery-method briefing. The logic lives in [InsulinDeliveryContext] so the prompt and the local insights read the same detection. */
    fun deliveryContext(treatments: List<NightscoutTreatment>, therapyType: InsulinTherapyType): String =
        InsulinDeliveryContext(treatments, therapyType).promptSummary

    /** Therapy settings by time of day, so the model can reason about ratios and schedules rather than inferring them. */
    fun therapyProfile(profile: DoctorVisitTherapySnapshot?): String {
        if (profile == null || !profile.hasAnyContent) return "No therapy profile on device."
        fun schedule(label: String, segments: List<DoctorVisitTherapySegment>): String? =
            if (segments.isEmpty()) null else "$label: " + segments.joinToString(", ") { "${it.time}=${it.valueLabel}" }

        val lines = mutableListOf<String>()
        profile.diaHours?.let { lines += "DIA: ${String.format(Locale.US, "%.1f", it)}h" }
        lines += listOfNotNull(
            schedule("Basal", profile.basalSegments),
            schedule("Carb ratio", profile.carbRatioSegments),
            schedule("Correction factor", profile.sensitivitySegments),
            schedule("Target", profile.targetSegments),
        )
        if (profile.overrides.isNotEmpty()) {
            lines += "Override presets: " + profile.overrides.joinToString(", ") { "${it.name} (${it.detail})" }
        }
        return if (lines.isEmpty()) "No therapy profile on device." else lines.joinToString("\n")
    }

    fun glucoseSummary(entries: List<NightscoutGlucoseEntry>, lowGlucose: Double, highGlucose: Double, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (entries.isEmpty()) return "No glucose data available"

        val values = entries.map { it.sgv.toDouble() }
        val n = values.size.toDouble()
        val average = values.sum() / n
        val timeInRange = values.count { it >= lowGlucose && it <= highGlucose } / n * 100
        val timeBelow = values.count { it < lowGlucose } / n * 100
        val timeAbove = values.count { it > highGlucose } / n * 100
        val variance = values.sumOf { (it - average) * (it - average) } / n
        val cv = if (average > 0) sqrt(variance) / average * 100 else 0.0

        val slots = SLOT_KEYS.associateWith { mutableListOf<Double>() }
        val calendar = java.util.Calendar.getInstance(timeZone)
        for (entry in entries) {
            calendar.timeInMillis = entry.epochMilliseconds
            slots[DoseSuggestionService.getTimeSlot(calendar.get(java.util.Calendar.HOUR_OF_DAY))]?.add(entry.sgv.toDouble())
        }

        val summary = StringBuilder(
            "Total Readings: ${values.size}\n" +
                "Average Glucose: ${average.toInt()} mg/dL\n" +
                "TIR (Time in Range): ${timeInRange.toInt()}%\n" +
                "TBR (Time Below Range): ${timeBelow.toInt()}%\n" +
                "TAR (Time Above Range): ${timeAbove.toInt()}%\n" +
                "CV (Coefficient of Variation): ${cv.toInt()}%\n\n" +
                "BY TIME SLOT:"
        )
        for (slot in SLOT_KEYS) {
            val v = slots.getValue(slot)
            if (v.isEmpty()) continue
            val c = v.size.toDouble()
            val slotTIR = v.count { it >= lowGlucose && it <= highGlucose } / c * 100
            val below = (v.count { it < lowGlucose } / c * 100).toInt()
            val above = (v.count { it > highGlucose } / c * 100).toInt()
            summary.append("\n$slot: Avg ${(v.sum() / c).toInt()} mg/dL, ${slotTIR.toInt()}% in range, $below% below target, $above% above target, ${v.size} readings")
        }
        return summary.toString()
    }

    private val SLOT_KEYS = listOf(
        "00:00-02:00", "02:00-04:00", "04:00-06:00", "06:00-08:00", "08:00-10:00", "10:00-12:00",
        "12:00-14:00", "14:00-16:00", "16:00-18:00", "18:00-20:00", "20:00-22:00", "22:00-24:00",
    )

    private fun recordsCarbs(t: NightscoutTreatment): Boolean =
        (t.carbs ?: 0.0) > 0 || t.eventType in listOf("Carb", "Meal Bolus", "Carb Correction", "Snack Bolus")

    fun treatmentSummary(treatments: List<NightscoutTreatment>, foodLogSummary: String = ""): String {
        val corrections = treatments.count { it.eventType == "Correction Bolus" || (it.eventType == "Bolus" && (it.carbs ?: 0.0) == 0.0) }
        val carbTreatments = treatments.filter(::recordsCarbs)
        val totalCarbGrams = carbTreatments.sumOf { it.carbs ?: 0.0 }
        val tempBasals = treatments.count { it.eventType == "Temp Basal" }

        var summary = "Total Treatments (Event Log / Nightscout): ${treatments.size}\n" +
            "Correction Boluses: $corrections\n" +
            "Carb/Meal Entries (Event Log, any type with carbs > 0): ${carbTreatments.size}\n" +
            "Total carbs in Event Log: ${totalCarbGrams.roundToInt()} g\n" +
            "Temp Basals: $tempBasals"
        val trimmedFood = foodLogSummary.trim()
        if (trimmedFood.isNotEmpty()) summary += "\n\nFOOD LOG (in-app diary; may overlap Event Log when meals were linked):\n$trimmedFood"
        return summary
    }

    // MARK: - The two live prompts

    /**
     * Once-daily explanation and prioritization pass for the formula-based therapy review. The
     * model receives the full seven-day context but cannot create a numeric dose proposal; it
     * may only reference a local formula finding by KEY.
     */
    fun dailyTherapyPlan(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogSummary: String,
        profile: DoctorVisitTherapySnapshot?,
        formulaReview: TherapySettingsReview,
        lowGlucose: Double,
        highGlucose: Double,
        periodStartMillis: Long,
        periodEndMillis: Long,
        therapyType: InsulinTherapyType,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }
        val verifiedFindings = formulaReview.findings.map { finding ->
            listOf(
                "KEY: ${finding.dailyReviewKey}",
                "Parameter: ${finding.parameter.displayName}",
                "Window: ${finding.windowLabel}",
                "Formula conclusion: ${finding.headline}",
                "Sample: ${finding.sampleLabel}; evidence strength: ${finding.strength.label}",
                "Calculation rationale: ${finding.rationale}",
                "Evidence: " + finding.evidence.joinToString(" | "),
                "Known caveats: " + finding.caveats.joinToString(" | "),
            ).joinToString("\n")
        }.joinToString("\n\n")

        return """
You are reviewing seven completed days of Type 1 Diabetes data to make a concise,
evidence-grounded therapy discussion plan. The local formula engine has already done
all dose arithmetic. Your job is to connect that verified arithmetic with the full
context: glucose shape, meals and food composition, carbs, insulin, corrections,
exercise, notes, and whether insulin delivery is manual or automated.

PERIOD: ${day.format(Date(periodStartMillis))} through ${day.format(Date(periodEndMillis))}
TARGET RANGE: ${lowGlucose.toInt()}-${highGlucose.toInt()} mg/dL

DELIVERY CONTEXT:
${deliveryContext(treatments, therapyType)}

GLUCOSE SUMMARY:
${glucoseSummary(entries, lowGlucose, highGlucose, timeZone)}

CGM SERIES:
${glucoseSeries(entries, timeZone)}

EVENT LOG (insulin, carbs, corrections, exercise, and notes):
${eventLog(treatments, timeZone)}

IN-APP FOOD LOG (including composition when recorded):
$foodLogSummary

THERAPY PROFILE:
${therapyProfile(profile)}

VERIFIED LOCAL FORMULA FINDINGS:
${if (verifiedFindings.isEmpty()) "No numeric setting finding cleared the formula's evidence thresholds." else verifiedFindings}

SETTINGS THE FORMULA CHECKED WITHOUT RAISING A CHANGE:
${if (formulaReview.steadyNotes.isEmpty()) "None." else formulaReview.steadyNotes.joinToString("\n")}

SETTINGS THE FORMULA COULD NOT EVALUATE:
${if (formulaReview.dataNotes.isEmpty()) "None." else formulaReview.dataNotes.joinToString("\n")}

RULES:
- Select up to five verified findings that are most meaningful after considering all
  logged context. A recommendation MUST copy an exact KEY above.
- Do not output any numeric treatment value, glucose value, percentage, target,
  pre-bolus interval, or algorithm parameter. The app adds verified numbers later.
- Do not tell the user to set, increase, decrease, weaken, strengthen, or test a dose
  or setting. Explain context around the formula finding instead.
- Explain whether meals, carb estimates, fat/protein, corrections, exercise, active
  insulin, missing logs, or automated corrections strengthen or weaken each finding.
- For automated delivery, distinguish the programmed baseline from what the algorithm
  delivered. Do not treat automatic micro-boluses as user corrections.
- Use additional observations for meaningful food, timing, exercise or logging patterns
  that do not justify a dose-setting proposal. They must quote concrete evidence from
  the supplied data and must not contain a dose instruction.
- Keep `overview` focused on whether the verified therapy settings could contribute to
  the week. Put food, exercise, timing, and logging observations only in `observations`.
- `whatToVerify` and `experiments` are observation checklists, not instructions to alter
  treatment. Never suggest testing a dose change without the user's care team.
- If the verified list is empty, recommendations must be empty. Explain what can still
  be learned and what data would make tomorrow's review stronger.
- Keep the overview under 90 words. Keep each explanation under 140 words.

Respond with JSON only, using this exact shape:
{
  "overview": "plain-language therapy contribution summary",
  "recommendations": [
    {
      "findingKey": "exact KEY from the verified list",
      "explanation": "why this verified formula finding matters in the full context",
      "contributingFactors": ["specific contextual factor supported by the data"],
      "whatToVerify": ["specific item to log or review with the care team"]
    }
  ],
  "observations": [
    {
      "title": "short title",
      "observation": "objective non-dose observation",
      "supportingEvidence": ["specific evidence"],
      "whatToTrack": "one useful follow-up item"
    }
  ],
  "experiments": ["specific observation or logging checklist item"],
  "safetyNotes": ["brief limitation specific to this data set"]
}
""".trim()
    }

    /** Asks the model to improve the wording of formula patterns and propose any it missed. Never asks for numbers. */
    fun patternReview(
        formulaPatterns: List<WhatHappenedPattern>,
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        timeRangeDays: Int,
        mealContext: String,
        profile: DoctorVisitTherapySnapshot?,
        therapyType: InsulinTherapyType,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val existing = formulaPatterns.mapIndexed { index, p -> "$index. ${p.title} — ${p.observation} (${p.frequencyLabel})" }.joinToString("\n")
        return """
You are a diabetes data analyst reviewing an automated pattern detector's output for a person with Type 1 Diabetes. This is EDUCATIONAL analysis to help them talk to their care team. Do not give medical advice, dose instructions, or numeric dose targets.

TARGET RANGE: ${lowGlucose.toInt()}-${highGlucose.toInt()} mg/dL
PERIOD: last $timeRangeDays days

DELIVERY CONTEXT (read this before interpreting the event log):
${deliveryContext(treatments, therapyType)}

CGM SERIES:
${glucoseSeries(entries, timeZone)}

EVENT LOG (insulin, carbs, exercise, corrections):
${eventLog(treatments, timeZone)}

${if (mealContext.isEmpty()) "MEAL OUTCOMES:\nNo meal data for this period." else mealContext}

THERAPY PROFILE:
${therapyProfile(profile)}

PATTERNS THE FORMULA ALREADY FOUND:
${if (existing.isEmpty()) "(none)" else existing}

YOUR TASK:
1. For each formula pattern, rewrite the observation in clearer plain language and list likely contributing factors and questions for the care team. Reference the pattern by its index.
2. Propose up to 3 ADDITIONAL patterns the formula missed. The meal, repeat-food and weekday tables above are already joined to glucose outcomes for you — read across them rather than re-deriving anything. The formula cannot see these, so this is where you add what it cannot:
   - Specific foods that behave differently from their carb count. A meal high in fat or protein typically absorbs late, so a normal peak followed by a rise still present at 4 hours is a composition effect, not a carb-counting error. Name the food when a repeat group shows it.
   - Pre-bolus timing: compare peak height and time-to-peak against how many minutes before the meal the dose was given.
   - Day-of-week effects, when a weekday differs from the others by more than a few points and there is something in the meal or insulin totals to explain it.
   - Exercise, illness or schedule effects visible in the event log.
Only propose a pattern you can point to in the data, and say how many meals or days it rests on. Rows marked "interrupted" had more carbs or another dose inside the four hours — their 4h value describes two decisions, so weigh them accordingly.

Explaining WHY food behaves the way it does is in scope and useful. Naming a dose, a dose split, or a specific pre-bolus figure to use is NOT — describe the mechanism and leave the numbers to the care team.

Do NOT invent percentages, day-level values or chart data — the app computes those.

Respond with JSON only:
{
  "enriched": [
    {"index": 0, "observation": "...", "contributingFactors": ["..."], "discussQuestions": ["..."]}
  ],
  "additional": [
    {"title": "...", "observation": "...", "occurrenceCount": 3, "priority": "High|Medium|Low", "contributingFactors": ["..."], "discussQuestions": ["..."]}
  ]
}
""".trim()
    }

    /** The meal-photo prompt. Description and notes are kept apart on purpose. */
    val foodAnalysis: String = """
Analyze this food image and estimate the nutritional content. Please provide:
1. A brief description of the food items you see
2. Estimated total carbohydrates in grams
3. Estimated total calories (kcal)
4. Estimated total fat in grams
5. Estimated total protein in grams
6. Estimated total fiber in grams
7. Your confidence level (High/Medium/Low)
8. Caveats that affect how much the estimate can be trusted

The "description" and "notes" fields serve different purposes and must not overlap:
- "description": ONLY what food is visible, as a short phrase. Example: "Grilled chicken breast, white rice, steamed broccoli".
- "notes": ONLY caveats affecting accuracy — assumed portion sizes, items partially hidden or hard to identify, unknown preparation method or added fats/sugars, packaging obscuring contents. Do NOT repeat the food description here. If there are no meaningful caveats, return an empty string.

Please be as accurate as possible and consider typical serving sizes. Respond in this exact JSON format:
{
  "description": "food items visible",
  "carbs_grams": number,
  "calories_kcal": number,
  "fat_grams": number,
  "protein_grams": number,
  "fiber_grams": number,
  "confidence": "High/Medium/Low",
  "notes": "caveats affecting accuracy, or empty string"
}
""".trim()
}
