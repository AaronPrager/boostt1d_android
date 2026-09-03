package com.boostt1d.android

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.data.toDocument
import com.boostt1d.android.engine.DailyTherapyReviewSource
import com.boostt1d.android.engine.InMemoryAnalysisCacheStore
import com.boostt1d.android.engine.InMemoryTherapySnapshotStore
import com.boostt1d.android.engine.PatternService
import com.boostt1d.android.engine.TherapyChangeDetector
import com.boostt1d.android.engine.TherapyGlucoseFormatter
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.insights.WhatHappenedReportLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The one load every page paints from, end to end over a synthetic fortnight. */
class WhatHappenedReportLoaderTest {

    private val zone: TimeZone = TimeZone.getDefault()
    /** Midday on a Monday; the analysis window is the seven complete days before it. */
    private val now: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 12, 0, 0) }.timeInMillis

    private fun at(daysBack: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_MONTH, -daysBack); add(Calendar.MINUTE, minute) }.timeInMillis

    /** Fourteen days: 120 at midnight climbing to 200 by 06:00, flat 120 the rest of the day. */
    private fun entries(): List<NightscoutGlucoseEntry> = (14 downTo 0).flatMap { back ->
        (0 until 288).mapNotNull { step ->
            val minute = step * 5
            val time = at(back, minute)
            if (time > now) return@mapNotNull null
            val hour = minute / 60.0
            val value = when { hour < 6 -> 120 + 80 * hour / 6; hour < 7 -> 200 - 80 * (hour - 6); else -> 120.0 }
            NightscoutGlucoseEntry(sgv = value.toInt(), direction = null, date = time, device = "test")
        }
    }

    private fun meals(): List<NightscoutTreatment> = (14 downTo 1).flatMap { back ->
        listOf(
            NightscoutTreatment(eventType = "Meal Bolus", mills = at(back, 12 * 60), insulin = 4.0, carbs = 50.0, enteredBy = "test"),
            NightscoutTreatment(eventType = "Meal Bolus", mills = at(back, 18 * 60 + 30), insulin = 5.0, carbs = 60.0, enteredBy = "test"),
        )
    }

    private val profile = TherapyProfile(
        basal = listOf(TimeValue("00:00", 1.0)), carbRatio = listOf(TimeValue("00:00", 15.0)),
        sensitivity = listOf(TimeValue("00:00", 50.0)), dia = 4.0, updatedAtMillis = at(20, 0),
    ).toDocument()

    private fun loader(store: InMemoryAnalysisCacheStore = InMemoryAnalysisCacheStore()) = WhatHappenedReportLoader(
        patternService = PatternService(timeZone = zone),
        detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone),
        analysisCache = WhatHappenedAnalysisCache(store),
        timeZone = zone,
    )

    @Test
    fun `a fortnight of data produces every page of the report`() = runBlocking {
        val snapshot = loader().build(entries(), meals(), profile, 70.0, 180.0, InsulinTherapyType.UNSPECIFIED, TherapyGlucoseFormatter.mgdl, now)

        assertTrue(snapshot.report.hasEnoughCurrentData)
        assertNotNull(snapshot.report.previous)
        assertEquals(7, snapshot.dailyDays.size)
        assertTrue(snapshot.patterns.any { it.title == "Overnight glucose rise" })
        assertNotNull(snapshot.coverageLabel)
        assertTrue(snapshot.review.findings.any { it.parameter == TherapyParameter.BASAL })
        assertEquals(DailyTherapyReviewSource.FORMULA_ONLY, snapshot.dailyTherapyReview.source)
        assertTrue(snapshot.dailyTherapyReview.proposals.isNotEmpty())
        assertNull(snapshot.dailyTherapyReview.statusNote)
        // The first save of a profile starts the change-watching clock.
        assertNotNull(snapshot.watchingSinceMillis)
        assertTrue(snapshot.outcomes.isEmpty())
        Unit
    }

    @Test
    fun `a second load of the same week reuses the memoised review and persists the snapshot`() = runBlocking {
        val store = InMemoryAnalysisCacheStore()
        val first = loader(store)
        val a = first.build(entries(), meals(), profile, 70.0, 180.0, InsulinTherapyType.UNSPECIFIED, TherapyGlucoseFormatter.mgdl, now)
        val b = first.build(entries(), meals(), profile, 70.0, 180.0, InsulinTherapyType.UNSPECIFIED, TherapyGlucoseFormatter.mgdl, now + 3_600_000)

        // Same object: the bundle came back from the cache rather than being rebuilt.
        assertSame(a.review, b.review)
        assertNotNull(store.contents)

        // A cold start restores what the last load painted.
        assertEquals(a.review.findings.size, WhatHappenedAnalysisCache(store).snapshot?.review?.findings?.size)
        Unit
    }
}
