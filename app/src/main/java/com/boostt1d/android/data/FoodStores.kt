package com.boostt1d.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.foodDataStore: DataStore<Preferences> by preferencesDataStore(name = "boost_food")

/**
 * The food feature's small persistent facts: the daily estimation counter and the server-side
 * registration id. Synchronous reads block on DataStore; callers run off the main thread.
 */
class FoodStores(private val context: Context) {

    private val usedKey = intPreferencesKey("food_estimations_used")
    private val resetKey = longPreferencesKey("food_estimations_reset_at")
    private val registrationKey = stringPreferencesKey("registration_id")

    val usage: UsageStore = object : UsageStore {
        override var usedCount: Int
            get() = read { it[usedKey] ?: 0 }
            set(value) = write { it[usedKey] = value }
        override var lastResetMillis: Long?
            get() = read { it[resetKey] }
            set(value) = write { if (value == null) it.remove(resetKey) else it[resetKey] = value }
    }

    var registrationId: String?
        get() = read { it[registrationKey] }
        set(value) = write { if (value.isNullOrEmpty()) it.remove(registrationKey) else it[registrationKey] = value }

    suspend fun clear() {
        context.foodDataStore.edit { it.clear() }
    }

    private fun <T> read(block: (Preferences) -> T): T = runBlocking { block(context.foodDataStore.data.first()) }
    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking { context.foodDataStore.edit { block(it) } }
    }
}
