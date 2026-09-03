package com.boostt1d.android.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.TherapyChangeVerdict
import com.boostt1d.android.engine.TherapyParameter
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Colours and glyphs shared by every Insights view, so the tile, the row and the card cannot
// drift apart — three places rendering the same verdict in different colours is a bug the
// compiler cannot catch.

@Composable
fun Priority.tint(): Color = when (this) {
    Priority.HIGH -> BoostTheme.colors.low
    Priority.MEDIUM -> BoostTheme.colors.high
    Priority.LOW -> BoostTheme.colors.inRange
}

@Composable
fun TherapyChangeVerdict.tint(): Color = when (this) {
    TherapyChangeVerdict.IMPROVED -> BoostTheme.colors.inRange
    TherapyChangeVerdict.WORSE -> BoostTheme.colors.high
    TherapyChangeVerdict.UNCHANGED -> BoostTheme.colors.neutral
    TherapyChangeVerdict.TOO_EARLY, TherapyChangeVerdict.NOT_ENOUGH_DATA -> BoostTheme.colors.textSecondary
}

val TherapyChangeVerdict.icon: ImageVector
    get() = when (this) {
        TherapyChangeVerdict.IMPROVED -> Icons.Filled.CheckCircle
        TherapyChangeVerdict.WORSE -> Icons.Filled.Warning
        TherapyChangeVerdict.UNCHANGED -> Icons.Filled.RemoveCircleOutline
        TherapyChangeVerdict.TOO_EARLY -> Icons.Filled.Schedule
        TherapyChangeVerdict.NOT_ENOUGH_DATA -> Icons.Filled.HelpOutline
    }

@Composable
fun TherapyParameter.tint(): Color = when (this) {
    TherapyParameter.BASAL -> BoostTheme.colors.primary
    TherapyParameter.ISF -> BoostTheme.colors.insulin
    TherapyParameter.CARB_RATIO -> BoostTheme.colors.carbs
}

val TherapyParameter.icon: ImageVector
    get() = when (this) {
        TherapyParameter.BASAL -> Icons.Filled.WaterDrop
        TherapyParameter.ISF -> Icons.Filled.MonitorHeart
        TherapyParameter.CARB_RATIO -> Icons.Filled.Restaurant
    }

fun formatBg(mgdl: Double, unit: BGUnit): String = "${Fmt.glucose(mgdl, unit)} ${unit.displayName}"

/** "today", "yesterday", "9 days ago" — used wherever a therapy change is dated for a reader. */
fun relativeDays(millis: Long, nowMillis: Long): String {
    val days = ((nowMillis - millis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        else -> "$days days ago"
    }
}

private val monthDay = SimpleDateFormat("MMM d", Locale.getDefault())
fun monthDay(millis: Long): String = monthDay.format(Date(millis))

/** One line per section: name on the left, freshness or count on the right. */
@Composable
fun SectionHeading(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 12.sp, color = colors.textTertiary) }
    }
}

@Composable
fun PriorityBadge(priority: Priority) {
    val tint = priority.tint()
    Text(
        priority.label.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = tint,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(BoostRadius.pillLike))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A tinted heading that expands or collapses the lines under it. */
@Composable
fun DisclosureRow(title: String, icon: ImageVector, tint: Color, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = BoostSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun BulletLine(text: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(modifier = Modifier.padding(top = 7.dp).size(4.dp).background(tint, CircleShape))
        Text(text, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** A small labelled value on a muted ground — the current setting, what was measured, and so on. */
@Composable
fun ValueTile(label: String, text: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    Column(
        modifier = modifier
            .background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.sm))
            .padding(horizontal = BoostSpacing.xs, vertical = BoostSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = colors.textTertiary)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
fun SmallChip(icon: ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(BoostRadius.pillLike))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** iOS BoostNotice: a sentence on a tinted ground with an icon, for things the reader must not miss. */
@Composable
fun BoostNotice(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
            .padding(BoostSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** Two lines and a chevron: the row shape every list on Insights uses. */
@Composable
fun ChevronRow(icon: ImageVector, iconTint: Color, title: String, detail: String, onClick: () -> Unit) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(detail, fontSize = 12.sp, color = colors.textSecondary, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
    }
}

@Composable
fun LoadingBlock(message: String) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        CircularProgressIndicator(color = colors.primary)
        Text(message, fontSize = 14.sp, color = colors.textSecondary)
    }
}
