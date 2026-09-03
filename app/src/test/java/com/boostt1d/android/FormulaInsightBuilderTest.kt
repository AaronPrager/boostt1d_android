package com.boostt1d.android

import com.boostt1d.android.engine.FormulaInsightBuilder
import com.boostt1d.android.engine.OutOfRangeDriver
import com.boostt1d.android.engine.PatternInsight
import com.boostt1d.android.engine.PatternSource
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.SlotAnalysisSnapshot
import com.boostt1d.android.engine.WhatHappenedPattern
import com.boostt1d.android.engine.WhatHappenedPatternChartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FormulaInsightBuilder has no dedicated test file on iOS; these pin the copy the port was
 * read from — windows, period names, drivers and the AI-prose bullet cleanup.
 */
class FormulaInsightBuilderTest {

    private val snapshot = SlotAnalysisSnapshot(
        averageGlucose = 185.4, timeInRange = 41.9, timeBelowRange = 0.0, timeAboveRange = 58.1, dataPoints = 36,
    )

    @Test
    fun `time windows are written with an en dash, overviews as a day view`() {
        assertEquals("00:00–02:00", FormulaInsightBuilder.formatTimeWindow("00:00-02:00"))
        assertEquals("7-day view", FormulaInsightBuilder.formatTimeWindow("7-day overview"))
        assertEquals("Meal Times", FormulaInsightBuilder.formatTimeWindow("Meal Times"))
    }

    @Test
    fun `the driver needs a five-point margin either way`() {
        assertEquals(OutOfRangeDriver.MOSTLY_HIGH, FormulaInsightBuilder.outOfRangeDriver(below = 2, above = 20))
        assertEquals(OutOfRangeDriver.MOSTLY_LOW, FormulaInsightBuilder.outOfRangeDriver(below = 20, above = 2))
        assertEquals(OutOfRangeDriver.MIXED, FormulaInsightBuilder.outOfRangeDriver(below = 10, above = 15))
        assertEquals(OutOfRangeDriver.MIXED, FormulaInsightBuilder.outOfRangeDriver(below = 0, above = 5))
    }

    @Test
    fun `the sample overnight high reads as iOS renders it`() {
        val insight = FormulaInsightBuilder.sampleOvernightHigh

        assertEquals("Overnight highs", insight.title)
        assertEquals("00:00–02:00", insight.timeWindow)
        assertEquals("Only 41% in range from 00:00–02:00. Most out-of-range readings were high.", insight.summary)
        assertEquals(Priority.HIGH, insight.priority)
        assertEquals(41, insight.inRangePercent)
        assertEquals(58, insight.highPercent)
        assertEquals(0, insight.lowPercent)
        assertEquals(36, insight.readingCount)
        assertTrue(insight.hasMetricStats)
        assertNull(insight.comparison)
        assertEquals(4, insight.contributors.size)
        assertTrue(insight.doctorQuestions.first().contains("evening insulin and overnight settings"))
    }

    @Test
    fun `period names follow the slot's start hour`() {
        fun title(key: String) = FormulaInsightBuilder.makeSlotTIRInsight(key, Priority.LOW, snapshot, 70, 7, 20, 2).title
        assertEquals("Overnight lows", title("03:00-05:00"))
        assertEquals("Morning lows", title("06:00-08:00"))
        assertEquals("Afternoon lows", title("12:00-14:00"))
        assertEquals("Evening lows", title("17:00-19:00"))
        assertEquals("Night lows", title("21:00-23:00"))
        assertEquals("Meal-time lows", title("Meal Times"))
    }

    @Test
    fun `closed loop adds one contributor to the high and low lists`() {
        val open = FormulaInsightBuilder.makeSustainedHighInsight("00:00-02:00", Priority.HIGH, snapshot, 25, 180, 70, 7, isClosedLoop = false)
        val looped = FormulaInsightBuilder.makeSustainedHighInsight("00:00-02:00", Priority.HIGH, snapshot, 25, 180, 70, 7, isClosedLoop = true)

        assertEquals("Overnight elevated glucose", open.title)
        assertEquals("Average 185 mg/dL from 00:00–02:00 — about 25 mg/dL above your 180 mg/dL upper target.", open.summary)
        assertEquals(4, open.contributors.size)
        assertEquals(5, looped.contributors.size)
        assertEquals("Sustained averages — not single temp basal changes", looped.contributors.last())
    }

    @Test
    fun `the overview names its weakest windows and flags a sub-70 goal`() {
        val insight = FormulaInsightBuilder.makeOverviewInsight(
            timeSlotKey = "7-day overview", priority = Priority.MEDIUM, overallTIR = 62, belowRange = 4, aboveRange = 34,
            readingCount = 1900, averageGlucose = 172, periodDays = 7,
            worstWindowLabels = listOf("00:00–02:00 (38% in range)", "18:00–20:00 (45% in range)", "12:00–14:00 (60% in range)"),
        )

        assertEquals("Overall highs pattern", insight.title)
        assertEquals("7-day view", insight.timeWindow)
        assertEquals(
            "Only 62% in range over the last 7 days. Most out-of-range readings were high. Weakest windows: 00:00–02:00 (38% in range), 18:00–20:00 (45% in range).",
            insight.summary,
        )
        assertEquals("Below common goal of ~70% in range", insight.comparison)
        assertTrue(insight.doctorQuestions.first().contains("00:00–02:00 (38% in range) and 18:00–20:00 (45% in range)"))

        val fine = FormulaInsightBuilder.makeOverviewInsight("7-day overview", Priority.LOW, 78, 3, 19, 1900, 150, 7, emptyList())
        assertNull(fine.comparison)
        assertTrue(fine.doctorQuestions.first().contains("about 78%"))
    }

    @Test
    fun `AI prose becomes clean bullets and at most two questions`() {
        val insight = FormulaInsightBuilder.makeFromAI(
            timeSlotKey = "06:00-08:00",
            priority = Priority.MEDIUM,
            trendObservation = "  Glucose ran high after breakfast on most days. ",
            factorsProse = "Review bolus timing. check the breakfast carb ratio. Late snacks. Stress or illness. A fifth thing.",
            doctorQuestions = listOf("  ", "Is the ratio right?", "Should I pre-bolus?", "A third question"),
        )

        assertEquals("Morning pattern", insight.title)
        assertEquals("06:00–08:00", insight.timeWindow)
        assertEquals("Glucose ran high after breakfast on most days.", insight.summary)
        assertEquals(listOf("Bolus timing", "The breakfast carb ratio", "Late snacks", "Stress or illness"), insight.contributors)
        assertEquals(listOf("Is the ratio right?", "Should I pre-bolus?"), insight.doctorQuestions)
        assertFalse(insight.hasMetricStats)

        val noQuestions = FormulaInsightBuilder.makeFromAI("06:00-08:00", Priority.LOW, "x", "", listOf("   "))
        assertEquals(listOf("Could related habits and settings be worth reviewing together?"), noQuestions.doctorQuestions)
        assertTrue(noQuestions.contributors.isEmpty())
    }

    @Test
    fun `a WhatHappened pattern maps onto an insight without inventing numbers`() {
        val pattern = WhatHappenedPattern(
            id = "p1", source = PatternSource.AI_ENRICHED, title = "Repeated lunch highs",
            observation = "Average glucose during lunch was above 180 mg/dL on 5 of 7 days.",
            frequencyLabel = "5 of 7 days", occurrenceCount = 5, opportunityCount = 7, priority = Priority.HIGH,
            chartPoints = emptyList(), chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE,
            contributingFactors = listOf("a"), discussQuestions = listOf("b"), score = 1.0,
        )

        val insight = PatternInsight.from(pattern)
        assertEquals("p1", insight.id)
        assertEquals("Repeated lunch highs", insight.timeSlotKey)
        assertEquals("5 of 7 days", insight.timeWindow)
        assertEquals("AI-reviewed", insight.comparison)
        assertFalse(insight.hasMetricStats)
        assertNull(PatternInsight.from(pattern.copy(source = PatternSource.FORMULA)).comparison)
    }
}
