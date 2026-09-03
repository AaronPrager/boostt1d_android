package com.boostt1d.android.food

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restaurant
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boostt1d.android.data.FoodLogEntryEntity
import com.boostt1d.android.data.FoodLogRepository
import com.boostt1d.android.data.FoodLogRetention
import com.boostt1d.android.insights.BoostNotice
import com.boostt1d.android.logs.EmptyNote
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import java.util.Locale

private enum class FoodWindow(val days: Int, val label: String) { ONE(1, "1 day"), SEVEN(7, "7 days"), THIRTY(30, "30 days") }

/**
 * The food diary. Photo analyses, manual meals, and carbs imported from the Event Log — all
 * in one place, for up to thirty days. Ported from the iOS FoodLogView.
 */
@Composable
fun FoodLogScreen(
    foodLog: FoodLogRepository,
    nowMillis: Long,
    onOpenEntry: (String) -> Unit,
    onAdd: () -> Unit,
    onSnap: () -> Unit,
    onImportTreatments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var window by rememberSaveable { mutableStateOf(FoodWindow.ONE) }
    val entries by remember(window) { foodLog.observe(window.days, System.currentTimeMillis()) }.collectAsStateWithLifecycle(emptyList())

    // Carb rows from the Event Log become Food Log rows the moment the screen opens.
    LaunchedEffect(window) { onImportTreatments() }

    ScreenScaffold(title = "Food Log", subtitle = "Meals, carbs and photos", modifier = modifier) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                BoostSegmented(options = FoodWindow.entries.toList(), selected = window, optionLabel = { it.label }, onSelect = { window = it }, modifier = Modifier.weight(1f))
                IconButton(onClick = onSnap) { Icon(Icons.Filled.CameraAlt, contentDescription = "Snap a meal", tint = colors.primary) }
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add food entry", tint = colors.primary) }
            }
        }

        // Totals across the window, so the list has a headline number.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
                MetricTile("Meals logged", "${entries.size}", null, Icons.Filled.Restaurant, colors.food, Modifier.weight(1f))
                MetricTile("Total carbs", formatCarbs(entries.sumOf { it.carbsGrams ?: 0.0 }), "g", Icons.Filled.Eco, colors.carbs, Modifier.weight(1f))
            }
        }

        if (entries.isEmpty()) {
            item { EmptyNote("No food entries", "Snap a meal with the camera, or tap + to add one manually.") }
        } else {
            item { Text("Entries", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.padding(top = BoostSpacing.xs)) }
            items(entries.size, key = { entries[it].id }) { FoodLogRow(entries[it]) { onOpenEntry(entries[it].id) } }
        }

        item {
            BoostNotice(
                "Food log entries are stored on your device for up to ${FoodLogRetention.RETENTION_DAYS} days.",
                Icons.Filled.Lock, colors.neutral, Modifier.padding(top = BoostSpacing.xs),
            )
        }
    }
}

@Composable
private fun MetricTile(title: String, value: String, unit: String?, icon: ImageVector, color: Color, modifier: Modifier) {
    val colors = BoostTheme.colors
    BoostCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(title, fontSize = 12.sp, color = colors.textSecondary)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            unit?.let { Text(it, fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.padding(bottom = 3.dp)) }
        }
    }
}

@Composable
private fun FoodLogRow(entry: FoodLogEntryEntity, onClick: () -> Unit) {
    val colors = BoostTheme.colors
    // Decoded once per row and remembered; the bytes are small (≤80 KB) by construction.
    val thumbnail = remember(entry.id, entry.thumbnailJpeg?.size) { entry.thumbnailJpeg?.let { FoodImage.decode(it)?.asImageBitmap() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
            .border(1.dp, colors.border, RoundedCornerShape(BoostRadius.lg))
            .clickable(onClick = onClick)
            .padding(BoostSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(BoostRadius.md)).background(colors.food.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
            } else {
                Icon(Icons.Filled.Restaurant, contentDescription = null, tint = colors.food, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.descriptionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 2)
            entry.carbsGrams?.let {
                Text(
                    "${formatCarbs(it)}g carbs", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.carbs,
                    modifier = Modifier.background(colors.carbs.copy(alpha = 0.12f), RoundedCornerShape(BoostRadius.pillLike)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(Fmt.dayTime(entry.recordedAtMillis), fontSize = 11.sp, color = colors.textTertiary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
    }
}

internal fun formatCarbs(value: Double): String =
    if (value % 1.0 == 0.0) String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.1f", value)
