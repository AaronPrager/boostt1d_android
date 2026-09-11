package com.boostt1d.android

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.engine.BasalDeliveryCalculator
import com.boostt1d.android.engine.TherapyProfileSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The basal reconstruction behind every TDD the app prints. No iOS test file; these pin the
 * behaviour the port was read from.
 */
class BasalDeliveryCalculatorTest {

    private val zone: TimeZone = TimeZone.getTimeZone("America/New_York")
    private val dayStart: Long = Calendar.getInstance(zone)
        .apply { clear(); timeZone = zone; set(2025, Calendar.JUNE, 16, 0, 0, 0) }.timeInMillis

    private fun at(hour: Int, minute: Int = 0): Long =
        dayStart + hour * 3_600_000L + minute * 60_000L

    /** A flat schedule, one rate all day. */
    private fun flatProfile(rate: Double): NightscoutProfileDocument =
        profile(listOf(TimeValue("00:00", rate)))

    private fun profile(basal: List<TimeValue>) = NightscoutProfileDocument(
        id = null,
        defaultProfile = "Default",
        store = mapOf(
            "Default" to ProfileStoreEntry(
                units = "mg/dl", dia = 4.0, basal = basal,
                carbRatio = listOf(TimeValue("00:00", 15.0)),
                sensitivity = listOf(TimeValue("00:00", 50.0)),
                targetLow = emptyList(), targetHigh = emptyList(),
            ),
        ),
        mills = null, startDate = null, createdAt = null, units = "mg/dl",
    )

    private fun settings(document: NightscoutProfileDocument?) = TherapyProfileSettings(document)

    @Test
    fun `a whole day at a flat rate is that rate times twenty-four`() {
        val s = settings(flatProfile(1.0))
        val units = BasalDeliveryCalculator.units(dayStart, dayStart + 86_400_000L, emptyList(), s, zone)
        assertEquals(24.0, units!!, 0.0001)
    }

    @Test
    fun `a part day counts only the hours it covers`() {
        val s = settings(flatProfile(0.5))
        val units = BasalDeliveryCalculator.units(dayStart, at(6), emptyList(), s, zone)
        assertEquals(3.0, units!!, 0.0001)
    }

    @Test
    fun `a schedule that changes on the clock is walked hour by hour`() {
        // 0.5 U/hr until 12:00, 1.5 U/hr after. A whole day is 6 + 18 = 24 units.
        val s = settings(profile(listOf(TimeValue("00:00", 0.5), TimeValue("12:00", 1.5))))
        val units = BasalDeliveryCalculator.units(dayStart, dayStart + 86_400_000L, emptyList(), s, zone)
        assertEquals(24.0, units!!, 0.0001)
    }

    @Test
    fun `a temp basal is time-weighted over the part of the hour it covers`() {
        val s = settings(flatProfile(1.0))
        // Two hours at 2.0 U/hr from 02:00. Twenty-two profile hours plus four temp units.
        val temps = BasalDeliveryCalculator.intervals(
            listOf(NightscoutTreatment(eventType = "Temp Basal", mills = at(2), duration = 120, absolute = 2.0)),
            s, zone,
        )
        val units = BasalDeliveryCalculator.units(dayStart, dayStart + 86_400_000L, temps, s, zone)
        assertEquals(26.0, units!!, 0.0001)
    }

    @Test
    fun `a zero-rate temp basal is a suspend and delivers nothing while it runs`() {
        val s = settings(flatProfile(1.0))
        val temps = BasalDeliveryCalculator.intervals(
            listOf(NightscoutTreatment(eventType = "Temp Basal", mills = at(1), duration = 180, absolute = 0.0)),
            s, zone,
        )
        val units = BasalDeliveryCalculator.units(dayStart, dayStart + 86_400_000L, temps, s, zone)
        assertEquals(21.0, units!!, 0.0001)
    }

    @Test
    fun `a temp basal crossing an hour boundary splits its units across both hours`() {
        val s = settings(flatProfile(1.0))
        // 30 minutes at 3.0 U/hr from 05:45: 1.5 units instead of the 0.5 the profile would give.
        val temps = BasalDeliveryCalculator.intervals(
            listOf(NightscoutTreatment(eventType = "Temp Basal", mills = at(5, 45), duration = 30, absolute = 3.0)),
            s, zone,
        )
        val units = BasalDeliveryCalculator.units(at(5), at(7), temps, s, zone)
        assertEquals(2.0 - 0.5 + 1.5, units!!, 0.0001)
    }

    @Test
    fun `a profile with no rate for an hour reports nothing rather than a partial day`() {
        assertNull(BasalDeliveryCalculator.units(dayStart, at(6), emptyList(), settings(null), zone))
    }

    @Test
    fun `scheduled basal counts on a pump and on a loop`() {
        val s = settings(flatProfile(1.0))
        assertTrue(BasalDeliveryCalculator.countsScheduledBasal(emptyList(), InsulinTherapyType.PUMP, s))
        assertTrue(BasalDeliveryCalculator.countsScheduledBasal(emptyList(), InsulinTherapyType.CLOSED_LOOP, s))
    }

    @Test
    fun `scheduled basal never counts on injections, because the long-acting dose is a treatment`() {
        val s = settings(flatProfile(1.0))
        assertFalse(BasalDeliveryCalculator.countsScheduledBasal(emptyList(), InsulinTherapyType.INJECTIONS, s))
    }

    @Test
    fun `unspecified therapy reads a temp basal record as the evidence of a pump`() {
        val s = settings(flatProfile(1.0))
        assertFalse(BasalDeliveryCalculator.countsScheduledBasal(emptyList(), InsulinTherapyType.UNSPECIFIED, s))
        assertTrue(
            BasalDeliveryCalculator.countsScheduledBasal(
                listOf(NightscoutTreatment(eventType = "Temp Basal", mills = at(3), duration = 30, absolute = 1.2)),
                InsulinTherapyType.UNSPECIFIED, s,
            ),
        )
    }

    @Test
    fun `an empty basal schedule counts for nobody`() {
        val s = settings(null)
        assertFalse(BasalDeliveryCalculator.countsScheduledBasal(emptyList(), InsulinTherapyType.PUMP, s))
    }
}
