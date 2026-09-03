package com.boostt1d.android.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FoodLogSource(val rawValue: String, val displayName: String) {
    @SerialName("photo_analysis") PHOTO_ANALYSIS("photo_analysis", "From photo analysis"),
    @SerialName("manual") MANUAL("manual", "Manual entry"),
    /** Carbs imported from a Nightscout / Event Log treatment on download. */
    @SerialName("nightscout_event") NIGHTSCOUT_EVENT("nightscout_event", "From Event Log download");

    companion object {
        fun from(raw: String?): FoodLogSource = entries.firstOrNull { it.rawValue == raw } ?: MANUAL
    }
}

object FoodLogRetention {
    const val RETENTION_DAYS = 30
}

/** What the backend's food analysis returns — the same snake_case keys the iOS decoder reads. */
@Serializable
data class FoodAnalysis(
    val description: String? = null,
    @SerialName("carbs_grams") val carbsGrams: Double? = null,
    @SerialName("calories_kcal") val caloriesKcal: Double? = null,
    @SerialName("fat_grams") val fatGrams: Double? = null,
    @SerialName("protein_grams") val proteinGrams: Double? = null,
    @SerialName("fiber_grams") val fiberGrams: Double? = null,
    val confidence: String? = null,
    val notes: String? = null,
) {
    val descriptionText: String get() = description ?: "Food items detected"
    val carbsValue: Double get() = carbsGrams ?: 0.0
    val confidenceLevel: String get() = confidence ?: "Medium"
    val notesText: String get() = notes ?: ""
}

sealed class CarbEstimationException(message: String) : Exception(message) {
    class ImageProcessingFailed : CarbEstimationException("Failed to process the image. Please try again.")
    class NoFoodDetected : CarbEstimationException("No food was detected in the image. Please try a clearer photo.")
    class Unknown : CarbEstimationException("An unknown error occurred. Please try again.")
}

/**
 * A food diary row. The thumbnail rides in the row as a small JPEG (≤80 KB), which is what
 * iOS stores too; decoding it is the list's job, not the table's.
 */
@Entity(tableName = "food_log_entries")
data class FoodLogEntryEntity(
    @PrimaryKey val id: String,
    val recordedAtMillis: Long,
    val descriptionText: String,
    val carbsGrams: Double? = null,
    val caloriesKcal: Double? = null,
    val fatGrams: Double? = null,
    val proteinGrams: Double? = null,
    val fiberGrams: Double? = null,
    val confidence: String? = null,
    val notes: String? = null,
    val thumbnailJpeg: ByteArray? = null,
    val source: String,
    /** The treatment this row was imported from, so an import is idempotent. */
    val linkedTreatmentId: String? = null,
    /** Insulin actually given for this meal, entered by the user (not an AI estimate). */
    val insulinUnits: Double? = null,
) {
    val foodLogSource: FoodLogSource get() = FoodLogSource.from(source)
    val hasLinkedTreatment: Boolean get() = linkedTreatmentId != null

    /** The six scalars the analysis builders read, free of the table. */
    fun toSnapshot(): FoodLogSnapshot = FoodLogSnapshot(
        id = id,
        recordedAtMillis = recordedAtMillis,
        descriptionText = descriptionText,
        carbsGrams = carbsGrams,
        insulinUnits = insulinUnits,
        fatGrams = fatGrams,
        proteinGrams = proteinGrams,
        fiberGrams = fiberGrams,
        notes = notes,
    )
}

@Dao
interface FoodLogDao {
    @Query("SELECT * FROM food_log_entries WHERE recordedAtMillis >= :since ORDER BY recordedAtMillis DESC")
    fun observeSince(since: Long): Flow<List<FoodLogEntryEntity>>

    @Query("SELECT * FROM food_log_entries WHERE recordedAtMillis >= :since ORDER BY recordedAtMillis DESC")
    suspend fun since(since: Long): List<FoodLogEntryEntity>

    @Query("SELECT * FROM food_log_entries WHERE linkedTreatmentId IS NOT NULL AND recordedAtMillis >= :since")
    suspend fun linkedSince(since: Long): List<FoodLogEntryEntity>

    @Query("SELECT * FROM food_log_entries WHERE id = :id")
    suspend fun byId(id: String): FoodLogEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<FoodLogEntryEntity>)

    @Query("DELETE FROM food_log_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM food_log_entries WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM food_log_entries WHERE recordedAtMillis < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM food_log_entries")
    suspend fun deleteAll()
}
