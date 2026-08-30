package com.boostt1d.android

import com.boostt1d.android.data.GlucoseSourceRank
import com.boostt1d.android.data.GlucoseSourceStitch
import com.boostt1d.android.data.GlucoseSourceTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of truth: Nightscout beats a direct CGM, both beat manual, and the source
 * the user actually picked beats everything.
 *
 * These rules decide which number a person sees when two sources disagree about the same
 * minute, so they are pinned. Ported from the iOS GlucoseSourceOrderOfTruth / Stitch /
 * Trust tests.
 */
class GlucoseSourceTest {

    private data class Row(val epoch: Long, val source: String?)

    private fun stitch(rows: List<Row>, preferred: String? = null, isolate: Set<String> = emptySet()) =
        GlucoseSourceStitch.stitched(
            rows, source = { it.source }, epoch = { it.epoch },
            preferredActive = preferred, isolateToRemoteTags = isolate,
        )

    @Test
    fun `nightscout outranks a direct CGM, which outranks manual`() {
        val ns = GlucoseSourceRank.rank(GlucoseSourceTag.NIGHTSCOUT)
        val dexcom = GlucoseSourceRank.rank(GlucoseSourceTag.DEXCOM)
        val libre = GlucoseSourceRank.rank(GlucoseSourceTag.LIBRE)
        val manual = GlucoseSourceRank.rank(GlucoseSourceTag.MANUAL)
        val unknown = GlucoseSourceRank.rank(null)

        assertTrue(ns > dexcom)
        assertEquals(dexcom, libre)
        assertTrue(dexcom > manual)
        assertTrue(manual > unknown)
    }

    @Test
    fun `the live source outranks everything, including nightscout`() {
        val liveDexcom = GlucoseSourceRank.rank(
            GlucoseSourceTag.DEXCOM, preferredActive = GlucoseSourceTag.DEXCOM,
        )
        val ns = GlucoseSourceRank.rank(GlucoseSourceTag.NIGHTSCOUT, preferredActive = GlucoseSourceTag.DEXCOM)

        assertTrue(liveDexcom > ns)
    }

    @Test
    fun `ranking ignores case and surrounding space`() {
        assertEquals(
            GlucoseSourceRank.rank(GlucoseSourceTag.NIGHTSCOUT),
            GlucoseSourceRank.rank("  NightScout "),
        )
    }

    @Test
    fun `two vendors describing the same moment collapse to the better one`() {
        val rows = listOf(
            Row(1_000_000, GlucoseSourceTag.MANUAL),
            Row(1_030_000, GlucoseSourceTag.NIGHTSCOUT),  // 30s later — same moment
        )

        val result = stitch(rows)

        assertEquals(1, result.size)
        assertEquals(GlucoseSourceTag.NIGHTSCOUT, result[0].source)
    }

    @Test
    fun `readings further apart than the tolerance are separate moments`() {
        val rows = listOf(
            Row(1_000_000, GlucoseSourceTag.MANUAL),
            Row(1_000_000 + GlucoseSourceStitch.DUPLICATE_TOLERANCE_MILLIS + 1, GlucoseSourceTag.NIGHTSCOUT),
        )

        assertEquals(2, stitch(rows).size)
    }

    @Test
    fun `switching source keeps the history the previous one left behind`() {
        // A day of Dexcom, then Nightscout takes over. Nothing older should vanish.
        val rows = listOf(
            Row(1_000_000, GlucoseSourceTag.DEXCOM),
            Row(2_000_000, GlucoseSourceTag.DEXCOM),
            Row(3_000_000, GlucoseSourceTag.NIGHTSCOUT),
        )

        val result = stitch(rows, preferred = GlucoseSourceTag.NIGHTSCOUT)

        assertEquals(3, result.size)
    }

    @Test
    fun `isolating to one account hides other vendors but never local entries`() {
        val rows = listOf(
            Row(1_000_000, GlucoseSourceTag.MANUAL),
            Row(2_000_000, GlucoseSourceTag.DEXCOM),
            Row(3_000_000, GlucoseSourceTag.NIGHTSCOUT),
            Row(4_000_000, null),
        )

        val result = stitch(rows, isolate = setOf(GlucoseSourceTag.NIGHTSCOUT))

        // Dexcom is withheld; manual and untagged rows are this user's own regardless.
        assertEquals(
            listOf(GlucoseSourceTag.MANUAL, GlucoseSourceTag.NIGHTSCOUT, null),
            result.map { it.source },
        )
    }

    @Test
    fun `a newest-first list comes back newest-first`() {
        val rows = listOf(
            Row(3_000_000, GlucoseSourceTag.NIGHTSCOUT),
            Row(2_000_000, GlucoseSourceTag.NIGHTSCOUT),
            Row(1_000_000, GlucoseSourceTag.NIGHTSCOUT),
        )

        assertEquals(listOf(3_000_000L, 2_000_000L, 1_000_000L), stitch(rows).map { it.epoch })
    }

    @Test
    fun `a single reading passes through untouched`() {
        val rows = listOf(Row(1_000_000, GlucoseSourceTag.MANUAL))
        assertEquals(rows, stitch(rows))
    }

    @Test
    fun `stitching a fortnight of CGM data keeps every distinct moment`() {
        // 14 days at a 5-minute cadence, which is what a real Nightscout download looks
        // like. Nothing here should be collapsed: the samples are well outside the
        // 90-second duplicate window.
        val fortnight = (0 until 4_032).map {
            Row(1_700_000_000_000L + it * 5L * 60 * 1000, GlucoseSourceTag.NIGHTSCOUT)
        }

        val result = stitch(fortnight, preferred = GlucoseSourceTag.NIGHTSCOUT)

        assertEquals(4_032, result.size)
    }

    @Test
    fun `duplicate uploads of a fortnight collapse to one series`() {
        // The same fortnight uploaded twice, a few seconds apart — what happens when a
        // site has two uploaders running.
        val base = (0 until 4_032).map {
            Row(1_700_000_000_000L + it * 5L * 60 * 1000, GlucoseSourceTag.NIGHTSCOUT)
        }
        val echo = base.map { Row(it.epoch + 20_000, GlucoseSourceTag.DEXCOM) }

        val result = stitch((base + echo).sortedBy { it.epoch }, preferred = GlucoseSourceTag.NIGHTSCOUT)

        assertEquals(4_032, result.size)
        assertTrue(result.all { it.source == GlucoseSourceTag.NIGHTSCOUT })
    }

    @Test
    fun `a manual entry survives when no CGM covers that moment`() {
        // The point of manual entry: it fills gaps the sensor never saw.
        val rows = listOf(
            Row(1_000_000, GlucoseSourceTag.NIGHTSCOUT),
            Row(5_000_000, GlucoseSourceTag.MANUAL),
            Row(9_000_000, GlucoseSourceTag.NIGHTSCOUT),
        )

        assertEquals(3, stitch(rows, preferred = GlucoseSourceTag.NIGHTSCOUT).size)
    }
}
