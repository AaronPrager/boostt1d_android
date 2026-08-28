package com.boostt1d.android

import com.boostt1d.android.data.BolusCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dose arithmetic. These numbers become a suggested insulin dose, so every branch is
 * pinned — especially the ones that decide when a correction is *not* applied.
 *
 * Ported from the iOS BolusCalculatorTests.
 */
class BolusCalculatorTest {

    private fun input(
        carbs: Double = 60.0,
        carbRatio: Double = 10.0,
        glucose: Double? = null,
        target: Double? = null,
        isf: Double? = null,
        iob: Double = 0.0,
        cob: Double = 0.0,
    ) = BolusCalculator.Input(carbs, carbRatio, glucose, target, isf, iob, cob)

    @Test
    fun `carbs divided by ratio gives the meal bolus`() {
        val out = BolusCalculator.calculate(input(carbs = 60.0, carbRatio = 10.0))

        assertEquals(6.0, out.carbBolusUnits, 0.0001)
        assertEquals(6.0, out.totalUnits, 0.0001)
        assertEquals(6.0, out.safeBolus, 0.0001)
    }

    @Test
    fun `zero carbs is allowed and yields a correction-only dose`() {
        val out = BolusCalculator.calculate(
            input(carbs = 0.0, glucose = 200.0, target = 100.0, isf = 50.0)
        )

        assertEquals(0.0, out.carbBolusUnits, 0.0001)
        assertEquals(2.0, out.correctionUnits, 0.0001)
        assertEquals(2.0, out.safeBolus, 0.0001)
    }

    @Test
    fun `negative carbs is rejected`() {
        assertThrows(BolusCalculator.ValidationError.InvalidCarbs::class.java) {
            BolusCalculator.calculate(input(carbs = -1.0))
        }
    }

    @Test
    fun `a zero carb ratio is rejected rather than dividing by zero`() {
        assertThrows(BolusCalculator.ValidationError.InvalidCarbRatio::class.java) {
            BolusCalculator.calculate(input(carbRatio = 0.0))
        }
    }

    @Test
    fun `correction applies only above target`() {
        val above = BolusCalculator.calculate(
            input(carbs = 0.0, glucose = 180.0, target = 100.0, isf = 40.0)
        )
        assertEquals(2.0, above.correctionUnits, 0.0001)

        val atTarget = BolusCalculator.calculate(
            input(carbs = 0.0, glucose = 100.0, target = 100.0, isf = 40.0)
        )
        assertEquals(0.0, atTarget.correctionUnits, 0.0001)

        val below = BolusCalculator.calculate(
            input(carbs = 0.0, glucose = 70.0, target = 100.0, isf = 40.0)
        )
        assertEquals(0.0, below.correctionUnits, 0.0001)
    }

    @Test
    fun `a missing correction input skips the correction silently`() {
        val noTarget = BolusCalculator.calculate(input(glucose = 200.0, isf = 50.0))
        assertEquals(0.0, noTarget.correctionUnits, 0.0001)

        val noIsf = BolusCalculator.calculate(input(glucose = 200.0, target = 100.0))
        assertEquals(0.0, noIsf.correctionUnits, 0.0001)

        val zeroIsf = BolusCalculator.calculate(
            input(glucose = 200.0, target = 100.0, isf = 0.0)
        )
        assertEquals(0.0, zeroIsf.correctionUnits, 0.0001)
    }

    @Test
    fun `missing inputs are named so the screen can explain the omission`() {
        assertEquals(
            emptyList<String>(),
            BolusCalculator.missingCorrectionInputs(input()),
        )
        assertEquals(
            listOf("target glucose", "correction factor"),
            BolusCalculator.missingCorrectionInputs(input(glucose = 200.0)),
        )
        assertEquals(
            listOf("correction factor"),
            BolusCalculator.missingCorrectionInputs(input(glucose = 200.0, target = 100.0)),
        )
    }

    @Test
    fun `available IOB is subtracted to prevent stacking`() {
        val out = BolusCalculator.calculate(input(carbs = 60.0, carbRatio = 10.0, iob = 2.0))

        assertEquals(2.0, out.availableIOB, 0.0001)
        assertEquals(2.0, out.iobReduction, 0.0001)
        assertEquals(4.0, out.safeBolus, 0.0001)
    }

    @Test
    fun `IOB already covering carbs on board is not available to offset the meal`() {
        // 30g COB at 1:10 accounts for 3U of the 4U on board, leaving 1U available.
        val out = BolusCalculator.calculate(
            input(carbs = 60.0, carbRatio = 10.0, iob = 4.0, cob = 30.0)
        )

        assertEquals(3.0, out.iobNeededForCOB, 0.0001)
        assertEquals(1.0, out.availableIOB, 0.0001)
        assertEquals(5.0, out.safeBolus, 0.0001)
    }

    @Test
    fun `the dose never goes negative`() {
        val out = BolusCalculator.calculate(input(carbs = 10.0, carbRatio = 10.0, iob = 5.0))

        assertEquals(0.0, out.safeBolus, 0.0)
        assertTrue(out.safeBolus >= 0)
    }

    @Test
    fun `excess insulin is expressed in grams at the user's own ratio`() {
        // 1U meal need against 5U available leaves 4U excess; at 1:10 that is 40g.
        val out = BolusCalculator.calculate(input(carbs = 10.0, carbRatio = 10.0, iob = 5.0))

        assertEquals(4.0, out.excessInsulinUnits, 0.0001)
        assertEquals(40.0, out.carbsToOffsetExcess, 0.0001)
    }

    @Test
    fun `no excess when the meal fully absorbs the active insulin`() {
        val out = BolusCalculator.calculate(input(carbs = 60.0, carbRatio = 10.0, iob = 2.0))

        assertEquals(0.0, out.excessInsulinUnits, 0.0)
        assertEquals(0.0, out.carbsToOffsetExcess, 0.0)
    }

    @Test
    fun `a high glucose can cancel out apparent excess insulin`() {
        // Without the correction this looks like 3U of excess; the high absorbs it.
        val out = BolusCalculator.calculate(
            input(carbs = 10.0, carbRatio = 10.0, glucose = 300.0, target = 100.0, isf = 50.0, iob = 4.0)
        )

        assertEquals(4.0, out.correctionUnits, 0.0001)
        assertEquals(5.0, out.totalUnits, 0.0001)
        assertEquals(1.0, out.safeBolus, 0.0001)
        assertEquals(0.0, out.excessInsulinUnits, 0.0)
    }
}
