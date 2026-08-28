package com.boostt1d.android.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.onboarding.OnboardingDraft
import com.boostt1d.android.onboarding.OnboardingViewModel
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme

/**
 * How readings arrive.
 *
 * iOS offers Nightscout, Dexcom Share, FreeStyle Libre and Manual here, each with
 * its own credentials and a Test connection button. This build offers Manual only —
 * none of the sync services are ported yet, and an option that cannot work is worse
 * than an option that is not shown. The target range below applies either way.
 */
@Composable
fun ConnectionStep(draft: OnboardingDraft, viewModel: OnboardingViewModel) {
    val colors = BoostTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        Field("Data source") {
            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                GlucoseConnectionOption.selectableInThisBuild.forEach { option ->
                    val isSelected = draft.connection == option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) colors.primary.copy(alpha = 0.08f) else colors.surfaceMuted,
                                RoundedCornerShape(BoostRadius.md),
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) colors.primary else colors.border,
                                shape = RoundedCornerShape(BoostRadius.md),
                            )
                            .padding(BoostSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
                    ) {
                        Icon(
                            Icons.Filled.TouchApp,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            option.displayName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.primary.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                .padding(BoostSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
        ) {
            Text(
                "You'll enter readings yourself",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                "Nothing is downloaded from a CGM. Glucose, carbs and doses are whatever " +
                    "you log, and everything stays on this device. Connecting Nightscout, " +
                    "Dexcom or Libre is coming later.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }

        GlucoseRangeFields(draft, viewModel)
    }
}

/**
 * Target range, edited in whatever unit the user reads and stored in mg/dL.
 *
 * The text is held locally rather than derived from the draft on every keystroke:
 * round-tripping through mg/dL and back would fight the user mid-entry, turning a
 * half-typed "7" into "7 mg/dL -> 0.4 mmol/L -> 0.4".
 */
@Composable
private fun GlucoseRangeFields(draft: OnboardingDraft, viewModel: OnboardingViewModel) {
    val colors = BoostTheme.colors

    var lowText by remember { mutableStateOf("") }
    var highText by remember { mutableStateOf("") }

    // Re-seed when the unit changes, so switching mg/dL to mmol/L rewrites the fields
    // rather than reinterpreting 70 as 70 mmol/L.
    LaunchedEffect(draft.bgUnit) {
        lowText = GlucoseDisplay.format(draft.lowGlucose, draft.bgUnit)
        highText = GlucoseDisplay.format(draft.highGlucose, draft.bgUnit)
    }

    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Field("Target range (${draft.bgUnit.displayName})") {
            Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Low", fontSize = 12.sp, color = colors.textSecondary)
                    BoostTextField(
                        value = lowText,
                        onValueChange = { text ->
                            lowText = text
                            text.toDoubleOrNull()?.let { value ->
                                viewModel.update {
                                    it.copy(lowGlucose = GlucoseDisplay.toMgdL(value, it.bgUnit))
                                }
                            }
                        },
                        placeholder = GlucoseDisplay.format(70.0, draft.bgUnit),
                        keyboardType = KeyboardType.Decimal,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("High", fontSize = 12.sp, color = colors.textSecondary)
                    BoostTextField(
                        value = highText,
                        onValueChange = { text ->
                            highText = text
                            text.toDoubleOrNull()?.let { value ->
                                viewModel.update {
                                    it.copy(highGlucose = GlucoseDisplay.toMgdL(value, it.bgUnit))
                                }
                            }
                        },
                        placeholder = GlucoseDisplay.format(180.0, draft.bgUnit),
                        keyboardType = KeyboardType.Decimal,
                    )
                }
            }
        }

        Text(
            "The band your readings are measured against. You can change it later in Profile.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
    }
}
