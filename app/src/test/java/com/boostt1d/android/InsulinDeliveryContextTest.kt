package com.boostt1d.android

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.engine.InsulinDeliveryContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InsulinDeliveryContext has no iOS test file. These pin the three detection signals, the
 * user's answer overriding them, and the two briefings.
 */
class InsulinDeliveryContextTest {

    private val DAY = 86_400_000L
    private val t0 = 1_750_000_000_000L

    private fun bolus(at: Long, units: Double = 1.0, smb: Boolean? = null, by: String? = "Trio") =
        NightscoutTreatment(eventType = "Correction Bolus", mills = at, insulin = units, enteredBy = by, isSMB = smb)

    private fun tempBasal(at: Long, by: String? = "AndroidAPS") =
        NightscoutTreatment(eventType = "Temp Basal", mills = at, rate = 0.8, duration = 30, enteredBy = by)

    // MARK: - Detection

    @Test
    fun `flagged doses prove a loop and separate the user's own`() {
        val context = InsulinDeliveryContext(
            (0 until 10).map { bolus(t0 + it * 3_600_000L, smb = it < 7) } // 7 SMBs, 3 manual
        )

        assertTrue(context.detectedClosedLoop)
        assertTrue(context.isClosedLoop)
        assertTrue(context.hasPerDoseAttribution)
        assertEquals(10, context.bolusCount)
        assertEquals(7, context.automaticBolusCount)
        assertEquals(3, context.userInitiatedBolusCount)
    }

    @Test
    fun `twenty temp basals prove a loop, nineteen do not`() {
        val over = (0 until 20).map { tempBasal(t0 + it * 3_600_000L) }
        val under = (0 until 19).map { tempBasal(t0 + it * 3_600_000L) }

        assertTrue(InsulinDeliveryContext(over).detectedClosedLoop)
        assertFalse(InsulinDeliveryContext(under).detectedClosedLoop)
        assertEquals(20, InsulinDeliveryContext(over).tempBasalCount)
    }

    @Test
    fun `a bolus rate no hand could produce proves a loop, the same count over a week does not`() {
        val oneDay = (0 until 13).map { bolus(t0 + it * 3_600_000L) } // 13 in ~12h → observedDays floors to 1
        val oneWeek = (0 until 13).map { bolus(t0 + it * (DAY / 2)) } // 13 across ~6 days

        val fast = InsulinDeliveryContext(oneDay)
        assertEquals(1.0, fast.observedDays, 0.0)
        assertEquals(13.0, fast.bolusesPerDay, 0.0)
        assertTrue(fast.detectedClosedLoop)

        val slow = InsulinDeliveryContext(oneWeek)
        assertTrue(slow.bolusesPerDay < 12)
        assertFalse(slow.detectedClosedLoop)
    }

    @Test
    fun `a row without any timestamp does not stretch the observed span`() {
        val rows = listOf(bolus(t0), bolus(t0 + DAY), NightscoutTreatment(eventType = "Note", notes = "undated"))
        assertEquals(1.0, InsulinDeliveryContext(rows).observedDays, 0.0)
    }

    // MARK: - The user's answer

    @Test
    fun `the user's answer wins in both directions and contradictions are flagged`() {
        val looped = (0 until 10).map { bolus(t0 + it * 3_600_000L, smb = true) }
        val manual = (0 until 3).map { bolus(t0 + it * DAY) }

        val saysPump = InsulinDeliveryContext(looped, InsulinTherapyType.PUMP)
        assertTrue(saysPump.detectedClosedLoop)
        assertFalse(saysPump.isClosedLoop)
        assertTrue(saysPump.therapyTypeContradictsData)

        val saysLoop = InsulinDeliveryContext(manual, InsulinTherapyType.CLOSED_LOOP)
        assertFalse(saysLoop.detectedClosedLoop)
        assertTrue(saysLoop.isClosedLoop)
        assertTrue(saysLoop.therapyTypeContradictsData)

        val agrees = InsulinDeliveryContext(manual, InsulinTherapyType.INJECTIONS)
        assertFalse(agrees.isClosedLoop)
        assertFalse(agrees.therapyTypeContradictsData)

        val unanswered = InsulinDeliveryContext(looped)
        assertTrue(unanswered.isClosedLoop)
        assertFalse(unanswered.therapyTypeContradictsData)
    }

    // MARK: - Uploaders

    @Test
    fun `uploaders are counted most frequent first and blank names are dropped`() {
        val rows = listOf(
            bolus(t0, by = "xDrip+"), bolus(t0 + 1, by = "Trio"), bolus(t0 + 2, by = "Trio"),
            bolus(t0 + 3, by = "  "), bolus(t0 + 4, by = null), bolus(t0 + 5, by = " Trio "),
        )
        val context = InsulinDeliveryContext(rows)

        assertEquals(listOf("Trio" to 3, "xDrip+" to 1), context.uploaders.map { it.name to it.count })
    }

    // MARK: - Briefings

    @Test
    fun `the clinical summary states what the data shows, in three shapes`() {
        val flagged = InsulinDeliveryContext((0 until 10).map { bolus(t0 + it * 3_600_000L, smb = it < 7) })
        assertEquals(
            "Insulin delivered via Trio. Automated closed loop: 7 of 10 doses in this period were delivered by the algorithm (SMB/automatic); 3 were user-initiated.",
            flagged.clinicalSummary,
        )

        val unflaggedLoop = InsulinDeliveryContext((0 until 25).map { tempBasal(t0 + it * 3_600_000L) })
        assertEquals(
            "Insulin delivered via AndroidAPS. An automated system is running (25 temp-basal adjustments this period). Individual doses are not flagged, so algorithm-delivered and user-initiated insulin cannot be separated here.",
            unflaggedLoop.clinicalSummary,
        )

        val manual = InsulinDeliveryContext((0 until 3).map { bolus(t0 + it * DAY, by = null) })
        assertEquals("Delivery app not recorded. No automation detected — insulin doses appear user-initiated.", manual.clinicalSummary)
    }

    @Test
    fun `the prompt briefing instructs the model and never names a system it has not seen`() {
        assertEquals("No treatment data — delivery method unknown.", InsulinDeliveryContext(emptyList()).promptSummary)

        val flagged = InsulinDeliveryContext((0 until 10).map { bolus(t0 + it * 3_600_000L, smb = it < 7) }).promptSummary
        assertTrue(flagged.startsWith("Uploaded by: Trio (10 events)"))
        assertTrue(flagged.contains("7 of 10 insulin doses are flagged by the uploader as automatic / SMB"))
        assertTrue(flagged.contains("The remaining 3 doses are the user's own"))

        val unflaggedLoop = InsulinDeliveryContext((0 until 25).map { tempBasal(t0 + it * 3_600_000L) }).promptSummary
        assertTrue(unflaggedLoop.contains("25 temp-basal adjustments in this period indicate an automated delivery system"))

        val manual = InsulinDeliveryContext((0 until 3).map { bolus(t0 + it * DAY, by = null) }).promptSummary
        assertTrue(manual.startsWith("Uploading app: not recorded."))
        assertTrue(manual.contains("No automation markers found."))
    }
}
