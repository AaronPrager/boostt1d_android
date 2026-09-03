package com.boostt1d.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boostt1d.android.engine.AnalysisCacheStore
import com.boostt1d.android.engine.DailyTherapyReviewStore
import com.boostt1d.android.engine.TherapySettingsSnapshot
import com.boostt1d.android.engine.TherapySnapshotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val Context.insightsDataStore: DataStore<Preferences> by preferencesDataStore(name = "boost_insights")

/**
 * Where the engine's small persistent facts live on Android: the therapy snapshot timeline, the
 * daily review's attempt and response, and the advanced-detail preference. The engine sees only
 * its own interfaces; this is the DataStore behind them.
 *
 * The engine's stores are synchronous, so reads block on the DataStore flow. Every caller runs
 * on a background dispatcher (the report is built off-main), which is what makes that
 * acceptable here and nowhere else.
 */
class InsightsStores(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val snapshotsKey = stringPreferencesKey("therapy_snapshots")
    private val historyFetchedKey = longPreferencesKey("therapy_history_fetched_at")
    private val attemptKey = longPreferencesKey("daily_review_attempt_at")
    private val resultKey = stringPreferencesKey("daily_review_result")
    private val advancedKey = booleanPreferencesKey("therapy_advanced_detail")

    /** Off by default: the Therapy page speaks plain English until someone asks for the numbers. */
    val advancedTherapyDetail: Flow<Boolean> = context.insightsDataStore.data.map { it[advancedKey] ?: false }

    suspend fun setAdvancedTherapyDetail(enabled: Boolean) {
        context.insightsDataStore.edit { it[advancedKey] = enabled }
    }

    val therapySnapshots: TherapySnapshotStore = object : TherapySnapshotStore {
        override fun load(): List<TherapySettingsSnapshot> = readBlocking { prefs ->
            prefs[snapshotsKey]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(TherapySettingsSnapshot.serializer()), raw) }.getOrNull()
            } ?: emptyList()
        }

        override fun save(snapshots: List<TherapySettingsSnapshot>) = writeBlocking {
            it[snapshotsKey] = json.encodeToString(ListSerializer(TherapySettingsSnapshot.serializer()), snapshots)
        }

        override var historyFetchedAtMillis: Long?
            get() = readBlocking { it[historyFetchedKey] }
            set(value) = writeBlocking { if (value == null) it.remove(historyFetchedKey) else it[historyFetchedKey] = value }

        override fun clear() = writeBlocking { it.remove(snapshotsKey); it.remove(historyFetchedKey) }
    }

    val dailyReview: DailyTherapyReviewStore = object : DailyTherapyReviewStore {
        override var lastAttemptMillis: Long?
            get() = readBlocking { it[attemptKey] }
            set(value) = writeBlocking { if (value == null) it.remove(attemptKey) else it[attemptKey] = value }

        override var latestResultJson: String?
            get() = readBlocking { it[resultKey] }
            set(value) = writeBlocking { if (value == null) it.remove(resultKey) else it[resultKey] = value }
    }

    /**
     * Cache directory, not files: entirely derived from data the app still holds, so it must
     * never be backed up, and the system reclaiming it costs one recomputation.
     */
    val analysisCache: AnalysisCacheStore = object : AnalysisCacheStore {
        private val file: File get() = File(context.cacheDir, "what-happened-analysis.json")
        override fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
        override fun write(json: String) {
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json)
                if (!temp.renameTo(file)) file.writeText(json)
            }
        }
        override fun delete() { runCatching { file.delete() } }
    }

    /** For "delete everything": every derived fact goes with the data it was derived from. */
    suspend fun clear() {
        context.insightsDataStore.edit { it.clear() }
        analysisCache.delete()
    }

    private fun <T> readBlocking(read: (Preferences) -> T): T =
        runBlocking { read(context.insightsDataStore.data.first()) }

    private fun writeBlocking(edit: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking { context.insightsDataStore.edit { edit(it) } }
    }
}
