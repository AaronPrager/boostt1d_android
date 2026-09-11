package com.boostt1d.android

import com.boostt1d.android.home.ReviewCadence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a qualified open is due a Play rating prompt. Ported from the iOS ReviewRequestService. */
class ReviewCadenceTest {

    private val day = 86_400_000L
    private val now = 1_750_000_000_000L
    private val longAgo = now - 400 * day

    private fun due(
        opens: Int,
        installMillis: Long = longAgo,
        lastRequestMillis: Long = 0L,
        lastRequestVersion: String? = null,
        currentVersion: String = "1.2",
        isDevMode: Boolean = false,
    ) = ReviewCadence.isDue(
        opens, installMillis, lastRequestMillis, lastRequestVersion, currentVersion, now, isDevMode,
    )

    @Test
    fun `the first four opens are never asked`() {
        (1..4).forEach { assertFalse("open $it", due(it)) }
    }

    @Test
    fun `the cadence is five, then every twenty-five after that`() {
        assertTrue(due(5))
        assertFalse(due(6))
        assertFalse(due(29))
        assertTrue(due(30))
        assertTrue(due(55))
        assertTrue(due(80))
        assertFalse(due(81))
    }

    @Test
    fun `a fresh install is left alone for the first few days`() {
        assertFalse(due(5, installMillis = now - 2 * day))
        assertTrue(due(5, installMillis = now - 3 * day))
    }

    @Test
    fun `a prompt shown last month is not shown again`() {
        assertFalse(due(30, lastRequestMillis = now - 30 * day))
        assertTrue(due(30, lastRequestMillis = now - 91 * day))
    }

    @Test
    fun `asking twice on one version would burn an open for nothing`() {
        assertFalse(due(5, lastRequestVersion = "1.2", currentVersion = "1.2"))
        assertTrue(due(5, lastRequestVersion = "1.1", currentVersion = "1.2"))
    }

    @Test
    fun `dev mode asks on every open so the prompt can be seen`() {
        assertTrue(due(1, isDevMode = true))
        assertTrue(due(2, installMillis = now, lastRequestMillis = now, isDevMode = true))
    }
}
