package com.boostt1d.android.sync

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.boostt1d.android.background.BatteryExemption
import com.boostt1d.android.background.SyncReminders
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.sync.DexcomRegion
import com.boostt1d.android.sync.LibreRegion
import com.boostt1d.android.sync.LibreVerification
import com.boostt1d.android.logs.EmptyNote
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import kotlinx.coroutines.launch

/**
 * Changing where readings come from, after setup.
 *
 * Saving is explicit rather than live: switching source mid-edit would start a sync
 * against a half-typed address, and the resulting failure would look like the site's
 * fault rather than the edit's.
 */
@Composable
fun DataSourceScreen(
    settings: GlucoseSettings,
    currentToken: String,
    currentDexcomPassword: String,
    currentLibrePassword: String,
    syncing: Boolean,
    lastOutcome: SyncOutcome?,
    nowMillis: Long,
    onTest: suspend (String, String) -> NightscoutConnectionReport,
    onTestDexcom: suspend (String, String, DexcomRegion) -> Result<Unit>,
    onTestLibre: suspend (String, String, LibreRegion) -> Result<LibreVerification>,
    onSave: (GlucoseSettings, String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val scope = rememberCoroutineScope()

    var connection by remember { mutableStateOf(settings.connection) }
    var url by remember { mutableStateOf(settings.nightscoutUrl) }
    var token by remember { mutableStateOf(currentToken) }
    var dexcomUsername by remember { mutableStateOf(settings.dexcomUsername) }
    var dexcomPassword by remember { mutableStateOf(currentDexcomPassword) }
    var dexcomRegion by remember { mutableStateOf(settings.dexcomRegion) }
    var libreUsername by remember { mutableStateOf(settings.libreUsername) }
    var librePassword by remember { mutableStateOf(currentLibrePassword) }
    var libreRegion by remember { mutableStateOf(settings.libreRegion) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf<Boolean?>(null) }

    val dirty = connection != settings.connection ||
        url.trim() != settings.nightscoutUrl ||
        token.trim() != currentToken ||
        dexcomUsername.trim() != settings.dexcomUsername ||
        dexcomPassword != currentDexcomPassword ||
        dexcomRegion != settings.dexcomRegion ||
        libreUsername.trim() != settings.libreUsername ||
        librePassword != currentLibrePassword ||
        libreRegion != settings.libreRegion

    ScreenScaffold(title = "Data Source", subtitle = "Where readings come from", modifier = modifier) {
        item {
            BoostCard {
                Text(
                    "STATUS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
                Text(
                    settings.connection.displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    when {
                        settings.connection == GlucoseConnectionOption.MANUAL ->
                            "Nothing is downloaded. Everything in the app is what you logged."
                        settings.lastSyncMillis == 0L ->
                            "Not synced yet."
                        else ->
                            "Last synced ${Fmt.ago(settings.lastSyncMillis, nowMillis)}."
                    },
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                )

                if (settings.connection == GlucoseConnectionOption.NIGHTSCOUT) {
                    Button(
                        onClick = onSyncNow,
                        enabled = !syncing,
                        shape = RoundedCornerShape(BoostRadius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = BoostSpacing.xs),
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(
                                color = colors.surface,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Text("Syncing…", modifier = Modifier.padding(start = 8.dp))
                        } else {
                            Text("Sync now")
                        }
                    }
                }

                lastOutcome?.let { SyncOutcomeLine(it) }
            }
        }

        item {
            BoostCard {
                ConnectionFields(
                    connection = connection,
                    nightscoutUrl = url,
                    nightscoutToken = token,
                    dexcomUsername = dexcomUsername,
                    dexcomPassword = dexcomPassword,
                    dexcomRegion = dexcomRegion,
                    onConnectionChange = { connection = it; testResult = null },
                    onUrlChange = { url = it; testResult = null },
                    onTokenChange = { token = it; testResult = null },
                    onDexcomUsernameChange = { dexcomUsername = it; testResult = null },
                    onDexcomPasswordChange = { dexcomPassword = it; testResult = null },
                    onDexcomRegionChange = { dexcomRegion = it; testResult = null },
                    libreUsername = libreUsername,
                    librePassword = librePassword,
                    libreRegion = libreRegion,
                    onLibreUsernameChange = { libreUsername = it; testResult = null },
                    onLibrePasswordChange = { librePassword = it; testResult = null },
                    onLibreRegionChange = { libreRegion = it; testResult = null },
                    onTest = {
                        scope.launch {
                            testing = true
                            testResult = null

                            if (connection == GlucoseConnectionOption.LIBRE) {
                                val result = onTestLibre(libreUsername.trim(), librePassword, libreRegion)
                                testSucceeded = result.isSuccess
                                testResult = result.fold(
                                    onSuccess = { v ->
                                        libreRegion = v.region
                                        "Signed in. Following ${'$'}{v.connectionName}" +
                                            (v.latestMgdl?.let { " — latest reading ${'$'}it mg/dL." }
                                                ?: ", but no recent reading yet.")
                                    },
                                    onFailure = { it.message ?: "Could not sign in to LibreLinkUp." },
                                )
                            } else if (connection == GlucoseConnectionOption.DEXCOM) {
                                val result = onTestDexcom(
                                    dexcomUsername.trim(), dexcomPassword, dexcomRegion,
                                )
                                testSucceeded = result.isSuccess
                                testResult = if (result.isSuccess) {
                                    "Signed in to Dexcom Share. Readings will arrive on the next sync."
                                } else {
                                    result.exceptionOrNull()?.message
                                        ?: "Could not sign in to Dexcom Share."
                                }
                            } else {
                                val report = runCatching { onTest(url.trim(), token.trim()) }.getOrNull()
                                testSucceeded = report?.glucoseAvailable
                                testResult = report?.message(hasToken = token.isNotBlank())
                                    ?: "Could not reach the site. Check the address and your connection."
                            }

                            testing = false
                        }
                    },
                    testing = testing,
                    testResult = testResult,
                    testSucceeded = testSucceeded,
                )

                Button(
                    onClick = {
                        onSave(
                            settings.copy(
                                connection = connection,
                                nightscoutUrl = if (connection == GlucoseConnectionOption.NIGHTSCOUT) {
                                    NightscoutUrl.normalize(url)
                                } else {
                                    ""
                                },
                                dexcomUsername = if (connection == GlucoseConnectionOption.DEXCOM) {
                                    dexcomUsername.trim()
                                } else {
                                    ""
                                },
                                dexcomRegion = dexcomRegion,
                                libreUsername = if (connection == GlucoseConnectionOption.LIBRE) {
                                    libreUsername.trim()
                                } else {
                                    ""
                                },
                                libreRegion = libreRegion,
                                // A source change invalidates "last synced": the old
                                // timestamp described a different source.
                                lastSyncMillis = if (connection == settings.connection) {
                                    settings.lastSyncMillis
                                } else {
                                    0L
                                },
                            ),
                            token.trim(),
                            dexcomPassword,
                            librePassword,
                        )
                    },
                    enabled = dirty,
                    shape = RoundedCornerShape(BoostRadius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        disabledContainerColor = colors.surfaceMuted,
                        disabledContentColor = colors.textTertiary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = BoostSpacing.xs),
                ) {
                    Text("Save and sync")
                }
            }
        }

        if (settings.connection != GlucoseConnectionOption.MANUAL) {
            item { BackgroundSyncCard() }
        }

        if (settings.connection == GlucoseConnectionOption.MANUAL) {
            item {
                EmptyNote(
                    "Switching to Nightscout keeps what you logged",
                    "Manual entries stay where they are. Where a downloaded reading and one " +
                        "of yours describe the same minute, the downloaded one wins — but a " +
                        "manual entry filling a gap the sensor never saw is kept.",
                )
            }
        }
    }
}

@Composable
private fun SyncOutcomeLine(outcome: SyncOutcome) {
    val colors = BoostTheme.colors
    val (text, tint) = when (outcome) {
        is SyncOutcome.Success -> {
            val parts = mutableListOf("${outcome.readings} readings", "${outcome.treatments} events")
            if (outcome.therapyUpdated) parts.add("insulin doses")
            val base = "Downloaded ${parts.joinToString(", ")}."
            // A capability the token could not reach is named rather than swallowed —
            // otherwise a half-working token looks like a fully working one.
            val note = if (outcome.skipped.isEmpty()) {
                ""
            } else {
                " Could not read ${outcome.skipped.joinToString(" or ")} — check the token's permissions."
            }
            (base + note) to if (outcome.skipped.isEmpty()) colors.inRange else colors.high
        }
        is SyncOutcome.Failed -> outcome.message to colors.low
        SyncOutcome.NotConfigured -> "No source configured." to colors.textSecondary
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Text(text, fontSize = 13.sp, color = tint)
    }
}

/**
 * Whether background sync can actually run.
 *
 * Both of these fail silently. Without notification permission the stale reminder is
 * simply never seen; under battery optimisation the periodic work is deferred for hours.
 * Neither shows an error anywhere, so the only way a person finds out is by losing
 * readings — which is exactly the thing the reminder exists to prevent.
 */
@Composable
private fun BackgroundSyncCard() {
    val colors = BoostTheme.colors
    val context = LocalContext.current

    var canNotify by remember { mutableStateOf(SyncReminders.canPost(context)) }
    var isExempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> canNotify = granted }

    // Re-checked on resume: the battery screen is the system's, so the app only learns the
    // answer when it comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canNotify = SyncReminders.canPost(context)
                isExempt = BatteryExemption.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoostCard {
        Text(
            "BACKGROUND SYNC",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
        Text(
            "BoostT1D syncs about every fifteen minutes while it is closed. Android may " +
                "delay that, so it also warns you before readings fall out of your CGM's " +
                "history — both of these have to be allowed for that warning to arrive.",
            fontSize = 13.sp,
            color = colors.textSecondary,
        )

        PermissionRow(
            label = "Notifications",
            granted = canNotify,
            actionLabel = "Allow",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )

        PermissionRow(
            label = "Run without battery restrictions",
            granted = isExempt,
            actionLabel = "Open settings",
            onAction = { BatteryExemption.openSettings(context) },
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (granted) colors.inRange else colors.high,
            modifier = Modifier.size(18.dp),
        )
        Text(label, fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (!granted) {
            Text(
                actionLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}
