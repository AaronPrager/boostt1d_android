package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * Where the daily AI review, once it exists, keeps its wording. An interface so the engine
 * holds no Android dependency; the app backs it with DataStore, tests with a map.
 */
interface PatternReviewStore {
    fun load(periodDays: Int): StoredPatternReview?
    fun save(review: StoredPatternReview, periodDays: Int)
    fun clear(periodDays: Int)
}

class InMemoryPatternReviewStore : PatternReviewStore {
    private val reviews = mutableMapOf<Int, StoredPatternReview>()
    override fun load(periodDays: Int) = reviews[periodDays]
    override fun save(review: StoredPatternReview, periodDays: Int) { reviews[periodDays] = review }
    override fun clear(periodDays: Int) { reviews.remove(periodDays) }
}

@Serializable
data class StoredProse(
    val observation: String? = null,
    val contributingFactors: List<String>? = null,
    val discussQuestions: List<String>? = null,
)

@Serializable
data class StoredProposal(
    val title: String,
    val observation: String,
    val occurrenceCount: Int,
    val priority: String,
    val contributingFactors: List<String>,
    val discussQuestions: List<String>,
)

/** One day's AI review of the patterns, persisted so a failed call cannot erase good wording. */
@Serializable
data class StoredPatternReview(
    val reviewedAtMillis: Long,
    /**
     * Prose keyed by the detector's pattern title — never by position, which shifts as the
     * ranking changes through the day.
     */
    val proseByTitle: Map<String, StoredProse>,
    /** Patterns AI proposed itself, which the detector cannot re-derive. */
    val proposals: List<StoredProposal>,
    /**
     * Every formula title AI has been shown today. A detector title missing from this set
     * means the picture moved and another review is justified.
     */
    val reviewedTitles: List<String>,
    /** Reviews spent today. */
    val reviewCount: Int,
)

/**
 * The one place patterns are produced for every screen.
 *
 * Caching matters for more than speed: it is what guarantees the doctor PDF and the on-screen
 * list show identical findings. Every number comes from the detector and cannot change until
 * the window rolls over at midnight, so a cached result is served until then.
 *
 * The AI wording path is present in shape and absent in behaviour, exactly as on iOS: the
 * comprehensive daily therapy review is the single daily AI pass, and pattern cards keep the
 * detector's own deterministic wording rather than spending a second request on a settled
 * week. The merge that would apply AI wording arrives with the rest of the AI path in phase 4.
 *
 * Ported from the iOS PatternService.
 */
class PatternService(
    private val reviewStore: PatternReviewStore = InMemoryPatternReviewStore(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    /** Patterns plus provenance and freshness, enough for a caller to label them honestly. */
    data class Result(
        val patterns: List<WhatHappenedPattern>,
        /** True when AI wording is present, whether reviewed just now or earlier today. */
        val usedAI: Boolean,
        /** Why AI was skipped or failed, when it was. Null on success or when AI is disabled. */
        val aiFallbackReason: String?,
        /** When the AI review backing this wording was produced. Null when there is none. */
        val reviewedAtMillis: Long?,
        /**
         * When these numbers were computed. They cannot change until the window rolls over at
         * midnight, so a cached result keeps the time it was first produced.
         */
        val dataThroughMillis: Long?,
        /** Window the patterns describe. */
        val periodDays: Int,
    ) {
        val coverageLabel: String? get() = coverageLabel(dataThroughMillis)

        companion object {
            val empty = Result(emptyList(), false, null, null, null, 7)
        }
    }

    /**
     * Identifies the *analysed* data, not the data the caller happened to hand over.
     *
     * Patterns cover complete days ending last midnight, so the answer cannot change until the
     * window rolls over — but keying on every entry the caller held, today's included, meant a
     * new reading every five minutes produced a new key and a full recompute of a result that
     * was already correct. Keyed on the window's own contents, a hit lasts until midnight, or
     * until data inside the window is backfilled — which is exactly when the answer changes.
     */
    private data class CacheKey(
        val formulaSchemaVersion: String,
        val periodDays: Int,
        /** The depth patterns were *detected* at, not the depth the caller asked for. */
        val detectionLimit: Int,
        /** Pins the key to one calendar window, so it expires on its own at midnight. */
        val windowStartMillis: Long,
        val entryCount: Int,
        val treatmentCount: Int,
        /** Newest reading *inside the window*. Moves only on backfill. */
        val latestWindowReadingMillis: Long,
        /** Digest of the meal and therapy context. Richer context must not be served a poorer answer. */
        val contextDigest: Int,
    )

    private val cache = mutableMapOf<CacheKey, Result>()

    /**
     * Returns patterns for the window, reusing a cached result when the inputs are unchanged.
     *
     * Detects at the shared review depth so every caller reads the same reviewed set — a
     * screen showing 3 and a screen showing 5 would otherwise describe the same pattern two
     * ways, which is the divergence this service exists to prevent — then hands back only as
     * many as this caller wants.
     */
    fun patterns(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        periodDays: Int,
        limit: Int = 5,
        mealContext: String = "",
        hasTherapyProfile: Boolean = false,
        nowMillis: Long,
    ): Result {
        val detectionLimit = max(limit, REVIEW_LIMIT)

        // The detector windows its own inputs; mirror that so the key describes exactly what
        // will be analysed.
        val window = WhatHappenedPatternDetector.analysisWindow(periodDays, nowMillis, timeZone)
        val windowEntries = entries.filter { it.epochMilliseconds >= window.startMillis && it.epochMilliseconds < window.endMillis }
        val windowTreatments = treatments.filter {
            val at = MealOutcomeBuilder.treatmentMillis(it)
            at >= window.startMillis && at < window.endMillis
        }

        val key = CacheKey(
            formulaSchemaVersion = FORMULA_SCHEMA_VERSION,
            periodDays = periodDays,
            detectionLimit = detectionLimit,
            windowStartMillis = window.startMillis,
            entryCount = windowEntries.size,
            treatmentCount = windowTreatments.size,
            latestWindowReadingMillis = windowEntries.maxOfOrNull { it.epochMilliseconds } ?: 0L,
            contextDigest = contextDigest(mealContext, hasTherapyProfile),
        )
        cache[key]?.let { return limited(it, limit) }

        // Deterministic base. This always runs — it owns every number we display.
        val formulaPatterns = WhatHappenedPatternDetector.detectTopPatterns(
            entries = entries, treatments = treatments,
            lowMgdL = lowGlucose, highMgdL = highGlucose,
            nowMillis = nowMillis, timeZone = timeZone,
            periodDays = periodDays, limit = detectionLimit,
        )

        // When this result was produced. Not the newest reading: today is outside the window,
        // so the newest reading is last night's and would read as permanently stale.
        val result = Result(
            patterns = formulaPatterns,
            usedAI = false,
            aiFallbackReason = null,
            reviewedAtMillis = null,
            dataThroughMillis = nowMillis,
            periodDays = periodDays,
        )

        // AI wording is gated off, as on iOS. When phase 4 brings the reviewer, this is where
        // needsReview() decides whether to spend a call and the stored review is merged in.
        cache[key] = result
        return limited(result, limit)
    }

    fun invalidate() = cache.clear()

    /** Clears stored AI reviews as well. For sign-out or a deliberate reset, not for refresh. */
    fun invalidateAll() {
        cache.clear()
        KNOWN_PERIODS.forEach { reviewStore.clear(it) }
    }

    // MARK: - Daily review policy

    /**
     * True when AI should run now: no review yet today, or the detector has surfaced a pattern
     * this review has never seen.
     *
     * The second case is what keeps the wording honest. Freezing prose for a whole day is fine
     * while the picture is unchanged — a 7-day trend does not turn over between breakfast and
     * lunch. When something genuinely new appears, waiting until tomorrow to describe it is not.
     */
    internal fun needsReview(stored: StoredPatternReview?, formulaPatterns: List<WhatHappenedPattern>, nowMillis: Long): Boolean {
        if (stored == null) return true
        if (TodaySoFarBuilder.startOfDay(stored.reviewedAtMillis, timeZone) != TodaySoFarBuilder.startOfDay(nowMillis, timeZone)) return true
        if (stored.reviewCount >= MAX_REVIEWS_PER_DAY) return false
        val seen = stored.reviewedTitles.toSet()
        return formulaPatterns.any { it.title !in seen }
    }

    /** Average glucose per complete day in the window — the detector's window, not a second definition of it. */
    fun dailyAverages(entries: List<NightscoutGlucoseEntry>, periodDays: Int, nowMillis: Long): List<WhatHappenedPatternChartPoint> {
        val window = WhatHappenedPatternDetector.analysisWindow(periodDays, nowMillis, timeZone)
        val windowed = entries.filter { it.epochMilliseconds >= window.startMillis && it.epochMilliseconds < window.endMillis }
        if (windowed.isEmpty()) return emptyList()

        val grouped = windowed
            .groupBy { TodaySoFarBuilder.startOfDay(it.epochMilliseconds, timeZone) }
            .filter { it.value.size >= WhatHappenedPatternDetector.MIN_READINGS_PER_DAY }
        val formatter = SimpleDateFormat("E d", Locale.getDefault()).apply { this.timeZone = this@PatternService.timeZone }

        return grouped.keys.sorted().map { day ->
            val values = grouped.getValue(day).map { it.sgv.toDouble() }
            WhatHappenedPatternChartPoint(label = formatter.format(Date(day)), value = values.sum() / values.size, highlighted = false)
        }
    }

    companion object {
        /**
         * Patterns are reviewed at this depth regardless of how many the caller wants, so a
         * screen showing 3 and a screen showing 5 share one review and one wording.
         */
        private const val REVIEW_LIMIT = 5

        /**
         * What Happened's comprehensive therapy review is the single daily AI pass. Pattern cards
         * keep deterministic detector wording instead of spending a second request on the same
         * settled week. False on iOS as well.
         */
        const val AI_PATTERN_WORDING_ENABLED = false

        /** Ceiling on AI reviews per period per day; stops unstable titles from looping. */
        private const val MAX_REVIEWS_PER_DAY = 4

        /**
         * Bump when the detector's candidate set or scoring changes, so a cached result cannot
         * keep serving a pre-change top list for an unchanged week.
         */
        private const val FORMULA_SCHEMA_VERSION = "meal-lunch-v1"

        /** Periods the app can ask for. Used only to clear storage; unknown periods still work. */
        private val KNOWN_PERIODS = listOf(3, 7, 14, 30, 90)

        /**
         * Days a window needs before any weekday can occur twice.
         *
         * Below this, no weekday claim can be true — not "on Fridays", not "at weekends", not
         * "some days". The window contains one of each.
         */
        const val MIN_PERIOD_DAYS_FOR_WEEKDAY_CLAIMS = 14

        private fun limited(result: Result, limit: Int): Result =
            if (result.patterns.size <= limit) result else result.copy(patterns = result.patterns.take(limit))

        /** Stable within a run; the cache it keys is in-memory only. */
        private fun contextDigest(mealContext: String, hasTherapyProfile: Boolean): Int =
            mealContext.hashCode() * 31 + hasTherapyProfile.hashCode()

        fun priorityFrom(raw: String?): Priority = when (raw?.lowercase(Locale.US)) {
            "high" -> Priority.HIGH
            "medium" -> Priority.MEDIUM
            else -> Priority.LOW
        }

        internal fun cleaned(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

        internal fun cleanedList(items: List<String>?): List<String>? =
            items?.mapNotNull(::cleaned)?.takeIf { it.isNotEmpty() }

        // MARK: - Coverage wording

        /**
         * The one place the "what this covers" sentence is written. Every screen showing
         * patterns reads from here: two screens describing the same week in two different
         * sentences is how a user stops trusting either.
         *
         * Only how fresh the numbers are. Null when there are no readings, so the label
         * renders nothing rather than an empty prefix.
         */
        fun coverageLabel(dataThroughMillis: Long?): String? {
            val at = dataThroughMillis ?: return null
            return "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(at))}"
        }

        // MARK: - Weekday claims

        private val weekdayTerms: Set<String> by lazy {
            val symbols = DateFormatSymbols.getInstance(Locale.getDefault())
            val names = (symbols.weekdays.toList() + symbols.shortWeekdays.toList())
                .filter { it.isNotEmpty() }
                .map { it.lowercase(Locale.getDefault()) }
            (names + names.map { it + "s" } + listOf("weekend", "weekends", "weekday", "weekdays", "midweek")).toSet()
        }

        private val wordSplit = Regex("[^\\p{L}\\p{N}]+")

        /**
         * True when the text asserts something about a day of the week.
         *
         * Matched on whole words so "Sunday" is caught but a food called "sundae", or a weekday
         * name inside a longer word, does not produce a false hit on a substring.
         */
        fun makesWeekdayClaim(text: String): Boolean {
            val words = text.lowercase(Locale.getDefault()).split(wordSplit).filter { it.isNotEmpty() }.toSet()
            return weekdayTerms.any { it in words }
        }

        /**
         * Drops wording that claims a weekday pattern the window cannot support.
         *
         * The prompt already forbids this and the weekday table is withheld below the threshold,
         * but neither is a guarantee — a model can reconstruct weekdays from the dated meal rows
         * and often does. Dropping one field is invisible to the user; "some days, like Fridays"
         * from a single Friday is not.
         */
        internal fun withoutUnsupportedWeekdayClaims(text: String?, periodDays: Int): String? {
            val clean = cleaned(text) ?: return null
            if (periodDays >= MIN_PERIOD_DAYS_FOR_WEEKDAY_CLAIMS) return clean
            return if (makesWeekdayClaim(clean)) null else clean
        }

        internal fun withoutUnsupportedWeekdayClaims(items: List<String>?, periodDays: Int): List<String>? =
            items?.mapNotNull { withoutUnsupportedWeekdayClaims(it, periodDays) }?.takeIf { it.isNotEmpty() }
    }
}
