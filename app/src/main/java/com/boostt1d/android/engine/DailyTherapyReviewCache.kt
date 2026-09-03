package com.boostt1d.android.engine

import com.boostt1d.android.data.TodaySoFarBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TimeZone

/** The two values the cache keeps. The app backs this with DataStore; tests with memory. */
interface DailyTherapyReviewStore {
    var lastAttemptMillis: Long?
    var latestResultJson: String?
}

class InMemoryDailyTherapyReviewStore : DailyTherapyReviewStore {
    override var lastAttemptMillis: Long? = null
    override var latestResultJson: String? = null
}

/**
 * Enforces one AI trip per calendar day for the seven-day therapy review, and keeps that day's
 * wording reusable for every reopen.
 *
 * An attempt is recorded before the network request. A failure therefore falls back to the
 * local formula for the rest of that day instead of retrying every time the screen opens. The
 * latest successful response is stored separately so recording a failed attempt cannot
 * destroy it.
 *
 * `reuseKey` deliberately identifies the *analysis week* — its start, the therapy profile and
 * the range settings — and nothing else. It used to hash every reading, treatment and food line
 * in the window, so one late CGM point or a Nightscout backfill discarded wording the user had
 * been reading all morning. The review only ever describes complete days ending last midnight,
 * so today's incoming data cannot change that story and must not delete it either.
 *
 * Ported from the iOS DailyTherapyReviewCache.
 */
class DailyTherapyReviewCache(
    private val store: DailyTherapyReviewStore = InMemoryDailyTherapyReviewStore(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    @Serializable
    data class StoredResult(
        val response: AIDailyTherapyReview,
        val analyzedAtMillis: Long,
        val periodStartMillis: Long,
        val periodEndMillis: Long,
        val reuseKey: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun hasAttemptedToday(nowMillis: Long): Boolean = synchronized(lock) { hasAttemptedTodayUnlocked(nowMillis) }

    /** Returns false when today's allowance has already been spent. */
    fun beginAttempt(nowMillis: Long): Boolean = synchronized(lock) {
        if (hasAttemptedTodayUnlocked(nowMillis)) return false
        store.lastAttemptMillis = nowMillis
        true
    }

    fun resultForToday(nowMillis: Long): StoredResult? {
        val result = latestResult() ?: return null
        return if (sameDay(result.analyzedAtMillis, nowMillis)) result else null
    }

    fun latestResult(): StoredResult? {
        val raw = store.latestResultJson ?: return null
        return runCatching { json.decodeFromString(StoredResult.serializer(), raw) }.getOrNull()
    }

    fun store(response: AIDailyTherapyReview, analyzedAtMillis: Long, periodStartMillis: Long, periodEndMillis: Long, reuseKey: String) {
        val result = StoredResult(response, analyzedAtMillis, periodStartMillis, periodEndMillis, reuseKey)
        store.latestResultJson = json.encodeToString(StoredResult.serializer(), result)
    }

    fun clear() {
        store.lastAttemptMillis = null
        store.latestResultJson = null
    }

    private fun hasAttemptedTodayUnlocked(nowMillis: Long): Boolean {
        val attempted = store.lastAttemptMillis ?: return false
        return sameDay(attempted, nowMillis)
    }

    private fun sameDay(a: Long, b: Long): Boolean =
        TodaySoFarBuilder.startOfDay(a, timeZone) == TodaySoFarBuilder.startOfDay(b, timeZone)
}
