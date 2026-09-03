package com.boostt1d.android.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
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
import com.boostt1d.android.engine.DailyTherapyReview
import com.boostt1d.android.engine.TherapyChangeOutcome
import com.boostt1d.android.engine.TherapyChangeVerdict
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/** Page 3: what to tune — the daily review, your changes, and the detailed evidence behind both. */
internal fun LazyListScope.therapyPage(
    snapshot: WhatHappenedAnalysisCache.Snapshot,
    unit: BGUnit,
    showsAdvancedDetail: Boolean,
    nowMillis: Long,
    onOpenProposal: (String) -> Unit,
    onOpenOutcomes: () -> Unit,
) {
    item { DailyTherapyReviewSection(snapshot.dailyTherapyReview, showsAdvancedDetail, onOpenProposal) }
    item { TherapyChangeOutcomeSection(snapshot.outcomes, snapshot.watchingSinceMillis, showsAdvancedDetail, nowMillis, onOpenOutcomes) }
    if (showsAdvancedDetail && snapshot.review.hasReviewContent) {
        item { DetailedTherapyReview(snapshot.review, unit) }
    }
}

/**
 * Top-level answer on Therapy. Numeric proposals are formula-owned; AI is presented as a
 * once-daily contextual review, never as the calculator.
 *
 * An *index*, not a document: each finding gets a two-line row and its own screen.
 * Ported from the iOS DailyTherapyReviewSection.
 */
@Composable
fun DailyTherapyReviewSection(review: DailyTherapyReview, showsAdvancedDetail: Boolean, onOpenProposal: (String) -> Unit) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // "How therapy may be contributing" is accurate and almost nobody's first language.
            Text("Your insulin settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text("Whether your settings may have played a part in how this week went.", fontSize = 12.sp, color = colors.textSecondary)
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                Text("${monthDay(review.periodStartMillis)}–${monthDay(review.periodEndMillis)}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary)
                Text("·", fontSize = 11.sp, color = colors.textSecondary)
                Icon(if (review.deliveryModeLabel.contains("Automated")) Icons.Filled.Autorenew else Icons.Filled.MedicalServices, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                Text(review.deliveryModeLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary)
            }
            // Without saying so, a reader who opens this in the afternoon expects today's
            // insulin and meals to be in here — and reads the same sentences as stale.
            Text("Covers the 7 days ending last night. Today goes into tomorrow’s review.", fontSize = 11.sp, color = colors.textTertiary)
            review.statusNote?.let { Text(it, fontSize = 10.sp, color = colors.textTertiary) }
        }

        Text(review.overview, fontSize = 15.sp, color = colors.textPrimary)

        if (review.proposals.isEmpty()) {
            BoostCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Verified, contentDescription = null, tint = colors.inRange, modifier = Modifier.size(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Nothing to raise this week", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text(
                            if (showsAdvancedDetail) "This does not mean every setting is correct. It means no available window cleared the evidence and safety thresholds."
                            else "That does not mean every setting is right — it means nothing this week was clear enough to point at one.",
                            fontSize = 13.sp, color = colors.textSecondary,
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                Text("WORTH ASKING ABOUT", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = colors.textTertiary)
                Column(
                    modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(BoostRadius.lg)).border(1.dp, colors.border, RoundedCornerShape(BoostRadius.lg)),
                ) {
                    review.proposals.forEachIndexed { index, proposal ->
                        ChevronRow(proposal.parameter.icon, colors.clinical, proposal.parameter.plainName, rowDetail(proposal)) { onOpenProposal(proposal.id) }
                        if (index < review.proposals.size - 1) BoostDivider(modifier = Modifier.padding(start = 50.dp))
                    }
                }
            }
        }
    }
}

/** The window always; the direction only when there are two numbers to compare. */
private fun rowDetail(proposal: DailyTherapyProposal): String {
    val current = proposal.currentValue
    val proposed = proposal.proposedValue
    if (current == null || proposed == null || current == proposed) return proposal.timeWindow
    return "${proposal.timeWindow} · ${proposal.parameter.shortChangePhrase(increasing = proposed > current)}"
}

/**
 * "Did it work?" — every therapy change the app can see, with what happened after it. One tile,
 * not a list: the reader almost always wants one thing, how the last change went.
 * Ported from the iOS TherapyChangeOutcomeSection.
 */
@Composable
fun TherapyChangeOutcomeSection(outcomes: List<TherapyChangeOutcome>, watchingSinceMillis: Long?, showsAdvancedDetail: Boolean, nowMillis: Long, onOpen: () -> Unit) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Text("YOUR CHANGES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = colors.textTertiary)

        if (outcomes.isEmpty()) {
            // Two meanings, different words: the settings have not moved, or the app has not
            // been watching long enough to know.
            val hasBaseline = watchingSinceMillis != null && nowMillis - watchingSinceMillis > 3 * 86_400_000L
            BoostCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (hasBaseline) Icons.Filled.Verified else Icons.Filled.Schedule, contentDescription = null, tint = if (hasBaseline) colors.inRange else colors.neutral, modifier = Modifier.size(20.dp))
                    Text(if (hasBaseline) "No therapy changes to check" else "Watching for your next change", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                }
                Text(
                    if (hasBaseline) "Nothing to compare — your settings have not changed recently."
                    else "Your current settings are recorded. The next change gets a before-and-after here.",
                    fontSize = 13.sp, color = colors.textSecondary,
                )
            }
        } else {
            val leading = outcomes.first()
            val setting = if (showsAdvancedDetail) leading.change.parameter.shortName else leading.change.parameter.plainName.lowercase()
            val subtitle = if (outcomes.size > 1) "See how your recent setting changes turned out."
            else "See how your $setting change ${relativeDays(leading.change.changedAtMillis, nowMillis)} turned out."
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
                    .border(1.dp, colors.border, RoundedCornerShape(BoostRadius.lg))
                    .clickable(onClick = onOpen)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, tint = colors.report, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Did your changes work?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(subtitle, fontSize = 12.sp, color = colors.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VerdictChip(leading.verdict)
                        if (outcomes.size > 1) Text("+${outcomes.size - 1} more", fontSize = 11.sp, color = colors.textTertiary)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
            }
        }
    }
}

@Composable
internal fun VerdictChip(verdict: TherapyChangeVerdict) {
    val tint = verdict.tint()
    Row(
        modifier = Modifier.background(tint.copy(alpha = 0.15f), RoundedCornerShape(BoostRadius.pillLike)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(verdict.icon, contentDescription = null, tint = tint, modifier = Modifier.size(10.dp))
        Text(verdict.displayName, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
private fun DetailedTherapyReview(review: TherapySettingsReview, unit: BGUnit) {
    val colors = BoostTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Functions, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
            Text("Detailed evidence", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = colors.textPrimary)
        }
        Text(
            "Every checked setting window, the calculation and exclusions behind it, and why any setting could not be evaluated.",
            fontSize = 13.sp, color = colors.textSecondary,
        )
        if (expanded) TherapySettingsReviewSection(review, unit, showsHourlyProfile = false)
    }
}
