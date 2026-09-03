package com.boostt1d.android.sync

import com.boostt1d.android.data.NightscoutOnBoard
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.ProfileStoreEntry
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.data.TimeValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Whether a given capability is reachable with the credentials supplied. */
sealed interface NightscoutAccess {
    data object Ok : NightscoutAccess
    data object Unauthorized : NightscoutAccess
    data class Unreachable(val message: String) : NightscoutAccess
}

/**
 * What a site will actually give us.
 *
 * `/api/v1/status` returns 200 for any URL regardless of the token, which is why testing
 * it alone reported success for connections that then downloaded nothing. Each capability
 * is probed against the endpoint the real fetch uses, with the same authentication.
 */
data class NightscoutConnectionReport(
    val glucose: NightscoutAccess,
    val treatments: NightscoutAccess,
    val profile: NightscoutAccess,
) {
    /**
     * Glucose is the only capability the app can run on by itself, so the connection is
     * usable the moment readings come back — even if the token is rejected for the rest.
     */
    val glucoseAvailable: Boolean get() = glucose is NightscoutAccess.Ok
    val fullyAuthorized: Boolean
        get() = glucose is NightscoutAccess.Ok &&
            treatments is NightscoutAccess.Ok &&
            profile is NightscoutAccess.Ok

    /** What to tell the user, in one sentence, without making them read three statuses. */
    fun message(hasToken: Boolean): String = when {
        fullyAuthorized -> "Connected. Readings, events and insulin doses are all available."
        glucoseAvailable && !hasToken ->
            "Connected for readings. Add an access token to download events and insulin doses too."
        glucoseAvailable ->
            "Connected for readings. This token cannot read events or insulin doses — a token " +
                "with readable permissions would bring those in as well."
        glucose is NightscoutAccess.Unauthorized ->
            "The site answered but rejected the token. Check it in your Nightscout settings."
        else -> (glucose as? NightscoutAccess.Unreachable)?.message ?: "Could not reach the site."
    }
}

class NightscoutException(message: String, val unauthorized: Boolean = false) : IOException(message)

/**
 * Reads a Nightscout site.
 *
 * Read-only by design: the app never writes to someone's Nightscout. Every call is a
 * suspending function on the IO dispatcher, so callers do not have to think about threads.
 */
class NightscoutService(
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // MARK: - Connection test

    /**
     * Probes the three endpoints the app downloads from, using the same authorization
     * each real fetch uses, so the result predicts what sync will actually manage.
     */
    suspend fun testConnection(url: String, token: String): NightscoutConnectionReport =
        withContext(Dispatchers.IO) {
            NightscoutConnectionReport(
                glucose = probe(url, "/api/v1/entries.json", listOf("count" to "1"), NightscoutUrl.glucoseStrategies(token)),
                treatments = probe(url, "/api/v1/treatments.json", listOf("count" to "1"), NightscoutUrl.therapyStrategies(token)),
                profile = probe(url, "/api/v1/profile.json", emptyList(), NightscoutUrl.therapyStrategies(token)),
            )
        }

    private fun probe(
        base: String,
        path: String,
        query: List<Pair<String, String>>,
        strategies: List<NightscoutUrl.AuthStrategy>,
    ): NightscoutAccess {
        var sawUnauthorized = false
        var lastMessage = "Could not reach the site."

        for (strategy in strategies) {
            try {
                execute(base, path, query, strategy)
                return NightscoutAccess.Ok
            } catch (e: NightscoutException) {
                if (e.unauthorized) sawUnauthorized = true else lastMessage = e.message ?: lastMessage
            } catch (e: IOException) {
                Log.d(TAG, "$path via ${describe(strategy)} -> ${e.message}")
                lastMessage = friendlyError(e)
            }
        }

        // A rejected token is a different problem from an unreachable site, and only one
        // of them is something the user can fix by editing the token.
        return if (sawUnauthorized) NightscoutAccess.Unauthorized else NightscoutAccess.Unreachable(lastMessage)
    }

    // MARK: - Fetches

    suspend fun fetchGlucose(url: String, token: String, hours: Int = 24): List<NightscoutGlucoseEntry> =
        withContext(Dispatchers.IO) {
            val cappedHours = hours.coerceIn(1, GlucoseCacheRules.RETENTION_DAYS * 24)
            val since = System.currentTimeMillis() - cappedHours * 60L * 60 * 1000

            val body = firstWorkingStrategy(
                url,
                "/api/v1/entries.json",
                listOf(
                    "find[type]" to "sgv",
                    "find[date][\$gte]" to since.toString(),
                    "count" to NightscoutUrl.glucoseQueryLimit(cappedHours).toString(),
                ),
                NightscoutUrl.glucoseStrategies(token),
            )

            parseGlucose(body).filter { it.epochMilliseconds >= since }
        }

    /**
     * Treatments filtered by `created_at`, which is the documented field, falling back to
     * `mills` for uploaders that do not write it.
     */
    suspend fun fetchTreatments(url: String, token: String, hours: Int = 24): List<NightscoutTreatment> =
        withContext(Dispatchers.IO) {
            val cappedHours = hours.coerceIn(1, GlucoseCacheRules.RETENTION_DAYS * 24)
            val endMillis = System.currentTimeMillis()
            val startMillis = endMillis - cappedHours * 60L * 60 * 1000
            val limit = NightscoutUrl.treatmentQueryLimit(cappedHours).toString()
            val strategies = NightscoutUrl.therapyStrategies(token)

            val body = try {
                firstWorkingStrategy(
                    url, "/api/v1/treatments.json",
                    listOf("find[created_at][\$gte]" to iso8601(startMillis), "count" to limit),
                    strategies,
                )
            } catch (e: NightscoutException) {
                // A rejected token fails every query shape the same way, so retrying with
                // `mills` would only replace a clear token error with a second 401.
                if (e.unauthorized) throw e
                firstWorkingStrategy(
                    url, "/api/v1/treatments.json",
                    listOf("find[mills][\$gte]" to startMillis.toString(), "count" to limit),
                    strategies,
                )
            }

            // A client-side filter as well, as a safety net for mixed schemas and clock skew.
            parseTreatments(body).filter { treatmentMillis(it) in startMillis..endMillis }
        }

    /**
     * Insulin and carbs on board, from whatever the loop last published to
     * `devicestatus`.
     *
     * Several uploaders write this and they disagree about where: Loop nests it under
     * `loop`, the oref lineage under `openaps.suggested` or `openaps.enacted`. All three
     * are read, newest first, and the first that carries a figure wins.
     */
    suspend fun fetchOnBoard(url: String, token: String): OnBoard = withContext(Dispatchers.IO) {
        val body = firstWorkingStrategy(
            url, "/api/v1/devicestatus.json", listOf("count" to "24"),
            NightscoutUrl.therapyStrategies(token),
        )
        parseOnBoard(body)
    }

    internal fun parseOnBoard(body: String): OnBoard {
        val array = runCatching { json.parseToJsonElement(body.trim()).jsonArray }.getOrNull()
            ?: return OnBoard.none

        // Newest first, so the freshest row carrying a figure wins. Nightscout usually returns
        // them that way already; sorting pins the assumption instead of trusting it.
        val rows = JsonArray(
            array.mapNotNull { it as? JsonObject }
                .sortedByDescending { parseTimestamp(it["created_at"]?.jsonPrimitive?.contentOrNull) ?: 0L }
        )

        // Every uploader's shape — Loop, Trio, AAPS, OpenAPS, pump — is read by the shared
        // walker, and IOB and COB may come from different rows. A figure with no timestamp
        // cannot be shown as current, so it is dropped rather than dated "now".
        val reading = NightscoutOnBoard.fromDeviceStatuses(rows, nowIso = "") ?: return OnBoard.none
        val at = parseTimestamp(reading.time.takeIf { it.isNotEmpty() }) ?: return OnBoard.none
        return OnBoard(reading.iob, reading.cob, at)
    }

    /** The therapy settings from `profile.json`, mapped onto the app's own shape. */
    suspend fun fetchTherapyProfile(url: String, token: String): TherapyProfile? =
        withContext(Dispatchers.IO) {
            val body = firstWorkingStrategy(
                url, "/api/v1/profile.json", emptyList(), NightscoutUrl.therapyStrategies(token),
            )
            parseTherapyProfile(body)
        }

    // MARK: - HTTP

    private fun firstWorkingStrategy(
        base: String,
        path: String,
        query: List<Pair<String, String>>,
        strategies: List<NightscoutUrl.AuthStrategy>,
    ): String {
        var lastError: IOException? = null
        for (strategy in strategies) {
            try {
                return execute(base, path, query, strategy)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: NightscoutException("Could not reach the site.")
    }

    private fun execute(
        base: String,
        path: String,
        query: List<Pair<String, String>>,
        strategy: NightscoutUrl.AuthStrategy,
    ): String {
        val allQuery = query.toMutableList()
        if (strategy is NightscoutUrl.AuthStrategy.Query) allQuery += strategy.name to strategy.value

        val url = NightscoutUrl.buildUrl(base, path, allQuery)
            ?: throw NightscoutException("That does not look like a web address.")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (strategy is NightscoutUrl.AuthStrategy.Header) header(strategy.field, strategy.value)
            }
            .build()

        client.newCall(request).execute().use { response ->
            // The strategy is named but never the credential itself, so a shared logcat
            // cannot leak someone's token.
            Log.d(TAG, "$path via ${describe(strategy)} -> ${response.code}")

            if (response.code == 401 || response.code == 403) {
                throw NightscoutException("The site rejected the access token.", unauthorized = true)
            }
            if (!response.isSuccessful) {
                throw NightscoutException(httpMessage(response.code))
            }
            return response.body?.string() ?: throw NightscoutException("The site returned nothing.")
        }
    }

    // MARK: - Parsing

    /**
     * Nightscout entries come back as JSON from most sites and as tab-separated text from
     * a few older ones. Both are accepted, because the alternative is telling a user their
     * working site is broken.
     */
    internal fun parseGlucose(body: String): List<NightscoutGlucoseEntry> {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            return runCatching {
                json.decodeFromString<List<NightscoutGlucoseEntry>>(trimmed)
            }.getOrElse { parseGlucoseLeniently(trimmed) }
        }
        return parseTsvGlucose(trimmed)
    }

    /** Skips individual malformed rows rather than failing the whole download. */
    private fun parseGlucoseLeniently(body: String): List<NightscoutGlucoseEntry> {
        val array = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(NightscoutGlucoseEntry.serializer(), element) }.getOrNull()
        }
    }

    private fun parseTsvGlucose(body: String): List<NightscoutGlucoseEntry> =
        body.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val millis = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
                val sgv = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
                NightscoutGlucoseEntry(
                    sgv = sgv,
                    date = millis,
                    direction = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            .toList()

    internal fun parseTreatments(body: String): List<NightscoutTreatment> {
        val array = runCatching { json.parseToJsonElement(body.trim()).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(NightscoutTreatment.serializer(), element) }.getOrNull()
        }.map { treatment ->
            // Nightscout dates arrive as `mills`, `created_at` or `timestamp` depending on
            // the uploader; normalizing here means nothing downstream has to know that.
            if (treatment.mills != null) treatment
            else treatment.copy(mills = parseTimestamp(treatment.createdAt ?: treatment.timestamp))
        }
    }

    /**
     * The flattened current settings: the first usable document in the order Nightscout returns
     * them, which is newest first. Kept for the callers that only need "what are the settings".
     */
    internal fun parseTherapyProfile(body: String): TherapyProfile? =
        parseProfileDocuments(body).firstNotNullOfOrNull { it.toTherapyProfile() }

    /** The therapy settings from `profile.json`, as documents with their dates intact. */
    suspend fun fetchProfileDocuments(url: String, token: String): List<NightscoutProfileDocument> =
        withContext(Dispatchers.IO) {
            val body = firstWorkingStrategy(
                url, "/api/v1/profile.json", emptyList(), NightscoutUrl.therapyStrategies(token),
            )
            parseProfileDocuments(body)
        }

    /**
     * `profile.json` is either one document or an array of historical ones. Every document is
     * kept — with its `mills`, `startDate` and `created_at` — because the list *is* the user's
     * edit history, and the change detector reads changes out of it that happened before the
     * app was installed. Unreadable rows are skipped rather than failing the whole payload.
     */
    internal fun parseProfileDocuments(body: String): List<NightscoutProfileDocument> {
        val root = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull() ?: return emptyList()
        val documents: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> return emptyList()
        }

        return documents.mapNotNull { document ->
            val store = (document["store"] as? JsonObject) ?: return@mapNotNull null
            val entries = store.mapNotNull { (name, raw) ->
                val obj = raw as? JsonObject ?: return@mapNotNull null
                name to ProfileStoreEntry(
                    units = obj["units"]?.jsonPrimitive?.contentOrNull,
                    dia = obj["dia"]?.jsonPrimitive?.doubleOrNull,
                    basal = timeValues(obj, "basal"),
                    carbRatio = timeValues(obj, "carbratio").ifEmpty { timeValues(obj, "carb_ratio") },
                    sensitivity = timeValues(obj, "sens").ifEmpty { timeValues(obj, "sensitivity") },
                    targetLow = timeValues(obj, "target_low"),
                    targetHigh = timeValues(obj, "target_high"),
                )
            }.toMap()

            NightscoutProfileDocument(
                id = document["_id"]?.let { idElement ->
                    (idElement as? JsonObject)?.get("\$oid")?.jsonPrimitive?.contentOrNull
                        ?: idElement.jsonPrimitive.contentOrNull
                },
                defaultProfile = document["defaultProfile"]?.jsonPrimitive?.contentOrNull,
                store = entries,
                mills = document["mills"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() },
                startDate = document["startDate"]?.jsonPrimitive?.contentOrNull,
                createdAt = document["created_at"]?.jsonPrimitive?.contentOrNull,
                units = document["units"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /**
     * A schedule entry states its start as `time` ("06:00") or `timeAsSeconds`, and its
     * value as a number or a quoted string. All four combinations appear in the wild.
     */
    private fun timeValues(profile: JsonObject, key: String): List<TimeValue> {
        val array = profile[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.let {
                it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()
            } ?: return@mapNotNull null

            val time = obj["time"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["timeAsSeconds"]?.jsonPrimitive?.let {
                    it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull()
                }?.let { seconds ->
                    val total = seconds.toInt()
                    String.format(Locale.US, "%02d:%02d", total / 3600, (total % 3600) / 60)
                }
                ?: return@mapNotNull null

            val parts = time.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            TimeValue(String.format(Locale.US, "%02d:%02d", hour, minute), value)
        }
    }

    private fun treatmentMillis(treatment: NightscoutTreatment): Long =
        treatment.mills ?: parseTimestamp(treatment.createdAt ?: treatment.timestamp) ?: 0L

    companion object {
        const val TAG = "BoostNightscout"

        /** Names an auth attempt without ever printing the credential. */
        fun describe(strategy: NightscoutUrl.AuthStrategy): String = when (strategy) {
            is NightscoutUrl.AuthStrategy.None -> "no credential"
            is NightscoutUrl.AuthStrategy.Header -> "${strategy.field} header"
            is NightscoutUrl.AuthStrategy.Query -> "${strategy.name} query"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val isoFormats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )

        fun parseTimestamp(raw: String?): Long? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { return if (it < 1_000_000_000_000L) it * 1000 else it }
            for (pattern in isoFormats) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(value)
                }.getOrNull()
                if (parsed != null) return parsed.time
            }
            return null
        }

        fun iso8601(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))

        fun httpMessage(code: Int): String = when (code) {
            400 -> "The site did not understand the request."
            404 -> "No Nightscout API at that address. Check the URL."
            429 -> "The site is rate limiting requests. Try again shortly."
            in 500..599 -> "The site reported an error. It may be waking up — try again shortly."
            else -> "The site returned an unexpected response ($code)."
        }

        fun friendlyError(error: IOException): String {
            val text = error.message.orEmpty().lowercase(Locale.US)
            return when {
                "timeout" in text || "timed out" in text ->
                    "The site did not respond in time. It may be asleep — try again shortly."
                "unable to resolve host" in text || "nodename" in text ->
                    "Could not find that address. Check the URL."
                "certificate" in text || "trust anchor" in text ->
                    "The site's security certificate could not be verified."
                else -> "Could not reach the site. Check the URL and your connection."
            }
        }
    }
}
