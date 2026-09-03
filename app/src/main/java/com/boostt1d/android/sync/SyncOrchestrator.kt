package com.boostt1d.android.sync

import com.boostt1d.android.data.CredentialStore
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.GlucoseSourceTag
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.ProfileRepository
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.engine.TherapyChangeDetector
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
    private val libre: LibreLinkUpService = LibreLinkUpService(),
    /**
     * Told about every profile document fetched, so a setting edited on Nightscout is dated.
     * Lazy because the detector's store blocks on first read and this runs off the main thread.
     */
    private val detector: Lazy<TherapyChangeDetector>? = null,
) {
    suspend fun sync(settings: GlucoseSettings, nowMillis: Long = System.currentTimeMillis()): SyncOutcome =
        when (settings.connection) {
            GlucoseConnectionOption.NIGHTSCOUT -> syncNightscout(settings, nowMillis)
            GlucoseConnectionOption.DEXCOM -> syncDexcom(settings, nowMillis)
            GlucoseConnectionOption.LIBRE -> syncLibre(settings, nowMillis)
            else -> SyncOutcome.NotConfigured
        }

    /**
     * LibreLinkUp serves about twelve hours and, like Dexcom, readings only. The region
     * LibreView redirected to is saved back, so the next sync skips the redirect.
     */
    private suspend fun syncLibre(settings: GlucoseSettings, nowMillis: Long): SyncOutcome {
        val email = settings.libreUsername.ifBlank { return SyncOutcome.NotConfigured }
        val password = credentials.librePassword.ifBlank { return SyncOutcome.NotConfigured }

        val (servedRegion, entries) = try {
            libre.fetchGlucose(email, password, settings.libreRegion)
        } catch (e: LibreException.InvalidCredentials) {
            return SyncOutcome.Failed(e.message ?: "LibreLinkUp rejected those details.", unauthorized = true)
        } catch (e: LibreException.TermsNotAccepted) {
            // Not a credential problem and not transient: only Abbott's own app clears it.
            return SyncOutcome.Failed(e.message ?: "LibreLinkUp needs its terms accepted.", unauthorized = true)
        } catch (e: LibreException.NoGlucoseData) {
            // A quiet sensor is not a broken connection. Record the sync so staleness is
            // measured from now, and report nothing new.
            profiles.saveSettings(settings.copy(lastSyncMillis = nowMillis))
            return SyncOutcome.Success(0, 0, false, listOf("events and insulin doses (LibreLinkUp provides readings only)"))
        } catch (e: LibreException) {
            return SyncOutcome.Failed(e.message ?: "Could not reach LibreLinkUp.", unauthorized = false)
        } catch (e: IOException) {
            return SyncOutcome.Failed(NightscoutService.friendlyError(e), unauthorized = false)
        }

        logs.upsertRemoteReadings(entries, GlucoseSourceTag.LIBRE)
        logs.trimHistory(nowMillis)
        profiles.saveSettings(settings.copy(lastSyncMillis = nowMillis, libreRegion = servedRegion))

        return SyncOutcome.Success(
            readings = entries.size,
            treatments = 0,
            therapyUpdated = false,
            skipped = listOf("events and insulin doses (LibreLinkUp provides readings only)"),
        )
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
            // One request serves both the current profile and the edit history: Nightscout
            // keeps one document per edit, newest first, and the newest *is* the profile.
            // Asking for them separately fetched the same response twice.
            val documents = service.fetchProfileDocuments(url, token)
            detector?.value?.record(profile = documents.firstOrNull(), history = documents, nowMillis = nowMillis)
            val downloaded = documents.firstNotNullOfOrNull { it.toTherapyProfile() }
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
