package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.engine.GlucoseWeeklyReportBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Guards the gate on "Compared with previous week".
 *
 * A freshly re-onboarded profile holds only the few days its source can still serve, and the
 * report used to declare a week-over-week comparison off as little as an hour of readings in
 * the prior window — so three real days were shown next to a "last week" that barely existed.
 *
 * Ported from the iOS GlucoseWeeklyReportComparisonTests.
 */
class GlucoseWeeklyReportBuilderTest {

    private val low = 70.0
    private val high = 180.0
    private val zone: TimeZone = TimeZone.getDefault()
    private val DAY = 86_400_000L

    private val now: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 12, 0, 0) }.timeInMillis

    /** Five-minute readings across `days`, counted back from `endingDaysAgo`. */
    private fun entries(days: Int, endingDaysAgo: Int, sgv: Int): List<NightscoutGlucoseEntry> {
        val result = mutableListOf<NightscoutGlucoseEntry>()
        for (day in 0 until days) {
            val dayStart = now - (endingDaysAgo + day) * DAY
            for (step in 0 until 288) {
                val at = dayStart + step * 300_000L
                if (at >= now) continue
                result += NightscoutGlucoseEntry(sgv = sgv, direction = null, date = at, device = "test")
            }
        }
        return result
    }

    private fun build(entries: List<NightscoutGlucoseEntry>) =
        GlucoseWeeklyReportBuilder.build(entries = entries, lowMgdL = low, highMgdL = high, nowMillis = now, timeZone = zone)

    @Test
    fun `three days of history produces no previous week`() {
        val report = build(entries(days = 3, endingDaysAgo = 1, sgv = 120))

        assertTrue(report.hasEnoughCurrentData)
        assertNull(report.previous)
        assertNull(report.previousPeriodStartMillis)
        assertFalse(report.plainLanguageSummary.contains("last week"))
    }

    @Test
    fun `a sliver of old data does not pass as a previous week`() {
        // Three real days, plus a single stray day sitting inside the 7–14 day window —
        // comfortably past the old 12-reading floor.
        val pool = entries(days = 3, endingDaysAgo = 1, sgv = 120) + entries(days = 1, endingDaysAgo = 9, sgv = 200)

        assertNull(build(pool).previous)
    }

    @Test
    fun `two full weeks still compare`() {
        val report = build(entries(days = 14, endingDaysAgo = 0, sgv = 120))

        assertNotNull(report.previous)
        assertNotNull(report.previousPeriodStartMillis)
        assertNotNull(report.previousPeriodEndMillis)
    }
}
