package com.boostt1d.android.data

import java.util.Locale

/**
 * Unit conversion and formatting for everything the user reads a glucose value in.
 *
 * Ported from the iOS GlucoseDisplayService. Storage is always mg/dL; this is the
 * only place that knows what the user reads.
 */
object GlucoseDisplay {

    /**
     * Countries that read mg/dL. Everywhere else defaults to mmol/L.
     *
     * Kept as an explicit list rather than inferred, because the split does not
     * follow any other property of a locale.
     */
    private val MGDL_COUNTRIES = setOf(
        "AR", "AT", "BE", "BR", "CA", "CL", "CO", "CY", "EC", "EG", "FR", "GE",
        "DE", "GR", "IN", "ID", "IL", "IT", "JP", "JO", "KR", "LB", "MX", "PE",
        "PL", "PT", "ES", "SY", "TW", "TH", "TN", "TR", "AE", "US", "UY", "VE", "YE",
    )

    fun defaultUnit(countryCode: String): BGUnit =
        if (countryCode.uppercase(Locale.US) in MGDL_COUNTRIES) BGUnit.MGDL else BGUnit.MMOLL

    fun fromMgdL(mgdL: Double, unit: BGUnit): Double = when (unit) {
        BGUnit.MGDL -> mgdL
        BGUnit.MMOLL -> BGUnit.roundMmolToOneDecimal(BGUnit.toMmolL(mgdL))
    }

    fun toMgdL(value: Double, unit: BGUnit): Double = when (unit) {
        BGUnit.MGDL -> value
        BGUnit.MMOLL -> BGUnit.toMgdL(value)
    }

    /** A stored mg/dL value, written the way the user reads it. */
    fun format(mgdL: Double, unit: BGUnit): String = when (unit) {
        BGUnit.MGDL -> String.format(Locale.US, "%.0f", mgdL)
        BGUnit.MMOLL -> String.format(Locale.US, "%.1f", BGUnit.toMmolL(mgdL))
    }

    /** A value already in the user's unit, written for an editable field. */
    fun formatInUserUnit(value: Double, unit: BGUnit): String = when (unit) {
        BGUnit.MGDL -> String.format(Locale.US, "%.0f", value)
        BGUnit.MMOLL -> String.format(Locale.US, "%.1f", value)
    }
}

/** The country list the region step picks from, built from the platform's own ISO data. */
object Countries {
    data class Country(val code: String, val name: String)

    val all: List<Country> by lazy {
        Locale.getISOCountries()
            .map { Country(it, Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.US)) }
            .filter { it.name.isNotBlank() && it.name != it.code }
            .sortedBy { it.name }
    }

    fun name(code: String): String =
        all.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name ?: ""

    fun code(name: String): String =
        all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.code ?: ""
}
