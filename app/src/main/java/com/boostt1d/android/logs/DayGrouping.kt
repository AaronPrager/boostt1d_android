package com.boostt1d.android.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.TodaySoFarBuilder
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

/**
 * Log rows grouped by the day they happened on.
 *
 * A flat list stops reading past about fifty entries — every row looks alike and the only
 * way to find yesterday is to scan timestamps. Two weeks of CGM is thousands of rows, so
 * the header is what makes the list navigable rather than merely complete.
 */
fun <T> groupByDay(items: List<T>, millis: (T) -> Long): List<Pair<Long, List<T>>> =
    items
        .groupBy { TodaySoFarBuilder.startOfDay(millis(it)) }
        .toList()
        .sortedByDescending { it.first }
        .map { (day, rows) -> day to rows.sortedByDescending(millis) }

/** A sticky-feeling day header with the day's own summary on the right. */
@Composable
fun DayHeader(dayStartMillis: Long, nowMillis: Long, trailing: String? = null) {
    val colors = BoostTheme.colors
    val today = TodaySoFarBuilder.startOfDay(nowMillis)
    val yesterday = today - 24 * 60 * 60 * 1000

    val label = when (dayStartMillis) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> Fmt.day(dayStartMillis)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(top = BoostSpacing.sm, bottom = BoostSpacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary,
        )
        trailing?.let {
            Text(it, fontSize = 11.sp, color = colors.textTertiary)
        }
    }
}
