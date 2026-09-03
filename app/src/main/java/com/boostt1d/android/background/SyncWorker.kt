package com.boostt1d.android.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.boostt1d.android.data.CredentialStore
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.ProfileRepository
import com.boostt1d.android.sync.NightscoutService
import com.boostt1d.android.sync.SyncOrchestrator
import com.boostt1d.android.sync.SyncOutcome
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Glucose sync while the app is not on screen.
 *
 * Every other sync path is driven by the dashboard being visible, which makes an unopened
 * app indistinguishable from a broken one. That matters more here than in most apps: a
 * vendor CGM serves only a bounded window of history, so a gap wider than that window is
 * permanent loss.
 *
 * Android's floor is fifteen minutes and the system still defers under Doze, so this
 * narrows the average gap rather than closing it — [SyncReminders] remains the guarantee.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val profiles = ProfileRepository(applicationContext)
        val settings = profiles.settings.first()

        // Manual mode fetches nothing, so a wake-up would spend a grant on a no-op.
        if (settings.connection == GlucoseConnectionOption.MANUAL) {
            SyncScheduler.cancel(applicationContext)
            return Result.success()
        }

        val logs = LogRepository(applicationContext).also { it.load() }
        val credentials = CredentialStore(applicationContext)
        val orchestrator = SyncOrchestrator(NightscoutService(), logs, profiles, credentials)

        return when (val outcome = orchestrator.sync(settings)) {
            is SyncOutcome.Success -> {
                // A successful sync is what clears the reminder; nothing else does.
                SyncReminders.cancelStale(applicationContext)
                SyncReminders.cancelAuthFailure(applicationContext)
                Result.success()
            }

            is SyncOutcome.Failed -> {
                if (outcome.unauthorized) {
                    // Credentials cannot repair themselves, so this is said now rather than
                    // retried quietly until the window closes.
                    SyncReminders.postAuthFailure(applicationContext, settings.connection)
                    Result.success()
                } else {
                    warnIfApproachingWindow(settings.connection, settings.lastSyncMillis)
                    // Retried with backoff: a timeout on a train is not a broken connection.
                    Result.retry()
                }
            }

            SyncOutcome.NotConfigured -> Result.success()
        }
    }

    /**
     * Warns once the last success is three-quarters of the way through this source's
     * history window — the point where opening the app still saves the readings.
     *
     * Nightscout has no window, so there is nothing to warn about: a sync that has not
     * happened yet can still happen tomorrow and lose nothing.
     */
    private fun warnIfApproachingWindow(connection: GlucoseConnectionOption, lastSyncMillis: Long) {
        val windowHours = connection.historyWindowHours ?: return
        if (lastSyncMillis == 0L) return
        val elapsed = System.currentTimeMillis() - lastSyncMillis
        if (elapsed >= SyncReminders.staleDelayMillis(windowHours)) {
            SyncReminders.postStale(applicationContext, windowHours)
        }
    }
}

/** Enqueues and cancels the periodic sync. */
object SyncScheduler {

    private const val WORK_NAME = "boost-glucose-sync"

    /**
     * Fifteen minutes is Android's floor for periodic work, and asking for less does not
     * make it run more often — it makes the request invalid.
     */
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            // Periodic work fires immediately on first enqueue, but the source has only ever
            // been configured from the foreground, which syncs at that moment. Running again
            // at once duplicates a full 14-day fetch — and on Dexcom or Libre, a second login
            // against a lockout counter right as the user typed their password. Waiting one
            // interval costs nothing: the next useful sync is fifteen minutes out either way.
            .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        // KEEP, not REPLACE: replacing on every launch resets the interval, so an app
        // opened often would never actually reach a periodic run.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Called whenever the source changes, so manual mode stops waking the device. */
    fun applyFor(context: Context, connection: GlucoseConnectionOption) {
        if (connection == GlucoseConnectionOption.MANUAL) cancel(context) else schedule(context)
    }
}
