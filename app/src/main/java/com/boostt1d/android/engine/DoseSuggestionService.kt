package com.boostt1d.android.engine

import com.boostt1d.android.data.Config
import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class AdjustmentType(val displayName: String) {
    BASAL_RATE("Basal Rate"),
    CARB_RATIO("Carb Ratio"),
    CORRECTION_FACTOR("Correction Factor"),
    TARGET_GLUCOSE("Insulin Timing"),
}

data class AdjustmentSuggestion(
    val id: String,
    val type: AdjustmentType,
    val timeSlot: String,
    val currentValue: Double,
    val suggestedValue: Double,
    val priority: Priority,
    val reasoning: String,
)

data class CorrectionEffectiveness(
    val timeSlot: String,
    val correctionCount: Int,
    val totalInsulin: Double,
    val avgBGDrop: Double,
    val avgActualISF: Double,
    val isfValues: List<Double>,
)

data class InsulinTimingAnalysis(
    val postMealSpikes: Int,
    val totalMeals: Int,
    val spikeRate: Double,
    val needsPreBolus: Boolean,
)

data class TimeSlotAnalysis(
    val averageGlucose: Double,
    val timeInRange: Double,
    val timeBelowRange: Double,
    val timeAboveRange: Double,
    val dataPoints: Int,
)

fun TimeSlotAnalysis.snapshot(isClosedLoop: Boolean = false) = SlotAnalysisSnapshot(
    averageGlucose = averageGlucose, timeInRange = timeInRange, timeBelowRange = timeBelowRange,
    timeAboveRange = timeAboveRange, dataPoints = dataPoints, isClosedLoop = isClosedLoop,
)

/**
 * Deterministic dose-adjustment analysis, shared by every screen that shows doses.
 *
 * Lifted out of the therapy screen so the Insights screen and the Doctor Visit report cannot
 * compute different suggestions from the same data — the same reason [PatternService] exists
 * for patterns.
 *
 * Everything here is local and formula-driven; no AI, no network. Whether the results are ever
 * *shown* is a separate question, governed everywhere by [Config.HIDE_DOSE_RECOMMENDATIONS] —
 * and the wording below is chosen by that flag too, so a hidden build never even composes a
 * sentence that reads as a dose instruction.
 *
 * Ported 1:1 from the iOS DoseSuggestionService, including its two generations of profile
 * lookup: the `profile…` helpers return null without a profile, the `get…ForTimeSlot` helpers
 * substitute placeholders for internal arithmetic only.
 */
object DoseSuggestionService {

    // MARK: - Profile lookups without fallbacks

    /** Basal rate (U/hr) in effect at the start of [timeSlot], or null when no basal schedule exists. */
    fun profileBasal(timeSlot: String, profile: NightscoutProfileDocument?): Double? =
        primaryProfileData(profile)?.let { segmentValue(it.basal, timeSlot) }

    /** ISF (mg/dL per unit) in effect at the start of [timeSlot], or null when none is configured. */
    fun profileISF(timeSlot: String, profile: NightscoutProfileDocument?): Double? =
        primaryProfileData(profile)?.let { segmentValue(it.sensitivity, timeSlot) }

    /** Carb ratio (g per unit) in effect at the start of [timeSlot], or null when none is configured. */
    fun profileCarbRatio(timeSlot: String, profile: NightscoutProfileDocument?): Double? =
        primaryProfileData(profile)?.let { segmentValue(it.carbRatio, timeSlot) }

    private fun primaryProfileData(profile: NightscoutProfileDocument?): ProfileStoreEntry? = profile?.activeEntry

    /** Last segment starting at or before the slot's start hour — same rule the formula path uses. */
    private fun segmentValue(segments: List<TimeValue>, timeSlot: String): Double? {
        if (segments.isEmpty()) return null
        val hourString = timeSlot.split('-', '–').firstOrNull()?.trim() ?: ""
        // Descriptive slot label ("Overnight", "Meal Times") — no hour to anchor to.
        val targetHour = hourString.split(":").firstOrNull()?.toIntOrNull() ?: return null

        var bestMatch: TimeValue? = null
        for (segment in segments) {
            val segmentHour = segment.time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            if (segmentHour <= targetHour) {
                val current = bestMatch
                val currentHour = current?.time?.split(":")?.firstOrNull()?.toIntOrNull()
                if (current != null && currentHour != null && currentHour > segmentHour) continue
                bestMatch = segment
            }
        }
        return (bestMatch ?: segments.first()).value
    }

    fun getISFValueForTimeSlot(timeSlot: String, profile: NightscoutProfileDocument?): Double {
        val sensArray = profile?.store?.values?.firstOrNull()?.sensitivity
        if (sensArray.isNullOrEmpty()) return 50.0 // Default fallback

        val hourString = timeSlot.split(" - ").firstOrNull() ?: "00:00"
        val targetHour = hourString.split(":").firstOrNull()?.toIntOrNull() ?: return sensArray.first().value

        var bestMatch = sensArray.first()
        for (entry in sensArray) {
            val hour = entry.time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            if (hour <= targetHour) bestMatch = entry
        }
        return bestMatch.value
    }

    fun getBasalValueForTimeSlot(timeSlot: String, profile: NightscoutProfileDocument?): Double {
        val basalArray = profile?.store?.values?.firstOrNull()?.basal
        if (basalArray.isNullOrEmpty()) return 1.0 // Default fallback

        val hourString = timeSlot.split(" - ").firstOrNull() ?: "00:00"
        val targetHour = hourString.split(":").firstOrNull()?.toIntOrNull() ?: return basalArray.first().value

        // The most recent basal rate before or at this hour.
        var bestMatch = basalArray.first()
        for (entry in basalArray) {
            val hour = entry.time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            if (hour <= targetHour) bestMatch = entry
        }
        return bestMatch.value
    }

    // MARK: - Analyses

    fun analyzeCorrectionEffectiveness(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Map<String, CorrectionEffectiveness> {
        val calendar = Calendar.getInstance(timeZone)
        val bySlot = LinkedHashMap<String, MutableList<Pair<Double, Double>>>() // (insulin, actualISF)

        val corrections = treatments.filter { it.eventType == "Correction Bolus" || (it.eventType == "Bolus" && (it.carbs ?: 0.0) == 0.0) }

        for (correction in corrections) {
            val correctionMillis = correction.mills ?: continue
            val insulinAmount = correction.insulin?.takeIf { it > 0 } ?: continue

            calendar.timeInMillis = correctionMillis
            val timeSlot = getTimeSlot(calendar.get(Calendar.HOUR_OF_DAY))

            // BG before the correction (within 10 minutes before).
            val beforeStart = correctionMillis - 10 * 60_000L
            val before = glucoseEntries
                .filter { it.epochMilliseconds in beforeStart..correctionMillis }
                .maxByOrNull { it.epochMilliseconds }

            // BG after the correction (2–3 hours after, to see the effect).
            val afterStart = correctionMillis + 120 * 60_000L
            val afterEnd = correctionMillis + 180 * 60_000L
            val after = glucoseEntries
                .filter { it.epochMilliseconds in afterStart..afterEnd }
                .minByOrNull { it.epochMilliseconds }

            if (before != null && after != null) {
                val bgDrop = (before.sgv - after.sgv).toDouble()
                bySlot.getOrPut(timeSlot) { mutableListOf() } += insulinAmount to bgDrop / insulinAmount
            }
        }

        return bySlot.mapValues { (slot, samples) ->
            val isfValues = samples.map { it.second }
            CorrectionEffectiveness(
                timeSlot = slot,
                correctionCount = samples.size,
                totalInsulin = samples.sumOf { it.first },
                avgBGDrop = 0.0,
                avgActualISF = if (isfValues.isEmpty()) 0.0 else isfValues.sum() / isfValues.size,
                isfValues = isfValues,
            )
        }
    }

    fun analyzeInsulinTiming(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogEntries: List<FoodLogSnapshot> = emptyList(),
    ): InsulinTimingAnalysis {
        var postMealSpikes = 0
        var totalMeals = 0

        for (treatmentTime in mealTimesForTimingAnalysis(treatments, foodLogEntries)) {
            totalMeals += 1

            // Glucose 60–120 minutes after the meal.
            val checkStart = treatmentTime + 60 * 60_000L
            val checkEnd = treatmentTime + 120 * 60_000L
            val maxGlucose = glucoseEntries
                .filter { it.epochMilliseconds in checkStart..checkEnd }
                .maxOfOrNull { it.sgv.toDouble() }
            if (maxGlucose != null && maxGlucose > 180) postMealSpikes += 1 // Significant post-meal spike
        }

        val spikeRate = if (totalMeals > 0) postMealSpikes.toDouble() / totalMeals else 0.0
        return InsulinTimingAnalysis(
            postMealSpikes = postMealSpikes,
            totalMeals = totalMeals,
            spikeRate = spikeRate,
            needsPreBolus = spikeRate > 0.3, // More than 30% of meals cause spikes
        )
    }

    fun analyzeTimeSlots(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        lowGlucose: Double,
        highGlucose: Double,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Map<String, TimeSlotAnalysis> {
        val calendar = Calendar.getInstance(timeZone)
        val bySlot = LinkedHashMap<String, MutableList<Double>>()
        for (entry in glucoseEntries) {
            calendar.timeInMillis = entry.epochMilliseconds
            bySlot.getOrPut(getTimeSlot(calendar.get(Calendar.HOUR_OF_DAY))) { mutableListOf() } += entry.sgv.toDouble()
        }

        return bySlot.mapValues { (_, values) ->
            val count = values.size.toDouble()
            TimeSlotAnalysis(
                averageGlucose = values.sum() / count,
                timeInRange = values.count { it >= lowGlucose && it <= highGlucose } / count * 100,
                timeBelowRange = values.count { it < lowGlucose } / count * 100,
                timeAboveRange = values.count { it > highGlucose } / count * 100,
                dataPoints = values.size,
            )
        }
    }

    fun getTimeSlot(hour: Int): String = when (hour) {
        in 0 until 2 -> "00:00-02:00"
        in 2 until 4 -> "02:00-04:00"
        in 4 until 6 -> "04:00-06:00"
        in 6 until 8 -> "06:00-08:00"
        in 8 until 10 -> "08:00-10:00"
        in 10 until 12 -> "10:00-12:00"
        in 12 until 14 -> "12:00-14:00"
        in 14 until 16 -> "14:00-16:00"
        in 16 until 18 -> "16:00-18:00"
        in 18 until 20 -> "18:00-20:00"
        in 20 until 22 -> "20:00-22:00"
        else -> "22:00-24:00"
    }

    // MARK: - Suggestions

    fun generateDoseAdjustments(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        profile: NightscoutProfileDocument?,
        foodLogEntries: List<FoodLogSnapshot> = emptyList(),
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<AdjustmentSuggestion> {
        // Derived from the data rather than passed in.
        val isClosedLoop = InsulinDeliveryContext(treatments, therapyType).isClosedLoop
        val hide = Config.HIDE_DOSE_RECOMMENDATIONS
        val rawSuggestions = mutableListOf<AdjustmentSuggestion>()

        val timeSlotAnalysis = analyzeTimeSlots(glucoseEntries, lowGlucose, highGlucose, timeZone)

        for ((timeSlot, analysis) in timeSlotAnalysis) {
            if (analysis.dataPoints < 3) continue // Insufficient data for a 2-hour window

            // For closed loop systems, be much more conservative with basal suggestions.
            val basalThreshold = if (isClosedLoop) 25.0 else 15.0
            val basalMinDataPoints = if (isClosedLoop) 10 else 3

            if (analysis.averageGlucose > highGlucose + basalThreshold && analysis.dataPoints >= basalMinDataPoints) {
                // High glucose pattern — suggest a basal increase (10–15% range for a meaningful change).
                val adjustmentPercent = min(15, max(10, ((analysis.averageGlucose - highGlucose) / 10).toInt()))
                val pointsAboveTarget = (analysis.averageGlucose - highGlucose).toInt()

                val priority = when {
                    pointsAboveTarget > 50 -> Priority.HIGH
                    pointsAboveTarget > 30 -> Priority.MEDIUM
                    else -> Priority.LOW
                }

                val currentBasal = getBasalValueForTimeSlot(timeSlot, profile)
                val suggestedBasal = currentBasal * (1.0 + adjustmentPercent / 100.0)

                val reasoning = StringBuilder("${priorityText(priority)}\n\n")
                reasoning.append("📊 PATTERN ANALYSIS:\n")
                reasoning.append("   • Average BG: ${analysis.averageGlucose.toInt()} mg/dL\n")
                reasoning.append("   • Target: ${highGlucose.toInt()} mg/dL\n")
                reasoning.append("   • Difference: +$pointsAboveTarget mg/dL\n")
                reasoning.append("   • Data points: ${analysis.dataPoints} readings\n")
                if (isClosedLoop) reasoning.append("   • Closed loop active: Only baseline profile adjustment suggested\n")
                if (hide) {
                    reasoning.append("\n💡 INSIGHT:\n")
                    reasoning.append("   Your glucose levels are consistently elevated during this time period. This pattern suggests your basal insulin may need review.\n\n")
                    reasoning.append("📝 WHAT THIS MEANS:\n")
                    if (isClosedLoop) {
                        reasoning.append("   Your closed loop system is making temporary adjustments, but the persistent pattern suggests your baseline basal profile may need review. Discuss with your healthcare provider how to optimize your baseline settings to help the algorithm work more effectively.\n\n")
                    } else {
                        reasoning.append("   Basal insulin provides background coverage throughout the day. Since glucose is consistently elevated during this time, your healthcare provider may want to review your basal rate settings for this period.\n\n")
                    }
                    reasoning.append("⚠️ NEXT STEPS:\n")
                    reasoning.append("   Share this pattern with your healthcare provider. They can help determine if and how to adjust your basal rate. Always consult your healthcare provider before making any changes.")
                } else {
                    reasoning.append("\n💉 RECOMMENDATION:\n")
                    reasoning.append("   Increase basal rate from ${two(currentBasal)} to ${two(suggestedBasal)} U/hr ($adjustmentPercent% increase).\n\n")
                    reasoning.append("📝 WHY THIS WORKS:\n")
                    if (isClosedLoop) {
                        reasoning.append("   Your closed loop system is making temporary adjustments, but the persistent pattern suggests your baseline basal profile needs adjustment. This will help the algorithm work more effectively.\n\n")
                    } else {
                        reasoning.append("   Basal insulin provides background coverage. Since BG is consistently elevated during this time, increasing basal helps maintain stable levels without food.\n\n")
                    }
                    reasoning.append("⚠️ IMPLEMENTATION:\n")
                    reasoning.append("   Make one small change at a time. Wait 2-3 days to observe the effect before making another adjustment. Always consult your healthcare provider.")
                }

                rawSuggestions += AdjustmentSuggestion(UUID.randomUUID().toString(), AdjustmentType.BASAL_RATE, timeSlot, currentBasal, suggestedBasal, priority, reasoning.toString())
            } else if (analysis.averageGlucose < lowGlucose - (if (isClosedLoop) 15.0 else 8.0) && analysis.dataPoints >= basalMinDataPoints) {
                // Low glucose pattern — suggest a basal decrease.
                val adjustmentPercent = min(15, max(10, ((lowGlucose - analysis.averageGlucose) / 10).toInt()))
                val pointsBelowTarget = (lowGlucose - analysis.averageGlucose).toInt()

                // HIGH priority for low BG — safety critical.
                val priority = if (pointsBelowTarget > 10) Priority.HIGH else Priority.MEDIUM

                val currentBasal = getBasalValueForTimeSlot(timeSlot, profile)
                val suggestedBasal = currentBasal * (1.0 - adjustmentPercent / 100.0)

                val reasoning = StringBuilder("${priorityText(priority)}\n\n")
                reasoning.append("📊 PATTERN ANALYSIS:\n")
                reasoning.append("   • Average BG: ${analysis.averageGlucose.toInt()} mg/dL\n")
                reasoning.append("   • Target: ${lowGlucose.toInt()} mg/dL\n")
                reasoning.append("   • Difference: -$pointsBelowTarget mg/dL\n")
                reasoning.append("   • Data points: ${analysis.dataPoints} readings\n")
                if (isClosedLoop) reasoning.append("   • Closed loop active: Baseline profile adjustment needed\n")
                if (hide) {
                    reasoning.append("\n💡 INSIGHT:\n")
                    reasoning.append("   Your glucose levels are consistently low during this time period. This pattern suggests your basal insulin may need review.\n\n")
                    reasoning.append("📝 WHAT THIS MEANS:\n")
                    if (isClosedLoop) {
                        reasoning.append("   Your closed loop system is suspending or reducing insulin, but the persistent low pattern suggests your baseline basal profile may be too aggressive. Discuss with your healthcare provider how to optimize your baseline settings to help prevent lows and reduce algorithm interventions.\n\n")
                    } else {
                        reasoning.append("   Basal insulin provides background coverage. Since glucose is consistently low during this time, your healthcare provider may want to review your basal rate settings for this period.\n\n")
                    }
                    reasoning.append("⚠️ NEXT STEPS:\n")
                    reasoning.append("   Share this pattern with your healthcare provider. They can help determine if and how to adjust your basal rate. Always consult your healthcare provider before making any changes.")
                } else {
                    reasoning.append("\n💉 RECOMMENDATION:\n")
                    reasoning.append("   Decrease basal rate from ${two(currentBasal)} to ${two(suggestedBasal)} U/hr ($adjustmentPercent% decrease).\n\n")
                    reasoning.append("📝 WHY THIS WORKS:\n")
                    if (isClosedLoop) {
                        reasoning.append("   Your closed loop system is suspending or reducing insulin, but the persistent low pattern suggests your baseline basal profile is too aggressive. Lowering it will help prevent lows and reduce algorithm interventions.\n\n")
                    } else {
                        reasoning.append("   Your basal insulin may be too high during this period, causing BG to drift low. Reducing basal prevents unnecessary lows while maintaining coverage.\n\n")
                    }
                    reasoning.append("⚠️ IMPLEMENTATION:\n")
                    reasoning.append("   Make one small change at a time. Wait 2-3 days to observe the effect before making another adjustment. Always consult your healthcare provider.")
                }

                rawSuggestions += AdjustmentSuggestion(UUID.randomUUID().toString(), AdjustmentType.BASAL_RATE, timeSlot, currentBasal, suggestedBasal, priority, reasoning.toString())
            }

            if (analysis.timeInRange < 60 && analysis.dataPoints >= 6) {
                val currentISF = getISFValueForTimeSlot(timeSlot, profile)
                val suggestedISF = currentISF * 0.90 // 10% decrease for a meaningful change

                val reasoning = StringBuilder("${priorityText(Priority.LOW)}\n\n")
                reasoning.append("📊 PATTERN ANALYSIS:\n")
                reasoning.append("   • Time in range: ${analysis.timeInRange.toInt()}%\n")
                reasoning.append("   • Target: 70%+\n")
                reasoning.append("   • Data points: ${analysis.dataPoints} readings\n\n")
                if (hide) {
                    reasoning.append("💡 INSIGHT:\n")
                    reasoning.append("   Your time in range is below target during this period. This pattern suggests your correction factor (ISF) settings may need review.\n\n")
                    reasoning.append("📝 WHAT THIS MEANS:\n")
                    reasoning.append("   Correction factor (ISF) determines how much 1 unit of insulin lowers glucose. If you're spending too much time out of range during this period, your healthcare provider may want to review your ISF settings.\n\n")
                    reasoning.append("⚠️ NEXT STEPS:\n")
                    reasoning.append("   Share this pattern with your healthcare provider. They can help determine if and how to adjust your correction factor. Always consult your healthcare provider before making any changes.")
                } else {
                    reasoning.append("💉 RECOMMENDATION:\n")
                    reasoning.append("   Adjust correction factor from 1:${currentISF.toInt()} to 1:${suggestedISF.toInt()}.\n\n")
                    reasoning.append("📝 WHY THIS WORKS:\n")
                    reasoning.append("   Correction factor (ISF) determines how much 1 unit of insulin lowers BG. If you're spending too much time out of range during this period, your correction doses may not be strong enough.\n\n")
                    reasoning.append("⚠️ IMPLEMENTATION:\n")
                    reasoning.append("   Test this adjustment carefully with correction doses during this time window. Monitor for 2-3 days before making further changes.")
                }

                rawSuggestions += AdjustmentSuggestion(UUID.randomUUID().toString(), AdjustmentType.CORRECTION_FACTOR, timeSlot, currentISF, suggestedISF, Priority.LOW, reasoning.toString())
            }
        }

        // Time-specific ISF suggestions from actual correction effectiveness.
        val correctionAnalysis = analyzeCorrectionEffectiveness(glucoseEntries, treatments, timeZone)
        for ((timeSlot, effectiveness) in correctionAnalysis) {
            // At least 3 corrections in a time slot before making a suggestion.
            if (effectiveness.correctionCount < 3) continue

            val avgActualISF = effectiveness.avgActualISF
            val assumedISF = getISFValueForTimeSlot(timeSlot, profile)
            val percentDiff = abs(avgActualISF - assumedISF) / assumedISF * 100

            if (percentDiff > 10) { // More than 10% difference
                val suggestedISF = avgActualISF * 0.95 // Conservative: 95% of measured

                val priority = when {
                    percentDiff > 30 -> Priority.HIGH
                    percentDiff > 20 -> Priority.MEDIUM
                    else -> Priority.LOW
                }

                val reasoning = StringBuilder("${priorityText(priority)}\n\n")
                reasoning.append("📊 PATTERN ANALYSIS:\n")
                reasoning.append("   • Corrections in this time: ${effectiveness.correctionCount}\n")
                reasoning.append("   • Measured ISF: 1:${avgActualISF.toInt()} mg/dL per unit\n")
                reasoning.append("   • Current setting: 1:${assumedISF.toInt()} mg/dL per unit\n")
                reasoning.append("   • Difference: ${percentDiff.toInt()}%\n\n")
                if (hide) {
                    reasoning.append("💡 INSIGHT:\n")
                    if (avgActualISF < assumedISF) {
                        reasoning.append("   Your insulin appears more effective during this time than your current settings suggest.\n\n")
                    } else {
                        reasoning.append("   Your insulin appears less effective during this time than your current settings suggest.\n\n")
                    }
                    reasoning.append("📝 WHAT THIS MEANS:\n")
                    reasoning.append("   Based on ${effectiveness.correctionCount} actual corrections, your insulin sensitivity varies by time of day. Dawn phenomenon, exercise, stress, and hormones all affect insulin action. Your healthcare provider may want to review your correction factor (ISF) settings for this time period.\n\n")
                    reasoning.append("⏱️ TIMING NOTE:\n")
                    reasoning.append("   Insulin peaks 60-90 minutes after dosing. This analysis measured glucose change 2-3 hours after corrections to assess insulin sensitivity.\n\n")
                    reasoning.append("⚠️ NEXT STEPS:\n")
                    reasoning.append("   Share this pattern with your healthcare provider. They can help determine if and how to adjust your correction factor. Always consult your healthcare provider before making any changes.")
                } else {
                    reasoning.append("💉 RECOMMENDATION:\n")
                    if (avgActualISF < assumedISF) {
                        reasoning.append("   Your insulin is MORE effective during this time.\n")
                        reasoning.append("   Adjust ISF from 1:${assumedISF.toInt()} to 1:${suggestedISF.toInt()} (stronger).\n\n")
                    } else {
                        reasoning.append("   Your insulin is LESS effective during this time.\n")
                        reasoning.append("   Adjust ISF from 1:${assumedISF.toInt()} to 1:${suggestedISF.toInt()} (weaker).\n\n")
                    }
                    reasoning.append("📝 WHY THIS WORKS:\n")
                    reasoning.append("   Based on ${effectiveness.correctionCount} actual corrections, your insulin sensitivity varies by time of day. Dawn phenomenon, exercise, stress, and hormones all affect insulin action.\n\n")
                    reasoning.append("⏱️ TIMING TIP:\n")
                    reasoning.append("   Insulin peaks 60-90 minutes after dosing. We measured BG change 2-3 hours after corrections to get accurate ISF.\n\n")
                    reasoning.append("⚠️ IMPLEMENTATION:\n")
                    reasoning.append("   Test this carefully over 3-4 days. Monitor corrections during this time window before making further changes.")
                }

                rawSuggestions += AdjustmentSuggestion(UUID.randomUUID().toString(), AdjustmentType.CORRECTION_FACTOR, timeSlot, assumedISF, suggestedISF, priority, reasoning.toString())
            }
        }

        // Combine consecutive time slots with the same adjustment.
        val suggestions = combineConsecutiveSuggestions(rawSuggestions).toMutableList()

        // Insulin timing.
        val timingAnalysis = analyzeInsulinTiming(glucoseEntries, treatments, foodLogEntries)
        if (timingAnalysis.needsPreBolus) {
            val priority = if (timingAnalysis.spikeRate > 0.6) Priority.MEDIUM else Priority.LOW

            val reasoning = StringBuilder("${priorityText(priority)}\n\n")
            reasoning.append("📊 PATTERN ANALYSIS:\n")
            reasoning.append("   • Post-meal spikes: ${timingAnalysis.postMealSpikes} out of ${timingAnalysis.totalMeals} meals\n")
            reasoning.append("   • Spike rate: ${(timingAnalysis.spikeRate * 100).toInt()}%\n\n")
            if (hide) {
                reasoning.append("💡 INSIGHT:\n")
                reasoning.append("   You're experiencing frequent post-meal glucose spikes. This pattern suggests insulin timing may need review.\n\n")
                reasoning.append("📝 WHAT THIS MEANS:\n")
                reasoning.append("   Insulin takes time to start working:\n")
                reasoning.append("   • Onset: ~15-20 minutes\n")
                reasoning.append("   • Peak effect: 60-90 minutes\n")
                reasoning.append("   • Duration: 3-4 hours\n\n")
                reasoning.append("   By dosing before eating, insulin can start working when carbs begin digesting, which may help prevent the initial spike. Your healthcare provider can help determine the best timing for your meals.\n\n")
                reasoning.append("⚠️ NEXT STEPS:\n")
                reasoning.append("   Share this pattern with your healthcare provider. They can help determine if and how to adjust your insulin timing. Always consult your healthcare provider before making any changes.")
            } else {
                reasoning.append("⏱️ RECOMMENDATION:\n")
                reasoning.append("   Pre-bolus 15-20 minutes before meals.\n\n")
                reasoning.append("📝 WHY THIS WORKS:\n")
                reasoning.append("   Insulin takes time to start working:\n")
                reasoning.append("   • Onset: ~15-20 minutes\n")
                reasoning.append("   • Peak effect: 60-90 minutes\n")
                reasoning.append("   • Duration: 3-4 hours\n\n")
                reasoning.append("   By dosing before eating, insulin starts working when carbs begin digesting, preventing the initial spike.\n\n")
                reasoning.append("⚠️ IMPLEMENTATION:\n")
                reasoning.append("   Start with 15 minutes for familiar meals. Adjust timing based on results. Be careful with high-protein or high-fat meals that digest slower.")
            }

            suggestions += AdjustmentSuggestion(UUID.randomUUID().toString(), AdjustmentType.TARGET_GLUCOSE, "Meal Times", 100.0, 100.0, priority, reasoning.toString())
        }

        return suggestions
    }

    fun combineConsecutiveSuggestions(rawSuggestions: List<AdjustmentSuggestion>): List<AdjustmentSuggestion> {
        if (rawSuggestions.isEmpty()) return emptyList()

        // Group by type, current value, suggested value, and priority.
        val grouped = LinkedHashMap<String, MutableList<AdjustmentSuggestion>>()
        for (suggestion in rawSuggestions) {
            val key = "${suggestion.type}_${suggestion.currentValue}_${suggestion.suggestedValue}_${suggestion.priority}"
            grouped.getOrPut(key) { mutableListOf() } += suggestion
        }

        val combined = mutableListOf<AdjustmentSuggestion>()
        for (suggestions in grouped.values) {
            val sorted = suggestions.sortedBy { TIME_SLOT_ORDER.indexOf(it.timeSlot).let { i -> if (i < 0) 999 else i } }

            var i = 0
            while (i < sorted.size) {
                val consecutive = mutableListOf(sorted[i])
                var j = i + 1
                while (j < sorted.size) {
                    val currentIdx = TIME_SLOT_ORDER.indexOf(consecutive.last().timeSlot)
                    val nextIdx = TIME_SLOT_ORDER.indexOf(sorted[j].timeSlot)
                    if (nextIdx == currentIdx + 1) { consecutive += sorted[j]; j += 1 } else break
                }

                if (consecutive.size > 1) {
                    val startTime = consecutive.first().timeSlot.split("-")[0]
                    val endTime = consecutive.last().timeSlot.split("-")[1]
                    val first = consecutive.first()
                    combined += AdjustmentSuggestion(
                        id = UUID.randomUUID().toString(),
                        type = first.type,
                        timeSlot = "$startTime-$endTime",
                        currentValue = first.currentValue,
                        suggestedValue = first.suggestedValue,
                        priority = first.priority,
                        reasoning = "Consistent pattern across ${consecutive.size} consecutive 2-hour periods. ${first.reasoning}",
                    )
                } else {
                    combined += consecutive[0]
                }
                i = j
            }
        }
        return combined
    }

    private val TIME_SLOT_ORDER = listOf(
        "00:00-02:00", "02:00-04:00", "04:00-06:00", "06:00-08:00",
        "08:00-10:00", "10:00-12:00", "12:00-14:00", "14:00-16:00",
        "16:00-18:00", "18:00-20:00", "20:00-22:00", "22:00-24:00",
    )

    fun priorityText(priority: Priority): String = when (priority) {
        Priority.HIGH -> "🔴 HIGH PRIORITY - Critical adjustment needed for safety and control."
        Priority.MEDIUM -> "🟠 MEDIUM PRIORITY - Important adjustment that should be addressed soon."
        Priority.LOW -> "🟢 LOW PRIORITY - Optional improvement for better control."
    }

    fun mealTimesForTimingAnalysis(treatments: List<NightscoutTreatment>, foodLogEntries: List<FoodLogSnapshot>): List<Long> {
        val mealTimes = mutableListOf<Long>()

        for (treatment in treatments) {
            val hasCarbsAmount = (treatment.carbs ?: 0.0) > 0
            val isCarbEventType = treatment.eventType in listOf("Carb", "Meal Bolus", "Carb Correction", "Snack Bolus")
            if (!hasCarbsAmount && !isCarbEventType) continue
            val millis = treatment.mills ?: continue
            mealTimes += millis
        }

        for (entry in foodLogEntries) {
            if ((entry.carbsGrams ?: 0.0) <= 0) continue
            val alreadyCovered = mealTimes.any { abs(it - entry.recordedAtMillis) < 15 * 60_000L }
            if (!alreadyCovered) mealTimes += entry.recordedAtMillis
        }

        return mealTimes.sorted()
    }

    private fun two(value: Double): String = String.format(Locale.US, "%.2f", value)
}
