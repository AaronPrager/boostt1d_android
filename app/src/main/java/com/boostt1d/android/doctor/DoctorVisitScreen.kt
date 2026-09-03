package com.boostt1d.android.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.charts.AgpChart
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.Config
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.engine.DoctorVisitPeriod
import com.boostt1d.android.engine.DoctorVisitReport
import com.boostt1d.android.engine.DoctorVisitTherapySegment
import com.boostt1d.android.engine.DoctorVisitTherapySnapshot
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.logs.ScreenScaffold
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class VisitPage(val label: String) { BEFORE("Before Visit"), CLINICAL("Clinical"), DETAILS("Details") }

/**
 * Clinical Doctor Visit Report with a one-page "Before the Appointment" summary. Ported from the
 * iOS DoctorVisitReportView: two segmented controls (period, page), refresh and share, and the
 * three pages behind them.
 */
@Composable
fun DoctorVisitScreen(
    report: DoctorVisitReport?,
    loading: Boolean,
    patternCoverageLabel: String?,
    agpEntries: List<NightscoutGlucoseEntry>,
    questions: String,
    exporting: Boolean,
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    nowMillis: Long,
    onPeriodChange: (DoctorVisitPeriod) -> Unit,
    onRefresh: () -> Unit,
    onQuestionsChange: (String) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var period by rememberSaveable { mutableStateOf(DoctorVisitPeriod.DAYS_7) }
    var page by rememberSaveable { mutableStateOf(VisitPage.BEFORE) }

    // Loads on open and again whenever the period changes, as the iOS screen does.
    LaunchedEffect(period) { onPeriodChange(period) }

    val hasData = report != null && report.hasEnoughData
    val canExport = !exporting && !loading && hasData

    ScreenScaffold(title = "Doctor Visit", subtitle = periodRangeLabel(report, period, loading, nowMillis), modifier = modifier) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                BoostSegmented(
                    options = DoctorVisitPeriod.entries.toList(), selected = period, optionLabel = { it.title },
                    onSelect = { period = it }, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onExport, enabled = canExport) {
                    if (exporting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Share, contentDescription = "Share PDF report", tint = if (canExport) colors.clinical else colors.textTertiary)
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh report", tint = if (loading) colors.textTertiary else colors.clinical)
                }
            }
        }
        item {
            BoostSegmented(options = VisitPage.entries.toList(), selected = page, optionLabel = { it.label }, onSelect = { page = it })
        }

        when (page) {
            VisitPage.BEFORE -> beforeVisitPage(report, period, loading, patternCoverageLabel, questions, exporting, onQuestionsChange, onExport, nowMillis)
            VisitPage.CLINICAL -> clinicalPage(report, period, loading, agpEntries, unit, lowMgdl, highMgdl, nowMillis, exporting, onExport)
            VisitPage.DETAILS -> detailsPage(report, period, loading, exporting, onExport)
        }
        item { Spacer(modifier = Modifier.padding(bottom = 40.dp)) }
    }
}

// MARK: - Page 1: Before the Appointment

private fun LazyListScope.beforeVisitPage(
    report: DoctorVisitReport?, period: DoctorVisitPeriod, loading: Boolean, coverage: String?, questions: String,
    exporting: Boolean, onQuestionsChange: (String) -> Unit, onExport: () -> Unit, nowMillis: Long,
) {
    item {
        val colors = BoostTheme.colors
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.MedicalServices, contentDescription = null, tint = colors.clinical, modifier = Modifier.size(40.dp))
            Text("Before the Appointment", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text(periodRangeLabel(report, period, loading, nowMillis), fontSize = 15.sp, color = colors.textSecondary)
            Text(
                "One page to bring to your visit — what changed, patterns, and questions for your care team.",
                fontSize = 17.sp, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
    if (loading && report == null) { item { LoadingRow("Building visit summary…") }; return }
    if (report == null || !report.hasEnoughData) { item { InsufficientDataCard() }; return }

    if (report.plainLanguageSummary.isNotEmpty()) item { AppointmentCard("Summary") { Body(report.plainLanguageSummary) } }
    if (report.deliverySummary.isNotEmpty()) item { AppointmentCard("How insulin is delivered") { Body(report.deliverySummary) } }
    item {
        AppointmentCard("What changed since my last appointment") {
            report.changeSummaryLines.forEach { Body("• $it") }
            Secondary(
                if (report.usesHalfPeriodComparison) "Compared first half vs second half of this ${period.title} (prior full period not available yet)."
                else "Compared with the prior ${period.title}.",
                topPadding = 4.dp,
            )
        }
    }
    item {
        AppointmentCard("Patterns BoostT1D detected") {
            if (report.patterns.isEmpty()) Secondary("No strong recurrent patterns stood out in this period.", size = 17.sp)
            else {
                coverage?.let { Secondary(it, size = 13.sp) }
                report.patterns.take(3).forEachIndexed { index, pattern ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("${index + 1}. ${pattern.title}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = BoostTheme.colors.textPrimary)
                        Secondary(pattern.observation, size = 17.sp)
                        Text(pattern.frequencyLabel, fontSize = 15.sp, color = BoostTheme.colors.clinical)
                        if (pattern.contributingFactors.isNotEmpty()) Secondary("Possible factors: " + pattern.contributingFactors.joinToString(", "))
                        pattern.discussQuestions.forEach { Secondary("Ask: $it") }
                    }
                }
            }
        }
    }
    item {
        val colors = BoostTheme.colors
        AppointmentCard("Questions I want answered") {
            OutlinedTextField(
                value = questions, onValueChange = onQuestionsChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                placeholder = { Text("Write them down here", color = colors.textTertiary) },
            )
            val suggested = report.patterns.flatMap { it.discussQuestions }.distinct().take(4)
            if (suggested.isNotEmpty()) {
                Secondary("Suggested from patterns:", topPadding = 6.dp)
                suggested.forEach { question ->
                    Text(
                        "+ $question", fontSize = 17.sp, color = colors.clinical,
                        modifier = Modifier.fillMaxWidth().clickable { onQuestionsChange(appendQuestion(questions, question)) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
    item { ExportButton(exporting, enabled = !exporting && !loading, onExport) }
}

private fun appendQuestion(current: String, question: String): String {
    val trimmed = current.trim()
    return when {
        trimmed.isEmpty() -> "• $question"
        trimmed.contains(question) -> current
        else -> "$trimmed\n• $question"
    }
}

// MARK: - Page 2: Clinical

private fun LazyListScope.clinicalPage(
    report: DoctorVisitReport?, period: DoctorVisitPeriod, loading: Boolean, agpEntries: List<NightscoutGlucoseEntry>,
    unit: BGUnit, lowMgdl: Double, highMgdl: Double, nowMillis: Long, exporting: Boolean, onExport: () -> Unit,
) {
    item { SectionHeader(Icons.Filled.ShowChart, "Clinical summary", "AGP-style overview and key statistics for ${period.title}.") }
    if (loading && report == null) { item { LoadingRow("Loading clinical data…") }; return }
    if (report == null || !report.hasEnoughData) { item { InsufficientDataCard() }; return }

    item {
        ClinicalCard("Ambulatory glucose profile") {
            AgpChart(
                entries = agpEntries, dayCount = period.days, lowMgdl = lowMgdl, highMgdl = highMgdl, unit = unit, nowMillis = nowMillis,
                chartHeight = 240.dp, minimumDistinctDays = 1, showsBackfillPlaceholder = false, localDaysAvailable = report.dataDaysAvailable,
            )
            Secondary("Median with 25th–75th percentile band across ${period.title}.", size = 12.sp)
        }
    }
    item {
        val colors = BoostTheme.colors
        ClinicalCard("Time in range") {
            MetricRow("In range", pct(report.current.timeInRangePercent), colors.inRange)
            MetricRow("Low", pct(report.current.timeLowPercent), colors.low)
            MetricRow("High", pct(report.current.timeHighPercent), colors.high)
            MetricRow("Very high", pct(report.current.timeVeryHighPercent), colors.veryHigh)
        }
    }
    item {
        ClinicalCard("Average glucose & variability") {
            MetricRow("Average", "${report.current.averageGlucoseMgdL.roundToInt()} mg/dL")
            MetricRow("GMI", String.format(Locale.US, "%.1f%%", report.current.gmi))
            MetricRow("Estimated A1C", String.format(Locale.US, "%.1f%%", report.current.estimatedA1C))
            MetricRow("CV", String.format(Locale.US, "%.0f%%", report.current.coefficientOfVariation))
            MetricRow("Std. deviation", String.format(Locale.US, "%.0f mg/dL", report.current.standardDeviationMgdL))
        }
    }
    item {
        val colors = BoostTheme.colors
        ClinicalCard("Recurrent high & low periods") {
            if (report.recurrentHighBlocks.isEmpty() && report.recurrentLowBlocks.isEmpty()) Secondary("No strong clock-time clusters above the recurrence threshold.")
            else {
                report.recurrentHighBlocks.forEach { MetricRow(it.label, String.format(Locale.US, "%.0f%% of readings", it.percent), colors.high) }
                report.recurrentLowBlocks.forEach { MetricRow(it.label, String.format(Locale.US, "%.0f%% of readings", it.percent), colors.low) }
            }
        }
    }
    item {
        val colors = BoostTheme.colors
        ClinicalCard("Insulin & carbohydrates by day") {
            if (report.dailyProfiles.none { it.hasInsulinOrCarbs }) Secondary("No insulin or carb treatments in this window (or none synced yet).")
            else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Day", fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text("Insulin", fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    Text("Carbs", fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                }
                report.dailyProfiles.take(period.days).forEach { day ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(day.weekdayLabel, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(if (day.insulinUnits > 0) String.format(Locale.US, "%.1fu", day.insulinUnits) else "—", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.primary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                        Text(if (day.carbsGrams > 0) String.format(Locale.US, "%.0fg", day.carbsGrams) else "—", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.high, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                    }
                }
            }
        }
    }
    item {
        ClinicalCard("Weekday vs weekend") {
            ComparisonRow("Time in range", pct(report.weekday.timeInRangePercent), pct(report.weekend.timeInRangePercent))
            ComparisonRow("Average glucose", "${report.weekday.averageGlucoseMgdL.roundToInt()}", "${report.weekend.averageGlucoseMgdL.roundToInt()}")
            ComparisonRow("CV", String.format(Locale.US, "%.0f%%", report.weekday.coefficientOfVariation), String.format(Locale.US, "%.0f%%", report.weekend.coefficientOfVariation))
            ComparisonRow("Low episodes", "${report.weekday.lowEpisodeCount}", "${report.weekend.lowEpisodeCount}")
        }
    }
    item {
        ClinicalCard("Exercise-associated changes") {
            val delta = report.exerciseAssociatedDeltaMgdL
            if (delta != null) {
                val direction = if (delta < 0) "lower" else "higher"
                Text(
                    "After ${report.exerciseCount} exercise events, glucose averaged ${String.format(Locale.US, "%.0f", abs(delta))} mg/dL $direction in the following 2 hours vs earlier the same day.",
                    fontSize = 15.sp, color = BoostTheme.colors.textPrimary,
                )
            } else Secondary("Need at least two exercise events with nearby CGM to estimate post-exercise change.")
        }
    }
    item { ExportButton(exporting, enabled = !exporting && !loading, onExport) }
}

// MARK: - Page 3: Details

private fun LazyListScope.detailsPage(report: DoctorVisitReport?, period: DoctorVisitPeriod, loading: Boolean, exporting: Boolean, onExport: () -> Unit) {
    item { SectionHeader(Icons.AutoMirrored.Filled.List, "Visit details", "Daily profiles, detected patterns, and full therapy settings.") }
    if (loading && report == null) { item { LoadingRow("Loading details…") }; return }
    if (report == null || !report.hasEnoughData) { item { InsufficientDataCard() }; return }

    item {
        val colors = BoostTheme.colors
        ClinicalCard("Daily glucose profiles") {
            report.dailyProfiles.take(period.days).forEach { day ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(day.weekdayLabel, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(day.averageGlucoseMgdL?.let { "${it.roundToInt()} avg" } ?: "—", fontSize = 12.sp, color = colors.textSecondary)
                    day.timeInRangePercent?.let {
                        Text("${it.roundToInt()}% TIR", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.inRange, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    }
                }
            }
            if (report.dailyProfiles.size > period.days) Secondary("Showing ${period.days} days.", size = 12.sp)
        }
    }
    item {
        ClinicalCard("Detected patterns") {
            if (report.patterns.isEmpty()) Secondary("No strong patterns in this period.")
            else report.patterns.forEachIndexed { index, pattern ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("${index + 1}. ${pattern.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BoostTheme.colors.textPrimary)
                    Secondary(pattern.observation, size = 12.sp)
                    Text(pattern.frequencyLabel, fontSize = 11.sp, color = BoostTheme.colors.clinical)
                    if (pattern.contributingFactors.isNotEmpty()) Secondary("Possible factors: " + pattern.contributingFactors.joinToString(", "), size = 12.sp, topPadding = 2.dp)
                    pattern.discussQuestions.forEach { Secondary("Ask: $it", size = 12.sp) }
                }
            }
        }
    }
    // Only ever populated when doses may be shown at all; the guard is belt-and-braces.
    if (!Config.HIDE_DOSE_RECOMMENDATIONS && report.doseSuggestions.isNotEmpty()) {
        item {
            ClinicalCard("Dose suggestions") {
                Secondary("Formula-derived, for discussion with the care team — not instructions.", size = 12.sp)
                report.doseSuggestions.forEach { suggestion ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("${suggestion.type.displayName} · ${suggestion.timeSlot}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BoostTheme.colors.textPrimary)
                        Text(String.format(Locale.US, "%.2f → %.2f", suggestion.currentValue, suggestion.suggestedValue), fontSize = 12.sp, color = BoostTheme.colors.clinical)
                        Secondary(suggestion.reasoning, size = 11.sp)
                    }
                }
            }
        }
    }
    item {
        ClinicalCard("Current therapy profile") {
            if (report.therapy.hasAnyContent) TherapyProfileView(report.therapy)
            else Secondary("No insulin doses on device yet. Enter them in Insulin Doses.")
        }
    }
    item { ExportButton(exporting, enabled = !exporting && !loading, onExport) }
}

@Composable
private fun TherapyProfileView(therapy: DoctorVisitTherapySnapshot) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        therapy.profileName?.let { MetricRow("Profile", it) }
        therapy.timezone?.let { MetricRow("Timezone", it) }
        therapy.units?.let { MetricRow("Units", it) }
        therapy.diaHours?.let { MetricRow("DIA", String.format(Locale.US, "%.1f hours", it)) }
        ScheduleSection("Basal rates", therapy.basalSegments)
        ScheduleSection("Carb ratios", therapy.carbRatioSegments)
        ScheduleSection("Insulin sensitivity", therapy.sensitivitySegments)
        ScheduleSection("Target range", therapy.targetSegments)
        if (therapy.overrides.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Override presets", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                therapy.overrides.forEach { preset ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(preset.name, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(preset.detail, fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.End)
                    }
                }
            }
        }
        therapy.notes?.takeIf { it.isNotEmpty() }?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Notes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                Text(it, fontSize = 15.sp, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun ScheduleSection(title: String, segments: List<DoctorVisitTherapySegment>) {
    if (segments.isEmpty()) return
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        segments.forEach { segment ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(segment.time, fontSize = 15.sp, color = colors.textSecondary, modifier = Modifier.width(52.dp))
                Text(segment.valueLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            }
        }
    }
}

// MARK: - Shared UI

@Composable
private fun ExportButton(exporting: Boolean, enabled: Boolean, onExport: () -> Unit) {
    val colors = BoostTheme.colors
    Button(
        onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.insulin, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
    ) {
        if (exporting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        else Icon(Icons.Filled.Description, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (exporting) "Creating PDF…" else "Export PDF report", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

private fun periodRangeLabel(report: DoctorVisitReport?, period: DoctorVisitPeriod, loading: Boolean, nowMillis: Long): String {
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val (start, end) = if (loading || report == null) {
        // Provisional range from the selected period until the load finishes.
        val start = Calendar.getInstance().apply { timeInMillis = nowMillis; add(Calendar.DAY_OF_MONTH, -period.days) }.timeInMillis
        start to nowMillis
    } else report.periodStartMillis to report.periodEndMillis
    return "${formatter.format(Date(start))} – ${formatter.format(Date(end))} · ${period.title}"
}

@Composable
private fun InsufficientDataCard() {
    val colors = BoostTheme.colors
    BoostCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Icon(Icons.Filled.TrendingUp, contentDescription = null, tint = colors.neutral, modifier = Modifier.size(36.dp))
            Text("Not enough data for this period", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(
                "Keep syncing glucose, or choose a shorter window. On-device history can grow up to ${GlucoseCacheRules.RETENTION_DAYS} days.",
                fontSize = 15.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoadingRow(message: String) {
    val colors = BoostTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        CircularProgressIndicator(color = colors.clinical)
        Text(message, fontSize = 15.sp, color = colors.textSecondary)
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String) {
    val colors = BoostTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = colors.clinical, modifier = Modifier.size(34.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(subtitle, fontSize = 15.sp, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun AppointmentCard(title: String, content: @Composable () -> Unit) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.clinical.copy(alpha = 0.08f), RoundedCornerShape(BoostRadius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        content()
    }
}

@Composable
private fun ClinicalCard(title: String, content: @Composable () -> Unit) {
    BoostCard {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = BoostTheme.colors.textPrimary)
            content()
        }
    }
}

@Composable
private fun MetricRow(title: String, value: String, color: Color? = null) {
    val colors = BoostTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color ?: colors.textPrimary)
    }
}

@Composable
private fun ComparisonRow(title: String, weekday: String, weekend: String) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        Text(title, fontSize = 12.sp, color = colors.textSecondary)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Weekday $weekday", fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("Weekend $weekend", fontSize = 15.sp, color = colors.textPrimary)
        }
    }
}

@Composable
private fun Body(text: String) = Text(text, fontSize = 17.sp, color = BoostTheme.colors.textPrimary)

@Composable
private fun Secondary(text: String, size: androidx.compose.ui.unit.TextUnit = 15.sp, topPadding: androidx.compose.ui.unit.Dp = 0.dp) =
    Text(text, fontSize = size, color = BoostTheme.colors.textSecondary, modifier = Modifier.padding(top = topPadding))

private fun pct(value: Double): String = "${value.roundToInt()}%"
