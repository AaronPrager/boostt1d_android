package com.boostt1d.android.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.charts.GlucoseChart
import com.boostt1d.android.charts.glucoseColor
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.PhotoScaling
import com.boostt1d.android.data.TodaySoFar
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.data.UserProfile
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import com.boostt1d.android.ui.TrendArrow

/**
 * Where you are now.
 *
 * Every tile is derived from what has actually been logged, and a tile with nothing behind
 * it says so rather than showing a zero — "0% in range" and "no readings yet" mean very
 * different things, and only one of them is true on a quiet day.
 */
@Composable
fun DashboardScreen(
    profile: UserProfile,
    settings: GlucoseSettings,
    logs: LogState,
    nowMillis: Long,
    onOpenProfile: () -> Unit,
    onAddReading: () -> Unit,
    onAddEvent: () -> Unit,
    onOpenBolusCalculator: () -> Unit,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val unit = profile.bgUnit
    val low = settings.lowGlucose
    val high = settings.highGlucose

    val dayStart = TodaySoFarBuilder.startOfDay(nowMillis)
    val today = remember(logs.readings, low, high, nowMillis) {
        TodaySoFarBuilder.build(logs.entries, low, high, nowMillis = nowMillis)
    }
    val latest = logs.latest

    ScreenScaffold(title = "Dashboard", subtitle = null, modifier = modifier) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = BoostSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
            ) {
                Avatar(profile, onOpenProfile)
                Text(
                    profile.name.ifBlank { "Welcome" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            CurrentGlucoseCard(
                latestSgv = latest?.sgv,
                latestAtMillis = latest?.epochMilliseconds,
                direction = latest?.direction,
                unit = unit,
                low = low,
                high = high,
                nowMillis = nowMillis,
            )
        }

        if (settings.connection == GlucoseConnectionOption.NIGHTSCOUT) {
            item {
                SyncStatusRow(
                    lastSyncMillis = settings.lastSyncMillis,
                    syncing = syncing,
                    nowMillis = nowMillis,
                    onSyncNow = onSyncNow,
                )
            }
        }

        item {
            QuickActions(
                onAddReading = onAddReading,
                onAddEvent = onAddEvent,
                onOpenBolusCalculator = onOpenBolusCalculator,
            )
        }

        item { TodayCard(today, logs, dayStart, unit, low, high, nowMillis) }

        if (logs.readings.any { it.epochMilliseconds >= nowMillis - 24 * 60 * 60 * 1000 }) {
            item {
                BoostCard {
                    Text(
                        "LAST 24 HOURS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                    )
                    GlucoseChart(
                        entries = logs.entries,
                        lowMgdl = low,
                        highMgdl = high,
                        unit = unit,
                        windowStartMillis = nowMillis - 24 * 60 * 60 * 1000,
                        windowEndMillis = nowMillis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentGlucoseCard(
    latestSgv: Int?,
    latestAtMillis: Long?,
    direction: String?,
    unit: BGUnit,
    low: Double,
    high: Double,
    nowMillis: Long,
) {
    val colors = BoostTheme.colors

    BoostCard {
        Text(
            "GLUCOSE NOW",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )

        if (latestSgv == null || latestAtMillis == null) {
            Text(
                "No readings yet",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textTertiary,
            )
            Text(
                "You're set up for manual entry, so nothing arrives on its own. Add a reading " +
                    "and this fills in.",
                fontSize = 14.sp,
                color = colors.textSecondary,
            )
            return@BoostCard
        }

        val value = latestSgv.toDouble()
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                GlucoseDisplay.format(value, unit),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = glucoseColor(value, low, high),
            )
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(unit.displayName, fontSize = 13.sp, color = colors.textSecondary)
                TrendArrow.symbol(direction)?.let {
                    Text(it, fontSize = 18.sp, color = colors.textPrimary)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.ago(latestAtMillis, nowMillis), fontSize = 13.sp, color = colors.textSecondary)
            TrendArrow.label(direction)?.let {
                Text("· $it", fontSize = 13.sp, color = colors.textSecondary)
            }
        }

        // A manual reading is a point in time, not a live feed. Saying so keeps the number
        // from being read as "current" hours after it was taken.
        val ageMinutes = (nowMillis - latestAtMillis) / 60_000
        if (ageMinutes > 60) {
            Text(
                "This is the last reading you logged, not a live value.",
                fontSize = 12.sp,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun TodayCard(
    today: TodaySoFar,
    logs: LogState,
    dayStartMillis: Long,
    unit: BGUnit,
    low: Double,
    high: Double,
    nowMillis: Long,
) {
    val colors = BoostTheme.colors
    val insulin = logs.insulinSince(dayStartMillis)
    val carbs = logs.carbsSince(dayStartMillis)

    BoostCard {
        Text(
            "TODAY SO FAR",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xxs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat("Insulin", "${Fmt.units(insulin)} U", colors.insulin)
            Stat("Carbs", "${Fmt.carbs(carbs)} g", colors.carbs)
            Stat(
                "Readings",
                "${today.readingCount}",
                colors.textPrimary,
            )
        }

        if (!today.hasEnoughData) {
            Text(
                if (today.readingCount == 0) {
                    "No readings today yet."
                } else {
                    // Below the threshold the percentages would be noise dressed as a summary.
                    "${today.readingCount} readings today — not enough to summarise the day yet."
                },
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = BoostSpacing.xs),
            )
            return@BoostCard
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat("Average", today.averageGlucose?.let { GlucoseDisplay.format(it, unit) } ?: "—", colors.textPrimary)
            Stat("In range", Fmt.percent(today.inRange), colors.inRange)
            Stat("Low events", "${today.lowEpisodes}", if (today.lowEpisodes > 0) colors.low else colors.textPrimary)
        }

        if (today.hasBaseline) {
            val delta = today.averageDelta
            val inRangeDelta = today.inRangeDelta
            Text(
                buildString {
                    append("Against the same hours on the last ${today.baselineDays} days: ")
                    delta?.let { append("average ${Fmt.signed(it, unit)} ${unit.displayName}") }
                    if (delta != null && inRangeDelta != null) append(", ")
                    inRangeDelta?.let {
                        val sign = if (it >= 0) "+" else "−"
                        append("in range $sign${Fmt.percent(kotlin.math.abs(it))}")
                    }
                    append(".")
                },
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = BoostSpacing.xs),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, valueColor: Color) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = colors.textSecondary)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun QuickActions(
    onAddReading: () -> Unit,
    onAddEvent: () -> Unit,
    onOpenBolusCalculator: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        QuickAction("Reading", Icons.Filled.Add, Modifier.weight(1f), onAddReading)
        QuickAction("Event", Icons.Filled.Vaccines, Modifier.weight(1f), onAddEvent)
        QuickAction("Bolus", Icons.Filled.Calculate, Modifier.weight(1f), onOpenBolusCalculator)
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = BoostTheme.colors
    Column(
        modifier = modifier
            .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
            .clickable(onClick = onClick)
            .padding(vertical = BoostSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    }
}

@Composable
private fun Avatar(profile: UserProfile, onClick: () -> Unit) {
    val colors = BoostTheme.colors
    val bitmap = remember(profile.photoData) { PhotoScaling.decodeAvatar(profile.photoData) }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.surfaceMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Profile",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                profile.name.trim().take(1).uppercase().ifEmpty { "?" },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * Whether the numbers above are current.
 *
 * A CGM's history window is a hard loss boundary — Dexcom keeps about a day, Libre about
 * half of one — so a sync that quietly stopped working costs readings that cannot be
 * recovered later. Saying "synced 3 hours ago" plainly is what gives someone the chance
 * to notice before the window closes.
 */
@Composable
private fun SyncStatusRow(
    lastSyncMillis: Long,
    syncing: Boolean,
    nowMillis: Long,
    onSyncNow: () -> Unit,
) {
    val colors = BoostTheme.colors
    val ageMinutes = if (lastSyncMillis == 0L) Long.MAX_VALUE else (nowMillis - lastSyncMillis) / 60_000
    val stale = ageMinutes > 60

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (stale) colors.high.copy(alpha = 0.10f) else colors.surface,
                RoundedCornerShape(BoostRadius.md),
            )
            .clickable(enabled = !syncing, onClick = onSyncNow)
            .padding(horizontal = BoostSpacing.sm, vertical = BoostSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Icon(
            Icons.Filled.CloudSync,
            contentDescription = null,
            tint = if (stale) colors.high else colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            when {
                syncing -> "Syncing with Nightscout…"
                lastSyncMillis == 0L -> "Not synced yet — tap to sync"
                stale -> "Last synced ${Fmt.ago(lastSyncMillis, nowMillis)} — tap to sync"
                else -> "Synced ${Fmt.ago(lastSyncMillis, nowMillis)}"
            },
            fontSize = 13.sp,
            color = if (stale) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}
