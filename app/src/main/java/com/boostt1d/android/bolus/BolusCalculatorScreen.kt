package com.boostt1d.android.bolus

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.boostt1d.android.data.BolusCalculator
import com.boostt1d.android.data.Config
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.TherapyProfile
import com.boostt1d.android.logs.EmptyNote
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostFieldLabel
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import java.util.Calendar
import java.util.Locale

/**
 * A suggested dose from the numbers you enter.
 *
 * The arithmetic lives in [BolusCalculator] and is fully tested; this screen only parses,
 * presents, and says plainly what it could not account for. It deliberately does not read
 * insulin on board from the event log: IOB needs a decay curve over known dose times, and
 * inventing one from manually logged doses would produce a number that looks computed and
 * isn't. The field is left for the user to fill from their pump.
 */
@Composable
fun BolusCalculatorScreen(
    logs: LogState,
    therapy: TherapyProfile,
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    nowMillis: Long,
    /** From Snap a Meal; null when opened from the menu, so every field starts empty. */
    prefill: BolusPrefill? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors

    var carbsText by remember(prefill) { mutableStateOf(prefill?.let { String.format(Locale.US, "%.1f", it.carbsGrams) } ?: "") }
    var glucoseText by remember(prefill) { mutableStateOf(prefill?.glucoseMgdl?.let { GlucoseDisplay.format(it.toDouble(), unit) } ?: "") }
    var iobText by remember(prefill) { mutableStateOf(prefill?.let { String.format(Locale.US, "%.1f", it.iob) } ?: "") }
    var cobText by remember(prefill) { mutableStateOf(prefill?.let { String.format(Locale.US, "%.1f", it.cob) } ?: "") }

    // Scheduled values for right now, so the ratios match the time of day the meal is at.
    val minutesNow = Calendar.getInstance().let {
        it.timeInMillis = nowMillis
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }
    val carbRatio = therapy.valueAt(therapy.carbRatio, minutesNow)
    val isfMgdl = therapy.valueAt(therapy.sensitivity, minutesNow)
    // Mid-range is the target when no explicit one is set, which is what the user's own
    // low and high already say about where they want to be.
    val targetMgdl = (lowMgdl + highMgdl) / 2

    // The subtitle has to match what the screen does. With doses withheld it does not
    // give you a dose, and saying it does would be the wrong promise on the one screen
    // where the promise matters most.
    val subtitle = if (Config.HIDE_DOSE_RECOMMENDATIONS) {
        "How a bolus is worked out"
    } else {
        "A dose from your own numbers"
    }

    ScreenScaffold(title = "Insulin Calculator", subtitle = subtitle, modifier = modifier) {
        if (carbRatio == null) {
            item {
                EmptyNote(
                    "Add your carb ratio first",
                    "The calculator divides carbohydrates by your ratio, so it cannot say " +
                        "anything until that is entered. It is under Menu, Insulin Doses.",
                )
            }
            return@ScreenScaffold
        }

        item {
            BoostCard {
                BoostFieldLabel("Carbohydrates (g)")
                BoostTextField(
                    value = carbsText,
                    onValueChange = { carbsText = it },
                    placeholder = "45",
                    keyboardType = KeyboardType.Decimal,
                )

                BoostFieldLabel("Glucose now (${unit.displayName})", isOptional = true)
                BoostTextField(
                    value = glucoseText,
                    onValueChange = { glucoseText = it },
                    placeholder = GlucoseDisplay.format(150.0, unit),
                    keyboardType = KeyboardType.Decimal,
                )

                BoostFieldLabel("Insulin on board (U)", isOptional = true)
                BoostTextField(
                    value = iobText,
                    onValueChange = { iobText = it },
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                )

                BoostFieldLabel("Carbs on board (g)", isOptional = true)
                BoostTextField(
                    value = cobText,
                    onValueChange = { cobText = it },
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                )

                Text(
                    "Insulin and carbs on board come from your pump or loop. BoostT1D does " +
                        "not estimate them from the event log — a guessed number here would " +
                        "change the dose.",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = BoostSpacing.xxs),
                )
            }
        }

        item { UsingCard(carbRatio, isfMgdl, targetMgdl, unit) }

        val carbs = carbsText.toDoubleOrNull()
        if (carbs != null) {
            val input = BolusCalculator.Input(
                carbs = carbs,
                carbRatio = carbRatio,
                // Glucose, target and correction factor all in the user's unit, which is
                // what keeps the correction term unit-agnostic.
                glucose = glucoseText.toDoubleOrNull(),
                targetGlucose = GlucoseDisplay.fromMgdL(targetMgdl, unit),
                insulinSensitivity = isfMgdl?.let { GlucoseDisplay.fromMgdL(it, unit) },
                iob = iobText.toDoubleOrNull() ?: 0.0,
                cob = cobText.toDoubleOrNull() ?: 0.0,
            )

            val result = runCatching { BolusCalculator.calculate(input) }

            result.onSuccess { output ->
                item {
                    if (Config.HIDE_DOSE_RECOMMENDATIONS) {
                        DoseWithheldNotice()
                    } else {
                        ResultCard(output, BolusCalculator.missingCorrectionInputs(input))
                    }
                }
            }
            result.onFailure { error ->
                item {
                    EmptyNote("That does not add up", error.message ?: "Check the numbers.")
                }
            }
        }

        item { DisclaimerCard() }
    }
}

@Composable
private fun UsingCard(carbRatio: Double, isfMgdl: Double?, targetMgdl: Double, unit: BGUnit) {
    val colors = BoostTheme.colors
    BoostCard {
        Text(
            "USING YOUR SETTINGS FOR NOW",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
        SettingRow("Carb ratio", "${Fmt.units(carbRatio)} g/U")
        SettingRow(
            "Correction factor",
            isfMgdl?.let {
                "${GlucoseDisplay.format(it, unit)} ${unit.displayName}/U"
            } ?: "Not set",
        )
        SettingRow("Target", "${GlucoseDisplay.format(targetMgdl, unit)} ${unit.displayName}")
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 14.sp, color = colors.textSecondary)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    }
}

@Composable
private fun ResultCard(output: BolusCalculator.Output, missing: List<String>) {
    val colors = BoostTheme.colors
    BoostCard {
        Text(
            "SUGGESTED DOSE",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                Fmt.units(output.safeBolus),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = colors.insulin,
            )
            Text("units", fontSize = 15.sp, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        }

        SettingRow("For carbs", "${Fmt.units(output.carbBolusUnits)} U")
        if (output.correctionUnits > 0) {
            SettingRow("Correction", "${Fmt.units(output.correctionUnits)} U")
        }
        if (output.iobReduction > 0) {
            SettingRow("Less insulin on board", "−${Fmt.units(output.iobReduction)} U")
        }
        if (output.iobNeededForCOB > 0) {
            SettingRow("Already covering carbs on board", "${Fmt.units(output.iobNeededForCOB)} U")
        }

        if (missing.isNotEmpty()) {
            Text(
                "No correction applied — ${missing.joinToString(" and ")} is not set.",
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = BoostSpacing.xxs),
            )
        }

        if (output.excessInsulinUnits > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BoostSpacing.xs)
                    .background(colors.low.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                    .padding(BoostSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "More insulin on board than this meal needs",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    "${Fmt.units(output.excessInsulinUnits)} U spare — about " +
                        "${Fmt.carbs(output.carbsToOffsetExcess)} g of carbohydrate at your ratio. " +
                        "That does not by itself mean you need to eat: it may be correcting a " +
                        "high that has not come down yet.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.low.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
            .padding(BoostSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.low,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "This is arithmetic on the numbers you entered, not medical advice. BoostT1D is " +
                "not a medical device and does not dose. Check anything you plan to act on " +
                "with your care team.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
    }
}

/**
 * Shown in place of every dose figure when [Config.HIDE_DOSE_RECOMMENDATIONS] is set.
 *
 * No computed result at all — not a rounded one, not a hidden one behind a tap. The
 * arithmetic still runs and is still tested, because the same figures feed the therapy
 * review later; it simply is not shown to anyone as a dose to take.
 */
@Composable
private fun DoseWithheldNotice() {
    val colors = BoostTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.primary.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                .padding(BoostSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
        ) {
            Icon(
                Icons.Filled.MedicalServices,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                Text(
                    "Discuss your doses with your doctor",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    "This version does not calculate a dose. Your insulin doses, and any " +
                        "changes to them, should be worked out with your healthcare provider.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.primary.copy(alpha = 0.05f), RoundedCornerShape(BoostRadius.md))
                .padding(BoostSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        ) {
            Text(
                "For education only: how a bolus is calculated",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                "These are the standard formulas, shown with named terms rather than your " +
                    "numbers. Work through them with your care team.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )

            Config.educationalFormulaSteps.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(colors.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${step.number}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            step.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(step.formula, fontSize = 13.sp, color = colors.primary)
                        Text(step.explanation, fontSize = 12.sp, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}
