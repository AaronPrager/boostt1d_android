package com.boostt1d.android.onboarding

import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseConnectionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules setup enforces, pinned.
 *
 * These mirror the iOS OnboardingValidation. When a rule changes on one platform it
 * has to change here too — which is the point: a silent divergence in who is allowed
 * to finish setup is exactly the kind of drift two codebases produce.
 */
class OnboardingValidationTest {

    private val adult = OnboardingDraft(
        name = "Sam",
        age = "30",
        gender = "Prefer not to say",
        email = "sam@example.com",
        yearsSinceDiagnosis = "3-9",
        countryCode = "US",
        countryName = "United States",
    )

    // MARK: canContinue

    @Test
    fun `personal info needs name age gender and email`() {
        assertTrue(OnboardingValidation.canContinue(OnboardingStep.PERSONAL_INFO, adult))
        assertFalse(
            OnboardingValidation.canContinue(OnboardingStep.PERSONAL_INFO, adult.copy(name = "  ")),
        )
        assertFalse(
            OnboardingValidation.canContinue(OnboardingStep.PERSONAL_INFO, adult.copy(gender = "")),
        )
        assertFalse(
            OnboardingValidation.canContinue(OnboardingStep.PERSONAL_INFO, adult.copy(email = "")),
        )
    }

    @Test
    fun `under 13 needs a parent instead of their own email`() {
        val child = adult.copy(age = "9", email = "")
        assertFalse(OnboardingValidation.canContinue(OnboardingStep.PERSONAL_INFO, child))

        val withParent = child.copy(parentName = "Alex", parentEmail = "alex@example.com")
        assertTrue(OnboardingValidation.canContinue(OnboardingStep.PERSONAL_INFO, withParent))
    }

    @Test
    fun `13 is old enough to stand alone`() {
        assertFalse(adult.copy(age = "13").needsParentGuardian)
        assertTrue(adult.copy(age = "12").needsParentGuardian)
    }

    @Test
    fun `photo is skippable and agreements are not`() {
        assertTrue(OnboardingValidation.canContinue(OnboardingStep.PHOTO, OnboardingDraft()))

        assertFalse(OnboardingValidation.canContinue(OnboardingStep.AGREEMENTS, adult))
        val agreed = adult.copy(
            agreedAge = true,
            agreedDisclaimer = true,
            agreedPrivacy = true,
            agreedTerms = true,
        )
        assertTrue(OnboardingValidation.canContinue(OnboardingStep.AGREEMENTS, agreed))
    }

    @Test
    fun `marketing opt-in is never required to finish`() {
        val agreed = adult.copy(
            agreedAge = true,
            agreedDisclaimer = true,
            agreedPrivacy = true,
            agreedTerms = true,
            marketingOptIn = false,
        )
        assertTrue(OnboardingValidation.canContinue(OnboardingStep.AGREEMENTS, agreed))
    }

    @Test
    fun `manual is the only connection that can be continued from`() {
        assertTrue(
            OnboardingValidation.canContinue(
                OnboardingStep.CONNECTION,
                adult.copy(connection = GlucoseConnectionOption.MANUAL),
            ),
        )
        assertFalse(
            OnboardingValidation.canContinue(
                OnboardingStep.CONNECTION,
                adult.copy(connection = GlucoseConnectionOption.NIGHTSCOUT),
            ),
        )
    }

    @Test
    fun `region needs a country code, not just a name`() {
        assertTrue(OnboardingValidation.canContinue(OnboardingStep.REGION, adult))
        assertFalse(
            OnboardingValidation.canContinue(OnboardingStep.REGION, adult.copy(countryCode = "")),
        )
    }

    // MARK: problem

    @Test
    fun `a malformed email is reported, not silently accepted`() {
        assertNull(OnboardingValidation.problem(OnboardingStep.PERSONAL_INFO, adult))
        assertNotNull(
            OnboardingValidation.problem(
                OnboardingStep.PERSONAL_INFO,
                adult.copy(email = "sam@@example"),
            ),
        )
    }

    @Test
    fun `a child's own email stays optional but must be valid when given`() {
        val child = adult.copy(
            age = "9",
            email = "",
            parentName = "Alex",
            parentEmail = "alex@example.com",
        )
        assertNull(OnboardingValidation.problem(OnboardingStep.PERSONAL_INFO, child))
        assertNotNull(
            OnboardingValidation.problem(
                OnboardingStep.PERSONAL_INFO,
                child.copy(email = "not-an-email"),
            ),
        )
    }

    @Test
    fun `you cannot have had diabetes longer than you have been alive`() {
        val impossible = adult.copy(age = "5", yearsSinceDiagnosis = "10+")
        assertNotNull(OnboardingValidation.problem(OnboardingStep.DIABETES, impossible))
    }

    @Test
    fun `no diabetes skips the years-against-age check`() {
        val none = adult.copy(age = "5", yearsSinceDiagnosis = OnboardingValidation.NO_DIABETES_OPTION)
        assertFalse(none.hasDiabetes)
        assertNull(OnboardingValidation.problem(OnboardingStep.DIABETES, none))
    }

    @Test
    fun `target range is bounded and ordered`() {
        assertNull(OnboardingValidation.problem(OnboardingStep.CONNECTION, adult))

        assertNotNull(
            OnboardingValidation.problem(OnboardingStep.CONNECTION, adult.copy(lowGlucose = 40.0)),
        )
        assertNotNull(
            OnboardingValidation.problem(OnboardingStep.CONNECTION, adult.copy(highGlucose = 250.0)),
        )
        assertNotNull(
            OnboardingValidation.problem(
                OnboardingStep.CONNECTION,
                adult.copy(lowGlucose = 150.0, highGlucose = 120.0),
            ),
        )
    }

    @Test
    fun `range bounds are checked in mg per dL whatever the user reads`() {
        // 70 / 180 mg/dL is valid; the same draft shown in mmol/L must still pass,
        // because the stored value never changed.
        val mmol = adult.copy(bgUnit = BGUnit.MMOLL)
        assertNull(OnboardingValidation.problem(OnboardingStep.CONNECTION, mmol))
    }

    // MARK: buckets

    @Test
    fun `years buckets map to comparable numbers`() {
        assertEquals(0, OnboardingValidation.yearsValue("<1"))
        assertEquals(1, OnboardingValidation.yearsValue("1-2"))
        assertEquals(5, OnboardingValidation.yearsValue("3-9"))
        // The legacy iOS spelling of the same bucket.
        assertEquals(5, OnboardingValidation.yearsValue("3-10"))
        assertEquals(10, OnboardingValidation.yearsValue("10+"))
        assertEquals(0, OnboardingValidation.yearsValue(OnboardingValidation.NO_DIABETES_OPTION))
    }
}
