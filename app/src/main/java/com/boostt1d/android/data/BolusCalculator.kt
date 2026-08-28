package com.boostt1d.android.data

import kotlin.math.max
import kotlin.math.min

/**
 * Insulin dose arithmetic, kept free of Compose so it can be exercised directly by
 * tests. The screen owns parsing and presentation; the numbers are decided here.
 *
 * Glucose, target and correction factor are all expected in the *same* unit — the
 * user's display unit — which keeps the correction term unit-agnostic.
 */
object BolusCalculator {

    data class Input(
        val carbs: Double,
        val carbRatio: Double,
        val glucose: Double? = null,
        val targetGlucose: Double? = null,
        val insulinSensitivity: Double? = null,
        val iob: Double = 0.0,
        val cob: Double = 0.0,
    )

    data class Output(
        /** Units covering the meal's carbohydrates. */
        val carbBolusUnits: Double,
        /**
         * Units correcting glucose above target. Zero at or below target, and zero when
         * glucose, target or correction factor is missing.
         */
        val correctionUnits: Double,
        /** Carb coverage plus correction, before any IOB adjustment. */
        val totalUnits: Double,
        /** Active insulin already accounted for by carbs on board. */
        val iobNeededForCOB: Double,
        /** Active insulin not already spoken for by carbs on board. */
        val availableIOB: Double,
        /** How much of the total was actually offset by available IOB. */
        val iobReduction: Double,
        /** The recommended dose: total minus available IOB, never negative. */
        val safeBolus: Double,
        /**
         * Active insulin left over once this meal is fully covered. Zero unless the raw
         * subtraction would have gone negative.
         */
        val excessInsulinUnits: Double,
        /**
         * Carbohydrates that would offset [excessInsulinUnits] at the user's own carb
         * ratio. Zero when there is no excess.
         *
         * Derived only from the entered numbers. Excess insulin does not by itself mean
         * carbs are needed — it may be correcting a high that has not come down yet,
         * which is why glucose feeds the total above.
         */
        val carbsToOffsetExcess: Double,
    )

    sealed class ValidationError(message: String) : Exception(message) {
        data object InvalidCarbs : ValidationError("Carbs cannot be negative.")
        data object InvalidCarbRatio : ValidationError("Carb ratio must be greater than zero.")
    }

    /**
     * @throws ValidationError when carbs are negative or the carb ratio is not positive —
     *   dividing by a zero ratio would otherwise yield infinity and present it as a dose.
     */
    fun calculate(input: Input): Output {
        if (input.carbs < 0) throw ValidationError.InvalidCarbs
        if (input.carbRatio <= 0) throw ValidationError.InvalidCarbRatio

        val carbBolusUnits = input.carbs / input.carbRatio

        // A correction applies only when all three inputs are present, the correction
        // factor is usable, and glucose is actually above target.
        val glucose = input.glucose
        val target = input.targetGlucose
        val isf = input.insulinSensitivity
        val correctionUnits =
            if (glucose != null && target != null && isf != null && isf > 0 && glucose > target) {
                (glucose - target) / isf
            } else {
                0.0
            }

        val totalUnits = carbBolusUnits + correctionUnits

        // Insulin already working on carbs on board is not available to offset this meal,
        // so it is excluded before subtracting IOB.
        val iobNeededForCOB = input.cob / input.carbRatio
        val availableIOB = max(0.0, input.iob - iobNeededForCOB)

        // Subtracting available IOB is what prevents insulin stacking.
        val safeBolus = max(0.0, totalUnits - availableIOB)
        val iobReduction = min(availableIOB, totalUnits)

        // Where the subtraction would have gone negative, the shortfall is active insulin
        // with nothing left to cover it. The carb ratio expresses that shortfall in grams.
        val excessInsulinUnits = max(0.0, availableIOB - totalUnits)
        val carbsToOffsetExcess = excessInsulinUnits * input.carbRatio

        return Output(
            carbBolusUnits = carbBolusUnits,
            correctionUnits = correctionUnits,
            totalUnits = totalUnits,
            iobNeededForCOB = iobNeededForCOB,
            availableIOB = availableIOB,
            iobReduction = iobReduction,
            safeBolus = safeBolus,
            excessInsulinUnits = excessInsulinUnits,
            carbsToOffsetExcess = carbsToOffsetExcess,
        )
    }

    /**
     * Names the inputs missing for a correction, so the screen can say why one was
     * skipped. Empty when no glucose was entered (nothing to correct) or when everything
     * needed is present.
     */
    fun missingCorrectionInputs(input: Input): List<String> {
        if (input.glucose == null) return emptyList()
        val missing = mutableListOf<String>()
        if (input.targetGlucose == null) missing.add("target glucose")
        if (input.insulinSensitivity == null || input.insulinSensitivity == 0.0) {
            missing.add("correction factor")
        }
        return missing
    }
}
