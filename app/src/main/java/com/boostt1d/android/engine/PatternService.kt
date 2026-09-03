package com.boostt1d.android.engine

import com.boostt1d.android.data.Config
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
 * The AI wording path is complete but gated off, exactly as on iOS: the comprehensive daily
 * therapy review is the single daily AI pass, and pattern cards keep the detector's own
 * deterministic wording rather than spending a second request on a settled week.
 *
 * Ported from the iOS PatternService.
 */
/** The once-daily AI pass over the detected patterns. Absent until the backend client is wired. */
interface PatternReviewer {
    suspend fun reviewPatterns(
        formulaPatterns: List<WhatHappenedPattern>,
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        timeRangeDays: Int,
        mealContext: String,
    ): AIPatternResponse
}

class PatternService(
    private val reviewStore: PatternReviewStore = InMemoryPatternReviewStore(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val reviewer: PatternReviewer? = null,
    private val aiEnabled: Boolean = Config.AI_INSIGHTS_ENABLED,
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
    suspend fun patterns(
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
        val dataThrough = nowMillis
        fun finish(result: Result): Result {
            cache[key] = result
            return limited(result, limit)
        }

        val activeReviewer = reviewer
        if (activeReviewer == null || !aiEnabled || !AI_PATTERN_WORDING_ENABLED) {
            return finish(Result(formulaPatterns, usedAI = false, aiFallbackReason = null, reviewedAtMillis = null, dataThroughMillis = dataThrough, periodDays = periodDays))
        }

        val stored = reviewStore.load(periodDays)

        if (needsReview(stored, formulaPatterns, nowMillis)) {
            try {
                val review = activeReviewer.reviewPatterns(formulaPatterns, entries, treatments, lowGlucose, highGlucose, periodDays, mealContext)
                val merged = merge(formulaPatterns, review, entries, periodDays, nowMillis)
                reviewStore.save(record(merged, formulaPatterns, nowMillis, stored), periodDays)
                return finish(Result(merged, usedAI = true, aiFallbackReason = null, reviewedAtMillis = nowMillis, dataThroughMillis = dataThrough, periodDays = periodDays))
            } catch (error: Exception) {
                // AI is best-effort, and a failed attempt does not consume the day's review —
                // the next open tries again. A report is never empty because of an API error.
                // Earlier wording, if we have any from today, is still better than none.
                if (stored != null && sameDay(stored.reviewedAtMillis, nowMillis)) {
                    return finish(Result(apply(stored, formulaPatterns, entries, periodDays, nowMillis), usedAI = true, aiFallbackReason = error.message, reviewedAtMillis = stored.reviewedAtMillis, dataThroughMillis = dataThrough, periodDays = periodDays))
                }
                return finish(Result(formulaPatterns, usedAI = false, aiFallbackReason = error.message, reviewedAtMillis = null, dataThroughMillis = dataThrough, periodDays = periodDays))
            }
        }

        // Today's review already happened. Fresh numbers, this morning's wording.
        if (stored == null) {
            return finish(Result(formulaPatterns, usedAI = false, aiFallbackReason = null, reviewedAtMillis = null, dataThroughMillis = dataThrough, periodDays = periodDays))
        }
        return finish(Result(apply(stored, formulaPatterns, entries, periodDays, nowMillis), usedAI = true, aiFallbackReason = null, reviewedAtMillis = stored.reviewedAtMillis, dataThroughMillis = dataThrough, periodDays = periodDays))
    }

    private fun sameDay(a: Long, b: Long) = TodaySoFarBuilder.startOfDay(a, timeZone) == TodaySoFarBuilder.startOfDay(b, timeZone)

    // MARK: - Storing and reapplying wording

    /** What today's review said, keyed so tomorrow's detector output can pick it up by title. */
    internal fun record(merged: List<WhatHappenedPattern>, formulaPatterns: List<WhatHappenedPattern>, reviewedAtMillis: Long, previous: StoredPatternReview?): StoredPatternReview {
        val proseByTitle = mutableMapOf<String, StoredProse>()
        val proposals = mutableListOf<StoredProposal>()
        for (pattern in merged) {
            when (pattern.source) {
                PatternSource.AI_ENRICHED -> proseByTitle[pattern.title] = StoredProse(pattern.observation, pattern.contributingFactors, pattern.discussQuestions)
                PatternSource.AI -> proposals += StoredProposal(pattern.title, pattern.observation, pattern.occurrenceCount, pattern.priority.label, pattern.contributingFactors, pattern.discussQuestions)
                PatternSource.FORMULA -> {}
            }
        }
        // Carry forward titles seen earlier today so a re-run triggered by one new pattern
        // doesn't make every other pattern look new on the next open.
        val carried = previous?.takeIf { sameDay(it.reviewedAtMillis, reviewedAtMillis) }
        val titles = (carried?.reviewedTitles ?: emptyList()).toMutableSet()
        titles += formulaPatterns.map { it.title }
        return StoredPatternReview(reviewedAtMillis, proseByTitle, proposals, titles.toList(), (carried?.reviewCount ?: 0) + 1)
    }

    /**
     * Re-applies stored wording to freshly detected patterns, matching on title. A pattern
     * that faded during the day drops out and its wording goes with it; one that emerged since
     * keeps formula wording until the next review. Losing prose is the safe failure here.
     */
    internal fun apply(stored: StoredPatternReview, formulaPatterns: List<WhatHappenedPattern>, entries: List<NightscoutGlucoseEntry>, periodDays: Int, nowMillis: Long): List<WhatHappenedPattern> {
        val patterns = formulaPatterns.map { pattern ->
            val prose = stored.proseByTitle[pattern.title] ?: return@map pattern
            enriched(pattern, prose.observation, prose.contributingFactors, prose.discussQuestions, periodDays)
        }.toMutableList()
        // AI-proposed patterns have no detector counterpart, so they are rebuilt from the stored
        // claim — with the chart recomputed from today's data and the count re-clamped.
        val averages = dailyAverages(entries, periodDays, nowMillis)
        for (proposal in stored.proposals) {
            proposed(proposal.title, proposal.observation, proposal.occurrenceCount, proposal.priority, proposal.contributingFactors, proposal.discussQuestions, averages, periodDays)?.let { patterns += it }
        }
        return patterns
    }

    /**
     * Applies AI's wording to formula patterns and appends AI-proposed ones. Index-based, which
     * is correct here and only here: the response and the detector output it refers to are
     * both in hand. Numbers are never taken from AI.
     */
    internal fun merge(formulaPatterns: List<WhatHappenedPattern>, review: AIPatternResponse, entries: List<NightscoutGlucoseEntry>, periodDays: Int, nowMillis: Long): List<WhatHappenedPattern> {
        val patterns = formulaPatterns.toMutableList()
        for (enrichment in review.resolvedEnriched) {
            if (enrichment.index !in patterns.indices) continue
            patterns[enrichment.index] = enriched(patterns[enrichment.index], enrichment.observation, enrichment.contributingFactors, enrichment.discussQuestions, periodDays)
        }
        val averages = dailyAverages(entries, periodDays, nowMillis)
        for (proposal in review.resolvedAdditional) {
            proposed(proposal.title, proposal.observation, proposal.occurrenceCount ?: 0, proposal.priority, proposal.contributingFactors ?: emptyList(), proposal.discussQuestions ?: emptyList(), averages, periodDays)?.let { patterns += it }
        }
        return patterns
    }

    /** Rebuilds a detected pattern with AI wording. Every numeric field is taken from the original. */
    private fun enriched(original: WhatHappenedPattern, observation: String?, contributingFactors: List<String>?, discussQuestions: List<String>?, periodDays: Int): WhatHappenedPattern =
        original.copy(
            source = PatternSource.AI_ENRICHED,
            observation = withoutUnsupportedWeekdayClaims(observation, periodDays) ?: original.observation,
            contributingFactors = withoutUnsupportedWeekdayClaims(contributingFactors, periodDays) ?: original.contributingFactors,
            discussQuestions = withoutUnsupportedWeekdayClaims(discussQuestions, periodDays) ?: original.discussQuestions,
        )

    /**
     * Builds an AI-proposed pattern, verifying its one numeric claim against real data: the
     * opportunity count is the actual number of days with readings, and the occurrence count is
     * clamped into it. Null when there is no data to verify against, or when the claim is one
     * the window cannot support.
     */
    private fun proposed(title: String, observation: String, occurrenceCount: Int, priority: String?, contributingFactors: List<String>, discussQuestions: List<String>, dailyAverages: List<WhatHappenedPatternChartPoint>, periodDays: Int): WhatHappenedPattern? {
        val cleanTitle = cleaned(title) ?: return null
        val cleanObservation = cleaned(observation) ?: return null
        // No detector wording to fall back on, so an unsupported weekday claim cannot be
        // replaced — the whole pattern goes.
        if (periodDays < MIN_PERIOD_DAYS_FOR_WEEKDAY_CLAIMS && (makesWeekdayClaim(cleanTitle) || makesWeekdayClaim(cleanObservation))) return null
        val opportunities = dailyAverages.size
        if (opportunities <= 0) return null
        val occurrences = occurrenceCount.coerceIn(0, opportunities)
        return WhatHappenedPattern(
            id = java.util.UUID.randomUUID().toString(),
            source = PatternSource.AI,
            title = cleanTitle,
            observation = cleanObservation,
            frequencyLabel = if (occurrences > 0) "$occurrences of $opportunities days" else "Across $opportunities days",
            occurrenceCount = occurrences,
            opportunityCount = opportunities,
            priority = priorityFrom(priority),
            // Chart values are ours, not AI's.
            chartPoints = dailyAverages,
            chartKind = WhatHappenedPatternChartKind.AVERAGE_GLUCOSE,
            contributingFactors = withoutUnsupportedWeekdayClaims(contributingFactors, periodDays) ?: emptyList(),
            discussQuestions = withoutUnsupportedWeekdayClaims(discussQuestions, periodDays) ?: emptyList(),
            score = 0.0,
        )
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
