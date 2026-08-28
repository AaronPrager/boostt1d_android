package com.boostt1d.android.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boostt1d.android.onboarding.steps.AgreementsStep
import com.boostt1d.android.onboarding.steps.ConnectionStep
import com.boostt1d.android.onboarding.steps.DiabetesStep
import com.boostt1d.android.onboarding.steps.PersonalInfoStep
import com.boostt1d.android.onboarding.steps.PhotoStep
import com.boostt1d.android.onboarding.steps.RegionStep
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSoftIcon
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Setup, one full screen per step.
 *
 * Every step shares one header, one progress bar and one pair of buttons; the steps
 * themselves only supply fields, so no step can drift into its own layout, button
 * wording or stale "Step 1 of 4" counter.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val colors = BoostTheme.colors
    val step = viewModel.step
    val draft = viewModel.draft

    LaunchedEffect(viewModel.isComplete) {
        if (viewModel.isComplete) onFinished()
    }

    viewModel.problem?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::consumeProblem,
            confirmButton = { TextButton(onClick = viewModel::consumeProblem) { Text("OK") } },
            title = { Text("Check this step") },
            text = { Text(message) },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
        )
    }

    Box(
        // Surface, not the page background: the fields are muted grey and would
        // disappear against the grey backdrop the rest of the app uses.
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Readable column on a tablet while the background still runs to every edge.
                .widthIn(max = 560.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TopBar()
            Header(step)
            Progress(step)
            BoostDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
            ) {
                when (step) {
                    OnboardingStep.PERSONAL_INFO -> PersonalInfoStep(draft, viewModel)
                    OnboardingStep.DIABETES -> DiabetesStep(draft, viewModel)
                    OnboardingStep.PHOTO -> PhotoStep(draft, viewModel)
                    OnboardingStep.REGION -> RegionStep(draft, viewModel)
                    OnboardingStep.CONNECTION -> ConnectionStep(draft, viewModel)
                    OnboardingStep.AGREEMENTS -> AgreementsStep(draft, viewModel)
                }
            }

            BoostDivider()
            Footer(
                step = step,
                canContinue = OnboardingValidation.canContinue(step, draft),
                onBack = if (step.previous != null) viewModel::back else null,
                onContinue = viewModel::advance,
            )
        }
    }
}

@Composable
private fun TopBar() {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = BoostSpacing.lg,
                end = BoostSpacing.lg,
                top = BoostSpacing.xs,
                bottom = BoostSpacing.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SET UP BOOSTT1D",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun Header(step: OnboardingStep) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = BoostSpacing.lg,
                end = BoostSpacing.lg,
                top = BoostSpacing.xs,
                bottom = BoostSpacing.md,
            ),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        BoostSoftIcon(icon = step.icon(), tint = colors.primary)

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(step.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text(step.subtitle, fontSize = 15.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun Progress(step: OnboardingStep) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoostSpacing.lg)
            .padding(bottom = BoostSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OnboardingStep.entries.forEach { candidate ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (candidate.ordinal <= step.ordinal) colors.primary else colors.border,
                            CircleShape,
                        ),
                )
            }
        }
        Text(
            "Step ${step.ordinal + 1} of ${OnboardingStep.entries.size}",
            fontSize = 11.sp,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun Footer(
    step: OnboardingStep,
    canContinue: Boolean,
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        if (onBack != null) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(BoostRadius.md),
                modifier = Modifier.widthIn(max = 120.dp).height(52.dp),
            ) {
                Text("Back", color = colors.textPrimary)
            }
        }

        Button(
            onClick = onContinue,
            enabled = canContinue,
            shape = RoundedCornerShape(BoostRadius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                disabledContainerColor = colors.neutral,
                disabledContentColor = androidx.compose.ui.graphics.Color.White,
            ),
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Text(
                if (step.isLast) "Finish setup" else "Continue",
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (step.isLast) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(start = BoostSpacing.xs),
            )
        }
    }
}

/**
 * The icon for a step.
 *
 * Deliberately not a property of [OnboardingStep]: an ImageVector on the enum would
 * drag Compose into every test that wants to check a validation rule.
 */
private fun OnboardingStep.icon(): ImageVector = when (this) {
    OnboardingStep.PERSONAL_INFO -> Icons.Filled.Person
    OnboardingStep.DIABETES -> Icons.Filled.Vaccines
    OnboardingStep.PHOTO -> Icons.Filled.CameraAlt
    OnboardingStep.REGION -> Icons.Filled.Public
    OnboardingStep.CONNECTION -> Icons.Filled.Wifi
    OnboardingStep.AGREEMENTS -> Icons.Filled.Verified
}
