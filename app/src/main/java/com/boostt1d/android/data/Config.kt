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

    // MARK: - Backend
    //
    // Both AI features go through the BoostT1D proxy; the Gemini key never leaves the server
    // (it spends the Google credits through Vertex, which only the backend can reach).

    /** Meal photos → nutrition estimate. */
    const val BACKEND_FOOD_ANALYSIS_URL = "https://boostt1d.com/api/food-analysis"
    /** Text-only pattern and therapy review. */
    const val BACKEND_INSIGHTS_URL = "https://boostt1d.com/api/insights"
    const val SUPPORT_PAGE_URL = "https://boostt1d.com/support"

    /**
     * Demographics-only registration, no account. iOS posts to `/api/ios/register-profile`;
     * the server needs this route added (or the iOS one generalised) before the client below
     * can succeed. Best-effort either way — onboarding never waits on it.
     */
    const val REGISTRATION_URL = "https://boostt1d.com/api/android/register-profile"
    const val REGISTRATION_MARK_DELETED_URL = "$REGISTRATION_URL/mark-deleted"
    const val REGISTRATION_MARKETING_OPT_IN_URL = "$REGISTRATION_URL/marketing-opt-in"

    // MARK: - Build-time feature switches

    /** `false` = every pattern and insight comes from the local formula. `true` = AI, falling back to formula silently. */
    const val AI_INSIGHTS_ENABLED = true

    /** `true` = [FOOD_ANALYSIS_DAILY_LIMIT] free estimations per day. `false` = unlimited. */
    const val LIMIT_FOOD_ANALYSIS = true
    const val FOOD_ANALYSIS_DAILY_LIMIT = 6

    const val IS_DEV_MODE = false

    /** Dev only: show Re-run AI and allow bypassing the once-per-day therapy AI cache. */
    val ALLOW_REPEATED_DAILY_AI_REVIEW: Boolean get() = IS_DEV_MODE
}
