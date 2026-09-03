package com.boostt1d.android

import com.boostt1d.android.sync.NightscoutService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightscoutHttpMessageTest {

    @Test
    fun `a short plain-text body is repeated after the status wording`() {
        assertEquals(
            "That Nightscout site is no longer active. Check the URL. The site said: “This Nightscout site is inactive.”",
            NightscoutService.httpMessage(410, "This Nightscout site is inactive.\n"),
        )
        assertEquals("The site returned an unexpected response (418).", NightscoutService.httpMessage(418, null))
    }

    @Test
    fun `html, json and long bodies are never repeated`() {
        assertNull(NightscoutService.siteSaid("<html><body>Bad gateway</body></html>"))
        assertNull(NightscoutService.siteSaid("{\"status\":502}"))
        assertNull(NightscoutService.siteSaid("x".repeat(161)))
        assertNull(NightscoutService.siteSaid("   "))
        assertEquals("Bad gateway", NightscoutService.siteSaid("Bad gateway\nsecond line ignored"))
        assertEquals("The site reported an error. It may be waking up — try again shortly.", NightscoutService.httpMessage(502, "<html>"))
    }
}
