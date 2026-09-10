package com.boostt1d.android

import com.boostt1d.android.data.EventType
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.sync.NightscoutService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing what real Nightscout sites actually return.
 *
 * The payloads here are shaped like the ones different uploaders emit — xDrip, Loop,
 * AndroidAPS and the Nightscout web UI all differ in which fields they write and what
 * type they write them as. A single odd field must never fail a whole download, because
 * the readings behind it are only recoverable inside the CGM's history window.
 */
class NightscoutParsingTest {

    private val service = NightscoutService()

    @Test
    fun `a standard entries payload parses`() {
        val body = """
            [
              {"_id":"a1","sgv":142,"date":1756382400000,"dateString":"2025-08-28T12:00:00.000Z",
               "direction":"Flat","type":"sgv","device":"xDrip-DexcomG6","noise":1},
              {"_id":"a2","sgv":128,"date":1756382100000,"direction":"FortyFiveDown","type":"sgv"}
            ]
        """.trimIndent()

        val entries = service.parseGlucose(body)

        assertEquals(2, entries.size)
        assertEquals(142, entries[0].sgv)
        assertEquals("Flat", entries[0].direction)
        assertEquals(1756382400000L, entries[0].epochMilliseconds)
        assertEquals("xDrip-DexcomG6", entries[0].device)
    }

    @Test
    fun `entries survive quoted numbers and a mills-only timestamp`() {
        val body = """
            [
              {"sgv":"142","mills":1756382400000},
              {"sgv":95,"date":"1756382100000"}
            ]
        """.trimIndent()

        val entries = service.parseGlucose(body)

        assertEquals(2, entries.size)
        assertEquals(142, entries[0].sgv)
        assertEquals(1756382400000L, entries[0].epochMilliseconds)
        assertEquals(1756382100000L, entries[1].epochMilliseconds)
    }

    @Test
    fun `an unknown field does not fail the payload`() {
        // Uploaders add their own fields freely; ignoring them is the whole point.
        val body = """[{"sgv":142,"date":1756382400000,"somethingNew":{"nested":true}}]"""

        assertEquals(1, service.parseGlucose(body).size)
    }

    @Test
    fun `the tab-separated format some older sites return is accepted`() {
        val body = "2025-08-28T12:00:00\t1756382400000\t142\tFlat\n" +
            "2025-08-28T11:55:00\t1756382100000\t128\tFortyFiveDown"

        val entries = service.parseGlucose(body)

        assertEquals(2, entries.size)
        assertEquals(142, entries[0].sgv)
        assertEquals("Flat", entries[0].direction)
    }

    @Test
    fun `treatments parse and keep the flags that identify an algorithm's dose`() {
        val body = """
            [
              {"_id":"t1","eventType":"Meal Bolus","insulin":3.5,"carbs":45,
               "created_at":"2025-08-28T12:00:00.000Z","enteredBy":"loop"},
              {"_id":"t2","eventType":"Correction Bolus","insulin":0.15,
               "created_at":"2025-08-28T12:05:00.000Z","isSMB":true},
              {"_id":"t3","eventType":"Temp Basal","rate":0.8,"duration":30,
               "created_at":"2025-08-28T12:10:00.000Z","automatic":true}
            ]
        """.trimIndent()

        val treatments = service.parseTreatments(body)

        assertEquals(3, treatments.size)
        assertEquals(3.5, treatments[0].insulin!!, 0.0001)
        assertEquals(45.0, treatments[0].carbs!!, 0.0001)
        // A dose with no flag is the user's own, whoever uploaded it.
        assertTrue(!treatments[0].isAlgorithmDelivered)
        assertTrue(treatments[1].isAlgorithmDelivered)
        assertTrue(treatments[2].isAlgorithmDelivered)
    }

    @Test
    fun `a treatment with no mills gets its timestamp from created_at`() {
        val body = """[{"_id":"t1","eventType":"Bolus","insulin":2,"created_at":"2025-08-28T12:00:00.000Z"}]"""

        val treatment = service.parseTreatments(body).single()

        assertEquals(1756382400000L, treatment.mills)
    }

    @Test
    fun `one malformed treatment does not lose the rest`() {
        val body = """
            [
              {"_id":"t1","eventType":"Bolus","insulin":2,"mills":1756382400000},
              "not an object",
              {"_id":"t3","eventType":"Carb Correction","carbs":15,"mills":1756382500000}
            ]
        """.trimIndent()

        val treatments = service.parseTreatments(body)

        assertEquals(2, treatments.size)
        assertEquals(EventType.CARB_CORRECTION, treatments[1].eventType)
    }

    @Test
    fun `a profile document yields the therapy schedules`() {
        val body = """
            {
              "defaultProfile":"Default",
              "store":{
                "Default":{
                  "dia":5,
                  "basal":[{"time":"00:00","value":0.8},{"time":"06:00","value":1.0}],
                  "carbratio":[{"time":"00:00","value":10}],
                  "sens":[{"time":"00:00","value":50}],
                  "target_low":[{"time":"00:00","value":80}],
                  "target_high":[{"time":"00:00","value":140}]
                }
              }
            }
        """.trimIndent()

        val profile = service.parseTherapyProfile(body)

        assertNotNull(profile)
        assertEquals(2, profile!!.basal.size)
        assertEquals(1.0, profile.basal[1].value, 0.0001)
        assertEquals(10.0, profile.carbRatio.single().value, 0.0001)
        assertEquals(50.0, profile.sensitivity.single().value, 0.0001)
        assertEquals(5.0, profile.dia!!, 0.0001)
    }

    @Test
    fun `a profile array picks the newest usable document`() {
        // profile.json is a history; an empty leading document must not win.
        val body = """
            [
              {"defaultProfile":"Default","store":{}},
              {"defaultProfile":"Default","store":{"Default":{"basal":[{"time":"00:00","value":0.9}]}}}
            ]
        """.trimIndent()

        val profile = service.parseTherapyProfile(body)

        assertNotNull(profile)
        assertEquals(0.9, profile!!.basal.single().value, 0.0001)
    }

    @Test
    fun `timeAsSeconds and quoted values are both understood`() {
        // AndroidAPS writes timeAsSeconds; some exports quote every number.
        val body = """
            {"store":{"Default":{"basal":[{"timeAsSeconds":21600,"value":"1.25"}]}}}
        """.trimIndent()

        val profile = service.parseTherapyProfile(body)

        assertEquals("06:00", profile!!.basal.single().time)
        assertEquals(1.25, profile.basal.single().value, 0.0001)
    }

    @Test
    fun `carb_ratio and sensitivity spellings are both accepted`() {
        val body = """
            {"store":{"Default":{"carb_ratio":[{"time":"00:00","value":12}],
                                 "sensitivity":[{"time":"00:00","value":45}]}}}
        """.trimIndent()

        val profile = service.parseTherapyProfile(body)

        assertEquals(12.0, profile!!.carbRatio.single().value, 0.0001)
        assertEquals(45.0, profile.sensitivity.single().value, 0.0001)
    }

    @Test
    fun `insulin on board is read from Loop's nesting`() {
        val body = """
            [{"created_at":"2025-08-28T12:00:00.000Z","loop":{"iob":{"iob":2.35},"cob":{"cob":18}}}]
        """.trimIndent()

        val onBoard = service.parseOnBoard(body)

        assertEquals(2.35, onBoard.insulinUnits!!, 0.0001)
        assertEquals(18.0, onBoard.carbsGrams!!, 0.0001)
        assertEquals(1756382400000L, onBoard.asOfMillis)
    }

    @Test
    fun `insulin on board is read from the oref lineage's flatter shape`() {
        val body = """
            [{"created_at":"2025-08-28T12:00:00.000Z","openaps":{"suggested":{"IOB":1.2,"iob":1.2,"cob":30}}}]
        """.trimIndent()

        val onBoard = service.parseOnBoard(body)

        assertEquals(1.2, onBoard.insulinUnits!!, 0.0001)
        assertEquals(30.0, onBoard.carbsGrams!!, 0.0001)
    }

    @Test
    fun `the newest devicestatus wins`() {
        val body = """
            [
              {"created_at":"2025-08-28T11:00:00.000Z","loop":{"iob":{"iob":9.9}}},
              {"created_at":"2025-08-28T12:00:00.000Z","loop":{"iob":{"iob":1.1}}}
            ]
        """.trimIndent()

        assertEquals(1.1, service.parseOnBoard(body).insulinUnits!!, 0.0001)
    }

    @Test
    fun `a devicestatus with no on-board figures reports nothing`() {
        val body = """[{"created_at":"2025-08-28T12:00:00.000Z","pump":{"battery":{"percent":80}}}]"""

        assertTrue(service.parseOnBoard(body).isEmpty)
        assertTrue(service.parseOnBoard("[]").isEmpty)
        assertTrue(service.parseOnBoard("not json").isEmpty)
    }

    @Test
    fun `stale on-board figures are dropped rather than shown`() {
        val at = 1_756_382_400_000L
        val fresh = OnBoard(2.0, 20.0, at)

        assertEquals(fresh, OnBoard.unlessConnectionStale(fresh, latestReadingMillis = at, nowMillis = at + 60_000))
        // Twenty minutes on, "2 U on board" no longer describes now.
        assertTrue(OnBoard.unlessConnectionStale(fresh, at, at + 20 * 60_000).isEmpty)
        assertTrue(OnBoard.unlessConnectionStale(fresh, at, at + 20 * 60_000).connectionStale)
        assertTrue(OnBoard.unlessConnectionStale(fresh, null, at).isEmpty)
    }

    @Test
    fun `a profile with no usable schedules is treated as absent`() {
        assertNull(service.parseTherapyProfile("""{"store":{"Default":{}}}"""))
        assertNull(service.parseTherapyProfile("""{"nonsense":true}"""))
    }


    // MARK: - profile.json history (ported from the iOS DiabetesProfileDecodingTests)

    @Test
    fun `one unreadable history document does not fail the whole profile array`() {
        val body = """
            [
              {
                "defaultProfile": "Default",
                "store": {
                  "Default": {
                    "dia": 5,
                    "basal": [{"time":"00:00","value":0.8}],
                    "carbratio": [{"time":"00:00","value":"10"}],
                    "sens": [{"timeAsSeconds":0,"value":50}],
                    "target_low": [{"time":"00:00:00","value":80}],
                    "target_high": [{"time":"00:00","value":120}]
                  }
                }
              },
              {
                "defaultProfile": 12345,
                "store": "not-an-object",
                "overridePresets": {"broken": true}
              }
            ]
        """

        val docs = service.parseProfileDocuments(body)

        assertEquals(1, docs.size)
        assertEquals("Default", docs[0].defaultProfile)
        val entry = docs[0].store.getValue("Default")
        assertEquals(0.8, entry.basal.first().value, 0.0)
        assertEquals(10.0, entry.carbRatio.first().value, 0.0)
        assertEquals("00:00", entry.sensitivity.first().time)
        assertEquals("00:00", entry.targetLow.first().time)
    }

    @Test
    fun `a single profile object still decodes`() {
        val docs = service.parseProfileDocuments("""
            {
              "defaultProfile": "Work",
              "store": {
                "Work": {
                  "basal": [{"time":"00:00","value":1}]
                }
              }
            }
        """)

        assertEquals(1, docs.size)
        assertEquals(1.0, docs[0].store.getValue("Work").basal.first().value, 0.0)
    }

    @Test
    fun `properties answer first, dated by its own mills, and pebble is the last resort`() {
        val properties = """{"iob":{"iob":3.64,"basaliob":0,"source":"OpenAPS","mills":1788473100000,"display":"3.64"},"cob":{"cob":0,"isDecaying":0}}"""
        val onBoard = service.parseOnBoardProperties(properties, nowMillis = 1_000L)
        assertEquals(3.64, onBoard.insulinUnits!!, 0.0001)
        assertEquals(0.0, onBoard.carbsGrams!!, 0.0001)
        assertEquals(1788473100000L, onBoard.asOfMillis)
        assertTrue(service.parseOnBoardProperties("{}", 1_000L).isEmpty)

        val pebble = """{"status":[{"now":1788474383352}],"bgs":[{"sgv":"101","iob":"3.64","cob":0}]}"""
        val fromPebble = service.parseOnBoardPebble(pebble, nowMillis = 1_000L)
        assertEquals(3.64, fromPebble.insulinUnits!!, 0.0001)
        assertEquals(1788474383352L, fromPebble.asOfMillis)
        assertTrue(service.parseOnBoardPebble("not json", 1_000L).isEmpty)
    }
}
