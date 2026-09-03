package com.boostt1d.android.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.sync.ConnectionFields
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        ConnectionFields(
            connection = draft.connection,
            nightscoutUrl = draft.nightscoutUrl,
            nightscoutToken = draft.nightscoutToken,
            dexcomUsername = draft.dexcomUsername,
            dexcomPassword = draft.dexcomPassword,
            dexcomRegion = draft.dexcomRegion,
            onConnectionChange = { option -> viewModel.update { it.copy(connection = option) } },
            onUrlChange = { url -> viewModel.update { it.copy(nightscoutUrl = url) } },
            onTokenChange = { token -> viewModel.update { it.copy(nightscoutToken = token) } },
            onDexcomUsernameChange = { value -> viewModel.update { it.copy(dexcomUsername = value) } },
            onDexcomPasswordChange = { value -> viewModel.update { it.copy(dexcomPassword = value) } },
            onDexcomRegionChange = { value -> viewModel.update { it.copy(dexcomRegion = value) } },
            libreUsername = draft.libreUsername,
            librePassword = draft.librePassword,
            libreRegion = draft.libreRegion,
            onLibreUsernameChange = { value -> viewModel.update { it.copy(libreUsername = value) } },
            onLibrePasswordChange = { value -> viewModel.update { it.copy(librePassword = value) } },
            onLibreRegionChange = { value -> viewModel.update { it.copy(libreRegion = value) } },
            onTest = viewModel::testNightscout,
            testing = viewModel.testingConnection,
            testResult = viewModel.connectionTestResult,
            testSucceeded = viewModel.connectionTestSucceeded,
        )

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
