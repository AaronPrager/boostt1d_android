package com.boostt1d.android

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boostt1d.android.data.BoostDatabase
import com.boostt1d.android.data.GlucoseReadingEntity
import com.boostt1d.android.data.GlucoseReadingDao
import com.boostt1d.android.data.GlucoseSourceTag
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Storage behaviour that only shows up against a real SQLite database.
 *
 * The unit tests cover the rules; these cover the parts Room actually decides — that the
 * millisecond primary key really does deduplicate a re-downloaded window, and that a
 * fortnight of CGM data reads back in a reasonable time rather than at whatever size the
 * tests happened to use.
 */
@RunWith(AndroidJUnit4::class)
class GlucoseStorageTest {

    private lateinit var database: BoostDatabase
    private lateinit var dao: GlucoseReadingDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, BoostDatabase::class.java).build()
        dao = database.glucoseReadingDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun reDownloadingAnOverlappingWindowUpdatesRatherThanDuplicates() = runTest {
        val at = 1_700_000_000_000L
        dao.upsert(listOf(GlucoseReadingEntity(at, 120, source = GlucoseSourceTag.NIGHTSCOUT)))
        // The same moment again, as a later sync of an overlapping window would send it.
        dao.upsert(listOf(GlucoseReadingEntity(at, 125, source = GlucoseSourceTag.NIGHTSCOUT)))

        val rows = dao.between(at - 1, at + 1)

        assertEquals(1, rows.size)
        assertEquals(125, rows.single().sgv)
    }

    @Test
    fun aFortnightOfReadingsStoresAndReadsBack() = runTest {
        val start = 1_700_000_000_000L
        val readings = (0 until 4_032).map {
            GlucoseReadingEntity(
                epochMilliseconds = start + it * 5L * 60 * 1000,
                sgv = 100 + (it % 80),
                source = GlucoseSourceTag.NIGHTSCOUT,
            )
        }

        dao.upsert(readings)

        assertEquals(4_032, dao.between(start, start + 15L * 24 * 60 * 60 * 1000).size)
        assertEquals(readings.last().epochMilliseconds, dao.latest()!!.epochMilliseconds)
    }

    @Test
    fun retentionTrimDropsOnlyWhatIsPastTheWindow() = runTest {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        dao.upsert(
            listOf(
                GlucoseReadingEntity(now - 20 * day, 100),
                GlucoseReadingEntity(now - 13 * day, 110),
                GlucoseReadingEntity(now, 120),
            )
        )

        dao.deleteBefore(now - 14 * day)

        val remaining = dao.between(0, now + 1)
        assertEquals(2, remaining.size)
        assertEquals(listOf(110, 120), remaining.map { it.sgv })
    }

    @Test
    fun deletingAReadingRemovesOnlyThatOne() = runTest {
        val at = 1_700_000_000_000L
        dao.upsert(listOf(GlucoseReadingEntity(at, 100), GlucoseReadingEntity(at + 300_000, 110)))

        dao.delete(at)

        assertEquals(1, dao.between(0, at + 400_000).size)
    }

    @Test
    fun anEmptyDatabaseHasNoLatestReading() = runTest {
        assertNull(dao.latest())
    }
}
