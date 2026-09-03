package com.boostt1d.android

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.TherapyBasalMethod
import com.boostt1d.android.engine.TherapyChange
import com.boostt1d.android.engine.TherapyDirection
import com.boostt1d.android.engine.TherapyEvidenceStrength
import com.boostt1d.android.engine.TherapyGlucoseFormatter
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.TherapySettingsReviewBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Pins the settings review to arithmetic a clinician could redo by hand.
 *
 * Every case is built from glucose curves with one known cause, so a failure here means the
 * engine read the curve wrong — not that the thresholds moved.
 *
 * Ported from the iOS TherapySettingsReviewBuilderTests.
 */
class TherapySettingsReviewBuilderTest {

    private val low = 70.0
    private val high = 180.0
    private val zone: TimeZone = TimeZone.getDefault()
    private val formatter = TherapyGlucoseFormatter({ String.format(Locale.US, "%.0f", it) }, "mg/dL")

    /** Early June, so no test can straddle a daylight-saving change in any timezone. */
    private val base: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 2, 0, 0, 0) }.timeInMillis

    private fun dayStart(day: Int): Long = Calendar.getInstance(zone).apply { timeInMillis = base; add(Calendar.DAY_OF_MONTH, day) }.timeInMillis
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply { timeInMillis = dayStart(day); add(Calendar.MINUTE, hour * 60 + minute) }.timeInMillis

    /** 120 mg/dL at midnight climbing to 200 by 06:00 — 13.3 mg/dL/h — then flat. */
    private val overnightRise: (Int, Int) -> Double = { _, minute ->
        val hour = minute / 60.0
        when {
            hour < 6 -> 120 + (80 * hour / 6)
            hour < 7 -> 200 - 80 * (hour - 6)
            else -> 120.0
        }
    }

    private fun basalFindings(review: TherapySettingsReview) = review.findings.filter { it.parameter == TherapyParameter.BASAL }

    // MARK: - Basal

    @Test
    fun `a fasting overnight rise is read as a basal shortfall, sized from ISF`() {
        val review = build(entries(value = overnightRise))

        val basal = basalFindings(review)
        assertEquals(1, basal.size)

        val finding = basal.first()
        assertEquals("00:00–06:00", finding.windowLabel)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        assertEquals(Priority.HIGH, finding.priority)
        assertEquals(TherapyEvidenceStrength.STRONG, finding.strength)
        assertEquals(1.0, finding.currentValue!!, 0.0)

        // 13.3 mg/dL/h ÷ 50 mg/dL/U = 0.27 U/hr, halved to 0.13, capped at 20% (0.20),
        // rounded to the nearest 0.05.
        assertTrue(abs(finding.suggestedValue!! - 1.15) < 0.001)
    }

    @Test
    fun `flat fasting glucose produces no basal finding`() {
        val review = build(entries { _, _ -> 115.0 })

        assertTrue(basalFindings(review).isEmpty())
        assertTrue(review.steadyNotes.any { it.startsWith("Basal") })
    }

    @Test
    fun `hours shadowed by carbs are excluded from the basal read`() {
        // Same overnight rise, but 40 g logged at 02:00 every night. Only the two hours before
        // the carbs are still fasting; 02:00–06:00 becomes unreadable.
        val carbs = (0 until 7).map { day -> treatment(day, 2, 0, insulin = null, carbs = 40.0) }

        val review = build(entries(value = overnightRise), treatments = carbs)

        assertEquals("00:00–02:00", basalFindings(review).firstOrNull()?.windowLabel)
        // The shadowed hours report no drift at all rather than a weak one.
        assertNull(review.hours[4].fastingDrift)
        assertEquals(0, review.hours[4].fastingWindowCount)
    }

    @Test
    fun `logged exercise is not misread as a basal mismatch`() {
        val treatments = ordinaryMealLog(7) + (0 until 7).map { day ->
            treatment(day, 0, 15, insulin = null, carbs = null, eventType = "Exercise")
        }

        val review = build(entries(value = overnightRise), treatments = treatments)

        assertTrue(basalFindings(review).isEmpty())
        assertTrue(review.dataNotes.any { it.contains("exercise", ignoreCase = true) })
    }

    /**
     * The rule that keeps this engine safe for anyone who does not log. With an empty log every
     * hour of every day looks like clean fasting, so ordinary post-meal excursions get reported
     * as basal shortfalls — and it points at the one setting with hypoglycemia on the other side.
     */
    @Test
    fun `an empty log yields no basal finding, however clean the curve looks`() {
        val review = build(entries(value = overnightRise), treatments = emptyList())

        assertTrue(basalFindings(review).isEmpty())
        // And nothing may claim the settings were checked and looked fine.
        assertFalse(review.steadyNotes.any { it.startsWith("Basal") })

        val note = review.dataNotes.first { it.startsWith("Basal") }
        // Must name the missing log as the cause…
        assertTrue(note.contains("when you ate"))
        assertTrue(note.contains("nothing logged"))
        // …and must not read as "come back later" — no amount of waiting adds a log.
        assertTrue(note.contains("Event Log"))
    }

    /** Daytime and overnight are not equally trustworthy, and the engine has to say so. */
    @Test
    fun `a single logged meal buys overnight windows but not daytime ones`() {
        // One meal a day at 12:00. Overnight is plausibly fasting; 16:00–18:00 is not
        // established by one record, even though nothing was logged there.
        val single = (0 until 7).map { day -> treatment(day, 12, 0, insulin = 4.0, carbs = 50.0) }
        val review = build(entries { _, _ -> 140.0 }, treatments = single)

        assertTrue(review.hours[3].fastingWindowCount > 0)
        assertEquals(0, review.hours[17].fastingWindowCount)
    }

    // MARK: - Closed loop

    /**
     * On a loop the basal read comes from the loop's own temp basals, not from fasting glucose.
     * Flat overnight glucose there means the algorithm compensated well — it says nothing about
     * whether the baseline under it is right.
     */
    @Test
    fun `a loop running above profile overnight reads as a light baseline`() {
        // 140% of a 1.00 U/hr profile from 00:00–06:00 every night, profile rate otherwise.
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 6, 1.40) + loopTempBasals(7, 8 until 12, 1.00)

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        val finding = basalFindings(review).first()
        assertEquals("00:00–06:00", finding.windowLabel)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        assertEquals(1.0, finding.currentValue!!, 0.0)
        // Median delivered is the measurement; the suggestion is a capped half-step toward it.
        assertTrue(abs((finding.observedValue ?: 0.0) - 1.40) < 0.01)
        assertTrue(abs((finding.suggestedValue ?: 0.0) - 1.20) < 0.001)
        // The evidence must say where the number came from — delivery, not glucose.
        assertTrue(finding.evidence.any { it.contains("delivered") })
        assertTrue(finding.evidence.any { it.contains("not inferred from glucose") })

        // The screen's "what this was measured from" line reads these.
        assertEquals(TherapyBasalMethod.LOOP_DELIVERY, review.basalMethod)
        assertEquals(0, review.cleanFastingHours)
        assertTrue(review.loopComparedDays >= 5)
        assertTrue(review.loopComparedHours > 0)
    }

    @Test
    fun `open-loop therapy still reports its footprint as fasting hours`() {
        val review = build(entries(value = overnightRise))

        assertEquals(TherapyBasalMethod.FASTING_DRIFT, review.basalMethod)
        assertTrue(review.cleanFastingHours > 0)
        assertEquals(0, review.loopComparedHours)
    }

    @Test
    fun `a loop holding back insulin is a hypo-side finding, ranked high`() {
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 6, 0.65)

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        val finding = basalFindings(review).first()
        assertEquals(TherapyDirection.DECREASE, finding.direction)
        assertEquals(Priority.HIGH, finding.priority)
    }

    /** The loop tracking the profile closely is a real result and has to be said, not left as silence. */
    @Test
    fun `a loop that tracks the profile is reported as nothing to change`() {
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 6, 1.02)

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        assertTrue(basalFindings(review).isEmpty())
        val steady = review.steadyNotes.first { it.startsWith("Basal") }
        assertTrue(steady.contains("within"))
        assertTrue(steady.contains("profile"))
    }

    /**
     * The bug that started this: unflagged SMBs made every hour look like insulin was acting, so
     * the fasting read went silent on a week of complete data. The loop path must not depend on
     * the fasting filter at all.
     */
    @Test
    fun `unflagged automatic boluses no longer silence the basal read`() {
        val treatments = ordinaryMealLog(7).toMutableList()
        treatments += loopTempBasals(7, 0 until 6, 1.40)
        // An SMB every 30 minutes all night, with nothing marking it automatic.
        for (day in 0 until 7) for (hour in 0 until 6) for (minute in 0 until 60 step 30) {
            treatments += treatment(day, hour, minute, insulin = 0.15, carbs = null)
        }

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        val finding = basalFindings(review).first()
        assertEquals("00:00–06:00", finding.windowLabel)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
    }

    /** A 01:00 snack used to erase 01:00–05:00 under the daytime 4h shadow. Overnight's 2h shadow keeps 03:00–06:00 readable. */
    @Test
    fun `a late-night snack no longer erases the overnight loop basal window`() {
        val treatments = ordinaryMealLog(7) +
            (0 until 7).map { day -> treatment(day, 1, 0, insulin = null, carbs = 15.0) } +
            loopTempBasals(7, 0 until 6, 1.40)

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        val finding = basalFindings(review).first()
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        assertTrue(finding.startHour >= 3)
        assertEquals(6, finding.endHour)
    }

    /** Daytime still demanded 20%. Overnight at ~16% was silent before; it must surface now. */
    @Test
    fun `a moderate overnight loop offset is worth discussing`() {
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 6, 1.16)

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        val finding = basalFindings(review).first()
        assertEquals("00:00–06:00", finding.windowLabel)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
    }

    /** Five messy nights and two clean ones used to fail the 3-day bar. Overnight needs two. */
    @Test
    fun `two clean overnight nights are enough for a loop basal finding`() {
        val treatments = mutableListOf<NightscoutTreatment>()
        for (day in 0 until 7) {
            treatments += treatment(day, 12, 0, insulin = 4.0, carbs = 50.0)
            treatments += treatment(day, 18, 30, insulin = 5.0, carbs = 60.0)
            if (day < 5) {
                // Shadow 03:00–06:00 on the first five nights so only days 5–6 contribute.
                treatments += treatment(day, 3, 0, insulin = null, carbs = 20.0)
            }
            for (hour in 0 until 6) {
                val rate = if (day >= 5 && hour >= 3) 1.40 else 1.00
                treatments += NightscoutTreatment(eventType = "Temp Basal", mills = at(day, hour), enteredBy = "loop", duration = 60, absolute = rate)
            }
        }

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        val finding = basalFindings(review).first()
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        assertTrue(finding.startHour >= 3)
        assertEquals(6, finding.endHour)
    }

    /**
     * A window is built from hours that deviate by the same *proportion*, so it can span a
     * profile step. Comparing a pooled U/hr median against the first hour's rate used to produce
     * a "withholds insulin" card whose suggestion was to raise the rate.
     */
    @Test
    fun `a window spanning a profile step suggests a change in the direction it found`() {
        // 0.40 U/hr at 00:00, 2.00 U/hr from 01:00. The loop runs 25% under all night.
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 1, 0.30) + loopTempBasals(7, 1 until 6, 1.50)

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments, profile = steppedProfile)

        val finding = basalFindings(review).first()
        assertEquals("00:00–06:00", finding.windowLabel)
        assertEquals(TherapyDirection.DECREASE, finding.direction)
        assertEquals(0.40, finding.currentValue!!, 0.0)
        // Delivery stated at the 00:00 rate: 25% under 0.40 is 0.30, and the step is half of
        // that gap. A suggestion above the current rate would contradict the finding.
        assertTrue(abs((finding.observedValue ?: 0.0) - 0.30) < 0.001)
        assertTrue(abs((finding.suggestedValue ?: 0.0) - 0.35) < 0.001)
        assertTrue((finding.suggestedValue ?: 0.0) < (finding.currentValue ?: 0.0))
    }

    /** Nightscout ends a temp basal early with a zero-duration record. Ignoring it billed the cancelled rate for its full nominal length. */
    @Test
    fun `a temp basal cancelled early is not billed for its full duration`() {
        // 1.60 U/hr set on the hour against a 1.00 profile, cancelled six minutes in: 1.06 U/hr
        // for the hour, which is inside the steady band. Uncancelled it reads as +60%.
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 6, 1.60) +
            (0 until 7).flatMap { day -> (0 until 6).map { hour -> treatment(day, hour, 6, insulin = null, carbs = null, eventType = "Temp Basal", duration = 0) } }

        val review = build(entries { _, _ -> 120.0 }, treatments = treatments)

        assertTrue(basalFindings(review).isEmpty())
        assertTrue(review.steadyNotes.any { it.startsWith("Basal") })
    }

    @Test
    fun `a basal change less than three days ago suspends the closed-loop basal review`() {
        val treatments = ordinaryMealLog(7) + loopTempBasals(7, 0 until 6, 1.40)
        val change = TherapyChange(
            id = "basal-recent", parameter = TherapyParameter.BASAL, changedAtMillis = dayStart(6),
            startHour = 0, endHour = 6, windowLabel = "00:00–06:00", previousValue = 0.8, newValue = 1.0,
        )

        val review = TherapySettingsReviewBuilder.build(
            glucoseEntries = entries { _, _ -> 120.0 },
            treatments = treatments,
            foodLogEntries = emptyList(),
            profile = standardProfile,
            lowGlucose = low,
            highGlucose = high,
            periodDays = 7,
            changes = listOf(change),
            nowMillis = dayStart(7),
            therapyType = InsulinTherapyType.UNSPECIFIED,
            formatter = formatter,
            timeZone = zone,
        )

        assertTrue(basalFindings(review).isEmpty())
        assertTrue(review.dataNotes.any { it.contains("Basal rate changed") })
    }

    // MARK: - ISF

    @Test
    fun `corrections that outperform the setting raise ISF, not lower it`() {
        // 2 U at 14:00 takes 250 down to 130 by 17:00: a measured 60 mg/dL per unit against a
        // configured 50.
        val corrections = (0 until 7).map { day -> treatment(day, 14, 0, insulin = 2.0, carbs = null) }

        val review = build(entries(value = correctionCurve), treatments = corrections)

        val finding = review.findings.first { it.parameter == TherapyParameter.ISF }
        assertEquals("12:00–18:00", finding.windowLabel)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        assertTrue(abs(finding.observedValue!! - 60) < 0.001)
        // Half a step from 50 toward 60.
        assertEquals(55.0, finding.suggestedValue!!, 0.0)
    }

    @Test
    fun `fewer than three standalone corrections is reported as unreadable`() {
        val corrections = (0 until 2).map { day -> treatment(day, 14, 0, insulin = 2.0, carbs = null) }

        val review = build(entries(value = correctionCurve), treatments = corrections)

        assertTrue(review.findings.none { it.parameter == TherapyParameter.ISF })
        assertTrue(review.dataNotes.any { it.contains("Correction factor") })
    }

    private val correctionCurve: (Int, Int) -> Double = { _, minute ->
        when {
            minute < 840 -> 120.0                                       // before 14:00
            minute < 1020 -> 250 - 120 * (minute - 840) / 180.0          // 14:00 → 17:00
            else -> 130.0
        }
    }

    // MARK: - Carb ratio

    private val lunchCurve: (Int, Int) -> Double = { _, minute ->
        when {
            minute < 720 -> 120.0
            minute < 840 -> 120 + 100 * (minute - 720) / 120.0
            else -> 220.0
        }
    }

    @Test
    fun `a meal still elevated four hours later strengthens the carb ratio`() {
        // 60 g covered by 4 U at noon, still 100 mg/dL up at 16:00. Erasing that needs 2 U
        // more at an ISF of 50, so the meal really wanted 6 U — a 1:10 ratio.
        val meals = (0 until 7).map { day -> treatment(day, 12, 0, insulin = 4.0, carbs = 60.0) }

        val review = build(entries(value = lunchCurve), treatments = meals)

        val finding = review.findings.first { it.parameter == TherapyParameter.CARB_RATIO }
        assertEquals("11:00–16:00", finding.windowLabel)
        assertEquals(TherapyDirection.DECREASE, finding.direction)
        assertEquals(15.0, finding.currentValue!!, 0.0)
        assertTrue(abs(finding.observedValue!! - 10) < 0.6)
        // Half a step from 15 toward 10, rounded to the nearest 0.5 g.
        assertEquals(12.5, finding.suggestedValue!!, 0.0)
    }

    // MARK: - Shape

    @Test
    fun `every hour of the day appears in the profile, data or not`() {
        val review = build(entries { _, minute -> if (minute < 720) 140.0 else 160.0 })

        assertEquals(24, review.hours.size)
        assertEquals((0 until 24).toList(), review.hours.map { it.hour })
        assertTrue(review.hours.all { it.dayCount == 7 })
        assertTrue(review.hasHourlyProfile)
    }

    @Test
    fun `too little glucose data returns an empty review rather than a weak one`() {
        val review = build(entries { _, _ -> 120.0 }.take(20))

        assertTrue(review.hours.isEmpty())
        assertTrue(review.findings.isEmpty())
        assertFalse(review.hasHourlyProfile)
    }

    @Test
    fun `without a therapy profile, findings carry evidence but no setting values`() {
        val review = build(entries(value = overnightRise), useProfile = false)

        val finding = basalFindings(review).first()
        assertNull(finding.currentValue)
        assertNull(finding.suggestedValue)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        assertFalse(review.hasTherapySettings)
        assertTrue(review.dataNotes.any { it.contains("No therapy profile") })
    }

    // MARK: - Real-world shape
    //
    // The screen defaults to three days, and a cache may keep one reading per ten minutes rather
    // than the sensor's five. Thresholds tuned for a week of five-minute data silently emptied
    // the whole review under those conditions.

    @Test
    fun `three days of ten-minute data still reads a basal window`() {
        val review = build(entries(days = 3, intervalMinutes = 10, value = overnightRise), periodDays = 3, days = 3)

        val finding = basalFindings(review).first()
        assertEquals("00:00–06:00", finding.windowLabel)
        assertEquals(TherapyDirection.INCREASE, finding.direction)
        // Three days can only ever supply three windows per hour, so this must not claim more.
        assertNotEquals(TherapyEvidenceStrength.STRONG, finding.strength)
    }

    @Test
    fun `three days of ten-minute data still reads a meal`() {
        val meals = (0 until 3).map { day -> treatment(day, 12, 0, insulin = 4.0, carbs = 60.0) }

        val review = build(entries(days = 3, intervalMinutes = 10, value = lunchCurve), treatments = meals, periodDays = 3)

        val finding = review.findings.first { it.parameter == TherapyParameter.CARB_RATIO }
        assertEquals(TherapyDirection.DECREASE, finding.direction)
        assertEquals(3, review.cleanMeals)
    }

    @Test
    fun `when nothing is readable, the notes name what got in the way`() {
        // Carbs every three hours around the clock: plenty of data, none of it uninterrupted.
        val grazing = (0 until 7).flatMap { day -> (0 until 24 step 3).map { hour -> treatment(day, hour, 0, insulin = 3.0, carbs = 40.0) } }

        val review = build(entries { _, _ -> 150.0 }, treatments = grazing)

        assertTrue(review.findings.isEmpty())
        // Never a bare "not enough data": the note has to carry the count and the reason.
        val basalNote = review.dataNotes.first { it.startsWith("Basal") }
        assertTrue(basalNote.contains("clock-hours"))
        assertTrue(basalNote.contains("of food"))
        assertTrue(review.dataNotes.any { it.contains("Carb ratio") })
    }

    // MARK: - Fixtures

    /**
     * Basal is only readable on days the user was logging, so unless a case supplies its own
     * treatments the fixture stands in an ordinarily-logged day: lunch at 12:00 and dinner at
     * 18:30. Both sit well clear of the overnight window most of these cases measure.
     */
    private fun build(
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>? = null,
        useProfile: Boolean = true,
        profile: NightscoutProfileDocument? = null,
        periodDays: Int = 7,
        days: Int = 7,
    ): TherapySettingsReview = TherapySettingsReviewBuilder.build(
        glucoseEntries = entries,
        treatments = treatments ?: ordinaryMealLog(days),
        foodLogEntries = emptyList(),
        profile = if (useProfile) (profile ?: standardProfile) else null,
        lowGlucose = low,
        highGlucose = high,
        periodDays = periodDays,
        // Anchored to the data, not the real clock, so these fixtures never age into rejection.
        nowMillis = dayStart(days),
        // UNSPECIFIED on purpose: these fixtures are about what the *data* says.
        therapyType = InsulinTherapyType.UNSPECIFIED,
        formatter = formatter,
        timeZone = zone,
    )

    private fun ordinaryMealLog(days: Int): List<NightscoutTreatment> = (0 until days).flatMap { day ->
        listOf(
            treatment(day, 12, 0, insulin = 4.0, carbs = 50.0),
            treatment(day, 18, 30, insulin = 5.0, carbs = 60.0),
        )
    }

    private fun loopTempBasals(days: Int, hours: IntRange, rate: Double): List<NightscoutTreatment> =
        (0 until days).flatMap { day ->
            hours.map { hour -> NightscoutTreatment(eventType = "Temp Basal", mills = at(day, hour), enteredBy = "loop", duration = 60, absolute = rate) }
        }

    /** A run of days at a given sampling resolution. `value` is given the day index and the minute of the day. */
    private fun entries(days: Int = 7, intervalMinutes: Int = 5, value: (Int, Int) -> Double): List<NightscoutGlucoseEntry> {
        val result = mutableListOf<NightscoutGlucoseEntry>()
        for (day in 0 until days) {
            for (step in 0 until (24 * 60 / intervalMinutes)) {
                val minute = step * intervalMinutes
                result += NightscoutGlucoseEntry(
                    sgv = TherapySettingsReviewBuilder.schoolbookRound(value(day, minute)).toInt(),
                    direction = null,
                    date = at(day, 0, minute),
                    device = "test",
                )
            }
        }
        return result
    }

    private fun treatment(
        day: Int, hour: Int, minute: Int, insulin: Double?, carbs: Double?,
        eventType: String? = null, notes: String? = null, duration: Int? = null,
    ) = NightscoutTreatment(
        eventType = eventType ?: (if (carbs == null) "Correction Bolus" else "Meal Bolus"),
        mills = at(day, hour, minute),
        enteredBy = "test",
        insulin = insulin,
        carbs = carbs,
        notes = notes,
        duration = duration,
    )

    /** 1.00 U/hr flat, ISF 50 mg/dL/U, carb ratio 1:15 — round numbers so expected values can be checked by hand. */
    private val standardProfile: NightscoutProfileDocument get() = profile(listOf(TimeValue("00:00", 1.0)))

    /** The standard profile with a basal step inside the overnight window: 0.40 U/hr at midnight, 2.00 U/hr from 01:00. */
    private val steppedProfile: NightscoutProfileDocument get() = profile(listOf(TimeValue("00:00", 0.40), TimeValue("01:00", 2.00)))

    private fun profile(basal: List<TimeValue>) = NightscoutProfileDocument(
        id = null,
        defaultProfile = "Default",
        store = mapOf(
            "Default" to ProfileStoreEntry(
                units = "mg/dl", dia = 4.0, basal = basal,
                carbRatio = listOf(TimeValue("00:00", 15.0)),
                sensitivity = listOf(TimeValue("00:00", 50.0)),
                targetLow = emptyList(), targetHigh = emptyList(),
            )
        ),
        mills = null, startDate = null, createdAt = null, units = "mg/dl",
    )
}
