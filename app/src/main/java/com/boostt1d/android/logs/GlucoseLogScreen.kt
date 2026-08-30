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
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.GlucoseStatistics
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.LogState
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

/** How much history a log is showing, in whole days as on iOS. */
enum class LogWindow(val days: Int) {
    ONE_DAY(1),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
    ONE_MONTH(30);

    val label: String get() = if (days == 30) "1 month" else "$days day" + if (days == 1) "" else "s"

    val millis: Long get() = days * 24L * 60 * 60 * 1000

    companion object {
        /** Readings are capped by the retention window, so a month of them cannot exist. */
        val forReadings = listOf(ONE_DAY, THREE_DAYS, SEVEN_DAYS)

        /** Events are few enough to keep a month of. */
        val forEvents = entries.toList()
    }
}

/** The BG Log shows either the chart or the readings behind it, not both at once. */
enum class GlucoseLogTab(val label: String) {
    CHART("Chart"),
    DATA("Raw Data"),
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
    var window by remember { mutableStateOf(LogWindow.ONE_DAY) }
    var tab by remember { mutableStateOf(GlucoseLogTab.CHART) }
    var showingAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GlucoseReadingEntity?>(null) }
    var pendingEdit by remember { mutableStateOf<GlucoseReadingEntity?>(null) }

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
                options = LogWindow.forReadings,
                selected = window,
                optionLabel = { it.label },
                onSelect = { window = it },
            )
        }

        if (visible.isNotEmpty()) {
            item { StatisticsCard(stats, unit) }
        }

        item {
            // Chart or the readings behind it, not both: stacked, the list pushed the
            // chart off the top the moment there was a day's worth of data.
            BoostSegmented(
                options = GlucoseLogTab.entries.toList(),
                selected = tab,
                optionLabel = { it.label },
                onSelect = { tab = it },
            )
        }

        if (tab == GlucoseLogTab.CHART) {
            item {
                BoostCard {
                    GlucoseChart(
                        entries = visible.map { it.toEntry() },
                        lowMgdl = lowMgdl,
                        highMgdl = highMgdl,
                        unit = unit,
                        windowStartMillis = since,
                        windowEndMillis = nowMillis,
                    )
                }
            }
            return@ScreenScaffold
        }

        if (visible.isEmpty()) {
            item {
                EmptyNote(
                    "No glucose data",
                    "No readings found for the selected time range. Tap Add a reading to " +
                        "record one — your meter number, whenever you took it.",
                )
            }
        }

        groupByDay(visible) { it.epochMilliseconds }.forEach { (day, rows) ->
            item(key = "day-$day") {
                DayHeader(
                    dayStartMillis = day,
                    nowMillis = nowMillis,
                    trailing = "${rows.size} ${if (rows.size == 1) "reading" else "readings"}",
                )
            }
            items(rows, key = { it.epochMilliseconds }) { reading ->
                ReadingRow(
                    reading = reading,
                    unit = unit,
                    lowMgdl = lowMgdl,
                    highMgdl = highMgdl,
                    nowMillis = nowMillis,
                    onEdit = { pendingEdit = reading },
                    onDelete = { pendingDelete = reading },
                )
            }
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

    pendingEdit?.let { reading ->
        AddReadingDialog(
            unit = unit,
            nowMillis = nowMillis,
            existing = reading,
            onDismiss = { pendingEdit = null },
            onSave = { sgv, at ->
                // A changed time changes the primary key, so the old row is removed
                // rather than left behind as a duplicate.
                if (at != reading.epochMilliseconds) onDeleteReading(reading.epochMilliseconds)
                onAddReading(sgv, at)
            },
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
            Metric("GMI", Fmt.oneDecimal(stats.gmi), "%")
            // Above 36% is the conventional variability flag, so it is coloured once it
            // crosses rather than left for the reader to remember the threshold.
            Metric(
                "Variability",
                Fmt.percent(stats.coefficientOfVariation),
                "CV",
                if (stats.coefficientOfVariation > 36) colors.high else null,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("Below", Fmt.percent(stats.timeBelowRange), null, colors.low)
            Metric("In range", Fmt.percent(stats.timeInRange), null, colors.inRange)
            Metric("Above", Fmt.percent(stats.timeAboveRange), null, colors.high)
        }

        // Very high is a subset of above, so it sits under that row rather than beside it
        // as a fourth slice that would not add up to a hundred.
        if (stats.timeVeryHigh > 0) {
            Text(
                "Of which ${Fmt.percent(stats.timeVeryHigh)} was very high " +
                    "(${GlucoseDisplay.format(GlucoseCacheRules.VERY_HIGH_MGDL, unit)} " +
                    "${unit.displayName} or above).",
                fontSize = 12.sp,
                color = colors.veryHigh,
                modifier = Modifier.padding(top = BoostSpacing.xs),
            )
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BoostTheme.colors
    val value = reading.sgv.toDouble()
    val tint = glucoseColor(value, lowMgdl, highMgdl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.md))
            .clickable(enabled = reading.source == LogRepository.SOURCE_MANUAL, onClick = onEdit)
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
                    append(Fmt.time(reading.epochMilliseconds))
                    // Where a reading came from only needs saying when it was not typed
                    // here — a log full of "manual" tells the user nothing.
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
