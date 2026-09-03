package com.boostt1d.android.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.engine.DailyTherapyReview
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.engine.WhatHappenedPattern
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Page 2: repeated glucose behaviour plus non-setting context that may explain it. Therapy
 * contribution and proposed setting changes deliberately live on the next page.
 */
internal fun LazyListScope.patternsPage(snapshot: WhatHappenedAnalysisCache.Snapshot, unit: BGUnit) {
    val patterns = snapshot.patterns
    val review = snapshot.review

    if (patterns.isEmpty()) {
        item { NoPatterns() }
    } else {
        item { SectionHeading("Patterns", trailing = snapshot.coverageLabel) }
        items(patterns.size, key = { patterns[it].id }) { index ->
            PatternCard(patterns[index], rank = index + 1, relatedWindows = relatedWindows(patterns[index], review))
        }
    }

    if (review.hasHourlyProfile) {
        item { HourlyPatternSection(review, unit) }
    }

    val daily = snapshot.dailyTherapyReview
    if (daily.observations.isNotEmpty() || daily.experiments.isNotEmpty() || daily.safetyNotes.isNotEmpty()) {
        item { DailyPatternContextSection(daily) }
    }

    // Said once for the page, rather than in every card.
    item {
        Text(
            "Discussion prompts, not dosing instructions.",
            fontSize = 12.sp, color = BoostTheme.colors.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * Windows in the settings review that cover the same hours a pattern is about, so the two
 * halves of the page point at each other instead of repeating each other.
 */
private fun relatedWindows(pattern: WhatHappenedPattern, review: TherapySettingsReview): List<String> {
    if (pattern.hours.isEmpty()) return emptyList()
    return review.findings
        .filter { finding ->
            val hours = if (finding.startHour < finding.endHour) (finding.startHour until finding.endHour).toSet()
            else (finding.startHour until 24).toSet() + (0 until finding.endHour).toSet()
            hours.any { it in pattern.hours }
        }
        .map { "${it.parameter.shortName} · ${it.windowLabel}" }
        .toSet()
        .sorted()
}

@Composable
private fun NoPatterns() {
    val colors = BoostTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Verified, contentDescription = null, tint = colors.inRange, modifier = Modifier.size(28.dp))
        Text("No strong patterns this week", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text("Nothing repeated often enough to highlight.", fontSize = 14.sp, color = colors.textSecondary)
    }
}

@Composable
private fun PatternCard(pattern: WhatHappenedPattern, rank: Int, relatedWindows: List<String>) {
    val colors = BoostTheme.colors
    BoostCard {
        // Rank, priority and title on one row. "Pattern 1" on its own line was a label for a
        // number the ordering already conveys.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$rank.", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary)
            Text(pattern.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.weight(1f))
            PriorityBadge(pattern.priority)
        }

        Text(pattern.observation, fontSize = 14.sp, color = colors.textPrimary)
        Text(pattern.frequencyLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.report)

        MiniChart(pattern)

        if (pattern.contributingFactors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Plausible causes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                pattern.contributingFactors.forEach { BulletLine(it, colors.textSecondary) }
            }
        }

        if (pattern.discussQuestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What to ask your doctor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                pattern.discussQuestions.forEach { question ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(top = 2.dp).size(12.dp))
                        Text(question, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (relatedWindows.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.SubdirectoryArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(top = 2.dp).size(12.dp))
                Text("See Settings: ${relatedWindows.joinToString(" · ")}", fontSize = 12.sp, color = colors.textTertiary)
            }
        }
    }
}

/** Day-level bars for the pattern window — averages, or 0/1 occurrence flags. */
@Composable
private fun MiniChart(pattern: WhatHappenedPattern) {
    val colors = BoostTheme.colors
    val points = pattern.chartPoints
    if (points.isEmpty()) return
    val maxValue = maxOf(points.maxOf { it.value }, 1.0)
    val highlight = colors.high
    val base = colors.report.copy(alpha = 0.35f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(42.dp)) {
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (points.size - 1)) / points.size).coerceAtLeast(8f)
            points.forEachIndexed { index, point ->
                val barHeight = (point.value / maxValue * size.height).toFloat().coerceAtLeast(4f)
                drawRoundRect(
                    color = if (point.highlighted) highlight else base,
                    topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            points.forEach { point ->
                Text(
                    point.label, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (point.highlighted) colors.high else colors.textSecondary, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A glucose-only time-of-day view. Delivery-ratio and fasting interpretation belong on Therapy. */
@Composable
private fun HourlyPatternSection(review: TherapySettingsReview, unit: BGUnit) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        SectionHeading("Glucose by time of day", trailing = "${review.periodDays} days")
        Text(
            "Each bar combines the same clock hour across the seven-day window. Tap an hour for its average and range breakdown.",
            fontSize = 13.sp, color = colors.textSecondary,
        )
        TherapyHourlyProfileStrip(hours = review.hours, method = review.basalMethod, showsTherapyContext = false, unit = unit)
    }
}

/**
 * Cross-domain context belongs with Patterns: it describes repeated food, exercise, timing and
 * logging behaviour without implying that a therapy setting should change. Empty until the AI
 * path arrives, since only it produces observations.
 */
@Composable
private fun DailyPatternContextSection(review: DailyTherapyReview) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        SectionHeading("Food, exercise & timing context")
        Text(
            "These observations may help explain the patterns above. They are kept separate from therapy-setting proposals.",
            fontSize = 13.sp, color = colors.textSecondary,
        )
        review.observations.forEach { observation ->
            BoostCard {
                Text(observation.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(observation.observation, fontSize = 15.sp, color = colors.textSecondary)
                observation.supportingEvidence.forEach { Text(it, fontSize = 13.sp, color = colors.textTertiary) }
                Text("Watch next: ${observation.whatToTrack}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
            }
        }
        if (review.experiments.isNotEmpty()) {
            BoostCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.FactCheck, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    Text("What to verify next (${review.experiments.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                }
                review.experiments.forEach { BulletLine(it, colors.primary) }
            }
        }
        if (review.safetyNotes.isNotEmpty()) {
            BoostNotice(review.safetyNotes.joinToString(" "), Icons.Filled.Security, colors.high)
        }
    }
}
