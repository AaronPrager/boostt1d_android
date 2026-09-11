package com.boostt1d.android.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.engine.WhatHappenedDayEvent
import com.boostt1d.android.engine.WhatHappenedDayOverview
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import kotlin.math.roundToInt

/** Page 4: the week's raw record — one card per day, expandable. Newest first. */
internal fun LazyListScope.daysPage(days: List<WhatHappenedDayOverview>, unit: BGUnit) {
    if (days.isEmpty()) {
        emptyPageNote("No days to show yet", "Keep the app syncing glucose for a few days and each day appears here.")
        return
    }
    items(days.size, key = { days[it].dayStartMillis }) { DayCard(days[it], unit) }
}

@Composable
private fun DayCard(day: WhatHappenedDayOverview, unit: BGUnit) {
    val colors = BoostTheme.colors
    // Expansion is per card and forgotten on leaving Days, so a return starts collapsed.
    var expanded by rememberSaveable(day.dayStartMillis) { mutableStateOf(false) }

    BoostCard {
        Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(day.weekdayLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(day.dateLabel, fontSize = 12.sp, color = colors.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val avg = day.averageGlucoseMgdL
                if (avg != null) Text("Avg ${Fmt.glucose(avg, unit)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                else Text("No CGM", fontSize = 14.sp, color = colors.textSecondary)
                day.timeInRangePercent?.let { Text("${it.roundToInt()}% in range", fontSize = 12.sp, color = colors.inRange) }
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = colors.textSecondary)
        }

        // Compact activity chips. No reading-count chip: how many samples the CGM happened to
        // upload is a fact about the sensor, not about the day.
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (day.meals.isNotEmpty()) SmallChip(Icons.Filled.Restaurant, "${day.totalCarbs.roundToInt()}g", colors.carbs)
            // TDD where basal is known, the bolus figure where it is not. Labelled either
            // way, because a number that is sometimes one and sometimes the other is worse
            // than no number.
            val tdd = day.totalDailyDose
            if (tdd != null && tdd > 0) {
                SmallChip(Icons.Filled.Vaccines, "TDD ${Fmt.units(tdd)}u", colors.insulin)
            } else if (day.bolusInsulin > 0) {
                SmallChip(Icons.Filled.Vaccines, "${Fmt.units(day.bolusInsulin)}u bolus", colors.insulin)
            }
            if (day.activity.isNotEmpty()) SmallChip(Icons.Filled.DirectionsRun, "${day.activity.size}", colors.clinical)
            if (day.lowMoments.isNotEmpty()) SmallChip(Icons.Filled.Warning, "${day.lowMoments.size} low", colors.low)
            if (day.notes.isNotEmpty()) SmallChip(Icons.Filled.Notes, "${day.notes.size}", colors.insight)
            if (!day.hasAnyActivity) SmallChip(Icons.Filled.Bedtime, "Quiet day", colors.neutral)
        }

        if (expanded) {
            BoostDivider()
            GlucoseDetail(day, unit)
            if (day.meals.isNotEmpty()) EventSection("Meals & carbs", Icons.Filled.Restaurant, colors.carbs, day.meals)
            if (day.boluses.isNotEmpty()) EventSection("Boluses", Icons.Filled.Vaccines, colors.insulin, day.boluses)
            // Not "Exercise": this lane carries whatever was logged under Nightscout's Exercise
            // event type, which in practice includes illness and other non-exercise context.
            if (day.activity.isNotEmpty()) EventSection("Activity & events", Icons.Filled.DirectionsRun, colors.clinical, day.activity)
            if (day.lowMoments.isNotEmpty()) EventSection("Lows", Icons.Filled.Warning, colors.low, day.lowMoments)
            if (day.notes.isNotEmpty()) EventSection("Notes", Icons.Filled.Notes, colors.insight, day.notes)
            if (!day.hasAnyActivity) Text("Nothing logged this day.", fontSize = 12.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun GlucoseDetail(day: WhatHappenedDayOverview, unit: BGUnit) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = colors.report, modifier = Modifier.width(14.dp))
            Text("Glucose", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.report)
        }
        if (day.readingCount == 0) {
            Text("No readings", fontSize = 14.sp, color = colors.textSecondary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val minG = day.minGlucoseMgdL
                val maxG = day.maxGlucoseMgdL
                if (minG != null && maxG != null) Text("${Fmt.glucose(minG, unit)}–${Fmt.glucose(maxG, unit)} ${unit.displayName}", fontSize = 14.sp, color = colors.textPrimary)
                if (day.lowReadingCount > 0) Text("${day.lowReadingCount} below target", fontSize = 12.sp, color = colors.low)
            }
        }
    }
}

@Composable
private fun EventSection(title: String, icon: ImageVector, color: Color, events: List<WhatHappenedDayEvent>) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.width(14.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
        events.forEach { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(event.title, fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.width(64.dp))
                Text(event.detail ?: "", fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}
