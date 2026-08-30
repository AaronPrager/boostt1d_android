package com.boostt1d.android.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseState
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import com.boostt1d.android.ui.TrendArrow

/**
 * What your glucose is now.
 *
 * The card is tinted by the state it is reporting, so the answer is legible before any of
 * it is read. Ported from the iOS GlucoseCard: status badge, the reading itself, a
 * low-to-high track with a marker, and a footer naming where the number came from.
 */
@Composable
fun GlucoseCard(
    glucoseMgdl: Int?,
    trendDirection: String?,
    source: GlucoseConnectionOption,
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    previousMgdl: Int?,
    measurementAtMillis: Long?,
    nowMillis: Long,
    isRefreshing: Boolean,
    showsAddReading: Boolean,
    onRefresh: () -> Unit,
    onAddReading: () -> Unit,
) {
    val colors = BoostTheme.colors
    val state = GlucoseState.classify(glucoseMgdl?.toDouble(), lowMgdl, highMgdl)
    val tint = state.color()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.xl))
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(BoostRadius.xl))
            .padding(BoostSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StateBadge(state, tint)

                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        glucoseMgdl?.let { GlucoseDisplay.format(it.toDouble(), unit) } ?: "--",
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                    )
                    Text(
                        unit.displayName,
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularAction(
                        icon = Icons.Filled.Refresh,
                        label = if (isRefreshing) "Refreshing glucose" else "Refresh glucose",
                        spinning = isRefreshing,
                        onClick = onRefresh,
                    )
                    if (showsAddReading) {
                        CircularAction(Icons.Filled.Add, "Add entry", false, onAddReading)
                    }
                }

                val arrow = TrendArrow.symbol(trendDirection)
                if (arrow != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(arrow, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tint)
                        TrendArrow.label(trendDirection)?.let {
                            Text(it, fontSize = 11.sp, color = colors.textSecondary)
                        }
                    }
                } else if (glucoseMgdl == null) {
                    Text("No recent reading", fontSize = 11.sp, color = colors.textTertiary)
                }
            }
        }

        RangeTrack(glucoseMgdl?.toDouble(), lowMgdl, highMgdl)

        Footer(
            source = source,
            unit = unit,
            glucoseMgdl = glucoseMgdl,
            previousMgdl = previousMgdl,
            measurementAtMillis = measurementAtMillis,
            nowMillis = nowMillis,
        )
    }
}

@Composable
private fun StateBadge(state: GlucoseState, tint: Color) {
    val icon: ImageVector = when (state) {
        GlucoseState.LOW -> Icons.Filled.ArrowDownward
        GlucoseState.IN_RANGE -> Icons.Filled.CheckCircle
        GlucoseState.HIGH, GlucoseState.VERY_HIGH -> Icons.Filled.ArrowUpward
        GlucoseState.UNKNOWN -> Icons.Filled.HelpOutline
    }
    Row(
        modifier = Modifier
            .background(tint, RoundedCornerShape(BoostRadius.pillLike))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text(state.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/**
 * A low-to-high track with the reading marked on it.
 *
 * The track runs from 40 to 1.6x the high bound, which puts the target band around the
 * middle — a track scaled to the reading itself would move the target every time the
 * number changed, and the point is to see where you are *relative to a fixed range*.
 */
@Composable
private fun RangeTrack(glucoseMgdl: Double?, lowMgdl: Double, highMgdl: Double) {
    val colors = BoostTheme.colors
    val trackMin = 40.0
    val trackMax = maxOf(highMgdl * 1.6, lowMgdl + 60)

    fun fraction(value: Double) = ((value.coerceIn(trackMin, trackMax) - trackMin) / (trackMax - trackMin)).toFloat()

    val lowFraction = fraction(lowMgdl)
    val highFraction = fraction(highMgdl)

    Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
        val trackHeight = 6.dp.toPx()
        val top = (size.height - trackHeight) / 2

        drawRoundRect(
            brush = Brush.horizontalGradient(
                0f to colors.low,
                lowFraction to colors.low,
                (lowFraction + 0.001f) to colors.inRange,
                highFraction to colors.inRange,
                (highFraction + 0.001f) to colors.high,
                1f to colors.veryHigh,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2),
        )

        glucoseMgdl?.let { value ->
            val x = fraction(value) * size.width
            // A white ring so the marker stays visible wherever it lands on the gradient.
            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, size.height / 2))
            drawCircle(colors.textPrimary, radius = 3.dp.toPx(), center = Offset(x, size.height / 2))
        }
    }
}

@Composable
private fun Footer(
    source: GlucoseConnectionOption,
    unit: BGUnit,
    glucoseMgdl: Int?,
    previousMgdl: Int?,
    measurementAtMillis: Long?,
    nowMillis: Long,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Icon(
            if (source == GlucoseConnectionOption.MANUAL) Icons.Filled.TouchApp else Icons.Filled.Cloud,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(13.dp),
        )
        Text(source.displayName, fontSize = 11.sp, color = colors.textSecondary)

        // The change since the previous reading, which is what says whether a number is
        // going somewhere or sitting still.
        if (glucoseMgdl != null && previousMgdl != null) {
            val delta = (glucoseMgdl - previousMgdl).toDouble()
            Text(
                "· ${Fmt.signed(delta, unit)} from ${GlucoseDisplay.format(previousMgdl.toDouble(), unit)}",
                fontSize = 11.sp,
                color = colors.textSecondary,
            )
        }

        Box(modifier = Modifier.weight(1f))

        measurementAtMillis?.let {
            Text(Fmt.ago(it, nowMillis), fontSize = 11.sp, color = colors.textTertiary)
        }
    }
}

@Composable
private fun CircularAction(
    icon: ImageVector,
    label: String,
    spinning: Boolean,
    onClick: () -> Unit,
) {
    val colors = BoostTheme.colors
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = if (spinning) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .background(colors.surface.copy(alpha = 0.9f), CircleShape)
            .clickable(enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = colors.primary,
            modifier = Modifier.size(16.dp).rotate(if (spinning) rotation else 0f),
        )
    }
}

@Composable
private fun GlucoseState.color(): Color {
    val colors = BoostTheme.colors
    return when (this) {
        GlucoseState.LOW -> colors.low
        GlucoseState.IN_RANGE -> colors.inRange
        GlucoseState.HIGH -> colors.high
        GlucoseState.VERY_HIGH -> colors.veryHigh
        GlucoseState.UNKNOWN -> colors.neutral
    }
}
