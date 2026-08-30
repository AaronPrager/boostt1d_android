package com.boostt1d.android.data

import kotlin.math.sqrt

/**
 * Summary statistics derived from a set of glucose readings.
 *
 * Kept free of Compose so the arithmetic can be tested directly — these are numbers a
 * user may take to a clinician, so they are pinned to published references rather than
 * to whatever the code currently produces. All values are mg/dL.
 */
data class GlucoseStatistics(
    val estimatedA1C: Double = 0.0,
    val averageGlucose: Double = 0.0,
    val standardDeviation: Double = 0.0,
    val coefficientOfVariation: Double = 0.0,
    val gmi: Double = 0.0,
    val totalReadings: Int = 0,
    val timeInRange: Double = 0.0,
    val timeAboveRange: Double = 0.0,
    val timeBelowRange: Double = 0.0,
    /**
     * The share of readings above the ADA "very high" threshold. A subset of
     * [timeAboveRange], not a fourth slice — the three bands still total 100.
     */
    val timeVeryHigh: Double = 0.0,
) {
    companion object {
        /**
         * Derives statistics from raw mg/dL values.
         *
         * Returns all zeroes for empty input rather than producing NaNs by dividing by a
         * zero count — a dashboard showing "NaN%" is worse than one showing nothing.
         *
         * @param low lower bound of the target range, inclusive.
         * @param high upper bound of the target range, inclusive.
         */
        fun calculate(values: List<Double>, low: Double, high: Double): GlucoseStatistics {
            if (values.isEmpty()) return GlucoseStatistics()

            val count = values.size.toDouble()
            val average = values.sum() / count

            // Population, not sample, standard deviation.
            val variance = values.sumOf { (it - average) * (it - average) } / count
            val standardDeviation = sqrt(variance)

            val cv = if (average == 0.0) 0.0 else (standardDeviation / average) * 100

            // Nathan et al. eAG-to-A1C regression.
            val estimatedA1C = (average + 46.7) / 28.7

            // Bergenstal et al. glucose management indicator.
            val gmi = 3.31 + (0.02392 * average)

            val inRange = values.count { it in low..high }
            val aboveRange = values.count { it > high }
            val belowRange = values.count { it < low }
            val veryHigh = values.count { it >= GlucoseCacheRules.VERY_HIGH_MGDL }

            return GlucoseStatistics(
                estimatedA1C = estimatedA1C,
                averageGlucose = average,
                standardDeviation = standardDeviation,
                coefficientOfVariation = cv,
                gmi = gmi,
                totalReadings = values.size,
                timeInRange = inRange / count * 100,
                timeAboveRange = aboveRange / count * 100,
                timeBelowRange = belowRange / count * 100,
                timeVeryHigh = veryHigh / count * 100,
            )
        }
    }
}

/** Rolling on-device history limits, ported from the iOS CGMGlucoseCache. */
object GlucoseCacheRules {
    /** How long readings are kept locally. 14 days enables week-over-week comparison. */
    const val RETENTION_DAYS = 14

    /** Typical CGM cadence, used for coverage expectations. */
    const val STORAGE_BUCKET_MINUTES = 5

    /** Overlay-chart aggregation, independent of storage cadence. */
    const val AGP_BUCKET_MINUTES = 10

    /** ADA "very high" threshold (mg/dL). */
    const val VERY_HIGH_MGDL = 250.0

    const val MAX_RETENTION_MILLIS = RETENTION_DAYS * 24L * 60 * 60 * 1000
}
