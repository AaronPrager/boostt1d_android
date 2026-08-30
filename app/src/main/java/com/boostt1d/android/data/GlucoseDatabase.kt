package com.boostt1d.android.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A persisted glucose reading.
 *
 * The exact millisecond is the primary key, matching the iOS `@Attribute(.unique)` on
 * `epochMilliseconds`: re-importing the same reading updates the row instead of adding a
 * duplicate. Nearby samples from a different source may both exist and are reconciled at
 * read time, not on insert.
 */
@Entity(tableName = "glucose_readings")
data class GlucoseReadingEntity(
    @PrimaryKey val epochMilliseconds: Long,
    val sgv: Int,
    val direction: String? = null,
    val device: String? = null,
    val noise: Int? = null,
    /** Origin tag (`manual`, `nightscout`, `dexcom`, `libre`). */
    val source: String? = null,
) {
    fun toEntry(): NightscoutGlucoseEntry = NightscoutGlucoseEntry(
        sgv = sgv,
        direction = direction,
        date = epochMilliseconds,
        device = device,
        noise = noise,
    )

    companion object {
        fun from(entry: NightscoutGlucoseEntry, source: String?): GlucoseReadingEntity =
            GlucoseReadingEntity(
                epochMilliseconds = entry.epochMilliseconds,
                sgv = entry.sgv,
                direction = entry.direction,
                device = entry.device,
                noise = entry.noise,
                source = source,
            )
    }
}

@Dao
interface GlucoseReadingDao {

    @Query("SELECT * FROM glucose_readings ORDER BY epochMilliseconds DESC")
    fun observeAll(): Flow<List<GlucoseReadingEntity>>

    @Query(
        "SELECT * FROM glucose_readings WHERE epochMilliseconds BETWEEN :from AND :to " +
            "ORDER BY epochMilliseconds ASC"
    )
    fun observeBetween(from: Long, to: Long): Flow<List<GlucoseReadingEntity>>

    @Query(
        "SELECT * FROM glucose_readings WHERE epochMilliseconds BETWEEN :from AND :to " +
            "ORDER BY epochMilliseconds ASC"
    )
    suspend fun between(from: Long, to: Long): List<GlucoseReadingEntity>

    @Query("SELECT * FROM glucose_readings ORDER BY epochMilliseconds DESC LIMIT 1")
    suspend fun latest(): GlucoseReadingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(readings: List<GlucoseReadingEntity>)

    @Query("DELETE FROM glucose_readings WHERE epochMilliseconds = :epochMilliseconds")
    suspend fun delete(epochMilliseconds: Long)

    /** Trims history beyond the retention window so the database cannot grow without bound. */
    @Query("DELETE FROM glucose_readings WHERE epochMilliseconds < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM glucose_readings")
    suspend fun deleteAll()
}

@Database(entities = [GlucoseReadingEntity::class], version = 1, exportSchema = true)
abstract class BoostDatabase : RoomDatabase() {
    abstract fun glucoseReadingDao(): GlucoseReadingDao

    companion object {
        @Volatile private var instance: BoostDatabase? = null

        fun get(context: Context): BoostDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BoostDatabase::class.java,
                    "boost.db",
                ).build().also { instance = it }
            }
    }
}
