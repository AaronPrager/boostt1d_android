package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.sync.NightscoutService
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Where the detector keeps its timeline.
 *
 * An interface rather than a concrete store so the engine holds no Android dependency: the
 * app backs it with DataStore, the tests with a list. This is the same isolation the iOS
 * tests get from a private UserDefaults suite.
 */
interface TherapySnapshotStore {
    fun load(): List<TherapySettingsSnapshot>
    fun save(snapshots: List<TherapySettingsSnapshot>)
    var historyFetchedAtMillis: Long?
    fun clear()
}

/** A store that forgets everything when it goes out of scope. For tests. */
class InMemoryTherapySnapshotStore : TherapySnapshotStore {
    private var snapshots: List<TherapySettingsSnapshot> = emptyList()
    override var historyFetchedAtMillis: Long? = null
    override fun load() = snapshots
    override fun save(snapshots: List<TherapySettingsSnapshot>) { this.snapshots = snapshots }
    override fun clear() { snapshots = emptyList(); historyFetchedAtMillis = null }
}

/**
 * Notices when therapy settings change, and remembers what they were before.
 *
 * Two sources, in order of trust:
 *
 * - **Nightscout profile history.** `/api/v1/profile.json` returns every profile document
 *   ever uploaded, each with its own start date. That is a real edit history and it covers
 *   changes made long before the app was installed.
 * - **What the app has seen.** Every profile load is fingerprinted and compared against the
 *   last one stored. This is the only source in Manual and Dexcom modes, where the profile
 *   lives on the device and has no history of its own.
 *
 * Both feed one ordered list of snapshots. Changes are derived by diffing consecutive
 * entries, never asserted — if the app has only ever seen one schedule, there are no changes
 * to report, and it says nothing rather than guessing.
 *
 * Ported from the iOS TherapyChangeDetector.
 */
class TherapyChangeDetector(
    private val store: TherapySnapshotStore,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private var stored: List<TherapySettingsSnapshot> = store.load().sortedBy { it.effectiveAtMillis }

    // MARK: Recording

    /**
     * Fingerprints what the profile says now, folds in any Nightscout history, and returns
     * every change the stored timeline can support.
     *
     * Safe to call on every report load: identical settings append nothing.
     */
    fun record(
        profile: NightscoutProfileDocument?,
        history: List<NightscoutProfileDocument> = emptyList(),
        nowMillis: Long,
    ): List<TherapyChange> {
        val candidates = mutableListOf<TherapySettingsSnapshot>()

        for (document in history) {
            snapshot(document, fallbackMillis = null)?.let { candidates.add(it) }
        }
        if (profile != null) {
            snapshot(profile, fallbackMillis = nowMillis)?.let { candidates.add(it) }
        }
        if (candidates.isEmpty()) return changes()

        val merged = stored.toMutableList()
        for (candidate in candidates.sortedBy { it.effectiveAtMillis }) {
            insert(candidate, merged)
        }
        merged.sortBy { it.effectiveAtMillis }
        val trimmed = if (merged.size > MAX_SNAPSHOTS) merged.takeLast(MAX_SNAPSHOTS) else merged

        if (trimmed != stored) {
            stored = trimmed
            store.save(trimmed)
        }
        return changes()
    }

    /**
     * Adds a snapshot unless the timeline already says the same thing.
     *
     * A snapshot whose settings match the one before it carries no information — but if it
     * declares an *earlier* effective date than what we recorded by observation, it does: it
     * means the settings have been in place longer than the app knew, which moves the
     * "before" side of any comparison. So that case rewrites the date rather than appending.
     */
    private fun insert(candidate: TherapySettingsSnapshot, list: MutableList<TherapySettingsSnapshot>) {
        if (candidate.isEmpty) return

        val duplicateIndex = list.indexOfFirst {
            it.hasSameSettings(candidate) && abs(it.effectiveAtMillis - candidate.effectiveAtMillis) < DEDUPE_WINDOW_MILLIS
        }
        if (duplicateIndex >= 0) {
            if (candidate.effectiveAtMillis < list[duplicateIndex].effectiveAtMillis) list[duplicateIndex] = candidate
            return
        }

        // Same settings as the most recent entry: nothing changed, so only the start date can
        // be worth keeping, and only when it is earlier than what we already had.
        val last = list.lastOrNull()
        if (last != null && last.hasSameSettings(candidate)) {
            if (candidate.effectiveAtMillis < last.effectiveAtMillis) list[list.size - 1] = candidate
            return
        }

        list.add(candidate)
    }

    // MARK: Reading

    /** Every change the stored timeline supports, newest first. */
    fun changes(): List<TherapyChange> {
        if (stored.size < 2) return emptyList()
        val result = mutableListOf<TherapyChange>()
        for (index in 1 until stored.size) result += diff(stored[index - 1], stored[index])
        return result.sortedByDescending { it.changedAtMillis }
    }

    /** Changes that took effect within the last [days] days, newest first. */
    fun changes(withinDays: Int, nowMillis: Long): List<TherapyChange> {
        val cutoff = nowMillis - withinDays * DAY_MILLIS
        return changes().filter { it.changedAtMillis >= cutoff }
    }

    /**
     * The most recent change to each parameter inside the window — what the settings review
     * needs in order to avoid mixing data from either side of an edit.
     */
    fun latestChangePerParameter(withinDays: Int, nowMillis: Long): Map<TherapyParameter, TherapyChange> {
        val result = mutableMapOf<TherapyParameter, TherapyChange>()
        for (change in changes(withinDays, nowMillis)) {
            val existing = result[change.parameter]
            if (existing == null || change.changedAtMillis > existing.changedAtMillis) result[change.parameter] = change
        }
        return result
    }

    /** True once the app has enough of a timeline to say anything about edits at all. */
    val hasHistory: Boolean get() = stored.size >= 2

    /**
     * When the app first had a picture of this user's settings. Below the analysis window this
     * means "we have not been watching long enough", which the UI says out loud.
     */
    val watchingSinceMillis: Long? get() = stored.firstOrNull()?.effectiveAtMillis

    fun snapshots(): List<TherapySettingsSnapshot> = stored

    // MARK: History refresh cadence

    /**
     * True when the Nightscout profile history has not been read yet today.
     *
     * That document list only changes when the user edits their therapy — a few times a year
     * for most people. Re-fetching it on every screen open bought nothing and cost a round trip
     * on the critical path. A local profile edit is caught at save time regardless.
     */
    fun needsHistoryRefresh(nowMillis: Long): Boolean {
        val last = store.historyFetchedAtMillis ?: return true
        return TodaySoFarBuilder.startOfDay(last, timeZone) != TodaySoFarBuilder.startOfDay(nowMillis, timeZone)
    }

    fun markHistoryFetched(nowMillis: Long) {
        store.historyFetchedAtMillis = nowMillis
    }

    /**
     * Clears the timeline. Used when the data source changes underneath us — settings from a
     * different Nightscout site are not a change to these settings — and when the user deletes
     * their profile so a new account does not inherit the previous timeline.
     */
    fun reset() {
        stored = emptyList()
        store.clear()
    }

    companion object {
        /** Enough to cover a year of ordinary tuning without growing without bound. */
        private const val MAX_SNAPSHOTS = 24

        /** Two snapshots this close together are the same edit seen twice, not two edits. */
        private const val DEDUPE_WINDOW_MILLIS = 30L * 60 * 1000

        /** Relative moves below this are float noise from unit conversion, not decisions. */
        private const val MIN_RELATIVE_CHANGE = 0.01

        private const val DAY_MILLIS = 86_400_000L

        // MARK: Diffing

        /** Hour-by-hour comparison, collapsed into contiguous runs that moved the same way. */
        fun diff(previous: TherapySettingsSnapshot, next: TherapySettingsSnapshot): List<TherapyChange> {
            val result = mutableListOf<TherapyChange>()

            for (parameter in TherapyParameter.entries) {
                // A schedule that appeared or vanished is a data event, not an edit — reporting
                // "0 → 0.9 U/hr" the first time a profile syncs would be a lie about a decision
                // the user never made.
                if (previous.segments(parameter).isEmpty() || next.segments(parameter).isEmpty()) continue

                val moved = mutableMapOf<Int, Pair<Double, Double>>()
                for (hour in 0 until 24) {
                    val before = previous.value(parameter, hour) ?: continue
                    val after = next.value(parameter, hour) ?: continue
                    if (before <= 0) continue
                    if (abs(after - before) / before < MIN_RELATIVE_CHANGE) continue
                    moved[hour] = before to after
                }
                if (moved.isEmpty()) continue

                for (run in circularRuns { it in moved }) {
                    // Split a run further wherever the values themselves differ, so one change
                    // always describes one before/after pair.
                    var segmentStart = 0
                    while (segmentStart < run.size) {
                        val anchor = moved.getValue(run[segmentStart])
                        var segmentEnd = segmentStart + 1
                        while (segmentEnd < run.size) {
                            val value = moved[run[segmentEnd]] ?: break
                            if (!isSame(value.first, anchor.first) || !isSame(value.second, anchor.second)) break
                            segmentEnd++
                        }
                        val hours = run.subList(segmentStart, segmentEnd)
                        val first = hours.first()
                        val last = hours.last()
                        result.add(
                            TherapyChange(
                                id = "${parameter.name}-${next.effectiveAtMillis / 1000}-$first",
                                parameter = parameter,
                                changedAtMillis = next.effectiveAtMillis,
                                startHour = first,
                                endHour = (last + 1) % 24,
                                windowLabel = windowLabel(hours),
                                previousValue = anchor.first,
                                newValue = anchor.second,
                            )
                        )
                        segmentStart = segmentEnd
                    }
                }
            }

            return result
        }

        private fun isSame(a: Double, b: Double) = abs(a - b) < 0.0001

        /**
         * Contiguous runs of hours, wrapping across midnight — an overnight edit is one change,
         * not one ending at 23:59 and another starting at 00:00.
         */
        private fun circularRuns(predicate: (Int) -> Boolean): List<List<Int>> {
            val flags = (0 until 24).map(predicate)
            if (flags.none { it }) return emptyList()
            if (flags.all { it }) return listOf((0 until 24).toList())

            val start = (0 until 24).first { flags[it] && !flags[(it + 23) % 24] }

            val runs = mutableListOf<List<Int>>()
            var current = mutableListOf<Int>()
            for (offset in 0 until 24) {
                val hour = (start + offset) % 24
                if (flags[hour]) {
                    current.add(hour)
                } else if (current.isNotEmpty()) {
                    runs.add(current); current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) runs.add(current)
            return runs
        }

        private fun windowLabel(hours: List<Int>): String {
            if (hours.isEmpty()) return ""
            if (hours.size >= 24) return "all day"
            val first = hours.first()
            val last = hours.last()
            val end = if ((last + 1) % 24 == 0) 24 else last + 1
            return String.format(Locale.US, "%02d:00–%02d:00", first, end)
        }

        // MARK: Snapshotting

        /**
         * Reuses [TherapyProfileSettings] so the values compared here are the same normalised
         * values the settings review reasons about — including its mmol/L ISF correction.
         */
        fun snapshot(document: NightscoutProfileDocument, fallbackMillis: Long?): TherapySettingsSnapshot? {
            val settings = TherapyProfileSettings(document)
            if (!settings.hasAny) return null
            val effectiveAt = effectiveMillis(document) ?: fallbackMillis ?: return null
            return TherapySettingsSnapshot(effectiveAt, settings.basal, settings.isf, settings.carbRatio)
        }

        /**
         * When the profile document says it started applying. `mills` is Nightscout's own epoch
         * milliseconds and is the most reliable; the string dates are a fallback for uploaders
         * that omit it.
         */
        fun effectiveMillis(document: NightscoutProfileDocument): Long? {
            document.mills?.takeIf { it > 0 }?.let { return it }
            for (candidate in listOf(document.startDate, document.createdAt)) {
                NightscoutService.parseTimestamp(candidate)?.let { return it }
            }
            return null
        }
    }
}
