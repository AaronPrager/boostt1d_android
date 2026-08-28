package com.boostt1d.android.logs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.charts.GlucoseChart
import com.boostt1d.android.charts.glucoseColor
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseReadingEntity
import com.boostt1d.android.data.GlucoseStatistics
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.LogState
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

/** How much history a log or chart is showing. */
enum class LogWindow(val label: String, val days: Int) {
    DAY("24h", 1),
    WEEK("7d", 7),
    FORTNIGHT("14d", 14);

    val millis: Long get() = days * 24L * 60 * 60 * 1000
}

@Composable
fun GlucoseLogScreen(
    logs: LogState,
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    nowMillis: Long,
    onAddReading: (Int, Long) -> Unit,
    onDeleteReading: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var window by remember { mutableStateOf(LogWindow.DAY) }
    var showingAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GlucoseReadingEntity?>(null) }

    val since = nowMillis - window.millis
    val visible = logs.readings.filter { it.epochMilliseconds >= since }
    val stats = GlucoseStatistics.calculate(visible.map { it.sgv.toDouble() }, lowMgdl, highMgdl)

    ScreenScaffold(
        title = "BG Log",
        subtitle = "Readings and charts",
        modifier = modifier,
        onAdd = { showingAdd = true },
        addLabel = "Add a reading",
    ) {
        item {
            BoostSegmented(
                options = LogWindow.entries.toList(),
                selected = window,
                optionLabel = { it.label },
                onSelect = { window = it },
            )
        }

        item {
            BoostCard {
                Text(
                    "GLUCOSE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
                GlucoseChart(
                    entries = visible.map { it.toEntry() },
                    lowMgdl = lowMgdl,
                    highMgdl = highMgdl,
                    unit = unit,
                    windowStartMillis = since,
                    windowEndMillis = nowMillis,
                    // Manual entries are hours apart; joining them would assert a curve
                    // nobody measured. A dense CGM series will earn the line in phase 2.
                    connectPoints = false,
                )
            }
        }

        if (visible.isNotEmpty()) {
            item { StatisticsCard(stats, unit) }
        }

        item {
            Text(
                if (visible.isEmpty()) "No readings yet" else "${visible.size} readings",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = BoostSpacing.xs),
            )
        }

        if (visible.isEmpty()) {
            item {
                BoostCard {
                    Text(
                        "Nothing logged in this window. Tap Add a reading to record one — " +
                            "your meter number, whenever you took it.",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        items(visible, key = { it.epochMilliseconds }) { reading ->
            ReadingRow(
                reading = reading,
                unit = unit,
                lowMgdl = lowMgdl,
                highMgdl = highMgdl,
                nowMillis = nowMillis,
                onDelete = { pendingDelete = reading },
            )
        }
    }

    if (showingAdd) {
        AddReadingDialog(
            unit = unit,
            nowMillis = nowMillis,
            onDismiss = { showingAdd = false },
            onSave = onAddReading,
        )
    }

    pendingDelete?.let { reading ->
        ConfirmDeleteDialog(
            what = "${GlucoseDisplay.format(reading.sgv.toDouble(), unit)} ${unit.displayName} " +
                "at ${Fmt.time(reading.epochMilliseconds)}",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDeleteReading(reading.epochMilliseconds)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun StatisticsCard(stats: GlucoseStatistics, unit: BGUnit) {
    val colors = BoostTheme.colors
    BoostCard {
        Text(
            "IN THIS WINDOW",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xxs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("Average", Fmt.glucose(stats.averageGlucose, unit), unit.displayName)
            Metric("In range", Fmt.percent(stats.timeInRange), null, colors.inRange)
            Metric("GMI", Fmt.oneDecimal(stats.gmi), "%")
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("Below", Fmt.percent(stats.timeBelowRange), null, colors.low)
            Metric("Above", Fmt.percent(stats.timeAboveRange), null, colors.high)
            Metric("Variability", Fmt.percent(stats.coefficientOfVariation), "CV")
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    suffix: String?,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: colors.textPrimary,
            )
            suffix?.let { Text(it, fontSize = 11.sp, color = colors.textTertiary) }
        }
    }
}

@Composable
private fun ReadingRow(
    reading: GlucoseReadingEntity,
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    nowMillis: Long,
    onDelete: () -> Unit,
) {
    val colors = BoostTheme.colors
    val value = reading.sgv.toDouble()
    val tint = glucoseColor(value, lowMgdl, highMgdl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.md))
            .padding(horizontal = BoostSpacing.sm, vertical = BoostSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        Box(modifier = Modifier.size(10.dp).background(tint, CircleShape))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${GlucoseDisplay.format(value, unit)} ${unit.displayName}",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                buildString {
                    append(Fmt.dayTime(reading.epochMilliseconds))
                    if (reading.source != LogRepository.SOURCE_MANUAL) {
                        append(" · ")
                        append(reading.source ?: "unknown")
                    }
                },
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }

        Text(
            Fmt.ago(reading.epochMilliseconds, nowMillis),
            fontSize = 12.sp,
            color = colors.textTertiary,
        )

        Icon(
            Icons.Filled.DeleteOutline,
            contentDescription = "Delete reading",
            tint = colors.textTertiary,
            modifier = Modifier.size(20.dp).clickable(onClick = onDelete),
        )
    }
}
