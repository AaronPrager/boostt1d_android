package com.boostt1d.android.data

/**
 * Build-time switches. Edit the values directly; every call site reads them by name.
 *
 * Mirrors the iOS `Config`, and the values have to stay in step: these decide what the
 * app is allowed to tell someone, and two platforms answering differently is not a
 * difference in polish.
 */
object Config {

    /**
     * `true` withholds every computed dose figure from the insulin calculator, showing
     * the formulas in named terms as reference material instead.
     *
     * This is not a styling preference. Apple withholds it under App Store Guideline
     * 1.4.1 (drug dosage calculation) and Google Play's health-apps policy is comparable,
     * so the shipping value is `true` on both platforms. Setting it false makes BoostT1D
     * an app that recommends insulin doses, which is a different product with different
     * obligations.
     */
    const val HIDE_DOSE_RECOMMENDATIONS = true

    /** The formulas, in named terms rather than anyone's numbers. */
    data class FormulaStep(
        val number: Int,
        val title: String,
        val formula: String,
        val explanation: String,
    )

    val educationalFormulaSteps = listOf(
        FormulaStep(
            1, "Carb Coverage",
            "Carb grams ÷ Carb ratio",
            "Carbohydrate grams divided by the carb ratio gives the units that cover a meal.",
        ),
        FormulaStep(
            2, "Correction Dose",
            "(Current glucose − Target glucose) ÷ Correction factor",
            "Used only when glucose is above target. At or below target, this step is skipped.",
        ),
        FormulaStep(
            3, "Total Insulin Needed",
            "Carb coverage + Correction",
            "The carb coverage units plus any correction units.",
        ),
        FormulaStep(
            4, "Active Insulin (IOB) Adjustment",
            "Available IOB = Active IOB − (Carbs on board ÷ Carb ratio)",
            "Insulin already working on carbs on board is set aside; only the remainder " +
                "counts against a new meal. With no carbs on board, all active insulin counts.",
        ),
        FormulaStep(
            5, "Final Dose",
            "Total insulin needed − Available IOB",
            "Subtracting available IOB is what avoids insulin stacking, which reduces the " +
                "risk of hypoglycemia.",
        ),
    )
}
