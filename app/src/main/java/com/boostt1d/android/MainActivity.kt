package com.boostt1d.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boostt1d.android.bolus.BolusCalculatorScreen
import com.boostt1d.android.dashboard.DashboardScreen
import com.boostt1d.android.data.AppState
import com.boostt1d.android.data.AppViewModel
import com.boostt1d.android.home.AboutScreen
import com.boostt1d.android.home.HomeDestination
import com.boostt1d.android.home.HomeShell
import com.boostt1d.android.logs.AddEventDialog
import com.boostt1d.android.logs.AddReadingDialog
import com.boostt1d.android.logs.EventLogScreen
import com.boostt1d.android.logs.GlucoseLogScreen
import com.boostt1d.android.onboarding.OnboardingScreen
import com.boostt1d.android.profile.ProfileScreen
import com.boostt1d.android.sync.DataSourceScreen
import com.boostt1d.android.therapy.TherapyProfileScreen
import com.boostt1d.android.ui.BoostT1DTheme
import com.boostt1d.android.ui.BoostTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BoostT1DTheme {
                BoostRoot()
            }
        }
    }
}

/**
 * Setup, then the home shell.
 *
 * Routing is driven by the stored profile rather than by a navigation library: the
 * destinations are flat — selecting one replaces the screen rather than pushing onto a
 * stack — so a nav graph would be more machinery than there are screens.
 */
@Composable
private fun BoostRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logState.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastOutcome by viewModel.lastOutcome.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf(HomeDestination.DASHBOARD) }
    var showingAddReading by remember { mutableStateOf(false) }
    var showingAddEvent by remember { mutableStateOf(false) }

    // "Now" is held in state and ticked, so relative times ("12 min ago") do not freeze at
    // whatever moment the screen happened to be composed.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }

    when (val current = state) {
        // Storage has not answered yet. Rendering setup here would flash the wrong screen
        // at every returning user.
        AppState.Loading -> Box(
            modifier = Modifier.fillMaxSize().background(BoostTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = BoostTheme.colors.primary)
        }

        AppState.NeedsOnboarding -> OnboardingScreen(onFinished = {
            destination = HomeDestination.DASHBOARD
        })

        is AppState.Ready -> {
            // Back returns to the dashboard rather than closing the app from a submenu
            // destination — leaving the app should take a deliberate second press.
            BackHandler(enabled = destination != HomeDestination.DASHBOARD) {
                destination = HomeDestination.DASHBOARD
            }

            HomeShell(destination = destination, onSelect = { destination = it }) { modifier ->
                when (destination) {
                    HomeDestination.DASHBOARD -> DashboardScreen(
                        profile = current.profile,
                        settings = current.settings,
                        logs = logs,
                        nowMillis = nowMillis,
                        onOpenProfile = { destination = HomeDestination.SETTINGS },
                        onAddReading = { showingAddReading = true },
                        onAddEvent = { showingAddEvent = true },
                        onOpenBolusCalculator = { destination = HomeDestination.BOLUS_CALCULATOR },
                        syncing = syncing,
                        onSyncNow = { viewModel.syncNow(current.settings) },
                        modifier = modifier,
                    )

                    HomeDestination.BLOOD_GLUCOSE -> GlucoseLogScreen(
                        logs = logs,
                        unit = current.profile.bgUnit,
                        lowMgdl = current.settings.lowGlucose,
                        highMgdl = current.settings.highGlucose,
                        nowMillis = nowMillis,
                        onAddReading = viewModel::addReading,
                        onDeleteReading = viewModel::deleteReading,
                        modifier = modifier,
                    )

                    HomeDestination.EVENT_LOG -> EventLogScreen(
                        logs = logs,
                        nowMillis = nowMillis,
                        onAddEvent = viewModel::addTreatment,
                        onDeleteTreatment = viewModel::deleteTreatment,
                        modifier = modifier,
                    )

                    HomeDestination.BOLUS_CALCULATOR -> BolusCalculatorScreen(
                        logs = logs,
                        therapy = logs.therapy,
                        unit = current.profile.bgUnit,
                        lowMgdl = current.settings.lowGlucose,
                        highMgdl = current.settings.highGlucose,
                        nowMillis = nowMillis,
                        modifier = modifier,
                    )

                    HomeDestination.THERAPY_PROFILE -> TherapyProfileScreen(
                        therapy = logs.therapy,
                        unit = current.profile.bgUnit,
                        onSave = viewModel::saveTherapy,
                        modifier = modifier,
                    )

                    HomeDestination.SETTINGS -> ProfileScreen(
                        profile = current.profile,
                        settings = current.settings,
                        onSave = viewModel::save,
                        onBack = { destination = HomeDestination.DASHBOARD },
                        modifier = modifier,
                    )

                    HomeDestination.DATA_SOURCE -> DataSourceScreen(
                        settings = current.settings,
                        currentToken = viewModel.nightscoutToken(),
                        syncing = syncing,
                        lastOutcome = lastOutcome,
                        nowMillis = nowMillis,
                        onTest = viewModel::testNightscout,
                        onSave = { updated, token ->
                            viewModel.saveNightscoutToken(token)
                            viewModel.saveSettings(updated)
                            viewModel.syncNow(updated)
                        },
                        onSyncNow = { viewModel.syncNow(current.settings) },
                        modifier = modifier,
                    )

                    HomeDestination.ABOUT -> AboutScreen(modifier = modifier)
                }
            }

            // The quick actions on the dashboard open the same sheets the logs use, so a
            // reading entered from either place lands identically.
            if (showingAddReading) {
                AddReadingDialog(
                    unit = current.profile.bgUnit,
                    nowMillis = nowMillis,
                    onDismiss = { showingAddReading = false },
                    onSave = viewModel::addReading,
                )
            }

            if (showingAddEvent) {
                AddEventDialog(
                    nowMillis = nowMillis,
                    onDismiss = { showingAddEvent = false },
                    onSave = viewModel::addTreatment,
                )
            }
        }
    }
}
