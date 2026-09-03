package com.boostt1d.android.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * The food diary: photo analyses, manual meals, and carbs imported from the Event Log.
 *
 * Room-backed where iOS uses SwiftData. Windows are calendar days, not a rolling N×24h clock:
 * "1 day" means "today", so an entry from yesterday evening does not linger in it.
 *
 * Ported from the iOS FoodLogService.
 */
class FoodLogRepository(context: Context, private val timeZone: TimeZone = TimeZone.getDefault()) {

    private val dao = BoostDatabase.get(context.applicationContext).foodLogDao()

    private fun windowStart(withinDays: Int, nowMillis: Long): Long {
        val capped = withinDays.coerceIn(1, FoodLogRetention.RETENTION_DAYS)
        val startOfToday = TodaySoFarBuilder.startOfDay(nowMillis, timeZone)
        return Calendar.getInstance(timeZone).apply { timeInMillis = startOfToday; add(Calendar.DAY_OF_MONTH, -(capped - 1)) }.timeInMillis
    }

    fun observe(withinDays: Int, nowMillis: Long): Flow<List<FoodLogEntryEntity>> = dao.observeSince(windowStart(withinDays, nowMillis))

    suspend fun entries(withinDays: Int, nowMillis: Long): List<FoodLogEntryEntity> = dao.since(windowStart(withinDays, nowMillis))

    suspend fun snapshots(withinDays: Int, nowMillis: Long): List<FoodLogSnapshot> = entries(withinDays, nowMillis).map { it.toSnapshot() }

    suspend fun entry(id: String): FoodLogEntryEntity? = dao.byId(id)

    // MARK: - Save

    suspend fun save(analysis: FoodAnalysis, thumbnailJpeg: ByteArray?, recordedAtMillis: Long, notes: String? = null): FoodLogEntryEntity =
        saveEntry(
            descriptionText = analysis.descriptionText, carbsGrams = analysis.carbsGrams, caloriesKcal = analysis.caloriesKcal,
            fatGrams = analysis.fatGrams, proteinGrams = analysis.proteinGrams, fiberGrams = analysis.fiberGrams,
            confidence = analysis.confidence, recordedAtMillis = recordedAtMillis, notes = notes,
            thumbnailJpeg = thumbnailJpeg, source = FoodLogSource.PHOTO_ANALYSIS,
        )

    suspend fun saveEntry(
        descriptionText: String,
        carbsGrams: Double?,
        caloriesKcal: Double?,
        fatGrams: Double?,
        proteinGrams: Double?,
        fiberGrams: Double?,
        confidence: String?,
        recordedAtMillis: Long,
        notes: String?,
        thumbnailJpeg: ByteArray?,
        source: FoodLogSource,
        insulinUnits: Double? = null,
    ): FoodLogEntryEntity {
        val entry = FoodLogEntryEntity(
            id = UUID.randomUUID().toString(), recordedAtMillis = recordedAtMillis, descriptionText = descriptionText,
            carbsGrams = carbsGrams, caloriesKcal = caloriesKcal, fatGrams = fatGrams, proteinGrams = proteinGrams,
            fiberGrams = fiberGrams, confidence = confidence, notes = notes, thumbnailJpeg = thumbnailJpeg,
            source = source.rawValue, insulinUnits = insulinUnits,
        )
        dao.upsert(listOf(entry))
        pruneStaleEntries(recordedAtMillis.coerceAtLeast(System.currentTimeMillis()))
        return entry
    }

    /** Updates an existing food entry (Food Log only — does not write to the Event Log). */
    suspend fun update(
        id: String,
        descriptionText: String,
        carbsGrams: Double?,
        caloriesKcal: Double?,
        proteinGrams: Double?,
        fatGrams: Double?,
        fiberGrams: Double?,
        insulinUnits: Double?,
        recordedAtMillis: Long,
        notes: String?,
        thumbnailJpeg: ByteArray? = null,
        updateThumbnail: Boolean = false,
    ) {
        val existing = dao.byId(id) ?: return
        dao.upsert(listOf(existing.copy(
            descriptionText = descriptionText, carbsGrams = carbsGrams, caloriesKcal = caloriesKcal,
            proteinGrams = proteinGrams, fatGrams = fatGrams, fiberGrams = fiberGrams,
            insulinUnits = insulinUnits?.takeIf { it > 0 }, recordedAtMillis = recordedAtMillis, notes = notes,
            thumbnailJpeg = if (updateThumbnail) thumbnailJpeg else existing.thumbnailJpeg,
        )))
    }

    // MARK: - Import from treatments

    /**
     * Carb-bearing treatments (Nightscout download or local Event Log) become Food Log rows.
     * Idempotent via `linkedTreatmentId` == treatment cache key (legacy raw ids also count).
     * Returns how many rows were created.
     */
    suspend fun importCarbsFromTreatments(treatments: List<NightscoutTreatment>, nowMillis: Long = System.currentTimeMillis()): Int {
        val candidates = treatments.filter { it.recordsCarbsForFoodLog }
        if (candidates.isEmpty()) return 0
        val cutoff = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis; add(Calendar.DAY_OF_MONTH, -FoodLogRetention.RETENTION_DAYS) }.timeInMillis
        val existing = dao.linkedSince(cutoff).mapNotNull { it.linkedTreatmentId }.toSet()
        val rows = FoodLogImport.plan(candidates, existing)
        if (rows.isNotEmpty()) {
            dao.upsert(rows)
            pruneStaleEntries(nowMillis)
        }
        return rows.size
    }

    // MARK: - Delete

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    /** Only rows derived from a remote download. Photo analyses and manual meals belong to the user and survive a connection change. */
    suspend fun deleteImportedTreatmentEntries() = dao.deleteBySource(FoodLogSource.NIGHTSCOUT_EVENT.rawValue)

    suspend fun pruneStaleEntries(nowMillis: Long) {
        val cutoff = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis; add(Calendar.DAY_OF_MONTH, -FoodLogRetention.RETENTION_DAYS) }.timeInMillis
        dao.deleteBefore(cutoff)
    }
}
