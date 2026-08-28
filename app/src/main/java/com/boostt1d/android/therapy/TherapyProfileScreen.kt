package com.boostt1d.android.therapy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.InsulinDoseSchedule
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.data.TimeValue
import com.boostt1d.android.logs.EmptyNote
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostFieldLabel
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt

/** Which schedule a row belongs to, and how its numbers read. */
private enum class ScheduleKind(
    val title: String,
    val detail: String,
    val unitLabel: String,
    val valueHint: String,
) {
    BASAL("Basal rates", "Units per hour, by time of day", "U/hr", "0.85"),
    CARB_RATIO("Carb ratio", "Grams of carbohydrate covered by one unit", "g/U", "10"),
    SENSITIVITY("Correction factor", "How far one unit moves your glucose", "", "50"),
}

/**
 * The therapy settings the app reasons about, entered by hand.
 *
 * On iOS these normally arrive inside a Nightscout profile. With no sync there is nothing
 * to download, so the user types them — and the same shape is filled either way, which is
 * what lets the bolus calculator read them without caring where they came from.
 */
@Composable
fun TherapyProfileScreen(
    therapy: TherapyProfile,
    unit: BGUnit,
    onSave: (TherapyProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var editing by remember { mutableStateOf<Pair<ScheduleKind, TimeValue?>?>(null) }

    fun scheduleFor(kind: ScheduleKind) = when (kind) {
        ScheduleKind.BASAL -> therapy.basal
        ScheduleKind.CARB_RATIO -> therapy.carbRatio
        ScheduleKind.SENSITIVITY -> therapy.sensitivity
    }

    fun replace(kind: ScheduleKind, next: List<TimeValue>) {
        val sorted = next
            .distinctBy { it.timeFormatted }
            .sortedBy { InsulinDoseSchedule.minutes(it.time) ?: 0 }
        onSave(
            when (kind) {
                ScheduleKind.BASAL -> therapy.copy(basal = sorted)
                ScheduleKind.CARB_RATIO -> therapy.copy(carbRatio = sorted)
                ScheduleKind.SENSITIVITY -> therapy.copy(sensitivity = sorted)
            }
        )
    }

    ScreenScaffold(title = "Insulin Doses", subtitle = "Your basal, ratio and correction", modifier = modifier) {
        if (therapy.isEmpty) {
            item {
                EmptyNote(
                    "Nothing entered yet",
                    "These are the numbers your care team set. Adding them turns on the bolus " +
                        "calculator, and they are what the therapy review will check against " +
                        "your data once it is built.",
                )
            }
        }

        ScheduleKind.entries.forEach { kind ->
            item(key = kind.name) {
                ScheduleCard(
                    kind = kind,
                    entries = scheduleFor(kind),
                    unit = unit,
                    onAdd = { editing = kind to null },
                    onEdit = { editing = kind to it },
                    onDelete = { target ->
                        replace(kind, scheduleFor(kind).filterNot { it.time == target.time })
                    },
                )
            }
        }

        item {
            Text(
                "Entered by hand. When Nightscout sync arrives these will be read from your " +
                    "site instead, and this screen will show which.",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = BoostSpacing.xs),
            )
        }
    }

    editing?.let { (kind, existing) ->
        SegmentDialog(
            kind = kind,
            existing = existing,
            unit = unit,
            onDismiss = { editing = null },
            onSave = { entry ->
                val without = scheduleFor(kind).filterNot {
                    it.time == entry.time || it.time == existing?.time
                }
                replace(kind, without + entry)
                editing = null
            },
        )
    }
}

@Composable
private fun ScheduleCard(
    kind: ScheduleKind,
    entries: List<TimeValue>,
    unit: BGUnit,
    onAdd: () -> Unit,
    onEdit: (TimeValue) -> Unit,
    onDelete: (TimeValue) -> Unit,
) {
    val colors = BoostTheme.colors
    val segments = InsulinDoseSchedule.segments(entries)

    BoostCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(kind.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(kind.detail, fontSize = 13.sp, color = colors.textSecondary)
            }
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add a ${kind.title.lowercase()} segment",
                tint = colors.primary,
                modifier = Modifier.size(24.dp).clickable(onClick = onAdd),
            )
        }

        if (segments.isEmpty()) {
            Text("Not set", fontSize = 14.sp, color = colors.textTertiary)
            return@BoostCard
        }

        segments.forEach { segment ->
            val entry = entries.firstOrNull { it.timeFormatted == segment.start }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = entry != null) { entry?.let(onEdit) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
            ) {
                Text(
                    segment.rangeLabel,
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    valueLabel(kind, segment.value, unit),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                if (entry != null) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Remove segment",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp).clickable { onDelete(entry) },
                    )
                }
            }
        }

        if (kind == ScheduleKind.BASAL) {
            InsulinDoseSchedule.totalDailyBasal(entries)?.let { total ->
                Text(
                    "${Fmt.units(total)} U a day in total",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.insulin,
                    modifier = Modifier.padding(top = BoostSpacing.xxs),
                )
            }
        }
    }
}

private fun valueLabel(kind: ScheduleKind, value: Double, unit: BGUnit): String = when (kind) {
    ScheduleKind.BASAL -> "${Fmt.units(value)} ${kind.unitLabel}"
    ScheduleKind.CARB_RATIO -> "${Fmt.units(value)} ${kind.unitLabel}"
    // A correction factor is a glucose distance, so it is written in the user's own unit.
    ScheduleKind.SENSITIVITY -> "${Fmt.units(value)} ${unit.displayName}/U"
}

@Composable
private fun SegmentDialog(
    kind: ScheduleKind,
    existing: TimeValue?,
    unit: BGUnit,
    onDismiss: () -> Unit,
    onSave: (TimeValue) -> Unit,
) {
    val colors = BoostTheme.colors
    var time by remember { mutableStateOf(existing?.timeFormatted ?: "00:00") }
    var value by remember {
        mutableStateOf(existing?.let { shownValue(kind, it.value, unit) } ?: "")
    }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(if (existing == null) "Add segment" else "Edit segment", color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
                Text(
                    "The segment runs from this time until the next one starts. The last one " +
                        "wraps past midnight, so a schedule always covers a full day.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )

                BoostFieldLabel("Starts at (24-hour)")
                BoostTextField(
                    value = time,
                    onValueChange = { time = it; problem = null },
                    placeholder = "06:00",
                )

                BoostFieldLabel(
                    when (kind) {
                        ScheduleKind.SENSITIVITY -> "Value (${unit.displayName} per unit)"
                        else -> "Value (${kind.unitLabel})"
                    }
                )
                BoostTextField(
                    value = value,
                    onValueChange = { value = it; problem = null },
                    placeholder = kind.valueHint,
                    keyboardType = KeyboardType.Decimal,
                )

                problem?.let { Text(it, fontSize = 13.sp, color = colors.low) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val minutes = InsulinDoseSchedule.minutes(time)
                    val entered = value.toDoubleOrNull()
                    when {
                        minutes == null -> problem = "Enter a time as HH:mm, like 06:00."
                        entered == null -> problem = "Enter a number."
                        entered <= 0 -> problem = "The value has to be greater than zero."
                        else -> onSave(
                            TimeValue(
                                time = String.format(
                                    java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60,
                                ),
                                value = storedValue(kind, entered, unit),
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(BoostRadius.md),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

/**
 * A correction factor is a glucose distance, so it is stored in mg/dL like every other
 * glucose value and shown in whatever the user reads. Basal and carb ratio are not
 * glucose and pass through untouched.
 */
private fun storedValue(kind: ScheduleKind, entered: Double, unit: BGUnit): Double =
    if (kind == ScheduleKind.SENSITIVITY) GlucoseDisplay.toMgdL(entered, unit) else entered

private fun shownValue(kind: ScheduleKind, stored: Double, unit: BGUnit): String =
    if (kind == ScheduleKind.SENSITIVITY) {
        GlucoseDisplay.formatInUserUnit(GlucoseDisplay.fromMgdL(stored, unit), unit)
    } else {
        Fmt.units(stored)
    }
