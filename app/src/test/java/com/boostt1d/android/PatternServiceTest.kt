package com.boostt1d.android

import com.boostt1d.android.engine.PatternService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The wording rules PatternService applies over the detector's numbers.
 *
 * Ported from the coverage-label and weekday-claim cases of the iOS PatternClaimIntegrityTests.
 */
class PatternServiceTest {

    private val zone: TimeZone = TimeZone.getDefault()

    // MARK: - Coverage wording

    /**
     * The label is now only a freshness stamp — the date range and the note about today were
     * removed from the screen.
     */
    @Test
    fun `the coverage label reports freshness and nothing else`() {
        val monday = Calendar.getInstance(zone).apply { clear(); set(2025, Calendar.JUNE, 16, 15, 30, 0) }.timeInMillis
        val label = PatternService.coverageLabel(monday)!!

        assertTrue(label.startsWith("Updated"))
        assertFalse(label.contains("full days"))
        assertFalse(label.lowercase().contains("today"))
    }

    @Test
    fun `with no readings the label is absent rather than empty`() {
        assertNull(PatternService.coverageLabel(null))
    }

    // MARK: - Weekday claims

    @Test
    fun `weekday words are recognised, ordinary words are not`() {
        assertTrue(PatternService.makesWeekdayClaim("Highs cluster on Fridays"))
        assertTrue(PatternService.makesWeekdayClaim("Weekend mornings run higher"))
        assertTrue(PatternService.makesWeekdayClaim("Some days, like Friday, show a rise"))
        assertTrue(PatternService.makesWeekdayClaim("Glucose rises on Sat and Sun"))

        assertFalse(PatternService.makesWeekdayClaim("Overnight glucose rose on 5 of 7 days"))
        assertFalse(PatternService.makesWeekdayClaim("Breakfast highs after high-carb meals"))
        // Must not fire on a substring inside an unrelated word.
        assertFalse(PatternService.makesWeekdayClaim("A sundae after dinner pushed glucose up"))
    }

    @Test
    fun `fourteen days is the floor for any weekday claim`() {
        // Below 14 days no weekday can occur twice, so no weekday claim can be true.
        assertEquals(14, PatternService.MIN_PERIOD_DAYS_FOR_WEEKDAY_CLAIMS)
    }

    @Test
    fun `a weekday claim is dropped below the floor and kept above it`() {
        assertNull(PatternService.withoutUnsupportedWeekdayClaims("Highs cluster on Fridays", periodDays = 7))
        assertEquals(
            "Highs cluster on Fridays",
            PatternService.withoutUnsupportedWeekdayClaims("Highs cluster on Fridays", periodDays = 14),
        )
        // Ordinary wording passes at any period.
        assertEquals(
            "Overnight glucose rose on 5 of 7 days",
            PatternService.withoutUnsupportedWeekdayClaims("Overnight glucose rose on 5 of 7 days", periodDays = 7),
        )
    }
}
