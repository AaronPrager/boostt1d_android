package com.boostt1d.android.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors

    ScreenScaffold(title = "About", subtitle = "Version and legal", modifier = modifier) {
        item {
            BoostCard {
                Text("BoostT1D for Android", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text("Version 0.1 — manual logging", fontSize = 14.sp, color = colors.textSecondary)
            }
        }

        item {
            BoostCard {
                Text(
                    "Not a medical device",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    "BoostT1D is an educational and informational tool. It does not diagnose, " +
                        "treat or prescribe, and it does not change anything on your pump. " +
                        "Every number it shows is for awareness and organization — verify " +
                        "anything you plan to act on with your care team. In an emergency, " +
                        "call your local emergency number.",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                )
            }
        }

        item {
            BoostCard {
                Text(
                    "What this build does",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    "Everything you log stays on this device. Nothing is uploaded, and no " +
                        "CGM is connected — glucose, carbs and doses are whatever you enter " +
                        "by hand. Connecting Nightscout, Dexcom or FreeStyle Libre, food " +
                        "photos and the insight reports are still being built.",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                )
            }
        }

        item {
            Text(
                "Readings older than 14 days are removed automatically.",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = BoostSpacing.xs),
            )
        }
    }
}
