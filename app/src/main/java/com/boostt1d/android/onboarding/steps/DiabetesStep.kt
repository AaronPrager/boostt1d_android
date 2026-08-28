package com.boostt1d.android.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.onboarding.OnboardingDraft
import com.boostt1d.android.onboarding.OnboardingValidation
import com.boostt1d.android.onboarding.OnboardingViewModel
import com.boostt1d.android.ui.BoostDropdownField
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

@Composable
fun DiabetesStep(draft: OnboardingDraft, viewModel: OnboardingViewModel) {
    val colors = BoostTheme.colors

    // "Not set" is offered here as a real choice — it is the storage value meaning
    // "work it out from the doses", which is what iOS labels "None".
    val therapyOptions = listOf(InsulinTherapyType.UNSPECIFIED) + InsulinTherapyType.selectable

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        Field("How long have you had diabetes?") {
            BoostDropdownField(
                selected = draft.yearsSinceDiagnosis.ifEmpty { null },
                placeholder = "Select duration",
                options = OnboardingValidation.yearsSinceDiagnosisOptions,
                optionLabel = { it },
                onSelect = { value -> viewModel.update { it.copy(yearsSinceDiagnosis = value) } },
            )
        }

        Field("Insulin therapy", isOptional = true) {
            BoostDropdownField(
                selected = draft.therapy,
                placeholder = "None",
                options = therapyOptions,
                optionLabel = { if (it == InsulinTherapyType.UNSPECIFIED) "None" else it.displayName },
                onSelect = { value -> viewModel.update { it.copy(therapy = value) } },
            )
        }

        if (draft.therapy != InsulinTherapyType.UNSPECIFIED) {
            Text(draft.therapy.detail, fontSize = 12.sp, color = colors.textSecondary)
        }
    }
}
