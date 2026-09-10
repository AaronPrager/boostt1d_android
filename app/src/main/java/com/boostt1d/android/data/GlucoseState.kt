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
 * [asOfMillis] is when the loop published it, or when Nightscout worked it out. The figures
 * are hidden only while the glucose connection itself is stale, as on iOS: a loop that
 * uploads every five minutes is current for as long as the readings beside it are, and a
 * site with no loop still answers through Nightscout's own IOB/COB calculation.
 */
data class OnBoard(
    val insulinUnits: Double? = null,
    val carbsGrams: Double? = null,
    val asOfMillis: Long = 0L,
    /** True when figures were withheld because the last glucose reading is too old. */
    val connectionStale: Boolean = false,
) {
    val isEmpty: Boolean get() = insulinUnits == null && carbsGrams == null

    companion object {
        /** Past this since the last reading, the connection is stale and on-board figures with it. */
        const val CONNECTION_STALE_MILLIS = 15L * 60 * 1000

        val none = OnBoard()
        val stale = OnBoard(connectionStale = true)

        fun unlessConnectionStale(value: OnBoard, latestReadingMillis: Long?, nowMillis: Long): OnBoard =
            if (latestReadingMillis != null && nowMillis - latestReadingMillis <= CONNECTION_STALE_MILLIS) value else stale
    }
}
