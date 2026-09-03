package com.boostt1d.android.sync

import android.util.Log
import com.boostt1d.android.data.Config
import com.boostt1d.android.data.FoodStores
import com.boostt1d.android.data.RegistrationPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Best-effort demographics sync. Every call swallows its own failure: local onboarding, the
 * marketing toggle and deletion must all complete whether or not the server answers.
 *
 * Ported from the iOS IosRegistrationService, pointed at the Android route in [Config].
 */
class RegistrationService(
    private val stores: FoodStores,
    client: OkHttpClient? = null,
) {
    private val client = client ?: OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val media = "application/json".toMediaType()

    @Serializable private data class SubmitResponse(val id: String? = null)
    @Serializable private data class IdBody(val id: String)
    @Serializable private data class OptInBody(val id: String, val marketingOptIn: Boolean)

    suspend fun submit(payload: RegistrationPayload) = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = post(Config.REGISTRATION_URL, json.encodeToString(RegistrationPayload.serializer(), payload))
            if (code !in 200..299) { Log.w(TAG, "Server returned $code"); return@runCatching }
            runCatching { json.decodeFromString(SubmitResponse.serializer(), body) }.getOrNull()?.id?.takeIf { it.isNotEmpty() }?.let { stores.registrationId = it }
        }.onFailure { Log.w(TAG, "Failed to submit demographics: ${it.message}") }
        Unit
    }

    /** Marks the server-side demographics row when the local profile is deleted. */
    suspend fun markProfileDeleted() = withContext(Dispatchers.IO) {
        val id = stores.registrationId?.takeIf { it.isNotEmpty() } ?: run { Log.i(TAG, "No stored registration id to mark deleted"); return@withContext }
        runCatching {
            val (code, _) = post(Config.REGISTRATION_MARK_DELETED_URL, json.encodeToString(IdBody.serializer(), IdBody(id)))
            if (code !in 200..299) { Log.w(TAG, "Mark deleted returned $code"); return@runCatching }
            stores.registrationId = null
        }.onFailure { Log.w(TAG, "Failed to mark profile deleted: ${it.message}") }
        Unit
    }

    /**
     * Opt-in with no prior row: submit the full payload (first collection). Either way with an
     * existing row: update the flag only. Opt-out with no row: nothing — we never recorded them.
     */
    suspend fun syncMarketingPreference(optedIn: Boolean, payloadIfNew: () -> RegistrationPayload?) {
        if (!stores.registrationId.isNullOrEmpty()) { updateMarketingOptIn(optedIn); return }
        if (!optedIn) return
        payloadIfNew()?.let { submit(it) }
    }

    suspend fun updateMarketingOptIn(optedIn: Boolean) = withContext(Dispatchers.IO) {
        val id = stores.registrationId?.takeIf { it.isNotEmpty() } ?: return@withContext
        runCatching {
            val (code, _) = post(Config.REGISTRATION_MARKETING_OPT_IN_URL, json.encodeToString(OptInBody.serializer(), OptInBody(id, optedIn)))
            if (code !in 200..299) Log.w(TAG, "Marketing opt-in update returned $code")
        }.onFailure { Log.w(TAG, "Failed to update marketing opt-in: ${it.message}") }
        Unit
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val request = Request.Builder().url(url).post(body.toRequestBody(media)).header("Content-Type", "application/json").build()
        client.newCall(request).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private companion object { const val TAG = "BoostRegistration" }
}
