package com.boostt1d.android

import com.boostt1d.android.sync.NightscoutService
import com.boostt1d.android.sync.NightscoutUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Addressing and authenticating a Nightscout site.
 *
 * This is the layer that decides whether someone's site works at all, and almost all of
 * it is string handling — so it is tested without a network.
 */
class NightscoutUrlTest {

    @Test
    fun `a bare host gains https`() {
        assertEquals("https://mysite.up.railway.app", NightscoutUrl.normalize("mysite.up.railway.app"))
    }

    @Test
    fun `http is upgraded, because the token travels on every request`() {
        assertEquals("https://mysite.com", NightscoutUrl.normalize("http://mysite.com"))
    }

    @Test
    fun `trailing slashes and surrounding space are removed`() {
        assertEquals("https://mysite.com", NightscoutUrl.normalize("  https://mysite.com///  "))
    }

    @Test
    fun `a half-typed protocol is left alone rather than mangled`() {
        // Prepending https:// here would turn "https:" into "https://https:".
        assertEquals("https:", NightscoutUrl.normalize("https:"))
        assertEquals("http:/", NightscoutUrl.normalize("http:/"))
    }

    @Test
    fun `unusable input normalizes to empty so the field can show a placeholder`() {
        assertEquals("", NightscoutUrl.normalize(""))
        assertEquals("   ".let { NightscoutUrl.normalize(it) }, "")
        // A protocol with no host is not usable, but it is also what someone mid-type
        // looks like, so it comes back untouched rather than being rewritten under them.
        assertEquals("https://", NightscoutUrl.normalize("https://"))
        assertEquals("", NightscoutUrl.normalize("https://?"))
    }

    @Test
    fun `a path is preserved for sites hosted under a subdirectory`() {
        assertEquals("https://mysite.com/nightscout", NightscoutUrl.normalize("mysite.com/nightscout/"))
    }

    @Test
    fun `the token is sent as its SHA-1, which is what Nightscout expects`() {
        // The canonical SHA-1 of "abc", so a change in hashing is caught here.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", NightscoutUrl.sha1("abc"))
    }

    @Test
    fun `glucose tries three auth shapes, therapy only one`() {
        // Deployments differ in which header they honour; treatments accept only api-secret.
        assertEquals(3, NightscoutUrl.glucoseStrategies("secret").size)
        assertEquals(1, NightscoutUrl.therapyStrategies("secret").size)
    }

    @Test
    fun `no token means one unauthenticated attempt, not three identical ones`() {
        assertEquals(1, NightscoutUrl.glucoseStrategies("").size)
    }

    @Test
    fun `query limits grow with the window`() {
        assertTrue(NightscoutUrl.glucoseQueryLimit(24) < NightscoutUrl.glucoseQueryLimit(24 * 8))
        assertTrue(NightscoutUrl.treatmentQueryLimit(24) < NightscoutUrl.treatmentQueryLimit(24 * 14))
        // Treatments are far fewer than readings, so their caps are smaller.
        assertTrue(NightscoutUrl.treatmentQueryLimit(24 * 14) < NightscoutUrl.glucoseQueryLimit(24 * 14))
    }

    @Test
    fun `timestamps parse from epoch seconds, milliseconds and ISO-8601`() {
        val noon = 1_756_382_400_000L // 2025-08-28T12:00:00Z
        assertEquals(noon, NightscoutService.parseTimestamp("1756382400000"))
        assertEquals(noon, NightscoutService.parseTimestamp("1756382400"))
        assertEquals(noon, NightscoutService.parseTimestamp("2025-08-28T12:00:00.000Z"))
        assertEquals(noon, NightscoutService.parseTimestamp("2025-08-28T12:00:00Z"))
        assertNull(NightscoutService.parseTimestamp(null))
        assertNull(NightscoutService.parseTimestamp("not a date"))
    }
}
