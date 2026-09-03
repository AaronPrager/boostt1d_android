package com.boostt1d.android

import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.RegistrationPayloads
import com.boostt1d.android.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RegistrationPayloadTest {

    private val zone: TimeZone = TimeZone.getTimeZone("UTC")
    private fun date(y: Int, m: Int, d: Int): Long = Calendar.getInstance(zone).apply { clear(); set(y, m, d) }.timeInMillis
    private val now = date(2026, Calendar.SEPTEMBER, 3)

    @Test
    fun `age and years-since-diagnosis are whole years, bucketed as iOS buckets them`() {
        val profile = UserProfile(name = " Aaron ", dateOfBirthEpochMillis = date(1990, Calendar.OCTOBER, 1), dateOfDiagnosisEpochMillis = date(2021, Calendar.JANUARY, 1), hasDiabetes = true, country = "United States", state = "MA")
        val payload = RegistrationPayloads.from(profile, GlucoseSettings(connection = GlucoseConnectionOption.MANUAL), marketingOptIn = false, deviceModel = "sdk_gphone", appVersion = "0.1", nowMillis = now, timeZone = zone)!!

        assertEquals("Aaron", payload.name)
        assertEquals(35, payload.age)
        assertEquals("3-9", payload.yearsSinceDiagnosis)
        assertEquals(5, payload.diabetesAge)
        assertEquals("manual", payload.connectivityMethod)
        assertNull(payload.connectivityLogin)
        assertNull(payload.email)
    }

    @Test
    fun `connectivity carries a public identifier and never a token`() {
        assertEquals("nightscout" to "https://x.nightscoutpro.com", RegistrationPayloads.connectivityFields(GlucoseConnectionOption.NIGHTSCOUT, "https://x.nightscoutpro.com", "", ""))
        assertEquals("dexcom" to "user@example.com", RegistrationPayloads.connectivityFields(GlucoseConnectionOption.DEXCOM, "", "user@example.com", ""))
        assertEquals("libre" to "libre@example.com", RegistrationPayloads.connectivityFields(GlucoseConnectionOption.LIBRE, "", "", "libre@example.com"))
        assertEquals("manual" to null, RegistrationPayloads.connectivityFields(GlucoseConnectionOption.MANUAL, "", "", ""))
    }

    @Test
    fun `no name means no payload, and no diabetes means the none bucket`() {
        assertNull(RegistrationPayloads.from(UserProfile(name = "  "), GlucoseSettings(), false, null, null, now, zone))
        val none = RegistrationPayloads.from(UserProfile(name = "Sam", hasDiabetes = false, dateOfBirthEpochMillis = date(2000, Calendar.JANUARY, 1)), GlucoseSettings(), true, null, null, now, zone)!!
        assertEquals("none", none.yearsSinceDiagnosis)
        assertNull(none.diabetesAge)
        assertEquals(true, none.marketingOptIn)
    }
}
