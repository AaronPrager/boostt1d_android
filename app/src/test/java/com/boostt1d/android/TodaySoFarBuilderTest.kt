package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.TodaySoFar
import com.boostt1d.android.data.TodaySoFarBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Today's strip. The comparison is deliberately against the *same clock hours* on
 * completed days — measuring a morning against whole days would make every morning look
 * calm from the shape of the day rather than from anything that happened.
 *
 * Ported from the iOS TodaySoFarBuilderTests.
 */
class TodaySoFarBuilderTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** Midday on a fixed date, so the tests never depend on when they run. */
    private val now = 1_756_387_200_000L // 2025-08-28T12:00:00Z

    private val dayStart = TodaySoFarBuilder.startOfDay(now, utc)

    private fun entry(offsetMillis: Long, sgv: Int) =
        NightscoutGlucoseEntry(sgv = sgv, date = dayStart + offsetMillis)

    private fun minutes(n: Long) = n * 60 * 1000

    @Test
    fun `no readings today gives the empty strip`() {
        val result = TodaySoFarBuilder.build(emptyList(), 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertEquals(TodaySoFar.empty, result)
        assertFalse(result.hasEnoughData)
    }

    @Test
    fun `a handful of readings is not enough of today to speak for it`() {
        val entries = (1..3).map { entry(minutes(it * 5L), 120) }

        val result = TodaySoFarBuilder.build(entries, 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertEquals(3, result.readingCount)
        assertFalse(result.hasEnoughData)
    }

    @Test
    fun `time in range splits and totals one hundred`() {
        val entries = listOf(
            entry(minutes(10), 50), entry(minutes(20), 120), entry(minutes(30), 120),
            entry(minutes(40), 120), entry(minutes(50), 250), entry(minutes(60), 120),
        )

        val result = TodaySoFarBuilder.build(entries, 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertTrue(result.hasEnoughData)
        assertEquals(6, result.readingCount)
        assertEquals(100.0, result.inRange + result.above + result.below, 0.0001)
        assertEquals(1, result.lowEpisodes)
    }

    @Test
    fun `readings from tomorrow or yesterday are not part of today`() {
        val entries = listOf(
            entry(-minutes(30), 300),              // yesterday
            entry(minutes(10), 120), entry(minutes(20), 120), entry(minutes(30), 120),
            entry(minutes(40), 120), entry(minutes(50), 120), entry(minutes(60), 120),
            entry(minutes(60 * 20), 400),          // later today, after `now`
        )

        val result = TodaySoFarBuilder.build(entries, 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertEquals(6, result.readingCount)
        assertEquals(120.0, result.averageGlucose!!, 0.0001)
    }

    @Test
    fun `one dip is one episode however many readings it spans`() {
        // Six consecutive lows five minutes apart are a single excursion.
        val entries = (1..6).map { entry(minutes(it * 5L), 55) }

        val result = TodaySoFarBuilder.build(entries, 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertEquals(1, result.lowEpisodes)
    }

    @Test
    fun `separate dips are separate episodes`() {
        val entries = listOf(
            entry(minutes(10), 55), entry(minutes(15), 55),
            entry(minutes(120), 55),                       // well clear of the first dip
            entry(minutes(200), 120), entry(minutes(210), 120), entry(minutes(220), 120),
        )

        val result = TodaySoFarBuilder.build(entries, 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertEquals(2, result.lowEpisodes)
    }

    @Test
    fun `with no completed days behind it there is no baseline`() {
        val entries = (1..6).map { entry(minutes(it * 10L), 120) }

        val result = TodaySoFarBuilder.build(entries, 70.0, 180.0, nowMillis = now, timeZone = utc)

        assertNull(result.baselineAverage)
        assertFalse(result.hasBaseline)
        assertNull(result.averageDelta)
    }

    @Test
    fun `the baseline uses the same clock hours on completed days`() {
        val today = (1..6).map { entry(minutes(it * 10L), 150) }
        // Two prior days, same early-morning slice, running lower than today.
        val history = (1..2).flatMap { dayBack ->
            (1..6).map { i ->
                NightscoutGlucoseEntry(
                    sgv = 100,
                    date = dayStart - dayBack * 86_400_000L + minutes(i * 10L),
                )
            }
        }

        val result = TodaySoFarBuilder.build(
            today + history, 70.0, 180.0, nowMillis = now, timeZone = utc,
        )

        assertEquals(2, result.baselineDays)
        assertEquals(100.0, result.baselineAverage!!, 0.0001)
        assertTrue(result.hasBaseline)
        assertEquals(50.0, result.averageDelta!!, 0.0001)
    }

    @Test
    fun `a day too thin to represent itself is not counted as a baseline day`() {
        val today = (1..6).map { entry(minutes(it * 10L), 150) }
        val thinDay = (1..2).map { i ->
            NightscoutGlucoseEntry(sgv = 100, date = dayStart - 86_400_000L + minutes(i * 10L))
        }

        val result = TodaySoFarBuilder.build(
            today + thinDay, 70.0, 180.0, nowMillis = now, timeZone = utc,
        )

        assertEquals(0, result.baselineDays)
        assertNull(result.baselineAverage)
    }
}
