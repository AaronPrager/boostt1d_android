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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.bolus.BolusPrefill
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.Config
import com.boostt1d.android.data.FoodAnalysis
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.data.UsageTracker
import com.boostt1d.android.insights.formatBg
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.sync.BoostBackend
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import com.boostt1d.android.ui.Fmt
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Snap a Meal: a photo becomes a nutrition estimate, then the inputs a dose would be built
 * from — read-only, because the dose itself is withheld under HIDE_DOSE_RECOMMENDATIONS.
 * Ported from the iOS FoodAnalysisView.
 */
@Composable
fun FoodAnalysisScreen(
    backend: BoostBackend,
    usage: UsageTracker,
    logs: LogState,
    onBoard: OnBoard,
    connection: GlucoseConnectionOption,
    unit: BGUnit,
    nowMillis: Long,
    onSaveToFoodLog: (FoodAnalysis, Bitmap?) -> Unit,
    onOpenBolusCalculator: (BolusPrefill) -> Unit,
    onOpenTherapyProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FoodAnalysis?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showingPhotoChoice by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(usage.remaining(nowMillis)) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { FoodImage.decode(context, it) }?.let { selectedImage = it; result = null; errorMessage = null }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { FoodImage.decode(context, it) }?.let { selectedImage = it; result = null; errorMessage = null }
    }

    fun analyze() {
        val image = selectedImage ?: return
        isAnalyzing = true; errorMessage = null; result = null
        scope.launch {
            try {
                val jpeg = FoodImage.jpegForUpload(image) ?: throw IllegalStateException("Failed to process the image. Please try again.")
                result = backend.analyzeFood(jpeg, System.currentTimeMillis())
                remaining = usage.remaining(System.currentTimeMillis())
            } catch (e: Exception) {
                errorMessage = e.message ?: "An unknown error occurred. Please try again."
            } finally {
                isAnalyzing = false
            }
        }
    }

    ScreenScaffold(title = "Snap a Meal", subtitle = "A photo becomes a carb estimate", modifier = modifier) {
        if (Config.LIMIT_FOOD_ANALYSIS) {
            item {
                val ok = remaining > 0
                Column(
                    modifier = Modifier.fillMaxWidth().background((if (ok) colors.primary else colors.high).copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.lg)).padding(BoostSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (ok) Icons.Filled.CardGiftcard else Icons.Filled.Warning, contentDescription = null, tint = if (ok) colors.primary else colors.high, modifier = Modifier.size(18.dp))
                        Text(
                            if (ok) "$remaining free estimation${if (remaining == 1) "" else "s"} remaining today" else "Daily limit reached",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
                        )
                    }
                    Text(if (ok) "Resets daily at midnight." else "Daily limit reached. Resets at midnight.", fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.md)) {
                val image = selectedImage
                if (image != null) {
                    Image(image.asImageBitmap(), contentDescription = "Selected meal photo", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(BoostRadius.lg)).border(1.dp, colors.neutral, RoundedCornerShape(BoostRadius.lg)))
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(200.dp).background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.lg)).clickable { showingPhotoChoice = true },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = colors.neutral, modifier = Modifier.size(40.dp))
                        Text("Tap to select a photo", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.neutral, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showingPhotoChoice = true }, shape = RoundedCornerShape(BoostRadius.md), colors = ButtonDefaults.buttonColors(containerColor = colors.inRange), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp)); Text("  Select Photo", maxLines = 1)
                    }
                    if (selectedImage != null) {
                        Button(onClick = { analyze() }, enabled = !isAnalyzing, shape = RoundedCornerShape(BoostRadius.md), colors = ButtonDefaults.buttonColors(containerColor = colors.primary, disabledContainerColor = colors.neutral), modifier = Modifier.weight(1f)) {
                            if (isAnalyzing) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp)) else Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(if (isAnalyzing) "  Analyzing…" else "  Analyze Food", maxLines = 1)
                        }
                    }
                }
            }
        }

        result?.let { analysis ->
            item { Text("Analysis Results", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary) }
            item {
                BoostCard {
                    // Macros lead the results card as tinted tiles; carbs on their own row since they drive the dosing math.
                    MacroStat("Carbs", grams(analysis.carbsGrams), colors.primary, emphasized = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MacroStat("Calories", analysis.caloriesKcal?.let { "${it.toInt()} cal" } ?: "—", colors.insight, modifier = Modifier.weight(1f))
                        MacroStat("Protein", grams(analysis.proteinGrams), colors.food, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MacroStat("Fat", grams(analysis.fatGrams), colors.insulin, modifier = Modifier.weight(1f))
                        MacroStat("Fiber", grams(analysis.fiberGrams), colors.inRange, modifier = Modifier.weight(1f))
                    }
                    LabelValue("Description", analysis.descriptionText)
                    LabelValue("Confidence", analysis.confidenceLevel, valueColor = confidenceColor(analysis.confidenceLevel))
                    if (analysis.notesText.isNotEmpty()) LabelValue("Notes", analysis.notesText)
                }
            }

            // Current Data: the inputs, read-only. No bolus figure — the flag exists to withhold it.
            val current = MealCurrentData.build(analysis.carbsValue, logs.latest?.toEntry(), onBoard, logs.therapy, connection == GlucoseConnectionOption.NIGHTSCOUT, nowMillis)
            item { Text("Current Data", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary) }
            item {
                BoostCard {
                    DataRow("Estimated Carbs:", "${String.format(Locale.US, "%.1f", current.carbsGrams)}g")
                    current.currentGlucoseMgdl?.let { DataRow("Current Glucose:", formatBg(it.toDouble(), unit)) }
                    current.targetGlucoseMgdl?.let { DataRow("Target Glucose:", formatBg(it.toDouble(), unit)) }
                    current.carbRatio?.let { DataRow("Carb Ratio:", "1:${String.format(Locale.US, "%.1f", it)} (at ${current.carbRatioTime})") }
                    current.insulinSensitivity?.let { DataRow("Correction Factor:", "1:${Fmt.glucose(it, unit)} ${unit.displayName}") }
                    DataRow("Active IOB:", if (current.iobDataStale) "Unknown" else "${String.format(Locale.US, "%.1f", current.iob)}u")
                    DataRow("Carbs on Board (COB):", if (current.iobDataStale) "Unknown" else "${String.format(Locale.US, "%.1f", current.cob)}g")
                }
            }
            current.setupMessage?.let { message ->
                item {
                    Column(modifier = Modifier.fillMaxWidth().background(colors.high.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.lg)).padding(BoostSpacing.md), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.high, modifier = Modifier.size(22.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Insulin Doses Needed", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.high)
                                Text(message, fontSize = 12.sp, color = colors.textSecondary)
                            }
                        }
                        TextButton(onClick = onOpenTherapyProfile, colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text("Enter Insulin Doses", fontWeight = FontWeight.Medium) }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onSaveToFoodLog(analysis, selectedImage) }, shape = RoundedCornerShape(BoostRadius.md), colors = ButtonDefaults.buttonColors(containerColor = colors.inRange), modifier = Modifier.weight(1f)) { Text("Save to Food Log", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    if (!current.needsProfile) {
                        Button(onClick = { onOpenBolusCalculator(BolusPrefill(analysis.carbsGrams ?: 0.0, current.currentGlucoseMgdl, current.iob, current.cob)) }, shape = RoundedCornerShape(BoostRadius.md), colors = ButtonDefaults.buttonColors(containerColor = colors.primary), modifier = Modifier.weight(1f)) { Text("Bolus Calculator", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    }
                }
            }

            if (current.iobDataStale) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = colors.high, modifier = Modifier.size(14.dp))
                        Text("No recent CGM connection — active insulin and carbs on board are unknown, so this calculation assumes 0 for both.", fontSize = 12.sp, color = colors.high)
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().background(colors.primary.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Important", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text(
                            "This information is for awareness and organization only. It is not a medical device and does not provide medical advice, diagnosis, or treatment recommendations. Always consult a qualified healthcare professional before making dosing decisions.",
                            fontSize = 11.sp, color = colors.textSecondary,
                        )
                    }
                }
            }
        }

        errorMessage?.let { message ->
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(BoostSpacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.high, modifier = Modifier.size(40.dp))
                    Text("Analysis Failed", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(message, fontSize = 15.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
                    TextButton(onClick = { errorMessage = null }) { Text("Try Again", color = colors.primary) }
                }
            }
        }
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
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showingPhotoChoice = false }) { Text("Cancel", color = colors.textSecondary) } },
        )
    }
}

@Composable
private fun MacroStat(label: String, value: String, color: Color, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md)).border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(BoostRadius.md)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label.uppercase(), fontSize = if (emphasized) 13.sp else 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = color)
        Text(value, fontSize = if (emphasized) 20.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun LabelValue(label: String, value: String, valueColor: Color? = null) {
    val colors = BoostTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label:", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
        Text(value, fontSize = 14.sp, color = valueColor ?: colors.textSecondary, fontWeight = if (valueColor != null) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    val colors = BoostTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
    }
}

@Composable
private fun confidenceColor(confidence: String): Color = when (confidence.lowercase()) {
    "high" -> BoostTheme.colors.inRange
    "medium" -> BoostTheme.colors.high
    "low" -> BoostTheme.colors.low
    else -> BoostTheme.colors.neutral
}

private fun grams(value: Double?): String = value?.let { "${String.format(Locale.US, "%.1f", it)}g" } ?: "—"
