package com.boostt1d.android.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * How to get a FreeStyle Libre sensor streaming into BoostT1D. Ported from the iOS
 * LibreSetupHelpView.
 *
 * Abbott has no direct read API for Libre sensors, so BoostT1D reads through LibreLinkUp,
 * the same follower service Abbott's own app uses. The setup is a sharing invitation rather
 * than an API key, and people reliably get stuck on it without the steps written out.
 */
@Composable
fun LibreSetupHelpDialog(onDismiss: () -> Unit) {
    val colors = BoostTheme.colors
    val steps = listOf(
        "Make sure the sensor streams to LibreView" to
            "In the FreeStyle Libre 3 (or Libre 3 Plus) app, sign in to your LibreView " +
            "account. Readings have to reach LibreView before anything else can read them.",
        "Invite a LibreLinkUp connection" to
            "In the FreeStyle Libre app, open Connected Apps, then LibreLinkUp, and invite an " +
            "email address. You can invite your own second email if you are setting this up " +
            "for yourself.",
        "Accept the invitation" to
            "Install Abbott's LibreLinkUp app, sign in with the invited email and accept the " +
            "invitation. Confirm you can see live glucose there: BoostT1D reads exactly what " +
            "that app sees.",
        "Enter those credentials here" to
            "Use the LibreLinkUp email and password, not the FreeStyle Libre app login. Your " +
            "password is stored in EncryptedSharedPreferences, never in the plain settings file.",
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(BoostSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Connect FreeStyle Libre",
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Done", color = colors.primary) }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(BoostSpacing.md),
                ) {
                    Text(
                        "BoostT1D reads Libre data through LibreLinkUp, Abbott's sharing " +
                            "service. Set up sharing once and readings arrive automatically.",
                        fontSize = 15.sp, color = colors.textSecondary,
                    )

                    steps.forEachIndexed { index, (title, detail) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm)) {
                            Box(
                                modifier = Modifier.size(26.dp).background(colors.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs)) {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                                Text(detail, fontSize = 13.sp, color = colors.textSecondary)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.primary.copy(alpha = 0.08f), RoundedCornerShape(BoostRadius.md))
                            .padding(BoostSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs)) {
                            Text("What to expect", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text(
                                "LibreLinkUp returns roughly the last twelve hours per sync, so " +
                                    "3-day and 7-day charts fill in as you keep the app open over " +
                                    "the following days. Glucose only: meals, boluses and active " +
                                    "insulin need Nightscout or manual entry.",
                                fontSize = 13.sp, color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
