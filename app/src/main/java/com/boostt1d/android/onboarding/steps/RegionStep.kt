package com.boostt1d.android.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.onboarding.OnboardingDraft
import com.boostt1d.android.onboarding.OnboardingViewModel
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.CountryPickerDialog

@Composable
fun RegionStep(draft: OnboardingDraft, viewModel: OnboardingViewModel) {
    val colors = BoostTheme.colors
    var showingPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        Field("Country") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.md))
                    .border(1.dp, colors.border, RoundedCornerShape(BoostRadius.md))
                    .clickable { showingPicker = true }
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = draft.countryName.ifEmpty { "Select country" },
                    color = if (draft.countryName.isEmpty()) colors.textTertiary else colors.textPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                )
            }
        }

        Field("Glucose units") {
            BoostSegmented(
                options = BGUnit.entries.toList(),
                selected = draft.bgUnit,
                optionLabel = { it.displayName },
                onSelect = viewModel::selectUnit,
            )
        }

        Text(
            "Picked from your country. Change it if you read your numbers the other way.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
    }

    if (showingPicker) {
        CountryPickerDialog(
            onDismiss = { showingPicker = false },
            onSelect = { country ->
                viewModel.selectCountry(country.code, country.name)
                showingPicker = false
            },
        )
    }
}
