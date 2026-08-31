package com.boostt1d.android.sync

import com.boostt1d.android.data.NightscoutGlucoseEntry
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Where a Dexcom Share account lives.
 *
 * Share is regional and the hosts do not federate: a US account gets nothing but errors
 * from the OUS host. Several are tried because some accounts only answer on a mirror.
 */
@kotlinx.serialization.Serializable
enum class DexcomRegion(val displayName: String) {
    US("US"),
    OUS("Outside US"),
    JAPAN("Japan");

    val hosts: List<String>
        get() = when (this) {
            US -> listOf("https://share2.dexcom.com", "https://share1.dexcom.com")
            OUS -> listOf("https://shareous1.dexcom.com")
            JAPAN -> listOf("https://share.dexcom.jp")
        }

    /**
     * Share Publisher application IDs — not Dexcom Developer OAuth client IDs.
     *
     * The first is the one the Nightscout bridge has used for years and is the most
     * widely accepted; Dexcom's own is tried after it, and first in Japan where the
     * order is reversed.
     */
    val applicationIds: List<String>
        get() = when (this) {
            US, OUS -> listOf(NIGHTSCOUT_BRIDGE_APP_ID, DEXCOM_APP_ID)
            JAPAN -> listOf(DEXCOM_APP_ID, NIGHTSCOUT_BRIDGE_APP_ID)
        }

    companion object {
        const val NIGHTSCOUT_BRIDGE_APP_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"
        const val DEXCOM_APP_ID = "d8665ade-9673-4e27-9ff6-92db4ce13d13"

        /** A reasonable first guess from the user's country, still overridable. */
        fun suggestedFor(countryCode: String?): DexcomRegion = when (countryCode?.uppercase()) {
            "US", "PR", "VI", "GU" -> US
            "JP" -> JAPAN
            null, "" -> US
            else -> OUS
        }
    }
}

/** The Share endpoints the app uses. All are POSTs; Share ignores the request body's shape. */
object DexcomSharePath {
    const val AUTHENTICATE = "General/AuthenticatePublisherAccount"
    const val LOGIN_BY_ID = "General/LoginPublisherAccountById"
    const val LOGIN_BY_NAME = "General/LoginPublisherAccountByName"
    const val GLUCOSE = "Publisher/ReadPublisherLatestGlucoseValues"

    /** The host is chosen per attempt, so the URL is built from it rather than a region. */
    fun url(host: String, path: String): String = "$host/ShareWebServices/Services/$path"
}

/** One reading as Dexcom Share returns it. */
data class DexcomEgv(
    /** Blood glucose, mg/dL. */
    val value: Int,
    /** Trend as the legacy integer, when Share sends that form. */
    val trend: Int?,
    /** Trend as the modern string, when Share sends that form instead. */
    val trendDirection: String?,
    /** System time: `/Date(ms)/`, `Date(ms)`, plain epoch millis, or ISO-8601. */
    val systemTime: String,
) {
    val nightscoutDirection: String?
        get() = trendDirection?.let { DexcomTranslator.directionFromName(it) }
            ?: trend?.let { DexcomTranslator.directionFromCode(it) }

    fun toEntry(): NightscoutGlucoseEntry? {
        val millis = DexcomTranslator.parseMilliseconds(systemTime) ?: return null
        return NightscoutGlucoseEntry(
            sgv = value,
            direction = nightscoutDirection,
            date = millis,
            device = DEVICE,
        )
    }

    companion object {
        const val DEVICE = "Dexcom Share"
    }
}

/**
 * Turning what Share sends into what the rest of the app already speaks.
 *
 * Share reports trend two different ways depending on the account and the endpoint, and
 * timestamps in four. Everything downstream sees a Nightscout-shaped entry, so none of
 * that variety leaks past this file.
 */
object DexcomTranslator {

    /** Microsoft-style `/Date(1756382400000)/`, with an optional timezone offset. */
    private val dateMillisPattern = Regex("""\(-?\d+""")

    fun parseMilliseconds(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        dateMillisPattern.find(trimmed)?.let { match ->
            match.value.drop(1).toLongOrNull()?.let { return it }
        }

        if (trimmed.all { it.isDigit() }) return trimmed.toLongOrNull()

        return NightscoutService.parseTimestamp(trimmed)
    }

    /** The modern string form. Anything Share cannot compute has no direction at all. */
    fun directionFromName(name: String): String? = when (name) {
        "DoubleUp", "SingleUp", "FortyFiveUp", "Flat",
        "FortyFiveDown", "SingleDown", "DoubleDown" -> name
        // "None", "NotComputable", "NonComputable", "RateOutOfRange" — a trend that does
        // not exist is not "Flat", and showing an arrow for it would invent one.
        else -> null
    }

    /** The legacy integer form, 1 through 7. */
    fun directionFromCode(code: Int): String? = when (code) {
        1 -> "DoubleUp"
        2 -> "SingleUp"
        3 -> "FortyFiveUp"
        4 -> "Flat"
        5 -> "FortyFiveDown"
        6 -> "SingleDown"
        7 -> "DoubleDown"
        else -> null
    }

    fun codeFromName(name: String): Int? = when (name) {
        "DoubleUp" -> 1
        "SingleUp" -> 2
        "FortyFiveUp" -> 3
        "Flat" -> 4
        "FortyFiveDown" -> 5
        "SingleDown" -> 6
        "DoubleDown" -> 7
        else -> null
    }

    fun entries(readings: List<DexcomEgv>): List<NightscoutGlucoseEntry> =
        readings.mapNotNull { it.toEntry() }.sortedByDescending { it.epochMilliseconds }

    /** Nightscout-compatible ISO-8601, for anything that wants a `dateString`. */
    fun iso8601(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(millis))
}
