package com.boostt1d.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secrets, kept out of the settings store.
 *
 * A Nightscout access token grants read access to someone's entire glucose history, so it
 * is held in EncryptedSharedPreferences — backed by the Android Keystore — rather than in
 * DataStore beside the display preferences. This is the same split iOS makes between the
 * Keychain and UserDefaults, and the reason `NightscoutSettings` deliberately excludes
 * `apiToken` from what it serializes.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            "boost_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // Keystore initialisation can fail on a device whose keys were invalidated. An
        // unencrypted fallback would silently downgrade where the token is stored, so the
        // app forgets the token instead and asks for it again.
        context.applicationContext
            .getSharedPreferences("boost_credentials_unavailable", Context.MODE_PRIVATE)
            .also { it.edit().clear().apply() }
    }

    var nightscoutToken: String
        get() = prefs.getString(KEY_NIGHTSCOUT_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().apply {
                if (value.isEmpty()) remove(KEY_NIGHTSCOUT_TOKEN) else putString(KEY_NIGHTSCOUT_TOKEN, value)
            }.apply()
        }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_NIGHTSCOUT_TOKEN = "nightscout_token"
    }
}
