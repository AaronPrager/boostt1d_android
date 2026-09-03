package com.boostt1d.android.food

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.FoodAnalysis
import com.boostt1d.android.data.FoodLogEntryEntity
import com.boostt1d.android.data.FoodLogRepository
import com.boostt1d.android.data.FoodLogSource
import com.boostt1d.android.insights.BoostNotice
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The shared food-entry editor: photo-analysis saves, manual adds, Event Log carbs and edits —
 * the same photo / nutrition / insulin space everywhere. Ported from ManualFoodLogEntrySheet.
 */
@Composable
fun FoodEntrySheet(
    foodLog: FoodLogRepository,
    entryId: String?,
    analysis: FoodAnalysis?,
    analysisImage: Bitmap?,
    nowMillis: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var existing by remember { mutableStateOf<FoodLogEntryEntity?>(null) }
    var loaded by remember { mutableStateOf(entryId == null) }
    var descriptionText by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var caloriesText by remember { mutableStateOf("") }
    var proteinText by remember { mutableStateOf("") }
    var fatText by remember { mutableStateOf("") }
    var fiberText by remember { mutableStateOf("") }
    var insulinText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var recordedAt by remember { mutableStateOf(nowMillis) }
    var confidence by remember { mutableStateOf<String?>(null) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var removedExistingThumbnail by remember { mutableStateOf(false) }
    var showingPhotoChoice by remember { mutableStateOf(false) }
    var showingDelete by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Prefill once: from the entry being edited, or from the analysis being saved.
    LaunchedEffect(entryId) {
        if (entryId != null) {
            val entry = foodLog.entry(entryId)
            existing = entry
            if (entry != null) {
                descriptionText = entry.descriptionText
                carbsText = formatOptional(entry.carbsGrams); caloriesText = formatOptional(entry.caloriesKcal)
                proteinText = formatOptional(entry.proteinGrams); fatText = formatOptional(entry.fatGrams)
                fiberText = formatOptional(entry.fiberGrams); insulinText = formatOptional(entry.insulinUnits)
                notes = entry.notes ?: ""; recordedAt = entry.recordedAtMillis; confidence = entry.confidence
            }
            loaded = true
        } else if (analysis != null) {
            descriptionText = analysis.descriptionText
            carbsText = formatOptional(analysis.carbsGrams); caloriesText = formatOptional(analysis.caloriesKcal)
            proteinText = formatOptional(analysis.proteinGrams); fatText = formatOptional(analysis.fatGrams)
            fiberText = formatOptional(analysis.fiberGrams); confidence = analysis.confidence
            selectedImage = analysisImage
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { FoodImage.decode(context, it) }?.let { selectedImage = it; removedExistingThumbnail = false }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { FoodImage.decode(context, it) }?.let { selectedImage = it; removedExistingThumbnail = false }
    }

    val isEditing = entryId != null
    val title = when { isEditing -> "Edit Food Entry"; analysis != null -> "Save to Food Log"; else -> "Add Food Entry" }
    val canSave = descriptionText.trim().isNotEmpty()
    val existingThumbnail = existing?.thumbnailJpeg?.takeIf { !removedExistingThumbnail }
    val displayImage = selectedImage ?: existingThumbnail?.let { remember(it.size) { FoodImage.decode(it) } }

    fun save() {
        if (!canSave) return
        val description = descriptionText.trim()
        val notesOrNull = notes.trim().takeIf { it.isNotEmpty() }
        val thumbnailFromSelection = selectedImage?.let { FoodImage.thumbnailJpeg(it) }
        scope.launch {
            try {
                val target = existing
                if (isEditing && target != null) {
                    val updateThumbnail = selectedImage != null || removedExistingThumbnail
                    foodLog.update(
                        id = target.id, descriptionText = description, carbsGrams = parse(carbsText), caloriesKcal = parse(caloriesText),
                        proteinGrams = parse(proteinText), fatGrams = parse(fatText), fiberGrams = parse(fiberText), insulinUnits = parse(insulinText),
                        recordedAtMillis = recordedAt, notes = notesOrNull,
                        thumbnailJpeg = thumbnailFromSelection ?: if (removedExistingThumbnail) null else target.thumbnailJpeg,
                        updateThumbnail = updateThumbnail,
                    )
                } else {
                    foodLog.saveEntry(
                        descriptionText = description, carbsGrams = parse(carbsText), caloriesKcal = parse(caloriesText), fatGrams = parse(fatText),
                        proteinGrams = parse(proteinText), fiberGrams = parse(fiberText), confidence = confidence, recordedAtMillis = recordedAt,
                        notes = notesOrNull, thumbnailJpeg = thumbnailFromSelection,
                        source = if (analysis != null) FoodLogSource.PHOTO_ANALYSIS else FoodLogSource.MANUAL, insulinUnits = parse(insulinText),
                    )
                }
                onDone()
            } catch (e: Exception) {
                saveError = e.message ?: "Could not save this entry."
            }
        }
    }

    ScreenScaffold(title = null, subtitle = null, modifier = modifier) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xs)) {
                TextButton(onClick = onDone) { Text("Cancel", color = colors.textSecondary) }
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton(onClick = { save() }, enabled = canSave && loaded) { Text("Save", fontWeight = FontWeight.SemiBold, color = if (canSave) colors.primary else colors.textTertiary) }
            }
        }

        // Photo
        item {
            BoostCard {
                CardHeader(Icons.Filled.CameraAlt, colors.food, "Photo")
                if (displayImage != null) {
                    Image(displayImage.asImageBitmap(), contentDescription = "Meal photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(BoostRadius.lg)).border(1.dp, colors.border, RoundedCornerShape(BoostRadius.lg)))
                    Text("Change photo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.primary, modifier = Modifier.clickable { showingPhotoChoice = true })
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(110.dp).background(colors.food.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.lg))
                            .border(1.dp, colors.food.copy(alpha = 0.35f), RoundedCornerShape(BoostRadius.lg)).clickable { showingPhotoChoice = true },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AddAPhoto, contentDescription = null, tint = colors.food, modifier = Modifier.size(20.dp))
                        Text("  Add photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.food)
                    }
                }
            }
        }

        // Meal details
        item {
            BoostCard {
                CardHeader(Icons.Filled.Restaurant, colors.food, "Meal details")
                LabeledField("Description", "e.g. Chicken salad with rice", descriptionText) { descriptionText = it }
                MacroField("Carbs", carbsText, "g", Icons.Filled.Eco) { carbsText = it }
                Text("Date & time: ${Fmt.dayTime(recordedAt)}", fontSize = 14.sp, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    listOf(0 to "Now", 30 to "30 min ago", 60 to "1 hr ago", 120 to "2 hr ago").forEach { (minutes, label) ->
                        val selected = recordedAt == nowMillis - minutes * 60_000L
                        Text(
                            label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.primary else colors.textSecondary,
                            modifier = Modifier.background(if (selected) colors.primary.copy(alpha = 0.10f) else colors.surfaceMuted, RoundedCornerShape(BoostRadius.pillLike))
                                .clickable { recordedAt = nowMillis - minutes * 60_000L }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                LabeledField("Notes (optional)", "Anything worth remembering", notes) { notes = it }
            }
        }

        // Nutrition
        item {
            BoostCard {
                CardHeader(Icons.Filled.PieChart, colors.carbs, "Nutrition")
                MacroField("Calories", caloriesText, "kcal", Icons.Filled.LocalFireDepartment) { caloriesText = it }
                MacroField("Protein", proteinText, "g", Icons.Filled.Egg) { proteinText = it }
                MacroField("Fat", fatText, "g", Icons.Filled.WaterDrop) { fatText = it }
                MacroField("Fiber", fiberText, "g", Icons.Filled.Eco) { fiberText = it }
                confidence?.takeIf { it.isNotEmpty() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                        Text("Confidence", fontSize = 14.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    }
                }
            }
        }

        // Insulin
        item {
            BoostCard {
                CardHeader(Icons.Filled.Vaccines, colors.insulin, "Insulin")
                MacroField("Bolus given", insulinText, "U", Icons.Filled.Vaccines) { insulinText = it }
                Text("How much insulin you actually gave for this meal, saved with the entry.", fontSize = 13.sp, color = colors.textTertiary)
            }
        }

        if (isEditing) {
            item {
                Button(
                    onClick = { showingDelete = true }, shape = RoundedCornerShape(BoostRadius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.low.copy(alpha = 0.12f), contentColor = colors.low),
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp)); Text("  Delete Entry") }
            }
            existing?.let { entry ->
                item { Text(entry.foodLogSource.displayName, fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
        }

        saveError?.let { item { BoostNotice(it, Icons.Filled.Delete, colors.low) } }
    }

    if (showingPhotoChoice) {
        AlertDialog(
            onDismissRequest = { showingPhotoChoice = false },
            containerColor = colors.surface,
            title = { Text("Select Photo", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showingPhotoChoice = false; cameraUri = FoodCapture.newUri(context); cameraUri?.let { takePhoto.launch(it) } }) { Text("Camera", color = colors.primary) }
                    TextButton(onClick = { showingPhotoChoice = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photo Library", color = colors.primary) }
                    if (displayImage != null) TextButton(onClick = { showingPhotoChoice = false; selectedImage = null; removedExistingThumbnail = true }) { Text("Remove Photo", color = colors.low) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showingPhotoChoice = false }) { Text("Cancel", color = colors.textSecondary) } },
        )
    }

    if (showingDelete) {
        AlertDialog(
            onDismissRequest = { showingDelete = false },
            containerColor = colors.surface,
            title = { Text("Delete Food Entry", color = colors.textPrimary) },
            text = { Text("This removes the entry from your Food Log on this device. This can’t be undone.", color = colors.textSecondary) },
            confirmButton = {
                Button(onClick = { showingDelete = false; scope.launch { entryId?.let { foodLog.delete(it) }; onDone() } }, shape = RoundedCornerShape(BoostRadius.md), colors = ButtonDefaults.buttonColors(containerColor = colors.low)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showingDelete = false }) { Text("Cancel", color = colors.textSecondary) } },
        )
    }
}

@Composable
private fun CardHeader(icon: ImageVector, color: Color, title: String) {
    val colors = BoostTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Box(modifier = Modifier.size(32.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(BoostRadius.md)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    }
}

@Composable
private fun LabeledField(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    val colors = BoostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        OutlinedTextField(value = value, onValueChange = onChange, placeholder = { Text(placeholder, color = colors.textTertiary) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)
    }
}

@Composable
private fun MacroField(label: String, value: String, unit: String, icon: ImageVector, onChange: (String) -> Unit) {
    val colors = BoostTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
        Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 15.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value, onValueChange = onChange, placeholder = { Text("0", color = colors.textTertiary) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.width(96.dp),
        )
        Text(unit, fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.width(32.dp))
    }
}

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private fun formatOptional(value: Double?): String {
    if (value == null) return ""
    return if (value % 1.0 == 0.0) String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.1f", value)
}
