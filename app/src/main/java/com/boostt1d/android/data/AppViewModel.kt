package com.boostt1d.android.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.boostt1d.android.background.SyncReminders
import com.boostt1d.android.background.SyncScheduler
import com.boostt1d.android.sync.DexcomRegion
import com.boostt1d.android.sync.DexcomShareService
import com.boostt1d.android.sync.LibreLinkUpService
import com.boostt1d.android.sync.LibreRegion
import com.boostt1d.android.sync.LibreVerification
import com.boostt1d.android.sync.NightscoutConnectionReport
import com.boostt1d.android.sync.NightscoutService
import com.boostt1d.android.sync.SyncOrchestrator
import com.boostt1d.android.sync.SyncOutcome
import com.boostt1d.android.engine.DailyTherapyReviewCache
import com.boostt1d.android.engine.PatternService
import com.boostt1d.android.sync.BoostBackend
import com.boostt1d.android.engine.TherapyChangeDetector
import com.boostt1d.android.engine.TherapyGlucoseFormatter
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.insights.WhatHappenedReportLoader
import com.boostt1d.android.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val dexcom = DexcomShareService()
    private val libre = LibreLinkUpService()
    // The engine's persistent facts and the report built from them. Everything here is lazy:
    // the stores block on their first DataStore read, so nothing may touch them on the main
    // thread, and nothing does — sync, saves and the report all run on background dispatchers.
    private val insightsStores by lazy { InsightsStores(application) }
    private val detector by lazy { TherapyChangeDetector(insightsStores.therapySnapshots) }
    private val analysisCache by lazy { WhatHappenedAnalysisCache(insightsStores.analysisCache) }
    private val dailyReviewCache by lazy { DailyTherapyReviewCache(insightsStores.dailyReview) }

    // Food: the diary, the daily estimation allowance, and the proxy both AI features call.
    val foodLog = FoodLogRepository(application)
    private val foodStores by lazy { FoodStores(application) }
    val usage by lazy { UsageTracker(foodStores.usage) }
    val backend by lazy {
        BoostBackend(usage = usage, therapyType = { (state.value as? AppState.Ready)?.profile?.therapy ?: InsulinTherapyType.UNSPECIFIED })
    }
    /** AI is a build-time switch; when off, every reviewer is absent and the formula stands alone. */
    private val aiReviewer: BoostBackend? get() = if (Config.AI_INSIGHTS_ENABLED) backend else null
    private val patternService by lazy { PatternService(reviewer = aiReviewer) }
    private val reportLoader by lazy {
        WhatHappenedReportLoader(patternService, detector, analysisCache, dailyReviewCache = dailyReviewCache, dailyReviewer = aiReviewer)
    }

    /** True while the once-daily AI wording is being fetched for an already painted report. */
    private val _aiReviewLoading = MutableStateFlow(false)
    val aiReviewLoading = _aiReviewLoading.asStateFlow()

    private val orchestrator = SyncOrchestrator(nightscout, logs, repository, credentials, detector = lazy { detector })

    /** The last What Happened result — painted instantly on open, refreshed underneath. */
    private val _reportSnapshot = MutableStateFlow<WhatHappenedAnalysisCache.Snapshot?>(null)
    val reportSnapshot = _reportSnapshot.asStateFlow()

    private val _reportLoading = MutableStateFlow(false)
    val reportLoading = _reportLoading.asStateFlow()

    /** Off by default: the Therapy page speaks plain English until someone asks for the numbers. */
    val advancedTherapyDetail: StateFlow<Boolean> =
        insightsStores.advancedTherapyDetail.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Non-null while a sync is running, so the UI can show it without guessing. */
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    private val _lastOutcome = MutableStateFlow<SyncOutcome?>(null)
    val lastOutcome = _lastOutcome.asStateFlow()

    private val _onBoard = MutableStateFlow(OnBoard.none)
    val onBoard = _onBoard.asStateFlow()

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
        viewModelScope.launch(Dispatchers.IO) {
            // Restored before any network call, so the report has something to show on the
            // very first frame — deferring it would reintroduce the spinner it exists to remove.
            _reportSnapshot.value = analysisCache.snapshot
        }

        viewModelScope.launch {
            logs.load()
            // Retention is enforced on launch rather than on write: a device left closed
            // for a month should not need a new reading before it prunes the old ones.
            logs.trimHistory(System.currentTimeMillis())

            SyncReminders.ensureChannel(application)
        }

        // Sync whenever the source *becomes* remote — at launch, and the moment setup or
        // the Data Source screen switches it. A one-shot check at launch missed the case
        // that matters most: someone who has just connected their site, looking at an
        // empty dashboard with no sync until they happen to restart the app.
        //
        // A CGM's history window is a hard loss boundary, so catching up the instant a
        // source is configured is not a nicety.
        viewModelScope.launch {
            repository.settings
                .map { it.connection }
                .distinctUntilChanged()
                .collect { connection ->
                    // Background sync follows the source: a manual setup should not be
                    // waking the device every fifteen minutes to fetch nothing.
                    SyncScheduler.applyFor(application, connection)
                    if (connection != GlucoseConnectionOption.MANUAL) {
                        syncNow(repository.settings.first())
                    }
                }
        }
    }

    fun save(profile: UserProfile, settings: GlucoseSettings) {
        viewModelScope.launch {
            repository.saveProfile(profile)
            repository.saveSettings(settings)
        }
    }

    fun saveSettings(settings: GlucoseSettings) {
        viewModelScope.launch {
            repository.saveSettings(settings)
            SyncScheduler.applyFor(getApplication(), settings.connection)
        }
    }

    fun saveCredentials(nightscoutToken: String, dexcomPassword: String, librePassword: String) {
        credentials.nightscoutToken = nightscoutToken
        credentials.dexcomPassword = dexcomPassword
        credentials.librePassword = librePassword
    }

    fun librePassword(): String = credentials.librePassword

    suspend fun testLibre(email: String, password: String, region: LibreRegion): Result<LibreVerification> =
        runCatching { libre.verify(email, password, region) }

    fun nightscoutToken(): String = credentials.nightscoutToken

    fun dexcomPassword(): String = credentials.dexcomPassword

    /**
     * A Dexcom login is the only honest test — Share has no status endpoint, and anything
     * short of signing in would pass for a wrong region.
     */
    suspend fun testDexcom(username: String, password: String, region: DexcomRegion): Result<Unit> =
        runCatching { dexcom.login(username, password, region) }.map { }

    suspend fun testNightscout(url: String, token: String): NightscoutConnectionReport =
        nightscout.testConnection(url, token)

    /** A sync the user asked for. Never runs two at once. */
    fun syncNow(settings: GlucoseSettings) {
        if (_syncing.value) return
        viewModelScope.launch { performSync(settings) }
    }

    private suspend fun performSync(settings: GlucoseSettings) {
        _syncing.value = true
        _lastOutcome.value = orchestrator.sync(settings)

        // Insulin and carbs on board come from the loop's own devicestatus, not from
        // anything the app derives. A failure here is not a sync failure — the
        // readings still arrived.
        _onBoard.value = runCatching {
            OnBoard.freshOrNone(
                nightscout.fetchOnBoard(settings.nightscoutUrl, credentials.nightscoutToken),
                System.currentTimeMillis(),
            )
        }.getOrDefault(OnBoard.none)

        _syncing.value = false
        importCarbsIntoFoodLog()
    }

    /** Carb-bearing treatments become Food Log rows. Idempotent, so safe after every sync and on every open of the diary. */
    fun importCarbsIntoFoodLog() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { foodLog.importCarbsFromTreatments(logs.treatments.value) }
        }
    }

    /**
     * Rebuilds the What Happened report: a sync first when there is a remote source, then the
     * builders over the fourteen days the app holds, off the main thread.
     *
     * The iOS report does both halves itself; here the sync is the same one every other screen
     * uses, so the report can never hold a different fourteen days from the dashboard.
     */
    fun refreshReport(settings: GlucoseSettings, profile: UserProfile) {
        if (_reportLoading.value) return
        viewModelScope.launch {
            _reportLoading.value = true
            try {
                if (settings.connection != GlucoseConnectionOption.MANUAL && !_syncing.value) {
                    performSync(settings)
                }
                val readings = logs.readingRows.first()
                val treatments = logs.treatments.value
                val therapy = repository.therapy.first()
                val unit = profile.bgUnit
                val nowMillis = System.currentTimeMillis()
                val food = withContext(Dispatchers.IO) { foodLog.snapshots(withinDays = 8, nowMillis = nowMillis) }
                val entries = withContext(Dispatchers.Default) {
                    GlucoseSourceStitch.stitched(
                        readings,
                        source = { it.source },
                        epoch = { it.epochMilliseconds },
                        preferredActive = settings.primarySourceTag,
                    ).map { it.toEntry() }
                }
                val document = therapy.toDocument()
                val snapshot = withContext(Dispatchers.Default) {
                    reportLoader.build(
                        entries = entries,
                        treatments = treatments,
                        profile = document,
                        lowGlucose = settings.lowGlucose,
                        highGlucose = settings.highGlucose,
                        therapyType = profile.therapy,
                        formatter = TherapyGlucoseFormatter({ Fmt.glucose(it, unit) }, unit.displayName),
                        nowMillis = nowMillis,
                        foodLogEntries = food,
                    )
                }
                _reportSnapshot.value = snapshot
                _reportLoading.value = false

                // Paint the complete local answer first, then enrich it in the background. The
                // cache enforces one AI attempt per calendar day, so refreshes and reopens do not
                // spend another request or move the wording around.
                if (aiReviewer != null) {
                    _aiReviewLoading.value = true
                    val enriched = withContext(Dispatchers.IO) {
                        runCatching {
                            reportLoader.enrich(
                                snapshot, entries, treatments, document, settings.lowGlucose, settings.highGlucose,
                                profile.therapy, nowMillis, foodLogEntries = food,
                            )
                        }.getOrDefault(snapshot)
                    }
                    _reportSnapshot.value = enriched
                }
            } finally {
                _reportLoading.value = false
                _aiReviewLoading.value = false
            }
        }
    }

    fun setAdvancedTherapyDetail(enabled: Boolean) {
        viewModelScope.launch { insightsStores.setAdvancedTherapyDetail(enabled) }
    }

    fun clearSyncOutcome() {
        _lastOutcome.value = null
    }

    fun saveTherapy(therapy: TherapyProfile) {
        viewModelScope.launch {
            repository.saveTherapy(therapy)
            // A device profile is overwritten in place and carries no start date, so this save
            // is the only moment the edit is observable. Recording it later would date the
            // change to whenever the user next opened the report, and every day already on
            // the new setting would count as "before".
            withContext(Dispatchers.IO) {
                detector.record(repository.therapy.first().toDocument(), emptyList(), System.currentTimeMillis())
            }
        }
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
            // A carb entry in the Event Log is a meal; it appears in the Food Log too.
            if ((carbs ?: 0.0) > 0) importCarbsIntoFoodLog()
        }
    }

    /**
     * Erases everything the app holds: profile, settings, credentials, readings and
     * treatments.
     *
     * The Privacy Policy says the app offers this, so it has to exist and has to be
     * complete — a delete that leaves the Nightscout token in the Keystore is not a
     * delete. Afterwards the app is back at setup, which is the honest end state.
     */
    fun deleteEverything() {
        viewModelScope.launch {
            SyncScheduler.cancel(getApplication())
            logs.deleteEverything()
            credentials.clear()
            repository.clear()
            withContext(Dispatchers.IO) {
                insightsStores.clear()
                analysisCache.invalidate()
                detector.reset()
                patternService.invalidateAll()
                foodLog.deleteAll()
                foodStores.clear()
            }
            _reportSnapshot.value = null
            _onBoard.value = OnBoard.none
            _lastOutcome.value = null
        }
    }

    fun deleteTreatment(cacheKey: String) {
        viewModelScope.launch { logs.deleteTreatment(cacheKey) }
    }
}
