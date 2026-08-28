package com.boostt1d.android.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.EventType
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSoftIcon
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

@Composable
fun EventLogScreen(
    logs: LogState,
    nowMillis: Long,
    onAddEvent: (String, Long, Double?, Double?, String?, Int?) -> Unit,
    onDeleteTreatment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var window by remember { mutableStateOf(LogWindow.WEEK) }
    var showingAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NightscoutTreatment?>(null) }

    val since = nowMillis - window.millis
    val visible = logs.treatments.filter { it.recordedAtMillis >= since }
    val insulin = visible.sumOf { it.insulin ?: 0.0 }
    val carbs = visible.sumOf { it.carbs ?: 0.0 }

    ScreenScaffold(
        title = "Event Log",
        subtitle = "Insulin, carbs and events",
        modifier = modifier,
        onAdd = { showingAdd = true },
        addLabel = "Log an event",
    ) {
        item {
            BoostSegmented(
                options = LogWindow.entries.toList(),
                selected = window,
                optionLabel = { it.label },
                onSelect = { window = it },
            )
        }

        if (visible.isNotEmpty()) {
            item {
                BoostCard {
                    Text(
                        "IN THIS WINDOW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xl),
                    ) {
                        Column {
                            Text("Insulin", fontSize = 12.sp, color = colors.textSecondary)
                            Text(
                                "${Fmt.units(insulin)} U",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.insulin,
                            )
                        }
                        Column {
                            Text("Carbs", fontSize = 12.sp, color = colors.textSecondary)
                            Text(
                                "${Fmt.carbs(carbs)} g",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.carbs,
                            )
                        }
                        Column {
                            Text("Events", fontSize = 12.sp, color = colors.textSecondary)
                            Text(
                                "${visible.size}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyNote(
                    "Nothing logged in this window",
                    "Doses, meals and events go here. Tap Log an event to add one — this is " +
                        "also what the bolus calculator and, later, the insights will read.",
                )
            }
        }

        items(visible, key = { it.cacheKey }) { treatment ->
            TreatmentRow(
                treatment = treatment,
                nowMillis = nowMillis,
                onDelete = { pendingDelete = treatment },
            )
        }
    }

    if (showingAdd) {
        AddEventDialog(
            nowMillis = nowMillis,
            onDismiss = { showingAdd = false },
            onSave = onAddEvent,
        )
    }

    pendingDelete?.let { treatment ->
        ConfirmDeleteDialog(
            what = treatmentTitle(treatment.eventType, treatment.insulin, treatment.carbs),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDeleteTreatment(treatment.cacheKey)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun TreatmentRow(
    treatment: NightscoutTreatment,
    nowMillis: Long,
    onDelete: () -> Unit,
) {
    val colors = BoostTheme.colors
    val (icon, tint) = iconFor(treatment.eventType)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.md))
            .padding(horizontal = BoostSpacing.sm, vertical = BoostSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        BoostSoftIcon(icon = icon, tint = tint, size = 36.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                treatmentTitle(treatment.eventType, treatment.insulin, treatment.carbs),
                fontSize = 16.sp,
                fontWeight = TreatmentTitleWeight,
                color = colors.textPrimary,
            )
            Text(
                Fmt.dayTime(treatment.recordedAtMillis),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
            treatment.notes?.let {
                Text(it, fontSize = 13.sp, color = colors.textSecondary)
            }
            // An algorithm's dose is not the user's decision, and the log should not let
            // the two be confused once sync starts filling this screen.
            if (treatment.isAlgorithmDelivered) {
                Text("Delivered by your loop", fontSize = 11.sp, color = colors.textTertiary)
            }
        }

        Text(
            Fmt.ago(treatment.recordedAtMillis, nowMillis),
            fontSize = 12.sp,
            color = colors.textTertiary,
        )

        Icon(
            Icons.Filled.DeleteOutline,
            contentDescription = "Delete event",
            tint = colors.textTertiary,
            modifier = Modifier.size(20.dp).clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun iconFor(eventType: String?): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val colors = BoostTheme.colors
    return when (eventType) {
        EventType.EXERCISE -> Icons.Filled.DirectionsRun to colors.accent
        EventType.CARB_CORRECTION -> Icons.Filled.LocalDining to colors.carbs
        EventType.NOTE -> Icons.Filled.Notes to colors.neutral
        EventType.MEAL_BOLUS -> Icons.Filled.LocalDining to colors.insulin
        else -> Icons.Filled.Vaccines to colors.insulin
    }
}
