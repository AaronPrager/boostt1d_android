package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.engine.AgpProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AgpProfileTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long = Calendar.getInstance(zone).apply { clear(); set(y, m, d, h, min, 0) }.timeInMillis

    @Test
    fun `percentiles interpolate linearly between ranks`() {
        val sorted = listOf(100.0, 110.0, 120.0, 130.0, 140.0)
        assertEquals(120.0, AgpProfile.percentile(sorted, 0.5), 0.0)
        assertEquals(110.0, AgpProfile.percentile(sorted, 0.25), 0.0)
        assertEquals(130.0, AgpProfile.percentile(sorted, 0.75), 0.0)
        assertEquals(115.0, AgpProfile.percentile(listOf(100.0, 110.0, 120.0, 130.0), 0.5), 0.0)
        assertEquals(7.0, AgpProfile.percentile(listOf(7.0), 0.9), 0.0)
        assertEquals(0.0, AgpProfile.percentile(emptyList(), 0.5), 0.0)
    }

    @Test
    fun `readings collapse onto ten-minute clock buckets, one sample per day`() {
        // Three days, each with a reading at 08:03 and a second one at 08:07 in the same bucket.
        val entries = (1..3).flatMap { day ->
            listOf(
                NightscoutGlucoseEntry(sgv = 100 * day, date = at(2025, Calendar.JUNE, day, 8, 3), device = "t"),
                NightscoutGlucoseEntry(sgv = 100 * day + 5, date = at(2025, Calendar.JUNE, day, 8, 7), device = "t"),
                NightscoutGlucoseEntry(sgv = 90, date = at(2025, Calendar.JUNE, day, 23, 55), device = "t"),
            )
        }
        val points = AgpProfile.points(entries, zone)

        assertEquals(listOf(8 * 60, 23 * 60 + 50), points.map { it.minuteOfDay })
        val eight = points.first()
        // The later reading in the bucket wins: 105, 205, 305.
        assertEquals(205.0, eight.median, 0.0)
        assertEquals(155.0, eight.p25, 0.0)
        assertEquals(255.0, eight.p75, 0.0)
        assertEquals(3, AgpProfile.distinctCalendarDays(entries, zone))

        // Limiting to two days drops the third day's sample.
        val twoDays = AgpProfile.points(entries, zone, allowedDayStarts = setOf(at(2025, Calendar.JUNE, 1, 0, 0), at(2025, Calendar.JUNE, 2, 0, 0)))
        assertEquals(155.0, twoDays.first().median, 0.0)
        assertEquals(7, AgpProfile.dayStarts(7, at(2025, Calendar.JUNE, 16, 12, 0), zone).size)
    }
}
