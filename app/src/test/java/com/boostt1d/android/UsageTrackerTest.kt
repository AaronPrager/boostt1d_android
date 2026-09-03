package com.boostt1d.android

import com.boostt1d.android.data.InMemoryUsageStore
import com.boostt1d.android.data.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UsageTrackerTest {

    private val zone: TimeZone = TimeZone.getTimeZone("America/New_York")
    private fun at(day: Int, hour: Int): Long = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.AUGUST, day, hour, 0, 0) }.timeInMillis

    @Test
    fun `six a day, counted from the first use, capped at the limit`() {
        val tracker = UsageTracker(InMemoryUsageStore(), limit = 6, limitEnabled = true, timeZone = zone)
        val morning = at(3, 8)

        assertEquals(6, tracker.remaining(morning))
        repeat(6) { tracker.use(morning) }
        assertEquals(0, tracker.remaining(morning))
        assertFalse(tracker.hasRemaining(morning))
        tracker.use(morning)
        assertEquals(6, tracker.used(morning))
    }

    @Test
    fun `the counter resets at local midnight, not twenty-four hours later`() {
        val tracker = UsageTracker(InMemoryUsageStore(), limit = 6, limitEnabled = true, timeZone = zone)
        tracker.use(at(3, 23))
        assertEquals(5, tracker.remaining(at(3, 23)))
        assertEquals(6, tracker.remaining(at(4, 0)))
        assertEquals(at(4, 0), tracker.nextResetMillis(at(3, 23)))
    }

    @Test
    fun `an unlimited build never counts`() {
        val store = InMemoryUsageStore()
        val tracker = UsageTracker(store, limit = 6, limitEnabled = false, timeZone = zone)
        tracker.use(at(3, 8))
        assertTrue(tracker.hasRemaining(at(3, 8)))
        assertEquals(Int.MAX_VALUE, tracker.remaining(at(3, 8)))
        assertEquals(0, store.usedCount)
    }
}
