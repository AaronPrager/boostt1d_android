package com.boostt1d.android.data

/**
 * How a reading reads against the user's own range.
 *
 * The bands are the user's low and high, plus a "very high" at 1.4x the high — far
 * enough above target that it is a different situation, not just a worse one.
 */
enum class GlucoseState(val label: String) {
    LOW("Below range"),
    IN_RANGE("In range"),
    HIGH("Above range"),
    VERY_HIGH("Very high"),
    UNKNOWN("No data");

    companion object {
        fun classify(mgdl: Double?, low: Double, high: Double): GlucoseState {
            if (mgdl == null) return UNKNOWN
            if (mgdl < low) return LOW
            if (mgdl > high * 1.4) return VERY_HIGH
            if (mgdl > high) return HIGH
            return IN_RANGE
        }
    }
}

/** The windows the dashboard trend can show. */
enum class TrendRange(val hours: Int, val label: String) {
    TWO_HOURS(2, "2h"),
    SIX_HOURS(6, "6h"),
    TWELVE_HOURS(12, "12h"),
    TWENTY_FOUR_HOURS(24, "24h");

    val millis: Long get() = hours * 60L * 60 * 1000
}

/**
 * Insulin and carbs still acting, as reported by the uploading loop.
 *
 * Never estimated here. IOB needs a decay curve over known dose times and COB needs an
 * absorption model; deriving either from a manual log would produce a number that looks
 * computed and isn't, next to a reading someone is about to act on.
 *
 * [asOfMillis] is when the loop published it. Stale figures are dropped rather than shown,
 * because "2.4 U on board" from three hours ago is worse than "--".
 */
data class OnBoard(
    val insulinUnits: Double? = null,
    val carbsGrams: Double? = null,
    val asOfMillis: Long = 0L,
) {
    val isEmpty: Boolean get() = insulinUnits == null && carbsGrams == null

    companion object {
        /** Beyond this the loop's figures no longer describe now. */
        const val FRESHNESS_WINDOW_MILLIS = 15L * 60 * 1000

        val none = OnBoard()

        fun freshOrNone(value: OnBoard, nowMillis: Long): OnBoard =
            if (value.asOfMillis > 0 && nowMillis - value.asOfMillis <= FRESHNESS_WINDOW_MILLIS) {
                value
            } else {
                none
            }
    }
}
