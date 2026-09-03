package com.boostt1d.android.sync

import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The full download that runs after a new user accepts setup or an existing user changes
 * their connection, given its own screen so the dashboard opens on data that is already on
 * the device rather than filling in underneath the user. Ported from the iOS InitialDataDownload.
 *
 * iOS runs three requests; here the orchestrator makes one pass in the same order and each
 * step settles from that pass's outcome, so the rows read the same.
 */
class InitialDataDownload(
    settings: GlucoseSettings,
    val reason: Reason,
    private val sync: suspend () -> SyncOutcome,
) {
    enum class Reason { INITIAL_SETUP, CONNECTION_CHANGE }
    enum class Stage { GLUCOSE, TREATMENTS, THERAPY }

    sealed interface Status {
        data object Waiting : Status
        data object Running : Status
        data class Done(val summary: String) : Status
        data class Failed(val message: String) : Status
    }

    data class Step(val stage: Stage, val title: String, val subtitle: String, val status: Status = Status.Waiting)

    private val _steps = MutableStateFlow(plan(settings))
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()
    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    val sourceName: String = sourceName(settings.connection)

    val progress: Double
        get() {
            val steps = _steps.value
            if (steps.isEmpty()) return 1.0
            return steps.count { it.status is Status.Done || it.status is Status.Failed }.toDouble() / steps.size
        }

    val hasFailures: Boolean get() = _steps.value.any { it.status is Status.Failed }

    suspend fun run() {
        _isFinished.value = false
        val steps = _steps.value
        if (steps.isEmpty()) { _isFinished.value = true; return }

        set(0, Status.Running)
        val outcome = runCatching { sync() }.getOrElse { SyncOutcome.Failed(it.message ?: "The download failed.", unauthorized = false) }

        for (index in steps.indices) {
            if (index > 0) set(index, Status.Running)
            set(index, status(steps[index].stage, outcome))
            // A step that resolves from cache finishes faster than the eye can follow;
            // without this the whole screen flashes past and reads as a glitch.
            delay(300)
        }
        _isFinished.value = true
    }

    /** Re-runs everything rather than only what failed; each step merges, so repeating one costs a request and changes nothing. */
    suspend fun retry() {
        _steps.value = _steps.value.map { it.copy(status = Status.Waiting) }
        run()
    }

    private fun set(index: Int, status: Status) {
        _steps.value = _steps.value.mapIndexed { i, step -> if (i == index) step.copy(status = status) else step }
    }

    private fun status(stage: Stage, outcome: SyncOutcome): Status = when (outcome) {
        is SyncOutcome.Failed -> Status.Failed(outcome.message)
        SyncOutcome.NotConfigured -> Status.Done("Nothing to download")
        is SyncOutcome.Success -> when (stage) {
            Stage.GLUCOSE -> Status.Done(if (outcome.readings == 0) "No readings yet" else "${outcome.readings} readings")
            Stage.TREATMENTS ->
                if (outcome.skipped.any { it.contains("event", ignoreCase = true) || it.contains("treatment", ignoreCase = true) }) Status.Failed("Not reachable with this token")
                else Status.Done(if (outcome.treatments == 0) "Nothing logged yet" else "${outcome.treatments} entries")
            Stage.THERAPY ->
                if (outcome.skipped.any { it.contains("profile", ignoreCase = true) || it.contains("therapy", ignoreCase = true) || it.contains("dose", ignoreCase = true) }) Status.Failed("Not reachable with this token")
                else Status.Done(if (outcome.therapyUpdated) "Saved" else "None published")
        }
    }

    companion object {
        /**
         * Only the steps the chosen source can actually answer. Dexcom Share and LibreLinkUp
         * serve glucose and nothing else, so listing a treatments step for them would show a
         * row that could only ever be skipped.
         */
        fun plan(settings: GlucoseSettings): List<Step> {
            val steps = mutableListOf<Step>()
            when (settings.connection) {
                GlucoseConnectionOption.NIGHTSCOUT -> steps += Step(Stage.GLUCOSE, "Glucose history", "Up to ${GlucoseCacheRules.RETENTION_DAYS} days of readings")
                GlucoseConnectionOption.DEXCOM -> steps += Step(Stage.GLUCOSE, "Glucose history", "The last 24 hours Dexcom Share keeps")
                GlucoseConnectionOption.LIBRE -> steps += Step(Stage.GLUCOSE, "Glucose history", "The readings LibreLinkUp keeps")
                else -> Unit
            }
            if (settings.connection == GlucoseConnectionOption.NIGHTSCOUT) {
                steps += Step(Stage.TREATMENTS, "Insulin and carbs", "Doses and meals from your event log")
                steps += Step(Stage.THERAPY, "Insulin doses", "Basal rates, carb ratios and correction factors")
            }
            return steps
        }

        fun sourceName(connection: GlucoseConnectionOption): String = when (connection) {
            GlucoseConnectionOption.NIGHTSCOUT -> "Nightscout"
            GlucoseConnectionOption.DEXCOM -> "Dexcom Share"
            GlucoseConnectionOption.LIBRE -> "LibreLinkUp"
            else -> "Manual entry"
        }
    }
}
