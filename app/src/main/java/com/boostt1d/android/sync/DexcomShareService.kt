package com.boostt1d.android.sync

import android.util.Log
import com.boostt1d.android.data.NightscoutGlucoseEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/** What went wrong, in terms the user can act on — or not act on, which also matters. */
sealed class DexcomShareException(message: String) : IOException(message) {
    class InvalidCredentials(message: String) : DexcomShareException(message)
    class RateLimited(message: String) : DexcomShareException(message)
    class NoSharingEnabled(message: String) : DexcomShareException(message)
    class Transport(message: String) : DexcomShareException(message)
}

/**
 * Reads a Dexcom Share account.
 *
 * Share is a private API with no documentation and several quirks that only show up
 * against real accounts, so this is a faithful port of what the iOS client learned rather
 * than a clean-room implementation:
 *
 * - Login is tried against each host for the region, and each application ID, because
 *   some accounts answer only on a mirror and only for one of the two IDs.
 * - Account-id login is preferred where the account name is already a UUID; otherwise
 *   name login, then a two-step authenticate-then-login.
 * - A session id is a UUID, and Share signals failure by returning the *null* UUID with a
 *   200, which is why the response is checked rather than the status code.
 */
class DexcomShareService(
    private val client: OkHttpClient = NightscoutService.defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Share returns this instead of an error when a login did not actually work. */
    private val nullSessionId = "00000000-0000-0000-0000-000000000000"

    // MARK: - Login

    /**
     * A session id for the account, trying every host and application ID for the region.
     *
     * @throws DexcomShareException.InvalidCredentials when the account or password is
     *   rejected — retrying that only burns attempts against Dexcom's lockout.
     */
    suspend fun login(
        username: String,
        password: String,
        region: DexcomRegion,
    ): String = withContext(Dispatchers.IO) {
        var lastError: DexcomShareException =
            DexcomShareException.Transport("Could not reach Dexcom Share.")

        for (host in region.hosts) {
            for (applicationId in region.applicationIds) {
                try {
                    return@withContext authenticate(host, applicationId, username, password)
                } catch (e: DexcomShareException.InvalidCredentials) {
                    // Wrong credentials are wrong everywhere. Trying the other host and id
                    // would spend three more attempts against a lockout that counts them.
                    throw e
                } catch (e: DexcomShareException.RateLimited) {
                    throw e
                } catch (e: DexcomShareException) {
                    lastError = e
                }
            }
        }
        throw lastError
    }

    private fun authenticate(
        host: String,
        applicationId: String,
        username: String,
        password: String,
    ): String {
        // An account name that is already a UUID is an account id, and Share wants it on
        // the by-id endpoint; sending it by name fails with a misleading error.
        val accountId = username.trim().takeIf { it.looksLikeUuid() }

        if (accountId != null) {
            return post(
                host, DexcomSharePath.LOGIN_BY_ID,
                """{"accountId":"$accountId","password":${password.jsonString()},"applicationId":"$applicationId"}""",
            ).let(::sessionIdOrThrow)
        }

        // Name login first — one round trip when it works.
        runCatching {
            return post(
                host, DexcomSharePath.LOGIN_BY_NAME,
                """{"accountName":${username.jsonString()},"password":${password.jsonString()},"applicationId":"$applicationId"}""",
            ).let(::sessionIdOrThrow)
        }.onFailure { error ->
            if (error is DexcomShareException.InvalidCredentials) throw error
        }

        // Two-step: authenticate to get an account id, then log in with it. Some accounts
        // — notably those migrated between regions — only work this way.
        val resolvedId = post(
            host, DexcomSharePath.AUTHENTICATE,
            """{"accountName":${username.jsonString()},"password":${password.jsonString()},"applicationId":"$applicationId"}""",
        ).let(::sessionIdOrThrow)

        return post(
            host, DexcomSharePath.LOGIN_BY_ID,
            """{"accountId":"$resolvedId","password":${password.jsonString()},"applicationId":"$applicationId"}""",
        ).let(::sessionIdOrThrow)
    }

    // MARK: - Readings

    /**
     * Recent readings, newest first.
     *
     * Share serves roughly a day and ignores requests for more, which is the whole reason
     * a missed sync can cost readings permanently.
     */
    suspend fun fetchGlucose(
        sessionId: String,
        host: String,
        minutes: Int = 1440,
        maxCount: Int = 288,
    ): List<NightscoutGlucoseEntry> = withContext(Dispatchers.IO) {
        val body = post(
            host, DexcomSharePath.GLUCOSE,
            """{"sessionId":"$sessionId","minutes":$minutes,"maxCount":$maxCount}""",
        )
        DexcomTranslator.entries(parseReadings(body))
    }

    /** Logs in and reads in one call, for a caller that has credentials but no session. */
    suspend fun fetchGlucose(
        username: String,
        password: String,
        region: DexcomRegion,
        minutes: Int = 1440,
    ): List<NightscoutGlucoseEntry> {
        val sessionId = login(username, password, region)
        // The host that authenticated is the one holding the session.
        return fetchGlucose(sessionId, region.hosts.first(), minutes)
    }

    internal fun parseReadings(body: String): List<DexcomEgv> {
        val array = runCatching { json.parseToJsonElement(body.trim()).jsonArray }.getOrNull()
            ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val value = obj["Value"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val systemTime = obj["ST"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            // Trend arrives as an integer on some accounts and a string on others.
            val trendPrimitive = obj["Trend"]?.jsonPrimitive
            val trendCode = trendPrimitive?.intOrNull
            val trendName = trendPrimitive?.contentOrNull?.takeIf { trendCode == null }

            DexcomEgv(
                value = value,
                trend = trendCode ?: trendName?.let { DexcomTranslator.codeFromName(it) },
                trendDirection = trendName,
                systemTime = systemTime,
            )
        }
    }

    // MARK: - HTTP

    private fun post(host: String, path: String, body: String): String {
        val request = Request.Builder()
            .url(DexcomSharePath.url(host, path))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DexcomShareException.Transport(NightscoutService.friendlyError(e))
        }

        response.use {
            val text = it.body?.string().orEmpty()
            Log.d(TAG, "$path -> ${it.code}")

            if (!it.isSuccessful) throw classify(text, it.code)
            return text
        }
    }

    /**
     * A successful login returns a quoted UUID. Share also returns 200 with the null UUID
     * when the login did not work, so the body decides, not the status code.
     */
    private fun sessionIdOrThrow(body: String): String {
        val id = body.trim().trim('"')
        if (!id.looksLikeUuid() || id == nullSessionId) {
            throw classify(body, 200)
        }
        return id
    }

    /**
     * Share reports failures as a JSON body with a `Code`, not as a status code, and the
     * codes matter: a wrong password and a locked account need different advice.
     */
    private fun classify(body: String, status: Int): DexcomShareException {
        val lower = body.lowercase(Locale.US)

        val credentialMarkers = listOf(
            "accountpasswordinvalid", "passwordinvalid", "publisher account password",
            "invalidpassword", "accountnameinvalid", "cannot authenticate by accountname",
        )
        if (credentialMarkers.any { it in lower }) {
            return DexcomShareException.InvalidCredentials(
                "Dexcom rejected that username or password."
            )
        }

        if ("maxattemptsexceeded" in lower || "max attempts" in lower) {
            return DexcomShareException.RateLimited(
                "Too many attempts. Dexcom has locked sign-in for a while — wait, then try again."
            )
        }

        if ("sharingnotenabled" in lower || "not a publisher" in lower) {
            return DexcomShareException.NoSharingEnabled(
                "Sharing is not switched on for this account. Turn on Share in the Dexcom app first."
            )
        }

        return DexcomShareException.Transport(
            if (status in 500..599) {
                "Dexcom Share reported an error. Try again shortly."
            } else {
                "Dexcom Share did not accept that request."
            }
        )
    }

    companion object {
        const val TAG = "BoostDexcom"

        /** Share rejects requests without a recognisable agent. */
        private const val USER_AGENT = "Dexcom Share/3.0.2.11 CFNetwork/711.2.23 Darwin/14.0.0"
    }
}

/** Share account ids are UUIDs, and the distinction decides which endpoint to use. */
internal fun String.looksLikeUuid(): Boolean =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        .matches(this.trim())

/** Quotes and escapes a value for a hand-built JSON body. */
internal fun String.jsonString(): String = buildString {
    append('"')
    this@jsonString.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
