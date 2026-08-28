package com.boostt1d.android.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.EventType
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.ui.BoostDropdownField
import com.boostt1d.android.ui.BoostFieldLabel
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import kotlin.math.roundToInt

/**
 * Log a glucose reading by hand.
 *
 * The value is typed in whatever unit the user reads and stored as mg/dL, like everywhere
 * else. Time defaults to now and can be nudged backwards — a reading is almost always
 * being entered after the fact, never for the future, which is why there is no way to
 * push it forward.
 */
@Composable
fun AddReadingDialog(
    unit: BGUnit,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onSave: (sgvMgdl: Int, atMillis: Long) -> Unit,
) {
    val colors = BoostTheme.colors
    var text by remember { mutableStateOf("") }
    var minutesAgo by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }

    val entered = text.toDoubleOrNull()
    val mgdl = entered?.let { GlucoseDisplay.toMgdL(it, unit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Add a reading", color = colors.textPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
            ) {
                BoostFieldLabel("Glucose (${unit.displayName})")
                BoostTextField(
                    value = text,
                    onValueChange = { text = it; problem = null },
                    placeholder = GlucoseDisplay.format(120.0, unit),
                    keyboardType = KeyboardType.Decimal,
                )

                BoostFieldLabel("When")
                MinutesAgoPicker(minutesAgo) { minutesAgo = it }
                Text(
                    Fmt.dayTime(nowMillis - minutesAgo * 60_000L),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )

                problem?.let { Text(it, fontSize = 13.sp, color = colors.low) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // The bounds are the physiological range a meter can report at all;
                    // outside them the entry is a typo, not a reading.
                    val value = mgdl?.roundToInt()
                    when {
                        value == null -> problem = "Enter a glucose value."
                        value < 20 || value > 600 ->
                            problem = "That is outside the range a meter can report. Check the number."
                        else -> {
                            onSave(value, nowMillis - minutesAgo * 60_000L)
                            onDismiss()
                        }
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
 * Log insulin, carbs or an event.
 *
 * One sheet for all of them, because the fields overlap: a meal bolus carries both carbs
 * and insulin, a correction carries only insulin, and exercise carries neither. Which
 * fields matter follows from the event type rather than from four separate screens.
 */
@Composable
fun AddEventDialog(
    nowMillis: Long,
    onDismiss: () -> Unit,
    onSave: (
        eventType: String,
        atMillis: Long,
        insulin: Double?,
        carbs: Double?,
        notes: String?,
        durationMinutes: Int?,
    ) -> Unit,
) {
    val colors = BoostTheme.colors
    var eventType by remember { mutableStateOf(EventType.MEAL_BOLUS) }
    var insulinText by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var minutesAgo by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }

    val wantsInsulin = eventType in listOf(
        EventType.MEAL_BOLUS, EventType.CORRECTION_BOLUS, EventType.BOLUS,
    )
    val wantsCarbs = eventType in listOf(
        EventType.MEAL_BOLUS, EventType.CARB_CORRECTION,
    )
    val wantsDuration = eventType == EventType.EXERCISE

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Log an event", color = colors.textPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
            ) {
                BoostFieldLabel("Type")
                BoostDropdownField(
                    selected = eventType,
                    placeholder = "Select type",
                    options = EventType.manualOptions,
                    optionLabel = { it },
                    onSelect = { eventType = it; problem = null },
                )

                if (wantsInsulin) {
                    BoostFieldLabel("Insulin (U)")
                    BoostTextField(
                        value = insulinText,
                        onValueChange = { insulinText = it; problem = null },
                        placeholder = "2.5",
                        keyboardType = KeyboardType.Decimal,
                    )
                }

                if (wantsCarbs) {
                    BoostFieldLabel("Carbs (g)")
                    BoostTextField(
                        value = carbsText,
                        onValueChange = { carbsText = it; problem = null },
                        placeholder = "45",
                        keyboardType = KeyboardType.Decimal,
                    )
                }

                if (wantsDuration) {
                    BoostFieldLabel("Duration (minutes)", isOptional = true)
                    BoostTextField(
                        value = durationText,
                        onValueChange = { durationText = it },
                        placeholder = "30",
                        keyboardType = KeyboardType.Number,
                    )
                }

                BoostFieldLabel("Notes", isOptional = true)
                BoostTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = "Anything worth remembering",
                )

                BoostFieldLabel("When")
                MinutesAgoPicker(minutesAgo) { minutesAgo = it }
                Text(
                    Fmt.dayTime(nowMillis - minutesAgo * 60_000L),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )

                problem?.let { Text(it, fontSize = 13.sp, color = colors.low) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val insulin = insulinText.toDoubleOrNull()
                    val carbs = carbsText.toDoubleOrNull()
                    val duration = durationText.toIntOrNull()

                    // An event has to record something. Saving an empty bolus would put a
                    // row in the log that says a dose happened without saying how much.
                    val hasSomething = (wantsInsulin && insulin != null && insulin > 0) ||
                        (wantsCarbs && carbs != null && carbs > 0) ||
                        (!wantsInsulin && !wantsCarbs)

                    when {
                        wantsInsulin && insulinText.isNotBlank() && insulin == null ->
                            problem = "Insulin must be a number."
                        wantsCarbs && carbsText.isNotBlank() && carbs == null ->
                            problem = "Carbs must be a number."
                        insulin != null && insulin > 50 ->
                            problem = "That is a very large dose. Check the number."
                        !hasSomething ->
                            problem = "Enter an amount, or pick a different type."
                        else -> {
                            onSave(
                                eventType,
                                nowMillis - minutesAgo * 60_000L,
                                insulin?.takeIf { it > 0 },
                                carbs?.takeIf { it > 0 },
                                notes,
                                duration,
                            )
                            onDismiss()
                        }
                    }
                },
                shape = RoundedCornerShape(BoostRadius.md),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

/** Coarse offsets, because nobody remembers whether a dose was 11 or 13 minutes ago. */
@Composable
private fun MinutesAgoPicker(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(0, 15, 30, 60, 120, 240)
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp()),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
    ) {
        com.boostt1d.android.ui.BoostSegmented(
            options = options,
            selected = selected,
            optionLabel = { if (it == 0) "Now" else "-${label(it)}" },
            onSelect = onSelect,
        )
    }
}

private fun label(minutes: Int): String =
    if (minutes < 60) "${minutes}m" else "${minutes / 60}h"

private fun Int.dp() = this.let { androidx.compose.ui.unit.Dp(it.toFloat()) }

/** Row title for a treatment, so the two logs describe an event the same way. */
fun treatmentTitle(eventType: String?, insulin: Double?, carbs: Double?): String {
    val parts = mutableListOf<String>()
    insulin?.takeIf { it > 0 }?.let { parts.add("${Fmt.units(it)} U") }
    carbs?.takeIf { it > 0 }?.let { parts.add("${Fmt.carbs(it)} g") }
    val amount = parts.joinToString(" · ")
    val type = eventType ?: "Event"
    return if (amount.isEmpty()) type else "$type — $amount"
}

/** Emphasis for a row's leading label. */
val TreatmentTitleWeight = FontWeight.SemiBold
