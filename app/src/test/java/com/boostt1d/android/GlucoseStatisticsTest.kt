package com.boostt1d.android

import com.boostt1d.android.data.GlucoseStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs

/**
 * The clinical summary numbers. A user may show these to a clinician, so the formulas
 * are pinned to published references rather than to current output.
 *
 * Ported from the iOS GlucoseStatisticsTests.
 */
class GlucoseStatisticsTest {

    private val low = 70.0
    private val high = 180.0

    @Test
    fun `no readings yields zeroes, not NaN`() {
        val stats = GlucoseStatistics.calculate(emptyList(), low, high)

        assertEquals(GlucoseStatistics(), stats)
        assertEquals(0, stats.totalReadings)
        assertFalse(stats.averageGlucose.isNaN())
        assertFalse(stats.coefficientOfVariation.isNaN())
        assertFalse(stats.timeInRange.isNaN())
    }

    @Test
    fun `average and reading count`() {
        val stats = GlucoseStatistics.calculate(listOf(100.0, 200.0, 150.0, 50.0), low, high)

        assertEquals(125.0, stats.averageGlucose, 0.0)
        assertEquals(4, stats.totalReadings)
    }

    @Test
    fun `estimated A1C follows the eAG regression`() {
        // (154 + 46.7) / 28.7 ~ 6.99 — the canonical "eAG 154 ~ A1C 7%" anchor.
        val stats = GlucoseStatistics.calculate(listOf(154.0), low, high)

        assertEquals(6.9930, stats.estimatedA1C, 0.001)
    }

    @Test
    fun `GMI follows the Bergenstal formula`() {
        // 3.31 + 0.02392 x 154 = 6.9937
        val stats = GlucoseStatistics.calculate(listOf(154.0), low, high)

        assertEquals(6.9937, stats.gmi, 0.001)
    }

    @Test
    fun `standard deviation and coefficient of variation`() {
        // Population SD of [100, 200] about a mean of 150 is exactly 50.
        val stats = GlucoseStatistics.calculate(listOf(100.0, 200.0), low, high)

        assertEquals(50.0, stats.standardDeviation, 0.0001)
        assertEquals(50.0 / 150.0 * 100, stats.coefficientOfVariation, 0.0001)
    }

    @Test
    fun `a flat series has zero variability`() {
        val stats = GlucoseStatistics.calculate(listOf(120.0, 120.0, 120.0), low, high)

        assertEquals(0.0, stats.standardDeviation, 0.0)
        assertEquals(0.0, stats.coefficientOfVariation, 0.0)
    }

    @Test
    fun `range boundaries are inclusive`() {
        // Exactly 70 and exactly 180 are in range, not below and above it.
        val stats = GlucoseStatistics.calculate(listOf(70.0, 180.0), low, high)

        assertEquals(100.0, stats.timeInRange, 0.0001)
        assertEquals(0.0, stats.timeBelowRange, 0.0001)
        assertEquals(0.0, stats.timeAboveRange, 0.0001)
    }

    @Test
    fun `time in range splits three ways and totals one hundred`() {
        val stats = GlucoseStatistics.calculate(listOf(50.0, 120.0, 120.0, 250.0), low, high)

        assertEquals(25.0, stats.timeBelowRange, 0.0001)
        assertEquals(50.0, stats.timeInRange, 0.0001)
        assertEquals(25.0, stats.timeAboveRange, 0.0001)
        assertEquals(
            100.0,
            stats.timeInRange + stats.timeAboveRange + stats.timeBelowRange,
            0.0001,
        )
    }

    @Test
    fun `a zero average cannot divide by zero`() {
        val stats = GlucoseStatistics.calculate(listOf(0.0, 0.0), low, high)

        assertEquals(0.0, stats.coefficientOfVariation, 0.0)
        assertFalse(abs(stats.coefficientOfVariation).isNaN())
    }
}
