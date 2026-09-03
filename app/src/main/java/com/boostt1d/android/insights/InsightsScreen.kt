package com.boostt1d.android.insights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.engine.WhatHappenedAnalysisCache
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/** Where within Insights the user is. Flat, like the shell: a detail replaces the report and Back returns. */
private sealed interface InsightsRoute {
    data object Report : InsightsRoute
    data class Proposal(val id: String) : InsightsRoute
    data object Outcomes : InsightsRoute
    data class Outcome(val id: String) : InsightsRoute
}

private enum class ReportPage(val label: String) { SUMMARY("Summary"), PATTERNS("Patterns"), THERAPY("Therapy"), DAYS("Days") }

/**
 * The Insights tab: the multi-page "What Happened?" weekly report and the screens it opens.
 *
 * Ported from the iOS WhatHappenedReportView. Four pages, one question each: how was the
 * week · what repeated · what to tune · what happened day by day. One load, one seven-day
 * window, one source of truth — the snapshot the view model built.
 */
@Composable
fun InsightsScreen(
    snapshot: WhatHappenedAnalysisCache.Snapshot?,
    loading: Boolean,
    aiReviewLoading: Boolean,
    unit: BGUnit,
    showsAdvancedDetail: Boolean,
    nowMillis: Long,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var route by rememberSaveable { mutableStateOf<String>("report") }
    var proposalId by rememberSaveable { mutableStateOf<String?>(null) }
    var outcomeId by rememberSaveable { mutableStateOf<String?>(null) }

    // Load once on first open; the toolbar refresh is always available for a deliberate reload.
    LaunchedEffect(Unit) { if (snapshot == null) onRefresh() }

    BackHandler(enabled = route != "report") {
        route = when (route) {
            "outcome" -> "outcomes"
            else -> "report"
        }
    }

    val review = snapshot?.dailyTherapyReview
    when (route) {
        "proposal" -> {
            val proposal = review?.proposals?.firstOrNull { it.id == proposalId }
            if (proposal != null) {
                TherapyProposalDetailScreen(proposal, unit, showsAdvancedDetail, onBack = { route = "report" }, modifier = modifier)
                return
            }
            route = "report"
        }
        "outcomes" -> {
            TherapyChangeOutcomesListScreen(
                outcomes = snapshot?.outcomes ?: emptyList(), showsAdvancedDetail = showsAdvancedDetail, nowMillis = nowMillis,
                onOpen = { outcomeId = it; route = "outcome" }, onBack = { route = "report" }, modifier = modifier,
            )
            return
        }
        "outcome" -> {
            val outcome = snapshot?.outcomes?.firstOrNull { it.id == outcomeId }
            if (outcome != null) {
                TherapyChangeOutcomeDetailScreen(outcome, unit, showsAdvancedDetail, nowMillis, onBack = { route = "outcomes" }, modifier = modifier)
                return
            }
            route = "report"
        }
    }

    WhatHappenedReportScreen(
        snapshot = snapshot, loading = loading, aiReviewLoading = aiReviewLoading, unit = unit, showsAdvancedDetail = showsAdvancedDetail, nowMillis = nowMillis,
        onRefresh = onRefresh,
        onOpenProposal = { proposalId = it; route = "proposal" },
        onOpenOutcomes = { route = "outcomes" },
        modifier = modifier,
    )
}

@Composable
private fun WhatHappenedReportScreen(
    snapshot: WhatHappenedAnalysisCache.Snapshot?,
    loading: Boolean,
    aiReviewLoading: Boolean,
    unit: BGUnit,
    showsAdvancedDetail: Boolean,
    nowMillis: Long,
    onRefresh: () -> Unit,
    onOpenProposal: (String) -> Unit,
    onOpenOutcomes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var page by rememberSaveable { mutableStateOf(ReportPage.SUMMARY) }

    // AI wording arriving while Therapy is on screen is held until the user leaves the page, so
    // proposal layout and scroll position do not jump as sentences reflow under a thumb.
    var shown by remember { mutableStateOf(snapshot) }
    LaunchedEffect(snapshot, page) {
        if (shown == null || page != ReportPage.THERAPY) shown = snapshot
    }
    val active = shown ?: snapshot
    val hasData = active?.report?.hasEnoughCurrentData == true

    ScreenScaffold(title = "What Happened?", subtitle = "Your last seven days, in plain language", modifier = modifier) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                BoostSegmented(
                    options = ReportPage.entries.toList(), selected = page, optionLabel = { it.label },
                    onSelect = { page = it }, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh report", tint = if (loading) colors.textTertiary else colors.primary)
                }
            }
        }

        // One page exists at a time, so the segmented control costs exactly one page.
        when {
            loading && active == null -> item { LoadingBlock(pageLoadingMessage(page)) }
            !hasData -> item { EmptyReport() }
            else -> when (page) {
                ReportPage.SUMMARY -> summaryPage(active!!, unit)
                ReportPage.PATTERNS -> patternsPage(active!!, unit)
                ReportPage.THERAPY -> therapyPage(active!!, unit, showsAdvancedDetail, nowMillis, aiReviewLoading, onOpenProposal, onOpenOutcomes)
                ReportPage.DAYS -> daysPage(active!!.dailyDays, unit)
            }
        }
    }
}

private fun pageLoadingMessage(page: ReportPage): String = when (page) {
    ReportPage.SUMMARY -> "Building weekly summary…"
    ReportPage.PATTERNS -> "Looking for patterns…"
    ReportPage.THERAPY -> "Reviewing settings…"
    ReportPage.DAYS -> "Laying out the days…"
}

@Composable
private fun EmptyReport() {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.ShowChart, contentDescription = null, tint = colors.neutral, modifier = Modifier.size(40.dp))
        Text("Not enough data yet", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(
            "Keep the app syncing glucose for a few days to unlock your weekly “What Happened?” summary.",
            fontSize = 15.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
        )
    }
}

/** Shared by pages that have a "nothing here" state of their own. */
internal fun LazyListScope.emptyPageNote(title: String, body: String) {
    item {
        val colors = BoostTheme.colors
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.EventNote, contentDescription = null, tint = colors.neutral, modifier = Modifier.size(32.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(body, fontSize = 14.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}
