package com.boostt1d.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseCacheRules
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseStatistics

/**
 * Average, GMI, variability and the three range bands, with card chrome.
 *
 * Shared by the BG Log and the dashboard so the same window can never produce two different
 * readings of itself. It takes finished statistics rather than readings: the caller owns
 * which window it is describing, and the eyebrow says so.
 */
@Composable
fun GlucoseStatisticsCard(
    stats: GlucoseStatistics,
    unit: BGUnit,
    /** What window these numbers cover, in the caller's own words. */
    eyebrow: String,
    modifier: Modifier = Modifier,
) {
    BoostCard(modifier = modifier) {
        GlucoseStatisticsContent(stats, unit, eyebrow)
    }
}

/**
 * The same figures with no chrome of their own, for a caller that is already inside a card.
 * The dashboard puts the reading, what is still on board and the trend in one panel, and a
 * card within a card reads as unrelated panels sitting on top of each other.
 */
@Composable
fun GlucoseStatisticsContent(
    stats: GlucoseStatistics,
    unit: BGUnit,
    eyebrow: String,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Text(
            eyebrow,
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
    valueColor: Color? = null,
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
