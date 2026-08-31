package com.boostt1d.android

import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.data.UserProfile
import com.boostt1d.android.sync.DexcomRegion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to data already on someone's phone when a stored shape gains a field.
 *
 * This is the failure that does not look like a failure: an undecodable profile reads as
 * "no profile", which sends a returning user back through setup as though they were new,
 * with their readings still on disk and invisible. Every payload below is what an earlier
 * version of the app actually wrote.
 */
class StoredShapeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `settings written before Dexcom existed still decode`() {
        val stored = """
            {"connection":"nightscout","lowGlucose":70.0,"highGlucose":180.0,
             "nightscoutUrl":"https://example.com","lastSyncMillis":1756382400000}
        """.trimIndent()

        val settings = json.decodeFromString<GlucoseSettings>(stored)

        assertEquals(GlucoseConnectionOption.NIGHTSCOUT, settings.connection)
        assertEquals("https://example.com", settings.nightscoutUrl)
        assertEquals(1756382400000L, settings.lastSyncMillis)
        // The new fields take their defaults rather than failing the whole value.
        assertEquals("", settings.dexcomUsername)
        assertEquals(DexcomRegion.US, settings.dexcomRegion)
    }

    @Test
    fun `settings written before Nightscout existed still decode`() {
        val stored = """{"connection":"manual","lowGlucose":70.0,"highGlucose":180.0}"""

        val settings = json.decodeFromString<GlucoseSettings>(stored)

        assertTrue(settings.isManualMode)
        assertEquals("", settings.nightscoutUrl)
        assertEquals(0L, settings.lastSyncMillis)
    }

    @Test
    fun `a profile from the first build still decodes`() {
        val stored = """
            {"id":"abc","name":"Aaron","country":"United States","countryCode":"US",
             "dateOfBirthEpochMillis":1199145600000,"dateOfDiagnosisEpochMillis":1609459200000,
             "hasDiabetes":true,"isProfileComplete":true,"createdAtEpochMillis":1756382400000,
             "updatedAtEpochMillis":1756382400000,"bgUnit":"mg/dL","marketingOptIn":false}
        """.trimIndent()

        val profile = json.decodeFromString<UserProfile>(stored)

        assertEquals("Aaron", profile.name)
        assertTrue(profile.isProfileComplete)
        assertEquals(BGUnit.MGDL, profile.bgUnit)
        // Absent optional fields must not fail the decode; they are simply unset.
        assertEquals(null, profile.email)
        assertEquals("", profile.yearsSinceDiagnosisBucket)
    }

    @Test
    fun `an unknown field from a newer build is ignored, not fatal`() {
        // A user who downgrades must not lose their profile to a field we added later.
        val stored = """
            {"id":"abc","name":"Aaron","country":"US","countryCode":"US",
             "dateOfBirthEpochMillis":0,"dateOfDiagnosisEpochMillis":0,"hasDiabetes":true,
             "isProfileComplete":true,"createdAtEpochMillis":0,"updatedAtEpochMillis":0,
             "bgUnit":"mg/dL","marketingOptIn":false,"somethingFromTheFuture":{"a":1}}
        """.trimIndent()

        assertEquals("Aaron", json.decodeFromString<UserProfile>(stored).name)
    }

    @Test
    fun `a therapy profile written by hand decodes with the source it was given`() {
        val stored = """
            {"basal":[{"time":"00:00","value":0.8}],"carbRatio":[{"time":"00:00","value":10.0}],
             "sensitivity":[],"targetLow":[],"targetHigh":[],"source":"MANUAL","updatedAtMillis":0}
        """.trimIndent()

        val therapy = json.decodeFromString<TherapyProfile>(stored)

        assertEquals(TherapyProfile.Source.MANUAL, therapy.source)
        assertEquals(0.8, therapy.basal.single().value, 0.0001)
    }

    @Test
    fun `every stored shape survives its own round trip`() {
        val settings = GlucoseSettings(
            connection = GlucoseConnectionOption.DEXCOM,
            dexcomUsername = "someone",
            dexcomRegion = DexcomRegion.OUS,
            lastSyncMillis = 1756382400000,
        )

        assertEquals(settings, json.decodeFromString<GlucoseSettings>(json.encodeToString(settings)))
    }
}
