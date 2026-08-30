package com.boostt1d.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
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
    syncing: Boolean,
    lastOutcome: SyncOutcome?,
    nowMillis: Long,
    onTest: suspend (String, String) -> NightscoutConnectionReport,
    onSave: (GlucoseSettings, String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val scope = rememberCoroutineScope()

    var connection by remember { mutableStateOf(settings.connection) }
    var url by remember { mutableStateOf(settings.nightscoutUrl) }
    var token by remember { mutableStateOf(currentToken) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf<Boolean?>(null) }

    val dirty = connection != settings.connection ||
        url.trim() != settings.nightscoutUrl ||
        token.trim() != currentToken

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
                    onConnectionChange = { connection = it; testResult = null },
                    onUrlChange = { url = it; testResult = null },
                    onTokenChange = { token = it; testResult = null },
                    onTest = {
                        scope.launch {
                            testing = true
                            testResult = null
                            val report = runCatching { onTest(url.trim(), token.trim()) }.getOrNull()
                            testSucceeded = report?.glucoseAvailable
                            testResult = report?.message(hasToken = token.isNotBlank())
                                ?: "Could not reach the site. Check the address and your connection."
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
                                // A source change invalidates "last synced": the old
                                // timestamp described a different source.
                                lastSyncMillis = if (connection == settings.connection) {
                                    settings.lastSyncMillis
                                } else {
                                    0L
                                },
                            ),
                            token.trim(),
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
