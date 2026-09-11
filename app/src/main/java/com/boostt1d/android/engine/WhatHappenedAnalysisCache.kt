package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutProfileDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the persisted state lives. The app backs this with a file in the cache directory. */
interface AnalysisCacheStore {
    fun read(): String?
    fun write(json: String)
    fun delete()
}

class InMemoryAnalysisCacheStore : AnalysisCacheStore {
    var contents: String? = null
    override fun read(): String? = contents
    override fun write(json: String) { contents = json }
    override fun delete() { contents = null }
}

/**
 * Memoises the two heaviest parts of the report: the hour-by-hour settings review and the
 * therapy-change outcomes.
 *
 * Both describe **complete days only**, so neither can change between midnights. Without a
 * cache they were rebuilt from scratch every time the screen appeared — sweeping a week of
 * five-minute readings for fasting windows, clean corrections, single-bolus meals and
 * before/after comparisons — to produce byte-for-byte the same answer as thirty seconds
 * earlier.
 *
 * The signature is the point. It covers everything that can legitimately change the result
 * and nothing that cannot: the window's start (so the entry expires at midnight), how much
 * settled data exists and how recent it is (so backfill invalidates), and the thresholds and
 * profile (so a settings edit invalidates). Today's readings appear in none of it.
 *
 * Ported from the iOS WhatHappenedAnalysisCache.
 */
class WhatHappenedAnalysisCache(private val store: AnalysisCacheStore = InMemoryAnalysisCacheStore()) {

    @Serializable
    data class Signature(
        val windowStartMillis: Long,
        /** Readings up to last midnight — the only ones any of this analyses. */
        val settledEntryCount: Int,
        val latestSettledReadingMillis: Long,
        val settledTreatmentCount: Int,
        val lowGlucose: Double,
        val highGlucose: Double,
        val profileDigest: Int,
    )

    class Bundle(val review: TherapySettingsReview, val outcomes: List<TherapyChangeOutcome>)

    /**
     * Everything the report screen renders, kept so a reopen can paint before any network
     * call is made — the last good result, painted immediately, refreshed underneath.
     */
    @Serializable
    data class Snapshot(
        val report: WhatHappenedWeeklyReport,
        val patterns: List<WhatHappenedPattern>,
        val coverageLabel: String?,
        val dailyDays: List<WhatHappenedDayOverview>,
        val review: TherapySettingsReview,
        val outcomes: List<TherapyChangeOutcome>,
        val watchingSinceMillis: Long?,
        val dailyTherapyReview: DailyTherapyReview,
    )

    /** What actually reaches disk: the painted result plus the signature it was computed under. */
    @Serializable
    private data class PersistedState(val schemaVersion: Int, val signature: Signature, val snapshot: Snapshot)

    private val json = Json { ignoreUnknownKeys = true }

    /** One entry. A second window is never wanted at the same moment. */
    private var signature: Signature? = null
    private var bundle: Bundle? = null

    /** Deliberately not signature-keyed. Its job is to have *something* correct-as-of-last-time on screen instantly. */
    var snapshot: Snapshot? = null
        private set

    init {
        restore()
    }

    fun store(snapshot: Snapshot) {
        this.snapshot = snapshot
        persist()
    }

    fun cached(signature: Signature): Bundle? = if (this.signature == signature) bundle else null

    /**
     * Does not write to disk on its own, deliberately. On a day boundary this runs with the
     * *new* signature while [snapshot] is still the one restored from the previous launch, so
     * persisting here would pair a fresh signature with a stale snapshot. The next
     * [store] is the first moment the two are coherent, and it is what writes.
     */
    fun store(bundle: Bundle, signature: Signature) {
        this.signature = signature
        this.bundle = bundle
    }

    fun invalidate() {
        signature = null
        bundle = null
        snapshot = null
        store.delete()
    }

    private fun persist() {
        val snapshot = snapshot ?: return
        val signature = signature ?: return
        val state = PersistedState(SCHEMA_VERSION, signature, snapshot)
        runCatching { store.write(json.encodeToString(PersistedState.serializer(), state)) }
    }

    /** A decode failure is simply a miss: any change to a persisted type must not be able to crash the screen. */
    private fun restore() {
        val raw = store.read() ?: return
        val state = runCatching { json.decodeFromString(PersistedState.serializer(), raw) }.getOrNull() ?: return
        if (state.schemaVersion != SCHEMA_VERSION) return
        snapshot = state.snapshot
        signature = state.signature
        bundle = Bundle(state.snapshot.review, state.snapshot.outcomes)
    }

    companion object {
        /**
         * Bumped whenever any persisted type gains or loses a field. A mismatch is treated as
         * no cache at all, which is the only safe reading of a payload written by another build.
         */
        const val SCHEMA_VERSION = 5

        /** Digest of the settings the review reasons about, so editing basal, ISF or carb ratio drops the cached answer. */
        fun profileDigest(profile: NightscoutProfileDocument?): Int {
            if (profile == null) return 0
            val settings = TherapyProfileSettings(profile)
            var hash = 17
            for (segments in listOf(settings.basal, settings.isf, settings.carbRatio)) {
                for (segment in segments) {
                    hash = hash * 31 + segment.startHour.hashCode()
                    hash = hash * 31 + segment.value.hashCode()
                }
                hash = hash * 31 - 1
            }
            return hash
        }
    }
}
