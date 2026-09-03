package com.boostt1d.android.onboarding

import com.boostt1d.android.data.AgeSelectionOptions
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.sync.DexcomRegion
import com.boostt1d.android.sync.LibreRegion
import com.boostt1d.android.sync.NightscoutUrl

/**
 * The named stages of setup, in the order a person expects to be asked: who you
 * are, your diabetes, your picture, your region, your data source, then what you
 * have to agree to.
 */
enum class OnboardingStep(
    val title: String,
    val subtitle: String,
) {
    PERSONAL_INFO("Personal info", "Your name, age and how to reach you"),
    DIABETES("Diabetes", "How long you've had it, and how you take insulin"),
    PHOTO("Photo", "Optional — it personalizes your dashboard"),
    REGION("Region & units", "Sets your glucose units"),
    CONNECTION("Connection", "How BoostT1D receives your readings"),
    AGREEMENTS("Agreements", "Read and confirm to finish setup");

    val isLast: Boolean get() = this == entries.last()
    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)
    val previous: OnboardingStep? get() = entries.getOrNull(ordinal - 1)
}

/** Everything setup collects, before any of it is committed. */
data class OnboardingDraft(
    val name: String = "",
    val age: String = AgeSelectionOptions.DEFAULT_AGE.toString(),
    val email: String = "",
    val gender: String = "",
    val parentName: String = "",
    val parentEmail: String = "",
    val yearsSinceDiagnosis: String = "",
    val therapy: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
    val countryName: String = "",
    val countryCode: String = "",
    val marketingOptIn: Boolean = false,
    val connection: GlucoseConnectionOption = GlucoseConnectionOption.MANUAL,
    val nightscoutUrl: String = "",
    val nightscoutToken: String = "",
    val dexcomUsername: String = "",
    val dexcomPassword: String = "",
    val dexcomRegion: DexcomRegion = DexcomRegion.US,
    val libreUsername: String = "",
    val librePassword: String = "",
    val libreRegion: LibreRegion = LibreRegion.AUTOMATIC,
    val photoBase64: String? = null,
    val bgUnit: BGUnit = BGUnit.MGDL,
    /** Stored in mg/dL whatever the user is reading, exactly as on iOS. */
    val lowGlucose: Double = 70.0,
    val highGlucose: Double = 180.0,
    val agreedAge: Boolean = false,
    val agreedDisclaimer: Boolean = false,
    val agreedPrivacy: Boolean = false,
    val agreedTerms: Boolean = false,
) {
    val ageValue: Int? get() = age.toIntOrNull()

    val needsParentGuardian: Boolean
        get() = ageValue?.let { AgeSelectionOptions.requiresParentGuardian(it) } ?: false

    val hasDiabetes: Boolean get() = yearsSinceDiagnosis != OnboardingValidation.NO_DIABETES_OPTION
}

/**
 * Everything setup refuses to accept, in one place.
 *
 * A direct port of the iOS OnboardingValidation. `canContinue` gates the button;
 * `problem` is the message shown when the entered values are the wrong shape rather
 * than merely missing. Keeping the two separate is what stops a step from having
 * three different answers to "is this email good enough".
 */
object OnboardingValidation {

    const val NO_DIABETES_OPTION = "I do not have diabetes"

    val genderOptions = listOf("Male", "Female", "Prefer not to say")
    val yearsSinceDiagnosisOptions = listOf("<1", "1-2", "3-9", "10+", NO_DIABETES_OPTION)

    /** Whether the step's required fields are filled in at all. */
    fun canContinue(step: OnboardingStep, draft: OnboardingDraft): Boolean = when (step) {
        OnboardingStep.PERSONAL_INFO -> {
            val base = draft.name.isNotBlank() && draft.age.isNotEmpty() && draft.gender.isNotEmpty()
            when {
                !base -> false
                draft.needsParentGuardian ->
                    draft.parentName.isNotBlank() && draft.parentEmail.isNotBlank()
                else -> draft.email.isNotBlank()
            }
        }

        // Therapy is optional — "None" leaves UNSPECIFIED and BoostT1D falls back to
        // reading the uploaded doses.
        OnboardingStep.DIABETES -> draft.yearsSinceDiagnosis.isNotEmpty()

        OnboardingStep.PHOTO -> true

        OnboardingStep.REGION -> draft.countryCode.isNotEmpty()

        OnboardingStep.CONNECTION -> when (draft.connection) {
            GlucoseConnectionOption.MANUAL -> true
            // A token is required, not optional: without one Nightscout returns readings
            // but no treatments or therapy settings, which is most of what the app reads.
            GlucoseConnectionOption.NIGHTSCOUT ->
                draft.nightscoutUrl.isNotBlank() && draft.nightscoutToken.isNotBlank()
            GlucoseConnectionOption.DEXCOM ->
                draft.dexcomUsername.isNotBlank() && draft.dexcomPassword.isNotBlank()
            GlucoseConnectionOption.LIBRE ->
                draft.libreUsername.isNotBlank() && draft.librePassword.isNotBlank()
        }

        OnboardingStep.AGREEMENTS ->
            draft.agreedAge && draft.agreedDisclaimer && draft.agreedPrivacy && draft.agreedTerms
    }

    /** The reason the step cannot be left, or null when the values are usable. */
    fun problem(step: OnboardingStep, draft: OnboardingDraft): String? = when (step) {
        OnboardingStep.PERSONAL_INFO -> personalInfoProblem(draft)
        OnboardingStep.DIABETES -> diabetesProblem(draft)
        OnboardingStep.CONNECTION -> connectionProblem(draft)
        OnboardingStep.PHOTO, OnboardingStep.REGION, OnboardingStep.AGREEMENTS -> null
    }

    private fun personalInfoProblem(draft: OnboardingDraft): String? {
        val ageValue = draft.ageValue
        if (ageValue == null || ageValue !in AgeSelectionOptions.ages) {
            return "Please select your age."
        }

        val email = draft.email.trim()
        if (draft.needsParentGuardian) {
            // A child's own email stays optional; validate it only when given.
            if (email.isNotEmpty() && !isValidEmail(email)) {
                return "Please enter a valid email address."
            }
            if (draft.parentName.isBlank()) {
                return "Parent or guardian name is required if the account holder is under 13."
            }
            if (!isValidEmail(draft.parentEmail.trim())) {
                return "Please enter a valid parent or guardian email."
            }
        } else {
            if (email.isEmpty()) return "Please enter your email address."
            if (!isValidEmail(email)) return "Please enter a valid email address."
        }
        return null
    }

    private fun diabetesProblem(draft: OnboardingDraft): String? {
        val ageValue = draft.ageValue ?: return null
        if (draft.hasDiabetes && yearsValue(draft.yearsSinceDiagnosis) > ageValue) {
            return "Years with diabetes cannot be more than your age. Please check your information."
        }
        return null
    }

    private fun connectionProblem(draft: OnboardingDraft): String? {
        if (draft.connection == GlucoseConnectionOption.NIGHTSCOUT) {
            val trimmed = draft.nightscoutUrl.trim()
            if (trimmed.isNotEmpty() && NightscoutUrl.normalize(trimmed).isEmpty()) {
                return "That does not look like a web address. It should look like " +
                    "https://yourname.up.railway.app."
            }
        }

        val unit = draft.bgUnit
        val label = unit.displayName
        if (draft.lowGlucose < 60 || draft.lowGlucose > 110) {
            return "Low glucose must be between ${GlucoseDisplay.format(60.0, unit)} and " +
                "${GlucoseDisplay.format(110.0, unit)} $label."
        }
        if (draft.highGlucose < 110 || draft.highGlucose > 200) {
            return "High glucose must be between ${GlucoseDisplay.format(110.0, unit)} and " +
                "${GlucoseDisplay.format(200.0, unit)} $label."
        }
        if (draft.lowGlucose >= draft.highGlucose) {
            return "Low glucose must be less than high glucose."
        }
        return null
    }

    private val EMAIL_PATTERN =
        Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)

    fun isValidEmail(value: String): Boolean = EMAIL_PATTERN.matches(value)

    /** Years bucket to a comparable number of years. */
    fun yearsValue(bucket: String): Int = when (bucket) {
        "<1" -> 0
        "1-2" -> 1
        "3-9", "3-10" -> 5
        "10+" -> 10
        else -> 0
    }
}
