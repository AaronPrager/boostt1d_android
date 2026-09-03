package com.boostt1d.android

import com.boostt1d.android.data.FoodLogImport
import com.boostt1d.android.data.FoodLogSource
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.NightscoutTreatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the iOS EventLogFoodLogImportTests, plus the import planner's own rules. */
class FoodLogImportTest {

    private fun sample(carbs: Double?, insulin: Double?, eventType: String, notes: String? = null, enteredBy: String = "nightscout", id: String = java.util.UUID.randomUUID().toString()) =
        NightscoutTreatment(
            mongoId = id, eventType = eventType, mills = 1_750_000_000_000L, enteredBy = enteredBy, insulin = insulin, carbs = carbs, notes = notes,
            duration = if (eventType == "Temp Basal") 30 else null, rate = if (eventType == "Temp Basal") 1.0 else null,
        )

    @Test
    fun `carb-only treatments leave the Event Log and are Food Log candidates`() {
        val carb = sample(carbs = 40.0, insulin = null, eventType = "Carb")
        assertTrue(carb.recordsCarbsForFoodLog)
        assertTrue(carb.isCarbOnlyEventLogRow)

        val meal = sample(carbs = 45.0, insulin = 4.5, eventType = "Meal Bolus")
        assertTrue(meal.recordsCarbsForFoodLog)
        assertFalse(meal.isCarbOnlyEventLogRow)

        val bolus = sample(carbs = null, insulin = 2.0, eventType = "Correction Bolus")
        assertFalse(bolus.recordsCarbsForFoodLog)
        assertFalse(bolus.isCarbOnlyEventLogRow)
    }

    @Test
    fun `temp basal and exercise are not treated as carb-only Food Log rows`() {
        val temp = sample(carbs = 10.0, insulin = null, eventType = "Temp Basal")
        assertTrue(temp.recordsCarbsForFoodLog)
        assertFalse(temp.isCarbOnlyEventLogRow)

        val exercise = sample(carbs = 15.0, insulin = null, eventType = "Exercise")
        assertFalse(exercise.isCarbOnlyEventLogRow)
    }

    @Test
    fun `the description prefers the note, then a meaningful event type, then the grams`() {
        assertEquals("pizza night", FoodLogImport.description(sample(40.0, null, "Carb", notes = " pizza night ")))
        assertEquals("Meal Bolus", FoodLogImport.description(sample(40.0, 4.0, "Meal Bolus")))
        assertEquals("40g carbs", FoodLogImport.description(sample(40.0, null, "Carb")))
        assertEquals("40g carbs", FoodLogImport.description(sample(40.0, null, "Carb Correction")))
        // The note rides along only when it says something the description does not.
        assertNull(FoodLogImport.notes(sample(40.0, null, "Carb", notes = "pizza night"), "pizza night"))
        assertEquals("pizza night", FoodLogImport.notes(sample(40.0, 4.0, "Meal Bolus", notes = "pizza night"), "Meal Bolus"))
    }

    @Test
    fun `manual Event Log entries import as manual, downloads as Nightscout events`() {
        assertEquals(FoodLogSource.MANUAL, FoodLogImport.source(sample(40.0, null, "Carb", enteredBy = LogRepository.ENTERED_BY_MANUAL)))
        assertEquals(FoodLogSource.NIGHTSCOUT_EVENT, FoodLogImport.source(sample(40.0, null, "Carb", enteredBy = "loop")))
    }

    @Test
    fun `the import plan is idempotent across cache keys, legacy ids and repeats`() {
        val a = sample(40.0, 4.0, "Meal Bolus", id = "a")
        val b = sample(20.0, null, "Carb", id = "b")
        val noCarbs = sample(null, 2.0, "Correction Bolus", id = "c")

        val first = FoodLogImport.plan(listOf(a, b, noCarbs, a), existingLinks = emptySet())
        assertEquals(2, first.size)
        assertEquals(setOf(a.cacheKey, b.cacheKey), first.map { it.linkedTreatmentId }.toSet())
        assertEquals(4.0, first.first { it.linkedTreatmentId == a.cacheKey }.insulinUnits!!, 0.0)
        assertNull(first.first { it.linkedTreatmentId == b.cacheKey }.insulinUnits)

        // Already linked under the cache key, or under a legacy raw id: nothing new.
        assertTrue(FoodLogImport.plan(listOf(a, b), existingLinks = setOf(a.cacheKey, "b")).isEmpty())
    }
}
