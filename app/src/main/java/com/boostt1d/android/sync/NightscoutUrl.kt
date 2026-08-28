package com.boostt1d.android.sync

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale

/**
 * Everything about addressing and authenticating a Nightscout site.
 *
 * Kept separate from the HTTP client so the parts that are pure string handling — and
 * therefore the parts most likely to be wrong — can be tested without a network.
 */
object NightscoutUrl {

    /**
     * Normalizes a site address the way iOS does.
     *
     * Returns an empty string for anything unusable, so a half-typed address shows a
     * placeholder rather than an error the user cannot act on yet. HTTP is upgraded to
     * HTTPS: a Nightscout token travels on every request, and sending it in clear text
     * because the user omitted one letter is not a choice worth offering.
     */
    fun normalize(raw: String): String {
        var normalized = raw.trim()
        if (normalized.isEmpty()) return ""

        while (normalized.endsWith("/")) normalized = normalized.dropLast(1)

        val lower = normalized.lowercase(Locale.US)
        normalized = when {
            lower.startsWith("http://") -> "https://" + normalized.substring("http://".length)
            lower.startsWith("https://") -> normalized
            // Still typing the protocol — do not prepend a second one.
            lower.startsWith("https:") || lower.startsWith("http:") -> return raw.trim()
            else -> "https://$normalized"
        }

        return if (hasHost(normalized)) normalized else ""
    }

    private fun hasHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return host.isNotEmpty() && host != "https" && host != "http"
    }

    /**
     * Nightscout authenticates with the SHA-1 of the access token, not the token itself.
     * This matches what the web app sends, and a site will reject the raw value.
     */
    fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** How a request authenticates. Tried in order until one returns 200. */
    sealed interface AuthStrategy {
        data object None : AuthStrategy
        data class Header(val field: String, val value: String) : AuthStrategy
        data class Query(val name: String, val value: String) : AuthStrategy
    }

    /**
     * Glucose accepts any of three shapes, because Nightscout deployments differ in
     * which they honour: the api-secret header, X-API-Key, then a token query parameter.
     */
    fun glucoseStrategies(token: String): List<AuthStrategy> {
        if (token.isEmpty()) return listOf(AuthStrategy.None)
        val hashed = sha1(token)
        return listOf(
            AuthStrategy.Header("api-secret", hashed),
            AuthStrategy.Header("X-API-Key", hashed),
            AuthStrategy.Query("token", hashed),
        )
    }

    /**
     * Treatments and the therapy profile authenticate only with the api-secret header.
     * A token that cannot satisfy that will not download them, and the probe reports the
     * capability unavailable rather than over-promising.
     */
    fun therapyStrategies(token: String): List<AuthStrategy> =
        if (token.isEmpty()) listOf(AuthStrategy.None)
        else listOf(AuthStrategy.Header("api-secret", sha1(token)))

    /** Query limits sized to the window, matching the iOS caps. */
    fun glucoseQueryLimit(hours: Int): Int {
        val days = hours / 24.0
        return when {
            days >= 30 -> 10_000
            days >= 7 -> 5_000
            else -> 1_000
        }
    }

    /** Treatments are far fewer than readings, so the caps are much smaller. */
    fun treatmentQueryLimit(hours: Int): Int {
        val days = hours / 24.0
        return when {
            days >= 14 -> 1_500
            days >= 7 -> 800
            days >= 2 -> 400
            else -> 200
        }
    }

    fun buildUrl(base: String, path: String, query: List<Pair<String, String>>): HttpUrl? {
        val root = normalize(base).ifEmpty { return null }
        val builder = "$root$path".toHttpUrlOrNull()?.newBuilder() ?: return null
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }
}
