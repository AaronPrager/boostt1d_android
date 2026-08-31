package com.boostt1d.android

import com.boostt1d.android.background.SyncReminders
import com.boostt1d.android.data.GlucoseConnectionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app warns that readings are about to be lost.
 *
 * The timing is the whole point: too early and it nags, too late and the readings are
 * already gone. Three-quarters of the window leaves the last quarter to act in.
 */
class SyncRemindersTest {

    @Test
    fun `the reminder lands at three quarters of the window`() {
        // 18h for Dexcom's 24h window, 9h for LibreLinkUp's 12h.
        assertEquals(18 * 60 * 60 * 1000L, SyncReminders.staleDelayMillis(24))
        assertEquals(9 * 60 * 60 * 1000L, SyncReminders.staleDelayMillis(12))
    }

    @Test
    fun `a shorter window is warned about sooner`() {
        assertTrue(SyncReminders.staleDelayMillis(12) < SyncReminders.staleDelayMillis(24))
    }

    @Test
    fun `rejected credentials are raised long before the window closes`() {
        // They cannot repair themselves, so waiting out the window would waste it.
        assertTrue(SyncReminders.AUTH_DELAY_MILLIS < SyncReminders.staleDelayMillis(12))
    }

    @Test
    fun `only sources with a bounded history can lose readings`() {
        assertEquals(24, GlucoseConnectionOption.DEXCOM.historyWindowHours)
        assertEquals(12, GlucoseConnectionOption.LIBRE.historyWindowHours)
        // Nightscout is the user's own server and keeps everything, so a missed sync is a
        // delay, not a loss. Manual has nothing to fetch at all.
        assertNull(GlucoseConnectionOption.NIGHTSCOUT.historyWindowHours)
        assertNull(GlucoseConnectionOption.MANUAL.historyWindowHours)
    }
}
