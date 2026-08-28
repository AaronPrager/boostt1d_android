package com.boostt1d.android.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.boostt1d.android.sync.NightscoutConnectionReport
import com.boostt1d.android.sync.NightscoutService
import com.boostt1d.android.sync.SyncOrchestrator
import com.boostt1d.android.sync.SyncOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone

/** What the app should be showing, once storage has actually answered. */
sealed interface AppState {
    /** DataStore has not emitted yet. Showing anything here would flash the wrong screen. */
    data object Loading : AppState
    data object NeedsOnboarding : AppState
    data class Ready(val profile: UserProfile, val settings: GlucoseSettings) : AppState
}

/**
 * Everything logged, summarized for the dashboard.
 *
 * Built in one place so the dashboard, the logs and the bolus calculator cannot disagree
 * about what today holds.
 */
data class LogState(
    /**
     * Already stitched: one representative per ~90-second cluster, with the active source
     * winning any overlap. Every screen reads this rather than the raw table, so no two
     * screens can disagree about which of two vendors described a given minute.
     */
    val readings: List<GlucoseReadingEntity> = emptyList(),
    val treatments: List<NightscoutTreatment> = emptyList(),
    val therapy: TherapyProfile = TherapyProfile(),
) {
    val entries: List<NightscoutGlucoseEntry> get() = readings.map { it.toEntry() }

    val latest: GlucoseReadingEntity? get() = readings.maxByOrNull { it.epochMilliseconds }

    fun todayStart(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        TodaySoFarBuilder.startOfDay(nowMillis, zone)

    fun statistics(low: Double, high: Double, sinceMillis: Long): GlucoseStatistics =
        GlucoseStatistics.calculate(
            readings.filter { it.epochMilliseconds >= sinceMillis }.map { it.sgv.toDouble() },
            low,
            high,
        )

    fun treatmentsSince(millis: Long): List<NightscoutTreatment> =
        treatments.filter { it.recordedAtMillis >= millis }

    fun insulinSince(millis: Long): Double =
        treatmentsSince(millis).sumOf { it.insulin ?: 0.0 }

    fun carbsSince(millis: Long): Double =
        treatmentsSince(millis).sumOf { it.carbs ?: 0.0 }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository(application)
    private val logs = LogRepository(application)

    val state: StateFlow<AppState> =
        combine(repository.profile, repository.settings) { profile, settings ->
            if (profile != null && profile.isProfileComplete) {
                AppState.Ready(profile, settings)
            } else {
                AppState.NeedsOnboarding
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState.Loading)

    private val credentials = CredentialStore(application)
    private val nightscout = NightscoutService()
    private val orchestrator = SyncOrchestrator(nightscout, logs, repository, credentials)

    /** Non-null while a sync is running, so the UI can show it without guessing. */
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    private val _lastOutcome = MutableStateFlow<SyncOutcome?>(null)
    val lastOutcome = _lastOutcome.asStateFlow()

    val logState: StateFlow<LogState> =
        combine(
            logs.readingRows, logs.treatments, repository.therapy, repository.settings,
        ) { readings, treatments, therapy, settings ->
            LogState(
                readings = GlucoseSourceStitch.stitched(
                    readings,
                    source = { it.source },
                    epoch = { it.epochMilliseconds },
                    preferredActive = settings.primarySourceTag,
                ),
                treatments = treatments,
                therapy = therapy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogState())

    init {
        viewModelScope.launch {
            logs.load()
            // Retention is enforced on launch rather than on write: a device left closed
            // for a month should not need a new reading before it prunes the old ones.
            logs.trimHistory(System.currentTimeMillis())

            // Sync on launch. A CGM's history window is a hard loss boundary — readings
            // older than it are gone for good — so the app catches up the moment it can
            // rather than waiting to be asked.
            val settings = repository.settings.first()
            if (settings.connection == GlucoseConnectionOption.NIGHTSCOUT) syncNow(settings)
        }
    }

    fun save(profile: UserProfile, settings: GlucoseSettings) {
        viewModelScope.launch {
            repository.saveProfile(profile)
            repository.saveSettings(settings)
        }
    }

    fun saveSettings(settings: GlucoseSettings) {
        viewModelScope.launch { repository.saveSettings(settings) }
    }

    fun saveNightscoutToken(token: String) {
        credentials.nightscoutToken = token
    }

    fun nightscoutToken(): String = credentials.nightscoutToken

    suspend fun testNightscout(url: String, token: String): NightscoutConnectionReport =
        nightscout.testConnection(url, token)

    /** A sync the user asked for. Never runs two at once. */
    fun syncNow(settings: GlucoseSettings) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            _lastOutcome.value = orchestrator.sync(settings)
            _syncing.value = false
        }
    }

    fun clearSyncOutcome() {
        _lastOutcome.value = null
    }

    fun saveTherapy(therapy: TherapyProfile) {
        viewModelScope.launch { repository.saveTherapy(therapy) }
    }

    fun addReading(sgvMgdl: Int, atMillis: Long) {
        viewModelScope.launch { logs.addManualReading(sgvMgdl, atMillis) }
    }

    fun deleteReading(epochMilliseconds: Long) {
        viewModelScope.launch { logs.deleteReading(epochMilliseconds) }
    }

    fun addTreatment(
        eventType: String,
        atMillis: Long,
        insulin: Double?,
        carbs: Double?,
        notes: String?,
        durationMinutes: Int?,
    ) {
        viewModelScope.launch {
            logs.addTreatment(eventType, atMillis, insulin, carbs, notes, durationMinutes)
        }
    }

    fun deleteTreatment(cacheKey: String) {
        viewModelScope.launch { logs.deleteTreatment(cacheKey) }
    }
}
