package com.boostt1d.android.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.charts.GlucoseChart
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.data.TrendRange
import com.boostt1d.android.data.UserProfile
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

/**
 * Home is status only: what your glucose is, what is still acting on it, and the trend
 * those two produced.
 *
 * Ported from the iOS DashboardView. Logging and the calculator are deliberately not here
 * — they live under Logs and Menu, and putting shortcuts to them on the home screen made
 * this read as a launcher rather than an answer to "where am I".
 */
@Composable
fun DashboardScreen(
    profile: UserProfile,
    settings: GlucoseSettings,
    logs: LogState,
    onBoard: OnBoard,
    nowMillis: Long,
    syncing: Boolean,
    onRefresh: () -> Unit,
    onAddReading: () -> Unit,
    onOpenHistory: () -> Unit,
    /** Opens the Data Source screen from the glucose-only notice. */
    onOpenDataSource: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var trendRange by remember { mutableStateOf(TrendRange.TWO_HOURS) }

    // Newest two readings: the current value and the one it moved from.
    val newest = logs.readings.maxByOrNull { it.epochMilliseconds }
    val previous = remember(logs.readings, newest) {
        logs.readings
            .filter { newest == null || it.epochMilliseconds < newest.epochMilliseconds }
            .maxByOrNull { it.epochMilliseconds }
    }

    // No screen title: the greeting is this screen's heading, and iOS carries the logo
    // in the toolbar rather than a second line of text.
    ScreenScaffold(title = null, subtitle = null, modifier = modifier) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BoostSpacing.md, bottom = BoostSpacing.xxs),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "Hello, ${profile.name.trim().substringBefore(' ').ifBlank { "there" }}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (settings.connection != GlucoseConnectionOption.MANUAL && settings.lastSyncMillis > 0) {
                    Text(
                        Fmt.ago(settings.lastSyncMillis, nowMillis),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        item {
            GlucoseCard(
                glucoseMgdl = newest?.sgv,
                trendDirection = newest?.direction,
                source = settings.connection,
                unit = profile.bgUnit,
                lowMgdl = settings.lowGlucose,
                highMgdl = settings.highGlucose,
                previousMgdl = previous?.sgv,
                measurementAtMillis = newest?.epochMilliseconds,
                nowMillis = nowMillis,
                isRefreshing = syncing,
                // Refresh means "sync" with a source and "add a reading" without one.
                showsAddReading = settings.isManualMode,
                onRefresh = if (settings.isManualMode) onAddReading else onRefresh,
                onAddReading = onAddReading,
            )
        }

        // Dexcom Share and LibreLinkUp send readings and nothing else, so the empty insulin
        // and carb tiles below need a reason next to them rather than an apology about
        // Nightscout not having published anything.
        vendorFor(settings.connection)?.let { vendor ->
            item {
                VendorCgmNotice(
                    context = VendorCgmNoticeContext.DASHBOARD,
                    vendor = vendor,
                    onConfigureNightscout = onOpenDataSource,
                )
            }
        }

        item {
            // One card for everything still acting on the reading above, and the trend it
            // produced. Nothing inside carries its own card chrome — a card within a card
            // made these read as unrelated panels.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
                    .padding(BoostSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
            ) {
                StillOnBoard(onBoard, settings.connection)
                BoostDivider()
                TrendSection(
                    logs = logs,
                    profile = profile,
                    settings = settings,
                    range = trendRange,
                    onRangeChange = { trendRange = it },
                    nowMillis = nowMillis,
                    onOpenHistory = onOpenHistory,
                )
            }
        }

        if (settings.connection == GlucoseConnectionOption.NIGHTSCOUT && settings.lastSyncMillis == 0L) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.high.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                        .clickable(enabled = !syncing, onClick = onRefresh)
                        .padding(horizontal = BoostSpacing.sm, vertical = BoostSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
                ) {
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = colors.high,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (syncing) "Syncing with Nightscout…" else "Not synced yet — tap to sync",
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Active insulin and carbs.
 *
 * Read-only figures, so they sit beside the trend they explain rather than in the reading
 * card, where they invited taps that went nowhere. Both come from the uploading loop via
 * Nightscout — BoostT1D does not estimate them, because a guessed number here would look
 * computed and change what someone does next.
 */
@Composable
private fun StillOnBoard(onBoard: OnBoard, connection: GlucoseConnectionOption) {
    val colors = BoostTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Text(
            "STILL ON BOARD",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = colors.textSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.md)) {
            OnBoardStat(
                icon = Icons.Filled.WaterDrop,
                title = "Active Insulin",
                value = onBoard.insulinUnits?.let { Fmt.oneDecimal(it) } ?: "--",
                unit = "U",
                tint = colors.insulin,
            )
            OnBoardStat(
                icon = Icons.Filled.LocalDining,
                title = "Active Carbs",
                value = onBoard.carbsGrams?.let { Fmt.carbs(it) } ?: "--",
                unit = "g",
                tint = colors.carbs,
            )
        }

        if (onBoard.isEmpty) {
            Text(
                when (connection) {
                    GlucoseConnectionOption.MANUAL ->
                        "Your pump or loop reports these. Nothing is connected, so there is " +
                            "nothing to read them from."
                    // Dexcom and Libre carry no treatment data at all, so there is nothing
                    // to wait for and saying otherwise would send someone hunting a fault.
                    GlucoseConnectionOption.DEXCOM, GlucoseConnectionOption.LIBRE ->
                        "${connection.displayName} sends readings only. These come from " +
                            "Nightscout or from what you log yourself."
                    else -> if (onBoard.connectionStale) {
                        "Your last reading is over 15 minutes old, so these stay hidden until " +
                            "readings resume."
                    } else {
                        "Your loop reports these to Nightscout. Nothing has been published yet."
                    }
                },
                fontSize = 11.sp,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun OnBoardStat(
    icon: ImageVector,
    title: String,
    value: String,
    unit: String,
    tint: Color,
) {
    val colors = BoostTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier.size(24.dp).background(tint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        }
        Column {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(unit, fontSize = 10.sp, color = colors.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/**
 * The trend, over a window the user picks.
 *
 * Two hours by default: on the home screen the question is what glucose has been doing
 * since the last meal or dose, which a full day flattens out.
 */
@Composable
private fun TrendSection(
    logs: LogState,
    profile: UserProfile,
    settings: GlucoseSettings,
    range: TrendRange,
    onRangeChange: (TrendRange) -> Unit,
    nowMillis: Long,
    onOpenHistory: () -> Unit,
) {
    val colors = BoostTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        // The pills replace the usual eyebrow label: the selected one already says which
        // window is on screen, and a title beside them crowded the row.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.md))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TrendRange.entries.forEach { option ->
                    val selected = option == range
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) colors.surface else Color.Transparent,
                                RoundedCornerShape(BoostRadius.sm),
                            )
                            .clickable { onRangeChange(option) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            option.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.textPrimary else colors.textSecondary,
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.clickable(onClick = onOpenHistory),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("History", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        GlucoseChart(
            entries = logs.entries,
            lowMgdl = settings.lowGlucose,
            highMgdl = settings.highGlucose,
            unit = profile.bgUnit,
            windowStartMillis = nowMillis - range.millis,
            windowEndMillis = nowMillis,
        )
    }
}
