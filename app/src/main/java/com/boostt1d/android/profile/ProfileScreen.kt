package com.boostt1d.android.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.AgeSelectionOptions
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.Countries
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.PhotoScaling
import com.boostt1d.android.data.UserProfile
import com.boostt1d.android.onboarding.OnboardingValidation
import com.boostt1d.android.ui.BoostConsentRow
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostDropdownField
import com.boostt1d.android.ui.BoostFieldLabel
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.CountryPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Everything setup collected, editable afterwards.
 *
 * The same validation gates saving that gated Continue during onboarding — a profile
 * cannot be edited into a state setup would have refused.
 */
@Composable
fun ProfileScreen(
    profile: UserProfile,
    settings: GlucoseSettings,
    onSave: (UserProfile, GlucoseSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(profile.name) }
    var age by remember { mutableStateOf(currentAge(profile).toString()) }
    var gender by remember { mutableStateOf(profile.gender ?: "") }
    var email by remember { mutableStateOf(profile.email ?: "") }
    var parentName by remember { mutableStateOf(profile.parentName ?: "") }
    var parentEmail by remember { mutableStateOf(profile.parentEmail ?: "") }
    var years by remember { mutableStateOf(profile.yearsSinceDiagnosisBucket) }
    var therapy by remember { mutableStateOf(profile.therapy) }
    var countryCode by remember { mutableStateOf(profile.countryCode) }
    var countryName by remember { mutableStateOf(profile.country) }
    var unit by remember { mutableStateOf(profile.bgUnit) }
    var marketingOptIn by remember { mutableStateOf(profile.marketingOptIn) }
    var photoBase64 by remember { mutableStateOf(profile.photoData) }
    var lowMgdl by remember { mutableStateOf(settings.lowGlucose) }
    var highMgdl by remember { mutableStateOf(settings.highGlucose) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showingCountryPicker by remember { mutableStateOf(false) }

    val ageValue = age.toIntOrNull()
    val needsParentGuardian = ageValue != null && AgeSelectionOptions.requiresParentGuardian(ageValue)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) { PhotoScaling.encodeAvatar(context, uri) }
                if (encoded != null) photoBase64 = encoded
            }
        }
    }

    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
            title = { Text("Check your profile") },
            text = { Text(message) },
            containerColor = colors.surface,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // No back arrow: every other destination in the shell is reached and left the
        // same way, through the bottom bar, and one screen with its own affordance reads
        // as a screen that works differently.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.md),
        ) {
            Text("Profile", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text("Units, range and account", fontSize = 14.sp, color = colors.textSecondary)
        }

        BoostDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
        ) {
            PhotoRow(
                photoBase64 = photoBase64,
                onPick = {
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onClear = { photoBase64 = null },
            )

            LabelledField("Your name") {
                BoostTextField(name, { name = it }, "Enter your name")
            }

            LabelledField("Your age") {
                BoostDropdownField(
                    selected = ageValue,
                    placeholder = "Select age",
                    options = AgeSelectionOptions.ages,
                    optionLabel = { it.toString() },
                    onSelect = { selected ->
                        age = selected.toString()
                        if (!AgeSelectionOptions.requiresParentGuardian(selected)) {
                            parentName = ""
                            parentEmail = ""
                        }
                    },
                )
            }

            LabelledField("Gender") {
                BoostDropdownField(
                    selected = gender.ifEmpty { null },
                    placeholder = "Select gender",
                    options = OnboardingValidation.genderOptions,
                    optionLabel = { it },
                    onSelect = { gender = it },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                BoostFieldLabel("Your email", isOptional = needsParentGuardian)
                BoostTextField(email, { email = it }, "name@example.com", keyboardType = KeyboardType.Email)
            }

            if (needsParentGuardian) {
                LabelledField("Parent or guardian name") {
                    BoostTextField(parentName, { parentName = it }, "Enter parent or guardian name")
                }
                LabelledField("Parent or guardian email") {
                    BoostTextField(
                        parentEmail,
                        { parentEmail = it },
                        "parent@example.com",
                        keyboardType = KeyboardType.Email,
                    )
                }
            }

            BoostDivider()

            LabelledField("How long have you had diabetes?") {
                BoostDropdownField(
                    selected = years.ifEmpty { null },
                    placeholder = "Select duration",
                    options = OnboardingValidation.yearsSinceDiagnosisOptions,
                    optionLabel = { it },
                    onSelect = { years = it },
                )
            }

            LabelledField("Insulin therapy", isOptional = true) {
                BoostDropdownField(
                    selected = therapy,
                    placeholder = "None",
                    options = listOf(InsulinTherapyType.UNSPECIFIED) + InsulinTherapyType.selectable,
                    optionLabel = {
                        if (it == InsulinTherapyType.UNSPECIFIED) "None" else it.displayName
                    },
                    onSelect = { therapy = it },
                )
            }

            BoostDivider()

            LabelledField("Country") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.md))
                        .border(1.dp, colors.border, RoundedCornerShape(BoostRadius.md))
                        .clickable { showingCountryPicker = true }
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                ) {
                    Text(
                        countryName.ifEmpty { "Select country" },
                        color = if (countryName.isEmpty()) colors.textTertiary else colors.textPrimary,
                        fontSize = 15.sp,
                    )
                }
            }

            LabelledField("Glucose units") {
                BoostSegmented(
                    options = BGUnit.entries.toList(),
                    selected = unit,
                    optionLabel = { it.displayName },
                    onSelect = { unit = it },
                )
            }

            TargetRangeFields(
                unit = unit,
                lowMgdl = lowMgdl,
                highMgdl = highMgdl,
                onLowChange = { lowMgdl = it },
                onHighChange = { highMgdl = it },
            )

            BoostDivider()

            BoostConsentRow(
                checked = marketingOptIn,
                onCheckedChange = { marketingOptIn = it },
                text = "Email me about new releases and important BoostT1D news.",
            )

            Text(
                "Data source: Manual. Connecting Nightscout, Dexcom or Libre is coming later.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }

        BoostDivider()

        Box(modifier = Modifier.padding(start = BoostSpacing.md, end = BoostSpacing.md, top = BoostSpacing.md, bottom = 84.dp)) {
            Button(
                onClick = {
                    val validationProblem = validate(
                        name = name,
                        ageValue = ageValue,
                        email = email,
                        needsParentGuardian = needsParentGuardian,
                        parentName = parentName,
                        parentEmail = parentEmail,
                        years = years,
                        countryCode = countryCode,
                        lowMgdl = lowMgdl,
                        highMgdl = highMgdl,
                        unit = unit,
                    )
                    if (validationProblem != null) {
                        problem = validationProblem
                        return@Button
                    }

                    val hasDiabetes = years != OnboardingValidation.NO_DIABETES_OPTION
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)

                    onSave(
                        profile.copy(
                            name = name.trim(),
                            photoData = photoBase64,
                            country = countryName,
                            countryCode = countryCode,
                            dateOfBirthEpochMillis = januaryFirst(currentYear - (ageValue ?: 0)),
                            dateOfDiagnosisEpochMillis = if (hasDiabetes) {
                                januaryFirst(currentYear - OnboardingValidation.yearsValue(years))
                            } else {
                                System.currentTimeMillis()
                            },
                            hasDiabetes = hasDiabetes,
                            bgUnit = unit,
                            parentName = parentName.trim().ifEmpty { null },
                            parentEmail = parentEmail.trim().ifEmpty { null },
                            email = email.trim().ifEmpty { null },
                            gender = gender.ifEmpty { null },
                            marketingOptIn = marketingOptIn,
                            therapy = therapy,
                            yearsSinceDiagnosisBucket = years,
                        ),
                        settings.copy(lowGlucose = lowMgdl, highGlucose = highMgdl),
                    )
                    onBack()
                },
                shape = RoundedCornerShape(BoostRadius.md),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Save changes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showingCountryPicker) {
        CountryPickerDialog(
            onDismiss = { showingCountryPicker = false },
            onSelect = { country ->
                countryCode = country.code
                countryName = country.name
                showingCountryPicker = false
            },
        )
    }
}

@Composable
private fun LabelledField(
    label: String,
    isOptional: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        BoostFieldLabel(label, isOptional = isOptional)
        content()
    }
}

@Composable
private fun PhotoRow(photoBase64: String?, onPick: () -> Unit, onClear: () -> Unit) {
    val colors = BoostTheme.colors
    val bitmap = remember(photoBase64) { PhotoScaling.decodeAvatar(photoBase64) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(colors.surfaceMuted)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Your photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (bitmap == null) "Add a photo" else "Change photo",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                modifier = Modifier.clickable(onClick = onPick),
            )
            if (bitmap != null) {
                Text(
                    "Remove",
                    fontSize = 13.sp,
                    color = colors.low,
                    modifier = Modifier.clickable(onClick = onClear),
                )
            }
        }
    }
}

@Composable
private fun TargetRangeFields(
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    onLowChange: (Double) -> Unit,
    onHighChange: (Double) -> Unit,
) {
    val colors = BoostTheme.colors
    var lowText by remember { mutableStateOf("") }
    var highText by remember { mutableStateOf("") }

    // Seeded from storage and re-seeded on a unit change only — not on every
    // keystroke, which would fight the user mid-entry.
    LaunchedEffect(unit) {
        lowText = GlucoseDisplay.format(lowMgdl, unit)
        highText = GlucoseDisplay.format(highMgdl, unit)
    }

    Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        BoostFieldLabel("Target range (${unit.displayName})")
        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Low", fontSize = 12.sp, color = colors.textSecondary)
                BoostTextField(
                    value = lowText,
                    onValueChange = { text ->
                        lowText = text
                        text.toDoubleOrNull()?.let { onLowChange(GlucoseDisplay.toMgdL(it, unit)) }
                    },
                    placeholder = GlucoseDisplay.format(70.0, unit),
                    keyboardType = KeyboardType.Decimal,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("High", fontSize = 12.sp, color = colors.textSecondary)
                BoostTextField(
                    value = highText,
                    onValueChange = { text ->
                        highText = text
                        text.toDoubleOrNull()?.let { onHighChange(GlucoseDisplay.toMgdL(it, unit)) }
                    },
                    placeholder = GlucoseDisplay.format(180.0, unit),
                    keyboardType = KeyboardType.Decimal,
                )
            }
        }
    }
}

/** The onboarding rules, applied again on edit. */
private fun validate(
    name: String,
    ageValue: Int?,
    email: String,
    needsParentGuardian: Boolean,
    parentName: String,
    parentEmail: String,
    years: String,
    countryCode: String,
    lowMgdl: Double,
    highMgdl: Double,
    unit: BGUnit,
): String? {
    if (name.isBlank()) return "Please enter your name."
    if (ageValue == null || ageValue !in AgeSelectionOptions.ages) return "Please select your age."

    if (needsParentGuardian) {
        if (email.trim().isNotEmpty() && !OnboardingValidation.isValidEmail(email.trim())) {
            return "Please enter a valid email address."
        }
        if (parentName.isBlank()) {
            return "Parent or guardian name is required if the account holder is under 13."
        }
        if (!OnboardingValidation.isValidEmail(parentEmail.trim())) {
            return "Please enter a valid parent or guardian email."
        }
    } else {
        if (email.trim().isEmpty()) return "Please enter your email address."
        if (!OnboardingValidation.isValidEmail(email.trim())) return "Please enter a valid email address."
    }

    if (years.isEmpty()) return "Please select how long you've had diabetes."
    if (years != OnboardingValidation.NO_DIABETES_OPTION &&
        OnboardingValidation.yearsValue(years) > ageValue
    ) {
        return "Years with diabetes cannot be more than your age. Please check your information."
    }

    if (countryCode.isEmpty()) return "Please select your country."

    val label = unit.displayName
    if (lowMgdl < 60 || lowMgdl > 110) {
        return "Low glucose must be between ${GlucoseDisplay.format(60.0, unit)} and " +
            "${GlucoseDisplay.format(110.0, unit)} $label."
    }
    if (highMgdl < 110 || highMgdl > 200) {
        return "High glucose must be between ${GlucoseDisplay.format(110.0, unit)} and " +
            "${GlucoseDisplay.format(200.0, unit)} $label."
    }
    if (lowMgdl >= highMgdl) return "Low glucose must be less than high glucose."

    return null
}

private fun currentAge(profile: UserProfile): Int {
    if (profile.dateOfBirthEpochMillis <= 0L) return AgeSelectionOptions.DEFAULT_AGE
    val birth = Calendar.getInstance().apply { timeInMillis = profile.dateOfBirthEpochMillis }
    val now = Calendar.getInstance()
    var years = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
    if (now.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) years -= 1
    return years.coerceIn(AgeSelectionOptions.MINIMUM_AGE, AgeSelectionOptions.MAXIMUM_AGE)
}

private fun januaryFirst(year: Int): Long = Calendar.getInstance().apply {
    clear()
    set(year, Calendar.JANUARY, 1)
}.timeInMillis
