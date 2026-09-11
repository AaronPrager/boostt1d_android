package com.boostt1d.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import com.boostt1d.android.ui.BoostHeartMark
import com.boostt1d.android.ui.BoostSpacing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import com.boostt1d.android.home.BoostTipId
import com.boostt1d.android.home.BoostTips
import com.boostt1d.android.home.HelpScreen
import com.boostt1d.android.home.HomeDestination
import com.boostt1d.android.home.HomeShell
import com.boostt1d.android.home.ReviewRequestService
import com.boostt1d.android.home.SupportScreen
import com.boostt1d.android.home.TutorialScreen
import com.boostt1d.android.food.FoodHost
import com.boostt1d.android.food.FoodStart
import com.boostt1d.android.sync.InitialDataDownloadScreen
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.insights.InsightsScreen
import com.boostt1d.android.doctor.DoctorVisitScreen
import com.boostt1d.android.doctor.DoctorVisitShare
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
    val onBoard by viewModel.onBoard.collectAsStateWithLifecycle()
    val report by viewModel.reportSnapshot.collectAsStateWithLifecycle()
    val doctorReport by viewModel.doctorReport.collectAsStateWithLifecycle()
    val doctorLoading by viewModel.doctorLoading.collectAsStateWithLifecycle()
    val doctorCoverage by viewModel.doctorCoverage.collectAsStateWithLifecycle()
    val doctorEntries by viewModel.doctorEntries.collectAsStateWithLifecycle()
    val doctorQuestions by viewModel.doctorQuestions.collectAsStateWithLifecycle()
    val pdfExporting by viewModel.pdfExporting.collectAsStateWithLifecycle()
    val pdfReady by viewModel.pdfReady.collectAsStateWithLifecycle()
    val pdfError by viewModel.pdfError.collectAsStateWithLifecycle()
    val reportLoading by viewModel.reportLoading.collectAsStateWithLifecycle()
    val advancedDetail by viewModel.advancedTherapyDetail.collectAsStateWithLifecycle()
    val aiReviewLoading by viewModel.aiReviewLoading.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf(HomeDestination.DASHBOARD) }

    // Help and Support link out to the web. The browser is the only place a donation or a
    // Nightscout guide belongs, so the app hands the URL over rather than embedding one.
    val context = androidx.compose.ui.platform.LocalContext.current
    val openUrl: (String) -> Unit = remember(context) {
        { url ->
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
        }
    }
    val tips = remember(context) { BoostTips(context) }
    var activeTip by remember { mutableStateOf<BoostTipId?>(null) }
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

    // A pending full download owns the screen: the dashboard is deliberately not mounted,
    // since its own sync would race the one this screen is running.
    val downloadReason by viewModel.initialDownloadReason.collectAsStateWithLifecycle()
    (state as? AppState.Ready)?.let { ready ->
        if (downloadReason != null && ready.settings.connection != GlucoseConnectionOption.MANUAL) {
            // Keyed on the reason alone: the sync itself writes lastSyncMillis into settings,
            // and keying on those would restart the download every time it finished a pass.
            val download = remember(downloadReason) { viewModel.newInitialDownload(ready.settings) }
            InitialDataDownloadScreen(download, ready.settings.connection, onFinished = { viewModel.finishInitialDownload() })
            return
        }
    }

    when (val current = state) {
        // Storage has not answered yet. Rendering setup here would flash the wrong screen
        // at every returning user.
        AppState.Loading -> Box(
            modifier = Modifier.fillMaxSize().background(BoostTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            // The mark, not a bare spinner: this is the first thing anyone sees, and the
            // system splash that precedes it shows the same heart.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.lg),
            ) {
                BoostHeartMark(width = 96.dp)
                CircularProgressIndicator(color = BoostTheme.colors.primary)
            }
        }

        AppState.NeedsOnboarding -> OnboardingScreen(onFinished = {
            destination = HomeDestination.DASHBOARD
        })

        is AppState.Ready -> {
            // One qualified open per process, counted once the app is actually usable rather
            // than at launch. The service decides whether that open is due a rating prompt.
            LaunchedEffect(Unit) {
                (context as? android.app.Activity)?.let { activity ->
                    ReviewRequestService(context).registerQualifiedOpen(activity, appVersion(context))
                }
            }

            // At most one coaching tip per day, and only on the dashboard: a tip that follows
            // the user into every screen stops being a tip.
            LaunchedEffect(Unit) {
                tips.nextTip(System.currentTimeMillis())?.let { next ->
                    tips.recordShown(next, System.currentTimeMillis())
                    activeTip = next
                }
            }

            // Back returns to the dashboard rather than closing the app from a submenu
            // destination — leaving the app should take a deliberate second press.
            BackHandler(enabled = destination != HomeDestination.DASHBOARD) {
                destination = HomeDestination.DASHBOARD
            }

            HomeShell(
                destination = destination,
                onSelect = { destination = it },
                tip = activeTip.takeIf { destination == HomeDestination.DASHBOARD },
                onDismissTip = { tips.dismiss(it); activeTip = null },
            ) { modifier ->
                when (destination) {
                    HomeDestination.DASHBOARD -> DashboardScreen(
                        profile = current.profile,
                        settings = current.settings,
                        logs = logs,
                        onBoard = onBoard,
                        nowMillis = nowMillis,
                        syncing = syncing,
                        onRefresh = { viewModel.syncNow(current.settings) },
                        onAddReading = { showingAddReading = true },
                        onOpenHistory = { destination = HomeDestination.BLOOD_GLUCOSE },
                        onOpenDataSource = { destination = HomeDestination.DATA_SOURCE },
                        modifier = modifier,
                    )

                    HomeDestination.FOOD_LOG, HomeDestination.SNAP_MEAL -> FoodHost(
                        start = if (destination == HomeDestination.SNAP_MEAL) FoodStart.SNAP else FoodStart.LOG,
                        foodLog = viewModel.foodLog,
                        backend = viewModel.backend,
                        usage = viewModel.usage,
                        logs = logs,
                        onBoard = onBoard,
                        connection = current.settings.connection,
                        unit = current.profile.bgUnit,
                        lowMgdl = current.settings.lowGlucose,
                        highMgdl = current.settings.highGlucose,
                        nowMillis = nowMillis,
                        onImportTreatments = viewModel::importCarbsIntoFoodLog,
                        onOpenTherapyProfile = { destination = HomeDestination.THERAPY_PROFILE },
                        modifier = modifier,
                    )

                    HomeDestination.INSIGHTS -> InsightsScreen(
                        snapshot = report,
                        loading = reportLoading,
                        aiReviewLoading = aiReviewLoading,
                        unit = current.profile.bgUnit,
                        showsAdvancedDetail = advancedDetail,
                        nowMillis = nowMillis,
                        onRefresh = { viewModel.refreshReport(current.settings, current.profile) },
                        modifier = modifier,
                    )

                    HomeDestination.DOCTOR_VISIT -> {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        // A finished PDF opens the system share sheet once, then is consumed.
                        LaunchedEffect(pdfReady) {
                            val file = pdfReady ?: return@LaunchedEffect
                            viewModel.consumePdf()
                            DoctorVisitShare.share(context, file)
                        }
                        pdfError?.let { message ->
                            AlertDialog(
                                onDismissRequest = { viewModel.dismissPdfError() },
                                confirmButton = { TextButton(onClick = { viewModel.dismissPdfError() }) { Text("OK") } },
                                title = { Text("Couldn’t create PDF") },
                                text = { Text(message) },
                            )
                        }
                        DoctorVisitScreen(
                            report = doctorReport,
                            loading = doctorLoading,
                            patternCoverageLabel = doctorCoverage,
                            agpEntries = doctorEntries,
                            questions = doctorQuestions,
                            exporting = pdfExporting,
                            unit = current.profile.bgUnit,
                            lowMgdl = current.settings.lowGlucose,
                            highMgdl = current.settings.highGlucose,
                            nowMillis = nowMillis,
                            onPeriodChange = { viewModel.loadDoctorReport(it, current.settings, current.profile) },
                            onRefresh = { viewModel.reloadDoctorReport(current.settings, current.profile) },
                            onQuestionsChange = { viewModel.saveDoctorQuestions(it) },
                            onExport = { viewModel.exportDoctorVisitPdf(current.settings, current.profile) },
                            modifier = modifier,
                        )
                    }
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
                        connection = current.settings.connection,
                        onOpenDataSource = { destination = HomeDestination.DATA_SOURCE },
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
                        onDeleteEverything = {
                            viewModel.deleteEverything()
                            // Setup runs again after a wipe, so the tour and its tips should
                            // be waiting for whoever sets the app up next.
                            tips.resetAll()
                            destination = HomeDestination.DASHBOARD
                        },
                        advancedTherapyDetail = advancedDetail,
                        onToggleAdvancedTherapyDetail = viewModel::setAdvancedTherapyDetail,
                        modifier = modifier,
                    )

                    HomeDestination.DATA_SOURCE -> DataSourceScreen(
                        settings = current.settings,
                        currentToken = viewModel.nightscoutToken(),
                        currentDexcomPassword = viewModel.dexcomPassword(),
                        currentLibrePassword = viewModel.librePassword(),
                        syncing = syncing,
                        lastOutcome = lastOutcome,
                        nowMillis = nowMillis,
                        onTest = viewModel::testNightscout,
                        onTestDexcom = viewModel::testDexcom,
                        onTestLibre = viewModel::testLibre,
                        onSave = { updated, token, dexcomPassword, librePassword ->
                            viewModel.saveCredentials(token, dexcomPassword, librePassword)
                            viewModel.saveSettings(updated)
                            viewModel.syncNow(updated)
                        },
                        onDiscardPreviousConnection = viewModel::discardPreviousConnection,
                        onSyncNow = { viewModel.syncNow(current.settings) },
                        modifier = modifier,
                    )

                    HomeDestination.HOW_IT_WORKS -> TutorialScreen(
                        onFinished = {
                            tips.markTutorialCompleted()
                            destination = HomeDestination.DASHBOARD
                        },
                        modifier = modifier,
                    )

                    HomeDestination.HELP -> HelpScreen(onOpenUrl = openUrl, modifier = modifier)

                    HomeDestination.SUPPORT -> SupportScreen(onOpenUrl = openUrl, modifier = modifier)

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

/** The versionName Play shows, or a placeholder when the package cannot be read. */
private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "unknown"
