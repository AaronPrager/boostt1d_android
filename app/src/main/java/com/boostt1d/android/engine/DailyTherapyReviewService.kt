package com.boostt1d.android.engine

import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Formula-first daily therapy review.
 *
 * 1. The local builder measures basal, ISF and ICR and calculates conservative, bounded
 *    discussion values.
 * 2. At most once per day, AI sees the full seven-day context and chooses which verified
 *    findings are most meaningful, explains cross-domain contributors, and suggests what to
 *    verify next.
 * 3. Any AI recommendation that does not name a current formula finding is discarded.
 *
 * The window is the seven complete days ending last midnight, so the story it tells is
 * settled: today's readings are not in it. That is why today's wording is reused for every
 * reopen — see [weekReuseKey].
 *
 * Ported from the iOS DailyTherapyReviewService. The network call behind step 2 is the
 * [Reviewer] interface; this build passes none, so the ladder ends at the formula review the
 * way iOS does with AI disabled.
 */
object DailyTherapyReviewService {
    private const val REVIEW_SCHEMA_VERSION = "2"

    /** The once-daily AI trip. Absent until phase 4 wires the backend proxy. */
    interface Reviewer {
        suspend fun reviewDailyTherapyPlan(
            glucoseEntries: List<NightscoutGlucoseEntry>,
            treatments: List<NightscoutTreatment>,
            foodLogSummary: String,
            profile: NightscoutProfileDocument?,
            formulaReview: TherapySettingsReview,
            lowGlucose: Double,
            highGlucose: Double,
            periodStartMillis: Long,
            periodEndMillis: Long,
        ): AIDailyTherapyReview
    }

    /**
     * Status notes exist to explain the state of the review pipeline — cache hits, spent
     * attempts, whether AI answered. That is our diagnostic, not the reader's business, and
     * telling them a conclusion came from "the local formula fallback" only invites them to
     * weigh it differently. Outside dev builds they see either nothing, or the one thing they
     * can actually act on — never where it came from.
     */
    private fun note(dev: String, user: String? = null, isDevMode: Boolean): String? = if (isDevMode) dev else user

    fun formulaReview(
        review: TherapySettingsReview,
        treatments: List<NightscoutTreatment>,
        periodStartMillis: Long,
        periodEndMillis: Long,
        nowMillis: Long,
        statusNote: String? = null,
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
    ): DailyTherapyReview = assemble(
        formulaReview = review,
        treatments = treatments,
        periodStartMillis = periodStartMillis,
        periodEndMillis = periodEndMillis,
        ai = null,
        generatedAtMillis = nowMillis,
        statusNote = statusNote,
        therapyType = therapyType,
    )

    /**
     * @param forceRefresh dev-only bypass for the once-per-day cache. Ignored unless
     *   [allowRepeatedDailyAIReview] is true, so production builds cannot spend extra calls
     *   from a mistaken call site.
     * @param reviewer the AI trip; null means AI is disabled in this build.
     */
    suspend fun review(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogEntries: List<FoodLogSnapshot>,
        profile: NightscoutProfileDocument?,
        formulaReview: TherapySettingsReview,
        lowGlucose: Double,
        highGlucose: Double,
        periodStartMillis: Long,
        periodEndMillis: Long,
        nowMillis: Long,
        cache: DailyTherapyReviewCache,
        reviewer: Reviewer?,
        forceRefresh: Boolean = false,
        allowRepeatedDailyAIReview: Boolean = false,
        isDevMode: Boolean = false,
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): DailyTherapyReview {
        val reuseKey = weekReuseKey(periodStartMillis, profile, lowGlucose, highGlucose)
        val bypassDailyLimit = forceRefresh && allowRepeatedDailyAIReview

        fun formulaOnly(statusNote: String?) = formulaReview(
            formulaReview, treatments, periodStartMillis, periodEndMillis, nowMillis, statusNote, therapyType,
        )

        if (reviewer == null) {
            return formulaOnly(note(dev = "AI review is disabled in this build; showing the complete local formula review.", isDevMode = isDevMode))
        }

        if (!formulaReview.hasTherapySettings) {
            return formulaOnly(
                // The one case where the reader has something to do about it.
                note(
                    dev = "Add your basal, correction factor, and carb-ratio profile before using the daily AI review. No AI request was made.",
                    user = "Add your insulin settings in your profile to get a dose review.",
                    isDevMode = isDevMode,
                )
            )
        }

        if (!hasUsableSevenDayCGM(glucoseEntries, timeZone)) {
            return formulaOnly(
                note(
                    dev = "The seven-day window does not yet have enough CGM coverage on every day for a meaningful AI pass. No AI request was made.",
                    user = "There is not yet enough glucose data across the week for a full review.",
                    isDevMode = isDevMode,
                )
            )
        }

        // Reuse today's wording for every reopen. New glucose, treatments and food logged
        // *today* are outside this window by definition, so they do not invalidate it; only a
        // therapy-profile or range change does, and that falls through to the attempt gate
        // below (already spent, in practice, since a result exists).
        if (!bypassDailyLimit) {
            val cached = cache.resultForToday(nowMillis)
            if (cached != null && cached.reuseKey == reuseKey) {
                return assemble(
                    formulaReview, treatments, periodStartMillis, periodEndMillis,
                    ai = cached.response, generatedAtMillis = cached.analyzedAtMillis,
                    statusNote = note(dev = "Reused today's seven-day AI review.", isDevMode = isDevMode),
                    therapyType = therapyType,
                )
            }
        }

        if (!bypassDailyLimit) {
            if (!cache.beginAttempt(nowMillis)) {
                return formulaOnly(note(dev = "Today's AI attempt has already been used; showing the local formula review.", isDevMode = isDevMode))
            }
        } else {
            // Drop the spent attempt / stale response so a later normal open does not
            // resurrect the pre-rerun wording.
            cache.clear()
            cache.beginAttempt(nowMillis)
        }

        return try {
            val response = reviewer.reviewDailyTherapyPlan(
                glucoseEntries, treatments, foodLogSummary(foodLogEntries, timeZone), profile,
                formulaReview, lowGlucose, highGlucose, periodStartMillis, periodEndMillis,
            )
            cache.store(response, analyzedAtMillis = nowMillis, periodStartMillis = periodStartMillis, periodEndMillis = periodEndMillis, reuseKey = reuseKey)
            assemble(
                formulaReview, treatments, periodStartMillis, periodEndMillis,
                ai = response, generatedAtMillis = nowMillis,
                statusNote = note(
                    dev = if (bypassDailyLimit) "Dev re-run: spent a fresh AI request (once-per-day limit bypassed)." else "AI runs at most once per day; refreshes reuse this review.",
                    isDevMode = isDevMode,
                ),
                therapyType = therapyType,
            )
        } catch (error: Exception) {
            // Silent for the reader by design: the review below is complete either way, so
            // announcing a fallback would only cast doubt on content that did not change.
            formulaOnly(note(dev = "AI was unavailable today (${error.message}). The dose review below is the local formula fallback.", isDevMode = isDevMode))
        }
    }

    // MARK: - Assembly and validation

    fun assemble(
        formulaReview: TherapySettingsReview,
        treatments: List<NightscoutTreatment>,
        periodStartMillis: Long,
        periodEndMillis: Long,
        ai: AIDailyTherapyReview?,
        generatedAtMillis: Long,
        statusNote: String?,
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
    ): DailyTherapyReview {
        val delivery = InsulinDeliveryContext(treatments, therapyType)
        val findingsByKey = firstByKey(formulaReview.findings.map { it.dailyReviewKey to it })
        val aiByKey = firstByKey(
            ai?.recommendations
                ?.filter { findingsByKey[it.findingKey] != null && isSafeAIRecommendation(it) }
                ?.map { it.findingKey to it }
                ?: emptyList()
        )

        // AI-selected findings lead; every remaining formula finding still appears so an AI
        // omission or malformed key can never erase a deterministic result.
        val selectedKeys = mutableListOf<String>()
        ai?.recommendations?.forEach { recommendation ->
            if (findingsByKey[recommendation.findingKey] == null) return@forEach
            if (!isSafeAIRecommendation(recommendation)) return@forEach
            if (recommendation.findingKey in selectedKeys) return@forEach
            selectedKeys += recommendation.findingKey
        }
        val allKeys = selectedKeys + formulaReview.findings.map { it.dailyReviewKey }.filter { it !in selectedKeys }

        val proposals = allKeys.mapNotNull { key ->
            val finding = findingsByKey[key] ?: return@mapNotNull null
            val enrichment = aiByKey[key]
            // U/hr cannot be translated into a long-acting injection change safely.
            val proposedValue = if (delivery.therapyType == InsulinTherapyType.INJECTIONS && finding.parameter == TherapyParameter.BASAL) null else finding.suggestedValue

            DailyTherapyProposal(
                id = key,
                parameter = finding.parameter,
                timeWindow = finding.windowLabel,
                title = finding.title,
                summary = finding.headline,
                currentValue = finding.currentValue,
                proposedValue = proposedValue,
                priority = finding.priority,
                evidenceStrength = finding.strength,
                sampleLabel = finding.sampleLabel,
                explanation = enrichment?.explanation ?: finding.rationale,
                evidence = finding.evidence,
                contributingFactors = enrichment?.contributingFactors ?: emptyList(),
                whatToVerify = enrichment?.whatToVerify ?: emptyList(),
                caveats = finding.caveats,
                careTeamQuestion = finding.doctorQuestion,
                deliveryNote = deliveryNote(finding.parameter, delivery),
                wasAIReviewed = enrichment != null,
            )
        }

        val safeObservations = ai?.observations?.filter { item ->
            isSafeAINarrative(listOf(item.title, item.observation, item.whatToTrack) + item.supportingEvidence)
        } ?: emptyList()
        val observations = safeObservations.take(4).mapIndexed { index, item ->
            DailyTherapyObservation(
                id = "ai-observation-$index-${item.title}",
                title = item.title,
                observation = item.observation,
                supportingEvidence = item.supportingEvidence,
                whatToTrack = item.whatToTrack,
            )
        }

        val overview = when {
            ai != null && ai.overview.trim().isNotEmpty() && isSafeAINarrative(listOf(ai.overview)) -> ai.overview
            proposals.isEmpty() ->
                if (formulaReview.steadyNotes.isEmpty()) "Not enough of this week could be checked to raise a setting change."
                else "Your settings were checked against this week and nothing looked off by enough to raise as a change."
            else -> "${proposals.size} setting window${if (proposals.size == 1) "" else "s"} worth discussing this week. " +
                "The numbers come from your current profile and your readings from the last seven days."
        }

        return DailyTherapyReview(
            periodStartMillis = periodStartMillis,
            periodEndMillis = periodEndMillis,
            generatedAtMillis = generatedAtMillis,
            source = if (ai == null) DailyTherapyReviewSource.FORMULA_ONLY else DailyTherapyReviewSource.FORMULA_AND_AI,
            deliveryModeLabel = deliveryModeLabel(delivery),
            overview = overview,
            proposals = proposals,
            observations = observations,
            experiments = (ai?.experiments ?: emptyList()).filter { isSafeAINarrative(listOf(it)) }.take(6),
            safetyNotes = (ai?.safetyNotes ?: emptyList()).filter { isSafeAINarrative(listOf(it)) }.take(4),
            statusNote = statusNote,
        )
    }

    /** Swift's `Dictionary(_, uniquingKeysWith: { first, _ in first })`. */
    private fun <V> firstByKey(pairs: List<Pair<String, V>>): Map<String, V> {
        val result = LinkedHashMap<String, V>()
        for ((key, value) in pairs) if (key !in result) result[key] = value
        return result
    }

    /**
     * Identifies the analysis week for cache reuse: which seven days, against which therapy
     * profile and range settings. Nothing that can move during the day belongs in here.
     *
     * A local key only — never compared across devices — so it summarises the profile from
     * the document's own fields rather than through the doctor-visit snapshot iOS reuses.
     */
    fun weekReuseKey(periodStartMillis: Long, profile: NightscoutProfileDocument?, lowGlucose: Double, highGlucose: Double): String =
        sha256(
            listOf(
                REVIEW_SCHEMA_VERSION,
                (periodStartMillis / 1000).toString(),
                String.format(Locale.US, "%.1f", lowGlucose),
                String.format(Locale.US, "%.1f", highGlucose),
                stableProfileSummary(profile),
            ).joinToString("\n---\n")
        )

    /**
     * Today's AI wording, when it belongs to this same analysis week. The screen paints with
     * this on open instead of formula-only; painting the formula first and swapping AI back in
     * afterwards is what made a sentence appear to vanish between opens.
     */
    fun todaysCachedAI(reuseKey: String, nowMillis: Long, cache: DailyTherapyReviewCache, aiEnabled: Boolean): DailyTherapyReviewCache.StoredResult? {
        if (!aiEnabled) return null
        val cached = cache.resultForToday(nowMillis) ?: return null
        return if (cached.reuseKey == reuseKey) cached else null
    }

    private fun stableProfileSummary(profile: NightscoutProfileDocument?): String {
        val document = profile ?: return "no-profile"
        val entry = document.activeEntry
        fun segments(values: List<com.boostt1d.android.data.TimeValue>) = values.joinToString(",") { "${it.time}=${it.value}" }
        return listOf(
            document.defaultProfile ?: "",
            entry?.units ?: document.units ?: "",
            entry?.dia?.toString() ?: "",
            segments(entry?.basal ?: emptyList()),
            segments(entry?.carbRatio ?: emptyList()),
            segments(entry?.sensitivity ?: emptyList()),
            segments(entry?.targetLow ?: emptyList()),
            segments(entry?.targetHigh ?: emptyList()),
        ).joinToString("|")
    }

    private fun sha256(source: String): String =
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) }

    /**
     * AI owns explanation only. Reject narrative that smuggles a numeric treatment value into
     * a string even though the response schema deliberately has no dose fields.
     */
    private fun isSafeAIRecommendation(recommendation: AIDailyTherapyRecommendation): Boolean =
        isSafeAINarrative(listOf(recommendation.explanation) + recommendation.contributingFactors + recommendation.whatToVerify)

    private val unsafePatterns = listOf(
        Regex("""(?i)\b\d+(?:\.\d+)?\s*(?:u|unit|units)(?:\s*/\s*(?:h|hr|hour))?\b"""),
        Regex("""(?i)\b\d+(?:\.\d+)?\s*(?:mg/dl(?:/u)?|mmol/l(?:/u)?|g/u)\b"""),
        Regex("""\b\d+(?:\.\d+)?\s*%"""),
        Regex("""(?i)\b1\s*:\s*\d+(?:\.\d+)?\b"""),
        Regex("""(?i)\b(?:set|change|increase|decrease|raise|lower|reduce|strengthen|weaken|adjust|test)\b[^.!?\n]{0,50}\b(?:basal|isf|icr|ratio|factor|target|dose|insulin)\b"""),
        Regex("""(?i)\b(?:basal|isf|icr|ratio|factor|target|dose|insulin)\b[^.!?\n]{0,50}\b(?:set|change|increase|decrease|raise|lower|reduce|strengthen|weaken|adjust|test)\b"""),
    )

    internal fun isSafeAINarrative(strings: List<String>): Boolean {
        val joined = strings.joinToString(" ")
        return unsafePatterns.none { it.containsMatchIn(joined) }
    }

    /**
     * Require evidence on all seven completed calendar days before spending the daily AI
     * request. Forty-eight readings is eight hours at ten-minute resolution — intentionally
     * tolerant of gaps while still rejecting a mostly empty "week."
     */
    private fun hasUsableSevenDayCGM(entries: List<NightscoutGlucoseEntry>, timeZone: TimeZone): Boolean {
        val counts = entries.groupingBy { TodaySoFarBuilder.startOfDay(it.epochMilliseconds, timeZone) }.eachCount()
        return counts.size == 7 && counts.values.all { it >= 48 }
    }

    fun foodLogSummary(entries: List<FoodLogSnapshot>, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (entries.isEmpty()) return "No in-app food log entries in this period."
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }
        return entries.sortedBy { it.recordedAtMillis }.joinToString("\n") { entry ->
            val parts = mutableListOf(formatter.format(Date(entry.recordedAtMillis)), entry.descriptionText)
            entry.carbsGrams?.let { parts += "${TherapySettingsReviewBuilder.schoolbookRound(it).toInt()}g carbs" }
            entry.insulinUnits?.let { parts += String.format(Locale.US, "%.2fu", it) }
            entry.fatGrams?.let { parts += "${TherapySettingsReviewBuilder.schoolbookRound(it).toInt()}g fat" }
            entry.proteinGrams?.let { parts += "${TherapySettingsReviewBuilder.schoolbookRound(it).toInt()}g protein" }
            entry.fiberGrams?.let { parts += "${TherapySettingsReviewBuilder.schoolbookRound(it).toInt()}g fiber" }
            entry.notes?.takeIf { it.isNotEmpty() }?.let { parts += "notes: $it" }
            parts.joinToString(" · ")
        }
    }

    private fun deliveryModeLabel(delivery: InsulinDeliveryContext): String {
        if (delivery.isClosedLoop) return "Automated insulin delivery"
        return when (delivery.therapyType) {
            InsulinTherapyType.PUMP -> "Manual pump"
            InsulinTherapyType.INJECTIONS -> "Pens or injections"
            InsulinTherapyType.UNSPECIFIED, InsulinTherapyType.CLOSED_LOOP -> "Standard insulin delivery"
        }
    }

    private fun deliveryNote(parameter: TherapyParameter, delivery: InsulinDeliveryContext): String {
        if (delivery.isClosedLoop) {
            return when (parameter) {
                TherapyParameter.BASAL -> "Automated delivery: this is a programmed baseline discussion point. Only hours outside meals and logged exercise were measured; review why the algorithm compensated before changing the profile."
                TherapyParameter.ISF -> "Automated delivery: correction factor can affect both manual corrections and algorithm calculations. Only standalone user-initiated corrections were measured."
                TherapyParameter.CARB_RATIO -> "Automated delivery: carb ratio affects the announced meal dose; later automatic corrections may partially hide a weak meal setting."
            }
        }
        return when (parameter) {
            TherapyParameter.BASAL ->
                if (delivery.therapyType == InsulinTherapyType.INJECTIONS)
                    "Injection therapy: an hourly pump-basal value is not translated into a long-acting dose. Use the time pattern as a care-team discussion point only."
                else
                    "Manual pump: this discussion value applies to the programmed basal segment; check for food, active insulin and exercise before interpreting fasting drift."
            TherapyParameter.ISF -> "This discussion value applies to the correction factor used for standalone corrections in this time window."
            TherapyParameter.CARB_RATIO -> "This discussion value applies to the meal carb ratio in this window, based only on meals with enough follow-up data."
        }
    }
}
