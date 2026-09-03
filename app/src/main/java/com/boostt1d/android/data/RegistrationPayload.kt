package com.boostt1d.android.data

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.TimeZone

/**
 * Demographics-only registration, no account and no password. Public connectivity identifiers
 * only — the Nightscout URL, the Dexcom username, the LibreLinkUp email — never a token.
 *
 * Ported from the iOS IosRegistrationService payload.
 */
@Serializable
data class RegistrationPayload(
    val name: String,
    val age: Int,
    val gender: String? = null,
    val email: String? = null,
    val country: String? = null,
    val state: String? = null,
    val diabetesAge: Int? = null,
    val yearsSinceDiagnosis: String? = null,
    val parentName: String? = null,
    val parentEmail: String? = null,
    /** `nightscout`, `dexcom`, `libre`, or `manual`. */
    val connectivityMethod: String? = null,
    val connectivityLogin: String? = null,
    val marketingOptIn: Boolean,
    val deviceModel: String? = null,
    val appVersion: String? = null,
)

object RegistrationPayloads {

    /** Builds a payload from the local profile. Null without a usable name. */
    fun from(
        profile: UserProfile,
        settings: GlucoseSettings,
        marketingOptIn: Boolean,
        deviceModel: String?,
        appVersion: String?,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): RegistrationPayload? {
        val name = profile.name.trim()
        if (name.isEmpty()) return null

        val age = yearsBetween(profile.dateOfBirthEpochMillis, nowMillis, timeZone).coerceAtLeast(0)
        val yearsBucket: String? = if (!profile.hasDiabetes) "none" else {
            when (yearsBetween(profile.dateOfDiagnosisEpochMillis, nowMillis, timeZone)) {
                in Int.MIN_VALUE..0 -> "<1"
                in 1..2 -> "1-2"
                in 3..9 -> "3-9"
                else -> "10+"
            }
        }
        val connectivity = connectivityFields(settings.connection, settings.nightscoutUrl, settings.dexcomUsername, settings.libreUsername)

        return RegistrationPayload(
            name = name,
            age = age,
            gender = nonEmpty(profile.gender),
            email = null,
            country = nonEmpty(profile.country),
            state = nonEmpty(profile.state),
            diabetesAge = diabetesAge(yearsBucket ?: "none"),
            yearsSinceDiagnosis = yearsBucket,
            parentName = nonEmpty(profile.parentName),
            parentEmail = nonEmpty(profile.parentEmail),
            connectivityMethod = connectivity.first,
            connectivityLogin = connectivity.second,
            marketingOptIn = marketingOptIn,
            deviceModel = deviceModel,
            appVersion = appVersion,
        )
    }

    fun diabetesAge(yearsSinceDiagnosis: String): Int? = when (yearsSinceDiagnosis) {
        "<1" -> 0
        "1-2" -> 1
        "3-9", "3-10" -> 5
        "10+" -> 10
        else -> null
    }

    fun normalizedYearsBucket(yearsSinceDiagnosis: String): String? = when {
        yearsSinceDiagnosis.isEmpty() -> null
        yearsSinceDiagnosis == "I do not have diabetes" -> "none"
        else -> yearsSinceDiagnosis
    }

    fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Public connectivity identifiers only — never API tokens or passwords. */
    fun connectivityFields(connection: GlucoseConnectionOption, nightscoutUrl: String, dexcomUsername: String, libreUsername: String = ""): Pair<String, String?> =
        when (connection) {
            GlucoseConnectionOption.NIGHTSCOUT -> "nightscout" to nonEmpty(nightscoutUrl)
            GlucoseConnectionOption.DEXCOM -> "dexcom" to nonEmpty(dexcomUsername)
            GlucoseConnectionOption.LIBRE -> "libre" to nonEmpty(libreUsername)
            else -> "manual" to null
        }

    private fun yearsBetween(fromMillis: Long, toMillis: Long, timeZone: TimeZone): Int {
        if (fromMillis <= 0) return 0
        val from = Calendar.getInstance(timeZone).apply { timeInMillis = fromMillis }
        val to = Calendar.getInstance(timeZone).apply { timeInMillis = toMillis }
        var years = to.get(Calendar.YEAR) - from.get(Calendar.YEAR)
        if (to.get(Calendar.DAY_OF_YEAR) < from.get(Calendar.DAY_OF_YEAR)) years -= 1
        return years
    }
}
