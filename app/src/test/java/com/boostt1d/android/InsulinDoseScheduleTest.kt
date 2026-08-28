package com.boostt1d.android

import com.boostt1d.android.data.InsulinDoseSchedule
import com.boostt1d.android.data.TimeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the iOS InsulinDoseScheduleTests. */
class InsulinDoseScheduleTest {

    @Test
    fun `an empty schedule has no segments and no daily total`() {
        assertTrue(InsulinDoseSchedule.segments(emptyList()).isEmpty())
        assertNull(InsulinDoseSchedule.totalDailyBasal(emptyList()))
    }

    @Test
    fun `a single entry covers the whole day`() {
        val segments = InsulinDoseSchedule.segments(listOf(TimeValue("00:00", 0.8)))

        assertEquals(1, segments.size)
        assertEquals(InsulinDoseSchedule.MINUTES_PER_DAY, segments[0].minutes)
        assertEquals("All day", segments[0].rangeLabel)
    }

    @Test
    fun `the last segment wraps midnight so the day totals exactly 24 hours`() {
        val segments = InsulinDoseSchedule.segments(
            listOf(
                TimeValue("00:00", 0.8),
                TimeValue("06:00", 1.0),
                TimeValue("22:00", 0.7),
            )
        )

        assertEquals(3, segments.size)
        assertEquals(360, segments[0].minutes)   // 00:00-06:00
        assertEquals(960, segments[1].minutes)   // 06:00-22:00
        assertEquals(120, segments[2].minutes)   // 22:00-24:00
        assertEquals(
            InsulinDoseSchedule.MINUTES_PER_DAY,
            segments.sumOf { it.minutes },
        )
    }

    @Test
    fun `a schedule not starting at midnight still totals one day`() {
        val segments = InsulinDoseSchedule.segments(
            listOf(TimeValue("06:00", 1.0), TimeValue("22:00", 0.7))
        )

        assertEquals(
            InsulinDoseSchedule.MINUTES_PER_DAY,
            segments.sumOf { it.minutes },
        )
        // The wrap reads as the next day's start, not "30:00".
        assertEquals("06:00", segments[1].end)
    }

    @Test
    fun `entries are sorted into clock order`() {
        val segments = InsulinDoseSchedule.segments(
            listOf(TimeValue("22:00", 0.7), TimeValue("00:00", 0.8), TimeValue("06:00", 1.0))
        )

        assertEquals(listOf("00:00", "06:00", "22:00"), segments.map { it.start })
    }

    @Test
    fun `an unreadable time is dropped, not defaulted to midnight`() {
        val segments = InsulinDoseSchedule.segments(
            listOf(TimeValue("00:00", 0.8), TimeValue("not-a-time", 9.9))
        )

        assertEquals(1, segments.size)
        assertEquals(0.8, segments[0].value, 0.0)
    }

    @Test
    fun `daily basal totals the rate over each segment's span`() {
        // 0.8 for 6h + 1.0 for 16h + 0.7 for 2h = 4.8 + 16.0 + 1.4 = 22.2
        val total = InsulinDoseSchedule.totalDailyBasal(
            listOf(
                TimeValue("00:00", 0.8),
                TimeValue("06:00", 1.0),
                TimeValue("22:00", 0.7),
            )
        )

        assertEquals(22.2, total!!, 0.0001)
    }

    @Test
    fun `minutes parses HH mm and rejects nonsense`() {
        assertEquals(0, InsulinDoseSchedule.minutes("00:00"))
        assertEquals(390, InsulinDoseSchedule.minutes("06:30"))
        assertEquals(390, InsulinDoseSchedule.minutes("06:30:00"))
        assertNull(InsulinDoseSchedule.minutes("6"))
        assertNull(InsulinDoseSchedule.minutes("25:00"))
        assertNull(InsulinDoseSchedule.minutes("06:99"))
        assertNull(InsulinDoseSchedule.minutes(""))
    }
}
