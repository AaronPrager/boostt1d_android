package com.boostt1d.android.data

import java.util.Calendar
import java.util.TimeZone

/** The two facts the daily counter keeps. */
interface UsageStore {
    var usedCount: Int
    var lastResetMillis: Long?
}

class InMemoryUsageStore : UsageStore {
    override var usedCount: Int = 0
    override var lastResetMillis: Long? = null
}

/**
 * Free food estimations per day, reset at local midnight.
 *
 * Counted on the device, as on iOS. The limit is the build-time setting rather than a
 * hardcoded number, so the limit and the message the user reads can never drift apart.
 */
class UsageTracker(
    private val store: UsageStore,
    private val limit: Int = Config.FOOD_ANALYSIS_DAILY_LIMIT,
    private val limitEnabled: Boolean = Config.LIMIT_FOOD_ANALYSIS,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private fun resetIfNewDay(nowMillis: Long) {
        val last = store.lastResetMillis
        if (last == null || TodaySoFarBuilder.startOfDay(last, timeZone) != TodaySoFarBuilder.startOfDay(nowMillis, timeZone)) {
            store.usedCount = 0
            store.lastResetMillis = nowMillis
        }
    }

    fun remaining(nowMillis: Long): Int {
        if (!limitEnabled) return Int.MAX_VALUE
        resetIfNewDay(nowMillis)
        return (limit - store.usedCount).coerceAtLeast(0)
    }

    fun hasRemaining(nowMillis: Long): Boolean = !limitEnabled || remaining(nowMillis) > 0

    fun use(nowMillis: Long) {
        if (!limitEnabled) return
        resetIfNewDay(nowMillis)
        store.usedCount = (store.usedCount + 1).coerceAtMost(limit)
    }

    fun used(nowMillis: Long): Int {
        resetIfNewDay(nowMillis)
        return store.usedCount
    }

    /** Next local midnight. */
    fun nextResetMillis(nowMillis: Long): Long =
        Calendar.getInstance(timeZone).apply { timeInMillis = TodaySoFarBuilder.startOfDay(nowMillis, timeZone); add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    fun reset() {
        store.usedCount = 0
        store.lastResetMillis = null
    }
}
