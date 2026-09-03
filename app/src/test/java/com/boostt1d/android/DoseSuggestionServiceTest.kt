package com.boostt1d.android

import com.boostt1d.android.data.Config
import com.boostt1d.android.data.FoodLogSnapshot
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.engine.AdjustmentSuggestion
import com.boostt1d.android.engine.AdjustmentType
import com.boostt1d.android.engine.DoseSuggestionService
import com.boostt1d.android.engine.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * DoseSuggestionService has no iOS test file. These pin the slot arithmetic, the profile
 * lookups, and — above all — that a hidden-doses build never composes a dose instruction.
 */
class DoseSuggestionServiceTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val base: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 2, 0, 0, 0) }.timeInMillis
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply { timeInMillis = base; add(Calendar.DAY_OF_MONTH, day); add(Calendar.MINUTE, hour * 60 + minute) }.timeInMillis

    private val profile = NightscoutProfileDocument(
        id = null, defaultProfile = "Default",
        store = mapOf(
            "Default" to ProfileStoreEntry(
                units = "mg/dl", dia = 4.0,
                basal = listOf(TimeValue("00:00", 0.8), TimeValue("06:00", 1.2), TimeValue("12:00", 1.0)),
                carbRatio = listOf(TimeValue("00:00", 12.0)),
                sensitivity = listOf(TimeValue("00:00", 50.0), TimeValue("18:00", 40.0)),
                targetLow = emptyList(), targetHigh = emptyList(),
            )
        ),
        mills = null, startDate = null, createdAt = null, units = "mg/dl",
    )

    private fun entries(days: Int = 7, value: (Int, Int) -> Double): List<NightscoutGlucoseEntry> =
        (0 until days).flatMap { day -> (0 until 288).map { step -> NightscoutGlucoseEntry(sgv = value(day, step * 5).toInt(), direction = null, date = at(day, 0, step * 5), device = "test") } }

    @Test
    fun `the dose-hiding flag is on, and stays on`() {
        assertTrue(Config.HIDE_DOSE_RECOMMENDATIONS)
    }

    @Test
    fun `hours map to twelve two-hour slots`() {
        assertEquals("00:00-02:00", DoseSuggestionService.getTimeSlot(0))
        assertEquals("00:00-02:00", DoseSuggestionService.getTimeSlot(1))
        assertEquals("02:00-04:00", DoseSuggestionService.getTimeSlot(2))
        assertEquals("12:00-14:00", DoseSuggestionService.getTimeSlot(13))
        assertEquals("22:00-24:00", DoseSuggestionService.getTimeSlot(22))
        assertEquals("22:00-24:00", DoseSuggestionService.getTimeSlot(23))
    }

    @Test
    fun `profile lookups anchor to the slot's start hour and return null without a profile`() {
        assertNull(DoseSuggestionService.profileBasal("06:00-08:00", null))
        assertEquals(0.8, DoseSuggestionService.profileBasal("00:00-02:00", profile)!!, 0.0)
        assertEquals(1.2, DoseSuggestionService.profileBasal("06:00-08:00", profile)!!, 0.0)
        assertEquals(1.2, DoseSuggestionService.profileBasal("10:00-12:00", profile)!!, 0.0)
        assertEquals(1.0, DoseSuggestionService.profileBasal("12:00–14:00", profile)!!, 0.0)
        assertEquals(40.0, DoseSuggestionService.profileISF("18:00-20:00", profile)!!, 0.0)
        assertEquals(12.0, DoseSuggestionService.profileCarbRatio("06:00-08:00", profile)!!, 0.0)
        // A descriptive slot has no hour to anchor to.
        assertNull(DoseSuggestionService.profileBasal("Meal Times", profile))
    }

    @Test
    fun `the internal lookups fall back to placeholders, which is why they are never shown`() {
        assertEquals(1.0, DoseSuggestionService.getBasalValueForTimeSlot("06:00-08:00", null), 0.0)
        assertEquals(50.0, DoseSuggestionService.getISFValueForTimeSlot("06:00-08:00", null), 0.0)
        assertEquals(1.2, DoseSuggestionService.getBasalValueForTimeSlot("08:00-10:00", profile), 0.0)
        assertEquals(40.0, DoseSuggestionService.getISFValueForTimeSlot("20:00-22:00", profile), 0.0)
    }

    @Test
    fun `time slots are summarised as average and range shares`() {
        val analysis = DoseSuggestionService.analyzeTimeSlots(
            entries(days = 1) { _, minute -> if (minute < 120) 200.0 else if (minute < 240) 60.0 else 120.0 }, 70.0, 180.0, zone,
        )

        assertEquals(12, analysis.size)
        val first = analysis.getValue("00:00-02:00")
        assertEquals(24, first.dataPoints)
        assertEquals(200.0, first.averageGlucose, 0.0)
        assertEquals(100.0, first.timeAboveRange, 0.0)
        val second = analysis.getValue("02:00-04:00")
        assertEquals(100.0, second.timeBelowRange, 0.0)
        assertEquals(100.0, analysis.getValue("04:00-06:00").timeInRange, 0.0)
    }

    @Test
    fun `correction effectiveness reads the drop two to three hours after the dose`() {
        // 2 U at 14:00; 250 up to and including the dose minute (the "before" window is
        // inclusive), 130 from 16:00 on → 60 mg/dL per unit.
        val corrections = (0 until 3).map { day -> NightscoutTreatment(eventType = "Correction Bolus", mills = at(day, 14), insulin = 2.0) }
        val glucose = entries(days = 3) { _, minute -> if (minute <= 14 * 60) 250.0 else if (minute < 16 * 60) 190.0 else 130.0 }

        val effectiveness = DoseSuggestionService.analyzeCorrectionEffectiveness(glucose, corrections, zone)

        val slot = effectiveness.getValue("14:00-16:00")
        assertEquals(3, slot.correctionCount)
        assertEquals(6.0, slot.totalInsulin, 0.0)
        assertEquals(60.0, slot.avgActualISF, 0.001)
        // A plain "Bolus" with no carbs counts; a meal bolus does not.
        val mixed = listOf(
            NightscoutTreatment(eventType = "Bolus", mills = at(0, 14), insulin = 1.0),
            NightscoutTreatment(eventType = "Meal Bolus", mills = at(1, 14), insulin = 1.0, carbs = 30.0),
        )
        assertEquals(1, DoseSuggestionService.analyzeCorrectionEffectiveness(glucose, mixed, zone).getValue("14:00-16:00").correctionCount)
    }

    @Test
    fun `meal times come from carb events and the food log, deduplicated within fifteen minutes`() {
        val treatments = listOf(
            NightscoutTreatment(eventType = "Meal Bolus", mills = at(0, 12), carbs = 40.0),
            NightscoutTreatment(eventType = "Snack Bolus", mills = at(0, 16)), // carb-typed, no gram count
            NightscoutTreatment(eventType = "Correction Bolus", mills = at(0, 20), insulin = 1.0),
            NightscoutTreatment(eventType = "Meal Bolus", carbs = 40.0), // no timestamp
        )
        val food = listOf(
            FoodLogSnapshot(id = "a", recordedAtMillis = at(0, 12, 10), descriptionText = "same lunch", carbsGrams = 40.0, insulinUnits = null, fatGrams = null, proteinGrams = null, fiberGrams = null, notes = null),
            FoodLogSnapshot(id = "b", recordedAtMillis = at(0, 8), descriptionText = "breakfast", carbsGrams = 30.0, insulinUnits = null, fatGrams = null, proteinGrams = null, fiberGrams = null, notes = null),
        )

        assertEquals(listOf(at(0, 8), at(0, 12), at(0, 16)), DoseSuggestionService.mealTimesForTimingAnalysis(treatments, food))
    }

    @Test
    fun `post-meal spikes above 180 within the second hour call for a pre-bolus once they pass thirty percent`() {
        val meals = (0 until 5).map { day -> NightscoutTreatment(eventType = "Meal Bolus", mills = at(day, 12), carbs = 50.0) }
        // Days 0 and 1 spike to 220 between 13:00 and 14:00; the rest stay at 150.
        val glucose = entries(days = 5) { day, minute -> if (day < 2 && minute in 13 * 60..14 * 60) 220.0 else 150.0 }

        val timing = DoseSuggestionService.analyzeInsulinTiming(glucose, meals)

        assertEquals(5, timing.totalMeals)
        assertEquals(2, timing.postMealSpikes)
        assertEquals(0.4, timing.spikeRate, 0.001)
        assertTrue(timing.needsPreBolus)
    }

    @Test
    fun `consecutive slots with the same adjustment merge into one window`() {
        fun s(slot: String, type: AdjustmentType = AdjustmentType.BASAL_RATE, current: Double = 1.0, suggested: Double = 1.1) =
            AdjustmentSuggestion("id-$slot", type, slot, current, suggested, Priority.LOW, "r")

        val combined = DoseSuggestionService.combineConsecutiveSuggestions(listOf(
            s("02:00-04:00"), s("00:00-02:00"), s("04:00-06:00"), // one run
            s("10:00-12:00"),                                     // alone
            s("12:00-14:00", suggested = 1.2),                    // different value: not merged
        ))

        val labels = combined.map { it.timeSlot }.toSet()
        assertEquals(setOf("00:00-06:00", "10:00-12:00", "12:00-14:00"), labels)
        val merged = combined.first { it.timeSlot == "00:00-06:00" }
        assertTrue(merged.reasoning.startsWith("Consistent pattern across 3 consecutive 2-hour periods."))
    }

    @Test
    fun `a sustained high produces a basal insight worded for a care team, never a dose`() {
        // 240 all night, 120 the rest of the day.
        val glucose = entries { _, minute -> if (minute < 6 * 60) 240.0 else 120.0 }

        val suggestions = DoseSuggestionService.generateDoseAdjustments(glucose, emptyList(), 70.0, 180.0, profile, timeZone = zone)

        val basal = suggestions.filter { it.type == AdjustmentType.BASAL_RATE }
        assertEquals(1, basal.size)
        val overnight = basal.single()
        assertEquals("00:00-06:00", overnight.timeSlot)
        assertEquals(Priority.HIGH, overnight.priority)
        assertEquals(0.8, overnight.currentValue, 0.0)
        // 60 over target reads as 6%, but the step is clamped to the 10–15% band.
        assertEquals(0.8 * 1.10, overnight.suggestedValue, 0.001)
        assertTrue(overnight.reasoning.contains("💡 INSIGHT"))
        assertTrue(overnight.reasoning.contains("healthcare provider"))
        assertFalse(overnight.reasoning.contains("RECOMMENDATION"))
        assertFalse(overnight.reasoning.contains("Increase basal rate from"))
        // Overnight TIR is 0%, so the TIR-based correction-factor insight fires there too.
        assertTrue(suggestions.any { it.type == AdjustmentType.CORRECTION_FACTOR && it.timeSlot == "00:00-06:00" })
    }

    @Test
    fun `a loop raises the bar for a basal suggestion`() {
        // 200 overnight: 20 over target — enough open-loop (15), not enough on a loop (25).
        val glucose = entries { _, minute -> if (minute < 6 * 60) 200.0 else 120.0 }
        val loop = (0 until 7).flatMap { day -> (0 until 24).map { hour -> NightscoutTreatment(eventType = "Temp Basal", mills = at(day, hour), duration = 60, absolute = 1.0, enteredBy = "loop") } }

        val open = DoseSuggestionService.generateDoseAdjustments(glucose, emptyList(), 70.0, 180.0, profile, timeZone = zone)
        val looped = DoseSuggestionService.generateDoseAdjustments(glucose, loop, 70.0, 180.0, profile, timeZone = zone)

        assertTrue(open.any { it.type == AdjustmentType.BASAL_RATE })
        assertFalse(looped.any { it.type == AdjustmentType.BASAL_RATE })
    }
}
