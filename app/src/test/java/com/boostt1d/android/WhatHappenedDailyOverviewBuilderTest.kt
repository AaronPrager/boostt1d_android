package com.boostt1d.android

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.engine.WhatHappenedDailyOverviewBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Days tab has no dedicated test file on iOS; these pin the behaviour the port was read
 * from, so a later change here is a decision rather than drift.
 */
class WhatHappenedDailyOverviewBuilderTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val low = 70.0
    private val high = 180.0

    private val weekEnd: Long = at(2025, Calendar.JUNE, 16, 12, 0)
    private val endDay = TodaySoFarBuilder.startOfDay(weekEnd, zone)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month, day, hour, minute, 0) }.timeInMillis

    private fun daysBefore(day: Long, n: Int): Long =
        Calendar.getInstance(zone).apply { timeInMillis = day; add(Calendar.DAY_OF_MONTH, -n) }.timeInMillis

    private fun time(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = zone }.format(Date(millis))

    private fun build(
        entries: List<NightscoutGlucoseEntry> = flatWeek(),
        treatments: List<NightscoutTreatment> = emptyList(),
        profile: NightscoutProfileDocument? = null,
        therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
        nowMillis: Long = weekEnd,
    ) = WhatHappenedDailyOverviewBuilder.build(
        entries, treatments, low, high, weekEnd,
        profile = profile, therapyType = therapyType, nowMillis = nowMillis, timeZone = zone,
    )

    // MARK: - Shape

    @Test
    fun `exactly seven calendar days, newest first, ending on the report's end day`() {
        val days = build()

        assertEquals(7, days.size)
        assertEquals(endDay, days.first().dayStartMillis)
        assertEquals(daysBefore(endDay, 6), days.last().dayStartMillis)
        assertEquals(SimpleDateFormat("EEE", Locale.getDefault()).format(Date(endDay)), days.first().weekdayLabel)
        assertEquals(SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(endDay)), days.first().dateLabel)
    }

    @Test
    fun `a day with nothing in it is still listed, with no numbers`() {
        // Readings on six of the seven days: the oldest is empty.
        val days = build(entries = flatWeek(skipOldest = true))

        val empty = days.last()
        assertEquals(0, empty.readingCount)
        assertNull(empty.averageGlucoseMgdL)
        assertNull(empty.timeInRangePercent)
        assertFalse(empty.hasAnyActivity)
        assertTrue(days.first().hasAnyActivity)
    }

    // MARK: - Glucose

    @Test
    fun `a flat day reads as its own statistics`() {
        val today = build().first()

        assertEquals(288, today.readingCount)
        assertEquals(120.0, today.averageGlucoseMgdL!!, 0.001)
        assertEquals(120.0, today.minGlucoseMgdL!!, 0.001)
        assertEquals(120.0, today.maxGlucoseMgdL!!, 0.001)
        assertEquals(100.0, today.timeInRangePercent!!, 0.001)
        assertEquals(0, today.lowReadingCount)
    }

    /** One marker per contiguous stretch, at the stretch's start, labelled with its lowest reading. */
    @Test
    fun `lows are compacted into episodes`() {
        val day = daysBefore(endDay, 2)
        val first = day + 8 * 3_600_000L
        val second = day + 9 * 3_600_000L
        val dips = mapOf(
            first to 60, first + 300_000 to 58, first + 600_000 to 55, first + 900_000 to 59, first + 1_200_000 to 62,
            second to 65, second + 300_000 to 64,
        )
        val entries = flatWeek().map { e -> dips[e.epochMilliseconds]?.let { e.copy(sgv = it) } ?: e }

        val overview = build(entries = entries)[2]

        assertEquals(2, overview.lowMoments.size)
        assertEquals(first, overview.lowMoments[0].timeMillis)
        assertEquals("Low · 55 mg/dL", overview.lowMoments[0].detail)
        assertEquals("Low · 64 mg/dL", overview.lowMoments[1].detail)
        assertEquals(7, overview.lowReadingCount)
        assertEquals(55.0, overview.minGlucoseMgdL!!, 0.001)
    }

    // MARK: - Lanes

    @Test
    fun `a meal carries carbs, insulin and its note, and is not repeated as a bolus`() {
        val mealAt = daysBefore(endDay, 1) + 18 * 3_600_000L + 30 * 60_000L
        val meal = NightscoutTreatment(eventType = "Meal Bolus", mills = mealAt, carbs = 45.0, insulin = 4.5, notes = "pizza")

        val yesterday = build(treatments = listOf(meal))[1]

        assertEquals(1, yesterday.meals.size)
        val row = yesterday.meals.single()
        assertEquals(time(mealAt), row.title)
        assertEquals("45.0g carbs · 4.5u insulin · pizza", row.detail)
        assertEquals(45.0, row.carbs!!, 0.001)
        assertEquals(4.5, row.insulin!!, 0.001)
        assertTrue(yesterday.boluses.isEmpty())
        // The note rides on the meal row; it is not a standalone note as well.
        assertTrue(yesterday.notes.isEmpty())
    }

    @Test
    fun `a correction is a bolus with its units and trimmed note`() {
        val at1 = endDay + 10 * 3_600_000L
        val at2 = endDay + 11 * 3_600_000L
        val today = build(
            treatments = listOf(
                NightscoutTreatment(eventType = "Correction Bolus", mills = at1, insulin = 2.0, notes = "  correction "),
                NightscoutTreatment(eventType = "Correction Bolus", mills = at2, insulin = 1.5),
            )
        ).first()

        assertEquals(listOf("2.0u · correction", "1.5u"), today.boluses.map { it.detail })
        assertTrue(today.meals.isEmpty())
    }

    @Test
    fun `exercise goes to the activity lane with its duration, and is not a note`() {
        val base = endDay + 7 * 3_600_000L
        val today = build(
            treatments = listOf(
                NightscoutTreatment(eventType = "Exercise", mills = base, duration = 45, notes = "run"),
                NightscoutTreatment(eventType = "Exercise", mills = base + 3_600_000L, duration = 90),
                NightscoutTreatment(eventType = "Exercise", mills = base + 7_200_000L, notes = "walk"),
            )
        ).first()

        assertEquals(listOf("45 min · run", "1h 30m · Activity", "walk"), today.activity.map { it.detail })
        assertTrue(today.notes.isEmpty())
    }

    @Test
    fun `a note on its own is a note, a note on a dose is not`() {
        val base = endDay + 9 * 3_600_000L
        val today = build(
            treatments = listOf(
                NightscoutTreatment(eventType = "Note", mills = base, notes = "site change"),
                NightscoutTreatment(eventType = "Note", mills = base + 60_000, notes = "   "),
                NightscoutTreatment(eventType = "Correction Bolus", mills = base + 120_000, insulin = 1.0, notes = "stacked"),
            )
        ).first()

        assertEquals(listOf("site change"), today.notes.map { it.detail })
    }

    @Test
    fun `daily totals add meals and corrections`() {
        val base = endDay + 12 * 3_600_000L
        val today = build(
            treatments = listOf(
                NightscoutTreatment(eventType = "Meal Bolus", mills = base, carbs = 45.0, insulin = 4.5),
                NightscoutTreatment(eventType = "Meal Bolus", mills = base + 3_600_000L, carbs = 20.0),
                NightscoutTreatment(eventType = "Correction Bolus", mills = base + 7_200_000L, insulin = 2.0),
            )
        ).first()

        assertEquals(65.0, today.totalCarbs, 0.001)
        assertEquals(6.5, today.bolusInsulin, 0.001)
    }

    // MARK: - Fixtures

    /** Seven days of five-minute readings at 120, covering the whole report window. */
    private fun flatWeek(skipOldest: Boolean = false): List<NightscoutGlucoseEntry> {
        val result = mutableListOf<NightscoutGlucoseEntry>()
        val firstDay = if (skipOldest) 5 else 6
        for (back in firstDay downTo 0) {
            val day = daysBefore(endDay, back)
            for (step in 0 until 288) {
                result += NightscoutGlucoseEntry(sgv = 120, direction = null, date = day + step * 300_000L, device = "test")
            }
        }
        return result
    }

    // MARK: - Basal and TDD

    private fun basalProfile(rate: Double) = NightscoutProfileDocument(
        id = null,
        defaultProfile = "Default",
        store = mapOf(
            "Default" to ProfileStoreEntry(
                units = "mg/dl", dia = 4.0,
                basal = listOf(TimeValue("00:00", rate)),
                carbRatio = listOf(TimeValue("00:00", 15.0)),
                sensitivity = listOf(TimeValue("00:00", 50.0)),
                targetLow = emptyList(), targetHigh = emptyList(),
            ),
        ),
        mills = null, startDate = null, createdAt = null, units = "mg/dl",
    )

    @Test
    fun `without a therapy profile a day reports a bolus figure and no TDD`() {
        val day = build(
            treatments = listOf(NightscoutTreatment(eventType = "Bolus", mills = weekEnd - 3_600_000L, insulin = 5.0)),
        ).first()
        assertNull(day.basalInsulin)
        assertNull(day.totalDailyDose)
        assertEquals(5.0, day.bolusInsulin, 0.001)
    }

    @Test
    fun `on a pump a settled day adds its whole scheduled basal to the bolus total`() {
        val days = build(
            treatments = listOf(NightscoutTreatment(eventType = "Bolus", mills = weekEnd - 26 * 3_600_000L, insulin = 5.0)),
            profile = basalProfile(1.0),
            therapyType = InsulinTherapyType.PUMP,
            nowMillis = weekEnd,
        )
        // Days are newest first, and the newest is still running, so the day before it is the
        // first one that covers twenty-four hours.
        val yesterday = days[1]
        assertEquals(24.0, yesterday.basalInsulin!!, 0.001)
        assertEquals(5.0, yesterday.bolusInsulin, 0.001)
        assertEquals(29.0, yesterday.totalDailyDose!!, 0.001)
    }

    @Test
    fun `today is only counted up to now, never filled in from the schedule`() {
        // Six hours into the newest day: six units of basal, not twenty-four.
        val dayStart = TodaySoFarBuilder.startOfDay(weekEnd, zone)
        val sixHoursIn = dayStart + 6 * 3_600_000L
        val today = build(
            profile = basalProfile(1.0),
            therapyType = InsulinTherapyType.PUMP,
            nowMillis = sixHoursIn,
        ).first()
        assertEquals(6.0, today.basalInsulin!!, 0.001)
    }

    @Test
    fun `on injections the long-acting dose is already a treatment, so no schedule is added`() {
        val day = build(
            treatments = listOf(NightscoutTreatment(eventType = "Bolus", mills = weekEnd - 3_600_000L, insulin = 22.0)),
            profile = basalProfile(1.0),
            therapyType = InsulinTherapyType.INJECTIONS,
            nowMillis = weekEnd,
        ).first()
        assertNull(day.basalInsulin)
        assertNull(day.totalDailyDose)
    }
}
