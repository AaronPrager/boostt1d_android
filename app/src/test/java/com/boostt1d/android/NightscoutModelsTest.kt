package com.boostt1d.android

import com.boostt1d.android.data.EventType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.data.TherapyProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire types. Nightscout's JSON is inconsistent across uploaders, and these are the
 * variations iOS learned to absorb — a single oddly-typed field must not fail a payload.
 */
class NightscoutModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `seconds and milliseconds timestamps both normalize to milliseconds`() {
        val millis = NightscoutGlucoseEntry(sgv = 120, date = 1_756_387_200_000L)
        val seconds = NightscoutGlucoseEntry(sgv = 120, date = 1_756_387_200L)

        assertEquals(1_756_387_200_000L, millis.epochMilliseconds)
        assertEquals(1_756_387_200_000L, seconds.epochMilliseconds)
    }

    @Test
    fun `mills stands in when date is absent`() {
        val entry = NightscoutGlucoseEntry(sgv = 120, mills = 1_756_387_200_000L)

        assertEquals(1_756_387_200_000L, entry.epochMilliseconds)
    }

    @Test
    fun `sgv decodes from a number or a quoted string`() {
        val asNumber = json.decodeFromString<NightscoutGlucoseEntry>(
            """{"sgv":142,"date":1756387200000}"""
        )
        val asString = json.decodeFromString<NightscoutGlucoseEntry>(
            """{"sgv":"142","date":"1756387200000"}"""
        )

        assertEquals(142, asNumber.sgv)
        assertEquals(142, asString.sgv)
        assertEquals(asNumber.epochMilliseconds, asString.epochMilliseconds)
    }

    @Test
    fun `treatment numbers decode from strings`() {
        val treatment = json.decodeFromString<NightscoutTreatment>(
            """{"eventType":"Meal Bolus","insulin":"2.5","carbs":"45","mills":"1756387200000"}"""
        )

        assertEquals(2.5, treatment.insulin!!, 0.0001)
        assertEquals(45.0, treatment.carbs!!, 0.0001)
        assertEquals(1_756_387_200_000L, treatment.mills)
    }

    @Test
    fun `glucose decodes from string, int or double`() {
        fun glucoseOf(raw: String) =
            json.decodeFromString<NightscoutTreatment>("""{"glucose":$raw}""").glucose

        assertEquals("120", glucoseOf("\"120\""))
        assertEquals("120", glucoseOf("120"))
        assertEquals("120", glucoseOf("120.0"))
        assertNull(glucoseOf("\"\""))
    }

    @Test
    fun `an unflagged bolus is never assumed to be the algorithm's`() {
        val manual = NightscoutTreatment(eventType = EventType.BOLUS, insulin = 2.0)
        val smb = NightscoutTreatment(eventType = EventType.BOLUS, insulin = 0.2, isSMB = true)
        val automatic = NightscoutTreatment(eventType = EventType.BOLUS, insulin = 0.3, automatic = true)

        assertFalse(manual.isAlgorithmDelivered)
        assertTrue(smb.isAlgorithmDelivered)
        assertTrue(automatic.isAlgorithmDelivered)
    }

    @Test
    fun `cache key prefers the Nightscout id, then id, then a fingerprint`() {
        assertTrue(NightscoutTreatment(mongoId = "abc", id = "def").cacheKey.startsWith("ns:"))
        assertTrue(NightscoutTreatment(id = "def").cacheKey.startsWith("id:"))
        assertTrue(NightscoutTreatment(mongoId = "  ", insulin = 2.0).cacheKey.startsWith("f:"))
    }

    @Test
    fun `two identical unflagged treatments share a fingerprint`() {
        val a = NightscoutTreatment(eventType = EventType.BOLUS, insulin = 2.0, mills = 1_000)
        val b = NightscoutTreatment(eventType = EventType.BOLUS, insulin = 2.0, mills = 1_000)

        assertEquals(a.cacheKey, b.cacheKey)
    }

    @Test
    fun `carb-only rows leave the event log but insulin and exercise stay`() {
        val carbsOnly = NightscoutTreatment(eventType = EventType.CARB_CORRECTION, carbs = 30.0)
        val withInsulin = NightscoutTreatment(eventType = EventType.MEAL_BOLUS, carbs = 30.0, insulin = 3.0)
        val exercise = NightscoutTreatment(eventType = EventType.EXERCISE, carbs = 15.0)
        val tempBasal = NightscoutTreatment(eventType = EventType.TEMP_BASAL, carbs = 15.0)

        assertTrue(carbsOnly.isCarbOnlyEventLogRow)
        assertFalse(withInsulin.isCarbOnlyEventLogRow)
        assertFalse(exercise.isCarbOnlyEventLogRow)
        assertFalse(tempBasal.isCarbOnlyEventLogRow)
    }

    @Test
    fun `a scheduled value covers from its start until the next one`() {
        val profile = TherapyProfile(
            carbRatio = listOf(TimeValue("00:00", 12.0), TimeValue("06:00", 8.0), TimeValue("22:00", 10.0))
        )

        assertEquals(12.0, profile.valueAt(profile.carbRatio, 0)!!, 0.0)
        assertEquals(12.0, profile.valueAt(profile.carbRatio, 5 * 60 + 59)!!, 0.0)
        assertEquals(8.0, profile.valueAt(profile.carbRatio, 6 * 60)!!, 0.0)
        assertEquals(10.0, profile.valueAt(profile.carbRatio, 23 * 60)!!, 0.0)
    }

    @Test
    fun `before the first boundary belongs to the segment that wrapped midnight`() {
        val profile = TherapyProfile(carbRatio = listOf(TimeValue("06:00", 8.0), TimeValue("22:00", 10.0)))

        assertEquals(10.0, profile.valueAt(profile.carbRatio, 60)!!, 0.0)
    }
}
