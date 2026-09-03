package com.boostt1d.android

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.engine.AIPrompts
import com.boostt1d.android.engine.DoctorVisitTherapySnapshot
import com.boostt1d.android.engine.TherapySettingsReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The prompt is the contract with the backend; these pin the parts a model actually reads numbers from. */
class AIPromptsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val t0 = 1_750_000_000_000L

    @Test
    fun `the CGM series widens its bucket to stay within five hundred points`() {
        // Seven days at five minutes is 2,016 readings spanning 10,075 minutes; the budget forces
        // the bucket to ceil(10075 / 5 / 500) = 5 cadences, i.e. 25 minutes (~403 points).
        val entries = (0 until 2016).map { NightscoutGlucoseEntry(sgv = 120 + it % 7, date = t0 + it * 300_000L, device = "t") }
        val series = AIPrompts.glucoseSeries(entries, utc)
        val header = series.lineSequence().first()
        val count = header.substringBefore(" readings").toInt()
        assertTrue("was $count", count <= AIPrompts.MAX_GLUCOSE_SERIES_POINTS)
        assertTrue(header, header.contains("25-minute resolution"))
        assertEquals("No CGM readings.", AIPrompts.glucoseSeries(emptyList(), utc))
    }

    @Test
    fun `the event log lists everything but temp basals, which it counts`() {
        val treatments = listOf(
            NightscoutTreatment(eventType = "Meal Bolus", mills = t0, insulin = 4.0, carbs = 45.0, notes = "pizza"),
            NightscoutTreatment(eventType = "Correction Bolus", mills = t0 + 3_600_000, insulin = 0.15, isSMB = true),
            NightscoutTreatment(eventType = "Temp Basal", mills = t0 + 7_200_000, duration = 30, rate = 1.2),
            NightscoutTreatment(eventType = "Temp Basal", mills = t0 + 9_000_000, duration = 30, rate = 0.8),
        )
        val log = AIPrompts.eventLog(treatments, utc)
        assertTrue(log.startsWith("2 events"))
        assertTrue(log.contains("Meal Bolus · 4.00u · 45g · (pizza)"))
        assertTrue(log.contains("0.15u · auto/SMB"))
        assertTrue(log.contains("(2 algorithm-driven Temp Basal events omitted for brevity.)"))
        assertFalse(log.contains("rate 1.20"))
    }

    @Test
    fun `the therapy profile is written as labelled schedules`() {
        val document = NightscoutProfileDocument(
            id = null, defaultProfile = "Default",
            store = mapOf("Default" to ProfileStoreEntry(
                units = "mg/dl", dia = 4.0,
                basal = listOf(TimeValue("06:00", 1.2), TimeValue("00:00", 0.8)),
                carbRatio = listOf(TimeValue("00:00", 12.0)), sensitivity = listOf(TimeValue("00:00", 50.0)),
                targetLow = listOf(TimeValue("00:00", 80.0)), targetHigh = listOf(TimeValue("00:00", 120.0)),
            )),
            mills = null, startDate = null, createdAt = null, units = "mg/dl",
        )
        val snapshot = DoctorVisitTherapySnapshot.from(document)
        val text = AIPrompts.therapyProfile(snapshot)

        assertEquals(
            "DIA: 4.0h\nBasal: 00:00=0.80 u/hr, 06:00=1.20 u/hr\nCarb ratio: 00:00=12.0 g/u\nCorrection factor: 00:00=50 mg/dL/u\nTarget: 00:00=80–120 mg/dL",
            text,
        )
        assertEquals("0.80–1.20 u/hr (2 segments)", snapshot.basalSummary)
        assertEquals("80–120 mg/dL", snapshot.targetSummary)
        assertEquals("No therapy profile on device.", AIPrompts.therapyProfile(null))
    }

    @Test
    fun `the daily plan carries every verified finding by KEY and the period as dates`() {
        val entries = (0 until 40).map { NightscoutGlucoseEntry(sgv = 130, date = t0 + it * 300_000L, device = "t") }
        val prompt = AIPrompts.dailyTherapyPlan(
            entries, emptyList(), "No in-app food log entries in this period.", null, TherapySettingsReview.empty,
            70.0, 180.0, t0, t0 + 6 * 86_400_000L, InsulinTherapyType.UNSPECIFIED, utc,
        )
        assertTrue(prompt.startsWith("You are reviewing seven completed days"))
        assertTrue(prompt.contains("PERIOD: 2025-06-15 through 2025-06-21"))
        assertTrue(prompt.contains("TARGET RANGE: 70-180 mg/dL"))
        assertTrue(prompt.contains("No numeric setting finding cleared the formula's evidence thresholds."))
        assertTrue(prompt.contains("No treatment data — delivery method unknown."))
        assertTrue(prompt.trimEnd().endsWith("}"))
    }
}
