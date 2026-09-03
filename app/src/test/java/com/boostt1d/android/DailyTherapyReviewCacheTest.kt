package com.boostt1d.android

import com.boostt1d.android.engine.AIDailyTherapyReview
import com.boostt1d.android.engine.DailyTherapyReviewCache
import com.boostt1d.android.engine.InMemoryDailyTherapyReviewStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Ported from the iOS DailyTherapyReviewCacheTests. */
class DailyTherapyReviewCacheTest {

    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(newYork).apply { clear(); set(year, month, day, hour, minute, 0) }.timeInMillis

    private fun plus(millis: Long, field: Int, amount: Int): Long =
        Calendar.getInstance(newYork).apply { timeInMillis = millis; add(field, amount) }.timeInMillis

    @Test
    fun `a daily therapy AI attempt can be spent only once per calendar day`() {
        val cache = DailyTherapyReviewCache(InMemoryDailyTherapyReviewStore(), newYork)
        val morning = at(2026, Calendar.AUGUST, 3, 8)
        val evening = plus(morning, Calendar.HOUR_OF_DAY, 12)
        val tomorrow = plus(morning, Calendar.DAY_OF_MONTH, 1)

        assertTrue(cache.beginAttempt(morning))
        assertFalse(cache.beginAttempt(evening))
        assertTrue(cache.hasAttemptedToday(evening))
        assertTrue(cache.beginAttempt(tomorrow))
    }

    @Test
    fun `a successful review is reusable today but not presented as today's review tomorrow`() {
        val cache = DailyTherapyReviewCache(InMemoryDailyTherapyReviewStore(), newYork)
        val analyzedAt = at(2026, Calendar.AUGUST, 3, 23, 50)
        val response = AIDailyTherapyReview("Seven-day overview", emptyList(), emptyList(), emptyList(), emptyList())

        cache.store(response, analyzedAtMillis = analyzedAt, periodStartMillis = analyzedAt - 7 * 86_400_000L, periodEndMillis = analyzedAt, reuseKey = "week-2026-08-03")

        assertEquals("Seven-day overview", cache.resultForToday(analyzedAt)?.response?.overview)
        val tomorrow = plus(analyzedAt, Calendar.MINUTE, 20)
        assertNull(cache.resultForToday(tomorrow))
        assertEquals("week-2026-08-03", cache.latestResult()?.reuseKey)
    }

    @Test
    fun `recording a failed attempt cannot destroy the last good response`() {
        val store = InMemoryDailyTherapyReviewStore()
        val cache = DailyTherapyReviewCache(store, newYork)
        val yesterday = at(2026, Calendar.AUGUST, 2, 9)
        cache.store(AIDailyTherapyReview("Kept", emptyList(), emptyList(), emptyList(), emptyList()), yesterday, yesterday - 1, yesterday, "k")

        assertTrue(cache.beginAttempt(at(2026, Calendar.AUGUST, 3, 9)))
        assertEquals("Kept", cache.latestResult()?.response?.overview)

        cache.clear()
        assertNull(cache.latestResult())
        assertNull(store.lastAttemptMillis)
    }
}
