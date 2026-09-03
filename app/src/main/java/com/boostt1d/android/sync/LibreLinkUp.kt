package com.boostt1d.android.sync

import com.boostt1d.android.data.NightscoutGlucoseEntry
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Where a LibreView account is hosted.
 *
 * [AUTOMATIC] is the global entry point, which answers a login with a redirect to the
 * account's real region. That redirect is followed once and the resolved region saved, so
 * the round trip is paid on the first sign-in only.
 */
@Serializable
enum class LibreRegion(val code: String, val displayName: String) {
    AUTOMATIC("automatic", "Detect automatically"),
    US("us", "United States"),
    EU("eu", "Europe"),
    EU2("eu2", "Europe 2"),
    DE("de", "Germany"),
    FR("fr", "France"),
    AP("ap", "Asia-Pacific"),
    AU("au", "Australia"),
    CA("ca", "Canada"),
    JP("jp", "Japan"),
    LA("la", "Latin America"),
    AE("ae", "Middle East"),
    RU("ru", "Russia");

    val apiHost: String
        get() = when (this) {
            AUTOMATIC -> "https://api.libreview.io"
            RU -> "https://api.libreview.ru"
            else -> "https://api-$code.libreview.io"
        }

    fun url(path: String): String = "$apiHost/$path"

    companion object {
        /** Resolves the `region` string LibreView returns in a login redirect. */
        fun fromCode(raw: String?): LibreRegion? {
            val normalized = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
            if (normalized == AUTOMATIC.code) return null
            return entries.firstOrNull { it.code == normalized }
        }
    }
}

/** The LibreLinkUp endpoints the app uses. */
object LibrePath {
    const val LOGIN = "llu/auth/login"
    const val CONNECTIONS = "llu/connections"
    fun graph(patientId: String) = "llu/connections/$patientId/graph"
}

/**
 * One glucose measurement from LibreLinkUp.
 *
 * The same shape serves the "current" reading on a connection and every point in the
 * ~12-hour graph, except that graph points carry no trend arrow.
 */
data class LibreMeasurement(
    /** mg/dL. Converted here if the payload only carried the account's display unit. */
    val valueMgdl: Int,
    /** LibreLinkUp arrow, 1 through 5. Absent on graph history points. */
    val trendArrow: Int?,
    /** UTC, formatted `M/d/yyyy h:mm:ss a`. */
    val factoryTimestamp: String,
    /** Device-local, same format. A fallback only. */
    val timestamp: String?,
    val isHigh: Boolean = false,
    val isLow: Boolean = false,
) {
    val millis: Long?
        get() = LibreTranslator.parseUtcMillis(factoryTimestamp)
            ?: timestamp?.let { LibreTranslator.parseLocalMillis(it) }

    val nightscoutDirection: String? get() = trendArrow?.let { LibreTranslator.direction(it) }

    fun toEntry(): NightscoutGlucoseEntry? {
        val at = millis ?: return null
        return NightscoutGlucoseEntry(sgv = valueMgdl, direction = nightscoutDirection, date = at, device = DEVICE)
    }

    companion object {
        const val DEVICE = "LibreLinkUp"
    }
}

/** A patient whose data a LibreLinkUp account is allowed to read. */
data class LibreConnection(
    val patientId: String,
    val firstName: String?,
    val lastName: String?,
    val current: LibreMeasurement?,
) {
    val displayName: String
        get() = listOfNotNull(firstName?.trim(), lastName?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "your Libre sensor" }
}

/** Turning what LibreLinkUp sends into what the rest of the app speaks. */
object LibreTranslator {

    /**
     * LibreLinkUp stamps dates `M/d/yyyy h:mm:ss a` in en_US_POSIX. Some regional responses
     * use a 24-hour clock instead, so both are attempted, plus a plain ISO form.
     */
    private val patterns = listOf("M/d/yyyy h:mm:ss a", "M/d/yyyy H:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")

    private fun parse(value: String, zone: TimeZone): Long? {
        val trimmed = value.trim().ifEmpty { return null }
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone; isLenient = false }.parse(trimmed)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    fun parseUtcMillis(value: String): Long? = parse(value, TimeZone.getTimeZone("UTC"))

    fun parseLocalMillis(value: String): Long? = parse(value, TimeZone.getDefault())

    /**
     * LibreLinkUp has five arrows. Nightscout's double-arrow values are never produced, and
     * anything outside 1..5 is a trend the sensor did not report.
     */
    fun direction(trendArrow: Int): String? = when (trendArrow) {
        1 -> "SingleDown"
        2 -> "FortyFiveDown"
        3 -> "Flat"
        4 -> "FortyFiveUp"
        5 -> "SingleUp"
        else -> null
    }

    /**
     * Newest first, one per timestamp.
     *
     * The graph payload and the current reading overlap on the newest point; the current
     * reading is the only one carrying a trend arrow, so it wins the shared timestamp.
     */
    fun entries(measurements: List<LibreMeasurement>): List<NightscoutGlucoseEntry> {
        val byDate = LinkedHashMap<Long, NightscoutGlucoseEntry>()
        for (entry in measurements.mapNotNull { it.toEntry() }) {
            val existing = byDate[entry.epochMilliseconds]
            if (existing != null && existing.direction != null && entry.direction == null) continue
            byDate[entry.epochMilliseconds] = entry
        }
        return byDate.values.sortedByDescending { it.epochMilliseconds }
    }
}
