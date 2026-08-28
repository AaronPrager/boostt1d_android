package com.boostt1d.android.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.AgeSelectionOptions
import com.boostt1d.android.onboarding.OnboardingDraft
import com.boostt1d.android.onboarding.OnboardingValidation
import com.boostt1d.android.onboarding.OnboardingViewModel
import com.boostt1d.android.ui.BoostDropdownField
import com.boostt1d.android.ui.BoostFieldLabel
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme

@Composable
fun PersonalInfoStep(draft: OnboardingDraft, viewModel: OnboardingViewModel) {
    val colors = BoostTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        Field("Your name") {
            BoostTextField(
                value = draft.name,
                onValueChange = { name -> viewModel.update { it.copy(name = name) } },
                placeholder = "Enter your name",
            )
        }

        Field("Your age") {
            BoostDropdownField(
                selected = draft.ageValue,
                placeholder = "Select age",
                options = AgeSelectionOptions.ages,
                optionLabel = { it.toString() },
                onSelect = { viewModel.selectAge(it.toString()) },
            )
        }

        Field("Gender") {
            BoostDropdownField(
                selected = draft.gender.ifEmpty { null },
                placeholder = "Select gender",
                options = OnboardingValidation.genderOptions,
                optionLabel = { it },
                onSelect = { gender -> viewModel.update { it.copy(gender = gender) } },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
            BoostFieldLabel("Your email", isOptional = draft.needsParentGuardian)
            BoostTextField(
                value = draft.email,
                onValueChange = { email -> viewModel.update { it.copy(email = email) } },
                placeholder = "name@example.com",
                keyboardType = KeyboardType.Email,
            )
        }

        if (draft.needsParentGuardian) {
            Field("Parent or guardian name") {
                BoostTextField(
                    value = draft.parentName,
                    onValueChange = { value -> viewModel.update { it.copy(parentName = value) } },
                    placeholder = "Enter parent or guardian name",
                )
            }

            Field("Parent or guardian email") {
                BoostTextField(
                    value = draft.parentEmail,
                    onValueChange = { value -> viewModel.update { it.copy(parentEmail = value) } },
                    placeholder = "parent@example.com",
                    keyboardType = KeyboardType.Email,
                )
            }

            Text(
                "Under 13, a parent or guardian has to be reachable. Their email is where " +
                    "account matters are sent.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
    }
}

/** Label plus field, at the spacing every step uses. */
@Composable
internal fun Field(label: String, isOptional: Boolean = false, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        BoostFieldLabel(label, isOptional = isOptional)
        content()
    }
}
