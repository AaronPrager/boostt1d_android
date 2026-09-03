package com.boostt1d.android.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.Config
import com.boostt1d.android.engine.TherapyBasalMethod
import com.boostt1d.android.engine.TherapyDirection
import com.boostt1d.android.engine.TherapyFinding
import com.boostt1d.android.engine.TherapyHourStat
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.TherapySettingsReviewBuilder
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The hour-by-hour settings review: an hourly glucose profile, then one card per
 * setting-and-window that the data has something to say about.
 *
 * Deliberately shaped like a clinician's pass over a download rather than like the pattern
 * list — basal first, then corrections, then meal ratios, each with the sample it rests on.
 * Ported from the iOS TherapySettingsReviewSection.
 */
@Composable
fun TherapySettingsReviewSection(review: TherapySettingsReview, unit: BGUnit, showsHourlyProfile: Boolean = true) {
    val colors = BoostTheme.colors
    var steadyExpanded by rememberSaveable { mutableStateOf(false) }
    // When there are no findings, the reasons *are* the content.
    var notesExpanded by rememberSaveable { mutableStateOf(review.findings.isEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.md)) {
        SectionHeading("How each setting was evaluated", trailing = "${review.periodDays} days")

        if (showsHourlyProfile && review.hasHourlyProfile) {
            TherapyHourlyProfileStrip(review.hours, review.basalMethod, showsTherapyContext = true, unit = unit)
        }

        if (review.isClosedLoop) {
            BoostNotice(
                "Automated delivery — basal findings compare insulin delivered outside meal and logged-exercise windows with your programmed profile. " +
                    "They show how the algorithm compensated, not why it made each adjustment.",
                Icons.Filled.Autorenew, colors.clinical,
            )
        }

        if (review.findings.isEmpty()) {
            EmptyFindings(review)
        } else {
            TherapyParameter.entries.forEach { parameter ->
                val findings = review.findings.filter { it.parameter == parameter }
                if (findings.isNotEmpty()) ParameterGroup(parameter, findings, unit)
            }
        }

        if (review.steadyNotes.isNotEmpty()) {
            DisclosureCard("No clear mismatch (${review.steadyNotes.size})", Icons.Filled.Verified, colors.inRange, steadyExpanded, { steadyExpanded = !steadyExpanded }, review.steadyNotes)
        }
        if (review.dataNotes.isNotEmpty()) {
            DisclosureCard("Couldn’t evaluate (${review.dataNotes.size})", Icons.Filled.HelpOutline, colors.neutral, notesExpanded, { notesExpanded = !notesExpanded }, review.dataNotes)
        }

        Text(footprintText(review), fontSize = 11.sp, color = colors.textTertiary)
    }
}

@Composable
private fun ParameterGroup(parameter: TherapyParameter, findings: List<TherapyFinding>, unit: BGUnit) {
    val colors = BoostTheme.colors
    val tint = parameter.tint()
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(parameter.icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(parameter.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, modifier = Modifier.weight(1f))
            Text("${findings.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        }
        findings.forEach { TherapyFindingCard(it, unit) }
    }
}

/** Two different silences: the settings were checked and looked right, or nothing could be checked. */
@Composable
private fun EmptyFindings(review: TherapySettingsReview) {
    val colors = BoostTheme.colors
    val reviewedSomething = review.steadyNotes.isNotEmpty()
    BoostCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (reviewedSomething) Icons.Filled.Verified else Icons.Filled.HelpOutline, contentDescription = null,
                tint = if (reviewedSomething) colors.inRange else colors.neutral, modifier = Modifier.size(20.dp))
            Text(
                if (reviewedSomething) "No setting stood out this period" else "Not enough uninterrupted data to read your settings",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
            )
        }
        Text(
            if (reviewedSomething) "Nothing crossed the threshold worth raising. Checked windows are listed below."
            else "Not about how much data you have, but how much is uninterrupted. What got in the way is listed below.",
            fontSize = 13.sp, color = colors.textSecondary,
        )
        if (!reviewedSomething && review.periodDays < 7) {
            Text("Try the 7-day period — more days, more chances to come up clean.", fontSize = 13.sp, color = colors.primary)
        }
    }
}

/** Lists only the evidence that actually produced something, in the vocabulary of the path that produced it. */
private fun footprintText(review: TherapySettingsReview): String {
    val parts = mutableListOf<String>()
    when (review.basalMethod) {
        TherapyBasalMethod.LOOP_DELIVERY -> parts += "${review.loopComparedHours} hours of loop delivery vs your profile, ${review.loopComparedDays} days"
        TherapyBasalMethod.FASTING_DRIFT -> parts += "${review.cleanFastingHours} fasting hours"
        TherapyBasalMethod.NONE -> {}
    }
    if (review.cleanCorrections > 0) parts += "${review.cleanCorrections} corrections"
    if (review.cleanMeals > 0) parts += "${review.cleanMeals} meals"
    if (parts.isEmpty()) return "Nothing could be measured cleanly enough to read a setting from — see above."
    return "Measured from " + parts.joinToString(" · ") + "."
}

@Composable
private fun DisclosureCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, expanded: Boolean, onToggle: () -> Unit, lines: List<String>) {
    BoostCard {
        DisclosureRow(title, icon, tint, expanded, onToggle)
        if (expanded) lines.forEach { BulletLine(it, tint) }
    }
}

// MARK: - Hourly profile

/**
 * Twenty-four bars, one per clock hour: average glucose, coloured by where that hour mostly
 * sat, with an arrow where fasting glucose reliably drifts. Tap an hour for its detail.
 */
@Composable
fun TherapyHourlyProfileStrip(hours: List<TherapyHourStat>, method: TherapyBasalMethod, showsTherapyContext: Boolean, unit: BGUnit) {
    val colors = BoostTheme.colors
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    val barHeight = 92.dp

    BoostCard {
        Row(modifier = Modifier.fillMaxWidth().height(barHeight), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            hours.forEach { hour ->
                val isSelected = selectedHour == hour.hour
                val fraction = heightFraction(hour)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight)
                        .clickable { selectedHour = if (isSelected) null else hour.hour }
                        .semantics { contentDescription = accessibilityLabel(hour, unit) },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val drift = hour.fastingDrift
                    if (showsTherapyContext && drift != null && abs(drift) >= 8) {
                        Icon(if (drift > 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, contentDescription = null,
                            tint = if (drift > 0) colors.high else colors.low, modifier = Modifier.size(8.dp))
                    } else {
                        Box(modifier = Modifier.height(8.dp))
                    }
                    val color = barColor(hour)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .height((barHeight - 12.dp) * fraction)
                            .background(color.copy(alpha = if (hour.readingCount == 0) 0.15f else if (isSelected) 1f else 0.75f), RoundedCornerShape(2.dp))
                            .then(if (isSelected) Modifier.border(1.dp, colors.textPrimary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)) else Modifier),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18).forEach { mark ->
                Text(String.format(Locale.US, "%02d:00", mark), fontSize = 9.sp, color = colors.textTertiary, modifier = Modifier.weight(1f))
            }
        }

        val selected = hours.firstOrNull { it.hour == selectedHour }
        if (selected != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    String.format(Locale.US, "%02d:00–%02d:00", selected.hour, (selected.hour + 1) % 24),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
                )
                Text(detailLine(selected, method, showsTherapyContext, unit), fontSize = 11.sp, color = colors.textSecondary)
            }
        } else {
            Text(
                if (hours.any { it.deliveredRatio != null })
                    "Average glucose by hour of day. Tap any hour to see what your profile asked for and what was actually delivered."
                else
                    "Average glucose by hour of day. Arrows mark hours where glucose reliably drifts while fasting — the signal basal is read from. Tap any hour.",
                fontSize = 11.sp, color = colors.textTertiary,
            )
        }
    }
}

private fun heightFraction(hour: TherapyHourStat): Float {
    val average = hour.averageGlucose ?: return 0.05f
    if (hour.readingCount == 0) return 0.05f
    val clamped = average.coerceIn(60.0, 320.0)
    return (((clamped - 40) / (320 - 40)).toFloat()).coerceAtLeast(0.07f)
}

@Composable
private fun barColor(hour: TherapyHourStat): Color {
    val colors = BoostTheme.colors
    return when {
        hour.timeBelow >= 10 -> colors.low
        hour.timeAbove >= 45 -> colors.high
        hour.timeInRange >= 70 -> colors.inRange
        else -> colors.neutral
    }
}

private fun accessibilityLabel(hour: TherapyHourStat, unit: BGUnit): String {
    val average = hour.averageGlucose ?: return "${hour.hour} hundred hours, no data"
    return "${hour.hour} hundred hours, average ${Fmt.glucose(average, unit)} ${unit.displayName}, ${hour.timeInRange.roundToInt()} percent in range"
}

private fun detailLine(hour: TherapyHourStat, method: TherapyBasalMethod, showsTherapyContext: Boolean, unit: BGUnit): String {
    val average = hour.averageGlucose
    if (average == null || hour.readingCount == 0) return "No readings in this hour."
    var line = "Average ${Fmt.glucose(average, unit)} ${unit.displayName} · ${hour.timeInRange.roundToInt()}% in range"
    if (hour.timeBelow >= 1) line += " · ${hour.timeBelow.roundToInt()}% low"
    line += " · ${hour.dayCount} days"
    if (!showsTherapyContext) return line

    // Delivery first when the review used it. Reporting fasting windows underneath a finding
    // drawn from delivery data told loop users their basal could not be read from an hour it
    // had just been read from.
    val ratio = hour.deliveredRatio
    val drift = hour.fastingDrift
    when {
        ratio != null -> {
            val percent = ((ratio - 1) * 100).roundToInt()
            line += if (abs(percent) < 5) ". Insulin delivered here matched your profile across ${hour.deliveredDayCount} days."
            else ". Delivered ${abs(percent)}% ${if (percent > 0) "above" else "below"} profile across ${hour.deliveredDayCount} days."
            if (hour.mealShadowedDays > 0) line += " ${hour.mealShadowedDays} more excluded as meal coverage."
            if (hour.activityShadowedDays > 0) line += " ${hour.activityShadowedDays} more excluded near logged exercise."
        }
        method == TherapyBasalMethod.LOOP_DELIVERY -> {
            val exclusions = mutableListOf<String>()
            if (hour.mealShadowedDays > 0) {
                val shadow = if (hour.hour in 0 until 6 || hour.hour == 23) TherapySettingsReviewBuilder.overnightMealExclusionHours else TherapySettingsReviewBuilder.mealExclusionHours
                exclusions += "${hour.mealShadowedDays} day${if (hour.mealShadowedDays == 1) " was" else "s were"} within ${shadow.toInt()}h of a meal"
            }
            if (hour.activityShadowedDays > 0) {
                exclusions += "${hour.activityShadowedDays} day${if (hour.activityShadowedDays == 1) " was" else "s were"} within ${TherapySettingsReviewBuilder.activityExclusionHours.toInt()}h of exercise"
            }
            val reason = if (exclusions.isEmpty()) "" else " — " + exclusions.joinToString("; ")
            line += if (hour.deliveredDayCount > 0) ". Only ${hour.deliveredDayCount} day${if (hour.deliveredDayCount == 1) "" else "s"} to compare here$reason."
            else ". Nothing to compare here${if (reason.isEmpty()) ", no delivery outside excluded hours" else reason}."
        }
        drift != null -> {
            line += ". Fasting glucose ${if (drift > 0) "rising" else "falling"} ${Fmt.glucose(abs(drift), unit)} ${unit.displayName}/h across ${hour.fastingWindowCount} clean hours."
        }
        hour.fastingWindowCount > 0 -> line += ". Only ${hour.fastingWindowCount} uninterrupted hour(s) here — too few to read basal from."
        else -> line += ". Nothing uninterrupted enough here to read basal from."
    }
    return line
}

// MARK: - Finding card

@Composable
fun TherapyFindingCard(finding: TherapyFinding, unit: BGUnit) {
    val colors = BoostTheme.colors
    var evidenceExpanded by rememberSaveable { mutableStateOf(false) }
    var caveatsExpanded by rememberSaveable { mutableStateOf(false) }

    BoostCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
            Text(finding.windowLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            PriorityBadge(finding.priority)
        }
        Text(finding.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(finding.headline, fontSize = 15.sp, color = colors.textSecondary)

        // The current setting, what the data measured against it, and — only when the build
        // shows dose figures — the conservative half-step toward it.
        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
            finding.currentValue?.let { ValueTile("Profile", valueText(finding.parameter, it, unit), colors.textPrimary, Modifier.weight(1f)) }
            finding.observedValue?.let { ValueTile("Observed", valueText(finding.parameter, it, unit), colors.clinical, Modifier.weight(1f)) }
            val directionTint = if (finding.direction == TherapyDirection.DECREASE) colors.low else colors.high
            if (Config.HIDE_DOSE_RECOMMENDATIONS) {
                ValueTile("Reads as", directionText(finding), directionTint, Modifier.weight(1f))
            } else {
                finding.suggestedValue?.let { ValueTile(percentLabel(finding), valueText(finding.parameter, it, unit), directionTint, Modifier.weight(1f)) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.QueryStats, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
            Text("${finding.sampleLabel} · ${finding.strength.label.lowercase()} evidence", fontSize = 11.sp, color = colors.textTertiary)
        }

        BoostDivider()
        DisclosureRow("How this was measured", Icons.Filled.Functions, colors.clinical, evidenceExpanded) { evidenceExpanded = !evidenceExpanded }
        if (evidenceExpanded) {
            finding.evidence.forEach { BulletLine(it, colors.clinical) }
            Text(finding.rationale, fontSize = 13.sp, color = colors.textSecondary)
        }

        BoostDivider()
        DisclosureRow("Before anything changes", Icons.Filled.MedicalServices, colors.report, caveatsExpanded) { caveatsExpanded = !caveatsExpanded }
        if (caveatsExpanded) {
            finding.caveats.forEach { BulletLine(it, colors.report) }
            Text("Ask your care team", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.report)
            Text("“${finding.doctorQuestion}”", fontSize = 13.sp, color = colors.textSecondary)
        }
    }
}

internal fun valueText(parameter: TherapyParameter, value: Double, unit: BGUnit): String = when (parameter) {
    TherapyParameter.BASAL -> String.format(Locale.US, "%.2f U/hr", value)
    TherapyParameter.ISF -> "1:${Fmt.glucose(value, unit)} ${unit.displayName}/U"
    TherapyParameter.CARB_RATIO -> "1:${String.format(Locale.US, "%.1f", value)} g/U"
}

private fun percentLabel(finding: TherapyFinding): String {
    val percent = finding.percentChange ?: return "Discuss"
    return "${if (percent > 0) "+" else ""}${percent.roundToInt()}%"
}

/**
 * Wording used when the build hides dose figures. States which way the setting reads against
 * the data and stops there — the measured numbers stay (they describe what happened), but no
 * target value or percentage that could be followed as an instruction.
 */
private fun directionText(finding: TherapyFinding): String = when (finding.parameter) {
    TherapyParameter.BASAL -> if (finding.direction == TherapyDirection.INCREASE) "Runs light" else "Runs strong"
    TherapyParameter.ISF -> if (finding.direction == TherapyDirection.INCREASE) "Corrections strong" else "Corrections short"
    TherapyParameter.CARB_RATIO -> if (finding.direction == TherapyDirection.INCREASE) "Meal dose strong" else "Meal dose short"
}
