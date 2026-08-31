package com.boostt1d.android.sync

import com.boostt1d.android.data.CredentialStore
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.GlucoseSourceTag
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.ProfileRepository
import com.boostt1d.android.data.TherapyProfile
import java.io.IOException

/** What the last sync did, so the UI can say something specific rather than "error". */
sealed interface SyncOutcome {
    data class Success(
        val readings: Int,
        val treatments: Int,
        val therapyUpdated: Boolean,
        /** Capabilities the token could not reach — named, not swallowed. */
        val skipped: List<String>,
    ) : SyncOutcome

    data class Failed(val message: String, val unauthorized: Boolean) : SyncOutcome
    data object NotConfigured : SyncOutcome
}

/**
 * One download, in the order that matters.
 *
 * Glucose first and on its own: it is the only capability the app can run on, and a token
 * that cannot read treatments should still bring in readings rather than failing the whole
 * sync. Each later step is allowed to fail independently and is reported by name.
 *
 * How much history to ask for is decided by what is already stored — a first run pulls the
 * full retention window, a routine refresh pulls only the gap. Dexcom's ~24-hour and
 * Libre's ~12-hour windows are hard loss boundaries, so this deliberately over-fetches
 * slightly rather than risking a gap.
 */
class SyncOrchestrator(
    private val service: NightscoutService,
    private val logs: LogRepository,
    private val profiles: ProfileRepository,
    private val credentials: CredentialStore,
    private val dexcom: DexcomShareService = DexcomShareService(),
) {
    suspend fun sync(settings: GlucoseSettings, nowMillis: Long = System.currentTimeMillis()): SyncOutcome =
        when (settings.connection) {
            GlucoseConnectionOption.NIGHTSCOUT -> syncNightscout(settings, nowMillis)
            GlucoseConnectionOption.DEXCOM -> syncDexcom(settings, nowMillis)
            else -> SyncOutcome.NotConfigured
        }

    /**
     * Dexcom Share serves readings and nothing else — no treatments, no therapy settings.
     * Those stay whatever the user entered by hand, which is why the outcome names them
     * as unavailable rather than reporting zero of each.
     */
    private suspend fun syncDexcom(settings: GlucoseSettings, nowMillis: Long): SyncOutcome {
        val username = settings.dexcomUsername.ifBlank { return SyncOutcome.NotConfigured }
        val password = credentials.dexcomPassword.ifBlank { return SyncOutcome.NotConfigured }

        val entries = try {
            // Share ignores requests past its own window, so asking for a day is asking
            // for everything it has.
            dexcom.fetchGlucose(username, password, settings.dexcomRegion, minutes = 1440)
        } catch (e: DexcomShareException.InvalidCredentials) {
            return SyncOutcome.Failed(e.message ?: "Dexcom rejected those details.", unauthorized = true)
        } catch (e: DexcomShareException) {
            return SyncOutcome.Failed(e.message ?: "Could not reach Dexcom Share.", unauthorized = false)
        } catch (e: IOException) {
            return SyncOutcome.Failed(NightscoutService.friendlyError(e), unauthorized = false)
        }

        logs.upsertRemoteReadings(entries, GlucoseSourceTag.DEXCOM)
        logs.trimHistory(nowMillis)
        profiles.saveSettings(settings.copy(lastSyncMillis = nowMillis))

        return SyncOutcome.Success(
            readings = entries.size,
            treatments = 0,
            therapyUpdated = false,
            // Not a failure, a fact about the source: Share has no events to give.
            skipped = listOf("events and insulin doses (Dexcom Share provides readings only)"),
        )
    }

    private suspend fun syncNightscout(settings: GlucoseSettings, nowMillis: Long): SyncOutcome {
        val url = settings.nightscoutUrl.ifBlank { return SyncOutcome.NotConfigured }
        val token = credentials.nightscoutToken

        val hours = hoursToFetch(logs.latestReading()?.epochMilliseconds, nowMillis)

        val entries = try {
            service.fetchGlucose(url, token, hours)
        } catch (e: NightscoutException) {
            return SyncOutcome.Failed(e.message ?: "Sync failed.", e.unauthorized)
        } catch (e: IOException) {
            return SyncOutcome.Failed(NightscoutService.friendlyError(e), unauthorized = false)
        }

        logs.upsertRemoteReadings(entries, GlucoseSourceTag.NIGHTSCOUT)

        val skipped = mutableListOf<String>()

        val treatments = try {
            service.fetchTreatments(url, token, hours).also { logs.mergeRemoteTreatments(it) }
        } catch (e: IOException) {
            skipped.add("events")
            emptyList()
        }

        val therapyUpdated = try {
            val downloaded = service.fetchTherapyProfile(url, token)
            if (downloaded != null && !downloaded.isEmpty) {
                profiles.saveTherapy(downloaded)
                true
            } else {
                false
            }
        } catch (e: IOException) {
            skipped.add("insulin doses")
            false
        }

        logs.trimHistory(nowMillis)
        profiles.saveSettings(settings.copy(lastSyncMillis = nowMillis))

        return SyncOutcome.Success(entries.size, treatments.size, therapyUpdated, skipped)
    }

    /**
     * A first run pulls the whole retention window; a routine refresh pulls only the gap
     * since the newest stored reading, plus an hour of overlap so a boundary reading
     * cannot fall between two syncs.
     */
    internal fun hoursToFetch(newestStoredMillis: Long?, nowMillis: Long): Int {
        val maxHours = GlucoseCacheRules.RETENTION_DAYS * 24
        if (newestStoredMillis == null) return maxHours
        val gapHours = ((nowMillis - newestStoredMillis) / 3_600_000.0).toInt() + 1
        return gapHours.coerceIn(1, maxHours)
    }
}
