package com.boostt1d.android.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.engine.AgpProfile
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Ambulatory Glucose Profile: median line with a 25th–75th percentile band on a 24-hour axis.
 * Ported from the iOS MultiDayOverlayChartView; the maths lives in [AgpProfile].
 */
@Composable
fun AgpChart(
    entries: List<NightscoutGlucoseEntry>,
    dayCount: Int,
    lowMgdl: Double,
    highMgdl: Double,
    unit: BGUnit,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 300.dp,
    minimumDistinctDays: Int = 2,
    isBackfilling: Boolean = false,
    showsBackfillPlaceholder: Boolean = true,
    /** When set, skips the insufficient-data placeholders if the cache already spans enough days. */
    localDaysAvailable: Double? = null,
) {
    val colors = BoostTheme.colors
    val measurer = rememberTextMeasurer()
    val zone = TimeZone.getDefault()
    val cappedDays = dayCount.coerceIn(1, GlucoseCacheRules.RETENTION_DAYS)

    val points = remember(entries, cappedDays, nowMillis / 3_600_000L) {
        AgpProfile.points(entries, zone, AgpProfile.dayStarts(cappedDays, nowMillis, zone).toSet())
    }
    val distinctDays = remember(entries) { AgpProfile.distinctCalendarDays(entries, zone) }
    val sufficient = points.isNotEmpty() && ((localDaysAvailable ?: 0.0) >= cappedDays || distinctDays >= minimumDistinctDays)

    if (!sufficient) {
        if (showsBackfillPlaceholder) Placeholder(distinctDays, minimumDistinctDays, isBackfilling, chartHeight)
        else Box(modifier = modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
            Text("No glucose readings found for the selected time range.", color = colors.textTertiary, fontSize = 14.sp)
        }
        return
    }

    val yMin = min(50.0, lowMgdl - 20)
    val yMax = max(300.0, highMgdl + 50)
    val band = colors.primary
    val gridColor = colors.border
    val inRange = colors.inRange
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.textTertiary)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            val leftGutter = 42.dp.toPx()
            val bottomGutter = 18.dp.toPx()
            val plot = Rect(Offset(leftGutter, 6.dp.toPx()), Size(size.width - leftGutter, size.height - bottomGutter - 6.dp.toPx()))
            fun yFor(mgdl: Double): Float = (plot.bottom - ((mgdl - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0) * plot.height).toFloat()
            fun xFor(minute: Int): Float = (plot.left + minute / (24.0 * 60.0) * plot.width).toFloat()

            // Target band, then dashed threshold lines.
            drawRect(inRange.copy(alpha = 0.10f), Offset(plot.left, yFor(highMgdl)), Size(plot.width, yFor(lowMgdl) - yFor(highMgdl)))
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            for (threshold in listOf(lowMgdl, highMgdl)) {
                drawLine(inRange.copy(alpha = 0.45f), Offset(plot.left, yFor(threshold)), Offset(plot.right, yFor(threshold)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }

            // Gridlines every six hours with their labels, and a few glucose ticks.
            for (hour in listOf(0, 6, 12, 18, 24)) {
                val x = xFor(hour * 60)
                drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 0.5f.dp.toPx())
                val label = when (hour) { 0, 24 -> "12a"; 12 -> "12p"; in 1..11 -> "${hour}a"; else -> "${hour - 12}p" }
                val measured = measurer.measure(label, labelStyle)
                val lx = (x - measured.size.width / 2f).coerceIn(plot.left, plot.right - measured.size.width)
                drawText(measured, topLeft = Offset(lx, plot.bottom + 4.dp.toPx()))
            }
            for (tick in listOf(lowMgdl, highMgdl, 250.0).filter { it in yMin..yMax }) {
                val measured = measurer.measure(GlucoseDisplay.format(tick, unit), labelStyle)
                drawText(measured, topLeft = Offset(leftGutter - measured.size.width - 6.dp.toPx(), yFor(tick) - measured.size.height / 2f))
            }

            if (points.isNotEmpty()) {
                // 25th–75th percentile ribbon: forward along p75, back along p25.
                val ribbon = Path().apply {
                    moveTo(xFor(points.first().minuteOfDay), yFor(points.first().p75))
                    points.drop(1).forEach { lineTo(xFor(it.minuteOfDay), yFor(it.p75)) }
                    points.reversed().forEach { lineTo(xFor(it.minuteOfDay), yFor(it.p25)) }
                    close()
                }
                drawPath(ribbon, band.copy(alpha = 0.2f))

                val median = Path().apply {
                    moveTo(xFor(points.first().minuteOfDay), yFor(points.first().median))
                    points.drop(1).forEach { lineTo(xFor(it.minuteOfDay), yFor(it.median)) }
                }
                drawPath(median, band, style = Stroke(width = 2.5.dp.toPx()))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(18.dp, 10.dp).background(band.copy(alpha = 0.2f), RoundedCornerShape(2.dp)))
                Text("75% of readings", fontSize = 11.sp, color = colors.textSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.width(18.dp).height(2.5.dp).background(band))
                Text("Median", fontSize = 11.sp, color = colors.textSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(10.dp).background(inRange.copy(alpha = 0.35f), CircleShape))
                Text("Target range", fontSize = 11.sp, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun Placeholder(distinctDays: Int, minimumDistinctDays: Int, isBackfilling: Boolean, chartHeight: Dp) {
    val colors = BoostTheme.colors
    val title = when { isBackfilling -> "Building Glucose Profile"; distinctDays == 0 -> "Typical Day Profile"; else -> "Collecting More History" }
    val message = when {
        isBackfilling -> "Your typical-day glucose profile will appear here as readings accumulate across multiple days."
        distinctDays == 0 -> "Connect Dexcom Share or Nightscout to start building your local glucose profile."
        else -> "At least $minimumDistinctDays days of readings are needed to show a typical-day profile with variance bands."
    }
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.fillMaxWidth().height(chartHeight).background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.lg)).padding(BoostSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(if (isBackfilling) Icons.Filled.CloudDownload else Icons.Filled.ShowChart, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
            Text(title, fontSize = 17.sp, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text(message, fontSize = 14.sp, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
        if (distinctDays == 1) Text("1 day collected so far", fontSize = 11.sp, color = colors.textTertiary)
    }
}
