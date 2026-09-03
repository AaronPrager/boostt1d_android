package com.boostt1d.android.sync

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt

/**
 * A full sync, given its own screen so setup or a connection change ends on something to
 * watch rather than an empty dashboard filling itself in. Ported from InitialDataDownloadView.
 */
@Composable
fun InitialDataDownloadScreen(
    download: InitialDataDownload,
    connection: GlucoseConnectionOption,
    /** Called once the user is ready for the dashboard — automatically when everything succeeded, on their tap when not. */
    onFinished: () -> Unit,
) {
    val colors = BoostTheme.colors
    val scope = rememberCoroutineScope()
    val steps by download.steps.collectAsState()
    val isFinished by download.isFinished.collectAsState()
    var canSkip by remember { mutableStateOf(false) }

    LaunchedEffect(download) {
        download.run()
        if (download.hasFailures) return@LaunchedEffect
        // A beat on the completed state, long enough for the bar to be seen reaching the end.
        delay(1_100)
        onFinished()
    }
    LaunchedEffect(download) {
        // A source that never answers would otherwise trap the user here; the escape
        // appears only once waiting looks like a problem.
        delay(12_000)
        canSkip = true
    }

    val hasFailures = download.hasFailures
    val progress = download.progress.toFloat()
    val animated by animateFloatAsState(progress, tween(350), label = "progress")
    val tint = when { hasFailures -> colors.high; isFinished -> colors.inRange; else -> colors.primary }

    Box(modifier = Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.lg),
        ) {
            Hero(animated, tint, isFinished, hasFailures, connection)

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                val title = when {
                    isFinished && hasFailures -> "Almost there"
                    isFinished -> "You're all set"
                    download.reason == InitialDataDownload.Reason.CONNECTION_CHANGE -> "Updating your data"
                    else -> "Getting your data"
                }
                val subtitle = when {
                    isFinished && hasFailures -> "Some of it didn't come through. You can try again, or carry on — the app keeps syncing in the background."
                    isFinished -> "Everything is on your phone and ready to use."
                    download.reason == InitialDataDownload.Reason.CONNECTION_CHANGE -> "Your connection changed, so we're replacing imported data with everything available from ${download.sourceName}."
                    else -> "We're downloading everything ${download.sourceName} has."
                }
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, textAlign = TextAlign.Center)
                Text(subtitle, fontSize = 16.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(progress = { animated }, color = colors.primary, trackColor = colors.primary.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(if (isFinished) "Done" else "Please wait…", fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text("${(animated * 100).roundToInt()}%", fontSize = 12.sp, color = colors.textSecondary)
                }
            }

            BoostCard {
                Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
                    steps.forEach { step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm), verticalAlignment = Alignment.Top) {
                            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                when (step.status) {
                                    InitialDataDownload.Status.Waiting -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                                    InitialDataDownload.Status.Running -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                                    is InitialDataDownload.Status.Done -> Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = colors.inRange, modifier = Modifier.size(20.dp))
                                    is InitialDataDownload.Status.Failed -> Icon(Icons.Filled.Error, contentDescription = "Failed", tint = colors.high, modifier = Modifier.size(20.dp))
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(step.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                                val (detail, color) = when (val status = step.status) {
                                    is InitialDataDownload.Status.Done -> status.summary to colors.textSecondary
                                    is InitialDataDownload.Status.Failed -> status.message to colors.high
                                    else -> step.subtitle to colors.textSecondary
                                }
                                Text(detail, fontSize = 12.sp, color = color)
                            }
                        }
                    }
                }
            }

            when {
                isFinished && hasFailures -> Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { scope.launch { download.retry() } }, modifier = Modifier.fillMaxWidth()) { Text("Try again", color = colors.primary) }
                    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = colors.primary)) { Text("Continue anyway") }
                }
                isFinished -> Unit
                canSkip -> OutlinedButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Continue without waiting", color = colors.primary) }
                else -> Text("Keep the app open until this finishes.", fontSize = 12.sp, color = colors.textTertiary)
            }
        }
    }
}

/** Pulsing rings around a ring that fills with each settled step, and a badge for the source, then the outcome. */
@Composable
private fun Hero(progress: Float, tint: Color, isFinished: Boolean, hasFailures: Boolean, connection: GlucoseConnectionOption) {
    val colors = BoostTheme.colors
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1_700), RepeatMode.Reverse), label = "pulse")
    Box(modifier = Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(168.dp).scale(pulse).background(colors.primary.copy(alpha = 0.08f), CircleShape))
        Box(modifier = Modifier.size(126.dp).scale(2f - pulse).background(colors.primary.copy(alpha = 0.14f), CircleShape))
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(108.dp), color = tint, trackColor = tint.copy(alpha = 0.18f), strokeWidth = 10.dp)
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).size(32.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
            val icon = when {
                isFinished && hasFailures -> Icons.Filled.PriorityHigh
                isFinished -> Icons.Filled.Check
                connection == GlucoseConnectionOption.NIGHTSCOUT -> Icons.Filled.Cloud
                connection == GlucoseConnectionOption.MANUAL -> Icons.Filled.Edit
                else -> Icons.Filled.Sensors
            }
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
