package com.boostt1d.android

import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseDisplayTest {

    @Test
    fun `the conventional factor holds at the numbers people recognise`() {
        assertEquals(10.0, BGUnit.toMmolL(180.0), 0.001)
        assertEquals(180.0, BGUnit.toMgdL(10.0), 0.001)
    }

    @Test
    fun `a round trip through the user's unit does not drift`() {
        val stored = 70.0
        val shown = GlucoseDisplay.fromMgdL(stored, BGUnit.MMOLL)
        assertEquals(3.9, shown, 0.001)
        assertEquals(70.2, GlucoseDisplay.toMgdL(shown, BGUnit.MMOLL), 0.001)
    }

    @Test
    fun `units follow the country, and default to mmol where unlisted`() {
        assertEquals(BGUnit.MGDL, GlucoseDisplay.defaultUnit("US"))
        assertEquals(BGUnit.MGDL, GlucoseDisplay.defaultUnit("de"))
        assertEquals(BGUnit.MMOLL, GlucoseDisplay.defaultUnit("GB"))
        assertEquals(BGUnit.MMOLL, GlucoseDisplay.defaultUnit("AU"))
        assertEquals(BGUnit.MMOLL, GlucoseDisplay.defaultUnit(""))
    }

    @Test
    fun `mg per dL reads whole, mmol reads to one decimal`() {
        assertEquals("180", GlucoseDisplay.format(180.0, BGUnit.MGDL))
        assertEquals("10.0", GlucoseDisplay.format(180.0, BGUnit.MMOLL))
    }
}
