package com.boostt1d.android.sync

import android.util.Log
import com.boostt1d.android.data.NightscoutGlucoseEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * What went wrong, in terms the user can act on.
 *
 * [TermsNotAccepted] and [ClientTooOld] are the two nothing in this app can fix: the
 * first needs Abbott's own app, the second needs a new build of this one. They get their
 * own types so the reminder logic can stop retrying them.
 */
sealed class LibreException(message: String) : IOException(message) {
    class InvalidCredentials(message: String) : LibreException(message)
    class TermsNotAccepted : LibreException(
        "LibreLinkUp needs you to accept its updated terms. Open the LibreLinkUp app, sign in, " +
            "accept the terms, then try again here."
    )
    class ClientTooOld(val minimumVersion: String) : LibreException(
        "LibreLinkUp no longer accepts this version of BoostT1D's connection (it now requires " +
            "$minimumVersion). Please update BoostT1D — there is nothing wrong with your account."
    )
    class NoConnections : LibreException(
        "Signed in, but this LibreLinkUp account isn't following anyone yet. In the FreeStyle " +
            "Libre app, invite this email as a LibreLinkUp connection, then accept the invitation " +
            "in the LibreLinkUp app."
    )
    class NoGlucoseData : LibreException(
        "Connected, but no recent readings were found. Make sure the Libre sensor is active and " +
            "the FreeStyle Libre app is streaming to LibreView."
    )
    class UnsupportedRegion(region: String) : LibreException(
        "Your LibreView account is hosted in a region BoostT1D doesn't support yet ($region)."
    )
    class Transport(message: String) : LibreException(message)
}

/** A sign-in that worked: the ticket, the account, and the region that actually served it. */
data class LibreSession(
    val token: String,
    /** SHA-256 hex of the user id — LibreLinkUp wants it as an `Account-Id` header. */
    val accountIdHash: String,
    val region: LibreRegion,
    val patientId: String? = null,
)

/** What Test connection reports back. */
data class LibreVerification(
    val region: LibreRegion,
    val connectionName: String,
    val connectionCount: Int,
    val latestMgdl: Int?,
)

/**
 * Reads a LibreLinkUp account.
 *
 * LibreLinkUp is Abbott's *follower* service — there is no direct-to-third-party API for a
 * Libre sensor. The BoostT1D user signs in with a LibreLinkUp account the sensor wearer has
 * invited as a connection, which may be the wearer's own second account.
 *
 * The graph endpoint returns roughly the last twelve hours however much is asked for, so
 * history builds up over repeated syncs rather than arriving at once — and a gap wider
 * than twelve hours is permanent.
 */
class LibreLinkUpService(
    private val client: OkHttpClient = NightscoutService.defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // MARK: - Login

    /**
     * Signs in, following LibreView's regional redirect when it issues one.
     *
     * @throws LibreException.InvalidCredentials for a wrong email or password.
     * @throws LibreException.TermsNotAccepted when Abbott wants the terms re-accepted in its
     *   own app — retrying here can never clear that.
     */
    suspend fun login(email: String, password: String, region: LibreRegion): LibreSession =
        withContext(Dispatchers.IO) {
            val normalizedEmail = email.trim().lowercase(Locale.US)
            val normalizedPassword = password.trim()
            if (normalizedEmail.isEmpty()) throw LibreException.InvalidCredentials("Enter your LibreLinkUp email.")
            if (normalizedPassword.isEmpty()) throw LibreException.InvalidCredentials("Enter your LibreLinkUp password.")

            var attemptRegion = region
            // One redirect hop is all LibreView issues; the second pass is belt-and-braces.
            repeat(2) {
                val response = postLogin(normalizedEmail, normalizedPassword, attemptRegion)

                response.redirectRegion?.let { code ->
                    attemptRegion = LibreRegion.fromCode(code) ?: throw LibreException.UnsupportedRegion(code)
                    return@repeat
                }

                val token = response.token?.takeIf { it.isNotEmpty() }
                val userId = response.userId
                if (token == null || userId == null) {
                    if (response.requiresTermsAcceptance) throw LibreException.TermsNotAccepted()
                    throw LibreException.InvalidCredentials(response.errorMessage ?: "Invalid email or password.")
                }

                return@withContext LibreSession(token, sha256(userId), attemptRegion)
            }

            throw LibreException.Transport("LibreLinkUp kept redirecting between regions.")
        }

    // MARK: - Reading

    suspend fun fetchConnections(session: LibreSession): List<LibreConnection> = withContext(Dispatchers.IO) {
        val body = authorizedGet(session, LibrePath.CONNECTIONS, "connections")
        val connections = parseConnections(body)
        if (connections.isEmpty()) throw LibreException.NoConnections()
        connections
    }

    /**
     * Recent readings for the followed patient, newest first.
     *
     * @param minutes trims what comes back. The endpoint always returns about twelve hours;
     *   asking for more cannot extend it.
     */
    suspend fun fetchGlucose(session: LibreSession, minutes: Int = 24 * 60): List<NightscoutGlucoseEntry> =
        withContext(Dispatchers.IO) {
            val patientId = session.patientId ?: fetchConnections(session).first().patientId
            val body = authorizedGet(session, LibrePath.graph(patientId), "glucose graph")

            val measurements = parseGraph(body)
            val cutoff = System.currentTimeMillis() - maxOf(minutes, 1) * 60_000L
            val entries = LibreTranslator.entries(measurements).filter { it.epochMilliseconds >= cutoff }

            if (entries.isEmpty()) throw LibreException.NoGlucoseData()
            entries
        }

    /** Logs in and reads in one call, for a caller with credentials but no session. */
    suspend fun fetchGlucose(email: String, password: String, region: LibreRegion): Pair<LibreRegion, List<NightscoutGlucoseEntry>> {
        val session = login(email, password, region)
        return session.region to fetchGlucose(session)
    }

    /** One-shot sign-in and read for Test connection. */
    suspend fun verify(email: String, password: String, region: LibreRegion): LibreVerification {
        val session = login(email, password, region)
        val connections = fetchConnections(session)
        val first = connections.first()
        val latest = runCatching {
            fetchGlucose(session.copy(patientId = first.patientId)).firstOrNull()
        }.getOrNull()

        return LibreVerification(
            region = session.region,
            connectionName = first.displayName,
            connectionCount = connections.size,
            latestMgdl = latest?.sgv,
        )
    }

    // MARK: - Parsing

    private class LoginResponse(private val root: JsonObject) {
        private val data get() = root["data"] as? JsonObject

        val status: Int get() = root["status"]?.jsonPrimitive?.intOrNull ?: -1

        val redirectRegion: String?
            get() {
                if (data?.get("redirect")?.jsonPrimitive?.booleanOrNull != true) return null
                return data?.get("region")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            }

        val token: String? get() = (data?.get("authTicket") as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull
        val userId: String? get() = (data?.get("user") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull

        /** `status: 4` with a `step` means the terms or privacy policy need re-accepting. */
        val requiresTermsAcceptance: Boolean
            get() {
                val type = (data?.get("step") as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
                    ?.lowercase(Locale.US) ?: return false
                return type == "tou" || type == "pp" || "accept" in type
            }

        val errorMessage: String?
            get() {
                val raw = (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() } ?: return null
                return when (raw.lowercase(Locale.US)) {
                    "notauthenticated", "invalidcredentials" -> "Invalid email or password."
                    "toomanyattempts", "accountlocked" -> "Too many sign-in attempts. Wait a few minutes and try again."
                    else -> raw
                }
            }
    }

    internal fun parseConnections(body: String): List<LibreConnection> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val array = root["data"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val patientId = obj["patientId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            LibreConnection(
                patientId = patientId,
                firstName = obj["firstName"]?.jsonPrimitive?.contentOrNull,
                lastName = obj["lastName"]?.jsonPrimitive?.contentOrNull,
                current = (obj["glucoseMeasurement"] as? JsonObject)?.let(::parseMeasurement),
            )
        }
    }

    /** Graph points plus the current reading, which is the only one carrying a trend arrow. */
    internal fun parseGraph(body: String): List<LibreMeasurement> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()

        val points = (data["graphData"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::parseMeasurement) }
            .orEmpty()
        val current = ((data["connection"] as? JsonObject)?.get("glucoseMeasurement") as? JsonObject)
            ?.let(::parseMeasurement)

        return points + listOfNotNull(current)
    }

    /**
     * `ValueInMgPerDl` is canonical. Older and regional payloads sometimes send only `Value`
     * in the account's display unit, with `GlucoseUnits` 0 meaning mmol/L.
     */
    internal fun parseMeasurement(obj: JsonObject): LibreMeasurement? {
        val mgdl = obj["ValueInMgPerDl"]?.jsonPrimitive?.intOrNull
            ?: obj["Value"]?.jsonPrimitive?.doubleOrNull?.let { raw ->
                val units = obj["GlucoseUnits"]?.jsonPrimitive?.intOrNull ?: 1
                if (units == 0) Math.round(raw * 18.0182).toInt() else Math.round(raw).toInt()
            }
            ?: return null

        return LibreMeasurement(
            valueMgdl = mgdl,
            trendArrow = obj["TrendArrow"]?.jsonPrimitive?.intOrNull,
            factoryTimestamp = obj["FactoryTimestamp"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            timestamp = obj["Timestamp"]?.jsonPrimitive?.contentOrNull,
            isHigh = obj["isHigh"]?.jsonPrimitive?.booleanOrNull ?: false,
            isLow = obj["isLow"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /** `{"status":920,"data":{"minimumVersion":"4.16.0"}}` — the client is below Abbott's floor. */
    internal fun minimumVersionRequired(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return ((root["data"] as? JsonObject)?.get("minimumVersion") as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    /** Failures arrive as `{"message":…}` on data endpoints and `{"error":{"message":…}}` on login. */
    private fun apiMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return (root["message"]?.jsonPrimitive?.contentOrNull
            ?: (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull)
            ?.takeIf { it.isNotEmpty() }
    }

    // MARK: - HTTP

    private fun postLogin(email: String, password: String, region: LibreRegion): LoginResponse {
        val body = """{"email":${email.jsonString()},"password":${password.jsonString()}}"""
        val request = standardRequest(region.url(LibrePath.LOGIN))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val (code, text) = execute(request, "sign-in")

        minimumVersionRequired(text)?.let { throw LibreException.ClientTooOld(it) }

        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw LibreException.Transport(httpFailure(code, text))
        val response = LoginResponse(root)

        // LibreLinkUp answers 200 with a non-zero `status` for credential failures.
        when (response.status) {
            0 -> Unit
            2 -> throw LibreException.InvalidCredentials(response.errorMessage ?: "Invalid email or password.")
            4 -> throw LibreException.TermsNotAccepted()
            else -> {
                if (code == 401 || code == 403) {
                    throw LibreException.InvalidCredentials(
                        apiMessage(text) ?: response.errorMessage ?: "LibreLinkUp rejected the sign-in."
                    )
                }
                throw LibreException.Transport(response.errorMessage ?: "Unexpected status ${response.status}.")
            }
        }

        if (code !in 200..299) throw LibreException.Transport(httpFailure(code, text))
        return response
    }

    private fun authorizedGet(session: LibreSession, path: String, step: String): String {
        val request = standardRequest(session.region.url(path))
            .header("Authorization", "Bearer ${session.token}")
            .header("Account-Id", session.accountIdHash)
            .get()
            .build()

        val (code, text) = execute(request, step)

        // Abbott returns the version rejection as a 403, so it has to be recognised before
        // the status code alone gets read as a credential problem.
        minimumVersionRequired(text)?.let { throw LibreException.ClientTooOld(it) }

        if (code == 401 || code == 403) {
            // Sign-in worked, so this is not a stale session — the fresh ticket itself was
            // refused. There is no setting the user can change to fix that.
            throw LibreException.Transport(
                "LibreLinkUp accepted your email and password but refused the $step request " +
                    "(HTTP $code${apiMessage(text)?.let { " — $it" } ?: ""}). This is a fault in " +
                    "BoostT1D's LibreLinkUp connection, not your account — please report it."
            )
        }
        if (code !in 200..299) throw LibreException.Transport(httpFailure(code, text))
        return text
    }

    private fun standardRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("product", PRODUCT)
        .header("version", CLIENT_VERSION)
        .header("User-Agent", USER_AGENT)
        .header("Cache-Control", "no-cache")

    private fun execute(request: Request, step: String): Pair<Int, String> {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw LibreException.Transport(NightscoutService.friendlyError(e))
        }
        response.use {
            val text = it.body?.string().orEmpty()
            Log.d(TAG, "$step -> ${it.code}")
            return it.code to text
        }
    }

    private fun httpFailure(code: Int, body: String) =
        "LibreLinkUp error (HTTP $code): ${body.take(200)}"

    companion object {
        const val TAG = "BoostLibre"

        /**
         * LibreLinkUp client identifiers. The API rejects requests without them, and Abbott
         * enforces a floor on `version`: once a release drops below it the server answers
         * `status: 920` with the required `minimumVersion`, which [LibreException.ClientTooOld]
         * surfaces so the next bump is a one-line change here.
         */
        const val PRODUCT = "llu.ios"
        const val CLIENT_VERSION = "4.16.0"
        private const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

        /** LibreLinkUp wants the `Account-Id` header as the SHA-256 hex of the user id. */
        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
