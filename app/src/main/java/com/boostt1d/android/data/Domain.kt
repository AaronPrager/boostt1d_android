package com.boostt1d.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** iOS BGUnit. */
@Serializable
enum class BGUnit(val displayName: String) {
    @SerialName("mg/dL") MGDL("mg/dL"),
    @SerialName("mmol/L") MMOLL("mmol/L");

    companion object {
        /** Conventional UI factor: 1 mmol/L ~ 18 mg/dL (180 mg/dL -> 10.0 mmol/L). */
        private const val MGDL_PER_MMOLL = 18.0

        fun toMmolL(mgdL: Double): Double = mgdL / MGDL_PER_MMOLL
        fun toMgdL(mmolL: Double): Double = mmolL * MGDL_PER_MMOLL

        /** One decimal, so editing does not surface float noise like 9.999. */
        fun roundMmolToOneDecimal(mmol: Double): Double = Math.round(mmol * 10.0) / 10.0
    }
}

/** iOS InsulinTherapyType. */
@Serializable
enum class InsulinTherapyType(val displayName: String, val detail: String) {
    /** Not answered yet. Falls back to reading the data. */
    UNSPECIFIED("Not set", "BoostT1D will work it out from your uploaded doses."),
    CLOSED_LOOP(
        "Pump + CGM, automated",
        "An algorithm adjusts your insulin — Loop, AndroidAPS, Trio, iAPS, Omnipod 5, Control-IQ.",
    ),
    PUMP("Pump, manual", "You set your own basal rates and decide every bolus."),
    INJECTIONS("Pens or injections", "Long-acting insulin plus doses by pen or syringe.");

    companion object {
        /** Offered in the UI. UNSPECIFIED is a storage state, never a choice. */
        val selectable = listOf(CLOSED_LOOP, PUMP, INJECTIONS)
    }
}

/**
 * iOS GlucoseConnectionOption.
 *
 * The remote sources are declared so the enum keeps its shape and the settings that
 * carry a source survive a round trip — but only [MANUAL] is offered in this build.
 * See [selectableInThisBuild].
 */
@Serializable
enum class GlucoseConnectionOption(val displayName: String) {
    @SerialName("nightscout") NIGHTSCOUT("Nightscout"),
    @SerialName("dexcom") DEXCOM("Dexcom Share"),
    @SerialName("libre") LIBRE("FreeStyle Libre"),
    @SerialName("manual") MANUAL("Manual");

    companion object {
        /**
         * What setup offers. Dexcom Share and FreeStyle Libre are not ported yet, and an
         * option that cannot work is worse than one that is not shown.
         */
        val selectableInThisBuild = listOf(NIGHTSCOUT, MANUAL)
    }
}

/** iOS AgeSelectionOptions. */
object AgeSelectionOptions {
    const val MINIMUM_AGE = 1
    const val MAXIMUM_AGE = 100
    const val DEFAULT_AGE = 18
    val ages: List<Int> = (MINIMUM_AGE..MAXIMUM_AGE).toList()

    fun requiresParentGuardian(age: Int): Boolean = age in 1 until 13
}

/**
 * Glucose settings, trimmed to what a manual-only build actually stores.
 *
 * The iOS NightscoutSettings also carries site URL, tokens and CGM regions; those
 * fields arrive with the sync services, not before.
 */
@Serializable
data class GlucoseSettings(
    val connection: GlucoseConnectionOption = GlucoseConnectionOption.MANUAL,
    /** Always stored in mg/dL, whatever the user reads. */
    val lowGlucose: Double = 70.0,
    val highGlucose: Double = 180.0,
    /** Nightscout site address, already normalized. The token lives in [CredentialStore]. */
    val nightscoutUrl: String = "",
    /** When the last successful sync finished, so staleness can be shown honestly. */
    val lastSyncMillis: Long = 0L,
) {
    val isManualMode: Boolean get() = connection == GlucoseConnectionOption.MANUAL

    /** The vendor tag readings from the active source are stored under. */
    val primarySourceTag: String?
        get() = when (connection) {
            GlucoseConnectionOption.NIGHTSCOUT -> GlucoseSourceTag.NIGHTSCOUT
            GlucoseConnectionOption.DEXCOM -> GlucoseSourceTag.DEXCOM
            GlucoseConnectionOption.LIBRE -> GlucoseSourceTag.LIBRE
            GlucoseConnectionOption.MANUAL -> null
        }
}

/**
 * iOS UserProfile.
 *
 * Ages and durations are stored as instants, not as the buckets they were entered
 * with, so a profile keeps meaning as time passes — the same reason iOS stores a
 * date of birth rather than "18".
 */
@Serializable
data class UserProfile(
    val id: String = "",
    val name: String = "",
    /** Base64 JPEG, downscaled before it gets here. See PhotoScaling. */
    val photoData: String? = null,
    val country: String = "",
    /** ISO 3166-1 alpha-2 (e.g. "US"); may be empty for legacy saves. */
    val countryCode: String = "",
    val state: String? = null,
    val dateOfBirthEpochMillis: Long = 0L,
    val dateOfDiagnosisEpochMillis: Long = 0L,
    /** False when the user selected "I do not have diabetes". */
    val hasDiabetes: Boolean = true,
    val isProfileComplete: Boolean = false,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val bgUnit: BGUnit = BGUnit.MGDL,
    val parentName: String? = null,
    val parentEmail: String? = null,
    val email: String? = null,
    val gender: String? = null,
    /**
     * Opt-in to product news. Absent means false — there is no consent to assume,
     * and only an explicit choice is ever recorded.
     */
    val marketingOptIn: Boolean = false,
    val therapy: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
    /** The bucket the user picked, kept verbatim so Profile can show it back. */
    val yearsSinceDiagnosisBucket: String = "",
)
