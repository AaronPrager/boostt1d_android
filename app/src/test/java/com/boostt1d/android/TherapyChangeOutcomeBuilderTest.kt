package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.engine.TherapyChange
import com.boostt1d.android.engine.TherapyChangeOutcome
import com.boostt1d.android.engine.TherapyChangeOutcomeBuilder
import com.boostt1d.android.engine.TherapyChangeVerdict
import com.boostt1d.android.engine.TherapyGlucoseFormatter
import com.boostt1d.android.engine.TherapyParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pins "did it work?" to the two rules that make the answer trustworthy: it measures only the
 * hours that changed, and added hypoglycemia beats any improvement elsewhere.
 *
 * Ported from the iOS TherapyChangeOutcomeBuilderTests.
 */
class TherapyChangeOutcomeBuilderTest {

    private val low = 70.0
    private val high = 180.0
    private val zone: TimeZone = TimeZone.getDefault()
    private val formatter = TherapyGlucoseFormatter({ String.format(Locale.US, "%.0f", it) }, "mg/dL")

    private val DAY = 86_400_000L

    /** Early June, so no case straddles a daylight-saving change. */
    private val now: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 12, 0, 0) }.timeInMillis
    private val changedAt: Long = now - 6 * DAY

    private fun hourOf(millis: Long): Int = Calendar.getInstance(zone).apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    // MARK: - Verdicts

    @Test
    fun `overnight glucose brought into range after a basal increase reads as better`() {
        // Overnight 240 before the change, 130 after; the rest of the day unchanged.
        val outcome = buildOne(entries = entries { at ->
            if (hourOf(at) !in 0 until 6) 120.0 else if (at < changedAt) 240.0 else 130.0
        })!!

        assertEquals(TherapyChangeVerdict.IMPROVED, outcome.verdict)
        assertTrue(outcome.inRangeDelta > 50)
        assertTrue(abs(outcome.before.averageGlucose!! - 240) < 1)
        assertTrue(abs(outcome.after.averageGlucose!! - 130) < 1)
    }

    /** The rule that matters most. A change can lower the average, lift time in range on paper, and still be the wrong change. */
    @Test
    fun `added lows outrank an improved average`() {
        // Before: a flat 200 overnight — no lows, no time in range.
        // After: mostly 110, but a third of the window under 70.
        val outcome = buildOne(entries = entries { at ->
            val hour = hourOf(at)
            when {
                hour !in 0 until 6 -> 120.0
                at < changedAt -> 200.0
                hour < 2 -> 62.0
                else -> 110.0
            }
        })!!

        assertEquals(TherapyChangeVerdict.WORSE, outcome.verdict)
        assertTrue(outcome.belowDelta > 2)
        assertTrue(outcome.headline.contains("below range"))
    }

    @Test
    fun `a window that barely moved reads as no clear change`() {
        val outcome = buildOne(entries = entries { at ->
            if (hourOf(at) !in 0 until 6) 120.0 else if (at < changedAt) 140.0 else 143.0
        })!!

        assertEquals(TherapyChangeVerdict.UNCHANGED, outcome.verdict)
        assertTrue(abs(outcome.inRangeDelta) < 5)
    }

    @Test
    fun `a change made yesterday is too early to judge`() {
        val yesterday = now - DAY
        val outcome = buildOne(
            change = change(at = yesterday),
            entries = entries { at ->
                if (hourOf(at) !in 0 until 6) 120.0 else if (at < yesterday) 240.0 else 130.0
            },
        )!!

        assertEquals(TherapyChangeVerdict.TOO_EARLY, outcome.verdict)
        assertFalse(outcome.hasComparison)
    }

    @Test
    fun `no readings before the change means no comparison, not a verdict`() {
        // Data starts the day after the change, so the "before" side is empty.
        val outcome = buildOne(entries = entries(from = changedAt + DAY) { 130.0 })!!

        assertEquals(TherapyChangeVerdict.NOT_ENOUGH_DATA, outcome.verdict)
        assertFalse(outcome.hasComparison)
    }

    // MARK: - Scoping

    /**
     * The whole reason the comparison is window-scoped: an overnight edit judged on
     * twenty-four hours would be diluted by eighteen hours it never touched.
     */
    @Test
    fun `only the changed hours are measured`() {
        // Overnight is unchanged at 120; the afternoon swings wildly. The verdict must
        // ignore the afternoon entirely.
        val outcome = buildOne(entries = entries { at ->
            if (hourOf(at) in 0 until 6) 120.0 else if (at < changedAt) 90.0 else 300.0
        })!!

        assertEquals(TherapyChangeVerdict.UNCHANGED, outcome.verdict)
        assertTrue(abs(outcome.before.averageGlucose!! - 120) < 1)
        assertTrue(abs(outcome.after.averageGlucose!! - 120) < 1)
    }

    @Test
    fun `a window spanning midnight counts nights, not calendar days`() {
        val wrapping = TherapyChange(
            id = "basal-wrap",
            parameter = TherapyParameter.BASAL,
            changedAtMillis = changedAt,
            startHour = 22,
            endHour = 4,
            windowLabel = "22:00–04:00",
            previousValue = 0.80,
            newValue = 0.95,
        )
        val outcome = buildOne(change = wrapping, entries = entries { at ->
            val hour = hourOf(at)
            if (!(hour >= 22 || hour < 4)) 120.0 else if (at < changedAt) 220.0 else 130.0
        })!!

        assertEquals(TherapyChangeVerdict.IMPROVED, outcome.verdict)
        // 14 days of data, the change 6 days back: at most 6 nights after, 7 before.
        assertTrue(outcome.after.dayCount in 5..6)
        assertTrue(outcome.before.dayCount in 6..7)
    }

    @Test
    fun `only the newest edit to the same window gets a verdict`() {
        val older = change(at = now - 11 * DAY, previous = 0.70, new = 0.80)
        val newer = change(at = changedAt, previous = 0.80, new = 0.95)

        val outcomes = TherapyChangeOutcomeBuilder.build(
            changes = listOf(older, newer),
            glucoseEntries = entries { 130.0 },
            treatments = emptyList(),
            lowGlucose = low,
            highGlucose = high,
            nowMillis = now,
            formatter = formatter,
            timeZone = zone,
        )

        assertEquals(1, outcomes.size)
        assertTrue(abs(outcomes[0].change.newValue - 0.95) < 0.001)
    }

    @Test
    fun `logged exercise inside the window is named as a confound`() {
        val session = NightscoutTreatment(
            eventType = "Exercise",
            // Two days and ten hours after the change, which lands at 02:00 — inside the
            // 00:00–06:00 window the change is judged on.
            mills = changedAt + 3 * DAY - 10 * 3_600_000L,
            enteredBy = "test",
            notes = "run",
            duration = 45,
        )

        val outcome = buildOne(entries = entries { 130.0 }, treatments = listOf(session))!!

        assertTrue(outcome.caveats.any { it.contains("logged exercise") })
    }

    // MARK: - Helpers

    private fun buildOne(
        change: TherapyChange? = null,
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment> = emptyList(),
    ): TherapyChangeOutcome? = TherapyChangeOutcomeBuilder.build(
        changes = listOf(change ?: change(at = changedAt)),
        glucoseEntries = entries,
        treatments = treatments,
        lowGlucose = low,
        highGlucose = high,
        nowMillis = now,
        formatter = formatter,
        timeZone = zone,
    ).firstOrNull()

    /** An overnight basal increase — more background insulin between midnight and 06:00. */
    private fun change(at: Long, previous: Double = 0.80, new: Double = 0.95) = TherapyChange(
        id = "basal-${at / 1000}",
        parameter = TherapyParameter.BASAL,
        changedAtMillis = at,
        startHour = 0,
        endHour = 6,
        windowLabel = "00:00–06:00",
        previousValue = previous,
        newValue = new,
    )

    /**
     * Fourteen days at five-minute resolution ending at `now`, so every case has a full week
     * on each side of a change made six days ago.
     */
    private fun entries(from: Long? = null, value: (Long) -> Double): List<NightscoutGlucoseEntry> {
        val first = from ?: (now - 14 * DAY)
        val result = mutableListOf<NightscoutGlucoseEntry>()
        var at = first
        while (at <= now) {
            result += NightscoutGlucoseEntry(sgv = value(at).roundToInt(), direction = null, date = at, device = "test")
            at += 300_000
        }
        return result
    }
}
