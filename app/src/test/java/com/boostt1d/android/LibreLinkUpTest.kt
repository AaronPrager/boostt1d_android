package com.boostt1d.android

import com.boostt1d.android.sync.LibreLinkUpService
import com.boostt1d.android.sync.LibreMeasurement
import com.boostt1d.android.sync.LibreRegion
import com.boostt1d.android.sync.LibreTranslator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LibreLinkUp is an unofficial API whose payloads differ by region and client version.
 * Each shape here is one a real account returns.
 */
class LibreLinkUpTest {

    private val service = LibreLinkUpService()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the factory timestamp format is parsed as UTC`() {
        // 8/28/2025 12:00:00 PM UTC
        assertEquals(1756382400000L, LibreTranslator.parseUtcMillis("8/28/2025 12:00:00 PM"))
        // Some regions use a 24-hour clock.
        assertEquals(1756382400000L, LibreTranslator.parseUtcMillis("8/28/2025 12:00:00"))
        assertNull(LibreTranslator.parseUtcMillis(""))
        assertNull(LibreTranslator.parseUtcMillis("yesterday"))
    }

    @Test
    fun `five arrows map to five directions and nothing else`() {
        assertEquals("SingleDown", LibreTranslator.direction(1))
        assertEquals("Flat", LibreTranslator.direction(3))
        assertEquals("SingleUp", LibreTranslator.direction(5))
        // Libre never produces a double arrow, and 0 is "no arrow".
        assertNull(LibreTranslator.direction(0))
        assertNull(LibreTranslator.direction(6))
    }

    @Test
    fun `ValueInMgPerDl is used when present`() {
        val obj = json.parseToJsonElement(
            """{"ValueInMgPerDl":142,"TrendArrow":3,"FactoryTimestamp":"8/28/2025 12:00:00 PM","isHigh":false,"isLow":false}"""
        ).jsonObject

        val m = service.parseMeasurement(obj)!!

        assertEquals(142, m.valueMgdl)
        assertEquals("Flat", m.nightscoutDirection)
        assertEquals(1756382400000L, m.millis)
    }

    @Test
    fun `a payload carrying only a mmol Value is converted`() {
        // GlucoseUnits 0 means mmol/L. 7.9 mmol/L is 142 mg/dL.
        val obj = json.parseToJsonElement(
            """{"Value":7.9,"GlucoseUnits":0,"FactoryTimestamp":"8/28/2025 12:00:00 PM"}"""
        ).jsonObject

        assertEquals(142, service.parseMeasurement(obj)!!.valueMgdl)
    }

    @Test
    fun `a payload carrying only a mg per dL Value is taken as-is`() {
        val obj = json.parseToJsonElement(
            """{"Value":142,"GlucoseUnits":1,"FactoryTimestamp":"8/28/2025 12:00:00 PM"}"""
        ).jsonObject

        assertEquals(142, service.parseMeasurement(obj)!!.valueMgdl)
    }

    @Test
    fun `a measurement with no value at all is dropped`() {
        val obj = json.parseToJsonElement("""{"FactoryTimestamp":"8/28/2025 12:00:00 PM"}""").jsonObject
        assertNull(service.parseMeasurement(obj))
    }

    @Test
    fun `the graph payload yields its points plus the current reading`() {
        val body = """
            {"status":0,"data":{
              "connection":{"glucoseMeasurement":{"ValueInMgPerDl":142,"TrendArrow":4,"FactoryTimestamp":"8/28/2025 12:00:00 PM"}},
              "graphData":[
                {"ValueInMgPerDl":120,"FactoryTimestamp":"8/28/2025 11:45:00 AM"},
                {"ValueInMgPerDl":130,"FactoryTimestamp":"8/28/2025 11:50:00 AM"}
              ]}}
        """.trimIndent()

        val measurements = service.parseGraph(body)

        assertEquals(3, measurements.size)
        // Graph points carry no arrow; only the current reading does.
        assertEquals(listOf(null, null, 4), measurements.map { it.trendArrow })
    }

    @Test
    fun `the current reading wins a timestamp it shares with a graph point`() {
        val graphPoint = LibreMeasurement(142, null, "8/28/2025 12:00:00 PM", null)
        val current = LibreMeasurement(142, 3, "8/28/2025 12:00:00 PM", null)

        val entries = LibreTranslator.entries(listOf(graphPoint, current))

        assertEquals(1, entries.size)
        assertEquals("Flat", entries.single().direction)
    }

    @Test
    fun `entries come back newest first`() {
        val entries = LibreTranslator.entries(
            listOf(
                LibreMeasurement(120, null, "8/28/2025 11:45:00 AM", null),
                LibreMeasurement(142, 3, "8/28/2025 12:00:00 PM", null),
            )
        )

        assertEquals(listOf(142, 120), entries.map { it.sgv })
        assertEquals(LibreMeasurement.DEVICE, entries.first().device)
    }

    @Test
    fun `connections parse with their patient id and name`() {
        val body = """
            {"status":0,"data":[{"patientId":"abc-123","firstName":"Sam","lastName":"Lee",
              "glucoseMeasurement":{"ValueInMgPerDl":110,"FactoryTimestamp":"8/28/2025 12:00:00 PM"}}]}
        """.trimIndent()

        val connections = service.parseConnections(body)

        assertEquals("abc-123", connections.single().patientId)
        assertEquals("Sam Lee", connections.single().displayName)
        assertEquals(110, connections.single().current!!.valueMgdl)
    }

    @Test
    fun `a connection with no name still has something to call it`() {
        val body = """{"status":0,"data":[{"patientId":"abc"}]}"""
        assertEquals("your Libre sensor", service.parseConnections(body).single().displayName)
    }

    @Test
    fun `the version floor is recognised wherever it appears`() {
        assertEquals("4.16.0", service.minimumVersionRequired("""{"status":920,"data":{"minimumVersion":"4.16.0"}}"""))
        assertNull(service.minimumVersionRequired("""{"status":0,"data":{}}"""))
        assertNull(service.minimumVersionRequired("not json"))
    }

    @Test
    fun `regions build their own host, and the redirect code resolves back to one`() {
        assertEquals("https://api.libreview.io", LibreRegion.AUTOMATIC.apiHost)
        assertEquals("https://api-eu.libreview.io", LibreRegion.EU.apiHost)
        assertEquals("https://api.libreview.ru", LibreRegion.RU.apiHost)
        assertEquals(LibreRegion.DE, LibreRegion.fromCode(" DE "))
        // "automatic" as a redirect target would loop; it resolves to nothing.
        assertNull(LibreRegion.fromCode("automatic"))
        assertNull(LibreRegion.fromCode("mars"))
    }

    @Test
    fun `the Account-Id header is the SHA-256 of the user id`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LibreLinkUpService.sha256("abc"),
        )
    }

    @Test
    fun `Libre's window is half of Dexcom's`() {
        assertTrue(com.boostt1d.android.data.GlucoseConnectionOption.LIBRE.historyWindowHours!! <
            com.boostt1d.android.data.GlucoseConnectionOption.DEXCOM.historyWindowHours!!)
    }
}
