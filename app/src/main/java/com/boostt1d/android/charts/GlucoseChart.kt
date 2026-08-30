package com.boostt1d.android.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

/**
 * Glucose over a window of time.
 *
 * Drawn on a Canvas rather than through a charting library: the target range band, the
 * per-point colouring and the sparse manual data are all specific enough that a general
 * chart would need fighting into shape. Points are drawn as dots, not joined into a line —
 * manual entries are hours apart, and a line between two of them would assert a curve
 * nobody measured.
 */
@Composable
fun GlucoseChart(
    entries: List<NightscoutGlucoseEntry>,
    lowMgdl: Double,
    highMgdl: Double,
    unit: BGUnit,
    windowStartMillis: Long,
    windowEndMillis: Long,
    modifier: Modifier = Modifier,
    /**
     * Null decides from the data: a series whose median gap is CGM-sized is joined, and
     * sparse manual points are left as dots. A line between two readings four hours apart
     * asserts a curve nobody measured.
     */
    connectPoints: Boolean? = null,
) {
    val colors = BoostTheme.colors
    val measurer = rememberTextMeasurer()

    val visible = remember(entries, windowStartMillis, windowEndMillis) {
        entries
            .filter { it.recordedAtMillis in windowStartMillis..windowEndMillis }
            .sortedBy { it.recordedAtMillis }
    }

    if (visible.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No readings in this window", color = colors.textTertiary, fontSize = 14.sp)
        }
        return
    }

    // The axis always contains the target band, so the band never sits off-screen when a
    // day's readings happen to be all high or all low.
    val axisBounds = remember(visible, lowMgdl, highMgdl) {
        val values = visible.map { it.sgv.toDouble() }
        (minOf(values.min(), lowMgdl) - 20) to (maxOf(values.max(), highMgdl) + 20)
    }
    val axisMin = axisBounds.first
    val axisMax = axisBounds.second

    // Both ends of a 24-hour window land on the same clock time, so the labels need the
    // day to say anything at all.
    val crossesDay = TodaySoFarBuilder.startOfDay(windowStartMillis) !=
        TodaySoFarBuilder.startOfDay(windowEndMillis)

    val joinPoints = remember(visible, connectPoints) { connectPoints ?: isDenseSeries(visible) }

    val gridColor = colors.border
    val bandColor = colors.inRange.copy(alpha = 0.10f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.textTertiary)

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val leftGutter = 42.dp.toPx()
        val bottomGutter = 18.dp.toPx()
        val plot = Rect(
            offset = Offset(leftGutter, 6.dp.toPx()),
            size = Size(size.width - leftGutter, size.height - bottomGutter - 6.dp.toPx()),
        )

        fun yFor(mgdl: Double): Float {
            val t = ((mgdl - axisMin) / (axisMax - axisMin)).coerceIn(0.0, 1.0)
            return (plot.bottom - t * plot.height).toFloat()
        }

        fun xFor(millis: Long): Float {
            val span = (windowEndMillis - windowStartMillis).coerceAtLeast(1)
            val t = ((millis - windowStartMillis).toDouble() / span).coerceIn(0.0, 1.0)
            return (plot.left + t * plot.width).toFloat()
        }

        // Target band first, so everything else sits on top of it.
        drawRect(
            color = bandColor,
            topLeft = Offset(plot.left, yFor(highMgdl)),
            size = Size(plot.width, yFor(lowMgdl) - yFor(highMgdl)),
        )

        listOf(lowMgdl, highMgdl).forEach { bound ->
            val y = yFor(bound)
            drawLine(colors.inRange.copy(alpha = 0.45f), Offset(plot.left, y), Offset(plot.right, y), 1f)
            drawAxisLabel(measurer, GlucoseDisplay.format(bound, unit), 4f, y - 6f, labelStyle)
        }

        drawLine(gridColor, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1f)

        if (joinPoints && visible.size > 1) {
            val path = Path()
            visible.forEachIndexed { index, entry ->
                val x = xFor(entry.recordedAtMillis)
                val y = yFor(entry.sgv.toDouble())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors.primary.copy(alpha = 0.5f), style = Stroke(width = 2f))
        }

        visible.forEach { entry ->
            val value = entry.sgv.toDouble()
            val dot = when {
                value < lowMgdl -> colors.low
                value > highMgdl -> colors.high
                else -> colors.inRange
            }
            drawCircle(dot, radius = 3.5.dp.toPx() / 2, center = Offset(xFor(entry.recordedAtMillis), yFor(value)))
        }

        // Time axis: the window's ends, which is all a chart this size can label honestly.
        drawAxisLabel(
            measurer,
            Fmt.axisLabel(windowStartMillis, crossesDay),
            plot.left,
            plot.bottom + 4f,
            labelStyle,
        )
        val endLabel = Fmt.axisLabel(windowEndMillis, crossesDay)
        val endWidth = measurer.measure(endLabel, labelStyle).size.width
        drawAxisLabel(measurer, endLabel, plot.right - endWidth, plot.bottom + 4f, labelStyle)
    }
}

private fun DrawScope.drawAxisLabel(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
) {
    drawText(measurer, text, topLeft = Offset(x, y), style = style)
}

/** A colour for a single reading, used by the logs as well as the chart. */
@Composable
fun glucoseColor(mgdl: Double, low: Double, high: Double): Color {
    val colors = BoostTheme.colors
    return when {
        mgdl < low -> colors.low
        mgdl > high -> colors.high
        else -> colors.inRange
    }
}

/**
 * Whether the readings are close enough together to draw a line through.
 *
 * The median gap is used rather than the mean so one long sensor outage does not make a
 * whole day of five-minute data look sparse.
 */
private fun isDenseSeries(entries: List<com.boostt1d.android.data.NightscoutGlucoseEntry>): Boolean {
    if (entries.size < 10) return false
    val gaps = entries.zipWithNext { a, b -> b.recordedAtMillis - a.recordedAtMillis }.sorted()
    val median = gaps[gaps.size / 2]
    return median <= 15 * 60 * 1000
}
