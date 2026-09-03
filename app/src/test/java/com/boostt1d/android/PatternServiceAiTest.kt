package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.engine.AIEnrichedPattern
import com.boostt1d.android.engine.AIPatternResponse
import com.boostt1d.android.engine.AIProposedPattern
import com.boostt1d.android.engine.PatternService
import com.boostt1d.android.engine.PatternSource
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.WhatHappenedPattern
import com.boostt1d.android.engine.WhatHappenedPatternChartKind
import com.boostt1d.android.engine.WhatHappenedPatternChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** AI may reword a pattern; it may never move a number. */
class PatternServiceAiTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val now: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 15, 30, 0) }.timeInMillis
    private val service = PatternService(timeZone = zone)

    private fun formula() = WhatHappenedPattern(
        id = "f1", title = "Overnight glucose rise", observation = "Glucose tended to rise overnight on 5 of 7 nights.",
        frequencyLabel = "5 of 7 nights", occurrenceCount = 5, opportunityCount = 7, priority = Priority.HIGH,
        chartPoints = listOf(WhatHappenedPatternChartPoint(label = "Mon", value = 30.0, highlighted = true)),
        chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE, contributingFactors = listOf("Basal"), discussQuestions = listOf("Q"), score = 90.0, hours = (0 until 7).toSet(),
    )

    /** Seven complete days of readings so an AI proposal has real days to be clamped against. */
    private fun week(): List<NightscoutGlucoseEntry> {
        val start = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_MONTH, -7) }.timeInMillis
        return (0 until 7 * 288).map { NightscoutGlucoseEntry(sgv = 120, date = start + it * 300_000L, device = "t") }
    }

    @Test
    fun `enrichment changes wording only`() {
        val base = formula()
        val merged = service.merge(
            listOf(base),
            AIPatternResponse(enriched = listOf(AIEnrichedPattern(0, "Clearer wording.", listOf("Late dinner"), listOf("Ask about basal timing")))),
            week(), 7, now,
        )
        val p = merged.single()
        assertEquals(PatternSource.AI_ENRICHED, p.source)
        assertEquals("Clearer wording.", p.observation)
        assertEquals(listOf("Late dinner"), p.contributingFactors)
        assertEquals(5, p.occurrenceCount); assertEquals(7, p.opportunityCount); assertEquals("5 of 7 nights", p.frequencyLabel)
        assertEquals(base.chartPoints, p.chartPoints); assertEquals(90.0, p.score, 0.0)
    }

    @Test
    fun `a proposal is clamped to real days and dropped when it claims a weekday the window cannot support`() {
        val merged = service.merge(
            listOf(formula()),
            AIPatternResponse(additional = listOf(
                AIProposedPattern("Pizza runs late", "Glucose was still rising four hours after pizza.", occurrenceCount = 12, priority = "medium"),
                AIProposedPattern("Friday highs", "Fridays ran higher.", occurrenceCount = 1),
            )),
            week(), 7, now,
        )
        assertEquals(2, merged.size)
        val proposed = merged.last()
        assertEquals(PatternSource.AI, proposed.source)
        assertEquals(7, proposed.opportunityCount)
        assertEquals(7, proposed.occurrenceCount)
        assertEquals("7 of 7 days", proposed.frequencyLabel)
        assertEquals(Priority.MEDIUM, proposed.priority)
        assertEquals(7, proposed.chartPoints.size)
    }

    @Test
    fun `stored wording reapplies by title and the day's titles are carried forward`() {
        val merged = service.merge(listOf(formula()), AIPatternResponse(enriched = listOf(AIEnrichedPattern(0, "Reworded."))), week(), 7, now)
        val stored = service.record(merged, listOf(formula()), now, previous = null)
        assertEquals(1, stored.reviewCount)
        assertEquals(listOf("Overnight glucose rise"), stored.reviewedTitles)

        // Later the same day: the detector re-finds the pattern, and a new one appears.
        val fresh = formula().copy(id = "f2", occurrenceCount = 6, frequencyLabel = "6 of 7 nights")
        val newcomer = formula().copy(id = "f3", title = "Afternoon lows", hours = (14 until 17).toSet())
        val applied = service.apply(stored, listOf(fresh, newcomer), week(), 7, now + 3_600_000)
        assertEquals("Reworded.", applied[0].observation)
        assertEquals(6, applied[0].occurrenceCount)
        assertEquals(PatternSource.FORMULA, applied[1].source)

        val again = service.record(applied, listOf(fresh, newcomer), now + 3_600_000, previous = stored)
        assertEquals(2, again.reviewCount)
        assertTrue(again.reviewedTitles.containsAll(listOf("Overnight glucose rise", "Afternoon lows")))
    }
}
