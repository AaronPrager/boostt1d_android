package com.boostt1d.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Everything the user has logged: glucose readings and treatments.
 *
 * Readings live in Room because there will eventually be tens of thousands of them.
 * Treatments live in one JSON file, which is what iOS does — a week of insulin doses and
 * meals is small, and the file is written whole so a partial write cannot leave half a
 * treatment behind.
 *
 * The file sits in `filesDir`, not the cache: these entries were typed by a person and
 * exist nowhere else, so the system must not be free to reclaim them. That is the same
 * reasoning behind iOS choosing Application Support over Caches.
 */
class LogRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = BoostDatabase.get(appContext).glucoseReadingDao()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storeFile = File(appContext.filesDir, "local-data.json")

    /** Serialized so two writes landing together cannot interleave and lose one. */
    private val writeLock = Mutex()

    private val _treatments = MutableStateFlow<List<NightscoutTreatment>>(emptyList())
    val treatments = _treatments.asStateFlow()

    val readings: Flow<List<NightscoutGlucoseEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toEntry() } }

    /** Readings newest-first with their source tag, for a log that shows where each came from. */
    val readingRows: Flow<List<GlucoseReadingEntity>> = dao.observeAll()

    @kotlinx.serialization.Serializable
    private data class StoredData(
        val treatments: List<NightscoutTreatment> = emptyList(),
    )

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!storeFile.exists()) return@withContext
        val stored = runCatching { json.decodeFromString<StoredData>(storeFile.readText()) }
            .getOrNull() ?: return@withContext
        _treatments.value = stored.treatments.sortedByDescending { it.recordedAtMillis }
    }

    // MARK: - Readings

    suspend fun addManualReading(sgvMgdl: Int, atMillis: Long) {
        // Manual entries carry the same shape a download would produce, so nothing
        // downstream has to know which is which — only `source` distinguishes them.
        dao.upsert(
            listOf(
                GlucoseReadingEntity(
                    epochMilliseconds = atMillis,
                    sgv = sgvMgdl,
                    device = MANUAL_DEVICE,
                    source = SOURCE_MANUAL,
                )
            )
        )
    }

    /**
     * Readings downloaded from a remote source.
     *
     * Written with the source tag rather than merged against what is already stored: the
     * primary key is the exact millisecond, so re-downloading an overlapping window
     * updates those rows instead of duplicating them. Reconciling two vendors that
     * describe the *same moment slightly differently* happens at read time, in
     * [GlucoseSourceStitch], because which one wins depends on the active source.
     */
    suspend fun upsertRemoteReadings(entries: List<NightscoutGlucoseEntry>, sourceTag: String) {
        if (entries.isEmpty()) return
        dao.upsert(entries.map { GlucoseReadingEntity.from(it, sourceTag) })
    }

    /**
     * Treatments downloaded from a remote source, merged with what is already held.
     *
     * Deduped on [NightscoutTreatment.cacheKey], and the remote row wins: it carries the
     * Nightscout id and any algorithm flags a local copy would not have.
     */
    suspend fun mergeRemoteTreatments(remote: List<NightscoutTreatment>) {
        if (remote.isEmpty()) return
        val remoteKeys = remote.map { it.cacheKey }.toSet()
        val kept = _treatments.value.filterNot { it.cacheKey in remoteKeys }
        replaceTreatments(kept + remote)
    }

    suspend fun deleteReading(epochMilliseconds: Long) = dao.delete(epochMilliseconds)

    suspend fun readingsBetween(from: Long, to: Long): List<NightscoutGlucoseEntry> =
        dao.between(from, to).map { it.toEntry() }

    suspend fun latestReading(): NightscoutGlucoseEntry? = dao.latest()?.toEntry()

    /** Drops readings past the retention window. Safe to call on every launch. */
    suspend fun trimHistory(nowMillis: Long) =
        dao.deleteBefore(nowMillis - GlucoseCacheRules.MAX_RETENTION_MILLIS)

    // MARK: - Treatments

    suspend fun addTreatment(
        eventType: String,
        atMillis: Long,
        insulin: Double? = null,
        carbs: Double? = null,
        notes: String? = null,
        durationMinutes: Int? = null,
    ) {
        val treatment = NightscoutTreatment(
            id = UUID.randomUUID().toString(),
            eventType = eventType,
            mills = atMillis,
            enteredBy = ENTERED_BY_MANUAL,
            insulin = insulin,
            carbs = carbs,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            duration = durationMinutes,
        )
        replaceTreatments(_treatments.value + treatment)
    }

    suspend fun deleteTreatment(cacheKey: String) {
        replaceTreatments(_treatments.value.filterNot { it.cacheKey == cacheKey })
    }

    private suspend fun replaceTreatments(next: List<NightscoutTreatment>) {
        val sorted = next
            .distinctBy { it.cacheKey }
            .sortedByDescending { it.recordedAtMillis }
        _treatments.value = sorted
        persist(sorted)
    }

    private suspend fun persist(treatments: List<NightscoutTreatment>) =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                // Written via a temp file and renamed: a crash mid-write leaves the previous
                // good copy in place rather than a truncated one.
                val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
                temp.writeText(json.encodeToString(StoredData(treatments)))
                temp.renameTo(storeFile)
            }
        }

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val MANUAL_DEVICE = "BoostT1D Manual"
        const val ENTERED_BY_MANUAL = "BoostT1D"
    }
}
