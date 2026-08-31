package com.boostt1d.android.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.ui.BoostFieldLabel
import com.boostt1d.android.ui.BoostSegmented
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTextField
import com.boostt1d.android.ui.BoostTheme

/**
 * Picking a data source and entering its credentials.
 *
 * Shared by setup and Profile so the two cannot drift — the rules about what a token
 * gets you are the same whether you are setting it up or changing it later.
 */
@Composable
fun ConnectionFields(
    connection: GlucoseConnectionOption,
    nightscoutUrl: String,
    nightscoutToken: String,
    dexcomUsername: String,
    dexcomPassword: String,
    dexcomRegion: DexcomRegion,
    onConnectionChange: (GlucoseConnectionOption) -> Unit,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onDexcomUsernameChange: (String) -> Unit,
    onDexcomPasswordChange: (String) -> Unit,
    onDexcomRegionChange: (DexcomRegion) -> Unit,
    onTest: () -> Unit,
    testing: Boolean,
    testResult: String?,
    testSucceeded: Boolean?,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BoostSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
            BoostFieldLabel("Data source")
            GlucoseConnectionOption.selectableInThisBuild.forEach { option ->
                SourceOption(
                    option = option,
                    selected = connection == option,
                    onSelect = { onConnectionChange(option) },
                )
            }
        }

        when (connection) {
            GlucoseConnectionOption.NIGHTSCOUT -> {
                Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    BoostFieldLabel("Site address")
                    BoostTextField(
                        value = nightscoutUrl,
                        onValueChange = onUrlChange,
                        placeholder = "https://yourname.up.railway.app",
                        keyboardType = KeyboardType.Uri,
                    )
                    Text(
                        "The web address of your own Nightscout site. BoostT1D reads from it " +
                            "and never writes to it.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    BoostFieldLabel("Access token")
                    BoostTextField(
                        value = nightscoutToken,
                        onValueChange = onTokenChange,
                        placeholder = "From your Nightscout Admin Tools",
                        isSecret = true,
                    )
                    Text(
                        "Create a read-only token in Nightscout under Admin Tools. Without one " +
                            "you get readings but not events or insulin doses. The token is " +
                            "stored encrypted on this device and never sent anywhere else.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }

                TestButton(onTest, testing, colors, enabled = nightscoutUrl.isNotBlank())
                TestResult(testResult, testSucceeded, colors)
            }

            GlucoseConnectionOption.DEXCOM -> {
                Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    BoostFieldLabel("Region")
                    BoostSegmented(
                        options = DexcomRegion.entries.toList(),
                        selected = dexcomRegion,
                        optionLabel = { it.displayName },
                        onSelect = onDexcomRegionChange,
                    )
                    Text(
                        "Dexcom Share accounts are regional and the servers do not talk to " +
                            "each other, so the wrong one here looks exactly like a wrong password.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    BoostFieldLabel("Dexcom username")
                    BoostTextField(
                        value = dexcomUsername,
                        onValueChange = onDexcomUsernameChange,
                        placeholder = "The account you sign in to Dexcom with",
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    BoostFieldLabel("Dexcom password")
                    BoostTextField(
                        value = dexcomPassword,
                        onValueChange = onDexcomPasswordChange,
                        placeholder = "Your Dexcom password",
                        isSecret = true,
                    )
                    Text(
                        "Share must be switched on in the Dexcom app, with at least one " +
                            "follower. The password is stored encrypted on this device and is " +
                            "sent only to Dexcom.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.high.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                        .padding(BoostSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
                ) {
                    Text(
                        "Share keeps about a day of history",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Readings older than that are gone for good if the app was not there " +
                            "to catch them, so leave background sync and notifications enabled.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }

                TestButton(onTest, testing, colors, enabled = dexcomUsername.isNotBlank())
                TestResult(testResult, testSucceeded, colors)
            }

            GlucoseConnectionOption.MANUAL -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.primary.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
                        .padding(BoostSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
                ) {
                    Text(
                        "You'll enter readings yourself",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Nothing is downloaded. Glucose, carbs and doses are whatever you log, " +
                            "and everything stays on this device.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun SourceOption(
    option: GlucoseConnectionOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = BoostTheme.colors
    val icon: ImageVector = when (option) {
        GlucoseConnectionOption.NIGHTSCOUT -> Icons.Filled.Cloud
        GlucoseConnectionOption.DEXCOM -> Icons.Filled.Sensors
        else -> Icons.Filled.TouchApp
    }
    val detail = when (option) {
        GlucoseConnectionOption.NIGHTSCOUT -> "Readings, events and insulin doses"
        GlucoseConnectionOption.DEXCOM -> "Readings only, about a day of history"
        else -> "You log everything yourself"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.primary.copy(alpha = 0.08f) else colors.surfaceMuted,
                RoundedCornerShape(BoostRadius.md),
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.border,
                shape = RoundedCornerShape(BoostRadius.md),
            )
            .clickable(onClick = onSelect)
            .padding(BoostSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                option.displayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(detail, fontSize = 12.sp, color = colors.textSecondary)
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TestButton(
    onTest: () -> Unit,
    testing: Boolean,
    colors: com.boostt1d.android.ui.BoostColors,
    enabled: Boolean,
) {
    OutlinedButton(
        onClick = onTest,
        enabled = !testing && enabled,
        shape = RoundedCornerShape(BoostRadius.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (testing) {
            CircularProgressIndicator(
                color = colors.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Text("Testing…", color = colors.textPrimary, modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Test connection", color = colors.textPrimary)
        }
    }
}

@Composable
private fun TestResult(
    message: String?,
    succeeded: Boolean?,
    colors: com.boostt1d.android.ui.BoostColors,
) {
    // Success and partial success both read as usable; only a real failure is coloured as
    // a problem, because "readings but not events" is a working connection, not a broken one.
    val text = message ?: return
    val tint = when (succeeded) {
        true -> colors.inRange
        false -> colors.low
        null -> colors.textSecondary
    }
    Text(
        text,
        fontSize = 13.sp,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(BoostRadius.md))
            .padding(BoostSpacing.sm),
    )
}
