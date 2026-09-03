package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.food.MealCurrentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MealCurrentDataTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val noon: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 12, 30, 0) }.timeInMillis
    private val therapy = TherapyProfile(
        basal = listOf(TimeValue("00:00", 1.0)),
        carbRatio = listOf(TimeValue("00:00", 12.0), TimeValue("11:00", 10.0), TimeValue("18:00", 12.0)),
        sensitivity = listOf(TimeValue("00:00", 50.0)),
        targetHigh = listOf(TimeValue("00:00", 120.0)),
    )

    @Test
    fun `the inputs are read from the profile segment in effect and a fresh feed`() {
        val latest = NightscoutGlucoseEntry(sgv = 160, date = noon - 5 * 60_000L, device = "t")
        val data = MealCurrentData.build(45.0, latest, OnBoard(insulinUnits = 1.2, carbsGrams = 8.0, asOfMillis = noon), therapy, hasTherapyFeed = true, nowMillis = noon, timeZone = zone)

        assertEquals(10.0, data.carbRatio!!, 0.0)
        assertEquals("11:00", data.carbRatioTime)
        assertEquals(160, data.currentGlucoseMgdl)
        assertEquals(120, data.targetGlucoseMgdl)
        assertEquals(50.0, data.insulinSensitivity!!, 0.0)
        assertEquals(1.2, data.iob, 0.0); assertEquals(8.0, data.cob, 0.0)
        assertFalse(data.iobDataStale); assertFalse(data.needsProfile)
    }

    @Test
    fun `a stale CGM connection zeroes the feed's IOB and COB and says so`() {
        val old = NightscoutGlucoseEntry(sgv = 160, date = noon - 40 * 60_000L, device = "t")
        val data = MealCurrentData.build(45.0, old, OnBoard(insulinUnits = 1.2, carbsGrams = 8.0, asOfMillis = noon), therapy, hasTherapyFeed = true, nowMillis = noon, timeZone = zone)
        assertTrue(data.iobDataStale); assertEquals(0.0, data.iob, 0.0); assertEquals(0.0, data.cob, 0.0)

        // Without a therapy feed there is nothing to distrust.
        val manual = MealCurrentData.build(45.0, old, OnBoard.none, therapy, hasTherapyFeed = false, nowMillis = noon, timeZone = zone)
        assertFalse(manual.iobDataStale)
    }

    @Test
    fun `a missing profile or carb ratio asks for the setting instead of inventing one`() {
        assertEquals("No insulin doses found on this device", MealCurrentData.build(45.0, null, OnBoard.none, TherapyProfile(), false, noon, zone).setupMessage)
        val noRatio = MealCurrentData.build(45.0, null, OnBoard.none, therapy.copy(carbRatio = emptyList()), false, noon, zone)
        assertEquals("Your insulin doses are missing a carb ratio", noRatio.setupMessage)
        assertNull(noRatio.carbRatio)
    }
}
