package com.boostt1d.android.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.engine.DailyTherapyProposal
import com.boostt1d.android.engine.TherapyChangeOutcome
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.engine.TherapySettingsReviewBuilder
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A back arrow and a title, for the screens Insights pushes. */
private fun LazyListScope.detailHeader(title: String, onBack: () -> Unit) {
    item {
        val colors = BoostTheme.colors
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xs)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        }
    }
}

// MARK: - One proposal

/**
 * One proposal, on its own screen. Given a screen it can be read at a normal size instead of
 * squeezed into captions. Ported from the iOS TherapyProposalDetailView.
 */
@Composable
fun TherapyProposalDetailScreen(proposal: DailyTherapyProposal, unit: BGUnit, showsAdvancedDetail: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    val current = proposal.currentValue
    val proposed = proposal.proposedValue
    val plainChange = if (current != null && proposed != null && current != proposed) proposal.parameter.plainChangeSentence(increasing = proposed > current) else null
    val measuredNote = measuredNote(proposal, unit)

    ScreenScaffold(title = null, subtitle = null, modifier = modifier) {
        detailHeader(proposal.parameter.plainName, onBack)

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                    Text(proposal.timeWindow, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary)
                }
                Text(proposal.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(proposal.summary, fontSize = 15.sp, color = colors.textSecondary)
                // How much the week actually supports this, said as days rather than as a grade.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
                    Text(
                        if (showsAdvancedDetail) "${proposal.evidenceStrength.label} evidence · ${proposal.sampleLabel}" else proposal.evidenceStrength.plainLabel,
                        fontSize = 11.sp, color = colors.textTertiary,
                    )
                }
            }
        }

        if (current != null || proposed != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                        ValueTile("Your setting now", current?.let { proposalValue(proposal.parameter, it, unit) } ?: "No numeric value", colors.textPrimary, Modifier.weight(1f))
                        ValueTile("Suggested", proposed?.let { proposalValue(proposal.parameter, it, unit) } ?: "No numeric value", colors.clinical, Modifier.weight(1f))
                    }
                    // The single most useful line for someone who does not read U/hr fluently.
                    plainChange?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.TurnSharpRight, contentDescription = null, tint = colors.clinical, modifier = Modifier.size(14.dp))
                            Text(it, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.clinical)
                        }
                    }
                    measuredNote?.let {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Straighten, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                            Text(it, fontSize = 11.sp, color = colors.textTertiary)
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Why this came up", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                // What the setting even is. Readers who turned advanced detail on already know.
                if (!showsAdvancedDetail) Text(proposal.parameter.plainDescription, fontSize = 12.sp, color = colors.textTertiary)
                Text(proposal.explanation, fontSize = 15.sp, color = colors.textSecondary)
            }
        }

        // Safety-relevant, so it is never behind the advanced switch.
        item { BoostNotice(proposal.deliveryNote, Icons.Filled.Autorenew, colors.clinical) }

        if (showsAdvancedDetail) listBlock("The numbers behind this", proposal.evidence)
        listBlock(if (showsAdvancedDetail) "Context to check" else "What else to look at", proposal.contributingFactors + proposal.whatToVerify)
        listBlock(if (showsAdvancedDetail) "Caveats" else "Keep in mind", proposal.caveats)

        item {
            BoostCard {
                Text("Ask your care team", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.report)
                Text("“${proposal.careTeamQuestion}”", fontSize = 15.sp, color = colors.textSecondary)
            }
        }

        item {
            Text(
                "Discussion prompts, not dosing instructions. The app never changes pump or delivery settings — verify every proposal with your care team and your device’s own reports.",
                fontSize = 11.sp, color = colors.textTertiary,
            )
        }
    }
}

private fun LazyListScope.listBlock(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    item {
        val colors = BoostTheme.colors
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            lines.forEach { BulletLine(it, colors.textTertiary) }
        }
    }
}

/**
 * Why the suggested number is not the measured one.
 *
 * Shown only when the suggestion genuinely stopped short: the measurement lies beyond it in
 * the direction of the change, and the two do not print as the same value. Side by side with
 * no explanation, "48% above profile" over a 1.10 to 1.30 step reads as a bug.
 */
private fun measuredNote(proposal: DailyTherapyProposal, unit: BGUnit): String? {
    val current = proposal.currentValue ?: return null
    val proposed = proposal.proposedValue ?: return null
    val observed = proposal.observedValue ?: return null
    val observedText = proposalValue(proposal.parameter, observed, unit)
    if (observedText == proposalValue(proposal.parameter, proposed, unit)) return null
    val movingUp = proposed > current
    if (if (movingUp) observed <= proposed else observed >= proposed) return null
    val cap = TherapySettingsReviewBuilder.MAX_CHANGE_PERCENT.roundToInt()
    return "Measured $observedText. The suggestion moves part of the way there: half the gap, " +
        "and never more than $cap% in one review."
}

private fun proposalValue(parameter: TherapyParameter, value: Double, unit: BGUnit): String = when (parameter) {
    TherapyParameter.BASAL -> String.format(Locale.US, "%.2f U/hr", value)
    TherapyParameter.ISF -> "${Fmt.glucose(value, unit)} ${unit.displayName}/U"
    TherapyParameter.CARB_RATIO -> "1 U : ${String.format(Locale.US, "%.1f", value)} g"
}

// MARK: - Outcomes list

/** Every change the app can see, newest problem first. Ported from the iOS TherapyChangeOutcomesListView. */
@Composable
fun TherapyChangeOutcomesListScreen(outcomes: List<TherapyChangeOutcome>, showsAdvancedDetail: Boolean, nowMillis: Long, onOpen: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    ScreenScaffold(title = null, subtitle = null, modifier = modifier) {
        detailHeader("Your changes", onBack)
        item { Text("What happened after each setting change, compared with the days before it.", fontSize = 15.sp, color = colors.textSecondary) }
        item {
            Column(modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(BoostRadius.lg)).border(1.dp, colors.border, RoundedCornerShape(BoostRadius.lg))) {
                outcomes.forEachIndexed { index, outcome ->
                    ChevronRow(
                        outcome.verdict.icon, outcome.verdict.tint(),
                        if (showsAdvancedDetail) outcome.change.parameter.shortName else outcome.change.parameter.plainName,
                        "${relativeDays(outcome.change.changedAtMillis, nowMillis)} · ${outcome.verdict.displayName}",
                    ) { onOpen(outcome.id) }
                    if (index < outcomes.size - 1) BoostDivider(modifier = Modifier.padding(start = 50.dp))
                }
            }
        }
    }
}

// MARK: - One outcome

@Composable
fun TherapyChangeOutcomeDetailScreen(outcome: TherapyChangeOutcome, unit: BGUnit, showsAdvancedDetail: Boolean, nowMillis: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    ScreenScaffold(title = null, subtitle = null, modifier = modifier) {
        detailHeader("Did it work?", onBack)
        item { TherapyChangeOutcomeCard(outcome, unit, showsAdvancedDetail, nowMillis) }
        item {
            Text(
                "Before-and-after comparisons cannot separate a setting change from everything else that happened that week. Treat this as a starting point for a conversation, not proof.",
                fontSize = 11.sp, color = colors.textTertiary,
            )
        }
    }
}

/** One change and what came of it. Ported from the iOS TherapyChangeOutcomeCard. */
@Composable
fun TherapyChangeOutcomeCard(outcome: TherapyChangeOutcome, unit: BGUnit, showsAdvancedDetail: Boolean, nowMillis: Long) {
    val colors = BoostTheme.colors
    val change = outcome.change
    val verdictColor = outcome.verdict.tint()
    var caveatsExpanded by rememberSaveable { mutableStateOf(false) }

    BoostCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
            Text("${change.windowLabel} · ${relativeDays(change.changedAtMillis, nowMillis)}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            VerdictChip(outcome.verdict)
        }

        // What actually moved, stated before any verdict about it — the user needs to
        // recognise their own edit before they will believe anything said about its effect.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(change.parameter.icon, contentDescription = null, tint = colors.clinical, modifier = Modifier.size(14.dp))
            Text(
                "${if (showsAdvancedDetail) change.parameter.shortName else change.parameter.plainName} ${change.valueLabel(unit)}",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
            )
            Text("· ${change.intentLabel}", fontSize = 12.sp, color = colors.textTertiary, maxLines = 1)
        }

        Text(outcome.headline, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(outcome.detail, fontSize = 15.sp, color = colors.textSecondary)

        if (outcome.hasComparison) {
            Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                StatTile("Before", averageText(outcome.before.averageGlucose, unit), "${percent(outcome.before.inRange)} in range", colors.textSecondary, Modifier.weight(1f))
                StatTile("After", averageText(outcome.after.averageGlucose, unit), "${percent(outcome.after.inRange)} in range", colors.textPrimary, Modifier.weight(1f))
                StatTile("Change", signed(outcome.inRangeDelta, " pts"), lowsText(outcome.belowDelta), verdictColor, Modifier.weight(1f))
            }
        }

        if (showsAdvancedDetail) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.QueryStats, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
                Text(outcome.sampleLabel, fontSize = 11.sp, color = colors.textTertiary)
            }
        }

        if (outcome.caveats.isNotEmpty()) {
            BoostDivider()
            DisclosureRow("What else could explain this", Icons.Filled.Warning, colors.report, caveatsExpanded) { caveatsExpanded = !caveatsExpanded }
            if (caveatsExpanded) outcome.caveats.forEach { BulletLine(it, colors.report) }
        }
    }
}

@Composable
private fun StatTile(label: String, primary: String, secondary: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val colors = BoostTheme.colors
    Column(
        modifier = modifier.background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.sm)).padding(BoostSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = colors.textTertiary)
        Text(primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint)
        Text(secondary, fontSize = 10.sp, color = colors.textTertiary)
    }
}

private fun averageText(value: Double?, unit: BGUnit): String = value?.let { Fmt.glucose(it, unit) } ?: "—"
private fun percent(value: Double): String = "${value.roundToInt()}%"
private fun signed(value: Double, suffix: String): String { val r = value.roundToInt(); return "${if (r > 0) "+" else ""}$r$suffix" }

/** Lows get their own line: a change that traded highs for lows must not read as an improvement. */
private fun lowsText(delta: Double): String {
    if (abs(delta) < 1) return "lows unchanged"
    return if (delta > 0) "+${delta.roundToInt()}% below range" else "${delta.roundToInt()}% below range"
}
