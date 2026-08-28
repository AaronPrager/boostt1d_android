package com.boostt1d.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "boost_profile")

/**
 * The profile and glucose settings, as two JSON values in DataStore.
 *
 * iOS keeps the same two objects in UserDefaults. DataStore is the direct
 * equivalent — a keyed store, read asynchronously, with no schema — which keeps
 * the port honest until there is enough data to justify Room.
 */
class ProfileRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val profileKey = stringPreferencesKey("user_profile")
    private val settingsKey = stringPreferencesKey("glucose_settings")

    val profile: Flow<UserProfile?> = context.dataStore.data.map { prefs ->
        prefs[profileKey]?.let { decodeOrNull<UserProfile>(it) }
    }

    val settings: Flow<GlucoseSettings> = context.dataStore.data.map { prefs ->
        prefs[settingsKey]?.let { decodeOrNull<GlucoseSettings>(it) } ?: GlucoseSettings()
    }

    suspend fun saveProfile(profile: UserProfile) {
        val stamped = profile.copy(updatedAtEpochMillis = System.currentTimeMillis())
        context.dataStore.edit { it[profileKey] = json.encodeToString(stamped) }
    }

    suspend fun saveSettings(settings: GlucoseSettings) {
        context.dataStore.edit { it[settingsKey] = json.encodeToString(settings) }
    }

    /**
     * A stored value that no longer parses is treated as absent rather than fatal:
     * a shape change during development should send the user back through setup,
     * not crash them out of the app with no way back in.
     */
    private inline fun <reified T> decodeOrNull(raw: String): T? =
        runCatching { json.decodeFromString<T>(raw) }.getOrNull()
}
