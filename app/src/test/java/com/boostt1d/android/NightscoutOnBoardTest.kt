package com.boostt1d.android

import com.boostt1d.android.data.NightscoutOnBoard
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from the iOS NightscoutOnBoardTests. */
class NightscoutOnBoardTest {

    private fun json(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun `Loop device status exposes IOB and COB under loop iob and loop cob`() {
        val reading = NightscoutOnBoard.fromDeviceStatuses(json("""
            [
              {"device":"xdrip","created_at":"2026-08-17T18:00:00.000Z"},
              {
                "created_at":"2026-08-17T17:59:00.000Z",
                "loop": {
                  "iob": {"iob": 3.4, "bolusiob": 3.1},
                  "cob": {"cob": 22, "timestamp":"2026-08-17T17:59:00.000Z"}
                }
              }
            ]
        """))!!

        assertEquals(3.4, reading.iob!!, 0.0)
        assertEquals(22.0, reading.cob!!, 0.0)
        assertEquals("2026-08-17T17:59:00.000Z", reading.time)
    }

    @Test
    fun `whole-unit OpenAPS IOB encoded as an integer is still read`() {
        val reading = NightscoutOnBoard.fromDeviceStatuses(json("""
            [{
              "created_at":"2026-08-17T18:00:00.000Z",
              "openaps": {
                "iob": {"iob": 2},
                "suggested": {"COB": 15, "IOB": 2}
              }
            }]
        """))!!

        assertEquals(2.0, reading.iob!!, 0.0)
        assertEquals(15.0, reading.cob!!, 0.0)
    }

    @Test
    fun `v2 properties payload is the preferred shape`() {
        val reading = NightscoutOnBoard.fromProperties(json("""
            {
              "iob": {"iob": 1.25, "basaliob": 0.1, "source": "Loop"},
              "cob": {"cob": "8.5", "source": "Loop"}
            }
        """))!!

        assertEquals(1.25, reading.iob!!, 0.0)
        assertEquals(8.5, reading.cob!!, 0.0)
    }

    @Test
    fun `Pebble watch face iob and cob may be strings`() {
        val reading = NightscoutOnBoard.fromPebble(json("""{"bgs":[{"sgv":"140","iob":"2.31","cob":12}]}"""))!!

        assertEquals(2.31, reading.iob!!, 0.0)
        assertEquals(12.0, reading.cob!!, 0.0)
    }

    @Test
    fun `a CGM-only latest row does not hide Loop IOB on an older row`() {
        val reading = NightscoutOnBoard.fromDeviceStatuses(json("""
            [
              {"created_at":"2026-08-17T18:01:00.000Z","device":"xdrip-js"},
              {"created_at":"2026-08-17T18:00:00.000Z","loop":{"iob":{"iob":4.8},"cob":{"cob":0}}}
            ]
        """))!!

        assertEquals(4.8, reading.iob!!, 0.0)
        assertEquals(0.0, reading.cob!!, 0.0)
    }

    @Test
    fun `booleans, nulls and blanks are not numbers`() {
        assertNull(NightscoutOnBoard.number(json("true")))
        assertNull(NightscoutOnBoard.number(json("null")))
        assertNull(NightscoutOnBoard.number(json("\"  \"")))
        assertNull(NightscoutOnBoard.number(null))
        assertEquals(0.0, NightscoutOnBoard.number(json("0"))!!, 0.0)
        assertEquals(7.0, NightscoutOnBoard.number(json("\" 7 \""))!!, 0.0)
        assertNull(NightscoutOnBoard.fromDeviceStatuses(json("[{\"device\":\"xdrip\"}]")))
    }
}
