package com.boostt1d.android.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.boostt1d.android.data.LogRepository
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSoftIcon
import com.boostt1d.android.ui.BoostSpacing
import androidx.compose.ui.semantics.Role
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
    // Three days by default: long enough to see a pattern in doses, short enough that
    // the list is still scannable.
    var window by remember { mutableStateOf(LogWindow.THREE_DAYS) }
    var activeTypes by remember { mutableStateOf(EventCategory.filterable.toSet()) }
    var showingAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NightscoutTreatment?>(null) }
    var pendingEdit by remember { mutableStateOf<NightscoutTreatment?>(null) }

    val since = nowMillis - window.millis
    val inWindow = logs.treatments.filter { it.recordedAtMillis >= since }
    val visible = inWindow.filter { EventCategory.of(it) in activeTypes }
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
                options = LogWindow.forEvents,
                selected = window,
                optionLabel = { it.label },
                onSelect = { window = it },
            )
        }

        item {
            TypeFilter(
                active = activeTypes,
                counts = inWindow.groupingBy { EventCategory.of(it) }.eachCount(),
                onToggle = { category ->
                    // Turning the last one off would show an empty log that looks like
                    // missing data, so the last active filter cannot be cleared.
                    activeTypes = if (category in activeTypes) {
                        (activeTypes - category).ifEmpty { activeTypes }
                    } else {
                        activeTypes + category
                    }
                },
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
                    if (inWindow.isEmpty()) "Nothing logged in this window" else "Nothing matches those filters",
                    "Doses, meals and events go here. Tap Log an event to add one — this is " +
                        "also what the bolus calculator and, later, the insights will read.",
                )
            }
        }

        groupByDay(visible) { it.recordedAtMillis }.forEach { (day, rows) ->
            item(key = "day-$day") {
                val dayInsulin = rows.sumOf { it.insulin ?: 0.0 }
                val dayCarbs = rows.sumOf { it.carbs ?: 0.0 }
                DayHeader(
                    dayStartMillis = day,
                    nowMillis = nowMillis,
                    trailing = "${Fmt.units(dayInsulin)} U · ${Fmt.carbs(dayCarbs)} g",
                )
            }
            items(rows, key = { it.cacheKey }) { treatment ->
                TreatmentRow(
                    treatment = treatment,
                    nowMillis = nowMillis,
                    onEdit = { pendingEdit = treatment },
                    onDelete = { pendingDelete = treatment },
                )
            }
        }
    }

    if (showingAdd) {
        AddEventDialog(
            nowMillis = nowMillis,
            onDismiss = { showingAdd = false },
            onSave = onAddEvent,
        )
    }

    pendingEdit?.let { treatment ->
        AddEventDialog(
            nowMillis = nowMillis,
            existing = treatment,
            onDismiss = { pendingEdit = null },
            onSave = { type, at, insulin, carbs, notes, duration ->
                // Replaced rather than mutated: the fingerprint that identifies an
                // unflagged treatment is built from its own values, so an edited row is a
                // different row.
                onDeleteTreatment(treatment.cacheKey)
                onAddEvent(type, at, insulin, carbs, notes, duration)
            },
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BoostTheme.colors
    val (icon, tint) = iconFor(treatment.eventType)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.md))
            // A downloaded treatment belongs to the site it came from; editing it here
            // would be overwritten on the next sync without ever reaching Nightscout.
            .clickable(enabled = treatment.enteredBy == LogRepository.ENTERED_BY_MANUAL, onClick = onEdit)
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
                Fmt.time(treatment.recordedAtMillis),
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

/**
 * How an event is grouped for filtering.
 *
 * Carbs are deliberately absent from the filter row: a carb-only row downloaded from
 * Nightscout belongs to the Food Log, and offering it here as a filter implies the Event
 * Log is where meals live.
 */
enum class EventCategory(val label: String) {
    INSULIN("Insulin"),
    TEMP_BASAL("Temp Basal"),
    EXERCISE("Activity"),
    OTHER("Other");

    companion object {
        val filterable = entries.toList()

        fun of(treatment: NightscoutTreatment): EventCategory = when {
            treatment.eventType == EventType.TEMP_BASAL -> TEMP_BASAL
            treatment.eventType == EventType.EXERCISE -> EXERCISE
            (treatment.insulin ?: 0.0) > 0 -> INSULIN
            else -> OTHER
        }
    }
}

@Composable
private fun TypeFilter(
    active: Set<EventCategory>,
    counts: Map<EventCategory, Int>,
    onToggle: (EventCategory) -> Unit,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
    ) {
        EventCategory.filterable.forEach { category ->
            val isActive = category in active
            val tint = when (category) {
                EventCategory.INSULIN -> colors.insulin
                EventCategory.TEMP_BASAL -> colors.high
                EventCategory.EXERCISE -> colors.report
                EventCategory.OTHER -> colors.neutral
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isActive) tint.copy(alpha = 0.14f) else colors.surfaceMuted,
                        RoundedCornerShape(BoostRadius.md),
                    )
                    .selectable(
                        selected = isActive,
                        role = Role.Checkbox,
                        onClick = { onToggle(category) },
                    )
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        category.label,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isActive) tint else colors.textTertiary,
                    )
                    Text(
                        "${counts[category] ?: 0}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) colors.textPrimary else colors.textTertiary,
                    )
                }
            }
        }
    }
}
