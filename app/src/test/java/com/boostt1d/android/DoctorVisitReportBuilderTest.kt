package com.boostt1d.android

import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.engine.DoctorVisitPeriod
import com.boostt1d.android.engine.DoctorVisitReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** No iOS test file; these pin the behaviour the port was read from. */
class DoctorVisitReportBuilderTest {

    private val zone: TimeZone = TimeZone.getDefault()
    private val now: Long = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 12, 0, 0) }.timeInMillis
    private fun at(daysBack: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_MONTH, -daysBack); add(Calendar.MINUTE, minute) }.timeInMillis

    /** [days] days ending now; [value] gets (daysBack, minuteOfDay). */
    private fun entries(days: Int, value: (Int, Int) -> Double): List<NightscoutGlucoseEntry> =
        (days downTo 0).flatMap { back -> (0 until 288).mapNotNull { step -> val t = at(back, step * 5); if (t > now) null else NightscoutGlucoseEntry(sgv = value(back, step * 5).toInt(), date = t, device = "t") } }

    @Test
    fun `a full prior period is preferred, otherwise the period is split in half`() {
        val flat = entries(14) { _, _ -> 130.0 }
        val withPrior = DoctorVisitReportBuilder.build(flat, emptyList(), DoctorVisitPeriod.DAYS_7, 70.0, 180.0, null, nowMillis = now, timeZone = zone)
        assertTrue(withPrior.hasEnoughData)
        assertNotNull(withPrior.previous)
        assertFalse(withPrior.usesHalfPeriodComparison)
        assertEquals("Time in range: similar to prior period", withPrior.changeSummaryLines.first())

        val only7 = entries(6) { back, _ -> if (back >= 4) 220.0 else 130.0 } // nothing before the period; first half high, second half in range
        val halved = DoctorVisitReportBuilder.build(only7, emptyList(), DoctorVisitPeriod.DAYS_7, 70.0, 180.0, null, nowMillis = now, timeZone = zone)
        assertTrue(halved.usesHalfPeriodComparison)
        assertNotNull(halved.previous)
        assertTrue(halved.changeSummaryLines.first().startsWith("Time in range: +"))
        assertTrue(halved.changeSummaryLines.first().endsWith("(improved)"))
    }

    @Test
    fun `recurrent highs merge abutting three-hour windows into one distinct block`() {
        // 240 from 14:00 to 20:00 every day, 120 otherwise.
        val data = entries(7) { _, minute -> if (minute in 14 * 60 until 20 * 60) 240.0 else 120.0 }
        val report = DoctorVisitReportBuilder.build(data, emptyList(), DoctorVisitPeriod.DAYS_7, 70.0, 180.0, null, nowMillis = now, timeZone = zone)

        assertEquals(1, report.recurrentHighBlocks.size)
        val block = report.recurrentHighBlocks.single()
        assertEquals(14, block.hourStart); assertEquals(20, block.hourEnd)
        assertEquals("High 2pm–8pm", block.label)
        assertTrue(block.percent > 95)
        assertTrue(report.recurrentLowBlocks.isEmpty())
        // Midnight wraps into one span, and noon reads as 12pm.
        assertEquals(listOf(22 to 4), DoctorVisitReportBuilder.contiguousHourSpans(setOf(22, 23, 0, 1)))
    }

    @Test
    fun `daily profiles run newest first and count each day's doses and meals`() {
        val treatments = listOf(
            NightscoutTreatment(eventType = "Meal Bolus", mills = at(1, 12 * 60), insulin = 4.0, carbs = 50.0),
            NightscoutTreatment(eventType = "Correction Bolus", mills = at(1, 16 * 60), insulin = 1.5),
            NightscoutTreatment(eventType = "Exercise", mills = at(2, 17 * 60)),
        )
        val report = DoctorVisitReportBuilder.build(entries(7) { _, _ -> 140.0 }, treatments, DoctorVisitPeriod.DAYS_7, 70.0, 180.0, null, nowMillis = now, timeZone = zone)

        assertEquals(8, report.dailyProfiles.size) // start day through end day, inclusive
        assertEquals(at(0, 0), report.dailyProfiles.first().dayStartMillis)
        val yesterday = report.dailyProfiles[1]
        assertEquals(5.5, yesterday.insulinUnits, 0.0); assertEquals(50.0, yesterday.carbsGrams, 0.0)
        assertEquals(1, yesterday.mealCount); assertEquals(2, yesterday.bolusCount)
        assertEquals(1, report.exerciseCount); assertEquals(5.5, report.totalInsulinUnits, 0.0)
        assertNull(report.exerciseAssociatedDeltaMgdL) // one exercise event is not enough
        assertTrue(report.deliverySummary.contains("No automation detected"))
        assertFalse(report.plainLanguageSummary.isEmpty())
    }
}
