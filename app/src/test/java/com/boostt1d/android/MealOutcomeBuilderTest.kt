package com.boostt1d.android

import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.engine.MealOutcomeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * The meal table is what the AI review reasons from, so what it does and doesn't contain is
 * a correctness question, not a formatting one.
 *
 * Ported from the iOS MealOutcomeBuilderTests, fixtures included.
 */
class MealOutcomeBuilderTest {

    private val low = 70.0
    private val high = 180.0
    private val zone: TimeZone = TimeZone.getDefault()

    /** Midnight, 2 June 2025, local — a Monday. */
    private val base: Long = Calendar.getInstance(zone).apply {
        clear(); set(2025, Calendar.JUNE, 2, 0, 0, 0)
    }.timeInMillis

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    // MARK: - Carbs without a food log

    @Test
    fun `carb entries in the event log produce meals with no food log at all`() {
        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 3) { _, m ->
                when {
                    m < 720 -> 120.0
                    m < 840 -> 120 + 100 * (m - 720) / 120.0
                    else -> 200.0
                }
            },
            treatments = (0 until 3).map { treatment(day = it, hour = 12, insulin = 5.0, carbs = 55.0) },
            foodLogEntries = emptyList(),
            lowGlucose = low, timeZone = zone,
        )

        assertEquals(3, outcomes.size)
        val meal = outcomes.first()
        assertEquals(55.0, meal.carbs, 0.0)
        assertEquals(5.0, meal.insulin, 0.0)
        assertNull(meal.label)                 // no description available, and that is fine
        assertFalse(meal.hasComposition)
        assertTrue(abs(meal.baseline - 120) < 1)
        assertTrue(abs(meal.peakRise - 100) < 6)
        assertTrue(abs((meal.delta4h ?: 0.0) - 80) < 6)
    }

    @Test
    fun `carbs below the meal floor are not treated as meals`() {
        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 2) { _, _ -> 120.0 },
            treatments = (0 until 2).map { treatment(day = it, hour = 12, insulin = 0.0, carbs = 8.0) },
            foodLogEntries = emptyList(),
            lowGlucose = low, timeZone = zone,
        )

        assertTrue(outcomes.isEmpty())
    }

    // MARK: - Food log enrichment

    @Test
    fun `a food log entry enriches the matching carb entry instead of duplicating it`() {
        val mealTime = base + (12 * 60 + 5) * minute
        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 1) { _, _ -> 130.0 },
            treatments = listOf(treatment(day = 0, hour = 12, insulin = 6.0, carbs = 70.0)),
            foodLogEntries = listOf(food(mealTime, "Pepperoni pizza", carbs = 70.0, fat = 32.0, protein = 28.0)),
            lowGlucose = low, timeZone = zone,
        )

        assertEquals(1, outcomes.size)
        val meal = outcomes.first()
        assertEquals("pepperoni pizza", meal.label)
        assertEquals(32.0, meal.fat!!, 0.0)
        assertEquals(28.0, meal.protein!!, 0.0)
        // Carbs stay with the event log, which is the record the pump actually acted on.
        assertEquals(70.0, meal.carbs, 0.0)
    }

    @Test
    fun `a food log entry with no carb entry nearby still becomes a meal`() {
        val mealTime = base + 13 * 60 * minute
        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 1) { _, _ -> 140.0 },
            treatments = emptyList(),
            foodLogEntries = listOf(food(mealTime, "Chicken salad", carbs = 25.0, fat = 10.0, protein = 30.0)),
            lowGlucose = low, timeZone = zone,
        )

        assertEquals(1, outcomes.size)
        assertEquals(25.0, outcomes.first().carbs, 0.0)
        assertEquals(0.0, outcomes.first().insulin, 0.0)
    }

    // MARK: - Repeat foods

    @Test
    fun `descriptions of the same food group together across wordings`() {
        val wordings = listOf("Pepperoni pizza", "2 slices of pizza", "Pizza and salad", "Leftover pizza")
        val treatments = wordings.indices.map { treatment(day = it, hour = 18, insulin = 6.0, carbs = 70.0) }
        val foods = wordings.mapIndexed { index, wording ->
            food(base + index * day + 18 * 60 * minute, wording, carbs = 70.0, fat = 30.0, protein = 25.0)
        }

        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 5) { _, m -> if (m < 1080) 120.0 else 190.0 },
            treatments = treatments, foodLogEntries = foods,
            lowGlucose = low, timeZone = zone,
        )
        val groups = MealOutcomeBuilder.repeatFoods(outcomes)

        val pizza = groups.first { it.label == "pizza" }
        assertEquals(4, pizza.count)
        assertEquals(70.0, pizza.medianCarbs, 0.0)
        assertEquals(30.0, pizza.medianFat!!, 0.0)
        // Each meal belongs to one group only, so medians can't double-count.
        assertEquals(4, groups.sumOf { it.count })
    }

    // MARK: - Honesty of the row

    @Test
    fun `a second meal inside the window marks the row interrupted rather than dropping it`() {
        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 1) { _, _ -> 150.0 },
            treatments = listOf(
                treatment(day = 0, hour = 12, insulin = 5.0, carbs = 60.0),
                treatment(day = 0, hour = 14, insulin = 3.0, carbs = 30.0),
            ),
            foodLogEntries = emptyList(),
            lowGlucose = low, timeZone = zone,
        )

        assertEquals(2, outcomes.size)
        assertTrue(outcomes[0].interrupted)
    }

    @Test
    fun `a missing four-hour reading is null, never zero`() {
        // Readings stop two hours after the meal.
        val mealMinute = 12 * 60
        val outcomes = MealOutcomeBuilder.mealOutcomes(
            glucoseEntries = entries(days = 1) { _, m -> if (m <= mealMinute + 120) 150.0 else null },
            treatments = listOf(treatment(day = 0, hour = 12, insulin = 5.0, carbs = 60.0)),
            foodLogEntries = emptyList(),
            lowGlucose = low, timeZone = zone,
        )

        assertEquals(1, outcomes.size)
        assertNull(outcomes.first().delta4h)
    }

    // MARK: - Weekdays and prompt text

    @Test
    fun `weekday rows carry their own averages and meal counts`() {
        val days = MealOutcomeBuilder.weekdayOutcomes(
            glucoseEntries = entries(days = 7) { d, _ -> if (d == 2) 200.0 else 120.0 },
            treatments = (0 until 7).map { treatment(day = it, hour = 12, insulin = 5.0, carbs = 60.0) },
            lowGlucose = low, highGlucose = high, timeZone = zone,
        )

        assertEquals(7, days.size)
        assertTrue(days.all { it.mealCount == 1 })
        val elevated = days.maxByOrNull { it.averageGlucose }!!
        assertTrue(abs(elevated.averageGlucose - 200) < 1)
    }

    @Test
    fun `prompt text names the meal, its composition and its outcome`() {
        val mealTime = base + 18 * 60 * minute
        val text = MealOutcomeBuilder.promptContext(
            glucoseEntries = entries(days = 1) { _, _ -> 150.0 },
            treatments = listOf(treatment(day = 0, hour = 18, insulin = 6.0, carbs = 70.0)),
            foodLogEntries = listOf(food(mealTime, "Pepperoni pizza", carbs = 70.0, fat = 32.0, protein = 28.0)),
            lowGlucose = low, highGlucose = high, timeZone = zone,
        )

        assertTrue(text.contains("MEAL OUTCOMES"))
        assertTrue(text.contains("pepperoni pizza"))
        assertTrue(text.contains("fat 32g"))
        assertTrue(text.contains("BY DAY OF WEEK"))
    }

    @Test
    fun `with no meals at all the context says so instead of going silent`() {
        val text = MealOutcomeBuilder.promptContext(
            glucoseEntries = entries(days = 2) { _, _ -> 120.0 },
            treatments = emptyList(), foodLogEntries = emptyList(),
            lowGlucose = low, highGlucose = high, timeZone = zone,
        )

        assertTrue(text.contains("No carb entries"))
    }

    @Test
    fun `a treatment dated only by created_at is still placed in time`() {
        // Downloaded rows are normalised at parse time; this covers the raw shape anyway.
        val fromCreatedAt = NightscoutTreatment(eventType = "Meal Bolus", carbs = 60.0, createdAt = "2025-06-02T12:00:00.000Z")
        assertTrue(MealOutcomeBuilder.treatmentMillis(fromCreatedAt) > 0L)
        assertEquals(0L, MealOutcomeBuilder.treatmentMillis(NightscoutTreatment(eventType = "Note")))
    }

    // MARK: - Fixtures

    /**
     * Five-minute readings. Returning null from [value] leaves a gap, which is how a real
     * sensor outage looks.
     */
    private fun entries(days: Int, value: (Int, Int) -> Double?): List<NightscoutGlucoseEntry> {
        val result = mutableListOf<NightscoutGlucoseEntry>()
        for (d in 0 until days) {
            val dayStart = base + d * day
            for (step in 0 until 288) {
                val m = step * 5
                val sgv = value(d, m) ?: continue
                result.add(
                    NightscoutGlucoseEntry(
                        sgv = Math.round(sgv).toInt(),
                        direction = null,
                        date = dayStart + m * minute,
                        device = "test",
                    )
                )
            }
        }
        return result
    }

    private fun treatment(day: Int, hour: Int, insulin: Double, carbs: Double): NightscoutTreatment =
        NightscoutTreatment(
            eventType = "Meal Bolus",
            mills = base + day * this.day + hour * 60 * minute,
            enteredBy = "test",
            insulin = insulin.takeIf { it > 0 },
            carbs = carbs,
        )

    private fun food(at: Long, text: String, carbs: Double, fat: Double, protein: Double): FoodLogSnapshot =
        FoodLogSnapshot(
            id = "f-$at", recordedAtMillis = at, descriptionText = text,
            carbsGrams = carbs, insulinUnits = null, fatGrams = fat, proteinGrams = protein,
            fiberGrams = null, notes = null,
        )
}
