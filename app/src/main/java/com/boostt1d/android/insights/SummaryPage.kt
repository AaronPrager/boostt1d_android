package com.boostt1d.android.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.engine.WhatHappenedWeeklyReport
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import java.util.Locale
import kotlin.math.abs

/** Page 1: how the week went — the metrics, each with an explanation a tap away. */
internal fun LazyListScope.summaryPage(snapshot: WhatHappenedAnalysisCache.Snapshot, unit: BGUnit) {
    val report = snapshot.report

    item { SummaryHeader(report) }
    item { PlainLanguageCard(report.plainLanguageSummary) }
    item { TimeInRangeSection(report, unit) }
    item { AveragesSection(report, unit) }
    item { VariabilitySection(report, unit) }
    item { LowsSection(report, unit) }
    item {
        if (report.previous != null) ComparisonSection(report, unit)
        else Text(
            "Week-over-week comparison will appear after about 14 days of glucose history are available on this device.",
            fontSize = 12.sp, color = BoostTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun SummaryHeader(report: WhatHappenedWeeklyReport) {
    val colors = BoostTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.EventAvailable, contentDescription = null, tint = colors.report, modifier = Modifier.size(36.dp))
        Text("Last 7 days", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text("${monthDay(report.currentPeriodStartMillis)} – ${monthDay(report.currentPeriodEndMillis)}", fontSize = 12.sp, color = colors.textSecondary)
        // Everything on this page is built from complete days only, so the dates above are the
        // whole answer to "why doesn't this mention this morning?".
        Text("Complete days only — today goes into tomorrow’s report.", fontSize = 11.sp, color = colors.textTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlainLanguageCard(summary: String) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.report.copy(alpha = 0.08f), RoundedCornerShape(BoostRadius.lg)).padding(BoostSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Text("Summary", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(summary, fontSize = 15.sp, color = colors.textPrimary)
    }
}

@Composable
private fun TimeInRangeSection(report: WhatHappenedWeeklyReport, unit: BGUnit) {
    val colors = BoostTheme.colors
    val c = report.current
    val p = report.previous
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
        SectionHeading("Time in ranges")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RangeMetricCard("In range", percent(c.timeInRangePercent), "${formatBg(report.lowThresholdMgdL, unit)}–${formatBg(report.highThresholdMgdL, unit)}",
                colors.inRange, delta(c.timeInRangePercent, p?.timeInRangePercent), MetricInfo.TimeInRange(formatBg(report.lowThresholdMgdL, unit), formatBg(report.highThresholdMgdL, unit)), Modifier.weight(1f))
            RangeMetricCard("Low", percent(c.timeLowPercent), "Below ${formatBg(report.lowThresholdMgdL, unit)}",
                colors.low, delta(c.timeLowPercent, p?.timeLowPercent), MetricInfo.TimeLow(formatBg(report.lowThresholdMgdL, unit)), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RangeMetricCard("High", percent(c.timeHighPercent), "${formatBg(report.highThresholdMgdL, unit)}–${formatBg(report.veryHighThresholdMgdL, unit)}",
                colors.high, delta(c.timeHighPercent, p?.timeHighPercent), MetricInfo.TimeHigh(formatBg(report.highThresholdMgdL, unit), formatBg(report.veryHighThresholdMgdL, unit)), Modifier.weight(1f))
            RangeMetricCard("Very high", percent(c.timeVeryHighPercent), "Above ${formatBg(report.veryHighThresholdMgdL, unit)}",
                colors.veryHigh, delta(c.timeVeryHighPercent, p?.timeVeryHighPercent), MetricInfo.TimeVeryHigh(formatBg(report.veryHighThresholdMgdL, unit)), Modifier.weight(1f))
        }
    }
}

@Composable
private fun AveragesSection(report: WhatHappenedWeeklyReport, unit: BGUnit) {
    val c = report.current
    val p = report.previous
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
        SectionHeading("Average glucose & A1C")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Average", formatBg(c.averageGlucoseMgdL, unit), deltaText(c.averageGlucoseMgdL, p?.averageGlucoseMgdL) { formatBg(it, unit) }, MetricInfo.AverageGlucose, Modifier.weight(1f))
            StatCard("Est. A1C", String.format(Locale.US, "%.1f%%", c.estimatedA1C), "GMI ${String.format(Locale.US, "%.1f%%", c.gmi)}", MetricInfo.EstimatedA1CAndGMI, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VariabilitySection(report: WhatHappenedWeeklyReport, unit: BGUnit) {
    val c = report.current
    val p = report.previous
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
        SectionHeading("Glucose variability")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("CV", String.format(Locale.US, "%.0f%%", c.coefficientOfVariation), variabilityLabel(c.coefficientOfVariation), MetricInfo.CoefficientOfVariation, Modifier.weight(1f))
            StatCard("Std. Dev.", formatBg(c.standardDeviationMgdL, unit), deltaText(c.coefficientOfVariation, p?.coefficientOfVariation) { String.format(Locale.US, "%.0f%% CV", it) }, MetricInfo.StandardDeviation, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LowsSection(report: WhatHappenedWeeklyReport, unit: BGUnit) {
    val c = report.current
    val p = report.previous
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
        SectionHeading("Lows")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Episodes", "${c.lowEpisodeCount}", deltaCount(c.lowEpisodeCount, p?.lowEpisodeCount), MetricInfo.LowEpisodes(formatBg(report.lowThresholdMgdL, unit)), Modifier.weight(1f))
            StatCard("Total time", formatDuration(c.lowEpisodeTotalMinutes), if (p == null) "≥15 min each" else "Was ${formatDuration(p.lowEpisodeTotalMinutes)}", MetricInfo.LowEpisodeDuration, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComparisonSection(report: WhatHappenedWeeklyReport, unit: BGUnit) {
    val colors = BoostTheme.colors
    val c = report.current
    val p = report.previous ?: return
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
        SectionHeading("Compared with previous week")
        val start = report.previousPeriodStartMillis
        val end = report.previousPeriodEndMillis
        if (start != null && end != null) Text("${monthDay(start)} – ${monthDay(end)}", fontSize = 12.sp, color = colors.textSecondary)
        BoostCard {
            ComparisonRow("Time in range", percent(c.timeInRangePercent), percent(p.timeInRangePercent), c.timeInRangePercent >= p.timeInRangePercent)
            ComparisonRow("Average glucose", formatBg(c.averageGlucoseMgdL, unit), formatBg(p.averageGlucoseMgdL, unit), c.averageGlucoseMgdL <= p.averageGlucoseMgdL)
            ComparisonRow("Variability (CV)", String.format(Locale.US, "%.0f%%", c.coefficientOfVariation), String.format(Locale.US, "%.0f%%", p.coefficientOfVariation), c.coefficientOfVariation <= p.coefficientOfVariation)
            ComparisonRow("Low episodes", "${c.lowEpisodeCount}", "${p.lowEpisodeCount}", c.lowEpisodeCount <= p.lowEpisodeCount)
        }
    }
}

@Composable
private fun ComparisonRow(label: String, current: String, previous: String, better: Boolean) {
    val colors = BoostTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(previous, fontSize = 14.sp, color = colors.textSecondary)
        Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
        Text(current, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (better) colors.inRange else colors.high)
    }
}

@Composable
private fun RangeMetricCard(title: String, value: String, detail: String, color: Color, delta: String?, info: MetricInfo, modifier: Modifier) {
    val colors = BoostTheme.colors
    BoostCard(modifier = modifier) {
        MetricTitle(title, info)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(detail, fontSize = 11.sp, color = colors.textSecondary)
        delta?.let { Text(it, fontSize = 11.sp, color = colors.textSecondary) }
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String, info: MetricInfo, modifier: Modifier) {
    val colors = BoostTheme.colors
    BoostCard(modifier = modifier) {
        MetricTitle(title, info)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(subtitle, fontSize = 11.sp, color = colors.textSecondary)
    }
}

/** The metric's name with the info button that opens its explanation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricTitle(title: String, info: MetricInfo) {
    val colors = BoostTheme.colors
    var showing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 12.sp, color = colors.textSecondary)
        IconButton(onClick = { showing = true }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = "About $title", tint = colors.primary, modifier = Modifier.size(14.dp))
        }
    }
    if (showing) {
        ModalBottomSheet(onDismissRequest = { showing = false }, containerColor = colors.surface) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(info.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                ExplanationBlock("What it means", info.meaning)
                ExplanationBlock("What to strive for", info.striveFor)
                Text(
                    "These are general educational guides, not personal medical advice. Your care team sets targets that fit you.",
                    fontSize = 12.sp, color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ExplanationBlock(title: String, body: String) {
    val colors = BoostTheme.colors
    BoostCard {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(body, fontSize = 15.sp, color = colors.textPrimary)
    }
}

// MARK: - Formatting

private fun percent(value: Double) = String.format(Locale.US, "%.0f%%", value)

private fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "0 min"
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) (if (mins > 0) "${hours}h ${mins}m" else "${hours}h") else "$mins min"
}

private fun variabilityLabel(cv: Double) = when {
    cv < 36 -> "Generally stable"
    cv < 40 -> "Moderate variability"
    else -> "Higher variability"
}

private fun delta(current: Double, previous: Double?): String? {
    if (previous == null) return null
    val change = current - previous
    if (abs(change) < 0.5) return "Similar to last week"
    return String.format(Locale.US, "%s %.0f%% vs last week", if (change > 0) "↑" else "↓", abs(change))
}

private fun deltaText(current: Double, previous: Double?, formatter: (Double) -> String): String {
    if (previous == null) return "This week"
    val change = current - previous
    if (abs(change) < 0.5) return "Similar to last week"
    return "${if (change > 0) "up" else "down"} from ${formatter(previous)}"
}

private fun deltaCount(current: Int, previous: Int?): String {
    if (previous == null) return "≥15 min each"
    val change = current - previous
    if (change == 0) return "Same as last week"
    return if (change > 0) "$change more than last week" else "${abs(change)} fewer than last week"
}

// MARK: - Metric explanations (ported from WhatHappenedMetricInfo)

internal sealed class MetricInfo(val title: String, val meaning: String, val striveFor: String) {
    class TimeInRange(low: String, high: String) : MetricInfo(
        "Time in range",
        "The percent of readings that fell between your target range ($low–$high). Higher usually means more time spent near your goals.",
        "Many care teams aim for roughly 70% or more time in range, when safe for you. Improving gradually is more important than any single week.",
    )
    class TimeLow(threshold: String) : MetricInfo(
        "Time low",
        "The percent of readings below $threshold. Even small amounts of time low can matter because lows can feel urgent and unsafe.",
        "A common safety goal is under about 4% time below range. Fewer lows is generally better — discuss your personal target with your care team.",
    )
    class TimeHigh(high: String, veryHigh: String) : MetricInfo(
        "Time high",
        "The percent of readings above $high but not above $veryHigh. This is elevated glucose that is high, but not in the “very high” band.",
        "Lower is generally better. Reducing repeated high periods (especially around the same meals or times) is often a useful discussion topic.",
    )
    class TimeVeryHigh(threshold: String) : MetricInfo(
        "Time very high",
        "The percent of readings above $threshold. These are the highest values and often need closer attention with your care team.",
        "Aim to keep this as low as safely possible. Frequent very-high time is worth reviewing with your care team.",
    )
    data object AverageGlucose : MetricInfo(
        "Average glucose",
        "The mean of all glucose readings this week. It is a simple overall level, but it does not show how much glucose bounced up and down.",
        "Many adults aim for an average near their target range (often around the mid-100s mg/dL / ~7–8 mmol/L), but your personal goal may differ.",
    )
    data object EstimatedA1CAndGMI : MetricInfo(
        "Estimated A1C & GMI",
        "Estimated A1C and GMI are lab-style estimates calculated from your average CGM glucose. They approximate longer-term glucose exposure, not a clinic blood test.",
        "Many guidelines discuss A1C near or below about 7% when safe, but targets are individual. Use this as a conversation aid, not a diagnosis.",
    )
    data object CoefficientOfVariation : MetricInfo(
        "CV (variability)",
        "CV (coefficient of variation) compares how much glucose swings relative to your average. Lower CV means smoother, more predictable glucose.",
        "A common goal is CV under about 36%. Lower variability usually means fewer extreme swings.",
    )
    data object StandardDeviation : MetricInfo(
        "Standard deviation",
        "Standard deviation is the average size of ups and downs around your mean glucose. Larger values mean bigger swings.",
        "Lower is generally better. If average glucose is stable but standard deviation is high, focus on reducing large peaks and drops.",
    )
    class LowEpisodes(threshold: String) : MetricInfo(
        "Low episodes",
        "How many separate low stretches occurred (glucose below $threshold for about 15 minutes or longer). Counting episodes shows how often lows interrupt the week.",
        "Fewer episodes is better. Preventing repeats at the same time of day is often more useful than reacting after each low.",
    )
    data object LowEpisodeDuration : MetricInfo(
        "Total time in lows",
        "The combined length of those low episodes. A few short lows and one long low can look different even if episode count is similar.",
        "Shorter total low time is better. If total time is high, ask your care team how to recognize and treat lows earlier.",
    )
}
