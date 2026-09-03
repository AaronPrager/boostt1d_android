package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.engine.WhatHappenedDailyOverviewBuilder
import com.boostt1d.android.engine.WhatHappenedPatternDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pins the two ways a seven-day report was making claims a seven-day window cannot support —
 * counting eight days, and describing one Friday as "Fridays" — plus meal-window ranking.
 *
 * Ported from the iOS PatternClaimIntegrityTests, MealWindowPatternTests and
 * WhatHappenedDurationLabelTests.
 */
class WhatHappenedPatternDetectorTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val low = 70.0
    private val high = 180.0

    /**
     * Mid-afternoon on purpose. A rolling `now − 7 days` window only spans eight calendar days
     * when `now` is not midnight, which is exactly why this went unnoticed.
     */
    private val now: Long = at(2025, Calendar.JUNE, 16, 15, 30)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month, day, hour, minute, 0) }.timeInMillis

    private fun weekday(millis: Long): Int =
        Calendar.getInstance(zone).apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)

    private fun daysBetween(from: Long, to: Long): Int {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = from }
        var days = 0
        while (cal.timeInMillis < to) { cal.add(Calendar.DAY_OF_MONTH, 1); days++ }
        return days
    }

    // MARK: - Denominators

    @Test
    fun `a seven-day window counts seven days, not eight`() {
        val patterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = entries(days = 10), lowMgdL = low, highMgdL = high,
            nowMillis = now, timeZone = zone, periodDays = 7, limit = 5,
        )

        assertTrue(patterns.isNotEmpty())
        for (pattern in patterns) {
            assertTrue(pattern.opportunityCount <= 7)
            assertTrue(pattern.occurrenceCount <= pattern.opportunityCount)
        }
    }

    /**
     * The visible symptom: two patterns on one page quoting different denominators for the
     * same week. Any pattern counting whole days must agree with any other.
     */
    @Test
    fun `day-counting patterns agree on how many days the week had`() {
        val patterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = entries(days = 10), lowMgdL = low, highMgdL = high,
            nowMillis = now, timeZone = zone, periodDays = 7, limit = 5,
        )

        assertTrue(patterns.map { it.opportunityCount }.all { it <= 7 })
    }

    /**
     * A day with a couple of stray readings is not a day the pattern had a chance to happen
     * on, and counting it silently deflates every frequency on the page.
     */
    @Test
    fun `a day with almost no readings is not counted as an opportunity`() {
        val yesterday = TodaySoFarBuilder.startOfDay(now, zone).let {
            Calendar.getInstance(zone).apply { timeInMillis = it; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        }
        val dayAfter = Calendar.getInstance(zone).apply { timeInMillis = yesterday; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

        val full = entries(days = 10)
        val inYesterday = full.filter { it.epochMilliseconds >= yesterday && it.epochMilliseconds < dayAfter }
        // Strip yesterday down to four readings.
        val sparse = full.filterNot { it.epochMilliseconds >= yesterday && it.epochMilliseconds < dayAfter } + inYesterday.take(4)

        val patterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = sparse, lowMgdL = low, highMgdL = high,
            nowMillis = now, timeZone = zone, periodDays = 7, limit = 5,
        )

        for (pattern in patterns) assertTrue(pattern.opportunityCount <= 6)
    }

    /**
     * The complaint that found this: opened on a Monday, a seven-day report showed six days,
     * Tuesday to Sunday. Today was in the window but partial, so it counted as a day when it
     * had data and vanished when it did not.
     */
    @Test
    fun `a seven-day window is seven whole days ending last midnight`() {
        val monday = at(2025, Calendar.JUNE, 16, 15, 30)
        assertEquals(Calendar.MONDAY, weekday(monday))

        val window = WhatHappenedPatternDetector.analysisWindow(7, monday, zone)

        // Ends at this morning's midnight, so today is excluded entirely.
        assertEquals(TodaySoFarBuilder.startOfDay(monday, zone), window.endMillis)
        // Seven whole days: the previous Monday through Sunday.
        assertEquals(7, daysBetween(window.startMillis, window.endMillis))
        assertEquals(Calendar.MONDAY, weekday(window.startMillis))
    }

    /** The denominator must not depend on when the user happens to open the app. */
    @Test
    fun `the window does not change through the day`() {
        val earlyMorning = at(2025, Calendar.JUNE, 16, 6, 5)
        val lateEvening = at(2025, Calendar.JUNE, 16, 23, 45)

        val morning = WhatHappenedPatternDetector.analysisWindow(7, earlyMorning, zone)
        val evening = WhatHappenedPatternDetector.analysisWindow(7, lateEvening, zone)

        assertEquals(morning.startMillis, evening.startMillis)
        assertEquals(morning.endMillis, evening.endMillis)

        // And the day count the user sees is the same at both times.
        val data = entries(days = 12)
        val morningPatterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = data, lowMgdL = low, highMgdL = high, nowMillis = earlyMorning, timeZone = zone, periodDays = 7, limit = 5,
        )
        val eveningPatterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = data, lowMgdL = low, highMgdL = high, nowMillis = lateEvening, timeZone = zone, periodDays = 7, limit = 5,
        )
        assertEquals(morningPatterns.map { it.frequencyLabel }, eveningPatterns.map { it.frequencyLabel })
    }

    // MARK: - Meal windows

    @Test
    fun `lunch highs are detected alongside dinner highs`() {
        val patterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = mealHighEntries(lunch = 240, dinner = 200), lowMgdL = low, highMgdL = high,
            nowMillis = now, timeZone = zone, periodDays = 7, limit = 5,
        )

        val titles = patterns.map { it.title }
        assertTrue(titles.contains("Repeated lunch highs"))
        assertTrue(titles.contains("Repeated dinner highs"))
    }

    @Test
    fun `larger lunch highs outrank milder dinner highs at the same frequency`() {
        val patterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = mealHighEntries(lunch = 260, dinner = 195), lowMgdL = low, highMgdL = high,
            nowMillis = now, timeZone = zone, periodDays = 7, limit = 2,
        )

        assertEquals("Repeated lunch highs", patterns.first().title)
        assertTrue(patterns.map { it.title }.contains("Repeated dinner highs"))
    }

    // MARK: - Duration labels

    @Test
    fun `under an hour stays in minutes`() {
        assertEquals("0 min", WhatHappenedDailyOverviewBuilder.durationLabel(0))
        assertEquals("45 min", WhatHappenedDailyOverviewBuilder.durationLabel(45))
        assertEquals("59 min", WhatHappenedDailyOverviewBuilder.durationLabel(59))
    }

    @Test
    fun `an hour or more reads in hours and minutes`() {
        assertEquals("1h", WhatHappenedDailyOverviewBuilder.durationLabel(60))
        assertEquals("1h 58m", WhatHappenedDailyOverviewBuilder.durationLabel(118))
        assertEquals("6h 49m", WhatHappenedDailyOverviewBuilder.durationLabel(409))
        assertEquals("23h 59m", WhatHappenedDailyOverviewBuilder.durationLabel(1439))
    }

    @Test
    fun `a day or more reads in days`() {
        assertEquals("1 day", WhatHappenedDailyOverviewBuilder.durationLabel(1440))
        assertEquals("1 day 1h", WhatHappenedDailyOverviewBuilder.durationLabel(1500))
        assertEquals("2 days", WhatHappenedDailyOverviewBuilder.durationLabel(2880))
        // The one that started this: an indefinite override.
        assertEquals("30 days", WhatHappenedDailyOverviewBuilder.durationLabel(43200))
    }

    // MARK: - Fixtures

    /**
     * Whole days of five-minute readings ending at `now`, with an overnight rise so the
     * detector has something to find.
     */
    private fun entries(days: Int): List<NightscoutGlucoseEntry> {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = TodaySoFarBuilder.startOfDay(now, zone)
        cal.add(Calendar.DAY_OF_MONTH, -(days - 1))
        val result = mutableListOf<NightscoutGlucoseEntry>()
        var at = cal.timeInMillis
        val hourCal = Calendar.getInstance(zone)
        while (at <= now) {
            hourCal.timeInMillis = at
            val hour = hourCal.get(Calendar.HOUR_OF_DAY).toDouble()
            val value = if (hour < 6) 130 + 90 * hour / 6 else 135.0
            result += NightscoutGlucoseEntry(sgv = value.toInt(), direction = null, date = at, device = "test")
            at += 300_000
        }
        return result
    }

    /** Seven complete analysis days with elevated lunch and dinner averages every day. */
    private fun mealHighEntries(lunch: Int, dinner: Int): List<NightscoutGlucoseEntry> {
        val window = WhatHappenedPatternDetector.analysisWindow(7, now, zone)
        val result = mutableListOf<NightscoutGlucoseEntry>()
        val cal = Calendar.getInstance(zone)
        var day = window.startMillis
        while (day < window.endMillis) {
            for (hour in 0 until 24) {
                for (minute in 0 until 60 step 10) {
                    cal.timeInMillis = day
                    cal.add(Calendar.MINUTE, hour * 60 + minute)
                    val value = when (hour) {
                        in 11 until 16 -> lunch
                        in 17 until 22 -> dinner
                        else -> 120
                    }
                    result += NightscoutGlucoseEntry(sgv = value, direction = null, date = cal.timeInMillis, device = "test")
                }
            }
            cal.timeInMillis = day
            cal.add(Calendar.DAY_OF_MONTH, 1)
            day = cal.timeInMillis
        }
        return result
    }
}
