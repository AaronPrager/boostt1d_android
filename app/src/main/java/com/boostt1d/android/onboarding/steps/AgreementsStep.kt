package com.boostt1d.android.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.legal.LegalDocumentDialog
import com.boostt1d.android.legal.LegalText
import com.boostt1d.android.onboarding.OnboardingDraft
import com.boostt1d.android.onboarding.OnboardingViewModel
import com.boostt1d.android.ui.BoostConsentRow
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * The four things a person has to actively agree to, on one step.
 *
 * They were four consecutive screens once, which trains people to tap through
 * without reading. Each item is still acknowledged separately and each still links
 * to its full text.
 */
@Composable
fun AgreementsStep(draft: OnboardingDraft, viewModel: OnboardingViewModel) {
    val colors = BoostTheme.colors
    var document by remember { mutableStateOf<LegalText.Document?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.low.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                .padding(BoostSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.low,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "BoostT1D is not a medical device",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }
            Text(
                "It does not diagnose, treat or prescribe, and its AI estimates can be wrong. " +
                    "Use it alongside your care team, never instead of them. In an emergency " +
                    "call your local emergency number.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }

        BoostConsentRow(
            checked = draft.agreedAge,
            onCheckedChange = { value -> viewModel.update { it.copy(agreedAge = value) } },
            text = "I am 13 or older, or I have parental or guardian consent to use BoostT1D.",
        )

        BoostConsentRow(
            checked = draft.agreedDisclaimer,
            onCheckedChange = { value -> viewModel.update { it.copy(agreedDisclaimer = value) } },
            text = "I understand and acknowledge the medical disclaimer.",
            readLabel = "Read the medical disclaimer",
            onRead = { document = LegalText.medicalDisclaimer },
        )

        BoostConsentRow(
            checked = draft.agreedPrivacy,
            onCheckedChange = { value -> viewModel.update { it.copy(agreedPrivacy = value) } },
            text = "I have read and agree to the Privacy Policy.",
            readLabel = "Read the Privacy Policy",
            onRead = { document = LegalText.privacyPolicy },
        )

        BoostConsentRow(
            checked = draft.agreedTerms,
            onCheckedChange = { value -> viewModel.update { it.copy(agreedTerms = value) } },
            text = "I have read and agree to the Terms of Use.",
            readLabel = "Read the Terms of Use",
            onRead = { document = LegalText.termsOfUse },
        )

        BoostDivider()

        BoostConsentRow(
            checked = draft.marketingOptIn,
            onCheckedChange = { value -> viewModel.update { it.copy(marketingOptIn = value) } },
            text = "Optional: email me about new releases and important BoostT1D news.",
        )
    }

    document?.let { doc ->
        LegalDocumentDialog(doc) { document = null }
    }
}
