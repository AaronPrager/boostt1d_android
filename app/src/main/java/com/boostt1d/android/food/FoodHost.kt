package com.boostt1d.android.food

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.FoodAnalysis
import com.boostt1d.android.data.FoodLogRepository
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.data.LogState
import com.boostt1d.android.data.OnBoard
import com.boostt1d.android.data.UsageTracker
import com.boostt1d.android.sync.BoostBackend

enum class FoodStart { LOG, SNAP }

private sealed interface FoodRoute {
    data object Log : FoodRoute
    data object Snap : FoodRoute
    data class Edit(val entryId: String?) : FoodRoute
    data class SaveAnalysis(val analysis: FoodAnalysis, val image: Bitmap?) : FoodRoute
}

/**
 * The food screens: the diary, the camera, and the editor they both open. Flat, like the shell:
 * a detail replaces the list and Back returns to where it was opened from.
 */
@Composable
fun FoodHost(
    start: FoodStart,
    foodLog: FoodLogRepository,
    backend: BoostBackend,
    usage: UsageTracker,
    logs: LogState,
    onBoard: OnBoard,
    connection: GlucoseConnectionOption,
    unit: BGUnit,
    lowMgdl: Double,
    highMgdl: Double,
    nowMillis: Long,
    onImportTreatments: () -> Unit,
    /**
     * Snap is its own destination in the bar now, so the Food Log's own button hands the
     * navigation back to the shell. Switching route in here instead would leave the bar
     * highlighting Logs while the camera was on screen.
     */
    onOpenSnap: () -> Unit,
    onOpenTherapyProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home: FoodRoute = if (start == FoodStart.SNAP) FoodRoute.Snap else FoodRoute.Log
    var route by remember(start) { mutableStateOf(home) }

    BackHandler(enabled = route != home) {
        route = when (route) {
            is FoodRoute.SaveAnalysis -> FoodRoute.Snap
            else -> home
        }
    }

    when (val current = route) {
        FoodRoute.Log -> FoodLogScreen(
            foodLog = foodLog, nowMillis = nowMillis,
            onOpenEntry = { route = FoodRoute.Edit(it) }, onAdd = { route = FoodRoute.Edit(null) }, onSnap = onOpenSnap,
            onImportTreatments = onImportTreatments, modifier = modifier,
        )
        FoodRoute.Snap -> FoodAnalysisScreen(
            backend = backend, usage = usage, logs = logs, onBoard = onBoard, connection = connection, unit = unit,
            lowMgdl = lowMgdl, highMgdl = highMgdl, nowMillis = nowMillis,
            onSaveToFoodLog = { analysis, image -> route = FoodRoute.SaveAnalysis(analysis, image) },
            onOpenTherapyProfile = onOpenTherapyProfile, modifier = modifier,
        )
        is FoodRoute.Edit -> FoodEntrySheet(foodLog, current.entryId, null, null, nowMillis, onDone = { route = FoodRoute.Log }, modifier = modifier)
        is FoodRoute.SaveAnalysis -> FoodEntrySheet(foodLog, null, current.analysis, current.image, nowMillis, onDone = { route = FoodRoute.Log }, modifier = modifier)
    }
}
