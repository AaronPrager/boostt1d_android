package com.boostt1d.android

import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.engine.InMemoryTherapySnapshotStore
import com.boostt1d.android.engine.TherapyChangeDetector
import com.boostt1d.android.engine.TherapyDirection
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.TherapySegmentValue
import com.boostt1d.android.engine.TherapySettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Pins therapy-change detection to what the user actually did.
 *
 * The failure that matters here is a false positive: telling someone they changed a setting
 * they never touched, and then attributing a week of glucose to it. Most of these cases exist
 * to prove the detector stays silent.
 *
 * Ported from the iOS TherapyChangeDetectorTests.
 */
class TherapyChangeDetectorTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val day = 86_400_000L

    /** Noon, 2 June 2025, local. */
    private val base: Long = Calendar.getInstance(zone).apply {
        clear(); set(2025, Calendar.JUNE, 2, 12, 0, 0)
    }.timeInMillis

    // MARK: - Diffing

    @Test
    fun `a single overnight basal edit is one change, not six hourly ones`() {
        val before = snapshot(basal = listOf(0.0 to 0.80, 6.0 to 1.00), at = base)
        val after = snapshot(basal = listOf(0.0 to 0.95, 6.0 to 1.00), at = base + day)

        val changes = TherapyChangeDetector.diff(before, after)

        assertEquals(1, changes.size)
        val change = changes.first()
        assertEquals(TherapyParameter.BASAL, change.parameter)
        assertEquals(0, change.startHour)
        assertEquals(6, change.endHour)
        assertEquals("00:00–06:00", change.windowLabel)
        assertTrue(abs(change.previousValue - 0.80) < 0.001)
        assertTrue(abs(change.newValue - 0.95) < 0.001)
        assertEquals(TherapyDirection.INCREASE, change.direction)
    }

    @Test
    fun `two windows moved by different amounts stay two changes`() {
        val before = snapshot(basal = listOf(0.0 to 0.80, 6.0 to 1.00, 12.0 to 1.20), at = base)
        val after = snapshot(basal = listOf(0.0 to 0.95, 6.0 to 1.00, 12.0 to 1.10), at = base + day)

        val changes = TherapyChangeDetector.diff(before, after).sortedBy { it.startHour }

        assertEquals(2, changes.size)
        assertEquals(0, changes[0].startHour)
        assertEquals(TherapyDirection.INCREASE, changes[0].direction)
        assertEquals(12, changes[1].startHour)
        assertEquals(TherapyDirection.DECREASE, changes[1].direction)
    }

    @Test
    fun `an edit spanning midnight is one window, not two`() {
        val before = snapshot(basal = listOf(0.0 to 0.80, 4.0 to 1.00, 22.0 to 0.80), at = base)
        val after = snapshot(basal = listOf(0.0 to 0.95, 4.0 to 1.00, 22.0 to 0.95), at = base + day)

        val changes = TherapyChangeDetector.diff(before, after)

        assertEquals(1, changes.size)
        val change = changes.first()
        assertEquals(22, change.startHour)
        assertEquals(4, change.endHour)
        assertTrue(change.contains(23))
        assertTrue(change.contains(2))
        assertFalse(change.contains(12))
    }

    @Test
    fun `identical settings produce no change`() {
        val before = snapshot(basal = listOf(0.0 to 0.80), isf = listOf(0.0 to 50.0), carbRatio = listOf(0.0 to 15.0), at = base)
        val after = snapshot(basal = listOf(0.0 to 0.80), isf = listOf(0.0 to 50.0), carbRatio = listOf(0.0 to 15.0), at = base + day)

        assertTrue(TherapyChangeDetector.diff(before, after).isEmpty())
    }

    @Test
    fun `sub-percent drift is float noise, not an edit`() {
        val before = snapshot(basal = listOf(0.0 to 1.000), at = base)
        val after = snapshot(basal = listOf(0.0 to 1.005), at = base + day)

        assertTrue(TherapyChangeDetector.diff(before, after).isEmpty())
    }

    /**
     * The first Nightscout sync is not a decision the user made. Reporting "0 → 1.0 U/hr"
     * there would put a fabricated change at the top of the report.
     */
    @Test
    fun `a schedule appearing for the first time is not reported as a change`() {
        val before = snapshot(basal = listOf(0.0 to 0.80), isf = emptyList(), at = base)
        val after = snapshot(basal = listOf(0.0 to 0.80), isf = listOf(0.0 to 50.0), at = base + day)

        assertTrue(TherapyChangeDetector.diff(before, after).isEmpty())
    }

    @Test
    fun `carb ratio and ISF edits are reported with their own parameters`() {
        val before = snapshot(basal = listOf(0.0 to 1.0), isf = listOf(0.0 to 50.0), carbRatio = listOf(0.0 to 15.0), at = base)
        val after = snapshot(basal = listOf(0.0 to 1.0), isf = listOf(0.0 to 45.0), carbRatio = listOf(0.0 to 12.0), at = base + day)

        val changes = TherapyChangeDetector.diff(before, after)

        assertEquals(setOf(TherapyParameter.ISF, TherapyParameter.CARB_RATIO), changes.map { it.parameter }.toSet())
        // A smaller ISF means each unit is expected to do more, so corrections get bigger.
        val isf = changes.first { it.parameter == TherapyParameter.ISF }
        assertEquals("larger corrections", isf.intentLabel)
        assertTrue(isf.expectsLowerGlucose)
    }

    // MARK: - Timeline

    @Test
    fun `one recorded profile is a baseline, not a change`() {
        val detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone)
        val changes = detector.record(profile(basal = 0.80), nowMillis = base)

        assertTrue(changes.isEmpty())
        assertFalse(detector.hasHistory)
        assertNotNull(detector.watchingSinceMillis)
    }

    @Test
    fun `recording the same profile twice adds nothing`() {
        val detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone)
        detector.record(profile(basal = 0.80), nowMillis = base)
        detector.record(profile(basal = 0.80), nowMillis = base + day)
        val changes = detector.record(profile(basal = 0.80), nowMillis = base + 2 * day)

        assertTrue(changes.isEmpty())
        assertEquals(1, detector.snapshots().size)
    }

    @Test
    fun `a profile that differs from the last one recorded becomes a change`() {
        val detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone)
        detector.record(profile(basal = 0.80), nowMillis = base)
        val changes = detector.record(profile(basal = 0.95), nowMillis = base + 3 * day)

        assertEquals(1, changes.size)
        assertTrue(detector.hasHistory)
        val change = changes.first()
        assertTrue(abs(change.previousValue - 0.80) < 0.001)
        assertTrue(abs(change.newValue - 0.95) < 0.001)
    }

    /**
     * The point of reading Nightscout's history: changes made before the app was installed
     * still get an answer.
     */
    @Test
    fun `nightscout history seeds changes the app never watched happen`() {
        val detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone)
        val old = profile(basal = 0.80, mills = base - 20 * day)
        val recent = profile(basal = 1.00, mills = base - 5 * day)

        val changes = detector.record(recent, history = listOf(recent, old), nowMillis = base)

        assertEquals(1, changes.size)
        val change = changes.first()
        assertTrue(abs(change.previousValue - 0.80) < 0.001)
        assertTrue(abs(change.newValue - 1.00) < 0.001)
        assertTrue(abs(change.changedAtMillis - (base - 5 * day)) < 60_000)
    }

    @Test
    fun `only changes inside the window are returned`() {
        val detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone)
        val old = profile(basal = 0.80, mills = base - 60 * day)
        val mid = profile(basal = 0.90, mills = base - 40 * day)
        detector.record(mid, history = listOf(mid, old), nowMillis = base)

        assertEquals(1, detector.changes().size)
        assertTrue(detector.changes(withinDays = 7, nowMillis = base).isEmpty())
        assertEquals(1, detector.changes(withinDays = 45, nowMillis = base).size)
    }

    @Test
    fun `the newest edit per parameter is the one the review scopes to`() {
        val detector = TherapyChangeDetector(InMemoryTherapySnapshotStore(), zone)
        detector.record(profile(basal = 0.80, mills = base - 10 * day), nowMillis = base)
        detector.record(profile(basal = 0.90, mills = base - 6 * day), nowMillis = base)
        detector.record(profile(basal = 1.00, mills = base - 2 * day), nowMillis = base)

        val latest = detector.latestChangePerParameter(withinDays = 14, nowMillis = base)

        assertEquals(1, latest.size)
        val basal = latest.getValue(TherapyParameter.BASAL)
        assertTrue(abs(basal.newValue - 1.00) < 0.001)
        assertTrue(abs(basal.previousValue - 0.90) < 0.001)
    }

    // MARK: - Normalisation, which the iOS tests relied on implicitly

    @Test
    fun `an ISF uploaded in mmol per L is compared in mg per dL`() {
        // 2.8 mmol/L per unit is 50 mg/dL per unit. Left as 2.8, an edit to 45 mg/dL would
        // read as a fifteen-fold change.
        val mmol = document(basal = 1.0, isf = 2.8, carbRatio = 15.0, mills = base, units = "mmol")
        val snapshot = TherapyChangeDetector.snapshot(mmol, fallbackMillis = null)!!

        assertTrue(abs(snapshot.isf.single().value - 50.4) < 0.1)
    }

    @Test
    fun `a small ISF is treated as mmol even when no unit is declared`() {
        val undeclared = document(basal = 1.0, isf = 2.8, carbRatio = 15.0, mills = base, units = null)
        val snapshot = TherapyChangeDetector.snapshot(undeclared, fallbackMillis = null)!!

        assertTrue(snapshot.isf.single().value > 25)
    }

    // MARK: - Fixtures

    private fun snapshot(
        basal: List<Pair<Double, Double>> = emptyList(),
        isf: List<Pair<Double, Double>> = emptyList(),
        carbRatio: List<Pair<Double, Double>> = emptyList(),
        at: Long,
    ) = TherapySettingsSnapshot(
        effectiveAtMillis = at,
        basal = basal.map { TherapySegmentValue(it.first, it.second) },
        isf = isf.map { TherapySegmentValue(it.first, it.second) },
        carbRatio = carbRatio.map { TherapySegmentValue(it.first, it.second) },
    )

    private fun profile(basal: Double, isf: Double = 50.0, carbRatio: Double = 15.0, mills: Long? = null) =
        document(basal, isf, carbRatio, mills, units = "mg/dl")

    private fun document(basal: Double, isf: Double, carbRatio: Double, mills: Long?, units: String?) =
        NightscoutProfileDocument(
            id = null,
            defaultProfile = "Default",
            store = mapOf(
                "Default" to ProfileStoreEntry(
                    units = units,
                    dia = 4.0,
                    basal = listOf(TimeValue("00:00", basal)),
                    carbRatio = listOf(TimeValue("00:00", carbRatio)),
                    sensitivity = listOf(TimeValue("00:00", isf)),
                    targetLow = emptyList(),
                    targetHigh = emptyList(),
                )
            ),
            mills = mills,
            startDate = null,
            createdAt = null,
            units = units,
        )
}
