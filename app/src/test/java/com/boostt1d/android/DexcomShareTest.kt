package com.boostt1d.android

import com.boostt1d.android.sync.DexcomEgv
import com.boostt1d.android.sync.DexcomRegion
import com.boostt1d.android.sync.DexcomShareService
import com.boostt1d.android.sync.DexcomSharePath
import com.boostt1d.android.sync.DexcomTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dexcom Share is an undocumented private API and its responses vary by account, region
 * and app version. These pin what the client has to absorb — every one of these shapes is
 * something a real account returns.
 */
class DexcomShareTest {

    private val service = DexcomShareService()

    @Test
    fun `the Microsoft date format Share actually sends is understood`() {
        assertEquals(1756382400000L, DexcomTranslator.parseMilliseconds("/Date(1756382400000)/"))
        assertEquals(1756382400000L, DexcomTranslator.parseMilliseconds("Date(1756382400000)"))
        // Some accounts include a timezone offset after the millis.
        assertEquals(1756382400000L, DexcomTranslator.parseMilliseconds("/Date(1756382400000+0000)/"))
    }

    @Test
    fun `plain epoch and ISO-8601 timestamps are also accepted`() {
        assertEquals(1756382400000L, DexcomTranslator.parseMilliseconds("1756382400000"))
        assertEquals(1756382400000L, DexcomTranslator.parseMilliseconds("2025-08-28T12:00:00.000Z"))
        assertNull(DexcomTranslator.parseMilliseconds(""))
        assertNull(DexcomTranslator.parseMilliseconds("not a date"))
    }

    @Test
    fun `trend maps both ways between the integer and string forms`() {
        assertEquals("Flat", DexcomTranslator.directionFromCode(4))
        assertEquals(4, DexcomTranslator.codeFromName("Flat"))
        assertEquals("DoubleUp", DexcomTranslator.directionFromCode(1))
        assertEquals("DoubleDown", DexcomTranslator.directionFromCode(7))
    }

    @Test
    fun `a trend Share could not compute has no direction, not a flat one`() {
        // Showing an arrow for "NotComputable" would invent a trend nobody measured.
        assertNull(DexcomTranslator.directionFromName("NotComputable"))
        assertNull(DexcomTranslator.directionFromName("None"))
        assertNull(DexcomTranslator.directionFromName("RateOutOfRange"))
        assertNull(DexcomTranslator.directionFromCode(0))
        assertNull(DexcomTranslator.directionFromCode(9))
    }

    @Test
    fun `readings parse with an integer trend`() {
        val body = """
            [{"WT":"Date(1756382400000)","ST":"Date(1756382400000)","DT":"Date(1756382400000+0000)","Value":142,"Trend":4}]
        """.trimIndent()

        val readings = service.parseReadings(body)

        assertEquals(1, readings.size)
        assertEquals(142, readings[0].value)
        assertEquals("Flat", readings[0].nightscoutDirection)
    }

    @Test
    fun `readings parse with a string trend`() {
        val body = """
            [{"ST":"/Date(1756382400000)/","Value":98,"Trend":"FortyFiveDown"}]
        """.trimIndent()

        val readings = service.parseReadings(body)

        assertEquals("FortyFiveDown", readings[0].nightscoutDirection)
        assertEquals(5, readings[0].trend)
    }

    @Test
    fun `a reading with no usable timestamp is dropped, not dated to now`() {
        val entry = DexcomEgv(value = 120, trend = 4, trendDirection = "Flat", systemTime = "").toEntry()

        assertNull(entry)
    }

    @Test
    fun `entries come back newest first and shaped like Nightscout`() {
        val body = """
            [
              {"ST":"Date(1756382100000)","Value":120,"Trend":4},
              {"ST":"Date(1756382400000)","Value":142,"Trend":2}
            ]
        """.trimIndent()

        val entries = DexcomTranslator.entries(service.parseReadings(body))

        assertEquals(listOf(142, 120), entries.map { it.sgv })
        assertEquals("SingleUp", entries[0].direction)
        assertEquals(DexcomEgv.DEVICE, entries[0].device)
    }

    @Test
    fun `a malformed row does not lose the rest of the download`() {
        val body = """
            [
              {"ST":"Date(1756382400000)","Value":142,"Trend":4},
              {"Value":"nonsense"},
              {"ST":"Date(1756382100000)","Value":120}
            ]
        """.trimIndent()

        assertEquals(2, service.parseReadings(body).size)
    }

    @Test
    fun `regions do not share hosts, because the accounts do not federate`() {
        assertTrue(DexcomRegion.US.hosts.first().contains("share2.dexcom.com"))
        assertTrue(DexcomRegion.OUS.hosts.first().contains("shareous1"))
        assertTrue(DexcomRegion.JAPAN.hosts.first().contains("dexcom.jp"))
        assertTrue(DexcomRegion.US.hosts.intersect(DexcomRegion.OUS.hosts.toSet()).isEmpty())
    }

    @Test
    fun `Japan tries Dexcom's own application id first`() {
        assertEquals(DexcomRegion.DEXCOM_APP_ID, DexcomRegion.JAPAN.applicationIds.first())
        assertEquals(DexcomRegion.NIGHTSCOUT_BRIDGE_APP_ID, DexcomRegion.US.applicationIds.first())
    }

    @Test
    fun `region is suggested from the country but stays overridable`() {
        assertEquals(DexcomRegion.US, DexcomRegion.suggestedFor("US"))
        assertEquals(DexcomRegion.JAPAN, DexcomRegion.suggestedFor("JP"))
        assertEquals(DexcomRegion.OUS, DexcomRegion.suggestedFor("GB"))
        assertEquals(DexcomRegion.US, DexcomRegion.suggestedFor(null))
    }

    @Test
    fun `the endpoint is built under the Share services path`() {
        assertEquals(
            "https://share2.dexcom.com/ShareWebServices/Services/General/LoginPublisherAccountById",
            DexcomSharePath.url("https://share2.dexcom.com", DexcomSharePath.LOGIN_BY_ID),
        )
    }
}
